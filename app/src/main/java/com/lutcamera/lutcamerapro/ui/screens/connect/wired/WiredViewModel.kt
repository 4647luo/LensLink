package com.lutcamera.lutcamerapro.ui.screens.connect.wired

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lutcamera.lutcamerapro.lut.LutData
import com.lutcamera.lutcamerapro.model.BorderState
import com.lutcamera.lutcamerapro.model.CameraBrand
import com.lutcamera.lutcamerapro.network.UsbPtpClient
import com.lutcamera.lutcamerapro.service.AppForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class SaveMode(val label: String, val desc: String) {
    DUAL("Dual Save", "Save both original and LUT version"),
    ORIGINAL_ONLY("Original Only", "Save only original photo"),
    LUT_ONLY("LUT Only", "Save only LUT version")
}

enum class FormatFilterOption(val label: String) {
    ALL("All formats"),
    JPG_ONLY("JPG only"),
    RAW_ONLY("RAW only")
}

enum class WiredWorkMode {
    IDLE,
    LIVE_WATCH,
    USB_TETHER_CONTROL,
    GALLERY_MANUAL
}

sealed class WiredUiEvent {
    data class ShowToast(val message: String) : WiredUiEvent()
    data class NewPhotoArrived(val fileName: String) : WiredUiEvent()
    data class BatchImportFinished(val successCount: Int) : WiredUiEvent()
}

class CameraPhotoItem(
    val handle: Int,
    val name: String,
    val size: Long,
    val dateCreated: Long,
    val isRaw: Boolean,
    var thumbnailBytes: ByteArray? = null
) {
    var isSelected by mutableStateOf(false)
    fun release() { thumbnailBytes = null }
}

class WiredViewModel : ViewModel() {

    companion object {
        const val ACTION_USB_PERMISSION = "com.lutcamera.lutcamerapro.USB_PERMISSION"
        private const val TAG = "WiredViewModel"
        private val RAW_EXTENSIONS = setOf("NEF", "ARW", "CR2", "CR3", "DNG", "RAF", "RW2")
        private val JPG_EXTENSIONS = setOf("JPG", "JPEG", "HEIF", "HEIC", "HIF")
    }

    private val _uiEvent = Channel<WiredUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    var isConnected by mutableStateOf(false)
        private set
    var cameraName by mutableStateOf("Waiting for USB Camera")
        private set
    var cameraSerialNumber by mutableStateOf("")
        private set
    var targetUsbDevice: UsbDevice? = null
        private set

    // 仿苹果的 USB 自动识别品牌
                var detectedBrand by mutableStateOf<CameraBrand?>(null)
        private set

    // 快门拍照自动回传开关
                var autoTransferOnShutter by mutableStateOf(true)
    var isRecordingVideo by mutableStateOf(false)

    var shutterSpeed by mutableStateOf("1/250s")
    var aperture by mutableStateOf("F2.8")
    var iso by mutableStateOf("ISO 400")
    var shootMode by mutableStateOf("Manual")

    // 最新一张处理完成的照片（用于控制台缩略图预览）
                var lastProcessedBitmap by mutableStateOf<Bitmap?>(null)

    val usbPtpClient = UsbPtpClient.defaultInstance
    val usbLiveViewBitmap = usbPtpClient.liveViewBitmap
    val isUsbLiveViewActive = usbPtpClient.isLiveViewActive
    var isUsbTetherActive by mutableStateOf(false)
        private set

    val isWatchingService = AppForegroundService.isWatching
    val liveWatchProcessedCount = AppForegroundService.processedCount

    val currentWorkMode: WiredWorkMode
        get() = when {
            isUsbTetherActive -> WiredWorkMode.USB_TETHER_CONTROL
            isWatchingService.value -> WiredWorkMode.LIVE_WATCH
            isScanningGallery || isProcessing -> WiredWorkMode.GALLERY_MANUAL
            else -> WiredWorkMode.IDLE
        }

    var formatFilter by mutableStateOf(FormatFilterOption.ALL)
        private set
    var autoApplyLut by mutableStateOf(true)
        private set
    var autoApplyBorder by mutableStateOf(false)
        private set
    var saveMode by mutableStateOf(SaveMode.DUAL)

