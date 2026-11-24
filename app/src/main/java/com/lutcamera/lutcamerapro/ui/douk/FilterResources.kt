package com.lutcamera.lutcamerapro.ui.douk

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
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
    /**
     * 图片编辑器能不能用：只要不是明确标成「仅视频」，LUT 和尼康 Picture Control
     * 都可以进图片滤镜。之前按扩展名把 .cube/.3dl/.look 一律排除，导致资源页
     * 导入的 LUT 在图片编辑器里根本找不到。
     */
    val forPhoto: Boolean
        get() = use != "视频"
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

    /**
     * 内置两条算法生成的监看还原 LUT，不依赖用户先导入文件：
     * - N-Log → Rec.709：先把低对比 log 曲线做 S 形还原，再轻推饱和度。
     * - HLG → Rec.709：HLG 的高光更亮，用肩部压缩把 1.0 以上收回来。
     * 这里只做监看还原，不冒充尼康原厂 LUT 数据。
     */
    private val builtInVideoLuts = listOf(
        "builtin:nlog" to "Nikon N-Log 还原（内置）",
        "builtin:hlg" to "HLG 还原（内置）"
    )

    /**
     * 解析缓存：监视页每次进入工具面板、打开 LUT 列表、切回监看都会调用 [load]。
     * 以前每次都把导入目录下的 .cube/.3dl/.np3 全量重新读盘 + 解析，三十多个 LUT
     * 光解析就要好几秒；机身参数轮询和取景线程还会被一起拖慢。
     * 现在按「路径 + 大小 + mtime」判断文件有没有变，只重读新增/修改过的那几个，
     * 内置两条算法 LUT 也只生成一次。
     */
    private data class CachedLut(val stamp: String, val lut: Lut3D)
    private val fileCache = LinkedHashMap<String, CachedLut>()
    private var builtInCache: Pair<Map<String, Lut3D>, List<FilterEntry>>? = null
    private const val LUT_DISK_MAGIC = 0x4C555432
    private const val LUT_DISK_VERSION = 1
    private const val MAX_LUT_FILE_BYTES = 32L * 1024 * 1024
    private const val MAX_LUT_GRID = 128
    private const val MAX_LUT_VALUES = MAX_LUT_GRID * MAX_LUT_GRID * MAX_LUT_GRID * 3

    @Synchronized
    fun load(context: Context): FilterLibraryResult {
        val built = builtInCache ?: run {
            val map = HashMap<String, Lut3D>()
            val list = ArrayList<FilterEntry>()
            builtInVideoLuts.forEach { (id, label) ->
                map[id] = buildVideoRestoreLut(id.endsWith("hlg"))
                list += FilterEntry(
                    id = id,
                    label = label,
                    category = "监看还原",
                    kind = "LUT",
                    use = "视频"
                )
            }
            (map to list).also { builtInCache = it }
        }
        val luts = HashMap<String, Lut3D>(built.first)
        val entries = ArrayList<FilterEntry>(built.second)
        cleanupLutDiskCache(context)

        val files = DoukPrefs.importedResources(context)
            .filter { it.extension.lowercase() in exts && it.isFile && it.length() in 1..MAX_LUT_FILE_BYTES }
        val alivePaths = files.mapTo(HashSet()) { it.absolutePath }
        fileCache.keys.retainAll { it in alivePaths }

        files.forEach { file ->
            val ext = file.extension.lowercase()
            val id = "res:${file.name}"
            val stamp = "${file.length()}:${file.lastModified()}"
            val cached = fileCache[file.absolutePath]
            val lut = if (cached?.stamp == stamp) {
                cached.lut
            } else {
                // 大 LUT 的文本解析要几秒。进程重启后先读上次解析好的 FloatArray，
                // 读 10~25MB 二进制通常只要几十到一两百毫秒；文件改动时 stamp 不匹配会自动重解析。
                val fromDisk = readLutDiskCache(context, file)
                if (fromDisk != null) {
                    fileCache[file.absolutePath] = CachedLut(stamp, fromDisk)
                    fromDisk
                } else {
                    runCatching {
                        if (ext == "np3" || ext == "ncp" || ext == "np2") parseNikon(file)
                        else parseTextLut(file, ext)
                    }.getOrNull()?.also { parsed ->
                        fileCache[file.absolutePath] = CachedLut(stamp, parsed)
                        writeLutDiskCache(context, file, parsed)
                    } ?: run {
                        fileCache.remove(file.absolutePath)
                        null
                    }
                }
            } ?: return@forEach
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

    private fun lutDiskDir(context: Context): File =
        File(context.cacheDir, "lut-parsed-v2").apply { mkdirs() }

    private fun lutDiskFile(context: Context, source: File): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.absolutePath.toByteArray(Charsets.UTF_8))
        val key = digest.take(12).joinToString("") { "%02x".format(it) }
        return File(lutDiskDir(context), "$key.lutbin")
    }

    private fun readLutDiskCache(context: Context, source: File): Lut3D? {
        val cache = lutDiskFile(context, source)
        if (!cache.isFile) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(cache), 64 * 1024)).use { input ->
                if (input.readInt() != LUT_DISK_MAGIC || input.readInt() != LUT_DISK_VERSION) {
                    return@use null
                }
                if (input.readLong() != source.length() || input.readLong() != source.lastModified()) {
                    return@use null
                }
                val size = input.readInt()
                val length = input.readInt()
                if (size !in 2..128 || length != size * size * size * 3) return@use null
                val floats = FloatArray(length)
                val chunk = ByteArray(64 * 1024)
                var offset = 0
                while (offset < length) {
                    val n = minOf(length - offset, chunk.size / 4)
                    input.readFully(chunk, 0, n * 4)
                    ByteBuffer.wrap(chunk, 0, n * 4).order(ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer().get(floats, offset, n)
                    offset += n
                }
                cache.setLastModified(System.currentTimeMillis())
                Lut3D(size, floats)
            }
        }.getOrElse {
            cache.delete()
            null
        }
    }

    private fun writeLutDiskCache(context: Context, source: File, lut: Lut3D) {
        runCatching {
            val cache = lutDiskFile(context, source)
            val tmp = File(cache.parentFile, "${cache.name}.tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024)).use { out ->
                out.writeInt(LUT_DISK_MAGIC)
                out.writeInt(LUT_DISK_VERSION)
                out.writeLong(source.length())
                out.writeLong(source.lastModified())
                out.writeInt(lut.size)
                out.writeInt(lut.data.size)
                val chunk = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                val floats = chunk.asFloatBuffer()
                var offset = 0
                while (offset < lut.data.size) {
                    val n = minOf(lut.data.size - offset, floats.capacity())
                    floats.clear()
                    floats.put(lut.data, offset, n)
                    out.write(chunk.array(), 0, n * 4)
                    offset += n
                }
            }
            cache.delete()
            if (!tmp.renameTo(cache)) tmp.delete()
        }
    }

    /** 缓存文件超过 30 天没被命中就删掉；正在用的 LUT 读取时会刷新 mtime。 */
    private fun cleanupLutDiskCache(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            lutDiskDir(context).listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) f.delete()
            }
        }
    }

    private fun buildVideoRestoreLut(hlg: Boolean): Lut3D {
        val size = 33
        val data = FloatArray(size * size * size * 3)
        for (bi in 0 until size) {
            val b = bi.toFloat() / (size - 1)
            for (gi in 0 until size) {
                val g = gi.toFloat() / (size - 1)
                for (ri in 0 until size) {
                    val r = ri.toFloat() / (size - 1)
                    // S 形曲线：不抬高黑位，只把 log 的低对比拉到 Rec.709 的正常反差。
                    fun restore(x: Float): Float {
                        val s = x * x * (3f - 2f * x)
                        val base = (x * 0.35f + s * 0.65f).coerceIn(0f, 1f)
                        return if (hlg) {
                            // HLG 高光更亮：只压缩 0.72 以上的肩部，暗部保持原样。
                            if (base <= 0.72f) base
                            else 0.72f + 0.28f * (1f - kotlin.math.exp(-(base - 0.72f) / 0.22f))
                        } else base
                    }
                    var rr = restore(r)
                    var gg = restore(g)
                    var bb = restore(b)
                    val gray = 0.299f * rr + 0.587f * gg + 0.114f * bb
                    val sat = if (hlg) 0.92f else 1.06f
                    rr = (gray + (rr - gray) * sat).coerceIn(0f, 1f)
                    gg = (gray + (gg - gray) * sat).coerceIn(0f, 1f)
                    bb = (gray + (bb - gray) * sat).coerceIn(0f, 1f)
                    val idx = ((bi * size + gi) * size + ri) * 3
                    data[idx] = rr
                    data[idx + 1] = gg
                    data[idx + 2] = bb
                }
            }
        }
        return Lut3D(size, data)
    }

    private fun parseTextLut(file: File, ext: String): Lut3D? {
        if (file.length() <= 0L || file.length() > MAX_LUT_FILE_BYTES) return null
        // 大 LUT（65^3/128^3）文本可达十几 MB，用 BufferedReader 逐行读，避免 readText()
        // 把整份文本和每个 split 出来的子串同时留在内存里。
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            return if (ext == "3dl") parse3dl(reader) else parseCube(reader)
        }
    }

    /** .cube：LUT_3D_SIZE 后按 blue 最外层、red 最内层排列；兼容 0..255/0..1023 整数导出。 */
    private fun parseCube(reader: BufferedReader): Lut3D? {
        var size = 0
        var oneDSize = 0
        var scale = 1f
        var values = FloatArray(33 * 33 * 33 * 3)
        var valueCount = 0
        var oneD = FloatArray(4096)
        var oneDCount = 0
        val nums = FloatArray(3)
        var firstLine = true

        while (true) {
            var line = reader.readLine() ?: break
            if (firstLine) {
                line = line.removePrefix("\uFEFF")
                firstLine = false
            }
            line = line.trim()
            if (line.isEmpty()) continue
            val first = line[0]
            if (first == '#' || first == '/') continue
            if (first.isLetter()) {
                // 头信息只在字母开头的行检查，避免每个数据行都做 4 次 startsWith。
                when {
                    line.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                        val n = parseFloats(line.substring("LUT_3D_SIZE".length), nums, 1)
                        if (n >= 1) {
                            size = nums[0].toInt()
                            if (size in 2..128 && valueCount == 0) {
                                values = FloatArray(size * size * size * 3)
                            }
                        }
                    }
                    line.startsWith("LUT_1D_SIZE", ignoreCase = true) -> {
                        val n = parseFloats(line.substring("LUT_1D_SIZE".length), nums, 1)
                        if (n >= 1) {
                            oneDSize = nums[0].toInt()
                            if (oneDSize in 2..65536 && oneDCount == 0) {
                                oneD = FloatArray(oneDSize)
                            }
                        }
                    }
                    line.startsWith("DOMAIN_", ignoreCase = true) ||
                        line.startsWith("TITLE", ignoreCase = true) ||
                        line.startsWith("LUT_3D_INPUT_RANGE", ignoreCase = true) ||
                        line.startsWith("LUT_3D_OUTPUT_RANGE", ignoreCase = true) -> Unit
                }
                continue
            }
            val n = parseFloats(line, nums, 3)
            if (n < 3) continue
            // 有些 LUT 直接写 0..255 或 0..1023 的整数；先记录最大再看是否要归一化。
            scale = maxOf(scale, nums[0], nums[1], nums[2])
            if (oneDSize > 0 && size == 0) {
                if (oneDCount < oneD.size) oneD[oneDCount++] = nums[0]
            } else {
                if (valueCount + 3 > MAX_LUT_VALUES) return null
                if (valueCount + 3 > values.size) {
                    values = values.copyOf(minOf(MAX_LUT_VALUES, maxOf(valueCount + 3, values.size * 2)))
                }
                values[valueCount++] = nums[0]
                values[valueCount++] = nums[1]
                values[valueCount++] = nums[2]
            }
        }

        // 1D LUT：转成 3D 恒等网格，逐通道套曲线，也能在监视里生效。
        if (oneDSize > 1 && oneDCount >= oneDSize && size == 0) {
            val curve = FloatArray(oneDSize) { oneD[it].let { v -> if (scale > 2f) v / scale else v }.coerceIn(0f, 1f) }
            val n = 33
            val data = FloatArray(n * n * n * 3)
            for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
                val idx = ((bi * n + gi) * n + ri) * 3
                data[idx] = sampleCurve(curve, ri.toFloat() / (n - 1))
                data[idx + 1] = sampleCurve(curve, gi.toFloat() / (n - 1))
                data[idx + 2] = sampleCurve(curve, bi.toFloat() / (n - 1))
            }
            return Lut3D(n, data)
        }
        if (size < 2 || size > 128) return null
        val need = size * size * size * 3
        if (valueCount < need) return null
        val norm = if (scale > 2f) scale else 1f
        return Lut3D(size, FloatArray(need) { (values[it] / norm).coerceIn(0f, 1f) })
    }

    private fun sampleCurve(curve: FloatArray, x: Float): Float {
        val pos = x.coerceIn(0f, 1f) * (curve.size - 1)
        val a = pos.toInt().coerceIn(0, curve.size - 1)
        val b = (a + 1).coerceAtMost(curve.size - 1)
        val t = pos - a
        return curve[a] + (curve[b] - curve[a]) * t
    }

    /**
     * .3dl：常见导出为首行 3DMESH，随后每行三个整数。
     * 数据按 blue 最快排列，0..4095 或 0..1023 归一化。
     */
    private fun parse3dl(reader: BufferedReader): Lut3D? {
        var values = FloatArray(33 * 33 * 33 * 3)
        var count = 0
        var maxValue = 0f
        val nums = FloatArray(3)
        var firstLine = true
        while (true) {
            var line = reader.readLine() ?: break
            if (firstLine) {
                line = line.removePrefix("\uFEFF")
                firstLine = false
            }
            line = line.trim()
            if (line.isEmpty()) continue
            val first = line[0]
            if (first == '#' || first == '/' || first.isLetter()) continue
            val n = parseFloats(line, nums, 3)
            if (n < 3) continue
            if (count + 3 > MAX_LUT_VALUES) return null
            if (count + 3 > values.size) {
                values = values.copyOf(minOf(MAX_LUT_VALUES, maxOf(count + 3, values.size * 2)))
            }
            values[count++] = nums[0]
            values[count++] = nums[1]
            values[count++] = nums[2]
            maxValue = maxOf(maxValue, nums[0], nums[1], nums[2])
        }
        val rows = count / 3
        if (rows < 8) return null
        val n = cbrt(rows.toDouble()).roundToInt()
        if (n < 2 || n * n * n != rows) return null
        val scale = when {
            maxValue <= 255f -> 255f
            maxValue <= 1023f -> 1023f
            maxValue <= 4095f -> 4095f
            else -> maxValue
        }
        val data = FloatArray(rows * 3) { (values[it] / scale).coerceIn(0f, 1f) }
        return Lut3D(n, data)
    }

    /**
     * 不经过 split/Regex/List 的数值扫描：一行只取前 max 个 3D LUT 需要的数。
     * 65^3 的 .cube 有 27 万行数据，原来的 line.split(Regex(...)) 会为每行编译正则、
     * 产生多个子串和 List，主线程外的解析也要 5 秒；这里直接扫字符，通常能快数倍。
     */
    private fun parseFloats(line: String, out: FloatArray, max: Int): Int {
        var i = 0
        var n = 0
        val len = line.length
        while (i < len && n < max) {
            val c = line[i]
            if (c == ',' || c == ' ' || c == '\t' || c == '\r') {
                i++
                continue
            }
            if (c == '#') break
            val start = i
            if (c == '+' || c == '-') i++
            var hasDigit = false
            while (i < len && line[i].isDigit()) {
                i++
                hasDigit = true
            }
            if (i < len && line[i] == '.') {
                i++
                while (i < len && line[i].isDigit()) {
                    i++
                    hasDigit = true
                }
            }
            if (!hasDigit) {
                i++
                continue
            }
            if (i < len && (line[i] == 'e' || line[i] == 'E')) {
                val expStart = i
                i++
                if (i < len && (line[i] == '+' || line[i] == '-')) i++
                var expDigit = false
                while (i < len && line[i].isDigit()) {
                    i++
                    expDigit = true
                }
                if (!expDigit) i = expStart
            }
            val v = line.substring(start, i).toFloatOrNull() ?: continue
            out[n++] = v
        }
        return n
    }

    /**
     * 尼康 Picture Control（NP3/NCP/NP2）。字段来自公开的
     * nikon-flexible-color-picture-control 解析约定：
     * magic NCP，名称 24..43，对比度 272、高光 282、阴影 292、白 302、
     * 黑 312、饱和度 322，均为 byte-128；曲线 257 个 BE u16 从 460 开始。
     * 这里把字段还原成一条等效 3D LUT，不伪造尼康原厂数据。
     */
    private fun parseNikon(file: File): Lut3D? {
        if (file.length() !in 340..MAX_LUT_FILE_BYTES) return null
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
