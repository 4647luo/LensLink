package com.lutcamera.lutcamerapro.ui.douk

import android.content.Context
import java.io.File
import kotlin.math.cbrt
import kotlin.math.roundToInt

/**
 * 滤镜 / LUT 列表项：
 * - kind = LUT（.cube/.3dl/.look，通常做视频监看与还原）或 滤镜（尼康 Picture Control，做图片风格）
 * - use  = 通用 / 图片 / 视频，决定它出现在图片编辑器还是监看 LUT 列表
 */
internal data class FilterEntry(
    val id: String,
    val label: String,
    val category: String,
    val kind: String = "LUT",
    val use: String = "通用"
) {
    /** 图片编辑器（含颜色调整）能不能用 */
    val forPhoto: Boolean get() = use != "视频"
    /** 监看 / 视频还原能不能用 */
    val forVideo: Boolean get() = use != "图片"
}

/** 资源页与分类页共用的两组标签。 */
internal val RESOURCE_KINDS = listOf("LUT", "滤镜")
internal val RESOURCE_USES = listOf("通用", "图片", "视频")

/** 颜色 LUT，按 0..1 的三线性插值取色，供 .cube / .3dl / 尼康预设共用。 */
internal class Lut3D(val size: Int, val data: FloatArray) {
    /** 预压缩到 64×64×64 的预览查找表，避免监视时每帧对上百万像素做浮点三线性插值。 */
    private var previewTable: IntArray? = null

    fun previewRgb(): IntArray {
        previewTable?.let { return it }
        val levels = 64
        val table = IntArray(levels * levels * levels)
        val out = FloatArray(3)
        var i = 0
        for (r in 0 until levels) {
            for (g in 0 until levels) {
                for (b in 0 until levels) {
                    map(r / (levels - 1f), g / (levels - 1f), b / (levels - 1f), out)
                    val cr = (out[0].coerceIn(0f, 1f) * 255).toInt()
                    val cg = (out[1].coerceIn(0f, 1f) * 255).toInt()
                    val cb = (out[2].coerceIn(0f, 1f) * 255).toInt()
                    table[i++] = (cr shl 16) or (cg shl 8) or cb
                }
            }
        }
        previewTable = table
        return table
    }

    fun map(r: Float, g: Float, b: Float, out: FloatArray) {
        val n = size - 1
        val fr = r.coerceIn(0f, 1f) * n
        val fg = g.coerceIn(0f, 1f) * n
        val fb = b.coerceIn(0f, 1f) * n
        val r0 = fr.toInt().coerceAtMost(n - 1)
        val g0 = fg.toInt().coerceAtMost(n - 1)
        val b0 = fb.toInt().coerceAtMost(n - 1)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0
        for (c in 0..2) {
            val c000 = data[((b0 * size + g0) * size + r0) * 3 + c]
            val c100 = data[((b0 * size + g0) * size + r0 + 1) * 3 + c]
            val c010 = data[((b0 * size + g0 + 1) * size + r0) * 3 + c]
            val c110 = data[((b0 * size + g0 + 1) * size + r0 + 1) * 3 + c]
            val c001 = data[(((b0 + 1) * size + g0) * size + r0) * 3 + c]
            val c101 = data[(((b0 + 1) * size + g0) * size + r0 + 1) * 3 + c]
            val c011 = data[(((b0 + 1) * size + g0 + 1) * size + r0) * 3 + c]
            val c111 = data[(((b0 + 1) * size + g0 + 1) * size + r0 + 1) * 3 + c]
            val x00 = c000 + (c100 - c000) * dr
            val x10 = c010 + (c110 - c010) * dr
            val x01 = c001 + (c101 - c001) * dr
            val x11 = c011 + (c111 - c011) * dr
            val y0 = x00 + (x10 - x00) * dg
            val y1 = x01 + (x11 - x01) * dg
            out[c] = y0 + (y1 - y0) * db
        }
    }
}

/** 编辑器持有的一份已解析滤镜池。 */
internal class FilterRuntime(
    val luts: Map<String, Lut3D> = emptyMap()
) {
    fun lut(id: String): Lut3D? = luts[id]
}

internal class FilterLibraryResult(
    val entries: List<FilterEntry>,
    val runtime: FilterRuntime
)

/**
 * 读取资源目录里的滤镜：
 * - .cube / .3dl / .look 文本 LUT
 * - 尼康 .NP3 / .NCP / .NP2 Picture Control，按公开字段生成等价 LUT
 * 解析失败的文件直接跳过，不会在列表里出现假按钮。
 */
internal object FilterLibrary {
    private val exts = setOf("cube", "3dl", "look", "np3", "ncp", "np2")

