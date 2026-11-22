package com.lutcamera.lutcamerapro.ui.douk

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.lutcamera.lutcamerapro.network.CameraLanProbe
import com.lutcamera.lutcamerapro.network.nikon.ble.NikonBleGpsClient
import com.lutcamera.lutcamerapro.network.nikon.ble.NikonBleRemoteClient

@Composable
fun DeviceScreen(
    state: DoukState,
    onAddDevice: () -> Unit,
    onOpenControl: () -> Unit,
    // 当前显示哪一组服务卡片：null = 初始设备页，"wifi" = Wi‑Fi|USB 页，"bluetooth" = 蓝牙页。
    // 它和切换条是否展开是两件事：收起切换条后，页面仍停留在用户刚选的那一页。
    serviceMode: String? = null,
    // 底部「蓝牙 / Wi‑Fi|USB」切换条是否正在显示，只决定要不要给浮层让出 72dp。
    serviceBarVisible: Boolean = false
) {
    var showManual by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 设备页的相机实物照片只做展示：要换图请去「设置 → 通用 → 设备页相机照片」，
    // 这里读的是同一个文件，设置页换完回来就跟着变。
    // 解码必须离开主线程：原图动辄 4000×3000，在主线程 decode 会掉帧甚至 ANR。
    val cameraPhoto by produceState<ImageBitmap?>(initialValue = null, state.photoVersion) {
        value = withContext(Dispatchers.IO) { decodeHeroPhoto(DoukPrefs.cameraPhotoPath) }
    }

    // ===== 下拉刷新：在设备页顶部往下拽一把就重新搜索相机 =====
    // 注意：Android 的 overscroll 会被 scrollable 自己吃掉，nestedScroll 收不到位移，
    // 所以这里在 Initial pass 上抢手势，只处理「列表已在顶部 + 向下拖」这一种。
    val scrollState = rememberScrollState()
    val pullThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    var pull by remember { mutableStateOf(0f) }
    var refreshing by remember { mutableStateOf(false) }
    var searchStartedAt by remember { mutableStateOf(0L) }
    var searchSeconds by remember { mutableStateOf(0) }

    // 搜索结束就把加载条收起来；自动连接随后走自己的连接进度，不再让「正在搜索」一直挂着
    LaunchedEffect(refreshing, state.lanScanning) {
        if (refreshing && !state.lanScanning) {
            refreshing = false
            pull = 0f
        }
    }

    // 搜索计时：界面上显示已经搜了多少秒，搜不到就回到原样
    LaunchedEffect(refreshing) {
        if (!refreshing) return@LaunchedEffect
        searchStartedAt = System.currentTimeMillis()
        searchSeconds = 0
        while (refreshing) {
            delay(1000)
            searchSeconds = ((System.currentTimeMillis() - searchStartedAt) / 1000).toInt()
        }
    }

    if (state.lockedImportPrompt) {
        val onlyNew = DoukPrefs.lockedNewOnly
        AlertDialog(
            onDismissRequest = { state.dismissLockedImport() },
            title = {
                Text(if (onlyNew) "导入连接后新增的锁定照片？" else "导入相机里所有锁定照片？")
            },
            text = {
                Text(
                    if (onlyNew) "只导入本次连接相机之后新拍/新锁定的文件，已经锁定过的旧文件不会重复导入。"
                    else "导入相机里所有标了保护/锁定的文件，逐张保存到手机，耗时取决于数量。"
                )
            },
            confirmButton = {
                TextButton(onClick = { state.confirmLockedImport() }) {
                    Text("开始导入", color = DoukColors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { state.dismissLockedImport() }) {
                    Text("暂不", color = DoukColors.textSecondary)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(state.isConnected, refreshing) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var dragging = false
                    val startY = down.position.y
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dy = change.position.y - startY
                        if (!dragging) {
                            // 只有内容已经滚到顶部、手指还在往下走，才算下拉刷新
                            if (dy > 30f && scrollState.value == 0) dragging = true
                        } else {
                            change.consume()
                            pull = (dy * 0.55f).coerceAtLeast(0f)
                        }
                    }
                    if (dragging) {
                        if (!refreshing && pull >= pullThreshold) {
                            refreshing = true
                            pull = pullThreshold
                            if (state.isConnected) state.disconnect() else state.searchDevices(context)
                        } else {
                            pull = 0f
                        }
                    }
                }
            }
    ) {
        DeviceTopBar(
            onAdd = onAddDevice,
            onRefresh = {
                if (state.isConnected) state.disconnect() else state.searchDevices(context)
            },
            onManual = { showManual = true }
        )

        // pull 用 lambda 传给指示条：拖拽时只重组顶部这一小块，
        // 不再让整页（含照片、卡片、蓝牙列表）跟着每一个手指位移重组。
        PullIndicator(
            pullProvider = { pull },
            threshold = pullThreshold,
            refreshing = refreshing,
            secondsProvider = { searchSeconds }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                // 设备页切换条是浮层；显示时把滚动视口底部让出 72dp，
                // 否则最后一张蓝牙/存储卡会被「蓝牙 | Wi‑Fi|USB」压住。
                // 收起切换条后只收回这 72dp，不改变当前显示的服务页。
                .padding(bottom = if (serviceBarVisible) 72.dp else 0.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = DoukSpace.page)
        ) {
            Spacer(modifier = Modifier.height(DoukSpace.xxs))
            if (state.isConnected) {
                ConnectedStatus(state)
                Spacer(modifier = Modifier.height(DoukSpace.md))
                PhotoHero(photo = cameraPhoto, connected = true, modelName = state.profile.name)
                Spacer(modifier = Modifier.height(DoukSpace.lg))
                when (serviceMode) {
                    "bluetooth" -> {
                        BleCards(state, context)
                    }
                    else -> {
                        CameraInfoGrid(state)
                        Spacer(modifier = Modifier.height(DoukSpace.xl))
                        PrimaryButton(
                            text = "进入控制面板",
                            icon = Icons.Rounded.Tune,
                            onClick = onOpenControl
                        )
                        Spacer(modifier = Modifier.height(DoukSpace.xs))
                        // 已连接时也要能换/再加一台相机，否则「添加设备」在连接后形同消失
                        TonalButton(text = "添加设备", onClick = onAddDevice)
                        Spacer(modifier = Modifier.height(DoukSpace.xs))
                        TonalButton(text = "断开连接", onClick = { state.disconnect() })
                    }
                }
            } else {
                PhotoHero(photo = cameraPhoto, connected = false, modelName = "")
                if (state.showAddDeviceButton) {
                    Spacer(modifier = Modifier.height(DoukSpace.lg))
                    PrimaryButton(text = "添加设备", icon = Icons.Rounded.Add, onClick = onAddDevice)
                }
                // 之前这里挂了一个「模拟相机（测试监看）」的入口，正式版不需要，去掉

                // 连接过的设备：只有这次搜索真的搜到了这台（IP 对上）才列出来；
                // 搜不到就什么都不显示，等于回到原本的界面
                val searching = refreshing || state.lanScanning
                val knownDevices = if (searching || state.lanFound.isNotEmpty()) {
                    DoukPrefs.knownDevices().filter { known ->
                        state.lanFound.any { it.ip == known.ip }
                    }
                } else emptyList()
                if (knownDevices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(DoukSpace.lg))
                    SectionLabel("连接过的设备")
                    Spacer(modifier = Modifier.height(DoukSpace.sm))
                    knownDevices.forEach { device ->
                        DeviceRow(
                            title = device.model.ifBlank { "尼康相机" },
                            subtitle = device.ip,
                            onClick = { state.connectDirect(context, device.ip) }
                        )
                        Spacer(modifier = Modifier.height(DoukSpace.xs))
                    }
                }

                // 搜索到的设备：下拉刷新 / 点搜索跑出来的结果就地列出来，点一下即连
                if (state.lanFound.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(DoukSpace.lg))
                    SectionLabel("搜索到的设备")
                    Spacer(modifier = Modifier.height(DoukSpace.sm))
                    state.lanFound.forEach { hit ->
                        DeviceRow(
                            title = "尼康相机",
                            subtitle = "${hit.ip}:${CameraLanProbe.PTP_PORT}",
                            onClick = { state.connectDirect(context, hit.ip) }
                        )
                        Spacer(modifier = Modifier.height(DoukSpace.xs))
                    }
                }

                Spacer(modifier = Modifier.height(DoukSpace.lg))
                if (serviceMode == "bluetooth") {
                    BleCards(state, context)
                }
            }
            Spacer(modifier = Modifier.height(DoukSpace.xxl + DoukSpace.lg))
            // 悬浮 Douk 栏 + 设备页切换条浮在内容上方，末尾留出两层高度，
            // 保证最后一张卡/按钮不会被压住
            Spacer(modifier = Modifier.height(128.dp))
        }
    }

    if (showManual) {
        AlertDialog(
            onDismissRequest = { showManual = false },
            shape = DoukShapes.sheet,
            containerColor = DoukColors.surface,
            confirmButton = {
                TextButton(onClick = { showManual = false }) {
                    Text("知道了", style = DoukType.title, color = DoukColors.accent)
                }
            },
            title = { Text("使用手册", style = DoukType.h2, color = DoukColors.textPrimary) },
            text = {
                Column {
                    ManualLine("1. 相机与手机先建立连接：Wi-Fi AP 直连、Wi-Fi STA 或 USB PTP 三选一。")
                    ManualLine("2. 相机菜单 → 连接至智能设备（Wi-Fi）；二代机可选「连接至计算机」走 USB PTP。")
                    ManualLine("3. 回到设备页点「添加设备」，选择连接方式后搜索并配对。")
                    ManualLine("4. 连接成功后进入控制页监看实时取景、调整曝光与对焦参数。")
                    ManualLine("5. 蓝牙可做 GPS 推送或 ML‑L7 快门/录像遥控，两者共用一条链路；蓝牙不承担图传。")
                }
            }
        )
    }
}

