package com.lutcamera.lutcamerapro.ui.douk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import com.lutcamera.lutcamerapro.log.DoukLog
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lutcamera.lutcamerapro.network.nikon.ble.DoukBleKeepAliveService
import com.lutcamera.lutcamerapro.network.nikon.ble.NikonBleGpsClient
import com.lutcamera.lutcamerapro.network.nikon.ble.NikonBleRemoteClient
import com.lutcamera.lutcamerapro.ui.screens.connect.nikon.NikonViewModel

private data class DoukTab(val label: String, val route: String, val icon: ImageVector)

@Composable
fun DoukRoot() {
    val context = LocalContext.current
    // 手势导航时我们在 MainActivity 里隐藏了系统导航条（避开 ROM 强制的浅色底），
    // 这里补一条和系统手势提示线同形的小胶囊，视觉上仍然有“返回桌面”的提示。
    val gestureNavigation = remember {
        runCatching {
            android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_mode", 0) == 2
        }.getOrDefault(false)
    }
    val vm = remember { NikonViewModel() }
    val usb = remember { DoukUsbConnector(context.applicationContext) }
    val ble = remember { NikonBleGpsClient(context.applicationContext) }
    val bleRemote = remember { NikonBleRemoteClient(context.applicationContext) }
    val state = remember(vm, usb, ble, bleRemote) { DoukState(vm, usb, ble, bleRemote) }

    // USB PTP 的 StateFlow 同步成 Compose 状态，页面才能实时刷新
    val usbConnected by usb.connected.collectAsStateWithLifecycle()
    LaunchedEffect(usbConnected) {
        state.usbConnected = usbConnected
        if (usbConnected) {
            state.refreshUsbBody()
        } else {
            state.usbBattery = null
            state.usbStorage = null
            state.usbIso = null
            state.usbPhotos = emptyList()
        }
    }
    LaunchedEffect(Unit) { usb.connectIfPermitted() }

    // PTP/IP 连接阶段（含失败原因）同步成 Compose 状态，用户能直接看到卡在哪一步
    val ptpStage by vm.connectionStage.collectAsStateWithLifecycle()
    LaunchedEffect(ptpStage) {
        state.connectionStageMirror = ptpStage
    }

    // 相机只给最小能力集时进入“待配对”，把状态同步到页面
    val pairingNeeded by vm.ptpClient.pairingRequired.collectAsStateWithLifecycle()
    LaunchedEffect(pairingNeeded) {
        state.pairingRequired = pairingNeeded
    }

    // 蓝牙 GPS / 蓝牙遥控 / Wi‑Fi / USB 任一路在线就拉前台服务，
    // 防止切到后台被系统冻结或回收；全部断开才停。
    val blePhase by ble.phase.collectAsStateWithLifecycle()
    val bleRemotePhase by bleRemote.phase.collectAsStateWithLifecycle()
    LaunchedEffect(blePhase, bleRemotePhase, state.isConnected) {
        val activeLinks = buildList {
            if (blePhase == NikonBleGpsClient.Phase.READY) add("蓝牙 GPS")
            if (bleRemotePhase == NikonBleRemoteClient.Phase.READY) add("蓝牙遥控")
            if (state.isConnected) add("Wi‑Fi / USB 相机")
        }
        if (activeLinks.isNotEmpty()) {
            DoukBleKeepAliveService.start(context, "正在保持 ${activeLinks.joinToString("、")} 连接")
        } else {
            DoukBleKeepAliveService.stop(context)
        }
    }

    DisposableEffect(vm) {
        onDispose {
            vm.disconnect()
            usb.release()
            ble.dispose()
        }
    }
    val nav = rememberNavController()
    val currentEntry by nav.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    var showAddDevice by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    // 控制页点最新照片缩略图 → 独立的「相机内」图库整屏，不经过底部 tab
    var showCameraGallery by remember { mutableStateOf(false) }
    // 在图库页再点一次底部「图库」，就把「照片 / 相册」条从底部弹出来
    var gallerySwitchTick by remember { mutableStateOf(0) }
    // 在设备页再点一次底部「设备」，就在 Douk 栏上方弹出「蓝牙 / Wi‑Fi|USB」两个按钮
    var showDeviceServiceBar by remember { mutableStateOf(false) }
    var deviceServiceMode by remember { mutableStateOf("wifi") }
    // 一旦用户切过服务页，内容就和切换条解绑：收起切换条只是藏掉两个按钮，
    // 当前页（蓝牙卡片 / Wi‑Fi|USB 卡片）继续显示，不再弹回初始设备页。
    var deviceServicePinned by remember { mutableStateOf(false) }
    // 离开设备页只收起切换条，不再复位服务模式：
    // 用户点了「蓝牙」后，下次回到设备页仍应停在蓝牙卡片，而不是被强行拉回 Wi‑Fi|USB。
    LaunchedEffect(currentRoute) {
        if (currentRoute != "device") {
            showDeviceServiceBar = false
        }
    }
    // 运行日志落盘需要 Context；Application 已安装，这里只作为兜底（重复调用无副作用）
    LaunchedEffect(Unit) { DoukLog.install(context) }


    // 机身拨到视频档 / 拨回拍照档时，控制页和监视页一起跟着换参数。
    // 以前只在控制页监听，用户停在监视页拨机身档位时监视页不会切；提到根节点后两页都跟。
    LaunchedEffect(state.detectedVideoMode) {
        val detected = state.detectedVideoMode ?: return@LaunchedEffect
        state.followDetectedMode(detected)
    }

    // 监看面板「接管相机」按钮的亮起状态要和持久化值一致（默认关闭 = 不接管、相机屏幕照常显示）
    LaunchedEffect(state) { state.syncTakeoverFromPref() }

    // 未连接相机时每 30 秒静默扫一次「连接过的设备」，设置里开着自动连接就直接连上
    LaunchedEffect(state) { state.startAutoConnectLoop(context) }
    DisposableEffect(state) {
        onDispose { state.stopAutoConnectLoop() }
    }

    // 蓝牙 GPS 不再随 Wi-Fi 相机自动连接：尼康同一时间只允许一路连接，
    // 自动抢占会导致 BLE 连上又被顶掉，界面反复横跳。需要 GPS 时到设备页手动连一次。

    // Android 13+ 的通知权限：连上相机要拉前台服务，没有这条权限通知不显示，
    // 用户就不知道 app 还在后台跑。放在「第一次真的连上相机」时问，启动时不打扰。
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    var askedNotification by remember { mutableStateOf(false) }

    // 启动时恢复上次选的外观：主题色 + 深色模式（未选过则跟随系统）
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(Unit) {
        DoukColors.applyAccent(DoukPrefs.themeColor)
        DoukColors.applyGlass(DoukPrefs.glassEffect)
        DoukColors.applyMaterial(DoukPrefs.materialAlpha, DoukPrefs.materialBlur)
        DoukColors.applyContrast(DoukPrefs.uiContrast)
        state.showBleGpsCard = DoukPrefs.showBleGpsCard
        state.showBleRemoteCard = DoukPrefs.showBleRemoteCard
        when (DoukPrefs.darkMode) {
            "深色" -> DoukColors.applyDark(true)
            "浅色" -> DoukColors.applyDark(false)
            else -> DoukColors.applyDark(systemDark)
        }
        state.showAddDeviceButton = DoukPrefs.showAddDeviceButton
        state.reloadMonitorPrefs()
    }

    // 全屏监视开/关时同步启停相机取景：不开监视就不占着相机的 LiveView 事务。
    // 关键：连接是异步的，用户可能在「未连接」时先进了监视页；只监听 previewFullscreen
    // 会错过连接完成的那一刻，导致监视页一直停在「等待相机画面」的黑屏。
    LaunchedEffect(state.previewFullscreen, state.isConnected) {
        when {
            !state.previewFullscreen -> vm.stopPreview()
            state.isConnected -> vm.startPreview()
        }
    }

    // 「识别到连接过的设备自动连接」交给 DoukState 的自动连接循环：
    // 先扫 300ms 端口确认在线，再直接进 PTP 握手。旧实现在启动时无条件拿历史 IP
    // 做 2×1.5 秒预检，相机不在线时每次冷启动都白等 4 秒，且和自动扫描重复。

    // Wi‑Fi 会话期间用更短的心跳阻止相机待机；断开后恢复默认间隔
    LaunchedEffect(state.isConnected, state.connectMode) {
        state.applyWifiPowerSave()
    }

    // 连上就把这台相机记进「已连接过的设备」，下次开机可自动连
    LaunchedEffect(state.isConnected) {
        if (!state.isConnected) return@LaunchedEffect
        val ip = state.lastTargetIp
        if (ip.isNotBlank()) DoukPrefs.rememberDevice(ip, state.profile.name)
        // 连上 Wi-Fi / USB 后不再自动抢蓝牙：相机同一时间只允许一路连接，
        // 蓝牙 GPS 需要时由设备页「搜索相机蓝牙」手动建立，避免两条链路互相顶掉。

        // 第一次连上时补通知权限：此后拉前台服务，通知才看得见
        if (!askedNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotification = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DoukTheme {
        Surface(
            color = Color.Transparent,
            modifier = Modifier
                .fillMaxSize()
                .background(DoukColors.backgroundBrush)
        ) {
            // hazeSource 只挂页面内容这一层：底栏、服务切换条、批量操作卡都是它的兄弟节点、
            // 画在它之后，hazeEffect 才能取到真正压在下面的内容。
            // （之前把源上移到根层，源里连底栏自己也一起被捕获，糊到的是自己，看着就是一块实色。）
            Box(Modifier.fillMaxSize()) {
            // 背景只负责铺色。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(DoukColors.backgroundBrush)
            )
            if (showAddDevice) {
                // 这两页在主 Scaffold 之外，edge-to-edge 下要自己让开系统栏。
                // 页面上没有悬浮底栏，不做 hazeSource，省掉一次全屏 RenderNode 捕获。
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    AddDeviceScreen(
                        state = state,
                        onBack = { showAddDevice = false },
                        onConnected = {
                            showAddDevice = false
                            nav.navigate("control") {
                                launchSingleTop = true
                            }
                        }
                    )
                }
            } else if (showLog) {
                BackHandler { showLog = false }
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    DoukLogScreen(onBack = { showLog = false })
                }
            } else if (showCameraGallery) {
                BackHandler { showCameraGallery = false }
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    GalleryScreen(state = state, startInCamera = true)
                }
            } else if (state.previewFullscreen) {
                // 全屏监看挂在根层：连底栏一起盖掉，才叫全屏
                BackHandler { state.previewFullscreen = false }
                FullscreenPreview(state)
            } else if (state.showCopyrightSheet) {
                // 版权与 IPTC：独立的设置页，可从机身读入
                BackHandler { state.showCopyrightSheet = false }
                CopyrightScreen(state = state, onBack = { state.showCopyrightSheet = false })
            } else {
                Scaffold(
                    // 让根层 Surface 的渐变一直铺到系统导航条下面；否则 Scaffold 的实色底
                    // 会在底栏和手势条之间切出一条颜色不同的白带。
                    containerColor = Color.Transparent,
                    // 底栏不占 Scaffold 的 bottomBar 槽位：内容一直铺到屏幕最底部，
                    // 滚动时从透明胶囊下面穿过去（胶囊背后的真实模糊才有东西可糊）。
                    // 用户要求的「最下面那块增长一点」由各页自己的收尾间距负责（> 胶囊高度），
                    // 所以滚到最底时最后一块内容会整体越过胶囊，不会被切掉。
                    bottomBar = {}
                ) { padding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 内容只让开顶部状态栏；底部不再让出 100+dp，否则网格/卡片会被裁在胶囊那条线上。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = padding.calculateTopPadding())
                        ) {
                        // 页面内容这层做源：底栏与浮层都在它上面，那句话才能糊出内容本身。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .hazeSource(DoukColors.hazeState)
                        ) {
                        NavHost(
                            navController = nav,
                            startDestination = "device",
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable("device") {
                                DeviceScreen(
                                    state = state,
                                    onAddDevice = { showAddDevice = true },
                                    onOpenControl = { nav.navigate("control") { launchSingleTop = true } },
                                    serviceMode = if (showDeviceServiceBar || deviceServicePinned) deviceServiceMode else null,
                                    serviceBarVisible = showDeviceServiceBar
                                )
                            }
                            composable("control") {
                                ControlScreen(
                                    state = state,
                                    onBack = { nav.popBackStack() },
                                    onOpenCameraGallery = { showCameraGallery = true }
                                )
                            }
                            composable("gallery") {
                                GalleryScreen(
                                    state = state,
                                    switchTick = gallerySwitchTick,
                                    onSwitchConsumed = { gallerySwitchTick = 0 }
                                )
                            }
                            composable("settings") {
                                DoukSettingsScreen(state = state)
                            }
                        }
                        }
                        }
                        // 看图/编辑器/详情是盖在 Activity 上的独立 Dialog，
                        // 底栏本体留在下面只会在 2780–2880 露出投影和圆角边。
                        // 打开这些覆盖层时整条底栏不参与组合树。
                        if (!ViewerChrome.open && !EditorChrome.open && !DetailChrome.open) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                        ) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = showDeviceServiceBar
                            ) {
                                DeviceServiceSwitcher(
                                    mode = deviceServiceMode,
                                    onSelect = {
                                        deviceServiceMode = it
                                        // 记下用户这次选的服务页；之后即使收起切换条也保持不跳页
                                        deviceServicePinned = true
                                    }
                                )
                            }
                            // 图库多选的批量操作卡：贴在底栏正上方（不再塞在图库页底部）
                            if (state.gallerySelCount > 0 && state.gallerySelKind.isNotBlank()) {
                                val pill = RoundedCornerShape(percent = 50)
                                val isDelete = state.gallerySelKind == "delete"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 18.dp, vertical = 4.dp)
                                        .clip(pill)
                                        .doukCardSurface(pill)
                                        .clickable(enabled = !state.galleryBulkBusy) { state.requestGalleryBulk() }
                                        .padding(horizontal = 18.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        if (isDelete) Icons.Rounded.Delete else Icons.Rounded.Save,
                                        contentDescription = null,
                                        tint = if (isDelete) Color(0xFFE5484D) else DoukColors.accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        when {
                                            state.galleryBulkBusy -> if (isDelete) "正在删除…" else "正在保存…"
                                            isDelete -> "删除选中 ${state.gallerySelCount} 张"
                                            else -> "保存选中 ${state.gallerySelCount} 张到应用相册"
                                        },
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isDelete) Color(0xFFE5484D) else DoukColors.accent
                                    )
                                }
                            }
                            DoukBottomBar(
                                nav = nav,
                                state = state,
                                onGalleryReselect = { gallerySwitchTick++ },
                                onDeviceReselect = {
                                    // 再点一次设备是开关：显示时收起，收起时显示。
                                    // 收起时保留当前服务模式，避免用户刚切到蓝牙又被拉回 Wi‑Fi|USB。
                                    if (currentRoute == "device") {
                                        showDeviceServiceBar = !showDeviceServiceBar
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        }
                    }
                }
            }
            // 保护文件导入确认：设备页有一份，这里补一份全局的。
            // 用户连上相机后通常停在控制/图库页，只在设备页弹的话确认框永远看不到，
            // 自动导入就一直卡在「等待确认」上，表现就是保护文件从来不导入。
            if (state.lockedImportPrompt && currentRoute != "device") {
                val onlyNew = DoukPrefs.lockedNewOnly
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { state.dismissLockedImport() },
                    title = {
                        Text(if (onlyNew) "导入连接后新增的锁定照片？" else "导入相机里所有锁定照片？")
                    },
                    text = {
                        Text(
                            if (onlyNew) "只导入本次连接相机之后新拍/新锁定的文件，已经锁定过的旧文件不会重复导入。"
                            else "导入相机里所有标了保护/锁定的文件，逐张保存到应用图库，耗时取决于数量。"
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { state.confirmLockedImport() }
                        ) { Text("开始导入", color = DoukColors.accent) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { state.dismissLockedImport() }
                        ) { Text("暂不", color = DoukColors.textSecondary) }
                    }
                )
            }
            if (gestureNavigation) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .width(108.dp)
                        .height(3.dp)
                        .background(
                            if (DoukColors.isDark) Color.White.copy(alpha = 0.62f)
                            else Color.Black.copy(alpha = 0.32f),
                            RoundedCornerShape(percent = 50)
                        )
                )
            }
            // 编辑器窗口整体下移了导航栏高度，顶部 100px 会露出下面页面。
            // 编辑器打开时把 base 整屏铺成编辑器画布色，顶部的状态栏/空隙
            // 就和编辑器标题栏无缝接上，不再透出图库日期。
            if (EditorChrome.open) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFEFEAE2))
                )
            }
            // 看图页打开时，Dialog 窗口在部分 ROM 上无法真正铺进状态栏；
            // 在根布局最上层补色，盖住透出的图库浅灰，也让日期渐变看起来从屏幕最顶部开始。
            // 编辑器是浅色界面，用编辑器自己的浅色底盖住，避免白图标压黑底。
            if (ViewerChrome.open || EditorChrome.open || DetailChrome.open) {
                val chromeTopColor = when {
                    DetailChrome.open -> Color.White
                    EditorChrome.open -> Color(0xFFEFEAE2)
                    else -> Color.Black
                }
                val chromeBottomColor = when {
                    DetailChrome.open -> Color.White
                    EditorChrome.open -> Color(0xFFFFFDF9)
                    else -> Color.Black
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                        .background(chromeTopColor)
                )
                // 部分 ROM 上 navigationBars inset 会是 0，Dialog 又会停在 2780，
                // 底部仍露出图库页。这里固定铺够 56dp，视觉上直接和 Dialog 拼齐。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(chromeBottomColor)
                )
            }
            }
        }
    }
}

