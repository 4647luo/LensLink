package com.lutcamera.lutcamerapro.gpu

import kotlin.math.min
import kotlin.math.roundToInt

/** Pure output policy, shared by processing and JVM tests. */
object PhotoExportPolicy {
    const val ORIGINAL_RESOLUTION = "原始分辨率"
    data class Size(val width: Int, val height: Int)
    data class Plan(val saveOriginal: Boolean, val saveOutput: Boolean)

    fun plan(mode: String): Plan = when (mode) {
        "ORIGINAL_ONLY" -> Plan(true, false)
        "LUT_ONLY" -> Plan(false, true)
        "DUAL" -> Plan(true, true)
        else -> throw IllegalArgumentException("未知保存策略: $mode")
    }

    fun targetSize(width: Int, height: Int, resolution: String): Size {
        require(width > 0 && height > 0)
        val edges = when (resolution) {
            "高清 (1080P)" -> 1920 to 1080
            "流畅 (720P)" -> 1280 to 720
            else -> return Size(width, height)
        }
        val maxWidth = if (width >= height) edges.first else edges.second
        val maxHeight = if (width >= height) edges.second else edges.first
        val scale = min(1.0, min(maxWidth.toDouble() / width, maxHeight.toDouble() / height))
        return Size((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
    }

    fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        require(width > 0 && height > 0 && targetWidth > 0 && targetHeight > 0)
        var sample = 1
        while (sample <= Int.MAX_VALUE / 2 && width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }

    fun originalName(source: String?, timestamp: Long): String {
        val safe = source.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}<>:\"/\\\\|?*]"), "_").take(180).trim()
        return if (safe.endsWith(".jpg", true) || safe.endsWith(".jpeg", true)) safe else "ORIGINAL_$timestamp.jpg"
    }

    fun isSupportedJpeg(bytes: ByteArray, sourceFileName: String?): Boolean {
        if (sourceFileName != null && !sourceFileName.endsWith(".jpg", true) && !sourceFileName.endsWith(".jpeg", true)) return false
        return bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()
    }
}
