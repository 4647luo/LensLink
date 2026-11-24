package com.lutcamera.lutcamerapro.ui.screens.connect.nikon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lutcamera.lutcamerapro.gpu.ImageAutoProcessor
import com.lutcamera.lutcamerapro.lut.LutData
import com.lutcamera.lutcamerapro.lut.XmpData
import com.lutcamera.lutcamerapro.model.BorderState
import com.lutcamera.lutcamerapro.network.FtpPushReceiver
import com.lutcamera.lutcamerapro.network.NetworkUtils
import com.lutcamera.lutcamerapro.network.NikonPtpClient
import com.lutcamera.lutcamerapro.ui.screens.connect.wired.SaveMode
import com.lutcamera.lutcamerapro.model.CameraBrand
import com.lutcamera.lutcamerapro.model.CameraStorageFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import com.lutcamera.lutcamerapro.network.NikonDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import com.lutcamera.lutcamerapro.network.PhotoInbox
import com.lutcamera.lutcamerapro.ui.douk.TransferCenter

enum class WirelessSubMode(val title: String, val desc: String) {
    AP_DIRECT("AP Direct", "Camera AP Mode (192.168.1.1)"),
    STA_LAN("STA LAN", "Camera connected to router")
}

sealed class NikonUiEvent {
    data class ShowToast(val message: String) : NikonUiEvent()
    data class NewPhotoArrived(val fileName: String) : NikonUiEvent()
    data class PhotoSaved(val fileName: String) : NikonUiEvent()
    data class DownloadCompleted(val fileName: String, val success: Boolean) : NikonUiEvent()
}

/**
 * 尼康相机控制入口。
 *
 * ── 机型差异备注（改属性号前必读）────────────────────────────
 * 当前所有实测基准都是 Nikon Z50II（Z50_2，固件 V1.11）：
 *   0x500A  对焦模式：只读枚举 1/32784/32785/32786/32787，写通常回 0x200F
 *   0xD061  实时取景 AF 模式：可写 UINT8，0=AF-S、1=AF-C、4=MF、5=AF-A
 *   0x5013  释放模式：1=单张、32784=CL、32785=CH、32793=C15、33039=C30、33054=C60
 *   0x5018  低速连拍张数：Z50II 为 1..9；其他机型按 descriptor，应用上限 100
 *   0x500B  测光：Z50II 枚举 2/3/4/32784；0x500E 曝光模式：1=M 3=A 4=S 5=P
 *   0x5004  图像品质、0x5003 图像尺寸、0xD016 RAW、0xD030 图像区域、0xD138 减震、0xD14E 动态 D
 *
 * 规则：
 *  1. 参数一律优先读相机 descriptor；只有 descriptor 缺失才使用本文件回退表。
 *  2. 若某机型属性号/枚举/可写性与 Z50II 不同，不要直接改 Z50II 分支；
 *     按机型新增分支，并在此处及项目根目录《尼康机型控制代码对照.md》写明：
 *     机型 + 属性号 + 枚举/取值 + 可写性 + 实测固件。
 *  3. 多数 Z 机型通用的属性（电量 0x5001、焦距 0x5008 等）如果实测一致，无需按机型分支。
 * ────────────────────────────────────────────────────────────
 */
class NikonViewModel : ViewModel() {

    // 最近一次连接时保存的应用上下文，供下载原图等操作使用
                private var appContext: Context? = null

    // 当前相机品牌系统（默认尼康）
                var currentBrand by mutableStateOf(CameraBrand.NIKON)
        private set

    // 模式切换：AP 直连 vs STA 局域网
                var wirelessMode by mutableStateOf(WirelessSubMode.AP_DIRECT)

    var cameraIp by mutableStateOf(NikonPtpClient.DEFAULT_NIKON_AP_IP)
    var localIp by mutableStateOf("192.168.1.2")
    var ftpPort by mutableIntStateOf(2121)

    var isConnected by mutableStateOf(false)
        private set
    var isConnecting by mutableStateOf(false)
        private set

    var capturedCount by mutableIntStateOf(0)
        private set

    // 快门拍照自动回传开关
                var autoTransferOnShutter by mutableStateOf(false)
        private set
    var autoTransferStatus by mutableStateOf("自动回传已关闭")
        private set
    fun setAutoTransfer(enabled: Boolean) {
        if (autoTransferOnShutter == enabled && ptpClient.autoTransferJpeg == enabled) return
        autoTransferOnShutter = enabled
        ptpClient.autoTransferJpeg = enabled
        autoTransferStatus = if (enabled) "等待新照片；相机请设置 JPG 或 RAW+JPG，并开启自动上传" else "自动回传已关闭"
        addLog(autoTransferStatus)
    }

    // 照片回传保存策略（仅原始 / 仅LUT / 双保存）
                var saveMode by mutableStateOf(SaveMode.DUAL)

    private data class PhotoSettings(
        val lut: LutData?, val intensity: Float, val autoLut: Boolean,
        val autoBorder: Boolean, val mode: SaveMode, val border: BorderState?, val xmp: XmpData?,
        val originalRelativePath: String = "Pictures/LutCameraPro/Originals",
        val thumbnailOnly: Boolean = false
    )
    private data class PhotoJob(val entry: PhotoInbox.Entry, val settings: PhotoSettings)
    private var photoSettings: PhotoSettings? = null
    private var photoInbox: PhotoInbox? = null
    private var inboxInitializationStarted = false
    private val photoJobs = Channel<PhotoJob>(4)
    // Accessed only on Main. Full JPEGs live on disk, not in this queue.
    private val queuedPhotoIds = mutableSetOf<String>()
    private val failedPhotoIds = mutableSetOf<String>()
    var photoProcessingStatus by mutableStateOf("收到 JPG 后，按当前设置保存原图或生成成片")
        private set
    var photosProcessing by mutableIntStateOf(0)
        private set
    var photosSaved by mutableIntStateOf(0)
        private set
    var photosFailed by mutableIntStateOf(0)
        private set
    var failedPhotoCount by mutableIntStateOf(0)
        private set
    var lastSavedPhotoUri by mutableStateOf<String?>(null)
        private set

    fun configurePhotoProcessing(
        context: Context, lut: LutData?, intensity: Float, autoApplyLut: Boolean,
        autoApplyBorder: Boolean, saveMode: SaveMode, borderState: BorderState?, xmp: XmpData?
    ) {
        appContext = context.applicationContext
        FtpPushReceiver.init(context.applicationContext)
        photoSettings = PhotoSettings(
            lut, intensity, autoApplyLut, autoApplyBorder, saveMode, borderState, xmp,
            originalRelativePath = com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.let {
                downloadRelativePath(it.downloadDir, "Originals")
            },
            thumbnailOnly = com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.downloadSize == "仅缩略图"
        )
        this.saveMode = saveMode
        if (photoInbox == null && !inboxInitializationStarted) {
            inboxInitializationStarted = true
            viewModelScope.launch {
                try {
                    val (inbox, pending) = withContext(Dispatchers.IO) {
                        val store = PhotoInbox(File(context.filesDir, "nikon-photo-inbox"))
                        store to store.pending()
                    }
                    photoInbox = inbox
                    failedPhotoIds.addAll(pending.map { it.id }.filterNot { it in queuedPhotoIds })
                    failedPhotoCount = failedPhotoIds.size
                    if (failedPhotoCount > 0) photoProcessingStatus = "有 $failedPhotoCount 张暂存照片，可按当前 LUT 与保存设置重试"
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    photoProcessingStatus = "无法准备照片暂存：${e.message}，请检查手机空间后重启应用"
                    addLog(photoProcessingStatus)
                }
            }
        }
    }

    fun retryFailedPhotos() {
        val inbox = photoInbox ?: return
        val settings = photoSettings ?: return
        viewModelScope.launch {
            try {
                val pending = withContext(Dispatchers.IO) { inbox.pending() }
                for (entry in pending) {
                    if (!queuedPhotoIds.add(entry.id)) continue
                    failedPhotoIds.remove(entry.id)
                    failedPhotoCount = failedPhotoIds.size
                    photosProcessing = queuedPhotoIds.size
                    photoJobs.send(PhotoJob(entry, settings))
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { photoProcessingStatus = "重试失败：${e.message}" }
        }
    }

    fun shareLastPhoto(context: Context) {
        val uri = lastSavedPhotoUri?.let(android.net.Uri::parse) ?: return
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri("尼康照片", uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "分享最近保存的照片"))
        } catch (e: Exception) { photoProcessingStatus = "无法分享：${e.message}" }
    }