@Composable
private fun DeviceTopBar(onAdd: () -> Unit, onRefresh: () -> Unit, onManual: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DoukSpace.page, end = DoukSpace.xs, top = DoukSpace.sm, bottom = DoukSpace.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "设备",
            style = DoukType.h1,
            color = DoukColors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        TopBarAction(Icons.Rounded.Add, "添加设备", onAdd)
        TopBarAction(Icons.Rounded.Refresh, "刷新搜索", onRefresh)
        TopBarAction(Icons.Rounded.MenuBook, "使用手册", onManual)
    }
}

@Composable
private fun TopBarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    // 用 Box 自己定 44dp 命中区：IconButton 在部分机型会被最小尺寸压到图标大小，
    // 指头点不准。这里明确给 44dp 触达圈。
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = DoukColors.textPrimary, modifier = Modifier.size(20.dp))
    }
}

/** 下拉刷新的胶囊指示：随位移展开，达到阈值后提示松手 */
@Composable
private fun PullIndicator(
    pullProvider: () -> Float,
    threshold: Float,
    refreshing: Boolean,
    secondsProvider: () -> Int
) {
    val pull = pullProvider()
    if (!refreshing && pull <= 0f) return
    val seconds = secondsProvider()
    val density = LocalDensity.current
    val height = if (refreshing) 40.dp else with(density) { pull.coerceAtMost(threshold).toDp() }
    Box(modifier = Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .clip(DoukShapes.pill)
                .doukCardSurface(DoukShapes.pill, baseColor = DoukColors.surfaceMuted)
                .padding(horizontal = DoukSpace.sm, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = DoukColors.accent,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(DoukSpace.xs))
                Text(
                    if (seconds > 0) "正在搜索相机… ${seconds}s" else "正在搜索相机…",
                    style = DoukType.caption,
                    color = DoukColors.textSecondary
                )
            } else {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = DoukColors.accent,
                    modifier = Modifier
                        .size(13.dp)
                        .rotate(180f * (pull / threshold).coerceIn(0f, 1f))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (pull >= threshold) "松开搜索相机" else "下拉搜索相机",
                    style = DoukType.caption,
                    color = DoukColors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun ConnectedStatus(state: DoukState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(DoukColors.success)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            "已连接 · ${state.connectMode.label}",
            style = DoukType.caption,
            color = DoukColors.success
        )
    }
}

