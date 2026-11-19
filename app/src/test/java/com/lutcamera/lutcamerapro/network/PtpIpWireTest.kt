package com.lutcamera.lutcamerapro.network

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class PtpIpWireTest {
    @Test fun z30EmptyAccessDeniedDoesNotDesynchronizeNextTransaction() {
        val input = ByteArrayInputStream(start(42,0) + response(42,0x200f) + response(43))
        val output = ByteArrayOutputStream()
        assertEquals(0x200f, PtpIpWire.transaction(input, output, 42, 0x1008).response.code)
        assertEquals(0x2001, PtpIpWire.transaction(input, output, 43, 0x90c8).response.code)
    }
    @Test(expected = IOException::class) fun nonemptyTruncatedErrorStillRejected() {
        PtpIpWire.transaction(ByteArrayInputStream(start(42,5) + response(42,0x200f)), ByteArrayOutputStream(), 42, 0x1008)
    }
    @Test fun pairingCodeKeepsLeadingZero() {
        assertEquals("0123", PtpIpWire.pairingCode(byteArrayOf(8,8,0,1,2,3)))
    }
    @Test(expected = IOException::class) fun pairingCodeRejectsUnexpectedEncoding() {
        PtpIpWire.pairingCode(byteArrayOf(48,49,50,51))
    }
    @Test(expected = IOException::class) fun pairingCodeRejectsTruncatedReply() {
        PtpIpWire.pairingCode(byteArrayOf(1,2,3))
    }
    @Test fun observedZ30LimitedProfileDoesNotPermitAutomaticLiveView() {
        assertTrue(PtpIpWire.restrictedNikonProfile(setOf(0x1001, 0x1002, 0x1003, 0x952b, 0x935a)))
        assertFalse(PtpIpWire.restrictedNikonProfile(setOf(0x1001, 0x952b, 0x935a, 0x9201, 0x9203)))
        assertFalse(PtpIpWire.restrictedNikonProfile(emptySet()))
    }
    @Test fun deviceInfoReadsModelAndCapabilities() {
        val b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        fun text(value: String) { b.put((value.length + 1).toByte()); value.forEach { b.putShort(it.code.toShort()) }; b.putShort(0) }
        b.putShort(100); b.putInt(10); b.putShort(100); text("Nikon"); b.putShort(0)
        b.putInt(1); b.putShort(0x9203.toShort())
        b.putInt(0)
        b.putInt(1); b.putShort(0xd1bc.toShort())
        b.putInt(0); b.putInt(0)
        text("Nikon"); text("Z 30"); text("1.00"); text("test")
        val info = PtpIpWire.deviceInfo(b.array().copyOf(b.position()))
        assertEquals("Z 30", info.model); assertEquals("1.00", info.firmware)
        assertTrue(0x9203 in info.operations); assertTrue(0xd1bc in info.properties)
    }
    @Test(expected = IOException::class) fun truncatedDeviceInfoRejected() {
        PtpIpWire.deviceInfo(byteArrayOf(1, 2))
    }
    private fun payload(size: Int, write: ByteBuffer.() -> Unit) =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(write).array()
    private fun response(id: Int = 42, code: Int = 0x2001) =
        PtpIpWire.packet(7, payload(6) { putShort(code.toShort()); putInt(id) })
    private fun start(id: Int, size: Long) = PtpIpWire.packet(9, payload(12) { putInt(id); putLong(size) })
    private fun data(id: Int, type: Int, bytes: ByteArray) = PtpIpWire.packet(type, payload(4 + bytes.size) { putInt(id); put(bytes) })

    @Test fun responseDoesNotConfuseTransactionWithResult() {
        val r = PtpIpWire.response(response())
        assertEquals(0x2001, r.code); assertEquals(42, r.transaction)
    }
    @Test fun objectAddedUsesFirstParameterAtOffsetFourteen() {
        val event = PtpIpWire.packet(8, payload(10) { putShort(0x4002); putInt(42); putInt(123) })
        val r = PtpIpWire.response(event, 8)
        assertEquals(18, event.size); assertEquals(0x4002, r.code); assertEquals(123, r.parameters[0])
    }
    @Test fun receivesFragmentedDataAndDrainsResponse() {
        val all = start(42, 5) + data(42, 10, byteArrayOf(1,2)) + data(42, 12, byteArrayOf(3,4,5)) + response()
        val fragmented = object : ByteArrayInputStream(all) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 1))
        }
        val result = PtpIpWire.transaction(fragmented, ByteArrayOutputStream(), 42, 0x9203)
        assertArrayEquals(byteArrayOf(1,2,3,4,5), result.data)
        assertEquals(0, fragmented.available())
    }
    @Test fun busyIsACompleteResponseNotATransportFailure() {
        val result = PtpIpWire.transaction(ByteArrayInputStream(response(code=0x2019)), ByteArrayOutputStream(), 42, 0x9203)
        assertEquals(0x2019, result.response.code); assertEquals(0, result.data.size)
    }
    @Test(expected=IOException::class) fun mismatchedTransactionIsRejected() {
        PtpIpWire.transaction(ByteArrayInputStream(response(id=43)), ByteArrayOutputStream(), 42, 0x9203)
    }
    @Test(expected=EOFException::class) fun truncatedBodyCannotBecomeNextPacket() {
        PtpIpWire.read(ByteArrayInputStream(response().dropLast(1).toByteArray()))
    }
    @Test(expected=IOException::class) fun oversizedLengthRejectedBeforeAllocation() {
        PtpIpWire.read(ByteArrayInputStream(payload(8) { putInt(Int.MAX_VALUE); putInt(12) }))
    }
    @Test(expected=IOException::class) fun declaredFrameLimitIsEnforced() {
        PtpIpWire.transaction(ByteArrayInputStream(start(42, 99999)), ByteArrayOutputStream(), 42, 0x9203, maxData=100)
    }
    @Test(expected=IOException::class) fun shortDataPhaseRejected() {
        val all = start(42, 5) + data(42, 12, byteArrayOf(1)) + response()
        PtpIpWire.transaction(ByteArrayInputStream(all), ByteArrayOutputStream(), 42, 0x9203)
    }
    @Test fun outgoingPropertyDataUsesDataOutAndKeepsResponseCode() {
        val out = ByteArrayOutputStream()
        PtpIpWire.transaction(ByteArrayInputStream(response()), out, 42, 0x1016, intArrayOf(0xd1bc), byteArrayOf(2))
        val input = ByteArrayInputStream(out.toByteArray())
        val command = PtpIpWire.read(input)
        assertEquals(2, PtpIpWire.le(command).getInt(8))
        assertEquals(9, PtpIpWire.type(PtpIpWire.read(input)))
        assertEquals(12, PtpIpWire.type(PtpIpWire.read(input)))
        assertEquals(0, input.available())
    }
    @Test fun writableCompressionDescriptorAndEnumsAreParsed() {
        val bytes = payload(16) { putShort(0xd1bc.toShort()); putShort(2); put(1); put(0); put(2); put(2); putShort(6); put(byteArrayOf(0,1,2,3,4,5)) }
        val d = PtpIpWire.numericProperty(bytes)
        assertTrue(d.writable); assertEquals(2L, d.current); assertArrayEquals(longArrayOf(0,1,2,3,4,5), d.values)
        bytes[4]=0
        assertFalse(PtpIpWire.numericProperty(bytes).writable)
    }
    @Test(expected=IOException::class) fun truncatedDescriptorRejectedInsteadOfPartialEnums() {
        PtpIpWire.numericProperty(payload(10) { putShort(0xd1bc.toShort()); putShort(2); put(1); put(0); put(0); put(2); putShort(6) })
    }
    @Test fun localhostCameraSupportsSuccessiveTransactionsAndPing() {
        ServerSocket(0).use { server ->
            var failure: Throwable? = null
            val worker = thread {
                try {
                    server.accept().use { camera ->
                        repeat(2) {
                            val request = PtpIpWire.read(camera.inputStream)
                            val id = PtpIpWire.le(request).getInt(14)
                            camera.outputStream.write(PtpIpWire.packet(13)); camera.outputStream.flush()
                            assertEquals(14, PtpIpWire.type(PtpIpWire.read(camera.inputStream)))
                            val result = start(id, 3) + data(id,12,byteArrayOf(7,8,9)) + response(id)
                            result.forEach { byte -> camera.outputStream.write(byte.toInt()) }
                            camera.outputStream.flush()
                        }
                    }
                } catch (e: Throwable) { failure=e }
            }
            Socket("127.0.0.1",server.localPort).use { client ->
                repeat(2) { i ->
                    val deadline = System.nanoTime()+3_000_000_000L
                    val result = PtpIpWire.transaction(client.inputStream,client.outputStream,i+1,0x9203) {
                        client.soTimeout=PtpIpWire.remainingMillis(deadline)
                    }
                    assertArrayEquals(byteArrayOf(7,8,9),result.data)
                }
            }
            worker.join(4000)
            assertFalse(worker.isAlive)
            failure?.let { throw AssertionError(it) }
        }
    }
}
