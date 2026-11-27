package com.lutcamera.lutcamerapro.ui.douk

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * Live 图（iPhone 实况照片 / 小米动图 / Google Motion Photo）：
 * 本质上是一个 JPEG/HEIF，尾部追加了一段 MP4，XMP 里带 MotionPhoto / MicroVideo 标记。
 *
 * 图库列表里只做「廉价探测」——读文件头 128KB 找标记，结果按 stableKey 缓存；
 * 真正要播放时才把尾部视频切出来，并且先用 MediaMetadataRetriever 验一下能不能播。
 */
internal object LivePhoto {
    private val cache: MutableMap<String, Boolean> = Collections.synchronizedMap(HashMap())

    /** 已经切出来的尾部视频，进图时预抽一次，长按就不用再等 */
    private val videoCache: MutableMap<String, File> = Collections.synchronizedMap(HashMap())

    fun cachedVideo(key: String): File? =
        videoCache[key]?.takeIf { it.isFile && it.length() > 0 }

    /** XMP 里常见的动图标记，大小写不统一，统一按小写比较 */
    private val markers = arrayOf(
        "motionphoto",
        "microvideo",
        "gcamera:microvideo",
        "motionphoto_data",
        "com.google.android.apps.motionphoto"
    )

    fun cached(key: String): Boolean? = cache[key]

    /** 读文件头找标记；列表里的每个格子都会调一次，所以只读 128KB。 */
    suspend fun probe(context: Context, item: GalleryItem): Boolean = withContext(Dispatchers.IO) {
        cache[item.stableKey]?.let { return@withContext it }
        val result = runCatching {
            if (item.isVideo) return@runCatching false
            val head = readHead(context, item, 128 * 1024) ?: return@runCatching false
            var hit = false
            for (m in markers) {
                if (containsAsciiIgnoreCase(head, m)) { hit = true; break }
            }
            hit
        }.getOrDefault(false)
        cache[item.stableKey] = result
        result
    }

    fun forget(key: String) {
        cache.remove(key)
    }

    /**
     * 把追加在图片尾部的 MP4 切出来。做法和系统相册一致：找到最后一个 `ftyp` box，
     * 从它前面 4 字节的 box size 开始截到文件结尾，再用 MediaMetadataRetriever 验时长。
     */
    suspend fun extractVideo(context: Context, item: GalleryItem): File? = withContext(Dispatchers.IO) {
        cachedVideo(item.stableKey)?.let { return@withContext it }
        runCatching {
            val source = when (item.origin) {
                "app" -> GalleryFileOps.locateAppFile(item.fileName)
                "phone" -> ImageDecode.materialize(context, phoneUri(item), "live-${item.key}")
                else -> null
            } ?: return@runCatching null
            if (source.length() > 200L * 1024 * 1024) return@runCatching null
            val out = File(context.cacheDir, "live-${item.key}.mp4")
            // 视频永远在文件尾部：只读最后 12MB 找 ftyp box，
            // 整张图动辄十几 MB，全量 readBytes + 逐字节扫要几百毫秒，长按就会"停一下"。
            val tailLimit = 12L * 1024 * 1024
            val from = maxOf(0L, source.length() - tailLimit)
            val bytes: ByteArray? = java.io.RandomAccessFile(source, "r").use { raf ->
                raf.seek(from)
                val buf = ByteArray((source.length() - from).toInt())
                var n = 0
                while (n < buf.size) {
                    val r = raf.read(buf, n, buf.size - n)
                    if (r <= 0) break
                    n += r
                }
                if (n <= 0) null else buf.copyOf(n)
            }
            if (bytes == null) return@runCatching null
            val idx = lastIndexOfAscii(bytes, "ftyp")
            if (idx < 4) return@runCatching null
            val start = idx - 4
            if (start >= bytes.size - 8) return@runCatching null
            out.outputStream().use { o -> o.write(bytes, start, bytes.size - start) }
            val retriever = MediaMetadataRetriever()
            val ok = runCatching {
                retriever.setDataSource(out.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }.getOrDefault(0L) > 0L
            runCatching { retriever.release() }
            if (ok) {
                videoCache[item.stableKey] = out
                out
            } else {
                out.delete()
                null
            }
        }.getOrNull()
    }

    private fun phoneUri(item: GalleryItem): Uri {
        val collection = if (item.isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(collection, item.key.toLong())
    }

    private fun readHead(context: Context, item: GalleryItem, limit: Int): ByteArray? = runCatching {
        when (item.origin) {
            "app" -> {
                val f = GalleryFileOps.locateAppFile(item.fileName) ?: return@runCatching null
                readHead(f, limit)
            }
            "phone" -> context.contentResolver.openInputStream(phoneUri(item))?.use { input ->
                val buf = ByteArray(limit)
                var n = 0
                while (n < limit) {
                    val r = input.read(buf, n, limit - n)
                    if (r <= 0) break
                    n += r
                }
                if (n <= 0) null else buf.copyOf(n)
            }
            else -> null
        }
    }.getOrNull()

    private fun readHead(file: File, limit: Int): ByteArray? = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(minOf(limit, file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r <= 0) break
                n += r
            }
            if (n <= 0) null else buf.copyOf(n)
        }
    }.getOrNull()

    private fun containsAsciiIgnoreCase(data: ByteArray, needle: String): Boolean =
        indexOfAsciiIgnoreCase(data, needle) >= 0

    private fun indexOfAsciiIgnoreCase(data: ByteArray, needle: String): Int {
        val n = needle.length
        if (n == 0 || data.size < n) return -1
        var i = 0
        while (i <= data.size - n) {
            var k = 0
            while (k < n) {
                val a = data[i + k].toInt().toChar().lowercaseChar()
                if (a != needle[k]) break
                k++
            }
            if (k == n) return i
            i++
        }
        return -1
    }

    private fun lastIndexOfAscii(data: ByteArray, needle: String): Int {
        val n = needle.length
        if (n == 0 || data.size < n) return -1
        var i = data.size - n
        while (i >= 0) {
            var k = 0
            while (k < n) {
                val a = data[i + k].toInt().toChar()
                if (a != needle[k]) break
                k++
            }
            if (k == n) return i
            i--
        }
        return -1
    }
}
