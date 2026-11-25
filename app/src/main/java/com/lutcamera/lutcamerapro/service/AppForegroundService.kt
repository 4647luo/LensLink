package com.lutcamera.lutcamerapro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import com.lutcamera.lutcamerapro.log.Log
import androidx.core.app.NotificationCompat
import com.lutcamera.lutcamerapro.gpu.ImageAutoProcessor
import com.lutcamera.lutcamerapro.lut.LutData
import com.lutcamera.lutcamerapro.model.BorderState
import com.lutcamera.lutcamerapro.ui.screens.connect.wired.FormatFilterOption
import com.lutcamera.lutcamerapro.ui.screens.connect.wired.SaveMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class AppForegroundService : Service() {

    companion object {
        private const val TAG = "AppForegroundService"
        private const val CHANNEL_ID = "lut_camera_wired_watch"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_WATCH = "com.lutcamera.action.START_WATCH"
        const val ACTION_STOP_WATCH = "com.lutcamera.action.STOP_WATCH"

        const val EXTRA_VENDOR_ID = "extra_vendor_id"
        const val EXTRA_PRODUCT_ID = "extra_product_id"

        const val RELATIVE_PATH_ORIGINALS = "Pictures/LutCameraPro/Originals"
        const val RELATIVE_PATH_RENDERED = "Pictures/LutCameraPro/Rendered"
        const val RELATIVE_PATH_RAW = "Download/LutCameraPro/RAW"

        private val _isWatching = MutableStateFlow(false)
        val isWatching = _isWatching.asStateFlow()

        private val _processedCount = MutableStateFlow(0)
        val processedCount = _processedCount.asStateFlow()

        private val _eventFlow = MutableSharedFlow<String>(extraBufferCapacity = 20)
        val eventFlow = _eventFlow.asSharedFlow()

        // 🌟 核心控制参数（Volatile 保证多线程实时可见）
        @Volatile var currentLut: LutData? = null
        @Volatile var lutIntensity: Float = 1.0f
        @Volatile var borderState: BorderState = BorderState()
        @Volatile var formatFilter: FormatFilterOption = FormatFilterOption.ALL
        @Volatile var saveMode: SaveMode = SaveMode.DUAL
        @Volatile var autoApplyLut: Boolean = true
        @Volatile var autoApplyBorder: Boolean = false // 🌟 独立边框自动推送控制量
        @Volatile var currentXmp: com.lutcamera.lutcamerapro.lut.XmpData? = null

        private val RAW_EXTENSIONS = setOf("NEF", "ARW", "CR2", "CR3", "DNG", "RAF", "RW2")
        private val JPG_EXTENSIONS = setOf("JPG", "JPEG", "HEIF", "HEIC", "HIF")
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchJob: Job? = null
    private val liveWatchProcessedHandles = mutableSetOf<Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START_WATCH -> {
                val vendorId = intent.getIntExtra(EXTRA_VENDOR_ID, -1)
                val productId = intent.getIntExtra(EXTRA_PRODUCT_ID, -1)

                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                val targetDevice = findUsbDevice(usbManager, vendorId, productId)

                if (targetDevice != null) {
                    startForeground(NOTIFICATION_ID, buildNotification("🟢 有线快门监听中..."))
                    startMtpPolling(targetDevice)
                } else {
                    Log.e(TAG, "无法通过 Vid:$vendorId Pid:$productId 找到目标 USB 设备")
                    _eventFlow.tryEmit("找不到已连接的 USB 相机")
                    stopSelf()
                }
            }
            ACTION_STOP_WATCH -> {
                stopMtpPolling()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun findUsbDevice(usbManager: UsbManager, vendorId: Int, productId: Int): UsbDevice? {
        if (vendorId == -1 || productId == -1) return null
        return usbManager.deviceList.values.firstOrNull {
            it.vendorId == vendorId && it.productId == productId
        }
    }

    private fun startMtpPolling(device: UsbDevice) {
        watchJob?.cancel()
        _isWatching.value = true
        _processedCount.value = 0
        liveWatchProcessedHandles.clear()

        watchJob = serviceScope.launch {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val knownHandles = mutableSetOf<Int>()

            var connection: android.hardware.usb.UsbDeviceConnection? = null
            var mtpDevice: MtpDevice? = null

            var consecutiveErrors = 0
            var emptyStorageCount = 0

            suspend fun tryOpenMtpSessionWithBackoff(): Boolean {
                try {
                    mtpDevice?.close()
                    connection?.close()

                    if (consecutiveErrors > 0) {
                        val backoffMs = (1000L * (1 shl (consecutiveErrors - 1).coerceAtMost(3))).coerceAtMost(8000L)
                        Log.w(TAG, "MTP 链路异常，退避等待 ${backoffMs}ms...")
                        delay(backoffMs)
                    }

                    val conn = usbManager.openDevice(device) ?: return false
                    connection = conn

                    val mDevice = MtpDevice(device)
                    mtpDevice = mDevice

                    return mDevice.open(conn)
                } catch (e: Exception) {
                    Log.e(TAG, "MTP 会话重建异常", e)
                    return false
                }
            }

            try {
                if (!tryOpenMtpSessionWithBackoff()) {
                    _eventFlow.emit("MTP 会话建立失败，请拔插 USB 重试")
                    _isWatching.value = false
                    stopSelf()
                    return@launch
                }

                val initialStorageIds = mtpDevice?.storageIds ?: intArrayOf()
                for (sId in initialStorageIds) {
                    val handles = mtpDevice?.getObjectHandles(sId, 0, 0) ?: intArrayOf()
                    knownHandles.addAll(handles.toList())
                }

                while (isActive && _isWatching.value) {
                    // MTP 枚举每次都要跟相机通信，2 秒一轮在后台很费电；
                    // 3 秒一轮对「拍完自动回传」的体感没有区别。
                    delay(3000)

                    try {
                        var storageIds = mtpDevice?.storageIds

                        if (storageIds == null || storageIds.isEmpty()) {
                            emptyStorageCount++
                            consecutiveErrors++

                            if (tryOpenMtpSessionWithBackoff()) {
                                storageIds = mtpDevice?.storageIds
                                if (storageIds != null && storageIds.isNotEmpty()) {
                                    emptyStorageCount = 0
                                    consecutiveErrors = 0
                                }
                            }

                            if (emptyStorageCount >= 3) {
                                _eventFlow.emit("相机休眠，USB 链路断开")
                                _isWatching.value = false
                                break
                            }
                            continue
                        } else {
                            emptyStorageCount = 0
                        }

                        val currentAllHandles = mutableSetOf<Int>()
                        for (sId in storageIds) {
                            val handles = mtpDevice?.getObjectHandles(sId, 0, 0) ?: intArrayOf()
                            currentAllHandles.addAll(handles.toList())
                        }

                        val newHandles = currentAllHandles - knownHandles
                        if (newHandles.isNotEmpty()) {
                            delay(600)

                            for (handle in newHandles) {
                                if (liveWatchProcessedHandles.contains(handle)) continue

                                val info = mtpDevice?.getObjectInfo(handle) ?: continue
                                val ext = info.name.substringAfterLast('.', "").uppercase()

                                if (isPassFilter(ext)) {
                                    val tempFile = File(cacheDir, "temp_${info.name}")
                                    val success = mtpDevice?.importFile(handle, tempFile.absolutePath) == true

                                    if (success && tempFile.exists() && tempFile.length() > 0) {
                                        liveWatchProcessedHandles.add(handle)
                                        val isRaw = ext in RAW_EXTENSIONS

                                        processAndSavePhoto(
                                            tempFile = tempFile,
                                            fileName = info.name,
                                            isRaw = isRaw
                                        )

                                        _processedCount.value += 1
                                        _eventFlow.emit("NEW_PHOTO:${info.name}")
                                        tempFile.delete()
                                    }
                                }
                            }
                            knownHandles.addAll(newHandles)
                        }
                        consecutiveErrors = 0
                    } catch (e: Exception) {
                        Log.e(TAG, "MTP 轮询遇到异常", e)
                        consecutiveErrors++
                        tryOpenMtpSessionWithBackoff()

                        if (consecutiveErrors >= 4) {
                            _eventFlow.emit("USB link stopped")
                            _isWatching.value = false
                            break
                        }
                    }
                }
            } finally {
                try {
                    mtpDevice?.close()
                    connection?.close()
                } catch (ignored: Exception) {}
                _isWatching.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
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

    // 🌟 核心分发逻辑：调用统一管线处理 LUT 与边框
                private suspend fun processAndSavePhoto(tempFile: File, fileName: String, isRaw: Boolean) {
        if (isRaw && saveMode == SaveMode.LUT_ONLY) return

        try {
            tempFile.inputStream().use { inputStream ->
                ImageAutoProcessor.processIncomingCameraPhoto(
                    context = this,
                    imageStream = inputStream,
                    currentLut = currentLut,
                    intensity = lutIntensity,
                    autoApplyLut = autoApplyLut,
                    autoApplyBorder = autoApplyBorder, // 传递独立边框开关
                saveMode = saveMode,               // 传递双保存/单保存模式
                borderState = borderState,         // 传递完整的边框配置结构
                currentXmp = currentXmp            // 传递 XMP 配置
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Auto photo transfer failed", t)
        }
    }

    private fun stopMtpPolling() {
        _isWatching.value = false
        watchJob?.cancel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "有线相机后台回传服务",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LutCameraPro Background Service")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMtpPolling()
        serviceScope.cancel()
    }
}

