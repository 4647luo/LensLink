package com.lutcamera.lutcamerapro.gpu

import com.lutcamera.lutcamerapro.log.DoukLog
import java.io.File
import java.io.RandomAccessFile

/**
 * 尼康 Z 系列 MOV 里的 LPCM 音轨，小米这代 MediaExtractor 只报一个
 * application/octet-stream、采样率声道都没有，readSampleData 也取不到数据。
 * 所以这里自己走一遍 MOV 的 box 结构（moov/trak/mdia/minf/stbl），
 * 把 stsd / stsz / stsc / stco 读出来，直接按偏移取 PCM，转成 16-bit 小端。
 */
class MovAudioReader private constructor(
    private val file: File,
    val channels: Int,
    val sampleRate: Int,
    val sampleBits: Int,
    private val littleEndian: Boolean,
    val format: String,
    private val offsets: LongArray,
    private val sizes: IntArray
) {

    val bytesPerFrame: Int get() = channels * sampleBits / 8

    val durationMs: Long
        get() {
            val frames = sizes.sumOf { (it / bytesPerFrame).toLong() }
            return if (sampleRate > 0) frames * 1000L / sampleRate else 0L
        }

    /** 按包懒加载读出 16-bit 小端 PCM，块大小总是 frameBytes 的整数倍。 */
    fun stream16(): Sequence<ByteArray> = sequence {
        RandomAccessFile(file, "r").use { raf ->
            val raw = ByteArray(if (sizes.isEmpty()) 0 else sizes.max().coerceIn(1, 4 * 1024 * 1024))
            // 尼康这边一个 sample 只有 6 字节，逐包喂太碎，攒到 8KB 以上再交出去
            var block = ByteArray(0)
            for (i in offsets.indices) {
                val size = sizes[i]
                if (size <= 0) continue
                raf.seek(offsets[i])
                val got = raf.read(raw, 0, size)
                if (got <= 0) continue
                val part = to16(raw, got)
                block = if (block.isEmpty()) part else block + part
                if (block.size >= 8192) {
                    yield(block)
                    block = ByteArray(0)
                }
            }
            if (block.isNotEmpty()) yield(block)
        }
    }

    private fun to16(src: ByteArray, size: Int): ByteArray {
        return when (sampleBits) {
            16 -> ByteArray(size).also { out ->
                if (littleEndian) {
                    System.arraycopy(src, 0, out, 0, size)
                } else {
                    var i = 0
                    while (i + 1 < size) {
                        out[i] = src[i + 1]
                        out[i + 1] = src[i]
                        i += 2
                    }
                }
            }
            32 -> {
                val out = ByteArray(size / 4 * 2)
                var i = 0
                var o = 0
                while (i + 3 < size) {
                    if (littleEndian) {
                        out[o] = src[i + 2]
                        out[o + 1] = src[i + 3]
                    } else {
                        out[o] = src[i + 1]
                        out[o + 1] = src[i]
                    }
                    i += 4; o += 2
                }
                out
            }
            else -> { // 24-bit
                val out = ByteArray(size / 3 * 2)
                var i = 0
                var o = 0
                while (i + 2 < size) {
                    if (littleEndian) {
                        out[o] = src[i + 1]
                        out[o + 1] = src[i + 2]
                    } else {
                        out[o] = src[i]
                        out[o + 1] = src[i + 1]
                    }
                    i += 3; o += 2
                }
                out
            }
        }
    }

    companion object {

        private const val TAG = "MovAudio"

        private class Box(val start: Long, val end: Long)

        fun open(file: File): MovAudioReader? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val moov = findTop(raf, "moov") ?: return null
                var trak: Box? = null
                var pos = moov.start + 8
                while (pos + 8 <= moov.end) {
                    val (size, type, _) = readHeader(raf, pos)
                    if (size <= 0) break
                    if (type == "trak" &&
                        isSoundTrack(raf, pos, pos + size)
                    ) {
                        trak = Box(pos, pos + size)
                        break
                    }
                    pos += size
                }
                val trakBox = trak ?: return null
                val stbl = descend(raf, trakBox, listOf("mdia", "minf", "stbl")) ?: return null

                val stsd = findChild(raf, stbl.start, stbl.end, "stsd") ?: return null
                // version/flags(4) + entry_count(4)，entry 从 +16 开始
                val entry = stsd.start + 16L
                val entrySize = readU32(raf, entry).toInt()
                val format = readType(raf, entry + 4)
                // 标准 AudioSampleEntry: channels(2) sampleSize(2) rate(4) 在 entry+24/+26/+32
                var channels = readU16(raf, entry + 24)
                var bits = readU16(raf, entry + 26).let { if (it == 0) 16 else it }
                var rate = (readU32(raf, entry + 32) ushr 16).toInt()
                var le = readU16(raf, entry + 16) == 0
                // lpcm 的 v1/v2 扩展里这几个字段是假的，真参数在一段
                // sizeOfStruct(4) rate(double 8) channels(4) 0x7F000000 bits(4) flags(4)
                // bytesPerPacket(4) framesPerPacket(4) 的结构里，认 0x7F000000 这个标记最稳
                var bytesPerPacket = 0
                var framesPerPacket = 1
                if (format == "lpcm") {
                    var p = entry + 8
                    while (p + 4 <= entry + entrySize) {
                        if (readU32(raf, p) == 0x7F000000L) {
                            channels = readU32(raf, p - 4).toInt()
                            bits = readU32(raf, p + 4).toInt().let { if (it == 0) 16 else it }
                            val flags = readU32(raf, p + 8)
                            le = (flags and 0x2L) == 0L
                            bytesPerPacket = readU32(raf, p + 12).toInt()
                            framesPerPacket = readU32(raf, p + 16).toInt().let { if (it <= 0) 1 else it }
                            rate = readDouble(raf, p - 12).toInt().let { if (it in 8000..192000) it else 48000 }

                            break
                        }
                        p += 4
                    }
                }
                if (channels !in 1..8 || rate !in 8000..192000) return null
                var bytesPerFrameOverride = 0
                if (bytesPerPacket > 0 && framesPerPacket > 0) {
                    val perFrame = bytesPerPacket / framesPerPacket
                    if (perFrame > 0) bytesPerFrameOverride = perFrame
                    // bytesPerFrame 更准的时候，用它反推每一位的字节数
                    if (perFrame > channels && perFrame % channels == 0) {
                        bits = perFrame / channels * 8
                    }
                }
                DoukLog.i(TAG, "PCM", "format" to format, "ch" to channels, "bits" to bits, "rate" to rate)

                val stsz = findChild(raf, stbl.start, stbl.end, "stsz") ?: return null
                // stsz 标准布局：version/flags(4)@+8、sample_size(4)@+12、sample_count(4)@+16、表@+20
                // 尼康的 LPCM 就是固定 6 字节/采样（2 声道 × 24bit），没有表
                var fixed = readU32(raf, stsz.start + 12).toInt()
                var count = readU32(raf, stsz.start + 16).toInt()
                var base = stsz.start + 20
                // 少数封装把 version 省掉，退回去按 +8/+12/+16 读
                if (count <= 0 || count > 5_000_000) {
                    val legacyFixed = readU32(raf, stsz.start + 8).toInt()
                    val legacyCount = readU32(raf, stsz.start + 12).toInt()
                    if (legacyCount in 1..5_000_000 && legacyFixed in 0..65536) {
                        fixed = legacyFixed
                        count = legacyCount
                        base = stsz.start + 16
                    }
                }
                if (count <= 0 || count > 5_000_000) return null
                val sizes = IntArray(count)
                if (fixed > 0) {
                    java.util.Arrays.fill(sizes, fixed)
                } else {
                    for (i in 0 until count) sizes[i] = readU32(raf, base + i * 4L).toInt()
                }

                val stco = findChild(raf, stbl.start, stbl.end, "stco")
                    ?: findChild(raf, stbl.start, stbl.end, "co64") ?: return null
                val is64 = readType(raf, stco.start + 4) == "co64"
                // 标准布局是 size/type(8) + version/flags(4) + entry_count(4) + 偏移表；
                // 尼康这个 stco/co64 也是这么排的，稳妥起见两个位置都试一下
                var chunkCount = readU32(raf, stco.start + 12).toInt()
                var cbase = stco.start + 16
                if (chunkCount !in 1..100_000) {
                    chunkCount = readU32(raf, stco.start + 8).toInt()
                    cbase = stco.start + 12
                }
                val chunkOffsets = LongArray(chunkCount)
                for (i in 0 until chunkCount) {
                    chunkOffsets[i] = if (is64) readU64(raf, cbase + i * 8L)
                    else readU32(raf, cbase + i * 4L)
                }

                val stsc = findChild(raf, stbl.start, stbl.end, "stsc") ?: return null
                // stsc 标准布局：version/flags(4)@+8、entry_count(4)@+12、表@+16（每条 12 字节）
                val entryCount = readU32(raf, stsc.start + 12).toInt()
                val scFirst = IntArray(entryCount)
                val scPerChunk = IntArray(entryCount)
                var s = stsc.start + 16
                for (i in 0 until entryCount) {
                    scFirst[i] = readU32(raf, s).toInt()
                    scPerChunk[i] = readU32(raf, s + 4).toInt()
                    s += 12
                }

                // 拼每个采样的绝对偏移
                val offsets = LongArray(count)
                var sample = 0
                for (c in 0 until chunkCount) {
                    if (sample >= count) break
                    var per = if (entryCount > 0) scPerChunk[entryCount - 1] else 1
                    for (e in entryCount - 1 downTo 0) {
                        if (c + 1 >= scFirst[e]) { per = scPerChunk[e]; break }
                    }
                    var off = chunkOffsets[c]
                    for (k in 0 until per) {
                        if (sample >= count) break
                        offsets[sample] = off
                        off += sizes[sample].toLong()
                        sample++
                    }
                }
                DoukLog.i(
                    TAG, "OFFSETS", "count" to count, "chunks" to chunkCount,
                    "filled" to sample, "first" to offsets[0], "size0" to sizes[0]
                )
                if (sample < count) return null

                val le2 = if (format == "lpcm") le else detectEndian(raf, offsets, sizes, channels, bits, format == "sowt")
                MovAudioReader(file, channels, rate, bits, le2, format, offsets, sizes)
            }
        }.onFailure { DoukLog.w(TAG, "FAIL", "err" to it.message) }
            .getOrNull()

        /** 只看前一小段 PCM：把字节按大端/小端两种解释算 RMS，小的那种就是真实字节序。 */
        private fun detectEndian(
            raf: RandomAccessFile,
            offsets: LongArray,
            sizes: IntArray,
            channels: Int,
            bits: Int,
            fallback: Boolean
        ): Boolean {
            if (bits != 24 || offsets.isEmpty()) return fallback
            var idx = 0
            var bytes = 0L
            while (idx < offsets.size && bytes < 120_000L) {
                bytes += sizes[idx].toLong()
                idx++
            }
            if (bytes < 3_000) return fallback
            val buf = ByteArray(bytes.toInt())
            raf.seek(offsets[0])
            raf.readFully(buf)

            fun rms(little: Boolean): Double {
                var sum = 0.0
                var frames = 0
                var i = 0
                while (i + 3 <= buf.size) {
                    val v = if (little)
                        (buf[i + 2].toInt() shl 16) or
                            ((buf[i + 1].toInt() and 0xFF) shl 8) or
                            (buf[i].toInt() and 0xFF)
                    else
                        (buf[i].toInt() shl 16) or
                            ((buf[i + 1].toInt() and 0xFF) shl 8) or
                            (buf[i + 2].toInt() and 0xFF)
                    sum += (v.toDouble() / 256.0) * (v.toDouble() / 256.0)
                    frames++
                    i += 3
                }
                return if (frames == 0) 0.0 else sum / frames
            }

            val leRms = rms(true)
            val beRms = rms(false)
            DoukLog.i(TAG, "ENDIAN_CMP", "le" to leRms.toLong(), "be" to beRms.toLong())
            return leRms <= beRms
        }

        private fun isSoundTrack(raf: RandomAccessFile, start: Long, end: Long): Boolean {
            val mdia = findChild(raf, start, end, "mdia") ?: return false
            val hdlr = findChild(raf, mdia.start, mdia.end, "hdlr") ?: return false
            return readType(raf, hdlr.start + 16) == "soun"
        }

        private fun descend(raf: RandomAccessFile, outer: Box, path: List<String>): Box? {
            var box: Box? = outer
            for (name in path) {
                box = findChild(raf, box!!.start, box.end, name) ?: return null
            }
            return box
        }

        private fun findChild(raf: RandomAccessFile, start: Long, end: Long, type: String): Box? {
            var pos = start + 8
            while (pos + 8 <= end) {
                val (size, t, _) = readHeader(raf, pos)
                if (size <= 0) return null
                if (t == type) return Box(pos, pos + size)
                pos += size
            }
            return null
        }

        private fun findTop(raf: RandomAccessFile, type: String): Box? {
            var pos = 0L
            val end = raf.length()
            while (pos + 8 <= end) {
                val (size, t, _) = readHeader(raf, pos)
                if (size <= 0) return null
                if (t == type) return Box(pos, pos + size)
                pos += size
            }
            return null
        }

        private fun readHeader(raf: RandomAccessFile, pos: Long): Triple<Int, String, Int> {
            val size32 = readU32(raf, pos)
            val type = readType(raf, pos + 4)
            return when {
                size32 == 1L -> Triple(readU64(raf, pos + 8).toInt(), type, 16)
                size32 == 0L -> Triple((raf.length() - pos).toInt(), type, 8)
                else -> Triple(size32.toInt(), type, 8)
            }
        }

        private fun readType(raf: RandomAccessFile, pos: Long): String {
            raf.seek(pos)
            val b = ByteArray(4)
            raf.readFully(b)
            return String(b, Charsets.US_ASCII)
        }

        private fun readDouble(raf: RandomAccessFile, pos: Long): Double {
            raf.seek(pos)
            return raf.readDouble()
        }

        private fun readU16(raf: RandomAccessFile, pos: Long): Int {
            raf.seek(pos)
            return raf.readUnsignedShort()
        }

        private fun readU32(raf: RandomAccessFile, pos: Long): Long {
            raf.seek(pos)
            return raf.readInt().toLong() and 0xFFFFFFFFL
        }

        private fun readU64(raf: RandomAccessFile, pos: Long): Long {
            raf.seek(pos)
            val hi = raf.readInt().toLong() and 0xFFFFFFFFL
            val lo = raf.readInt().toLong() and 0xFFFFFFFFL
            return (hi shl 32) or lo
        }
    }
}