    private suspend fun receivePhoto(fileName: String, bytes: ByteArray) {
        val settings = withContext(Dispatchers.Main) { photoSettings }
            ?: throw java.io.IOException("照片处理设置尚未就绪，请返回相机页后重试")
        val inbox = photoInbox ?: throw java.io.IOException("照片暂存尚未就绪")
        // Finish the local handoff even when the camera disconnects mid-save. The queued
        // jobs contain paths/settings only, so camera traffic never queues full JPEG arrays.
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                val entry = inbox.stage(fileName, bytes)
                withContext(Dispatchers.Main) {
                    capturedCount++
                    queuedPhotoIds.add(entry.id)
                    failedPhotoIds.remove(entry.id)
                    failedPhotoCount = failedPhotoIds.size
                    photosProcessing = queuedPhotoIds.size
                    photoProcessingStatus = "JPG 已暂存，等待处理：$fileName"
                    viewModelScope.launch {
                        try { photoJobs.send(PhotoJob(entry, settings))
                        } catch (e: CancellationException) {
                            withContext(NonCancellable) {
                                queuedPhotoIds.remove(entry.id)
                                failedPhotoIds.add(entry.id)
                                photosProcessing = queuedPhotoIds.size
                                failedPhotoCount = failedPhotoIds.size
                            }
                            throw e
                        }
                    }
                }
            } catch (e: Exception) {
                // Metadata can fail after the original has been synced; surface recoverable
                // orphans immediately instead of requiring the user to restart the app.
                val pending = runCatching { inbox.pending() }.getOrDefault(emptyList())
                withContext(Dispatchers.Main) {
                    failedPhotoIds.addAll(pending.map { it.id }.filterNot { it in queuedPhotoIds })
                    failedPhotoCount = failedPhotoIds.size
                    photoProcessingStatus = "照片暂存未完成：${e.message}"
                }
                throw e
            }
        }
    }
    private suspend fun receivePhoto(fileName: String, file: File) {
        val settings = withContext(Dispatchers.Main) { photoSettings }
            ?: throw java.io.IOException("照片处理设置尚未就绪，请返回相机页后重试")
        val inbox = photoInbox ?: throw java.io.IOException("照片暂存尚未就绪")
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                val entry = inbox.stage(fileName, file)
                withContext(Dispatchers.Main) {
                    capturedCount++
                    queuedPhotoIds.add(entry.id)
                    failedPhotoIds.remove(entry.id)
                    failedPhotoCount = failedPhotoIds.size
                    photosProcessing = queuedPhotoIds.size
                    photoProcessingStatus = "JPG 已暂存，等待处理：$fileName"
                    viewModelScope.launch {
                        try {
                            photoJobs.send(PhotoJob(entry, settings))
                        } catch (e: CancellationException) {
                            withContext(NonCancellable) {
                                queuedPhotoIds.remove(entry.id)
                                failedPhotoIds.add(entry.id)
                                photosProcessing = queuedPhotoIds.size
                                failedPhotoCount = failedPhotoIds.size
                            }
                            throw e
                        }
                    }
                }
            } catch (e: Exception) {
                val pending = runCatching { inbox.pending() }.getOrDefault(emptyList())
                withContext(Dispatchers.Main) {
                    failedPhotoIds.addAll(pending.map { it.id }.filterNot { it in queuedPhotoIds })
                    failedPhotoCount = failedPhotoIds.size
                    photoProcessingStatus = "照片暂存未完成：${e.message}"
                }
                throw e
            }
        }
    }

    private suspend fun processPhoto(job: PhotoJob) {
        val inbox = photoInbox ?: return
        val context = appContext ?: return
        var entry = job.entry
        var originalUri = entry.originalUri
        var outputUri = entry.outputUri
        withContext(Dispatchers.Main) {
            photoProcessingStatus = "正在处理：${entry.fileName}"
            addLogInner("照片处理 ${entry.fileName} LUT=${job.settings.lut?.title ?: "无"} 强度=${job.settings.intensity} 策略=${job.settings.mode.name}")
        }
        try {
            val settings = job.settings
            val needsOriginal = settings.mode != SaveMode.LUT_ONLY && originalUri == null
            val needsOutput = settings.mode != SaveMode.ORIGINAL_ONLY && outputUri == null
            val effectiveMode = when {
                needsOriginal && needsOutput -> SaveMode.DUAL
                needsOriginal -> SaveMode.ORIGINAL_ONLY
                else -> SaveMode.LUT_ONLY
            }
            var processingError: String? = null
            val ok = if (!needsOriginal && !needsOutput) true else entry.file.inputStream().use { input ->
                ImageAutoProcessor.processIncomingCameraPhoto(
                    context, input, settings.lut, settings.intensity, settings.autoLut,
                    settings.autoBorder, effectiveMode, settings.border, settings.xmp,
                    sourceFileName = entry.fileName,
                    originalRelativePath = settings.originalRelativePath,
                    originalMaxLongEdge = if (settings.thumbnailOnly) 1080 else null,
                    onOriginalSaved = {
                        originalUri = it
                        stampGpsIfEnabled(it)
                        entry = inbox.recordSaved(entry, originalUri, outputUri)
                    },
                    onOutputSaved = {
                        outputUri = it
                        entry = inbox.recordSaved(entry, originalUri, outputUri)
                    },
                    onProcessingError = { processingError = it }
                )
            }
            entry = inbox.recordSaved(entry, originalUri, outputUri)
            val savedUri = outputUri ?: originalUri
            if (savedUri != null) withContext(Dispatchers.Main) { lastSavedPhotoUri = savedUri }
            check(ok) { processingError ?: "成片处理或相册保存未完成；原始 JPG 已暂存，可重试" }
            inbox.complete(entry)
            val order = previewOrder(entry.fileName, entry.file)
            val isNewest = order >= lastPreviewOrder
            val preview = if (isNewest) savedUri?.let { readPhotoPreview(context, it) } else null
            withContext(Dispatchers.Main) {
                photosSaved++
                failedPhotoIds.remove(entry.id)
                val label = when (settings.mode) {
                    SaveMode.ORIGINAL_ONLY -> "原图"
                    SaveMode.LUT_ONLY -> "成片"
                    SaveMode.DUAL -> "原图 + 成片"
                }
                photoProcessingStatus = "已保存：${entry.fileName}（$label）"
                // 连拍/积压时先处理完的可能是旧照片，不能覆盖已经显示的新照片。
                if (isNewest) {
                    lastPreviewOrder = order
                    lastCapturedBitmap = preview
                    lastCapturedFileName = entry.fileName
                    lastCapturedAt = System.currentTimeMillis()
                }
                addLogInner(photoProcessingStatus)
                _uiEvent.trySend(NikonUiEvent.PhotoSaved(entry.fileName))
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                photosFailed++
                failedPhotoIds.add(entry.id)
                photoProcessingStatus = "${entry.fileName} 未完成：${e.message}"
                addLogInner(photoProcessingStatus)
            }
        } finally {
            withContext(Dispatchers.Main + NonCancellable) {
                queuedPhotoIds.remove(entry.id)
                photosProcessing = queuedPhotoIds.size
                failedPhotoCount = failedPhotoIds.size
            }
        }
    }

    private fun readPhotoPreview(context: Context, uri: String): Bitmap? = runCatching {
        val source = android.graphics.ImageDecoder.createSource(context.contentResolver, android.net.Uri.parse(uri))
        android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = (maxOf(info.size.width, info.size.height) / 640f).coerceAtLeast(1f)
            decoder.setTargetSize((info.size.width / scale).toInt().coerceAtLeast(1), (info.size.height / scale).toInt().coerceAtLeast(1))
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    val logs = mutableStateListOf<String>()
    var pairingCode by mutableStateOf<String?>(null)
        private set
    var pairingMessage by mutableStateOf("")
        private set
    fun restorePairingIdentity(context: Context, uri: android.net.Uri) {
        if (isConnected || isConnecting) return
        pairingMessage = "正在读取以前成功连接的日志…"
        viewModelScope.launch {
            try {
                val fingerprint = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        ptpClient.restorePairingIdentity(context.applicationContext, it)
                    } ?: throw java.io.IOException("无法打开日志 ZIP")
                }
                pairingMessage = "已恢复旧配对身份 ($fingerprint)。请保持相机使用原来的连接配置，再点立即连接。"
                addLog(pairingMessage)
            } catch (e: Exception) {
                pairingMessage = "恢复失败：${e.message}"
                addLog(pairingMessage)
            }
        }
    }
    var pairingBusy by mutableStateOf(false)
        private set
    private var pairingVersion: Long? = null
    fun startPairing() {
        if (!isConnected || pairingBusy || pairingCode != null) return
        val version = ptpClient.sessionVersion
        pairingBusy = true
        pairingMessage = "正在请求相机显示验证码…"
        viewModelScope.launch {
            try {
                val code = withContext(Dispatchers.IO) { ptpClient.beginPairing(version) }
                if (!isConnected || ptpClient.sessionVersion != version) throw java.io.IOException("连接已变化，请重新配对")
                pairingVersion = version
                pairingCode = code
                pairingMessage = "请对照相机屏幕。只有两边四位数字一致时才确认。"
            } catch (e: Exception) { pairingMessage = "请求配对失败：${e.message}。请导出日志。" }
            finally { pairingBusy = false }
        }
    }
    fun confirmPairing() {
        val version = pairingVersion ?: return
        if (pairingBusy || pairingCode == null || !isConnected) return
        pairingBusy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ptpClient.confirmPairing(version) }
                pairingMessage = "相机已接受配对确认。请在相机上按 OK 完成向导，再点击 App 的立即连接。"
                disconnect()
            } catch (e: Exception) { pairingMessage = "确认未成功：${e.message}。不要反复确认，请导出日志。" }
            finally { pairingCode = null; pairingVersion = null; pairingBusy = false }
        }
    }
    fun cancelPairing() {
        if (pairingBusy) return
        pairingCode = null; pairingVersion = null
        pairingMessage = "配对已取消，未提交确认；可在相机上取消或重新进入配对。"
        disconnect()
    }
    var exportingLogs by mutableStateOf(false)
        private set
    var exportLogMessage by mutableStateOf("")
        private set
    fun exportDetailedLogs(context: Context, uri: android.net.Uri) {
        if (exportingLogs) return
        exportingLogs = true
        exportLogMessage = "正在导出详细日志…"
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        ptpClient.exportDiagnostics(context.applicationContext, output)
                    } ?: throw java.io.IOException("无法打开保存位置")
                }
                exportLogMessage = "日志已保存，把这个 ZIP 文件发给我分析即可。"
            } catch (e: Exception) {
                exportLogMessage = "导出失败：${e.message}，请重新选择保存位置。"
            } finally { exportingLogs = false }
        }
    }

    // 尼康式 PTP 客户端实例
                val ptpClient = NikonPtpClient()

    init {
        // App 重启后设置页里的开关仍然是开的，但 PTP 客户端的自动回传标记会回到 false；
        // 这里按已保存的设置同步一次，否则事件通道收到新照片也会被直接丢掉。
        val wanted = com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.autoDownload &&
            com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.downloadFormat != "不下载"
        autoTransferOnShutter = wanted
        ptpClient.autoTransferJpeg = wanted
        autoTransferStatus = if (wanted) "等待新照片；相机请设置 JPG 或 RAW+JPG，并开启自动上传" else "自动回传已关闭"
    }

    // 实时图传流
                val isLiveViewActive = ptpClient.isLiveViewActive
    val liveViewBitmap = ptpClient.liveViewBitmap
    val liveMetrics = ptpClient.metrics
    val connectionStage = ptpClient.connectionStage
    val actualCameraLabel = ptpClient.cameraLabel
    var connectionMode by mutableStateOf(NikonPtpClient.ConnectionMode.CAMERA_AP)
    var compressionDesc by mutableStateOf<NikonPtpClient.PropDesc?>(null)
        private set
    var savedCompression by mutableStateOf<Long?>(null)
        private set
    var diagnosticsBusy by mutableStateOf(false)
        private set
    var diagnosticsMessage by mutableStateOf("连接后可读取相机支持的取景压缩档位")
        private set
    var scanning by mutableStateOf(false)
        private set
    /**
     * 机身当前是不是视频档的快速探针。
     *
     * 旧实现只在连上时读一次 30 多个扩展属性，用户把机身拨到视频档后 App 里
     * 仍是上一轮的「拍摄」参数，于是出现「相机已是视频、App 还显示拍摄档，
     * 快门/光圈/ISO 对不上」。这里在 2.5 秒参数轮询里顺带读 3-4 个视频档
     * 独占属性，检测到换档就立刻刷新整套扩展参数；null 表示这一轮判断不出。
     */
    var videoModeProbe by mutableStateOf<Boolean?>(null)
        private set
    val discoveredCameras = mutableStateListOf<NikonDiscovery.Device>()
    private var discoveryJob: Job? = null

    fun discoverCameras(context: Context) {
        if (scanning) { discoveryJob?.cancel(); return }
        discoveredCameras.clear()
        scanning = true
        discoveryJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(12000) {
                    NikonDiscovery.discover(context.applicationContext).collect { device ->
                        if (device.port == NikonPtpClient.PTP_PORT && discoveredCameras.none { it.ip == device.ip })
                            discoveredCameras.add(device)
                    }
                }
                if (discoveredCameras.isEmpty()) addLog("未发现广播设备；可从相机网络状态或手机热点设备列表读取相机 IP，手动连接")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { addLog("设备发现失败：${e.message}")
            } finally { scanning = false }
        }
    }

    fun refreshCompression() {
        if (!isConnected || diagnosticsBusy) return
        diagnosticsBusy = true
        viewModelScope.launch {
            try {
                compressionDesc = withContext(Dispatchers.IO) { ptpClient.fetchPropDesc(0xd1bc.toShort()) }
                savedCompression = ptpClient.originalCompression()
                diagnosticsMessage = if (compressionDesc == null) "当前会话未能读取取景压缩属性；不代表该机型永久不支持"
                    else if (compressionDesc?.dataType != 2) "此属性的数据类型与测试模式不兼容"
                    else if (compressionDesc?.writable != true) "当前状态下压缩属性只读"
                    else "档位按相机原始值显示，大小顺序不代表画质顺序。试用会在 6 秒后恢复。"
                addLog(diagnosticsMessage)
            } finally { diagnosticsBusy = false }
        }
    }

    fun setCompression(value: Long, trial: Boolean = false, restore: Boolean = false) {
        val desc = compressionDesc ?: return
        if (!isConnected || diagnosticsBusy || !desc.writable || desc.dataType != 2 || value !in desc.enumValues) return
        val version = ptpClient.sessionVersion
        diagnosticsBusy = true
        viewModelScope.launch {
            var previous: Long? = null
            var attempted = false
            var restoreNeeded = false
            val hadOriginal = ptpClient.originalCompression() != null
            try {
                previous = withContext(Dispatchers.IO) { ptpClient.readCompression(version) }
                withContext(Dispatchers.IO) { ptpClient.rememberOriginalCompression(previous!!, version) }
                savedCompression = ptpClient.originalCompression()
                attempted = true
                restoreNeeded = trial
                val ok = withContext(Dispatchers.IO) { ptpClient.writeCompression(version, value) }
                if (!ok) error("相机拒绝设置：0x${ptpClient.lastResponseCode.toString(16)}")
                val actual = withContext(Dispatchers.IO) { ptpClient.readCompression(version) }
                if (actual != value) error("设置后读回不一致")
                compressionDesc = desc.copy(currentRaw = actual)
                diagnosticsMessage = if (trial) "正在试用档位 $value，6 秒后恢复 $previous" else "已应用档位 $value"
                addLog(diagnosticsMessage)
                if (restore) { ptpClient.clearOriginalCompression(); savedCompression = null }
                if (trial) delay(6000)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                diagnosticsMessage = "压缩设置失败：${e.message}"
                addLog(diagnosticsMessage)
                restoreNeeded = attempted
            } finally {
                if (restoreNeeded && previous != null) {
                    withContext(NonCancellable) {
                        val restored = withContext(Dispatchers.IO) {
                            runCatching {
                                ptpClient.writeCompression(version, previous!!) &&
                                    ptpClient.readCompression(version) == previous
                            }.getOrDefault(false)
                        }
                        if (restored) {
                            compressionDesc = desc.copy(currentRaw = previous!!)
                            if (!hadOriginal) ptpClient.clearOriginalCompression()
                            savedCompression = ptpClient.originalCompression()
                            diagnosticsMessage = "试验结束，已恢复档位 $previous"
                        } else diagnosticsMessage = "未能恢复原档位；原值已保存，请重连后读取并点击恢复"
                        addLog(diagnosticsMessage)
                    }
                }
                diagnosticsBusy = false
            }
        }
    }


    // 灞忓绠风€靛湱劍（点哪对哪）
                var touchFocusPoint by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
    var touchFocusVisible by mutableStateOf(false)
    private var focusResetJob: Job? = null

    // 最新拍摄回传的照片流
                var lastCapturedBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var lastCapturedFileName by mutableStateOf("")
        private set
    /** 最近一次应用内按快门的本地时刻，用来判断它是否比机身列表里的文件更新 */
    var lastCapturedAt by mutableStateOf(0L)
        private set

    /** 防止 FTP 回传按处理完成顺序覆盖：只让文件名序号更大的那张成为「最新拍摄」。 */
    @Volatile private var lastPreviewOrder = 0L
    /** 逐项枚举落日志只是开发期取证；常态连接关掉，少 ~38 次 PTP 往返。 */
    private val DETAILED_PROP_DUMP = false

    /** 相机文件名一般带递增序号（DSC_0001 / _DSC1234）；没有序号时退回文件时间。 */
    private fun previewOrder(fileName: String, file: File? = null): Long {
        val digits = Regex("(\\d{3,})").find(fileName)?.groupValues?.get(1)?.toLongOrNull()
        return digits ?: (file?.lastModified() ?: 0L)
    }

    // 相机型号与曝光三要素
                var cameraModel by mutableStateOf("Nikon Z6 II")
    var shutterSpeed by mutableStateOf("1/250s")
    var aperture by mutableStateOf("F2.8")
    var iso by mutableStateOf("ISO 400")
    var shootMode by mutableStateOf("单张拍摄")
    var afMode by mutableStateOf("AF-S")
    var isRecordingVideo by mutableStateOf(false)

    val availableAfModes = listOf("AF-S", "AF-C", "MF")

    // 图传分辨率与帧率
                var liveResolution by mutableStateOf("1920 × 1080")
    var liveFps by mutableIntStateOf(30)

    // 动态更新 exposure 列表：连接相机后优先使用相机通讯层 GetDevicePropDesc 上报的真实档位
    /** 相机上报的一个可选档。含义：[label] 界面显示，[raw] PTP 枚举原始值，[dataType] PTP 数据类型编码 */
                data class CameraOption(val label: String, val raw: Long, val dataType: Int)

    var cameraShutterOptions by mutableStateOf<List<CameraOption>>(emptyList())
        private set
    var cameraApertureOptions by mutableStateOf<List<CameraOption>>(emptyList())
        private set
    var cameraIsoOptions by mutableStateOf<List<CameraOption>>(emptyList())
        private set

    val availableShutterSpeeds: List<String>
        get() = cameraShutterOptions.takeIf { it.isNotEmpty() }?.map { it.label } ?: currentBrand.shutterSpeeds

    val availableApertures: List<String>
        get() = cameraApertureOptions.takeIf { it.isNotEmpty() }?.map { it.label } ?: currentBrand.apertures

    val availableIsos: List<String>
        get() {
            // 机身的 0x500F 枚举里通常只有具体 ISO 数值，没有「自动」这一项，
            // 但相机确实支持 ISO 自动（0xFFFF / 0xD0AB ISO 自动控制）。
            // 这里统一把「自动」放在列表最前面，选它就切自动 ISO。
            val base = cameraIsoOptions.takeIf { it.isNotEmpty() }?.map { it.label } ?: currentBrand.isos
            return if (base.any { it == "自动" }) base else listOf("自动") + base
        }

    val availableShootModes = listOf(
        "Single", "Continuous High", "Continuous Low", "Self-timer 10s", "Self-timer 2s"
    )
                var showStorageDialog by mutableStateOf(false)
    var isLoadingStorageFiles by mutableStateOf(false)
    val storageFiles = mutableStateListOf<CameraStorageFile>()
    var storagePage by mutableIntStateOf(0)
    val pageSize = 50
    var storageFilter by mutableStateOf("All")
                private val _uiEvent = Channel<NikonUiEvent>(capacity = Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (job in photoJobs) processPhoto(job)
        }
        refreshNetworkInfo()
        addLogInner("通信引擎就绪，等待连接...")

        // 监听 FTP 回传照片流。新版 FTP 直接给临时文件，不再把整张 JPEG 读成 ByteArray。
        FtpPushReceiver.onPhotoFileReceived = { fileName, file ->
            handleReceivedPhotoFile(fileName, file)
        }
    }

    fun refreshNetworkInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = NetworkUtils.getLocalIpv4Address()
            withContext(Dispatchers.Main) {
                localIp = ip
                if (wirelessMode == WirelessSubMode.AP_DIRECT && cameraIp.isBlank()) {
                    cameraIp = NikonPtpClient.DEFAULT_NIKON_AP_IP
                }
            }
        }

        // 上次被杀进程时留下的传输队列：相机一重新连上，先补文件列表再自动续传。
        // PTP 的 object handle 只在当次连接有效，重连后必须重新读一遍列表，
        // 不能拿上一次连接留下的 storageFiles 直接续传，否则 handle 会错位。
        viewModelScope.launch {
            ptpClient.isConnected.collect { connected ->
                if (!connected || !TransferCenter.hasWaiting()) return@collect
                delay(700)
                // 上一条下载可能还在收尾；等它释放串行锁后再刷新列表。
                while (isLoadingStorageFiles || downloadingPhotoHandles.isNotEmpty()) {
                    if (!ptpClient.isConnected.value) return@collect
                    delay(250)
                }
                loadStoragePhotos(prepareTransfer = true, force = true)
            }
        }
    }

    fun switchBrand(brand: CameraBrand) {
        currentBrand = brand
        cameraModel = brand.defaultModels.firstOrNull() ?: brand.displayName
        if (wirelessMode == WirelessSubMode.AP_DIRECT) {
            cameraIp = brand.defaultApIp
        }
        // 映射曝光参数到该品牌区间范围
                if (!availableShutterSpeeds.contains(shutterSpeed)) {
            shutterSpeed = availableShutterSpeeds.getOrNull(availableShutterSpeeds.size / 2) ?: "1/250s"
        }
        if (!availableApertures.contains(aperture)) {
            aperture = availableApertures.getOrNull(availableApertures.size / 2) ?: "F2.8"
        }
        if (!availableIsos.contains(iso)) {
            iso = availableIsos.getOrNull(2) ?: "ISO 400"
        }
        addLogInner("已切换至相机品牌: ${brand.displayName} (${brand.englishName})")
    }

    fun setTouchFocus(normX: Float, normY: Float, offset: androidx.compose.ui.geometry.Offset) {
        touchFocusPoint = offset
        touchFocusVisible = true
        focusResetJob?.cancel()
        focusResetJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1800)
            touchFocusVisible = false
        }
        if (isConnected) {
            ptpClient.setFocusPoint(normX, normY)
            addLogInner("🎯 对焦点已下发机身: (${(normX * 100).toInt()}%, ${(normY * 100).toInt()}%)")
        }
    }

    fun setFocusPoint(normX: Float, normY: Float) {
        if (isConnected) {
            ptpClient.setFocusPoint(normX, normY)
            addLogInner("🎯 对焦点已下发机身: (${(normX * 100).toInt()}%, ${(normY * 100).toInt()}%)")
        }
    }

    fun toggleVideoRecording() {
        if (!isConnected) return
        val next = !isRecordingVideo
        viewModelScope.launch(Dispatchers.IO) {
            val ok = ptpClient.toggleMovieRecordSync(next)
            withContext(Dispatchers.Main) {
                if (ok) isRecordingVideo = next
                addLogInner(
                    when {
                        !ok -> "视频录制指令被机身拒绝（${ptpClient.lastResponseCode}）"
                        next -> "Video start"
                        else -> "Video stop"
                    }
                )
            }
        }
    }

    fun cycleAfMode() {
        val nextIdx = (availableAfModes.indexOf(afMode) + 1) % availableAfModes.size
        afMode = availableAfModes[nextIdx]
        addLogInner("对焦驱动模式: $afMode")
        // 真实下发到相机: PTP SetDevicePropValue (0xD061 = 尼康 LV_AFMODE)
                if (isConnected) {
            viewModelScope.launch(Dispatchers.IO) {
                // AF-S=0, AF-C=1, MF=4 (Z50II 的 0xD061 实测枚举为 0/1/4/5)
                val afValue: Byte = when (afMode) {
                    "AF-S" -> 0
                    "AF-C" -> 1
                    "MF" -> 4
                    else -> 0
                }
                val ok = ptpClient.setDevicePropValueUint8(0xD061.toShort(), afValue)
                withContext(Dispatchers.Main) {
                    if (ok) addLogInner("🎛️ AF 模式已下发机身: $afMode")
                    else addLogInner("⚠️ AF 模式下发失败 (相机可能不支持远程切换)")
                }
            }
        }
    }

    fun switchWirelessMode(mode: WirelessSubMode, settingsIp: String = "") {
        wirelessMode = mode
        if (mode == WirelessSubMode.AP_DIRECT) {
            cameraIp = currentBrand.defaultApIp
        } else if (mode == WirelessSubMode.STA_LAN && settingsIp.isNotBlank()) {
            cameraIp = settingsIp
        }
        refreshNetworkInfo()
    }

    fun toggleConnection(
        appContext: Context,
        getLut: () -> LutData?,
        getIntensity: () -> Float,
        getAutoApplyLut: () -> Boolean,
        getAutoApplyBorder: () -> Boolean = { false },
        getSaveMode: () -> SaveMode = { SaveMode.DUAL },
        getBorderState: () -> BorderState = { BorderState() },
        getXmp: () -> XmpData? = { null },
        onPhotoProcessed: (String) -> Unit = {}
    ) {
        if (isConnected || isConnecting) {
            disconnect()
        } else {
            connect(
                appContext = appContext,
                getLut = getLut,
                getIntensity = getIntensity,
                getAutoApplyLut = getAutoApplyLut,
                getAutoApplyBorder = getAutoApplyBorder,
                getSaveMode = getSaveMode,
                getBorderState = getBorderState,
                getXmp = getXmp,
                onPhotoProcessed = onPhotoProcessed
            )
        }
    }

    fun connect(context: Context, cameraIp: String) {
        appContext = context.applicationContext
        // 设置页的「自动下载」必须真正接到 PTP 新照片事件上，否则界面只记了偏好、
        // 相机拍完不会回传。选了「不下载」或总开关关闭时保持关闭。
        setAutoTransfer(
            com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.autoDownload &&
                com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.downloadFormat != "不下载"
        )
        val target = cameraIp.trim()
        // 同一台相机、会话还活着：不要重新握手。重握手会关掉旧事件通道，
        // 旧线程把主动关闭误报成掉线，界面就会「已连接/未连接」反复闪。
        if (this.cameraIp == target && isConnected && ptpClient.hasConnection) {
            addLog("已连接 $target，跳过重复连接")
            return
        }
        this.cameraIp = target
        connect(
            appContext = context,
            getLut = { photoSettings?.lut },
            getIntensity = { photoSettings?.intensity ?: 1f },
            getAutoApplyLut = { photoSettings?.autoLut ?: false },
            getAutoApplyBorder = { photoSettings?.autoBorder ?: false },
            getSaveMode = { photoSettings?.mode ?: SaveMode.ORIGINAL_ONLY },
            getBorderState = { photoSettings?.border ?: BorderState() },
            getXmp = { photoSettings?.xmp },
            onPhotoProcessed = {}
        )
    }

    private fun connect(
        appContext: Context,
        getLut: () -> LutData?,
        getIntensity: () -> Float,
        getAutoApplyLut: () -> Boolean,
        getAutoApplyBorder: () -> Boolean,
        getSaveMode: () -> SaveMode,
        getBorderState: () -> BorderState,
        getXmp: () -> XmpData?,
        onPhotoProcessed: (String) -> Unit
    ) {
        if (photoSettings == null) configurePhotoProcessing(
            appContext, getLut(), getIntensity(), getAutoApplyLut(), getAutoApplyBorder(),
            getSaveMode(), getBorderState(), getXmp()
        )
        if (cameraIp.isBlank()) {
            addLog("Please enter a valid camera IP!")
            return
        }

        isConnecting = true
        addLog("开始连接 ${this.cameraIp} ($connectionMode)")

        // 1. 启动 FTP 接收服务：只有用户打开「自动下载」才监听端口，
        //    否则相机连上后一直占着 21 端口，既多耗电也扩大局域网暴露面。
        if (com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.autoDownload) {
            FtpPushReceiver.startReceiver(ftpPort)
        } else {
            FtpPushReceiver.stopReceiver()
        }

        // 2. 启动 PTP/IP 控制通道与实时图传
                ptpClient.startNikonWiWatch(
            context = appContext,
            cameraIp = cameraIp,
            mode = connectionMode,
            listener = object : NikonPtpClient.NikonEventListener {
                override fun onConnected() {
                    viewModelScope.launch(Dispatchers.Main) {
                        isConnecting = false
                        isConnected = true
                        cameraModel = ptpClient.cameraLabel.value.ifBlank { "Nikon（型号未返回）" }
                        connectedAtMs = System.currentTimeMillis()
                        lockedBaseline = null
                        addLogInner("连接成功: ${currentBrand.displayName} $cameraModel ($cameraIp)")
                        addLogInner("获取相机信息: 机身型号 $cameraModel, 协议端口 ${currentBrand.defaultPort}")
                        addLogInner(if (ptpClient.canControlCamera) "正在从相机同步曝光参数..." else "基础连接已建立，控制权限尚未确认；暂停曝光同步")
                        _uiEvent.send(NikonUiEvent.ShowToast("🟢 ${currentBrand.displayName} 相机连接成功"))
                        // 必须等 isConnected 置真之后再拉机身状态，否则 refreshBodyStatus 一进来就 return
                        refreshBodyStatus()
                    }
                    // 连接成功后拉取相机的曝光档位枚举与当前值
                if (ptpClient.canControlCamera) viewModelScope.launch(Dispatchers.IO) {
                        try {
                            // 让机身状态先读完，避免两路 PTP 请求同时挤在刚连上的几秒里。
                            delay(1500)
                            syncCameraPropOptions()
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                addLogInner("⚠️ 参数同步异常: ${e.message}")
                            }
                        } finally {
                            startParamPolling()
                        }
                    }
                }

                override fun onDisconnected(reason: String) {
                    stopParamPolling()
                    viewModelScope.launch(Dispatchers.Main) {
                        runCatching { FtpPushReceiver.stopReceiver() }
                        pairingCode = null; pairingVersion = null
                        isConnecting = false
                        isConnected = false
                        // 断开后录制状态一定要归零，否则控制页快门钮会一直停在「方块」录制态
                        isRecordingVideo = false
                        connectedAtMs = 0L
                        lockedBaseline = null
                        compressionDesc = null
                        addLogInner("🔴 ${currentBrand.displayName} 相机已断开: $reason")
                        _uiEvent.send(NikonUiEvent.ShowToast("🔴 已断开连接"))
                    }
                }

                override fun onLog(msg: String) {
                    addLog(msg)
                }

                override fun onTransferStatus(message: String) {
                    viewModelScope.launch(Dispatchers.Main) { autoTransferStatus = message }
                }
                override suspend fun onAutoJpegReceived(fileName: String, jpegBytes: ByteArray) {
                    receivePhoto(fileName, jpegBytes)
                }

                override suspend fun onAutoRawReceived(fileName: String, rawBytes: ByteArray) {
                    val format = com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.downloadFormat
                    if (format == "JPG" || format == "不下载") {
                        addLogInner("自动下载设置只收 JPG，跳过 RAW：$fileName")
                        return
                    }
                    val saved = saveAutoRaw(fileName, rawBytes)
                    if (saved) {
                        addLogInner("RAW 已自动保存：$fileName")
                        autoTransferStatus = "RAW 已自动保存：$fileName"
                    }
                }
                override fun onPhotoReceived(fileName: String, jpegBytes: ByteArray) {
                    if (!autoTransferOnShutter) {
                        addLogInner("⚠️ [快门回传已关闭] 跳过接收: $fileName")
                        return
                    }
                    handleReceivedPhoto(fileName, jpegBytes)
                }
            }
        )
    }

    fun disconnect() {
        stopParamPolling()
        modeRefreshJob?.cancel()
        modeRefreshJob = null
        pairingCode = null; pairingVersion = null
        val wasConnecting = isConnecting
        isConnecting = false
        isConnected = false
        videoModeProbe = null
        discoveryJob?.cancel()
        compressionDesc = null
        savedCompression = null

        ptpClient.stopNikonWatch()
        FtpPushReceiver.stopReceiver()

        cameraShutterOptions = emptyList()
        cameraApertureOptions = emptyList()
        cameraIsoOptions = emptyList()

        viewModelScope.launch(Dispatchers.Main) {
            if (wasConnecting) {
                addLogInner("Connection cancelled")
            } else {
                addLogInner("Camera disconnected")
            }
        }
    }

    // ===== 相机真实档位（GetDevicePropDesc 枚举）同步与编解码 =====

    /**
     * 从机身重新读取全部可控参数，并把界面值改回相机真实上报值。
     * 手动点「刷新相机参数」或某次下发失败时调用：避免界面停留在用户选中、但机身没接受的值。
     */
    fun refreshCameraParams(silent: Boolean = false) {
        if (!isConnected) {
            if (!silent) lastParamMessage = "相机未连接，无法刷新参数"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { syncCameraPropOptions() }
            runCatching { refreshExtendedProps() }
            withContext(Dispatchers.Main) {
                if (!silent) {
                    lastParamMessage = "已重读相机当前参数"
                    addLogInner("已从机身重读当前参数")
                }
            }
        }
    }

    // 相机拨盘/机身菜单改动后，App 里显示的参数也要跟着变。
    // 这里只轮询「当前值」，不再每轮拉一遍完整 descriptor，避免把 PTP 和取景拖慢。
    private var paramPollJob: Job? = null
    private var modeRefreshJob: Job? = null

    private fun startParamPolling() {
        if (paramPollJob?.isActive == true) return
        paramPollJob = viewModelScope.launch(Dispatchers.IO) {
            delay(2000)
            while (isActive && isConnected) {
                runCatching { pollCameraParamSnapshot() }
                // 取景时参数会被机身实时改动，3 秒够用；空闲时没有再拉 2.5 秒的必要，
                // 降到 6 秒后连相机 Wi‑Fi 的命令流量少一半，机身也不容易被反复唤醒。
                delay(if (ptpClient.isLiveViewActive.value) 3_000L else 6_000L)
            }
        }
    }

    private fun stopParamPolling() {
        paramPollJob?.cancel()
        paramPollJob = null
    }

    private suspend fun pollCameraParamSnapshot() {
        if (!isConnected || !ptpClient.canControlCamera) return
        val shutterRaw = readPropBytes(0x500D)
        val apertureRaw = readPropBytes(0x5007)
        val isoRaw = readPropBytes(0x500F)
        val shutter = shutterRaw?.let(::leU32)
        val aperture = apertureRaw?.let(::leU16)
        val iso = isoRaw?.let(::leU16)
        val ev = readPropBytes(0x5010)?.let(::leS16)
        val metering = readPropBytes(0x500B)?.let(::leU16)
        val mode = readPropBytes(0x500E)?.let { if (it.isNotEmpty()) (it[0].toInt() and 0xFF).toLong() else null }
        val wb = readPropBytes(0x5005)?.let(::leU16)
        val focus = readPropBytes(0xD061)?.let { if (it.isNotEmpty()) (it[0].toInt() and 0xFF).toLong() else null }

        // 机身档位探针：复用上面已经读到的快门/光圈/ISO，先判断拍照三件套还在不在，
        // 只有「拍照三件套缺失」或「正在视频档」时才去补读视频档独占属性。
        // 拍照档下不再额外发 7~10 条 GetDevicePropValue，取景 3 秒/空闲 6 秒一轮的流量明显下降。
        val stillReporting = listOf(shutterRaw, apertureRaw, isoRaw).count { it?.isNotEmpty() == true }
        val probedVideoMode = probeVideoMode(stillReporting)

        withContext(Dispatchers.Main) {
            if (shutter != null && shutter > 0) shutterSpeed = formatShutterRaw(shutter)
            if (aperture != null && aperture > 0) this@NikonViewModel.aperture = formatApertureRaw(aperture)
            if (iso != null && iso > 0) this@NikonViewModel.iso = formatIsoRaw(iso)
            ev?.let { exposureComp = decodeExposureComp(it) }
            if (metering != null && metering > 0) meteringMode = meteringLabel(metering)
            if (mode != null && mode in 1L..5L) {
                exposureProgram = mode.toInt()
                exposureModeLabel = exposureModeLabelOf(mode)
            }
            if (wb != null && wb > 0) whiteBalance = whiteBalanceLabel(wb)
            if (focus != null && focus in 0L..5L) focusModeLabel = nikonFocusModeLabelOf(focus)
            if (probedVideoMode != null) {
                val previous = videoModeProbe
                videoModeProbe = probedVideoMode
                if (previous != probedVideoMode && (previous != null || probedVideoMode)) {
                    com.lutcamera.lutcamerapro.log.DoukLog.i(
                        "Camera", "MODE_PROBE",
                        "video" to probedVideoMode, "previous" to (previous?.toString() ?: "null")
                    )
                    // 换档后机身会停报旧档、改报新档的一整套属性；立刻补读一次，
                    // 否则界面会短暂停留在旧档快门/光圈上，看起来就是「对不上」。
                    modeRefreshJob?.cancel()
                    modeRefreshJob = viewModelScope.launch(Dispatchers.IO) {
                        runCatching { refreshExtendedProps() }
                        runCatching { pollCameraParamSnapshot() }
                    }
                }
            }
        }
    }

    /**
     * 快速判断机身当前档位。尼康视频档独占的 0xD0AF/D0A0/D0A7/D0A2 只有在视频档
     * 才会真正上报；拍照档下读不到，或返回空值。标准曝光三件套用来兜底。
     * 判断结果和 DoukState.detectedVideoMode 的规则保持一致。
     *
     * @param stillReporting 本轮已读到的快门/光圈/ISO 里有多少项有值。拍照档通常是 3，
     * 视频档会停报这组属性。复用已有读取结果，避免每轮把这些属性再读一遍。
     */
    private suspend fun probeVideoMode(stillReporting: Int): Boolean? {
        try {
            val previous = videoModeProbe
            // 视频档：只要拍照三件套还在，就保持视频判断，不再额外读视频属性。
            if (previous == true) {
                if (stillReporting < 2) return true
                // 拍照三件套重新出现，可能是刚拨回拍照档；先确认视频独占属性真的没了，
                // 避免一次读取抖动就把界面从视频切回拍照。
                val movieOnly = intArrayOf(0xD0AF, 0xD0A0, 0xD0A7, 0xD0A2)
                    .count { readPropBytes(it)?.isNotEmpty() == true }
                return movieOnly < 2
            }
            // 拍照档：三件套齐了就直接判定，不碰任何视频属性（这是绝大多数轮询的情况）。
            if (stillReporting >= 2) return false
            // 拍照三件套缺失：才补读视频独占属性和视频曝光属性，判断是否刚切到视频档。
            val movieOnly = intArrayOf(0xD0AF, 0xD0A0, 0xD0A7, 0xD0A2)
                .count { readPropBytes(it)?.isNotEmpty() == true }
            if (movieOnly >= 2) return true
            val movie = intArrayOf(0xD1A8, 0xD1A9, 0xD1AA)
                .count { readPropBytes(it)?.isNotEmpty() == true }
            return if (movie >= 2) true else null
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun readPropBytes(prop: Int): ByteArray? =
        runCatching { ptpClient.getDevicePropValue(prop.toShort()) }.getOrNull()

    private fun leU16(bytes: ByteArray): Long? {
        if (bytes.size < 2) return null
        return ((bytes[0].toLong() and 0xFF) or ((bytes[1].toLong() and 0xFF) shl 8))
    }

    private fun leU32(bytes: ByteArray): Long? {
        if (bytes.size < 4) return leU16(bytes)
        return (bytes[0].toLong() and 0xFF) or
            ((bytes[1].toLong() and 0xFF) shl 8) or
            ((bytes[2].toLong() and 0xFF) shl 16) or
            ((bytes[3].toLong() and 0xFF) shl 24)
    }

    private fun leS16(bytes: ByteArray): Long? {
        val u = leU16(bytes) ?: return null
        return if (u >= 0x8000) u - 0x10000 else u
    }

    /** 拉取快门/光圈/ISO 的枚举可选值与当前值，刷新界面选项 */
    private suspend fun syncCameraPropOptions() {        // 快门码 0x500D ExposureTime（UINT32，单位 1/10000 秒）
                ptpClient.fetchPropDesc(0x500D.toShort())?.let { desc ->
            if (desc.isEnum) {
                val options = desc.enumValues.map { CameraOption(formatShutterRaw(it), it, desc.dataType) }
                val current = options.minByOrNull { kotlin.math.abs(it.raw - desc.currentRaw) }?.label
                withContext(Dispatchers.Main) {
                    cameraShutterOptions = options
                    current?.let { shutterSpeed = it }
                    shutterWritable = desc.writable && options.size > 1
                    addLogInner("📖 相机快门档位 ${options.size} 个，当前 $current，可写=$shutterWritable")
                }
            }
        }
        // 光圈 0x5007 FNumber（UINT16），F 值 1.00 起
                ptpClient.fetchPropDesc(0x5007.toShort())?.let { desc ->
            if (desc.isEnum) {
                val options = desc.enumValues.map { CameraOption(formatApertureRaw(it), it, desc.dataType) }
                val current = options.minByOrNull { kotlin.math.abs(it.raw - desc.currentRaw) }?.label
                withContext(Dispatchers.Main) {
                    cameraApertureOptions = options
                    current?.let { aperture = it }
                    apertureWritable = desc.writable && options.size > 1
                    addLogInner("📖 相机光圈档位 ${options.size} 个，当前 $current，可写=$apertureWritable")
                }
            }
        }
        // ISO 0x500F ExposureIndex（UINT16，直接 ISO 数值）
                ptpClient.fetchPropDesc(0x500F.toShort())?.let { desc ->
            if (desc.isEnum) {
                val options = desc.enumValues.map { CameraOption(formatIsoRaw(it), it, desc.dataType) }
                val current = options.minByOrNull { kotlin.math.abs(it.raw - desc.currentRaw) }?.label
                withContext(Dispatchers.Main) {
                    cameraIsoOptions = options
                    current?.let { iso = it }
                    isoWritable = desc.writable && options.size > 1
                    addLogInner("📖 相机 ISO 档位 ${options.size} 个，当前 $current，可写=$isoWritable")
                }
            }
        }
        // 曝光模式：M(1)/A(3)/S(4) 等手动类档位才允许远程改曝光，自动档属性只读
                val program = ptpClient.getExposureProgram()
        withContext(Dispatchers.Main) {
            exposureProgram = program ?: -1
            when (program) {
                1, 3, 4, 5 -> addLogInner("Exposure mode code $program, remote adjustable")
                null -> addLogInner("Exposure mode not readable")
                else -> addLogInner("Camera in Auto/Scene mode (code $program)")
            }
        }
    }

    private fun formatShutterRaw(v: Long): String {
        if (v <= 0) return "?"
        if (v >= 10000) {
            val secs = v / 10000.0
            return if (secs == secs.toLong().toDouble()) "${secs.toLong()}s" else "${"%.1f".format(secs)}s"
        }
        return "1/${kotlin.math.round(10000.0 / v).toInt()}s"
    }

    private fun formatApertureRaw(v: Long): String {
        if (v <= 0) return "?"
        return if (v % 100 == 0L) "F${v / 100}" else "F${"%.1f".format(v / 100.0)}"
    }

    private fun formatIsoRaw(v: Long) =
        // 0xFFFF 是 PTP 里 ISO 的「自动」档；以前会原样显示成 ISO 65535
        if (v <= 0 || v >= 0xFFFF) "自动" else "ISO $v"

    /** 相机枚举不可选时，按 PTP 标准物理编码把界面档位字符串反算为原始值 */
    private fun parseShutterRaw(label: String): Long {
        val s = label.removeSuffix("s").trim()
        return if (s.startsWith("1/")) {
            val denom = s.removePrefix("1/").toDoubleOrNull() ?: return 0L
            kotlin.math.round(10000.0 / denom).toLong()
        } else {
            val secs = s.toDoubleOrNull() ?: return 0L
            kotlin.math.round(secs * 10000).toLong()
        }
    }

    private fun parseApertureRaw(label: String): Long {
        val f = label.removePrefix("F").trim().toDoubleOrNull() ?: return 0L
        return kotlin.math.round(f * 100).toLong()
    }

    private fun parseIsoRaw(label: String): Long =
        if (label.contains("自动")) 0xFFFFL
        else label.removePrefix("ISO").trim().toLongOrNull() ?: 0L

    /** 按下发失败时的 PTP 响应码给出可操作提示 */
    private fun propFailMessage(name: String, code: Int): String {
        if (code == 0x2001 && !ptpClient.lastWriteVerified) {
            return "⚠️ $name 未生效：相机回了 OK，但机身数值没变（可能被自动模式/机身拨盘锁定）"
        }
        return when (code) {
            0x200A -> "⚠️ $name 下发失败：相机报告该属性不支持"
            0x200F -> "⚠️ $name 下发被拒：请把相机曝光模式拨到 M / S / A 档（自动档仅支持参数微调）"
            0x2019 -> "$name failed: camera busy"
            0 -> "No response (connection might be lost)"
            else -> "⚠️ $name 下发失败，PTP 响应码 0x${"%04X".format(code)}"
        }
    }

    /**
     * 快门拍照
     */
    fun triggerShutter() {
        if (!isConnected) return
        ptpClient.triggerCapture()
        if (com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.capturePreview) fetchLatestShotPreview()
    }

    private var shotPreviewJob: Job? = null

    /** 拍完主动抓最新一张的机身缩略图，不依赖相机"自动上传"，用于拍摄后快速预览。
     *  连拍时相机会连续写卡，固定等 1.2 秒经常只拿到中间那张；这里改成轮询到文件名稳定，
     *  并且每次快门都会取消上一轮任务，所以连拍结束后一定落在最后一张。 */
    private fun fetchLatestShotPreview() {
        shotPreviewJob?.cancel()
        shotPreviewJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val beforeName = lastCapturedFileName
                var candidate: com.lutcamera.lutcamerapro.model.CameraStorageFile? = null
                var stable = 0
                repeat(12) { attempt ->
                    delay(if (attempt == 0) 800 else 500)
                    val latest = runCatching {
                        ptpClient.fetchCameraPhotos(startIdx = 0, count = 2, force = true)
                            .firstOrNull()
                    }.getOrNull() ?: return@repeat
                    if (latest.fileName == beforeName) return@repeat
                    if (latest.fileName == candidate?.fileName && latest.sizeBytes == candidate?.sizeBytes) {
                        stable++
                    } else {
                        candidate = latest
                        stable = 1
                    }
                    // 连续两次看到同一个新文件，说明连拍已经写完，再解码缩略图
                    if (stable >= 2) {
                        publishPreview(latest)
                        return@launch
                    }
                }
                candidate?.let { publishPreview(it) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { addLogInner("快速预览读取失败：${e.message}") }
            }
        }
    }

    private suspend fun publishPreview(latest: com.lutcamera.lutcamerapro.model.CameraStorageFile) {
        val order = previewOrder(latest.fileName)
        if (order < lastPreviewOrder) return
        val preview = ptpClient.readBigThumbnail(latest.handle)
            ?: ptpClient.readThumbnail(latest.handle)
            ?: return
        val bitmap = BitmapFactory.decodeByteArray(preview, 0, preview.size) ?: return
        withContext(Dispatchers.Main) {
            if (order < lastPreviewOrder) return@withContext
            lastPreviewOrder = order
            lastCapturedBitmap = bitmap
            lastCapturedFileName = latest.fileName
            lastCapturedAt = System.currentTimeMillis()
        }
    }

    fun triggerCapture() = triggerShutter()

    /**
     * 视频录制控制开关（与屏幕状态同步）
     * 机身的录像指令要等它真的回 OK 再翻状态：以前是点了就先翻，
     * 指令被机身拒绝时快门钮会一直停在「方块」的录制态，退不回来。
     */
    fun toggleMovieRecord(isRecording: Boolean) {
        if (isRecordingVideo == isRecording) return
        if (!isConnected) {
            isRecordingVideo = false
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = ptpClient.toggleMovieRecordSync(isRecording)
            withContext(Dispatchers.Main) {
                // 失败时不能把 isRecordingVideo 取反：开始录制失败还置 true，
                // 下一次点击就会发“停止”，用户看到的现象就是按钮一直没反应。
                isRecordingVideo = if (ok) isRecording else false
                if (!ok) {
                    val code = "0x%04X".format(ptpClient.lastResponseCode)
                    lastParamMessage = "视频录制指令被机身拒绝（$code）：请确认机身拨盘在视频档，且相机允许 App 控制录像"
                    addLogInner("视频录制指令被机身拒绝（${ptpClient.lastResponseCode}）")
                } else {
                    lastParamMessage = if (isRecording) "视频录制已开始" else "视频录制已停止"
                    addLogInner(if (isRecording) "Video start" else "Video stop")
                }
            }
        }
    }

    /**
     * 自动对焦     */
    fun triggerAf() {
        if (!isConnected) return
        ptpClient.triggerAf()
    }

    /**
     * 实时图传控制
     */
    fun toggleLiveView() {
        if (!isConnected) return
        if (isLiveViewActive.value) {
            ptpClient.stopLiveView()
        } else {
            ptpClient.startLiveView()
        }
    }

    /** 打开全屏监视才开取景；退出监视就把取景停掉，不让相机后台一直被占着 */
    fun startPreview() {
        if (!isConnected) return
        if (!ptpClient.isLiveViewActive.value) ptpClient.startLiveView()
    }

    fun stopPreview() {
        if (ptpClient.isLiveViewActive.value) ptpClient.stopLiveView()
    }

    private fun handleReceivedPhotoFile(fileName: String, file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                receivePhoto(fileName, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    photoProcessingStatus = "接收照片失败：${e.message}"
                    addLogInner(photoProcessingStatus)
                }
            } finally {
                if (file.exists()) runCatching { file.delete() }
            }
        }
    }

    private fun handleReceivedPhoto(fileName: String, jpegBytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try { receivePhoto(fileName, jpegBytes)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    photoProcessingStatus = "接收照片失败：${e.message}"
                    addLogInner(photoProcessingStatus)
                }
            }
        }
    }
    // ===== 📁 机身 SD 卡图库加载与无损下载 =====
                fun fetchCameraPhotos() = loadStoragePhotos()

    /** 机身图库照片列表（供界面直接遍历展示用）*/
    val cameraStoragePhotos: List<CameraStorageFile>
        get() = storageFiles.toList()

    var storageMessage by mutableStateOf("")
        private set
    var downloadingPhotoHandles by mutableStateOf<Set<Int>>(emptySet())
        private set
    private var resumeTransfersJob: Job? = null
    var storageDownloadMessage by mutableStateOf("")
        private set
    var storageHasMore by mutableStateOf(false)
        private set
    fun loadStoragePhotos(
        prepareTransfer: Boolean = false,
        force: Boolean = false,
        startIdx: Int = 0,
        count: Int = 4000
    ) {
        if (isLoadingStorageFiles || downloadingPhotoHandles.isNotEmpty()) return
        if (!isConnected) {
            addLog("🔴 未连接相机，无法获取图库")
            return
        }
        isLoadingStorageFiles = true
        storageMessage = "正在读取相机文件列表"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 按需读窗口：图库先只取最新一批，下拉再扩大窗口，不再每次全表扫 4000 个对象
                val realList = ptpClient.fetchCameraPhotos(
                    startIdx = startIdx,
                    count = count,
                    prepareTransfer = prepareTransfer,
                    force = force
                )
                
                withContext(Dispatchers.Main) {
                    storageFiles.clear()
                    storageMessage = ptpClient.storageStatus
                    storageFiles.addAll(realList)
                    storageHasMore = ptpClient.storageHasMore
                    isLoadingStorageFiles = false
                    storagePage = 0
                    if (realList.isEmpty()) {
                        addLogInner("Storage retrieved, no photos found or retrieval failed")
                    } else {
                        addLogInner(
                            "Storage retrieved, ${realList.size} media files" +
                                if (storageHasMore) ", more available" else ""
                        )
                    }
                    // 持久化队列里还有等待任务：文件列表已就绪，开始续传。
                    if (TransferCenter.hasWaiting()) resumePendingTransfers()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoadingStorageFiles = false
                    storageMessage = "图库读取失败：${e.message ?: "请重新连接后重试"}"
                    addLogInner("⚠️ 图库加载异常: ${e.message}")
                }
            }
        }
    }

    fun getFilteredPagedStorageFiles(): List<CameraStorageFile> {
        val filtered = when (storageFilter) {
            "JPG" -> storageFiles.filter { !it.isRaw }
            "RAW" -> storageFiles.filter { it.isRaw }
            else -> storageFiles
        }
        val start = storagePage * pageSize
        return filtered.drop(start).take(pageSize)
    }

    fun getTotalPages(): Int {
        val filtered = when (storageFilter) {
            "JPG" -> storageFiles.filter { !it.isRaw }
            "RAW" -> storageFiles.filter { it.isRaw }
            else -> storageFiles
        }
        val total = (filtered.size + pageSize - 1) / pageSize
        return if (total == 0) 1 else total
    }

    /** 手机最近一次定位；系统还没有缓存时同步请求一次新鲜定位，避免导入照片时拿不到坐标 */
    private fun lastKnownLatLon(): Pair<Double, Double>? {
        val ctx = appContext ?: return null
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val granted = fine == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            coarse == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE)
            as? android.location.LocationManager ?: return null
        val cached = runCatching { lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) }.getOrNull()
            ?: runCatching { lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER) }.getOrNull()
        cached?.let { return it.latitude to it.longitude }
        return freshLocation(lm)
    }

    private fun freshLocation(lm: android.location.LocationManager): Pair<Double, Double>? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var latest: android.location.Location? = null
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                if (latest == null) {
                    latest = location
                    latch.countDown()
                }
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER
        ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        providers.forEach { provider ->
            val ctx = appContext
            val granted = ctx != null &&
                (androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            if (!granted) return@forEach
            runCatching {
                lm.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
            }
        }
        try {
            latch.await(900, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        runCatching { lm.removeUpdates(listener) }
        return latest?.let { it.latitude to it.longitude }
    }

    /** 导入照片落盘后补写 EXIF GPS（设置里「导入图片时附加手机 GPS」打开才写） */
    private fun stampGpsIfEnabled(pathOrUri: String) {
        if (!com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.gpsEnabled) return
        if (!com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.gpsAttachImport) return
        val (lat, lon) = lastKnownLatLon() ?: run {
            addLogInner("未取到手机定位，照片未附加 GPS")
            return
        }
        val displayName = if (pathOrUri.startsWith("content://", true)) {
            android.net.Uri.parse(pathOrUri).lastPathSegment ?: pathOrUri
        } else java.io.File(pathOrUri).name
        val stamped = com.lutcamera.lutcamerapro.util.PhotoGpsStamp.stamp(
            appContext, pathOrUri, lat, lon
        )
        if (stamped) {
            addLogInner("已把手机 GPS 写入 $displayName")
        } else {
            addLogInner("JPG 可写 GPS，RAW/视频不支持写入定位：$displayName")
        }
    }

    fun downloadStorageFile(
        context: Context,
        file: CameraStorageFile,
        getLut: () -> LutData?,
        getIntensity: () -> Float,
        applyLut: Boolean = false
    ) {        if (!isConnected) {
            addLog("🔴 未连接相机，无法下载原图")
            return
        }
        val transferId = TransferCenter.enqueue(file.fileName, file.folder, file.sizeBytes)
        TransferCenter.start(transferId, file.sizeBytes)
        viewModelScope.launch(Dispatchers.IO) {
            addLogInner("📥 正在下载原图: ${file.fileName}...")
            try {
                // 调用真实 PTP 高速通道拉取原图流式分页
                val photoBytes = ptpClient.downloadCameraPhoto(file.handle) { copied, total ->
                    TransferCenter.progress(transferId, copied, total)
                }
                if (photoBytes == null || photoBytes.isEmpty()) {
                    TransferCenter.fail(transferId, "相机未返回图片")
                    withContext(Dispatchers.Main) {
                        addLogInner("Failed to fetch original photo")
                    }
                    return@launch
                }
                
                // 将拉取的真实原图送入自动化渲染与保存流水线
                val saved = ByteArrayInputStream(photoBytes).use { inputStream ->
                    ImageAutoProcessor.processIncomingCameraPhoto(
                        context = context,
                        imageStream = inputStream,
                        currentLut = if (applyLut) getLut() else null,
                        intensity = getIntensity(),
                        autoApplyLut = applyLut,
                        autoApplyBorder = false,
                        saveMode = if (applyLut) SaveMode.LUT_ONLY else SaveMode.ORIGINAL_ONLY,
                        borderState = null,
                        currentXmp = null,
                        sourceFileName = file.fileName,
                        onOriginalSaved = { stampGpsIfEnabled(it) }
                    )
                }

                check(saved) { "照片处理或保存失败，未完成下载" }
                TransferCenter.complete(transferId)
                withContext(Dispatchers.Main) {
                    addLogInner("Download and save successful: ${file.fileName}")
                    _uiEvent.send(NikonUiEvent.DownloadCompleted(file.fileName, true))
                }
            } catch (e: Exception) {
                TransferCenter.fail(transferId, e.message ?: "下载失败")
                withContext(Dispatchers.Main) {
                    addLogInner("⚠️ 下载失败: ${file.fileName} (${e.message})")
                    _uiEvent.send(NikonUiEvent.DownloadCompleted(file.fileName, false))
                }
            }
        }
    }

    /** 按 handle 下载机身原图并保存（供机身图库弹窗使用），完成后回调结果 */
    var lockedImportStatus by mutableStateOf("")
        private set
    var isImportingLocked by mutableStateOf(false)
        private set

    /** 本次连接时刻；「仅连接后新增」在机身列表还没回来时用它兜底 */
    private var connectedAtMs = 0L

    /**
     * 连接瞬间相机里已经锁定的文件句柄。
     * 用 CompletableDeferred 让「仅连接后新增」的导入等它一次，
     * 避免刚连上就导入时把旧锁定文件又算成新增。
     */
    private var lockedBaseline: CompletableDeferred<Set<Int>?>? = null
    /** 本次会话已经导入过的锁定句柄：自动巡检每 12 秒跑一次，不能把同一张反复下载。 */
    private val importedLockedHandles = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /**
     * 记录「本次连接时」已锁定的文件句柄。
     * 只有开启「仅连接后新增」时才需要在连接后调用，避免普通连接多做一次存储枚举。
     */
    fun snapshotLockedBaseline() {
        if (!isConnected || lockedBaseline != null) return
        val baseline = CompletableDeferred<Set<Int>?>()
        lockedBaseline = baseline
        viewModelScope.launch(Dispatchers.IO) {
            val base = runCatching {
                ptpClient.fetchCameraPhotos(startIdx = 0, count = 4000, force = false)
                    .filter { it.isLocked }
                    .map { it.handle }
                    .toSet()
            }.getOrNull()
            baseline.complete(base)
            addLogInner(
                if (base == null) "锁定文件分界读取失败，将按连接时间判断新增"
                else "已记录连接时锁定文件 ${base.size} 个"
            )
        }
    }

    /** 锁定文件的去重键：文件名 + 大小，跨连接稳定；handle 每次连接可能变，不能拿来跨会话判重。 */
    private fun doukLockedKey(file: com.lutcamera.lutcamerapro.model.CameraStorageFile): String =
        "${file.fileName}|${file.sizeBytes}"

    private fun doukIsLockedImported(file: com.lutcamera.lutcamerapro.model.CameraStorageFile): Boolean =
        com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.isLockedImported(doukLockedKey(file))

    /**
     * 导入相机里所有标了「保护/锁定」的照片。
     * 只下原图 JPG，按顺序逐张处理，避免同时开多个 PTP 事务把相机顶掉。
     */
    fun importLockedPhotos(
        onlyAfterConnect: Boolean = false,
        onDone: ((Int) -> Unit)? = null,
        scanCount: Int = 4000,
        dedupeHandled: Boolean = true,
        /** 后台巡检用：没有新东西就不写日志，避免每几秒刷一行 */
        quiet: Boolean = false
    ) {
        if (!isConnected) {
            lockedImportStatus = "相机未连接，无法导入锁定照片"
            onDone?.invoke(0)
            return
        }
        if (isImportingLocked) return
        val ctx = appContext ?: run {
            lockedImportStatus = "缺少应用上下文，请重开应用后再试"
            onDone?.invoke(0)
            return
        }
        isImportingLocked = true
        lockedImportStatus = "正在读取相机文件列表…"
        viewModelScope.launch(Dispatchers.IO) {
            var success = 0
            try {
                val all = ptpClient.fetchCameraPhotos(startIdx = 0, count = scanCount, force = true, quiet = quiet)
                val lockedCandidates = all.filter { it.isLocked }
                // 设置-下载保护文件里的图片/视频格式要真正参与筛选：
                // 只锁了 RAW 或视频时，默认「JPG」会一个都找不到，必须先按规则分类再报数量。
                val lkFormat = com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.lockedFormat
                val includeJpg = lkFormat != "不下载" && lkFormat != "RAW"
                val includeRaw = lkFormat == "RAW" || lkFormat == "JPG + RAW" || lkFormat == "全部格式"
                val includeVideo = com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.lockedVideoFormat != "不下载"
                // 落盘前先按磁盘现状判重：老版本下到 LutCameraPro、或手工导入过的同名同尺寸文件
                // 从来没进过去重记录，只靠 key 去重会再下一份 (1).JPG，所以这里加一层磁盘索引。
                val diskIndex = if (dedupeHandled) {
                    runCatching {
                        com.lutcamera.lutcamerapro.ui.douk.GalleryFileOps.appFileNameIndex()
                    }.getOrDefault(emptySet())
                } else emptySet()
                val alreadyOnDisk = lockedCandidates.filter { file ->
                    com.lutcamera.lutcamerapro.ui.douk.GalleryFileOps
                        .diskBaseName(file.fileName) in diskIndex
                }
                alreadyOnDisk.forEach {
                    com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.markLockedImported(doukLockedKey(it))
                    importedLockedHandles.add(it.handle)
                }
                val lockedByFormat = lockedCandidates.filter { file ->
                    when {
                        file.isVideo -> includeVideo
                        file.isRaw -> includeRaw
                        else -> includeJpg
                    }
                }
                val lockedAll = lockedByFormat
                    .filterNot { dedupeHandled && it.handle in importedLockedHandles }
                    // 跨会话去重：handle 每次连接可能变，用「文件名|大小」判断是否已经导入到手机
                    .filterNot { dedupeHandled && doukIsLockedImported(it) }
                    // 磁盘上已经有同名的文件，说明以前下过，直接跳过
                    .filterNot { dedupeHandled && com.lutcamera.lutcamerapro.ui.douk.GalleryFileOps
                        .diskBaseName(it.fileName) in diskIndex }
                val locked = if (onlyAfterConnect) {
                    val base = withTimeoutOrNull(10_000L) {
                        lockedBaseline?.await()
                    }
                    if (base != null) {
                        lockedAll.filter { it.handle !in base }
                    } else {
                        // 机身列表分界没读到时退回连接时间；-5s 容忍手机/相机时间的小偏差
                        val since = if (connectedAtMs > 0L) connectedAtMs - 5_000L else 0L
                        lockedAll.filter { it.dateModified >= since }
                    }
                } else {
                    lockedAll
                }
                if (!quiet) addLogInner(
                    "锁定文件：总计 ${lockedCandidates.size} 个" +
                        "（JPG/HEIF ${lockedCandidates.count { !it.isRaw && !it.isVideo }}，" +
                        "RAW ${lockedCandidates.count { it.isRaw }}，" +
                        "视频 ${lockedCandidates.count { it.isVideo }}），" +
                        "按当前设置命中 ${lockedAll.size} 个" +
                        if (alreadyOnDisk.isNotEmpty()) "，手机上已存在 ${alreadyOnDisk.size} 个" else ""
                )
                if (locked.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        lockedImportStatus = when {
                            lockedCandidates.isEmpty() -> "相机里没有找到锁定文件；请确认已在机身上给照片加锁"
                            lockedByFormat.isEmpty() -> "发现 ${lockedCandidates.size} 个锁定文件，但当前格式设置是「$lkFormat」；" +
                                "到设置-下载保护文件把图片格式改成 RAW / JPG + RAW，视频格式改成非「不下载」"
                            lockedAll.isNotEmpty() -> "本次连接后没有新增锁定文件（已导入过的不会重复下载）"
                            else -> "锁定文件都已导入过（手机上已有 ${alreadyOnDisk.size} 个），不会重复下载"
                        }
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    lockedImportStatus = if (onlyAfterConnect) {
                        "找到 ${locked.size} 个连接后新增的锁定文件，开始导入…"
                    } else {
                        "找到 ${locked.size} 个锁定文件，开始导入…"
                    }
                }
                locked.forEachIndexed { index, file ->
                    if (!isConnected) throw IllegalStateException("相机已断开")
                    withContext(Dispatchers.Main) {
                        lockedImportStatus = "正在导入 ${index + 1}/${locked.size}：${file.fileName}"
                    }
                    val bytes = ptpClient.downloadCameraPhoto(file.handle)
                    if (bytes == null || bytes.isEmpty()) {
                        addLogInner("锁定照片下载失败：${file.fileName}")
                        return@forEachIndexed
                    }
                    var failure: String? = null
                    val saved = if (file.isRaw || file.isVideo) {
                        // RAW / MOV 不能走 JPG 处理流水线，直接原文件写进对应媒体库
                        saveLockedBinary(ctx, file, bytes)
                    } else {
                        ByteArrayInputStream(bytes).use { input ->
                            ImageAutoProcessor.processIncomingCameraPhoto(
                                context = ctx,
                                imageStream = input,
                                currentLut = null,
                                intensity = 1.0f,
                                autoApplyLut = false,
                                autoApplyBorder = false,
                                saveMode = SaveMode.ORIGINAL_ONLY,
                                borderState = null,
                                currentXmp = null,
                                sourceFileName = file.fileName,
                                originalRelativePath = downloadRelativePath(
                                    com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.lockedDir,
                                    "Originals"
                                ),
                                originalMaxLongEdge = if (
                                    com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.lockedSize == "仅缩略图"
                                ) 1080 else null,
                                onOriginalSaved = { stampGpsIfEnabled(it) },
                                onProcessingError = { failure = it }
                            )
                        }
                    }
                    if (!saved) {
                        addLogInner("锁定照片保存失败：${file.fileName}（${failure ?: "未知原因"}）")
                        return@forEachIndexed
                    }
                    importedLockedHandles.add(file.handle)
                    com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.markLockedImported(doukLockedKey(file))
                    success++
                    withContext(Dispatchers.Main) {
                        val idx = storageFiles.indexOfFirst { it.handle == file.handle }
                        if (idx >= 0) storageFiles[idx] = storageFiles[idx].copy(isDownloaded = true)
                        lockedImportStatus = "已导入 $success/${locked.size} 张"
                    }
                }
                withContext(Dispatchers.Main) {
                    lockedImportStatus = "锁定照片导入完成：$success/${locked.size} 张"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    lockedImportStatus = "导入锁定照片失败：${e.message ?: "请重新连接后重试"}"
                    addLogInner("锁定照片导入异常：${e.message}")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    isImportingLocked = false
                    onDone?.invoke(success)
                }
            }
        }
    }

    /** 自动回传的 RAW 原样写入「设置-自动下载」选定的目录，不参与 JPG 成片流程。 */
    private fun saveAutoRaw(fileName: String, bytes: ByteArray): Boolean {
        val ctx = appContext ?: return false
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mime = when (extension) {
            "nef", "nrw" -> "image/x-nikon-nef"
            "dng" -> "image/x-adobe-dng"
            else -> "application/octet-stream"
        }
        val collection = android.provider.MediaStore.Files.getContentUri(
            android.provider.MediaStore.VOLUME_EXTERNAL
        )
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                downloadRelativePath(
                    com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.downloadDir,
                    "Raw"
                )
            )
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(collection, values) ?: return false
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw java.io.IOException("无法写入 $fileName")
            ctx.contentResolver.update(uri, android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            true
        } catch (e: Exception) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            addLogInner("RAW 自动保存失败：$fileName（${e.message}）")
            false
        }
    }

    /** RAW / 视频原文件直接落媒体库，不经过只认 JPG 的成片流水线。 */
    private fun saveLockedBinary(
        context: Context,
        file: CameraStorageFile,
        bytes: ByteArray
    ): Boolean {
        val isVideo = file.isVideo
        val collection = if (isVideo) {
            android.provider.MediaStore.Video.Media.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
            )
        } else {
            android.provider.MediaStore.Files.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL
            )
        }
        val mime = when (file.extension) {
            "mov" -> "video/quicktime"
            "mp4" -> "video/mp4"
            "nef", "nrw" -> "image/x-nikon-nef"
            "dng" -> "image/x-adobe-dng"
            else -> "application/octet-stream"
        }
        val relative = downloadRelativePath(
            com.lutcamera.lutcamerapro.ui.douk.DoukPrefs.lockedDir,
            if (isVideo) "Videos" else "Raw"
        )
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection, values) ?: return false
        return try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw java.io.IOException("无法写入 ${file.fileName}")
            context.contentResolver.update(uri, android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            true
        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            addLogInner("锁定文件写入失败：${file.fileName}（${e.message}）")
            false
        }
    }

    fun enqueueTransfer(handle: Int): String? {
        val file = storageFiles.firstOrNull { it.handle == handle } ?: return null
        return TransferCenter.enqueue(file.fileName, file.folder, file.sizeBytes)
    }

    /**
     * 应用被杀后留下的队列，在相机重新连上 / 文件列表重新读回来后继续。
     * handle 每次连接都会变，所以用「目录 + 文件名 + 大小」重新匹配；已落盘的同名文件直接算完成。
     * PTP 的 GetObject 没有字节偏移续读，单个文件会从 0 重新取，但队列不会丢。
     */
    fun resumePendingTransfers() {
        if (resumeTransfersJob?.isActive == true) return
        val waiting = TransferCenter.waitingRecords()
        if (waiting.isEmpty() || !isConnected) return
        resumeTransfersJob = viewModelScope.launch(Dispatchers.IO) {
            val diskNames = runCatching {
                com.lutcamera.lutcamerapro.ui.douk.GalleryFileOps.appFileNameIndex()
            }.getOrDefault(emptySet())
            for (record in waiting) {
                if (!isConnected) break
                while (isLoadingStorageFiles || downloadingPhotoHandles.isNotEmpty()) delay(150)
                val exact = storageFiles.firstOrNull {
                    it.fileName == record.fileName &&
                        (record.folder.isNullOrBlank() || it.folder == record.folder) &&
                        (record.totalBytes <= 0L || it.sizeBytes == record.totalBytes)
                }
                val byName = exact ?: storageFiles.firstOrNull {
                    it.fileName == record.fileName &&
                        (record.totalBytes <= 0L || it.sizeBytes == record.totalBytes)
                }
                if (byName == null) {
                    TransferCenter.markWaiting(record.id, "等待相机重新返回文件")
                    continue
                }
                val diskBase = com.lutcamera.lutcamerapro.ui.douk.GalleryFileOps.diskBaseName(byName.fileName)
                if (byName.isDownloaded || diskBase in diskNames) {
                    TransferCenter.complete(record.id)
                    continue
                }
                val done = CompletableDeferred<Boolean>()
                withContext(Dispatchers.Main.immediate) {
                    downloadPhoto(byName.handle) { ok ->
                        if (!done.isCompleted) done.complete(ok)
                    }
                }
                val ok = withTimeoutOrNull(240_000L) { done.await() }
                if (ok == null) {
                    TransferCenter.markWaiting(record.id, "等待下一次连接继续")
                }
                if (!isConnected) break
            }
        }
    }

    fun downloadPhoto(handle: Int, callback: (Boolean) -> Unit) {
        if (downloadingPhotoHandles.isNotEmpty() || isLoadingStorageFiles) return
        val ctx = appContext
        if (ctx == null || !isConnected) {
            storageDownloadMessage = "相机已断开，请重新连接后下载"
            addLog("🔴 未连接相机，无法下载原图")
            callback(false)
            return
        }
        val file = storageFiles.find { it.handle == handle }
        val transferId = TransferCenter.enqueue(
            file?.fileName ?: "相机照片_$handle",
            file?.folder,
            file?.sizeBytes ?: 0L
        )
        if (file?.isDownloaded == true) {
            TransferCenter.complete(transferId)
            callback(true)
            return
        }
        downloadingPhotoHandles = setOf(handle)
        storageDownloadMessage = "正在下载 ${file?.fileName ?: handle}，请保持连接"
        TransferCenter.start(transferId, file?.sizeBytes ?: 0L)
        viewModelScope.launch(Dispatchers.IO) {
            addLogInner("📥 正在下载原图: ${file?.fileName ?: handle}...")
            try {
                val photoBytes = ptpClient.downloadCameraPhoto(handle) { copied, total ->
                    TransferCenter.progress(transferId, copied, total)
                }
                if (photoBytes == null || photoBytes.isEmpty()) {
                    TransferCenter.fail(transferId, "相机未返回图片")
                    withContext(Dispatchers.Main) {
                        addLogInner("Failed to fetch original photo")
                        storageDownloadMessage = "下载失败：相机未返回图片，请检查连接后重试"
                        callback(false)
                    }
                    return@launch
                }

                var failureReason: String? = null
                val saved = ByteArrayInputStream(photoBytes).use { inputStream ->
                    ImageAutoProcessor.processIncomingCameraPhoto(
                        context = ctx,
                        imageStream = inputStream,
                        currentLut = null,
                        intensity = 1.0f,
                        autoApplyLut = false,
                        autoApplyBorder = false,
                        saveMode = SaveMode.ORIGINAL_ONLY,
                        borderState = null,
                        currentXmp = null,
                        sourceFileName = file?.fileName,
                        onOriginalSaved = { stampGpsIfEnabled(it) },
                        onProcessingError = { failureReason = it }
                    )
                }

                check(saved) { failureReason ?: "照片处理或保存失败，未完成下载" }
                TransferCenter.complete(transferId)
                withContext(Dispatchers.Main) {
                    if (file != null) {
                        val idx = storageFiles.indexOf(file)
                        if (idx >= 0) storageFiles[idx] = file.copy(isDownloaded = true)
                    }
                    addLogInner("Download and save successful: ${file?.fileName ?: handle}")
                    storageDownloadMessage = "${file?.fileName ?: handle} 原图已保存到手机相册"
                    callback(true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TransferCenter.fail(transferId, e.message ?: "下载失败")
                withContext(Dispatchers.Main) {
                    addLogInner("⚠️ 下载失败: ${file?.fileName ?: handle} (${e.message})")
                    storageDownloadMessage = "下载失败：${e.message ?: "请重试"}"
                    callback(false)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { downloadingPhotoHandles = emptySet() }
            }
        }
    }

    fun clearLogs() {
        logs.clear()
    }

    private fun addLogInner(msg: String) {
        // Network callbacks also arrive on IO; keep Compose state and the formatter on Main.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            viewModelScope.launch(Dispatchers.Main.immediate) { addLogInner(msg) }
            return
        }
        ptpClient.recordDiagnostic(msg)
        // 同时进统一运行日志，设置页的「运行日志」可以直接看到，不必再接 adb
        com.lutcamera.lutcamerapro.log.DoukLog.i("ViewModel", "STEP", "msg" to msg)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logs.add(0, "$timestamp  $msg")
        if (logs.size > 40) {
            logs.removeAt(logs.size - 1)
        }
    }

    fun addLog(msg: String) {
        viewModelScope.launch(Dispatchers.Main) {
            addLogInner(msg)
        }
    }

    fun getShutterProgress(): Float {
        val idx = availableShutterSpeeds.indexOf(shutterSpeed)
        return if (idx >= 0) idx.toFloat() / (availableShutterSpeeds.size - 1).coerceAtLeast(1) else 0.5f
    }

    @JvmName("setShutterSpeedSetting")
    fun setShutterSpeed(value: String) {
        shutterSpeed = value
        if (!isConnected) return
        val option = cameraShutterOptions.find { it.label == value }
        val raw = option?.raw ?: parseShutterRaw(value)
        val dataType = option?.dataType ?: 0x0006
        if (raw <= 0) { addLogInner("Shutter speed options not ready"); return }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = ptpClient.setPropRawValue(0x500D.toShort(), dataType, raw)
            withContext(Dispatchers.Main) {
                if (ok) { addLogInner("Shutter speed applied: $value"); lastParamMessage = "快门已写入 $value" }
                else {
                    val msg = propFailMessage("Shutter speed", ptpClient.lastResponseCode)
                    addLogInner(msg); lastParamMessage = msg
                    refreshCameraParams(silent = true)
                }
            }
        }
    }

    @JvmName("setApertureSetting")
    fun setAperture(value: String) {
        aperture = value
        if (!isConnected) return
        val option = cameraApertureOptions.find { it.label == value }
        val raw = option?.raw ?: parseApertureRaw(value)
        val dataType = option?.dataType ?: 0x0004
        if (raw <= 0) { addLogInner("Aperture options not ready"); return }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = ptpClient.setPropRawValue(0x5007.toShort(), dataType, raw)
            withContext(Dispatchers.Main) {
                if (ok) { addLogInner("Aperture applied: $value"); lastParamMessage = "光圈已写入 $value" }
                else {
                    val msg = propFailMessage("Aperture", ptpClient.lastResponseCode)
                    addLogInner(msg); lastParamMessage = msg
                    refreshCameraParams(silent = true)
                }
            }
        }
    }

    @JvmName("setIsoSetting")
    fun setIso(value: String) {
        iso = value
        if (!isConnected) return
        val auto = value == "自动"
        val option = cameraIsoOptions.find { it.label == value }
        val raw = if (auto) 0xFFFFL else (option?.raw ?: parseIsoRaw(value))
        val dataType = option?.dataType ?: 0x0004
        if (raw <= 0) { addLogInner("ISO options not ready"); return }
        viewModelScope.launch(Dispatchers.IO) {
            // 自动 ISO：先按 PTP 惯例把 0x500F 写成 0xFFFF；机身不接受时，
            // 退回「ISO 自动控制」(0xD0AB) 打开，保证自动档真的能落到相机上。
            var ok = ptpClient.setPropRawValue(0x500F.toShort(), dataType, raw)
            // 描述写 UINT32、机身只收 16 位时补一次 UINT16，别让「自动」点了没生效。
            if (!ok && auto) ok = ptpClient.setPropRawValue(0x500F.toShort(), 0x0004, 0xFFFFL)
            if (!ok && auto) {
                val type = extraProps["isoAuto"]?.options?.firstOrNull()?.dataType ?: 0x0007
                ok = ptpClient.setPropRawValue(0xD0AB.toShort(), type, 1L)
                if (ok) extraProps["isoAuto"] = (extraProps["isoAuto"] ?: ExtraPropState()).copy(value = "开启")
            }
            withContext(Dispatchers.Main) {
                if (ok) {
                    addLogInner("ISO applied: $value")
                    lastParamMessage = if (auto) "ISO 已切到自动" else "ISO 已写入 $value"
                    refreshCameraParams(silent = true)
                } else {
                    val msg = propFailMessage("ISO", ptpClient.lastResponseCode)
                    addLogInner(msg); lastParamMessage = msg
                    refreshCameraParams(silent = true)
                }
            }
        }
    }

    @JvmName("setShootModeSetting")
    fun setShootMode(value: String) {
        shootMode = value
        addLogInner("拍摄模式: $value")
    }

    fun setShutterFromProgress(p: Float) {
        val list = availableShutterSpeeds
        if (list.isEmpty()) return
        val idx = (p.coerceIn(0f, 1f) * (list.size - 1)).toInt()
        setShutterSpeed(list[idx])
    }

    fun getApertureProgress(): Float {
        val idx = availableApertures.indexOf(aperture)
        return if (idx >= 0) idx.toFloat() / (availableApertures.size - 1).coerceAtLeast(1) else 0.4f
    }

    fun setApertureFromProgress(p: Float) {
        val list = availableApertures
        if (list.isEmpty()) return
        val idx = (p.coerceIn(0f, 1f) * (list.size - 1)).toInt()
        setAperture(list[idx])
    }

    fun getIsoProgress(): Float {
        val idx = availableIsos.indexOf(iso)
        return if (idx >= 0) idx.toFloat() / (availableIsos.size - 1).coerceAtLeast(1) else 0.3f
    }

    fun setIsoFromProgress(p: Float) {
        val list = availableIsos
        if (list.isEmpty()) return
        val idx = (p.coerceIn(0f, 1f) * (list.size - 1)).toInt()
        setIso(list[idx])
    }

    // ===== Douk：机身状态与扩展曝光参数（全部来自真实 PTP 读取，读不到就如实显示） =====
    var batteryLevel by mutableStateOf<Int?>(null)
        private set
    var storageFreeBytes by mutableStateOf<Long?>(null)
        private set
    var storageTotalBytes by mutableStateOf<Long?>(null)
        private set
    var storageFreeImages by mutableIntStateOf(0)
        private set
    var lensInfo by mutableStateOf("相机未上报镜头")
        private set

    /** 当前实际焦距（如 18mm / 50mm），与镜头规格分开显示，设备页标题行用。 */
    var lensFocalText by mutableStateOf("")
        private set
    var exposureComp by mutableStateOf("相机未上报")
        private set
    var meteringMode by mutableStateOf("相机未上报")
        private set
    var focusModeLabel by mutableStateOf("相机未上报")
        private set
    var vrMode by mutableStateOf("机身菜单控制")
        private set
    var toneMode by mutableStateOf("机身菜单控制")
        private set
    var pictureControl by mutableStateOf("机身菜单控制")
        private set

    /** 控制页里可以直接读写的尼康私有属性（减震 / 动态 D-Lighting / 闪光模式）。 */
    data class ExtraPropState(
        val value: String = "相机未上报",
        val options: List<CameraOption> = emptyList(),
        val writable: Boolean = false
    )

    val extraProps = mutableStateMapOf<String, ExtraPropState>()

    // ===== 属性可写性：直接来自 GetDevicePropDesc 的 writable 位与档位数 =====
    // 相机在 AUTO / 场景模式下会把快门、光圈锁成「只有一个值、不可写」，
    // 这时手机下发必然无效，界面要如实置灰并说明原因，而不是让用户以为 App 坏了。
    var shutterWritable by mutableStateOf(true)
        private set
    var apertureWritable by mutableStateOf(true)
        private set
    var isoWritable by mutableStateOf(true)
        private set
    /** 0x500E 当前曝光模式码：1=M 3=A 4=S 5=P，其余（2 自动 / 6 以上场景）视为机身接管曝光 */
    var exposureProgram by mutableIntStateOf(-1)
        private set

    /** 最近一次参数下发的真实结果（成功/被相机的哪个响应码拒掉），控制页直接显示这句话 */
    var lastParamMessage by mutableStateOf("")
        private set

    /** 机顶拨盘不在 M/A/S/P 时，快门与光圈由相机自己决定，手机改不动 */
    val autoExposureLocked: Boolean
        get() = exposureProgram != -1 && exposureProgram !in listOf(1, 3, 4, 5)
    /** 0x5005 白平衡（可写枚举） */
    var whiteBalance by mutableStateOf("相机未上报")
        private set
    /** 0x500E 曝光模式（1=M 2=AUTO 3=A 4=S 5=P，可写） */
    var exposureModeLabel by mutableStateOf("相机未上报")
        private set
    /** 0x5013 释放模式（单张/低速/高速/C15/C30/C60） */
    var driveMode by mutableStateOf("相机未上报")
        private set
    /** 0x5018 低速连拍张数：只在释放模式为低速时下发 */
    var burstCount by mutableStateOf("1 张")
        private set
    /** 0x501C AF 区域模式（部分机身不开放） */
    var afAreaMode by mutableStateOf("相机未上报")
        private set
    /** 0x1D005 拍摄对象侦测（人物/动物/鸟类等，部分机身不开放） */
    var subjectDetection by mutableStateOf("相机未上报")
        private set
    /** 0x5003 ImageSize：数组型属性，单独解析 */
    var imageSizeLabel by mutableStateOf("相机未上报")
        private set
    var imageSizeOptions by mutableStateOf<List<CameraOption>>(emptyList())
        private set
    private var imageSizeRawByLabel: Map<String, String> = emptyMap()
    private var afAreaPropId: Int = 0x501C

    var whiteBalanceOptions by mutableStateOf<List<CameraOption>>(emptyList())
        private set
    var exposureModeOptions by mutableStateOf<List<CameraOption>>(emptyList())
        private set
    var driveModeOptions by mutableStateOf<List<CameraOption>>(emptyList())
        private set
    var burstCountOptions by mutableStateOf<List<CameraOption>>(emptyList())
        private set
    var afAreaModeOptions by mutableStateOf<List<CameraOption>>(emptyList())
        private set
    var subjectDetectionOptions by mutableStateOf<List<CameraOption>>(emptyList())
        private set

    var exposureCompOptions by mutableStateOf<List<CameraOption>>(emptyList())
        private set
    var meteringOptions by mutableStateOf<List<CameraOption>>(emptyList())
        private set
    var focusModeOptions by mutableStateOf<List<CameraOption>>(emptyList())
        private set
    var focusModeWritable by mutableStateOf(false)
        private set

    /** 机身信息（电量/存储/镜头）是否已经完成第一次读取，避免设备页先闪一行「未上报」 */
    var bodyStatusLoaded by mutableStateOf(false)
        private set

    /** 连接成功/断开时刷新设备页需要的电池、存储与镜头信息 */
    fun refreshBodyStatus() {
        if (!isConnected) {
            bodyStatusLoaded = false
            batteryLevel = null
            storageFreeBytes = null
            storageTotalBytes = null
            storageFreeImages = 0
            lensInfo = "未连接"
            lensFocalText = ""
            exposureComp = "未连接"
            meteringMode = "未连接"
            focusModeLabel = "未连接"
            extraProps.clear()
            return
        }
        // 对焦/测光/白平衡等扩展属性单独补读，不阻塞电量与存储的首屏显示
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { refreshExtendedProps() }
            // 刚连上时个别属性还没进相机属性表；只在仍有「未上报」时补读一次，
            // 不再无条件把 40+ 个属性重复读两遍，减少连接瞬间的 PTP 洪泛。
            kotlinx.coroutines.delay(2500)
            if (isConnected && extendedPropsMissing()) runCatching { refreshExtendedProps() }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val battery = runCatching {
                ptpClient.getDevicePropValue(0x5001.toShort())?.firstOrNull()?.toInt()
            }.getOrNull()?.takeIf { it in 0..100 }
            val cap = runCatching { ptpClient.readStorageCapacity() }.getOrNull()
            // 0x5008 FocalLength：Nikon 以 1/100 mm 上报，这是「当前焦距」，不是镜头型号。
            val focalRaw = runCatching { ptpClient.fetchPropDesc(0x5008.toShort())?.currentRaw }.getOrNull()
            suspend fun propRaw(id: Int): Long? = runCatching {
                ptpClient.fetchPropDesc(id.toShort())?.currentRaw
            }.getOrNull()
            // 镜头信息用 Nikon 的私有属性拼：0xD0E3/D0E4 是最近/最远焦距，
            // 0xD0E5/D0E6 是两端最大光圈（都是 1/100 单位）。这样显示的是镜头规格，
            // 不再是「镜头型号」里放一个当前焦距。
            val minFocal = propRaw(0xD0E3)
            val maxFocal = propRaw(0xD0E4)
            val apMin = propRaw(0xD0E5)
            val apMax = propRaw(0xD0E6)
            fun mm(raw: Long): String {
                val v = raw / 100.0
                return if (v % 1.0 == 0.0) v.toInt().toString()
                else String.format(java.util.Locale.US, "%.1f", v)
            }
            fun fNum(raw: Long): String {
                val v = raw / 100.0
                return if (v % 1.0 == 0.0) v.toInt().toString()
                else String.format(java.util.Locale.US, "%.1f", v)
            }
            // 镜头规格只保留型号/焦段/光圈；当前焦距单独放 lensFocalText，
            // 设备页才能按「镜头规格 | 当前焦距」显示，型号过长时可以单独滚动。
            val focalText = if (focalRaw != null && focalRaw > 0) "${mm(focalRaw)}mm" else ""
            val lens = when {
                minFocal != null && maxFocal != null && minFocal > 0 && maxFocal >= minFocal -> {
                    val range = if (maxFocal == minFocal) "${mm(maxFocal)}mm"
                    else "${mm(minFocal)}-${mm(maxFocal)}mm"
                    val aperture = when {
                        apMin != null && apMax != null && apMin > 0 && apMax > 0 ->
                            if (apMin == apMax) " f/${fNum(apMin)}"
                            else " f/${fNum(apMin)}-${fNum(apMax)}"
                        else -> ""
                    }
                    "NIKKOR $range$aperture"
                }
                focalText.isNotBlank() -> "相机未上报型号"
                else -> "相机未上报镜头"
            }
            withContext(Dispatchers.Main) {
                batteryLevel = battery
                if (cap != null) {
                    storageFreeBytes = cap.freeBytes.takeIf { it > 0 }
                    storageTotalBytes = cap.totalBytes.takeIf { it > 0 }
                    storageFreeImages = cap.freeImages
                } else {
                    storageFreeBytes = null
                    storageTotalBytes = null
                    storageFreeImages = 0
                }
                lensInfo = lens
                lensFocalText = focalText
                bodyStatusLoaded = true
                addLogInner(
                    "机身状态：电量 " + (battery?.let { "$it%" } ?: "未上报") +
                        "，存储 " + (storageFreeBytes?.let { "${it / 1024 / 1024}MB 可用" } ?: "未上报") +
                        "，$lens" + (if (focalText.isNotBlank()) " · 当前焦距 $focalText" else "")
                )
            }
        }
    }

    /** 扩展属性里还有没有「相机未上报」，用来决定是否值得再补读一轮。 */
    private fun extendedPropsMissing(): Boolean {
        fun missing(v: String) = v.isBlank() || v == "相机未上报" || v == "—"
        return listOf(
            exposureComp, meteringMode, focusModeLabel, whiteBalance, exposureModeLabel,
            driveMode, burstCount, afAreaMode, subjectDetection, imageSizeLabel
        ).any(::missing)
    }

    /** 读取曝光补偿(0x5010)、测光(0x500B)、对焦模式(0x500A)、白平衡(0x5005)、曝光模式(0x500E)、驱动模式(0x5013)
     *  尼康有时不返回 DevicePropDesc（回 0x200A/0x2019），这里退一步直接读当前值，值本身是机身真实上报的。 */
    suspend fun refreshExtendedProps() {
        if (!isConnected) return
        val evDesc = descOrNull(0x5010)
        val meteringDesc = descOrNull(0x500B)
        val focusDesc = descOrNull(0x500A)
        // 实时取景下的尼康对焦模式是 0xD061（PTP_PROP_NIKON_LV_AFMODE）。
        // 0x500A 在 Z50II 上虽然被描述为 writable=false，但静态模式实际仍可写，所以两个都取。
        val nikonFocusDesc = descOrNull(0xD061)
        val wbDesc = descOrNull(0x5005)
        val modeDesc = descOrNull(0x500E)
        val driveDesc = descOrNull(0x5013)
        val burstDesc = descOrNull(0x5018)
        val afAreaDesc = descOrNull(0x501C)
        val afAreaFallbackDesc = if (afAreaDesc == null) descOrNull(0xD05D) else null
        afAreaPropId = if (afAreaDesc != null) 0x501C else 0xD05D
        val subjectDesc = descOrNull(0x1D005)
        val evRaw = evDesc?.currentRaw ?: rawValueOrNull(0x5010)
        val meteringRaw = meteringDesc?.currentRaw ?: rawValueOrNull(0x500B)
        // 0xD061 是 Z50II 真正可写的对焦模式（实测可写并回读成功）；0x500A 只读，仅作兜底显示。
        val stillFocusLabel = focusDesc?.currentRaw?.let { focusModeLabelOf(it) }
        val liveFocusLabel = nikonFocusDesc?.currentRaw?.let { nikonFocusModeLabelOf(it) }
        val focusLabel = liveFocusLabel ?: stillFocusLabel
            ?: rawValueOrNull(0xD061)?.let { nikonFocusModeLabelOf(it) }
            ?: rawValueOrNull(0x500A)?.let { focusModeLabelOf(it) }
        val wbRaw = wbDesc?.currentRaw ?: rawValueOrNull(0x5005)
        val modeRaw = modeDesc?.currentRaw ?: rawValueOrNull(0x500E)
        val driveRaw = driveDesc?.currentRaw ?: rawValueOrNull(0x5013)
        val burstRaw = burstDesc?.currentRaw ?: rawValueOrNull(0x5018)
        val afAreaRaw = (afAreaDesc ?: afAreaFallbackDesc)?.currentRaw ?: rawValueOrNull(0x501C) ?: rawValueOrNull(0xD05D)
        val subjectRaw = subjectDesc?.currentRaw ?: rawValueOrNull(0x1D005)
        // 档位是相机真实枚举，名称必须按这份实测值来写，不能凭印象拍脑袋。
        // 这里把关键属性的全部枚举值落日志，后续档位映射直接对照本机 Z50II 的返回。
        if (DETAILED_PROP_DUMP) {
            listOf(0x5013, 0x500B, 0x500A, 0x501C, 0xD05D, 0x1D005, 0x1D006, 0xD09D,
                0xD061,
                0xD130, 0xD131, 0xD132, 0xD0C0, 0xD0C1, 0xD0C2, 0xD0C3,
                0xD078, 0xD079, 0xD1B0, 0xD1B5, 0xD030, 0x5004, 0xD016, 0x5003, 0x5018,
                0xD0AF, 0xD0A0, 0xD0A7, 0xD0A2, 0xD0A8, 0xD0AA, 0xD0B7, 0x1D032,
                0xD0BC, 0xD1FA, 0xD1F8, 0xD0AD, 0xD0AE, 0xD20F, 0xD210,
                0xD1FB, 0xD1FC, 0xD1FE).forEach { prop ->
                val d = descOrNull(prop)
                com.lutcamera.lutcamerapro.log.DoukLog.i(
                    "Prop", "ENUM_DUMP",
                    "prop" to "0x%04X".format(prop),
                    "cur" to (d?.currentRaw ?: "null"),
                    "writable" to (d?.writable ?: false),
                    "values" to (d?.enumValues?.joinToString(",") ?: "none")
                )
            }
        }
        val extras = readExtraProps()
        // 0x5003 ImageSize 在 Z 系上是 PTP 字符串（"5568x3712"），描述符里的枚举也是字符串。
        val imageSizeBytes = runCatching { ptpClient.getDevicePropValue(0x5003.toShort()) }.getOrNull()
        val imageSizeDescBytes = runCatching { ptpClient.getDevicePropDesc(0x5003.toShort()) }.getOrNull()
        val imageSizeParsed = parseImageSizeString(imageSizeBytes)
        com.lutcamera.lutcamerapro.log.DoukLog.i(
            "Prop", "RAW_ARRAY",
            "prop" to "0x5003",
            "len" to (imageSizeBytes?.size ?: 0),
            "bytes" to (imageSizeBytes?.joinToString("") { "%02X".format(it) } ?: "null")
        )
        // 「画质」相关候选：标准 0x5003/0x5004，尼康私有 0xF009(ImageCompression)/0xF00A(ImageSize)，
        // 先把机身到底报不报这四个码记进日志
        listOf(0x5003, 0x5004, 0xF009, 0xF00A).forEach { runCatching { ptpClient.fetchPropDesc(it.toShort()) } }
        withContext(Dispatchers.Main) {
            fun keepLabel(oldValue: String, newValue: String?): String =
                newValue ?: oldValue.takeIf { it.isNotBlank() && it != "相机未上报" && it != "—" } ?: "相机未上报"
            exposureComp = keepLabel(exposureComp, evRaw?.let { decodeExposureComp(it) })
            meteringMode = keepLabel(meteringMode, meteringRaw?.let { meteringLabel(it) })
            focusModeLabel = keepLabel(focusModeLabel, focusLabel)
            whiteBalance = keepLabel(whiteBalance, wbRaw?.let { whiteBalanceLabel(it) })
            exposureModeLabel = keepLabel(exposureModeLabel, modeRaw?.let { exposureModeLabelOf(it) })
            driveMode = keepLabel(driveMode, driveRaw?.let { driveModeLabel(it) })
            burstCount = keepLabel(burstCount, burstRaw?.let { if (it == 0L) "不限张数" else "$it 张" })
            afAreaMode = keepLabel(afAreaMode, afAreaRaw?.let { afAreaLabel(it) })
            subjectDetection = keepLabel(subjectDetection, subjectRaw?.let { subjectLabel(it) })
            imageSizeLabel = keepLabel(imageSizeLabel, imageSizeParsed)
            val sizeOptions = parseImageSizeOptions(imageSizeDescBytes)
            if (sizeOptions.isNotEmpty()) {
                imageSizeOptions = sizeOptions.map { (label, _) -> CameraOption(label, 0, 0) }
                imageSizeRawByLabel = sizeOptions.toMap()
            }
            enumOptions(evDesc, ::decodeExposureComp)?.let { exposureCompOptions = it }
            enumOptions(meteringDesc, ::meteringLabel)?.let { meteringOptions = it }
            // 0xD061 是实际可写属性，档位用它；没有 0xD061 时才退回 0x500A（去掉仅视频可用的 AF-F）。
            val liveFocusOptions = enumOptions(nikonFocusDesc, ::nikonFocusModeLabelOf)
            val standardFocusOptions = enumOptions(focusDesc, ::focusModeLabelOf)?.filter { it.raw != 0x8013L }
            focusModeOptions = liveFocusOptions ?: standardFocusOptions ?: focusModeOptions
            // 0x500A 在 Z50II 的描述里是 writable=false；0xD061 writable=true，才是可真正下发的属性。
            focusModeWritable = nikonFocusDesc?.writable == true || focusModeOptions.isNotEmpty()
            enumOptions(wbDesc, ::whiteBalanceLabel)?.let { whiteBalanceOptions = it }
            enumOptions(modeDesc, ::exposureModeLabelOf)?.let { exposureModeOptions = it }
            enumOptions(driveDesc, ::driveModeLabel)?.let { driveModeOptions = it }
            burstCountOptions = if (burstDesc != null && burstDesc.rangeMax > burstDesc.rangeMin) {
                (burstDesc.rangeMin..minOf(burstDesc.rangeMax, 100L)).map {
                    CameraOption(if (it == 0L) "不限张数" else "$it 张", it, burstDesc.dataType)
                }
            } else if (burstDesc != null && burstDesc.writable) {
                // Z 系部分固件只回当前值不回范围；低速 CL 仍可写 1—5 张
                (1L..5L).map { CameraOption("$it 张", it, burstDesc.dataType) }
            } else burstCountOptions
            enumOptions(afAreaDesc ?: afAreaFallbackDesc, ::afAreaLabel)?.let { afAreaModeOptions = it }
            enumOptions(subjectDesc, ::subjectLabel)?.let { subjectDetectionOptions = it }
            val mergedExtras = LinkedHashMap<String, ExtraPropState>()
            for ((key, fresh) in extras) {
                val old = extraProps[key]
                mergedExtras[key] =
                    if (fresh.value == "相机未上报" && old != null && old.value != "相机未上报") old else fresh
            }
            extraProps.clear()
            extraProps.putAll(mergedExtras)
        }
    }

    private suspend fun descOrNull(prop: Int): NikonPtpClient.PropDesc? {
        repeat(2) { attempt ->
            val desc = runCatching { ptpClient.fetchPropDesc(prop.toShort()) }.getOrNull()
            if (desc != null) return desc
            if (attempt == 0) kotlinx.coroutines.delay(220)
        }
        return null
    }

    /**
     * 控制页可直接下发/读取的尼康私有属性。
     * 属性号来自 libgphoto2 ptp.h，并在 Nikon Z50II（固件 V1.11）上逐项验证过可读/可写。
     *
     * 注意：Z8 / Z9 / Z6III / Zf 等机型的视频、AF 私有属性并不完全一致。
     * 如需为其他机型改表，请新增按机型区分的映射（或在 key 后缀标注机型），
     * 不要直接改这里 Z50II 已验证的编号；改动同步记录到项目根目录
     * 《尼康机型控制代码对照.md》。
     */
    private val extraPropIds = mapOf(
        "vr" to 0xD138,
        "activeD" to 0xD14E,
        "imageArea" to 0xD030,
        "imageQuality" to 0x5004,
        "rawCompression" to 0xD016,
        "movieVr" to 0xD1F9,
        "movieWb" to 0xD23A,
        "movieMetering" to 0xD1AF,
        "movieShutter" to 0xD1A8,
        "movieAperture" to 0xD1A9,
        "movieIso" to 0xD1AA,
        "movieEv" to 0xD1AB,
        // 视频格式 / 音频 / 对焦：全部走机身 descriptor 的原始枚举值，绝不自己拼档位
        "movieFileType" to 0xD0AF,
        "movieScreenSize" to 0xD0A0,
        "movieQuality" to 0xD0A7,
        "movieMic" to 0xD0A2,
        "movieMicLevel" to 0xD0A8,
        "movieWindNoise" to 0xD0AA,
        "movieFlicker" to 0xD0B7,
        "movieHighFreqFlicker" to 0x1D032,
        "movieAutoDistortion" to 0xD0BC,
        "movieFocusMode" to 0xD1FA,
        "movieAfAreaMode" to 0xD1F8,
        "movieIsoAuto" to 0xD0AD,
        "movieIsoAutoHigh" to 0xD0AE,
        // 静态照片的 ISO 自动控制 / 自动上限（和视频的 0xD0AD/0xD0AE 是两套）
        "isoAuto" to 0xD0AB,
        "isoAutoHigh" to 0xD0AC,
        "movieCrop" to 0xD20F,
        "movieAutoDx" to 0xD210,
        "movieTimeCode" to 0xD1FB,
        "movieCountUp" to 0xD1FC,
        "movieDropFrame" to 0xD1FE
    )

    private fun extraLabel(key: String, raw: Long): String = when (key) {
        "vr" -> when (raw) {
            0x0000L, 0x8000L -> "关闭"
            0x0001L, 0x8001L -> "开启"
            0x0002L, 0x8002L -> "运动"
            else -> "未识别减震档位"
        }
        "activeD" -> when (raw) {
            0x0000L, 0x8000L -> "关闭"
            0x0001L, 0x8001L -> "低"
            0x0002L, 0x8002L -> "标准"
            0x0003L, 0x8003L -> "高"
            0x0004L, 0x8004L -> "超高"
            0x0005L, 0x8005L -> "自动"
            else -> "未识别 D-Lighting 档位"
        }
        "imageArea" -> when (raw) {
            0L -> "FX 全画幅"
            1L -> "5:4"
            2L -> "DX 裁切"
            3L -> "1.2× 裁切"
            4L -> "1:1"
            5L -> "16:9"
            else -> "图像区域 $raw"
        }
        "imageQuality" -> when (raw) {
            0L -> "JPEG 基本"
            1L -> "JPEG 标准"
            2L -> "JPEG 精细"
            3L -> "JPEG 基本 ★"
            4L -> "JPEG 标准 ★"
            5L -> "JPEG 精细 ★"
            6L -> "TIFF"
            7L -> "RAW（NEF）"
            8L -> "RAW + JPEG 基本"
            9L -> "RAW + JPEG 标准"
            10L -> "RAW + JPEG 精细"
            11L -> "RAW + JPEG 基本 ★"
            12L -> "RAW + JPEG 标准 ★"
            13L -> "RAW + JPEG 精细 ★"
            else -> "图像品质 $raw"
        }
        "rawCompression" -> when (raw) {
            0L -> "无损压缩"
            1L -> "压缩"
            2L -> "未压缩"
            3L -> "高效 ★"
            4L -> "高效"
            else -> "RAW 压缩 $raw"
        }
        "movieVr" -> when (raw) {
            0L -> "关闭"
            1L -> "开启（标准）"
            2L -> "运动"
            100L -> "电子减震"
            else -> "机身档位"
        }
        "movieWb" -> whiteBalanceLabel(raw)
        "movieMetering" -> meteringLabel(raw)
        "movieShutter" -> shutterLabel(raw)
        "movieAperture" -> if (raw > 0) String.format(java.util.Locale.US, "f/%.1f", raw / 100.0) else "自动"
        "movieIso" -> if (raw > 0 && raw < 0xFFFF) "ISO $raw" else "自动"
        "isoAuto" -> onOffLabel(raw)
        "isoAutoHigh" -> if (raw > 0 && raw < 0xFFFF) "ISO $raw" else "自动"
        "movieEv" -> decodeExposureComp(raw)
        "movieFileType" -> movieFileTypeLabel(raw)
        "movieScreenSize" -> movieScreenSizeLabel(raw)
        "movieQuality" -> when (raw) {
            0L -> "高画质"
            1L -> "标准"
            2L -> "轻量"
            else -> "画质档位 $raw"
        }
        "movieMic" -> when (raw) {
            0L -> "关闭"
            1L -> "低"
            2L -> "标准"
            3L -> "高"
            4L -> "自动"
            else -> "灵敏度 $raw"
        }
        "movieMicLevel" -> "电平 $raw"
        "movieWindNoise" -> onOffLabel(raw)
        "movieFlicker" -> when (raw) {
            0L -> "关闭"
            1L -> "开启"
            2L -> "开启（50Hz）"
            3L -> "开启（60Hz）"
            else -> onOffLabel(raw)
        }
        "movieHighFreqFlicker" -> onOffLabel(raw)
        "movieAutoDistortion" -> onOffLabel(raw)
        "movieFocusMode" -> when (raw) {
            0L -> "MF 手动对焦"
            1L -> "AF-S 单次伺服"
            2L -> "AF-C 连续伺服"
            4L -> "AF-F 全时伺服"
            else -> "对焦模式 $raw"
        }
        "movieAfAreaMode" -> afAreaLabel(raw)
        "movieIsoAuto" -> onOffLabel(raw)
        "movieIsoAutoHigh" -> if (raw > 0) "ISO $raw" else "自动"
        "movieCrop" -> when (raw) {
            0L -> "FX 全画幅"
            1L -> "DX 裁切"
            else -> "裁切档位 $raw"
        }
        "movieAutoDx" -> onOffLabel(raw)
        "movieTimeCode" -> when (raw) {
            0L -> "关闭"
            1L -> "开启"
            2L -> "开启（自由运行）"
            else -> onOffLabel(raw)
        }
        "movieCountUp" -> when (raw) {
            0L -> "记录运行"
            1L -> "自由运行"
            else -> "计数方式 $raw"
        }
        "movieDropFrame" -> onOffLabel(raw)
        else -> "未识别"
    }

    private fun onOffLabel(raw: Long): String = when (raw) {
        0L, 0x8000L -> "关闭"
        1L, 0x8001L -> "开启"
        2L, 0x8002L, 65535L -> "自动"
        else -> if (raw > 0) "开启" else "关闭"
    }

    /** MovFileType 是打包值：[保留][编码][位深][封装]，Z50II 等新机身也可能只给 0/1。 */
    private fun movieFileTypeLabel(raw: Long): String {
        if (raw in 0L..3L) return if (raw == 0L) "MOV" else "MP4"
        val codec = ((raw ushr 16) and 0xFF).toInt()
        val depth = ((raw ushr 8) and 0xFF).toInt()
        val container = (raw and 0xFF).toInt()
        val codecName = when (codec) {
            0x00 -> "H.264"
            0x01 -> "H.265"
            0x02 -> "N-RAW"
            0x10 -> "ProRes 422 HQ"
            0x11 -> "ProRes RAW HQ"
            0x31 -> "R3D NE"
            else -> "编码 0x%02X".format(codec)
        }
        val containerName = when (container) {
            0 -> "MOV"
            1 -> "MP4"
            2 -> "NEV"
            3 -> "R3D"
            else -> "容器 $container"
        }
        val depthText = if (depth in 8..12) " · ${depth}bit" else ""
        return "$codecName$depthText · $containerName"
    }

    /** MovieRecordScreenSize 8 字节：[宽 16][高 16][帧率 8][保留]，标签直接给真实像素和 fps。 */
    private fun movieScreenSizeLabel(raw: Long): String {
        val width = ((raw ushr 48) and 0xFFFF).toInt()
        val height = ((raw ushr 32) and 0xFFFF).toInt()
        val fps = ((raw ushr 16) and 0xFF).toInt()
        if (width !in 640..8192 || height !in 360..5000 || fps !in 1..240) {
            return "机身档位"
        }
        val name = when {
            width >= 7000 -> "8K"
            width >= 5800 -> "6K"
            width >= 3800 -> "4K"
            width >= 2500 -> "2.5K"
            width >= 1900 -> "1080p"
            else -> "720p"
        }
        return "$name $width×$height · ${fps}p"
    }

    /** 尼康快门速度按 1/10000 秒上报；整数秒时显示 1s 而不是 1/1s。 */
    private fun shutterLabel(raw: Long): String {
        // 视频快门是另一套编码：0x10000 标志位 | 分母，例如 0x10032 = 1/50s。
        // 以前不带标志位直接按 1/10000 秒解码，1/50s 会被显示成 6.6s。
        if (raw and 0x10000L != 0L) {
            val denom = (raw and 0xFFFFL).toInt()
            if (denom > 0) return "1/${denom}s"
            return "自动"
        }
        if (raw <= 0) return "Bulb"
        val sec = raw / 10000.0
        if (sec >= 1.0) {
            return if (sec % 1.0 == 0.0) "${sec.toInt()}s"
            else String.format(java.util.Locale.US, "%.1fs", sec)
        }
        val denominator = (10000.0 / raw).toInt().coerceAtLeast(1)
        return "1/${denominator}s"
    }

    /** 读三个私有属性的描述与当前值；失败时退回直接读值，至少把当前状态显示出来。 */
    private suspend fun readExtraProps(): Map<String, ExtraPropState> {
        val result = mutableMapOf<String, ExtraPropState>()
        for ((key, prop) in extraPropIds) {
            val desc = descOrNull(prop)
            if (desc != null) {
                val value = extraLabel(key, desc.currentRaw)
                val options = if (desc.isEnum) {
                    desc.enumValues
                        .map { CameraOption(extraLabel(key, it), it, desc.dataType) }
                        .filter { !isUnlabeledOption(it.label) }
                } else if (desc.rangeMax > desc.rangeMin) {
                    val step = desc.rangeStep.takeIf { it > 0 } ?: 1L
                    val count = (desc.rangeMax - desc.rangeMin) / step
                    if (count in 1..64) {
                        (0..count).map {
                            val v = desc.rangeMin + it * step
                            CameraOption(extraLabel(key, v), v, desc.dataType)
                        }
                    } else emptyList()
                } else emptyList()
                // 尼康的 ISO 枚举里往往没有「自动」这一项，但机身确实支持 0xFFFF 自动档；
                // 补一个 Auto 选项，视频 ISO 也能选自动（以前只能选固定数值）。
                val withAuto = when {
                    (key == "movieIso" || key == "movieIsoAutoHigh") &&
                        options.none { it.label == "自动" } && desc.isEnum ->
                        listOf(CameraOption("自动", 0xFFFFL, desc.dataType)) + options
                    // 不少机身的视频光圈描述里只有 f/ 数值档，没有「自动」这一项；
                    // 但机身在视频曝光模式为 P/A 时确实支持自动光圈，raw 0 就是自动档。
                    // 补一个「自动」，否则视频页永远看不到自动挡。
                    key == "movieAperture" && options.none { it.label == "自动" } ->
                        listOf(CameraOption("自动", 0L, desc.dataType)) + options
                    else -> options
                }
                result[key] = ExtraPropState(value, withAuto, desc.writable)
            } else {
                val raw = rawValueOrNull(prop)
                result[key] = if (raw != null) {
                    ExtraPropState(extraLabel(key, raw), emptyList(), false)
                } else {
                    ExtraPropState("相机未上报", emptyList(), false)
                }
            }
        }
        // 机身的「视频 ISO 自动」(0xD0AD) 开着时，0xD1AA 仍会回报上一次的固定 ISO，
        // 只按 0xD1AA 显示就会和机身状态自相矛盾（刚点完自动又跳回 ISO 500）。
        val movieAutoOn = result["movieIsoAuto"]?.value?.contains("开") == true
        if (movieAutoOn) {
            result["movieIso"] = (result["movieIso"] ?: ExtraPropState()).copy(value = "自动")
        }
        return result
    }

    fun extraValue(key: String): String = extraProps[key]?.value ?: "—"
    fun extraOptions(key: String): List<String> = extraProps[key]?.options?.map { it.label } ?: emptyList()
    fun extraWritable(key: String): Boolean = extraProps[key]?.writable == true

    fun applyExtra(key: String, option: CameraOption) {
        val prop = extraPropIds[key] ?: return
        val current = extraProps[key] ?: ExtraPropState()
        extraProps[key] = current.copy(value = option.label)
        if (!isConnected) return
        viewModelScope.launch(Dispatchers.IO) {
            var ok = ptpClient.setPropRawValue(prop.toShort(), option.dataType, option.raw)
            // 视频光圈选「自动」：0xD1A9 收 0 表示自动，部分机身只接受 UINT16，
            // 还有的机身要先让曝光模式进 P/A 才认；这里按顺序退让，尽量让按钮点得动。
            if (!ok && key == "movieAperture" && option.raw == 0L) {
                ok = ptpClient.setPropRawValue(prop.toShort(), 0x0004, 0L)
                if (!ok) ok = ptpClient.setPropRawValue(prop.toShort(), option.dataType, 0xFFFFL)
                if (!ok) ok = ptpClient.setPropRawValue(prop.toShort(), 0x0004, 0xFFFFL)
            }
            // 视频 ISO 选「自动」：Z50II 对 0xD1AA=0xFFFF 常回 Device Busy(0x201C)。
            // 依次退回「视频 ISO 自动控制 0xD0AD=开」和「0x500F=0xFFFF」，三级都试，
            // 哪一级被机身接受就用哪一级，别让「自动」点了没反应。
            if (!ok && option.raw == 0xFFFFL) {
                if (key == "movieIso" || key == "movieIsoAutoHigh") {
                    ok = ptpClient.setPropRawValue(0xD0AD.toShort(), 0x0002, 1L)
                    if (!ok) ok = ptpClient.setPropRawValue(0xD0AD.toShort(), 0x0007, 1L)
                    if (!ok) ok = ptpClient.setPropRawValue(0xD0AD.toShort(), 0x0004, 1L)
                }
                if (!ok) ok = ptpClient.setPropRawValue(0x500F.toShort(), option.dataType, 0xFFFFL)
                // 少数机身 0x500F 的描述写 UINT32，实际只收 16 位；再补一次 UINT16。
                if (!ok) ok = ptpClient.setPropRawValue(0x500F.toShort(), 0x0004, 0xFFFFL)
                if (!ok) ok = ptpClient.setPropRawValue(prop.toShort(), 0x0004, 0xFFFFL)
                if (ok) addLogInner("$key 自动档：机身走的是回退路径")
            }
            withContext(Dispatchers.Main) {
                if (ok) {
                    lastParamMessage = "${extraLabel(key, option.raw)} 已写入 ${option.label}"
                    addLogInner("${key} 已下发: ${option.label}")
                    refreshCameraParams(silent = true)
                } else {
                    extraProps[key] = current
                    lastParamMessage = propFailMessage(key, ptpClient.lastResponseCode)
                    addLogInner(lastParamMessage)
                    refreshCameraParams(silent = true)
                }
            }
        }
    }

    /** 没有描述时直接读值（UINT16 优先，读不到再试 UINT32） */
    private suspend fun rawValueOrNull(prop: Int): Long? {
        val u16 = runCatching { ptpClient.getPropRawValue(prop.toShort(), 0x0004) }.getOrNull()
        if (u16 != null) {
            com.lutcamera.lutcamerapro.log.DoukLog.i("Prop", "VALUE_FALLBACK", "prop" to "0x%04X".format(prop), "raw" to u16)
            return u16
        }
        val u32 = runCatching { ptpClient.getPropRawValue(prop.toShort(), 0x0006) }.getOrNull()
        if (u32 != null) com.lutcamera.lutcamerapro.log.DoukLog.i("Prop", "VALUE_FALLBACK32", "prop" to "0x%04X".format(prop), "raw" to u32)
        return u32
    }

    /** 0x5005 白平衡档位（尼康枚举） */
    private fun whiteBalanceLabel(raw: Long): String = when (raw) {
        0x0000L, 0x0002L, 0x8000L -> "自动"
        0x0004L, 0x8003L -> "晴天"
        0x0005L, 0x8002L -> "荧光灯"
        0x0006L, 0x8001L -> "白炽灯"
        0x0007L, 0x8004L -> "闪光灯"
        0x8010L, 0x8005L -> "阴天"
        0x8011L, 0x8006L -> "阴影"
        0x8012L -> "色温（手选）"
        0x8013L, 0x8007L -> "预设手动"
        else -> "未识别白平衡"
    }

    /** 0x500E 曝光模式：1=M 2=AUTO 3=A 4=S 5=P */
    private fun exposureModeLabelOf(raw: Long): String = when (raw) {
        0x0001L, 0x8001L -> "M 全手动"
        0x0002L, 0x8002L -> "AUTO 自动"
        0x0003L, 0x8003L -> "A 光圈优先"
        0x0004L, 0x8004L -> "S 快门优先"
        0x0005L, 0x8000L -> "P 程序自动"
        else -> "未识别曝光模式"
    }

    /**
     * 0x5013 释放模式。
     * 关键修正：C15 与 C30 的编码原先写反了。真机反证——App 选「C30」下发 0x810F，
     * 相机屏幕显示的是 C15，说明 0x810F = C15；尼康是在原有 C30(0x8019) 之后追加的 C15，
     * 所以这两个档位的值并不是按速度递增排列的。
     */
    private fun driveModeLabel(raw: Long): String = when (raw) {
        0x0001L -> "单张拍摄"
        0x0002L -> "连拍"
        0x8000L + 0x10 -> "低速连拍 CL"
        0x8000L + 0x11 -> "高速连拍 CH"
        0x8019L -> "C30 高速连拍"
        0x810FL -> "C15 高速连拍"
        0x811EL -> "C60 高速连拍"
        else -> "释放模式 0x%04X".format(raw)
    }

    /** 0x501C AF 区域模式（本机 Z50II 未上报时不会列出档位） */
    private fun afAreaLabel(raw: Long): String = when (raw) {
        0x0002L -> "自动区域 AF"
        0x8010L -> "单点 AF"
        0x8011L -> "动态区域 AF"
        0x8017L -> "宽区域 AF（小）"
        0x8018L -> "宽区域 AF（大）"
        0x8019L -> "自动区域 AF（人物）"
        else -> "AF 区域 0x%04X".format(raw)
    }

    /** 0x1D005 拍摄对象侦测（本机未上报时只显示相机当前值或不可用） */
    private fun subjectLabel(raw: Long): String = when (raw) {
        0x0000L -> "关闭"
        0x0001L -> "人物"
        0x0002L -> "动物"
        0x0003L -> "鸟类"
        0x0004L -> "车辆"
        0x0005L -> "飞机"
        else -> "侦测对象 0x%04X".format(raw)
    }

    /** 相机报了、但我们还翻不出来的枚举档位：宁可不列，也不要让用户点到「未知档位」。 */
    private fun isUnlabeledOption(label: String): Boolean =
        label.startsWith("未识别") || label.contains(" 0x") || label.startsWith("档位 ")

    /**
     * 由相机上报的枚举值生成选项列表。
     * 只保留能翻译成真实档位名的项——列表里出现「未识别曝光模式 ×5」这种条目，
     * 用户一点就落到机身另一个档位，正是「改了跟相机对不上」的观感来源之一。
     */
    private fun enumOptions(
        desc: NikonPtpClient.PropDesc?,
        labelOf: (Long) -> String
    ): List<CameraOption>? {
        if (desc == null || !desc.isEnum) return null
        return desc.enumValues
            .map { CameraOption(labelOf(it), it, desc.dataType) }
            .filter { !isUnlabeledOption(it.label) }
            .distinctBy { "${it.label}:${it.raw}" }
    }

    /** C 档（C15/C30/C60）在机身上有硬前置条件，不满足时机身会自己降档，这里把原因说清楚。 */
    private fun hintDriveModeRestriction(label: String): String =
        if (label.contains("C15") || label.contains("C30") || label.contains("C60"))
            "（机身要求画质为 JPEG 标准、图像尺寸为「大」、释放模式拨盘在 c 档；不满足时它会自动降档）"
        else "（机身当前画质/尺寸/拨盘档位限制了这一档）"

    /**
     * 下发枚举类属性，并**以机身回读值**为准刷新界面。
     *
     * 尼康经常出现「回了 OK 但存的不是这一档」「回 OK 但机身其实没改」：
     * 只凭下发成功就更新界面，就会出现 App 显示 C30、相机屏幕显示 C15 这种错位。
     * 所以写完必须读回该属性的当前值，界面一律显示相机真实存档；不一致时明确提示。
     */
    private fun applyEnumPropChecked(
        propId: Int,
        name: String,
        option: CameraOption,
        setLabel: (String) -> Unit,
        labelOf: (Long) -> String
    ) {
        setLabel(option.label)
        if (!isConnected) return
        viewModelScope.launch(Dispatchers.IO) {
            val id = propId.toShort()
            val ok = ptpClient.setPropRawValue(id, option.dataType, option.raw)
            val actual = runCatching { descOrNull(propId)?.currentRaw }.getOrNull()
                ?: runCatching { ptpClient.getPropRawValue(id, option.dataType) }.getOrNull()
            withContext(Dispatchers.Main) {
                when {
                    actual != null -> {
                        val shown = labelOf(actual)
                        setLabel(shown)
                        if (actual == option.raw) {
                            addLogInner("$name 已生效：$shown")
                        } else {
                            // 回读值与请求值不同：显示按机身走，并告诉用户机身为什么没接受。
                            val why = when (name) {
                                "释放模式" -> hintDriveModeRestriction(option.label)
                                else -> "（机身当前画质/尺寸/拨盘档位限制了这一档）"
                            }
                            lastParamMessage = "$name 未生效，机身当前是「$shown」$why"
                            addLogInner("⚠️ $name 未生效：相机当前是「$shown」，不是「${option.label}」$why")
                            com.lutcamera.lutcamerapro.log.DoukLog.w(
                                "Prop", "SET_NOT_EFFECTIVE",
                                "prop" to "0x%04X".format(propId),
                                "want" to option.raw, "actual" to actual,
                                "code" to "0x%04X".format(ptpClient.lastResponseCode)
                            )
                        }
                    }
                    ok -> addLogInner("$name 已下发：${option.label}（机身未回读）")
                    else -> {
                        addLogInner(propFailMessage(name, ptpClient.lastResponseCode))
                        refreshCameraParams(silent = true)
                    }
                }
            }
        }
    }

    /** 下发白平衡 */
    fun applyWhiteBalance(option: CameraOption) {
        applyEnumPropChecked(0x5005, "白平衡", option, { whiteBalance = it }, ::whiteBalanceLabel)
    }

    /** 下发曝光模式（会改变机身 P/A/S/M 挡位） */
    fun applyExposureMode(option: CameraOption) {
        applyEnumPropChecked(0x500E, "曝光模式", option, { exposureModeLabel = it }, ::exposureModeLabelOf)
    }

    /** 下发释放模式 */
    fun applyDriveMode(option: CameraOption) {
        applyEnumPropChecked(0x5013, "释放模式", option, { driveMode = it }, ::driveModeLabel)
    }

    /** 下发低速连拍张数（0x5018），只在释放模式为低速时有效 */
    fun applyBurstCount(option: CameraOption) {
        applyEnumPropChecked(0x5018, "连拍张数", option, { burstCount = it },
            { if (it == 0L) "不限张数" else "$it 张" })
    }

    fun applyImageSize(option: CameraOption) {
        val raw = imageSizeRawByLabel[option.label] ?: return
        imageSizeLabel = option.label
        if (!isConnected) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = ptpClient.setImageSize(raw)
            // 0x5003 是 PTP 字符串，回读比对同样以机身为准
            val actual = runCatching {
                parseImageSizeString(ptpClient.getDevicePropValue(0x5003.toShort()))
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (actual != null && actual.isNotBlank()) {
                    imageSizeLabel = actual
                    if (formatImageSize(raw) == actual) addLogInner("图像尺寸已生效：$actual")
                    else addLogInner("⚠️ 图像尺寸未生效：相机当前是「$actual」，不是「${option.label}」")
                } else if (ok) {
                    addLogInner("图像尺寸已下发：${option.label}（机身未回读）")
                } else {
                    addLogInner(propFailMessage("图像尺寸", ptpClient.lastResponseCode))
                    refreshCameraParams(silent = true)
                }
            }
        }
    }

    /** PTP 字符串：1 字节字符数 + UTF-16LE，后面通常跟两个 0 终止字节。 */
    private fun readPtpString(bytes: ByteArray, start: Int): Pair<String, Int>? {
        if (start >= bytes.size) return null
        val count = bytes[start].toInt() and 0xFF
        val begin = start + 1
        val end = begin + count * 2
        if (count == 0) return "" to (start + 1)
        if (end > bytes.size) return null
        val text = String(bytes, begin, count * 2, Charsets.UTF_16LE)
        var next = end
        if (next + 1 < bytes.size && bytes[next] == 0.toByte() && bytes[next + 1] == 0.toByte()) next += 2
        return text to next
    }

    private fun formatImageSize(raw: String): String =
        raw.replace('x', 'X').split('X').takeIf { it.size == 2 }
            ?.let { "${it[0].trim()} × ${it[1].trim()}" } ?: raw

    private fun parseImageSizeString(bytes: ByteArray?): String? {
        if (bytes == null) return null
        val (text, _) = readPtpString(bytes, 0) ?: return null
        return formatImageSize(text)
    }

    /** 0x5003 描述符：code/type/getset + 出厂值 + 当前值 + formFlag(2) + count + 字符串枚举。 */
    private fun parseImageSizeOptions(bytes: ByteArray?): List<Pair<String, String>> {
        if (bytes == null || bytes.size < 6) return emptyList()
        val (_, afterFactory) = readPtpString(bytes, 5) ?: return emptyList()
        val (_, afterCurrent) = readPtpString(bytes, afterFactory) ?: return emptyList()
        if (afterCurrent >= bytes.size) return emptyList()
        val form = bytes[afterCurrent].toInt() and 0xFF
        if (form != 2) return emptyList()
        val countAt = afterCurrent + 1
        if (countAt + 1 >= bytes.size) return emptyList()
        val count = (bytes[countAt].toInt() and 0xFF) or ((bytes[countAt + 1].toInt() and 0xFF) shl 8)
        var p = countAt + 2
        val result = mutableListOf<Pair<String, String>>()
        repeat(count) {
            val (text, next) = readPtpString(bytes, p) ?: return result
            if (text.isNotBlank()) result.add(formatImageSize(text) to text)
            p = next
        }
        return result
    }

    fun applyAfAreaMode(option: CameraOption) {
        applyEnumPropChecked(afAreaPropId, "AF 区域模式", option, { afAreaMode = it }, ::afAreaLabel)
    }

    fun applySubjectDetection(option: CameraOption) {
        applyEnumPropChecked(0x1D005, "拍摄对象侦测", option, { subjectDetection = it }, ::subjectLabel)
    }

    private fun decodeExposureComp(raw: Long): String {
        // 标准 PTP 为 1/1000 EV；Nikon 机身常用 1/6 EV 档
        val ev = if (kotlin.math.abs(raw) <= 60L) raw / 6.0 else raw / 1000.0
        val sign = if (ev > 0) "+" else ""
        return String.format(java.util.Locale.US, "%s%.1f EV", sign, ev)
    }

    /** 0x500B 测光：本机 Z50II 枚举 [2,3,4,32784] */
    private fun meteringLabel(raw: Long): String = when (raw) {
        0x0002L -> "中央重点测光"
        0x0003L -> "矩阵测光"
        0x0004L -> "点测光"
        0x8010L -> "亮部重点测光"
        else -> "测光 0x%04X".format(raw)
    }

    /** 0x500A 对焦模式：本机 Z50II 枚举 [1,32784,32785,32786,32787] */
    private fun focusModeLabelOf(raw: Long): String = when (raw) {
        0x0001L -> "MF 手动对焦"
        0x8010L -> "AF-S 单次伺服"
        0x8011L -> "AF-C 连续伺服"
        0x8012L -> "AF-A 自动伺服"
        0x8013L -> "AF-F 全时伺服"
        else -> "对焦模式 0x%04X".format(raw)
    }

    /**
     * Z50II 的 0xD061 枚举实测为 [0,1,4,5]，当前 AF-A 时值为 5：
     * 0=AF-S，1=AF-C，4=MF，5=AF-A；没有 AF-F。
     */
    private fun nikonFocusModeLabelOf(raw: Long): String = when (raw) {
        0L -> "AF-S 单次伺服"
        1L -> "AF-C 连续伺服"
        4L -> "MF 手动对焦"
        5L -> "AF-A 自动伺服"
        else -> "对焦模式 0x%04X".format(raw)
    }

    /** 把界面上的对焦模式名称翻译回 0xD061 的 0/1/4/5 原始值。 */
    private fun nikonFocusModeRawForLabel(label: String): Long? = when {
        label.startsWith("AF-S") -> 0L
        label.startsWith("AF-C") -> 1L
        label.startsWith("AF-A") -> 5L
        label.startsWith("MF") -> 4L
        else -> null
    }

    /** 把界面上的对焦模式名称翻译回 0x500A 的 UINT16 原始值。 */
    private fun standardFocusModeRawForLabel(label: String): Long? = when {
        label.startsWith("AF-S") -> 0x8010L
        label.startsWith("AF-C") -> 0x8011L
        label.startsWith("AF-A") -> 0x8012L
        label.startsWith("AF-F") -> 0x8013L
        label.startsWith("MF") -> 0x0001L
        else -> null
    }

    /** 下发曝光补偿（单位跟随相机上报的档位定义） */
    fun applyExposureComp(option: CameraOption) {
        applyEnumPropChecked(0x5010, "曝光补偿", option, { exposureComp = it }, ::decodeExposureComp)
    }

    fun applyMeteringMode(option: CameraOption) {
        applyEnumPropChecked(0x500B, "测光模式", option, { meteringMode = it }, ::meteringLabel)
    }

    fun applyFocusMode(option: CameraOption) {
        focusModeLabel = option.label
        if (!isConnected) return
        viewModelScope.launch(Dispatchers.IO) {
            // 参考 ZENCHE：静态模式写 0x500A，实时取景写 0xD061（尼康 LV_AFMODE）；
            // 本机 0x500A 被相机标成只读（AccessDenied），所以优先尝试 0xD061，失败再回退 0x500A。
            val liveViewActive = ptpClient.isLiveViewActive.value
            val standardRaw = if (option.raw > 0xFF) {
                option.raw
            } else {
                standardFocusModeRawForLabel(option.label)
            }
            val liveRaw = nikonFocusModeRawForLabel(option.label)
            var ok = false
            if (liveViewActive) {
                if (liveRaw != null) {
                    ok = ptpClient.setPropRawValue(0xD061.toShort(), 0x0002, liveRaw)
                }
                if (!ok && standardRaw != null) {
                    ok = ptpClient.setPropRawValue(0x500A.toShort(), 0x0004, standardRaw)
                }
            } else {
                // Z50II 的 0x500A 静态模式也会回 AccessDenied；先试 0xD061，再试 0x500A。
                if (liveRaw != null) {
                    ok = ptpClient.setPropRawValue(0xD061.toShort(), 0x0002, liveRaw)
                }
                if (!ok && standardRaw != null) {
                    ok = ptpClient.setPropRawValue(0x500A.toShort(), 0x0004, standardRaw)
                }
            }
            // 以机身回读为准：对焦模式有两套编码（0xD061 的 0/1/4/5 和 0x500A 的 0x801x），
            // 只凭返回码更新界面容易把没生效显示成生效。
            val liveActual = runCatching { descOrNull(0xD061)?.currentRaw }.getOrNull()
            val standardActual = runCatching { descOrNull(0x500A)?.currentRaw }.getOrNull()
            withContext(Dispatchers.Main) {
                val shown = liveActual?.let { nikonFocusModeLabelOf(it) }
                    ?: standardActual?.let { focusModeLabelOf(it) }
                val accepted = (liveRaw != null && liveActual == liveRaw) ||
                    (standardRaw != null && standardActual == standardRaw)
                if (shown != null) {
                    focusModeLabel = shown
                    if (accepted) addLogInner("对焦模式已生效：$shown")
                    else addLogInner("⚠️ 对焦模式未生效：相机当前是「$shown」，不是「${option.label}」")
                } else if (ok) {
                    addLogInner("对焦模式已下发：${option.label}（机身未回读）")
                } else {
                    addLogInner(propFailMessage("对焦模式", ptpClient.lastResponseCode))
                    refreshCameraParams(silent = true)
                }
            }
        }
    }

    /** 「更多」菜单等就地反馈：写进控制页的「上次操作」行，同时落日志 */
    fun noteAction(message: String) {
        lastParamMessage = message
        addLogInner(message)
    }

    // ===== 「更多」菜单里的机身级操作 =====

    /** 应用接管相机：发 0x9435(1)/0x90C2(1)/0xD1A6=0，把机身控制权交给 App */
    fun takeOverCamera() {
        if (!isConnected) { addLogInner("未连接相机，接管失败"); return }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { ptpClient.takeOverHostControl() }.getOrElse { false }
            withContext(Dispatchers.Main) {
                lastParamMessage = if (ok) "应用接管相机：机身已响应" else "应用接管相机：机身拒绝（见日志）"
                addLogInner(lastParamMessage)
            }
        }
    }

    /** 更多菜单的「应用接管相机」开关：开=接管，关=把控制权还给机身 */
    fun setHostControl(on: Boolean) {
        if (!isConnected) { addLogInner("未连接相机，接管开关无效"); return }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                if (on) ptpClient.takeOverHostControl() else ptpClient.releaseHostControl()
            }.getOrElse { false }
            val msg = if (on) {
                if (ok) "已接管机身控制" else "接管未成功（机身拒绝，见日志）"
            } else {
                "控制权已还给机身，机身按键可正常使用"
            }
            withContext(Dispatchers.Main) {
                lastParamMessage = msg
                addLogInner(msg)
            }
        }
    }

    /**
     * 监看里的「接管相机」开关：切换 ptpClient 的取景方案。
     * 取景进行中就重启一次，让新的方案（是否发 0x9435/0x90C2）立刻生效。
     */
    fun setTakeoverCamera(on: Boolean) {
        ptpClient.takeoverHostControl = on
        addLogInner(if (on) "已接管相机：机身屏幕会让给 App" else "已退出接管：相机屏幕继续显示")
        if (ptpClient.isLiveViewActive.value) {
            viewModelScope.launch(Dispatchers.IO) {
                ptpClient.stopLiveView()
                kotlinx.coroutines.delay(600)
                ptpClient.startLiveView()
            }
        }
    }

    /** 从机身读回作者/版权文本（版权页的「导入相机数据」） */
    suspend fun readCopyrightFromCamera(): Pair<String, String> = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext "" to ""
        var artist = ""
        var copyright = ""
        for (prop in listOf(0xD303, 0xD30E, 0xD209)) {
            val text = runCatching { ptpClient.readPropAsString(prop) }.getOrNull() ?: continue
            if (text.isBlank()) continue
            if (artist.isEmpty()) artist = text else if (copyright.isEmpty()) copyright = text
            com.lutcamera.lutcamerapro.log.DoukLog.i("Prop", "COPYRIGHT_READ", "prop" to "0x%04X".format(prop), "text" to text)
        }
        artist to copyright
    }

    /** 把作者/版权写进机身（尼康版权属性，逐候选探测，机身没开放就如实报） */
    fun uploadCopyright(artist: String, copyright: String) {
        if (!isConnected) { addLogInner("未连接相机，版权写入失败"); return }
        viewModelScope.launch(Dispatchers.IO) {
            val candidates = listOf(0xD303.toShort(), 0xD30E.toShort(), 0xD209.toShort())
            var written = 0
            for (prop in candidates) {
                val desc = runCatching { ptpClient.fetchPropDesc(prop) }.getOrNull() ?: continue
                if (!desc.writable) continue
                val text = if (written == 0) artist.ifBlank { copyright } else copyright.ifBlank { artist }
                if (text.isBlank()) continue
                val ok = runCatching { ptpClient.setDevicePropValue(prop, ptpString(text)) }.getOrElse { false }
                com.lutcamera.lutcamerapro.log.DoukLog.i("Prop", "COPYRIGHT_WRITE", "prop" to "0x%04X".format(prop.toInt()),
                    "ok" to ok, "code" to "0x%04X".format(ptpClient.lastResponseCode))
                if (ok) written++
                if (written >= 2) break
            }
            val msg = if (written > 0) "版权/作者已写入机身（$written 项）"
            else "机身未开放版权属性，写入未执行（详见日志）"
            withContext(Dispatchers.Main) {
                lastParamMessage = msg
                addLogInner(msg)
            }
        }
    }

    /** PTP 字符串：1 字节字符数 + UTF-16LE 正文 */
    private fun ptpString(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_16LE)
        return ByteArray(1 + body.size).also { out ->
            out[0] = text.length.toByte()
            System.arraycopy(body, 0, out, 1, body.size)
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}

/**
 * 设置里的「保存位置」映射成 MediaStore RELATIVE_PATH。
 * 「应用相册」必须落进 App 图库扫描的 Pictures/连镜，否则拍完的照片只在系统相册可见、
 * 应用内图库永远空着（旧版写成 LutCameraPro 就是这个症状）。
 */
private fun downloadRelativePath(dir: String, sub: String): String {
    val root = when (dir) {
        "系统相册" -> "DCIM/连镜"
        "Download" -> "Download/连镜"
        else -> "Pictures/连镜"
    }
    return if (sub.isBlank()) root else "$root/$sub"
}



