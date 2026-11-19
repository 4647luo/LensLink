package com.lutcamera.lutcamerapro.network

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NikonPhotoEventsTest {
    @Test fun parsesCardAndSdramEventsWithUnsignedHandles() {
        val data = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(2).putShort(0x4002).putInt(123).putShort(0xc101.toShort()).putInt(-65535).array()
        assertEquals(listOf(0x4002 to 123, 0xc101 to -65535), NikonPhotoEvents.parse(data))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedEventBatch() {
        NikonPhotoEvents.parse(byteArrayOf(2, 0, 2, 64, 1, 0, 0, 0))
    }
    @Test fun handlesEmptyEvents() { assertTrue(NikonPhotoEvents.parse(byteArrayOf(0, 0)).isEmpty()) }
    @Test fun filtersRawAndFoldersAndSanitizesJpegName() {
        val name = "../DSC_123.JPG\u0000".toByteArray(Charsets.UTF_16LE)
        val info = ByteBuffer.allocate(53 + name.size).order(ByteOrder.LITTLE_ENDIAN)
        info.putShort(4, 0x3801); info.put(52, (name.size / 2).toByte())
        System.arraycopy(name, 0, info.array(), 53, name.size)
        assertEquals("DSC_123.JPG", NikonPhotoEvents.jpegName(info.array()))
        info.putShort(4, 0x3001); assertNull(NikonPhotoEvents.jpegName(info.array()))
        info.putShort(4, 0xb101.toShort()); assertNull(NikonPhotoEvents.jpegName(info.array()))
    }
    @Test fun rejectsRawAndIncompleteJpeg() {
        assertTrue(NikonPhotoEvents.isJpeg(byteArrayOf(-1, -40, 0, -1, -39)))
        assertFalse(NikonPhotoEvents.isJpeg(byteArrayOf(-1, -40, 0)))
        assertFalse(NikonPhotoEvents.isJpeg(byteArrayOf(73, 73, 42, 0)))
    }
}
