package com.lutcamera.lutcamerapro.ui.screens.connect.nikon

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lutcamera.lutcamerapro.model.BorderState
import com.lutcamera.lutcamerapro.network.NetworkUtils
import com.lutcamera.lutcamerapro.network.NikonPtpClient
import com.lutcamera.lutcamerapro.ui.screens.connect.wired.SaveMode
import com.lutcamera.lutcamerapro.ui.viewmodel.LutViewModel
import com.lutcamera.lutcamerapro.ui.viewmodel.SettingsViewModel

@Composable
fun NikonSection(
    viewModel: NikonViewModel = viewModel(),
    lutViewModel: LutViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
    borderState: BorderState = BorderState(),
    onPhotoProcessed: (fileName: String) -> Unit = {}
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val currentGlobalSaveMode = runCatching { SaveMode.valueOf(settingsViewModel.saveModeName) }.getOrDefault(SaveMode.DUAL)
    SideEffect {
        viewModel.configurePhotoProcessing(
            context = context.applicationContext,
            lut = lutViewModel.currentLutData,
            intensity = lutViewModel.lutIntensity,
            autoApplyLut = settingsViewModel.autoApplyLut,
            autoApplyBorder = settingsViewModel.autoApplyBorder,
            saveMode = currentGlobalSaveMode,
            borderState = borderState,
            xmp = lutViewModel.currentXmpData
        )
    }

    LaunchedEffect(Unit) {
        viewModel.refreshNetworkInfo()
        viewModel.uiEvent.collect { event ->
            when (event) {
                is NikonUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is NikonUiEvent.NewPhotoArrived -> {
                    // Receipt is not a successful gallery save; completion arrives separately.
                }
                is NikonUiEvent.PhotoSaved -> {
                    onPhotoProcessed(event.fileName)
                }
                is NikonUiEvent.DownloadCompleted -> {
                    val msg = if (event.success) "照片已按保存设置存入手机相册" else "下载失败: ${event.fileName}"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF14161B),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // 1. 标题栏与实时连接状态
                Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.WifiTethering,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "尼康无线图传与联机通道",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = when {
                                    viewModel.isConnected -> Color(0xFF00E676)
                                    viewModel.isConnecting -> Color(0xFFFFB300)
                                    else -> Color(0xFFFF3D00)
                                },
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            viewModel.isConnected -> "Connected"
                            viewModel.isConnecting -> "Connecting"
                            else -> "Disconnected"
                        },
                        color = when {
                            viewModel.isConnected -> Color(0xFF00E676)
                            viewModel.isConnecting -> Color(0xFFFFB300)
                            else -> Color.Gray
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. AP 模式 与 STA 模式双切换选项区
                Surface(
                modifier = Modifier.fillMaxWidth().height(38.dp),
                shape = RoundedCornerShape(19.dp),
                color = Color(0xFF1E222B)
            ) {
                Row(modifier = Modifier.padding(3.dp)) {
                    WirelessSubMode.values().forEach { mode ->
                        val isSelected = viewModel.wirelessMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) Color(0xFF7C4DFF) else Color.Transparent)
                                .clickable { viewModel.switchWirelessMode(mode) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode.title,
                                color = if (isSelected) Color.White else Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. AP 模式 vs STA 模式引导信息区
                if (viewModel.wirelessMode == WirelessSubMode.AP_DIRECT) {
                // AP 模式信息
                Surface(
                    color = Color(0xFF1B202A),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("AP 模式指引 (手机直连相机 Wi-Fi)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "1. 在相机中开启「连接到智能手机」\n2. 手机连接相机 Wi-Fi (通常为 DIRECT-xxxx)\n3. 相机默认 IP 为 192.168.1.1，端口 15740",
                            color = Color.LightGray,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            } else {
                // STA 局域网 / 手机热点信息
                Surface(
                    color = Color(0xFF261D33),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFFFF4081), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("STA 模式指引 (相机连手机热点 / 路由器)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("FTP_INFO", "${viewModel.localIp}:${viewModel.ftpPort}"))
                                    Toast.makeText(context, "已复制 FTP 地址", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "复制", tint = Color.LightGray, modifier = Modifier.size(14.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                        text = "Phone -> Camera Setup Guide",
                            color = Color.LightGray,
                            fontSize = 10.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                        text = "Phone -> Camera Setup Guide",
                                color = Color(0xFF00E676),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "匿名登录 (免密)",
                                color = Color.Gray,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            // 4. IP 输入与连接控制行
                Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = viewModel.cameraIp,
                    onValueChange = { viewModel.cameraIp = it },
                    label = { Text("相机控制 IP (PTP 端口 15740)", color = Color.Gray, fontSize = 11.sp) },
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    singleLine = true,
                    enabled = !viewModel.isConnected && !viewModel.isConnecting,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.toggleConnection(
                            appContext = context.applicationContext,
                            getLut = { lutViewModel.currentLutData },
                            getIntensity = { lutViewModel.lutIntensity },
                            getAutoApplyLut = { settingsViewModel.autoApplyLut },
                            getAutoApplyBorder = { settingsViewModel.autoApplyBorder },
                            getSaveMode = { runCatching { SaveMode.valueOf(settingsViewModel.saveModeName) }.getOrDefault(SaveMode.DUAL) },
                            getBorderState = { borderState },
                            getXmp = { lutViewModel.currentXmpData },
                            // PhotoSaved is the single completion notification for this screen.
                            onPhotoProcessed = {}
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            viewModel.isConnected -> Color(0xFFFF3D00)
                            viewModel.isConnecting -> Color(0xFFFFB300)
                            else -> Color(0xFF7C4DFF)
                        }
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(52.dp)
                ) {
                    Text(
                        text = when {
                            viewModel.isConnecting -> "Connecting..."
                            viewModel.isConnected -> "Disconnect"
                            else -> "Connect Nikon"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            NikonPhotoTransferPanel(viewModel, settingsViewModel, lutViewModel)
            Spacer(modifier = Modifier.height(10.dp))

            // 5. 快速线控与图传控制区 (连接成功后显示)
                if (viewModel.isConnected) {
                // 同一个 flow 只订阅一次：按钮底色和文案共用这一份状态，避免双收集器。
                val liveActive by viewModel.isLiveViewActive.collectAsStateWithLifecycle()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.triggerShutter() },
                        modifier = Modifier.weight(1f).height(38.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("快门拍照", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { viewModel.triggerAf() },
                        modifier = Modifier.weight(1f).height(38.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF262C3A)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.FilterCenterFocus, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("AF 对焦", color = Color.White, fontSize = 12.sp)
                    }

                    Button(
                        onClick = { viewModel.toggleLiveView() },
                        modifier = Modifier.weight(1f).height(38.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (liveActive) Color(0xFF7C4DFF) else Color(0xFF262C3A)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (liveActive) "Stop LiveView" else "LiveView", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }

            // 6. 统计与终端日志
                Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Auto captured: ${viewModel.capturedCount}",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
                Text(
                    text = "日志终端",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0C0E12))
                    .border(1.dp, Color(0xFF1F2430), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                if (viewModel.logs.isEmpty()) {
                    Text("等待连接尼康相机 (支持 AP 与 STA 模式)...", color = Color.DarkGray, fontSize = 10.sp)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(viewModel.logs, contentType = { "nikon-log" }) { log ->
                            Text(log, color = Color(0xFF00FF66), fontSize = 10.sp, lineHeight = 13.sp)
                        }
                    }
                }
            }
        }
    }
}


