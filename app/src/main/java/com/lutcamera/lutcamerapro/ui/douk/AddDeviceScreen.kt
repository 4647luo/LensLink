package com.lutcamera.lutcamerapro.ui.douk

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lutcamera.lutcamerapro.network.nikon.ble.NikonBleGpsClient

@Composable
fun AddDeviceScreen(
    state: DoukState,
    onBack: () -> Unit,
    onConnected: () -> Unit
) {
    val context = LocalContext.current
    var selectedMode by remember { mutableStateOf(state.connectMode) }
    var hint by remember { mutableStateOf("") }
    /** 相机不广播（mDNS）时，直接填相机 IP 建 PTP/IP 会话；STA 模式留空，靠搜索自动探测 */
    var manualIp by remember {
        mutableStateOf(if (state.connectMode == ConnectMode.WIFI_AP) "192.168.1.1" else "")
    }
    /** 手动填 IP 只是搜索失败时的备用手段，默认收起，避免和主流程抢注意力 */
    var manualExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(selectedMode) {
        when (selectedMode) {
            ConnectMode.WIFI_AP -> if (manualIp.isBlank()) manualIp = "192.168.1.1"
            // STA 模式的相机 IP 是路由器分配的，不能沿用 AP 的 192.168.1.1
            else -> if (manualIp == "192.168.1.1") manualIp = ""
        }
    }

    // 端口探测到相机后，自动把真实 IP 填进输入框（避免用户沿用写死的 192.168.1.1）
    val lanFoundNow = state.lanFound
    LaunchedEffect(lanFoundNow) {
        lanFoundNow.firstOrNull()?.let { manualIp = it.ip }
    }

    // USB 与蓝牙通道的实时状态
    val usbStatus by state.usb.status.collectAsStateWithLifecycle()
    val usbConnected by state.usb.connected.collectAsStateWithLifecycle()
    val blePhase by state.ble.phase.collectAsStateWithLifecycle()
    val bleDetail by state.ble.statusDetail.collectAsStateWithLifecycle()
    val bleCameras by state.ble.cameras.collectAsStateWithLifecycle()
    val blePushCount by state.ble.pushCount.collectAsStateWithLifecycle()
    val bleLocation by state.ble.locationText.collectAsStateWithLifecycle()
    val bleName by state.ble.controllerName.collectAsStateWithLifecycle()

    // 蓝牙扫描/连接前先要权限（Android 12+ 是「附近的设备」，之前是定位）
    var pendingBleAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            pendingBleAction?.invoke()
        } else {
            hint = "蓝牙或定位权限被拒绝，无法向相机推送 GPS"
        }
        pendingBleAction = null
    }

    // 连接成功后不再自动跳走：留在这里显示「已连接」和「进入控制」按钮。
    // 之前一进入本页就被弹回设备页，正是「添加设备按钮点不动」的原因。
    // （跳转由用户点上方「进入控制」触发，onConnected 仍然保留给这个按钮。）

    // 这一页是覆盖全屏的入口页，系统返回键应当回到设备页
    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = DoukSpace.page, top = DoukSpace.sm, bottom = DoukSpace.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = DoukColors.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = "添加设备",
                style = DoukType.h1,
                color = DoukColors.textPrimary,
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DoukSpace.page)
        ) {
            // 只有 PTP 会话真的成立（拿到机身型号）才显示「已连接」横幅
            val reallyConnected = state.isConnected && state.profile.model != "—" && state.profile.model.isNotBlank()
            if (reallyConnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(DoukShapes.cardSmall)
                        .doukCardSurface(DoukShapes.cardSmall, tint = DoukColors.successSoft)
                        .padding(DoukSpace.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = DoukColors.success,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(DoukSpace.xs))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "已连接 ${state.profile.model}",
                            style = DoukType.body,
                            fontWeight = FontWeight.SemiBold,
                            color = DoukColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "${state.profile.lens} · 电量 ${state.batteryText}",
                            style = DoukType.caption,
                            color = DoukColors.textSecondary
                        )
                    }
                    Button(
                        onClick = onConnected,
                        modifier = Modifier.height(36.dp),
                        shape = DoukShapes.pill,
                        colors = ButtonDefaults.buttonColors(containerColor = DoukColors.accent),
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) {
                        Text("进入控制", style = DoukType.caption, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
                Spacer(modifier = Modifier.height(DoukSpace.md))
            }

            // 机型不再让用户选：点「发现的设备」直接连，连上后以机身 PTP 上报的型号为准。
            // 这里只在真的识别到机型时显示一行结果，避免选错机型导致能力判断互相矛盾。
            val reportedModel = state.profile.model.takeIf { it != "—" && it.isNotBlank() }
            val spec = NikonModelSpec.of(reportedModel)

            if (reportedModel != null) {
                RecognizedModelCard(model = reportedModel, spec = spec)
                Spacer(modifier = Modifier.height(DoukSpace.md))
            }

            Text("选择连接方式", style = DoukType.section, color = DoukColors.textSecondary)
            Spacer(modifier = Modifier.height(10.dp))

            ConnectMode.entries.forEach { mode ->
                ModeCard(
                    mode = mode,
                    selected = selectedMode == mode,
                    unsupported = NikonModelSpec.blocked(spec, mode),
                    onClick = {
                        selectedMode = mode
                        hint = ""
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            Spacer(modifier = Modifier.height(6.dp))
            ConnectionHint(selectedMode, spec)

            // 主流程：先用搜索自动发现相机（mDNS + 局域网端口探测）
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    state.connectMode = selectedMode
                    when (selectedMode) {
                        ConnectMode.USB_PTP -> {
                            state.usb.connect()
                            hint = "正在枚举 USB 相机…"
                        }
                        ConnectMode.BLUETOOTH -> {
                            pendingBleAction = { state.ble.startScan() }
                            blePermissionLauncher.launch(blePermissions())
                            hint = "正在扫描相机蓝牙广播（服务 0xDE00）…"
                        }
                        else -> {
                            state.searchDevices(context)
                            hint = "正在扫描局域网内的尼康相机（mDNS _ptp._tcp）…"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = DoukShapes.cardSmall,
                colors = ButtonDefaults.buttonColors(containerColor = DoukColors.accent)
            ) {
                val busy = when (selectedMode) {
                    ConnectMode.USB_PTP -> false
                    ConnectMode.BLUETOOTH -> blePhase == NikonBleGpsClient.Phase.SCANNING
                    else -> state.isPairing
                }
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("正在搜索相机…", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = when (selectedMode) {
                            ConnectMode.USB_PTP -> "枚举并连接 USB 相机"
                            ConnectMode.BLUETOOTH -> "搜索蓝牙相机（GPS）"
                            else -> "搜索相机设备"
                        },
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 搜索进度与结果紧跟搜索按钮，搜完立刻能看到
            if (state.lanScanning) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = DoukColors.accent,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        "正在探测本机各网段的 15740 端口 ${state.lanScanProgress}",
                        fontSize = 12.sp,
                        color = DoukColors.textSecondary
                    )
                }
            }

            if (state.discovered.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("发现的设备", style = DoukType.section, color = DoukColors.textSecondary)
                Spacer(modifier = Modifier.height(10.dp))
                state.discovered.forEach { camera ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clip(DoukShapes.cardSmall)
                            .doukCardSurface(DoukShapes.cardSmall)
                            .clickable {
                                state.connectMode = selectedMode
                                state.connect(context, camera)
                                hint = "正在连接 ${camera.address} …"
                            }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.CameraAlt,
                            contentDescription = null,
                            tint = DoukColors.accent,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(camera.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DoukColors.textPrimary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                buildString {
                                    append(camera.mode.label)
                                    append(" · ")
                                    append(camera.address)
                                    if (camera.model.isNotBlank() && camera.model != camera.name) {
                                        append(" · ")
                                        append(camera.model)
                                    }
                                },
                                fontSize = 11.sp,
                                color = DoukColors.textSecondary
                            )
                        }
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = DoukColors.textTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (selectedMode == ConnectMode.USB_PTP) {
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(DoukShapes.cardSmall)
                        .doukCardSurface(DoukShapes.cardSmall)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Usb, contentDescription = null, tint = DoukColors.accent, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("USB PTP 通道", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DoukColors.textPrimary)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = if (usbConnected) "已连接" else "未连接",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (usbConnected) DoukColors.accent else DoukColors.textTertiary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(usbStatus, fontSize = 12.sp, color = DoukColors.textSecondary, lineHeight = 18.sp)

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            state.connectMode = ConnectMode.USB_PTP
                            state.usb.connect()
                            hint = "正在枚举 USB 相机…"
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = DoukShapes.cardSmall,
                        colors = ButtonDefaults.buttonColors(containerColor = DoukColors.accent)
                    ) {
                        Text("连接 USB 相机", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (usbConnected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { state.usb.disconnect() },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = DoukShapes.cardSmall,
                            colors = ButtonDefaults.buttonColors(containerColor = DoukColors.surfaceMuted)
                        ) {
                            Text("断开 USB", color = DoukColors.textPrimary, fontSize = 13.sp)
                        }
                    }
                }
            }

            if (selectedMode == ConnectMode.BLUETOOTH) {
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(DoukShapes.cardSmall)
                        .doukCardSurface(DoukShapes.cardSmall)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Bluetooth, contentDescription = null, tint = DoukColors.accent, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("蓝牙 GPS 推送", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DoukColors.textPrimary)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(blePhase.label, fontSize = 11.sp, color = DoukColors.textTertiary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "只推定位，不传图。相机端名称：$bleName",
                        fontSize = 12.sp,
                        color = DoukColors.textSecondary
                    )
                    if (bleDetail.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(bleDetail, fontSize = 12.sp, color = DoukColors.warning, lineHeight = 18.sp)
                    }
                    if (blePhase == NikonBleGpsClient.Phase.READY) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "已推送 $blePushCount 次${if (bleLocation.isNotBlank()) " · $bleLocation" else ""}",
                            fontSize = 12.sp,
                            color = DoukColors.accent
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row {
                        Button(
                            onClick = {
                                pendingBleAction = { state.ble.startScan() }
                                blePermissionLauncher.launch(blePermissions())
                            },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = DoukShapes.cardSmall,
                            colors = ButtonDefaults.buttonColors(containerColor = DoukColors.accent)
                        ) {
                            Text("扫描相机", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                pendingBleAction = { state.reconnectBleCamera() }
                                blePermissionLauncher.launch(blePermissions())
                            },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = DoukShapes.cardSmall,
                            colors = ButtonDefaults.buttonColors(containerColor = DoukColors.surfaceMuted)
                        ) {
                            Text("连接上次", color = DoukColors.textPrimary, fontSize = 13.sp)
                        }
                    }
                }

                if (bleCameras.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    bleCameras.forEach { camera ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                                .clip(DoukShapes.cardSmall)
                                .doukCardSurface(DoukShapes.cardSmall)
                                .clickable {
                                    pendingBleAction = { state.connectBleCamera(camera) }
                                    blePermissionLauncher.launch(blePermissions())
                                    hint = "正在连接 ${camera.name} 并握手…"
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Bluetooth, contentDescription = null, tint = DoukColors.accent, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.size(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(camera.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DoukColors.textPrimary)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("${camera.address} · RSSI ${camera.rssi}", fontSize = 11.sp, color = DoukColors.textSecondary)
                            }
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = DoukColors.textTertiary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // 备用手段：搜索不到时手动填 IP。默认收起，不跟主流程抢位置
            if (selectedMode == ConnectMode.WIFI_AP || selectedMode == ConnectMode.WIFI_STA) {
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(DoukShapes.cardSmall)
                        .clickable { manualExpanded = !manualExpanded }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "搜索不到？手动填相机 IP（备用）",
                        fontSize = 12.sp,
                        color = DoukColors.textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (manualExpanded) "收起" else "展开",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DoukColors.accent
                    )
                }
                if (manualExpanded) {
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = DoukShapes.cardSmall,
                        label = {
                            Text(
                                if (selectedMode == ConnectMode.WIFI_STA) "相机 IP（路由器分配给相机的地址）"
                                else "相机 IP（AP 直连常见 192.168.1.1）",
                                fontSize = 12.sp
                            )
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            state.connectMode = selectedMode
                            state.connectDirect(context, manualIp)
                            hint = state.lastHint.ifBlank { "正在与 $manualIp 建立 PTP/IP 会话…" }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = DoukShapes.cardSmall,
                        colors = ButtonDefaults.buttonColors(containerColor = DoukColors.surfaceMuted)
                    ) {
                        Text("用这个 IP 连接", color = DoukColors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 只在「正在连接」时回显一句进度；失败原因一律不铺在界面上（日志文件里都有）
            val liveHint = if (!state.isConnected) state.connectionStage else ""
            val shownHint = if (state.isConnecting) {
                listOf(state.lastHint, liveHint)
                    .firstOrNull { it.isNotBlank() && it != "未连接" && !it.startsWith("未连接 ") }
                    .orEmpty()
            } else ""
            if (shownHint.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    shownHint,
                    fontSize = 12.sp,
                    color = DoukColors.textSecondary,
                    lineHeight = 19.sp
                )
            }

            // 相机只给最小能力集 = 尼康的「待配对」状态。只保留标题和一个确认按钮，
            // 点一下自动完成 0x952B + 0x935A，不再要求用户核对机身验证码。
            if (state.pairingRequired) {
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(DoukShapes.cardSmall)
                        .doukCardSurface(DoukShapes.cardSmall)
                        .border(1.dp, DoukColors.accent.copy(alpha = 0.4f), DoukShapes.cardSmall)
                        .padding(14.dp)
                ) {
                    Text(
                        "相机需要匹配",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DoukColors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { state.confirmPairing(context) },
                        enabled = !state.pairingBusy,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = DoukShapes.cardSmall,
                        colors = ButtonDefaults.buttonColors(containerColor = DoukColors.accent)
                    ) {
                        Text(
                            if (state.pairingBusy) "匹配中…" else "确认匹配",
                            fontSize = 13.sp,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun ModeCard(
    mode: ConnectMode,
    selected: Boolean,
    unsupported: String? = null,
    onClick: () -> Unit
) {
    val icon: ImageVector = when (mode) {
        ConnectMode.WIFI_STA, ConnectMode.WIFI_AP -> Icons.Rounded.Wifi
        ConnectMode.USB_PTP -> Icons.Rounded.Usb
        ConnectMode.BLUETOOTH -> Icons.Rounded.Bluetooth
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DoukShapes.cardSmall)
            .doukCardSurface(
                DoukShapes.cardSmall,
                tint = if (selected) DoukColors.accentSoft else Color.Transparent
            )
            .border(
                width = 1.5.dp,
                color = if (selected) DoukColors.accent else DoukColors.divider,
                shape = DoukShapes.cardSmall
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (selected) DoukColors.accent else DoukColors.surfaceMuted),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) Color.White else DoukColors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(mode.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DoukColors.textPrimary)
            Spacer(modifier = Modifier.height(3.dp))
            Text(mode.summary, fontSize = 12.sp, color = DoukColors.textSecondary)
            if (unsupported != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "这台机型不支持：$unsupported",
                    fontSize = 11.sp,
                    color = DoukColors.accent,
                    lineHeight = 15.sp
                )
            }
        }
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (selected) DoukColors.accent else DoukColors.textTertiary, CircleShape)
                .background(if (selected) DoukColors.accent else Color.Transparent)
        )
    }
}

@Composable
private fun ConnectionHint(mode: ConnectMode, spec: NikonModelSpec.Spec?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DoukShapes.cardSmall)
            .doukCardSurface(DoukShapes.cardSmall, baseColor = DoukColors.surfaceMuted)
            .padding(14.dp)
    ) {
        Text("提示", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DoukColors.textPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        // 按「当前选中的方式 + 已识别机型」给步骤，避免选 AP 时还写着「加入手机热点」。
        Text(
            NikonModelSpec.steps(spec, mode),
            fontSize = 12.sp,
            color = DoukColors.textSecondary,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(10.dp))
        CameraGenCard(
            title = "推荐 Wi-Fi STA 的机型",
            models = NikonModelSpec.thirdNames,
            primary = "Wi-Fi STA（推荐）：相机连同一个 Wi-Fi 或手机热点 → 网络菜单 → 连接至智能设备 → Wi-Fi STA",
            secondary = "Wi-Fi AP：网络菜单 → 连接至智能设备 → Wi-Fi AP → 手机连上相机 Wi-Fi → 回应用匹配",
            highlight = spec?.gen == NikonModelSpec.Gen.THIRD
        )
        Spacer(modifier = Modifier.height(8.dp))
        CameraGenCard(
            title = "推荐 Wi-Fi AP 的机型",
            models = NikonModelSpec.secondNames,
            primary = "Wi-Fi AP（推荐）：设定菜单 → 连接至智能设备 → Wi-Fi 连接 → 手机连上相机 Wi-Fi → 回应用匹配",
            secondary = "Wi-Fi STA：相机连同一个 Wi-Fi 或手机热点 → 设定菜单 → 连接至 PC（类型选「照相机控制」）（不推荐）",
            highlight = spec?.gen == NikonModelSpec.Gen.SECOND
        )

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "后续操作：① 让相机停在上面对应的界面　② 回到本页点「搜索相机设备」　" +
                "③ 搜不到就展开「手动填相机 IP」再试　④ 连上后点「进入控制」",
            fontSize = 12.sp,
            color = DoukColors.textSecondary,
            lineHeight = 18.sp
        )
    }
}

/** 按代际分卡片列出机型与相机端菜单路径，已识别机型所在的那张加粗描边。 */
@Composable
private fun CameraGenCard(
    title: String,
    models: String,
    primary: String,
    secondary: String,
    highlight: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DoukShapes.cardSmall)
            .doukCardSurface(DoukShapes.cardSmall)
            .border(
                width = if (highlight) 1.5.dp else 1.dp,
                color = if (highlight) DoukColors.accent else DoukColors.divider,
                shape = DoukShapes.cardSmall
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DoukColors.textPrimary)
            if (highlight) {
                Spacer(modifier = Modifier.width(6.dp))
                Text("已识别机型", fontSize = 10.sp, color = DoukColors.accent)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(models, fontSize = 11.sp, color = DoukColors.textTertiary, lineHeight = 16.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(primary, fontSize = 12.sp, color = DoukColors.textPrimary, lineHeight = 18.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(secondary, fontSize = 12.sp, color = DoukColors.textSecondary, lineHeight = 18.sp)
    }
}

/** 已识别机型：只展示机身自己报上来的型号，不提供修改入口。 */
@Composable
private fun RecognizedModelCard(model: String, spec: NikonModelSpec.Spec?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DoukShapes.cardSmall)
            .doukCardSurface(DoukShapes.cardSmall)
            .border(1.dp, DoukColors.divider, DoukShapes.cardSmall)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.CameraAlt,
                contentDescription = null,
                tint = DoukColors.accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "尼康 $model",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = DoukColors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(text = "机身上报", fontSize = 11.sp, color = DoukColors.textTertiary)
        }
        if (spec != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${NikonModelSpec.summary(spec)}　${spec.note}",
                fontSize = 11.sp,
                color = DoukColors.textSecondary,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun Bullet() {
    Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(DoukColors.accent))
}

/** 蓝牙 GPS 需要的运行时权限。 */
private fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}
