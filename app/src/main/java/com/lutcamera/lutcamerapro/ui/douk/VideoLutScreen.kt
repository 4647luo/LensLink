package com.lutcamera.lutcamerapro.ui.douk

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.provider.MediaStore
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.lutcamera.lutcamerapro.gpu.VideoLutExporter
import com.lutcamera.lutcamerapro.gpu.VideoLutRenderCore
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.lutcamera.lutcamerapro.log.DoukLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 视频播放 / 套 LUT 导出页。
 *
 * 播放链路：MediaPlayer → SurfaceTexture → GLSurfaceView（ES3 取外置纹理，套 3D LUT 后上屏）。
 * 所以 MOV、HEVC 10-bit、N-Log / HLG 这类灰片都能在手机里直接看到还原后的颜色，
 * 不需要先交给别的播放器，也不会被系统播放器直接拒掉。
 *
 * 导出链路：MediaCodec 解码 → GL 套 LUT → MediaCodec 编码 → MediaMuxer，输出 MP4（H.264 + 原 AAC 音轨）。
 */
@Composable
internal fun VideoLutScreen(
    item: GalleryItem,
    startInLutMode: Boolean = true,
    onDismiss: () -> Unit,
    onExported: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var file by remember(item.stableKey) { mutableStateOf<File?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val glView = remember { VideoLutGlView(context) }
    var surfaceReady by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf(false) }

    var lutEntries by remember { mutableStateOf<List<FilterEntry>>(emptyList()) }
    var lutRuntime by remember { mutableStateOf<FilterRuntime?>(null) }
    var lutId by remember { mutableStateOf<String?>(null) }
    // true 才是调色工作台（LUT 条 + 强度 + 导出）；false 就是纯播放器
    var lutMode by remember(item.stableKey) { mutableStateOf(startInLutMode) }
    var intensity by remember { mutableFloatStateOf(1f) }
    var exporting by remember { mutableStateOf(false) }
    var exportPercent by remember { mutableIntStateOf(0) }
    // 导出参数：跟开源剪辑软件一样，先把分辨率/编码/质量选清楚再导
    var exportSheet by remember { mutableStateOf(false) }
    var exportRes by remember { mutableStateOf("原始") }
    var exportCodec by remember { mutableStateOf("自动") }
    var exportQuality by remember { mutableStateOf("高") }
    var exportBitrate by remember { mutableStateOf("高") }
    // 「选一段 → Live 图」：开始/结束都用毫秒，默认取当前播放位置往后 3 秒
    var liveSheet by remember { mutableStateOf(false) }
    var liveStartMs by remember { mutableIntStateOf(0) }
    var liveEndMs by remember { mutableIntStateOf(0) }
    var liveExporting by remember { mutableStateOf(false) }

    // 文件准备：应用内直接读；手机相册的视频先复制到 cache
    LaunchedEffect(item.stableKey) {
        val resolved = withContext(Dispatchers.IO) { runCatching { resolveVideoFile(context, item) }.getOrNull() }
        file = resolved
        if (resolved == null) errorText = "这个视频还在相机里，先点「保存」存到手机再播放"
    }

    // 资源里能用于视频的 LUT
    LaunchedEffect(Unit) {
        DoukLog.d(
            "VideoLut", "THEME",
            "accent" to Integer.toHexString(DoukColors.accent.toArgb()),
            "glass" to DoukColors.glass.toString()
        )
        val loaded = withContext(Dispatchers.IO) { runCatching { FilterLibrary.load(context) }.getOrNull() }
        lutEntries = (loaded?.entries ?: emptyList()).filter { it.forVideo }
        lutRuntime = loaded?.runtime
    }

    fun applyLut(id: String?) {
        lutId = id
        glView.setLut(if (id == null) null else lutRuntime?.lut(id))
    }

    fun togglePlay() {
        val p = player ?: return
        runCatching {
            if (p.isPlaying) {
                p.pause()
                playing = false
            } else {
                p.start()
                playing = true
            }
        }.onFailure { errorText = "播放失败：${it.message}" }
    }

    // 等 GL 线程把 SurfaceTexture 建好，再交给 MediaPlayer
    DisposableEffect(file, surfaceReady) {
        val f = file
        if (f == null || !surfaceReady) {
            onDispose { }
        } else {
            val mp = runCatching { MediaPlayer() }.getOrNull()
            if (mp == null) {
                errorText = "无法创建视频播放器"
                onDispose { }
            } else {
                player = mp
                runCatching {
                    mp.setDataSource(context, Uri.fromFile(f))
                    mp.setSurface(Surface(glView.surfaceTexture))
                    mp.setOnPreparedListener {
                        durationMs = it.duration
                        glView.setFrameSize(it.videoWidth, it.videoHeight)
                        it.setScreenOnWhilePlaying(true)
                        it.start()
                        playing = true
                    }
                    mp.setOnCompletionListener {
                        playing = false
                        positionMs = durationMs
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        errorText = "系统解码器打不开这个视频（what=$what extra=$extra），可以点右上角交给外部播放器"
                        playing = false
                        true
                    }
                    mp.prepareAsync()
                }.onFailure {
                    errorText = "无法打开视频：${it.message}"
                }
                onDispose {
                    runCatching { mp.stop() }
                    runCatching { mp.release() }
                    player = null
                    playing = false
                }
            }
        }
    }

    // 进度轮询
    LaunchedEffect(playing) {
        while (isActive && playing) {
            if (!seeking) runCatching { positionMs = player?.currentPosition ?: 0 }
            delay(300)
        }
    }

    fun startLiveExport() {
        val f = file ?: return
        if (exporting || liveExporting) return
        val start = liveStartMs.coerceIn(0, maxOf(0, durationMs - 500))
        val end = liveEndMs.coerceIn(start + 500, maxOf(start + 500, durationMs))
        liveExporting = true
        exporting = true
        exportPercent = 0
        message = null
        runCatching { player?.pause(); playing = false }
        val options = VideoLutExporter.ExportOptions(
            // Live 图的视频部分通常跟相册/分享平台按 1080p 处理；保留 1920 比原片 4K 小很多，
            // 但细看仍比 720p 清楚。封面图另取原视频帧，不跟着压。
            maxLongSide = 1920,
            codec = "auto",
            quality = "high",
            bitrateScale = 0.95f,
            startMs = start.toLong(),
            endMs = end.toLong()
        )
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES
                        ),
                        "连镜"
                    )
                    if (!dir.exists()) dir.mkdirs()
                    val base = f.nameWithoutExtension
                    var name = "${base}_live.jpg"
                    var i = 2
                    while (File(dir, name).exists()) {
                        name = "${base}_live${i}.jpg"
                        i++
                    }
                    val target = File(dir, name)
                    val tempMp4 = File(context.cacheDir, "live_${System.currentTimeMillis()}.mp4")
                    try {
                        val chosenLut = lutId
                        VideoLutExporter.export(
                            input = f,
                            output = tempMp4,
                            lut = if (chosenLut == null) null else lutRuntime?.lut(chosenLut),
                            intensity = intensity,
                            options = options
                        ) { p ->
                            scope.launch { exportPercent = p }
                        }.getOrThrow()
                        MotionPhotoWriter.write(tempMp4, target, coverAtUs = 200_000L).getOrThrow()
                        target
                    } catch (t: Throwable) {
                        runCatching { target.delete() }
                        throw t
                    } finally {
                        runCatching { tempMp4.delete() }
                    }
                }
            }
            liveExporting = false
            exporting = false
            out.onSuccess { target ->
                DoukLog.i(
                    "MotionPhoto", "LIVE_EXPORTED",
                    "file" to target.name, "start" to start, "end" to end
                )
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(target.absolutePath), arrayOf("image/jpeg"), null
                )
                message = "已生成 Live 图：${target.name}（长按可播放）"
                onExported(target.name)
            }.onFailure {
                message = "Live 图生成失败：${it.message ?: "未知错误"}"
            }
        }
    }

    fun startExport() {
        val f = file ?: return
        if (exporting) return
        exporting = true
        exportPercent = 0
        message = null
        val options = VideoLutExporter.ExportOptions(
            maxLongSide = when (exportRes) {
                "4K" -> 3840
                "1080P" -> 1920
                else -> 0
            },
            codec = when (exportCodec) {
                "H.265" -> "hevc"
                "H.264" -> "avc"
                else -> "auto"
            },
            quality = if (exportQuality == "高") "high" else "standard",
            bitrateScale = when (exportBitrate) {
                "省空间" -> 0.8f
                "极高" -> 1.5f
                else -> 1.0f
            }
        )
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES
                        ),
                        "连镜"
                    )
                    if (!dir.exists()) dir.mkdirs()
                    val base = f.nameWithoutExtension
                    var name = "${base}_lut.mp4"
                    var i = 2
                    while (File(dir, name).exists()) {
                        name = "${base}_lut${i}.mp4"
                        i++
                    }
                    val target = File(dir, name)
                    val chosenLut = lutId
                    VideoLutExporter.export(
                        input = f,
                        output = target,
                        lut = if (chosenLut == null) null else lutRuntime?.lut(chosenLut),
                        intensity = intensity,
                        options = options
                    ) { p ->
                        scope.launch { exportPercent = p }
                    }.getOrThrow()
                }
            }
            exporting = false
            out.onSuccess { exported ->
                DoukLog.i("VideoLut", "EXPORTED", "file" to exported.name)
                withContext(Dispatchers.IO) { writePoster(context, exported) }
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(exported.absolutePath), arrayOf("video/mp4"), null
                )
                onExported(exported.name)
            }.onFailure {
                message = "导出失败：${it.message ?: "未知错误"}"
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                glView.also { view -> view.onSurfaceReady = { surfaceReady = true } }
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.fileName,
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        when {
                            !lutMode -> "视频 · 直接播放"
                            item.formatSet.contains("mov") -> "MOV · 还原 LUT 预览"
                            else -> "视频 · 还原 LUT 预览"
                        },
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = { lutMode = !lutMode }) {
                    Icon(
                        Icons.Rounded.Tune,
                        contentDescription = if (lutMode) "收起调色" else "打开调色",
                        tint = if (lutMode) DoukColors.accent else Color.White
                    )
                }
                IconButton(onClick = {
                    val f = file ?: return@IconButton
                    runCatching {
                        // FileProvider 只暴露了 cache/video-preview，直接把原路径（
                        // Pictures/连镜 或机身缓存）丢给系统播放器会抛 IllegalArgumentException。
                        // 统一先复制一份只读副本到 cache/video-preview 再分享。
                        val shareFile = prepareShareCopy(context, f)
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(
                                    FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider", shareFile
                                    ),
                                    "video/*"
                                )
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        )
                    }.onFailure { message = "没有可用的外部播放器" }
                }) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = "用系统播放器", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            errorText?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = Color(0xFFFFB4A2),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
            message?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = Color(0xFFFFD9A0),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { togglePlay() }) {
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Text(timeText(positionMs), fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                    Slider(
                        value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                        onValueChange = { v ->
                            seeking = true
                            positionMs = (v * durationMs).toInt()
                        },
                        onValueChangeFinished = {
                            runCatching { player?.seekTo(positionMs) }
                            seeking = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = DoukColors.accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                        )
                    )
                    Text(timeText(durationMs), fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                }

                if (lutMode) {
                val currentLut = lutId
                Text(
                    when {
                        lutRuntime == null -> "资源读取中…"
                        currentLut == null -> "当前：原片（灰片未还原）"
                        else -> "当前：${currentLut.removePrefix("res:")}"
                    },
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { VideoChip("原片", lutId == null) { applyLut(null) } }
                    items(lutEntries, key = { it.id }, contentType = { "lut-entry" }) { entry ->
                        VideoChip(entry.label, lutId == entry.id) { applyLut(entry.id) }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Text("强度", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                    Slider(
                        value = intensity,
                        onValueChange = {
                            intensity = it
                            glView.setLutIntensity(it)
                        },
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = DoukColors.accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                        )
                    )
                    Text("${(intensity * 100).toInt()}%", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                }

                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (exporting) Color.White.copy(alpha = 0.14f) else DoukColors.accent)
                        .clickable(enabled = !exporting) { exportSheet = true }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when {
                            liveExporting -> "生成 Live 图… $exportPercent%"
                            exporting -> "导出中… $exportPercent%"
                            lutId == null -> "导出视频（原片色彩）"
                            else -> "套 LUT 并导出视频"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (exporting) Color.White.copy(alpha = 0.8f) else Color.White
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = if (exporting) 0.08f else 0.13f))
                        .clickable(enabled = !exporting && durationMs > 0) {
                            val tapped = positionMs.coerceIn(0, maxOf(0, durationMs - 500))
                            val defaultEnd = minOf(durationMs, tapped + 3_000)
                                .coerceAtLeast(minOf(durationMs, tapped + 500))
                            // 从片尾点进来时不要只截到 0.5 秒：向前补足 3 秒，保证默认片段符合建议长度。
                            val defaultStart = if (durationMs >= 3_000 && defaultEnd - tapped < 3_000) {
                                (defaultEnd - 3_000).coerceAtLeast(0)
                            } else tapped
                            liveStartMs = defaultStart
                            liveEndMs = defaultEnd
                            liveSheet = true
						}
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "选片段 · 转为 Live 图",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = if (exporting) 0.45f else 0.9f)
                    )
                }
                }
            }
        }

        if (exportSheet) {
            ExportOptionsDialog(
                res = exportRes,
                codec = exportCodec,
                quality = exportQuality,
                bitrate = exportBitrate,
                onRes = { exportRes = it },
                onCodec = { exportCodec = it },
                onQuality = { exportQuality = it },
                onBitrate = { exportBitrate = it },
                onDismiss = { exportSheet = false },
                onStart = {
                    exportSheet = false
                    startExport()
                }
            )
        }

        if (liveSheet) {
            LiveClipDialog(
                durationMs = durationMs,
                startMs = liveStartMs,
                endMs = liveEndMs,
                onStartChanged = {
                    liveStartMs = it
                    if (liveEndMs < it + 500) {
                        liveEndMs = minOf(durationMs, it + 500)
                    }
                },
                onEndChanged = { liveEndMs = it },
                onDismiss = { liveSheet = false },
                onConfirm = {
                    liveSheet = false
                    startLiveExport()
                }
            )
        }
    }
}

