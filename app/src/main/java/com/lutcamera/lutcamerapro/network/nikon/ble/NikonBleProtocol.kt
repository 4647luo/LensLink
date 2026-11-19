package com.lutcamera.lutcamerapro.network.nikon.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * 尼康「智能设备」（蓝牙）协议常量与载荷编码。
 *
 * 协议要点来自公开逆向成果：gkoh/furble（MIT，最早逆向该协议的实现）与
 * hurui200320/nsg、Octopustank/HarmoNiLink 的公开协议分析。
 * 本文件是按协议事实独立编写的实现（未复制上述项目的源代码），以便本工程
 * 继续保持 Apache-2.0 授权。
 *
 * 传输层：BLE GATT，服务 0xDE00，基础 UUID 0000xxxx-3dd4-4255-8d62-6dc7b9bd5561
 */
object NikonBleUuids {
    /** 主服务 0xDE00 —— 扫描时也用它识别尼康相机。 */
    val SERVICE: UUID = UUID.fromString("0000de00-3dd4-4255-8d62-6dc7b9bd5561")

    /** PAIR：配对握手，indication。 */
    val PAIR: UUID = UUID.fromString("00002000-3dd4-4255-8d62-6dc7b9bd5561")

    /** ID：写入控制器名称，32 字节 ASCII。 */
    val ID: UUID = UUID.fromString("00002002-3dd4-4255-8d62-6dc7b9bd5561")

    /** GEO：写入 41 字节定位载荷。 */
    val GEO: UUID = UUID.fromString("00002007-3dd4-4255-8d62-6dc7b9bd5561")

    /** TIME：写入 10 字节时间载荷。 */
    val TIME: UUID = UUID.fromString("00002006-3dd4-4255-8d62-6dc7b9bd5561")

    /** NOT1：相机状态通知，握手成功后回 01 00。 */
    val NOT1: UUID = UUID.fromString("00002008-3dd4-4255-8d62-6dc7b9bd5561")

    /**
     * ML‑L7 遥控（Remote）模式：
     *  - 2087 遥控配对指示
     *  - 2084 遥控状态指示
     *  - 2083 快门/录像控制
     * 智能设备模式用 2000 配对，两种模式不能混用。
     */
    val PAIR_REMOTE: UUID = UUID.fromString("00002087-3dd4-4255-8d62-6dc7b9bd5561")
    val REMOTE_IND1: UUID = UUID.fromString("00002084-3dd4-4255-8d62-6dc7b9bd5561")
    val REMOTE_SHUTTER: UUID = UUID.fromString("00002083-3dd4-4255-8d62-6dc7b9bd5561")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** 广播里的厂商数据 company id（尼康）。 */
    const val COMPANY_ID_NIKON = 0x0399
}

/**
 * 17 字节配对消息（小端）：
 *   byte 0    阶段（1 我们发起 / 2 相机挑战 / 3 我们应答 / 4 相机回执）
 *   byte 1-8  时间戳
 *   byte 9-12 device
 *   byte 13-16 nonce
 */
data class NikonPairingMessage(
    val stage: Int,
    val timestamp: Long,
    val device: Long,
    val nonce: Long
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(stage.toByte())
        buffer.putLong(timestamp)
        buffer.putInt((device and 0xFFFFFFFFL).toInt())
        buffer.putInt((nonce and 0xFFFFFFFFL).toInt())
        return buffer.array()
    }

    companion object {
        fun decode(data: ByteArray): NikonPairingMessage? {
            if (data.size < 17) return null
            val buffer = ByteBuffer.wrap(data, 0, 17).order(ByteOrder.LITTLE_ENDIAN)
            val stage = buffer.get().toInt() and 0xFF
            val timestamp = buffer.getLong()
            val device = buffer.getInt().toLong() and 0xFFFFFFFFL
            val nonce = buffer.getInt().toLong() and 0xFFFFFFFFL
            return NikonPairingMessage(stage, timestamp, device, nonce)
        }
    }
}

/**
 * 尼康握手用的自定义散列：底层是标准 Blowfish/ECB/NoPadding，8 字节密钥
 * FF FF AA 55 11 22 33 00，按 CBC-MAC 方式把 32 位大端字串起来。
 */
class NikonBlowfishMac {
    private val cipher: Cipher = Cipher.getInstance("Blowfish/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "Blowfish"))
    }

    /** [words] 长度必须为偶数；返回 (left, right)。 */
    fun mac(words: IntArray): IntArray {
        require(words.size % 2 == 0) { "block count must be even" }
        var left = 0x01020304
        var right = 0x05060708
        var i = 0
        while (i < words.size) {
            val inLeft = words[i] xor left
            val inRight = words[i + 1] xor right
            val block = cipher.doFinal(
                ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                    .putInt(inLeft).putInt(inRight).array()
            )
            val out = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN)
            left = out.getInt()
            right = out.getInt()
            i += 2
        }
        return intArrayOf(left, right)
    }

    companion object {
        private val KEY = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xAA.toByte(), 0x55.toByte(),
            0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x00.toByte()
        )
    }
}