/**
 * 设备页再次点底部「设备」后浮在 Douk 栏上方的两段切换：
 * 蓝牙 = GPS 推送与相机遥控；Wi‑Fi|USB = 电量/储存与当前链路状态。
 * 相机实物照片不参与切换，两种界面都固定显示在顶部。
 */
@Composable
private fun DeviceServiceSwitcher(
    mode: String,
    onSelect: (String) -> Unit
) {
    val pill = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .clip(pill)
            .doukCardSurface(pill)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ServiceSwitchSegment(
            label = "蓝牙",
            icon = Icons.Rounded.Bluetooth,
            selected = mode == "bluetooth",
            modifier = Modifier.weight(1f),
            onClick = { onSelect("bluetooth") }
        )
        ServiceSwitchSegment(
            label = "Wi‑Fi | USB",
            icon = Icons.Rounded.Wifi,
            selected = mode == "wifi",
            modifier = Modifier.weight(1f),
            onClick = { onSelect("wifi") }
        )
    }
}

@Composable
private fun ServiceSwitchSegment(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val pill = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .clip(pill)
            .then(
                if (selected) Modifier
                    .background(DoukColors.accent.copy(alpha = if (DoukColors.glass) 0.16f else 0.10f), pill)
                    .border(1.dp, DoukColors.accent.copy(alpha = 0.26f), pill)
                else Modifier
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) DoukColors.accent else DoukColors.textSecondary,
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            style = DoukType.title,
            color = if (selected) DoukColors.accent else DoukColors.textPrimary,
            maxLines = 1
        )
    }
}

