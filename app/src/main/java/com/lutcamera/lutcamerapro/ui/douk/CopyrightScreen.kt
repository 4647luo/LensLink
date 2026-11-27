package com.lutcamera.lutcamerapro.ui.douk

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.platform.LocalContext
import com.lutcamera.lutcamerapro.util.IptcStamp
import com.lutcamera.lutcamerapro.util.IptcTemplate
import com.lutcamera.lutcamerapro.util.IptcTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 版权与 IPTC（独立设置页）。
 * 「从相机导入 IPTC 数据」= 从机身读回作者/版权原文填进输入框；
 * 「写入相机」= 把输入框里的文字写回机身版权属性（真实 PTP SetDevicePropValue）。
 */
@Composable
fun CopyrightScreen(state: DoukState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var artist by remember { mutableStateOf("") }
    var copyright by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 照片文件版权（IPTC）：值直接落 DoukPrefs，下载链路读取时不必再问 UI
    var iptcOn by remember { mutableStateOf(DoukPrefs.iptcEnabled) }
    var iptcArtist by remember { mutableStateOf(DoukPrefs.iptcArtist) }
    var iptcCopyright by remember { mutableStateOf(DoukPrefs.iptcCopyright) }
    var iptcCredit by remember { mutableStateOf(DoukPrefs.iptcCredit) }
    var iptcKeywords by remember { mutableStateOf(DoukPrefs.iptcKeywords) }
    var templates by remember { mutableStateOf(IptcTemplates.list()) }
    var activeName by remember { mutableStateOf(DoukPrefs.iptcTemplateName) }
    var draftName by remember { mutableStateOf("") }

    fun applyTemplate(t: IptcTemplate) {
        iptcArtist = t.artist
        iptcCopyright = t.copyright
        iptcCredit = t.credit
        iptcKeywords = t.keywords
        activeName = t.name
        IptcTemplates.activate(t)
        status = "已套用模板：${t.name}"
    }

    val batchLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val profile = IptcTemplate("批量", iptcArtist, iptcCopyright, iptcCredit, iptcKeywords).toStampProfile()
        if (profile.isEmpty) {
            status = "先填作者或版权，再批量写入"
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            val (ok, skipped) = withContext(Dispatchers.IO) {
                var ok = 0
                var skipped = 0
                uris.forEach { uri ->
                    if (IptcStamp.apply(context, uri.toString(), profile)) ok++ else skipped++
                }
                ok to skipped
            }
            busy = false
            status = "已写入 $ok 张，跳过 $skipped 张（RAW、非 JPG 或无法写入）"
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = DoukSpace.page, top = DoukSpace.sm, bottom = DoukSpace.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = DoukColors.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text("版权与 IPTC", style = DoukType.h1, color = DoukColors.textPrimary)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DoukSpace.page)
        ) {
            Spacer(modifier = Modifier.height(DoukSpace.sm))
            Text(
                "机身「版权信息」存的是作者与版权两行文字，拍出来的照片会带进文件元数据。" +
                    "先点下面从相机读入，改完再写回去。",
                style = DoukType.caption,
                color = DoukColors.textTertiary
            )
            Spacer(modifier = Modifier.height(DoukSpace.md))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DoukShapes.cardSmall)
                    .doukCardSurface(DoukShapes.cardSmall, tint = DoukColors.accentSoft)
                    .clickable {
                        if (!state.isConnected) {
                            status = "未连接相机，读不到机身版权"
                        } else {
                            busy = true
                            scope.launch {
                                val (a, c) = state.vm.readCopyrightFromCamera()
                                artist = a
                                copyright = c
                                busy = false
                                status = if (a.isBlank() && c.isBlank()) {
                                    "机身没有可读的版权信息"
                                } else "已从相机读入"
                            }
                        }
                    }
                    .padding(horizontal = DoukSpace.md, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = DoukColors.accent,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(DoukSpace.xs))
                }
                Text("从相机导入 IPTC 数据", style = DoukType.body, color = DoukColors.accent)
            }

            Spacer(modifier = Modifier.height(DoukSpace.md))
            OutlinedTextField(
                value = artist,
                onValueChange = { artist = it },
                label = { Text("作者 / 摄影师") },
                singleLine = true,
                shape = DoukShapes.cardSmall,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(DoukSpace.xs))
            OutlinedTextField(
                value = copyright,
                onValueChange = { copyright = it },
                label = { Text("版权声明") },
                singleLine = true,
                shape = DoukShapes.cardSmall,
                modifier = Modifier.fillMaxWidth()
            )

            status?.let {
                Spacer(modifier = Modifier.height(DoukSpace.xs))
                Text(it, style = DoukType.label, color = DoukColors.textTertiary)
            }

            Spacer(modifier = Modifier.height(DoukSpace.md))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DoukShapes.pill)
                    .background(DoukColors.accent)
                    .clickable {
                        state.vm.uploadCopyright(artist.trim(), copyright.trim())
                        status = "已发送写入命令，结果见控制页提示与日志"
                    }
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("写入相机", style = DoukType.title, color = Color.White)
            }

            // ===== 照片文件版权（IPTC）：下载/导入落盘时写入 =====
            Spacer(modifier = Modifier.height(34.dp))
            Text("照片文件版权（IPTC）", style = DoukType.title, color = DoukColors.textPrimary)
            Spacer(modifier = Modifier.height(DoukSpace.xs))
            Text(
                "下载或导入照片时把这套信息写进文件：EXIF 的 Artist 与 Copyright，以及 JPEG 内的 IPTC" +
                    "（By-line、Copyright Notice、Credit、Keywords）。只处理 JPG，RAW 与视频跳过。",
                style = DoukType.caption,
                color = DoukColors.textTertiary
            )
            Spacer(modifier = Modifier.height(DoukSpace.sm))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("写入照片文件", style = DoukType.body, color = DoukColors.textPrimary)
                    Text(
                        "落盘后自动补写，不用再导一次",
                        style = DoukType.caption,
                        color = DoukColors.textTertiary
                    )
                }
                Switch(
                    checked = iptcOn,
                    onCheckedChange = {
                        iptcOn = it
                        DoukPrefs.iptcEnabled = it
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = DoukColors.accent)
                )
            }

            Spacer(modifier = Modifier.height(DoukSpace.xs))
            OutlinedTextField(
                value = iptcArtist,
                onValueChange = {
                    iptcArtist = it
                    DoukPrefs.iptcArtist = it
                },
                label = { Text("作者（Artist / By-line）") },
                singleLine = true,
                shape = DoukShapes.cardSmall,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(DoukSpace.xs))
            OutlinedTextField(
                value = iptcCopyright,
                onValueChange = {
                    iptcCopyright = it
                    DoukPrefs.iptcCopyright = it
                },
                label = { Text("版权声明（Copyright）") },
                singleLine = true,
                shape = DoukShapes.cardSmall,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(DoukSpace.xs))
            OutlinedTextField(
                value = iptcCredit,
                onValueChange = {
                    iptcCredit = it
                    DoukPrefs.iptcCredit = it
                },
                label = { Text("署名 / 来源（Credit，选填）") },
                singleLine = true,
                shape = DoukShapes.cardSmall,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(DoukSpace.xs))
            OutlinedTextField(
                value = iptcKeywords,
                onValueChange = {
                    iptcKeywords = it
                    DoukPrefs.iptcKeywords = it
                },
                label = { Text("关键词（逗号分隔，选填）") },
                singleLine = true,
                shape = DoukShapes.cardSmall,
                modifier = Modifier.fillMaxWidth()
            )

            // 模板：点一下整套值回填，× 删除
            Spacer(modifier = Modifier.height(DoukSpace.md))
            Text("IPTC 模板", style = DoukType.body, color = DoukColors.textPrimary)
            if (templates.isEmpty()) {
                Spacer(modifier = Modifier.height(DoukSpace.xxs))
                Text(
                    "还没有模板。填好上面几项，起个名字存下来，下次一点就套用。",
                    style = DoukType.caption,
                    color = DoukColors.textTertiary
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    items(templates, key = { it.name }) { t ->
                        val isActive = t.name == activeName
                        Row(
                            modifier = Modifier
                                .clip(DoukShapes.pill)
                                .doukCardSurface(DoukShapes.pill, tint = DoukColors.accentSoft)
                                .background(if (isActive) DoukColors.accent else Color.Transparent)
                                .clickable { applyTemplate(t) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                t.name,
                                style = DoukType.label,
                                color = if (isActive) Color.White else DoukColors.textPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "×",
                                style = DoukType.label,
                                color = if (isActive) Color.White.copy(alpha = 0.8f) else DoukColors.textTertiary,
                                modifier = Modifier.clickable {
                                    IptcTemplates.delete(t.name)
                                    templates = IptcTemplates.list()
                                    if (isActive) activeName = ""
                                    status = "已删除模板：${t.name}"
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(DoukSpace.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    label = { Text("模板名称") },
                    singleLine = true,
                    shape = DoukShapes.cardSmall,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    modifier = Modifier
                        .clip(DoukShapes.pill)
                        .doukCardSurface(DoukShapes.pill, tint = DoukColors.accentSoft)
                        .clickable {
                            val name = draftName.trim()
                            if (name.isEmpty()) {
                                status = "先给模板起个名字"
                            } else {
                                val t = IptcTemplate(name, iptcArtist, iptcCopyright, iptcCredit, iptcKeywords)
                                IptcTemplates.upsert(t)
                                templates = IptcTemplates.list()
                                activeName = name
                                DoukPrefs.iptcTemplateName = name
                                draftName = ""
                                status = "已保存模板：$name"
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text("保存模板", style = DoukType.label, color = DoukColors.accent)
                }
            }

            // 批量写进相册里已有的照片
            Spacer(modifier = Modifier.height(DoukSpace.md))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DoukShapes.pill)
                    .doukCardSurface(DoukShapes.pill, tint = DoukColors.accentSoft)
                    .clickable { batchLauncher.launch(arrayOf("image/jpeg")) }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("批量写入已选照片", style = DoukType.body, color = DoukColors.accent)
            }
            Spacer(modifier = Modifier.height(DoukSpace.xs))
            Text(
                "选几张写几张，写入的是上面这套值；已存在的 IPTC 版权会被替换，照片本身不会被重新压缩。",
                style = DoukType.caption,
                color = DoukColors.textTertiary
            )

            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}
