package com.lutcamera.lutcamerapro.gpu

import org.junit.Assert.*
import org.junit.Test

class PhotoExportPolicyTest {
    @Test fun fullResolutionIsNeverAffectedByPreviewQuality() {
        assertEquals(PhotoExportPolicy.Size(5568, 3712), PhotoExportPolicy.targetSize(5568, 3712, PhotoExportPolicy.ORIGINAL_RESOLUTION))
        assertEquals(PhotoExportPolicy.Size(5568, 3712), PhotoExportPolicy.targetSize(5568, 3712, "unknown legacy preference"))
    }

    @Test fun exportsFitLandscapeAndPortraitWithoutStretchingOrUpscaling() {
        assertEquals(PhotoExportPolicy.Size(1620, 1080), PhotoExportPolicy.targetSize(6000, 4000, "高清 (1080P)"))
        assertEquals(PhotoExportPolicy.Size(1080, 1620), PhotoExportPolicy.targetSize(4000, 6000, "高清 (1080P)"))
        assertEquals(PhotoExportPolicy.Size(1280, 720), PhotoExportPolicy.targetSize(3840, 2160, "流畅 (720P)"))
        assertEquals(PhotoExportPolicy.Size(600, 400), PhotoExportPolicy.targetSize(600, 400, "高清 (1080P)"))
    }

    @Test fun decodeSamplingKeepsEnoughPixelsForTheRequestedExport() {
        assertEquals(2, PhotoExportPolicy.sampleSize(5568, 3712, 1620, 1080))
        assertEquals(1, PhotoExportPolicy.sampleSize(5568, 3712, 5568, 3712))
        assertEquals(4, PhotoExportPolicy.sampleSize(8000, 6000, 1080, 810))
    }

    @Test fun selectedSaveStrategyControlsBothIndependentOutputs() {
        assertEquals(PhotoExportPolicy.Plan(true, false), PhotoExportPolicy.plan("ORIGINAL_ONLY"))
        assertEquals(PhotoExportPolicy.Plan(false, true), PhotoExportPolicy.plan("LUT_ONLY"))
        assertEquals(PhotoExportPolicy.Plan(true, true), PhotoExportPolicy.plan("DUAL"))
    }

    @Test fun cameraPathsCannotEscapeAndOriginalJpegNameIsPreserved() {
        assertEquals("DSC_1234.JPG", PhotoExportPolicy.originalName("../../DSC_1234.JPG", 42))
        assertEquals("DSC_1234.jpeg", PhotoExportPolicy.originalName("C:\\DCIM\\DSC_1234.jpeg", 42))
        assertEquals("a_b.JPG", PhotoExportPolicy.originalName("a:b.JPG", 42))
        assertEquals("ORIGINAL_42.jpg", PhotoExportPolicy.originalName(null, 42))
    }

    @Test fun rejectsRawTruncatedAndMislabeledInputBeforePublishing() {
        val jpeg = byteArrayOf(-1, -40, 1, 2, -1, -39)
        assertTrue(PhotoExportPolicy.isSupportedJpeg(jpeg, "DSC.JPG"))
        assertTrue(PhotoExportPolicy.isSupportedJpeg(jpeg, null))
        assertFalse(PhotoExportPolicy.isSupportedJpeg(jpeg, "DSC.NEF"))
        assertFalse(PhotoExportPolicy.isSupportedJpeg(jpeg.dropLast(1).toByteArray(), "DSC.JPG"))
        assertFalse(PhotoExportPolicy.isSupportedJpeg(byteArrayOf(73, 73, 42, 0), "DSC.JPG"))
    }
}
