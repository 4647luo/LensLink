package com.lutcamera.lutcamerapro.ui.douk

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/* ============================ 伪色 ============================ */

/** 曝光判断用的伪色档位：颜色 + 说明，图例与渲染共用同一张表。 */
private data class FalseColorBand(val from: Int, val to: Int, val color: Color, val label: String)

private val FALSE_COLOR_BANDS = listOf(
    FalseColorBand(0, 5, Color(0xFF6E2EE8), "黑场剪裁"),
    FalseColorBand(6, 40, Color(0xFF1E6BFF), "深阴影"),
    FalseColorBand(41, 80, Color(0xFF00B3B3), "阴影过渡"),
    FalseColorBand(81, 135, Color(0xFF1FD06A), "中灰附近 18%"),
    FalseColorBand(136, 175, Color(0xFFB4B4B4), "亮部过渡"),
    FalseColorBand(176, 215, Color(0xFFFFD400), "接近高光"),
    FalseColorBand(216, 240, Color(0xFFFF7A00), "高光"),
    FalseColorBand(241, 252, Color(0xFFFF3B30), "高光临限"),
    FalseColorBand(253, 255, Color(0xFFFF00FF), "过曝（无细节）")
)

// 伪色渲染已全部搬到 GPU（LutShaders.falseColorBand，在片元着色器里按 IRE 分段上色），
// 这里只保留图例用的色带表，CPU 侧不再做 480px 缩放查表。

@Composable
fun FalseColorLegend(modifier: Modifier = Modifier) {
    Column(
        modifier
            .clipCard()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            "伪色 · 曝光分布",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            FALSE_COLOR_BANDS.forEach { band ->
                Box(
                    Modifier
                        .weight((band.to - band.from + 1).toFloat())
                        .height(8.dp)
                        .background(band.color)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("黑", color = Color.White.copy(alpha = 0.55f), fontSize = 8.sp)
            Text("18%", color = Color(0xFF1FD06A), fontSize = 8.sp)
            Text("过曝", color = Color(0xFFFF00FF), fontSize = 8.sp)
        }
    }
}

private fun Modifier.clipCard(): Modifier =
    this.background(Color(0xE6101114))
        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
        .clip(RoundedCornerShape(12.dp))

/* ============================ 水平仪 ============================ */

/** 横屏监看下用重力传感器算横滚角：0° 表示画面水平。 */
@Composable
fun rememberTiltDegrees(active: Boolean): Float {
    var tilt by remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose {}
        val sm = context.getSystemService(android.content.Context.SENSOR_SERVICE)
            as? android.hardware.SensorManager ?: return@DisposableEffect onDispose {}
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                val gx = event.values[0]
                val gy = event.values[1]
                tilt = Math.toDegrees(atan2(gx.toDouble(), -gy.toDouble())).toFloat()
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }
        if (sensor != null) {
            sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sm.unregisterListener(listener) }
    }
    return tilt
}

@Composable
fun HorizonLevel(tiltDeg: Float, modifier: Modifier = Modifier) {
    val locked = kotlin.math.abs(tiltDeg) <= 1.2f
    val color = if (locked) Color(0xFF9BFF6A) else Color.White.copy(alpha = 0.78f)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val half = size.minDimension * 0.26f
            rotate(degrees = -tiltDeg, pivot = Offset(cx, cy)) {
                drawLine(
                    color = color.copy(alpha = 0.10f),
                    start = Offset(cx - half, cy),
                    end = Offset(cx + half, cy),
                    strokeWidth = 10f
                )
                drawLine(
                    color = color,
                    start = Offset(cx - half, cy),
                    end = Offset(cx + half, cy),
                    strokeWidth = 3f
                )
                // 中心缺口：两侧短刻度让「是否贴合」更容易看
                drawLine(color, Offset(cx - half * 0.32f, cy - 10f), Offset(cx - half * 0.32f, cy + 10f), 2f)
                drawLine(color, Offset(cx + half * 0.32f, cy - 10f), Offset(cx + half * 0.32f, cy + 10f), 2f)
            }
            // 固定中心十字
            drawLine(Color.White.copy(alpha = 0.45f), Offset(cx - 6f, cy), Offset(cx + 6f, cy), 1.5f)
            drawLine(Color.White.copy(alpha = 0.45f), Offset(cx, cy - 6f), Offset(cx, cy + 6f), 1.5f)
        }
        Text(
            text = String.format(java.util.Locale.US, "%.1f°", tiltDeg),
            color = if (locked) Color(0xFF9BFF6A) else Color.White.copy(alpha = 0.85f),
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 38.dp)
        )
    }
}


