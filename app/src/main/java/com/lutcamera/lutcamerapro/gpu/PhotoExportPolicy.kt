package com.lutcamera.lutcamerapro.gpu

import kotlin.math.min
import kotlin.math.roundToInt

/** Pure output policy, shared by processing and JVM tests. */
object PhotoExportPolicy {
    const val ORIGINAL_RESOLUTION = "原始分辨率"
    /** 45MP 的 Z8/Z9 图必须原尺寸导出；50MP 上限只挡恶意/异常超大图。 */
    const val MAX_EXPORT_PIXELS = 50_000_000L
    const val MAX_EXPORT_SIDE = 9000
    const val MAX_INPUT_SIDE = 20_000

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
        var targetWidth = width
        var targetHeight = height
        val edges = when (resolution) {
            "高清 (1080P)" -> 1920 to 1080
            "流畅 (720P)" -> 1280 to 720
            else -> null
        }
        if (edges != null) {
            val maxWidth = if (width >= height) edges.first else edges.second
            val maxHeight = if (width >= height) edges.second else edges.first
            val scale = min(1.0, min(maxWidth.toDouble() / width, maxHeight.toDouble() / height))
            targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
            targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        }
        // 任何“原始分辨率”也可能来自伪造头信息；先按像素数、再按长边兜底。
        val pixels = targetWidth.toLong() * targetHeight.toLong()
        if (pixels > MAX_EXPORT_PIXELS) {
            val scale = kotlin.math.sqrt(MAX_EXPORT_PIXELS.toDouble() / pixels)
            targetWidth = (targetWidth * scale).roundToInt().coerceAtLeast(1)
            targetHeight = (targetHeight * scale).roundToInt().coerceAtLeast(1)
        }
        val longSide = maxOf(targetWidth, targetHeight)
        if (longSide > MAX_EXPORT_SIDE) {
            val scale = MAX_EXPORT_SIDE.toDouble() / longSide
            targetWidth = (targetWidth * scale).roundToInt().coerceAtLeast(1)
            targetHeight = (targetHeight * scale).roundToInt().coerceAtLeast(1)
        }
        return Size(targetWidth, targetHeight)
    }

    fun isPlausibleInput(width: Int, height: Int): Boolean =
        width in 1..MAX_INPUT_SIDE && height in 1..MAX_INPUT_SIDE &&
            width.toLong() * height.toLong() <= MAX_EXPORT_PIXELS * 4

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
