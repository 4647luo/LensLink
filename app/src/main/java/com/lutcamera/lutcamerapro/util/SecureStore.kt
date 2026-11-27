package com.lutcamera.lutcamerapro.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本机敏感字符串的加密门面。
 *
 * 只用于相机热点口令这类「丢了不影响功能、泄露了很麻烦」的小字段：
 * 密钥由 Android Keystore 生成并托管（不可导出），密文以 `enc1:` 前缀存进 SharedPreferences。
 *
 * 取舍：
 * - 不引入 Jetpack Security，只加一个 ~100 行的门面，避免多一条依赖；
 * - 不绑用户认证：后台自动重连时不能弹指纹，绑锁屏会把无人值守重连弄坏；
 * - Keystore 异常时不阻塞功能：加密返回 null，调用方退回明文并记日志。
 */
object SecureStore {

    private const val KEY_ALIAS = "lenslink-secure-prefs-v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val PREFIX = "enc1:"
    private const val TAG_BYTES = 16
    private const val IV_BYTES = 12

    /** 是否为本工具写出的密文；旧版本留下的明文没有前缀。 */
    fun isEncrypted(value: String?): Boolean = value != null && value.startsWith(PREFIX)

    fun encrypt(plain: String): String? = runCatching {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        "$PREFIX${Base64.encodeToString(iv, Base64.NO_WRAP)}:${
            Base64.encodeToString(ct, Base64.NO_WRAP)
        }"
    }.getOrNull()

    /** 解密失败返回 null；明文入参同样返回 null（调用方应先判断 [isEncrypted]）。 */
    fun decrypt(stored: String?): String? = runCatching {
        if (stored == null || !isEncrypted(stored)) return null
        val body = stored.removePrefix(PREFIX)
        val sep = body.indexOf(':')
        if (sep <= 0) return null
        val iv = Base64.decode(body.substring(0, sep), Base64.NO_WRAP)
        val ct = Base64.decode(body.substring(sep + 1), Base64.NO_WRAP)
        if (iv.size != IV_BYTES || ct.size < TAG_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
