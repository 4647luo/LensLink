package com.lutcamera.lutcamerapro.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class BoundedIoTest {

    @Test
    fun `copies payload under the limit`() {
        val payload = ByteArray(1024) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val copied = BoundedIo.copyWithLimit(ByteArrayInputStream(payload), out, 4096)
        assertEquals(1024L, copied)
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun `copies empty stream as zero bytes`() {
        val out = ByteArrayOutputStream()
        assertEquals(0L, BoundedIo.copyWithLimit(ByteArrayInputStream(ByteArray(0)), out, 1024))
        assertTrue(out.toByteArray().isEmpty())
    }

    @Test
    fun `throws when payload exceeds limit`() {
        val out = ByteArrayOutputStream()
        try {
            BoundedIo.copyWithLimit(ByteArrayInputStream(ByteArray(5000)), out, 1024)
            fail("Expected IOException for oversized payload")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("上限"))
        }
    }

    @Test
    fun `exact limit boundary is accepted`() {
        val out = ByteArrayOutputStream()
        assertEquals(1024L, BoundedIo.copyWithLimit(ByteArrayInputStream(ByteArray(1024)), out, 1024))
    }

    @Test
    fun `one byte over limit is rejected`() {
        val out = ByteArrayOutputStream()
        try {
            BoundedIo.copyWithLimit(ByteArrayInputStream(ByteArray(1025)), out, 1024)
            fail("Expected IOException at limit + 1")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("上限"))
        }
    }

    @Test
    fun `readBytesWithLimit returns full payload`() {
        val payload = "连镜 LENS LINK".toByteArray(Charsets.UTF_8)
        assertArrayEquals(payload, BoundedIo.readBytesWithLimit(ByteArrayInputStream(payload), 4096))
    }

    @Test
    fun `readBytesWithLimit rejects oversized stream`() {
        try {
            BoundedIo.readBytesWithLimit(ByteArrayInputStream(ByteArray(2048)), 1024)
            fail("Expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("上限"))
        }
    }

    @Test
    fun `readUtf8WithLimit decodes utf8 payload`() {
        val text = "标题：尼康 Z50II · 测试 # 1"
        assertEquals(text, BoundedIo.readUtf8WithLimit(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), 4096))
    }

    @Test
    fun `non positive limit is rejected`() {
        try {
            BoundedIo.copyWithLimit(ByteArrayInputStream(ByteArray(1)), ByteArrayOutputStream(), 0)
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    @Test
    fun `propagates upstream io failure`() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("unreadable")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("unreadable")
        }
        try {
            BoundedIo.copyWithLimit(broken, ByteArrayOutputStream(), 1024)
            fail("Expected IOException")
        } catch (expected: IOException) {
            assertEquals("unreadable", expected.message)
        }
    }
}
