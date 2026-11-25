package com.lutcamera.lutcamerapro.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lutcamera.lutcamerapro.ui.components.*
import com.lutcamera.lutcamerapro.ui.theme.AppleTheme
import com.lutcamera.lutcamerapro.ui.viewmodel.LutItem
import com.lutcamera.lutcamerapro.ui.viewmodel.LutViewModel

@Composable
fun LutLibraryScreen(
    onBack: () -> Unit,
    viewModel: LutViewModel = viewModel()
) {
    val context = LocalContext.current
    val apple = AppleTheme.colors

    var searchQuery by remember { mutableStateOf("") }
    var selectedItemForMenu by remember { mutableStateOf<LutItem?>(null) }
    var itemToRename by remember { mutableStateOf<LutItem?>(null) }
    var newNameText by remember { mutableStateOf("") }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.importAndAddLutToLibrary(context, it)
            Toast.makeText(context, "滤镜导入处理中...", Toast.LENGTH_SHORT).show()
        }
    }

    val categories = listOf("全部", "收藏", "风景", "人像", "胶片", "电影")

    val filteredList = remember(viewModel.lutLibraryList.value, viewModel.selectedCategory, searchQuery) {
        val list = when (viewModel.selectedCategory) {
            "全部" -> viewModel.lutLibraryList.value
            "收藏" -> viewModel.lutLibraryList.value.filter { it.isFavorite }
            else -> viewModel.lutLibraryList.value.filter { it.category == viewModel.selectedCategory }
        }
        if (searchQuery.isBlank()) {
            list
        } else {
            list.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(apple.background)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // ================= 1. 顶部栏 (返回 + 导入) =================
                Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    size = 40.dp,
                    colorStyle = AppleButtonColor.SURFACE,
                    onClick = onBack
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "LUT Library & Presets",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = apple.textPrimary
                )
            }

            // 导入本地 .cube / .xmp
                AppleButton(
                text = "导入预设",
                icon = Icons.Default.Add,
                colorStyle = AppleButtonColor.PRIMARY,
                height = 40.dp,
                fontSize = 13.sp,
                onClick = { fileLauncher.launch(arrayOf("*/*")) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ================= 2. 搜索栏 =================
                OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("搜索滤镜名称...", color = apple.textTertiary, fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = apple.textSecondary) },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = apple.surface,
                unfocusedContainerColor = apple.surface,
                focusedBorderColor = apple.accent,
                unfocusedBorderColor = apple.border
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        // ================= 3. 分类切换胶囊 =================
                LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(categories, contentType = { "lut-category" }) { cat ->
                val isSelected = viewModel.selectedCategory == cat
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) apple.accent else apple.surface)
                        .border(
                            2.dp,
                            if (isSelected) apple.accent else apple.border,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { viewModel.setCategory(cat) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = cat,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                        color = if (isSelected) Color.White else apple.textPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ================= 4. 滤镜网格卡片 =================
                LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(filteredList, contentType = { "lut-item" }) { item ->
                val isCurrent = viewModel.currentLutId == item.id
                AppleCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.selectLut(item.id)
                            Toast.makeText(context, "已套用: ${item.name}", Toast.LENGTH_SHORT).show()
                        },
                    cornerRadius = 16.dp,
                    borderColor = if (isCurrent) apple.accent else apple.border
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            Image(
                                bitmap = item.thumbnailBitmap.asImageBitmap(),
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.5.dp, apple.border, RoundedCornerShape(12.dp))
                            )

                            // 收藏星标
                IconButton(
                                onClick = { viewModel.toggleFavorite(item.id) },
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.5f))
                            ) {
                                Icon(
                                    imageVector = if (item.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = "收藏",
                                    tint = if (item.isFavorite) apple.accent else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isCurrent) apple.accent else apple.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.category,
                                    fontSize = 11.sp,
                                    color = apple.textSecondary
                                )
                            }

                            // 更多操作
                IconButton(
                                onClick = { selectedItemForMenu = item },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = apple.textSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // 更多操作弹窗 (重命名、删除)
                selectedItemForMenu?.let { item ->
        Dialog(onDismissRequest = { selectedItemForMenu = null }) {
            AppleCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = item.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = apple.textPrimary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                AppleButton(
                    text = "Rename",
                    icon = Icons.Default.Edit,
                    colorStyle = AppleButtonColor.SURFACE,
                    height = 42.dp,
                    fontSize = 13.sp,
                    onClick = {
                        itemToRename = item
                        newNameText = item.name
                        selectedItemForMenu = null
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 内置预设不可删除
                val isBuiltin = item.id.startsWith("preset_")
                if (!isBuiltin) {
                    AppleButton(
                        text = "Delete Preset",
                        icon = Icons.Default.Delete,
                        colorStyle = AppleButtonColor.DESTRUCTIVE,
                        height = 42.dp,
                        fontSize = 13.sp,
                        onClick = {
                            viewModel.deleteLut(item.id)
                            selectedItemForMenu = null
                            Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    // 重命名弹窗
                itemToRename?.let { item ->
        Dialog(onDismissRequest = { itemToRename = null }) {
            AppleCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Rename Preset",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = apple.textPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = newNameText,
                    onValueChange = { newNameText = it },
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
                        onClick = { itemToRename = null },
                        modifier = Modifier.weight(1f)
                    )
                    AppleButton(
                        text = "保存",
                        colorStyle = AppleButtonColor.PRIMARY,
                        height = 40.dp,
                        fontSize = 13.sp,
                        onClick = {
                            if (newNameText.isNotBlank()) {
                                viewModel.renameLut(item.id, newNameText.trim())
                            }
                            itemToRename = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}



