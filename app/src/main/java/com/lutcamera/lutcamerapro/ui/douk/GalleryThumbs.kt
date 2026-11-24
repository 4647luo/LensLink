package com.lutcamera.lutcamerapro.ui.douk

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import coil.size.Size
import java.io.File

/**
 * 图库缩略图的 Coil 通路。
 *
 * 只接「应用内目录里的普通位图」这一种：JPG / PNG / HEIC / WebP 都在 BitmapFactory
 * 能解的范围内，Coil 顺带把 EXIF 方向、按目标尺寸降采样、内存缓存键都做掉，
 * 滚动时把请求和 LaunchedEffect 的生命周期绑在一起，划走的格子立刻取消。
 *
 * 三种情况仍然走原来的 ImageDecode，不走 Coil：
 * - RAW/NEF：要先扫内嵌 JPEG 或调 LibRaw，Coil 没有对应 decoder；
 * - 视频：封面有同名 .jpg 的优先路径，还有 10bit HEVC 的多次抽帧兜底；
 * - 手机相册：Ultra HDR JPEG 直接交给 Compose 会发灰，必须强制解成 SDR。
 */
internal object GalleryThumbs {

    /** 这个条目能不能安全交给 Coil；不能就走 [ImageDecode]。 */
    fun canUseCoil(item: GalleryItem): Boolean =
        item.origin == "app" && !item.isRaw && !item.isVideo

    /**
     * 解一张缩略图，长边 [side]。失败返回 null，由调用方回退到 ImageDecode。
     *
     * memoryCacheKey 里带上文件长度和修改时间：连镜会原地覆盖导出同一张图，
     * 只用路径做 key 会把旧图一直画在图库上。
     */
    suspend fun loadThumb(context: Context, file: File, side: Int): ImageBitmap? =
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(file)
                .size(Size(side, side))
                .scale(Scale.FIT)
                .precision(Precision.EXACT)
                // 缩略图要长期留在 Compose 的 ImageBitmap 里，硬件位图不能这样用
                .allowHardware(false)
                .memoryCacheKey(
                    "gallery-thumb|${file.absolutePath}|${file.length()}|${file.lastModified()}|$side"
                )
                // 原图本来就在本地，不需要再往磁盘缓存抄一份
                .diskCacheKey(null)
                .build()
            val result = context.imageLoader.execute(request)
            (result.drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
                ?: result.drawable?.let { drawable ->
                    // 少数 decoder 返回非 BitmapDrawable，统一画进一张 ARGB_8888
                    val bmp = android.graphics.Bitmap.createBitmap(
                        drawable.intrinsicWidth.coerceAtLeast(1),
                        drawable.intrinsicHeight.coerceAtLeast(1),
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    android.graphics.Canvas(bmp).also { drawable.setBounds(0, 0, bmp.width, bmp.height); drawable.draw(it) }
                    bmp.asImageBitmap()
                }
        }.getOrNull()
}
