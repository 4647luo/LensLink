package com.lutcamera.lutcamerapro.lut

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.StringTokenizer

data class LutData(val title: String, val size: Int, val data: FloatArray)

/** Strict, bounded 3D CUBE parsing shared by imports and persisted library loading. */
object CubeParser {
    // Built-in tables are 16; 65 also covers common 17/33/65 imports with ~3.2 MiB of floats.
    const val MAX_SIZE = 65
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    class ParseException(message: String) : IllegalArgumentException(message)

    /** Compatibility helper: invalid data is absent, never replaced by an Identity LUT. */
    fun parse(inputStream: InputStream): LutData? = try {
        parseOrThrow(inputStream)
    } catch (_: ParseException) {
        null
    } catch (_: IOException) {
        null
    }

    /** Throws a useful import error without changing library or selection state. */
    fun parseOrThrow(inputStream: InputStream): LutData {
        var title = "Unknown LUT"
        var hasTitle = false
        var size = 0
        var values: FloatArray? = null
        var index = 0
        var domainMinSeen = false
        var domainMaxSeen = false
        var lineNumber = 0
        BufferedReader(InputStreamReader(BoundedInput(inputStream), Charsets.UTF_8), 64 * 1024).use { reader ->
            while (true) {
                val raw = reader.readLine() ?: break
                lineNumber++
                if (raw.length > 4096) fail(lineNumber, "单行过长")
                val line = stripComment(if (lineNumber == 1) raw.removePrefix("\uFEFF") else raw).trim()
                if (line.isEmpty()) continue
                val tokens = StringTokenizer(line)
                val directive = tokens.nextToken().uppercase(Locale.ROOT)
                when (directive) {
                    "TITLE" -> {
                        if (hasTitle || index != 0) fail(lineNumber, "TITLE 必须只出现一次且位于数据之前")
                        val text = line.substring(5).trim()
                        if (text.isEmpty()) fail(lineNumber, "TITLE 不能为空")
                        title = if (text.startsWith('"')) {
                            if (text.length < 2 || !text.endsWith('"') || text.substring(1, text.lastIndex).contains('"')) {
                                fail(lineNumber, "TITLE 引号不完整")
                            }
                            text.substring(1, text.lastIndex)
                        } else text
                        hasTitle = true
                    }
                    "LUT_1D_SIZE" -> fail(lineNumber, "暂不支持 1D LUT，请选择纯 3D CUBE 文件")
                    "LUT_3D_SIZE" -> {
                        if (values != null || index != 0 || tokens.countTokens() != 1) fail(lineNumber, "LUT_3D_SIZE 重复或格式错误")
                        size = tokens.nextToken().toIntOrNull() ?: fail(lineNumber, "LUT_3D_SIZE 必须为整数")
                        if (size !in 2..MAX_SIZE) fail(lineNumber, "支持的 3D LUT 阶数为 2–$MAX_SIZE")
                        values = FloatArray(size * size * size * 3)
                    }
                    "DOMAIN_MIN", "DOMAIN_MAX" -> {
                        if (index != 0 || (directive == "DOMAIN_MIN" && domainMinSeen) || (directive == "DOMAIN_MAX" && domainMaxSeen)) {
                            fail(lineNumber, "DOMAIN 指令重复或出现在数据之后")
                        }
                        if (tokens.countTokens() != 3) fail(lineNumber, "DOMAIN 必须包含三个数值")
                        val expected = if (directive == "DOMAIN_MIN") 0f else 1f
                        repeat(3) {
                            val value = finite(tokens.nextToken(), lineNumber)
                            if (value != expected) fail(lineNumber, "当前渲染仅支持 DOMAIN_MIN 0 0 0 和 DOMAIN_MAX 1 1 1")
                        }
                        if (directive == "DOMAIN_MIN") domainMinSeen = true else domainMaxSeen = true
                    }
                    else -> {
                        val destination = values ?: fail(lineNumber, "缺少 LUT_3D_SIZE 或包含不支持的指令")
                        if (tokens.countTokens() != 2) fail(lineNumber, "每个颜色节点必须恰好包含 R G B 三个数值")
                        if (index + 3 > destination.size) fail(lineNumber, "颜色节点数量超过声明的阶数")
                        val red = finite(directive, lineNumber)
                        val green = finite(tokens.nextToken(), lineNumber)
                        val blue = finite(tokens.nextToken(), lineNumber)
                        destination[index++] = red
                        destination[index++] = green
                        destination[index++] = blue
                    }
                }
            }
        }
        val data = values ?: fail(lineNumber, "缺少 LUT_3D_SIZE")
        if (index != data.size) fail(lineNumber, "颜色节点不完整：需要 " + data.size / 3 + "，实际 " + index / 3)
        return LutData(title, size, data)
    }

    private fun finite(token: String, lineNumber: Int): Float {
        val value = token.toFloatOrNull() ?: fail(lineNumber, "无效的颜色数值：$token")
        if (!value.isFinite()) fail(lineNumber, "颜色数值不能为 NaN、Infinity 或溢出")
        return value
    }

    private fun stripComment(line: String): String {
        var quoted = false
        for (index in line.indices) {
            if (line[index] == '"') quoted = !quoted
            if (!quoted && (line[index] == '#' || (line[index] == '/' && line.getOrNull(index + 1) == '/'))) {
                return line.substring(0, index)
            }
        }
        return line
    }

    private fun fail(line: Int, message: String): Nothing = throw ParseException("CUBE 第 $line 行：$message")

    private class BoundedInput(private val source: InputStream) : InputStream() {
        private var count = 0L
        private fun account(length: Int) {
            if (length > 0) count += length
            if (count > MAX_FILE_BYTES) throw ParseException("CUBE 文件超过 64 MiB 限制")
        }
        override fun read(): Int = source.read().also { if (it != -1) account(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length).also(::account)
        override fun close() = source.close()
    }
}