/** 相机实物照片区：空态只放图与图标，已连接时叠型号名与状态胶囊 */
@Composable
private fun PhotoHero(photo: ImageBitmap?, connected: Boolean, modelName: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.55f)
    ) {
        if (photo != null) {
            // 透明底机身 PNG 直接铺满，不加圆角裁切、阴影和黑色渐变，
            // 避免图片周围出现一圈看得见的「画框」。
            Image(
                bitmap = photo,
                contentDescription = "相机照片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(DoukShapes.card)
                    .background(DoukColors.surfaceMuted)
            ) {
                // 没设照片时的占位：一圈很淡的主色光晕 + 相机图标，不写字
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(124.dp)
                            .clip(CircleShape)
                            .background(DoukColors.accentContainer)
                    )
                    Icon(
                        Icons.Rounded.CameraAlt,
                        contentDescription = null,
                        tint = DoukColors.accent.copy(alpha = 0.62f),
                        modifier = Modifier.size(52.dp)
                    )
                }
            }
        }

        // 图片只放机身 PNG，不再叠加「在线」和相机型号，避免透明底图片周围出现遮挡

    }
}

/** 连接失败 / 进行中的原因：放在照片下方，图面保持干净 */
@Composable
private fun ConnectionHint(state: DoukState) {
    val merged = listOf(state.lastHint, state.connectionStage)
        .filter { it.isNotBlank() && it != "未连接" && !it.startsWith("未连接 ") }
    if (merged.isEmpty()) return
    Spacer(modifier = Modifier.height(DoukSpace.sm))
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(if (state.isConnecting) DoukColors.textTertiary else DoukColors.warning)
        )
        Spacer(modifier = Modifier.width(DoukSpace.xs))
        Text(
            text = merged.joinToString("\n"),
            style = DoukType.caption,
            color = if (state.isConnecting) DoukColors.textSecondary else DoukColors.warning
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = DoukType.section,
        color = DoukColors.textSecondary
    )
}