    var galleryPhotos = mutableStateListOf<CameraPhotoItem>()
    var isScanningGallery by mutableStateOf(false)
        private set
    var showGalleryDialog by mutableStateOf(false)

    var isProcessing by mutableStateOf(false)
        private set
    var processingProgress by mutableStateOf(0f)
        private set

    var importTotalCount by mutableStateOf(0)
        private set
    var importProcessedCount by mutableStateOf(0)
        private set
    var importSuccessCount by mutableStateOf(0)
        private set
    var importFailCount by mutableStateOf(0)
        private set

    private val galleryImportedHandles = mutableSetOf<Int>()
    private var usbReceiver: BroadcastReceiver? = null
    private var transferJob: Job? = null

    init {
        viewModelScope.launch {
            AppForegroundService.eventFlow.collect { event ->
                if (event.startsWith("NEW_PHOTO:")) {
                    _uiEvent.send(WiredUiEvent.NewPhotoArrived(event.removePrefix("NEW_PHOTO:")))
                } else {
                    _uiEvent.send(WiredUiEvent.ShowToast(event))
                }
            }
        }
    }

    /**
     * 启动 USB 原生线控与图传服务     */
    fun startUsbTetherControl(context: Context, onPhotoProcessed: (String) -> Unit = {}) {
        val device = targetUsbDevice
        if (device == null) {
            _uiEvent.trySend(WiredUiEvent.ShowToast("请先授予USB权限"))
            return
        }

        val success = usbPtpClient.connectDevice(
            context = context,
            device = device,
            eventListener = object : UsbPtpClient.UsbEventListener {
                override fun onConnected(cameraName: String) {
                    isUsbTetherActive = true
                    _uiEvent.trySend(WiredUiEvent.ShowToast("✅ USB 线控模式已连接: $cameraName"))
                }

                override fun onDisconnected(reason: String) {
                    isUsbTetherActive = false
                    _uiEvent.trySend(WiredUiEvent.ShowToast("❌ USB 线控断连: $reason"))
                }

                override fun onPhotoCaptured(handle: Int) {
                    _uiEvent.trySend(WiredUiEvent.ShowToast("✅ 捕获快门新照片: Handle: $handle"))
                    onPhotoProcessed("USB_IMG_$handle.JPG")
                }

                override fun onLog(msg: String) {
                    Log.d(TAG, msg)
                }
            }
        )

        if (success) {
            isUsbTetherActive = true
        }
    }

    fun stopUsbTetherControl() {
        isUsbTetherActive = false
        usbPtpClient.disconnect()
    }

    fun triggerUsbShutter() {
        if (!isConnected) return
        usbPtpClient.triggerCapture()
    }

    fun triggerUsbAf() {
        if (!isConnected) return
        usbPtpClient.triggerAf()
    }

    fun setTouchFocus(normX: Float, normY: Float) {
        if (isConnected) {
            usbPtpClient.setFocusPoint(normX, normY)
        }
    }

    fun setFocusPoint(normX: Float, normY: Float) = setTouchFocus(normX, normY)

    fun triggerCapture() = triggerUsbShutter()

    fun triggerAf() = triggerUsbAf()

    fun toggleMovieRecord(isRecording: Boolean) {
        isRecordingVideo = isRecording
        if (isConnected) {
            usbPtpClient.toggleMovieRecord(isRecording)
        }
    }

    @JvmName("setShutterSpeedSetting")
    fun setShutterSpeed(value: String) { shutterSpeed = value }
    @JvmName("setApertureSetting")
    fun setAperture(value: String) { aperture = value }
    @JvmName("setIsoSetting")
    fun setIso(value: String) { iso = value }
    @JvmName("setShootModeSetting")
    fun setShootMode(value: String) { shootMode = value }

    fun disconnect() {
        stopUsbTetherControl()
        transferJob?.cancel()
        isConnected = false
        detectedBrand = null
        cameraName = "Waiting for USB Camera..."
        cameraSerialNumber = ""
        galleryPhotos.forEach { it.release() }
        galleryPhotos.clear()
        galleryImportedHandles.clear()
        isProcessing = false
    }

    fun toggleVideoRecording() {
        if (!isConnected) return
        isRecordingVideo = !isRecordingVideo
        usbPtpClient.toggleMovieRecord(isRecordingVideo)
    }