/**
 * 4 阶段握手（我们只负责发起 1、应答 3；2 与 4 由相机给出）。
 */
class NikonHandshake(private val mac: NikonBlowfishMac = NikonBlowfishMac()) {

    private val random = java.security.SecureRandom()

    /** 生成阶段 1。重连时可复用上次保存的 device / nonce。 */
    fun createStage1(deviceOverride: Long?, nonceOverride: Long?): NikonPairingMessage {
        val timestamp = random.nextLong()
        val device = deviceOverride ?: (((random.nextInt().toLong() and 0xFFFFFFFFL) and 0xFFFFFF00L) or 0x01L)
        val nonce = nonceOverride ?: (random.nextInt().toLong() and 0xFFFFFFFFL)
        return NikonPairingMessage(1, timestamp, device, nonce)
    }

    /**
     * 用阶段 2 的挑战反推相机用的盐，构造阶段 3 应答。找不到盐说明本机不被接受。
     */
    fun buildStage3(stage1: NikonPairingMessage, stage2: NikonPairingMessage): NikonPairingMessage? {
        val saltIndex = findSalt(stage1, stage2) ?: return null
        val salt = SALTS[saltIndex]
        val ourTs = timestampWords(stage1.timestamp)
        val camTs = timestampWords(stage2.timestamp)
        val result = mac.mac(
            intArrayOf(salt[0], salt[1], ourTs[0], ourTs[1], camTs[0], camTs[1])
        )
        return NikonPairingMessage(
            stage = 3,
            timestamp = stage1.timestamp,
            device = Integer.reverseBytes(result[0]).toLong() and 0xFFFFFFFFL,
            nonce = Integer.reverseBytes(result[1]).toLong() and 0xFFFFFFFFL
        )
    }

    /** 阶段 4 的 byte 9..16 是相机 8 位 ASCII 序列号。 */
    fun serialFromStage4(stage4: NikonPairingMessage): String =
        String(stage4.encode().copyOfRange(9, 17), Charsets.US_ASCII)

    /** 重连后相机可能不再走完整握手，用广播里的 device id 直接构造阶段 1 的身份。 */
    fun deviceFromAdvertisement(advertisedId: Long?): Long? = advertisedId

    private fun findSalt(stage1: NikonPairingMessage, stage2: NikonPairingMessage): Int? {
        val camTs = timestampWords(stage2.timestamp)
        val ourTs = timestampWords(stage1.timestamp)
        val camDeviceBe = Integer.reverseBytes((stage2.device and 0xFFFFFFFFL).toInt())
        val camNonceBe = Integer.reverseBytes((stage2.nonce and 0xFFFFFFFFL).toInt())
        for (i in SALTS.indices) {
            val salt = SALTS[i]
            val h = mac.mac(intArrayOf(salt[0], salt[1], camTs[0], camTs[1], ourTs[0], ourTs[1]))
            if (h[0] == camDeviceBe && h[1] == camNonceBe) return i
        }
        return null
    }

    /** 时间戳拆成两个 32 位字，并按大端字节序对齐（协议里散列按大端字处理）。 */
    private fun timestampWords(timestamp: Long): IntArray {
        val low = (timestamp and 0xFFFFFFFFL).toInt()
        val high = ((timestamp ushr 32) and 0xFFFFFFFFL).toInt()
        return intArrayOf(Integer.reverseBytes(low), Integer.reverseBytes(high))
    }

    companion object {
        private val SALTS = arrayOf(
            intArrayOf(0x704066e4, 0x0433d552),
            intArrayOf(0xed4b8fac.toInt(), 0x15f7e47b),
            intArrayOf(0x24471f11, 0x8b5ea1fc.toInt()),
            intArrayOf(0x05960c31, 0x2b8c7f41),
            intArrayOf(0xfda588c1.toInt(), 0xeba8b1f3.toInt()),
            intArrayOf(0x99166056.toInt(), 0x1bd3d550),
            intArrayOf(0xcd32687f.toInt(), 0xa9e28a30.toInt()),
            intArrayOf(0x2a8fe834, 0xdec7ebf4.toInt())
        )
    }
}

/**
 * 41 字节 GEO 载荷（WGS-84）：
 *   0-1   header 0x007F
 *   2-6   纬度：方向 N/S、度、分、分小数两位
 *   7-11  经度：方向 E/W、度、分、分小数两位
 *   12    卫星数
 *   13    海拔基准 P/M
 *   14-15 海拔绝对值
 *   16-17 年(UTC)  18 月 19 日 20 时 21 分 22 秒 23 百分秒
 *   24    有效标志 0x01
 *   25-30 "WGS-84"
 *   31-40 补零
 */
