package com.lutcamera.lutcamerapro.ui.douk

import android.graphics.Bitmap
import android.os.Build
import com.lutcamera.lutcamerapro.log.DoukLog
import java.io.File
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Ultra HDR 导出的统一入口：增益图算法在 Kotlin 里算（pyultrahdr 的逆色调映射曲线），
 * 容器（base JPEG + gain map JPEG + ISO 21496-1 / MPF 元数据）交给 Google libultrahdr 写。
 *
 * 换掉旧实现的理由：以前用 `Bitmap.setGainmap` + `compress` 让框架代写容器，增益只存在
 * 属性里、尺寸/参数一旦不自洽（比如 gain map 被压成方图）系统就直接当普通 JPEG 显示；
 * libultrahdr 是这套格式的参考实现，写出来的文件 Chrome / Android 14+ / iOS 17.4 都认。
 */
object UltraHdrCodec {

    /** 增益图与元数据（单位：ISO 21496-1 的线性 content boost，不是 log2）。 */
    class Gain(
        val gray: ByteArray,
        val width: Int,
        val height: Int,
        val minBoost: Float,
        val maxBoost: Float,
        val gamma: Float,
        val epsSdr: Float,
        val epsHdr: Float,
        val capMin: Float,
        val capMax: Float,
    )

    private var libReady = false

    init {
        libReady = runCatching {
            System.loadLibrary("lianjingraw")
            true
        }.getOrElse { false }
    }

    val available: Boolean get() = libReady

    private external fun nativeEncode(
        baseJpeg: ByteArray,
        gainGray: ByteArray,
        gw: Int,
        gh: Int,
        minBoost: Float,
        maxBoost: Float,
        gamma: Float,
        epsSdr: Float,
        epsHdr: Float,
        capMin: Float,
        capMax: Float,
        gainQuality: Int,
        outPath: String,
    ): Boolean

    private external fun nativeProbe(path: String): Boolean

    /** 写入 Ultra HDR 文件；baseJpeg 必须是未再压缩的 SDR 底图字节。 */
    fun encode(baseJpeg: ByteArray, gain: Gain, target: File, gainQuality: Int = 85): Boolean {
        if (!libReady) return false
        if (Build.VERSION.SDK_INT < 34) return false
        return runCatching {
            val ok = nativeEncode(
                baseJpeg, gain.gray, gain.width, gain.height,
                gain.minBoost, gain.maxBoost, gain.gamma, gain.epsSdr, gain.epsHdr,
                gain.capMin, gain.capMax, gainQuality, target.absolutePath
            )
            if (ok) {
                val probed = runCatching { nativeProbe(target.absolutePath) }.getOrDefault(false)
                DoukLog.i(
                    "UltraHdr", "ENCODED",
                    "size" to target.length().toString(),
                    "gain" to "${gain.width}x${gain.height}",
                    "boost" to "1..${"%.2f".format(gain.maxBoost)}",
                    "uhdr" to probed.toString()
                )
                if (!probed) {
                    // 容器没写成 Ultra HDR：至少保证底图能打开，不让用户拿到坏文件。
                    target.writeBytes(baseJpeg)
                    false
                } else true
            } else false
        }.getOrElse { e ->
            DoukLog.w("UltraHdr", "ENCODE_ERR", "msg" to (e.message ?: ""))
            false
        }
    }

    /** 把 Bitmap 里的单通道增益（ALPHA_8 的 alpha 或 RGB 均值）抽成 8bit 字节。 */
    fun grayOf(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h)
        val isAlpha = bmp.config == Bitmap.Config.ALPHA_8
        for (i in px.indices) {
            val p = px[i]
            val v = if (isAlpha) {
                (p ushr 24) and 0xFF
            } else {
                (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
            }
            out[i] = v.toByte()
        }
        return out
    }

    /**
     * SDR → HDR 的逆色调映射，按 pyultrahdr 的曲线：
     *   boost(L) = shadow + (peak - shadow) * L^gamma   （L 为线性亮度，0..1）
     * 增益就是 log2(boost)，直接落到 ISO 的 gain map 里，不再拿直方图分位数猜起点。
     */
    fun syntheticGain(
        sdr: Bitmap,
        strength: Float,
        highlight01: Float,
        glow: Float,
        gainMaxSide: Int = 2048,
    ): Gain? {
        if (sdr.isRecycled || sdr.width < 4 || sdr.height < 4) return null
        val scale = minOf(1f, gainMaxSide.toFloat() / maxOf(sdr.width, sdr.height).toFloat())
        val gw = (sdr.width * scale).toInt().coerceAtLeast(1)
        val gh = (sdr.height * scale).toInt().coerceAtLeast(1)
        val small = if (gw == sdr.width && gh == sdr.height) sdr else Bitmap.createScaledBitmap(sdr, gw, gh, true)
        val px = IntArray(gw * gh)
        small.getPixels(px, 0, gw, 0, 0, gw, gh)
        if (small !== sdr) small.recycle()

        val s = strength.coerceIn(0.02f, 1f)
        val peakBoost = (1f + 3.9f * s).coerceIn(1.02f, 5f)
        val shadowBoost = 1f + 0.15f * s
        val hiGamma = (0.75f + 0.45f * highlight01.coerceIn(0f, 1f)).coerceIn(0.6f, 1.5f)
        val logMax = log2(peakBoost)

        val grayF = FloatArray(gw * gh)
        for (i in px.indices) {
            val p = px[i]
            val r = srgbToLinear(((p shr 16) and 0xFF) / 255f)
            val g = srgbToLinear(((p shr 8) and 0xFF) / 255f)
            val b = srgbToLinear((p and 0xFF) / 255f)
            val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(1e-4f, 1f)
            val boost = shadowBoost + (peakBoost - shadowBoost) * lum.pow(hiGamma)
            // 归一化到 0..1（ISO 增益 = min + (max-min) * v^gamma，gamma=1 时是线性的）
            grayF[i] = (log2(boost.coerceAtLeast(1f)) / logMax).coerceIn(0f, 1f)
        }

        // 柔光：把增益按半径模糊后与原值做软混合，作用等同把高光「晕开」而不是只抬亮边缘
        if (glow > 0.02f) {
            val radius = (1 + glow * (gw.coerceAtLeast(gh) / 40f)).roundToInt().coerceIn(1, 12)
            val blurred = boxBlur(grayF, gw, gh, radius)
            val amount = glow.coerceIn(0f, 1f)
            for (i in grayF.indices) {
                grayF[i] = (grayF[i] * (1f - amount) + blurred[i] * amount * 0.85f).coerceIn(0f, 1f)
            }
        }

        val gray = ByteArray(grayF.size)
        for (i in grayF.indices) {
            gray[i] = (grayF[i] * 255f + 0.5f).roundToInt().toByte()
        }
        return Gain(
            gray = gray,
            width = gw,
            height = gh,
            minBoost = 1f,
            maxBoost = peakBoost,
            gamma = 1f,
            epsSdr = 0f,
            epsHdr = 0f,
            capMin = 1f,
            capMax = peakBoost
        )
    }

    private fun srgbToLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    /** 分离式盒式模糊，够快也够「柔光」用。 */
    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val r = radius.coerceAtLeast(1)
        val norm = 1f / (2 * r + 1)
        for (y in 0 until h) {
            var acc = 0f
            for (x in -r..r) acc += src[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = acc * norm
                acc += src[y * w + (x + r + 1).coerceIn(0, w - 1)]
                acc -= src[y * w + (x - r).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (y in -r..r) acc += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc * norm
                acc += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                acc -= tmp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }
}
