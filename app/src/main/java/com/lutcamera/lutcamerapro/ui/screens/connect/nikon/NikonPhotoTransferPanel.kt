package com.lutcamera.lutcamerapro.ui.screens.connect.nikon

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lutcamera.lutcamerapro.ui.components.AppleButton
import com.lutcamera.lutcamerapro.ui.components.AppleButtonColor
import com.lutcamera.lutcamerapro.ui.components.AppleCard
import com.lutcamera.lutcamerapro.ui.screens.connect.wired.SaveMode
import com.lutcamera.lutcamerapro.ui.theme.AppleTheme
import com.lutcamera.lutcamerapro.ui.viewmodel.LutViewModel
import com.lutcamera.lutcamerapro.ui.viewmodel.SettingsViewModel
import kotlin.math.roundToInt

/** Wireless JPG receipt and phone-side processing share the same persisted save policy. */
@Composable
fun NikonPhotoTransferPanel(
    viewModel: NikonViewModel,
    settings: SettingsViewModel,
    lutViewModel: LutViewModel
) {
    val apple = AppleTheme.colors
    val context = LocalContext.current
    val saveMode = runCatching { SaveMode.valueOf(settings.saveModeName) }.getOrDefault(SaveMode.DUAL)
    val generatesFinishedPhoto = saveMode != SaveMode.ORIGINAL_ONLY
    var expanded by rememberSaveable { mutableStateOf(false) }
    val saveOptions = listOf(
        SaveMode.ORIGINAL_ONLY to "仅原图",
        SaveMode.LUT_ONLY to "仅 LUT 成片",
        SaveMode.DUAL to "原图 + 成片"
    )

    AppleCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("JPG 回传与 LUT 成片", color = apple.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("拍照后自动接收 JPG", color = apple.textSecondary, fontSize = 12.sp)
            }
            Switch(
                modifier = Modifier.semantics { contentDescription = "拍照自动回传 JPG" },
                checked = viewModel.autoTransferOnShutter,
                onCheckedChange = viewModel::setAutoTransfer,
                colors = SwitchDefaults.colors(checkedTrackColor = apple.accent)
            )
        }
        Text(viewModel.autoTransferStatus, color = apple.textSecondary, fontSize = 12.sp)
        if (viewModel.autoTransferOnShutter && !viewModel.isConnected) {
            Text("连接相机后自动等待新照片", color = apple.accent, fontSize = 12.sp)
        }

        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(if (expanded) "收起成片设置" else "成片设置", color = apple.accent, fontSize = 13.sp)
                Text(
                    "${saveOptions.first { it.first == saveMode }.second} · " +
                        if (!generatesFinishedPhoto) "完整 JPG" else
                            "${settings.exportResolution} · ${if (settings.autoApplyLut) lutViewModel.currentLutName else "LUT 已关闭"}${if (settings.autoApplyBorder) " · 边框" else ""}",
                    color = apple.textSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }

        AnimatedVisibility(visible = expanded) {
            Column {
            Text("机身请设为 JPG 或 RAW+JPG，并开启“连接到计算机 → 选项 → 自动上传”。", color = apple.textSecondary, fontSize = 11.sp)
    
            Spacer(Modifier.height(12.dp))
            Text("保存到手机相册", color = apple.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                saveOptions.forEach { (mode, label) ->
                    FilterChip(
                        selected = saveMode == mode,
                        onClick = { settings.updateSaveMode(mode.name) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
            Text(
                when (saveMode) {
                    SaveMode.ORIGINAL_ONLY -> "仅保存相机原始 JPG，保留完整分辨率。"
                    SaveMode.LUT_ONLY -> "只保存手机生成的成片；关闭 LUT 后按当前其他处理设置导出。"
                    SaveMode.DUAL -> "保留相机原始 JPG，另外生成一份手机成片。"
                },
                color = apple.textSecondary,
                fontSize = 11.sp
            )
    
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动应用当前 LUT", color = apple.textPrimary, fontSize = 13.sp)
                    Text(lutViewModel.currentLutName, color = apple.textSecondary, fontSize = 12.sp)
                }
                Switch(
                    modifier = Modifier.semantics { contentDescription = "自动应用当前 LUT" },
                    checked = settings.autoApplyLut,
                    onCheckedChange = settings::toggleAutoApplyLut,
                    enabled = generatesFinishedPhoto,
                    colors = SwitchDefaults.colors(checkedTrackColor = apple.accent)
                )
            }
            Text("LUT 强度 ${(lutViewModel.lutIntensity.coerceIn(0f, 1f) * 100).roundToInt()}%", color = apple.textSecondary, fontSize = 12.sp)
            Slider(
                modifier = Modifier.semantics { contentDescription = "成片 LUT 强度" },
                value = lutViewModel.lutIntensity.coerceIn(0f, 1f),
                onValueChange = lutViewModel::updateIntensity,
                enabled = generatesFinishedPhoto && settings.autoApplyLut,
                colors = SliderDefaults.colors(thumbColor = apple.accent, activeTrackColor = apple.accent)
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("自动应用当前边框", color = apple.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    modifier = Modifier.semantics { contentDescription = "自动应用当前边框" },
                    checked = settings.autoApplyBorder,
                    onCheckedChange = settings::toggleAutoApplyBorder,
                    enabled = generatesFinishedPhoto,
                    colors = SwitchDefaults.colors(checkedTrackColor = apple.accent)
                )
            }
            Text("LUT 与边框在手机上生成，不会写入相机。预览开关不改变成片设置。", color = apple.textSecondary, fontSize = 11.sp)
    
            Spacer(Modifier.height(12.dp))
            Text("成片导出尺寸", color = apple.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("原始分辨率", "高清 (1080P)", "流畅 (720P)").forEach { resolution ->
                    FilterChip(
                        selected = settings.exportResolution == resolution,
                        enabled = generatesFinishedPhoto,
                        onClick = { settings.updateExportResolution(resolution) },
                        label = { Text(resolution, fontSize = 12.sp) }
                    )
                }
            }
            Text("仅影响手机成片尺寸；原始 JPG 不缩小。实时预览清晰度也不会缩小回传原图。", color = apple.textSecondary, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = apple.border)
        Spacer(Modifier.height(10.dp))
        Text(viewModel.photoProcessingStatus, color = apple.textPrimary, fontSize = 12.sp)
        Text(
            "处理中 ${viewModel.photosProcessing} · 本次已保存 ${viewModel.photosSaved} · 待重试 ${viewModel.failedPhotoCount}",
            color = apple.textSecondary,
            fontSize = 12.sp
        )
        if (viewModel.photosProcessing > 0) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = apple.accent)
        }
        if (viewModel.failedPhotoCount > 0) {
            Text("${viewModel.failedPhotoCount} 张已暂存在手机，可按当前 LUT 和保存设置重试。", color = apple.textSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (viewModel.failedPhotoCount > 0) AppleButton(
                text = "重试失败 (${viewModel.failedPhotoCount})",
                onClick = viewModel::retryFailedPhotos,
                enabled = viewModel.failedPhotoCount > 0 && viewModel.photosProcessing == 0,
                modifier = Modifier.weight(1f),
                colorStyle = AppleButtonColor.SECONDARY,
                height = 48.dp,
                fontSize = 12.sp
            )
            AppleButton(
                text = "分享最近保存",
                onClick = { viewModel.shareLastPhoto(context) },
                enabled = viewModel.lastSavedPhotoUri != null,
                modifier = Modifier.weight(1f),
                height = 48.dp,
                fontSize = 12.sp
            )
        }
    }
}
