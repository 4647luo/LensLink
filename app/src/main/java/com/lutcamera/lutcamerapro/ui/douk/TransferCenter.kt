package com.lutcamera.lutcamerapro.ui.douk

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** 一张照片从相机传到手机的过程状态。 */
internal enum class TransferStatus {
    WAITING, RUNNING, COMPLETED, FAILED
}

internal data class TransferRecord(
    val id: String,
    val fileName: String,
    val folder: String?,
    val totalBytes: Long,
    val copiedBytes: Long,
    val status: TransferStatus,
    val speedBytesPerSec: Long,
    val message: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    val percent: Int
        get() = if (totalBytes <= 0L) 0
        else ((copiedBytes.toDouble() * 100.0 / totalBytes).coerceIn(0.0, 100.0)).toInt()

    val isActive: Boolean
        get() = status == TransferStatus.WAITING || status == TransferStatus.RUNNING
}

/**
 * 传输中心：图库右上角「传输图片」的数据源。
 *
 * 这里只记录「相机 → 手机」的下载任务，不负责真正的 PTP 读取。队列会写进
 * SharedPreferences：应用被系统杀掉后，下次打开会把 RUNNING 记录恢复成
 * WAITING，相机重新连上并拿到文件列表后由 NikonViewModel 自动续传。
 */
internal object TransferCenter {
    private const val PREFS = "lianjing_transfer_center"
    private const val KEY_RECORDS = "records_v1"
    private const val MAX_RECORDS = 80

    private val lock = Any()
    private var initialized = false
    private var prefs: SharedPreferences? = null

    private val _records = MutableStateFlow<List<TransferRecord>>(emptyList())
    val records: StateFlow<List<TransferRecord>> = _records.asStateFlow()

    /** 图库缩略图缓存（只在当前进程有效），让传输列表能显示正在传的是哪张照片。 */
    private val _thumbnails = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, ImageBitmap>> = _thumbnails.asStateFlow()

    fun putThumbnail(id: String, bitmap: ImageBitmap?) {
        if (bitmap == null) return
        _thumbnails.value = _thumbnails.value + (id to bitmap)
    }

    fun thumbnail(id: String): ImageBitmap? = _thumbnails.value[id]

