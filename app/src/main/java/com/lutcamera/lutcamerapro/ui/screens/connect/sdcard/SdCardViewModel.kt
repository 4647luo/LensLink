package com.lutcamera.lutcamerapro.ui.screens.connect.sdcard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.*
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lutcamera.lutcamerapro.lut.LutData
import kotlinx.coroutines.*
import java.util.ArrayDeque

enum class SdSaveMode(val label: String, val desc: String) {
    DUAL("Dual Save", "Save both original and LUT version"),
    ORIGINAL_ONLY("Original Only", "Save only original photo"),
    LUT_ONLY("LUT Only", "Save only LUT version")
}

enum class PhotoFilterOption(val label: String) {
    ALL("All Photos"),
    JPG_ONLY("Only JPG / HEIF"),
    RAW_ONLY("Only RAW")
}

data class ImportStats(
    val totalProcessed: Int = 0,
    val successJpg: Int = 0,
    val successRaw: Int = 0,
    val failCount: Int = 0
)

/**
 * 轻量照片元数据实体类（绝不常驻持有大 Bitmap，保证万张照片也仅耗极微内存） */
data class SdPhotoItem(
    val id: String,
    val name: String,
    val uri: Uri,
    val size: Long,
    val lastModified: Long,
    val isRaw: Boolean
) {
    var isSelected: Boolean by mutableStateOf(false)
}

class SdCardViewModel : ViewModel() {

    companion object {
        private const val TAG = "SdCardViewModel"
        val SUPPORTED_EXTENSIONS = setOf(
            "JPG", "JPEG", "HEIF", "HEIC", "HIF", "NEF", "ARW", "CR2", "CR3", "DNG", "RAF", "RW2"
        )
        private val RAW_EXTENSIONS = setOf("NEF", "ARW", "CR2", "CR3", "DNG", "RAF", "RW2")
    }

    var isMounted by mutableStateOf(false)
        private set
    var sdCardName by mutableStateOf("No SD Card Mounted")
        private set
    var currentFolderUri by mutableStateOf<Uri?>(null)
        private set

    // 照片列表
                val sdPhotos = mutableStateListOf<SdPhotoItem>()

    var currentFilter by mutableStateOf(PhotoFilterOption.ALL)
        private set

    val filteredPhotos: List<SdPhotoItem> by derivedStateOf {
        when (currentFilter) {
            PhotoFilterOption.ALL -> sdPhotos
            PhotoFilterOption.JPG_ONLY -> sdPhotos.filter { !it.isRaw }
            PhotoFilterOption.RAW_ONLY -> sdPhotos.filter { it.isRaw }
        }
    }

    var isScanning by mutableStateOf(false)
        private set

    // 批量导入处理器流水线状态
                var isProcessing by mutableStateOf(false)
        private set
    var processingProgress by mutableStateOf(0f)
        private set
    var processingIndex by mutableStateOf(0)
        private set
    var totalToProcess by mutableStateOf(0)
        private set
    var currentProcessingName by mutableStateOf("")
        private set

    var autoApplyLut by mutableStateOf(true)
        private set
    var saveMode by mutableStateOf(SdSaveMode.DUAL)
        private set

    private var importJob: Job? = null