    fun toggleUsbLiveView() {
        if (isUsbLiveViewActive.value) {
            usbPtpClient.stopLiveView()
        } else {
            usbPtpClient.startLiveView()
        }
    }

    fun toggleAutoApplyBorder(enabled: Boolean) {
        autoApplyBorder = enabled
        AppForegroundService.autoApplyBorder = enabled
    }

    fun syncParamsToService(lut: LutData?, intensity: Float, border: BorderState) {
        AppForegroundService.currentLut = lut
        AppForegroundService.lutIntensity = intensity
        AppForegroundService.borderState = border
        AppForegroundService.formatFilter = formatFilter
        AppForegroundService.saveMode = saveMode
        AppForegroundService.autoApplyLut = autoApplyLut
        AppForegroundService.autoApplyBorder = autoApplyBorder
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun registerUsbReceiver(context: Context) {
        if (usbReceiver != null) return

        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_USB_PERMISSION -> {
                        synchronized(this) {
                            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            }
                            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                device?.let { checkUsbConnection(context) }
                            } else {
                                _uiEvent.trySend(WiredUiEvent.ShowToast("请先授予USB权限"))
                            }
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> checkUsbConnection(context)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        viewModelScope.launch(Dispatchers.Main) {
                            _uiEvent.send(WiredUiEvent.ShowToast("USB连接失败"))
                            disconnectUsb(context)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // API 33 以下没有 receiver flag 参数；授权回包会在 checkUsbConnection 里
            // 用 UsbManager.hasPermission 再校验，不单信广播内容。
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun unregisterUsbReceiver(context: Context) {
        usbReceiver?.let {
            try { context.unregisterReceiver(it) } catch (ignored: Exception) {}
            usbReceiver = null
        }
    }

    fun checkUsbConnection(context: Context) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList

        if (deviceList.isEmpty()) {
            disconnectUsb(context)
            return
        }

        var foundCamera = false
        for ((_, device) in deviceList) {
            val vendorId = device.vendorId
            val brand = CameraBrand.fromVendorId(vendorId)
            val isCameraVendor = brand != null

            var isPtpDevice = false
            for (i in 0 until device.interfaceCount) {
                val clazz = device.getInterface(i).interfaceClass
                if (clazz == UsbConstants.USB_CLASS_STILL_IMAGE || clazz == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                    isPtpDevice = true
                    break
                }
            }

            if (isCameraVendor || isPtpDevice) {
                foundCamera = true
                targetUsbDevice = device
                detectedBrand = brand

                if (!usbManager.hasPermission(device)) {
                    val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    // 显式锁到本应用 + 唯一 requestCode/data，避免授权结果被别的应用接走，
                    // 也避免多台 USB 设备共用同一个 PendingIntent。
                    val permissionIntent = Intent(ACTION_USB_PERMISSION)
                        .setPackage(context.packageName)
                        .setData(Uri.parse("douk-usb://${device.deviceId}"))
                    val pending = PendingIntent.getBroadcast(context, device.deviceId, permissionIntent, flags)
                    usbManager.requestPermission(device, pending)
                    return
                }

                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val connection = usbManager.openDevice(device)
                        if (connection != null) {
                            val mtpDevice = MtpDevice(device)
                            if (mtpDevice.open(connection)) {
                                val info = mtpDevice.deviceInfo
                                withContext(Dispatchers.Main) {
                                    if (info != null && !info.model.isNullOrBlank()) {
                                        cameraName = "${info.manufacturer} ${info.model}".trim()
                                        cameraSerialNumber = info.serialNumber ?: "SN: Unknown"
                                    } else {
                                        cameraName = formatCameraModel(vendorId, device.productName)
                                    }
                                    isConnected = true
                                }
                                mtpDevice.close()
                            }
                            connection.close()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read DeviceInfo", e)
                    }
                }
                break
            }
        }

        if (!foundCamera) {
            disconnectUsb(context)
        }
    }

    fun toggleLiveWatch(
        appContext: Context,
        enabled: Boolean,
        currentLut: () -> LutData?,
        lutIntensity: () -> Float,
        borderState: () -> BorderState
    ) {
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    _uiEvent.trySend(WiredUiEvent.ShowToast("Please grant notification permission"))
                    return
                }
            }

