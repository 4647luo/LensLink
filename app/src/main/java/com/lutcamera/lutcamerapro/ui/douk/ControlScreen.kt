package com.lutcamera.lutcamerapro.ui.douk

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import android.graphics.Bitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 参数小卡片的锚点：点哪一行，就在那一行左侧弹出，不再居中挡画面 */
private data class ParamEdit(val param: CameraParam, val anchor: IntOffset)

@Composable
fun ControlScreen(
    state: DoukState,
    onBack: () -> Unit,
    onOpenCameraGallery: () -> Unit = {}
) {
    var editing by remember { mutableStateOf<ParamEdit?>(null) }
    /** 释放模式 → 左侧同时展开的连拍张数卡片（二级卡片，只在本机真的支持 0x5018 时出现） */
    var burstEditing by remember { mutableStateOf<ParamEdit?>(null) }
    var showInterval by remember { mutableStateOf(false) }
    // 拍摄后快速预览：相机拍完、应用把成片存进相册后，直接铺一张全屏看刚拍的那张
    var quickPreview by remember { mutableStateOf<Bitmap?>(null) }
    var quickPreviewName by remember { mutableStateOf("") }
    var lastSeenCapture by remember { mutableStateOf(state.vm.lastCapturedBitmap) }
    var lastPreviewName by remember { mutableStateOf<String?>(null) }
    // 每次进控制页补读一次相机属性，刚连上时漏读的档位会在这里补回来
    LaunchedEffect(Unit) {
        if (state.isConnected) runCatching { state.vm.refreshExtendedProps() }
    }
    LaunchedEffect(state.vm.lastCapturedBitmap) {
        val latest = state.vm.lastCapturedBitmap
        if (latest != null && latest !== lastSeenCapture && DoukPrefs.capturePreview) {
            val name = state.vm.lastCapturedFileName
            // 连拍时队列会一张张处理完；等最后一张落定 450ms 再弹，避免预览停在中间那几张
            delay(450)
            if (state.vm.lastCapturedBitmap !== latest) {
                lastSeenCapture = latest
                return@LaunchedEffect
            }
            // 同一张照片可能先出缩略图、后出成片；文件名相同就不要再弹一次
            if (name != lastPreviewName) {
                quickPreview = latest
                quickPreviewName = name
                lastPreviewName = name
            }
        }
        lastSeenCapture = latest
    }

    // 机身拨到视频档 / 拨回拍照档，界面双向跟着切：控制页、监视页同时换成对应参数。
    // detectedVideoMode 为 null（判断不出来）时不动，避免误刷新抢走用户选择。
    LaunchedEffect(state.detectedVideoMode) {
        // 只做「识别到机身换档就跟随一次」，不锁档：用户随后手动切仍能改
        val detected = state.detectedVideoMode ?: return@LaunchedEffect
        state.followDetectedMode(detected)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // ===== 顶部：标题 + 拍摄/视频分段控件 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DoukSpace.page, end = DoukSpace.sm, top = DoukSpace.sm, bottom = DoukSpace.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "控制",
                    style = DoukType.h1,
                    color = DoukColors.textPrimary
                )
                // 机身实际档位只做提示，不接管界面；界面档位可以自己切
                val detected = state.detectedVideoMode
                Text(
                    text = when (detected) {
                        true -> "机身：视频档"
                        false -> "机身：拍摄档"
                        null -> "机身：未识别到档位"
                    } + if (state.modeManualOverride) " · 已手动切换" else "",
                    style = DoukType.label,
                    color = DoukColors.textTertiary
                )
            }
            ModeSwitch(selected = state.effectiveMode, onSelect = { state.selectMode(it) })
            // 拍摄/视频旁边的「更多」：就在图标下面展开，不弹整屏对话框
            Box {
                var showMore by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { showMore = true },
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(DoukColors.surface.copy(alpha = 0.8f))
                        .border(0.8.dp, DoukColors.outline.copy(alpha = 0.55f), CircleShape)
                ) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "更多",
                        tint = DoukColors.textPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMore,
                    onDismissRequest = { showMore = false },
                    shape = DoukShapes.card,
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .width(268.dp)
                        .clip(DoukShapes.card)
                        .doukCardSurface(DoukShapes.card)
                ) {
                    // 应用接管相机：开关式，关掉就把控制权还给机身
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DoukSpace.md, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("应用接管相机", style = DoukType.body, color = DoukColors.textPrimary)
                            Text(
                                "关掉之后机身按键恢复正常",
                                style = DoukType.label,
                                color = DoukColors.textTertiary
                            )
                        }
                        Switch(
                            checked = state.hostControlTaken,
                            onCheckedChange = { state.setHostControl(it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = DoukColors.accent)
                        )
                    }
                    HorizontalDivider(color = DoukColors.divider)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("刷新相机参数", style = DoukType.body, color = DoukColors.textPrimary)
                                Text(
                                    "重新读取机身当前值；参数被机身拨盘改过时也能对齐",
                                    style = DoukType.label,
                                    color = DoukColors.textTertiary
                                )
                            }
                        },
                        onClick = {
                            showMore = false
                            state.vm.refreshCameraParams()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("间隔拍摄", style = DoukType.body, color = DoukColors.textPrimary)
                                Text(
                                    if (state.intervalRunning) "进行中：${state.intervalProgress}" else "按设定间隔自动按快门，可随时停止",
                                    style = DoukType.label,
                                    color = DoukColors.textTertiary
                                )
                            }
                        },
                        onClick = {
                            showMore = false
                            if (state.intervalRunning) state.stopIntervalShooting() else showInterval = true
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("版权与 IPTC", style = DoukType.body, color = DoukColors.textPrimary)
                                Text(
                                    "打开设置页，可从相机读入",
                                    style = DoukType.label,
                                    color = DoukColors.textTertiary
                                )
                            }
                        },
                        onClick = {
                            showMore = false
                            state.showCopyrightSheet = true
                        }
                    )
                }
            }
        }

        // 页面主体：可滚动参数区 + 悬浮在底栏上方的拍摄按钮
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DoukSpace.page)
        ) {
            if (!state.isConnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(DoukShapes.cardSmall)
                        .doukCardSurface(DoukShapes.cardSmall, tint = DoukColors.warningSoft)
                        .padding(horizontal = DoukSpace.sm, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(DoukColors.warning)
                    )
                    Spacer(modifier = Modifier.width(DoukSpace.xs))
                    Text(
                        "相机未连接，参数区暂不可用",
                        style = DoukType.caption,
                        color = DoukColors.warning
                    )
                }
                Spacer(modifier = Modifier.height(DoukSpace.sm))
            }

            // 监视只有一个入口：点一下开全屏，不再在参数页里塞一块小画面
            MonitorButton(state = state)

            Spacer(modifier = Modifier.height(DoukSpace.xl))
            SectionHead(if (state.effectiveMode == CaptureMode.VIDEO) "视频参数" else "拍摄参数")
            state.exposureLockHint?.let { hint ->
                Spacer(modifier = Modifier.height(DoukSpace.xs))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(DoukShapes.cardSmall)
                        .doukCardSurface(DoukShapes.cardSmall, tint = DoukColors.warningSoft)
                        .padding(horizontal = DoukSpace.sm, vertical = DoukSpace.xs),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = DoukColors.warning,
                        modifier = Modifier.size(14.dp).padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(hint, style = DoukType.label, color = DoukColors.warning)
                }
            }
            if (state.isConnected && state.vm.lastParamMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(DoukSpace.xs))
                Text(
                    "上次操作：${state.vm.lastParamMessage}",
                    style = DoukType.label,
                    color = DoukColors.textTertiary
                )
            }
            if (state.usbOnly) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    "USB PTP 通道：ISO 实时读取，其余曝光项以机身设置为准",
                    style = DoukType.label,
                    color = DoukColors.textTertiary
                )
            }
            Spacer(modifier = Modifier.height(DoukSpace.sm))
            val paramKeys = state.orderedParamKeys(state.effectiveMode)
            if (paramKeys.isEmpty()) {
                Text(
                    "还没有可显示的参数。可在「设置 → 参数排列顺序」里打开被隐藏的功能，或等相机上报参数后再看。",
                    style = DoukType.caption,
                    color = DoukColors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(DoukShapes.cardSmall)
                        .doukCardSurface(DoukShapes.cardSmall)
                        .padding(horizontal = DoukSpace.md, vertical = DoukSpace.md)
                )
            } else {
                // 点开释放模式时，只要相机上报了 0x5018 张数档位，就在左侧同时展开张数卡；
                // 当前不是低速连拍时卡片会提示切到 CL 后才生效，避免“点了没反应”。
                val burstSupported = state.optionsOf("burstCount").isNotEmpty()
                val visible = paramKeys.toSet()
                val groups = state.paramGroupsFor(state.effectiveMode)
                    .map { group -> group.copy(keys = group.keys.filter { it in visible }) }
                    .filter { it.keys.isNotEmpty() }
                // 用户取消勾选、没有归到任何卡的功能不能凭空消失，统一放进「其他」
                val groupedKeys = groups.flatMap { it.keys }.toSet()
                val leftoverKeys = paramKeys.filterNot { it in groupedKeys }
                val renderedGroups = if (leftoverKeys.isEmpty()) groups else {
                    groups + ParamCardGroup("leftover", "其他", leftoverKeys)
                }
                if (renderedGroups.isEmpty()) {
                    // 用户把所有功能都移到空卡、或全部隐藏时的兜底
                    ParamGroup(
                        state,
                        paramKeys,
                        title = if (state.effectiveMode == CaptureMode.VIDEO) "视频参数" else "拍摄参数",
                        onEdit = { p, a ->
                            editing = ParamEdit(p, a)
                            burstEditing = if (p.key == "drive" && burstSupported) {
                                state.cameraParams.firstOrNull { it.key == "burstCount" }
                                    ?.let { ParamEdit(it, a) }
                            } else null
                        }
                    )
                } else {
                    renderedGroups.forEachIndexed { index, group ->
                        ParamGroup(
                            state,
                            group.keys,
                            title = group.name,
                            onEdit = { p, a ->
                                editing = ParamEdit(p, a)
                                burstEditing = if (p.key == "drive" && burstSupported) {
                                    state.cameraParams.firstOrNull { it.key == "burstCount" }
                                        ?.let { ParamEdit(it, a) }
                                } else null
                            }
                        )
                        if (index != renderedGroups.lastIndex) Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }

            // 悬浮快门压在滚动内容上，这里留出与它等高的空白，最后一行参数才不会被挡住
            Spacer(modifier = Modifier.height(170.dp))
        }

        FloatingShutter(
            state = state,
            onOpenGallery = onOpenCameraGallery,
            // 内容已经铺到屏幕最底部，快门要整体抬到透明胶囊上方，别和 Douk 栏叠在一起
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp)
        )
        }
    }

    editing?.let { target ->
        val isDrive = target.param.key == "drive"
        ParamPickerCard(
            state = state,
            target = target,
            stack = 0,
            focusable = true,
            onDismiss = { editing = null; burstEditing = null },
            onPick = { option ->
                val pickedKey = target.param.key
                state.setParam(pickedKey, option)
                // 选到「低速连拍 CL」后，释放模式卡片继续留着，
                // 并在左侧展开「连拍张数」卡；选到其它档位就一起收起。
                if (pickedKey == "drive" &&
                    (option.contains("低速连拍") || option.contains("CL")) &&
                    state.optionsOf("burstCount").isNotEmpty()
                ) {
                    editing = target
                    burstEditing = state.cameraParams.firstOrNull { it.key == "burstCount" }
                        ?.let { ParamEdit(it, target.anchor) }
                } else {
                    editing = null
                    burstEditing = null
                }
            }
        )
    }
    burstEditing?.let { target ->
        ParamPickerCard(
            state = state,
            target = target,
            stack = 1,
            focusable = false,
            maxOptionsHeight = 250.dp,
            onDismiss = { burstEditing = null },
            onPick = { option ->
                // 直接选张数时，如果当前不在低速连拍，顺手切到 CL，
                // 选完立刻显示「低速连拍 CL ｜ N 张」，不会出现选了没反应。
                val currentDrive = state.valueOf("drive")
                if (!currentDrive.contains("低速连拍") && !currentDrive.contains("CL")) {
                    state.optionsOf("drive")
                        .firstOrNull { it.contains("低速连拍") || it.contains("CL") }
                        ?.let { state.setParam("drive", it) }
                }
                state.setParam("burstCount", option)
                editing = null
                burstEditing = null
            }
        )
    }

    // 拍完直接看刚拍的那张：全屏黑底，点右上角关闭；不打断下一次快门
    quickPreview?.let { bmp ->
        Dialog(
            onDismissRequest = { quickPreview = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = quickPreviewName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { quickPreview = null }
                )
                IconButton(
                    onClick = { quickPreview = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(DoukSpace.md)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "关闭预览",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = DoukSpace.xl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        quickPreviewName.ifBlank { "刚拍摄的照片" },
                        style = DoukType.label,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "已保存到手机相册",
                        style = DoukType.caption,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
    if (showInterval) {
        IntervalShootDialog(
            running = state.intervalRunning,
            progress = state.intervalProgress,
            onDismiss = { showInterval = false },
            onStart = { interval, total ->
                state.startIntervalShooting(interval, total)
            },
            onStop = { state.stopIntervalShooting() }
        )
    }
}

/** 间隔拍摄设置：应用侧按秒循环按快门，不伪装成相机菜单里的定时器 */
@Composable
private fun IntervalShootDialog(
    running: Boolean,
    progress: String,
    onDismiss: () -> Unit,
    onStart: (Int, Int) -> Unit,
    onStop: () -> Unit
) {
    var interval by remember { mutableStateOf("10") }
    var total by remember { mutableStateOf("10") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .doukCardSurface(RoundedCornerShape(24.dp))
                .padding(DoukSpace.lg),
            verticalArrangement = Arrangement.spacedBy(DoukSpace.sm)
        ) {
            Text("间隔拍摄", style = DoukType.h2, color = DoukColors.textPrimary)
            Text(
                "应用按设定间隔自动按快门；相机会按当前释放模式拍摄。",
                style = DoukType.label,
                color = DoukColors.textTertiary
            )
            OutlinedTextField(
                value = interval,
                onValueChange = { interval = it.filter { ch -> ch.isDigit() }.take(4) },
                label = { Text("间隔（秒）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = total,
                onValueChange = { total = it.filter { ch -> ch.isDigit() }.take(4) },
                label = { Text("总张数") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            if (progress.isNotBlank()) {
                Text(progress, style = DoukType.label, color = DoukColors.accent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DoukSpace.sm)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("关闭", color = DoukColors.textSecondary)
                }
                if (running) {
                    TextButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                        Text("停止", color = DoukColors.warning)
                    }
                } else {
                    TextButton(
                        onClick = {
                            onStart(interval.toIntOrNull() ?: 10, total.toIntOrNull() ?: 10)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("开始", color = DoukColors.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
@Composable
private fun ModeSwitch(selected: CaptureMode, onSelect: (CaptureMode) -> Unit) {
    Row(
        modifier = Modifier
            .clip(DoukShapes.pill)
            .background(DoukColors.surfaceMuted)
            .padding(3.dp)
    ) {
        CaptureMode.entries.forEach { mode ->
            val active = mode == selected
            Box(
                modifier = Modifier
                    .clip(DoukShapes.pill)
                    .background(if (active) DoukColors.surface else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 15.dp, vertical = 6.dp)
            ) {
                Text(
                    mode.label,
                    style = DoukType.caption,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) DoukColors.accent else DoukColors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun SectionHead(text: String) {
    Text(text = text, style = DoukType.section, color = DoukColors.textSecondary)
}

/**
 * 监视入口：一行按钮，点开就是全屏取景。
 * 取景画面本身不在参数页里渲染，省下这块高度给参数行，也避免小画面反复重绘。
 */
@Composable
private fun MonitorButton(state: DoukState) {
    val wifiBmp by state.vm.liveViewBitmap.collectAsStateWithLifecycle()
    val usbBmp by state.usb.ptp.liveViewBitmap.collectAsStateWithLifecycle()
    val hasFrame = if (state.usbConnected) usbBmp != null else wifiBmp != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .doukShadow(DoukElevation.raised, DoukShapes.card)
            .clip(DoukShapes.card)
            .doukCardSurface(DoukShapes.card)
            .clickable { state.previewFullscreen = true }
            .padding(horizontal = DoukSpace.md, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(DoukColors.accentSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Videocam,
                contentDescription = "打开全屏监视",
                tint = DoukColors.accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(DoukSpace.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "监视",
                style = DoukType.title,
                color = DoukColors.textPrimary
            )
            Text(
                text = when {
                    !state.isConnected -> "未连接相机"
                    hasFrame -> "点开全屏取景 · ${state.profile.model}"
                    else -> "已连接 · 点开全屏取景 · ${state.profile.model}"
                },
                style = DoukType.label,
                color = DoukColors.textTertiary
            )
        }
        Icon(
            Icons.Rounded.Fullscreen,
            contentDescription = null,
            tint = DoukColors.textTertiary,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 悬浮拍摄条：贴在底栏上方，左侧是最新照片缩略图，中间大圆快门，右侧是对焦 */
@Composable
private fun FloatingShutter(
    state: DoukState,
    onOpenGallery: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVideo = state.effectiveMode == CaptureMode.VIDEO
    val recording = state.isRecordingNow
    val accent = if (isVideo) Color(0xFFE5484D) else DoukColors.accent
    val soft = if (isVideo) Color(0x1FE5484D) else DoukColors.accentSoft

    // 刚回传的成片优先；机身列表按日期取真正最新的一张，绝不能用 lastOrNull（列表是「最新在前」）
    val lastCaptured = state.vm.lastCapturedBitmap
    val capturedName = state.vm.lastCapturedFileName
    val capturedAt = state.vm.lastCapturedAt
    val newest = state.cameraPhotos.maxByOrNull { it.dateModified }
    // 机身列表可能还没刷新出刚拍的文件；只要列表里最新一张不是应用刚回传的那张，
    // 就先信任 lastCaptured，避免缩略图一直停在旧照片上。
    val preferNewest = lastCaptured == null ||
        (newest != null && newest.fileName != capturedName && newest.dateModified > capturedAt)
    val thumb by produceState<ImageBitmap?>(
        if (!preferNewest) lastCaptured?.asImageBitmap() else null,
        lastCaptured,
        newest?.handle,
        preferNewest,
        state.usbConnected
    ) {
        value = if (!preferNewest) {
            lastCaptured?.asImageBitmap()
        } else newest?.let {
            if (state.usbConnected) state.usbThumbBitmap(it.handle)
            else state.wifiThumbBitmap(it.handle)
        }
    }

    var showTimerMenu by remember { mutableStateOf(false) }
    val timerLabel = when {
        state.selfTimerCountdown > 0 -> "${state.selfTimerCountdown}"
        state.selfTimerSeconds > 0 -> "${state.selfTimerSeconds}s"
        else -> "定时"
    }

    Row(
        modifier = modifier
            .doukShadow(DoukElevation.raised, DoukShapes.pill)
            .clip(DoukShapes.pill)
            .doukCardSurface(DoukShapes.pill)
            .padding(horizontal = DoukSpace.md, vertical = DoukSpace.xs),
        horizontalArrangement = Arrangement.spacedBy(DoukSpace.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DoukColors.surfaceMuted)
                .clickable(enabled = state.isConnected) { onOpenGallery() },
            contentAlignment = Alignment.Center
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb!!,
                    contentDescription = "最新照片，打开相机内图库",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Rounded.PhotoLibrary,
                    contentDescription = "打开相机内图库",
                    tint = DoukColors.textTertiary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(66.dp)
                .clip(CircleShape)
                .background(soft)
                .padding(5.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(if (recording) RoundedCornerShape(8.dp) else CircleShape)
                    .background(if (recording) Color(0xFFB3261E) else accent)
                    .clickable { state.shoot() }
            )
        }

        Box {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(
                        if (state.selfTimerCountdown > 0) DoukColors.accentSoft
                        else DoukColors.surfaceMuted
                    )
                    .clickable(enabled = state.isConnected) {
                        if (state.selfTimerCountdown > 0) state.cancelSelfTimer()
                        else showTimerMenu = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    timerLabel,
                    style = DoukType.label,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.selfTimerSeconds > 0 || state.selfTimerCountdown > 0)
                        DoukColors.accent else DoukColors.textSecondary
                )
            }
            DropdownMenu(
                expanded = showTimerMenu,
                onDismissRequest = { showTimerMenu = false },
                shape = RoundedCornerShape(18.dp),
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .width(176.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .doukCardSurface(RoundedCornerShape(18.dp))
            ) {
                listOf(0 to "立即拍摄", 2 to "2 秒", 5 to "5 秒", 10 to "10 秒", 20 to "20 秒")
                    .forEach { (seconds, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    style = if (state.selfTimerSeconds == seconds) DoukType.title else DoukType.body,
                                    color = if (state.selfTimerSeconds == seconds) DoukColors.accent
                                    else DoukColors.textPrimary
                                )
                            },
                            onClick = {
                                showTimerMenu = false
                                state.selfTimerSeconds = seconds
                                state.shootWithTimer(seconds)
                            }
                        )
                    }
            }
        }
    }
}

/** 全屏取景：挂在 DoukRoot 的最外层渲染，盖住底栏与系统栏才是真正的「全屏」 */
@Composable
fun FullscreenPreview(state: DoukState) {
    MonitorFullscreen(state)
}

/**
 * 参数选择卡片：贴着被点行的右侧往左展开，圆角、带阴影，不再用居中 AlertDialog。
 * 位置按窗口坐标算，靠近屏幕边缘时自动上下/左右收进可视区。
 */
@Composable
private fun ParamPickerCard(
    state: DoukState,
    target: ParamEdit,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    stack: Int = 0,
    focusable: Boolean = true,
    maxOptionsHeight: androidx.compose.ui.unit.Dp = 420.dp
) {
    val density = LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val windowHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val cardWidth = 276.dp
    val cardWidthPx = with(density) { cardWidth.roundToPx() }
    val options = state.optionsOf(target.param.key)
    val lock = state.paramLockReason(target.param.key)
    val estimatedHeightPx = with(density) {
        (92.dp + maxOptionsHeight + if (lock != null) 58.dp else 0.dp).roundToPx()
    }
    val gapPx = with(density) { 12.dp.roundToPx() }
    val x = (target.anchor.x - cardWidthPx * (stack + 1) - gapPx * (stack + 1))
        .coerceAtLeast(with(density) { 8.dp.roundToPx() })
    val y = target.anchor.y.coerceIn(
        with(density) { 20.dp.roundToPx() },
        (windowHeightPx - estimatedHeightPx - with(density) { 20.dp.roundToPx() })
            .coerceAtLeast(with(density) { 20.dp.roundToPx() })
    )
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(x, y),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = focusable)
    ) {
        Column(
            modifier = Modifier
                .width(cardWidth)
                .doukShadow(DoukElevation.raised, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .doukCardSurface(RoundedCornerShape(22.dp))
                .padding(vertical = DoukSpace.sm)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DoukSpace.md, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    target.param.label,
                    style = DoukType.title,
                    color = DoukColors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    state.valueOf(target.param.key),
                    style = DoukType.label,
                    color = DoukColors.textTertiary
                )
            }
            if (lock != null) {
                Text(
                    lock,
                    style = DoukType.label,
                    color = DoukColors.warning,
                    modifier = Modifier
                        .padding(horizontal = DoukSpace.sm, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DoukColors.warningSoft)
                        .padding(horizontal = DoukSpace.sm, vertical = 8.dp)
                )
            }
            if (options.isEmpty()) {
                Text(
                    if (state.usbOnly) "USB PTP 通道没有读到该项档位，请在机身上调整"
                    else "相机未上报这项的可选档位，只能在机身上调",
                    style = DoukType.caption,
                    color = DoukColors.textSecondary,
                    modifier = Modifier.padding(horizontal = DoukSpace.md, vertical = DoukSpace.sm)
                )
            }
            Column(
                modifier = Modifier
                    .heightIn(max = maxOptionsHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                val current = state.valueOf(target.param.key)
                options.forEach { option ->
                    val active = option == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (active) DoukColors.accentSoft else Color.Transparent)
                            .clickable(enabled = lock == null) { onPick(option) }
                            .padding(horizontal = DoukSpace.sm, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            option,
                            style = if (active) DoukType.title else DoukType.body,
                            color = when {
                                lock != null -> DoukColors.textTertiary
                                active -> DoukColors.accent
                                else -> DoukColors.textPrimary
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (active) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = if (lock != null) DoukColors.textTertiary else DoukColors.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 参数组：左名称、右当前值、尾部 chevron，行间细分割线；不可写的行置灰并标「机身锁定」 */
@Composable
private fun ParamGroup(
    state: DoukState,
    keys: List<String>,
    title: String? = null,
    onEdit: (CameraParam, IntOffset) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DoukShapes.cardSmall)
            .doukCardSurface(DoukShapes.cardSmall)
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                title,
                style = DoukType.label,
                fontWeight = FontWeight.SemiBold,
                color = DoukColors.textTertiary,
                modifier = Modifier.padding(
                    start = DoukSpace.md,
                    end = DoukSpace.md,
                    top = 10.dp,
                    bottom = 4.dp
                )
            )
        }
        keys.forEachIndexed { index, key ->
            val param = state.cameraParams.firstOrNull { it.key == key }
            // 不能用 return@forEachIndexed 跳过：Compose 的 group 会因此不配对，
            // 后续重组直接 ArrayIndexOutOfBoundsException（IntStack.peek2）。
            if (param != null) {
            val locked = state.paramLockReason(key) != null
            // 坐标只给点击时用，不写进 Compose State：滚动时不会因为每帧
            // onGloballyPositioned 回写 IntOffset 而触发整行/整组重组。
            val rowAnchor = remember(key) { IntArray(2) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        val pos = coords.localToWindow(Offset.Zero)
                        rowAnchor[0] = pos.x.roundToInt() + coords.size.width
                        rowAnchor[1] = pos.y.roundToInt()
                    }
                    .clickable { onEdit(param, IntOffset(rowAnchor[0], rowAnchor[1])) }
                    .padding(start = DoukSpace.md, end = DoukSpace.sm, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    param.label,
                    style = DoukType.body,
                    color = DoukColors.textSecondary,
                    modifier = Modifier.weight(1f)
                )
                if (locked) {
                    Text(
                        "机身",
                        style = DoukType.label,
                        color = DoukColors.textTertiary,
                        modifier = Modifier
                            .clip(DoukShapes.pill)
                            .background(DoukColors.surfaceMuted)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    state.valueOf(param.key),
                    style = DoukType.value,
                    color = if (locked) DoukColors.textTertiary else DoukColors.textPrimary
                )
                Spacer(modifier = Modifier.width(DoukSpace.xxs))
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = DoukColors.textTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (index != keys.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DoukSpace.md)
                        .height(1.dp)
                        .background(DoukColors.divider)
                )
            }
            }
        }
    }
}