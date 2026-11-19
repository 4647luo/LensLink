package com.lutcamera.lutcamerapro.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NikonPhotoEvents {
    fun parse(data: ByteArray): List<Pair<Int, Int>> {
        require(data.size >= 2) { "照片事件数据不完整" }
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = b.short.toInt() and 0xffff
        require(count <= (data.size - 2) / 6) { "照片事件数量超出数据长度" }
        return List(count) { (b.short.toInt() and 0xffff) to b.int }
    }
    // null means a non-JPEG object; empty means use a safe generated name.
    fun objectName(data: ByteArray): String? {
        require(data.size >= 53) { "照片信息不完整" }
        val length = data[52].toInt() and 0xff
        if (length == 0) return ""
        require(53 + length * 2 <= data.size) { "照片文件名不完整" }
        val name = String(data, 53, (length - 1) * 2, Charsets.UTF_16LE)
        return name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
    }

    fun jpegName(data: ByteArray): String? {
        require(data.size >= 53) { "照片信息不完整" }
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.getShort(4).toInt() and 0xffff) != 0x3801) return null
        return objectName(data)
    }
    fun isJpeg(data: ByteArray): Boolean = data.size >= 4 &&
        data[0] == 0xff.toByte() && data[1] == 0xd8.toByte() &&
        data[data.size - 2] == 0xff.toByte() && data.last() == 0xd9.toByte()
}
