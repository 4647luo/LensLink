@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.lutcamera.lutcamerapro.ui.douk

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import com.lutcamera.lutcamerapro.log.DoukLog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Iso
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lutcamera.lutcamerapro.ui.screens.monitor.MonitorTextureView
import com.lutcamera.lutcamerapro.lut.LutData
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private enum class MonitorDeck { NONE, TOOLS, CAMERA }

/**
 * 直方图数据。四个数组按内容比较：data class 默认只比引用，
 * 每帧新数组会让「值没变」也判成变化，直方图面板因此被迫每 150ms 重组一次。
 */
@androidx.compose.runtime.Immutable
private class FrameAnalysis(
    val histR: IntArray? = null,
    val histG: IntArray? = null,
    val histB: IntArray? = null,
    val histL: IntArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (other !is FrameAnalysis) return false
        return histR.contentEqualsNullable(other.histR) &&
            histG.contentEqualsNullable(other.histG) &&
            histB.contentEqualsNullable(other.histB) &&
            histL.contentEqualsNullable(other.histL)
    }

    override fun hashCode(): Int {
        var result = histR?.contentHashCode() ?: 0
        result = 31 * result + (histG?.contentHashCode() ?: 0)
        result = 31 * result + (histB?.contentHashCode() ?: 0)
        result = 31 * result + (histL?.contentHashCode() ?: 0)
        return result
    }
}

private fun IntArray?.contentEqualsNullable(other: IntArray?): Boolean =
    if (this == null) other == null else other != null && contentEquals(other)

