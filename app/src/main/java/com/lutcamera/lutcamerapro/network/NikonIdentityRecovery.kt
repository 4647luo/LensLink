package com.lutcamera.lutcamerapro.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.ZipInputStream

/** Finds the initiator identity from a diagnostic archive containing an accepted PTP/IP handshake. */
internal object NikonIdentityRecovery {
    private val request = Regex("""([0-9a-f]{8})/command TX_WRITTEN bytes=54 type=1\b.*\bhex=([0-9a-f ]+)""")
    private val accepted = Regex("""([0-9a-f]{8})/command RX bytes=\d+ type=2\b""")

    fun fromArchive(input: InputStream): UUID {
        val requests = mutableMapOf<String, UUID>()
        val acknowledgements = mutableListOf<String>()
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > 12) throw IOException("日志 ZIP 文件过大")
                if (Regex("""trace-[0-3]\.txt""").matches(entry.name)) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 4_500_000) throw IOException("日志文件过大")
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name()).lineSequence().forEach { line ->
                        request.find(line)?.let { match ->
                            val packet = match.groupValues[2].trim().split(' ')
                                .mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
                            if (packet.size == 54 && packet.copyOfRange(24, 50)
                                    .toString(Charsets.UTF_16LE) == "LutCameraPro\u0000") {
                                val guid = ByteBuffer.wrap(packet, 8, 16)
                                requests[match.groupValues[1]] = UUID(guid.long, guid.long)
                            }
                        }
                        accepted.find(line)?.let { acknowledgements += it.groupValues[1] }
                    }
                }
                zip.closeEntry()
            }
        }
        return acknowledgements.firstNotNullOfOrNull { requests[it] }
            ?: throw IOException("这份日志里没有找到成功握手的 LutCameraPro 身份；请选择以前成功连接时导出的 ZIP")
    }
}
