package com.lutcamera.lutcamerapro.ui.douk

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 图库右上角「传输图片」面板：显示正在传 / 等待续传 / 历史记录，
 * 只有「清除记录」会删掉已结束的条目，正在传的任务保留。
 */
@Composable
internal fun TransferSheet(onDismiss: () -> Unit) {
    val records by TransferCenter.records.collectAsState()
    val thumbnails by TransferCenter.thumbnails.collectAsState()
    val context = LocalContext.current
    val active = records.filter { it.isActive }
        .sortedWith(compareBy({ if (it.status == TransferStatus.RUNNING) 0 else 1 }, { it.createdAt }))
    val finished = records.filterNot { it.isActive }.sortedByDescending { it.updatedAt }
    val running = records.filter { it.status == TransferStatus.RUNNING }
    val waiting = records.filter { it.status == TransferStatus.WAITING }
    val speed = TransferCenter.runningSpeed()

    val subtitle = when {
        running.isNotEmpty() ->
            "相机 → 手机 · ${TransferCenter.formatSpeed(speed)} · ${running.size} 项传输中"
        waiting.isNotEmpty() &&
            waiting.any { it.message.contains("中断") || it.message.contains("继续") } ->
            "相机重新连接后自动继续 ${waiting.size} 项传输"
        waiting.isNotEmpty() -> "相机 → 手机 · ${waiting.size} 项等待传输"
        records.isNotEmpty() -> "相机 → 手机 · 共 ${records.size} 条记录"
        else -> "相机 → 手机"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 18.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .shadow(20.dp, RoundedCornerShape(26.dp))
                    .clip(RoundedCornerShape(26.dp))
                    .background(DoukColors.cardLight)
                    .navigationBarsPadding()
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 9.dp)
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(DoukColors.textSecondary.copy(alpha = 0.32f))
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "传输图片",
                            style = DoukType.h2,
                            color = DoukColors.textPrimary
                        )
                        Text(
                            subtitle,
                            style = DoukType.caption,
                            color = DoukColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "关闭",
                            tint = DoukColors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (records.isEmpty()) {
                    EmptyTransfers(Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 14.dp, end = 14.dp, top = 4.dp, bottom = 6.dp
                        )
                    ) {
                        if (active.isNotEmpty()) {
                            item {
                                SectionLabel("正在传输 / 等待继续", active.size)
                            }
                            items(active, key = { it.id }) { record ->
                                TransferRow(record, thumbnails[record.id])
                            }
                        }
                        if (finished.isNotEmpty()) {
                            item {
                                SectionLabel("最近记录", finished.size)
                            }
                            items(finished.take(60), key = { it.id }) { record ->
                                TransferRow(record, thumbnails[record.id])
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "退出应用或进程被清理后，未完成任务会保留；相机重新连接会自动续传。",
                        style = DoukType.caption,
                        color = DoukColors.textSecondary.copy(alpha = 0.76f),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        enabled = finished.isNotEmpty(),
                        onClick = {
                            val removed = TransferCenter.clearFinished()
                            if (removed > 0) {
                                Toast.makeText(context, "已清除 $removed 条传输记录", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text(
                            "清除记录",
                            color = if (finished.isNotEmpty()) DoukColors.accent
                            else DoukColors.textSecondary.copy(alpha = 0.42f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = DoukType.caption,
            fontWeight = FontWeight.SemiBold,
            color = DoukColors.textSecondary
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            count.toString(),
            style = DoukType.caption,
            color = DoukColors.textSecondary.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun TransferRow(record: TransferRecord, cachedThumbnail: ImageBitmap?) {
    var localThumbnail by remember(record.id, record.status) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(record.id, record.status) {
        if (record.status != TransferStatus.COMPLETED) return@LaunchedEffect
        val file = withContext(Dispatchers.IO) {
            GalleryFileOps.locateAppFile(record.fileName)
        } ?: return@LaunchedEffect
        localThumbnail = withContext(Dispatchers.IO) { ImageDecode.decodeThumb(file, 180) }
    }
    val thumbnail = cachedThumbnail ?: localThumbnail
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DoukColors.surfaceMuted.copy(alpha = 0.62f))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DoukColors.surface),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    when (record.status) {
                        TransferStatus.FAILED -> Icons.Rounded.ErrorOutline
                        TransferStatus.WAITING -> Icons.Rounded.SwapVert
                        else -> Icons.Rounded.Image
                    },
                    contentDescription = null,
                    tint = when (record.status) {
                        TransferStatus.RUNNING -> DoukColors.accent
                        TransferStatus.COMPLETED -> Color(0xFF43A047)
                        TransferStatus.FAILED -> Color(0xFFE05A4F)
                        TransferStatus.WAITING -> DoukColors.textSecondary
                    },
                    modifier = Modifier.size(23.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                record.fileName,
                style = DoukType.body,
                fontWeight = FontWeight.SemiBold,
                color = DoukColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                statusLine(record),
                style = DoukType.caption,
                color = when (record.status) {
                    TransferStatus.FAILED -> Color(0xFFE05A4F)
                    TransferStatus.COMPLETED -> DoukColors.textSecondary
                    else -> DoukColors.textSecondary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (record.isActive) {
                val fraction = if (record.totalBytes > 0L) {
                    (record.copiedBytes.toFloat() / record.totalBytes).coerceIn(0f, 1f)
                } else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 7.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(DoukColors.surface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(DoukColors.accent)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        when (record.status) {
            TransferStatus.COMPLETED -> Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = Color(0xFF43A047),
                modifier = Modifier.size(19.dp)
            )
            TransferStatus.FAILED -> Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = Color(0xFFE05A4F),
                modifier = Modifier.size(19.dp)
            )
            else -> Text(
                if (record.totalBytes > 0L) "${record.percent}%" else "…",
                style = DoukType.caption,
                fontWeight = FontWeight.SemiBold,
                color = if (record.status == TransferStatus.RUNNING) DoukColors.accent
                else DoukColors.textSecondary
            )
        }
    }
}

private fun statusLine(record: TransferRecord): String = when (record.status) {
    TransferStatus.RUNNING -> {
        val bytes = if (record.totalBytes > 0L) {
            "${TransferCenter.formatBytes(record.copiedBytes)} / ${TransferCenter.formatBytes(record.totalBytes)}"
        } else TransferCenter.formatBytes(record.copiedBytes)
        "${TransferCenter.formatSpeed(record.speedBytesPerSec)} · $bytes"
    }
    TransferStatus.WAITING -> record.message.ifBlank { "等待相机连接" }
    TransferStatus.COMPLETED -> "已保存到手机相册 · ${clockText(record.updatedAt)}"
    TransferStatus.FAILED -> "失败：${record.message.ifBlank { "请重试" }}"
}

private fun clockText(ts: Long): String =
    runCatching { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts)) }.getOrDefault("--:--")

@Composable
private fun EmptyTransfers(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(DoukColors.surfaceMuted),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.CloudUpload,
                contentDescription = null,
                tint = DoukColors.accent,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "还没有传输记录",
            style = DoukType.h2,
            color = DoukColors.textPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "在相机图库选择照片并保存到手机后，这里会显示进度、速度和历史记录。",
            style = DoukType.body,
            color = DoukColors.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
