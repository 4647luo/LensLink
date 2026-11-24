package com.lutcamera.lutcamerapro.network

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** Durable original JPEGs awaiting gallery/LUT processing. No Android or LUT state is stored here. */
class PhotoInbox(private val directory: File) {
    data class Entry(
        val id: String,
        val fileName: String,
        val file: File,
        val originalUri: String? = null,
        val outputUri: String? = null
    )

    private val root = directory.canonicalFile

    init {
        ensureDirectory()
    }

    /** Publish the synced original first, so an interrupted metadata write never loses the photo. */
    @Synchronized
    fun stage(fileName: String, bytes: ByteArray): Entry {
        requireSafeName(fileName)
        require(bytes.isNotEmpty()) { "Photo data is empty" }
        require(bytes.size <= MAX_PHOTO_BYTES) { "Photo is larger than ${MAX_PHOTO_BYTES / 1024 / 1024}MB" }
        ensureDirectory()
        val id = UUID.randomUUID().toString()
        val file = photoFile(id)
        val temporary = File(root, "$id.jpg.part")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            atomicReplace(temporary, file)
        } finally {
            // Only our unpublished, UUID-named temporary file is eligible for cleanup.
            temporary.delete()
        }
        val entry = Entry(id, fileName, file)
        writeMetadata(entry, completed = false)
        return entry
    }

    /**
     * FTP 回传直接落盘后的移交：不再把 200MB 字节数组复制一遍，
     * 只用流把临时文件搬进 inbox，成功原子改名后删掉源临时文件。
     */
    @Synchronized
    fun stage(fileName: String, source: File): Entry {
        requireSafeName(fileName)
        require(source.isFile) { "Photo source is missing" }
        require(source.length() in 1..MAX_PHOTO_BYTES) {
            "Photo is larger than ${MAX_PHOTO_BYTES / 1024 / 1024}MB"
        }
        ensureDirectory()
        val id = UUID.randomUUID().toString()
        val file = photoFile(id)
        val temporary = File(root, "$id.jpg.part")
        try {
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    com.lutcamera.lutcamerapro.util.BoundedIo.copyWithLimit(input, output, MAX_PHOTO_BYTES)
                    output.fd.sync()
                }
            }
            atomicReplace(temporary, file)
            source.delete()
        } finally {
            temporary.delete()
        }
        val entry = Entry(id, fileName, file)
        writeMetadata(entry, completed = false)
        return entry
    }

    /** Missing or invalid metadata recovers with a safe name and preserves the original bytes. */
    @Synchronized
    fun pending(): List<Entry> {
        ensureDirectory()
        val children = root.listFiles() ?: throw IOException("Cannot list photo inbox")
        val ids = children.mapNotNull { file ->
            when {
                file.name.endsWith(".jpg") -> file.name.removeSuffix(".jpg")
                file.name.endsWith(".properties") -> file.name.removeSuffix(".properties")
                else -> null
            }?.takeIf(::isId)
        }.toSet()
        return ids.mapNotNull { id ->
            val file = photoFile(id)
            if (!isDirectFile(file)) return@mapNotNull null
            val metadata = readMetadata(id)
            if (metadata?.completed == true) {
                cleanupCompleted(id)
                return@mapNotNull null
            }
            if (!file.isFile) return@mapNotNull null
            metadata?.entry ?: Entry(id, recoveredName(id), file)
        }.sortedWith(compareBy<Entry> { it.file.lastModified() }.thenBy { it.id })
    }

    /** Remember each successful gallery write before attempting the remaining output. */
    @Synchronized
    fun recordSaved(
        entry: Entry,
        originalUri: String? = entry.originalUri,
        outputUri: String? = entry.outputUri
    ): Entry {
        requireOwned(entry)
        require(entry.file.isFile) { "Photo is no longer in this inbox" }
        val existing = readMetadata(entry.id)
        require(existing?.completed != true) { "Photo has already been completed" }
        require(existing == null || existing.entry.fileName == entry.fileName) { "Photo name does not match inbox metadata" }
        // Progress is monotonic: a stale caller cannot erase an already recorded gallery URI.
        val saved = entry.copy(
            originalUri = originalUri ?: existing?.entry?.originalUri,
            outputUri = outputUri ?: existing?.entry?.outputUri
        )
        writeMetadata(saved, completed = false)
        return saved
    }

    /** A durable completion marker prevents replay even if deletion is interrupted or fails. */
    @Synchronized
    fun complete(entry: Entry) {
        requireOwned(entry)
        val existing = readMetadata(entry.id)
        require(existing == null || existing.entry.fileName == entry.fileName) { "Photo name does not match inbox metadata" }
        if (!entry.file.exists() && !metadataFile(entry.id).exists()) return
        writeMetadata(existing?.entry ?: entry, completed = true)
        cleanupCompleted(entry.id)
    }

    private data class Metadata(val entry: Entry, val completed: Boolean)

    private fun ensureDirectory() {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create photo inbox")
        if (!root.isDirectory) throw IOException("Photo inbox is not a directory")
        if (root.canonicalFile != root) throw IOException("Photo inbox directory has moved")
    }

    private fun requireOwned(entry: Entry) {
        require(isId(entry.id)) { "Invalid inbox photo ID" }
        requireSafeName(entry.fileName)
        val expected = photoFile(entry.id)
        require(isDirectFile(expected) && entry.file.canonicalFile == expected) { "Photo does not belong to this inbox" }
        require(isDirectFile(metadataFile(entry.id))) { "Metadata does not belong to this inbox" }
    }

    private fun isDirectFile(file: File): Boolean =
        file.absoluteFile.parentFile == root && file.canonicalFile == file.absoluteFile

    private fun photoFile(id: String) = File(root, "$id.jpg")
    private fun metadataFile(id: String) = File(root, "$id.properties")
    private fun recoveredName(id: String) = "Nikon-recovered-$id.jpg"

    private fun readMetadata(id: String): Metadata? {
        val file = metadataFile(id)
        if (!isDirectFile(file) || !file.isFile) return null
        return try {
            val values = Properties().apply { file.inputStream().use(::load) }
            if (values.getProperty("version") != "1" || values.getProperty("id") != id) return null
            val name = values.getProperty("fileName") ?: return null
            requireSafeName(name)
            val completed = when (values.getProperty("completed")) {
                "true" -> true
                "false" -> false
                else -> return null
            }
            Metadata(Entry(id, name, photoFile(id), values.getProperty("originalUri"), values.getProperty("outputUri")), completed)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun writeMetadata(entry: Entry, completed: Boolean) {
        val file = metadataFile(entry.id)
        require(isDirectFile(file)) { "Metadata does not belong to this inbox" }
        val values = Properties().apply {
            setProperty("version", "1")
            setProperty("id", entry.id)
            setProperty("fileName", entry.fileName)
            setProperty("completed", completed.toString())
            entry.originalUri?.let { setProperty("originalUri", it) }
            entry.outputUri?.let { setProperty("outputUri", it) }
        }
        val temporary = File(root, "${entry.id}.${UUID.randomUUID()}.properties.part")
        try {
            FileOutputStream(temporary).use { stream ->
                values.store(stream, "Nikon photo inbox")
                stream.fd.sync()
            }
            atomicReplace(temporary, file)
        } finally {
            temporary.delete()
        }
    }

    private fun cleanupCompleted(id: String) {
        val file = photoFile(id)
        val metadata = metadataFile(id)
        if (!isDirectFile(file) || !isDirectFile(metadata)) return
        // Retain the completion marker if the photo cannot yet be removed.
        if (!file.exists() || file.delete()) metadata.delete()
    }

    private fun atomicReplace(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isId(id: String) = ID_PATTERN.matches(id)

    private fun requireSafeName(name: String) {
        require(name.isNotBlank() && name.length <= 240 && name != "." && name != ".." &&
            name.last() != '.' && name.last() != ' ' &&
            name.none { it.code < 32 || it in "<>:\"/\\|?*" }) { "Invalid photo file name" }
    }

    private companion object {
        const val MAX_PHOTO_BYTES = 200L * 1024 * 1024
        val ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