/** 选一段视频转 Live 图：两个滑杆选开始/结束，实时显示时长。 */
@Composable
private fun LiveClipDialog(
    durationMs: Int,
    startMs: Int,
    endMs: Int,
    onStartChanged: (Int) -> Unit,
    onEndChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val safeDuration = durationMs.coerceAtLeast(1)
    val startMin = 0f
    val startMax = (safeDuration - 500).coerceAtLeast(500).toFloat()
    val endMin = (startMs + 500).coerceAtMost(safeDuration).toFloat()
    val endMax = safeDuration.toFloat()
    val selectedSec = ((endMs - startMs).coerceAtLeast(0) / 1000f)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1D1B),
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = 0.8f),
        title = { Text("转为 Live 图", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "选一段画面和声音做成动态照片；建议 3–10 秒，太长文件会很大。",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("开始", fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.width(40.dp))
                    Slider(
                        value = startMs.toFloat().coerceIn(startMin, startMax),
                        onValueChange = { onStartChanged(it.toInt()) },
                        valueRange = startMin..startMax,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = DoukColors.accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.20f)
                        )
                    )
                    Text(timeText(startMs), fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("结束", fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.width(40.dp))
                    Slider(
                        value = endMs.toFloat().coerceIn(endMin, endMax),
                        onValueChange = { onEndChanged(it.toInt().coerceAtLeast(startMs + 500)) },
                        valueRange = endMin..endMax,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = DoukColors.accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.20f)
                        )
                    )
                    Text(timeText(endMs), fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
                }
                Text(
                    "片段长度：${String.format(java.util.Locale.US, "%.1f", selectedSec)} 秒",
                    fontSize = 12.sp,
                    color = DoukColors.accent,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "封面取片段开头约 0.2 秒的一帧；在「连镜」图库或系统相册里长按照片即可播放。",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = endMs - startMs >= 500) {
                Text("生成", color = DoukColors.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Color.White.copy(alpha = 0.7f)) }
        }
    )
}

