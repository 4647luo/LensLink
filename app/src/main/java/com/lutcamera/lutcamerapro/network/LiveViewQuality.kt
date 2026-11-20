package com.lutcamera.lutcamerapro.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 手机端取景画质档位。
 *
 * 尼康 PTP 的实时取景 JPEG 尺寸由机身决定，App 不能直接让相机换分辨率；
 * 这里在解码这一层做取舍，效果是真实可见的：
 *  - 流畅：半尺寸解码 + RGB_565，帧率最稳、内存最低；
 *  - 标准：全尺寸 + RGB_565，画质/性能折中；
 *  - 高清：全尺寸 + ARGB_8888，保留完整色彩与 LUT 处理精度。
 *
 * 解码器每帧重新读一次档位，所以监看页切换后下一帧就生效，不用重启取景。
 */
object LiveViewQuality {
    const val FLUENT = 0
    const val STANDARD = 1
    const val HD = 2

    @Volatile
    var level: Int = STANDARD

    fun decodeOptions(): BitmapFactory.Options = when (level) {
        FLUENT -> BitmapFactory.Options().apply {
            inSampleSize = 2
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        HD -> BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        else -> BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
    }
}
