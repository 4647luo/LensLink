package com.lutcamera.lutcamerapro.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lutcamera.lutcamerapro.model.BorderState
import com.lutcamera.lutcamerapro.model.CameraBrand
import com.lutcamera.lutcamerapro.model.CameraStorageFile
import com.lutcamera.lutcamerapro.ui.components.*
import com.lutcamera.lutcamerapro.ui.screens.connect.nikon.NikonDiagnosticsPanel
import com.lutcamera.lutcamerapro.ui.screens.connect.nikon.NikonPhotoTransferPanel
import com.lutcamera.lutcamerapro.ui.screens.connect.nikon.NikonViewModel
import com.lutcamera.lutcamerapro.ui.screens.connect.sdcard.SdCardSection
import com.lutcamera.lutcamerapro.ui.screens.connect.sdcard.SdCardViewModel
import com.lutcamera.lutcamerapro.ui.screens.connect.wired.SaveMode
import com.lutcamera.lutcamerapro.ui.screens.connect.wired.WiredViewModel
import com.lutcamera.lutcamerapro.ui.screens.monitor.LiveViewGlSurfaceView
import com.lutcamera.lutcamerapro.ui.theme.AppleTheme
import com.lutcamera.lutcamerapro.ui.viewmodel.LutViewModel
import com.lutcamera.lutcamerapro.ui.viewmodel.SettingsViewModel

/**
 * 联机拍摄工作台连接模式
 */
enum class ActiveTetherMode(val title: String, val icon: ImageVector) {
    WIRELESS_NIKON("尼康无线", Icons.Default.Wifi),
    USB_TETHER("USB有线", Icons.Default.Usb),
    SD_CARD("SD卡导入", Icons.Default.SdCard)
}

