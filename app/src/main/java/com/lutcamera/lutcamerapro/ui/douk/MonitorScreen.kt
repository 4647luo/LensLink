@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.lutcamera.lutcamerapro.ui.douk

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
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
    val waveform: ImageBitmap? = null,
    val peaking: ImageBitmap? = null
)

/** 全屏监视：点击画面对焦、顶部曝光参数、上下/左右滑出两组圆形工具。 */
@Composable
fun MonitorFullscreen(state: DoukState) {
    val wifiBmp by state.vm.liveViewBitmap.collectAsStateWithLifecycle()
    val usbBmp by state.usb.ptp.liveViewBitmap.collectAsStateWithLifecycle()
    val frame = if (state.usbConnected) usbBmp else wifiBmp

    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp

    var deck by remember { mutableStateOf(MonitorDeck.NONE) }
    var lutOn by remember { mutableStateOf(false) }
    var histOn by remember { mutableStateOf(false) }
    var waveOn by remember { mutableStateOf(false) }
    var peakOn by remember { mutableStateOf(false) }
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
    var rootRect by remember { mutableStateOf(IntOffset.Zero) }

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

    // 直方图 / 波形 / 峰值 / LUT 预览：统一在 IO 线程低频计算，不跟着 30fps 取景帧跑
    LaunchedEffect(Unit) {
        while (isActive) {
            val bmp = latestFrame?.asImageBitmap()
            val selectedLutId = lutId
            val anyOn = histOn || waveOn || peakOn || (lutOn && selectedLutId != null)
            if (bmp != null && anyOn) {
                val result = withContext(Dispatchers.IO) { analyzeFrame(bmp, histOn, waveOn, peakOn) }
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
                    lutOn && selectedLutId != null -> 120L
                    anyOn -> 100L
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
            val scale = min(rootW.toFloat() / visW, rootH.toFloat() / visH)
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
                    if (peakOn && analysis.peaking != null) {
                        Image(
                            bitmap = analysis.peaking!!,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
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
                Canvas(modifier = Modifier.fillMaxSize()) {
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
                    .pointerInput(source, rotation, mirror, dispWpx, dispHpx) {
                        detectTapGestures { pos ->
                            // 点画面：先收起划出来的工具/参数面板和已经打开的监看叠加层，
                            // 没有再对焦，避免面板挡住画面时点不到相机
                            if (topCard || viewCard || pickerKey != null) {
                                topCard = false
                                viewCard = false
                                pickerKey = null
                            } else if (deck != MonitorDeck.NONE) {
                                deck = MonitorDeck.NONE
                            } else if (lutOn || histOn || waveOn || peakOn || audioOn || gridOn) {
                                lutOn = false
                                histOn = false
                                waveOn = false
                                peakOn = false
                                audioOn = false
                                gridOn = false
                            } else {
                                mapTap(pos)?.let { (nx, ny) ->
                                    state.vm.setFocusPoint(nx, ny)
                                    focusMarker = pos
                                    focusTick++
                                }
                            }
                        }
                    }
            )

            focusMarker?.let { marker ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offsetPx(marker.x.toInt(), marker.y.toInt())
                        .size(64.dp)
                        .border(2.dp, Color(0xFF9BFF6A), RoundedCornerShape(8.dp))
                )
            }

            // 顶部曝光条：点一下向画面方向展开一张圆角小卡片
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

            if (landscape) {
                // 横屏：曝光参数竖排在画面左侧，快门固定右侧，互不挡取景
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 10.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xCC111214))
                        .clickable { topCard = !topCard }
                        .padding(horizontal = 11.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(isoText, color = Color.White, fontSize = 12.sp)
                    Text(shutterText, color = Color.White, fontSize = 12.sp)
                    Text(apertureText, color = Color.White, fontSize = 12.sp)
                    Text(evText, color = Color(0xFFB8FF8A), fontSize = 12.sp)
                    Text(meteringText, color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp)
                }
            } else {
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
                    .padding(top = 56.dp, end = 16.dp),
                enter = fadeIn() + slideInVertically { -it / 4 },
                exit = fadeOut() + slideOutVertically { -it / 4 }
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
                    AnimatedVisibility(visible = viewCard, enter = fadeIn(), exit = fadeOut()) {
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
            val toolEnter = if (landscape) slideInHorizontally { it } + fadeIn() else slideInVertically { it } + fadeIn()
            val toolExit = if (landscape) slideOutHorizontally { it } + fadeOut() else slideOutVertically { it } + fadeOut()
            AnimatedVisibility(
                visible = toolSheetVisible,
                modifier = Modifier
                    .align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
                    .then(if (landscape) Modifier.fillMaxHeight() else Modifier.navigationBarsPadding())
                    .padding(bottom = if (landscape) 0.dp else 112.dp)
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
                    MonitorTool("峰值", Icons.Rounded.Bolt, peakOn) { peakOn = !peakOn }
                    MonitorTool("音频", Icons.Rounded.GraphicEq, audioOn) { audioOn = !audioOn }
                    MonitorTool("网格", Icons.Rounded.GridOn, gridOn) { gridOn = !gridOn }
                }
            }

            // 右 / 下滑出的相机参数
            AnimatedVisibility(
                visible = deck == MonitorDeck.CAMERA,
                modifier = Modifier
                    .align(if (landscape) Alignment.CenterStart else Alignment.TopCenter)
                    .then(if (landscape) Modifier.fillMaxHeight() else Modifier.statusBarsPadding())
                    .padding(top = if (landscape) 0.dp else 64.dp),
                enter = if (landscape) slideInHorizontally { -it } + fadeIn() else slideInVertically { -it } + fadeIn(),
                exit = if (landscape) slideOutHorizontally { -it } + fadeOut() else slideOutVertically { -it } + fadeOut()
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
                }
            }

            // LUT 资源选择：贴着工具面板上方，按导入顺序列出
            if (toolSheetVisible && lutOn) {
                Column(
                    modifier = Modifier
                        .align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
                        .then(
                            if (landscape) Modifier.padding(end = 420.dp)
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
                        items(lutEntries, key = { it.id }) { entry ->
                            LutChip(entry.label, lutId == entry.id) { lutId = entry.id }
                        }
                    }
                }
            }

            if (histOn && analysis.histL != null) {
                HistogramPanel(
                    analysis,
                    modifier = Modifier
                        .align(if (landscape) Alignment.TopStart else Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = 14.dp, top = 60.dp)
                )
            }
            if (waveOn && analysis.waveform != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 14.dp)
                        .size(220.dp, 116.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xE6101114))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                ) {
                    Image(analysis.waveform!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
            if (audioOn) {
                AudioPanel(
                    state,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 14.dp)
                )
            }

            // 退出
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

            // 监视画面不再常驻显示相机/镜头型号，取景更干净

            // 快门：竖屏底部居中；横屏固定在右侧（参数面板/工具面板打开时也留在最上层）
            Box(
                modifier = Modifier
                    .align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(
                        end = if (landscape) 24.dp else 0.dp,
                        bottom = if (landscape) 24.dp else DoukSpace.xl
                    )
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

            val key = pickerKey
            if (key != null && !key.isBlank()) {
                MonitorPicker(
                    state = state,
                    key = key,
                    anchor = pickerAnchor,
                    rootW = rootW,
                    rootH = rootH,
                    fixedX = if (landscape && deck == MonitorDeck.CAMERA) {
                        with(density) { 372.dp.roundToPx() }
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
    var pos by remember { mutableStateOf(IntOffset.Zero) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                pos = IntOffset(it.positionInWindow().x.toInt(), it.positionInWindow().y.toInt())
            }
            .clip(RoundedCornerShape(12.dp))
            .clickable { onAnchor(pos) }
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
    anchorAware: ((IntOffset) -> Unit)? = null
) {
    var pos by remember { mutableStateOf(IntOffset.Zero) }
    Column(
        modifier = Modifier
            .width(78.dp)
            .onGloballyPositioned {
                pos = IntOffset(it.positionInWindow().x.toInt(), it.positionInWindow().y.toInt())
            }
            .clip(RoundedCornerShape(18.dp))
            .clickable { anchorAware?.invoke(pos) }
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

@Composable
private fun LutChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
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
            .size(200.dp, 96.dp)
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
    Column(
        modifier = modifier
            .width(188.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE6101114))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("音频", color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
        AudioLine("麦克风", mic)
        AudioLine("录音电平", level)
        AudioLine("风噪消减", wind)
        Text("实时电平：相机 PTP 未上报", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
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
            Spacer(Modifier.height(8.dp))
            if (options.isEmpty()) {
                Text("相机未上报可选项", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(options, key = { it }) { option ->
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

private fun analyzeFrame(src: ImageBitmap, hist: Boolean, wave: Boolean, peak: Boolean): FrameAnalysis {
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
    var peakPixels: IntArray? = if (peak) IntArray(w * h) else null
    val luma = if (wave || peak) IntArray(w * h) else null

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
            if (luma != null) {
                luma[y * w + x] = (r * 299 + g * 587 + b * 114) / 1000
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

    val peakBmp = if (peak && peakPixels != null && luma != null) {
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val gx = (luma[idx + 1] - luma[idx - 1])
                val gy = (luma[idx + w] - luma[idx - w])
                val edge = abs(gx) + abs(gy)
                if (edge > 38) {
                    val a = (edge * 2).coerceIn(0, 190)
                    peakPixels[idx] = (a shl 24) or 0x00FF3B30
                }
            }
        }
        Bitmap.createBitmap(peakPixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    } else null

    return FrameAnalysis(rBins, gBins, bBins, lBins, waveformBmp, peakBmp)
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
