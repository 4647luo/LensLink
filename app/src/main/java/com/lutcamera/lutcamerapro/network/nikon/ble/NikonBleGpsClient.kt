package com.lutcamera.lutcamerapro.network.nikon.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.lutcamera.lutcamerapro.ui.douk.DoukPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 向尼康相机推送手机 GPS（蓝牙「智能设备」模式）。
 *
 * 完整流程：扫描（服务 0xDE00 过滤）→ GATT 连接 → 订阅 PAIR/NOT1 →
 * 4 阶段 Blowfish 握手 → 写控制器名 → 系统蓝牙绑定 → 就绪 →
 * 持续写 TIME / GEO 载荷。相机把定位写进照片 EXIF。
 *
 * 协议来源见 [NikonBleProtocol] 的注释；本实现为独立重写。
 */
@SuppressLint("MissingPermission")
class NikonBleGpsClient(private val context: Context) {

    enum class Phase(val label: String) {
        IDLE("未启动"),
        SCANNING("正在扫描尼康相机…"),
        CONNECTING("正在连接相机蓝牙…"),
        HANDSHAKING("正在与相机握手…"),
        BONDING("等待系统蓝牙配对确认…"),
        READY("已连接，正在推送定位"),
        ERROR("连接失败")
    }

    data class BleCamera(
        val name: String,
        val address: String,
        val addressType: Int,
        val advertisedId: Long?,
        val rssi: Int
    )