@Composable
fun CameraScreen(
    lutViewModel: LutViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
    nikonViewModel: NikonViewModel = viewModel(),
    wiredViewModel: WiredViewModel = viewModel(),
    sdCardViewModel: SdCardViewModel = viewModel(),
    borderState: BorderState = BorderState()
) {
    val context = LocalContext.current
    val apple = AppleTheme.colors

    var tetherMode by rememberSaveable { mutableStateOf(ActiveTetherMode.WIRELESS_NIKON) }

    // 弹窗状态
    var showBrandDialog by remember { mutableStateOf(false) }
    var showShutterDialog by remember { mutableStateOf(false) }
    var showApertureDialog by remember { mutableStateOf(false) }
    var showIsoDialog by remember { mutableStateOf(false) }
    var showShootModeDialog by remember { mutableStateOf(false) }
    var showPhotoDetailDialog by remember { mutableStateOf(false) }
    var showStorageDialog by remember { mutableStateOf(false) }
    var showFullscreenLiveView by rememberSaveable { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }

    // 监视器辅助开关
    var showLutFilterOnMonitor by rememberSaveable { mutableStateOf(true) }
    var showGridLines by rememberSaveable { mutableStateOf(false) }
    var showZebraWarning by rememberSaveable { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }

    // 点击对焦指示
    var focusTouchPoint by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    // 照片详情
    var selectedPhotoForDetail by remember { mutableStateOf<Bitmap?>(null) }
    var selectedPhotoTitle by remember { mutableStateOf("") }

    // 选中的相机品牌
    var selectedBrand by remember { mutableStateOf(CameraBrand.NIKON) }

    // 实时图传流
    val nikonLiveViewActive by nikonViewModel.isLiveViewActive.collectAsState()
    val nikonLiveViewBmp by nikonViewModel.liveViewBitmap.collectAsState()
    val nikonLiveStats by nikonViewModel.liveMetrics.collectAsState()

    val usbLiveViewActive by wiredViewModel.isUsbLiveViewActive.collectAsState()
    val usbLiveViewBmp by wiredViewModel.usbLiveViewBitmap.collectAsState()

    val currentLiveFrame: Bitmap? = when (tetherMode) {
        ActiveTetherMode.WIRELESS_NIKON -> if (nikonLiveViewActive) nikonLiveViewBmp else null
        ActiveTetherMode.USB_TETHER -> if (usbLiveViewActive) usbLiveViewBmp else null
        else -> null
    }

    val isCurrentLiveActive = (tetherMode == ActiveTetherMode.WIRELESS_NIKON && nikonLiveViewActive) ||
            (tetherMode == ActiveTetherMode.USB_TETHER && usbLiveViewActive)

    val previewView = LocalView.current
    DisposableEffect(previewView, isCurrentLiveActive) {
        val previous = previewView.keepScreenOn
        if (isCurrentLiveActive) previewView.keepScreenOn = true
        onDispose { previewView.keepScreenOn = previous }
    }
    val focusRadiusPx = with(LocalDensity.current) { 24.dp.toPx() }
    LaunchedEffect(focusTouchPoint) {
        if (focusTouchPoint != null) {
            kotlinx.coroutines.delay(1200)
            focusTouchPoint = null
        }
    }

    val isCameraConnected = when (tetherMode) {
        ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.isConnected
        ActiveTetherMode.USB_TETHER -> wiredViewModel.isConnected
        ActiveTetherMode.SD_CARD -> sdCardViewModel.isMounted || sdCardViewModel.currentFolderUri != null
    }

    // USB 广播接收器注册
    DisposableEffect(context) {
        wiredViewModel.checkUsbConnection(context.applicationContext)
        onDispose {
            wiredViewModel.unregisterUsbReceiver(context.applicationContext)
        }
    }

    // 后台 Service 参数同步
    val currentGlobalSaveMode = try { SaveMode.valueOf(settingsViewModel.saveModeName) } catch (e: Exception) { SaveMode.DUAL }
    SideEffect {
        nikonViewModel.configurePhotoProcessing(
            context = context.applicationContext,
            lut = lutViewModel.currentLutData,
            intensity = lutViewModel.lutIntensity,
            autoApplyLut = settingsViewModel.autoApplyLut,
            autoApplyBorder = settingsViewModel.autoApplyBorder,
            saveMode = currentGlobalSaveMode,
            borderState = borderState,
            xmp = lutViewModel.currentXmpData
        )
    }
    LaunchedEffect(
        lutViewModel.currentLutData,
        lutViewModel.lutIntensity,
        borderState,
        settingsViewModel.autoApplyLut,
        settingsViewModel.autoApplyBorder,
        currentGlobalSaveMode,
        lutViewModel.currentXmpData
    ) {
        wiredViewModel.syncParamsToService(
            lut = lutViewModel.currentLutData,
            intensity = lutViewModel.lutIntensity,
            border = borderState
        )
        com.lutcamera.lutcamerapro.service.AppForegroundService.autoApplyLut = settingsViewModel.autoApplyLut
        com.lutcamera.lutcamerapro.service.AppForegroundService.autoApplyBorder = settingsViewModel.autoApplyBorder
        com.lutcamera.lutcamerapro.service.AppForegroundService.saveMode = currentGlobalSaveMode
        com.lutcamera.lutcamerapro.service.AppForegroundService.currentXmp = lutViewModel.currentXmpData
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(apple.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // ================= 1. 顶部 Header (品牌、状态与日志入口) =================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 品牌选择胶囊
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(apple.surface)
                    .border(2.dp, apple.border, RoundedCornerShape(14.dp))
                    .clickable { showBrandDialog = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Camera,
                    contentDescription = null,
                    tint = apple.accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = selectedBrand.shortName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = apple.textPrimary
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = apple.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            // 状态药丸与日志入口
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showLogs = !showLogs }) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "日志",
                        tint = if (showLogs) apple.accent else apple.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                ApplePillBadge(
                    text = if (isCameraConnected) "已连接" else "待连接",
                    dotColor = if (isCameraConnected) apple.accent else apple.accentRed,
                    badgeColor = apple.surface
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ================= 2. 模式切换分段控件 =================
        AppleSegmentedControl(
            items = ActiveTetherMode.entries,
            selectedItem = tetherMode,
            onItemSelected = { tetherMode = it },
            labelProvider = { it.title },
            iconProvider = { it.icon }
        )

        Spacer(modifier = Modifier.height(6.dp))

        // ================= 2.1 连接说明书快捷入口 =================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "当前工作流：${tetherMode.title}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = apple.textSecondary
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showHelpDialog = true }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = apple.accent,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "📖 连接说明书",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = apple.accent
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ================= 3. 主模式内容分发 =================
        if (tetherMode == ActiveTetherMode.SD_CARD) {
            // SD 卡导入专区
            SdCardSection(viewModel = sdCardViewModel)
        } else {
            // 无线 / USB 有线：专业监视取景与联机工作台
            // 3.1 监视器视口 (16:9 高圆角 OpenGL View)
            Text(
                text = "💡 取景监视器：点击画面任意位置下发触控对焦。下方可开启网格线与斑马纹辅助",
                fontSize = 12.sp,
                color = apple.textSecondary,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
            )
            AppleCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                backgroundColor = Color.Black,
                borderColor = if (isCurrentLiveActive) apple.accent else apple.border
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                        .pointerInput(tetherMode, isCurrentLiveActive) {
                            detectTapGestures { offset ->
                                if (!isCurrentLiveActive) return@detectTapGestures
                                val normX = (offset.x / size.width).coerceIn(0f, 1f)
                                val normY = (offset.y / size.height).coerceIn(0f, 1f)
                                focusTouchPoint = Pair(offset.x, offset.y)
                                when (tetherMode) {
                                    ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.setFocusPoint(normX, normY)
                                    else -> wiredViewModel.setFocusPoint(normX, normY)
                                }
                            }
                        }
                ) {
                    // OpenGL 渲染器
                    if (!showFullscreenLiveView) AndroidView(
                        factory = { ctx ->
                            LiveViewGlSurfaceView(ctx).apply {
                                setWarnings(highlight = showZebraWarning, shadow = showZebraWarning)
                                if (showLutFilterOnMonitor) {
                                    setLut(lutViewModel.currentLutData, lutViewModel.lutIntensity)
                                    setXmp(lutViewModel.currentXmpData)
                                }
                            }
                        },
                        update = { view ->
                            view.setWarnings(highlight = showZebraWarning, shadow = showZebraWarning)
                            if (showLutFilterOnMonitor) {
                                view.setLut(lutViewModel.currentLutData, lutViewModel.lutIntensity)
                                view.setXmp(lutViewModel.currentXmpData)
                            } else {
                                view.setLut(null, 0f)
                                view.setXmp(null)
                            }
                            currentLiveFrame?.let { view.updateLiveFrame(it) }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // 离线占位提示
                    if (!isCurrentLiveActive) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.VideocamOff,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (isCameraConnected) "取景休眠中 · 点击下方按钮唤醒" else "相机未连接 · 请先开启相机 Wi-Fi 并连接",
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // 九宫格参考线
                    if (showGridLines) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val lineColor = Color.White.copy(alpha = 0.35f)
                            drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(w / 3f, 0f), end = androidx.compose.ui.geometry.Offset(w / 3f, h), strokeWidth = 1.5f)
                            drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(w * 2f / 3f, 0f), end = androidx.compose.ui.geometry.Offset(w * 2f / 3f, h), strokeWidth = 1.5f)
                            drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(0f, h / 3f), end = androidx.compose.ui.geometry.Offset(w, h / 3f), strokeWidth = 1.5f)
                            drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(0f, h * 2f / 3f), end = androidx.compose.ui.geometry.Offset(w, h * 2f / 3f), strokeWidth = 1.5f)
                        }
                    }

                    // 触控对焦指示器
                    focusTouchPoint?.let { (fx, fy) ->
                        Box(
                            modifier = Modifier
                                .offset { IntOffset((fx - focusRadiusPx).roundToInt(), (fy - focusRadiusPx).roundToInt()) }
                                .size(48.dp)
                                .border(2.dp, apple.accent, CircleShape)
                        )
                    }

                    // 顶部控制快捷药丸
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // LUT 滤镜开关
                            MonitorMiniButton(
                                text = if (showLutFilterOnMonitor) "LUT 预览" else "原片直出",
                                active = showLutFilterOnMonitor,
                                activeColor = apple.accent,
                                onClick = { showLutFilterOnMonitor = !showLutFilterOnMonitor }
                            )
                            // 九宫格
                            MonitorMiniButton(
                                text = "九宫格",
                                active = showGridLines,
                                activeColor = apple.accent,
                                onClick = { showGridLines = !showGridLines }
                            )
                            // 斑马纹
                            MonitorMiniButton(
                                text = "斑马纹",
                                active = showZebraWarning,
                                activeColor = apple.accent,
                                onClick = { showZebraWarning = !showZebraWarning }
                            )
                        }

                        // 全屏
                        IconButton(
                            onClick = { showFullscreenLiveView = true },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Default.Fullscreen, contentDescription = "横屏全屏预览", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }

                    // 底部 FPS 与状态指示
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ApplePillBadge(
                            text = if (!isCurrentLiveActive) "待机" else if (tetherMode == ActiveTetherMode.WIRELESS_NIKON)
                                String.format(java.util.Locale.getDefault(), "接收 %.1f · 解码 %.1f fps", nikonLiveStats.receiveFps, nikonLiveStats.decodeFps)
                            else "实时取景",
                            dotColor = if (isCurrentLiveActive) apple.accent else apple.accentRed,
                            badgeColor = Color.Black.copy(alpha = 0.6f),
                            textColor = Color.White,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3.2 横向 LUT 胶囊快捷选择栏
            Text(
                text = "实时 LUT 滤镜选择（即时套用至监视器取景与成片）",
                fontSize = 12.sp,
                color = apple.textSecondary,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(lutViewModel.lutLibraryList.value) { lutItem ->
                    val isSelected = lutViewModel.currentLutId == lutItem.id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) apple.accent.copy(alpha = 0.15f) else apple.surface)
                            .border(
                                2.dp,
                                if (isSelected) apple.accent else apple.border,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { lutViewModel.selectLut(lutItem.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) apple.accent else Color.Gray)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = lutItem.name,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                                color = if (isSelected) apple.accent else apple.textPrimary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3.3 曝光参数四要素卡片 (快门 / 光圈 / ISO / 模式)
            Text(
                text = "曝光控制参数（点击调出可选档位）",
                fontSize = 12.sp,
                color = apple.textSecondary,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ParamAppleCard(
                    title = "快门",
                    value = when (tetherMode) {
                        ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.shutterSpeed
                        else -> wiredViewModel.shutterSpeed
                    },
                    modifier = Modifier.weight(1f),
                    color = apple.accent,
                    onClick = { showShutterDialog = true }
                )
                ParamAppleCard(
                    title = "光圈",
                    value = when (tetherMode) {
                        ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.aperture
                        else -> wiredViewModel.aperture
                    },
                    modifier = Modifier.weight(1f),
                    color = apple.accent,
                    onClick = { showApertureDialog = true }
                )
                ParamAppleCard(
                    title = "ISO",
                    value = when (tetherMode) {
                        ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.iso
                        else -> wiredViewModel.iso
                    },
                    modifier = Modifier.weight(1f),
                    color = apple.accent,
                    onClick = { showIsoDialog = true }
                )
                ParamAppleCard(
                    title = "模式",
                    value = when (tetherMode) {
                        ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.shootMode
                        else -> wiredViewModel.shootMode
                    },
                    modifier = Modifier.weight(1f),
                    color = apple.accent,
                    onClick = { showShootModeDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3.4 核心联机控制面板 (相册、AF、快门、视频录制、成片缩略图)
            Text(
                text = "联机操作台（对焦、拍摄与机身相册）",
                fontSize = 12.sp,
                color = apple.textSecondary,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
            )
            AppleCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 机身图库弹窗入口
                    AppleIconButton(
                        icon = Icons.Default.PhotoLibrary,
                        colorStyle = AppleButtonColor.SURFACE,
                        size = 50.dp,
                        iconSize = 22.dp,
                        onClick = {
                            when (tetherMode) {
                                ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.fetchCameraPhotos()
                                else -> {}
                            }
                            showStorageDialog = true
                        }
                    )

                    // AF 对焦按键
                    AppleIconButton(
                        icon = Icons.Default.CenterFocusStrong,
                        colorStyle = AppleButtonColor.PRIMARY,
                        size = 54.dp,
                        iconSize = 24.dp,
                        onClick = {
                            when (tetherMode) {
                                ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.triggerAf()
                                else -> wiredViewModel.triggerAf()
                            }
                            Toast.makeText(context, "自动对焦 (AF)", Toast.LENGTH_SHORT).show()
                        }
                    )

                    // 快门大按键 (苹果风蓝色胶囊，触感反馈)
                    AppleIconButton(
                        icon = Icons.Default.CameraAlt,
                        colorStyle = AppleButtonColor.PRIMARY,
                        size = 72.dp,
                        iconSize = 34.dp,
                        onClick = {
                            when (tetherMode) {
                                ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.triggerCapture()
                                else -> wiredViewModel.triggerCapture()
                            }
                            Toast.makeText(context, "快门已触发", Toast.LENGTH_SHORT).show()
                        }
                    )

                    // 视频录像开关
                    AppleIconButton(
                        icon = if (isRecordingVideo) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        colorStyle = AppleButtonColor.DESTRUCTIVE,
                        size = 54.dp,
                        iconSize = 24.dp,
                        onClick = {
                            isRecordingVideo = !isRecordingVideo
                            when (tetherMode) {
                                ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.toggleMovieRecord(isRecordingVideo)
                                else -> wiredViewModel.toggleMovieRecord(isRecordingVideo)
                            }
                        }
                    )

                    // 最新回传照片缩略图 / 成品预览
                    val latestBmp = when (tetherMode) {
                        ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.lastCapturedBitmap
                        else -> wiredViewModel.lastProcessedBitmap
                    }
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(apple.surfaceSecondary)
                            .border(2.dp, apple.border, RoundedCornerShape(14.dp))
                            .clickable(enabled = latestBmp != null) {
                                selectedPhotoForDetail = latestBmp
                                selectedPhotoTitle = "最新拍摄成片预览"
                                showPhotoDetailDialog = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (latestBmp != null) {
                            Image(
                                bitmap = latestBmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = apple.textTertiary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (tetherMode == ActiveTetherMode.WIRELESS_NIKON) {
                NikonPhotoTransferPanel(nikonViewModel, settingsViewModel, lutViewModel)
                Spacer(Modifier.height(12.dp))
                NikonDiagnosticsPanel(nikonViewModel, settingsViewModel.nikonIp, settingsViewModel::updateNikonIp)
                Spacer(Modifier.height(12.dp))
            }

            // 3.5 连接控制与状态操作
            Text(
                text = "通信连接状态与控制",
                fontSize = 12.sp,
                color = apple.textSecondary,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
            )
            AppleCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = when (tetherMode) {
                                ActiveTetherMode.WIRELESS_NIKON -> "尼康 PTP/IP 通信"
                                else -> "USB OTG 有线线控"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = apple.textPrimary
                        )
                        Text(
                            text = when (tetherMode) {
                                ActiveTetherMode.WIRELESS_NIKON -> "目标 IP: ${settingsViewModel.nikonIp}"
                                else -> "USB 硬件直连模式"
                            },
                            fontSize = 12.sp,
                            color = apple.textSecondary
                        )
                    }

                    AppleButton(
                        text = if (isCameraConnected) "断开连接" else if (tetherMode == ActiveTetherMode.WIRELESS_NIKON && nikonViewModel.isConnecting) "取消连接" else "立即连接",
                        colorStyle = if (isCameraConnected) AppleButtonColor.DESTRUCTIVE else AppleButtonColor.PRIMARY,
                        height = 38.dp,
                        fontSize = 13.sp,
                        onClick = {
                            when (tetherMode) {
                                ActiveTetherMode.WIRELESS_NIKON -> {
                                    if (nikonViewModel.diagnosticsBusy) {
                                        Toast.makeText(context, "请等待压缩测试恢复原值后再断开", Toast.LENGTH_SHORT).show()
                                    } else if (nikonViewModel.isConnected || nikonViewModel.isConnecting) nikonViewModel.disconnect()
                                    else nikonViewModel.connect(context = context, cameraIp = settingsViewModel.nikonIp)
                                }
                                else -> {
                                    if (wiredViewModel.isConnected) wiredViewModel.disconnect()
                                    else wiredViewModel.checkUsbConnection(context)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // ================= 弹窗管理 =================

    // 1. 品牌选择弹窗
    if (showBrandDialog) {
        Dialog(onDismissRequest = { showBrandDialog = false }) {
            AppleCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "选择相机品牌",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = apple.textPrimary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                CameraBrand.entries.forEach { brand ->
                    val isSelected = selectedBrand == brand
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) apple.accent.copy(alpha = 0.15f) else apple.surfaceSecondary)
                            .border(2.dp, if (isSelected) apple.accent else apple.border, RoundedCornerShape(14.dp))
                            .clickable {
                                selectedBrand = brand
                                when (brand) {
                                    CameraBrand.NIKON -> tetherMode = ActiveTetherMode.WIRELESS_NIKON
                                    else -> {}
                                }
                                showBrandDialog = false
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = brand.displayName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) apple.accent else apple.textPrimary
                            )
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = apple.accent)
                            }
                        }
                    }
                }
            }
        }
    }

    // 2. 通用参数选择弹窗（无线模式使用相机上报的真实档位列表）
    if (showShutterDialog) {
        AppleParamPicker(
            title = "快门速度",
            options = when (tetherMode) {
                ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.availableShutterSpeeds
                else -> selectedBrand.shutterSpeeds
            },
            currentValue = when (tetherMode) {
                ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.shutterSpeed
                else -> wiredViewModel.shutterSpeed
            },
            onSelect = {
                when (tetherMode) {
                    ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.setShutterSpeed(it)
                    else -> wiredViewModel.setShutterSpeed(it)
                }
                showShutterDialog = false
            },
            onDismiss = { showShutterDialog = false }
        )
    }

    if (showApertureDialog) {
        AppleParamPicker(
            title = "光圈 (F-Number)",
            options = when (tetherMode) {
                ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.availableApertures
                else -> selectedBrand.apertures
            },
            currentValue = when (tetherMode) {
                ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.aperture
                else -> wiredViewModel.aperture
            },
            onSelect = {
                when (tetherMode) {
                    ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.setAperture(it)
                    else -> wiredViewModel.setAperture(it)
                }
                showApertureDialog = false
            },
            onDismiss = { showApertureDialog = false }
        )
    }

    if (showIsoDialog) {
        AppleParamPicker(
            title = "ISO 感光度",
            options = when (tetherMode) {
                ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.availableIsos
                else -> selectedBrand.isos
            },
            currentValue = when (tetherMode) {
                ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.iso
                else -> wiredViewModel.iso
            },
            onSelect = {
                when (tetherMode) {
                    ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.setIso(it)
                    else -> wiredViewModel.setIso(it)
                }
                showIsoDialog = false
            },
            onDismiss = { showIsoDialog = false }
        )
    }

    if (showShootModeDialog) {
        AppleParamPicker(
            title = "拍摄模式",
            options = listOf("M (手动)", "A (光圈优先)", "S (快门优先)", "P (程序自动)"),
            currentValue = when (tetherMode) {
                ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.shootMode
                else -> wiredViewModel.shootMode
            },
            onSelect = {
                when (tetherMode) {
                    ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.setShootMode(it)
                    else -> wiredViewModel.setShootMode(it)
                }
                showShootModeDialog = false
            },
            onDismiss = { showShootModeDialog = false }
        )
    }

    // 3. 全屏 LiveView 监视弹窗
    if (showFullscreenLiveView) {
        com.lutcamera.lutcamerapro.ui.screens.monitor.FullscreenNikonPreview(
            frame = currentLiveFrame,
            lut = if (showLutFilterOnMonitor) lutViewModel.currentLutData else null,
            intensity = lutViewModel.lutIntensity,
            xmp = if (showLutFilterOnMonitor) lutViewModel.currentXmpData else null,
            warnings = showZebraWarning,
            onClose = { showFullscreenLiveView = false }
        )
    }

    // 4. 机身图库浏览弹窗
    if (showStorageDialog) {
        Dialog(onDismissRequest = { showStorageDialog = false }) {
            AppleCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "相机存储卡照片",
                        modifier = Modifier.weight(1f),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = apple.textPrimary
                    )
                    TextButton(enabled = isCameraConnected && !nikonViewModel.isLoadingStorageFiles && nikonViewModel.downloadingPhotoHandles.isEmpty(),
                        onClick = { nikonViewModel.loadStoragePhotos() }) { Text("刷新") }
                    IconButton(onClick = { showStorageDialog = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = apple.textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val photoList = nikonViewModel.cameraStoragePhotos
                if (nikonViewModel.isLoadingStorageFiles) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (nikonViewModel.storageDownloadMessage.isNotBlank()) {
                    Text(nikonViewModel.storageDownloadMessage, color = apple.textSecondary, fontSize = 12.sp)
                }
                if (isCameraConnected && !nikonViewModel.isLoadingStorageFiles && photoList.isEmpty()) {
                    Text("在相机回放中选一张照片，标记为上传至计算机，再点击下方按钮。此为传输验证，尚未确认 Z30 兼容性。", fontSize = 12.sp)
                    TextButton(onClick = { nikonViewModel.loadStoragePhotos(prepareTransfer = true) }) {
                        Text("已标记照片，重新读取")
                    }
                }
                if (photoList.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (!isCameraConnected) "请先连接相机" else if (nikonViewModel.isLoadingStorageFiles) "正在读取，请稍候…" else nikonViewModel.storageMessage.ifBlank { "暂无可显示文件" },
                            color = apple.textSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(photoList, key = { it.handle }) { file ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(apple.surfaceSecondary)
                                    .border(1.5.dp, apple.border, RoundedCornerShape(12.dp))
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text(
                                        text = file.fileName,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = apple.textPrimary
                                    )
                                    Text(
                                        text = file.sizeFormatted,
                                        fontSize = 11.sp,
                                        color = apple.textSecondary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    AppleButton(
                                        text = if (file.handle in nikonViewModel.downloadingPhotoHandles) "下载中…" else if (file.isDownloaded) "已保存" else "下载原图",
                                        enabled = isCameraConnected && !nikonViewModel.isLoadingStorageFiles && !file.isDownloaded && nikonViewModel.downloadingPhotoHandles.isEmpty(),
                                        colorStyle = if (file.isDownloaded) AppleButtonColor.SURFACE else AppleButtonColor.PRIMARY,
                                        height = 48.dp,
                                        fontSize = 11.sp,
                                        onClick = {
                                            nikonViewModel.downloadPhoto(file.handle) { success ->
                                                Toast.makeText(context, if (success) "原图已保存到手机相册" else "下载失败，请查看图库提示", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 5. 成品大图弹窗
    if (showPhotoDetailDialog && selectedPhotoForDetail != null) {
        Dialog(onDismissRequest = { showPhotoDetailDialog = false }) {
            AppleCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedPhotoTitle,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = apple.textPrimary
                    )
                    IconButton(onClick = { showPhotoDetailDialog = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = apple.textSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Image(
                    bitmap = selectedPhotoForDetail!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                )
            }
        }
    }

    // ================= 6. 连接说明书弹窗 =================
    if (showHelpDialog) {
        Dialog(onDismissRequest = { showHelpDialog = false }) {
            AppleCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp, max = 580.dp),
                backgroundColor = apple.surface
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = null,
                            tint = apple.accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "相机连接说明书",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = apple.textPrimary
                        )
                    }
                    IconButton(onClick = { showHelpDialog = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = apple.textSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                var selectedTab by remember { mutableStateOf(tetherMode) }
                AppleSegmentedControl(
                    items = ActiveTetherMode.entries,
                    selectedItem = selectedTab,
                    onItemSelected = { selectedTab = it },
                    labelProvider = { it.title }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    when (selectedTab) {
                        ActiveTetherMode.WIRELESS_NIKON -> {
                            Text("【尼康单反/微单 Wi-Fi 无线连接】", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = apple.accent)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("1. 相机开启 Wi-Fi：\n进入相机设定菜单 ->【网络/Wi-Fi】-> 选择【Wi-Fi 连接】或【连接至智能设备】并开启。", fontSize = 12.sp, color = apple.textPrimary)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("2. 手机连接 Wi-Fi：\n在手机系统 Wi-Fi 中连接尼康热点 (Nikon_xxxx)，输入相机设定的 WPA2 密码。", fontSize = 12.sp, color = apple.textPrimary)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("3. 点击连接：\n返回本 App 点击【立即连接】。默认 IP 为 192.168.1.1。本 App 将通过 PTP/IP 协议建立通信，实现实时取景与双向参数调控。", fontSize = 12.sp, color = apple.textPrimary)
                        }
                        ActiveTetherMode.USB_TETHER -> {
                            Text("【USB OTG 有线联机拍摄】", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = apple.accent)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("1. 硬件连接：\n使用 USB-C 转接线或 OTG 转接头连接手机与相机。", fontSize = 12.sp, color = apple.textPrimary)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("2. 相机 USB 模式：\n将相机 USB 模式设置为【电脑遥控】(PC Remote) 或【MTP / PTP】。", fontSize = 12.sp, color = apple.textPrimary)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("3. 授权连接：\n手机弹出'是否允许应用访问该 USB 设备'时，勾选并点击【确定】即可极速零延迟传输。", fontSize = 12.sp, color = apple.textPrimary)
                        }
                        ActiveTetherMode.SD_CARD -> {
                            Text("【SD 卡读卡器批量导入】", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = apple.accent)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("1. 插入读卡器：\n将相机 SD 卡通过 Type-C 读卡器插入手机。", fontSize = 12.sp, color = apple.textPrimary)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("2. 授权目录：\n点击【选择 SD 卡目录】，在系统文件选择器中选择 SD 卡的 DCIM 文件夹并授权。", fontSize = 12.sp, color = apple.textPrimary)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("3. 自动批处理：\n勾选照片即可一键批量应用 LUT 滤镜与复古边框装裱，保存至手机相册。", fontSize = 12.sp, color = apple.textPrimary)
                        }
                    }
                }
            }
        }
    }

    // ================= 7. 日志观察区弹窗 =================
    if (showLogs) {
        Dialog(onDismissRequest = { showLogs = false }) {
            AppleCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp, max = 560.dp),
                backgroundColor = apple.surface
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = apple.accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "通信日志 (${tetherMode.title})",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = apple.textPrimary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            when (tetherMode) {
                                ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.clearLogs()
                                else -> {}
                            }
                        }) {
                            Text("清空", fontSize = 12.sp, color = apple.accent)
                        }
                        IconButton(onClick = { showLogs = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = apple.textSecondary)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                val currentLogs = when (tetherMode) {
                    ActiveTetherMode.WIRELESS_NIKON -> nikonViewModel.logs
                    else -> emptyList()
                }

                if (currentLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("暂无日志记录", color = apple.textSecondary, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(currentLogs) { log ->
                            Text(
                                text = log,
                                fontSize = 11.sp,
                                color = apple.textSecondary,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 监视器悬浮小按钮
 */
@Composable
private fun MonitorMiniButton(
    text: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) activeColor else Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/**
 * 曝光参数胶囊卡片
 */
@Composable
private fun ParamAppleCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val apple = AppleTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(apple.surface)
            .border(2.dp, apple.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = apple.textSecondary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
