package com.lutcamera.lutcamerapro.ui.screens.connect.nikon

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lutcamera.lutcamerapro.network.NikonPtpClient
import com.lutcamera.lutcamerapro.ui.components.AppleCard
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import java.util.Locale

@Composable
fun NikonDiagnosticsPanel(vm: NikonViewModel, ip: String, onIp: (String) -> Unit) {
    val context = LocalContext.current
    val exportLogs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { vm.exportDetailedLogs(context, it) }
    }
    val recoverIdentity = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.restorePairingIdentity(context, it) }
    }
    val stage by vm.connectionStage.collectAsStateWithLifecycle()
    val camera by vm.actualCameraLabel.collectAsStateWithLifecycle()
    val stats by vm.liveMetrics.collectAsStateWithLifecycle()
    val live by vm.isLiveViewActive.collectAsStateWithLifecycle()
    var advanced by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<Long?>(null) }
    val desc = vm.compressionDesc
    LaunchedEffect(desc) { choice = desc?.currentRaw }
    val modes = listOf(
        NikonPtpClient.ConnectionMode.CAMERA_AP to "相机热点",
        NikonPtpClient.ConnectionMode.PHONE_HOTSPOT to "手机热点 STA",
        NikonPtpClient.ConnectionMode.ROUTER to "同一路由器 STA")
    AppleCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("尼康无线连接", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                modes.forEach { (mode, title) ->
                    FilterChip(selected = vm.connectionMode == mode,
                        enabled = !vm.isConnected && !vm.isConnecting,
                        onClick = {
                            vm.connectionMode = mode
                            if (mode == NikonPtpClient.ConnectionMode.CAMERA_AP) onIp(NikonPtpClient.DEFAULT_NIKON_AP_IP)
                        }, label = { Text(title) })
                }
            }
            Text(if (vm.connectionMode == NikonPtpClient.ConnectionMode.CAMERA_AP)
                "手机连接相机 Wi-Fi 后，使用下方按钮连接。"
                else "STA 验证：先让相机加入手机热点或同一路由器，填写相机获得的 IP。加入 Wi-Fi 后仍可能需要尼康电脑配对；本版不代替配对工具。",
                fontSize = 12.sp)
            OutlinedTextField(value = ip, onValueChange = onIp, label = { Text("相机 IP 地址") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, enabled = !vm.isConnected && !vm.isConnecting, modifier = Modifier.fillMaxWidth())
            if (vm.connectionMode != NikonPtpClient.ConnectionMode.CAMERA_AP && ip == NikonPtpClient.DEFAULT_NIKON_AP_IP) {
                Text("当前仍是默认地址 192.168.1.1。请核对并填写相机加入热点后屏幕显示的 IP。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            TextButton(enabled = !vm.exportingLogs, onClick = {
                exportLogs.launch("Nikon-STA-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())}.zip")
            }) { Text(if (vm.exportingLogs) "正在导出…" else "导出详细连接日志 ZIP") }
            Text("自动记录连接、网络路由、收发协议及错误，断线后仍可导出。包含局域网 IP 和设备标识；图像数据仅留包头摘要。", fontSize = 11.sp)
            if (vm.exportLogMessage.isNotBlank()) Text(vm.exportLogMessage, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }) { Text("网络设置") }
                TextButton(onClick = { vm.discoverCameras(context) }) { Text(if (vm.scanning) "停止发现" else "发现局域网设备") }
            }
            vm.discoveredCameras.forEach { device ->
                TextButton(enabled = !vm.isConnected && !vm.isConnecting, onClick = { onIp(device.ip) }) {
                    Text("${device.name} · ${device.ip}")
                }
            }
            if (vm.scanning) Text("发现广播中的 PTP 设备；不广播的相机需要手动输入 IP。", fontSize = 12.sp)
            if (camera.isNotBlank()) Text(camera, fontSize = 12.sp)
            Text(stage, fontSize = 12.sp)
            if (stage.contains("InitFail 1")) {
                Text("相机已收到连接请求，但拒绝了这次的应用身份。已有旧日志时先恢复原身份；" +
                    "找不到旧日志就换一个新身份，让相机重新弹一次配对确认。", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { recoverIdentity.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                        Text("从旧日志恢复配对身份")
                    }
                    TextButton(onClick = { vm.resetPairingIdentity(context) }) {
                        Text("换新身份并重新配对")
                    }
                }
            }
            if (vm.pairingMessage.isNotBlank()) Text(vm.pairingMessage, fontSize = 13.sp)
            if (vm.isConnected && !vm.ptpClient.canControlCamera) {
                Text("电脑配对（Z30 实验功能）", style = MaterialTheme.typography.titleSmall)
                Text("保持相机停在电脑配对页面。请求验证码后，核对相机和手机的数字。", fontSize = 12.sp)
                if (vm.pairingCode == null) {
                    Button(enabled = !vm.pairingBusy, onClick = vm::startPairing) { Text("开始配对 / 请求验证码") }
                } else {
                    Text("验证码：${vm.pairingCode}", style = MaterialTheme.typography.headlineMedium)
                    Button(enabled = !vm.pairingBusy, onClick = vm::confirmPairing) { Text("与相机数字一致，确认配对") }
                    TextButton(enabled = !vm.pairingBusy, onClick = vm::cancelPairing) { Text("不一致 / 取消配对") }
                }
                if (vm.pairingBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (vm.isConnected) {
                Text(String.format(Locale.getDefault(), "接收 %.1f fps · 解码 %.1f fps · %d×%d",
                    stats.receiveFps, stats.decodeFps, stats.width, stats.height), fontSize = 13.sp)
                Text(String.format(Locale.getDefault(), "图像 %.2f MB/s · %.0f KB/帧\n取帧 %.1f ms · 解码 %.1f ms · 跳过 %d 帧",
                    stats.payloadMBps, stats.jpegKB, stats.requestMs, stats.decodeMs, stats.skipped), fontSize = 12.sp)
                Text("图像吞吐量不等于 Wi-Fi 带宽；取帧耗时包括相机响应和命令排队。解码帧率不等于屏幕显示帧率。", fontSize = 11.sp)
                TextButton(enabled = !vm.diagnosticsBusy, onClick = { vm.toggleLiveView() }) {
                    Text(if (live) "暂停取景" else "恢复取景")
                }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起画质测试" else "展开画质测试") }
                if (advanced) {
                    TextButton(enabled = !vm.diagnosticsBusy, onClick = vm::refreshCompression) { Text("读取相机压缩档位") }
                    Text(vm.diagnosticsMessage, fontSize = 12.sp)
                    if (desc != null) {
                        Text("当前档位：${desc.currentRaw}${if (desc.writable) "" else "（只读）"}", fontSize = 12.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            desc.enumValues.forEach { value ->
                                FilterChip(selected = choice == value, enabled = desc.writable && !vm.diagnosticsBusy,
                                    onClick = { choice = value }, label = { Text("$value") })
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(enabled = desc.writable && !vm.diagnosticsBusy && choice != null,
                                onClick = { choice?.let { vm.setCompression(it, trial = true) } }) { Text("试用 6 秒") }
                            TextButton(enabled = desc.writable && !vm.diagnosticsBusy && choice != null,
                                onClick = { choice?.let { vm.setCompression(it) } }) { Text("应用此档") }
                            TextButton(enabled = desc.writable && !vm.diagnosticsBusy && vm.savedCompression != null,
                                onClick = { vm.savedCompression?.let { vm.setCompression(it, restore = true) } }) { Text("恢复原档") }
                        }
                    }
                    if (vm.diagnosticsBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
