package com.lutcamera.lutcamerapro.ui.douk

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.lutcamera.lutcamerapro.log.DoukLog
import com.lutcamera.lutcamerapro.model.CameraStorageFile
import com.lutcamera.lutcamerapro.network.CameraLanProbe
import com.lutcamera.lutcamerapro.network.LiveViewQuality
import com.lutcamera.lutcamerapro.network.NikonPtpClient
import com.lutcamera.lutcamerapro.network.UsbPtpClient
import com.lutcamera.lutcamerapro.network.nikon.ble.NikonBleGpsClient
import com.lutcamera.lutcamerapro.network.nikon.ble.NikonBleRemoteClient
import com.lutcamera.lutcamerapro.ui.screens.connect.nikon.NikonViewModel
import com.lutcamera.lutcamerapro.ui.screens.connect.nikon.WirelessSubMode
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 三种相机连接方式（外加蓝牙用于 GPS 推送）。 */
enum class ConnectMode(val label: String, val summary: String, val detail: String) {
    WIFI_STA("Wi-Fi STA", "相机连手机热点", "相机菜单 → 连接至智能设备 → 加入手机热点，App 用相机获得的 IP 建立 PTP/IP 连接"),
    WIFI_AP("Wi-Fi AP 直连", "手机连相机热点", "相机菜单 → 连接至智能设备 → 相机作为热点，手机连上相机 Wi-Fi 后由 App 自动探测相机 IP（15740 端口）"),
    USB_PTP("USB PTP", "数据线直连", "相机「设定菜单 → USB 数据连接」选「连接到计算机（照相机控制）」或 MTP/PTP，机身屏幕显示「连接至计算机」就是该状态，OTG 数据线接手机后走标准 PTP"),
    BLUETOOTH("蓝牙", "GPS / 遥控快门", "配对相机蓝牙后可推送手机定位，部分机型还能用 ML‑L7 协议遥控快门与录像；不承担图像传输")
}

/** 控制页顶部两种工作模式。 */
enum class CaptureMode(val label: String) {
    VIDEO("视频"),
    STILL("拍摄")
}

data class CameraProfile(
    val name: String = "未连接",
    val model: String = "—",
    val lens: String = "—",
    val lensFocal: String = "",
    val batteryPercent: Int = -1,
    val storageFreeMb: Int = 0,
    val storageTotalMb: Int = 0,
    val firmware: String = "—"
)

data class DiscoveredCamera(
    val name: String,
    val model: String,
    val mode: ConnectMode,
    val address: String
)

data class CameraParam(
    val key: String,
    val label: String,
    val value: String,
    val options: List<String>
)

/** 控制页的一张参数卡片：用户可改名、可决定哪些功能放进这张卡。 */
data class ParamCardGroup(
    val id: String,
    val name: String,
    val keys: List<String>
)

/** 把设置里存的 key 顺序合并到默认顺序：认得的 key 保留用户顺序，缺的补在末尾。 */
private fun mergedParamOrder(saved: String, defaults: List<String>): List<String> {
    val ids = saved.split(',').map { it.trim() }.filter { it.isNotBlank() && it in defaults }.distinct()
    return ids + defaults.filterNot { it in ids }
}

/** 把设置里存的隐藏 key 还原成列表，只接受当前目录里存在的 key。 */
private fun restoredHiddenKeys(saved: String, defaults: List<String>): List<String> =
    saved.split(',').map { it.trim() }.filter { it.isNotBlank() && it in defaults }.distinct()

/** 自定义参数卡片序列化：只用系统自带的 org.json，不额外引依赖。 */
private fun encodeParamGroups(groups: List<ParamCardGroup>): String {
    val arr = JSONArray()
    groups.forEach { group ->
        arr.put(
            JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("keys", JSONArray(group.keys))
            }
        )
    }
    return arr.toString()
}

/**
 * 读回自定义卡片；坏数据直接回默认值。
 * 新版本新增、但旧分组里没有的参数，自动补进最后一张卡，避免功能直接消失。
 */
private fun decodeParamGroups(saved: String, defaults: List<ParamCardGroup>): List<ParamCardGroup> {
    if (saved.isBlank()) return defaults
    val parsed = runCatching {
        val arr = JSONArray(saved)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val keysArr = obj.optJSONArray("keys") ?: JSONArray()
                val keys = buildList {
                    for (j in 0 until keysArr.length()) {
                        keysArr.optString(j).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
                val id = obj.optString("id").ifBlank { "card_$i" }
                val name = obj.optString("name").ifBlank { "卡片 ${i + 1}" }
                add(ParamCardGroup(id, name, keys.distinct()))
            }
        }
    }.getOrNull()
    if (parsed.isNullOrEmpty()) return defaults

    val allDefaultKeys = defaults.flatMap { it.keys }.distinct()
    val covered = parsed.flatMap { it.keys }.toSet()
    val missing = allDefaultKeys.filterNot { it in covered }
    if (missing.isEmpty()) return parsed
    // 补进已有最后一张卡；若用户把卡删空了就新建一张「其他」
    return if (parsed.last().keys.isEmpty()) {
        parsed.dropLast(1) + parsed.last().copy(keys = missing)
    } else {
        parsed.dropLast(1) + parsed.last().copy(keys = parsed.last().keys + missing)
    }
}

/**
 * 跨页共享的界面状态。
 *
 * 这里不再保存任何演示数据：所有连接状态、机身信息、曝光参数都实时读自
 * [NikonViewModel]（NikonPtpClient 的真实 PTP/IP 会话），读不到就显示“未上报”。
 */
