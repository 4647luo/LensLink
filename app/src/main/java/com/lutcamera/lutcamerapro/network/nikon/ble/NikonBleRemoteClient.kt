package com.lutcamera.lutcamerapro.network.nikon.ble

import android.Manifest
import android.annotation.SuppressLint
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
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 尼康 ML‑L7 蓝牙遥控（Remote 模式）。
 *
 * 与 [NikonBleGpsClient] 的「智能设备」模式是两套配对：
 *  - 智能设备：PAIR 0x2000 + Blowfish 挑战，用来写 GEO/TIME；
 *  - 遥控器：PAIR 0x2087 + 固定 4 阶段握手，用来写快门 0x2083。
 *
 * 协议事实来自公开逆向项目 gkoh/furble（MIT）的 NikonRemote 实现：
 *  - stage1 = 01 + 8 字节 00..01 + id 全 0
 *  - stage2 回 02 + 16 字节 0；stage3 回 03 + 16 字节 0
 *  - stage4 带回相机序列号，之后可写快门
 *  - 快门载荷 [0x02,0x02] 按下 / [0x02,0x00] 松开
 *  - 录像用 mode 0x03；公开实现只验证了快门，录像是否生效取决于机型固件
 *
 * 本机实现只做独立重写，不复制其源码。
 */
@SuppressLint("MissingPermission")
class NikonBleRemoteClient(private val context: Context) {

    enum class Phase(val label: String) {
        IDLE("未连接"),
        SCANNING("正在搜索蓝牙相机…"),
        CONNECTING("正在连接…"),
        PAIRING("正在配对遥控器…"),
        READY("已连接，可遥控快门"),
        ERROR("连接失败")
    }

