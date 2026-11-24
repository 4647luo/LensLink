package com.lutcamera.lutcamerapro.ui.douk

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/**
 * 大图细节页：黑色底、独立窗口，手势全归 Telephoto 管。
 *
 * 和看图页分开是有意的。看图页那套手势机要同时处理横滑换图、Live 长按播放、
 * 上滑分享、双击撑满，而 Telephoto 的 `Modifier.zoomable` 会把包括双击在内的
 * 所有 pointer 事件吃掉，两者塞在一起必然互相抢手势。这一页只做一件事：
 * 把当前这张图放大看清楚。
 *
 * 这里刻意不走 Telephoto 的分块（sub-sampling）渲染，直接把看图页已经解好的
 * 高清位图交给它做缩放。原因是 0.13.0 的分块画法在本项目的 Compose 1.7 运行时
 * 上会把可见区域算成顶部一条，放大后整屏只剩窄带能画出来；等构建链升到
 * Kotlin 2.x / Compose 1.8 之后再单独验证分块路径。位图本身已经按 6144 长边解好，
 * 在 2880 宽的屏幕上放到 2× 仍是 1:1 像素，够用。
 *
 * @param bitmap 当前这张的高清位图（看图页的 hd，或退而求其次用屏上那张）。
 */
@Composable
internal fun ImageDetailScreen(
    item: GalleryItem,
    bitmap: ImageBitmap,
    onDismiss: () -> Unit
) {
    // 点一下图收起顶部信息，再点一下回来，和相册的习惯一致
    var uiHidden by remember { mutableStateOf(false) }
    val zoomableState = rememberZoomableState(
        zoomSpec = ZoomSpec(maxZoomFactor = 4f)
    ).also { it.contentScale = ContentScale.Fit }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zoomable(
                        state = zoomableState,
                        onClick = { uiHidden = !uiHidden },
                        onDoubleClick = DoubleClickToZoomListener.cycle(2f)
                    )
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = item.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            // 缩放状态算出来之前内容还没定位，给个转圈，避免第一帧是空白
            if (zoomableState.contentTransformation.isUnspecified) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (!uiHidden) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }
                    Column(modifier = Modifier.padding(start = 2.dp, end = 16.dp)) {
                        Text(
                            item.fileName,
                            color = Color.White,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "双指缩放 · 双击切换 1× / 2×",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
