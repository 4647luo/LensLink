package com.lutcamera.lutcamerapro.network

import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bounded, persistent diagnostic recorder. Logging failures never break camera transport. */
internal class NikonTrace(private val directory: File, private val limit: Long = 4L * 1024 * 1024) {
    @Volatile var failure: String? = null
        private set
    private val started = System.nanoTime()
    private var sequence = 0L
    @Synchronized fun note(message: String) {
        try {
            directory.mkdirs()
            val current = File(directory, "trace-0.txt")
            if (current.length() >= limit) {
                File(directory, "trace-3.txt").delete()
                for (i in 2 downTo 0) {
                    val old = File(directory, "trace-$i.txt")
                    if (old.exists()) check(old.renameTo(File(directory, "trace-${i+1}.txt")))
                }
            }
            current.appendText("${Instant.now()} +${(System.nanoTime()-started)/1_000_000}ms #${++sequence} [${Thread.currentThread().name}] $message\n")
        } catch (e: Exception) { failure = e.toString() }
    }
    fun packet(channel: String, direction: String, bytes: ByteArray) {
        val summary = runCatching {
            val b = PtpIpWire.le(bytes)
            val type = PtpIpWire.type(bytes)
            val fields = when (type) {
                6 -> "opcode=0x${(b.getShort(12).toInt() and 65535).toString(16)} transaction=${b.getInt(14)} phase=${b.getInt(8)}"
                7, 8 -> "code=0x${(b.getShort(8).toInt() and 65535).toString(16)} transaction=${b.getInt(10)}"
                9 -> "transaction=${b.getInt(8)} declaredBytes=${b.getLong(12)}"
                10, 12 -> "transaction=${b.getInt(8)}"
                5 -> "initFailure=${b.getInt(8)}"
                else -> ""
            }
            "type=$type $fields"
        }.getOrElse { "partial/invalid packet: ${it.message}" }
        val type = if (bytes.size >= 8) PtpIpWire.le(bytes).getInt(4) else -1
        val cap = if ((type == 10 || type == 12) && bytes.size > 4096) 64 else 4096
        val hex = bytes.take(cap).joinToString(" ") { "%02x".format(it.toInt() and 255) }
        note("$channel $direction bytes=${bytes.size} $summary captured=${minOf(bytes.size,cap)} omitted=${(bytes.size-cap).coerceAtLeast(0)} hex=$hex")
    }
    @Synchronized fun export(output: OutputStream, environment: String) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write("Since 3.20: Live-view (0x9203) packet hex is sampled at transaction ID multiples of 30. Slow frames (>=250 ms), failed frame responses, transport errors, and per-second LIVE_METRICS remain logged. Other commands retain detailed logging. Receive/decode FPS are not display refresh FPS.\n".toByteArray())
            zip.write(("Nikon STA diagnostics v1\nTX_ATTEMPT = app writes to socket; TX_WRITTEN is not camera acknowledgement. RX = bytes returned to app. Camera acceptance requires its response code. Partial reads/errors are recorded. Control packets retain up to 4096 bytes; data packets above 4096 bytes retain only 64 bytes. Full image payloads are not the purpose of this log. Rolling retention: newest four ~4 MB files, trace-3 oldest. Identifiers and local IP addresses may appear. No hotspot password is collected.\nRecorder error: $failure\n$environment").toByteArray())
            zip.closeEntry()
            for (i in 3 downTo 0) {
                val file = File(directory, "trace-$i.txt")
                if (file.exists()) { zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }
        }
    }
}