    private val prefs = context.getSharedPreferences("douk_nikon_ble", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handshake = NikonHandshake()

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase = _phase.asStateFlow()

    private val _statusDetail = MutableStateFlow("")
    val statusDetail = _statusDetail.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _cameras = MutableStateFlow<List<BleCamera>>(emptyList())
    val cameras = _cameras.asStateFlow()

    private val _pushCount = MutableStateFlow(0)
    val pushCount = _pushCount.asStateFlow()

    private val _locationText = MutableStateFlow("")
    val locationText = _locationText.asStateFlow()

    /** GPS 总开关：设备页要实时显示，不能只读一次 SharedPreferences */
    private val _gpsEnabledFlow = MutableStateFlow(prefs.getBoolean("gps_enabled", true))
    val gpsEnabledFlow = _gpsEnabledFlow.asStateFlow()

    /** 定位精度模式：high / balanced / low（设置页和蓝牙层共用这个 key） */
    private val _locationAccuracyFlow =
        MutableStateFlow(prefs.getString("location_accuracy", "balanced") ?: "balanced")
    val locationAccuracy = _locationAccuracyFlow.asStateFlow()

    /** 推送时是否顺带校时 */
    private val _syncTimeFlow = MutableStateFlow(prefs.getBoolean("sync_time", true))
    val syncTime = _syncTimeFlow.asStateFlow()

    /** 当前蓝牙链路信息（连接时/扫描时由 BleCamera 带入） */
    private val _connectedCameraName = MutableStateFlow<String?>(null)
    val connectedCameraName = _connectedCameraName.asStateFlow()

    private val _connectedCameraAddress = MutableStateFlow<String?>(null)
    val connectedCameraAddress = _connectedCameraAddress.asStateFlow()

    private val _connectedCameraRssi = MutableStateFlow<Int?>(null)
    val connectedCameraRssi = _connectedCameraRssi.asStateFlow()

    /** 手机侧当前 fix 的完整信息，设备页用它显示精度/海拔/卫星/来源 */
    private val _lastFixAt = MutableStateFlow(0L)
    val lastFixAt = _lastFixAt.asStateFlow()

    private val _lastFixLat = MutableStateFlow(0.0)
    val lastFixLat = _lastFixLat.asStateFlow()

    private val _lastFixLon = MutableStateFlow(0.0)
    val lastFixLon = _lastFixLon.asStateFlow()

    private val _lastFixAccuracy = MutableStateFlow(0f)
    val lastFixAccuracy = _lastFixAccuracy.asStateFlow()

    private val _lastFixAltitude = MutableStateFlow<Double?>(null)
    val lastFixAltitude = _lastFixAltitude.asStateFlow()

    private val _lastFixSatellites = MutableStateFlow(0)
    val lastFixSatellites = _lastFixSatellites.asStateFlow()

    private val _lastFixProvider = MutableStateFlow("")
    val lastFixProvider = _lastFixProvider.asStateFlow()

    /** 最近一包 GEO 实际写给相机的卫星数 / 海拔（卫星数不足时会兜底成 4） */
    private val _lastPayloadSatellites = MutableStateFlow(0)
    val lastPayloadSatellites = _lastPayloadSatellites.asStateFlow()

    private val _lastPayloadAltitude = MutableStateFlow<Int?>(null)
    val lastPayloadAltitude = _lastPayloadAltitude.asStateFlow()

    /** null=还没推过；true=相机回执确认；false=写入失败/被拒收 */
    private val _lastPushConfirmed = MutableStateFlow<Boolean?>(null)
    val lastPushConfirmed = _lastPushConfirmed.asStateFlow()

    /** 最近一次成功推送的时间（毫秒），0 表示还没推过 */
    private val _lastPushAt = MutableStateFlow(prefs.getLong("last_push_at", 0L))
    val lastPushAt = _lastPushAt.asStateFlow()

    /** 最近一次推送的位置中文名（反地理编码得到） */
    private val _lastPushPlace = MutableStateFlow(prefs.getString("last_push_place", "") ?: "")
    val lastPushPlace = _lastPushPlace.asStateFlow()

    /** 相机确认收到的那一包定位的纬度 / 经度 / UTC 时间，用于设备页的成功反馈 */
    private val _lastPushLat = MutableStateFlow(prefs.getString("last_push_lat", "")?.toDoubleOrNull() ?: 0.0)
    val lastPushLat = _lastPushLat.asStateFlow()

    private val _lastPushLon = MutableStateFlow(prefs.getString("last_push_lon", "")?.toDoubleOrNull() ?: 0.0)
    val lastPushLon = _lastPushLon.asStateFlow()

    private val _lastPushUtc = MutableStateFlow(prefs.getString("last_push_utc", "") ?: "")
    val lastPushUtc = _lastPushUtc.asStateFlow()

    /** 推送间隔（分钟），可选 1/2/3/5/10/20/30/60 */
    private val _intervalMinutes = MutableStateFlow(prefs.getInt("interval_min", 5).takeIf { it > 0 } ?: 5)
    val intervalMinutes = _intervalMinutes.asStateFlow()

    fun setPushInterval(minutes: Int) {
        val valid = minutes.takeIf { it > 0 } ?: 5
        _intervalMinutes.value = valid
        prefs.edit().putInt("interval_min", valid).apply()
        log("推送间隔改为 $valid 分钟")
    }

    /** GPS 总开关：关闭后不再自动推送、不再起定位循环；打开后若相机已连上立刻恢复循环 */
    fun setGpsEnabled(enabled: Boolean) {
        gpsEnabled = enabled
        _gpsEnabledFlow.value = enabled
        prefs.edit().putBoolean("gps_enabled", enabled).apply()
        if (!enabled) {
            stopGpsLoop()
            _statusDetail.value = "GPS 总开关已关闭"
            log("GPS 总开关关闭，停止自动推送")
        } else {
            log("GPS 总开关打开")
            if (_phase.value == Phase.READY) startGpsLoop()
        }
    }

    /** 设置页改了定位精度后同步到设备页的状态卡片，避免两处显示不一致 */
    fun setLocationAccuracy(mode: String) {
        val valid = mode.takeIf { it == "high" || it == "low" } ?: "balanced"
        _locationAccuracyFlow.value = valid
        prefs.edit().putString("location_accuracy", valid).apply()
    }

    fun setSyncTime(enabled: Boolean) {
        _syncTimeFlow.value = enabled
        prefs.edit().putBoolean("sync_time", enabled).apply()
    }

    private val _controllerName = MutableStateFlow(
        prefs.getString("controller", null) ?: NikonControllerName.generate(Build.MODEL)
    )
    val controllerName = _controllerName.asStateFlow()

    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var lastDeviceAddress: String? = null
    private var lastDeviceName: String? = null
    private var advertisedDeviceId: Long? = null
    private var stage1: NikonPairingMessage? = null
    private var step = 0
    private var subscriptionStep = 0
    private var gpsJob: Job? = null
    private var gpsManager: LocationManager? = null
    private var gpsListener: LocationListener? = null
    private var bondReceiver: BroadcastReceiver? = null
    private var lastLocation: Location? = null
    private var bondWaitJob: Job? = null
    private var classicBondedDevice: BluetoothDevice? = null
    private var classicDiscoveryReceiver: BroadcastReceiver? = null
    private var classicDiscoveryRestarts = 0
    private var classicRepairTargetAddress: String? = null
    private var leBondRequested = false
    private var geoRecovering = false
    private var geoRejectAttempts = 0
    private var repairingBond = false
    private var bondVerifiedThisSession = false
    private var gpsLoopBlocked = false
    private var gpsEnabled = prefs.getBoolean("gps_enabled", true)
    private var timeSyncJob: Job? = null
    private var timeKeepAliveJob: Job? = null
    private var lastTimeWriteAt = 0L
    private var lastGeoWriteAt = 0L
    private var readyAt = 0L
    private var gattWriteBusy = false
    private var gattWriteStartedAt = 0L
    @Volatile private var discoveryStarted = false
    @Volatile private var servicesPrepared = false
    /** GATT 连接 / 服务发现 / 握手各自有独立超时，避免点连接后永远停在「连接中」。 */
    private var connectTimeoutJob: Job? = null
    private var discoveryTimeoutJob: Job? = null
    private var handshakeTimeoutJob: Job? = null
    private var pendingPush = false
    private var autoConnectOnHit = false
    private var autoReconnectJob: Job? = null
    private var autoReconnectAttempts = 0
    private var manualDisconnect = false
    private var lastGoodAltitude: Double? = null
    private var placeLat = 0.0
    private var placeLon = 0.0
    private var placeAt = 0L
    /** Wi‑Fi/USB 会话是否已经占用相机；此时不做无意义的蓝牙扫描，省电也避免两路互抢 */
    @Volatile private var wifiSessionActive = false
    /** 智能设备 GPS 是用户主动点的例外：允许在 Wi‑Fi 会话中临时扫一次蓝牙 */
    @Volatile private var allowScanDuringWifi = false

    fun setWifiSessionActive(active: Boolean) {
        wifiSessionActive = active
        if (active && _phase.value != Phase.READY) stopScan()
    }

    fun setAllowScanDuringWifi(allow: Boolean) {
        allowScanDuringWifi = allow
    }

    private val bluetoothManager: BluetoothManager?
        get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val scanner get() = adapter?.bluetoothLeScanner

    // ---------------------------------------------------------------- 扫描

    /** 「发送 GPS 位置至相机」：拿当前定位立刻推一次地理标记（时间由相机从 GEO 包内读取）。 */
    fun pushLocationNow(): Boolean = pushLocationNowInternal(manual = true)

    /**
     * 智能设备模式下的显式 GPS 推送。尼康没有 PTP GPS 写入口，实际仍走蓝牙 GEO；
     * 这里只允许用户在 Wi‑Fi 会话中主动触发一次扫描，后台不会常驻扫描。
     */
    fun pushLocationNowForSmartDevice(): Boolean {
        allowScanDuringWifi = true
        return pushLocationNowInternal(manual = true)
    }

    private fun pushLocationNowInternal(manual: Boolean): Boolean {
        if (!gpsEnabled) {
            _statusDetail.value = "GPS 总开关已关闭，请先在设置里打开"
            log("GPS 总开关关闭，忽略推送")
            return false
        }
        if (_phase.value != Phase.READY) {
            // 没连上时点「立即推送」不要什么都不做：先扫相机蓝牙并自动连上，连上后自动补推一次
            pendingPush = true
            autoConnectOnHit = true
            log("尚未连接，先扫描相机蓝牙，连上后自动推送")
            _statusDetail.value = "正在扫描相机蓝牙，连上后自动推送…"
            startScan(autoConnect = true)
            return false
        }
        if (manual) {
            // 用户主动再点一次，允许重新走一轮判断；自动补推不能重置，否则会无限重连
            geoRejectAttempts = 0
            gpsLoopBlocked = false
        }
        val location = lastLocation ?: runLastKnownLocation() ?: run {
            log("没有可用定位，无法推送")
            _statusDetail.value = "没有可用定位：请打开定位并在室外等卫星"
            return false
        }
        val now = System.currentTimeMillis()
        // HarmoNiLink 实测：相机在 READY 瞬间 GPS 会话还没唤醒，0x2007 会直接被 0x80 拒掉。
        // 等 TIME 心跳把会话拉起来 2.5 秒后再写 GEO。
        if (readyAt != 0L && now - readyAt < 2500L) {
            pendingPush = true
            _statusDetail.value = "相机 GPS 会话唤醒中，稍后自动推送…"
            log("相机 GPS 会话唤醒中，延后 ${2500L - (now - readyAt)}ms 再推 GEO")
            scope.launch {
                delay(2500L - (now - readyAt))
                if (pendingPush && _phase.value == Phase.READY) {
                    pendingPush = false
                    pushLocationNowInternal(manual = false)
                }
            }
            return true
        }
        // TIME 是会话心跳，必须比 GEO 先发；心跳超过 2 秒没发就先补一拍。
        if (now - lastTimeWriteAt > 2000L) {
            sendTimeHeartbeat("GEO 前补发 TIME")
            _statusDetail.value = "已补发 TIME 心跳，1.2 秒后推送定位…"
            scope.launch {
                delay(1200)
                if (_phase.value == Phase.READY) pushLocationNowInternal(manual = false)
            }
            return true
        }
        if (now - lastGeoWriteAt < 1500L) {
            log("GEO 刚写过一包（${now - lastGeoWriteAt}ms），跳过重复包避免 GATT 写槽冲突")
            return false
        }
        val payload = buildGeoPayload(location)
        lastGeoWriteAt = now
        val geoOk = writeCharacteristic(NikonBleUuids.GEO, payload)
        if (geoOk) _statusDetail.value = "定位已发出，等待相机确认…"
        log(if (geoOk) "手动推送：请求已发出，等相机回执" else "定位推送失败（GATT 写槽忙或通道未就绪）")
        return geoOk
    }

    /** 单独发一拍 TIME 心跳；GEO 成功与否都不影响它。 */
    private fun sendTimeHeartbeat(reason: String): Boolean {
        if (_phase.value != Phase.READY || geoRecovering || repairingBond) return false
        val payload = NikonTimePayload.build(System.currentTimeMillis())
        val ok = writeCharacteristic(NikonBleUuids.TIME, payload)
        if (ok) {
            if (lastTimeWriteAt == 0L) {
                log("TIME 首拍载荷 bytes=${payload.joinToString("") { "%02x".format(it) }}")
            }
            lastTimeWriteAt = System.currentTimeMillis()
            log("TIME 心跳（$reason）")
        } else {
            log("TIME 心跳未发出（GATT 写槽忙）")
        }
        return ok
    }

    /**
     * TIME 心跳轨：READY 后立刻发第一拍保活，之后每 4 秒一拍。
     * 握手/状态注入阶段用 HIGH 连接优先级，进入 READY 后切 LOW_POWER，
     * 由 4 秒心跳承担保活，不再每 1.5 秒唤醒一次相机和手机射频。
     */
    private fun startTimeKeepAlive() {
        timeKeepAliveJob?.cancel()
        timeKeepAliveJob = scope.launch {
            delay(250)
            while (_phase.value == Phase.READY && !geoRecovering && !repairingBond) {
                sendTimeHeartbeat("周期心跳")
                // 亮屏时 4 秒一次保证参数/取景响应；熄屏后拉长到 10 秒，
                // BLE 只做低功耗保活，避免口袋里边每 1.5 秒唤醒一次射频。
                val screenOn = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.isInteractive == true
                delay(if (screenOn) 4_000L else 10_000L)
            }
        }
    }

    private fun stopTimeKeepAlive() {
        timeKeepAliveJob?.cancel()
        timeKeepAliveJob = null
        timeSyncJob?.cancel()
        timeSyncJob = null
    }

    /** GEO 已被相机确认后再单独校时，避免和 GEO 抢同一个 GATT 写槽。 */
    private fun scheduleTimeSync() {
        // TIME 已经由独立心跳轨负责；GEO 成功后不再重启心跳，避免打断节奏。
        if (timeKeepAliveJob == null && _phase.value == Phase.READY) startTimeKeepAlive()
    }

    /**
     * 组装 41 字节 GEO 载荷。卫星数 Android 的 Location 不一定给（常为 0），
     * 而相机对「卫星数=0」的包会直接拒收（nsg 参考实现固定填 4），这里同样兜底成 4。
     */
    private fun buildGeoPayload(location: Location): ByteArray {
        val measured = location.extras?.getInt("satellites", 0) ?: 0
        val satellites = if (measured in 1..99) measured else 4
        val resolved = resolveAltitude(location)
        val altitude = resolved?.first?.roundToInt() ?: 0
        _lastPayloadSatellites.value = satellites
        _lastPayloadAltitude.value = altitude
        val payload = NikonGeoPayload.build(location.latitude, location.longitude, altitude, satellites)
        log(
            "GEO 载荷 sat=$satellites alt=$altitude src=${resolved?.second ?: "无"} " +
                "ref=${if (altitude >= 0) "P" else "M"} " +
                "bytes=${payload.joinToString("") { "%02x".format(it) }}"
        )
        return payload
    }

    /**
     * 取海拔。Android 的网络定位（NETWORK_PROVIDER）通常不带海拔，
     * 低精度模式下只开网络定位时相机会一直收到 0，机身上就看不到海拔。
     * 这里按「当前 fix → 最近一次有效海拔 → 最近一次 GPS fix」兜底，
     * 保证只要这台手机曾经拿到过 GPS 海拔，后续网络 fix 也能继续推送。
     */
    private fun resolveAltitude(location: Location): Pair<Double, String>? {
        if (location.hasAltitude()) {
            lastGoodAltitude = location.altitude
            return location.altitude to "本次"
        }
        lastGoodAltitude?.let { return it to "缓存" }
        val manager = gpsManager
            ?: (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
        val gps = runCatching { manager?.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        if (gps != null && gps.hasAltitude()) {
            val age = (System.currentTimeMillis() - gps.time).coerceAtLeast(0L)
            // 30 分钟内的 GPS 海拔仍有参考价值；再久就当作失效，避免把旧位置的高度带进来
            if (age <= 30 * 60 * 1000L) {
                lastGoodAltitude = gps.altitude
                return gps.altitude to "GPS 最近"
            }
        }
        return null
    }

    /**
     * 把一次 Location 同时写进 lastLocation 与设备页需要的细项。
     * 只改这里，避免 GPS 循环、lastKnown、手动推送三处各写一遍导致字段不同步。
     */
    private fun rememberFix(location: Location) {
        lastLocation = location
        _lastFixAt.value = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        _lastFixLat.value = location.latitude
        _lastFixLon.value = location.longitude
        _lastFixAccuracy.value = if (location.hasAccuracy()) location.accuracy else 0f
        _lastFixAltitude.value = resolveAltitude(location)?.first
        val measured = location.extras?.getInt("satellites", 0) ?: 0
        _lastFixSatellites.value = if (measured in 1..99) measured else 0
        _lastFixProvider.value = location.provider ?: ""
        val accuracyText = if (location.hasAccuracy()) {
            String.format(java.util.Locale.US, "  ±%.0fm", location.accuracy)
        } else {
            ""
        }
        _locationText.value = String.format(
            java.util.Locale.US, "%.6f, %.6f%s",
            location.latitude, location.longitude, accuracyText
        )
    }

    private fun runLastKnownLocation(): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val last = try { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: Exception) { null }
            ?: try { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { null }
        if (last != null) rememberFix(last)
        return last
    }


    fun startScan(autoConnect: Boolean = false) {
        // 「立即推送 GPS」只允许这一次扫描穿过 Wi‑Fi 门禁，用完立刻复位，避免后台长期扫描。
        val allowWifiOnce = allowScanDuringWifi
        allowScanDuringWifi = false
        autoConnectOnHit = autoConnect
        // 已经连上/正在握手时再点「搜索」不要重新扫描：会先把自己断开，
        // 界面就会在 READY 和 CONNECTING 之间反复跳。
        if (_phase.value == Phase.READY || _phase.value == Phase.CONNECTING) {
            _statusDetail.value = "蓝牙已连接，无需重新搜索"
            log("已连接/连接中，忽略重复扫描")
            return
        }
        if (wifiSessionActive && !allowWifiOnce) {
            _statusDetail.value = "相机正通过 Wi‑Fi 连接，蓝牙扫描已暂停（省电）；要推 GPS 请先断开 Wi‑Fi"
            log("Wi‑Fi 会话占用中，跳过蓝牙扫描")
            return
        }
        if (!hasPermissions()) {
            fail("缺少蓝牙权限，请在系统弹窗里允许「附近的设备」与「位置」")
            return
        }
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            fail("手机蓝牙未开启，请先打开蓝牙")
            return
        }
        stopScan()
        _cameras.value = emptyList()
        _phase.value = Phase.SCANNING
        _statusDetail.value = "把相机切到「连接至智能设备」并进入「配对」，屏幕显示等待连接后再点连接"
        log("开始扫描（尼康服务 0xDE00）")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // 不用服务过滤：部分 ROM 会丢掉只带 0xDE00 的广播，这里自己判断。
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                fail("扫描失败 errorCode=$errorCode")
            }
        }
        scanCallback = callback
        try {
            scanner?.startScan(null, settings, callback)
        } catch (e: Exception) {
            fail("启动扫描异常：${e.message}")
        }
        // 12 秒内一个尼康广播都没收到时给出可执行的提示：相机要在「连接至智能设备」的配对屏上才会开蓝牙广播
        scope.launch {
            delay(12_000)
            if (_phase.value == Phase.SCANNING && _cameras.value.isEmpty()) {
                log("扫描结束：没有收到任何相机蓝牙广播")
                _statusDetail.value =
                    "没搜到相机蓝牙：在相机「网络 → 连接至智能设备」里进入配对/等待连接的界面再点一次；" +
                        "如果手机蓝牙里还留着旧的配对记录，先忽略/删除它"
            }
        }
    }

