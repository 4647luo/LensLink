package com.lutcamera.lutcamerapro.ui.douk

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.activity.compose.BackHandler
import com.lutcamera.lutcamerapro.log.DoukLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 运行日志页：把 [DoukLog] 的行按「时间 级别/标签: 事件 key=value」展示，
 * 可导出到 Download/Douk 或清空。相机连不上时，把导出的文件发出来即可定位。
 */
@Composable
fun DoukLogScreen(onBack: () -> Unit) {
    // 日志页是覆盖全屏的，返回键要回上一页而不是直接退出 Activity
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    // 读 version 触发重组：DoukLog 每写一条都会让 version 自增
    val version = DoukLog.version
    val lines = remember(version) { DoukLog.snapshot().reversed() }
    var hint by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val exportLog: () -> Unit = {
        if (!exporting) {
            exporting = true
            hint = "正在导出…"
            scope.launch {
                val result = withContext(Dispatchers.IO) { DoukLog.export(context) }
                hint = result
                exporting = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = DoukColors.textPrimary)
            }
            Text(
                "运行日志",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = DoukColors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { DoukLog.clear(); hint = "已清空当前会话日志" }) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "清空", tint = DoukColors.textTertiary)
            }
            IconButton(onClick = exportLog) {
                Icon(Icons.Rounded.Save, contentDescription = "导出", tint = DoukColors.accent)
            }
        }

        Text(
            "格式：时间 级别/标签: 事件 key=value · 共 ${lines.size} 条 · 也写入 filesDir/douk-logs",
            fontSize = 11.sp,
            color = DoukColors.textTertiary,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        if (hint.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(hint, fontSize = 12.sp, color = DoukColors.accent, modifier = Modifier.padding(horizontal = 20.dp))
        }
        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .clip(DoukShapes.card)
                .doukCardSurface(DoukShapes.card)
                .padding(8.dp)
        ) {
            if (lines.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("还没有日志", fontSize = 14.sp, color = DoukColors.textSecondary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("连接相机时会自动记录握手、属性读取与取景结果", fontSize = 11.sp, color = DoukColors.textTertiary)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(
                        lines,
                        key = { index, _ -> index },
                        contentType = { _, _ -> "log-line" }
                    ) { _, line ->
                        LogLine(line)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Button(
                onClick = exportLog,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = DoukShapes.cardSmall,
                colors = ButtonDefaults.buttonColors(containerColor = DoukColors.accent)
            ) {
                Icon(Icons.Rounded.Save, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("导出日志到 Download/Douk", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun LogLine(line: String) {
    // 格式固定：`MM-dd HH:mm:ss.SSS L/TAG: event key=value`，级别字符在下标 19
    val level = line.getOrNull(19) ?: 'I'
    val color = when (level) {
        'E' -> Color(0xFFD93025)
        'W' -> DoukColors.accent
        'I' -> DoukColors.textPrimary
        else -> DoukColors.textTertiary
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .background(DoukColors.surface, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text = line,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            overflow = TextOverflow.Visible,
            softWrap = true
        )
    }
}