    data class RemoteCamera(
        val name: String,
        val address: String,
        val rssi: Int
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase = _phase.asStateFlow()

    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()

    private val _cameras = MutableStateFlow<List<RemoteCamera>>(emptyList())
    val cameras = _cameras.asStateFlow()

    private val _lastAction = MutableStateFlow("")
    val lastAction = _lastAction.asStateFlow()

    val isReady: Boolean get() = _phase.value == Phase.READY

    private var gatt: BluetoothGatt? = null
    // 断链自动重连：记住用户最后选的那台相机，掉线后按 1/2/4/8 秒退避重试，
    // 最多 4 次；重新接上（配对到阶段 4）就把计数清零，避免偶发掉线就要手动重连。
    private var lastCamera: RemoteCamera? = null
    private var keepAlive = false
    private var retryCount = 0
    private var retryJob: Job? = null
    private var scanCallback: ScanCallback? = null
    private var scanTimeout: Job? = null
    private var pendingSubscriptions = 0
    private var started = false
    private var shutterBusy = false
    /**
     * 遥控配对 ID：8 字节 = device(uint32) + nonce(uint32)，按小端发送。
     * 公开实现 gkoh/furble 的 device 首字节固定 0x01，其余随机；
     * 不能用全 0，否则相机侧会把它当成无效设备。
     */
    private val remoteId = ByteArray(8).also {
        it[0] = 0x01
        for (i in 1 until it.size) it[i] = kotlin.random.Random.nextInt(1, 256).toByte()
    }

    private fun hasPermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun startScan(timeoutMs: Long = 8000L) {
        if (!hasPermission()) {
            fail("缺少蓝牙或定位权限")
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            fail("系统蓝牙未打开")
            return
        }
        stopScan()
        _cameras.value = emptyList()
        _phase.value = Phase.SCANNING
        _status.value = "让相机进入「连接至智能设备 → 配对」或「蓝牙遥控」界面，再点扫描"
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // 遥控配对时相机只广播服务 UUID、没有厂商数据；带厂商数据的是
                // 「智能设备」模式，连上去也拿不到 0x2087，直接排除，避免误导。
                if ((result.scanRecord?.manufacturerSpecificData?.size() ?: 0) > 0) return
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                val camera = RemoteCamera(name.ifBlank { "尼康相机" }, result.device.address, result.rssi)
                val old = _cameras.value
                if (old.none { it.address == camera.address }) {
                    _cameras.value = (old + camera).sortedByDescending { it.rssi }.take(6)
                }
            }
        }
        scanCallback = callback
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(NikonBleUuids.SERVICE))
            .build()
        adapter.bluetoothLeScanner?.startScan(
            listOf(filter),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback
        )
        scanTimeout = scope.launch {
            delay(timeoutMs)
            stopScan()
            if (_phase.value == Phase.SCANNING) {
                if (_cameras.value.isEmpty()) {
                    fail("没搜到相机蓝牙：先把相机切到「连接至智能设备 → 配对」，屏幕出现等待连接再试")
                } else {
                    _phase.value = Phase.IDLE
                    _status.value = "找到 ${_cameras.value.size} 台，点相机名连接遥控"
                }
            }
        }
    }

    fun stopScan() {
        scanTimeout?.cancel()
        scanTimeout = null
        scanCallback?.let {
            runCatching {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(it)
            }
        }
        scanCallback = null
    }

    fun connect(camera: RemoteCamera) {
        if (!hasPermission()) {
            fail("缺少蓝牙或定位权限")
            return
        }
        lastCamera = camera
        keepAlive = true
        retryCount = 0
        retryJob?.cancel()
        retryJob = null
        connectInternal(camera)
    }

    /** 真正发起 GATT 连接；重连也走这里，不动 keepAlive 意图。 */
    private fun connectInternal(camera: RemoteCamera) {
        stopScan()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        pendingSubscriptions = 0
        started = false
        shutterBusy = false
        _phase.value = Phase.CONNECTING
        _status.value = if (retryCount > 0) "正在重连 ${camera.name}（第 $retryCount 次）"
        else "正在连接 ${camera.name}"
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val device = adapter?.getRemoteDevice(camera.address) ?: run {
            fail("找不到这台蓝牙设备")
            return
        }
        gatt = runCatching {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) {
            fail("GATT 连接启动失败")
            return
        }
        scope.launch {
            delay(9000)
            if (_phase.value == Phase.CONNECTING) {
                // 连接超时也走重连退避，不要直接判死
                if (keepAlive && lastCamera != null && retryCount < 4) {
                    runCatching { gatt?.close() }
                    gatt = null
                    scheduleReconnect("连接超时")
                } else {
                    fail("连接超时，请让相机保持在配对界面")
                }
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (retryCount >= 4) {
            fail("蓝牙多次重连失败，请重新扫描")
            return
        }
        retryCount += 1
        val waitMs = (1000L shl (retryCount - 1)).coerceAtMost(8000L)
        _phase.value = Phase.CONNECTING
        _status.value = "$reason，${waitMs / 1000}s 后第 $retryCount 次重试"
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(waitMs)
            val camera = lastCamera ?: return@launch
            if (keepAlive) connectInternal(camera)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _phase.value = Phase.CONNECTING
                _status.value = "GATT 已连接，正在发现服务"
                runCatching { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasActive = _phase.value == Phase.READY || _phase.value == Phase.PAIRING ||
                    _phase.value == Phase.CONNECTING
                runCatching { g.close() }
                if (g === gatt) gatt = null
                started = false
                pendingSubscriptions = 0
                shutterBusy = false
                when {
                    wasActive && keepAlive && lastCamera != null ->
                        scheduleReconnect(if (status == 133) "蓝牙状态 133" else "蓝牙断开")
                    wasActive -> fail("蓝牙连接已断开")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("服务发现失败 status=$status")
                return
            }
            val service = g.getService(NikonBleUuids.SERVICE)
            val pair = service?.getCharacteristic(NikonBleUuids.PAIR_REMOTE)
            val shutter = service?.getCharacteristic(NikonBleUuids.REMOTE_SHUTTER)
            if (service == null || pair == null || shutter == null) {
                fail("这台相机没有暴露 ML‑L7 遥控特征；可改用 Wi‑Fi 控制页的快门")
                return
            }
            _status.value = "服务已就绪，开始遥控配对"
            _phase.value = Phase.PAIRING
            pendingSubscriptions = 0
            started = false
            enableIndication(g, pair) { pendingSubscriptions++ }
            val ind1 = service.getCharacteristic(NikonBleUuids.REMOTE_IND1)
            if (ind1 != null) {
                enableIndication(g, ind1) { pendingSubscriptions++ }
            } else {
                // 个别固件没有 IND1，也允许继续
                scope.launch { delay(300); maybeStart() }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("订阅遥控特征失败 status=$status")
                return
            }
            pendingSubscriptions = (pendingSubscriptions - 1).coerceAtLeast(0)
            maybeStart()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (g !== gatt) return
            if (characteristic.uuid == NikonBleUuids.PAIR_REMOTE) handlePair(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (g !== gatt) return
            if (characteristic.uuid == NikonBleUuids.PAIR_REMOTE) {
                handlePair(characteristic.value ?: ByteArray(0))
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (g !== gatt) return
            shutterBusy = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _lastAction.value = "写入失败 status=$status"
            }
        }
    }

    private fun maybeStart() {
        if (started || pendingSubscriptions > 0) return
        started = true
        scope.launch {
            delay(220)
            sendStage1()
        }
    }

    private fun sendStage1() {
        // 17 字节：stage=1；timestamp 小端 0x0100000000000000；id 为 0x01 开头的随机 8 字节
        val stage1 = ByteArray(17).apply {
            this[0] = 0x01
            this[8] = 0x01
            remoteId.copyInto(this, destinationOffset = 9)
        }
        if (write(NikonBleUuids.PAIR_REMOTE, stage1)) {
            _status.value = "已发送遥控配对请求，等待相机回应"
        } else {
            fail("遥控配对写入失败")
        }
    }

    private fun handlePair(value: ByteArray) {
        if (value.size < 17) return
        when (value[0].toInt() and 0xFF) {
            2 -> {
                val stage3 = ByteArray(17).apply { this[0] = 0x03 }
                if (!write(NikonBleUuids.PAIR_REMOTE, stage3)) {
                    fail("遥控配对第 3 阶段写入失败")
                }
            }
            4 -> {
                val serial = String(value.copyOfRange(9, 17), Charsets.US_ASCII).trim()
                // 配对成功：清零重连计数，之后偶发掉线可以重新从 1 秒开始退避
                retryCount = 0
                _phase.value = Phase.READY
                _status.value = if (serial.isBlank()) "遥控已就绪" else "遥控已就绪 · 相机 $serial"
            }
            else -> _status.value = "配对阶段 ${value[0]}，等待下一步"
        }
    }

    fun disconnect() {
        // 用户主动断开：停掉重连意图，别在后台反复连回去
        keepAlive = false
        retryJob?.cancel()
        retryJob = null
        retryCount = 0
        lastCamera = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        started = false
        pendingSubscriptions = 0
        shutterBusy = false
        if (_phase.value == Phase.READY || _phase.value == Phase.PAIRING ||
            _phase.value == Phase.CONNECTING || _phase.value == Phase.ERROR
        ) {
            _phase.value = Phase.IDLE
            _status.value = ""
        }
    }

    /** 按住快门：按下；抬起时由 UI 调 [releaseShutter]。 */
    fun pressShutter(): Boolean {
        val ok = write(NikonBleUuids.REMOTE_SHUTTER, byteArrayOf(0x02, 0x02))
        if (ok) _lastAction.value = "快门按下"
        return ok
    }

    fun releaseShutter(): Boolean {
        val ok = write(NikonBleUuids.REMOTE_SHUTTER, byteArrayOf(0x02, 0x00))
        if (ok) _lastAction.value = "快门松开"
        return ok
    }

    /**
     * 录像开始/停止：ML‑L7 用 mode 0x03 的 press/release。
     * 是否真的切换取决于机型；相机需先在视频模式。
     */
    fun toggleVideo() {
        if (write(NikonBleUuids.REMOTE_SHUTTER, byteArrayOf(0x03, 0x02))) {
            _lastAction.value = "已发送录像切换"
            scope.launch {
                delay(180)
                write(NikonBleUuids.REMOTE_SHUTTER, byteArrayOf(0x03, 0x00))
            }
        }
    }

    private fun enableIndication(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        onDone: () -> Unit
    ) {
        g.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(NikonBleUuids.CCCD) ?: run {
            onDone(); return
        }
        val enable = if (
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        ) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, enable)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = enable
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
    }

    private fun write(uuid: UUID, data: ByteArray): Boolean {
        val g = gatt ?: return false
        val characteristic = g.getService(NikonBleUuids.SERVICE)?.getCharacteristic(uuid)
            ?: return false
        if (shutterBusy && uuid == NikonBleUuids.REMOTE_SHUTTER &&
            data.size >= 2 && data[1] == 0x02.toByte()
        ) {
            // 只拦「按下」的重复请求；抬起必须无条件发出，否则快速点击会卡在按住状态
            return false
        }
        if (uuid == NikonBleUuids.REMOTE_SHUTTER &&
            data.size >= 2 && data[1] == 0x02.toByte()
        ) {
            shutterBusy = true
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(
                    characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(characteristic)
            }
        }.getOrElse {
            shutterBusy = false
            false
        }
    }

    private fun fail(message: String) {
        _phase.value = Phase.ERROR
        _status.value = message
        runCatching { gatt?.close() }
        gatt = null
        started = false
        shutterBusy = false
    }
}