    fun load(context: Context): FilterLibraryResult {
        val luts = HashMap<String, Lut3D>()
        val entries = ArrayList<FilterEntry>()
        DoukPrefs.importedResources(context).forEach { file ->
            val ext = file.extension.lowercase()
            if (ext !in exts) return@forEach
            val id = "res:${file.name}"
            val lut = runCatching {
                if (ext == "np3" || ext == "ncp" || ext == "np2") parseNikon(file)
                else parseTextLut(file, ext)
            }.getOrNull() ?: return@forEach
            luts[id] = lut
            entries += FilterEntry(
                id = id,
                label = file.nameWithoutExtension,
                category = DoukPrefs.filterCategory(file.name),
                kind = DoukPrefs.resourceKind(file.name),
                use = DoukPrefs.resourceUse(file.name)
            )
        }
        return FilterLibraryResult(entries, FilterRuntime(luts))
    }

    private fun parseTextLut(file: File, ext: String): Lut3D? {
        val text = file.readText()
        if (ext == "3dl") return parse3dl(text)
        return parseCube(text)
    }

    /** .cube：LUT_3D_SIZE 后按 blue 最外层、red 最内层排列。 */
    private fun parseCube(text: String): Lut3D? {
        var size = 0
        val values = ArrayList<Float>(64 * 64 * 64 * 3)
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            if (line.startsWith("LUT_3D_SIZE")) {
                val numText = line.substringAfter("LUT_3D_SIZE").trim()
                    .removePrefix("=").trim()
                    .substringBefore(" ").substringBefore(",")
                size = numText.toIntOrNull() ?: 0
                return@forEach
            }
            if (line.startsWith("DOMAIN_") || line.startsWith("TITLE") ||
                line.startsWith("LUT_1D") || line.startsWith("LUT_3D_INPUT_RANGE") ||
                line.startsWith("LUT_3D_OUTPUT_RANGE")
            ) return@forEach
            val parts = line.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
            if (parts.size >= 3) {
                val r = parts[0].toFloatOrNull() ?: return@forEach
                val g = parts[1].toFloatOrNull() ?: return@forEach
                val b = parts[2].toFloatOrNull() ?: return@forEach
                values += r
                values += g
                values += b
            }
        }
        if (size < 2 || size > 128) return null
        val need = size * size * size * 3
        // 有些导出工具会在文件尾部多写注释/空行或重复数据；这里只取前 need 个，少一个数才算坏
        if (values.size < need) return null
        return Lut3D(size, FloatArray(need) { values[it] })
    }

    /**
     * .3dl：常见导出为首行 3DMESH，随后每行三个整数。
     * 数据按 blue 最快排列，0..4095 或 0..1023 归一化。
     */
    private fun parse3dl(text: String): Lut3D? {
        val rows = ArrayList<IntArray>()
        var maxValue = 0
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("3DMESH") ||
                line.startsWith("Mesh")
            ) return@forEach
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 3) {
                val r = parts[0].toIntOrNull() ?: return@forEach
                val g = parts[1].toIntOrNull() ?: return@forEach
                val b = parts[2].toIntOrNull() ?: return@forEach
                rows += intArrayOf(r, g, b)
                maxValue = maxOf(maxValue, r, g, b)
            }
        }
        if (rows.size < 8) return null
        val n = cbrt(rows.size.toDouble()).roundToInt()
        if (n < 2 || n * n * n != rows.size) return null
        val scale = when {
            maxValue <= 255 -> 255f
            maxValue <= 1023 -> 1023f
            maxValue <= 4095 -> 4095f
            else -> maxValue.toFloat()
        }
        val data = FloatArray(rows.size * 3)
        rows.forEachIndexed { i, c ->
            data[i * 3] = c[0] / scale
            data[i * 3 + 1] = c[1] / scale
            data[i * 3 + 2] = c[2] / scale
        }
        return Lut3D(n, data)
    }

    /**
     * 尼康 Picture Control（NP3/NCP/NP2）。字段来自公开的
     * nikon-flexible-color-picture-control 解析约定：
     * magic NCP，名称 24..43，对比度 272、高光 282、阴影 292、白 302、
     * 黑 312、饱和度 322，均为 byte-128；曲线 257 个 BE u16 从 460 开始。
     * 这里把字段还原成一条等效 3D LUT，不伪造尼康原厂数据。
     */
    private fun parseNikon(file: File): Lut3D? {
        val buf = file.readBytes()
        if (buf.size < 340) return null
        if (buf[0] != 'N'.code.toByte() || buf[1] != 'C'.code.toByte() || buf[2] != 'P'.code.toByte()) {
            return null
        }
        val contrast = (buf[272].toInt() and 0xFF) - 128
        val highlights = (buf[282].toInt() and 0xFF) - 128
        val shadows = (buf[292].toInt() and 0xFF) - 128
        val white = (buf[302].toInt() and 0xFF) - 128
        val black = (buf[312].toInt() and 0xFF) - 128
        val saturation = (buf[322].toInt() and 0xFF) - 128

        // 旧版 NCP 的曲线块偏移不固定，先只用已确认的对比度/饱和度/明暗字段，
        // 避免把错位的字节当成曲线导致整张图发黑。
        val tone: IntArray? = null
        return buildNikonLut(contrast, saturation, highlights, shadows, white, black, tone)
    }

    private fun buildNikonLut(
        contrast: Int,
        saturation: Int,
        highlights: Int,
        shadows: Int,
        white: Int,
        black: Int,
        tone: IntArray?
    ): Lut3D {
        val size = 33
        val data = FloatArray(size * size * size * 3)
        val toneLut = FloatArray(256) { i ->
            if (tone == null) i / 255f else {
                val pos = i / 255f * 256f
                val a = pos.toInt().coerceIn(0, 256)
                val b = (a + 1).coerceAtMost(256)
                val t = pos - a
                val va = tone[a.coerceAtMost(256)] / 65535f
                val vb = tone[b.coerceAtMost(256)] / 65535f
                (va + (vb - va) * t).coerceIn(0f, 1f)
            }
        }
        val cK = 1f + contrast / 100f * 0.7f
        val sK = (1f + saturation / 100f * 0.8f).coerceAtLeast(0f)
        val blackLift = black / 100f
        val whitePull = white / 100f
        for (bi in 0 until size) {
            val b = bi.toFloat() / (size - 1)
            for (gi in 0 until size) {
                val g = gi.toFloat() / (size - 1)
                for (ri in 0 until size) {
                    val r = ri.toFloat() / (size - 1)
                    var rr = toneLut[(r * 255f).toInt().coerceIn(0, 255)]
                    var gg = toneLut[(g * 255f).toInt().coerceIn(0, 255)]
                    var bb = toneLut[(b * 255f).toInt().coerceIn(0, 255)]
                    rr = (rr - 0.5f) * cK + 0.5f
                    gg = (gg - 0.5f) * cK + 0.5f
                    bb = (bb - 0.5f) * cK + 0.5f
                    val lum = 0.299f * rr + 0.587f * gg + 0.114f * bb
                    if (highlights != 0) {
                        val w = ((lum - 0.45f) / 0.55f).coerceIn(0f, 1f)
                        val d = highlights / 100f * w * 0.35f
                        rr += d; gg += d; bb += d
                    }
                    if (shadows != 0) {
                        val w = ((0.55f - lum) / 0.55f).coerceIn(0f, 1f)
                        val d = -shadows / 100f * w * 0.35f
                        rr += d; gg += d; bb += d
                    }
                    if (whitePull != 0f) {
                        val w = lum.coerceIn(0f, 1f)
                        rr += whitePull * w * 0.18f
                        gg += whitePull * w * 0.18f
                        bb += whitePull * w * 0.18f
                    }
                    if (blackLift != 0f) {
                        val w = 1f - lum
                        rr += blackLift * w * 0.18f
                        gg += blackLift * w * 0.18f
                        bb += blackLift * w * 0.18f
                    }
                    val gray = 0.299f * rr + 0.587f * gg + 0.114f * bb
                    rr = gray + (rr - gray) * sK
                    gg = gray + (gg - gray) * sK
                    bb = gray + (bb - gray) * sK
                    val idx = ((bi * size + gi) * size + ri) * 3
                    data[idx] = rr.coerceIn(0f, 1f)
                    data[idx + 1] = gg.coerceIn(0f, 1f)
                    data[idx + 2] = bb.coerceIn(0f, 1f)
                }
            }
        }
        return Lut3D(size, data)
    }
}