/* ============================ 构图辅助线 ============================ */

val GUIDE_STYLE_LABELS = listOf("三分", "三分+十字", "对角线", "安全框", "中心十字")

@Composable
fun CompositionGuides(style: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val line = Color.White.copy(alpha = 0.25f)
        val w = size.width
        val h = size.height
        when (style) {
            0, 1 -> {
                for (i in 1..2) {
                    drawLine(line, Offset(w / 3f * i, 0f), Offset(w / 3f * i, h), 1.2f)
                    drawLine(line, Offset(0f, h / 3f * i), Offset(w, h / 3f * i), 1.2f)
                }
                if (style == 1) {
                    drawLine(line, Offset(w / 2f, 0f), Offset(w / 2f, h), 1f)
                    drawLine(line, Offset(0f, h / 2f), Offset(w, h / 2f), 1f)
                }
            }

            2 -> {
                drawLine(line, Offset(0f, 0f), Offset(w, h), 1.2f)
                drawLine(line, Offset(w, 0f), Offset(0f, h), 1.2f)
            }

            3 -> {
                val inset = 0.05f
                drawRect(
                    color = line,
                    topLeft = Offset(w * inset, h * inset),
                    size = androidx.compose.ui.geometry.Size(w * (1 - inset * 2), h * (1 - inset * 2)),
                    style = Stroke(width = 1.2f)
                )
                drawLine(line, Offset(w / 3f, 0f), Offset(w / 3f, h), 1f)
                drawLine(line, Offset(w / 3f * 2, 0f), Offset(w / 3f * 2, h), 1f)
                drawLine(line, Offset(0f, h / 3f), Offset(w, h / 3f), 1f)
                drawLine(line, Offset(0f, h / 3f * 2), Offset(w, h / 3f * 2), 1f)
            }

            else -> {
                drawLine(line, Offset(w / 2f, h / 2f - 24f), Offset(w / 2f, h / 2f + 24f), 1.2f)
                drawLine(line, Offset(w / 2f - 24f, h / 2f), Offset(w / 2f + 24f, h / 2f), 1.2f)
            }
        }
    }
}

/* ============================ 音频电平 ============================ */

/**
 * 用手机麦克风采环境声做参考电平（相机取景流本身不带音频）。
 * 只有打开这个工具时才启动采集，避免一直占着麦克风。
 */
@Composable
fun rememberAudioLevel(active: Boolean, granted: Boolean): Pair<Float, Float> {
    var level by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(0f) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(active, granted) {
        if (!active || !granted) {
            level = 0f
            peak = 0f
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            // granted 只是界面开关状态；系统权限可能被用户在设置里收回，
            // 这里再查一次，避免 AudioRecord 直接抛 SecurityException（lint 也要求显式检查）。
            val allowed = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!allowed) return@withContext
            val sampleRate = 44100
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = if (minBuf > 0) minBuf else 8192
            val record = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize * 2
                )
            }.getOrNull()
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record?.release() }
                return@withContext
            }
            val buffer = ShortArray(bufSize / 2)
            try {
                record.startRecording()
                while (isActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            val v = buffer[i] / 32768.0
                            sum += v * v
                        }
                        val rms = sqrt(sum / read)
                        val db = if (rms <= 0.0) -80.0 else 20 * log10(rms)
                        val norm = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
                        level = norm
                        peak = max(peak * 0.93f, norm)
                    }
                    delay(60)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                level = 0f
                peak = 0f
            }
        }
    }
    return level to peak
}

