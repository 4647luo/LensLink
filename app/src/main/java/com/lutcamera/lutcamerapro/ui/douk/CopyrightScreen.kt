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
            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}