/**
 * 内置尼康 N-Log 转 Rec709 LUT：随 APK 打包在 assets/luts，
 * 首启（或升级后第一次启动）复制到 filesDir/lut，资源页/监视 LUT 列表立刻能用。
 * 已经存在的同名文件不覆盖，用户自己换过的版本不会被抹掉。
 */
internal object BundledLuts {
    private const val ASSET_DIR = "luts"
    private const val CATEGORY = "尼康 N-Log"

    fun installIfNeeded(context: Context) {
        val dir = DoukPrefs.lutDir(context)
        val names = runCatching { context.assets.list(ASSET_DIR) }
            .getOrNull()
            ?.filter { it.endsWith(".cube", ignoreCase = true) }
            ?: return
        names.forEach { name ->
            val target = File(dir, name)
            if (target.exists() && target.length() > 1024) {
                if (DoukPrefs.filterCategory(name) == "未分类") DoukPrefs.setFilterCategory(name, CATEGORY)
                DoukPrefs.setResourceKind(name, "LUT")
                DoukPrefs.setResourceUse(name, "视频")
                return@forEach
            }
            runCatching {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                DoukPrefs.setFilterCategory(name, CATEGORY)
                DoukPrefs.setResourceKind(name, "LUT")
                DoukPrefs.setResourceUse(name, "视频")
                com.lutcamera.lutcamerapro.log.DoukLog.i(
                    "Lut", "BUNDLED_INSTALL", "file" to name, "bytes" to target.length()
                )
            }.onFailure {
                com.lutcamera.lutcamerapro.log.DoukLog.w(
                    "Lut", "BUNDLED_FAIL", "file" to name, "err" to it.message
                )
                target.delete()
            }
        }
    }
}