/** 设备行：左侧相机图标 + 名称/地址，点整行就连这台 */
@Composable
private fun DeviceRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DoukShapes.cardSmall)
            .doukCardSurface(DoukShapes.cardSmall)
            .clickable(onClick = onClick)
            .padding(horizontal = DoukSpace.md, vertical = DoukSpace.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(DoukColors.accentSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.CameraAlt,
                contentDescription = null,
                tint = DoukColors.accent,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(DoukSpace.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = DoukType.title, color = DoukColors.textPrimary)
            Text(subtitle, style = DoukType.label, color = DoukColors.textTertiary)
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = DoukColors.textTertiary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun WifiPanel(state: DoukState, onOpenWizard: () -> Unit) {
    val context = LocalContext.current
    val wifi = state.isConnected && state.connectMode != ConnectMode.USB_PTP
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DoukShapes.card)
            .doukCardSurface(DoukShapes.card)
            .padding(DoukSpace.md)
    ) {
        Text("Wi‑Fi 连接", style = DoukType.title, color = DoukColors.textPrimary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            if (wifi) "已连接 · ${state.connectMode.label}" else "未连接",
            style = DoukType.caption,
            color = if (wifi) DoukColors.accent else DoukColors.textSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "相机端选「连接至智能设备」：可让相机加入手机热点，或让手机连相机热点；两种方式都由 PTP/IP 15740 端口控制。",
            style = DoukType.label,
            color = DoukColors.textTertiary
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(DoukSpace.xs)) {
            ServiceAction("连接向导") { onOpenWizard() }
            ServiceAction("搜索设备") { state.searchDevices(context) }
            if (wifi) ServiceAction("断开连接") { state.disconnect() }
        }
    }
}

@Composable
private fun UsbPanel(state: DoukState, onOpenWizard: () -> Unit) {
    val usb = state.usbConnected
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DoukShapes.card)
            .doukCardSurface(DoukShapes.card)
            .padding(DoukSpace.md)
    ) {
        Text("USB PTP 连接", style = DoukType.title, color = DoukColors.textPrimary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            if (usb) "已连接 · 数据线直连" else "未连接",
            style = DoukType.caption,
            color = if (usb) DoukColors.accent else DoukColors.textSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "相机「设定菜单 → USB 数据连接」选「连接到计算机（照相机控制）」，机身显示「连接至计算机」后插 OTG 数据线。",
            style = DoukType.label,
            color = DoukColors.textTertiary
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(DoukSpace.xs)) {
            ServiceAction("连接向导") { onOpenWizard() }
            if (usb) ServiceAction("重新检测") { state.refreshUsbBody() }
        }
    }
}

@Composable
private fun ServiceAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(DoukShapePill)
            .background(DoukColors.accentContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, style = DoukType.label, color = DoukColors.accent)
    }
}

/**
 * 设备页蓝牙区：两张卡片分别受设置-蓝牙里的显示开关控制。
 * 都关掉时留一个占位提示，避免用户以为界面坏了。
 */
@Composable
private fun BleCards(state: DoukState, context: android.content.Context) {
    val showGps = state.showBleGpsCard
    val showRemote = state.showBleRemoteCard
    if (!showGps && !showRemote) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .doukCardSurface(DoukShapes.cardSmall)
                .padding(16.dp)
        ) {
            Text(
                "蓝牙 GPS 和蓝牙遥控卡片都已隐藏。可在「设置 → 蓝牙」里重新打开。",
                style = DoukType.label,
                color = DoukColors.textSecondary
            )
        }
        return
    }
    if (showGps) {
        BleGpsCard(state, context)
        if (showRemote) Spacer(modifier = Modifier.height(DoukSpace.md))
    }
    if (showRemote) {
        BleRemoteCard(state, context)
    }
}

/**
 * 蓝牙遥控：尼康 ML‑L7 Remote 协议只公开了快门与录像模式，
 * 不做「变焦/菜单」这类协议里没有的承诺。
 */