    /** 速度采样只放内存，不落盘。 */
    private data class SpeedSample(val copied: Long, val atMs: Long)
    private val speedSamples = HashMap<String, SpeedSample>()

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val loaded = readPrefs()
            val reset = loaded.map { record ->
                if (record.status == TransferStatus.RUNNING) {
                    record.copy(
                        status = TransferStatus.WAITING,
                        speedBytesPerSec = 0L,
                        message = "上次传输中断，连接相机后自动继续",
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    record
                }
            }
            _records.value = reset
            if (reset != loaded) savePrefs(reset)
        }
    }

    fun idOf(fileName: String, folder: String?, sizeBytes: Long): String =
        "${folder.orEmpty()}|$fileName|${sizeBytes.coerceAtLeast(0L)}"

    /** 加入队列；同名同大小同目录的文件不会重复排队。返回稳定 id。 */
    fun enqueue(fileName: String, folder: String?, sizeBytes: Long): String {
        val id = idOf(fileName, folder, sizeBytes)
        synchronized(lock) {
            val list = _records.value.toMutableList()
            val index = list.indexOfFirst { it.id == id }
            val now = System.currentTimeMillis()
            if (index >= 0) {
                val old = list[index]
                if (old.status == TransferStatus.COMPLETED || old.status == TransferStatus.FAILED) {
                    list[index] = old.copy(
                        status = TransferStatus.WAITING,
                        copiedBytes = 0L,
                        speedBytesPerSec = 0L,
                        message = "等待传输",
                        totalBytes = sizeBytes.takeIf { it > 0L } ?: old.totalBytes,
                        updatedAt = now
                    )
                }
            } else {
                list.add(
                    0,
                    TransferRecord(
                        id = id,
                        fileName = fileName,
                        folder = folder,
                        totalBytes = sizeBytes.coerceAtLeast(0L),
                        copiedBytes = 0L,
                        status = TransferStatus.WAITING,
                        speedBytesPerSec = 0L,
                        message = "等待传输",
                        createdAt = now,
                        updatedAt = now
                    )
                )
                while (list.size > MAX_RECORDS) {
                    val cut = list.indexOfLast {
                        it.status == TransferStatus.COMPLETED || it.status == TransferStatus.FAILED
                    }
                    if (cut < 0) break
                    list.removeAt(cut)
                }
            }
            _records.value = list
            savePrefs(list)
        }
        return id
    }

    fun start(id: String, totalBytes: Long = 0L) {
        synchronized(lock) {
            val list = update(id) { old ->
                old.copy(
                    status = TransferStatus.RUNNING,
                    copiedBytes = 0L,
                    totalBytes = totalBytes.takeIf { it > 0L } ?: old.totalBytes,
                    speedBytesPerSec = 0L,
                    message = "正在从相机读取",
                    updatedAt = System.currentTimeMillis()
                )
            }
            speedSamples[id] = SpeedSample(0L, SystemClock.elapsedRealtime())
            savePrefs(list)
        }
    }

    /** 高频进度回调：只更新内存里的 StateFlow，不每包写 SharedPreferences。 */
    fun progress(id: String, copied: Long, total: Long) {
        synchronized(lock) {
            val nowMs = SystemClock.elapsedRealtime()
            val sample = speedSamples[id]
            val delta = if (sample != null && copied > sample.copied) copied - sample.copied else 0L
            val deltaMs = if (sample != null) (nowMs - sample.atMs).coerceAtLeast(1L) else 1L
            val instant = if (delta > 0L) delta * 1000L / deltaMs else 0L
            speedSamples[id] = SpeedSample(copied, nowMs)
            update(id) { old ->
                val speed = when {
                    instant <= 0L -> old.speedBytesPerSec
                    old.speedBytesPerSec <= 0L -> instant
                    else -> old.speedBytesPerSec * 7L / 10L + instant * 3L / 10L
                }
                old.copy(
                    status = TransferStatus.RUNNING,
                    copiedBytes = copied.coerceAtLeast(0L),
                    totalBytes = total.takeIf { it > 0L } ?: old.totalBytes,
                    speedBytesPerSec = speed,
                    message = "正在从相机读取",
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
    }

    fun complete(id: String) {
        synchronized(lock) {
            speedSamples.remove(id)
            val list = update(id) { old ->
                old.copy(
                    status = TransferStatus.COMPLETED,
                    copiedBytes = old.totalBytes.takeIf { it > 0L } ?: old.copiedBytes,
                    speedBytesPerSec = 0L,
                    message = "已保存到手机相册",
                    updatedAt = System.currentTimeMillis()
                )
            }
            savePrefs(list)
        }
    }

    fun fail(id: String, reason: String) {
        synchronized(lock) {
            speedSamples.remove(id)
            val list = update(id) { old ->
                old.copy(
                    status = TransferStatus.FAILED,
                    speedBytesPerSec = 0L,
                    message = reason.ifBlank { "传输失败" },
                    updatedAt = System.currentTimeMillis()
                )
            }
            savePrefs(list)
        }
    }

    fun markWaiting(id: String, reason: String = "等待相机连接") {
        synchronized(lock) {
            speedSamples.remove(id)
            val list = update(id) { old ->
                old.copy(
                    status = TransferStatus.WAITING,
                    speedBytesPerSec = 0L,
                    message = reason,
                    updatedAt = System.currentTimeMillis()
                )
            }
            savePrefs(list)
        }
    }

    fun waitingRecords(): List<TransferRecord> =
        _records.value.filter { it.status == TransferStatus.WAITING }
            .sortedBy { it.createdAt }

    fun hasWaiting(): Boolean = _records.value.any { it.status == TransferStatus.WAITING }

    fun activeCount(): Int =
        _records.value.count { it.status == TransferStatus.WAITING || it.status == TransferStatus.RUNNING }

    fun runningSpeed(): Long =
        _records.value.filter { it.status == TransferStatus.RUNNING }.sumOf { it.speedBytesPerSec }

    /** 只清掉已结束的记录，正在传和等待续传的任务必须保留。 */
    fun clearFinished(): Int {
        synchronized(lock) {
            val finished = _records.value.filter {
                it.status == TransferStatus.COMPLETED || it.status == TransferStatus.FAILED
            }
            if (finished.isEmpty()) return 0
            val remaining = _records.value.filterNot {
                it.status == TransferStatus.COMPLETED || it.status == TransferStatus.FAILED
            }
            finished.forEach { speedSamples.remove(it.id) }
            if (finished.isNotEmpty()) {
                val thumbs = _thumbnails.value.toMutableMap()
                finished.forEach { thumbs.remove(it.id) }
                _thumbnails.value = thumbs
            }
            _records.value = remaining
            savePrefs(remaining)
            return finished.size
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "--"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    fun formatSpeed(bytesPerSec: Long): String =
        if (bytesPerSec <= 0L) "--" else "${formatBytes(bytesPerSec)}/s"

    private inline fun update(id: String, block: (TransferRecord) -> TransferRecord): List<TransferRecord> {
        val list = _records.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) list[index] = block(list[index])
        _records.value = list
        return list
    }

    private fun readPrefs(): List<TransferRecord> {
        val raw = prefs?.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val fileName = obj.optString("fileName")
                    if (fileName.isBlank()) continue
                    val folder = obj.optString("folder").takeIf { it.isNotBlank() }
                    val total = obj.optLong("totalBytes", 0L).coerceAtLeast(0L)
                    add(
                        TransferRecord(
                            id = obj.optString("id").ifBlank { idOf(fileName, folder, total) },
                            fileName = fileName,
                            folder = folder,
                            totalBytes = total,
                            copiedBytes = obj.optLong("copiedBytes", 0L).coerceAtLeast(0L),
                            status = runCatching {
                                TransferStatus.valueOf(obj.optString("status", "WAITING"))
                            }.getOrDefault(TransferStatus.WAITING),
                            speedBytesPerSec = 0L,
                            message = obj.optString("message"),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun savePrefs(list: List<TransferRecord>) {
        val p = prefs ?: return
        runCatching {
            val array = JSONArray()
            list.forEach { record ->
                array.put(
                    JSONObject().apply {
                        put("id", record.id)
                        put("fileName", record.fileName)
                        if (record.folder == null) put("folder", JSONObject.NULL)
                        else put("folder", record.folder)
                        put("totalBytes", record.totalBytes)
                        put("copiedBytes", record.copiedBytes)
                        put("status", record.status.name)
                        put("message", record.message)
                        put("createdAt", record.createdAt)
                        put("updatedAt", record.updatedAt)
                    }
                )
            }
            p.edit().putString(KEY_RECORDS, array.toString()).apply()
        }
    }
}
