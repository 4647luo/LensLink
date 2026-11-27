package com.lutcamera.lutcamerapro.ui.douk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.RandomAccessFile
import android.util.Size

/**
 * 图库解码统一入口。
 *
 * 以前用的是 BitmapFactory + inSampleSize，三个毛病：
 * 1) 只按 400px / 1600px 出图，2 亿像素的图放到平板上就是糊；
 * 2) BitmapFactory 不懂 HEIF / AVIF / Ultra HDR JPEG，遇到直接黑图；
 * 3) 8bit sRGB，Display P3 和 HDR gainmap 全丢掉，看着又灰又闷。
 *
 * 这里统一走 ImageDecoder（API 29+）：
 * - 按「屏幕需要的大小」采样，不无脑压到 1600；
 * - 优先硬件+广色域目标色域（P3），有 gainmap 就把高光合成进去；
 * - 遇到 ImageDecoder 解不动的（老 DNG 内嵌预览等）再退回 BitmapFactory。
 */
object ImageDecode {

    private val videoExt = setOf("mp4", "mov", "m4v", "mkv", "avi", "3gp", "webm", "insv")

    fun isVideoFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in videoExt
    }

    /** 视频封面：应用内导出的 MP4 以前没有缩略图，列表里就是一片灰格。 */
    fun decodeVideoFrame(file: File, targetSize: Int = 720): ImageBitmap? = runCatching {
        // 导出时写下的同名 .jpg 就是最省事也最清晰的一张封面，有就直接用
        val sidecar = File(file.parentFile, file.nameWithoutExtension + ".jpg")
        if (sidecar.exists() && sidecar.length() > 0) {
            decodeFile(sidecar, targetSize)?.let { return@runCatching it }
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = runCatching {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }.getOrNull() ?: 0L
            // 10bit HEVC 的 MOV 首帧常常解码失败（关键帧还没落定），
            // 按 0 / 0.5s / 整段 10% 依次试，能出来哪张用哪张。
            val candidates = buildList {
                add(0L)
                add(500_000L)
                if (duration > 0) add(duration * 1000L / 10)
            }
            var frame: android.graphics.Bitmap? = null
            for (t in candidates) {
                frame = runCatching {
                    retriever.getScaledFrameAtTime(
                        t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetSize, targetSize
                    )
                }.getOrNull()
                if (frame == null) {
                    frame = runCatching {
                        retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }.getOrNull()
                }
                if (frame != null) break
            }
            val bmpFrame = frame ?: return null
            val w = bmpFrame.width
            val h = bmpFrame.height
            val scale = targetSize.toFloat() / maxOf(w, h)
            val bmp = if (scale < 0.98f) {
                Bitmap.createScaledBitmap(bmpFrame, (w * scale).toInt(), (h * scale).toInt(), true)
            } else bmpFrame
            bmp.asImageBitmap()
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    /** 缩略图：长边最大 maxSide，视频走帧抽取。 */
    fun decodeThumb(file: File, maxSide: Int = 640): ImageBitmap? {
        if (!file.exists()) return null
        if (isVideoFile(file.name)) return decodeVideoFrame(file, maxSide)
        if (isRawFile(file.name)) {
            // 缩略图优先用 RAW 内嵌 JPEG：比每次 dcraw_process 快几十倍；
            // 少数没有内嵌预览的 DNG 再退回 LibRaw 真解码。
            extractEmbeddedPreview(file, maxSide)?.let { return it }
            return RawDecode.decode(file, maxSide, preview = true)?.asImageBitmap()
        }
        return decodeFile(file, maxSide)
    }

    private val rawExt = setOf("nef", "dng", "cr2", "cr3", "arw", "raf", "orf", "rw2", "pef", "srw", "nrw")

    fun isRawFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in rawExt

    /**
     * RAW（NEF / DNG / CR2 …）预览。
     *
     * 安卓没有原生 RAW 解码（那得上 libraw，要 native 编译），但这些 RAW 文件里
     * 都嵌了一张相机生成的全尺寸/中等尺寸 JPEG 预览。这里按 JPEG 标记扫描整份文件，
     * 挑尺寸最大的那张解出来——编辑用的就是这张，观感和机身回放一致。
     */
    fun extractEmbeddedPreview(file: File, maxSide: Int = 4096): ImageBitmap? = runCatching {
        val length = file.length()
        if (length <= 0L || length > 256L * 1024 * 1024) return null

        // 不再 file.readBytes()：50–100MB NEF 整份进 Java 堆，再 copyOfRange 一份，
        // 解码前就已经把内存吃掉。这里用 1MB 滚动窗口只记“最大 JPEG 的起止偏移”，
        // 扫完后再按偏移读那一张，堆里最多只有内嵌预览本身。
        var bestStart = -1L
        var bestEnd = -1L
        var bestLen = 150_000L
        var start = -1L
        var previous = -1
        val buffer = ByteArray(1024 * 1024)
        var offset = 0L
        RandomAccessFile(file, "r").use { raf ->
            while (true) {
                val read = raf.read(buffer)
                if (read < 0) break
                for (k in 0 until read) {
                    val b = buffer[k].toInt() and 0xFF
                    if (previous == 0xFF && b == 0xD8) {
                        start = offset + k - 1
                    }
                    if (previous == 0xFF && b == 0xD9 && start >= 0L) {
                        val end = offset + k + 1
                        val candidateLen = end - start
                        // 只接受合理大小的内嵌预览，避免把整段视频/异常数据当 JPEG。
                        if (candidateLen in (bestLen + 1)..(64L * 1024 * 1024)) {
                            bestLen = candidateLen
                            bestStart = start
                            bestEnd = end
                        }
                        start = -1L
                    }
                    previous = b
                }
                offset += read
            }
        }
        if (bestStart < 0L || bestEnd <= bestStart) return null
        val payload = ByteArray((bestEnd - bestStart).toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(bestStart)
            raf.readFully(payload)
        }
        // 先量尺寸，再按长边采样解码，避免把 6000×4000 的预览整张塞进内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(payload, 0, payload.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(payload, 0, payload.size, opts) ?: return null
        bmp.asImageBitmap()
    }.getOrNull()

    /**
     * 大图 / 原图预览：长边最大 maxSide（传屏幕长边即可）。
     *
     * RAW 的普通看图先用相机内嵌 JPEG，保证图库翻页不卡；
     * 真正需要传感器数据的是编辑器预览和导出，那两处显式走 [RawDecode]。
     * 若文件没有内嵌预览（部分 DNG），再退回 LibRaw 真解码。
     */
    fun decodeFile(file: File, maxSide: Int): ImageBitmap? = runCatching {
        if (isRawFile(file.name)) {
            extractEmbeddedPreview(file, maxSide)
                ?: RawDecode.decode(file, maxSide, preview = true)?.asImageBitmap()
        } else {
            decodeViaImageDecoder(ImageDecoder.createSource(file), maxSide)
                ?: decodeViaBitmapFactory(file, maxSide)
        }
    }.getOrNull()

    // ===== 真 HDR（PQ / HLG / Ultra HDR gainmap） =====
    private fun isHdrColorSpace(cs: ColorSpace?): Boolean {
        val n = cs?.name ?: return false
        return n.contains("2020", true) || n.contains("2084", true) ||
            n.contains("HLG", true) || n.contains("PQ", true)
    }

    /** 这张图到底有没有 HDR 信息：BT.2020 PQ/HLG 色域，或带 gainmap 的 Ultra HDR。 */
    fun probeHdr(file: File): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@runCatching false
        // 先做便宜的字节扫描：XMP 里有 hdrgm / GContainer 就一定带增益图，省掉两次解码
        if (hasHdrMarker(file)) return@runCatching true
        // 路径 1：BitmapFactory —— Android 14 的 Ultra HDR 走这里才会挂 gainmap
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = 8
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bf = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        val bfGain = if (Build.VERSION.SDK_INT >= 34) bf?.hasGainmap() == true else false
        com.lutcamera.lutcamerapro.log.DoukLog.i(
            "HdrProbe", "BF",
            "gain" to bfGain.toString(),
            "cfg" to (bf?.config?.name ?: "-"),
            "cs" to (bf?.colorSpace?.name ?: "-")
        )
        bf?.recycle()
        if (bfGain) return@runCatching true

        // 路径 2：ImageDecoder 探针
        val probe = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, _, _ ->
            d.setTargetSize(64, 64)
        }
        val gain = if (Build.VERSION.SDK_INT >= 34) {
            runCatching { probe.hasGainmap() }.getOrDefault(false)
        } else false
        val csHdr = isHdrColorSpace(probe.colorSpace)
        com.lutcamera.lutcamerapro.log.DoukLog.i(
            "HdrProbe", "IMG",
            "gain" to gain.toString(),
            "cs" to (probe.colorSpace?.name ?: "-"),
            "cfg" to (probe.config?.name ?: "-")
        )
        probe.recycle()
        gain || csHdr || hasHdrMarker(file)
    }.getOrDefault(false)

    /** Ultra HDR 的「底图 + 增益图」组合，以及 ISO 21496-1 的完整元数据。 */
    class HdrPair(
        val base: android.graphics.Bitmap,
        val gain: android.graphics.Bitmap,
        val ratioMax: Float,
        val ratioMin: Float,
        val gamma: Float,
        val epsSdr: Float,
        val epsHdr: Float,
        val displayRatioFull: Float,
        val minDisplayRatio: Float,
        /** true 表示 base/gain 已经按 EXIF Orientation 转正，复用 GainMap 时不要再转一次。 */
        val orientationApplied: Boolean = false
    )

    /** Ultra HDR JPEG 的 XMP Container 里的一项。 */
    private data class ContainerItem(val semantic: String, val length: Long)

    /** 从增益图自带 XMP 里读出来的 ISO 21496-1 参数。 */
    private data class GainMeta(
        val ratioMax: Float,
        val ratioMin: Float,
        val gamma: Float,
        val epsSdr: Float,
        val epsHdr: Float,
        val displayRatioFull: Float,
        val minDisplayRatio: Float
    )

    /**
     * 解 Ultra HDR JPEG：底图走 BitmapFactory（只有它会把 gainmap 挂到 Bitmap 上），
     * 再把 gainmap 的 ALPHA_8 内容转成 GL 能采样的灰度 ARGB_8888。
     */
    fun decodeHdrPair(file: File, maxSide: Int): HdrPair? = runCatching {
        if (Build.VERSION.SDK_INT < 34) return@runCatching null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longSide / (sample * 2) > maxSide) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val base = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: return@runCatching null
        val gm = runCatching { base.gainmap }.getOrNull()
        val contents = runCatching { gm?.gainmapContents }.getOrNull()
        if (gm == null || contents == null) {
            // 手机（尤其小米/OPPO/vivo）拍的 Ultra HDR 常常只在 JPEG 尾部塞一份
            // GainMap JPEG，Bitmap.hasGainmap() 在这类文件上返回 false。
            // 这时不能再把整张图当 SDR 丢掉，改从 XMP Container 把增益图切出来。
            val pair = decodeHdrPairFromContainer(file, base)
            if (pair == null) base.recycle()
            return@runCatching pair
        }
        if (contents.width <= 0 || contents.height <= 0) {
            base.recycle()
            return@runCatching null
        }
        val gw = contents.width
        val gh = contents.height
        val src = IntArray(gw * gh)
        contents.getPixels(src, 0, gw, 0, 0, gw, gh)
        val gray = IntArray(gw * gh)
        // 标准 GainMap 是 ALPHA_8，但部分厂商会解码成 ARGB_8888 灰度图；
        // 以前一律取 alpha，ARGB 情况下 alpha 恒为 255，整张图会被抬爆。
        val alphaOnly = contents.config == android.graphics.Bitmap.Config.ALPHA_8
        for (i in src.indices) {
            val v = if (alphaOnly) {
                (src[i] ushr 24) and 0xFF
            } else {
                val p = src[i]
                (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
            }
            gray[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val gain = android.graphics.Bitmap.createBitmap(gw, gh, android.graphics.Bitmap.Config.ARGB_8888)
        gain.setPixels(gray, 0, gw, 0, 0, gw, gh)
        // ISO 21496-1：增益图不是简单映射，必须按 min/max boost、gamma、offset 和
        // 显示器实际 HDR/SDR 比一起算；否则高光会被整体抬到 1000+ nits，看着就是过曝。
        fun first(v: FloatArray?, fallback: Float) =
            runCatching { v?.get(0) ?: fallback }.getOrDefault(fallback)
        val ratio = (runCatching { gm.ratioMax.maxOrNull() }.getOrNull() ?: 1f)
            .coerceIn(1f, 16f)
        val ratioMin = (runCatching { gm.ratioMin.minOrNull() }.getOrNull() ?: 1f)
            .coerceIn(0.01f, ratio)
        val gamma = first(runCatching { gm.gamma }.getOrNull(), 1f).coerceIn(0.05f, 8f)
        val epsSdr = first(runCatching { gm.epsilonSdr }.getOrNull(), 1f / 64f).coerceIn(0f, 1f)
        val epsHdr = first(runCatching { gm.epsilonHdr }.getOrNull(), 1f / 64f).coerceIn(0f, 1f)
        val fullRatio = runCatching { gm.displayRatioForFullHdr }.getOrDefault(ratio)
            .coerceAtLeast(ratioMin)
        val minDisplayRatio = runCatching { gm.minDisplayRatioForHdrTransition }
            .getOrDefault(1f).coerceAtLeast(0.01f)
        com.lutcamera.lutcamerapro.log.DoukLog.i(
            "HdrDecode", "PAIR",
            "base" to "${base.width}x${base.height}",
            "gain" to "${gw}x${gh}",
            "max" to ratio.toString(),
            "min" to ratioMin.toString(),
            "gamma" to gamma.toString(),
            "eps" to "$epsSdr/$epsHdr",
            "full" to fullRatio.toString(),
            "minD" to minDisplayRatio.toString()
        )
        HdrPair(base, gain, ratio, ratioMin, gamma, epsSdr, epsHdr, fullRatio, minDisplayRatio)
    }.getOrNull()

    /**
     * 不走 Bitmap.gainmap，直接从 Ultra HDR / Google Container 的 JPEG 尾部切 GainMap。
     *
     * 手机厂商的 Ultra HDR 文件结构是：
     *   主图 JPEG + 一段 GainMap JPEG（半分辨率灰度图）+（可选）MotionPhoto MP4
     * XMP 的 Container:Directory 会给出 GainMap 的 Item:Length；增益参数则写在
     * GainMap 自己的 XMP 里（hdrgm:GainMapMax / Gamma / Offset* / HDRCapacity*）。
     */
    private fun decodeHdrPairFromContainer(file: File, base: android.graphics.Bitmap): HdrPair? =
        runCatching {
            val gainBytes = extractGainmapJpeg(file) ?: return@runCatching null
            val meta = parseGainmapMeta(gainBytes) ?: return@runCatching null
            val gainRaw = android.graphics.BitmapFactory.decodeByteArray(
                gainBytes, 0, gainBytes.size,
                android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
            ) ?: return@runCatching null
            val gainArgb = if (gainRaw.config == android.graphics.Bitmap.Config.ARGB_8888) {
                gainRaw
            } else {
                gainRaw.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: gainRaw
            }
            val orientation = GalleryFileOps.exifOrientationOf(file)
            val baseOut = GalleryFileOps.applyExifOrientation(base, orientation)
            val gainOut = GalleryFileOps.applyExifOrientation(gainArgb, orientation)
            val gainFinal = if (gainOut.config == android.graphics.Bitmap.Config.ARGB_8888) {
                gainOut
            } else {
                gainOut.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    ?: return@runCatching null
            }
            com.lutcamera.lutcamerapro.log.DoukLog.i(
                "HdrDecode", "CONTAINER",
                "base" to "${baseOut.width}x${baseOut.height}",
                "gain" to "${gainFinal.width}x${gainFinal.height}",
                "max" to meta.ratioMax.toString(),
                "min" to meta.ratioMin.toString(),
                "gamma" to meta.gamma.toString(),
                "full" to meta.displayRatioFull.toString(),
                "minD" to meta.minDisplayRatio.toString()
            )
            HdrPair(
                baseOut, gainFinal,
                meta.ratioMax, meta.ratioMin, meta.gamma,
                meta.epsSdr, meta.epsHdr, meta.displayRatioFull, meta.minDisplayRatio,
                orientationApplied = true
            )
        }.getOrNull()

    private fun extractGainmapJpeg(file: File): ByteArray? = runCatching {
        val xmp = readXmpHead(file) ?: return@runCatching null
        val items = parseContainerItems(xmp)
        val gain = items.firstOrNull {
            it.semantic.equals("gainmap", true) && it.length > 0
        } ?: items.firstOrNull { it.length > 0 } ?: return@runCatching null
        // Container 里 Primary 通常不写 Length；只把 GainMap / MotionPhoto 这些尾部项的长度相加，
        // 就能拿到第一个尾部 JPEG 的起点，不会把主图里的 SOI 误当成增益图。
        val trailing = items.filter {
            !it.semantic.equals("primary", true) && it.length > 0
        }.sumOf { it.length }
        val start = file.length() - trailing
        if (start < 0) return@runCatching null
        RandomAccessFile(file, "r").use { raf ->
            var pos = start
            val probe = ByteArray(minOf(2048L, file.length() - pos).toInt())
            raf.seek(pos)
            raf.readFully(probe)
            val off = jpegSoiOffset(probe)
            if (off < 0) return@runCatching null
            pos += off
            val len = minOf(gain.length, file.length() - pos).toInt()
            if (len <= 0) return@runCatching null
            val bytes = ByteArray(len)
            raf.seek(pos)
            raf.readFully(bytes)
            bytes
        }
    }.getOrNull()

    private fun readXmpHead(file: File): String? = runCatching {
        val max = minOf(file.length(), 2L * 1024 * 1024).toInt()
        val buf = ByteArray(max)
        var n = 0
        file.inputStream().use { input ->
            while (n < max) {
                val r = input.read(buf, n, max - n)
                if (r <= 0) break
                n += r
            }
        }
        findXmp(String(buf, 0, n, Charsets.UTF_8))
    }.getOrNull()

    private fun findXmp(text: String): String? {
        val a = text.indexOf("<x:xmpmeta", ignoreCase = true)
        val b = text.indexOf("</x:xmpmeta>", a + 1, ignoreCase = true)
        if (a < 0 || b <= a) return null
        return text.substring(a, b + "</x:xmpmeta>".length)
    }

    private fun parseContainerItems(xmp: String): List<ContainerItem> {
        val out = ArrayList<ContainerItem>()
        for (tag in CONTAINER_ITEM.findAll(xmp)) {
            val semantic = xmpAttr(tag.value, "Item:Semantic") ?: ""
            val length = xmpAttr(tag.value, "Item:Length")?.toLongOrNull() ?: 0L
            if (semantic.isNotEmpty() || length > 0) out += ContainerItem(semantic, length)
        }
        return out
    }

    private fun xmpAttr(tag: String, name: String): String? =
        runCatching {
            Regex("""${Regex.escape(name)}\s*=\s*"([^"]*)"""")
                .find(tag)?.groupValues?.get(1)
        }.getOrNull()

    private fun jpegSoiOffset(data: ByteArray): Int {
        if (data.size < 3) return -1
        for (i in 0 until data.size - 2) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() &&
                data[i + 2] == 0xFF.toByte()
            ) return i
        }
        return -1
    }

    private fun parseGainmapMeta(gain: ByteArray): GainMeta? = runCatching {
        val xmp = findXmp(String(gain, Charsets.UTF_8)) ?: return@runCatching null
        fun value(name: String): Float? =
            Regex("""hdrgm:${Regex.escape(name)}\s*=\s*"([^"]*)"""")
                .find(xmp)?.groupValues?.get(1)?.toFloatOrNull()
        val maxLog = value("GainMapMax") ?: return@runCatching null
        val minLog = value("GainMapMin") ?: 0f
        val gamma = value("Gamma") ?: 1f
        val offsetSdr = value("OffsetSDR") ?: 0f
        val offsetHdr = value("OffsetHDR") ?: 0f
        val capMin = value("HDRCapacityMin") ?: minLog
        val capMax = value("HDRCapacityMax") ?: maxLog
        fun boost(v: Float) = Math.pow(2.0, v.toDouble()).toFloat()
        val ratioMax = boost(maxLog).coerceIn(1f, 16f)
        val ratioMin = boost(minLog).coerceIn(0.01f, ratioMax)
        GainMeta(
            ratioMax = ratioMax,
            ratioMin = ratioMin,
            gamma = gamma.coerceIn(0.05f, 8f),
            epsSdr = offsetSdr.coerceIn(0f, 1f),
            epsHdr = offsetHdr.coerceIn(0f, 1f),
            displayRatioFull = boost(capMax).coerceAtLeast(ratioMin),
            minDisplayRatio = boost(capMin).coerceAtLeast(0.01f)
        )
    }.getOrNull()

    /** 探针：这张图是不是 Ultra HDR（带 gainmap）。 */
    fun hasGainmap(file: File): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 34) return@runCatching false
        val opts = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = 16
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val b = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: return@runCatching false
        val g = b.hasGainmap()
        b.recycle()
        g
    }.getOrDefault(false)

    /** JPEG/HEIF 里的 HDR 标记：Ultra HDR 的 hdrgm、Google 容器的 GContainer、苹果 HDRGainMap… */
    private val CONTAINER_ITEM = Regex(
        """<Container:Item\b[^>]*>""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val HDR_MARKERS = arrayOf(
        "hdrgm",
        "gcontainer",
        "gainmap",
        "gain map",
        "hdrgainmap",
        "iso:std:iso:ts:21496",
        "21496-1",
        "urn:com:apple:photo:2020:hdrgainmap"
    )

    fun hasHdrMarker(file: File): Boolean = runCatching {
        // 以前只扫前 256KB 且只认 "hdrgm"，有些机型把 XMP 放在更后面，
        // 或者用 GContainer / 苹果 gainmap 命名，就被判成 SDR。这里放宽到 1MB + 多个标记。
        val head = ByteArray(1024 * 1024)
        var n = 0
        file.inputStream().use { input ->
            while (n < head.size) {
                val r = input.read(head, n, head.size - n)
                if (r <= 0) break
                n += r
            }
        }
        var found = false
        for (needle in HDR_MARKERS) {
            if (indexOfAsciiIgnoreCase(head, n, needle)) { found = true; break }
        }
        found
    }.getOrDefault(false)

    /** 在字节数组里找一段 ASCII，大小写不敏感（XMP 里命名空间大小写并不统一）。 */
    private fun indexOfAsciiIgnoreCase(data: ByteArray, length: Int, needle: String): Boolean {
        val n = needle.length
        if (n == 0 || length < n) return false
        val first = needle[0].lowercaseChar().code
        var i = 0
        while (i <= length - n) {
            val c = data[i].toInt().toChar().lowercaseChar()
            if (c.code == first) {
                var k = 1
                while (k < n) {
                    val a = data[i + k].toInt().toChar().lowercaseChar()
                    val b = needle[k].lowercaseChar()
                    if (a != b) break
                    k++
                }
                if (k == n) return true
            }
            i++
        }
        return false
    }

    /**
     * 手机相册里的图：content:// 先落一份到 cache，后面的 gainmap / PQ 解析全部复用文件版实现。
     * 命名按 MediaStore id，命中缓存就不再重复拷贝。
     */
    fun materialize(context: Context, uri: Uri, key: String): File? = runCatching {
        val dir = File(context.cacheDir, "hdr").apply { mkdirs() }
        val safeKey = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        val target = File(dir, "hdr-$safeKey.bin")
        if (target.length() > 0L) return@runCatching target
        val temporary = File(dir, "hdr-$safeKey.bin.part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { out ->
                    com.lutcamera.lutcamerapro.util.BoundedIo.copyWithLimit(
                        input, out, MAX_HDR_CACHE_BYTES
                    )
                }
            }
            if (temporary.length() > 0L) {
                if (target.exists()) target.delete()
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
            }
        } finally {
            temporary.delete()
        }
        trimCache(context)
        if (target.length() > 0L) target else null
    }.getOrNull()

    /** 清掉旧 HDR 缓存，并保证整个 hdr 目录不超过 512MB。 */
    fun trimCache(context: Context) = runCatching {
        val dir = File(context.cacheDir, "hdr")
        if (!dir.isDirectory) return@runCatching
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val files = dir.listFiles()?.filter { it.isFile } ?: return@runCatching
        files.filter { it.lastModified() < cutoff }.forEach { runCatching { it.delete() } }
        var remaining = files.filter { it.exists() }.sortedBy { it.lastModified() }.toMutableList()
        var total = remaining.sumOf { it.length() }
        while (total > MAX_HDR_CACHE_BYTES && remaining.isNotEmpty()) {
            // 用 removeAt(0)，别用 removeFirst()：compileSdk 35 + JDK 21 会解析到
            // java.util.List#removeFirst（SequencedCollection），API 34 及以下没有这个方法，
            // 运行时会 NoSuchMethodError，等于缓存清理直接崩掉。
            val oldest = remaining.removeAt(0)
            total -= oldest.length()
            runCatching { oldest.delete() }
        }
    }

    private const val MAX_HDR_CACHE_BYTES = 512L * 1024 * 1024

    /**
     * 按 HDR 解码：目标色域直接要 BT.2020 / PQ，解出来是 RGBA_F16。
     * 拿到的是 PQ 编码值，交给 HDR 图片视图原样写进 PQ 交换链就是真 HDR。
     */
    fun decodeHdrBitmap(file: File, maxSide: Int): android.graphics.Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@runCatching null
        val listener = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            if (w > 0 && h > 0) {
                val scale = maxSide.toFloat() / maxOf(w, h)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
            // PQ 目标色域会让系统把 Ultra HDR 的 gainmap 一起合成进来。
            // BT2020_PQ 常量本身是 API 34 才有的，Android 10–13 必须显式跳过。
            if (Build.VERSION.SDK_INT >= 34) {
                runCatching {
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.BT2020_PQ))
                }
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
        val bmp = runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), listener) }
            .getOrNull() ?: decodeHdrViaFactory(file, maxSide)
        if (bmp == null) return@runCatching null
        val isHdr = bmp.config == android.graphics.Bitmap.Config.RGBA_F16 ||
            isHdrColorSpace(bmp.colorSpace)
        if (isHdr) bmp else null
    }.getOrNull()

    /**
     * ImageDecoder 拒绝的 PQ/HLG 图（部分 HEIF、改名后的 JPEG）再用 BitmapFactory 直解：
     * 目标 RGBA_F16 + BT.2020 PQ，能出 16bit 就交给 HDR 上屏。
     */
    private fun decodeHdrViaFactory(file: File, maxSide: Int): android.graphics.Bitmap? = runCatching {

        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return@runCatching null
        var sample = 1
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / (sample * 2) > maxSide) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGBA_F16
        }
        // BT2020_PQ 的 ColorSpace 常量需要 API 34；低版本只解 RGBA_F16，不指定 PQ 目标。
        if (Build.VERSION.SDK_INT >= 34) {
            opts.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.BT2020_PQ)
        }
        val b = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@runCatching null
        if (b.config == android.graphics.Bitmap.Config.RGBA_F16 || isHdrColorSpace(b.colorSpace)) b else {
            b.recycle()
            null
        }
    }.getOrNull()

    fun decodeUri(context: Context, uri: Uri, maxSide: Int): ImageBitmap? = runCatching {
        // 手机相册里的 HDR 图不能直接把 ImageDecoder 的 HDR 位图交给 Compose：
        // Skia 不会替它做 tone map，结果就是整张灰掉。这里强制解到 SDR sRGB。
        decodeViaImageDecoder(ImageDecoder.createSource(context.contentResolver, uri), maxSide, true)
            ?: context.contentResolver.loadThumbnail(uri, Size(maxSide, maxSide), null)
                ?.let { toSdrArgb8888(it) }
                ?.asImageBitmap()
    }.getOrNull()

    private fun decodeViaImageDecoder(
        source: ImageDecoder.Source,
        maxSide: Int,
        forceSdr: Boolean = false
    ): ImageBitmap? {

        return runCatching {
            var decoded: Bitmap? = null
            val listener = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                if (w <= 0 || h <= 0) return@OnHeaderDecodedListener
                val scale = maxSide.toFloat() / maxOf(w, h)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1)
                    )
                }
                // 普通预览优先广色域；手机相册缩略图强制 sRGB，避免 HDR 位图直接进 Compose 发灰。
                runCatching {
                    decoder.setTargetColorSpace(
                        ColorSpace.get(
                            if (forceSdr) ColorSpace.Named.SRGB
                            else ColorSpace.Named.DISPLAY_P3
                        )
                    )
                }
                // 走硬件解码（ALLOCATOR_DEFAULT）：大图、HEIF、AVIF 都能原尺寸上屏，
                // 以前强制 SOFTWARE 在 4K 图上又慢又只能出 8bit ARGB，放大看就糊。
                // 硬件位图不可改，所以 isMutableRequired 关掉；gainmap 需要时再 copy 一份软件图。
                decoder.allocator = if (forceSdr) {
                    ImageDecoder.ALLOCATOR_SOFTWARE
                } else {
                    ImageDecoder.ALLOCATOR_DEFAULT
                }
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                decoder.isMutableRequired = false
            }
            decoded = ImageDecoder.decodeBitmap(source, listener)
            val bmp = decoded ?: return null
            // 即使没强制 SDR，也兜底把 F16 / PQ 位图转成普通的 8bit 图；
            // 真正的 HDR 上屏另有 decodeHdrPair / decodeHdrBitmap，不走这条普通预览。
            toSdrArgb8888(bmp).asImageBitmap()
        }.getOrNull()
    }

    /** 把 HDR / 16bit 位图压回 8bit sRGB；已经是普通 SDR 位图就原样返回，不额外拷贝。 */
    private fun toSdrArgb8888(bmp: Bitmap): Bitmap {
        if (bmp.config == Bitmap.Config.ARGB_8888 && !isHdrColorSpace(bmp.colorSpace)) return bmp
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        out.setHasAlpha(bmp.hasAlpha())
        runCatching {
            Canvas(out).drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }.onFailure {
            if (out !== bmp) out.recycle()
            return bmp
        }
        if (out !== bmp) bmp.recycle()
        return out
    }

    /**
     * HDR（Ultra HDR JPEG / HEIF gainmap）预览：把增益图按约 1.6 档合成进基图。
     * 不追求严格 HDR 显示（那要 SurfaceView + HDR 窗口），但亮部层次、
     * 高光和阴影的过渡会明显比只看基图接近相机拍出来的样子。
     *
     * Gainmap API 是 Android 14 才有的，而且不同版本签名不完全一致，
     * 这里走反射取，取不到就当普通图显示，绝不因此崩掉图库。
     */
    private fun applyGainmap(base: Bitmap): ImageBitmap {
        if (Build.VERSION.SDK_INT < 34) return base.asImageBitmap()
        return runCatching {
            val getGainmap = Bitmap::class.java.getMethod("getGainmap")
            val gain = getGainmap.invoke(base) ?: return@runCatching base.asImageBitmap()
            val gainBitmap = gain.javaClass.getMethod("getGainmap").invoke(gain) as? Bitmap
                ?: return@runCatching base.asImageBitmap()
            val out = base.copy(Bitmap.Config.ARGB_8888, true) ?: return@runCatching base.asImageBitmap()
            val canvas = Canvas(out)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            paint.alpha = 150
            val scaled = Bitmap.createScaledBitmap(gainBitmap, base.width, base.height, true)
            canvas.drawBitmap(scaled, 0f, 0f, paint)
            out.asImageBitmap()
        }.getOrDefault(base.asImageBitmap())
    }

    private fun decodeViaBitmapFactory(file: File, maxSide: Int): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        GalleryFileOps.applyExifOrientation(bmp, GalleryFileOps.exifOrientationOf(file)).asImageBitmap()
    }.getOrNull()
}
