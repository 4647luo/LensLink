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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Iso
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private enum class MonitorDeck { NONE, TOOLS, CAMERA }

private data class FrameAnalysis(
    val histR: IntArray? = null,
    val histG: IntArray? = null,
    val histB: IntArray? = null,
    val histL: IntArray? = null,
    val waveform: ImageBitmap? = null
)

/** 全屏监视：点击画面对焦、顶部曝光参数、上下/左右滑出两组圆形工具。 */
@Composable
fun MonitorFullscreen(state: DoukState) {
    val wifiBmp by state.vm.liveViewBitmap.collectAsStateWithLifecycle()
    val usbBmp by state.usb.ptp.liveViewBitmap.collectAsStateWithLifecycle()
    val frame = if (state.usbConnected) usbBmp else wifiBmp

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
    var audioOn by remember { mutableStateOf(false) }
    var gridOn by remember { mutableStateOf(false) }
    var lutId by remember { mutableStateOf<String?>(null) }
    var lutEntries by remember { mutableStateOf<List<FilterEntry>>(emptyList()) }
    var lutRuntime by remember { mutableStateOf<FilterRuntime?>(null) }
    var lutLoading by remember { mutableStateOf(false) }
    var lutFrame by remember { mutableStateOf<ImageBitmap?>(null) }
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
    var audioOffset by remember { mutableStateOf<Offset?>(null) }
    // 左右栏自定义按钮
    var leftActions by remember {
        mutableStateOf(parseRailActions(DoukPrefs.monitorLeftActions, DEFAULT_LEFT_ACTIONS))
    }
    var rightActions by remember {
        mutableStateOf(parseRailActions(DoukPrefs.monitorRightActions, DEFAULT_RIGHT_ACTIONS))
    }
    var railConfigOpen by remember { mutableStateOf(false) }
    var paramToast by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val density = LocalDensity.current
    val latestFrame by rememberUpdatedState(frame)

    // 对焦点标记 1.8 秒后自己消失，和取景页一致
    LaunchedEffect(focusTick) {
        if (focusMarker != null) {
            delay(1800)
            focusMarker = null
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

    // LUT 只在打开监视或选中时才解析，避免每次进全屏都读一遍磁盘
    LaunchedEffect(deck, lutOn, lutId) {
        if ((deck == MonitorDeck.TOOLS || lutOn) && lutRuntime == null && !lutLoading) {
            lutLoading = true
            val loaded = withContext(Dispatchers.IO) { runCatching { FilterLibrary.load(context) }.getOrNull() }
            lutEntries = loaded?.entries ?: emptyList()
            lutRuntime = loaded?.runtime
            lutLoading = false
        }
    }

    // 直方图 / 波形 / LUT 预览：统一在 IO 线程低频计算，不跟着 30fps 取景帧跑
    LaunchedEffect(Unit) {
        while (isActive) {
            val bmp = latestFrame?.asImageBitmap()
            val selectedLutId = lutId
            val anyOn = histOn || waveOn || (lutOn && selectedLutId != null)
            if (bmp != null && anyOn) {
                val result = withContext(Dispatchers.IO) { analyzeFrame(bmp, histOn, waveOn) }
                analysis = result
                if (lutOn && selectedLutId != null) {
                    val lut = lutRuntime?.lut(selectedLutId)
                    if (lut != null) {
                        lutFrame = withContext(Dispatchers.IO) { renderLut(bmp, lut) }
                    }
                } else {
                    lutFrame = null
                }
            } else if (!anyOn) {
                analysis = FrameAnalysis()
                lutFrame = null
            }
            delay(
                when {
                    // 预览 LUT 和直方图都降频：全尺寸帧每 100ms 复制/缩放一次会制造 GC 峰值，
                    // 画面看起来仍是实时刷新，但计算不再和 30fps 取景抢主线程。
                    lutOn && selectedLutId != null -> 200L
                    anyOn -> 150L
                    // 没开任何监看叠加时低频空转，避免无意义地唤醒线程耗电
                    else -> 900L
                }
            )
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
            val sourceBmp = frame
            val source = remember(sourceBmp) { sourceBmp?.asImageBitmap() }
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
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .requiredSize(
                            with(density) { dispWpx.toDp() },
                            with(density) { dispHpx.toDp() }
                        )
                        .graphicsLayer {
                            rotationZ = rotation
                            scaleX = if (mirror) -1f else 1f
                        }
                ) {
                    val processed = lutFrame
                    val shown: ImageBitmap = if (lutOn && processed != null) processed else source
                    Image(
                        bitmap = shown,
                        contentDescription = "相机实时取景全屏",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
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

            if (gridOn) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = if (landscape) railLeftWidth else 0.dp,
                            end = if (landscape) railRightWidth else 0.dp
                        )
                ) {
                    val thirdW = size.width / 3f
                    val thirdH = size.height / 3f
                    for (i in 1..2) {
                        drawLine(Color.White.copy(alpha = 0.22f), androidx.compose.ui.geometry.Offset(thirdW * i, 0f), androidx.compose.ui.geometry.Offset(thirdW * i, size.height), 1f)
                        drawLine(Color.White.copy(alpha = 0.22f), androidx.compose.ui.geometry.Offset(0f, thirdH * i), androidx.compose.ui.geometry.Offset(size.width, thirdH * i), 1f)
                    }
                }
            }

            // 手势层：上滑/左滑=监看工具，下滑/右滑=相机参数（竖横构图都可用）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var dx = 0f
                        var dy = 0f
                        detectDragGestures(
                            onDragStart = { dx = 0f; dy = 0f },
                            onDragCancel = { dx = 0f; dy = 0f },
                            onDragEnd = {
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
                                    if (deck == MonitorDeck.TOOLS && dy > 70f) deck = MonitorDeck.NONE
                                    if (deck == MonitorDeck.CAMERA && dy < -70f) deck = MonitorDeck.NONE
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
                        .pointerInput(deck) {
                            detectTapGestures(
                                onTap = {
                                    DoukLog.i("Mon", "TAP_CLOSE_DECK", "deck" to deck.name)
                                    deck = MonitorDeck.NONE
                                },
                                onDoubleTap = {
                                    DoukLog.i("Mon", "DOUBLE_TAP_CLOSE_DECK", "deck" to deck.name)
                                    deck = MonitorDeck.NONE
                                }
                            )
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
            val video = state.mode == CaptureMode.VIDEO
            fun expValue(still: String, movie: String): String {
                val m = state.valueOf(movie)
                if (video && m.isNotBlank() && m != "—" && m != "相机未上报") return m
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
            val audioWpx = with(density) { 210.dp.roundToPx() }
            val audioHpx = with(density) { 220.dp.roundToPx() }
            val edge = with(density) { 10.dp.roundToPx() }

            // 首次进入/横竖切换时把叠加层放到画面内的默认角落，之后用户长按拖到哪就停在哪
            LaunchedEffect(rootW, rootH) {
                histOffset = Offset((railLeftPx + edge).toFloat(), edge.toFloat())
                waveOffset = Offset(
                    (railLeftPx + edge).toFloat(),
                    (edge + histHpx + edge).toFloat()
                )
                audioOffset = Offset(
                    (rootW - railRightPx - audioWpx - edge).coerceAtLeast(edge).toFloat(),
                    edge.toFloat()
                )
            }

            fun onRailAction(id: String) {
                when (id) {
                    "grid" -> gridOn = !gridOn
                    "hist" -> histOn = !histOn
                    "wave" -> waveOn = !waveOn
                    "audio" -> audioOn = !audioOn
                    "focus" -> state.vm.triggerAf()
                    "quality" -> state.cyclePreviewQuality()
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
                    leftActions.forEach { id ->
                        RailActionItem(
                            id = id,
                            state = state,
                            video = video,
                            active = railActionActive(id, histOn, waveOn, audioOn, gridOn, perspectiveOn = viewCard),
                            onOpen = { key, anchor -> pickerAnchor = anchor; pickerKey = key },
                            onAction = ::onRailAction
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (video) "视频" else "拍照",
                        color = if (video) Color(0xFFFF8A8A) else Color(0xFFC9FFA8),
                        fontSize = 10.sp
                    )
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
                            .clickable { deck = MonitorDeck.CAMERA }
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
                    rightActions.forEach { id ->
                        Box(Modifier.fillMaxWidth()) {
                            RailActionItem(
                                id = id,
                                state = state,
                                video = video,
                                active = railActionActive(id, histOn, waveOn, audioOn, gridOn, perspectiveOn = viewCard),
                                onOpen = { key, anchor -> pickerAnchor = anchor; pickerKey = key },
                                onAction = ::onRailAction
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                    Spacer(Modifier.weight(1f))
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
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { state.cyclePreviewQuality() }
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
                    columns = if (landscape) 4 else 3,
                    landscape = landscape
                ) {
                    MonitorTool("LUT", Icons.Rounded.PhotoFilter, lutOn) {
                        lutOn = !lutOn
                        if (!lutOn) deck = MonitorDeck.TOOLS
                    }
                    MonitorTool("直方图", Icons.Rounded.BarChart, histOn) { histOn = !histOn }
                    MonitorTool("波形图", Icons.Rounded.ShowChart, waveOn) { waveOn = !waveOn }
                    MonitorTool("音频", Icons.Rounded.GraphicEq, audioOn) { audioOn = !audioOn }
                    MonitorTool("网格", Icons.Rounded.GridOn, gridOn) { gridOn = !gridOn }
                    MonitorTool("预览画质", Icons.Rounded.Speed, false) { state.cyclePreviewQuality() }
                    MonitorTool("侧栏按钮", Icons.Rounded.Tune, false) { railConfigOpen = true }
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
                    columns = if (landscape) 4 else 3,
                    landscape = landscape
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

            // LUT 资源选择：贴着工具面板上方，按导入顺序列出
            if (toolSheetVisible && lutOn) {
                Column(
                    modifier = Modifier
                        .align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
                        .then(
                            if (landscape) Modifier.padding(end = railRightWidth + 372.dp)
                            else Modifier.padding(bottom = 360.dp)
                        )
                        .widthIn(max = if (landscape) 420.dp else 360.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xF217181B))
                        .padding(10.dp)
                ) {
                    Text(
                        if (lutLoading) "正在读取 LUT…" else if (lutEntries.isEmpty()) "设置 → 资源导入里还没有 LUT" else "选择 LUT",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            LutChip("无", lutId == null) {
                                lutId = null
                                lutOn = false
                            }
                        }
                        items(
                            lutEntries,
                            key = { it.id },
                            contentType = { "lut-chip" }
                        ) { entry ->
                            LutChip(entry.label, lutId == entry.id) { lutId = entry.id }
                        }
                    }
                }
            }

            // 直方图 / 波形图 / 音频：常驻叠加层，长按拖动，只有「监看工具」里能关
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
            if (waveOn) waveOffset?.let { off ->
                Box(
                    modifier = Modifier
                        .offset { IntOffset(off.x.toInt(), off.y.toInt()) }
                        .size(220.dp, 116.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xE6101114))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
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
                ) {
                    if (analysis.waveform != null) {
                        Image(analysis.waveform!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("等待波形…", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                        }
                    }
                }
            }
            if (audioOn) audioOffset?.let { off ->
                Box(
                    modifier = Modifier
                        .offset { IntOffset(off.x.toInt(), off.y.toInt()) }
                        .size(210.dp, 220.dp)
                        .pointerInput(rootW, rootH) {
                            detectDragGesturesAfterLongPress(
                                onDrag = { change, drag ->
                                    change.consume()
                                    val cur = audioOffset ?: return@detectDragGesturesAfterLongPress
                                    audioOffset = Offset(
                                        (cur.x + drag.x).coerceIn(0f, (rootW - audioWpx).coerceAtLeast(0).toFloat()),
                                        (cur.y + drag.y).coerceIn(0f, (rootH - audioHpx).coerceAtLeast(0).toFloat())
                                    )
                                }
                            )
                        }
                ) {
                    AudioPanel(state, modifier = Modifier.fillMaxSize())
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
                                if (state.mode == CaptureMode.VIDEO) Color(0xFFE5484D)
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
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .then(
                if (landscape) Modifier.fillMaxHeight().width(360.dp)
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = columns
            ) { content() }
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
private val DEFAULT_RIGHT_ACTIONS = listOf("grid", "hist", "wave", "audio")
private val RAIL_ACTION_IDS = listOf(
    "iso", "shutter", "aperture", "ev", "metering",
    "grid", "hist", "wave", "audio",
    "focus", "quality", "view", "rails"
)

private fun parseRailActions(raw: String, defaults: List<String>): List<String> {
    val parsed = raw.split(',').map { it.trim() }.filter { it in RAIL_ACTION_IDS }
    return if (parsed.isEmpty()) defaults else parsed.take(4)
}

private fun toggleRailAction(current: List<String>, id: String): List<String> =
    when {
        id in current -> current - id
        current.size >= 4 -> current
        else -> current + id
    }

private fun railExposureKey(id: String, video: Boolean): String? = when (id) {
    "iso" -> if (video) "movieIso" else "iso"
    "shutter" -> if (video) "movieShutter" else "shutter"
    "aperture" -> if (video) "movieAperture" else "aperture"
    "ev" -> if (video) "movieEv" else "ev"
    "metering" -> if (video) "movieMetering" else "metering"
    else -> null
}

private fun railActionLabel(id: String): String = when (id) {
    "iso" -> "ISO"
    "shutter" -> "快门"
    "aperture" -> "光圈"
    "ev" -> "曝光补偿"
    "metering" -> "测光"
    "grid" -> "网格"
    "hist" -> "直方图"
    "wave" -> "波形"
    "audio" -> "音频"
    "focus" -> "对焦"
    "quality" -> "画质"
    "view" -> "视图"
    "rails" -> "侧栏"
    else -> id
}

private fun railActionIcon(id: String): androidx.compose.ui.graphics.vector.ImageVector = when (id) {
    "iso" -> Icons.Rounded.Iso
    "shutter" -> Icons.Rounded.Timer
    "aperture" -> Icons.Rounded.PhotoFilter
    "ev" -> Icons.Rounded.Exposure
    "metering" -> Icons.Rounded.LightMode
    "grid" -> Icons.Rounded.GridOn
    "hist" -> Icons.Rounded.BarChart
    "wave" -> Icons.Rounded.ShowChart
    "audio" -> Icons.Rounded.GraphicEq
    "focus" -> Icons.Rounded.CenterFocusStrong
    "quality" -> Icons.Rounded.Speed
    "view" -> Icons.Rounded.RotateRight
    else -> Icons.Rounded.Tune
}

private fun railActionActive(
    id: String,
    histOn: Boolean,
    waveOn: Boolean,
    audioOn: Boolean,
    gridOn: Boolean,
    perspectiveOn: Boolean
): Boolean = when (id) {
    "hist" -> histOn
    "wave" -> waveOn
    "audio" -> audioOn
    "grid" -> gridOn
    "view" -> perspectiveOn
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
                state.valueOf(exposureKey).ifBlank { "—" },
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 1
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
                .width(520.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xF2161719))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("侧栏按钮", color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text("每侧最多 4 个", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
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
                "曝光类按钮点一下直接改参数；网格/直方图/波形/音频是开关；对焦会触发一次 AF。",
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

@Composable
private fun LutChip(label: String, active: Boolean, onClick: () -> Unit) {    Text(
        label,
        color = if (active) Color(0xFFC9FFA8) else Color.White.copy(alpha = 0.85f),
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Color(0xFF3A4A38) else Color.White.copy(alpha = 0.09f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
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
private fun AudioPanel(state: DoukState, modifier: Modifier = Modifier) {
    val mic = state.valueOf("movieMic")
    val level = state.valueOf("movieMicLevel")
    val wind = state.valueOf("movieWindNoise")
    // 相机没有实时音频电平通道；这里按「麦克风档位 / 录音电平」换算成 L/R 两条声道柱，
    // 让用户至少能看到机身收音的左右声道状态，而不是一行冷冰冰的「未上报」。
    val numeric = Regex("\\d+").find(level)?.value?.toIntOrNull()
    val gain = when {
        !state.isConnected -> 0f
        level.contains("高") -> 0.82f
        level.contains("中") -> 0.58f
        level.contains("低") -> 0.34f
        numeric != null -> (0.28f + numeric * 0.12f).coerceIn(0.25f, 0.85f)
        else -> 0.46f
    }
    val stereo = !mic.contains("单声道")
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE6101114))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("相机声道", color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (state.isConnected) Color(0xFF9BFF6A) else Color.White.copy(alpha = 0.3f))
            )
            Spacer(Modifier.width(5.dp))
            Text(if (state.isConnected) "收音中" else "未连接", color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp)
        }
        ChannelMeter("L", if (stereo) gain else gain * 0.94f)
        ChannelMeter("R", if (stereo) gain * 0.94f else 0f)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("-40", color = Color.White.copy(alpha = 0.35f), fontSize = 8.sp)
            Spacer(Modifier.weight(1f))
            Text("-20", color = Color.White.copy(alpha = 0.35f), fontSize = 8.sp)
            Spacer(Modifier.weight(1f))
            Text("-12", color = Color.White.copy(alpha = 0.35f), fontSize = 8.sp)
            Spacer(Modifier.weight(1f))
            Text("-6", color = Color.White.copy(alpha = 0.35f), fontSize = 8.sp)
            Spacer(Modifier.weight(1f))
            Text("0 dB", color = Color.White.copy(alpha = 0.35f), fontSize = 8.sp)
        }
        Text(
            "麦克风 ${mic.ifBlank { "—" }} · 电平 ${level.ifBlank { "—" }} · 风噪 ${wind.ifBlank { "—" }}",
            color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, maxLines = 1
        )
        Text(
            "PTP 无实时电平，柱高按收音档位估算",
            color = Color.White.copy(alpha = 0.5f), fontSize = 8.5.sp, maxLines = 2
        )
    }
}

@Composable
private fun ChannelMeter(label: String, level: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, modifier = Modifier.width(12.dp))
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
        ) {
            val segments = 22
            val gap = 2f
            val segW = (size.width - gap * (segments - 1)) / segments
            val active = (level.coerceIn(0f, 1f) * segments).toInt()
            for (i in 0 until segments) {
                val color = when {
                    i >= segments - 3 -> Color(0xFFE5484D)
                    i >= segments - 7 -> Color(0xFFFFC44D)
                    i < active -> Color(0xFF9BFF6A)
                    else -> Color.White.copy(alpha = 0.10f)
                }
                drawRect(
                    color = color,
                    topLeft = Offset(i * (segW + gap), 0f),
                    size = androidx.compose.ui.geometry.Size(segW, size.height)
                )
            }
        }
        Text(
            if (level <= 0f) "-∞" else "${(level * 100).toInt()}",
            color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp,
            modifier = Modifier.width(26.dp)
        )
    }
}

@Composable
private fun AudioLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text(value.ifBlank { "—" }, color = Color.White, fontSize = 11.sp)
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

private fun analyzeFrame(src: ImageBitmap, hist: Boolean, wave: Boolean): FrameAnalysis {
    val android = src.asAndroidBitmap()
    val maxW = 360
    val scale = min(1f, maxW.toFloat() / android.width)
    val w = max(1, (android.width * scale).toInt())
    val h = max(1, (android.height * scale).toInt())
    val small = if (android.width == w && android.height == h) android else Bitmap.createScaledBitmap(android, w, h, true)
    val pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)

    val rBins = if (hist) IntArray(256) else null
    val gBins = if (hist) IntArray(256) else null
    val bBins = if (hist) IntArray(256) else null
    val lBins = if (hist) IntArray(256) else null
    var waveDensity: IntArray? = if (wave) IntArray(256 * 128) else null

    for (y in 0 until h) {
        for (x in 0 until w) {
            val c = pixels[y * w + x]
            val r = AndroidColor.red(c)
            val g = AndroidColor.green(c)
            val b = AndroidColor.blue(c)
            if (hist && rBins != null && gBins != null && bBins != null && lBins != null) {
                rBins[r]++
                gBins[g]++
                bBins[b]++
                lBins[((r * 0.299f + g * 0.587f + b * 0.114f).toInt()).coerceIn(0, 255)]++
            }
            if (waveDensity != null && x % 2 == 0 && y % 2 == 0) {
                val l = (r * 299 + g * 587 + b * 114) / 1000
                val bx = (x * 255 / (w - 1).coerceAtLeast(1)).coerceIn(0, 255)
                val by = ((255 - l) * 127 / 255).coerceIn(0, 127)
                waveDensity[by * 256 + bx] += 1
            }
        }
    }

    val waveformBmp = waveDensity?.let { density ->
        val out = IntArray(256 * 128)
        val maxV = max(1, density.maxOrNull() ?: 1)
        for (i in out.indices) {
            val a = (density[i].toFloat() / maxV * 255).toInt().coerceIn(0, 255)
            out[i] = (a shl 24) or 0x00E8FF9A
        }
        Bitmap.createBitmap(out, 256, 128, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    return FrameAnalysis(rBins, gBins, bBins, lBins, waveformBmp)
}

private fun renderLut(src: ImageBitmap, lut: Lut3D): ImageBitmap? = runCatching {
    val android = src.asAndroidBitmap()
    val maxW = 420
    val scale = min(1f, maxW.toFloat() / android.width)
    val w = max(1, ceil(android.width * scale).toInt())
    val h = max(1, ceil(android.height * scale).toInt())
    val small = if (android.width == w && android.height == h) android else Bitmap.createScaledBitmap(android, w, h, true)
    val pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)
    val table = lut.previewRgb()
    for (i in pixels.indices) {
        val c = pixels[i]
        val a = c ushr 24
        val r6 = (AndroidColor.red(c) shr 2) and 63
        val g6 = (AndroidColor.green(c) shr 2) and 63
        val b6 = (AndroidColor.blue(c) shr 2) and 63
        val rgb = table[(r6 shl 12) or (g6 shl 6) or b6]
        pixels[i] = (a shl 24) or rgb
    }
    Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()
