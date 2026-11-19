package com.lutcamera.lutcamerapro.network

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PTP/IP packet framing. Offsets include the eight-byte IP header, not a USB container. */
internal object PtpIpWire {
    const val MAX_DATA = 128 * 1024 * 1024
    fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun packet(type: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(8 + payload.size).putInt(type).put(payload).array()

    fun read(input: InputStream, beforeRead: () -> Unit = {}, trace: (String, ByteArray) -> Unit = { _, _ -> }): ByteArray {
        fun fully(bytes: ByteArray, start: Int, end: Int) {
            var pos = start
            while (pos < end) {
                beforeRead()
                val count = try { input.read(bytes, pos, end - pos) } catch (e: IOException) {
                    trace("RX_PARTIAL_ERROR ${e.javaClass.simpleName}: ${e.message}", bytes.copyOf(pos)); throw e
                }
                if (count <= 0) { trace("RX_PARTIAL_EOF", bytes.copyOf(pos)); throw EOFException("PTP/IP connection closed mid-packet") }
                pos += count
            }
        }
        val header = ByteArray(8)
        fully(header, 0, 8)
        val length = le(header).int
        if (length !in 8..MAX_DATA) { trace("RX_INVALID_HEADER", header); throw IOException("Invalid PTP/IP packet length: $length") }
        return ByteArray(length).also {
            header.copyInto(it)
            fully(it, 8, length)
            trace("RX", it)
        }
    }

    fun type(packet: ByteArray): Int {
        if (packet.size < 8 || le(packet).int != packet.size) throw IOException("Invalid PTP/IP header")
        return le(packet).getInt(4)
    }

    data class Response(val code: Int, val transaction: Int, val parameters: IntArray)
    fun response(packet: ByteArray, expectedType: Int = 7): Response {
        if (type(packet) != expectedType || packet.size < 14 || (packet.size - 14) % 4 != 0)
            throw IOException("Malformed PTP/IP response/event")
        val b = le(packet)
        return Response(b.getShort(8).toInt() and 0xffff, b.getInt(10),
            IntArray((packet.size - 14) / 4) { b.getInt(14 + it * 4) })
    }

    data class Result(val response: Response, val data: ByteArray)

    /** One complete transaction; the caller must serialize access and close on framing/IO errors. */
    fun transaction(input: InputStream, output: OutputStream, id: Int, opcode: Int,
                    params: IntArray = intArrayOf(), outgoing: ByteArray? = null,
                    maxData: Int = MAX_DATA, trace: (String, ByteArray) -> Unit = { _, _ -> }, beforeRead: () -> Unit = {}): Result {
        fun send(bytes: ByteArray) {
            trace("TX_ATTEMPT", bytes)
            output.write(bytes)
            trace("TX_WRITTEN", bytes)
        }
        val request = ByteBuffer.allocate(10 + 4 * params.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(if (outgoing == null) 1 else 2).putShort(opcode.toShort()).putInt(id)
        params.forEach { request.putInt(it) }
        send(packet(6, request.array()))
        if (outgoing != null) {
            send(packet(9, ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(id).putLong(outgoing.size.toLong()).array()))
            send(packet(12, ByteBuffer.allocate(4 + outgoing.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(id).put(outgoing).array()))
        }
        output.flush()
        val data = ByteArrayOutputStream()
        var expected: Long? = null
        var ended = false
        while (true) {
            val p = read(input, beforeRead, trace)
            val b = le(p)
            when (type(p)) {
                7 -> {
                    val r = response(p)
                    if (r.transaction != id) throw IOException("PTP/IP transaction mismatch")
                    // Z30 may send StartData(0), then an error response without EndData.
                    val emptyError = r.code != 0x2001 && expected == 0L && data.size() == 0
                    if (expected != null && !emptyError && (!ended || data.size().toLong() != expected))
                        throw IOException("Incomplete PTP/IP data phase")
                    return Result(r, data.toByteArray())
                }
                9 -> {
                    if (p.size != 20 || b.getInt(8) != id || expected != null)
                        throw IOException("Invalid PTP/IP StartData")
                    val size = b.getLong(12)
                    if (size < 0 || size > maxData) throw IOException("PTP/IP data exceeds limit: $size")
                    expected = size
                }
                10, 12 -> {
                    if (p.size < 12 || b.getInt(8) != id || expected == null || ended)
                        throw IOException("Invalid PTP/IP data packet")
                    if (data.size().toLong() + p.size - 12 > expected)
                        throw IOException("PTP/IP data exceeds declared length")
                    data.write(p, 12, p.size - 12)
                    ended = type(p) == 12
                }
                13 -> { send(packet(14)); output.flush() }
                else -> throw IOException("Unexpected PTP/IP packet type ${type(p)}")
            }
        }
    }

    fun remainingMillis(deadlineNanos: Long): Int {
        val left = deadlineNanos - System.nanoTime()
        if (left <= 0) throw SocketTimeoutException("PTP/IP transaction deadline exceeded")
        return ((left + 999_999) / 1_000_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    data class NumericProperty(val code: Int, val type: Int, val writable: Boolean,
                               val current: Long, val values: LongArray,
                               val min: Long, val max: Long, val step: Long)
    fun numericProperty(data: ByteArray): NumericProperty {
        if (data.size < 5) throw IOException("Truncated property descriptor")
        val b = le(data)
        val code = b.short.toInt() and 0xffff
        val type = b.short.toInt() and 0xffff
        val writable = b.get().toInt() == 1
        fun value(): Long = when (type) {
            1 -> b.get().toLong()
            2 -> b.get().toLong() and 0xff
            3 -> b.short.toLong()
            4 -> b.short.toLong() and 0xffff
            5 -> b.int.toLong()
            6 -> b.int.toLong() and 0xffffffffL
            7, 8 -> b.long
            else -> throw IOException("Non-numeric/unsupported property type: $type")
        }
        try {
            value() // factory default
            val current = value()
            var values = longArrayOf()
            var min = 0L; var max = 0L; var step = 0L
            when (b.get().toInt()) {
                0 -> Unit
                1 -> { min = value(); max = value(); step = value() }
                2 -> { val count = b.short.toInt() and 0xffff; values = LongArray(count) { value() } }
                else -> throw IOException("Unknown property form")
            }
            return NumericProperty(code, type, writable, current, values, min, max, step)
        } catch (e: java.nio.BufferUnderflowException) {
            throw IOException("Truncated property descriptor", e)
        }
    }

    data class DeviceInfo(val manufacturer: String, val model: String, val firmware: String,
                          val serial: String, val operations: Set<Int>, val properties: Set<Int>)
    // Observed on Z30 V1.20 before full control is available. Do not infer unknown opcode semantics.
    fun restrictedNikonProfile(operations: Set<Int>): Boolean =
        0x952b in operations && 0x935a in operations && 0x9201 !in operations && 0x9203 !in operations
    fun pairingCode(data: ByteArray): String {
        if (data.size < 4) throw IOException("配对回复缺少四位验证码")
        val digits = data.takeLast(4).map { it.toInt() and 255 }
        if (digits.any { it !in 0..9 }) throw IOException("配对验证码格式与已知协议不符，已停止")
        return digits.joinToString("")
    }
    fun deviceInfo(data: ByteArray): DeviceInfo {
        val b = le(data)
        fun string(): String {
            val count = b.get().toInt() and 0xff
            if (count == 0) return ""
            val chars = CharArray(count) { b.short.toInt().toChar() }
            return String(chars).trimEnd('\u0000')
        }
        fun array(): Set<Int> {
            val count = b.int
            if (count < 0 || count > b.remaining()/2) throw IOException("Invalid device capability array")
            return (0 until count).map { b.short.toInt() and 0xffff }.toSet()
        }
        try {
            b.short; b.int; b.short; string(); b.short
            val ops = array(); array(); val props = array(); array(); array()
            return DeviceInfo(string(), string(), string(), string(), ops, props)
        } catch (e: java.nio.BufferUnderflowException) { throw IOException("Truncated device info", e) }
    }
}