object NikonGeoPayload {

    fun build(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Int,
        satellites: Int,
        utcMillis: Long = System.currentTimeMillis()
    ): ByteArray {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = utcMillis
        }
        val lat = toNikonCoordinate(latitude, 'N', 'S')
        val lon = toNikonCoordinate(longitude, 'E', 'W')
        val buffer = ByteBuffer.allocate(41).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0x007F.toShort())
        buffer.put(lat.direction.code.toByte())
        buffer.put(lat.degrees.toByte())
        buffer.put(lat.minutes.toByte())
        buffer.put(lat.sub1.toByte())
        buffer.put(lat.sub2.toByte())
        buffer.put(lon.direction.code.toByte())
        buffer.put(lon.degrees.toByte())
        buffer.put(lon.minutes.toByte())
        buffer.put(lon.sub1.toByte())
        buffer.put(lon.sub2.toByte())
        buffer.put(satellites.coerceIn(0, 100).toByte())
        buffer.put(if (altitudeMeters >= 0) 'P'.code.toByte() else 'M'.code.toByte())
        buffer.putShort(abs(altitudeMeters).toShort())
        buffer.putShort(calendar.get(Calendar.YEAR).toShort())
        buffer.put((calendar.get(Calendar.MONTH) + 1).toByte())
        buffer.put(calendar.get(Calendar.DAY_OF_MONTH).toByte())
        buffer.put(calendar.get(Calendar.HOUR_OF_DAY).toByte())
        buffer.put(calendar.get(Calendar.MINUTE).toByte())
        buffer.put(calendar.get(Calendar.SECOND).toByte())
        buffer.put(((calendar.get(Calendar.MILLISECOND) / 10).toByte()))
        buffer.put(0x01.toByte())
        buffer.put("WGS-84".toByteArray(Charsets.US_ASCII))
        repeat(10) { buffer.put(0x00.toByte()) }
        return buffer.array()
    }

    private data class Coord(val direction: Char, val degrees: Int, val minutes: Int, val sub1: Int, val sub2: Int)

    private fun toNikonCoordinate(value: Double, positive: Char, negative: Char): Coord {
        val direction = if (value < 0) negative else positive
        val absolute = abs(value)
        val degrees = absolute.toInt()
        val minutesFull = (absolute - degrees) * 60.0
        val minutes = minutesFull.toInt()
        val subFull = (minutesFull - minutes) * 100.0
        val sub1 = subFull.toInt()
        val sub2 = ((subFull - sub1) * 100.0).toInt()
        return Coord(direction, degrees, minutes, sub1, sub2)
    }
}

/**
 * 10 字节 TIME 载荷：本地时间 + 夏令时标志 + 时区偏移。
 */
object NikonTimePayload {

    /**
     * 协议要求 TIME 的日期/时间字段必须是 UTC，时区另用有符号的「时/分」表达。
     * 之前这里用了本地时间又带 +08:00 偏移，相机会把时间理解成「UTC 23:xx + 8h」，
     * 与 GEO 包里的 UTC 时间相差 8 小时，GPS 会话会被判为无效并回 0x80。
     * 参考 Octopustank/HarmoNiLink 的 TimePayloadGenerator 与 jaminxx-aha/ZPill 的
     * time_payload.dart：字段取 UTC，时区偏移单独写。
     */
    fun build(nowMillis: Long = System.currentTimeMillis()): ByteArray {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMillis }
        val offsetMinutes = TimeZone.getDefault().getOffset(nowMillis) / 60000
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(utc.get(Calendar.YEAR).toShort())
        buffer.put((utc.get(Calendar.MONTH) + 1).toByte())
        buffer.put(utc.get(Calendar.DAY_OF_MONTH).toByte())
        buffer.put(utc.get(Calendar.HOUR_OF_DAY).toByte())
        buffer.put(utc.get(Calendar.MINUTE).toByte())
        buffer.put(utc.get(Calendar.SECOND).toByte())
        // 夏令时已包含在系统时区偏移里，这里固定 0，避免重复加一小时。
        buffer.put(0)
        // Kotlin 的 / 与 % 都向零截断，UTC-3:30 会得到 (-3, -30)，符号一致。
        buffer.put((offsetMinutes / 60).toByte())
        buffer.put((offsetMinutes % 60).toByte())
        return buffer.array()
    }
}

/** SnapBridge 风格的控制器名称：Android_<机型>_<四位随机>，最长 32 字节。 */
object NikonControllerName {
    fun generate(model: String): String {
        val cleaned = model.replace(Regex("[^\\x21-\\x7e]"), "_")
        val suffix = String.format(Locale.US, "_%04d", java.util.Random().nextInt(10000))
        val head = if (cleaned.length > 31 - suffix.length) {
            cleaned.substring(0, 31 - suffix.length)
        } else cleaned
        return ("Android_" + head + suffix).take(31)
    }
}
