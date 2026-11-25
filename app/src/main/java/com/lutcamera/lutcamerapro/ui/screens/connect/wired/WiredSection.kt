package com.lutcamera.lutcamerapro.ui.screens.connect.wired

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lutcamera.lutcamerapro.model.BorderState
import com.lutcamera.lutcamerapro.ui.theme.AppleTheme
import com.lutcamera.lutcamerapro.ui.viewmodel.LutViewModel

@Composable
fun WiredSection(
    viewModel: WiredViewModel = viewModel(),
    lutViewModel: LutViewModel = viewModel(),
    borderState: BorderState = BorderState(),
    onPhotoProcessed: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val colors = AppleTheme.colors

    LaunchedEffect(lutViewModel.currentLutData, lutViewModel.lutIntensity, borderState) {
        viewModel.syncParamsToService(
            lut = lutViewModel.currentLutData,
            intensity = lutViewModel.lutIntensity,
            border = borderState
        )
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is WiredUiEvent.ShowToast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is WiredUiEvent.NewPhotoArrived -> onPhotoProcessed(event.fileName)
                is WiredUiEvent.BatchImportFinished -> Toast.makeText(context, "Transfer complete: ${event.successCount}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = colors.surface,
        border = if (colors.isDark) androidx.compose.foundation.BorderStroke(1.dp, colors.border) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (viewModel.isConnected) colors.accent.copy(alpha = 0.15f) else colors.surfaceSecondary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Usb,
                            contentDescription = null,
                            tint = if (viewModel.isConnected) colors.accent else colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (viewModel.isConnected) viewModel.cameraName else "No USB Camera",
                                color = colors.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (viewModel.isConnected) colors.accent else colors.accentRed)
                            )
                        }
                        Text(
                            text = if (viewModel.isConnected) "PTP connected" else "Please connect via OTG and turn on camera",
                            color = colors.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                if (viewModel.isConnected) {
                    Surface(
                        color = colors.accentRed.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(50.dp),
                        modifier = Modifier.clickable { viewModel.disconnectUsb(context) }
                    ) {
                        Text(
                            text = "Disconnect",
                            color = colors.accentRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                } else {
                    Surface(
                        color = colors.accent,
                        shape = RoundedCornerShape(50.dp),
                        modifier = Modifier.clickable { viewModel.checkUsbConnection(context) }
                    ) {
                        Text(
                            text = "Detect device",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = viewModel.isConnected) { viewModel.triggerUsbShutter() },
                    shape = RoundedCornerShape(12.dp),
                    color = if (viewModel.isConnected) colors.accent else colors.surfaceSecondary
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = if (viewModel.isConnected) Color.White else colors.textSecondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("线控蹇顐︽，", color = if (viewModel.isConnected) Color.White else colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = viewModel.isConnected) { viewModel.triggerUsbAf() },
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceSecondary
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("自动对焦 AF", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                val liveActive by viewModel.isUsbLiveViewActive.collectAsStateWithLifecycle()
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = viewModel.isConnected) { viewModel.toggleUsbLiveView() },
                    shape = RoundedCornerShape(12.dp),
                    color = if (liveActive) colors.accent else colors.surfaceSecondary
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = null, tint = if (liveActive) Color.White else colors.textPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (liveActive) "关闭图传" else "实时图传", color = if (liveActive) Color.White else colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceSecondary
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("拍摄后自动拉取并应用 LUT", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Returned photos will apply LUT immediately", color = colors.textSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = viewModel.autoApplyLut,
                        onCheckedChange = { viewModel.toggleAutoApplyLut(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accent)
                    )
                }
            }
        }
    }
}