@Composable
private fun DoukBottomBar(
    nav: androidx.navigation.NavHostController,
    state: DoukState,
    modifier: Modifier = Modifier,
    onGalleryReselect: () -> Unit = {},
    onDeviceReselect: () -> Unit = {}
) {
    val tabs = listOf(
        DoukTab("设备", "device", Icons.Rounded.CameraAlt),
        DoukTab("控制", "control", Icons.Rounded.Tune),
        DoukTab("图库", "gallery", Icons.Rounded.PhotoLibrary),
        DoukTab("设置", "settings", Icons.Rounded.Settings)
    )
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination

    // 真正的悬浮胶囊：四角留白、整条圆角，浮在页面内容上方。
    // 系统导航条的安全区由外层 Scaffold 的 padding 让开，这里不再重复加。
    val pill = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 玻璃开启时不要投影：半透明卡片会把背后的影子透出来，像多了一层方块
                .then(
                    if (DoukColors.glass) Modifier
                    // 20dp 的投影会在手势条上方压出一圈灰带，看起来像另一块底板；
                    // 收到 12dp，只保留胶囊边缘的一点点浮起感。
                    else Modifier.shadow(12.dp, pill, clip = false)
                )
                .clip(pill)
                // 玻璃开启时不再垫 96% 实色底，否则真实模糊会被整块盖住；
                // 底栏背后的页面文字/图标由 hazeChild 直接高斯模糊。
                .then(
                    if (DoukColors.glass) Modifier
                    else Modifier.background(DoukColors.surface, pill)
                )
                .then(
                    if (DoukColors.glass) Modifier.doukGlassDock(pill)
                    else Modifier.doukCardSurface(pill)
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val selected = current?.hierarchy?.any { it.route == tab.route } == true
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(pill)
                        .then(
                            if (selected) {
                                // 选中的 tab：一层均匀的淡主色玻璃 + 细描边，
                                // 不做渐变，避免胶囊出现左边深右边浅的色块
                                Modifier
                                    .background(
                                        DoukColors.accent.copy(
                                            alpha = if (DoukColors.glass) 0.14f else 0.10f
                                        ),
                                        pill
                                    )
                                    .border(
                                        1.dp,
                                        DoukColors.accent.copy(alpha = if (DoukColors.glass) 0.26f else 0.18f),
                                        pill
                                    )
                            } else Modifier
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            // 已经停在这一页、又点的是「图库」，就弹「照片 / 相册」选择条
                            if (selected && tab.route == "gallery") {
                                onGalleryReselect()
                            } else if (selected && tab.route == "device") {
                                // 再点一次设备：在 Douk 栏上方切换「蓝牙 / Wi‑Fi|USB」
                                onDeviceReselect()
                            } else if (tab.route == "device") {
                                // 「设备」是起始页：如果 restoreState，会把上次停在 control 的状态一起还原，
                                // 表现就是点设备标签却回到控制页（有时像“设备页打不开”）。
                                nav.navigate("device") {
                                    popUpTo(nav.graph.findStartDestination().id) { inclusive = false }
                                    launchSingleTop = true
                                    restoreState = false
                                }
                            } else {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (selected) DoukColors.accent else DoukColors.textTertiary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        style = DoukType.tab,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) DoukColors.accent else DoukColors.textSecondary
                    )
                }
            }
        }
    }
}