@Composable
fun AudioMeter(level: Float, peak: Float, modifier: Modifier = Modifier) {
    val db = (level * 60f - 60f).roundToInt()
    Column(
        modifier
            .clipCard()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .width(168.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("音频电平", color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("$db dB", color = if (db > -6) Color(0xFFFF6B6B) else Color(0xFF9BFF6A), fontSize = 10.sp)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(5.dp))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(level.coerceIn(0f, 1f))
                    .height(10.dp)
                    .background(if (level > 0.9f) Color(0xFFFF3B30) else Color(0xFF9BFF6A))
            )
            // 峰值保持线
            Box(
                Modifier
                    .fillMaxWidth(peak.coerceIn(0f, 1f))
                    .height(10.dp)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(2.dp)
                        .height(10.dp)
                        .background(Color.White.copy(alpha = 0.75f))
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "仅取电平做参考，不录音、不上传",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 8.sp
        )
    }
}

/* ============================ 时间码 ============================ */

fun formatTimecode(ms: Long, fps: Int): String {
    val totalSec = ms / 1000
    val hh = (totalSec / 3600) % 24
    val mm = (totalSec / 60) % 60
    val ss = totalSec % 60
    val ff = ((ms % 1000) * fps / 1000).toInt().coerceIn(0, fps - 1)
    return String.format(java.util.Locale.US, "%02d:%02d:%02d:%02d", hh, mm, ss, ff)
}

