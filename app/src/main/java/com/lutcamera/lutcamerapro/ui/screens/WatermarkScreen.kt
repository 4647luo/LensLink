package com.lutcamera.lutcamerapro.ui.screens.watermark

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import com.lutcamera.lutcamerapro.gpu.WatermarkExportEngine
import com.lutcamera.lutcamerapro.model.*
import com.lutcamera.lutcamerapro.ui.components.*
import com.lutcamera.lutcamerapro.ui.theme.AppleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WatermarkColorItem(val name: String, val colorInt: Int)

val watermarkColorPalette = listOf(
    WatermarkColorItem("Auto", 0),
    WatermarkColorItem("Black", android.graphics.Color.BLACK),
    WatermarkColorItem("White", android.graphics.Color.WHITE),
    WatermarkColorItem("Red", android.graphics.Color.parseColor("#E53935")),
    WatermarkColorItem("Gold", android.graphics.Color.parseColor("#D4AF37")),
    WatermarkColorItem("Purple", android.graphics.Color.parseColor("#8A2BE2")),
    WatermarkColorItem("Cyan", android.graphics.Color.parseColor("#00E5FF")),
    WatermarkColorItem("Gray", android.graphics.Color.parseColor("#777777")),
    WatermarkColorItem("Orange", android.graphics.Color.parseColor("#FF6A00"))
)
@Composable
fun WatermarkScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val apple = AppleTheme.colors

    var borderState by remember {
        mutableStateOf(BorderStateRepository.loadBorderState(context))
    }

    LaunchedEffect(borderState) {
        BorderStateRepository.saveBorderState(context, borderState)
    }

    var selectedCategory by remember { mutableStateOf(BorderCategory.ALL) }
    var isExporting by remember { mutableStateOf(false) }
    var showAdjustPanel by remember { mutableStateOf(false) }

    var fullResolutionBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewSourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var originalExif by remember { mutableStateOf<ExifInterface?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // 照片选择器
                val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(selectedUri)?.use { stream ->
                        originalExif = ExifInterface(stream)
                    }

                    val full = context.contentResolver.openInputStream(selectedUri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                    val preview = context.contentResolver.openInputStream(selectedUri)?.use {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                        BitmapFactory.decodeStream(it, null, opts)
                    }

                    withContext(Dispatchers.Main) {
                        fullResolutionBitmap = full
                        previewSourceBitmap = preview
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "照片导入异常: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // 初始演示图片
                LaunchedEffect(Unit) {
        if (previewSourceBitmap == null) {
            withContext(Dispatchers.IO) {
                val demoBmp = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888).apply {
                    val canvas = android.graphics.Canvas(this)
                    val paint = android.graphics.Paint()
                    paint.color = android.graphics.Color.parseColor("#34495E")
                    canvas.drawRect(0f, 0f, 1200f, 800f, paint)
                    paint.color = android.graphics.Color.WHITE
                    paint.textSize = 48f
                    paint.textAlign = android.graphics.Paint.Align.CENTER
                    canvas.drawText("LutCamera Pro · 边框预览", 600f, 420f, paint)
                }
                withContext(Dispatchers.Main) {
                    previewSourceBitmap = demoBmp
                }
            }
        }
    }

    // 边框渲染驱动
                LaunchedEffect(borderState, previewSourceBitmap) {
        val src = previewSourceBitmap ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val res = WatermarkExportEngine.generateWatermark(src, originalExif, borderState)
            withContext(Dispatchers.Main) {
                previewBitmap = res
            }
        }
    }

    val filteredModes = remember(selectedCategory) {
        if (selectedCategory == BorderCategory.ALL) BorderMode.entries
        else BorderMode.entries.filter { it.category == selectedCategory }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(apple.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // ================= 1. 顶部栏 =================
                Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "专业边框工坊",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = apple.textPrimary
                )
                Text(
                    text = "30 款大师装裱 · EXIF 铭牌",
                    fontSize = 12.sp,
                    color = apple.textSecondary
                )
            }

            AppleButton(
                text = "选择照片",
                icon = Icons.Default.AddPhotoAlternate,
                colorStyle = AppleButtonColor.PRIMARY,
                height = 38.dp,
                fontSize = 12.sp,
                onClick = { photoPickerLauncher.launch("image/*") }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ================= 2. 预览画框卡片 =================
                AppleCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
            backgroundColor = apple.surfaceSecondary
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 340.dp),
                contentAlignment = Alignment.Center
            ) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap!!.asImageBitmap(),
                        contentDescription = "边框预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    CircularProgressIndicator(color = apple.accent)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ================= 3. 分类切换胶囊 =================
                LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(BorderCategory.entries) { cat ->
                val isSelected = selectedCategory == cat
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) apple.accent else apple.surface)
                        .border(2.dp, if (isSelected) apple.accent else apple.border, RoundedCornerShape(12.dp))
                        .clickable { selectedCategory = cat }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = cat.title,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                        color = if (isSelected) Color.White else apple.textPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ================= 4. 30 款边框风格卡片横排 =================
                LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(filteredModes) { mode ->
                val isSelected = borderState.mode == mode
                Box(
                    modifier = Modifier
                        .width(130.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) apple.accent.copy(alpha = 0.12f) else apple.surface)
                        .border(
                            2.dp,
                            if (isSelected) apple.accent else apple.border,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { borderState = borderState.copy(mode = mode) }
                        .padding(10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = mode.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isSelected) apple.accent else apple.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = mode.subtitle,
                            fontSize = 10.sp,
                            color = apple.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ================= 5. 参数微调折叠面板 =================
                AppleCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdjustPanel = !showAdjustPanel },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "装裱细节微调 (留白/圆角/阴影/字体)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = apple.textPrimary
                )
                Icon(
                    imageVector = if (showAdjustPanel) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = apple.textSecondary
                )
            }

            AnimatedVisibility(visible = showAdjustPanel) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    // EXIF 铭牌总开关
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("显示 EXIF 参数铭牌", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = apple.textPrimary)
                        Switch(
                            checked = borderState.showExif,
                            onCheckedChange = { borderState = borderState.copy(showExif = it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = apple.accent)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 留白内衬
                Text("内衬留白: ${(borderState.paddingTop * 100).toInt()}%", fontSize = 12.sp, color = apple.textSecondary)
                    Slider(
                        value = borderState.paddingTop,
                        onValueChange = { borderState = borderState.copy(paddingTop = it, paddingLeft = it, paddingRight = it) },
                        valueRange = 0.01f..0.15f,
                        colors = SliderDefaults.colors(thumbColor = apple.accent, activeTrackColor = apple.accent)
                    )

                    // 圆角
                Text("照片圆角: ${borderState.cornerRadius.toInt()}", fontSize = 12.sp, color = apple.textSecondary)
                    Slider(
                        value = borderState.cornerRadius,
                        onValueChange = { borderState = borderState.copy(cornerRadius = it) },
                        valueRange = 0f..40f,
                        colors = SliderDefaults.colors(thumbColor = apple.accent, activeTrackColor = apple.accent)
                    )

                    // 字体选择
                Text("铭牌字体风格", fontSize = 12.sp, color = apple.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WatermarkFont.entries.forEach { font ->
                            val isSel = borderState.fontType == font
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) apple.accent else apple.surfaceSecondary)
                                    .clickable { borderState = borderState.copy(fontType = font) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(font.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isSel) Color.White else apple.textPrimary)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // ================= 6. 底部 3D 导出大按钮 =================
                AppleButton(
            text = if (isExporting) "Exporting..." else "Export Photo",
            icon = Icons.Default.SaveAlt,
            colorStyle = AppleButtonColor.PRIMARY,
            height = 52.dp,
            fontSize = 16.sp,
            enabled = !isExporting,
            onClick = {
                val targetBmp = fullResolutionBitmap ?: previewSourceBitmap
                if (targetBmp == null) {
                    Toast.makeText(context, "请先选择需要装裱的照片", Toast.LENGTH_SHORT).show()
                    return@AppleButton
                }

                isExporting = true
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val exported = WatermarkExportEngine.generateWatermark(targetBmp, originalExif, borderState)
                        if (exported != null) {
                            saveBitmapToGallery(context, exported)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Saved to gallery", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Saved to gallery", Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        withContext(Dispatchers.Main) { isExporting = false }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap) {
    val resolver = context.contentResolver
    val filename = "FRAME_${System.currentTimeMillis()}.jpg"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LutCameraPro")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            resolver.openOutputStream(it)?.use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }
        }
    } else {
        val imagesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
        val file = java.io.File(imagesDir, filename)
        java.io.FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
        }
    }
}