            val device = targetUsbDevice
            if (device == null) {
                _uiEvent.trySend(WiredUiEvent.ShowToast("Failed to connect USB device"))
                return
            }

            checkBatteryOptimizationPrompt(appContext)
            syncParamsToService(currentLut(), lutIntensity(), borderState())

            val intent = Intent(appContext, AppForegroundService::class.java).apply {
                action = AppForegroundService.ACTION_START_WATCH
                putExtra(AppForegroundService.EXTRA_VENDOR_ID, device.vendorId)
                putExtra(AppForegroundService.EXTRA_PRODUCT_ID, device.productId)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
                _uiEvent.trySend(WiredUiEvent.ShowToast("Failed to start foreground service"))
            }
        } else {
            val intent = Intent(appContext, AppForegroundService::class.java).apply {
                action = AppForegroundService.ACTION_STOP_WATCH
            }
            appContext.startService(intent)
        }
    }

    private fun checkBatteryOptimizationPrompt(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                _uiEvent.trySend(WiredUiEvent.ShowToast("Please ignore battery optimizations for stable USB connection"))
            }
        }
    }

    fun loadCameraGallery(appContext: Context) {
        if (currentWorkMode == WiredWorkMode.LIVE_WATCH || isUsbTetherActive) return

        showGalleryDialog = true
        isScanningGallery = true
        galleryPhotos.forEach { it.release() }
        galleryPhotos.clear()

        val device = targetUsbDevice ?: return
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

        viewModelScope.launch(Dispatchers.IO) {
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            var mtpDevice: MtpDevice? = null
            try {
                connection = usbManager.openDevice(device)
                if (connection != null) {
                    mtpDevice = MtpDevice(device)
                    if (mtpDevice.open(connection)) {
                        val storageIds = mtpDevice.storageIds ?: intArrayOf()
                        val scannedList = mutableListOf<CameraPhotoItem>()

                        for (storageId in storageIds) {
                            var handles = mtpDevice.getObjectHandles(storageId, MtpConstants.FORMAT_UNDEFINED, 0)
                            if (handles == null || handles.isEmpty()) {
                                handles = mtpDevice.getObjectHandles(storageId, 0, 0) ?: intArrayOf()
                            }

                            for (handle in handles) {
                                val info = mtpDevice.getObjectInfo(handle) ?: continue
                                val ext = info.name.substringAfterLast('.', "").uppercase()

                                if (isPassFilter(ext)) {
                                    val isRaw = ext in RAW_EXTENSIONS
                                    val thumbBytes = try { mtpDevice.getThumbnail(handle) } catch (e: Exception) { null }

                                    scannedList.add(
                                        CameraPhotoItem(
                                            handle = handle,
                                            name = info.name,
                                            size = info.compressedSize.toLong(),
                                            dateCreated = info.dateCreated,
                                            isRaw = isRaw,
                                            thumbnailBytes = thumbBytes
                                        )
                                    )
                                }
                            }
                        }
                        val sortedItems = scannedList.sortedByDescending { it.dateCreated }.take(200)

                        withContext(Dispatchers.Main) {
                            galleryPhotos.addAll(sortedItems)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MTP gallery scan error", e)
            } finally {
                withContext(Dispatchers.Main) { isScanningGallery = false }
                mtpDevice?.close()
                connection?.close()
            }
        }
    }

    private fun isPassFilter(ext: String): Boolean {
        val isJpg = ext in JPG_EXTENSIONS
        val isRaw = ext in RAW_EXTENSIONS
        if (!isJpg && !isRaw) return false
        return when (formatFilter) {
            FormatFilterOption.ALL -> true
            FormatFilterOption.JPG_ONLY -> isJpg
            FormatFilterOption.RAW_ONLY -> isRaw
        }
    }

    fun selectAllGallery(selectAll: Boolean) {
        galleryPhotos.forEach { it.isSelected = selectAll }
    }

    fun cancelImport() {
        transferJob?.cancel()
        isProcessing = false
        _uiEvent.trySend(WiredUiEvent.ShowToast("Transfer cancelled"))
    }

    fun importSelectedPhotos(
        appContext: Context,
        currentLut: LutData?,
        lutIntensity: Float,
        borderState: BorderState
    ) {
        val selectedItems = galleryPhotos.filter { it.isSelected }
        if (selectedItems.isEmpty()) return

        val device = targetUsbDevice ?: return
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

        importTotalCount = selectedItems.size
        importProcessedCount = 0
        importSuccessCount = 0
        importFailCount = 0
        isProcessing = true
        processingProgress = 0f
        showGalleryDialog = false

        transferJob = viewModelScope.launch(Dispatchers.IO) {
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            var mtpDevice: MtpDevice? = null

            try {
                connection = usbManager.openDevice(device)
                if (connection != null) {
                    mtpDevice = MtpDevice(device)
                    if (mtpDevice.open(connection)) {
                        for (item in selectedItems) {
                            if (!isActive) break

                            if (galleryImportedHandles.contains(item.handle)) {
                                importProcessedCount++
                                importSuccessCount++
                                withContext(Dispatchers.Main) {
                                    processingProgress = importProcessedCount.toFloat() / importTotalCount
                                }
                                continue
                            }

                            val tempFile = File(appContext.cacheDir, "temp_${item.name}")
                            val success = mtpDevice.importFile(item.handle, tempFile.absolutePath)

                            if (success && tempFile.exists() && tempFile.length() > 0) {
                                // 处理真实的下载链路（套 LUT 与边框的渲染流水线）
                try {
                                    java.io.FileInputStream(tempFile).use { inputStream ->
                                        com.lutcamera.lutcamerapro.gpu.ImageAutoProcessor.processIncomingCameraPhoto(
                                            context = appContext,
                                            imageStream = inputStream,
                                            currentLut = currentLut,
                                            intensity = lutIntensity,
                                            autoApplyLut = autoApplyLut,
                                            autoApplyBorder = autoApplyBorder,
                                            saveMode = saveMode,
                                            borderState = borderState,
                                            currentXmp = null
                                        )
                                    }
                                    galleryImportedHandles.add(item.handle)
                                    importSuccessCount++
                                } catch (e: Exception) {
                                    importFailCount++
                                } finally {
                                    tempFile.delete()
                                }
                            } else {
                                importFailCount++
                            }

                            importProcessedCount++
                            withContext(Dispatchers.Main) {
                                processingProgress = importProcessedCount.toFloat() / importTotalCount
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MTP Import Error", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    _uiEvent.send(WiredUiEvent.BatchImportFinished(importSuccessCount))
                }
                mtpDevice?.close()
                connection?.close()
            }
        }
    }

    private fun formatCameraModel(vendorId: Int, rawName: String?): String {
        val brand = CameraBrand.fromVendorId(vendorId)
        if (brand != null) {
            return if (!rawName.isNullOrBlank()) "${brand.displayName} - $rawName" else "${brand.displayName} (USB PTP)"
        }
        if (rawName.isNullOrBlank()) {
            return "Unknown PTP Camera"
        }
        return rawName
    }

    fun closeGalleryDialog() {
        if (isProcessing) return
        showGalleryDialog = false
    }

    fun setFormatFilterOption(option: FormatFilterOption) {
        formatFilter = option
        AppForegroundService.formatFilter = option
    }

    fun disconnectUsb(context: Context) {
        val intent = Intent(context, AppForegroundService::class.java).apply {
            action = AppForegroundService.ACTION_STOP_WATCH
        }
        context.startService(intent)

        stopUsbTetherControl()
        transferJob?.cancel()
        isConnected = false
        detectedBrand = null
        cameraName = "Waiting for USB Camera..."
        cameraSerialNumber = ""
        galleryPhotos.forEach { it.release() }
        galleryPhotos.clear()
        galleryImportedHandles.clear()
        isProcessing = false
    }

    fun toggleAutoApplyLut(enabled: Boolean) {
        autoApplyLut = enabled
        AppForegroundService.autoApplyLut = enabled
        if (!enabled && saveMode == SaveMode.LUT_ONLY) {
            saveMode = SaveMode.ORIGINAL_ONLY
            AppForegroundService.saveMode = SaveMode.ORIGINAL_ONLY
        }
    }

    fun setSaveModeOption(mode: SaveMode) {
        saveMode = mode
        AppForegroundService.saveMode = mode
        if (mode == SaveMode.LUT_ONLY) {
            autoApplyLut = true
            AppForegroundService.autoApplyLut = true
        }
    }
}