/** 导出参数：分辨率 / 编码 / 质量，跟常见开源剪辑软件的导出面板一个思路。 */
@Composable
private fun ExportOptionsDialog(
    res: String,
    codec: String,
    quality: String,
    bitrate: String,
    onRes: (String) -> Unit,
    onCodec: (String) -> Unit,
    onQuality: (String) -> Unit,
    onBitrate: (String) -> Unit,
    onDismiss: () -> Unit,
    onStart: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1D1B),
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = 0.8f),
        title = { Text("导出设置", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OptionRow("分辨率", listOf("原始", "4K", "1080P"), res, onRes)
                Spacer(modifier = Modifier.height(10.dp))
                OptionRow("编码", listOf("自动", "H.265", "H.264"), codec, onCodec)
                Spacer(modifier = Modifier.height(10.dp))
                OptionRow("画质", listOf("高", "标准"), quality, onQuality)
                Spacer(modifier = Modifier.height(10.dp))
                OptionRow("码率", listOf("省空间", "高", "极高"), bitrate, onBitrate)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    when (bitrate) {
                        "省空间" -> "省空间：默认码率打八折，体积最小，发聊天软件够用"
                        "极高" -> "极高：默认码率 ×1.5，4K 灰片的大面积渐变更干净，文件也最大"
                        else -> "高：H.265 约 0.15 bpp、H.264 约 0.24 bpp，4K 可到 90 Mbps 以上"
                    },
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.45f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "导出会同时生成一张同名 JPG 封面，图库里不再是一格灰块",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.35f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onStart) {
                Text("开始导出", color = DoukColors.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Color.White.copy(alpha = 0.7f)) }
        }
    )
}