class DoukState(
    val vm: NikonViewModel,
    val usb: DoukUsbConnector,
    val ble: NikonBleGpsClient,
    val bleRemote: NikonBleRemoteClient
) {

    /** 用户在添加设备页选中的连接方式；真正连接时映射为 PTP 的网络模式。 */
    var connectMode by mutableStateOf(ConnectMode.WIFI_STA)

    /** USB PTP 是否已建立会话（由 DoukRoot 从 UsbPtpClient 的 StateFlow 同步进来）。 */
    var usbConnected by mutableStateOf(false)

    /** USB 侧真实读到的机身状态（GetDevicePropValue / GetStorageInfo）。 */
    var usbBattery by mutableStateOf<Int?>(null)
    var usbStorage by mutableStateOf<UsbPtpClient.UsbStorage?>(null)
    var usbIso by mutableStateOf<Int?>(null)

    /** USB 侧枚举到的机身照片。 */
    var usbPhotos by mutableStateOf<List<UsbPtpClient.UsbObject>>(emptyList())
    var usbPhotosLoading by mutableStateOf(false)
    var usbGalleryMessage by mutableStateOf("")

    /** 每保存一张照片自增，图库「应用内」据此重新扫描。 */
    var galleryRefreshTick by mutableStateOf(0)

    // ===== 图库多选的批量操作：按钮画在 Douk 底栏上方，由图库页写入状态 =====
    /** 当前选中的数量；0 = 不在多选态 */
    var gallerySelCount by mutableStateOf(0)
    /** 批量动作类型：delete（应用内删除）/ save（相机内保存到应用相册）/ 空 */
    var gallerySelKind by mutableStateOf("")
    /** 底栏卡片被点了一下就自增，图库页据此执行真正的操作 */
    var galleryBulkTick by mutableStateOf(0)
    var galleryBulkBusy by mutableStateOf(false)

    fun requestGalleryBulk() {
        if (gallerySelCount > 0 && gallerySelKind.isNotBlank()) galleryBulkTick++
    }

    fun clearGallerySelectionState() {
        gallerySelCount = 0
        gallerySelKind = ""
        galleryBulkBusy = false
    }

    /**
     * 局域网 TCP 探测到的相机（端口 15740 可达即视为相机）。
     * 这是比 mDNS 更可靠的发现方式：尼康很多机型不广播 _ptp._tcp。
     */
    var lanFound by mutableStateOf<List<CameraLanProbe.Found>>(emptyList())
    var lanScanning by mutableStateOf(false)
    /** 连接后自动导入锁定照片前的询问弹窗 */
    var lockedImportPrompt by mutableStateOf(false)
        private set
    var lanScanProgress by mutableStateOf("")

    /** 最近一次连接失败/进行中的原因，直接显示到界面（不再只躺在日志里）。 */
    var connectionStageMirror by mutableStateOf("")
    val connectionStage: String get() = connectionStageMirror

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var mode by mutableStateOf(CaptureMode.STILL)
    var previewVisible by mutableStateOf(true)
    var previewFullscreen by mutableStateOf(false)
    var photoCounter by mutableStateOf(0)
    var lastHint by mutableStateOf("")
    /** 参数被锁/未下发时给监看页的一句话反馈（与连接提示分开，避免串台） */
    var lastParamHint by mutableStateOf("")
    /** 手机端取景画质：流畅 / 标准 / 高清，写进 PTP 解码器 */
    var previewQuality by mutableStateOf(runCatching { DoukPrefs.monitorPreviewQuality }.getOrDefault("标准"))
        private set
    /** 视频色调模式：SDR / N-Log / HLG。App 侧记录，用于自动挑还原 LUT 与状态展示。 */
    var movieToneMode by mutableStateOf(runCatching { DoukPrefs.monitorToneMode }.getOrDefault("SDR"))
        private set
    /** 设备页相机实物照片的版本号：设置页换了图就 +1，让设备页重新读文件 */
    var photoVersion by mutableStateOf(0)
    /** 设备页是否显示「添加设备」按钮，由设置页控制 */
    var showAddDeviceButton by mutableStateOf(true)
    /** 设备页蓝牙分支是否显示 GPS 卡片 / 遥控卡片，由设置-蓝牙控制 */
    var showBleGpsCard by mutableStateOf(true)
    var showBleRemoteCard by mutableStateOf(true)

    // ===== 相机待配对（尼康首次连接只给最小能力集时）=====
    var pairingRequired by mutableStateOf(false)
    var pairingCode by mutableStateOf<String?>(null)
    var pairingBusy by mutableStateOf(false)
    var pairingMessage by mutableStateOf("")
    /** 最近一次连接目标的 IP，配对提交后用它自动重连 */
    var lastTargetIp by mutableStateOf("")
    /** 最近一次真正探测到端口可达的 IP（相机若被 DHCP 改地址，重连优先用这个） */
    var lastHotIp by mutableStateOf("")

    /** 相机实物照片：用户可在设置页替换为自己的机身照。 */
    var heroPhotoUri by mutableStateOf<String?>(null)

    // ===== 真实连接状态 =====
    /** Wi-Fi（PTP/IP）或 USB（裸 PTP）任一通道连通即视为已连接。 */
    val isConnected: Boolean get() = vm.isConnected || usbConnected
    val isConnecting: Boolean get() = vm.isConnecting
    val isPairing: Boolean get() = vm.scanning

    /**
     * 连接时刷新 PTP 空闲心跳与「是否接管相机」。
     *
     * 0.0.143 起不再用 6 秒 GetDeviceInfo 强行阻止相机待机：实测那会让相机屏幕
     * 被反复点亮。心跳固定 15 秒（只要够维持 TCP 会话），相机能不能睡、屏幕亮不亮
     * 完全交随机身自己的节能设置。
     */
    fun applyWifiPowerSave() {
        // 空闲心跳放到 60 秒：以前 15 秒一次 GetDeviceInfo，相机的 Wi‑Fi 灯几乎一直在闪。
        // 取景时心跳整段跳过（画面数据流本身就在保活），不必再补命令。
        vm.ptpClient.keepAliveIntervalMs = 60_000L
        vm.ptpClient.takeoverHostControl = DoukPrefs.takeoverCamera
    }

    /** mDNS 广播 + 局域网端口探测合并后的真实设备；两者都没有也能手填 IP 直连。 */
    val discovered: List<DiscoveredCamera>
        get() {
            val mdns = vm.discoveredCameras.map {
                DiscoveredCamera(
                    name = it.name.ifBlank { "Nikon 相机" },
                    model = it.name.ifBlank { "Nikon" },
                    mode = connectMode,
                    address = "${it.ip}:${it.port}"
                )
            }
            // 同一台机有时会同时广播「型号名」和一条空名/通用名，界面就变成两台：
            // 一台是刚连的型号，另一台叫「Nikon 相机」。有具体型号时把通用名那条隐藏；
            // 已连接时通用名也不再出现在待连接列表。只有「仅剩通用名」时才保留它兜底。
            fun genericName(n: String): Boolean {
                val t = n.trim().lowercase()
                return t.isBlank() || t == "nikon" || t == "camera" ||
                    t == "nikon camera" || t == "nikon 相机" || t == "尼康相机"
            }
            val namedMdns = mdns.filterNot { genericName(it.name) }
            val genericMdns = mdns.filter { genericName(it.name) }
            val visibleMdns = when {
                namedMdns.isNotEmpty() -> namedMdns
                vm.isConnected -> emptyList()
                else -> genericMdns.take(1)
            }
            val mdnsIps = visibleMdns.map { it.address.substringBefore(":") }.toSet()
            val lan = lanFound
                .filterNot { it.ip in mdnsIps }
                .map { hit ->
                    DiscoveredCamera(
                        name = "相机 ${hit.ip}",
                        model = "${hit.ip.substringBeforeLast('.')}.x 网段 · 端口 15740 可达",
                        mode = connectMode,
                        address = "${hit.ip}:${CameraLanProbe.PTP_PORT}"
                    )
                }
            return visibleMdns + lan
        }

    // ===== 机身上报信息（设备页） =====
    /** USB 通道没有取属性描述，型号取 USB 描述符里的产品名，其余如实显示未上报。 */
    val usbOnly: Boolean get() = usbConnected && !vm.isConnected

    val profile: CameraProfile
        get() = CameraProfile(
            name = when {
                vm.isConnected -> vm.cameraModel.ifBlank { "Nikon 相机" }
                usbConnected -> usb.cameraName.value.ifBlank { "Nikon USB 相机" }
                else -> "未连接"
            },
            model = when {
                vm.isConnected -> vm.cameraModel.ifBlank { "相机未上报型号" }
                usbConnected -> usb.cameraName.value.ifBlank { "Nikon USB 相机" }
                else -> "—"
            },
            lens = if (usbOnly) "USB 未上报"
                else if (vm.isConnected && !vm.bodyStatusLoaded) "读取中…"
                else vm.lensInfo,
            lensFocal = if (usbOnly) "" else vm.lensFocalText,
            batteryPercent = if (usbOnly) (usbBattery ?: -1) else (vm.batteryLevel ?: -1),
            storageFreeMb = if (usbOnly) ((usbStorage?.freeBytes ?: 0L) / (1024L * 1024L)).toInt()
                else ((vm.storageFreeBytes ?: 0L) / (1024L * 1024L)).toInt(),
            storageTotalMb = if (usbOnly) ((usbStorage?.totalBytes ?: 0L) / (1024L * 1024L)).toInt()
                else ((vm.storageTotalBytes ?: 0L) / (1024L * 1024L)).toInt(),
            firmware = if (usbOnly) "USB PTP" else vm.connectionStage.value
        )

    val batteryText: String
        get() = when {
            usbOnly -> usbBattery?.let { "$it%" } ?: "USB 未上报"
            vm.batteryLevel != null -> "${vm.batteryLevel}%"
            vm.isConnected && !vm.bodyStatusLoaded -> "读取中…"
            vm.isConnected -> "相机未上报"
            else -> "未连接"
        }

    val storageText: String
        get() {
            if (usbOnly) {
                val storage = usbStorage ?: return "USB 未上报"
                if (storage.totalBytes <= 0) {
                    return if (storage.freeImages > 0) "剩余 ${storage.freeImages} 张" else "USB 未上报"
                }
                return String.format(
                    java.util.Locale.US, "%.1f GB / %.1f GB",
                    storage.freeBytes / 1024.0 / 1024.0 / 1024.0,
                    storage.totalBytes / 1024.0 / 1024.0 / 1024.0
                )
            }
            val free = vm.storageFreeBytes
            val total = vm.storageTotalBytes
            if (free != null && total != null && total > 0) {
                return String.format(
                    java.util.Locale.US, "%.1f GB / %.1f GB",
                    free / 1024.0 / 1024.0 / 1024.0, total / 1024.0 / 1024.0 / 1024.0
                )
            }
            if (vm.storageFreeImages > 0) return "剩余 ${vm.storageFreeImages} 张"
            if (vm.isConnected && !vm.bodyStatusLoaded) return "读取中…"
            return if (vm.isConnected) "相机未上报" else "未连接"
        }

    /** USB 连上后读电量与存储（后台线程，读不到就保持 null 显示未上报）。 */
    fun refreshUsbBody() {
        if (!usb.ptp.isConnected.value) return
        ioScope.launch {
            val battery = runCatching { usb.ptp.readBatteryLevel() }.getOrNull()
            val storage = runCatching { usb.ptp.readStorage() }.getOrNull()
            val iso = runCatching { usb.ptp.readIso() }.getOrNull()
            withContext(Dispatchers.Main) {
                usbBattery = battery
                usbStorage = storage
                usbIso = iso
            }
        }
    }

    /** USB 通道枚举机身照片（GetObjectHandles + GetObjectInfo）。 */
    fun loadUsbPhotos() {
        if (!usb.ptp.isConnected.value) {
            usbGalleryMessage = "USB 未连接，无法读取机身照片"
            return
        }
        if (usbPhotosLoading) return
        usbPhotosLoading = true
        usbGalleryMessage = "正在读取机身照片列表…"
        ioScope.launch {
            val list = runCatching { usb.ptp.listObjects() }.getOrElse { emptyList() }
            withContext(Dispatchers.Main) {
                usbPhotos = list
                usbPhotosLoading = false
                usbGalleryMessage = if (list.isEmpty()) {
                    "机身没有返回可读照片（相机可能正在写卡或存储卡未插）"
                } else {
                    "共 ${list.size} 张"
                }
            }
        }
    }

    /**
     * USB 通道保存一张机身照片到手机相册（Pictures/连镜）。
     * 走 GetPartialObject 分块，先落临时文件再走 MediaStore，Android 10+ 不需要存储权限。
     */
    fun saveUsbPhoto(context: Context, handle: Int, fileName: String, onDone: (String) -> Unit) {
        ioScope.launch {
            val target = usbPhotos.firstOrNull { it.handle == handle }
            val displayName = fileName.ifBlank { "LJ_$handle.JPG" }
            val result = runCatching {
                val temp = java.io.File(context.cacheDir, "usb_$handle.dat")
                if (!usb.ptp.copyObjectToFile(handle, temp, target?.sizeBytes ?: 0L)) {
                    temp.delete()
                    return@runCatching "下载失败：相机没有返回数据"
                }
                val mime = if (displayName.lowercase().endsWith(".nef")) "image/x-nikon-nef" else "image/jpeg"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, GalleryFileOps.PHONE_FOLDER)
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return@runCatching "无法写入系统相册"
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    temp.inputStream().use { input -> input.copyTo(out) }
                } ?: return@runCatching "无法写入系统相册"
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                temp.delete()
                "已保存到 ${GalleryFileOps.PHONE_FOLDER}：$displayName"
            }.getOrElse { "保存失败：${it.message}" }
            withContext(Dispatchers.Main) {
                if (result.startsWith("已保存")) galleryRefreshTick++
                onDone(result)
            }
        }
    }

    /** USB 通道取缩略图（先 0x90C4 大图，失败再 0x100A），返回可显示的位图。 */
    suspend fun usbThumbBitmap(handle: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = usb.ptp.getBigThumbnail(handle) ?: usb.ptp.getThumbnail(handle)
            ?: return@runCatching null
            decodeThumb(bytes)
        }.getOrNull()
    }

    /** Wi‑Fi 通道取缩略图（0x90C4 优先，回落 0x100A）。 */
    suspend fun wifiThumbBitmap(handle: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            // 省电优先：网格只要小图，先用 0x100A（几十 KB），拿不到才回落 0x90C4 大图；
            // 不再对 200 格逐张拉 1600×1200 大缩略图，避免几百 MB 的 PTP 流量。
            val bytes = vm.ptpClient.readThumbnail(handle)
                ?: vm.ptpClient.readBigThumbnail(handle)
                ?: return@runCatching null
            decodeThumb(bytes)
        }.getOrNull()
    }

    /**
     * Wi‑Fi 通道取机身原图（GetObject 0x1009 全尺寸字节），只用于看图页的「显示原图」。
     * 按最长边 2560 采样解码，避免 45MP 的 NEF/JPG 直接 OOM。
     */
    suspend fun cameraOriginalBitmap(handle: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = vm.ptpClient.downloadCameraPhoto(handle) ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 2560 || bounds.outHeight / (sample * 2) >= 2560) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching null
            GalleryFileOps.applyExifOrientation(bmp, GalleryFileOps.exifOrientationOf(bytes)).asImageBitmap()
        }.getOrNull()
    }

    /** 「保存到」用：按句柄从机身拉完整文件字节（JPG / NEF 原文件，不做解码） */
    suspend fun cameraPhotoBytes(handle: Int): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { vm.ptpClient.downloadCameraPhoto(handle) }.getOrNull()
    }

    /** 「保存到」用：USB 通道把机身文件完整读出来 */
    suspend fun usbPhotoBytes(context: Context, handle: Int): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val temp = java.io.File(context.cacheDir, "douk_save_$handle.dat")
            val size = usbPhotos.firstOrNull { it.handle == handle }?.sizeBytes ?: 0L
            val ok = usb.ptp.copyObjectToFile(handle, temp, size)
            if (!ok) { temp.delete(); return@runCatching null }
            val bytes = temp.readBytes()
            temp.delete()
            bytes
        }.getOrNull()
    }

    private fun decodeThumb(bytes: ByteArray): ImageBitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return GalleryFileOps.applyExifOrientation(bmp, GalleryFileOps.exifOrientationOf(bytes)).asImageBitmap()
    }

    fun formattedStorage(): String = storageText

    // ===== 控制页参数：顺序可调，取值实时来自相机 =====
    /** key 固定，value 由 [valueOf] 实时读取，避免任何写死的假参数。 */
    val cameraParams = mutableStateListOf(
        CameraParam("iso", "ISO 感光度", "", emptyList()),
        CameraParam("shutter", "快门速度", "", emptyList()),
        CameraParam("aperture", "光圈", "", emptyList()),
        CameraParam("ev", "曝光补偿", "", emptyList()),
        CameraParam("expMode", "曝光模式", "", emptyList()),
        CameraParam("metering", "测光模式", "", emptyList()),
        CameraParam("focusMode", "对焦模式", "", emptyList()),
        CameraParam("afAreaMode", "AF 区域模式", "", emptyList()),
        CameraParam("subjectDetection", "拍摄对象侦测", "", emptyList()),
        CameraParam("wb", "白平衡", "", emptyList()),
        CameraParam("drive", "释放模式", "", emptyList()),
        CameraParam("burstCount", "低速连拍张数", "", emptyList()),
        CameraParam("vr", "减震模式", "", emptyList()),
        CameraParam("activeD", "动态 D-Lighting", "", emptyList()),
        CameraParam("imageArea", "图像区域", "", emptyList()),
        CameraParam("imageQuality", "图像品质", "", emptyList()),
        CameraParam("imageSize", "图像尺寸", "", emptyList()),
        CameraParam("rawCompression", "RAW 录制", "", emptyList()),
        CameraParam("movieVr", "减震模式", "", emptyList()),
        CameraParam("movieWb", "白平衡", "", emptyList()),
        CameraParam("movieMetering", "测光模式", "", emptyList()),
        CameraParam("movieShutter", "快门速度", "", emptyList()),
        CameraParam("movieAperture", "光圈", "", emptyList()),
        CameraParam("movieIso", "ISO 感光度", "", emptyList()),
        CameraParam("movieEv", "曝光补偿", "", emptyList()),
        CameraParam("movieFileType", "文件类型", "", emptyList()),
        CameraParam("movieScreenSize", "画面尺寸 / 帧率", "", emptyList()),
        CameraParam("movieQuality", "画质", "", emptyList()),
        CameraParam("movieFocusMode", "对焦模式", "", emptyList()),
        CameraParam("movieAfAreaMode", "AF 区域模式", "", emptyList()),
        CameraParam("movieIsoAuto", "ISO 自动控制", "", emptyList()),
        CameraParam("movieIsoAutoHigh", "ISO 自动上限", "", emptyList()),
        CameraParam("movieMic", "麦克风灵敏度", "", emptyList()),
        CameraParam("movieMicLevel", "录音电平", "", emptyList()),
        CameraParam("movieWindNoise", "风噪消减", "", emptyList()),
        CameraParam("movieFlicker", "闪烁消减", "", emptyList()),
        CameraParam("movieHighFreqFlicker", "高频闪烁消减", "", emptyList()),
        CameraParam("movieAutoDistortion", "自动畸变校正", "", emptyList()),
        CameraParam("movieCrop", "画面裁切", "", emptyList()),
        CameraParam("movieAutoDx", "自动 DX 裁切", "", emptyList()),
        CameraParam("movieTimeCode", "时码", "", emptyList()),
        CameraParam("movieCountUp", "时码计数", "", emptyList()),
        CameraParam("movieDropFrame", "丢帧", "", emptyList())
    )

    // ===== 拍摄 / 视频两套参数顺序与显隐 =====
    // 默认顺序沿用控制页原来的分组顺序：曝光 → 对焦/画质 → 音频/闪烁。
    private val defaultStillParamOrder = listOf(
        "iso", "shutter", "aperture", "ev", "expMode",
        "metering", "focusMode", "afAreaMode", "subjectDetection",
        "wb", "drive", "vr", "activeD",
        "imageArea", "imageQuality", "imageSize", "rawCompression"
    )
    private val defaultVideoParamOrder = listOf(
        "movieShutter", "movieAperture", "movieIso", "movieEv",
        "movieMetering", "movieWb", "movieVr",
        "movieFileType", "movieScreenSize", "movieQuality",
        "movieFocusMode", "movieAfAreaMode",
        "movieIsoAuto", "movieIsoAutoHigh",
        "movieAutoDistortion", "movieCrop", "movieAutoDx",
        "movieMic", "movieMicLevel", "movieWindNoise",
        "movieFlicker", "movieHighFreqFlicker",
        "movieTimeCode", "movieCountUp", "movieDropFrame"
    )

    val stillParamOrder = mutableStateListOf<String>().apply {
        addAll(mergedParamOrder(DoukPrefs.paramOrderStill, defaultStillParamOrder))
    }
    val videoParamOrder = mutableStateListOf<String>().apply {
        addAll(mergedParamOrder(DoukPrefs.paramOrderVideo, defaultVideoParamOrder))
    }
    val hiddenStillParamKeys = mutableStateListOf<String>().apply {
        addAll(restoredHiddenKeys(DoukPrefs.paramHiddenStill, defaultStillParamOrder))
    }
    val hiddenVideoParamKeys = mutableStateListOf<String>().apply {
        addAll(restoredHiddenKeys(DoukPrefs.paramHiddenVideo, defaultVideoParamOrder))
    }

    // ===== 控制页自定义卡片 =====
    // 默认按「曝光 / 对焦 / 画质」拆，用户可以在设置里改名、把功能移到别的卡，
    // 控制页按这里的卡片名称逐张显示。
    private val defaultStillGroups = listOf(
        ParamCardGroup(
            "still_exposure", "曝光",
            listOf("iso", "shutter", "aperture", "ev", "expMode", "metering")
        ),
        ParamCardGroup(
            "still_focus", "对焦",
            listOf("focusMode", "afAreaMode", "subjectDetection")
        ),
        ParamCardGroup(
            "still_image", "画质",
            listOf(
                "wb", "drive", "vr", "activeD",
                "imageArea", "imageQuality", "imageSize", "rawCompression"
            )
        )
    )
    private val defaultVideoGroups = listOf(
        ParamCardGroup(
            "video_exposure", "曝光",
            listOf("movieShutter", "movieAperture", "movieIso", "movieEv", "movieMetering")
        ),
        ParamCardGroup(
            "video_frame", "画面",
            listOf("movieFileType", "movieScreenSize", "movieQuality", "movieWb", "movieVr")
        ),
        ParamCardGroup(
            "video_focus", "对焦",
            listOf("movieFocusMode", "movieAfAreaMode")
        ),
        ParamCardGroup(
            "video_audio", "音频",
            listOf("movieMic", "movieMicLevel", "movieWindNoise")
        ),
        ParamCardGroup(
            "video_extra", "其他",
            listOf(
                "movieIsoAuto", "movieIsoAutoHigh",
                "movieAutoDistortion", "movieCrop", "movieAutoDx",
                "movieFlicker", "movieHighFreqFlicker",
                "movieTimeCode", "movieCountUp", "movieDropFrame"
            )
        )
    )

    val stillParamGroups = mutableStateListOf<ParamCardGroup>().apply {
        addAll(decodeParamGroups(DoukPrefs.paramGroupsStill, defaultStillGroups))
    }
    val videoParamGroups = mutableStateListOf<ParamCardGroup>().apply {
        addAll(decodeParamGroups(DoukPrefs.paramGroupsVideo, defaultVideoGroups))
    }

    fun paramGroupsFor(mode: CaptureMode): List<ParamCardGroup> =
        if (mode == CaptureMode.VIDEO) videoParamGroups.toList() else stillParamGroups.toList()

    fun persistParamGroups(mode: CaptureMode) {
        if (mode == CaptureMode.VIDEO) DoukPrefs.paramGroupsVideo = encodeParamGroups(videoParamGroups)
        else DoukPrefs.paramGroupsStill = encodeParamGroups(stillParamGroups)
    }

    fun renameParamGroup(mode: CaptureMode, id: String, name: String) {
        val list = if (mode == CaptureMode.VIDEO) videoParamGroups else stillParamGroups
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) list[index] = list[index].copy(name = name.trim().ifBlank { list[index].name })
        persistParamGroups(mode)
    }

    /** 勾选/取消：一个功能只属于一张卡，勾上即从其它卡移除。 */
    fun setParamInGroup(mode: CaptureMode, groupId: String, key: String, checked: Boolean) {
        val list = if (mode == CaptureMode.VIDEO) videoParamGroups else stillParamGroups
        for (i in list.indices) {
            val group = list[i]
            val keys = if (checked) {
                if (group.id == groupId) {
                    if (key in group.keys) group.keys else group.keys + key
                } else group.keys - key
            } else {
                if (group.id == groupId) group.keys - key else group.keys
            }
            if (keys != group.keys) list[i] = group.copy(keys = keys)
        }
        persistParamGroups(mode)
    }

    fun addParamGroup(mode: CaptureMode) {
        val list = if (mode == CaptureMode.VIDEO) videoParamGroups else stillParamGroups
        list.add(ParamCardGroup("custom_${System.currentTimeMillis()}", "新卡片", emptyList()))
        persistParamGroups(mode)
    }

    /** 删除卡片；至少保留一张，被删卡的参数并到第一张，避免功能消失。 */
    fun removeParamGroup(mode: CaptureMode, id: String) {
        val list = if (mode == CaptureMode.VIDEO) videoParamGroups else stillParamGroups
        if (list.size <= 1) return
        val removed = list.firstOrNull { it.id == id } ?: return
        list.remove(removed)
        if (removed.keys.isNotEmpty()) {
            list[0] = list[0].copy(keys = (list[0].keys + removed.keys).distinct())
        }
        persistParamGroups(mode)
    }

    fun paramOrderFor(mode: CaptureMode): MutableList<String> =
        if (mode == CaptureMode.VIDEO) videoParamOrder else stillParamOrder

    /**
     * 相机现在到底是拍照还是视频：机身拨到视频档时，拍照那套参数会被机身停报，
     * 视频那套才有值。按「哪一组参数真的在报」推断，推断不出就沿用界面上手动选的档。
     */
    val detectedVideoMode: Boolean?
        get() {
            if (!vm.isConnected) return null
            // 轮询线程每 3/6 秒会直接探一次视频档独占属性，比等 extraProps 里旧值刷新快得多。
            vm.videoModeProbe?.let { return it }
            fun reporting(v: String?): Boolean =
                v != null && v.isNotBlank() && v != "—" && v != "相机未上报" && v != "读取中…"
            val still = listOf("shutter", "aperture", "iso").count { reporting(valueOf(it)) }
            val movie = listOf("movieShutter", "movieAperture", "movieIso").count { reporting(valueOf(it)) }
            // 视频档独占的属性：只有机身真的拨到视频档时才有值。
            // 拿它做主要判据，避免「拍照档也照样回报 movie 快门/光圈」造成的误判。
            val movieOnly = listOf("movieFileType", "movieScreenSize", "movieQuality", "movieMic")
                .count { reporting(vm.extraProps[it]?.value) }
            return when {
                movieOnly >= 3 -> true
                movie >= 2 && still == 0 -> true
                still >= 2 && movie == 0 && movieOnly == 0 -> false
                else -> null
            }
        }

    /**
     * 用户在本机手动切过一次档之后，界面就以用户的选择为准，机身识别只做提示。
     * 机身在录像档/拍照档之间拨动（detectedVideoMode 变化）时，由界面把 override 清掉重新跟随。
     */
    var modeManualOverride by mutableStateOf(false)
        private set

    fun selectMode(target: CaptureMode) {
        modeManualOverride = true
        if (mode != target) mode = target
    }

    /** 机身识别的档位变了：清掉手动覆盖，再跟随一次，之后仍允许用户自由切换 */
    fun followDetectedMode(detected: Boolean) {
        modeManualOverride = false
        val target = if (detected) CaptureMode.VIDEO else CaptureMode.STILL
        if (mode != target) mode = target
    }

    /** 界面显示用：手动选过就听手动，没选过才跟相机实际状态 */
    val effectiveMode: CaptureMode
        get() = if (!modeManualOverride) {
            when (detectedVideoMode) {
                true -> CaptureMode.VIDEO
                false -> CaptureMode.STILL
                null -> mode
            }
        } else {
            mode
        }

    fun isParamHidden(key: String): Boolean =
        key.startsWith("movie") && key in hiddenVideoParamKeys ||
            !key.startsWith("movie") && key in hiddenStillParamKeys

    fun setParamVisible(key: String, visible: Boolean) {
        if (key.startsWith("movie")) {
            if (visible) hiddenVideoParamKeys.remove(key) else if (key !in hiddenVideoParamKeys) hiddenVideoParamKeys.add(key)
            DoukPrefs.paramHiddenVideo = hiddenVideoParamKeys.joinToString(",")
        } else {
            if (visible) hiddenStillParamKeys.remove(key) else if (key !in hiddenStillParamKeys) hiddenStillParamKeys.add(key)
            DoukPrefs.paramHiddenStill = hiddenStillParamKeys.joinToString(",")
        }
    }

    fun persistParamOrder(mode: CaptureMode) {
        if (mode == CaptureMode.VIDEO) DoukPrefs.paramOrderVideo = videoParamOrder.joinToString(",")
        else DoukPrefs.paramOrderStill = stillParamOrder.joinToString(",")
    }

    /**
     * 控制页真正要显示的 key：按用户顺序排，去掉被关掉的项，
     * 视频只保留相机上报了的项，拍摄的画质类也只留真实可用的项。
     */
    fun orderedParamKeys(mode: CaptureMode): List<String> {
        val order = paramOrderFor(mode)
        return order.filterNot { isParamHidden(it) }.filter { key ->
            if (mode == CaptureMode.VIDEO) {
                val raw = vm.extraProps[key]?.value
                raw != null && raw != "相机未上报" && raw != "—"
            } else {
                when (key) {
                    "subjectDetection" -> valueOf(key) != "相机未上报" && optionsOf(key).isNotEmpty()
                    "imageArea", "imageQuality", "imageSize", "rawCompression" ->
                        valueOf(key) != "相机未上报" && valueOf(key) != "—"
                    else -> true
                }
            }
        }
    }

    /** 视频模式只显示相机真正上报了的项，避免满屏「相机未上报」的假入口。 */
    fun videoParamKeys(): List<String> = listOf(
        "movieFileType", "movieScreenSize", "movieQuality",
        "movieShutter", "movieAperture", "movieIso", "movieEv",
        "movieMetering", "movieWb", "movieVr",
        "movieFocusMode", "movieAfAreaMode",
        "movieIsoAuto", "movieIsoAutoHigh",
        "movieMic", "movieMicLevel", "movieWindNoise",
        "movieFlicker", "movieHighFreqFlicker",
        "movieAutoDistortion", "movieCrop", "movieAutoDx",
        "movieTimeCode", "movieCountUp", "movieDropFrame"
    ).filter { key ->
        val v = vm.extraProps[key]?.value
        v != null && v != "相机未上报" && v != "—"
    }

    fun valueOf(key: String): String {
        // 取景画质和视频色调都是 App 侧设置，不依赖机身连接
        if (key == "movieToneMode") return movieToneMode
        if (key == "previewQuality") return previewQuality
        // 没连相机就不给档位假数据，统一显示占位符
        if (!isConnected) return "—"
        return when (key) {
        "iso" -> if (usbOnly) (usbIso?.let { "ISO $it" } ?: "USB 未上报") else vm.iso
        "shutter" -> vm.shutterSpeed
        "aperture" -> vm.aperture
        "ev" -> vm.exposureComp
        "expMode" -> vm.exposureModeLabel
        "metering" -> vm.meteringMode
        "focusMode" -> vm.focusModeLabel
        "afAreaMode" -> vm.afAreaMode
        "subjectDetection" -> vm.subjectDetection
        "wb" -> vm.whiteBalance
        "drive" -> {
            // 只有低速连拍 CL 的张数可以调，才把张数并进同一行显示：低速连拍 CL ｜ 5 张
            val base = vm.driveMode
            if ((base.contains("低速连拍") || base.contains("CL")) && vm.burstCountOptions.isNotEmpty()) {
                "$base ｜ ${vm.burstCount}"
            } else base
        }
        "burstCount" -> vm.burstCount
        "vr" -> vm.extraValue("vr")
        "activeD" -> vm.extraValue("activeD")
        "imageArea" -> vm.extraValue("imageArea")
        "imageQuality" -> vm.extraValue("imageQuality")
        "imageSize" -> vm.imageSizeLabel
        "rawCompression" -> vm.extraValue("rawCompression")
        "movieVr" -> vm.extraValue("movieVr")
        "movieWb" -> vm.extraValue("movieWb")
        "movieMetering" -> vm.extraValue("movieMetering")
        "movieShutter" -> vm.extraValue("movieShutter")
        "movieAperture" -> vm.extraValue("movieAperture")
        "movieIso" -> vm.extraValue("movieIso")
        "movieEv" -> vm.extraValue("movieEv")
        "isoAuto" -> vm.extraValue("isoAuto")
        "isoAutoHigh" -> vm.extraValue("isoAutoHigh")
        "movieFileType", "movieScreenSize", "movieQuality", "movieFocusMode",
        "movieAfAreaMode", "movieIsoAuto", "movieIsoAutoHigh", "movieMic",
        "movieMicLevel", "movieWindNoise", "movieFlicker", "movieHighFreqFlicker",
        "movieAutoDistortion", "movieCrop", "movieAutoDx", "movieTimeCode",
        "movieCountUp", "movieDropFrame" -> vm.extraValue(key)
        else -> ""
        }
    }

    fun optionsOf(key: String): List<String> = when (key) {
        "movieToneMode" -> listOf("SDR", "N-Log", "HLG")
        "previewQuality" -> listOf("流畅", "标准", "高清")
        "iso" -> vm.availableIsos
        "shutter" -> vm.availableShutterSpeeds
        "aperture" -> vm.availableApertures
        "ev" -> vm.exposureCompOptions.map { it.label }
        "expMode" -> vm.exposureModeOptions.map { it.label }
        "metering" -> vm.meteringOptions.map { it.label }
        "focusMode" -> vm.focusModeOptions.map { it.label }
        "afAreaMode" -> vm.afAreaModeOptions.map { it.label }
        "subjectDetection" -> vm.subjectDetectionOptions.map { it.label }
        "wb" -> vm.whiteBalanceOptions.map { it.label }
        "drive" -> vm.driveModeOptions.map { it.label }.let { labels ->
            // 有些机型同时上报「连拍」和 CL/CH 分档；有分档时隐藏笼统的「连拍」，
            // 只保留相机真正支持的具体档位，避免看起来像多出一个不存在的选项。
            if (labels.any { it.contains("低速连拍") || it.contains("高速连拍") }) {
                labels.filterNot { it == "连拍" }
            } else labels
        }
        "burstCount" -> vm.burstCountOptions.map { it.label }
        "vr" -> vm.extraOptions("vr")
        "activeD" -> vm.extraOptions("activeD")
        "imageArea" -> vm.extraOptions("imageArea")
        "imageQuality" -> vm.extraOptions("imageQuality")
        "imageSize" -> vm.imageSizeOptions.map { it.label }
        "rawCompression" -> vm.extraOptions("rawCompression")
        "movieVr" -> vm.extraOptions("movieVr")
        "movieWb" -> vm.extraOptions("movieWb")
        "movieMetering" -> vm.extraOptions("movieMetering")
        "movieShutter" -> vm.extraOptions("movieShutter")
        "movieAperture" -> vm.extraOptions("movieAperture")
        "movieIso" -> vm.extraOptions("movieIso")
        "movieEv" -> vm.extraOptions("movieEv")
        "isoAuto" -> vm.extraOptions("isoAuto")
        "isoAutoHigh" -> vm.extraOptions("isoAutoHigh")
        "movieFileType", "movieScreenSize", "movieQuality", "movieFocusMode",
        "movieAfAreaMode", "movieIsoAuto", "movieIsoAutoHigh", "movieMic",
        "movieMicLevel", "movieWindNoise", "movieFlicker", "movieHighFreqFlicker",
        "movieAutoDistortion", "movieCrop", "movieAutoDx", "movieTimeCode",
        "movieCountUp", "movieDropFrame" -> vm.extraOptions(key)
        else -> emptyList()
    }

    fun setParam(key: String, value: String) {
        paramLockReason(key)?.let { reason ->
            lastParamHint = "$value 未下发：$reason"
            lastHint = lastParamHint
            return
        }
        lastParamHint = ""
        when (key) {
            "movieToneMode" -> applyMovieToneMode(value)
            "previewQuality" -> applyPreviewQuality(value)
            "iso" -> vm.setIso(value)
            "shutter" -> vm.setShutterSpeed(value)
            "aperture" -> vm.setAperture(value)
            "ev" -> vm.exposureCompOptions.firstOrNull { it.label == value }?.let { vm.applyExposureComp(it) }
            "expMode" -> vm.exposureModeOptions.firstOrNull { it.label == value }?.let { vm.applyExposureMode(it) }
            "metering" -> vm.meteringOptions.firstOrNull { it.label == value }?.let { vm.applyMeteringMode(it) }
            "focusMode" -> vm.focusModeOptions.firstOrNull { it.label == value }?.let { vm.applyFocusMode(it) }
            "afAreaMode" -> vm.afAreaModeOptions.firstOrNull { it.label == value }?.let { vm.applyAfAreaMode(it) }
            "subjectDetection" -> vm.subjectDetectionOptions.firstOrNull { it.label == value }?.let { vm.applySubjectDetection(it) }
            "wb" -> vm.whiteBalanceOptions.firstOrNull { it.label == value }?.let { vm.applyWhiteBalance(it) }
            "drive" -> vm.driveModeOptions.firstOrNull { it.label == value }?.let { vm.applyDriveMode(it) }
            "burstCount" -> vm.burstCountOptions.firstOrNull { it.label == value }?.let { vm.applyBurstCount(it) }
            "vr" -> vm.extraProps["vr"]?.options?.firstOrNull { it.label == value }?.let { vm.applyExtra("vr", it) }
            "activeD" -> vm.extraProps["activeD"]?.options?.firstOrNull { it.label == value }?.let { vm.applyExtra("activeD", it) }
            "imageArea" -> vm.extraProps["imageArea"]?.options?.firstOrNull { it.label == value }?.let { vm.applyExtra("imageArea", it) }
            "imageQuality" -> vm.extraProps["imageQuality"]?.options?.firstOrNull { it.label == value }?.let { vm.applyExtra("imageQuality", it) }
            "imageSize" -> vm.imageSizeOptions.firstOrNull { it.label == value }?.let { vm.applyImageSize(it) }
            "rawCompression" -> vm.extraProps["rawCompression"]?.options?.firstOrNull { it.label == value }?.let { vm.applyExtra("rawCompression", it) }
            "movieVr" -> vm.extraProps["movieVr"]?.options?.firstOrNull { it.label == value }?.let { vm.applyExtra("movieVr", it) }
            "movieWb" -> vm.extraProps["movieWb"]?.options?.firstOrNull { it.label == value }?.let { vm.applyExtra("movieWb", it) }
            "movieMetering" -> vm.extraProps["movieMetering"]?.options?.firstOrNull { it.label == value }?.let { vm.applyExtra("movieMetering", it) }
            "movieShutter" -> vm.extraProps["movieShutter"]?.options?.firstOrNull { it.label == value }?.let { vm.applyExtra("movieShutter", it) }
            "movieAperture" -> vm.extraProps["movieAperture"]?.options?.firstOrNull { it.label == value }?.let { vm.applyExtra("movieAperture", it) }
            "movieIso" -> vm.extraProps["movieIso"]?.options?.firstOrNull { it.label == value }?.let { vm.applyExtra("movieIso", it) }
            "movieEv" -> vm.extraProps["movieEv"]?.options?.firstOrNull { it.label == value }?.let { vm.applyExtra("movieEv", it) }
            "isoAuto" -> vm.extraProps["isoAuto"]?.options?.firstOrNull { it.label == value }?.let { vm.applyExtra("isoAuto", it) }
            "isoAutoHigh" -> vm.extraProps["isoAutoHigh"]?.options?.firstOrNull { it.label == value }?.let { vm.applyExtra("isoAutoHigh", it) }
            "movieFileType", "movieScreenSize", "movieQuality", "movieFocusMode",
            "movieAfAreaMode", "movieIsoAuto", "movieIsoAutoHigh", "movieMic",
            "movieMicLevel", "movieWindNoise", "movieFlicker", "movieHighFreqFlicker",
            "movieAutoDistortion", "movieCrop", "movieAutoDx", "movieTimeCode",
            "movieCountUp", "movieDropFrame" ->
                vm.extraProps[key]?.options?.firstOrNull { it.label == value }?.let { vm.applyExtra(key, it) }
            else -> lastHint = "$key 由机身菜单控制，PTP 未公开写入通道"
        }
    }

    /** 启动时把监看偏好送进解码器；换画质立刻生效，下一帧起按新档位解码 */
    fun reloadMonitorPrefs() {
        previewQuality = DoukPrefs.monitorPreviewQuality
        movieToneMode = DoukPrefs.monitorToneMode
        LiveViewQuality.level = when (previewQuality) {
            "流畅" -> LiveViewQuality.FLUENT
            "高清" -> LiveViewQuality.HD
            else -> LiveViewQuality.STANDARD
        }
    }

    /** 画质卡片里直接选档；不再只能点一次循环一档。 */
    fun applyPreviewQuality(value: String) {
        val valid = value.takeIf { it in listOf("流畅", "标准", "高清") } ?: return
        previewQuality = valid
        DoukPrefs.monitorPreviewQuality = valid
        reloadMonitorPrefs()
        lastHint = "预览画质已切到 $valid"
    }

    /** 视频色调模式只在 App 内生效：选 N-Log / HLG 后自动套对应还原 LUT。 */
    fun applyMovieToneMode(value: String) {
        val valid = value.takeIf { it in listOf("SDR", "N-Log", "HLG") } ?: return
        movieToneMode = valid
        DoukPrefs.monitorToneMode = valid
        lastHint = "视频色调模式：$valid"
    }

    /** 在「监看工具」里循环切换取景画质：流畅 → 标准 → 高清 */
    fun cyclePreviewQuality() {
        val next = when (previewQuality) {
            "流畅" -> "标准"
            "标准" -> "高清"
            else -> "流畅"
        }
        previewQuality = next
        DoukPrefs.monitorPreviewQuality = next
        reloadMonitorPrefs()
        lastHint = "预览画质已切到 $next"
    }

    /**
     * 这一项现在能不能真的写进相机；能写返回 null，不能写返回一句人话原因。
     * 依据是 GetDevicePropDesc 的 writable 位 + 档位数 + 曝光模式码，不是猜的：
     * 相机在 AUTO / 场景档会把快门、光圈锁成「单值、只读」，此时下发只会被 0x200F 拒。
     */
    fun paramLockReason(key: String): String? {
        // 取景画质和视频色调只改 App 自己，不可能被机身锁住
        if (key == "previewQuality" || key == "movieToneMode") return null
        // 没连相机时谈不上「不可写」，不挂锁标；否则满屏都是灰行
        if (!isConnected) return null
        return when (key) {
            "shutter" -> when {
                usbOnly -> "USB 通道未开放快门写入"
                vm.autoExposureLocked -> "机身在自动／场景档，快门由相机决定；把机顶拨盘转到 M 或 S 再试"
                !vm.shutterWritable -> "相机把快门上报为只读（只给 1 个档位）"
                vm.availableShutterSpeeds.size <= 1 -> "相机只上报 1 个快门档位，无法远程改"
                else -> null
            }
            "aperture" -> when {
                usbOnly -> "USB 通道未开放光圈写入"
                vm.autoExposureLocked -> "机身在自动／场景档，光圈由相机决定；把机顶拨盘转到 M 或 A 再试"
                !vm.apertureWritable -> "相机把光圈上报为只读（只给 1 个档位）"
                vm.availableApertures.size <= 1 -> "镜头当前只上报 1 个光圈档位"
                else -> null
            }
            "iso" -> when {
                usbOnly -> null
                !vm.isoWritable -> "相机把 ISO 上报为只读"
                vm.availableIsos.size <= 1 -> "相机只上报 1 个 ISO 档位"
                else -> null
            }
            "ev" -> if (vm.exposureCompOptions.isEmpty()) "相机未上报曝光补偿档位" else null
            "expMode" -> if (vm.exposureModeOptions.isEmpty()) "相机未上报曝光模式档位" else null
            "metering" -> if (vm.meteringOptions.isEmpty()) "相机未上报测光模式档位" else null
            "focusMode" -> when {
                vm.focusModeOptions.isEmpty() -> "相机未上报对焦模式档位"
                !vm.focusModeWritable -> "相机把对焦模式上报为只读，只能在机身/镜头上切换 MF/AF"
                else -> null
            }
            "afAreaMode" -> when {
                vm.afAreaModeOptions.isEmpty() -> "本机 PTP 没有开放 AF 区域模式档位，只能在机身上调"
                else -> null
            }
            "subjectDetection" -> when {
                vm.subjectDetectionOptions.isEmpty() -> "本机 PTP 没有开放拍摄对象侦测档位，只能在机身上调"
                else -> null
            }
            "wb" -> if (vm.whiteBalanceOptions.isEmpty()) "相机未上报白平衡档位" else null
            "drive" -> if (vm.driveModeOptions.isEmpty()) "相机未上报释放模式档位" else null
            "burstCount" -> when {
                vm.burstCountOptions.isEmpty() -> "相机未上报连拍张数范围"
                !vm.driveMode.contains("低速") -> "只有低速连拍 CL 档才能设连拍张数"
                else -> null
            }
            "imageSize" -> if (vm.imageSizeOptions.isEmpty()) "相机未上报图像尺寸档位" else null
            "vr", "activeD", "imageArea", "imageQuality", "rawCompression", "movieVr", "movieWb", "movieMetering",
            "movieShutter", "movieAperture", "movieIso", "movieEv",
            "movieFileType", "movieScreenSize", "movieQuality", "movieFocusMode",
            "movieAfAreaMode", "movieIsoAuto", "movieIsoAutoHigh", "movieMic",
            "movieMicLevel", "movieWindNoise", "movieFlicker", "movieHighFreqFlicker",
            "movieAutoDistortion", "movieCrop", "movieAutoDx", "movieTimeCode",
            "movieCountUp", "movieDropFrame" -> when {
                vm.extraOptions(key).isEmpty() -> "相机未上报这一项的可选档位，暂时只能在机身上调"
                !vm.extraWritable(key) -> "相机把这一项上报为只读，手机端不能改"
                else -> null
            }
            else -> null
        }
    }

    /** 曝光区顶部那条提示：机身接管曝光时告诉用户拨到哪一档，其它情况返回 null */
    val exposureLockHint: String?
        get() = when {
            !isConnected -> null
            usbOnly -> null
            vm.autoExposureLocked ->
                "机身当前不在 M/A/S/P 档，快门与光圈由相机决定（只上报 1 个值）。把机顶模式拨盘转到 M 或 S，回控制页下拉刷新即可远程调。"
            !vm.shutterWritable && !vm.apertureWritable ->
                "相机把快门和光圈都上报为只读：把镜头/机身切换到 M 档，或直接在机身上调。"
            !vm.shutterWritable ->
                "相机把快门上报为只读：把机顶拨盘转到 M 或 S 档再调。"
            !vm.apertureWritable ->
                "光圈由镜头光圈环/机身控制（相机上报为只读），快门与 ISO 仍可远程调。"
            else -> null
        }

    // ===== 真实连接动作 =====

    /** 应用是否已接管机身（更多菜单里的开关状态） */
    var hostControlTaken by mutableStateOf(false)
        private set

    /** 「版权与 IPTC」独立设置页开关 */
    var showCopyrightSheet by mutableStateOf(false)

    /** 更多菜单开关：开=接管机身，关=把控制权还给机身后按键恢复正常 */
    fun setHostControl(on: Boolean) {
        hostControlTaken = on
        vm.setHostControl(on)
        DoukLog.i("Ptp", "HOST_CONTROL_SWITCH", "on" to on)
    }

    /**
     * 监看里的「接管相机」开关：开=进控制模式（相机屏幕会让给 App），
     * 关=只取景、相机屏幕照常显示。取景中切换会重启一次取景让新方案生效。
     */
    fun setTakeoverCamera(on: Boolean) {
        DoukPrefs.takeoverCamera = on
        vm.setTakeoverCamera(on)
        hostControlTaken = on
        DoukLog.i("Ptp", "TAKEOVER_SWITCH", "on" to on)
    }

    /**
     * 启动时把「接管相机」按钮的亮起状态对齐到持久化值：
     * 取景方案本身一直读 pref，但 Compose 里的 hostControlTaken 初始是 false，
     * 不对齐的话会出现「实际已接管、按钮却没亮」的错觉，点一下反而又打开一次。
     */
    fun syncTakeoverFromPref() {
        hostControlTaken = DoukPrefs.takeoverCamera
    }

    /**
     * 后台自动连接：未连接时静默探测「连接过的设备」，设置里打开
     * 「自动连接已连接过的设备」就自动连上。
     *
     * 两个触发源：
     * 1. Wi-Fi 网络可用/能力变化时立即唤醒（手机刚连上相机热点就能连，不用等 30 秒）；
     * 2. 30 秒兜底轮询，防止相机比手机 Wi-Fi 晚开机、或热点没触发 NetworkCallback。
     *
     * 探测只扫端口，不往「搜索到的设备」列表里塞东西。
     */
    /** 未连接时的后台探测周期：5 秒一轮，够快又不至于让相机机和手机都一直醒着 */
    private val deviceWatchdogMs = 5_000L
    private var autoConnectJob: Job? = null
    /** 没连任何设备时的后台短扫：几秒一次，让「搜索到的设备」列表自己刷出来 */
    private var deviceWatchdogJob: Job? = null
    private var autoReconnectObserver: Job? = null
    /** 连上相机后按设置预加载机身图库第一屏；避免用户打开图库时空白等待 */
    private var galleryPreloadObserver: Job? = null
    private var bluetoothReadyObserver: Job? = null
    private var lockedWatchObserver: Job? = null
    /** 自动回传落盘后刷新图库，照片不用退出页面才出现 */
    private var autoSaveGalleryObserver: Job? = null
    private val autoConnectSignal = Channel<Unit>(Channel.CONFLATED)
    private var autoNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var autoNetworkManager: ConnectivityManager? = null
    private var lastNetworkSignalAt = 0L
    /** 上一次自动连接尝试时间：失败后按设置间隔再试，避免界面反复横跳。 */
    private var lastAutoConnectAttemptAt = 0L
    /** 用户主动断开后暂停自动探测，重新点连接时恢复，避免「刚断开又被自动连上」 */
    private var autoConnectPaused = false

    fun startAutoConnectLoop(context: Context) {
        if (autoConnectJob != null) return
        registerAutoConnectNetwork(context)
        autoConnectJob = ioScope.launch {
            var failStreak = 0
            while (isActive) {
                // 开关关着就只低频看一眼开关状态，不扫描、不碰 PTP；用户一打开最多 4 秒内开始连。
                if (!DoukPrefs.autoConnectKnown) {
                    failStreak = 0
                    delay(4_000)
                    continue
                }
                // 刚启动/刚掉线先快速重试：第一次 1.5 秒内就搜一遍，前几次每 4 秒一次；
                // 持续找不到设备后放慢到 8 秒（仍保持「隔几秒自动搜」），不会一直 4 秒空转耗电。
                val retryMs = if (failStreak <= 5) 4_000L
                    else minOf(DoukPrefs.autoReconnectSeconds * 1000L, 8_000L)
                val wait = if (failStreak == 0) 1_500L else retryMs
                withTimeoutOrNull(wait) { autoConnectSignal.receive() }
                try {
                    autoConnectOnce(
                        context,
                        // 快速重试阶段不要再被 20/30 秒冷却挡住，否则「每几秒搜一次」只是空话。
                        minGapMs = if (failStreak == 0) 0L else retryMs
                    )
                } catch (e: Exception) {
                    DoukLog.w("Scan", "AUTO_LOOP_ERROR", "error" to (e.message ?: "unknown"))
                }
                failStreak = if (isConnected) 0 else (failStreak + 1).coerceAtMost(10)
            }
        }
        // 真掉线时不要等满 30 秒：会话从「已连接」变「未连接」，1.5 秒后立刻补连一次。
        // 只监听 false 沿，避免连接失败时自己触发自己形成死循环。
        autoReconnectObserver = ioScope.launch(Dispatchers.Main) {
            var wasConnected = isConnected
            snapshotFlow { isConnected }.collect { connected ->
                if (wasConnected && !connected && DoukPrefs.autoConnectKnown && !autoConnectPaused &&
                    !vm.ptpClient.hasConnection
                ) {
                    delay(1_500)
                    autoConnectSignal.trySend(Unit)
                }
                wasConnected = connected
            }
        }
        // 没有连接任何设备时，后台每 5 秒自己探一次（网关 + 历史 IP + 尼康默认地址），
        // 相机一开机就能在「搜索到的设备」里出现，不用手点搜索。
        // 已经连上（Wi‑Fi / USB / PTP 会话在）、正在连、蓝牙已就绪时一律跳过，避免多路互抢。
        deviceWatchdogJob = ioScope.launch {
            while (isActive) {
                delay(deviceWatchdogMs)
                if (isConnected || isConnecting || vm.ptpClient.hasConnection || usbConnected) continue
                if (ble.phase.value == com.lutcamera.lutcamerapro.network.nikon.ble.NikonBleGpsClient.Phase.READY) continue
                if (lanScanning) continue
                if (connectMode == ConnectMode.BLUETOOTH) {
                    // 蓝牙模式：只是把扫描轮起来（READY/CONNECTING 时 startScan 自己会忽略）
                    if (ble.phase.value == com.lutcamera.lutcamerapro.network.nikon.ble.NikonBleGpsClient.Phase.IDLE) {
                        withContext(Dispatchers.Main) { ble.startScan() }
                    }
                    continue
                }
                if (connectMode == ConnectMode.USB_PTP) continue
                val known = DoukPrefs.knownDevices()
                // 开关关掉时不做后台短扫：手动打开设备页点「搜索」仍然正常扫，
                // 但没必要每隔 5 秒把 Wi‑Fi 模块和相机都叫醒。
                if (!DoukPrefs.autoConnectKnown || known.isEmpty()) continue
                val hits = runCatching {
                    CameraLanProbe.scan(
                        context = context.applicationContext,
                        timeoutMs = 320,
                        preferred = CameraLanProbe.priorityTargets(context, known.map { it.ip })
                    )
                }.getOrNull() ?: emptyList()
                withContext(Dispatchers.Main) {
                    if (hits.isNotEmpty() && hits.map { it.ip } != lanFound.map { it.ip }) lanFound = hits
                    if (hits.isEmpty() && lanFound.isNotEmpty()) lanFound = emptyList()
                }
                // 历史设备重新出现就顺手连上；用户刚手动断开（autoConnectPaused）时只列出来不连
                if (autoConnectPaused) continue
                // 只自动连「连接过」的设备；换网段时如果只记录过一台且只探到一台，才允许按唯一设备兜底。
                val hit = hits.firstOrNull { h -> known.any { it.ip == h.ip } }
                    ?: hits.singleOrNull()?.takeIf { known.size == 1 }
                    ?: continue
                val nowMs = System.currentTimeMillis()
                // 看门狗只在失败超过三次后的长间隔阶段接手；快速重试阶段由 autoConnectOnce 控制，
                // 否则看门狗 20 秒冷却会把「每 4 秒重试」直接挡掉。
                if (nowMs - lastAutoConnectAttemptAt < DoukPrefs.autoReconnectSeconds * 1000L) continue
                lastAutoConnectAttemptAt = nowMs
                DoukLog.i("Scan", "WATCHDOG_CONNECT", "ip" to hit.ip, "via" to hit.iface)
                withContext(Dispatchers.Main) { connectSafely(context, hit.ip, skipPreflight = true) }
            }
        }
        // 自动回传/保护文件导入落盘后让图库立刻重扫，不用退出再进来才看到新照片
        autoSaveGalleryObserver = ioScope.launch(Dispatchers.Main) {
            var last = vm.photosSaved
            snapshotFlow { vm.photosSaved }.collect { count ->
                if (count != last) {
                    last = count
                    galleryRefreshTick++
                }
            }
        }
        // 连接成功上升沿：后台预读一批机身照片，打开图库时直接有最新那几张
        galleryPreloadObserver = ioScope.launch(Dispatchers.Main) {
            var wasConnected = isConnected
            snapshotFlow { isConnected }.collect { connected ->
                // 一路连上就停另一路的搜索：Wi-Fi/USB 会话期间不再扫蓝牙，反过来蓝牙已连也不再扫 STA
                ble.setWifiSessionActive(connected)
                if (connected) {
                    // 成功连上后清掉自动连接冷却，短时间掉线可以立刻重试，而不是干等 30 秒。
                    lastAutoConnectAttemptAt = 0L
                    ble.stopScan()
                    lanScanning = false
                }
                if (connected && !wasConnected && DoukPrefs.galleryCameraPreload &&
                    cameraPhotos.isEmpty() && !vm.isLoadingStorageFiles
                ) {
                    loadCameraPhotos(count = DoukPrefs.galleryPageSize)
                }
                if (connected && !wasConnected && DoukPrefs.lockedOnly && DoukPrefs.lockedAutoImport) {
                    // 「仅连接后新增」需要连接瞬间的锁定句柄做分界；普通模式不做这次枚举
                    if (DoukPrefs.lockedNewOnly) vm.snapshotLockedBaseline()
                    if (DoukPrefs.lockedAskBeforeImport) lockedImportPrompt = true
                    else vm.importLockedPhotos(onlyAfterConnect = DoukPrefs.lockedNewOnly)
                }
                wasConnected = connected
            }
        }
        // 「仅连接后新增」只在连接瞬间扫一次会漏掉“拍完再在机身加锁”的情况：
        // 连着相机时低频巡检，发现新的锁定句柄就导入（带去重，避免重复下载）。
        // 取样间隔压到几秒：用户在机身上给照片加锁后，不打开设置页也能很快自动下载。
        // 取景期间不抢 PTP 通道（进存储枚举会打断实时取景），退出取景后下一轮立刻补扫。
        lockedWatchObserver = ioScope.launch(Dispatchers.Main) {
            snapshotFlow { isConnected }.collect { connected ->
                if (!connected) return@collect
                var lastScanAt = 0L
                var round = 0
                while (isActive && isConnected && DoukPrefs.lockedOnly && DoukPrefs.lockedAutoImport) {
                    // 取景中让路更久一点：实时取景本身就在占 PTP 通道，
                    // 巡检没必要每 2 秒醒一次去确认「有没有新锁定文件」。
                    delay(if (vm.ptpClient.isLiveViewActive.value) 5_000 else 3_000)
                    if (!isConnected) break
                    if (vm.isImportingLocked) continue
                    // 实时取景时让路，避免每次巡检都把取景打断
                    if (vm.ptpClient.isLiveViewActive.value) continue
                    val interval = if (DoukPrefs.lockedNewOnly) 6_000L else 8_000L
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (lastScanAt != 0L && now - lastScanAt < interval) continue
                    lastScanAt = now
                    round++
                    // 平时只看最新 40 个（覆盖“刚拍完才在机身上加锁”）；
                    // 每 6 轮补一次全量，捞得到在回放里给老照片补锁的情况。
                    val fullSweep = round % 6 == 0
                    vm.importLockedPhotos(
                        onlyAfterConnect = DoukPrefs.lockedNewOnly,
                        scanCount = if (fullSweep) 4000 else 40,
                        dedupeHandled = true,
                        quiet = !fullSweep
                    )
                }
            }
        }
        bluetoothReadyObserver = ioScope.launch(Dispatchers.Main) {
            snapshotFlow { ble.phase.value }.collect { phase ->
                if (phase == com.lutcamera.lutcamerapro.network.nikon.ble.NikonBleGpsClient.Phase.READY) {
                    lanScanning = false
                    lanFound = emptyList()
                }
            }
        }
    }

    /** Wi-Fi 网络出现/能力变化时立即触发一次自动连接，替代纯 30 秒轮询。 */
    private fun registerAutoConnectNetwork(context: Context) {
        if (autoNetworkCallback != null) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    signalAutoConnect("NETWORK_AVAILABLE")
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        signalAutoConnect("NETWORK_CAPS")
                    }
                }
            }
            cm.registerNetworkCallback(request, callback)
            autoNetworkCallback = callback
            autoNetworkManager = cm
            DoukLog.i("Connect", "NET_CALLBACK_REGISTERED")
        } catch (e: Exception) {
            DoukLog.w("Connect", "NET_CALLBACK_FAIL", "error" to (e.message ?: ""))
        }
    }

    private fun signalAutoConnect(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastNetworkSignalAt < 5_000L) return
        lastNetworkSignalAt = now
        DoukLog.i("Connect", "AUTO_WAKE", "reason" to reason)
        autoConnectSignal.trySend(Unit)
    }

    private suspend fun autoConnectOnce(
        context: Context,
        minGapMs: Long = DoukPrefs.autoReconnectSeconds * 1000L
    ) {
        if (!DoukPrefs.autoConnectKnown) return
        // 用户主动断开后不要偷偷连回来；重新点连接会解除暂停。
        if (autoConnectPaused) return
        // USB/蓝牙模式不要偷偷走 Wi-Fi 自动连接
        if (connectMode == ConnectMode.USB_PTP || connectMode == ConnectMode.BLUETOOTH) return
        if (ble.phase.value == com.lutcamera.lutcamerapro.network.nikon.ble.NikonBleGpsClient.Phase.READY) return
        if (isConnected || isConnecting) return
        // 事件通道抖动会让 UI 的 isConnected 短暂变 false，但 PTP 会话其实还活着。
        // 这时再连一次会顶掉旧会话，旧会话报断线，界面就来回横跳——以真实会话为准。
        if (vm.ptpClient.hasConnection) return
        // 刚失败过就别马上再顶一次：Nikon 一次只允许一条会话，连续握手会让两边都断。
        val now = System.currentTimeMillis()
        if (now - lastAutoConnectAttemptAt < minGapMs) return
        lastAutoConnectAttemptAt = now
        val known = DoukPrefs.knownDevices()
        if (known.isEmpty()) return
        val hits = CameraLanProbe.scan(
            context = context.applicationContext,
            timeoutMs = 300,
            preferred = CameraLanProbe.priorityTargets(context, known.map { it.ip })
        )
        // 换网段后 IP 会变：只有一台历史设备且只探到一台，才认为就是它；
        // 有多台历史设备时绝不猜，避免把别的相机/同名端口当成已连接过的设备。
        val hit = hits.firstOrNull { h -> known.any { it.ip == h.ip } }
            ?: if (known.size == 1) hits.singleOrNull() else null
        if (hit == null) {
            DoukLog.i("Scan", "AUTO_LOOP_MISS", "nets" to CameraLanProbe.localNetworkSummary())
            return
        }
        DoukLog.i("Scan", "AUTO_LOOP_CONNECT", "ip" to hit.ip, "via" to hit.iface)
        withContext(Dispatchers.Main) {
            // 端口已经探通，不再做一次多余的 1.5 秒预检；直接进 PTP 握手。
            connectSafely(context, hit.ip, skipPreflight = true)
        }
    }

    fun stopAutoConnectLoop() {
        autoConnectJob?.cancel()
        autoConnectJob = null
        autoReconnectObserver?.cancel()
        autoReconnectObserver = null
        galleryPreloadObserver?.cancel()
        galleryPreloadObserver = null
        deviceWatchdogJob?.cancel()
        deviceWatchdogJob = null
        bluetoothReadyObserver?.cancel()
        bluetoothReadyObserver = null
        autoNetworkCallback?.let { callback ->
            try {
                autoNetworkManager?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
        autoNetworkCallback = null
        autoNetworkManager = null
    }

    fun searchDevices(context: Context) {
        when (connectMode) {
            ConnectMode.USB_PTP -> {
                usb.connect()
                lastHint = usb.status.value
            }
            ConnectMode.BLUETOOTH -> {
                if (isConnected || vm.ptpClient.hasConnection) {
                    lastHint = "相机已通过 Wi‑Fi / USB 连接，蓝牙扫描已跳过（避免抢链路）"
                    return
                }
                ble.startScan()
                lastHint = "正在扫描相机的蓝牙广播…"
            }
            else -> {
                if (ble.phase.value == com.lutcamera.lutcamerapro.network.nikon.ble.NikonBleGpsClient.Phase.READY) {
                    lastHint = "相机蓝牙已连接，已暂停 Wi‑Fi 搜索（同一时间只保留一路）"
                    return
                }
                lastHint = "正在搜索相机…"
                // 先同步把 lanScanning 置 true，界面上的下拉指示条不会在扫描启动前误判为「已结束」
                lanScanning = true
                lanFound = emptyList()
                lanScanProgress = ""
                vm.discoverCameras(context)
                ioScope.launch {
                    val known = DoukPrefs.knownDevices()
                    val hits = try {
                        CameraLanProbe.scan(
                            context = context.applicationContext,
                            timeoutMs = 300,
                            preferred = CameraLanProbe.priorityTargets(context, known.map { it.ip })
                        ) { done, total ->
                            lanScanProgress = "$done/$total"
                        }
                    } catch (e: Exception) {
                        // 扫描异常也必须收掉指示条，否则界面会一直停在「正在搜索」
                        withContext(Dispatchers.Main) {
                            lanScanning = false
                            lanScanProgress = ""
                            lastHint = ""
                        }
                        DoukLog.w("Scan", "FAILED", "err" to (e.message ?: "unknown"))
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        lanFound = hits
                        lanScanning = false
                        lanScanProgress = ""
                        // 失败不再往界面上铺提示，结果就在下面「搜索到的设备」列表里
                        lastHint = ""
                        if (hits.isEmpty()) {
                            DoukLog.w("Scan", "NOT_FOUND", "mode" to connectMode, "nets" to CameraLanProbe.localNetworkSummary())
                        } else {
                            DoukLog.i("Scan", "FOUND", "count" to hits.size,
                                "ips" to hits.joinToString(",") { it.ip }, "nets" to CameraLanProbe.localNetworkSummary())
                            // 搜到的是连接过的相机，就直接自动连上，不用再点一次
                            val knownHit = hits.firstOrNull { hit -> known.any { it.ip == hit.ip } }
                                // 相机换网段后 IP 会变：只有一台历史设备 + 只搜到一台，也认作同一台
                                ?: hits.singleOrNull()?.takeIf { known.isNotEmpty() }
                            // 只有设置里打开「自动连接已连接过的设备」才自动连；
                            // 关闭时把搜到的相机列出来，让用户自己点
                            if (DoukPrefs.autoConnectKnown && knownHit != null) {
                                DoukLog.i("Scan", "AUTO_CONNECT", "ip" to knownHit.ip)
                                connectSafely(context, knownHit.ip, skipPreflight = true)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 连接前先验证 TCP 15740 是否真的能通，再进入 PTP 握手。
     *
     * 之前的实现直接把 IP 丢给 PTP 客户端，网络不通时界面只显示「未连接」，
     * 用户没法判断是 IP 写错、还是没连上相机热点。这里把每一步都回显出来。
     *
     * [attempts] 是端口探测次数：默认 2 次，配对完成后的重连要更多次 ——
     * 尼康在配对提交成功后会把内部服务从「配对服务」切到「正常服务」并重启 Wi-Fi，
     * 期间 15740 会关掉十几秒，一次探测必然判成「IP 不可达」。
     */
    fun connectSafely(context: Context, ip: String, attempts: Int = 2, skipPreflight: Boolean = false) {
        val target = ip.trim()
        if (target.isBlank() || !CameraLanProbe.isValidIpv4(target)) {
            lastHint = "请输入合法的相机 IPv4 地址"
            DoukLog.w("Connect", "IP_INVALID", "input" to ip)
            return
        }
        // 用户主动点连接/搜索时恢复自动探测：之前手动断开留下的暂停标记清掉。
        autoConnectPaused = false
        lastAutoConnectAttemptAt = 0L
        applyNetworkMode(target)
        // 自动连接/搜索结果已经用 TCP 15740 探通过，没必要再做一次 1.5 秒预检；
        // 这一步在旧实现里会把每次自动连接都白白拖慢 1.5~4 秒。
        if (skipPreflight) {
            lastHint = "$target 已确认可达，正在建立 PTP/IP 会话…"
            DoukLog.i("Connect", "FAST_CONNECT", "target" to target, "mode" to connectMode)
            vm.connect(context.applicationContext, target)
            return
        }
        lastHint = "正在验证 $target:${CameraLanProbe.PTP_PORT} …"
        DoukLog.i("Connect", "PRECHECK_BEGIN", "target" to "$target:${CameraLanProbe.PTP_PORT}", "mode" to connectMode)
        ioScope.launch {
            var reachable = false
            val tries = attempts.coerceAtLeast(1)
            for (i in 1..tries) {
                reachable = CameraLanProbe.isOpen(target, 1500)
                DoukLog.i("Connect", "PRECHECK_PROBE", "attempt" to i, "of" to tries,
                    "target" to target, "open" to reachable)
                if (reachable) break
                if (i < tries) {
                    val wait = (1200L * i).coerceAtMost(4000L)
                    withContext(Dispatchers.Main) {
                        lastHint = "$target:${CameraLanProbe.PTP_PORT} 暂时没开（第 $i/$tries 次）" +
                            "，${wait / 1000} 秒后再试…"
                    }
                    delay(wait)
                }
            }
            if (reachable) {
                withContext(Dispatchers.Main) {
                    DoukLog.i("Connect", "PREFLIGHT_OK", "target" to "$target:${CameraLanProbe.PTP_PORT}")
                    lastHint = "$target 端口可达，正在建立 PTP/IP 会话…"
                    vm.connect(context.applicationContext, target)
                }
                return@launch
            }

            // 端口一直不通：再扫一遍本机所有网段。STA 模式下相机重新加入热点后
            // 很可能被 DHCP 换到另一个地址，一直拿老 IP 探测就会永远「不可达」。
            val hits = if (connectMode == ConnectMode.WIFI_STA || connectMode == ConnectMode.WIFI_AP) {
                CameraLanProbe.scan(
                    context = context.applicationContext,
                    timeoutMs = 350,
                    preferred = CameraLanProbe.priorityTargets(
                        context,
                        listOf(target) + DoukPrefs.knownDevices().map { it.ip }
                    )
                )
            } else emptyList()
            val hit = hits.firstOrNull { it.ip != target } ?: hits.firstOrNull()
            withContext(Dispatchers.Main) {
                if (hit != null) {
                    DoukLog.i("Connect", "PREFLIGHT_RECOVER", "old" to target, "new" to hit.ip)
                    lastHotIp = hit.ip
                    lastHint = "原来的 $target 不通，但网段里扫到了相机 ${hit.ip}，正在用它连接…"
                    vm.connect(context.applicationContext, hit.ip)
                } else {
                    DoukLog.e("Connect", "PREFLIGHT_FAIL", "target" to "$target:${CameraLanProbe.PTP_PORT}",
                        "localNets" to CameraLanProbe.localNetworkSummary())
                    lastHint = when (connectMode) {
                        ConnectMode.WIFI_STA -> "$target 不可达。"
                        else -> "$target 不可达。"
                    }
                }
            }
        }
    }

    /** 按当前选中的连接方式，把网络模式映射到 PTP 客户端 */
    private fun applyNetworkMode(ip: String) {
        when (connectMode) {
            ConnectMode.WIFI_AP -> {
                vm.connectionMode = NikonPtpClient.ConnectionMode.CAMERA_AP
                vm.switchWirelessMode(WirelessSubMode.AP_DIRECT)
            }
            ConnectMode.WIFI_STA -> {
                vm.connectionMode = NikonPtpClient.ConnectionMode.PHONE_HOTSPOT
                vm.switchWirelessMode(WirelessSubMode.STA_LAN, ip)
            }
            ConnectMode.USB_PTP -> vm.connectionMode = NikonPtpClient.ConnectionMode.CAMERA_AP
            ConnectMode.BLUETOOTH -> vm.connectionMode = NikonPtpClient.ConnectionMode.CAMERA_AP
        }
        vm.cameraIp = ip.trim()
    }

    /** 连接 mDNS 搜到的设备 */
    fun connect(context: Context, camera: DiscoveredCamera) {
        connectMode = camera.mode
        when (camera.mode) {
            ConnectMode.USB_PTP -> {
                usb.connect()
                lastHint = usb.status.value
                return
            }
            ConnectMode.BLUETOOTH -> {
                lastHint = "蓝牙模式只推送 GPS，请在蓝牙卡片里选择相机"
                return
            }
            else -> Unit
        }
        val ip = camera.address.substringBefore(":").ifBlank { NikonPtpClient.DEFAULT_NIKON_AP_IP }
        lastTargetIp = ip
        connectSafely(context, ip)
    }

    /** 手动填写相机 IP 直接连接（相机不广播时的常规做法） */
    fun connectDirect(context: Context, ip: String) {
        lastTargetIp = ip.trim()
        if (connectMode == ConnectMode.USB_PTP) {
            usb.connect()
            lastHint = usb.status.value
            return
        }
        if (connectMode == ConnectMode.BLUETOOTH) {
            ble.startScan()
            lastHint = "蓝牙模式用于 GPS 推送，正在扫描相机。若找不到或配对失败：相机蓝牙同样会被 SnapBridge 占用，" +
                "请先在相机蓝牙菜单里删除已配对设备（含 SnapBridge），并关闭手机上 SnapBridge 的蓝牙连接。"
            DoukLog.i("Ble", "SCAN_START", "hint" to "蓝牙会被 SnapBridge 占用")
            return
        }
        if (ip.isBlank()) {
            lastHint = "请先填写相机 IP"
            return
        }
        connectSafely(context, ip)
    }

    fun confirmLockedImport() {
        lockedImportPrompt = false
        vm.importLockedPhotos(onlyAfterConnect = DoukPrefs.lockedNewOnly)
    }

    fun dismissLockedImport() {
        lockedImportPrompt = false
    }

    fun disconnect() {
        // 用户主动断开：先暂停自动探测，别刚断开 20 秒又被自动连回去。
        autoConnectPaused = true
        lastAutoConnectAttemptAt = System.currentTimeMillis()
        vm.disconnect()
        usb.disconnect()
        ble.disconnect()
        previewVisible = true
        pairingRequired = false
        pairingCode = null
        pairingMessage = ""
    }

    /**
     * 一键完成尼康配对：先发 0x952B 取验证码，再自动发 0x935A 确认。
     * 用户不需要看机身验证码，也不需要手动点第二次；相机没准备好时自动重试，
     * 最多 3 轮（每轮之间重发一次 0x952B），仍失败就只弹一条 Toast。
     */
    fun confirmPairing(context: Context) {
        if (pairingBusy) return
        pairingBusy = true
        pairingCode = null
        DoukLog.i("Pair", "AUTO_BEGIN", "stage" to vm.connectionStage.value)
        ioScope.launch {
            try {
                // 第一次 0x952B：有的机身要它先把配对会话打开
                runCatching { vm.ptpClient.requestPairingNow() }
                var accepted = false
                var lastCode = -1
                for (attempt in 0 until 3) {
                    delay(if (attempt == 0) 900L else 1500L)
                    val code = runCatching { vm.ptpClient.submitPairingNow() }.getOrElse { e ->
                        DoukLog.e("Pair", "AUTO_SUBMIT_FAIL", "attempt" to attempt + 1, "error" to (e.message ?: ""))
                        -1
                    }
                    lastCode = code
                    DoukLog.i("Pair", "AUTO_SUBMIT", "attempt" to attempt + 1, "code" to "0x%04X".format(code))
                    if (code == 0x2001) {
                        accepted = true
                        break
                    }
                    // 第一轮被拒时重发一次 0x952B，再继续确认（不少机型要两次往返才切到正常服务）
                    if (attempt == 0) runCatching { vm.ptpClient.requestPairingNow() }
                }
                if (!accepted) {
                    withContext(Dispatchers.Main) {
                        pairingBusy = false
                        Toast.makeText(
                            context,
                            "配对未完成（0x%04X），请让相机停在「连接至智能设备」界面再点一次".format(lastCode),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                // 配对完成后相机会把内部服务从「配对服务」切到「正常服务」，并重启 Wi-Fi：
                // 表现为机身屏幕显示已连接、而本机连 15740 一律超时。必须等它重新监听再握手，
                // 且要允许换 IP（热点可能重新分配地址）。不能只探测一次就判失败。
                vm.ptpClient.clearPairingRequired()
                withContext(Dispatchers.Main) {
                    pairingRequired = false
                    pairingBusy = false
                    pairingMessage = ""
                    DoukLog.i("Pair", "AUTO_ACCEPTED")
                }
                vm.disconnect()
                reconnectAfterPairing(context)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pairingBusy = false
                    Toast.makeText(context, "配对失败：${e.message}", Toast.LENGTH_LONG).show()
                    DoukLog.e("Pair", "AUTO_FAIL", "error" to (e.message ?: ""))
                }
            }
        }
    }

    /**
     * 配对成功之后的重新连接。
     *
     * 尼康的配对分两段：`0x952B` 取验证码、`0x935A` 提交确认。提交成功后机身会**换一套服务**
     * （研究项目 thetether 对 Z7II/Z8 的抓包把这一步叫 pairing → normal service handoff），
     * 原来的会话被相机主动关掉，15740 端口要关十几秒才重新开放；有的机型重连热点后
     * 还会被 DHCP 换到别的 IP。所以这里按「等待 → 探测 → 换 IP → 再探测」循环，而不是探一次就报失败。
     */
    private suspend fun reconnectAfterPairing(context: Context) {
        val plan = listOf(1500L, 2000L, 2500L, 3000L, 3500L, 4000L)
        var ip = lastHotIp.ifBlank { lastTargetIp.ifBlank { vm.cameraIp } }
        withContext(Dispatchers.Main) {
            lastHint = "配对已成功。机身正在重启 Wi-Fi、把服务切到「正常模式」，端口会关十几秒，正在等它回来…"
        }
        DoukLog.i("Pair", "HANDOFF_WAIT", "target" to ip)
        for ((idx, waitMs) in plan.withIndex()) {
            delay(waitMs)
            if (ip.isNotBlank() && CameraLanProbe.isOpen(ip, 1500)) {
                DoukLog.i("Pair", "HANDOFF_OPEN", "attempt" to idx + 1, "target" to ip)
                lastHotIp = ip
                withContext(Dispatchers.Main) {
                    lastHint = "相机端口已恢复，正在用 $ip 建立新会话…"
                    vm.connect(context.applicationContext, ip)
                }
                if (awaitConnected(9000)) {
                    withContext(Dispatchers.Main) {
                        pairingMessage = ""
                        DoukLog.i("Pair", "HANDOFF_CONNECTED", "ip" to ip, "attempt" to idx + 1)
                    }
                    return
                }
            }
            // 端口没开，或开了但握手没成：扫一遍网段，相机可能已经换 IP
            if (idx >= 1) {
                val hits = CameraLanProbe.scan(
                    context = context.applicationContext,
                    timeoutMs = 300,
                    preferred = CameraLanProbe.priorityTargets(context, listOf(ip))
                )
                val hit = hits.firstOrNull()
                if (hit != null && hit.ip != ip) {
                    ip = hit.ip
                    lastHotIp = ip
                    DoukLog.i("Pair", "HANDOFF_NEW_IP", "ip" to ip)
                    withContext(Dispatchers.Main) {
                        lastHint = "相机换到了 $ip，正在用它重连…"
                        vm.connect(context.applicationContext, ip)
                    }
                    if (awaitConnected(9000)) {
                        withContext(Dispatchers.Main) { pairingMessage = "" }
                        return
                    }
                }
            }
            withContext(Dispatchers.Main) {
                lastHint = "等相机重新开放 ${CameraLanProbe.PTP_PORT}…（第 ${idx + 1}/${plan.size} 次，目标 $ip）"
            }
        }
        withContext(Dispatchers.Main) {
            pairingMessage = ""
            lastHint = "配对已完成，但相机在 ~25 秒内没有重新开放 ${CameraLanProbe.PTP_PORT}。" +
                "很多机型核对完验证码会自动退出「连接至智能设备」菜单：请回到相机确认该菜单仍显示已连接" +
                "（或重新进入一次），再点「搜索相机设备」。"
        }
        DoukLog.w("Pair", "HANDOFF_TIMEOUT", "target" to ip)
    }

    /** 等 PTP 会话真的成立（[isConnected] 由 getter 读真实状态）。 */
    private suspend fun awaitConnected(timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (isConnected) return true
            delay(250)
            waited += 250
        }
        return isConnected
    }

    /** 蓝牙 GPS：选择扫描到的相机并开始配对 */
    fun connectBleCamera(camera: NikonBleGpsClient.BleCamera) {
        ble.connect(camera)
    }

    /** 蓝牙 GPS：连接上次配对过的相机 */
    fun reconnectBleCamera() {
        if (!ble.reconnectSaved()) lastHint = "还没有保存过的蓝牙相机，请先扫描配对"
    }

    // ===== 控制页动作 =====
    /** USB 侧的录像状态由本地记录（USB PTP 没有单独的状态属性）。 */
    var usbRecording by mutableStateOf(false)

    /** 快门旁延迟拍摄按钮：0=立即拍，2/5/10/20 秒倒计时 */
    var selfTimerSeconds by mutableIntStateOf(0)
    var selfTimerCountdown by mutableIntStateOf(0)
    private var selfTimerJob: Job? = null

    /** 延迟拍摄：按钮上显示剩余秒数，倒计时结束再真正按快门 */
    fun shootWithTimer(seconds: Int = selfTimerSeconds) {
        selfTimerJob?.cancel()
        if (seconds <= 0) {
            selfTimerCountdown = 0
            shoot()
            return
        }
        selfTimerJob = ioScope.launch {
            for (left in seconds downTo 1) {
                withContext(Dispatchers.Main) { selfTimerCountdown = left }
                delay(1000)
            }
            withContext(Dispatchers.Main) {
                selfTimerCountdown = 0
                shoot()
            }
        }
    }

    fun cancelSelfTimer() {
        selfTimerJob?.cancel()
        selfTimerJob = null
        selfTimerCountdown = 0
    }

    // ===== 间隔拍摄（应用侧定时按快门，不依赖相机菜单） =====
    var intervalRunning by mutableStateOf(false)
        private set
    var intervalProgress by mutableStateOf("")
        private set
    private var intervalJob: Job? = null

    fun startIntervalShooting(intervalSeconds: Int, totalShots: Int) {
        if (intervalRunning) return
        if (!isConnected) { lastHint = "相机未连接，无法开始间隔拍摄"; return }
        val interval = intervalSeconds.coerceIn(1, 3600)
        val total = totalShots.coerceIn(1, 999)
        intervalRunning = true
        intervalProgress = "0 / $total"
        intervalJob = ioScope.launch {
            // 间隔拍摄按「先等一个间隔、再拍第一张」执行：
            // 以前 repeat 里第一轮直接 shoot()，点开始就立刻拍一张，看着像多拍了一张。
            repeat(total) { index ->
                if (!isActive) return@launch
                intervalProgress = "等待第 ${index + 1} 张 · ${interval}s"
                delay(interval * 1000L)
                if (!isActive) return@launch
                withContext(Dispatchers.Main) {
                    shoot()
                    intervalProgress = "${index + 1} / $total"
                }
            }
            withContext(Dispatchers.Main) {
                intervalRunning = false
                intervalProgress = "已完成 $total 张"
            }
        }
    }

    fun stopIntervalShooting() {
        intervalJob?.cancel()
        intervalJob = null
        intervalRunning = false
        intervalProgress = "已停止"
    }

    // ===== 拍摄状态摘要：监看页参数卡直接显示，不再只有 ISO/快门/光圈 =====
    /** 拍摄格式：照片看画质/RAW 压缩，视频看文件类型 + 视频画质。 */
    val captureFormatText: String
        get() = if (effectiveMode == CaptureMode.VIDEO) {
            listOf(valueOf("movieFileType"), valueOf("movieQuality"))
                .filter { it.isNotBlank() && it != "—" && it != "相机未上报" }
                .joinToString(" · ")
                .ifBlank { "—" }
        } else {
            listOf(valueOf("imageQuality"), valueOf("rawCompression"))
                .filter { it.isNotBlank() && it != "—" && it != "相机未上报" }
                .joinToString(" · ")
                .ifBlank { "—" }
        }

    /** 拍摄分辨率：照片看图像尺寸 + 图像区域，视频看画面尺寸。 */
    val captureResolutionText: String
        get() = if (effectiveMode == CaptureMode.VIDEO) {
            valueOf("movieScreenSize").ifBlank { "—" }
        } else {
            listOf(valueOf("imageSize"), valueOf("imageArea"))
                .filter { it.isNotBlank() && it != "—" && it != "相机未上报" }
                .joinToString(" · ")
                .ifBlank { "—" }
        }

    /** 对焦模式：只读展示，不在状态卡里点开，避免和右栏对焦按钮重复。 */
    val focusModeText: String
        get() = valueOf(if (effectiveMode == CaptureMode.VIDEO) "movieFocusMode" else "focusMode")
            .ifBlank { "—" }

    /** 测光模式。 */
    val meteringModeText: String
        get() = valueOf(if (effectiveMode == CaptureMode.VIDEO) "movieMetering" else "metering")
            .ifBlank { "—" }

    fun shoot() {
        val activeMode = effectiveMode
        if (!isConnected) {
            lastHint = "相机未连接，无法拍摄"
            return
        }
        if (usbConnected) {
            if (activeMode == CaptureMode.VIDEO) {
                val next = !usbRecording
                usb.ptp.toggleMovieRecord(next) { ok ->
                    usbRecording = if (ok) next else false
                    lastHint = when {
                        ok && next -> "USB 视频录制开始"
                        ok -> "USB 视频录制停止"
                        else -> "USB 视频录制指令被机身拒绝，请确认相机在视频档"
                    }
                }
            } else {
                usb.ptp.triggerCapture()
                photoCounter += 1
                scheduleGalleryRefresh()
                lastHint = "已发送快门"
            }
            return
        }
        if (activeMode == CaptureMode.VIDEO) {
            // 之前这里读的是界面 mode 而不是 effectiveMode：机顶拨盘已经切到视频档时，
            // mode 仍可能停留在 STILL，点了录制却发出拍照命令，看起来就是“没反应”。
            vm.toggleMovieRecord(!vm.isRecordingVideo)
            lastHint = if (vm.isRecordingVideo) "正在停止视频录制…" else "正在开始视频录制…"
        } else {
            vm.triggerShutter()
            photoCounter += 1
            scheduleGalleryRefresh()
            lastHint = "已发送快门"
        }
    }

    /** 拍完等相机写完卡再刷新图库，否则新照片还没进 GetObjectHandles。 */
    private fun scheduleGalleryRefresh() {
        ioScope.launch {
            kotlinx.coroutines.delay(1800)
            withContext(Dispatchers.Main) {
                when {
                    usbOnly -> loadUsbPhotos()
                    vm.isConnected -> loadCameraPhotos()
                    else -> Unit
                }
            }
        }
    }

    fun autoFocus() {
        if (usbConnected) usb.ptp.triggerAf() else vm.triggerAf()
    }

    fun togglePreview() {
        if (usbConnected) {
            if (usb.ptp.isLiveViewActive.value) usb.ptp.stopLiveView() else usb.ptp.startLiveView()
        } else {
            vm.toggleLiveView()
        }
        previewVisible = !previewVisible
    }

    val isRecordingNow: Boolean
        get() = if (usbConnected) usbRecording else vm.isRecordingVideo

    // ===== 图库：相机内照片（PTP 读取） =====
    val cameraPhotos: List<CameraStorageFile> get() = vm.cameraStoragePhotos

    // 枚举本身要遍历所有目录，成本与 count 无关；一次读满整表，图库才不会「显示不全」。
    fun loadCameraPhotos(count: Int = 4000) =
        vm.loadStoragePhotos(prepareTransfer = true, count = maxOf(count, 4000))
    /** 用户主动刷新时才强制重枚举；进图库复用会话内缓存 */
    fun refreshCameraPhotos() = vm.loadStoragePhotos(prepareTransfer = true, force = true)
    val cameraPhotosHasMore: Boolean get() = vm.storageHasMore

    val storageMessage: String get() = vm.storageMessage

    val logs: List<String>
        get() = if (usbConnected) {
            listOf(usb.status.value)
        } else if (ble.phase.value != NikonBleGpsClient.Phase.IDLE) {
            ble.logs.value
        } else vm.logs
}
