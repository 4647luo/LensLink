package com.lutcamera.lutcamerapro.ui.screens.connect.sdcard

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lutcamera.lutcamerapro.ui.theme.AppleTheme
import com.lutcamera.lutcamerapro.ui.viewmodel.LutViewModel

@Composable
fun SdCardSection(
    viewModel: SdCardViewModel = viewModel(),
    lutViewModel: LutViewModel = viewModel()
) {
    val context = LocalContext.current
    val colors = AppleTheme.colors

    val treePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.onSdDirectorySelected(context, it)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = colors.surface,
        border = if (colors.isDark) androidx.compose.foundation.BorderStroke(1.dp, colors.border) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 头部：卡状态与挂载操作
                Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (viewModel.isMounted) colors.accent.copy(alpha = 0.15f) else colors.surfaceSecondary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SdCard,
                            contentDescription = null,
                            tint = if (viewModel.isMounted) colors.accent else colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = viewModel.sdCardName,
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (viewModel.isMounted) "Found ${viewModel.sdPhotos.size} photos" else "Select SD Card Folder ->",
                            color = colors.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Surface(
                    color = colors.accent,
                    shape = RoundedCornerShape(50.dp),
                    modifier = Modifier.clickable { treePickerLauncher.launch(null) }
                ) {
                    Text(
                        text = if (viewModel.isMounted) "Reselect" else "Mount SD Card",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }

            if (viewModel.isMounted) {
                Spacer(modifier = Modifier.height(14.dp))

                // 操作工具条：筛选标签与功能按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PhotoFilterOption.entries.forEach { option ->
                            val isSelected = viewModel.currentFilter == option
                            Surface(
                                color = if (isSelected) colors.accent else colors.surfaceSecondary,
                                shape = RoundedCornerShape(50.dp),
                                modifier = Modifier.clickable { viewModel.setFilterOption(option) }
                            ) {
                                Text(
                                    text = option.label,
                                    color = if (isSelected) Color.White else colors.textPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = { viewModel.selectAllVisible(true) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Select All", color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = { viewModel.selectAllVisible(false) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Cancel", color = colors.textSecondary, fontSize = 12.sp)
                        }
                    }
                }

                // 正在批量拉取进度条
                AnimatedVisibility(visible = viewModel.isProcessing) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceSecondary)
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "正在流式拉取 (${viewModel.processingIndex}/${viewModel.totalToProcess})",
                                color = colors.textPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(
                                onClick = { viewModel.cancelImport() },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Stop", color = colors.accentRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = viewModel.processingProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = colors.accent,
                            trackColor = colors.surface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = viewModel.currentProcessingName,
                            color = colors.textSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 相片轻量网格视口 (3 列，惰性 LRU 缩略图加载，永不卡顿)
                val photos = viewModel.filteredPhotos
                if (photos.isNotEmpty()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(photos, key = { it.id }, contentType = { "sd-photo" }) { item ->
                            AppleSdPhotoGridItem(
                                item = item,
                                viewModel = viewModel,
                                onClick = { item.isSelected = !item.isSelected }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 底部开始批量拉取操作流程
                val selectedCount = photos.count { it.isSelected
                }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Selected $selectedCount photos",
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Surface(
                            color = if (selectedCount > 0 && !viewModel.isProcessing) colors.accent else colors.surfaceSecondary,
                            shape = RoundedCornerShape(50.dp),
                            modifier = Modifier.clickable(enabled = selectedCount > 0 && !viewModel.isProcessing) {
                                viewModel.importSelectedPhotos(
                                    context = context,
                                    currentLut = lutViewModel.currentLutData,
                                    lutIntensity = lutViewModel.lutIntensity,
                                    onComplete = { stats ->
                                        Toast.makeText(
                                            context,
                                            "🎉 Import complete! JPG: ${stats.successJpg}, RAW: ${stats.successRaw}, Failed: ${stats.failCount}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                )
                            }
                        ) {
                            Text(
                                text = if (viewModel.isProcessing) "Pulling..." else "Start Batch Pull",
                                color = if (selectedCount > 0 && !viewModel.isProcessing) Color.White else colors.textSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                } else if (viewModel.isScanning) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Scanning SD card photos metadata...", color = colors.textSecondary, fontSize = 12.sp)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No supported photos found in this directory.", color = colors.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * 惰性按需异步加载缩略图网格。 */
@Composable
private fun AppleSdPhotoGridItem(
    item: SdPhotoItem,
    viewModel: SdCardViewModel,
    onClick: () -> Unit
) {
    val colors = AppleTheme.colors
    val context = LocalContext.current
    var thumbnailBmp by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.id) {
        viewModel.loadThumbnailAsync(context, item) { bmp ->
            thumbnailBmp = bmp
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceSecondary)
            .clickable(onClick = onClick)
            .border(
                width = if (item.isSelected) 2.5.dp else 0.dp,
                color = if (item.isSelected) colors.accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        if (thumbnailBmp != null) {
            Image(
                bitmap = thumbnailBmp!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (item.isRaw) Icons.Default.CameraAlt else Icons.Default.Image,
                    contentDescription = null,
                    tint = colors.textSecondary.copy(alpha = 0.5f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // RAW 格式标记
                if (item.isRaw) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(bottomEnd = 6.dp),
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Text(
                    text = "RAW",
                    color = Color(0xFFFFD60A),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        // 选中鍕鹃€夊湀
                Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (item.isSelected) colors.accent else Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            if (item.isSelected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}