    // 🌟 轻量级 LRU 缩略图缓存器 (最多缓存 160 张极小缩略图，占用 < 10MB 内存，自动淘汰)
                private val thumbnailCache = object : LruCache<String, Bitmap>(60) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            // 自动淘汰，不需要手动调用 recycle，交由 JVM GC 统一管理
                }
    }

    fun setFilterOption(option: PhotoFilterOption) { currentFilter = option }
    fun setSaveModeOption(mode: SdSaveMode) { saveMode = mode }
    fun toggleAutoApplyLut(enabled: Boolean) { autoApplyLut = enabled }

    fun selectAllVisible(selectAll: Boolean) {
        filteredPhotos.forEach { it.isSelected = selectAll }
    }

    fun onSdDirectorySelected(context: Context, treeUri: Uri) {
        currentFolderUri = treeUri
        isMounted = true

        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
        sdCardName = rootDoc?.name ?: "CFexpress / SD Card"
        scanSdCardPhotos(context)
    }

    /**
     * 🚀 纯轻量级元数据扫描：绝不解码任何图片，毫秒级扫描数千张照片，永不卡顿     */
    fun scanSdCardPhotos(context: Context) {
        val uri = currentFolderUri ?: return
        val rootDoc = DocumentFile.fromTreeUri(context, uri) ?: return

        isScanning = true
        sdPhotos.clear()
        thumbnailCache.evictAll()

        viewModelScope.launch(Dispatchers.IO) {
            val scannedFiles = mutableListOf<DocumentFile>()
            val queue = ArrayDeque<DocumentFile>()
            queue.addLast(rootDoc)

            while (queue.isNotEmpty()) {
                val folder = queue.removeFirst()
                val files = folder.listFiles()
                for (file in files) {
                    if (file.isDirectory) {
                        queue.addLast(file)
                    } else if (file.isFile) {
                        val ext = file.name?.substringAfterLast('.', "")?.uppercase() ?: ""
                        if (ext in SUPPORTED_EXTENSIONS) {
                            scannedFiles.add(file)
                        }
                    }
                }
            }

            val items = scannedFiles.mapNotNull { file ->
                val fileName = file.name ?: return@mapNotNull null
                val ext = fileName.substringAfterLast('.', "").uppercase()
                val isRaw = ext in RAW_EXTENSIONS
                SdPhotoItem(
                    id = file.uri.toString(),
                    name = fileName,
                    uri = file.uri,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    isRaw = isRaw
                )
            }.sortedByDescending { it.lastModified }

            withContext(Dispatchers.Main) {
                sdPhotos.addAll(items)
                isScanning = false
            }
        }
    }

    /**
     * 🌟 异步按需微型缩略图加载器 (160x160 RGB_565，极速且省内存)     */
    fun loadThumbnailAsync(context: Context, item: SdPhotoItem, onLoaded: (Bitmap?) -> Unit) {
        val cached = thumbnailCache.get(item.id)
        if (cached != null) {
            onLoaded(cached)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(item.uri)?.use {
                    BitmapFactory.decodeStream(it, null, boundsOpts)
                }

                if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
                    val targetDim = 160
                    var sample = 1
                    while (boundsOpts.outWidth / sample > targetDim || boundsOpts.outHeight / sample > targetDim) {
                        sample *= 2
                    }

                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }

                    val bmp = resolver.openInputStream(item.uri)?.use {
                        BitmapFactory.decodeStream(it, null, decodeOpts)
                    }

                    if (bmp != null) {
                        thumbnailCache.put(item.id, bmp)
                        withContext(Dispatchers.Main) {
                            onLoaded(bmp)
                        }
                    }
                }
            } catch (e: Exception) {
                // 读取缩略图失败不崩溃
                }
        }
    }

    /**
     * 🚀 大批量拉取流水线（单张流式隔离，永不卡顿）     */
    fun importSelectedPhotos(
        context: Context,
        currentLut: LutData?,
        lutIntensity: Float,
        onComplete: (ImportStats) -> Unit
    ) {
        val selectedItems = sdPhotos.filter { it.isSelected }
        if (selectedItems.isEmpty()) return

        isProcessing = true
        processingProgress = 0f
        processingIndex = 0
        totalToProcess = selectedItems.size

        importJob = viewModelScope.launch(Dispatchers.IO) {
            var successJpg = 0
            var successRaw = 0
            var failCount = 0

            for ((idx, item) in selectedItems.withIndex()) {
                if (!isActive) break

                withContext(Dispatchers.Main) {
                    processingIndex = idx + 1
                    currentProcessingName = item.name
                    processingProgress = (idx + 1).toFloat() / selectedItems.size
                }

                val result = SdImportProcessor.processAndSavePhoto(
                    context = context,
                    item = item,
                    saveMode = saveMode,
                    autoApplyLut = autoApplyLut,
                    currentLut = currentLut,
                    lutIntensity = lutIntensity
                )

                when (result) {
                    is ImportResult.Success -> {
                        if (result.isRaw) successRaw++ else successJpg++
                    }
                    is ImportResult.Failed -> {
                        failCount++
                    }
                }

                // 每批次处理 5 张主动让出协程时间片，防 ANR
                if (idx % 5 == 0) delay(10)
            }

            withContext(Dispatchers.Main) {
                isProcessing = false
                processingProgress = 1.0f
                onComplete(
                    ImportStats(
                        totalProcessed = selectedItems.size,
                        successJpg = successJpg,
                        successRaw = successRaw,
                        failCount = failCount
                    )
                )
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        isProcessing = false
    }
}