@Composable
private fun BleRemoteCard(state: DoukState, context: android.content.Context) {
    val phase by state.bleRemote.phase.collectAsState()
    val status by state.bleRemote.status.collectAsState()
    val cameras by state.bleRemote.cameras.collectAsState()
    val ready = phase == NikonBleRemoteClient.Phase.READY

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) pending?.invoke()
        else state.lastHint = "蓝牙或定位权限被拒绝，无法使用蓝牙遥控"
        pending = null
    }
    fun withPermission(action: () -> Unit) {
        val granted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) action() else {
            pending = action
            launcher.launch(permissions)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DoukShapes.card)
            .doukCardSurface(DoukShapes.card)
            .padding(DoukSpace.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(DoukShapes.icon)
                    .background(DoukColors.accentContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Bluetooth,
                    contentDescription = null,
                    tint = DoukColors.accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(DoukSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text("蓝牙遥控", style = DoukType.title, color = DoukColors.textPrimary)
                Text(
                    phase.label,
                    style = DoukType.caption,
                    color = if (ready) DoukColors.accent else DoukColors.textSecondary
                )
                if (status.isNotBlank()) {
                    Text(status, style = DoukType.label, color = DoukColors.textTertiary)
                }
            }
        }

        if (!ready) {
            Spacer(modifier = Modifier.height(DoukSpace.sm))
            Text(
                "先把相机切到「连接至智能设备 → 配对」或菜单里的「蓝牙遥控 / ML‑L7 配对」，屏幕出现等待连接后再扫描。",
                style = DoukType.label,
                color = DoukColors.textTertiary
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (cameras.isEmpty()) {
                ServiceAction("扫描蓝牙相机") {
                    withPermission {
                        // 同一条蓝牙链路不能同时扮演 GPS 与遥控，先断开 GPS 连接
                        if (state.ble.phase.value == NikonBleGpsClient.Phase.READY) state.ble.disconnect()
                        state.bleRemote.startScan()
                    }
                }
            } else {
                cameras.forEach { camera ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(DoukShapes.cardSmall)
                            .doukCardSurface(DoukShapes.cardSmall, baseColor = DoukColors.surfaceMuted)
                            .clickable {
                                withPermission {
                                    if (state.ble.phase.value == NikonBleGpsClient.Phase.READY) state.ble.disconnect()
                                    state.bleRemote.connect(camera)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            camera.name,
                            style = DoukType.title,
                            color = DoukColors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text("连接遥控", style = DoukType.label, color = DoukColors.accent)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        } else {
            Spacer(modifier = Modifier.height(DoukSpace.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(DoukSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clip(DoukShapes.cardSmall)
                        .background(DoukColors.accent)
                        .pointerInput(ready) {
                            awaitEachGesture {
                                awaitFirstDown()
                                state.bleRemote.pressShutter()
                                waitForUpOrCancellation()
                                state.bleRemote.releaseShutter()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("按住快门", style = DoukType.title, color = Color.White)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clip(DoukShapes.cardSmall)
                        .background(DoukColors.surfaceMuted)
                        .clickable { state.bleRemote.toggleVideo() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("录像开始 / 停止", style = DoukType.title, color = DoukColors.textPrimary)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "协议公开能力：快门、录像切换；对焦/变焦未公开，不做假按钮。",
                    style = DoukType.label,
                    color = DoukColors.textTertiary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "断开",
                    style = DoukType.label,
                    color = DoukColors.accent,
                    modifier = Modifier.clickable { state.bleRemote.disconnect() }
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "蓝牙遥控与蓝牙 GPS 共用同一条链路，同一时间只能选一种；切回 GPS 请在下方重新连接。",
            style = DoukType.label,
            color = DoukColors.textTertiary
        )
    }
}

@Composable
/** 蓝牙 GPS 卡片：连接状态 + 推送间隔 + 最近一次推送的时间与中文地名 */
private fun BleGpsCard(state: DoukState, context: android.content.Context) {
    val phase by state.ble.phase.collectAsState()
    val detail by state.ble.statusDetail.collectAsState()
    val lastPushAt by state.ble.lastPushAt.collectAsState()
    val place by state.ble.lastPushPlace.collectAsState()
    val lastLat by state.ble.lastPushLat.collectAsState()
    val lastLon by state.ble.lastPushLon.collectAsState()
    val cameras by state.ble.cameras.collectAsState()
    val pushCount by state.ble.pushCount.collectAsState()
    val locationText by state.ble.locationText.collectAsState()
    val intervalMinutes by state.ble.intervalMinutes.collectAsState()
    val gpsEnabled by state.ble.gpsEnabledFlow.collectAsState()
    val accuracyMode by state.ble.locationAccuracy.collectAsState()
    val syncTime by state.ble.syncTime.collectAsState()
    val connectedName by state.ble.connectedCameraName.collectAsState()
    val connectedAddress by state.ble.connectedCameraAddress.collectAsState()
    val connectedRssi by state.ble.connectedCameraRssi.collectAsState()
    val lastFixAt by state.ble.lastFixAt.collectAsState()
    val lastFixAccuracy by state.ble.lastFixAccuracy.collectAsState()
    val lastFixAltitude by state.ble.lastFixAltitude.collectAsState()
    val lastFixSatellites by state.ble.lastFixSatellites.collectAsState()
    val lastFixProvider by state.ble.lastFixProvider.collectAsState()
    val payloadSatellites by state.ble.lastPayloadSatellites.collectAsState()
    val payloadAltitude by state.ble.lastPayloadAltitude.collectAsState()
    val pushConfirmed by state.ble.lastPushConfirmed.collectAsState()

    val ready = phase == com.lutcamera.lutcamerapro.network.nikon.ble.NikonBleGpsClient.Phase.READY
    // 设备页的「多久之前」每 30 秒刷新一次；只在这张卡可见时跑，退出设备页就停。
    val now by produceState(System.currentTimeMillis(), ready) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    // 设备页空间有限：默认只显示关键状态，其余 GPS 细节点开再看
    var gpsDetailExpanded by remember { mutableStateOf(false) }

    val accuracyText = if (lastFixAccuracy > 0f) {
        String.format(java.util.Locale.US, "±%.0f m", lastFixAccuracy)
    } else "—"
    val satelliteText = if (lastFixSatellites > 0) "$lastFixSatellites 颗" else "—"
    val altitudeText = lastFixAltitude?.let {
        String.format(java.util.Locale.US, "%.0f m", it)
    } ?: "—"
    val intervalText = if (intervalMinutes >= 60) "1 小时" else "$intervalMinutes 分钟"
    val accuracyModeText = when (accuracyMode) {
        "high" -> "高精度"
        "low" -> "低功耗"
        else -> "均衡"
    }
    val providerText = when (lastFixProvider) {
        "gps" -> "GPS"
        "network" -> "网络"
        "fused" -> "融合"
        else -> lastFixProvider.ifBlank { "—" }
    }
    val linkText = if (ready) {
        buildString {
            append(connectedName?.ifBlank { "蓝牙相机" } ?: "蓝牙相机")
            connectedRssi?.let { append("　$it dBm") }
            if (connectedAddress.isNullOrBlank().not()) append("　").append(connectedAddress)
        }
    } else {
        "未连接"
    }
    val receiptText = when (pushConfirmed) {
        true -> "相机已确认"
        false -> "写入失败 / 被拒收"
        null -> "等待推送"
    }
    val payloadText = if (payloadSatellites > 0) {
        val alt = payloadAltitude?.let { "$it m" } ?: "—"
        "$payloadSatellites 颗 · $alt"
    } else "—"
    val lastPushAgo = if (lastPushAt > 0L) agoText(now - lastPushAt) else "—"
    val lastFixAgo = if (lastFixAt > 0L) agoText(now - lastFixAt) else "—"

    // Android 12+ 扫描/连接 BLE 必须同时有「附近的设备」和定位；旧版本只要定位。
    // 以前设备页直接 startScan，权限没授过时只会在内部 fail，界面看起来就是点了没反应。
    val blePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    var pendingBleAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            pendingBleAction?.invoke()
        } else {
            state.lastHint = "蓝牙或定位权限被拒绝，无法搜索相机蓝牙；请到系统设置里允许「附近的设备」和「位置」"
        }
        pendingBleAction = null
    }
    fun withBlePermission(action: () -> Unit) {
        val granted = blePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            action()
        } else {
            pendingBleAction = action
            blePermissionLauncher.launch(blePermissions)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DoukShapes.card)
            .doukCardSurface(DoukShapes.card)
            .padding(DoukSpace.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(DoukShapes.icon)
                    .background(DoukColors.accentContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Bluetooth, contentDescription = null, tint = DoukColors.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(DoukSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text("蓝牙 GPS 推送", style = DoukType.title, color = DoukColors.textPrimary)
                Text(
                    if (ready) "已通过蓝牙连上相机" else "${phase.label} · 每 $intervalText",
                    style = DoukType.caption,
                    color = if (ready) DoukColors.accent else DoukColors.textSecondary
                )
                if (detail.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(detail, style = DoukType.label, color = DoukColors.textTertiary)
                }
                if (state.isConnected && !ready) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        "Wi‑Fi 连接时可能停播；搜不到先断 Wi‑Fi，并让相机停在配对页",
                        style = DoukType.label,
                        color = DoukColors.textTertiary
                    )
                }
            }
        }

        // 找到的相机：点一下就配对连接（配对成功后就是上面那个「已通过蓝牙连上相机」状态）
        if (!ready && cameras.isNotEmpty()) {
            Spacer(modifier = Modifier.height(DoukSpace.sm))
            cameras.forEach { camera ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(DoukShapes.cardSmall)
                        .doukCardSurface(DoukShapes.cardSmall, baseColor = DoukColors.surfaceMuted)
                        .clickable { withBlePermission { state.ble.connect(camera) } }
                        .padding(horizontal = DoukSpace.sm, vertical = DoukSpace.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        camera.name.ifBlank { "尼康相机" },
                        style = DoukType.title,
                        color = DoukColors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text("连接", style = DoukType.label, color = DoukColors.accent)
                }
                Spacer(modifier = Modifier.height(DoukSpace.xs))
            }
        }

        Spacer(modifier = Modifier.height(DoukSpace.sm))
        if (gpsDetailExpanded) {
            GpsInfoRow("当前定位", locationText.ifBlank { "等待定位" })
            GpsInfoRow(
                "上次推送",
                if (lastPushAt > 0L) "$lastPushAgo · ${place.ifBlank { "定位中…" }}" else "还没有成功推送"
            )
            GpsInfoRow("定位精度", accuracyText)
            GpsInfoRow("卫星", satelliteText)
            GpsInfoRow("推送间隔", intervalText)
            GpsInfoRow(
                "坐标",
                if (lastLat != 0.0 || lastLon != 0.0) {
                    String.format(java.util.Locale.US, "%.5f, %.5f", lastLat, lastLon)
                } else "等待定位"
            )
            GpsInfoRow("海拔", altitudeText)
            GpsInfoRow("定位时间", if (lastFixAt > 0L) lastFixAgo else "尚未取得定位")
            GpsInfoRow("定位来源", providerText)
            GpsInfoRow("相机回执", receiptText)
            GpsInfoRow("上次数据包", payloadText)
            GpsInfoRow("蓝牙链路", linkText)
            GpsInfoRow("推送设置", "$accuracyModeText · 校时${if (syncTime) "开" else "关"} · 累计 $pushCount 次")
        }

        if (lastLat != 0.0 || lastLon != 0.0) {
            Spacer(modifier = Modifier.height(DoukSpace.xs))
            Text(
                "在地图中查看上次推送的位置",
                style = DoukType.label,
                color = DoukColors.accent,
                modifier = Modifier.clickable { openGpsInMap(context, lastLat, lastLon) }
            )
        }

        Spacer(modifier = Modifier.height(DoukSpace.sm))
        if (!ready && gpsEnabled) {
            // GPS 卡自己也能发起蓝牙连接，不用先切到遥控卡再扫
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DoukShapePill)
                    .background(DoukColors.accent)
                    .clickable {
                        withBlePermission {
                            state.ble.setAllowScanDuringWifi(true)
                            state.ble.startScan(autoConnect = true)
                        }
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("连接蓝牙相机", style = DoukType.title, color = Color.White)
            }
            Spacer(modifier = Modifier.height(DoukSpace.xs))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DoukSpace.xs)) {
            if (!gpsEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(DoukShapePill)
                        .background(DoukColors.surfaceMuted)
                        .alpha(0.6f)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("GPS 总开关已关闭（设置 → GPS 数据导入）", style = DoukType.caption, color = DoukColors.textSecondary)
                }
            } else if (ready) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(DoukShapePill)
                        .background(DoukColors.accentContainer)
                        .clickable { state.ble.pushLocationNowForSmartDevice() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("立即推送 GPS 数据", style = DoukType.title, color = DoukColors.accent)
                }
            } else {
                // 未连接时点这个不是只“搜一下”：会先连上相机蓝牙，再自动补推一次定位
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(DoukShapePill)
                        .background(DoukColors.accentContainer)
                        .clickable { withBlePermission { state.ble.pushLocationNowForSmartDevice() } }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("连接并推送一次 GPS", style = DoukType.title, color = DoukColors.accent)
                }
            }
        }

        Spacer(modifier = Modifier.height(DoukSpace.xs))
        Text(
            if (gpsDetailExpanded) "收起 GPS 详情" else "展开全部 GPS 状态",
            style = DoukType.label,
            color = DoukColors.accent,
            modifier = Modifier
                .fillMaxWidth()
                .clip(DoukShapes.cardSmall)
                .clickable { gpsDetailExpanded = !gpsDetailExpanded }
                .padding(vertical = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private val DoukShapePill = androidx.compose.foundation.shape.RoundedCornerShape(50)

/** 设备页 GPS 卡片里的小指标块：两列排布，避免把卡片拉得太长 */
@Composable
private fun GpsStatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(DoukShapes.cardSmall)
            .doukCardSurface(DoukShapes.cardSmall, baseColor = DoukColors.surfaceMuted)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(label, style = DoukType.label, color = DoukColors.textTertiary, maxLines = 1)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            value,
            style = DoukType.title,
            color = DoukColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 设备页 GPS 卡片的信息行：左侧固定标签，右侧值最多两行 */
@Composable
private fun GpsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = DoukType.label,
            color = DoukColors.textTertiary,
            modifier = Modifier.width(64.dp),
            maxLines = 1
        )
        Text(
            value,
            style = DoukType.caption,
            color = DoukColors.textSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun agoText(deltaMs: Long): String {
    if (deltaMs <= 0L) return "刚刚"
    val seconds = deltaMs / 1000L
    return when {
        seconds < 60L -> "${seconds} 秒前"
        seconds < 3600L -> "${seconds / 60L} 分钟前"
        seconds < 86_400L -> "${seconds / 3600L} 小时前"
        else -> "${seconds / 86_400L} 天前"
    }
}

/** 打开系统地图查看坐标；没有地图应用时给一条可读的 toast，不让点击静默失败 */
private fun openGpsInMap(context: android.content.Context, lat: Double, lon: Double) {
    val uri = android.net.Uri.parse(
        "geo:$lat,$lon?q=$lat,$lon(${android.net.Uri.encode("连镜 位置")})"
    )
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
    }.onFailure {
        android.widget.Toast.makeText(context, "没有找到可用的地图应用", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 主操作按钮：主色填充，54dp 高，图标与文字同一基线 */
@Composable
private fun PrimaryButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = DoukShapes.cardSmall,
        colors = ButtonDefaults.buttonColors(containerColor = DoukColors.accent)
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(DoukSpace.xs))
        Text(text, style = DoukType.title, color = Color.White)
    }
}

/** 次级按钮：中性底，不与主操作抢注意力 */
@Composable
private fun TonalButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = DoukShapes.cardSmall,
        colors = ButtonDefaults.buttonColors(containerColor = DoukColors.surfaceMuted)
    ) {
        Text(text, style = DoukType.body, color = DoukColors.textSecondary)
    }
}

@Composable
private fun CameraInfoGrid(state: DoukState) {
    Column(verticalArrangement = Arrangement.spacedBy(DoukSpace.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(DoukSpace.xs)) {
            InfoTile("电池电量", state.batteryText, Icons.Rounded.BatteryFull, Modifier.weight(1f))
            InfoTile("存储容量", state.storageText, Icons.Rounded.SdCard, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DoukSpace.xs)) {
            InfoTile("相机型号", state.profile.model, Icons.Rounded.CameraAlt, Modifier.weight(1f))
            InfoTile(
                "镜头规格",
                state.profile.lens,
                Icons.Rounded.Tune,
                Modifier.weight(1f),
                titleSuffix = state.profile.lensFocal,
                marquee = true
            )
        }
    }
}

/** 信息格：图标胶囊 + 小标签 + 主值，两级信息一眼分得开 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfoTile(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    titleSuffix: String? = null,
    marquee: Boolean = false
) {
    Column(
        modifier = modifier
            .clip(DoukShapes.cardSmall)
            .doukCardSurface(DoukShapes.cardSmall)
            .padding(horizontal = DoukSpace.md, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(DoukShapes.icon)
                    .background(DoukColors.accentContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = DoukColors.accent, modifier = Modifier.size(14.dp))
            }
            Spacer(modifier = Modifier.width(7.dp))
            Text(title, style = DoukType.caption, color = DoukColors.textSecondary)
            if (!titleSuffix.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    "| $titleSuffix",
                    style = DoukType.caption,
                    color = DoukColors.accent,
                    maxLines = 1
                )
            }
        }
        Spacer(modifier = Modifier.height(DoukSpace.xs))
        Text(
            value,
            style = DoukType.value,
            color = DoukColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (marquee) {
                Modifier.basicMarquee(initialDelayMillis = 1200)
            } else Modifier
        )
    }
}

@Composable
private fun ManualLine(text: String) {
    Text(
        text = text,
        style = DoukType.bodySmall,
        color = DoukColors.textSecondary,
        modifier = Modifier.padding(bottom = DoukSpace.xs)
    )
}

/**
 * 设备页头图解码：按目标宽度采样，只解出够用的大小。
 * 放到 Dispatchers.IO 上跑，别在主线程 decode 大图。
 */
private fun decodeHeroPhoto(path: String, targetWidthPx: Int = 1400): ImageBitmap? {
    if (path.isBlank()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > targetWidthPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null
        // 有些相机图是白底 JPG/PNG，直接铺在页面灰底上会像套了个白画框。
        // 从四条边开始只把「与边缘连通的近白像素」挖成透明，机身内部的白色字样不会被误伤。
        val cutout = removeEdgeWhiteBackground(decoded) ?: decoded
        cutout.asImageBitmap()
    }.getOrNull()
}

/** 只去掉与图片边缘连通的近白背景；如果本来就是透明底，原样返回。 */
private fun removeEdgeWhiteBackground(src: Bitmap): Bitmap? {
    if (src.width <= 0 || src.height <= 0) return null
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    fun nearWhite(c: Int): Boolean {
        val a = (c ushr 24) and 0xFF
        if (a < 8) return true
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r >= 228 && g >= 228 && b >= 228 &&
            (maxOf(r, g, b) - minOf(r, g, b) <= 18)
    }
    // 四个角都不是近白，说明不是白底图，别动它
    if (!nearWhite(pixels[0]) && !nearWhite(pixels[w - 1]) &&
        !nearWhite(pixels[(h - 1) * w]) && !nearWhite(pixels[h * w - 1])) {
        return src
    }
    val visited = BooleanArray(w * h)
    val stack = IntArray(w * h)
    var top = 0
    fun seed(index: Int) {
        if (!visited[index] && nearWhite(pixels[index])) {
            visited[index] = true
            stack[top++] = index
        }
    }
    for (x in 0 until w) {
        seed(x)
        seed((h - 1) * w + x)
    }
    for (y in 0 until h) {
        seed(y * w)
        seed(y * w + w - 1)
    }
    while (top > 0) {
        val index = stack[--top]
        pixels[index] = 0
        val x = index % w
        val y = index / w
        if (x > 0) seed(index - 1)
        if (x + 1 < w) seed(index + 1)
        if (y > 0) seed(index - w)
        if (y + 1 < h) seed(index + w)
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}
