package com.lutcamera.lutcamerapro.network

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

class NikonTraceTest {
    @get:Rule val folder = TemporaryFolder()
    private fun entries(bytes: ByteArray): Map<String, String> {
        val result = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        return result
    }
    @Test fun exportRetainsPreviousProcessRecordsAndReadableResponse() {
        val dir = folder.newFolder()
        NikonTrace(dir).note("earlier-process")
        val next = NikonTrace(dir)
        next.packet("command", "RX", PtpIpWire.packet(7, ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort(0x2001).putInt(42).array()))
        val output = ByteArrayOutputStream()
        next.export(output, "network-test")
        val files = entries(output.toByteArray())
        assertTrue(files.getValue("trace-0.txt").contains("earlier-process"))
        assertTrue(files.getValue("trace-0.txt").contains("code=0x2001 transaction=42"))
        assertTrue(files.getValue("README.txt").contains("network-test"))
    }
    @Test fun rotationKeepsNewestFourFiles() {
        val dir = folder.newFolder()
        val trace = NikonTrace(dir, 1)
        repeat(10) { trace.note("record-$it") }
        assertEquals(4, dir.listFiles()!!.size)
        val output = ByteArrayOutputStream(); trace.export(output, "test")
        val files = entries(output.toByteArray())
        assertTrue(files.getValue("trace-3.txt").contains("record-6"))
        assertTrue(files.getValue("trace-0.txt").contains("record-9"))
    }
    @Test fun partialReadAndOutgoingAttemptAreRecorded() {
        val events = mutableListOf<String>()
        val packet = PtpIpWire.packet(7, byteArrayOf(1,2,3,4,5,6))
        try {
            PtpIpWire.transaction(ByteArrayInputStream(packet.copyOf(11)), ByteArrayOutputStream(), 42, 0x1002,
                trace = { direction, _ -> events += direction })
            fail("Expected EOF")
        } catch (_: EOFException) { }
        assertEquals(listOf("TX_ATTEMPT", "TX_WRITTEN", "RX_PARTIAL_EOF"), events)
    }
    @Test fun failedWriteIsNeverMarkedWritten() {
        val events = mutableListOf<String>()
        val failing = object : OutputStream() { override fun write(value: Int) { throw IOException("test") } }
        try {
            PtpIpWire.transaction(ByteArrayInputStream(byteArrayOf()), failing, 1, 0x1001,
                trace = { direction, _ -> events += direction })
            fail("Expected write failure")
        } catch (_: IOException) { }
        assertEquals(listOf("TX_ATTEMPT"), events)
    }
    @Test fun largeImagePayloadIsBounded() {
        val dir = folder.newFolder(); val trace = NikonTrace(dir)
        trace.packet("command", "RX", PtpIpWire.packet(12, ByteArray(10000)))
        val text = File(dir, "trace-0.txt").readText()
        assertTrue(text.contains("captured=64 omitted=9944"))
        assertTrue(text.length < 1000)
    }
}