/** 全屏监视：点击画面对焦、顶部曝光参数、上下/左右滑出两组圆形工具。 */
@Composable
fun MonitorFullscreen(state: DoukState) {
    val wifiBmp by state.vm.liveViewBitmap.collectAsStateWithLifecycle()
    val usbBmp by state.usb.ptp.liveViewBitmap.collectAsStateWithLifecycle()
    // 模拟相机：真机不在时用合成画面顶替取景，只用于本地验收监看功能
    var mockBmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(MockCamera.active) {
        if (!MockCamera.active) {
            mockBmp = null
            return@LaunchedEffect
        }
        while (isActive) {
            mockBmp = MockCamera.render(android.os.SystemClock.uptimeMillis())
            delay(MockCamera.frameIntervalMs())
        }
    }
    val frame = when {
        MockCamera.active -> mockBmp
        state.usbConnected -> usbBmp
        else -> wifiBmp
    }

    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        val activity = view.context as? Activity
        val previousOrientation = activity?.requestedOrientation
        // 监看页强制横屏：手机竖着拿也按横屏排版，退出监看恢复原来的方向。
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 切到后台/退出监看时把取景还给机身：不占着 LiveView，相机屏幕能恢复显示；
    // 回到前台且还停在监看页时再重新取景。
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> state.vm.stopPreview()
                Lifecycle.Event.ON_START -> if (state.previewFullscreen && state.isConnected) state.vm.startPreview()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.vm.stopPreview()
        }
    }

    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp

    var deck by remember { mutableStateOf(MonitorDeck.NONE) }
    var lutOn by remember { mutableStateOf(false) }
    var histOn by remember { mutableStateOf(false) }
    var waveOn by remember { mutableStateOf(false) }
    var gridOn by remember { mutableStateOf(false) }
    var lutId by remember { mutableStateOf<String?>(null) }
    var lutEntries by remember { mutableStateOf<List<FilterEntry>>(emptyList()) }
    var lutRuntime by remember { mutableStateOf<FilterRuntime?>(null) }
    var lutLoading by remember { mutableStateOf(false) }
    var lutReload by remember { mutableIntStateOf(0) }
    var lutBrokenIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var analysis by remember { mutableStateOf(FrameAnalysis()) }
    var viewRotation by remember { mutableIntStateOf(0) }
    var mirror by remember { mutableStateOf(false) }
    var topCard by remember { mutableStateOf(false) }
    var viewCard by remember { mutableStateOf(false) }
    var pickerKey by remember { mutableStateOf<String?>(null) }
    var pickerAnchor by remember { mutableStateOf(IntOffset.Zero) }
    var focusMarker by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var focusTick by remember { mutableIntStateOf(0) }
    // 叠加层位置（px）：长按拖动后停在用户放下的位置，只有开启/关闭，不随点画面消失
    var histOffset by remember { mutableStateOf<Offset?>(null) }
    var waveOffset by remember { mutableStateOf<Offset?>(null) }
    // 左右栏自定义按钮
    var leftActions by remember {
        mutableStateOf(parseRailActions(DoukPrefs.monitorLeftActions, DEFAULT_LEFT_ACTIONS))
    }
    var rightActions by remember {
        mutableStateOf(parseRailActions(DoukPrefs.monitorRightActions, DEFAULT_RIGHT_ACTIONS))
    }
    var railConfigOpen by remember { mutableStateOf(false) }
    var qualityCardOpen by remember { mutableStateOf(false) }
    var paramToast by remember { mutableStateOf<String?>(null) }
    // 曝光监看 / 自动化：
    // falseColorOn 伪色、levelOn 水平仪、audioOn 音频电平、tcOn 时间码、
    // guideStyle 构图线样式（0=关闭）、autoOpen 间隔/包围/自拍面板
    var falseColorOn by remember { mutableStateOf(false) }
    // 峰值对焦：GPU 边缘检测，伪色开启时自动让位
    var peakingOn by remember { mutableStateOf(false) }
    var levelOn by remember { mutableStateOf(false) }
    var audioOn by remember { mutableStateOf(false) }
    var tcOn by remember { mutableStateOf(false) }
    var autoOpen by remember { mutableStateOf(false) }
    var guideStyle by remember { mutableIntStateOf(0) }
    var clockText by remember { mutableStateOf("") }
    var clockBusy by remember { mutableStateOf(false) }
    var tcText by remember { mutableStateOf("00:00:00:00") }
    // AF 锁定用可变状态包一层：手势协程里读的是最新的值，不需要重启 pointerInput
    val afLocked = remember { mutableStateOf(false) }
    val afLockBadge = remember { mutableStateOf<String?>(null) }
    // 本次手势是我们自己的「下划锁 AF」，就别再让同一串拖动去开工具箱
    val afDragSuppress = remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()

    val context = LocalContext.current
    val density = LocalDensity.current
    val latestFrame by rememberUpdatedState(frame)

    // 音频电平只读手机麦克风做参考，不录制：首次开启时才请求权限
    var audioGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val audioPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioGranted = granted
        if (!granted) {
            audioOn = false
            paramToast = "未授予麦克风权限，音频电平不可用"
        }
    }
    val audioMeter = rememberAudioLevel(audioOn && audioGranted, audioGranted)
    val tiltDeg = rememberTiltDegrees(levelOn)

    // 对焦点标记 1.8 秒后自己消失，和取景页一致
    LaunchedEffect(focusTick) {
        if (focusMarker != null) {
            delay(1800)
            focusMarker = null
        }
    }

    // AF 状态提示（已对焦 / 已锁定 / 已解锁）短提示，1.4 秒后自己消失
    LaunchedEffect(afLockBadge.value) {
        val badge = afLockBadge.value ?: return@LaunchedEffect
        delay(1400)
        if (afLockBadge.value == badge) afLockBadge.value = null
    }

    // 时间码：从打开显示（或切到录制）起累计，录制中前面亮红点
    LaunchedEffect(tcOn, state.isRecordingNow) {
        if (!tcOn) return@LaunchedEffect
        val base = android.os.SystemClock.elapsedRealtime()
        while (isActive) {
            tcText = formatTimecode(android.os.SystemClock.elapsedRealtime() - base, 30)
            delay(33)
        }
    }

    // 连接是异步的：进入监看后再连上、或取景中途掉帧，这里补一次启动/重启，
    // 避免停在「等待相机画面」或黑屏。
    LaunchedEffect(state.isConnected, state.previewFullscreen) {
        if (!state.previewFullscreen || !state.isConnected) return@LaunchedEffect
        delay(1200)
        if (!state.vm.ptpClient.isLiveViewActive.value) state.vm.startPreview()
        var lastFrame: Bitmap? = null
        var stalled = 0
        while (isActive && state.previewFullscreen) {
            delay(2500)
            val now = latestFrame
            if (now === lastFrame) {
                stalled++
                if (stalled >= 2 && state.isConnected) {
                    state.vm.stopPreview()
                    delay(500)
                    state.vm.startPreview()
                    stalled = 0
                }
            } else {
                lastFrame = now
                stalled = 0
            }
        }
    }

    // 参数下发结果不再静默：成功/失败都在画面下方弹一句
    var lastParamSeen by remember { mutableStateOf(state.vm.lastParamMessage) }
    LaunchedEffect(state.vm.lastParamMessage) {
        val message = state.vm.lastParamMessage
        if (message.isNotBlank() && message != lastParamSeen) {
            lastParamSeen = message
            paramToast = message
            delay(2600)
            if (paramToast == message) paramToast = null
        }
    }
    LaunchedEffect(state.lastParamHint) {
        val message = state.lastParamHint
        if (message.isNotBlank()) {
            paramToast = message
            delay(3200)
            if (paramToast == message) paramToast = null
        }
    }

    // LUT 只在打开监看工具或打开 LUT 时解析；每次打开都重读，资源页刚导入的文件立刻可见
    LaunchedEffect(deck, lutOn, lutReload) {
        if ((deck == MonitorDeck.TOOLS || lutOn) && !lutLoading) {
            lutLoading = true
            val loaded = withContext(Dispatchers.IO) { runCatching { FilterLibrary.load(context) }.getOrNull() }
            val runtime = loaded?.runtime
            val parsedIds = runtime?.luts?.keys ?: emptySet()
            // 不再按资源页标签过滤（以前 kind!="LUT" 就整条消失），改成扫目录里所有 LUT 后缀文件：
            // 能解析的直接可用，解析不了的也列出来并标「解析失败」，避免用户看到空列表。
            val extSet = setOf("cube", "3dl", "look", "np3", "ncp", "np2")
            val files = withContext(Dispatchers.IO) { DoukPrefs.importedResources(context) }
                .filter { it.extension.lowercase() in extSet }
            val broken = HashSet<String>()
            val builtIns = loaded?.entries
                ?.filter { it.id.startsWith("builtin:") }
                ?.takeIf { it.isNotEmpty() }
                ?: emptyList()
            // 以前这里只按导入文件重建列表，内置 N-Log / HLG 还原被整个丢掉，
            // 用户没导入文件时就看到空列表。现在内置项始终排在前面。
            lutEntries = builtIns + files.map { file ->
                val id = "res:${file.name}"
                val ok = id in parsedIds
                if (!ok) broken += id
                FilterEntry(
                    id = id,
                    label = file.nameWithoutExtension + if (ok) "" else "（解析失败）",
                    category = DoukPrefs.filterCategory(file.name),
                    kind = DoukPrefs.resourceKind(file.name),
                    use = DoukPrefs.resourceUse(file.name)
                )
            }
            lutBrokenIds = broken
            lutRuntime = runtime
            lutLoading = false
            com.lutcamera.lutcamerapro.log.DoukLog.i(
                "Mon", "LUT_SCAN",
                "files" to (loaded?.entries?.size ?: 0).toString(),
                "parsed" to parsedIds.size.toString()
            )
        }
    }

    // 拍摄档不显示 / 不保留 LUT；视频档才按色调模式自动挑内置还原 LUT。
    LaunchedEffect(state.effectiveMode, state.movieToneMode, lutEntries) {
        if (state.effectiveMode != CaptureMode.VIDEO) {
            lutOn = false
            if (qualityCardOpen) qualityCardOpen = false
            return@LaunchedEffect
        }
        val current = lutId
        if (current == null || current == "builtin:nlog" || current == "builtin:hlg") {
            val target = when (state.movieToneMode) {
                "N-Log" -> "builtin:nlog"
                "HLG" -> "builtin:hlg"
                else -> null
            }
            lutId = target
            // 选 N-Log / HLG 就默认套上对应还原 LUT；回到 SDR 自动关掉。
            lutOn = target != null
        }
    }

    // 直方图 / 波形 / LUT 预览：统一在 IO 线程低频计算，不跟着 30fps 取景帧跑
    var lastAnalyzedFrame: Bitmap? = null
    LaunchedEffect(Unit) {
        while (isActive) {
            val frameBmp = latestFrame
            val bmp = frameBmp?.asImageBitmap()
            // 伪色 / LUT / 波形都已经在 GL 着色器里跑，CPU 这边只剩直方图
            val anyOn = histOn
            // 同一帧不重复算：30fps 取景时缓存命中能省掉绝大部分 CPU 开销，
            // 也是监看不烫手、不掉电的关键之一。
            val sameFrame = frameBmp != null && frameBmp === lastAnalyzedFrame
            if (bmp != null && anyOn && !sameFrame) {
                lastAnalyzedFrame = frameBmp
                analysis = withContext(Dispatchers.IO) { analyzeFrame(bmp) }
            } else if (!anyOn) {
                lastAnalyzedFrame = null
                analysis = FrameAnalysis()
            }
            delay(if (anyOn) 150L else 900L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val rootW = constraints.maxWidth
            val rootH = constraints.maxHeight
            val railLeftWidth = 90.dp
            val railRightWidth = 104.dp
            val railLeftPx = with(density) { railLeftWidth.roundToPx() }
            val railRightPx = with(density) { railRightWidth.roundToPx() }
            // 横屏工具卡按实际屏宽铺开：手机/平板右边都不会再空一块，列数随宽度增加到 4–6 列。
            val screenWdp = with(density) { rootW.toDp() }
            val deckWidth = if (landscape) {
                (screenWdp - railLeftWidth - railRightWidth - 16.dp).coerceIn(360.dp, 640.dp)
            } else 0.dp
            val deckColumns = if (landscape) {
                ((deckWidth - 24.dp) / 86.dp).toInt().coerceIn(4, 6)
            } else 3
            val sourceBmp = frame
            val source = remember(sourceBmp) { sourceBmp?.asImageBitmap() }
            // 监看 LUT 交给 GL 渲染：把内部 Lut3D 包成 LutData，只在 LUT 切换时才重建，
            // 避免每帧重组都重新上传一张 3D 纹理
            val monitorLutData = remember(lutRuntime, lutId, lutOn) {
                if (!lutOn) {
                    null
                } else {
                    lutId?.let { id -> lutRuntime?.lut(id) }
                        ?.let { LutData("监看预览", it.size, it.data) }
                }
            }
            // 取景画面按相机实际输出的方向显示，不再按手机横竖自动旋转；
            // 需要旋转时由用户在「视图 → 旋转视图 90°」里手动切换，避免同一场景忽横忽竖。
            val baseRotation = 0f
            val rotation = (baseRotation + viewRotation) % 360f
            val side = abs(rotation) % 180f > 1f
            val srcW = source?.width ?: 1
            val srcH = source?.height ?: 1
            val visW = if (side) srcH else srcW
            val visH = if (side) srcW else srcH
            // 横屏时左右侧栏占掉自己的宽度，取景画面只在中间区域里等比缩放，
            // 不再铺到侧栏底下被按钮挡住。
            val contentW = if (landscape) (rootW - railLeftPx - railRightPx).coerceAtLeast(1) else rootW
            val scale = min(contentW.toFloat() / visW, rootH.toFloat() / visH)
            val dispWpx = (visW * scale).toInt().coerceAtLeast(1)
            val dispHpx = (visH * scale).toInt().coerceAtLeast(1)

            // 点击坐标 -> 取景源坐标：先反镜像，再反旋转
            fun mapTap(pos: androidx.compose.ui.geometry.Offset): Pair<Float, Float>? {
                if (source == null) return null
                val left = (rootW - dispWpx) / 2f
                val top = (rootH - dispHpx) / 2f
                var vx = (pos.x - left) / dispWpx - 0.5f
                val vy = (pos.y - top) / dispHpx - 0.5f
                if (vx < -0.6f || vx > 0.6f || vy < -0.6f || vy > 0.6f) return null
                if (mirror) vx = -vx
                val rad = Math.toRadians(rotation.toDouble())
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                val sx = vx * c + vy * s
                val sy = -vx * s + vy * c
                return (sx + 0.5f).coerceIn(0f, 1f) to (sy + 0.5f).coerceIn(0f, 1f)
            }

            if (source != null) {
                // 波形窗口在画面坐标系里的归一化大小（GL 里按 viewport 画，不依赖 Compose 布局）
                val waveRectW = with(density) { 220.dp.roundToPx() }.toFloat() / dispWpx
                val waveRectH = with(density) { 116.dp.roundToPx() }.toFloat() / dispHpx
                val glRotation = ((rotation.toInt() % 360) + 360) % 360
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .requiredSize(
                            with(density) { dispWpx.toDp() },
                            with(density) { dispHpx.toDp() }
                        )
                ) {
                    AndroidView(
                        factory = { ctx -> MonitorTextureView(ctx) },
                        modifier = Modifier.fillMaxSize(),
                        update = { view ->
                            // 取景帧直接进 GL 纹理；LUT / 伪色 / 旋转镜像 / 波形全在着色器里跑
                            frame?.let { view.updateLiveFrame(it) }
                            val previewLut = monitorLutData
                            view.setLut(previewLut, 1f)
                            view.setFalseColor(falseColorOn)
                            view.setPeaking(peakingOn && !falseColorOn)
                            // SurfaceView 不吃 graphicsLayer，旋转/镜像交给着色器反解 UV
                            view.setUvTransform(glRotation, mirror)
                            // 波形位置：叠加层的屏幕坐标换算成画面内的归一化矩形
                            val left = (rootW - dispWpx) / 2f
                            val top = (rootH - dispHpx) / 2f
                            val rect = waveOffset?.let { off ->
                                floatArrayOf(
                                    (off.x - left) / dispWpx,
                                    (off.y - top) / dispHpx,
                                    waveRectW,
                                    waveRectH
                                )
                            }
                            view.setWaveform(waveOn && rect != null, rect)
                            view.setWarnings(false, false)
                        }
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (state.isConnected) "正在获取取景画面…" else "等待相机画面",
                        style = DoukType.body,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            // 构图辅助线：三分 / 三分+十字 / 对角线 / 安全框 / 中心十字，统一走这里画
            if (guideStyle > 0) {
                CompositionGuides(
                    style = guideStyle,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = if (landscape) railLeftWidth else 0.dp,
                            end = if (landscape) railRightWidth else 0.dp
                        )
                )
            }

            // 手势层：上滑/左滑=监看工具，下滑/右滑=相机参数（竖横构图都可用）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // AF 手势：按住对焦 → 继续下滑锁定 AF-L → 已锁定时点一下解除。
                    // 和机身「半按 → AF-L → 再按」的顺序一致，比点击对焦更接近实拍手感。
                    .pointerInput(source, rotation, mirror, dispWpx, dispHpx, deck) {
                        if (source == null) return@pointerInput
                        val lockThreshold = 56.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (deck != MonitorDeck.NONE) return@awaitEachGesture
                            // 已锁定：单击即解除，不用再长按
                            if (afLocked.value) {
                                afLocked.value = false
                                afDragSuppress.value = false
                                afLockBadge.value = "AF 解锁"
                                focusMarker = null
                                // awaitEachGesture 会在块末自行等待手指抬起，这里不用再等 up
                                return@awaitEachGesture
                            }
                            // 按住到长按阈值才下发对焦；快速点击与滑动手势在这里直接放回给下面
                            val hold = awaitLongPressOrCancellation(down.id)
                            if (hold == null) return@awaitEachGesture
                            mapTap(hold.position)?.let { (nx, ny) ->
                                state.vm.setFocusPoint(nx, ny)
                                focusMarker = hold.position
                                focusTick++
                                afLockBadge.value = "AF 已对焦"
                            }
                            var locked = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) break
                                val dy = change.position.y - hold.position.y
                                if (!locked && dy > lockThreshold) {
                                    locked = true
                                    afLocked.value = true
                                    afDragSuppress.value = true
                                    afLockBadge.value = "AF 已锁定"
                                }
                                // 锁 AF 的下滑由这里吞掉，避免同一串拖动再被下面判定成开工具箱
                                if (dy > 0f) change.consume()
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        var dx = 0f
                        var dy = 0f
                        detectDragGestures(
                            onDragStart = { dx = 0f; dy = 0f },
                            onDragCancel = { dx = 0f; dy = 0f },
                            onDragEnd = {
                                if (afDragSuppress.value) {
                                    afDragSuppress.value = false
                                    dx = 0f; dy = 0f
                                    return@detectDragGestures
                                }
                                val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                                if (deck == MonitorDeck.NONE) {
                                    if (horizontal) {
                                        if (dx < -90f) deck = MonitorDeck.TOOLS
                                        else if (dx > 90f) deck = MonitorDeck.CAMERA
                                    } else {
                                        if (dy < -90f) deck = MonitorDeck.TOOLS
                                        else if (dy > 90f) deck = MonitorDeck.CAMERA
                                    }
                                } else {
                                    // 左滑出工具 → 右滑收起；右滑出参数 → 左滑收起。
                                    // 竖向也保留原来的上/下滑对称收起，不再只能往一个方向关。
                                    val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                                    if (deck == MonitorDeck.TOOLS) {
                                        if ((horizontal && dx > 70f) || (!horizontal && dy > 70f)) {
                                            deck = MonitorDeck.NONE
                                        }
                                    } else if (deck == MonitorDeck.CAMERA) {
                                        if ((horizontal && dx < -70f) || (!horizontal && dy < -70f)) {
                                            deck = MonitorDeck.NONE
                                        }
                                    }
                                }
                                dx = 0f; dy = 0f
                            }
                        ) { change, dragAmount ->
                            dx += dragAmount.x
                            dy += dragAmount.y
                            change.consume()
                        }
                    }
                    .pointerInput(source, rotation, mirror, dispWpx, dispHpx, deck) {
                        if (deck != MonitorDeck.NONE) {
                            // 面板划出来后：单击什么都不做，双击才收起。
                            // 单独一段 pointerInput 是为了不给「点画面对焦」加双击等待延迟。
                            detectTapGestures(
                                onTap = {
                                    // 双击的第一步也允许直接收起：面板打开时点一下画面就关，
                                    // 双击/单击都能取消工具箱，不会出现「点了没反应」。
                                    DoukLog.i("Mon", "TAP_CLOSE_DECK", "deck" to deck.name)
                                    deck = MonitorDeck.NONE
                                },
                                onDoubleTap = {
                                    DoukLog.i("Mon", "DOUBLE_TAP_CLOSE_DECK", "deck" to deck.name)
                                    deck = MonitorDeck.NONE
                                }
                            )
                        } else {
                            detectTapGestures { pos ->
                                // 点画面只收起当前打开的面板/参数选择器，不再把直方图、
                                // 波形、音频这些监看叠加层一起关掉——它们只在「监看工具」里关。
                                if (railConfigOpen) {
                                    railConfigOpen = false
                                } else if (topCard || viewCard || pickerKey != null) {
                                    topCard = false
                                    viewCard = false
                                    pickerKey = null
                                } else {
                                    mapTap(pos)?.let { (nx, ny) ->
                                        state.vm.setFocusPoint(nx, ny)
                                        focusMarker = pos
                                        focusTick++
                                    }
                                }
                            }
                        }
                    }
            )

            // 面板打开时的「点一下就收起」接收层：
            // 面板自己的 AnimatedVisibility 会盖住手势层，所以单靠画面上的
            // pointerInput 收不到点击；这里在全屏铺一层透明的点击层，
            // 单击/双击都收起面板，面板卡片本身叠在它上面，按钮照常可点。
            if (deck != MonitorDeck.NONE) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 单击/双击收起保留；同时接收拖动，保证「左滑出工具后右滑能关」，
                        // 反过来「右滑出参数后左滑能关」。以前这里只处理 tap，横滑事件被吃掉，
                        // 所以用户看到工具箱拉出来以后滑不回去。
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            DoukLog.i("Mon", "TAP_CLOSE_DECK", "deck" to deck.name)
                            deck = MonitorDeck.NONE
                        }
                        .pointerInput(deck) {
                            var dx = 0f
                            var dy = 0f
                            detectDragGestures(
                                onDragStart = { dx = 0f; dy = 0f },
                                onDragCancel = { dx = 0f; dy = 0f },
                                onDragEnd = {
                                    val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                                    if (deck == MonitorDeck.TOOLS) {
                                        if ((horizontal && dx > 70f) || (!horizontal && dy > 70f)) {
                                            deck = MonitorDeck.NONE
                                        }
                                    } else if (deck == MonitorDeck.CAMERA) {
                                        if ((horizontal && dx < -70f) || (!horizontal && dy < -70f)) {
                                            deck = MonitorDeck.NONE
                                        }
                                    }
                                    dx = 0f; dy = 0f
                                }
                            ) { change, dragAmount ->
                                dx += dragAmount.x
                                dy += dragAmount.y
                                change.consume()
                            }
                        }
                )
            }


            focusMarker?.let { marker ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offsetPx(marker.x.toInt(), marker.y.toInt())
                        .size(64.dp)
                        .border(2.dp, Color(0xFF9BFF6A), RoundedCornerShape(8.dp))
                )
            }

            // 曝光参数与自定义按钮分到左右两栏：中间整块留给取景画面
            // 档位优先跟相机实际状态（机身拨到视频档时自动切过来），不让界面一直停在「拍照」
            val video = state.effectiveMode == CaptureMode.VIDEO
            fun expValue(still: String, movie: String): String {
                // 视频档只读视频那一套：视频参数没上报就显示「—」，
                // 不再回退到拍照档的快门/光圈/ISO，否则切换档位后页面上会挂着拍照的旧值。
                if (video) {
                    val m = state.valueOf(movie)
                    return if (m.isBlank()) "—" else m
                }
                val s = state.valueOf(still)
                return if (s.isBlank()) "—" else s
            }
            val isoText = expValue("iso", "movieIso")
            val shutterText = expValue("shutter", "movieShutter")
            val apertureText = expValue("aperture", "movieAperture")
            val evText = expValue("ev", "movieEv")
            val meteringText = expValue("metering", "movieMetering")

            val histWpx = with(density) { 200.dp.roundToPx() }
            val histHpx = with(density) { 96.dp.roundToPx() }
            val waveWpx = with(density) { 220.dp.roundToPx() }
            val waveHpx = with(density) { 116.dp.roundToPx() }
            val edge = with(density) { 10.dp.roundToPx() }

            // 首次进入/横竖切换时把叠加层放到画面内的默认角落，之后用户长按拖到哪就停在哪
            // 注意波形窗口现在由 GPU 画在取景画面里，默认位置必须落在画面矩形内，
            // 否则会跑到画面外的黑边上
            LaunchedEffect(rootW, rootH, dispWpx, dispHpx) {
                val viewLeft = (rootW - dispWpx) / 2
                val viewTop = (rootH - dispHpx) / 2
                histOffset = Offset((viewLeft + edge).toFloat(), (viewTop + edge).toFloat())
                waveOffset = Offset(
                    (viewLeft + edge).toFloat(),
                    (viewTop + edge + histHpx + edge).toFloat()
                )
            }

            // 构图线依次循环：关 → 三分 → 三分+十字 → 对角线 → 安全框 → 中心十字
            fun cycleGuide() {
                guideStyle = (guideStyle + 1) % (GUIDE_STYLE_LABELS.size + 1)
                gridOn = guideStyle > 0
            }

            // 校时：读一次机身时间 → 写入手机时间 → 再读回确认
            fun syncClock() {
                if (clockBusy) return
                clockBusy = true
                uiScope.launch {
                    val before = state.readCameraClock()
                    val ok = state.syncCameraClock()
                    val after = if (ok) state.readCameraClock() else null
                    clockBusy = false
                    clockText = after ?: ""
                    paramToast = when {
                        !state.isConnected -> "相机未连接，无法校时"
                        !ok -> "机身拒绝写入时钟，请用机身菜单手动对时"
                        after != null -> "机身时钟已同步：$after"
                        before != null -> "已写入手机时间（原为 $before）"
                        else -> "已写入手机时间"
                    }
                }
            }

            fun toggleAudio() {
                if (audioOn) {
                    audioOn = false
                } else if (audioGranted) {
                    audioOn = true
                } else {
                    paramToast = "麦克风仅用于监看音频电平参考，不录音、不上传"
                    audioPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }

            fun onRailAction(id: String) {
                when (id) {
                    "grid" -> cycleGuide()
                    "falseCol" -> {
                        falseColorOn = !falseColorOn
                        if (falseColorOn) lutOn = false
                    }
                    "level" -> levelOn = !levelOn
                    "audio" -> toggleAudio()
                    "tc" -> tcOn = !tcOn
                    "auto" -> autoOpen = !autoOpen
                    "clock" -> syncClock()
                    "guide" -> cycleGuide()
                    "hist" -> histOn = !histOn
                    "wave" -> waveOn = !waveOn
                    "lut" -> {
                        if (state.effectiveMode == CaptureMode.VIDEO) {
                            lutOn = !lutOn
                            if (lutOn) deck = MonitorDeck.TOOLS
                        }
                    }
                    "focus" -> state.vm.triggerAf()
                    "quality" -> qualityCardOpen = !qualityCardOpen
                    "view" -> { topCard = true; viewCard = true }
                    "rails" -> railConfigOpen = true
                }
            }

            if (landscape) {
                // 左栏：参数 + 自定义按钮
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(railLeftWidth)
                        .fillMaxHeight()
                        .background(Color(0xB80B0C0E))
                        .padding(horizontal = 5.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable { state.previewFullscreen = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Close, "退出全屏", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Text("左栏", color = Color.White.copy(alpha = 0.42f), fontSize = 9.sp)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        leftActions.filter { railVisibleInMode(it, video) }.forEach { id ->
                            RailActionItem(
                                id = id,
                                state = state,
                                video = video,
                                active = railActionActive(
                                    id, histOn, waveOn, gridOn,
                                    perspectiveOn = viewCard,
                                    lutOn = lutOn,
                                    falseColorOn = falseColorOn,
                                    levelOn = levelOn,
                                    audioOn = audioOn,
                                    tcOn = tcOn,
                                    autoOpen = autoOpen
                                ),
                                onOpen = { key, anchor -> pickerAnchor = anchor; pickerKey = key },
                                onAction = ::onRailAction
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // 左下角档位胶囊：点一下就在拍摄/视频之间切换，工具与参数面板跟着换
                    MonitorModePill(video = video, compact = true, onToggle = {
                        state.selectMode(if (video) CaptureMode.STILL else CaptureMode.VIDEO)
                    })
                }

                // 右栏：功能一行一个，快门放在剩余空间正中，互不遮挡
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(railRightWidth)
                        .fillMaxHeight()
                        .background(Color(0xB80B0C0E))
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("右栏", color = Color.White.copy(alpha = 0.42f), fontSize = 9.sp)
                    Spacer(Modifier.height(2.dp))
                    // 常驻「相机控制」按钮：不用记左右滑手势，点一下就打开参数面板，
                    // 取景不退出，调完参数继续拍。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF3A2C18).copy(alpha = 0.92f))
                            .border(
                                1.dp,
                                Color(0xFFFFB15C).copy(alpha = 0.35f),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                // 点「相机控制」只开参数面板，不再弹“左右划显示控制工具箱”之类的提示卡。
                                paramToast = null
                                state.lastParamHint = ""
                                deck = MonitorDeck.CAMERA
                            }
                            .padding(vertical = 6.dp, horizontal = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Tune,
                            contentDescription = "相机控制",
                            tint = Color(0xFFFFC27A),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("相机控制", color = Color(0xFFFFD9AE), fontSize = 9.sp, maxLines = 1)
                    }
                    Spacer(Modifier.height(6.dp))
                    // 所有右栏按钮放进可滚动区域：手机上即使配了七八个参数也不会把快门挤掉，
                    // 也避免最底部按钮被系统手势条裁掉（这就是“显示不全”的来源）。
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        rightActions
                            .filter { railVisibleInMode(it, video) }
                            .forEach { id ->
                                Box(Modifier.fillMaxWidth()) {
                                    RailActionItem(
                                        id = id,
                                        state = state,
                                        video = video,
                                        active = railActionActive(
                                    id, histOn, waveOn, gridOn,
                                    perspectiveOn = viewCard,
                                    lutOn = lutOn,
                                    falseColorOn = falseColorOn,
                                    levelOn = levelOn,
                                    audioOn = audioOn,
                                    tcOn = tcOn,
                                    autoOpen = autoOpen
                                ),
                                        onOpen = { key, anchor -> pickerAnchor = anchor; pickerKey = key },
                                        onAction = ::onRailAction
                                    )
                                }
                            }
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .size(74.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(
                                    if (video) Color(0xFFE5484D) else DoukColors.accent
                                )
                                .clickable { state.shoot() }
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { qualityCardOpen = !qualityCardOpen }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Speed,
                            contentDescription = "屏幕画质",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "画质 ${state.previewQuality}",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                    }
                }
            } else {
                // 横屏锁定生效前的一帧兜底：仍然把参数放在顶部，避免完全不可用
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xCC111214))
                        .clickable { topCard = !topCard }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(isoText, color = Color.White, fontSize = 12.sp)
                    Text(shutterText, color = Color.White, fontSize = 12.sp)
                    Text(apertureText, color = Color.White, fontSize = 12.sp)
                    Text(evText, color = Color(0xFFB8FF8A), fontSize = 12.sp)
                    Text(meteringText, color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp)
                }
            }

            AnimatedVisibility(
                visible = topCard,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 56.dp, end = if (landscape) railRightWidth + 8.dp else 16.dp),
                enter = fadeIn(animationSpec = tween(150)) + slideInVertically(animationSpec = tween(180)) { -it / 4 },
                exit = fadeOut(animationSpec = tween(130)) + slideOutVertically(animationSpec = tween(150)) { -it / 4 }
            ) {
                Column(
                    modifier = Modifier
                        .width(238.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xF217181B))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("曝光", style = DoukType.caption, color = Color.White.copy(alpha = 0.62f))
                        Spacer(Modifier.weight(1f))
                        Icon(
                            Icons.Rounded.Close, null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .clickable { topCard = false }
                        )
                    }
                    ExposureLine("ISO", isoText, onAnchor = { pickerAnchor = it; pickerKey = if (video) "movieIso" else "iso" })
                    ExposureLine("快门", shutterText, onAnchor = { pickerAnchor = it; pickerKey = if (video) "movieShutter" else "shutter" })
                    ExposureLine("光圈", apertureText, onAnchor = { pickerAnchor = it; pickerKey = if (video) "movieAperture" else "aperture" })
                    ExposureLine("曝光补偿", evText, onAnchor = { pickerAnchor = it; pickerKey = if (video) "movieEv" else "ev" })
                    Spacer(Modifier.height(8.dp))
                    Text("拍摄状态", style = DoukType.caption, color = Color.White.copy(alpha = 0.62f))
                    MonitorStatusLine(if (video) "视频格式" else "拍摄格式", state.captureFormatText)
                    MonitorStatusLine("分辨率", state.captureResolutionText)
                    MonitorStatusLine("对焦模式", state.focusModeText)
                    MonitorStatusLine("测光模式", state.meteringModeText)
                    if (video) MonitorStatusLine("色调模式", state.movieToneMode)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { viewCard = !viewCard }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.RotateRight, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("视图", color = Color.White, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text(
                            buildString {
                                append(if (viewRotation == 0) "标准" else "旋转 ${viewRotation}°")
                                if (mirror) append(" · 镜像")
                            },
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 11.sp
                        )
                    }
                    AnimatedVisibility(
                        visible = viewCard,
                        enter = fadeIn(animationSpec = tween(140)),
                        exit = fadeOut(animationSpec = tween(120))
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF232529))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { viewRotation = (viewRotation + 90) % 360 }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.RotateRight, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("旋转视图 90°", color = Color.White, fontSize = 13.sp)
                                Spacer(Modifier.weight(1f))
                                Text("${viewRotation}°", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { mirror = !mirror }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Flip, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("对称视图", color = Color.White, fontSize = 13.sp)
                                Spacer(Modifier.weight(1f))
                                Text(if (mirror) "开" else "关", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { gridOn = !gridOn }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.GridOn, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("三分构图网格", color = Color.White, fontSize = 13.sp)
                                Spacer(Modifier.weight(1f))
                                Text(if (gridOn) "开" else "关", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // 左 / 上滑出的创作工具
            val toolSheetVisible = deck == MonitorDeck.TOOLS
            val toolEnter = if (landscape) {
                slideInHorizontally(animationSpec = tween(180)) { it } + fadeIn(animationSpec = tween(150))
            } else {
                slideInVertically(animationSpec = tween(180)) { it } + fadeIn(animationSpec = tween(150))
            }
            val toolExit = if (landscape) {
                slideOutHorizontally(animationSpec = tween(150)) { it } + fadeOut(animationSpec = tween(130))
            } else {
                slideOutVertically(animationSpec = tween(150)) { it } + fadeOut(animationSpec = tween(130))
            }
            AnimatedVisibility(
                visible = toolSheetVisible,
                modifier = Modifier
                    .align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
                    .then(if (landscape) Modifier.fillMaxHeight() else Modifier.navigationBarsPadding())
                    .padding(bottom = if (landscape) 0.dp else 112.dp)
                    .padding(end = if (landscape) railRightWidth else 0.dp)
                    .padding(if (landscape) 0.dp else 8.dp),
                enter = toolEnter,
                exit = toolExit
            ) {
                MonitorDeckCard(
                    title = "监看工具",
                    onClose = { deck = MonitorDeck.NONE },
                    columns = deckColumns,
                    landscape = landscape,
                    deckWidth = if (landscape) deckWidth else null,
                    footer = {
                        // LUT 列表直接嵌在工具卡内部，不再额外浮一张 330dp 的卡片盖住右侧工具；
                        // 手机横屏高度不够时，跟随外层一起滚动，不会显示不全。
                        if (video && lutOn) {
                            MonitorLutSection(
                                loading = lutLoading,
                                entries = lutEntries,
                                selectedId = lutId,
                                brokenIds = lutBrokenIds,
                                onReload = { lutReload++ },
                                onSelectNone = {
                                    lutId = null
                                    lutOn = false
                                },
                                onSelect = { entry ->
                                    if (entry.id in lutBrokenIds) {
                                        val hint = "「${entry.label}」解析失败：请确认文件是标准 .cube（含 LUT_3D_SIZE 或 LUT_1D_SIZE，且数值条数完整）。"
                                        paramToast = hint
                                        uiScope.launch {
                                            delay(3600)
                                            if (paramToast == hint) paramToast = null
                                        }
                                    } else {
                                        lutId = entry.id
                                        lutOn = true
                                    }
                                }
                            )
                        }
                    }
                ) {
                    if (video) {
                        MonitorTool("LUT", Icons.Rounded.PhotoFilter, lutOn) {
                            lutOn = !lutOn
                            if (!lutOn) deck = MonitorDeck.TOOLS
                        }
                        // 相机内「显示原图」：一键关掉 LUT，直接看相机原始画面。
                        // 以前只藏在 LUT 列表的「无」选项里，入口不够明显。
                        MonitorTool("原图", Icons.Rounded.Visibility, !lutOn) {
                            lutOn = false
                            deck = MonitorDeck.TOOLS
                        }
                        // 视频色调模式：不同文件类型只在这几种还原里选，避免和照片工具混在一起。
                        MonitorTool(
                            label = "色调 ${state.movieToneMode}",
                            icon = Icons.Rounded.ColorLens,
                            active = state.movieToneMode != "SDR",
                            anchorAware = { pickerAnchor = it; pickerKey = "movieToneMode" }
                        )
                    }
                    MonitorTool("直方图", Icons.Rounded.BarChart, histOn) { histOn = !histOn }
                    MonitorTool("波形图", Icons.Rounded.ShowChart, waveOn) { waveOn = !waveOn }
                    MonitorTool("伪色", Icons.Rounded.Palette, falseColorOn) {
                        falseColorOn = !falseColorOn
                        // 伪色会整屏换色，和 LUT 预览抢同一张画面，开伪色就顺手关掉 LUT
                        if (falseColorOn) lutOn = false
                    }
                    MonitorTool("峰值对焦", Icons.Rounded.BlurOn, peakingOn) {
                        peakingOn = !peakingOn
                        // 峰值是边缘染色，伪色是整屏换色，两个同时开看不清
                        if (peakingOn) falseColorOn = false
                    }
                    MonitorTool("水平仪", Icons.Rounded.Straighten, levelOn) { levelOn = !levelOn }
                    MonitorTool(
                        label = "音频电平",
                        icon = Icons.Rounded.GraphicEq,
                        active = audioOn
                    ) { toggleAudio() }
                    MonitorTool("时间码", Icons.Rounded.AccessTime, tcOn) { tcOn = !tcOn }
                    MonitorTool(
                        label = "构图线 " + if (guideStyle > 0) GUIDE_STYLE_LABELS[guideStyle - 1] else "关",
                        icon = Icons.Rounded.GridOn,
                        active = guideStyle > 0
                    ) { cycleGuide() }
                    MonitorTool("间隔/包围", Icons.Rounded.Timelapse, autoOpen) { autoOpen = !autoOpen }
                    MonitorTool("相机时钟", Icons.Rounded.Schedule, false) { syncClock() }
                    MonitorTool("预览画质", Icons.Rounded.Speed, false, onClick = {
                        qualityCardOpen = !qualityCardOpen
                        if (qualityCardOpen) deck = MonitorDeck.NONE
                    })
                    MonitorTool("左右栏设置", Icons.Rounded.Tune, false) { railConfigOpen = true }
                }
            }

            // 右 / 下滑出的相机参数
            AnimatedVisibility(
                visible = deck == MonitorDeck.CAMERA,
                modifier = Modifier
                    .align(if (landscape) Alignment.CenterStart else Alignment.TopCenter)
                    .then(if (landscape) Modifier.fillMaxHeight() else Modifier.statusBarsPadding())
                    .padding(top = if (landscape) 0.dp else 64.dp)
                    .padding(start = if (landscape) railLeftWidth else 0.dp),
                enter = if (landscape) {
                    slideInHorizontally(animationSpec = tween(180)) { -it } + fadeIn(animationSpec = tween(150))
                } else {
                    slideInVertically(animationSpec = tween(180)) { -it } + fadeIn(animationSpec = tween(150))
                },
                exit = if (landscape) {
                    slideOutHorizontally(animationSpec = tween(150)) { -it } + fadeOut(animationSpec = tween(130))
                } else {
                    slideOutVertically(animationSpec = tween(150)) { -it } + fadeOut(animationSpec = tween(130))
                }
            ) {
                MonitorDeckCard(
                    title = "相机参数",
                    onClose = { deck = MonitorDeck.NONE },
                    columns = deckColumns,
                    landscape = landscape,
                    deckWidth = if (landscape) deckWidth else null
                ) {
                    MonitorTool("ISO", Icons.Rounded.Iso, false, anchorAware = { pickerAnchor = it; pickerKey = if (video) "movieIso" else "iso" })
                    MonitorTool("快门", Icons.Rounded.Timer, false, anchorAware = { pickerAnchor = it; pickerKey = if (video) "movieShutter" else "shutter" })
                    MonitorTool("光圈", Icons.Rounded.PhotoFilter, false, anchorAware = { pickerAnchor = it; pickerKey = if (video) "movieAperture" else "aperture" })
                    if (video) {
                        MonitorTool("测光", Icons.Rounded.LightMode, false, anchorAware = { pickerAnchor = it; pickerKey = "movieMetering" })
                        MonitorTool("对焦", Icons.Rounded.CenterFocusStrong, false, anchorAware = { pickerAnchor = it; pickerKey = "movieFocusMode" })
                        MonitorTool("AF 区域", Icons.Rounded.CropFree, false, anchorAware = { pickerAnchor = it; pickerKey = "movieAfAreaMode" })
                        MonitorTool("白平衡", Icons.Rounded.WbSunny, false, anchorAware = { pickerAnchor = it; pickerKey = "movieWb" })
                        MonitorTool("画面尺寸", Icons.Rounded.CropFree, false, anchorAware = { pickerAnchor = it; pickerKey = "movieScreenSize" })
                        MonitorTool("视频画质", Icons.Rounded.Star, false, anchorAware = { pickerAnchor = it; pickerKey = "movieQuality" })
                        MonitorTool("文件类型", Icons.Rounded.Movie, false, anchorAware = { pickerAnchor = it; pickerKey = "movieFileType" })
                        MonitorTool("麦克风", Icons.Rounded.GraphicEq, false, anchorAware = { pickerAnchor = it; pickerKey = "movieMic" })
                        MonitorTool("闪烁消减", Icons.Rounded.Bolt, false, anchorAware = { pickerAnchor = it; pickerKey = "movieFlicker" })
                        MonitorTool("高频消减", Icons.Rounded.Star, false, anchorAware = { pickerAnchor = it; pickerKey = "movieHighFreqFlicker" })
                        MonitorTool("视频减震", Icons.Rounded.Speed, false, anchorAware = { pickerAnchor = it; pickerKey = "movieVr" })
                        MonitorTool("曝光补偿", Icons.Rounded.Exposure, false, anchorAware = { pickerAnchor = it; pickerKey = "movieEv" })
                    } else {
                        MonitorTool("ISO 自动", Icons.Rounded.Tune, state.valueOf("isoAuto").contains("开"), anchorAware = { pickerAnchor = it; pickerKey = "isoAuto" })
                        MonitorTool("测光", Icons.Rounded.LightMode, false, anchorAware = { pickerAnchor = it; pickerKey = "metering" })
                        MonitorTool("对焦", Icons.Rounded.CenterFocusStrong, false, anchorAware = { pickerAnchor = it; pickerKey = "focusMode" })
                        MonitorTool("AF 区域", Icons.Rounded.CropFree, false, anchorAware = { pickerAnchor = it; pickerKey = "afAreaMode" })
                        MonitorTool("白平衡", Icons.Rounded.WbSunny, false, anchorAware = { pickerAnchor = it; pickerKey = "wb" })
                        MonitorTool("曝光模式", Icons.Rounded.Tune, false, anchorAware = { pickerAnchor = it; pickerKey = "expMode" })
                        MonitorTool("侦测对象", Icons.Rounded.Face, false, anchorAware = { pickerAnchor = it; pickerKey = "subjectDetection" })
                        MonitorTool("释放模式", Icons.Rounded.Speed, false, anchorAware = { pickerAnchor = it; pickerKey = "drive" })
                        MonitorTool("画质", Icons.Rounded.Star, false, anchorAware = { pickerAnchor = it; pickerKey = "imageQuality" })
                        MonitorTool("图像区域", Icons.Rounded.CropFree, false, anchorAware = { pickerAnchor = it; pickerKey = "imageArea" })
                        MonitorTool("图像尺寸", Icons.Rounded.CropFree, false, anchorAware = { pickerAnchor = it; pickerKey = "imageSize" })
                        MonitorTool("RAW 录制", Icons.Rounded.Star, false, anchorAware = { pickerAnchor = it; pickerKey = "rawCompression" })
                        MonitorTool("动态 D", Icons.Rounded.Bolt, false, anchorAware = { pickerAnchor = it; pickerKey = "activeD" })
                        MonitorTool("减震模式", Icons.Rounded.Speed, false, anchorAware = { pickerAnchor = it; pickerKey = "vr" })
                        MonitorTool("曝光补偿", Icons.Rounded.Exposure, false, anchorAware = { pickerAnchor = it; pickerKey = "ev" })
                    }
                    // 接管相机：打开才会把机身控制权交给 App（相机屏幕让出来）；
                    // 默认关着，机身屏幕照常显示，也不会弹「已连接至智能设备」。
                    MonitorTool(
                        label = "接管相机",
                        icon = Icons.Rounded.Tune,
                        active = state.hostControlTaken,
                        onClick = { state.setTakeoverCamera(!state.hostControlTaken) }
                    )
                }
            }

            // LUT 列表已经嵌入「监看工具」卡片底部，不再额外浮层遮挡工具栏。

            // 取景画质卡片：点右下角画质按钮或监看工具里的「预览画质」都会打开这张卡，
            // 不再点一次只循环切一档，用户可以直接看到三档并选中。
            if (qualityCardOpen) {
                Column(
                    modifier = Modifier
                        .align(if (landscape) Alignment.CenterEnd else Alignment.BottomEnd)
                        .padding(end = if (landscape) railRightWidth + 8.dp else 14.dp, bottom = if (landscape) 16.dp else 120.dp)
                        .widthIn(min = 190.dp, max = 230.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xF217181B))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("取景画质", color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
                    listOf("流畅", "标准", "高清").forEach { level ->
                        val active = state.previewQuality == level
                        Text(
                            text = level,
                            color = if (active) Color(0xFFC9FFA8) else Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) Color(0xFF2F3F2C) else Color.White.copy(alpha = 0.06f))
                                .clickable {
                                    state.applyPreviewQuality(level)
                                    qualityCardOpen = false
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                    Text("画质越高越耗电", color = Color.White.copy(alpha = 0.38f), fontSize = 9.sp)
                }
            }

            // 直方图 / 波形图：常驻叠加层，长按拖动，只有「监看工具」里能关
            if (histOn) histOffset?.let { off ->
                Box(
                    modifier = Modifier
                        .offset { IntOffset(off.x.toInt(), off.y.toInt()) }
                        .size(200.dp, 96.dp)
                        .pointerInput(rootW, rootH) {
                            detectDragGesturesAfterLongPress(
                                onDrag = { change, drag ->
                                    change.consume()
                                    val cur = histOffset ?: return@detectDragGesturesAfterLongPress
                                    histOffset = Offset(
                                        (cur.x + drag.x).coerceIn(0f, (rootW - histWpx).coerceAtLeast(0).toFloat()),
                                        (cur.y + drag.y).coerceIn(0f, (rootH - histHpx).coerceAtLeast(0).toFloat())
                                    )
                                }
                            )
                        }
                ) {
                    HistogramPanel(analysis, modifier = Modifier.fillMaxSize())
                }
            }
            // 波形本体由 GPU 直接画在取景画面上，这里只保留透明的拖动/边框层
            if (waveOn) waveOffset?.let { off ->
                Box(
                    modifier = Modifier
                        .offset { IntOffset(off.x.toInt(), off.y.toInt()) }
                        .size(220.dp, 116.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                        .pointerInput(rootW, rootH) {
                            detectDragGesturesAfterLongPress(
                                onDrag = { change, drag ->
                                    change.consume()
                                    val cur = waveOffset ?: return@detectDragGesturesAfterLongPress
                                    waveOffset = Offset(
                                        (cur.x + drag.x).coerceIn(0f, (rootW - waveWpx).coerceAtLeast(0).toFloat()),
                                        (cur.y + drag.y).coerceIn(0f, (rootH - waveHpx).coerceAtLeast(0).toFloat())
                                    )
                                }
                            )
                        }
                )
            }

            // 伪色图例：只标黑场 / 18% / 过曝三个判断点，贴在画面下沿
            if (falseColorOn) {
                FalseColorLegend(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = if (landscape) railLeftWidth + 8.dp else 8.dp,
                            bottom = if (landscape) 12.dp else 100.dp
                        )
                )
            }

            // 水平仪：画面正中，跟着重力传感器转；±1.2° 内变绿表示已摆平
            if (levelOn) {
                HorizonLevel(
                    tiltDeg = tiltDeg,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(
                            start = if (landscape) railLeftWidth else 0.dp,
                            end = if (landscape) railRightWidth else 0.dp
                        )
                )
            }

            // 音频电平：右下角，横屏时让开右栏；读的是手机麦克风参考值
            if (audioOn) {
                AudioMeter(
                    level = audioMeter.first,
                    peak = audioMeter.second,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = if (landscape) railRightWidth + 8.dp else 8.dp,
                            bottom = if (landscape) 12.dp else 168.dp
                        )
                )
            }

            // 时间码：画面顶部中间，录制中前面带红点
            if (tcOn) {
                TimecodeBadge(
                    text = tcText,
                    recording = state.isRecordingNow,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                )
            }

            // AF 锁定时右上角常驻 AF-L 徽标
            if (afLocked.value) {
                Text(
                    "AF-L",
                    color = Color(0xFF9BFF6A),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            end = if (landscape) railRightWidth + 8.dp else 12.dp,
                            top = 12.dp
                        )
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xE6101114))
                        .border(1.dp, Color(0xFF9BFF6A).copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            afLockBadge.value?.let { badge ->
                Text(
                    badge,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 90.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xF0121315))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            // 自动化拍摄：间隔 / 包围 / 自拍计时，点遮罩空白处关闭
            if (autoOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            autoOpen = false
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AutoCapturePanel(state = state, onClose = { autoOpen = false })
                }
            }

            if (railConfigOpen) {
                RailConfigPanel(
                    left = leftActions,
                    right = rightActions,
                    onToggle = { side, id ->
                        if (side == 0) {
                            val before = leftActions
                            val after = toggleRailAction(before, id)
                            leftActions = after
                            if (id in after && id !in before) rightActions = rightActions - id
                            DoukPrefs.monitorLeftActions = leftActions.joinToString(",")
                            DoukPrefs.monitorRightActions = rightActions.joinToString(",")
                        } else {
                            val before = rightActions
                            val after = toggleRailAction(before, id)
                            rightActions = after
                            if (id in after && id !in before) leftActions = leftActions - id
                            DoukPrefs.monitorLeftActions = leftActions.joinToString(",")
                            DoukPrefs.monitorRightActions = rightActions.joinToString(",")
                        }
                    },
                    onClose = { railConfigOpen = false }
                )
            }

            // 参数/连接提示：不挡画面中心，显示在底部
            paramToast?.let { message ->
                Text(
                    message,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = if (landscape) 14.dp else 96.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xF0121315))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }

            // 横屏锁定前的一帧兜底；横屏时退出按钮已经在左栏顶部
            if (!landscape) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(12.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable { state.previewFullscreen = false },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "退出全屏", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }

            // 竖屏左下角档位胶囊：与横屏左栏底部同一个开关
            if (!landscape) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 14.dp, bottom = DoukSpace.xl)
                ) {
                    // 只显示当前这一档（视频 / 拍摄），点一下切到另一档
                    MonitorModePill(video = video, compact = true, onToggle = {
                        state.selectMode(if (video) CaptureMode.STILL else CaptureMode.VIDEO)
                    })
                }
            }

            // 竖屏兜底快门：横屏时快门固定在右栏垂直中心
            if (!landscape) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = DoukSpace.xl)
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(
                                if (state.effectiveMode == CaptureMode.VIDEO) Color(0xFFE5484D)
                                else DoukColors.accent
                            )
                            .clickable { state.shoot() }
                    )
                }
            }

            val key = pickerKey
            if (key != null && !key.isBlank()) {
                MonitorPicker(
                    state = state,
                    key = key,
                    anchor = pickerAnchor,
                    rootW = rootW,
                    rootH = rootH,
                    fixedX = if (landscape && deck == MonitorDeck.CAMERA) {
                        with(density) { (railLeftWidth + 372.dp).roundToPx() }
                    } else null,
                    onDismiss = { pickerKey = null }
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ 控件 */

@Composable
private fun MonitorStatusLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            value.ifBlank { "—" },
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ExposureLine(label: String, value: String, onAnchor: (IntOffset) -> Unit) {
    // 坐标只存普通数组，不进 Compose State；工具铺展开/收起时不会每帧回写重组。
    val pos = remember { IntArray(2) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                pos[0] = it.positionInWindow().x.toInt()
                pos[1] = it.positionInWindow().y.toInt()
            }
            .clip(RoundedCornerShape(12.dp))
            .clickable { onAnchor(IntOffset(pos[0], pos[1])) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White.copy(alpha = 0.58f), fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun MonitorDeckCard(
    title: String,
    onClose: () -> Unit,
    columns: Int,
    landscape: Boolean = false,
    deckWidth: Dp? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .then(
                if (landscape) Modifier.fillMaxHeight().width(deckWidth ?: 360.dp)
                else Modifier.widthIn(max = if (columns >= 4) 380.dp else 300.dp)
            )
            .clip(if (landscape) RoundedCornerShape(26.dp) else RoundedCornerShape(30.dp))
            .background(Color(0xF0161719))
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White.copy(alpha = 0.66f), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Close, contentDescription = "关闭面板",
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = if (landscape) {
                Modifier.weight(1f).verticalScroll(rememberScrollState())
            } else Modifier
        ) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = columns
            ) { content() }
            footer?.invoke()
        }
    }
}

