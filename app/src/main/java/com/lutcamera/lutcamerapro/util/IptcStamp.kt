package com.lutcamera.lutcamerapro.util

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.lutcamera.lutcamerapro.log.DoukLog
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 把作者 / 版权写进已经保存好的照片。
 *
 * 写两处，覆盖不同软件的读取习惯：
 * - EXIF：Artist(0x013B)、Copyright(0x8298)，Lightroom、系统相册、ExifTool 都读；
 * - IPTC-IIM：塞在 JPEG 的 APP13「Photoshop 3.0」段，资源 0x0404，含 By-line(2:80)、
 *   Copyright Notice(2:116)、Credit(2:110)、Keywords(2:25)，带 1:90 = ESC%G 声明 UTF-8。
 *
 * 只处理 JPG/JPEG。NEF 是 TIFF 容器、视频不是静态图，随便改会破坏文件，直接跳过。
 * 原有 APP13 里非 0x0404 的资源（别的软件写的）会原样保留。
 */
object IptcStamp {

    data class Profile(
        val artist: String = "",
        val copyright: String = "",
        val credit: String = "",
        val keywords: List<String> = emptyList()
    ) {
        val isEmpty: Boolean
            get() = artist.isBlank() && copyright.isBlank() && credit.isBlank() && keywords.isEmpty()
    }

    fun apply(context: Context?, pathOrUri: String, profile: Profile): Boolean {
        if (profile.isEmpty) return false
        return try {
            if (pathOrUri.startsWith("content://", ignoreCase = true)) {
                applyToUri(context, pathOrUri, profile)
            } else {
                val file = File(pathOrUri)
                if (!file.exists() || file.length() == 0L) false else applyToFile(file, profile)
            }
        } catch (e: Exception) {
            DoukLog.w("Iptc", "STAMP_FAIL", "target" to pathOrUri, "error" to (e.message ?: "?"))
            false
        }
    }

    private fun applyToFile(file: File, profile: Profile): Boolean {
        val ext = file.extension.lowercase()
        if (ext != "jpg" && ext != "jpeg") return false
        val exif = ExifInterface(file.absolutePath)
        if (profile.artist.isNotBlank()) {
            exif.setAttribute(ExifInterface.TAG_ARTIST, profile.artist.trim())
        }
        if (profile.copyright.isNotBlank()) {
            exif.setAttribute(ExifInterface.TAG_COPYRIGHT, profile.copyright.trim())
        }
        exif.saveAttributes()

        val original = file.readBytes()
        val stamped = injectIptc(original, profile)
        if (stamped !== original) file.writeBytes(stamped)
        DoukLog.i(
            "Iptc", "STAMP",
            "file" to file.name,
            "artist" to profile.artist,
            "copyright" to profile.copyright
        )
        return true
    }

    private fun applyToUri(context: Context?, uriText: String, profile: Profile): Boolean {
        if (context == null) return false
        val uri = Uri.parse(uriText)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        if (bytes.isEmpty()) return false
        val temp = File.createTempFile("iptc_", ".jpg", context.cacheDir)
        return try {
            temp.writeBytes(bytes)
            if (!applyToFile(temp, profile)) return false
            val stamped = temp.readBytes()
            var wrote = false
            // MediaStore 上 "rwt" 才是截断重写，部分机型只认 "w"，两种都试
            listOf("rwt", "w").forEach { mode ->
                if (wrote) return@forEach
                runCatching {
                    context.contentResolver.openOutputStream(uri, mode)?.use {
                        it.write(stamped)
                        wrote = true
                    }
                }
            }
            if (!wrote) throw java.io.IOException("无法回写照片")
            true
        } catch (e: Exception) {
            DoukLog.w("Iptc", "URI_STAMP_FAIL", "uri" to uriText, "error" to (e.message ?: "?"))
            false
        } finally {
            runCatching { temp.delete() }
        }
    }

    // ===== JPEG APP13 注入 =====

