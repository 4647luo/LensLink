package com.lutcamera.lutcamerapro.util

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 所有外部输入（局域网 FTP、预设 ZIP、更新包、content URI）都必须从这里过一遍，
 * 避免任何一条链路继续用无上限的 readBytes() 把内存打爆。
 */
object BoundedIo {
    const val MAX_LUT_BYTES = 32L * 1024 * 1024
    const val MAX_PRESET_ZIP_BYTES = 64L * 1024 * 1024
    const val MAX_PRESET_ENTRY_BYTES = 32L * 1024 * 1024
    const val MAX_PRESET_TOTAL_BYTES = 128L * 1024 * 1024
    const val MAX_UPDATE_APK_BYTES = 128L * 1024 * 1024
    const val MAX_FTP_BYTES = 200L * 1024 * 1024

    fun copyWithLimit(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var read = input.read(buffer)
        while (read >= 0) {
            total += read
            if (total > maxBytes) throw IOException("输入超过上限 ${maxBytes / 1024 / 1024}MB")
            output.write(buffer, 0, read)
            read = input.read(buffer)
        }
        output.flush()
        return total
    }

    fun readBytesWithLimit(input: InputStream, maxBytes: Long): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        // 上限最大 128MB，预分配只给 1MB，避免小文件也先占大数组。
        val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 1L * 1024 * 1024).toInt())
        copyWithLimit(input, out, maxBytes)
        return out.toByteArray()
    }

    fun readUtf8WithLimit(input: InputStream, maxBytes: Long): String =
        String(readBytesWithLimit(input, maxBytes), Charsets.UTF_8)
}