@Composable
private fun OptionRow(title: String, values: List<String>, current: String, onPick: (String) -> Unit) {
    Column {
        Text(title, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp)
        ) {
            values.forEach { v ->
                val active = v == current
                Text(
                    v,
                    fontSize = 12.sp,
                    color = if (active) Color.White else Color.White.copy(alpha = 0.75f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (active) DoukColors.accent else Color.White.copy(alpha = 0.10f))
                        .clickable { onPick(v) }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

/** 导出后写一张封面图：系统相册与应用图库都靠它显示缩略图，不再是一片灰格。 */
private fun writePoster(context: Context, video: File) {
    runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val frame = retriever.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0)
                ?: return@runCatching
            val poster = File(video.parentFile, video.nameWithoutExtension + ".jpg")
            poster.outputStream().use { out ->
                frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
            }
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(poster.absolutePath), arrayOf("image/jpeg"), null
            )
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/** 视频播放用 GLSurfaceView：解码帧当外置纹理，套 LUT 后上屏。 */
internal class VideoLutGlView(context: Context) : GLSurfaceView(context) {

    private val core = VideoLutRenderCore(1920, 1080)
    private var lutIntensity = 1f
    private var coreReady = false

    // 解码帧回调不能挂在主线程：主线程一被 Compose 重组/进度条刷新占住，
    // 帧就排到后面才被消费，看起来就是一卡一抽。这里单开一条只有 Looper 的线程。
    private val frameThread = HandlerThread("lut-video-frames").apply { start() }
    private val frameHandler = Handler(frameThread.looper)

    // 播放帧率实测：每 2 秒打一条，判断到底是解码慢还是上屏慢
    private var fpsFrames = 0
    private var fpsWindowStart = SystemClock.uptimeMillis()

    /** GL 线程建好 SurfaceTexture 后回调（在主线程） */
    var onSurfaceReady: (() -> Unit)? = null

    val surfaceTexture: SurfaceTexture
        get() = core.surfaceTexture

    private val renderer = object : Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            runCatching { core.init(frameHandler) }
                .onSuccess {
                    coreReady = true
                    core.onFrameAvailable = { requestRender() }
                    requestRender()
                    post { onSurfaceReady?.invoke() }
                }
                .onFailure { DoukLog.w("VideoLut", "GL_INIT_FAIL", "err" to it.message) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES30.glViewport(0, 0, width, height)
            core.setSurfaceSize(width, height)
            core.setFitAspect(true)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (!coreReady) return
            // 只画解码器送来的那一帧：没有新帧就什么都不做。
            // 以前连续模式每 vsync 都 updateTexImage，等于把同一帧反复上传，
            // 解码稍有突发就跟不上，画面就会「抽搐」。
            if (!core.awaitFrame(0)) return
            runCatching { core.draw(System.nanoTime(), lutIntensity) }
            fpsFrames++
            val now = SystemClock.uptimeMillis()
            if (now - fpsWindowStart >= 2000) {
                DoukLog.d(
                    "VideoLut", "PLAY_FPS",
                    "fps" to (fpsFrames * 1000f / (now - fpsWindowStart)).toInt()
                )
                fpsFrames = 0
                fpsWindowStart = now
            }
        }
    }

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        // 按需渲染：每来一帧画一次，节奏跟解码帧完全一致，也不空转耗电。
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }

    override fun onDetachedFromWindow() {
        runCatching { frameThread.quitSafely() }
        super.onDetachedFromWindow()
    }

    fun setFrameSize(width: Int, height: Int) {
        queueEvent { core.setFrameSize(width, height) }
    }

    fun setLut(lut: Lut3D?) {
        queueEvent { core.setLut(lut) }
    }

    fun setLutIntensity(value: Float) {
        lutIntensity = value
    }
}

private fun timeText(ms: Int): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

/** 应用内视频直接返回文件；手机相册视频复制到 cache，避免 Uri 权限在解码线程里失效。 */
private fun resolveVideoFile(context: Context, item: GalleryItem): File? = when (item.origin) {
    "app" -> GalleryFileOps.locateAppFile(item.fileName)
    "phone" -> {
        val src = ContentUris.withAppendedId(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item.key.toLong()
        )
        val dir = File(context.cacheDir, "video-preview").apply { mkdirs() }
        val dst = File(dir, item.fileName)
        if (!dst.exists() || dst.length() <= 0) {
            runCatching {
                context.contentResolver.openInputStream(src)?.use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        if (dst.exists() && dst.length() > 0) dst else null
    }
    else -> null
}

/** 外链播放前把片源复制到 FileProvider 唯一暴露的目录，避免 URI 权限异常。 */
private fun prepareShareCopy(context: Context, src: File): File {
    val dir = File(context.cacheDir, "video-preview").apply { mkdirs() }
    if (src.parentFile?.canonicalPath == dir.canonicalPath && src.exists()) return src
    val dst = File(dir, src.name.ifBlank { "video.mp4" })
    if (dst.exists() && dst.length() == src.length()) return dst
    runCatching {
        src.inputStream().use { input ->
            dst.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
        }
    }
    return dst
}

@Composable
private fun VideoChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) DoukColors.accent else Color.White.copy(alpha = 0.85f),
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) DoukColors.accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.09f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}