    private fun handleScanResult(result: ScanResult?) {
        result ?: return
        val device = result.device ?: return
        val name = result.scanRecord?.deviceName ?: device.name ?: ""
        val hasNikonService = result.scanRecord?.serviceUuids?.any {
            it.uuid == NikonBleUuids.SERVICE
        } ?: false
        val nameLooksNikon = name.contains("Z ", true) ||
            name.startsWith("NIKON", true) ||
            name.contains("Nikon", true) ||
            name.contains("Z\\d".toRegex())
        if (!hasNikonService && !nameLooksNikon) return
        val advertised = extractNikonDeviceId(result)
        log("扫到 $name / ${device.address} service=${hasNikonService} devId=${advertised ?: "-"}")
        val existing = _cameras.value
        if (existing.any { it.address == device.address }) return
        val camera = BleCamera(
            name = name.ifBlank { "尼康相机" },
            address = device.address,
            addressType = device.type,
            advertisedId = advertised,
            rssi = result.rssi
        )
        _cameras.value = existing + camera
        log("发现相机：${camera.name} [${device.address}]")

        // 点过一次「立即推送 / 搜索相机蓝牙」：命中已配对过的那台就自动连上（地址会轮转，按名字认）
        if (autoConnectOnHit) {
            val wanted = DoukPrefs.bleDefaultName.ifBlank { prefs.getString("name", null) ?: "" }
            val same = if (wanted.isNotBlank()) matchesName(camera.name, wanted) else existing.isEmpty()
            if (same) {
                autoConnectOnHit = false
                log("命中已连接过的相机（$wanted），自动建立 GATT")
                connect(camera)
            }
        }
    }

    private fun extractNikonDeviceId(result: ScanResult): Long? {
        val data = result.scanRecord?.manufacturerSpecificData ?: return null
        for (i in 0 until data.size()) {
            val company = data.keyAt(i)
            if (company != NikonBleUuids.COMPANY_ID_NIKON) continue
            val bytes = data.get(company) ?: continue
            if (bytes.size < 4) continue
            return ((bytes[0].toLong() and 0xFF) or
                ((bytes[1].toLong() and 0xFF) shl 8) or
                ((bytes[2].toLong() and 0xFF) shl 16) or
                ((bytes[3].toLong() and 0xFF) shl 24)) and 0xFFFFFFFFL
        }
        return null
    }

    fun stopScan() {
        val callback = scanCallback
        scanCallback = null
        callback?.let { cb ->
            try {
                scanner?.stopScan(cb)
            } catch (_: Exception) {
            }
        }
        if (_phase.value == Phase.SCANNING) _phase.value = Phase.IDLE
    }

