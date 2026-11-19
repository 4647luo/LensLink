package com.lutcamera.lutcamerapro.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lutcamera.lutcamerapro.ui.components.*
import com.lutcamera.lutcamerapro.ui.theme.AppThemeManager
import com.lutcamera.lutcamerapro.ui.theme.AppleTheme
import com.lutcamera.lutcamerapro.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val apple = AppleTheme.colors
    val isDark by AppThemeManager.isDarkMode.collectAsState()

    var showNikonIpDialog by remember { mutableStateOf(false) }
    var nikonIpEditText by remember { mutableStateOf(viewModel.nikonIp) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(apple.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // ================= 1. 顶部 Header =================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "系统与工作流设置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = apple.textPrimary
                )
                Text(
                    text = "LutCamera Pro · 自动化调色暗房",
                    fontSize = 12.sp,
                    color = apple.textSecondary
                )
            }

            // 主题一键切换按钮
            AppleIconButton(
                icon = if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                colorStyle = if (isDark) AppleButtonColor.PRIMARY else AppleButtonColor.SURFACE,
                size = 42.dp,
                iconSize = 20.dp,
                onClick = { AppThemeManager.toggleTheme(context) }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ================= 2. 外观主题 =================
        SettingsSectionTitle("外观与主题")
        Text(
            text = "界面视觉样式设置，可切换浅色与深色模式",
            fontSize = 11.sp,
            color = apple.textSecondary,
            modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
        )
        AppleCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingIconBadge(icon = Icons.Default.Palette, color = apple.accent)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("界面主题风格", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = apple.textPrimary)
                        Text(if (isDark) "当前为暗夜深色模式" else "当前为清爽浅色模式", fontSize = 12.sp, color = apple.textSecondary)
                    }
                }

                AppleSegmentedControl(
                    items = listOf(false, true),
                    selectedItem = isDark,
                    onItemSelected = { AppThemeManager.setDarkMode(it, context) },
                    labelProvider = { if (it) "深色" else "浅色" },
                    modifier = Modifier.width(130.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ================= 3. 相机通信 IP 设置 =================
        SettingsSectionTitle("相机通信 IP 设置")
        Text(
            text = "配置尼康相机的直连 IP，确保通信畅通",
            fontSize = 11.sp,
            color = apple.textSecondary,
            modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
        )
        AppleCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        nikonIpEditText = viewModel.nikonIp
                        showNikonIpDialog = true
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingIconBadge(icon = Icons.Default.Wifi, color = apple.accent)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("尼康相机 IP 地址", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = apple.textPrimary)
                        Text(viewModel.nikonIp, fontSize = 12.sp, color = apple.textSecondary)
                    }
                }
                ApplePillBadge(text = "已配置", dotColor = apple.accent, badgeColor = apple.surfaceSecondary)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ================= 4. 全自动流水线 =================
        SettingsSectionTitle("全自动流水线")
        Text(
            text = "开启自动化流水线后，相机拍照后即刻自动套用 LUT 与边框装裱",
            fontSize = 11.sp,
            color = apple.textSecondary,
            modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
        )
        AppleCard(modifier = Modifier.fillMaxWidth()) {
            // 自动套用 LUT
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingIconBadge(icon = Icons.Default.AutoFixHigh, color = apple.accent)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("快门回传自动套用 LUT", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = apple.textPrimary)
                        Text("按快门即刻自动生成调色大片", fontSize = 11.sp, color = apple.textSecondary)
                    }
                }
                Switch(
                    checked = viewModel.autoApplyLut,
                    onCheckedChange = { viewModel.setAutoApplyLut(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = apple.accent)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = apple.border, thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            // 自动套用边框水印
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingIconBadge(icon = Icons.Default.FilterFrames, color = apple.accent)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("快门回传自动叠加边框", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = apple.textPrimary)
                        Text("自动提取相机真实 EXIF 绘制装裱", fontSize = 11.sp, color = apple.textSecondary)
                    }
                }
                Switch(
                    checked = viewModel.autoApplyBorder,
                    onCheckedChange = { viewModel.setAutoApplyBorder(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = apple.accent)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ================= 5. 成片存储模式 =================
        SettingsSectionTitle("成片存储模式")
        Text(
            text = "存储策略：选择保存原图、LUT 成片或两者皆保存",
            fontSize = 11.sp,
            color = apple.textSecondary,
            modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
        )
        AppleCard(modifier = Modifier.fillMaxWidth()) {
            val saveModes = listOf("DUAL", "ORIGINAL_ONLY", "LUT_ONLY")
            val saveModeLabels = mapOf("DUAL" to "双保存 (原图 + 成片)", "ORIGINAL_ONLY" to "仅存原图", "LUT_ONLY" to "仅存成片")

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                saveModes.forEach { modeKey ->
                    val isSelected = viewModel.saveModeName == modeKey
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) apple.accent.copy(alpha = 0.12f) else apple.surfaceSecondary)
                            .border(2.dp, if (isSelected) apple.accent else apple.border, RoundedCornerShape(12.dp))
                            .clickable { viewModel.setSaveMode(modeKey) }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = saveModeLabels[modeKey] ?: modeKey,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                                color = if (isSelected) apple.accent else apple.textPrimary
                            )
                            if (isSelected) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = apple.accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ================= 6. 关于与版本 =================
        SettingsSectionTitle("关于与版本")
        Text(
            text = "当前应用版本与技术支持信息",
            fontSize = 11.sp,
            color = apple.textSecondary,
            modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
        )
        AppleCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingIconBadge(icon = Icons.Default.Info, color = apple.accent)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("LUT Camera Pro", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = apple.textPrimary)
                        Text("Version 1.0 (Build 34) · Apple Design", fontSize = 12.sp, color = apple.textSecondary)
                    }
                }
                ApplePillBadge(text = "最新版", dotColor = apple.accent, badgeColor = apple.surfaceSecondary)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // 尼康 IP 修改弹窗
    if (showNikonIpDialog) {
        Dialog(onDismissRequest = { showNikonIpDialog = false }) {
            AppleCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "修改尼康相机 IP 地址",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = apple.textPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = nikonIpEditText,
                    onValueChange = { nikonIpEditText = it },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = apple.accent,
                        unfocusedBorderColor = apple.border
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppleButton(
                        text = "取消",
                        colorStyle = AppleButtonColor.SECONDARY,
                        height = 40.dp,
                        fontSize = 13.sp,
                        onClick = { showNikonIpDialog = false },
                        modifier = Modifier.weight(1f)
                    )
                    AppleButton(
                        text = "确定",
                        colorStyle = AppleButtonColor.PRIMARY,
                        height = 40.dp,
                        fontSize = 13.sp,
                        onClick = {
                            if (nikonIpEditText.isNotBlank()) {
                                viewModel.setNikonIp(nikonIpEditText.trim())
                                Toast.makeText(context, "尼康 IP 已保存", Toast.LENGTH_SHORT).show()
                            }
                            showNikonIpDialog = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    val apple = AppleTheme.colors
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.ExtraBold,
        color = apple.textSecondary,
        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingIconBadge(icon: ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.5.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
    }
}
