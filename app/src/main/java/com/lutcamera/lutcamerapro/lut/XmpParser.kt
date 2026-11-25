package com.lutcamera.lutcamerapro.lut

import com.lutcamera.lutcamerapro.log.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.regex.Pattern

/**
 * 🚀 Adobe Lightroom / Camera Raw XMP 预设极速解析器
 * 兼容属性格式 (Attribute syntax) 与子节点格式 (Element syntax)
 */
object XmpParser {
    private const val TAG = "XmpParser"

    fun parse(inputStream: InputStream, defaultTitle: String = "Custom Preset"): XmpData? {
        try {
            val content = inputStream.bufferedReader().use { it.readText() }
            if (!content.contains("xmpmeta") && !content.contains("camera-raw-settings") && !content.contains("rdf:RDF")) {
                Log.w(TAG, "非标准 XMP 预设文件内容")
            }

            var title = defaultTitle

            // 提取预设名称
                val titleMatcher = Pattern.compile("crs:PresetName=\"([^\"]+)\"").matcher(content)
            if (titleMatcher.find()) {
                title = titleMatcher.group(1) ?: defaultTitle
            }

            // 读取各项数值 (支持 crs:Param="value" 与 <crs:Param>value</crs:Param>)
                fun findFloat(name: String, def: Float = 0f): Float {
                val attrPattern = Pattern.compile("crs:$name=\"([^\"]+)\"")
                val attrMatcher = attrPattern.matcher(content)
                if (attrMatcher.find()) {
                    return attrMatcher.group(1)?.toFloatOrNull() ?: def
                }

                val tagPattern = Pattern.compile("<crs:$name>([^<]+)</crs:$name>")
                val tagMatcher = tagPattern.matcher(content)
                if (tagMatcher.find()) {
                    return tagMatcher.group(1)?.toFloatOrNull() ?: def
                }

                return def
            }

            val exposure = findFloat("Exposure2012", findFloat("Exposure", 0.0f))
            val contrast = (findFloat("Contrast2012", findFloat("Contrast", 0.0f))) / 100.0f
            val highlights = (findFloat("Highlights2012", findFloat("Highlights", 0.0f))) / 100.0f
            val shadows = (findFloat("Shadows2012", findFloat("Shadows", 0.0f))) / 100.0f
            val whites = (findFloat("Whites2012", findFloat("Whites", 0.0f))) / 100.0f
            val blacks = (findFloat("Blacks2012", findFloat("Blacks", 0.0f))) / 100.0f

            // 色温与色璋
                val rawTemp = findFloat("IncrementalTemperature", 0.0f)
            val temperature = if (rawTemp != 0.0f) rawTemp / 100.0f else (findFloat("Temperature", 5000f) - 5000f) / 5000f
            val tint = (findFloat("IncrementalTint", findFloat("Tint", 0.0f))) / 100.0f

            val vibrance = (findFloat("Vibrance", 0.0f)) / 100.0f
            val saturation = (findFloat("Saturation", 0.0f)) / 100.0f
            val clarity = (findFloat("Clarity2012", findFloat("Clarity", 0.0f))) / 100.0f
            val dehaze = (findFloat("Dehaze", 0.0f)) / 100.0f

            // 8 通道微调
                val colorNames = arrayOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")
            val hueAdj = FloatArray(8)
            val satAdj = FloatArray(8)
            val lumAdj = FloatArray(8)

            for (i in 0 until 8) {
                val color = colorNames[i]
                hueAdj[i] = findFloat("HueAdjustment$color", 0.0f) / 100.0f
                satAdj[i] = findFloat("SaturationAdjustment$color", 0.0f) / 100.0f
                lumAdj[i] = findFloat("LuminanceAdjustment$color", 0.0f) / 100.0f
            }

            Log.d(TAG, "✅ 成功解析 XMP 调色预设: $title (曝光: $exposure, 对比度: $contrast, 色温: $temperature)")

            return XmpData(
                title = title,
                exposure = exposure,
                contrast = contrast,
                highlights = highlights,
                shadows = shadows,
                whites = whites,
                blacks = blacks,
                temperature = temperature,
                tint = tint,
                vibrance = vibrance,
                saturation = saturation,
                clarity = clarity,
                dehaze = dehaze,
                hueAdjustments = hueAdj,
                saturationAdjustments = satAdj,
                luminanceAdjustments = lumAdj
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析 XMP 预设异常", e)
            return null
        }
    }
}