    // ---------------------------------------------------------------- 连接

    /** 成功走到 READY 才记入「连接过的设备」，避免把半途失败的地址写进去 */
    private fun rememberThisCamera() {
        val name = lastDeviceName ?: return
        val address = lastDeviceAddress ?: return
        DoukPrefs.rememberBleDevice(name, address)
        if (DoukPrefs.bleDefaultName.isBlank()) DoukPrefs.bleDefaultName = name
        log("已记录蓝牙相机 $name")
    }

    fun connect(camera: BleCamera) {
        // 已经连上 / 正在连接时再点一次，closeGatt + connectGatt 会把好连接顶掉，
        // 旧 GATT 的断开回调又晚一步回来，界面就会「连上→断开→连上」来回跳。
        if (_phase.value == Phase.READY || _phase.value == Phase.CONNECTING ||
            _phase.value == Phase.HANDSHAKING || _phase.value == Phase.BONDING
        ) {
            log("已在连接流程中（${_phase.value.label}），忽略重复连接")
            return
        }
        manualDisconnect = false
        cancelAutoReconnect()
        if (!hasPermissions()) {
            fail("缺少蓝牙权限")
            return
        }
        stopScan()
        val device = try {
            adapter?.getRemoteDevice(camera.address)
        } catch (e: Exception) {
            null
        }
        if (device == null) {
            fail("无法获取蓝牙设备 ${camera.address}")
            return
        }
        targetDevice = device
        lastDeviceAddress = camera.address
        lastDeviceName = camera.name
        advertisedDeviceId = camera.advertisedId
        _connectedCameraName.value = camera.name
        _connectedCameraAddress.value = camera.address
        _connectedCameraRssi.value = camera.rssi
        _lastPushConfirmed.value = null
        prefs.edit().putString("address", camera.address).putString("name", camera.name).apply()
        openGatt(device)
    }