@Composable
private fun MonitorLutSection(
    loading: Boolean,
    entries: List<FilterEntry>,
    selectedId: String?,
    brokenIds: Set<String>,
    onReload: () -> Unit,
    onSelectNone: () -> Unit,
    onSelect: (FilterEntry) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.10f))
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                loading -> "正在读取 LUT…"
                entries.isEmpty() -> "LUT 列表准备中… 点此重新扫描"
                else -> "LUT（${entries.size} 个）· 点此重新扫描"
            },
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onReload() }
                .padding(horizontal = 6.dp, vertical = 6.dp)
        )
        Text(
            "内置 N-Log / HLG 还原始终可用；导入的 .cube / .3dl 会按文件名显示。",
            color = Color.White.copy(alpha = 0.42f),
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Spacer(Modifier.height(6.dp))
        LutRow("无（不使用 LUT）", selectedId == null, onSelectNone)
        entries.forEach { entry ->
            LutRow(entry.label, selectedId == entry.id) { onSelect(entry) }
        }
    }
}

@Composable
private fun MonitorTool(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    anchorAware: ((IntOffset) -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    // 同上：坐标用普通数组，避免工具条在 deck 动画时反复触发重组。
    val pos = remember { IntArray(2) }
    Column(
        modifier = Modifier
            .width(78.dp)
            .onGloballyPositioned {
                pos[0] = it.positionInWindow().x.toInt()
                pos[1] = it.positionInWindow().y.toInt()
            }
            .clip(RoundedCornerShape(18.dp))
            .clickable {
                anchorAware?.invoke(IntOffset(pos[0], pos[1]))
                onClick?.invoke()
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (active) Color(0xFF3A4A38) else Color(0xFF2B2D31))
                .border(1.dp, if (active) Color(0xFF9BFF6A).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = if (active) Color(0xFFC9FFA8) else Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp)
    }
}

/* ------------------------------------------------------------------ 左右栏 */

private val DEFAULT_LEFT_ACTIONS = listOf("iso", "shutter", "aperture", "ev")
private val DEFAULT_RIGHT_ACTIONS = listOf("lut", "grid", "hist", "wave")
private val RAIL_ACTION_IDS = listOf(
    "iso", "shutter", "aperture", "ev", "expMode", "metering", "focusMode",
    "afAreaMode", "wb", "drive", "imageFormat", "resolution", "rawCompression",
    "activeD", "vr", "imageArea", "isoAuto",
    "lut", "grid", "hist", "wave", "focus", "quality", "toneMode", "view", "rails",
    "falseCol", "level", "audio", "tc", "auto", "clock", "guide"
)

private fun parseRailActions(raw: String, defaults: List<String>): List<String> {
    val parsed = raw.split(',').map { it.trim() }.filter { it in RAIL_ACTION_IDS }
    return if (parsed.isEmpty()) defaults else parsed.take(8)
}

private fun toggleRailAction(current: List<String>, id: String): List<String> =
    when {
        id in current -> current - id
        current.size >= 8 -> current
        else -> current + id
    }

private fun railExposureKey(id: String, video: Boolean): String? = when (id) {
    "iso" -> if (video) "movieIso" else "iso"
    "shutter" -> if (video) "movieShutter" else "shutter"
    "aperture" -> if (video) "movieAperture" else "aperture"
    "ev" -> if (video) "movieEv" else "ev"
    "metering" -> if (video) "movieMetering" else "metering"
    "focusMode" -> if (video) "movieFocusMode" else "focusMode"
    "afAreaMode" -> if (video) "movieAfAreaMode" else "afAreaMode"
    "wb" -> if (video) "movieWb" else "wb"
    "imageFormat" -> if (video) "movieFileType" else "imageQuality"
    "resolution" -> if (video) "movieScreenSize" else "imageSize"
    "toneMode" -> "movieToneMode"
    "expMode", "drive", "rawCompression", "activeD", "vr", "imageArea", "isoAuto" -> id
    else -> null
}

/** 这一项在当前拍摄模式里是否出现：LUT/色调只属于视频，纯照片参数不在视频里挤位置。 */
private fun railVisibleInMode(id: String, video: Boolean): Boolean = when (id) {
    "lut", "toneMode" -> video
    "expMode", "drive", "rawCompression", "activeD", "vr", "imageArea", "isoAuto" -> !video
    else -> true
}

/** 左/右栏显示的摘要值：格式/分辨率/对焦用整段状态，其它沿用参数 picker 的值。 */
private fun railDisplayValue(id: String, state: DoukState, video: Boolean): String = when (id) {
    "imageFormat" -> state.captureFormatText
    "resolution" -> state.captureResolutionText
    "focusMode" -> state.focusModeText
    "metering" -> state.meteringModeText
    else -> railExposureKey(id, video)?.let { state.valueOf(it) } ?: ""
}

private fun railActionLabel(id: String): String = when (id) {
    "iso" -> "ISO"
    "shutter" -> "快门"
    "aperture" -> "光圈"
    "ev" -> "曝光补偿"
    "expMode" -> "曝光模式"
    "metering" -> "测光模式"
    "focusMode" -> "对焦模式"
    "afAreaMode" -> "AF 区域"
    "wb" -> "白平衡"
    "drive" -> "释放模式"
    "imageFormat" -> "拍摄格式"
    "resolution" -> "分辨率"
    "rawCompression" -> "RAW 压缩"
    "activeD" -> "动态 D"
    "vr" -> "减震"
    "imageArea" -> "图像区域"
    "isoAuto" -> "ISO 自动"
    "lut" -> "LUT"
    "grid" -> "网格"
    "hist" -> "直方图"
    "wave" -> "波形"
    "focus" -> "对焦"
    "quality" -> "画质"
    "toneMode" -> "色调模式"
    "view" -> "视图"
    "rails" -> "左右栏"
    "falseCol" -> "伪色"
    "level" -> "水平仪"
    "audio" -> "音频电平"
    "tc" -> "时间码"
    "auto" -> "间隔/包围"
    "clock" -> "相机时钟"
    "guide" -> "构图线"
    else -> id
}

private fun railActionIcon(id: String): androidx.compose.ui.graphics.vector.ImageVector = when (id) {
    "iso" -> Icons.Rounded.Iso
    "shutter" -> Icons.Rounded.Timer
    "aperture" -> Icons.Rounded.PhotoFilter
    "ev" -> Icons.Rounded.Exposure
    "expMode" -> Icons.Rounded.Tune
    "metering" -> Icons.Rounded.LightMode
    "focusMode" -> Icons.Rounded.CenterFocusStrong
    "afAreaMode" -> Icons.Rounded.CropFree
    "wb" -> Icons.Rounded.WbSunny
    "drive" -> Icons.Rounded.Speed
    "imageFormat" -> Icons.Rounded.Star
    "resolution" -> Icons.Rounded.CropFree
    "rawCompression" -> Icons.Rounded.Star
    "activeD" -> Icons.Rounded.Bolt
    "vr" -> Icons.Rounded.Speed
    "imageArea" -> Icons.Rounded.CropFree
    "isoAuto" -> Icons.Rounded.Tune
    "lut" -> Icons.Rounded.PhotoFilter
    "grid" -> Icons.Rounded.GridOn
    "hist" -> Icons.Rounded.BarChart
    "wave" -> Icons.Rounded.ShowChart
    "focus" -> Icons.Rounded.CenterFocusStrong
    "quality" -> Icons.Rounded.Speed
    "toneMode" -> Icons.Rounded.ColorLens
    "view" -> Icons.Rounded.RotateRight
    "falseCol" -> Icons.Rounded.Palette
    "level" -> Icons.Rounded.Straighten
    "audio" -> Icons.Rounded.GraphicEq
    "tc" -> Icons.Rounded.AccessTime
    "auto" -> Icons.Rounded.Timelapse
    "clock" -> Icons.Rounded.Schedule
    "guide" -> Icons.Rounded.GridOn
    else -> Icons.Rounded.Tune
}

private fun railActionActive(
    id: String,
    histOn: Boolean,
    waveOn: Boolean,
    gridOn: Boolean,
    perspectiveOn: Boolean,
    lutOn: Boolean,
    falseColorOn: Boolean = false,
    levelOn: Boolean = false,
    audioOn: Boolean = false,
    tcOn: Boolean = false,
    autoOpen: Boolean = false
): Boolean = when (id) {
    "hist" -> histOn
    "wave" -> waveOn
    "grid", "guide" -> gridOn
    "lut" -> lutOn
    "view" -> perspectiveOn
    "falseCol" -> falseColorOn
    "level" -> levelOn
    "audio" -> audioOn
    "tc" -> tcOn
    "auto" -> autoOpen
    else -> false
}

@Composable
private fun RailActionItem(
    id: String,
    state: DoukState,
    video: Boolean,
    active: Boolean,
    onOpen: (String, IntOffset) -> Unit,
    onAction: (String) -> Unit
) {
    val exposureKey = railExposureKey(id, video)
    val pos = remember { IntArray(2) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                pos[0] = it.positionInWindow().x.toInt()
                pos[1] = it.positionInWindow().y.toInt()
            }
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (active) Color(0xFF32422E).copy(alpha = 0.92f)
                else Color.White.copy(alpha = 0.07f)
            )
            .border(
                1.dp,
                if (active) Color(0xFF9BFF6A).copy(alpha = 0.45f)
                else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(12.dp)
            )
            .clickable {
                if (exposureKey != null) onOpen(exposureKey, IntOffset(pos[0], pos[1]))
                else onAction(id)
            }
            .padding(vertical = 5.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (exposureKey != null) {
            Text(railActionLabel(id), color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp, maxLines = 1)
            Text(
                railDisplayValue(id, state, video).ifBlank { "—" },
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Icon(
                railActionIcon(id), railActionLabel(id),
                tint = if (active) Color(0xFFC9FFA8) else Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(21.dp)
            )
            Text(railActionLabel(id), color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun RailConfigPanel(
    left: List<String>,
    right: List<String>,
    onToggle: (Int, String) -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .clickable { onClose() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 440.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xF2161719))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("左右栏设置", color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text("每侧最多 8 个，点一下可移到另一侧", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.Rounded.Close, "关闭",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable { onClose() }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("左侧栏", color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        RAIL_ACTION_IDS.forEach { id ->
                            RailChip(railActionLabel(id), id in left) { onToggle(0, id) }
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("右侧栏（快门固定居中）", color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        RAIL_ACTION_IDS.forEach { id ->
                            RailChip(railActionLabel(id), id in right) { onToggle(1, id) }
                        }
                    }
                }
            }
            Text(
                "所有参数都能放左栏或右栏；曝光/格式/分辨率/对焦/测光点一下直接改。" +
                    "网格/直方图/波形/LUT 是开关，对焦会触发一次 AF。",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun RailChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Color(0xFFC9FFA8) else Color.White.copy(alpha = 0.82f),
        fontSize = 10.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Color(0xFF3A4A38) else Color.White.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

/**
 * 档位胶囊「视频 | 拍摄」：显示当前档，点一下就切到另一档。
 * 只影响本机界面与快门行为，不改机身拨盘；机身拨档后这里会自动跟着变。
 */
@Composable
private fun MonitorModePill(video: Boolean, compact: Boolean = false, onToggle: () -> Unit) {
    // 横屏左栏只有几十 dp 宽，两段式会被挤成竖排，这里退化成单段开关
    if (compact) {
        Text(
            text = if (video) "视频" else "拍摄",
            color = if (video) Color(0xFFFF8A8A) else Color(0xFFC9FFA8),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (video) Color(0xFF3A2226) else Color(0xFF2F3F2C))
                .clickable { onToggle() }
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )
        return
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC0E0F12))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
            .clickable { onToggle() }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "视频",
            color = if (video) Color(0xFFFF8A8A) else Color.White.copy(alpha = 0.58f),
            fontSize = 11.sp,
            fontWeight = if (video) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (video) Color(0xFF3A2226) else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Text(
            "拍摄",
            color = if (!video) Color(0xFFC9FFA8) else Color.White.copy(alpha = 0.58f),
            fontSize = 11.sp,
            fontWeight = if (!video) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (!video) Color(0xFF2F3F2C) else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun LutRow(label: String, active: Boolean, onClick: () -> Unit) {
    // 竖排一行：整行可点，当前生效的右侧打勾并高亮
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Color(0xFF2F3F2C) else Color.White.copy(alpha = 0.07f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (active) Color(0xFFC9FFA8) else Color.White.copy(alpha = 0.86f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (active) {
            Text("●", color = Color(0xFFC9FFA8), fontSize = 12.sp)
        }
    }
}

@Composable
private fun HistogramPanel(analysis: FrameAnalysis, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE6101114))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width / 256f
            val h = size.height
            fun drawHist(data: IntArray, color: Color) {
                val maxV = max(1, data.maxOrNull() ?: 1)
                for (i in 0 until 256) {
                    val v = data[i].toFloat() / maxV
                    drawRect(
                        color = color,
                        topLeft = androidx.compose.ui.geometry.Offset(i * w, h - v * h),
                        size = androidx.compose.ui.geometry.Size(w + 0.5f, v * h)
                    )
                }
            }
            drawHist(analysis.histR ?: IntArray(256), Color(0x66FF5A5A))
            drawHist(analysis.histG ?: IntArray(256), Color(0x665AFF7A))
            drawHist(analysis.histB ?: IntArray(256), Color(0x665A8CFF))
            analysis.histL?.let {
                val maxV = max(1, it.maxOrNull() ?: 1)
                val path = androidx.compose.ui.graphics.Path()
                for (i in 0 until 256) {
                    val x = i * w
                    val y = h - it[i].toFloat() / maxV * h
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color.White.copy(alpha = 0.8f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
            }
        }
    }
}


@Composable
private fun MonitorPicker(
    state: DoukState,
    key: String,
    anchor: IntOffset,
    rootW: Int,
    rootH: Int,
    fixedX: Int? = null,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val cardWpx = with(density) { 216.dp.roundToPx() }
    val cardMaxHpx = with(density) { 330.dp.roundToPx() }
    val options = state.optionsOf(key).distinct()
    val lockReason = state.paramLockReason(key)
    val x = (fixedX ?: (anchor.x - cardWpx - with(density) { 10.dp.roundToPx() }))
        .coerceIn(with(density) { 8.dp.roundToPx() }, (rootW - cardWpx - with(density) { 8.dp.roundToPx() }).coerceAtLeast(0))
    val y = (anchor.y - with(density) { 40.dp.roundToPx() })
        .coerceIn(with(density) { 8.dp.roundToPx() }, (rootH - cardMaxHpx - with(density) { 8.dp.roundToPx() }).coerceAtLeast(0))
    Box(modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss)) {
        Column(
            modifier = Modifier
                .offsetPx(x, y)
                .width(216.dp)
                .heightIn(max = 330.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF516171A))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .padding(12.dp)
        ) {
            Text(paramTitle(key), color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
            if (lockReason != null) {
                Text(
                    lockReason,
                    color = Color(0xFFFFB45A),
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            if (options.isEmpty()) {
                Text("相机未上报可选项", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(
                        options,
                        key = { it },
                        contentType = { "monitor-option" }
                    ) { option ->
                        val current = state.valueOf(key) == option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (current) Color(0xFF36452F) else Color.Transparent)
                                .clickable {
                                    state.setParam(key, option)
                                    onDismiss()
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(option, color = if (current) Color(0xFFC9FFA8) else Color.White, fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            if (current) Icon(Icons.Rounded.Star, null, tint = Color(0xFFC9FFA8), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun paramTitle(key: String): String = when (key) {
    "previewQuality" -> "取景画质"
    "movieToneMode" -> "视频色调模式"
    "iso", "movieIso" -> "ISO"
    "shutter", "movieShutter" -> "快门"
    "aperture", "movieAperture" -> "光圈"
    "ev", "movieEv" -> "曝光补偿"
    "metering", "movieMetering" -> "测光"
    "focusMode", "movieFocusMode" -> "对焦模式"
    "afAreaMode", "movieAfAreaMode" -> "AF 区域"
    "wb", "movieWb" -> "白平衡"
    "drive" -> "释放模式"
    "imageQuality", "movieQuality" -> "画质"
    "movieFileType" -> "文件类型"
    else -> key
}

private fun Modifier.offsetPx(x: Int, y: Int): Modifier = this.then(
    Modifier.offset { IntOffset(x, y) }
)

/* ------------------------------------------------------------------ 图像分析 */

private fun analyzeFrame(src: ImageBitmap): FrameAnalysis {
    val android = src.asAndroidBitmap()
    val maxW = 360
    val scale = min(1f, maxW.toFloat() / android.width)
    val w = max(1, (android.width * scale).toInt())
    val h = max(1, (android.height * scale).toInt())
    val small = if (android.width == w && android.height == h) android else Bitmap.createScaledBitmap(android, w, h, true)
    val pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)

    val rBins = IntArray(256)
    val gBins = IntArray(256)
    val bBins = IntArray(256)
    val lBins = IntArray(256)

    // 跨 2 像素取样：360px 缩略图上直方图形状几乎不变，计算量降到 1/4，
    // 监看 15fps 时不再和取景解码抢 CPU。
    for (y in 0 until h step 2) {
        for (x in 0 until w step 2) {
            val c = pixels[y * w + x]
            val r = AndroidColor.red(c)
            val g = AndroidColor.green(c)
            val b = AndroidColor.blue(c)
            rBins[r]++
            gBins[g]++
            bBins[b]++
            lBins[(r * 0.299f + g * 0.587f + b * 0.114f).toInt().coerceIn(0, 255)]++
        }
    }

    return FrameAnalysis(rBins, gBins, bBins, lBins)
}

// 监看 LUT 预览也搬到 GL 了（LutRenderer 的三维 LUT 四面体插值是全分辨率、每帧实时），
// 之前的 640px CPU 三线性预览不再需要。