@Composable
fun TimecodeBadge(text: String, recording: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (recording) {
            Box(Modifier.size(6.dp).background(Color(0xFFFF3B30), androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = "TC $text",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

/* ============================ 自动化拍摄面板 ============================ */

@Composable
fun AutoCapturePanel(state: DoukState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) } // 0 间隔 / 1 自拍 / 2 包围
    var intervalSec by remember { mutableIntStateOf(3) }
    var frameCount by remember { mutableIntStateOf(5) }
    var delaySec by remember { mutableIntStateOf(5) }
    var bracketKind by remember { mutableIntStateOf(0) } // 0 AE / 1 WB / 2 D-Lighting
    var bracketSteps by remember { mutableIntStateOf(3) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("待开始") }
    val running = job?.isActive == true

    fun stopJob() {
        job?.cancel()
        job = null
        status = "已停止"
    }

    fun runInterval() {
        if (running) return
        status = "间隔拍摄中…"
        job = scope.launch {
            for (i in 1..frameCount) {
                if (!isActive) break
                status = "第 $i / $frameCount 张"
                state.shoot()
                if (i < frameCount) delay(intervalSec * 1000L)
            }
            status = "间隔拍摄完成"
            job = null
        }
    }

    fun runSelfTimer() {
        if (running) return
        job = scope.launch {
            for (s in delaySec downTo 1) {
                status = "自拍倒计时 $s s"
                delay(1000)
            }
            state.shoot()
            status = "已触发快门"
            job = null
        }
    }

    /** 包围：按现有可选档位挑出中心附近 N 档，逐张改参数再拍，收工恢复原值。 */
    fun runBracket() {
        if (running) return
        val key = when (bracketKind) {
            0 -> "ev"
            1 -> "wb"
            else -> "activeD"
        }
        val labels: List<String> = when (bracketKind) {
            0 -> state.vm.exposureCompOptions.map { it.label }
            1 -> state.vm.whiteBalanceOptions.map { it.label }
            else -> state.vm.extraProps["activeD"]?.options?.map { it.label } ?: emptyList()
        }
        if (labels.isEmpty()) {
            status = "相机没有上报可用的 ${if (bracketKind == 0) "曝光补偿" else if (bracketKind == 1) "白平衡" else "动态 D"} 档位"
            return
        }
        val original = state.valueOf(key)
        val center = labels.indexOf(original).let { if (it >= 0) it else labels.size / 2 }
        val span = bracketSteps.coerceAtLeast(1)
        val start = (center - span / 2).coerceAtLeast(0)
        val picks = labels.subList(start, (start + span).coerceAtMost(labels.size))
        status = "包围拍摄中…"
        job = scope.launch {
            for ((i, label) in picks.withIndex()) {
                if (!isActive) break
                state.setParam(key, label)
                status = "第 ${i + 1} / ${picks.size} 张：$label"
                delay(700)
                state.shoot()
                delay(900)
            }
            if (original.isNotBlank()) state.setParam(key, original)
            status = "包围完成，已恢复 $original"
            job = null
        }
    }

    Column(
        Modifier
            .width(420.dp)
            .background(Color(0xF2111216), RoundedCornerShape(20.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("自动化拍摄", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.weight(1f))
            Text(
                status,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.width(190.dp),
                textAlign = TextAlign.End
            )
            Spacer(Modifier.width(8.dp))
            Text("✕", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                modifier = Modifier.clickable { stopJob(); onClose() })
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("间隔拍摄", "自拍计时", "包围曝光").forEachIndexed { index, label ->
                val active = mode == index
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) Color(0xFFE5484D) else Color.White.copy(alpha = 0.08f))
                        .clickable { if (!running) mode = index }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        when (mode) {
            0 -> {
                StepperRow(
                    "间隔", "${intervalSec}s",
                    onMinus = { intervalSec = (intervalSec - 1).coerceAtLeast(1) },
                    onPlus = { intervalSec = (intervalSec + 1).coerceAtMost(3600) }
                )
                StepperRow(
                    "张数", "$frameCount 张",
                    onMinus = { frameCount = (frameCount - 1).coerceAtLeast(1) },
                    onPlus = { frameCount = (frameCount + 1).coerceAtMost(999) }
                )
                Spacer(Modifier.height(10.dp))
                ActionRow(running, "开始间隔拍摄", onStart = { runInterval() }, onStop = { stopJob() })
            }

            1 -> {
                StepperRow(
                    "延时", "${delaySec}s",
                    onMinus = { delaySec = (delaySec - 1).coerceAtLeast(1) },
                    onPlus = { delaySec = (delaySec + 1).coerceAtMost(60) }
                )
                Spacer(Modifier.height(10.dp))
                ActionRow(running, "开始倒计时", onStart = { runSelfTimer() }, onStop = { stopJob() })
            }

            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("AE 包围", "白平衡包围", "动态 D 包围").forEachIndexed { index, label ->
                        val active = bracketKind == index
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (active) Color(0xFF3A4A38) else Color.White.copy(alpha = 0.07f))
                                .border(1.dp, if (active) Color(0xFF9BFF6A).copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(9.dp))
                                .clickable { if (!running) bracketKind = index }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(label, color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                StepperRow(
                    "张数", "$bracketSteps 张",
                    onMinus = { bracketSteps = (bracketSteps - 1).coerceAtLeast(1) },
                    onPlus = { bracketSteps = (bracketSteps + 1).coerceAtMost(9) }
                )
                Spacer(Modifier.height(10.dp))
                ActionRow(running, "开始包围", onStart = { runBracket() }, onStop = { stopJob() })
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "拍摄用机身当前参数；包围会逐张改值，结束自动恢复。间隔拍摄请保持相机与 Wi‑Fi 连接。",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 9.sp
        )
    }
}

@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, modifier = Modifier.width(56.dp))
        StepButton("−", onMinus)
        Text(
            value,
            color = Color.White,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(92.dp)
        )
        StepButton("+", onPlus)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ActionRow(running: Boolean, startLabel: String, onStart: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(if (running) Color.White.copy(alpha = 0.10f) else Color(0xFFE5484D))
                .clickable { if (!running) onStart() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(startLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier
                .width(96.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .clickable { if (running) onStop() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("停止", color = Color.White.copy(alpha = if (running) 0.9f else 0.4f), fontSize = 12.sp)
        }
    }
}