    private val PHOTOSHOP_HEADER = "Photoshop 3.0".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    private fun injectIptc(jpeg: ByteArray, profile: Profile): ByteArray {
        if (jpeg.size < 4 || (jpeg[0].toInt() and 0xFF) != 0xFF || (jpeg[1].toInt() and 0xFF) != 0xD8) return jpeg

        val tail = ByteArrayOutputStream()
        val keptResources = ByteArrayOutputStream()
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if ((jpeg[pos].toInt() and 0xFF) != 0xFF) break
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0x01 || marker in 0xD0..0xD8) {
                tail.write(jpeg, pos, 2)
                pos += 2
                continue
            }
            if (marker == 0xDA || marker == 0xD9) break
            val len = u16(jpeg, pos + 2)
            if (len < 2 || pos + 2 + len > jpeg.size) break
            if (marker == 0xED) {
                val payload = jpeg.copyOfRange(pos + 4, pos + 2 + len)
                keptResources.write(collectOtherResources(payload))
            } else {
                tail.write(jpeg, pos, 2 + len)
            }
            pos += 2 + len
        }
        // SOS 之后是熵编码数据，整段原样保留
        if (pos < jpeg.size) tail.write(jpeg, pos, jpeg.size - pos)

        val app13 = buildApp13(profile, keptResources.toByteArray()) ?: return jpeg
        val tailBytes = tail.toByteArray()

        // APP0(JFIF) 优先放在最前，兼容对段顺序挑剔的老解析器
        var app0Len = 0
        if (tailBytes.size >= 4 &&
            (tailBytes[0].toInt() and 0xFF) == 0xFF &&
            (tailBytes[1].toInt() and 0xFF) == 0xE0
        ) {
            app0Len = 2 + u16(tailBytes, 2)
        }

        val out = ByteArrayOutputStream(jpeg.size + app13.size)
        out.write(0xFF)
        out.write(0xD8)
        if (app0Len > 0) out.write(tailBytes, 0, app0Len)
        out.write(app13)
        out.write(tailBytes, app0Len, tailBytes.size - app0Len)
        return out.toByteArray()
    }

    /** 从已有 APP13 payload 里挑出非 IPTC 资源，原样拼回去。 */
    private fun collectOtherResources(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        if (payload.size < PHOTOSHOP_HEADER.size) return out.toByteArray()
        for (i in PHOTOSHOP_HEADER.indices) {
            if (payload[i] != PHOTOSHOP_HEADER[i]) return out.toByteArray()
        }
        var i = PHOTOSHOP_HEADER.size
        while (i + 7 <= payload.size) {
            if (payload[i] != '8'.code.toByte() ||
                payload[i + 1] != 'B'.code.toByte() ||
                payload[i + 2] != 'I'.code.toByte() ||
                payload[i + 3] != 'M'.code.toByte()
            ) break
            val id = u16(payload, i + 4)
            val nameLen = payload[i + 6].toInt() and 0xFF
            var nameEnd = i + 7 + nameLen
            if ((1 + nameLen) % 2 == 1) nameEnd += 1
            if (nameEnd + 4 > payload.size) break
            val size = i32(payload, nameEnd)
            if (size < 0) break
            val dataStart = nameEnd + 4
            val dataEnd = dataStart + size
            if (dataEnd > payload.size) break
            val blockEnd = dataEnd + if (size % 2 == 1) 1 else 0
            if (blockEnd > payload.size) break
            if (id != 0x0404) out.write(payload, i, blockEnd - i)
            i = blockEnd
        }
        return out.toByteArray()
    }

    private fun buildApp13(profile: Profile, keep: ByteArray): ByteArray? {
        val body = ByteArrayOutputStream()
        body.write(PHOTOSHOP_HEADER)
        body.write(keep)
        body.write(buildResource(0x0404, buildIim(profile)))
        val raw = body.toByteArray()
        if (raw.size + 2 > 0xFFFF) return null
        val out = ByteArrayOutputStream(raw.size + 4)
        out.write(0xFF)
        out.write(0xED)
        val len = raw.size + 2
        out.write((len shr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(raw)
        return out.toByteArray()
    }

    private fun buildIim(profile: Profile): ByteArray {
        val out = ByteArrayOutputStream()
        // 1:90 CodedCharacterSet = ESC % G，声明后面是 UTF-8
        out.write(0x1C)
        out.write(0x01)
        out.write(90)
        out.write(0)
        out.write(3)
        out.write(0x1B)
        out.write(0x25)
        out.write(0x47)

        fun dataset(id: Int, text: String) {
            val data = text.toByteArray(Charsets.UTF_8)
            if (data.isEmpty() || data.size > 32000) return
            out.write(0x1C)
            out.write(0x02)
            out.write(id)
            out.write((data.size shr 8) and 0xFF)
            out.write(data.size and 0xFF)
            out.write(data)
        }

        profile.keywords.map { it.trim() }.filter { it.isNotEmpty() }.take(16).forEach { dataset(25, it) }
        if (profile.artist.isNotBlank()) dataset(80, profile.artist.trim())
        if (profile.credit.isNotBlank()) dataset(110, profile.credit.trim())
        if (profile.copyright.isNotBlank()) dataset(116, profile.copyright.trim())
        return out.toByteArray()
    }

    /** 8BIM 资源块：签名 + 2 字节 id + Pascal 名字（此处为空）+ 4 字节长度 + 数据（奇数补 1 字节）。 */
    private fun buildResource(id: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("8BIM".toByteArray(Charsets.US_ASCII))
        out.write((id shr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(0)
        out.write(0)
        out.write((data.size shr 24) and 0xFF)
        out.write((data.size shr 16) and 0xFF)
        out.write((data.size shr 8) and 0xFF)
        out.write(data.size and 0xFF)
        out.write(data)
        if (data.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun i32(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
}
