package com.lutcamera.lutcamerapro.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NikonIdentityRecoveryTest {
    private fun request(label: String, guid: UUID): String {
        val packet = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(54).putInt(1).order(ByteOrder.BIG_ENDIAN)
            .putLong(guid.mostSignificantBits).putLong(guid.leastSignificantBits)
            .put("LutCameraPro\u0000".toByteArray(Charsets.UTF_16LE))
            .order(ByteOrder.LITTLE_ENDIAN).putInt(0x10000).array()
        return "$label/command TX_WRITTEN bytes=54 type=1  captured=54 omitted=0 hex=" +
            packet.joinToString(" ") { "%02x".format(it.toInt() and 255) }
    }

    private fun archive(trace: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("trace-0.txt"))
            zip.write(trace.toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    @Test fun restoresOnlyAnAcceptedIdentity() {
        val rejected = UUID.randomUUID()
        val accepted = UUID.randomUUID()
        val trace = "${request("aaaaaaaa", rejected)}\n" +
            "aaaaaaaa/command RX bytes=12 type=5 initFailure=1\n" +
            "${request("bbbbbbbb", accepted)}\n" +
            "bbbbbbbb/command RX bytes=58 type=2 captured=58\n"
        assertEquals(accepted, NikonIdentityRecovery.fromArchive(ByteArrayInputStream(archive(trace))))
    }

    @Test fun rejectsAnArchiveWithoutSuccessfulHandshake() {
        val trace = "${request("aaaaaaaa", UUID.randomUUID())}\n" +
            "aaaaaaaa/command RX bytes=12 type=5 initFailure=1\n"
        assertThrows(IOException::class.java) {
            NikonIdentityRecovery.fromArchive(ByteArrayInputStream(archive(trace)))
        }
    }
}
