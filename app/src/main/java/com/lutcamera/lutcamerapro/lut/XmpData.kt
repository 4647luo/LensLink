package com.lutcamera.lutcamerapro.lut

/**
 * 🚀 Adobe Lightroom / Camera Raw XMP 调色预设参数模型
 */
data class XmpData(
    val title: String,
    val exposure: Float = 0.0f,         // 曝光补偿 (-5.0 ~ +5.0 EV)
                val contrast: Float = 0.0f,         // 对比度 (-100 ~ +100) -> 标准化 (-1.0 ~ 1.0)
                val highlights: Float = 0.0f,       // 高光 (-100 ~ +100) -> 标准化 (-1.0 ~ 1.0)
                val shadows: Float = 0.0f,          // 阴影 (-100 ~ +100) -> 标准化 (-1.0 ~ 1.0)
                val whites: Float = 0.0f,           // 白场 (-100 ~ +100) -> 标准化 (-1.0 ~ 1.0)
                val blacks: Float = 0.0f,           // 黑场 (-100 ~ +100) -> 标准化 (-1.0 ~ 1.0)
                val temperature: Float = 0.0f,      // 色温微调 (-100 ~ +100) -> 标准化 (-1.0 ~ 1.0)
                val tint: Float = 0.0f,             // 色调微调 (-100 ~ +100) -> 标准化 (-1.0 ~ 1.0)
                val vibrance: Float = 0.0f,         // 自然饱和度 (-100 ~ +100) -> 标准化 (-1.0 ~ 1.0)
                val saturation: Float = 0.0f,       // 整体饱和度 (-100 ~ +100) -> 标准化 (-1.0 ~ 1.0)
                val clarity: Float = 0.0f,          // 清晰度 (-100 ~ +100) -> 标准化 (-1.0 ~ 1.0)
                val dehaze: Float = 0.0f,           // 去雾 (-100 ~ +100) -> 标准化 (-1.0 ~ 1.0)
    
    // 8 种主要色相 (红 0, 橙 1, 黄 2, 绿 3, 蓝 4, 靛 5, 紫 6, 洋红 7)
                val hueAdjustments: FloatArray = FloatArray(8),        // 色相偏移
                val saturationAdjustments: FloatArray = FloatArray(8), // 饱和度偏绉
                val luminanceAdjustments: FloatArray = FloatArray(8)   // 明度偏移
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as XmpData
        return title == other.title && exposure == other.exposure && contrast == other.contrast
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + exposure.hashCode()
        return result
    }

    companion object {
        val IDENTITY = XmpData(title = "原片默认 (No XMP)")
    }
}


