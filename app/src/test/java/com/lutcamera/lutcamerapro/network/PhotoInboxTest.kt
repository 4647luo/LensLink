package com.lutcamera.lutcamerapro.network

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

class PhotoInboxTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val jpeg = byteArrayOf(-1, -40, 42, 12, -1, -39)

    @Test fun originalAndNameSurviveReopening() {
        val directory = temporaryFolder.newFolder("inbox")
        val entry = PhotoInbox(directory).stage("DSC_0123.JPG", jpeg)
        assertNotEquals(entry.fileName, entry.file.name)
        assertEquals("${entry.id}.jpg", entry.file.name)
        val recovered = PhotoInbox(directory).pending().single()
        assertEquals(entry, recovered)
        assertArrayEquals(jpeg, recovered.file.readBytes())
    }

    @Test fun preservesPartialGallerySuccessAcrossRetriesAndRestart() {
        val directory = temporaryFolder.newFolder("inbox")
        val inbox = PhotoInbox(directory)
        val entry = inbox.stage("DSC_0124.JPG", jpeg)
        inbox.recordSaved(entry, originalUri = "content://photos/original/1")

        val reopened = PhotoInbox(directory)
        val partial = reopened.pending().single()
        assertEquals("content://photos/original/1", partial.originalUri)
        assertNull(partial.outputUri)
        reopened.recordSaved(partial, outputUri = "content://photos/lut/2")
        val saved = PhotoInbox(directory).pending().single()
        assertEquals("content://photos/original/1", saved.originalUri)
        assertEquals("content://photos/lut/2", saved.outputUri)
        assertArrayEquals(jpeg, saved.file.readBytes())
    }

    @Test fun traversalAndUnsafeNamesCannotBecomeStagedFiles() {
        val directory = temporaryFolder.newFolder("inbox")
        val inbox = PhotoInbox(directory)
        listOf("../outside.jpg", "..\\outside.jpg", "/tmp/photo.jpg", "C:\\photo.jpg", ".", "..", "", "bad\u0000.jpg", "bad\n.jpg", "bad:photo.jpg").forEach { name ->
            expectIllegalArgument { inbox.stage(name, jpeg) }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun staleEntryCannotErasePreviouslyRecordedSuccess() {
        val directory = temporaryFolder.newFolder("inbox")
        val inbox = PhotoInbox(directory)
        val entry = inbox.stage("DSC_0124.JPG", jpeg)
        inbox.recordSaved(entry, originalUri = "content://photos/original/1")
        inbox.recordSaved(entry, outputUri = "content://photos/lut/2")
        val saved = PhotoInbox(directory).pending().single()
        assertEquals("content://photos/original/1", saved.originalUri)
        assertEquals("content://photos/lut/2", saved.outputUri)
    }

    @Test fun externalEntriesAreRejectedAndNeverDeletedOrRewritten() {
        val directory = temporaryFolder.newFolder("inbox")
        val inbox = PhotoInbox(directory)
        val actual = inbox.stage("DSC_0125.JPG", jpeg)
        val outside = temporaryFolder.newFile("outside.jpg").apply { writeBytes(jpeg) }
        val forged = actual.copy(file = outside)
        expectIllegalArgument { inbox.recordSaved(forged, originalUri = "content://forged") }
        expectIllegalArgument { inbox.complete(forged) }
        expectIllegalArgument { inbox.recordSaved(actual.copy(id = "../outside")) }
        expectIllegalArgument { inbox.complete(actual.copy(fileName = "../outside.jpg")) }
        expectIllegalArgument { inbox.recordSaved(actual.copy(fileName = "different.jpg")) }
        assertArrayEquals(jpeg, outside.readBytes())
        assertEquals(actual, inbox.pending().single())
    }

    @Test fun completionRemovesOnlyOwnedOriginalAndMetadata() {
        val directory = temporaryFolder.newFolder("inbox")
        val inbox = PhotoInbox(directory)
        val first = inbox.stage("DSC_0126.JPG", jpeg)
        val second = inbox.stage("DSC_0127.JPG", jpeg)
        val unrelated = File(directory, "notes.txt").apply { writeText("keep") }
        inbox.complete(first)
        inbox.complete(first)
        assertFalse(first.file.exists())
        assertFalse(File(directory, "${first.id}.properties").exists())
        assertEquals(listOf(second), PhotoInbox(directory).pending())
        assertEquals("keep", unrelated.readText())
    }

    @Test fun missingMetadataRecoversOriginalWithoutUsingUntrustedName() {
        val directory = temporaryFolder.newFolder("inbox")
        val inbox = PhotoInbox(directory)
        val entry = inbox.stage("DSC_0128.JPG", jpeg)
        assertTrue(File(directory, "${entry.id}.properties").delete())
        val reopened = PhotoInbox(directory)
        val recovered = reopened.pending().single()
        assertEquals(entry.id, recovered.id)
        assertEquals("Nikon-recovered-${entry.id}.jpg", recovered.fileName)
        assertArrayEquals(jpeg, recovered.file.readBytes())
        reopened.recordSaved(recovered, originalUri = "content://recovered/1")
        assertEquals("content://recovered/1", PhotoInbox(directory).pending().single().originalUri)
    }

    @Test fun corruptMetadataPreservesOriginalAndCanBeRepairedByRetry() {
        val directory = temporaryFolder.newFolder("inbox")
        val inbox = PhotoInbox(directory)
        val entry = inbox.stage("DSC_0129.JPG", jpeg)
        val metadata = File(directory, "${entry.id}.properties")
        metadata.writeText("version=1\nid=${entry.id}\nfileName=../outside.jpg\ncompleted=true\n")
        val recovered = PhotoInbox(directory).pending().single()
        assertArrayEquals(jpeg, entry.file.readBytes())
        assertFalse(recovered.fileName.contains('/'))
        inbox.recordSaved(recovered, outputUri = "content://recovered/2")
        assertEquals("content://recovered/2", PhotoInbox(directory).pending().single().outputUri)
    }

    @Test fun malformedPropertiesNeverDeleteOriginal() {
        val directory = temporaryFolder.newFolder("inbox")
        val entry = PhotoInbox(directory).stage("DSC_0130.JPG", jpeg)
        File(directory, "${entry.id}.properties").writeText("fileName=\\uNOTHEX\n")
        val recovered = PhotoInbox(directory).pending().single()
        assertArrayEquals(jpeg, recovered.file.readBytes())
    }

    @Test fun durableCompletionMarkerSkipsReplayAndFinishesInterruptedCleanup() {
        val directory = temporaryFolder.newFolder("inbox")
        val entry = PhotoInbox(directory).stage("DSC_0131.JPG", jpeg)
        val metadata = File(directory, "${entry.id}.properties")
        val values = Properties().apply { metadata.inputStream().use(::load) }
        values.setProperty("completed", "true")
        metadata.outputStream().use { values.store(it, "simulate completed before deletion") }
        assertTrue(entry.file.exists())
        assertTrue(PhotoInbox(directory).pending().isEmpty())
        assertFalse(entry.file.exists())
        assertFalse(metadata.exists())
    }

    @Test fun missingOriginalDoesNotCauseMetadataToBeSilentlyDeleted() {
        val directory = temporaryFolder.newFolder("inbox")
        val entry = PhotoInbox(directory).stage("DSC_0132.JPG", jpeg)
        assertTrue(entry.file.delete())
        assertTrue(PhotoInbox(directory).pending().isEmpty())
        assertTrue(File(directory, "${entry.id}.properties").exists())
    }

    private fun expectIllegalArgument(action: () -> Unit) {
        try {
            action()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected: callers cannot select arbitrary paths for inbox operations.
        }
    }
}
