package com.lutcamera.lutcamerapro.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lutcamera.lutcamerapro.gpu.LutSurfaceView
import com.lutcamera.lutcamerapro.ui.components.*
import com.lutcamera.lutcamerapro.ui.theme.AppleTheme
import com.lutcamera.lutcamerapro.ui.viewmodel.LutViewModel

@Composable
fun LutPreviewScreen(
    onNavigateToLibrary: () -> Unit,
    viewModel: LutViewModel = viewModel()
) {
    val context = LocalContext.current
    val apple = AppleTheme.colors

    var showFullscreen by remember { mutableStateOf(false) }
    var isHoldingOriginal by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.initStorage()
    }

    // 系统图片选择器
                val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            try {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(selectedUri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream, null, options)
                }

                val maxDim = 2048
                var sample = 1
                while (options.outWidth / sample > maxDim || options.outHeight / sample > maxDim) {
                    sample *= 2
                }

                val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    inMutable = true
                }

                val safeBmp = context.contentResolver.openInputStream(selectedUri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream, null, decodeOpts)
                }

                safeBmp?.let { bmp ->
                    viewModel.updateBitmap(bmp)
                    Toast.makeText(context, "照片导入成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "照片导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(apple.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // ================= 1. 顶部 Header (操作胶囊与导入按键) =================
                Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "LUT 电影级调色",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = apple.textPrimary
                )
                Text(
                    text = "当前: ${viewModel.currentLutName}",
                    fontSize = 12.sp,
                    color = apple.textSecondary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 滤镜库管理入口
                AppleButton(
                    text = "滤镜库",
                    icon = Icons.Default.CollectionsBookmark,
                    colorStyle = AppleButtonColor.PRIMARY,
                    height = 38.dp,
                    fontSize = 12.sp,
                    onClick = onNavigateToLibrary
                )

                // 导入照片
                AppleButton(
                    text = "换图",
                    icon = Icons.Default.AddPhotoAlternate,
                    colorStyle = AppleButtonColor.PRIMARY,
                    height = 38.dp,
                    fontSize = 12.sp,
                    onClick = { imagePickerLauncher.launch("image/*") }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ================= 2. 调色主画布卡片 (支持长按对比原图与全屏) =================
                AppleCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
            backgroundColor = Color.Black
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
            ) {
                // OpenGL 调色渲染表面
                AndroidView(
                    factory = { ctx ->
                        LutSurfaceView(ctx).apply {
                            setLutData(viewModel.currentLutData)
                            setXmpData(viewModel.currentXmpData)
                            setImageBitmap(viewModel.currentBitmap)
                            lutIntensity = if (isHoldingOriginal) 0f else viewModel.lutIntensity
                            isHighlightWarning = viewModel.isHighlightWarning
                            isShadowWarning = viewModel.isShadowWarning
                        }
                    },
                    update = { view ->
                        view.setImageBitmap(viewModel.currentBitmap)
                        view.setLutData(viewModel.currentLutData)
                        view.setXmpData(viewModel.currentXmpData)
                        view.lutIntensity = if (isHoldingOriginal) 0f else viewModel.lutIntensity
                        view.isHighlightWarning = viewModel.isHighlightWarning
                        view.isShadowWarning = viewModel.isShadowWarning
                        view.requestRender()
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 顶部控制浮条 (长按对比 + 全屏)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 长按原图对比药丸按键
                Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isHoldingOriginal) apple.accent else Color.Black.copy(alpha = 0.6f))
                            .border(1.5.dp, if (isHoldingOriginal) apple.accent else Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        isHoldingOriginal = true
                                        tryAwaitRelease()
                                        isHoldingOriginal = false
                                    }
                                )
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isHoldingOriginal) "?? 正对比原图" else "长按对比原图",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 直方图与全屏
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = { viewModel.toggleHistogram() },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(if (viewModel.isShowHistogram) apple.accent else Color.Black.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Default.BarChart, contentDescription = "直方图", tint = Color.White, modifier = Modifier.size(16.dp))
                        }

                        IconButton(
                            onClick = { showFullscreen = true },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Default.Fullscreen, contentDescription = "全屏", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // 动态 RGB 直方图悬浮窗
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                ) {
                    AnimatedVisibility(
                        visible = viewModel.isShowHistogram
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 120.dp, height = 50.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                .padding(4.dp)
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height
                                val rHist = viewModel.redHistogram
                                val gHist = viewModel.greenHistogram
                                val bHist = viewModel.blueHistogram

                                for (i in 0 until 255) {
                                    val x1 = (i / 255f) * w
                                    val x2 = ((i + 1) / 255f) * w
                                    val yr = h - (rHist[i] * h)
                                    val yg = h - (gHist[i] * h)
                                    val yb = h - (bHist[i] * h)
                                    drawLine(Color.Red.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(x1, yr), androidx.compose.ui.geometry.Offset(x2, yr), 1f)
                                    drawLine(Color.Green.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(x1, yg), androidx.compose.ui.geometry.Offset(x2, yg), 1f)
                                    drawLine(Color.Blue.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(x1, yb), androidx.compose.ui.geometry.Offset(x2, yb), 1f)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ================= 3. 滤镜强度立体滑块 =================
                AppleCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "调色浓度",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = apple.textPrimary
                )
                ApplePillBadge(
                    text = "${(viewModel.lutIntensity * 100).toInt()}%",
                    dotColor = apple.accent,
                    badgeColor = apple.surfaceSecondary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Slider(
                value = viewModel.lutIntensity,
                onValueChange = { viewModel.updateLutIntensity(it) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = apple.accent,
                    activeTrackColor = apple.accent,
                    inactiveTrackColor = apple.border
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ================= 4. 滤镜胶囊选择横滑列表 =================
                Text(
            text = "胶片与预设滤镜",
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            color = apple.textPrimary,
            modifier = Modifier.padding(start = 2.dp, bottom = 8.dp)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(viewModel.lutLibraryList.value) { item ->
                val isSelected = viewModel.currentLutId == item.id
                Box(
                    modifier = Modifier
                        .width(90.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) apple.accent.copy(alpha = 0.12f) else apple.surface)
                        .border(
                            width = 2.dp,
                            color = if (isSelected) apple.accent else apple.border,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { viewModel.selectLut(item.id) }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            bitmap = item.thumbnailBitmap.asImageBitmap(),
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .border(
                                    width = 2.dp,
                                    color = if (isSelected) apple.accent else apple.border,
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = item.name,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                            color = if (isSelected) apple.accent else apple.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // ================= 5. 底部 3D 导出大按钮 =================
                AppleButton(
            text = if (isExporting) "正在高保真渲染导出..." else "保存调色大片到相册",
            icon = Icons.Default.SaveAlt,
            colorStyle = AppleButtonColor.PRIMARY,
            height = 52.dp,
            fontSize = 16.sp,
            enabled = !isExporting,
            onClick = {
                isExporting = true
                viewModel.saveCurrentLutImage(context) { success ->
                    isExporting = false
                    val msg = if (success) "调色成片已成功保存至系统相册！" else "保存异常，请重试"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // 全屏视窗弹窗
                if (showFullscreen) {
        Dialog(onDismissRequest = { showFullscreen = false }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        LutSurfaceView(ctx).apply {
                            setLutData(viewModel.currentLutData)
                            setXmpData(viewModel.currentXmpData)
                            setImageBitmap(viewModel.currentBitmap)
                            lutIntensity = viewModel.lutIntensity
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                IconButton(
                    onClick = { showFullscreen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
            }
        }
    }
}



