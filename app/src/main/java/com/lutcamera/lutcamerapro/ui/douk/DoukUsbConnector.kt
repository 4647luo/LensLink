package com.lutcamera.lutcamerapro.ui.douk

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.lutcamera.lutcamerapro.network.UsbPtpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * USB PTP 连接器：枚举 USB 总线上的相机、申请系统授权、然后用工程里已有的
 * [UsbPtpClient] 建立裸 USB 的 PTP 会话（不经过 MTP，直接收发 PTP 报文）。
 *
 * 对应相机菜单「连接至计算机」（二代 Z 机型）。
 */
@SuppressLint("MissingPermission")
class DoukUsbConnector(private val context: Context) {

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _status = MutableStateFlow("USB 未连接")
    val status: StateFlow<String> = _status

    private val _cameraName = MutableStateFlow("")
    val cameraName: StateFlow<String> = _cameraName

    private val _usbDetected = MutableStateFlow(false)
    val usbDetected: StateFlow<Boolean> = _usbDetected

    val ptp: UsbPtpClient = UsbPtpClient.defaultInstance

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiver: BroadcastReceiver? = null

    private val listener = object : UsbPtpClient.UsbEventListener {
        override fun onConnected(cameraName: String) {
            _cameraName.value = cameraName
            _connected.value = true
            _status.value = "USB PTP 已连接：$cameraName"
        }

        override fun onDisconnected(reason: String) {
            _connected.value = false
            _status.value = "USB 断开：$reason"
        }

        override fun onLog(msg: String) {
            _status.value = msg
        }
    }

    /** 扫描 USB 总线并连接（没有授权时会先弹系统授权框）。 */
    fun connect() {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (manager == null) {
            _status.value = "本机没有 USB 服务"
            return
        }
        val candidates = manager.deviceList.values.filter { looksLikeCamera(it) }
        _usbDetected.value = candidates.isNotEmpty()
        if (candidates.isEmpty()) {
            _connected.value = false
            _status.value = "没有检测到 USB 相机。请确认：① 数据线支持数据传输（不是纯充电线）② 相机菜单已切到「连接至计算机」③ OTG 已开启"
            return
        }
        val device = candidates.first()
        registerReceiver()
        if (!manager.hasPermission(device)) {
            _status.value = "系统会弹出 USB 授权窗口，请点「允许」"
            requestPermission(manager, device)
            return
        }
        openPtpSession(device)
    }

    /** 已经授权过就直接连（不需要再弹窗）。 */
    fun connectIfPermitted(): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val device = manager.deviceList.values.firstOrNull { looksLikeCamera(it) } ?: return false
        if (!manager.hasPermission(device)) return false
        openPtpSession(device)
        return true
    }

    private fun openPtpSession(device: UsbDevice) {
        _status.value = "正在建立 USB PTP 会话…"
        scope.launch {
            val ok = try {
                ptp.connectDevice(context, device, listener)
            } catch (e: Exception) {
                _status.value = "USB PTP 连接异常：${e.message}"
                false
            }
            if (ok) {
                _connected.value = true
                if (_cameraName.value.isBlank()) _cameraName.value = device.productName ?: "Nikon USB 相机"
                _status.value = "USB PTP 已连接"
                ptp.startLiveView()
            } else {
                _connected.value = false
                if (_status.value == "正在建立 USB PTP 会话…") {
                    _status.value = "USB PTP 连接失败：相机可能没切到「连接至计算机」，或接口被系统 MTP 服务占用"
                }
            }
        }
    }

    fun disconnect() {
        try {
            ptp.stopLiveView()
            ptp.disconnect()
        } catch (_: Exception) {
        }
        _connected.value = false
        _status.value = "USB 已断开"
    }

    fun release() {
        disconnect()
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
            receiver = null
        }
    }

    private fun requestPermission(manager: UsbManager, device: UsbDevice) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        // requestCode 和 data 都带上 deviceId：同时插多台机器或先后申请时，
        // 系统不会把上一次的授权结果错投到这一台。
        val intent = Intent(ACTION_USB_PERMISSION)
            .setPackage(context.packageName)
            .setData(Uri.parse("douk-usb://${device.deviceId}"))
        val pending = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
        try {
            manager.requestPermission(device, pending)
        } catch (e: Exception) {
            _status.value = "请求 USB 权限失败：${e.message}"
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_USB_PERMISSION -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        // 不只信广播里的 granted：再向系统确认这台设备真的有权限，
                        // 防止旧版本上伪造 ACTION_USB_PERMISSION 绕过授权。
                        val manager = context.getSystemService(UsbManager::class.java)
                        if (granted && device != null && manager?.hasPermission(device) == true && looksLikeCamera(device)) {
                            openPtpSession(device)
                        } else {
                            _status.value = "USB 权限被拒绝，无法连接相机"
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        _status.value = "检测到 USB 设备插入，正在尝试连接…"
                        connect()
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        disconnect()
                    }
                }
            }
        }
        receiver = r
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                // API 33 以下没有 receiver flag 参数。这里只收系统 USB 广播和本应用
                // 锁包名的授权广播，且收到授权后会再次 hasPermission 校验。
                context.registerReceiver(r, filter)
            }
        } catch (e: Exception) {
            _status.value = "注册 USB 广播失败：${e.message}"
        }
    }

    /** 尼康 VID=0x04B0；也接受标准 PTP 静止图像接口，兼容其他厂牌试连。 */
    private fun looksLikeCamera(device: UsbDevice): Boolean {
        if (device.vendorId == NIKON_VENDOR_ID) return true
        for (i in 0 until device.interfaceCount) {
            when (device.getInterface(i).interfaceClass) {
                UsbConstants.USB_CLASS_STILL_IMAGE, UsbConstants.USB_CLASS_VENDOR_SPEC -> return true
            }
        }
        return false
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.lutcamera.lutcamerapro.douk.USB_PERMISSION"
        const val NIKON_VENDOR_ID = 0x04B0
    }
}