    fun reconnectSaved(): Boolean {
        val address = prefs.getString("address", null) ?: return false
        val name = prefs.getString("name", null) ?: "尼康相机"
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: Exception) {
            null
        } ?: return false
        manualDisconnect = false
        cancelAutoReconnect()
        targetDevice = device
        lastDeviceAddress = address
        lastDeviceName = name
        openGatt(device)
        return true
    }

    private fun handleGeoRejected() {
        if (gpsLoopBlocked) {
            log("GEO 128：已停止自动重试，忽略后续拒收")
            return
        }
        if (repairingBond) {
            log("GEO 128：正在重新配对，忽略重复拒收")
            return
        }
        if (geoRecovering) {
            log("GEO 128：重连还在路上，忽略重复拒收")
            return
        }
        stopGpsLoop()
        pendingPush = false
        // 拒收只允许自动恢复一次；第二次开始直接停下来，避免界面反复横跳
        geoRejectAttempts++
        if (geoRejectAttempts > 1) {
            geoRecovering = false
            gpsLoopBlocked = true
            _statusDetail.value = "相机仍拒收定位（status=128）：已停止自动重连。请把相机重新切到「连接至智能设备→配对」，再点一次「立即推送 GPS 数据」"
            log("GEO 连续拒收，停止自动重试，避免界面反复横跳")
            return
        }
        val inheritedBond = classicBondedDevice?.takeIf {
            !bondVerifiedThisSession && it.bondState == BluetoothDevice.BOND_BONDED
        }
        when {
            inheritedBond != null -> {
                // 参考 nsg 的 0x80 处理：先断开 → 重新扫描 → 重连 → 重新握手。相机只有
                // 在一次新的握手+ID 写入之后才会切到经典可发现状态；此时再去确认/删除旧配对。
                geoRecovering = true
                repairingBond = true
                _statusDetail.value = "相机拒绝定位：正在重新握手确认配对（请让相机保持「连接至智能设备」界面）"
                log("GEO 128：先重新握手，再确认经典地址 ${inheritedBond.address} 的配对")
                recoverGeoRejection()
            }
            geoRejectAttempts < 1 -> {
                geoRejectAttempts++
                geoRecovering = true
                pendingPush = true
                _statusDetail.value = "相机拒收定位，正在重连蓝牙并重试一次…"
                log("GEO 被拒收（status=128），只重连一次后重试")
                recoverGeoRejection()
            }
            else -> {
                geoRecovering = false
                gpsLoopBlocked = true
                _statusDetail.value = "相机仍拒收定位（status=128）：请把相机重新切到「连接至智能设备→配对」，再点一次「立即推送」"
                log("GEO 连续拒收，停止自动重试，避免界面反复横跳")
            }
        }
    }

    private fun recoverGeoRejection() {
        scope.launch {
            try {
                stopScan()
                closeGattKeepTarget()
                delay(700)
                log("重连准备：重新扫描相机蓝牙")
                autoConnectOnHit = true
                startScan(autoConnect = true)
            } catch (e: Exception) {
                log("重连失败：${e.message}")
                geoRecovering = false
                gpsLoopBlocked = true
                fail("蓝牙重连失败：${e.message}")
            }
        }
    }

    /** 先经典发现确认相机经典地址，再决定是否清除旧配对；绝不能在只知道 BLE 地址时盲删。 */
    private fun repairClassicBond(device: BluetoothDevice) {
        _phase.value = Phase.BONDING
        stopGpsLoop()
        pendingPush = false
        classicBondedDevice = device
        classicRepairTargetAddress = null
        registerBondReceiver()
        _statusDetail.value = "相机拒绝定位：正在经典发现确认相机地址，请保持相机在「连接至智能设备→配对」界面"
        log("GEO 128：保持 BLE 连接，先经典发现确认 ${device.address} 对应的经典设备，确认后再重新配对")
        bondWaitJob?.cancel()
        bondWaitJob = scope.launch {
            delay(90_000)
            if (repairingBond) {
                repairingBond = false
                geoRecovering = false
                gpsLoopBlocked = true
                _statusDetail.value = "重新配对超时：请把相机切到「连接至智能设备→配对」，屏幕出现「等待连接」后再点一次「立即推送」"
                log("重新配对超时")
            }
        }
        scope.launch {
            delay(900)
            if (repairingBond && classicDiscoveryReceiver == null) {
                log("开始经典发现，寻找相机的经典蓝牙地址")
                startClassicBonding()
            }
        }
    }

    private fun removeBondCompat(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("removeBond")
        method.invoke(device) as? Boolean ?: false
    } catch (e: Exception) {
        log("removeBond 反射调用失败：${e.message}")
        false
    }

    private fun cancelConnectTimers() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null
    }

    /**
     * 部分 ROM 上 GATT 已连上、MTU 也回调了，但 discoverServices 的
     * onServicesDiscovered 永远不回来（小米平板实测会卡在这一步）。
     * 这里 3 秒没结果就重新发一次服务发现，再 4 秒还没结果才报超时，
     * 避免界面一直停在「正在连接相机蓝牙…」。
     */
    private fun startDiscoveryWatchdog() {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = scope.launch {
            delay(3_000)
            if (_phase.value != Phase.CONNECTING || servicesPrepared) return@launch
            log("服务发现 3 秒无回调，重试 discoverServices")
            discoveryStarted = false
            runCatching { gatt?.discoverServices() }.onFailure { log("重试服务发现失败：${it.message}") }
            delay(4_000)
            if (_phase.value == Phase.CONNECTING && !servicesPrepared) {
                fail("蓝牙服务发现超时：手机蓝牙栈没有返回相机服务，请关闭再打开手机蓝牙后重试")
            }
        }
    }

    /** 点连接后 9 秒还没进入服务发现/握手，就明确报超时并把 GATT 关掉，别一直转圈。 */
    private fun startConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(9_000)
            if (_phase.value == Phase.CONNECTING) {
                fail("蓝牙连接超时：相机没有响应，请确认相机已切到「连接至智能设备」且距离足够近")
            }
        }
    }

    /** 服务订阅完成后的握手最多 12 秒，防止 NOT1 一直不回导致界面停在「握手中」。 */
    private fun startHandshakeTimeout() {
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = scope.launch {
            delay(12_000)
            if (_phase.value == Phase.HANDSHAKING) {
                fail("蓝牙握手超时：相机没有返回配对消息，请重试连接")
            }
        }
    }

    private fun openGatt(device: BluetoothDevice) {
        _phase.value = Phase.CONNECTING
        _statusDetail.value = "正在连接 ${lastDeviceName ?: device.address}"
        closeGatt()
        step = 0
        subscriptionStep = 0
        leBondRequested = false
        discoveryStarted = false
        servicesPrepared = false
        gattWriteBusy = false
        gattWriteStartedAt = 0L
        lastTimeWriteAt = 0L
        lastGeoWriteAt = 0L
        readyAt = 0L
        // 不要在这里清 geoRecovering / geoRejectAttempts：重连回来的第一次推送如果又被
        // 128 拒收，必须靠这两个计数收口，否则会无限断开重连。
        gatt = try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            fail("GATT 连接异常：${e.message}")
            null
        }
        if (gatt != null) startConnectTimeout()
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // closeGatt / 重新连接后，旧 GATT 的回调可能晚几秒才到。
            // 不拦住它就会把已经 READY 的新连接改回 ERROR，界面看起来反复横跳。
            if (gatt !== this@NikonBleGpsClient.gatt) {
                log("忽略旧 GATT 连接回调 status=$status newState=$newState")
                return
            }
            log("连接状态 status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
                startDiscoveryWatchdog()
                _phase.value = Phase.CONNECTING
                try {
                    // 高优先级连接间隔：握手要来回好几轮，这一句能少等几百毫秒
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt.requestMtu(517)
                    // 连接时顺手读一次信号强度；失败也不影响主流程
                    gatt.readRemoteRssi()
                } catch (e: Exception) {
                    discoveryStarted = true
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("GATT 已断开（status=$status）")
                cancelConnectTimers()
                val wasReady = _phase.value == Phase.READY
                if (wasReady) {
                    _phase.value = Phase.ERROR
                    _statusDetail.value = "连接断开，正在准备自动重连"
                }
                stopGpsLoop()
                // 相机关屏 / 休眠会主动断开 BLE；只要不是用户手动断开，就按间隔扫描找回。
                if (wasReady && !manualDisconnect) scheduleAutoReconnect()
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (gatt !== this@NikonBleGpsClient.gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && rssi in -127..0) {
                _connectedCameraRssi.value = rssi
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== this@NikonBleGpsClient.gatt) return
            log("MTU=$mtu status=$status")
            // onMtuChanged 在部分 ROM 上会重复回调；重复 discoverServices 会让
            // onServicesDiscovered 走两遍、订阅两遍，握手时序直接乱掉。
            if (discoveryStarted) {
                log("服务发现已启动，忽略重复 MTU 回调")
                return
            }
            discoveryStarted = true
            try {
                gatt.discoverServices()
            } catch (e: Exception) {
                fail("发现服务失败：${e.message}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== this@NikonBleGpsClient.gatt) return
            if (servicesPrepared) {
                log("重复的服务发现回调，已忽略（避免二次订阅/二次握手）")
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("服务发现失败 status=$status")
                return
            }
            servicesPrepared = true
            discoveryTimeoutJob?.cancel()
            discoveryTimeoutJob = null
            val service = gatt.getService(NikonBleUuids.SERVICE)
            if (service == null) {
                log("相机未暴露 0xDE00：services=" + gatt.services?.joinToString(",") { shortUuid(it.uuid) })
                fail("相机没有暴露 0xDE00 服务，可能没有切到「连接至智能设备」")
                return
            }
            log("DE00 特征：" + service.characteristics?.joinToString(",") { shortUuid(it.uuid) })
            val pair = service.getCharacteristic(NikonBleUuids.PAIR)
            val not1 = service.getCharacteristic(NikonBleUuids.NOT1)
            if (pair == null || not1 == null) {
                fail("相机缺少 PAIR / NOT1 特征")
                return
            }
            log("服务已就绪，订阅 PAIR 指示与 NOT1 通知")
            subscriptionStep = 1
            enableIndication(gatt, pair)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt !== this@NikonBleGpsClient.gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("订阅描述符写入失败 status=$status")
                return
            }
            when (subscriptionStep) {
                1 -> {
                    subscriptionStep = 2
                    val not1 = gatt.getService(NikonBleUuids.SERVICE)?.getCharacteristic(NikonBleUuids.NOT1)
                    if (not1 != null) enableNotification(gatt, not1) else fail("缺少 NOT1")
                }
                2 -> {
                    subscriptionStep = 0
                    _phase.value = Phase.HANDSHAKING
                    _statusDetail.value = "订阅完成，开始握手"
                    startHandshakeTimeout()
                    scope.launch {
                        delay(250)
                        sendStage1()
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (gatt !== this@NikonBleGpsClient.gatt) return
            handleCharacteristic(characteristic.uuid, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt !== this@NikonBleGpsClient.gatt) return
            handleCharacteristic(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (gatt !== this@NikonBleGpsClient.gatt) return
            gattWriteBusy = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("写入 ${shortUuid(characteristic.uuid)} 失败 status=$status")
                if (characteristic.uuid == NikonBleUuids.GEO) {
                    _lastPushConfirmed.value = false
                }
                // 128(0x80)：相机拒收这一包定位。常见原因是手机里留着一条相机早已不再认的
                // 旧经典蓝牙配对（相机从未弹过 6 位配对码）；其次才是 GATT 通道没恢复。
                // 处理顺序：旧配对 → 清掉重配；本次已确认的配对 → 只重连一次；再失败就停手，
                // 绝不能再无限断开重连，否则界面会一直横跳。
                if (status == 128 && characteristic.uuid == NikonBleUuids.GEO) {
                    handleGeoRejected()
                } else if (status == 128 && characteristic.uuid == NikonBleUuids.TIME) {
                    log("TIME 心跳被相机拒绝（128）：检查相机是否已切到「连接至智能设备」并完成配对")
                }
                return
            }
            when (characteristic.uuid) {
                NikonBleUuids.GEO -> {
                    geoRejectAttempts = 0
                    geoRecovering = false
                    repairingBond = false
                    gpsLoopBlocked = false
                    _lastPushConfirmed.value = true
                    _pushCount.value = _pushCount.value + 1
                    val now = System.currentTimeMillis()
                    _lastPushAt.value = now
                    val utc = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .format(java.util.Date(now)) + " UTC"
                    val location = lastLocation
                    if (location != null) {
                        val latText = String.format(java.util.Locale.US, "%.6f", location.latitude)
                        val lonText = String.format(java.util.Locale.US, "%.6f", location.longitude)
                        _lastPushLat.value = location.latitude
                        _lastPushLon.value = location.longitude
                        _lastPushUtc.value = utc
                        prefs.edit()
                            .putLong("last_push_at", now)
                            .putString("last_push_lat", latText)
                            .putString("last_push_lon", lonText)
                            .putString("last_push_utc", utc)
                            .apply()
                        val moved = Math.abs(location.latitude - placeLat) + Math.abs(location.longitude - placeLon) > 0.0015
                        if (_lastPushPlace.value.isEmpty() || moved || now - placeAt > 10 * 60_000L) {
                            placeLat = location.latitude
                            placeLon = location.longitude
                            placeAt = now
                            resolvePlaceName(location.latitude, location.longitude)
                        }
                    } else {
                        _lastPushUtc.value = utc
                        prefs.edit().putLong("last_push_at", now).putString("last_push_utc", utc).apply()
                    }
                    _statusDetail.value = "推送成功：相机已确认收到定位"
                    log("相机已确认收到定位（累计 ${_pushCount.value} 次）")
                    scheduleTimeSync()
                }
                NikonBleUuids.TIME -> {
                    log("TIME 心跳已被相机确认")
                }
                NikonBleUuids.ID -> {
                    log("控制器名已写入相机")
                    if (step == 4) {
                        step = 5
                        checkBondAndFinish()
                    }
                }
                NikonBleUuids.PAIR -> {
                    log("握手消息已发送（step=$step）")
                }
            }
        }
    }

    // ---------------------------------------------------------------- 握手

    private fun sendStage1() {
        val savedDevice = prefs.getLong("device", 0L).takeIf { it != 0L }
        val savedNonce = prefs.getLong("nonce", 0L).takeIf { it != 0L }
        val identity = advertisedDeviceId ?: savedDevice
        // nsg 的规则：身份来源从已保存 device 切到相机广播 device 时，必须重新生成 nonce；
        // 继续复用旧 nonce 会让相机把 8 字节身份判成另一台设备，后续 GEO 被拒。
        val nonce = if (advertisedDeviceId != null && advertisedDeviceId != savedDevice) null else savedNonce
        val message = handshake.createStage1(identity, nonce)
        stage1 = message
        // 记住本次身份：配对完成后的重连要复用 device/nonce，否则相机会当新设备重新握手
        prefs.edit().putLong("device", message.device).putLong("nonce", message.nonce).apply()
        step = 1
        log("发送阶段 1：device=0x%08x nonce=0x%08x（identity=${if (identity == advertisedDeviceId) "广播" else "已保存"}）"
            .format(message.device, message.nonce))
        writeCharacteristic(NikonBleUuids.PAIR, message.encode())
        // 相机不在配对模式时，阶段 1 发出去不会有任何回音，这里自己收口，别一直转圈
        scope.launch {
            delay(6000)
            if (step == 1 && _phase.value == Phase.HANDSHAKING) {
                fail("相机没有回应握手：请把机身 MENU→网络菜单→连接至智能设备→配对 打开，屏幕出现「等待连接」后再点连接；同时确保没有别的手机/平板连着这台相机")
            }
        }
    }

    private fun handleCharacteristic(uuid: UUID, value: ByteArray) {
        when (uuid) {
            NikonBleUuids.PAIR -> handlePair(value)
            NikonBleUuids.NOT1 -> handleNot1(value)
        }
    }

    private fun handlePair(value: ByteArray) {
        val message = NikonPairingMessage.decode(value)
        if (message == null) {
            log("PAIR 数据长度异常：${value.size}")
            return
        }
        log("收到阶段 ${message.stage}")
        when (step) {
            1 -> {
                val current = stage1
                if (current == null) {
                    fail("内部状态丢失")
                    return
                }
                val stage3 = handshake.buildStage3(current, message)
                if (stage3 == null) {
                    fail("握手校验失败：8 组盐都不匹配（相机可能已与 SnapBridge 绑定了别的身份）")
                    return
                }
                writeCharacteristic(NikonBleUuids.PAIR, stage3.encode())
                step = 2
                log("发送阶段 3 完成")
            }
            2 -> {
                val serial = handshake.serialFromStage4(message)
                log("相机序列号：$serial，等待 NOT1 确认")
                step = 3
                scope.launch {
                    delay(1500)
                    if (step == 3) {
                        log("1.5 秒内没有收到 NOT1 确认，直接写控制器名")
                        writeControllerId()
                    }
                }
            }
            else -> log("忽略阶段 ${message.stage} 的 PAIR 数据")
        }
    }

    private fun handleNot1(value: ByteArray) {
        log("NOT1：${value.joinToString(" ") { "%02x".format(it) }}")
        val ok = value.size >= 2 && value[0] == 0x01.toByte() && value[1] == 0x00.toByte()
        if (ok) {
            when (step) {
                3 -> writeControllerId()
                4 -> checkBondAndFinish()
            }
        }
    }

    private fun writeControllerId() {
        if (step >= 4) return
        step = 4
        val name = _controllerName.value
        val bytes = ByteArray(32)
        val ascii = name.toByteArray(Charsets.US_ASCII)
        ascii.copyInto(bytes, 0, 0, minOf(ascii.size, 32))
        log("写入控制器名：$name")
        writeCharacteristic(NikonBleUuids.ID, bytes)
        scope.launch {
            delay(3000)
            if (step == 4) {
                log("控制器名写入无回调，继续检查绑定状态")
                step = 5
                checkBondAndFinish()
            }
        }
    }

    // ---------------------------------------------------------------- 绑定

    private fun checkBondAndFinish() {
        val device = targetDevice ?: return
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            bondVerifiedThisSession = true
            finishReady()
            return
        }
        val controllerName = _controllerName.value
        val pairedByClassic = adapter?.bondedDevices?.firstOrNull {
            matchesName(it.name, lastDeviceName) || matchesName(it.name, controllerName)
        }
        if (pairedByClassic != null) {
            if (repairingBond) {
                // 重新握手完成，相机已经切到经典可发现状态；现在才去确认经典地址，
                // 找到了再删旧配对，避免像上一版那样把手机里唯一一条记录盲删掉。
                log("重新握手完成，开始确认经典地址（配对记录 ${pairedByClassic.address}）")
                repairClassicBond(pairedByClassic)
                return
            }
            // 如果本次会话刚刚在相机上确认过同一条配对，就不要把它又降级成“继承配对”，
            // 否则 GEO 一旦 128 会把刚配对好的记录删掉重来。
            val alreadyVerified = bondVerifiedThisSession &&
                classicBondedDevice?.address == pairedByClassic.address
            classicBondedDevice = pairedByClassic
            if (!alreadyVerified) bondVerifiedThisSession = false
            log(
                "系统里已有同名配对记录：${pairedByClassic.address} / ${pairedByClassic.name}" +
                    if (alreadyVerified) "（本次会话已确认）" else "（本次会话未验证）"
            )
            finishReady()
            return
        }
        bondVerifiedThisSession = false
        // 参考 hurui200320/nsg 的做法：相机在经典蓝牙里的名字是它自己保存的控制器名，
        // 地址也和 BLE 地址不同，所以必须先断开 GATT、再走经典蓝牙发现 + createBond，
        // 相机屏幕才会弹出 6 位配对码让用户确认（直接对 BLE 地址 createBond 相机不会有任何反应）
        _phase.value = Phase.BONDING
        _statusDetail.value = "请在相机屏幕上确认配对（相机没反应说明它不在配对模式）"
        registerBondReceiver()
        log("断开 GATT，改为经典蓝牙配对（相机经典名=$controllerName）")
        closeGattKeepTarget()
        scope.launch {
            delay(1500)
            if (_phase.value == Phase.BONDING) startClassicBonding()
        }
        bondWaitJob?.cancel()
        bondWaitJob = scope.launch {
            delay(90_000)
            if (_phase.value == Phase.BONDING) fail("配对超时：确认相机 MENU→网络菜单→连接至智能设备→配对 已打开且屏幕显示「等待连接」；已连过别的手机先在相机「已配对设备」里删除")
        }
    }

    /** 只断连接，保留 targetDevice / 地址，配对成功后还要按原 BLE 地址重连。 */
    private fun closeGattKeepTarget() {
        cancelConnectTimers()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        step = 0
        subscriptionStep = 0
    }

    private fun startClassicBonding() {
        val targetName = lastDeviceName ?: return
        val controllerName = _controllerName.value
        log("经典蓝牙发现匹配：BLE名=$targetName / 控制器名=$controllerName")
        stopClassicDiscovery()
        classicDiscoveryRestarts = 0
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                    if (_phase.value == Phase.BONDING && classicDiscoveryRestarts < 2) {
                        classicDiscoveryRestarts++
                        log("经典扫描结束，1 秒后再扫一次")
                        scope.launch {
                            delay(1000)
                            if (_phase.value == Phase.BONDING) startClassicBonding()
                        }
                    }
                    return
                }
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                // 相机经典蓝牙设备名是它保存的控制器名（我们写进去的名称），不是 “Z 50II”
                val match = matchesName(found.name, targetName) || matchesName(found.name, controllerName)
                log("经典设备 ${found.name} / ${found.address} type=${found.type} bond=${found.bondState} match=$match")
                if (!match) return
                // 经典发现里也会带出同一台相机的 BLE（随机）地址。对 LE 地址 createBond
                // 只会得到 BOND_NONE 并反复重试，永远配不上，必须先跳过。
                if (found.type == BluetoothDevice.DEVICE_TYPE_LE) {
                    log("跳过 BLE 地址 ${found.address}，等待相机真正的经典地址")
                    return
                }
                if (found.bondState == BluetoothDevice.BOND_BONDED) {
                    if (repairingBond) {
                        // 找到了真正的经典地址，且它带着一条旧配对：这时才允许删。
                        classicRepairTargetAddress = found.address
                        stopClassicDiscovery()
                        val removed = removeBondCompat(found)
                        log("确认经典地址 ${found.address}，清除旧配对 -> $removed")
                        if (!removed) {
                            repairingBond = false
                            geoRecovering = false
                            gpsLoopBlocked = true
                            _statusDetail.value = "旧蓝牙配对删除失败：请到手机蓝牙设置里删除「${found.name ?: "相机"}」，再回来点「立即推送」"
                            log("removeBond 失败，停止重试")
                            return
                        }
                        _statusDetail.value = "旧配对已删除，正在重新搜索经典地址；请在相机屏幕确认 6 位配对码"
                        // 大多数系统会广播 BOND_NONE；收不到时 2 秒后自己再扫一次
                        scope.launch {
                            delay(2000)
                            if (repairingBond && classicDiscoveryReceiver == null) {
                                log("没等到 BOND_NONE，主动重新经典发现")
                                startClassicBonding()
                            }
                        }
                        return
                    }
                    log("该经典设备已绑定，直接按 BLE 地址重连")
                    classicBondedDevice = found
                    bondVerifiedThisSession = false
                    stopClassicDiscovery()
                    reconnectAfterBonding()
                    return
                }
                log("对经典设备发起绑定 ${found.address}")
                try {
                    val created = found.createBond()
                    log("经典 createBond 返回 $created")
                    if (created) {
                        stopClassicDiscovery()
                    }
                } catch (e: Exception) {
                    log("经典 createBond 异常：${e.message}")
                }
            }
        }
        classicDiscoveryReceiver = receiver
        try {
            ContextCompat.registerReceiver(
                context, receiver,
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                },
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {
        }
        try {
            adapter?.cancelDiscovery()
            adapter?.startDiscovery()
            log("开始经典蓝牙扫描")
        } catch (e: Exception) {
            log("经典蓝牙扫描失败：${e.message}")
        }
        scope.launch {
            delay(25_000)
            if (_phase.value == Phase.BONDING && repairingBond) {
                log("经典扫描 25 秒没找到可绑定设备")
                _statusDetail.value = "没找到相机经典蓝牙：请在相机打开「连接至智能设备→配对」，屏幕出现「等待连接」后再试"
            }
        }
    }

    private fun stopClassicDiscovery() {
        classicDiscoveryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        classicDiscoveryReceiver = null
        try {
            adapter?.cancelDiscovery()
        } catch (_: Exception) {
        }
    }

    /** 绑定完成后回到 BLE 地址重连，让握手/GPS 通道生效。 */
    private fun reconnectAfterBonding() {
        val bleDevice = targetDevice
        if (bleDevice == null) {
            finishReady()
            return
        }
        log("配对完成，重连 BLE ${bleDevice.address}")
        scope.launch {
            delay(1000)
            openGatt(bleDevice)
        }
    }

    private fun registerBondReceiver() {
        if (bondReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
                    // 相机要求确认时，系统会广播配对请求，码就是相机屏幕上的 6 位数
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device == null) return
                    val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, 0)
                    val key = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, 0)
                    val passkey = String.format(java.util.Locale.US, "%06d", key)
                    log("配对请求 ${device.address} variant=$variant key=$passkey")
                    if (_phase.value == Phase.BONDING) {
                        _statusDetail.value = "相机屏幕会显示配对码 $passkey，请在相机上按确认"
                    }
                    return
                }
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                log("绑定状态 ${device.address} -> $state")
                val relevant = device.address == classicRepairTargetAddress ||
                    device.address == targetDevice?.address ||
                    matchesName(device.name, lastDeviceName) ||
                    matchesName(device.name, _controllerName.value)
                if (!relevant) {
                    log("忽略其它蓝牙设备的绑定状态 ${device.address} / ${device.name}")
                    return
                }
                when (state) {
                    BluetoothDevice.BOND_BONDED -> {
                        bondWaitJob?.cancel()
                        stopClassicDiscovery()
                        classicBondedDevice = device
                        classicRepairTargetAddress = null
                        val wasRepairing = repairingBond
                        repairingBond = false
                        geoRecovering = false
                        bondVerifiedThisSession = true
                        gpsLoopBlocked = false
                        // 本次刚在相机上确认过的配对，最多允许自动补推一次；再失败就停手
                        geoRejectAttempts = if (wasRepairing) 1 else 0
                        if (wasRepairing) {
                            pendingPush = true
                            _statusDetail.value = "重新配对成功，正在重连并补推定位…"
                        }
                        // 绑定是在经典蓝牙地址上完成的，BLE 连接要按原地址重新建立
                        reconnectAfterBonding()
                    }
                    BluetoothDevice.BOND_NONE -> {
                        if (repairingBond && device.address == classicRepairTargetAddress) {
                            log("旧配对已删除，开始重新搜索经典地址")
                            _statusDetail.value = "旧配对已删除，正在搜索相机经典蓝牙，请在相机屏幕确认配对码"
                            scope.launch {
                                delay(800)
                                if (repairingBond) startClassicBonding()
                            }
                        } else if (_phase.value == Phase.BONDING) {
                            // 删除旧配对前，BOND_NONE 可能来自别的设备或 LE 地址，不要打断流程
                            log("收到无关的 BOND_NONE ${device.address}，继续等待经典地址")
                        }
                    }
                }
            }
        }
        bondReceiver = receiver
        try {
            ContextCompat.registerReceiver(
                context, receiver,
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                },
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {
        }
    }

    private fun finishReady() {
        cancelConnectTimers()
        cancelAutoReconnect()
        _phase.value = Phase.READY
        _statusDetail.value = "相机已接收蓝牙身份，开始推送定位"
        geoRecovering = false
        repairingBond = false
        step = 6
        val current = stage1
        if (current != null) {
            prefs.edit()
                .putLong("device", current.device)
                .putLong("nonce", current.nonce)
                .putString("controller", _controllerName.value)
                .apply()
        }
        log("就绪：可以开始推送 GPS")
        // 握手/状态注入已完成，BLE 只保留「低频状态心跳 + 保活」职责。
        // 立刻申请 LOW_POWER 连接间隔，避免相机和手机射频维持高刷新率持续耗电。
        runCatching {
            gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
        }.onSuccess {
            log("BLE 已切换 LOW_POWER 连接优先级，进入低功耗保活")
        }.onFailure {
            log("BLE 低功耗优先级切换失败：${it.message}")
        }
        rememberThisCamera()
        readyAt = System.currentTimeMillis()
        // 先把 TIME 心跳轨拉起来：相机 GPS 会话靠它保活，GEO 不能抢在它前面。
        startTimeKeepAlive()
        // 补推也要等 2.5 秒，让相机从「刚握手完」切到可接收定位的状态。
        // 循环内部同样会等 3.8 秒，且 lastGeoWriteAt 会拦住重复包。
        if (pendingPush) {
            pendingPush = false
            log("补推：连接前点过立即推送，2.5 秒后补发一次")
            scope.launch {
                delay(2500)
                if (_phase.value == Phase.READY) pushLocationNowInternal(manual = false)
            }
        }
        if (!gpsLoopBlocked) startGpsLoop()
    }

    // ---------------------------------------------------------------- GPS

    private fun startGpsLoop() {
        if (!gpsEnabled) {
            log("GPS 总开关关闭，不启动定位循环")
            return
        }
        if (gpsJob != null) return
        if (gpsLoopBlocked) {
            log("GPS 自动推送已被拒收阻断，等用户手动重试")
            return
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run {
            log("无法获取定位服务")
            return
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                rememberFix(location)
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        try {
            when (prefs.getString("location_accuracy", "balanced")) {
                "high" -> {
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 0f, listener, Looper.getMainLooper())
                    manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 0f, listener, Looper.getMainLooper())
                }
                "low" -> manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 0f, listener, Looper.getMainLooper())
                else -> {
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30_000L, 0f, listener, Looper.getMainLooper())
                    manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 120_000L, 0f, listener, Looper.getMainLooper())
                }
            }
        } catch (_: Exception) {
        }
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { rememberFix(it) }
        gpsManager = manager
        gpsListener = listener

        gpsJob = scope.launch {
            // 给相机一个稳定窗口：READY 后马上写 GEO 经常被回 128，尤其在刚配对完/刚重连时；
            // HarmoNiLink 的实测值是 READY 后至少 2.5 秒，这里再留一点余量到 3.8 秒。
            delay(3_800)
            while (_phase.value == Phase.READY) {
                if (!geoRecovering && !repairingBond && !gpsLoopBlocked) {
                    val location = lastLocation
                    if (location != null) {
                        if (System.currentTimeMillis() - lastGeoWriteAt >= 1500L) {
                            val payload = buildGeoPayload(location)
                            lastGeoWriteAt = System.currentTimeMillis()
                            writeCharacteristic(NikonBleUuids.GEO, payload)
                        }
                    } else {
                        log("还没拿到卫星定位，等待中…")
                    }
                }
                // 设置页可能改了间隔，每轮都重新读一次，下一次推送即生效
                delay(prefs.getInt("interval_min", _intervalMinutes.value).coerceAtLeast(1) * 60_000L)
            }
        }
    }

    /** 把经纬度反查成中文地名（优先「市 + 区 + 街道/地标」），失败就退回经纬度文本。 */
    private fun resolvePlaceName(latitude: Double, longitude: Double) {
        scope.launch {
            val name = try {
                val geocoder = android.location.Geocoder(context, java.util.Locale.CHINA)
                val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    kotlinx.coroutines.suspendCancellableCoroutine<List<android.location.Address>?> { cont ->
                        geocoder.getFromLocation(latitude, longitude, 1, object : android.location.Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<android.location.Address>) {
                                if (cont.isActive) cont.resumeWith(Result.success(addresses))
                            }
                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resumeWith(Result.success(null))
                            }
                        })
                    }
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(latitude, longitude, 1)
                }
                addresses?.firstOrNull()?.let { addr ->
                    listOfNotNull(
                        addr.adminArea ?: addr.locality,
                        addr.subAdminArea?.takeIf { it != addr.adminArea },
                        addr.locality?.takeIf { it != addr.adminArea },
                        addr.subLocality,
                        addr.thoroughfare ?: addr.featureName
                    ).filter { it.isNotBlank() }.distinct().joinToString("")
                }
            } catch (e: Exception) {
                null
            }
            val text = name?.takeIf { it.isNotBlank() }
                ?: String.format(java.util.Locale.US, "%.5f, %.5f", latitude, longitude)
            _lastPushPlace.value = text
            prefs.edit().putString("last_push_place", text).apply()
            log("定位地名：$text")
        }
    }

    private fun stopGpsLoop() {
        stopTimeKeepAlive()
        gpsJob?.cancel()
        gpsJob = null
        readyAt = 0L
        gattWriteBusy = false
        gattWriteStartedAt = 0L
        try {
            val manager = gpsManager
            val listener = gpsListener
            if (manager != null && listener != null) manager.removeUpdates(listener)
        } catch (_: Exception) {
        }
        gpsManager = null
        gpsListener = null
    }

    // ---------------------------------------------------------------- GATT 工具

    private fun enableIndication(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(NikonBleUuids.CCCD) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        } catch (e: Exception) {
            fail("开启 PAIR 指示失败：${e.message}")
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(NikonBleUuids.CCCD) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        } catch (e: Exception) {
            fail("开启 NOT1 通知失败：${e.message}")
        }
    }

    private fun writeCharacteristic(uuid: UUID, data: ByteArray): Boolean {
        val connection = gatt ?: return false
        val characteristic = connection.getService(NikonBleUuids.SERVICE)?.getCharacteristic(uuid) ?: return false
        val now = System.currentTimeMillis()
        // Android 的 GATT 同一时刻只能有一个写操作在途。TIME 心跳和 GEO 如果背靠背写，
        // 后一包会被底层直接丢掉，相机还可能把前一包一起回 128。这里做单写槽串行化。
        // 握手 PAIR 例外：阶段 2 的 indication 可能比阶段 1 的写回调先到，串行化会把
        // 阶段 3 直接拦掉，导致握手失败；PAIR 保持原来的直写行为。
        val isPair = uuid == NikonBleUuids.PAIR
        if (!isPair && gattWriteBusy) {
            if (now - gattWriteStartedAt < 2000L) {
                log("GATT 写槽忙（${now - gattWriteStartedAt}ms），跳过 ${shortUuid(uuid)}")
                return false
            }
            log("GATT 写槽超过 2 秒未回执，强制释放后写 ${shortUuid(uuid)}")
            gattWriteBusy = false
        }
        return try {
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                connection.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                connection.writeCharacteristic(characteristic)
            }
            if (accepted && !isPair) {
                gattWriteBusy = true
                gattWriteStartedAt = now
            }
            accepted
        } catch (e: Exception) {
            gattWriteBusy = false
            log("写入 ${shortUuid(uuid)} 异常：${e.message}")
            false
        }
    }

    /**
     * 相机息屏 / 休眠后 BLE 会被机身主动断开。这里按「6 秒 → 20 秒 → 每分钟」
     * 扫描一轮，命中已连接过的那台就自动重连；用户手动断开则立即停止。
     * 最多重试 20 次（约 20 分钟），避免相机一直关机时无休止扫描和耗电。
     */
    private fun scheduleAutoReconnect() {
        if (manualDisconnect) return
        if (autoReconnectJob?.isActive == true) return
        autoReconnectAttempts = 0
        autoReconnectJob = scope.launch {
            while (!manualDisconnect) {
                val attempt = autoReconnectAttempts
                val waitMs = when (attempt) {
                    0 -> 6_000L
                    1 -> 20_000L
                    else -> 60_000L
                }
                if (attempt >= 20) {
                    _statusDetail.value = "已停止自动重连：$lastDeviceName 长时间没有重新广播，请唤醒相机后再点「连接上次」"
                    log("自动重连达到上限，停止")
                    break
                }
                autoReconnectAttempts = attempt + 1
                _statusDetail.value =
                    "蓝牙已断开（相机可能息屏了），${waitMs / 1000} 秒后自动扫描重连（第 ${attempt + 1} 次）"
                log("自动重连：${waitMs}ms 后开始第 ${attempt + 1} 次扫描")
                delay(waitMs)
                if (manualDisconnect) break
                val phase = _phase.value
                if (phase == Phase.READY || phase == Phase.CONNECTING ||
                    phase == Phase.HANDSHAKING || phase == Phase.BONDING
                ) break
                if (wifiSessionActive) allowScanDuringWifi = true
                startScan(autoConnect = true)
                _statusDetail.value = "正在自动找回 ${lastDeviceName ?: "相机"}…"
                // 一轮扫描 12 秒；命中后 handleScanResult 会自动 connect，循环下一轮检测到 CONNECTING 就退出
                delay(12_000)
                if (_phase.value == Phase.SCANNING) stopScan()
            }
            autoReconnectJob = null
        }
    }

    private fun cancelAutoReconnect() {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        autoReconnectAttempts = 0
    }

    fun isAutoReconnecting(): Boolean = autoReconnectJob?.isActive == true

    // ---------------------------------------------------------------- 生命周期

    fun disconnect() {
        manualDisconnect = true
        cancelAutoReconnect()
        stopGpsLoop()
        stopScan()
        stopClassicDiscovery()
        timeSyncJob?.cancel()
        timeSyncJob = null
        repairingBond = false
        geoRecovering = false
        gpsLoopBlocked = false
        bondWaitJob?.cancel()
        closeGatt()
        _phase.value = Phase.IDLE
        _statusDetail.value = ""
        try {
            adapter?.cancelDiscovery()
        } catch (_: Exception) {
        }
    }

    private fun closeGatt() {
        cancelConnectTimers()
        val connection = gatt
        gatt = null
        try {
            connection?.disconnect()
            connection?.close()
        } catch (_: Exception) {
        }
    }

    fun dispose() {
        disconnect()
        bondReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
            bondReceiver = null
        }
    }

    // ---------------------------------------------------------------- 辅助

    private fun hasPermissions(): Boolean {
        val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return wanted.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun matchesName(found: String?, target: String?): Boolean {
        if (found.isNullOrBlank() || target.isNullOrBlank()) return false
        val a = found.trim().lowercase(java.util.Locale.ROOT)
        val b = target.trim().lowercase(java.util.Locale.ROOT)
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        return shorter.length >= 4 && longer.startsWith(shorter)
    }

    private fun shortUuid(uuid: UUID): String = uuid.toString().substring(4, 8)

    private fun log(message: String) {
        val current = _logs.value
        _logs.value = (current + message).takeLast(200)
        // 同时写进会话日志文件：蓝牙这条链路以前只在内存里，出问题根本取不到证据
        com.lutcamera.lutcamerapro.log.DoukLog.i("BLE", "STEP", "msg" to message)
    }

    private fun fail(message: String) {
        _phase.value = Phase.ERROR
        _statusDetail.value = message
        log("❌ $message")
        stopGpsLoop()
        // 失败后必须把 GATT/扫描/配对等待都收掉，否则下一次点连接会被旧状态挡住。
        cancelConnectTimers()
        bondWaitJob?.cancel()
        bondWaitJob = null
        stopScan()
        closeGatt()
    }
}
