package com.lutcamera.lutcamerapro.ui.douk

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.lutcamera.lutcamerapro.log.DoukLog
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 图库的文件层：定位、复制、重命名、读元数据。
 *
 * 写入一律走 MediaStore + RELATIVE_PATH（Android 10 起公共目录直接 File 写会被分区存储拦住，
 * 而走 MediaStore 不需要任何存储权限，和 saveUsbPhoto 的做法一致）。
 */
object GalleryFileOps {

    data class Album(val name: String, val count: Int)

    /** 详情页要显示的东西，能读不到的都留 null，不编造。 */
    data class Meta(
        val fileName: String,
        val sizeBytes: Long,
        val path: String?,
        val width: Int?,
        val height: Int?,
        val takenAt: Long?,
        val camera: String?,
        val lens: String?,
        val exposureLine: String?,
        val title: String?,
        val keywords: String?,
        val artist: String?,
        val copyright: String?,
        /** 拍摄地点（EXIF GPS），没有就留 null，详情页据此决定要不要给「地图查看」 */
        val lat: Double? = null,
        val lon: Double? = null,
        /** 详情页「EXIF」一段：相机/镜头/曝光/白平衡等键值对，按读取顺序展示 */
        val exifRows: List<Pair<String, String>> = emptyList()
    )

    private const val APP_ROOT = "连镜"
    private const val PHONE_ALBUM_ROOT = "连镜"
    private const val LEGACY_APP_ROOT = "Douk"
    private const val LEGACY_PHONE_ALBUM_ROOT = "LensLink"

    /**
     * 应用自己的目录：新数据统一写入 Pictures/连镜 与 DCIM/连镜。
     * 旧的 Pictures/Douk、DCIM/Douk、DCIM/LensLink 以及 LutCameraPro 目录继续参与扫描，
     * 保证升级后老照片不会从应用图库里消失；只是不再往旧目录写新文件。
     */
    fun appRoots(): List<File> = listOf(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), APP_ROOT),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), APP_ROOT),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), LEGACY_APP_ROOT),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), LEGACY_APP_ROOT),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), LEGACY_PHONE_ALBUM_ROOT),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LutCameraPro"),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "LutCameraPro")
    )

    /** 文件名 → 文件句柄的短缓存：Live 图角标、缩略图都要反查，别每次都遍历目录 */
    private val locateCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, File?>>()

    fun locateAppFile(fileName: String): File? {
        val now = System.currentTimeMillis()
        locateCache[fileName]?.let { (stamp, file) ->
            if (now - stamp < 30 * 1000L) return file
        }
        val found = appRoots().filter { it.exists() }.flatMap { root ->
            root.walkTopDown().maxDepth(3).filter { it.isFile && it.name == fileName }.toList()
        }.firstOrNull()
        locateCache[fileName] = now to found
        return found
    }

    fun forgetLocate(fileName: String) {
        locateCache.remove(fileName)
    }

    /**
     * 判重名：去掉扩展名、去掉「 (1)」「 (2)」这类系统重名后缀，统一小写。
     * 同名即视为同一张照片——「仅缩略图」模式存下来的副本尺寸和机身原图不一致，
     * 只比大小会漏判，进而多下一份 (1).JPG，这里只认名字。
     */
    fun diskBaseName(fileName: String): String =
        fileName.substringBeforeLast('.', fileName).lowercase()
            .replace(Regex(""" \(\d+\)$"""), "")

    /**
     * 扫一遍应用内所有落盘文件（含 0.0.139 之前的 LutCameraPro 旧目录），做成文件名索引。
     * 保护文件导入前先比对这份索引，就能跳过「以前下过但没进过去重记录」的照片，
     * 不会再生成 xxx (1).JPG 这种重复副本。
     */
    fun appFileNameIndex(): Set<String> {
        val names = HashSet<String>()
        appRoots().filter { it.exists() }.forEach { root ->
            runCatching {
                root.walkTopDown().maxDepth(3).filter { it.isFile }.forEach { file ->
                    names += diskBaseName(file.name)
                }
            }
        }
        return names
    }

    /**
     * 删除应用内文件：直接 File.delete 与 MediaStore 记录删除双保险。
     * Android 10+ 公共目录只靠 File.delete 经常返回失败，必须同时清媒体库条目。
     */
    fun deleteAppFiles(context: Context, names: List<String>): Boolean {
        var removed = false
        names.distinct().forEach { name ->
            val file = locateAppFile(name)
            if (file != null) {
                removed = runCatching { file.delete() }.getOrDefault(false) || removed
            }
            val collections = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            )
            collections.forEach { collection ->
                val deleted = runCatching {
                    context.contentResolver.delete(
                        collection,
                        "${MediaStore.Images.Media.DISPLAY_NAME}=?",
                        arrayOf(name)
                    )
                }.getOrDefault(0)
                removed = deleted > 0 || removed
            }
        }
        return removed
    }

    /** 应用内相册：直接子目录算一个相册，根目录下的散图归到「应用相册」。 */
    fun appAlbums(): List<Album> {
        val out = mutableListOf<Album>()
        appRoots().filter { it.exists() }.forEach { root ->
            val loose = root.listFiles()?.count { it.isFile } ?: 0
            if (loose > 0) out += Album(root.name, loose)
            root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val count = dir.walkTopDown().maxDepth(2).count { it.isFile }
                if (count > 0) out += Album(dir.name, count)
            }
        }
        return out.distinctBy { it.name }.sortedByDescending { it.count }
    }

    /** 手机相册：按 MediaStore 的 BUCKET_DISPLAY_NAME 聚合。 */
    fun phoneAlbums(context: Context): List<Album> {
        val out = mutableListOf<Album>()
        runCatching {
            val projection = arrayOf(
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media._ID
            )
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val counts = HashMap<String, Int>()
                while (cursor.moveToNext()) {
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                    val key = name ?: "其他"
                    counts[key] = (counts[key] ?: 0) + 1
                }
                counts.forEach { (k, v) -> out += Album(k, v) }
            }
        }
        return out.sortedByDescending { it.count }
    }

    /** 应用内照片的绝对路径（详情页「文件路径」用）。 */
    fun appPathOf(fileName: String): String? = locateAppFile(fileName)?.absolutePath

    fun metaOfFile(file: File, fileName: String = file.name): Meta {
        val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull()
        // EXIF 的 DATETIME_ORIGINAL 是 "yyyy:MM:dd HH:mm:ss" 文本，不能用 getAttributeLong 直接取
        val taken = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { parseExifDate(it) }
        val width = exif?.let { it.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0) }?.takeIf { it > 0 }
        val height = exif?.let { it.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0) }?.takeIf { it > 0 }
        // 摘要行与 EXIF 段用同一套格式化：快门写 1/50s、焦距写 18mm，别把 "0.02"、"180/10" 直接抛给用户
        val exposure = exif?.let { e ->
            buildList {
                rationalText(e.getAttribute(ExifInterface.TAG_F_NUMBER))?.let { add("f/${trimNum(it)}") }
                rationalText(e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))?.let { t ->
                    add(if (t > 0.0 && t < 0.5) "1/${Math.round(1.0 / t)}s" else "${trimNum(t)}s")
                }
                e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { add("ISO $it") }
                rationalText(e.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))?.let { add("${trimNum(it)}mm") }
            }.joinToString(" · ").takeIf { it.isNotBlank() }
        }
        // exifinterface 没有暴露 Windows 的 XPTitle，标题与关键词走描述与注释两栏
        val title = exif?.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.trim()?.takeIf { it.isNotBlank() }
        val keywords = exif?.getAttribute(ExifInterface.TAG_USER_COMMENT)?.trim()?.takeIf { it.isNotBlank() }
        val artist = exif?.getAttribute(ExifInterface.TAG_ARTIST)?.trim()?.takeIf { it.isNotBlank() }
        val copyright = exif?.getAttribute(ExifInterface.TAG_COPYRIGHT)?.trim()?.takeIf { it.isNotBlank() }
        // GPS：ExifInterface 读第三方脚本写的 EXIF 时会把 GPSLatitude 解成 "0/1,0/1,0/1"、
        // ref 为空（实测日志见 DoukLog 的 GPS_FALLBACK），所以先自己走一遍 TIFF，读不到再问库。
        var latD: Double? = null
        var lonD: Double? = null
        val byTiff = readGpsFromJpeg(file)
        if (byTiff != null) {
            latD = byTiff.first
            lonD = byTiff.second
        } else if (exif != null) {
            val gps = FloatArray(2)
            val got = runCatching { exif.getLatLong(gps) }.getOrDefault(false)
            if (got && (gps[0] != 0f || gps[1] != 0f)) {
                latD = gps[0].toDouble()
                lonD = gps[1].toDouble()
            }
        }
        return Meta(
            fileName = fileName,
            sizeBytes = file.length(),
            path = file.absolutePath,
            width = width,
            height = height,
            takenAt = taken,
            camera = exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotBlank() },
            lens = exif?.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotBlank() },
            exposureLine = exposure,
            title = title,
            keywords = keywords,
            artist = artist,
            copyright = copyright,
            lat = latD,
            lon = lonD,
            exifRows = exifRowsOf(exif)
        )
    }

    /** EXIF 里能读到的相机参数，按拍摄者看得懂的顺序排；读不到的不占行。 */
    private fun exifRowsOf(exif: ExifInterface?): List<Pair<String, String>> {
        if (exif == null) return emptyList()
        fun tag(tagName: String) = exif.getAttribute(tagName)?.trim()?.takeIf { it.isNotBlank() }
        val rows = mutableListOf<Pair<String, String>>()
        fun add(key: String, value: String?) { if (!value.isNullOrBlank()) rows += key to value }

        add("相机", listOfNotNull(tag(ExifInterface.TAG_MAKE), tag(ExifInterface.TAG_MODEL))
            .joinToString(" ").takeIf { it.isNotBlank() })
        add("镜头", tag(ExifInterface.TAG_LENS_MODEL))
        add("曝光程序", exifEnum(tag(ExifInterface.TAG_EXPOSURE_PROGRAM),
            mapOf("0" to "未定义", "1" to "手动", "2" to "程序自动", "3" to "光圈优先",
                "4" to "快门优先", "5" to "创意程序", "6" to "人像模式", "7" to "风景模式", "8" to "运动模式")))
        add("快门", rationalText(tag(ExifInterface.TAG_EXPOSURE_TIME))?.let { t ->
            if (t > 0.0 && t < 0.5) "1/${Math.round(1.0 / t)}s" else "${trimNum(t)}s"
        })
        add("光圈", rationalText(tag(ExifInterface.TAG_F_NUMBER))?.let { "f/${trimNum(it)}" })
        add("ISO", tag(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" })
        val focal = rationalText(tag(ExifInterface.TAG_FOCAL_LENGTH))
        val focal35 = rationalText(tag(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM))
        add("焦距", listOfNotNull(
            focal?.let { "${trimNum(it)}mm" },
            focal35?.takeIf { it > 0.0 }?.let { "（等效 ${trimNum(it)}mm）" }
        ).joinToString(" ").takeIf { it.isNotBlank() })
        add("曝光补偿", rationalText(tag(ExifInterface.TAG_EXPOSURE_BIAS_VALUE))?.let { ev ->
            val sign = if (ev > 0.0) "+" else ""
            "$sign${trimNum(ev)} EV"
        })
        add("测光", exifEnum(tag(ExifInterface.TAG_METERING_MODE),
            mapOf("0" to "未知", "1" to "平均", "2" to "中央重点", "3" to "点测光",
                "4" to "多点", "5" to "评价", "6" to "局部", "255" to "其它")))
        add("白平衡", exifEnum(tag(ExifInterface.TAG_WHITE_BALANCE),
            mapOf("0" to "自动", "1" to "手动")))
        add("闪光灯", exifEnum(tag(ExifInterface.TAG_FLASH),
            mapOf("0" to "未闪光", "1" to "已闪光", "5" to "已闪光（未检测到反光）",
                "7" to "已闪光（检测到反光）", "9" to "强制闪光", "16" to "关闭",
                "24" to "自动", "25" to "自动闪光", "32" to "无闪光功能")))
        add("色彩空间", exifEnum(tag(ExifInterface.TAG_COLOR_SPACE),
            mapOf("1" to "sRGB", "65535" to "Adobe RGB")))
        add("拍摄时间", tag(ExifInterface.TAG_DATETIME_ORIGINAL))
        add("数字化时间", tag(ExifInterface.TAG_DATETIME_DIGITIZED))
        add("处理软件", tag(ExifInterface.TAG_SOFTWARE))
        add("方向", exifEnum(tag(ExifInterface.TAG_ORIENTATION),
            mapOf("1" to "正常", "2" to "水平翻转", "3" to "旋转 180°", "4" to "垂直翻转",
                "5" to "旋转 90° 并水平翻转", "6" to "旋转 90°", "7" to "旋转 270° 并水平翻转",
                "8" to "旋转 270°")))
        val px = tag(ExifInterface.TAG_PIXEL_X_DIMENSION)
        val py = tag(ExifInterface.TAG_PIXEL_Y_DIMENSION)
        if (px != null && py != null) add("像素尺寸", "${px}×${py}px")
        val altitude = exif.getAltitude(Double.NaN)
        if (!altitude.isNaN()) add("海拔", "${"%.1f".format(altitude)}m")
        return rows
    }

    /** EXIF 枚举值（库读出来是 "1"、"65535" 这种字符串）翻译成中文，查不到就原样返回。 */
    private fun exifEnum(raw: String?, labels: Map<String, String>): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return labels[value] ?: value
    }

    /** EXIF 数值常是 "180/10"、"-1/3" 这种分数字符串，转成可比较的小数。 */
    private fun rationalText(raw: String?): Double? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val slash = value.indexOf('/')
        return if (slash > 0) {
            val numerator = value.substring(0, slash).trim().toDoubleOrNull()
            val denominator = value.substring(slash + 1).trim().toDoubleOrNull()
            if (numerator != null && denominator != null && denominator != 0.0) numerator / denominator else null
        } else value.toDoubleOrNull()
    }

    /** 去掉多余小数：18.0 → 18，1.53 → 1.5 */
    private fun trimNum(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (Math.abs(rounded - Math.round(rounded)) < 0.0001) {
            Math.round(rounded).toString()
        } else String.format(java.util.Locale.US, "%.1f", rounded)
    }

    /**
     * 直接从 JPEG 的 EXIF 里解 GPS 经纬度（IFD0 → GPS IFD，按 TIFF 规范走一遍）。
     *
     * 只读文件头 96KB：EXIF 一定在最前面的 APP 段里，不值得为一张图把几十 MB 全读进来。
     */
    private fun readGpsFromJpeg(file: File): Pair<Double, Double>? {
        val res = runCatching { parseGpsTiff(file) }
        if (res.isFailure) {
            DoukLog.w(
                "Exif",
                "GPS_TIFF_FAIL",
                "file" to file.name,
                "err" to res.exceptionOrNull()?.toString()
            )
            return null
        }
        val value = res.getOrNull()
        if (value == null) {
            DoukLog.d("Exif", "GPS_TIFF_MISS", "file" to file.name)
        } else {
            DoukLog.i("Exif", "GPS_BY_TIFF", "file" to file.name, "lat" to value.first, "lon" to value.second)
        }
        return value
    }

    internal fun parseGpsTiff(file: File): Pair<Double, Double>? {
        // 只读头部 96KB：EXIF 一定在最前面的 APP 段里，没必要为一张几十 MB 的照片整读进内存
        val head = runCatching {
            java.io.FileInputStream(file).use { s ->
                val buf = ByteArray(96 * 1024)
                var n = 0
                while (n < buf.size) {
                    val r = s.read(buf, n, buf.size - n)
                    if (r <= 0) break
                    n += r
                }
                if (n == buf.size) buf else buf.copyOf(n)
            }
        }.getOrNull() ?: return null
        val readLen = head.size
        fun miss(why: String): Pair<Double, Double>? {
            DoukLog.d("Exif", "GPS_TIFF_STEP", "file" to file.name, "why" to why, "size" to readLen)
            return null
        }
        if (readLen < 32) return miss("head=$readLen")
        val tiff = findTiffStart(head) ?: return miss("no tiff in ${readLen}B")
        if (tiff + 8 > head.size) return miss("tiff=$tiff beyond head")
        val le = head[tiff] == 0x49.toByte()

        fun u16(at: Int): Int {
            if (at < 0 || at + 2 > head.size) return -1
            val a = head[at].toInt() and 0xFF
            val b = head[at + 1].toInt() and 0xFF
            return if (le) a or (b shl 8) else (a shl 8) or b
        }

        fun u32(at: Int): Int {
            if (at < 0 || at + 4 > head.size) return -1
            val a = head[at].toInt() and 0xFF
            val b = head[at + 1].toInt() and 0xFF
            val c = head[at + 2].toInt() and 0xFF
            val d = head[at + 3].toInt() and 0xFF
            return if (le) a or (b shl 8) or (c shl 16) or (d shl 24)
            else (a shl 24) or (b shl 16) or (c shl 8) or d
        }

        fun asciiOf(e: Int, count: Int): String? {
            if (count <= 0 || count > 64) return null
            val start = if (count <= 4) e + 8 else tiff + u32(e + 8)
            if (start < 0 || start + count > head.size) return null
            return buildString {
                for (i in 0 until count) {
                    val c = head[start + i].toInt() and 0xFF
                    if (c != 0) append(c.toChar())
                }
            }.trim()
        }

        fun rational3(e: Int): Double? {
            val at = tiff + u32(e + 8)
            if (at < 0 || at + 24 > head.size) return null
            val w = doubleArrayOf(1.0, 1.0 / 60.0, 1.0 / 3600.0)
            var acc = 0.0
            for (i in 0 until 3) {
                val num = u32(at + i * 8)
                val den = u32(at + i * 8 + 4)
                if (num < 0 || den <= 0) return null
                acc += num.toDouble() / den * w[i]
            }
            return acc
        }

        if (u16(tiff + 2) != 42) return miss("magic=${u16(tiff + 2)} tiff=$tiff")
        val ifd0 = tiff + u32(tiff + 4)
        var gpsIfd = -1
        val n0 = u16(ifd0).coerceAtMost(64)
        for (k in 0 until n0) {
            val e = ifd0 + 2 + k * 12
            if (u16(e) == 0x8825) {
                gpsIfd = tiff + u32(e + 8)
                break
            }
        }
        if (gpsIfd < 0 || gpsIfd >= head.size) return miss("no gps ifd n0=$n0 ifd0=$ifd0")

        var lat: Double? = null
        var lon: Double? = null
        var latRef = ""
        var lonRef = ""
        val gn = u16(gpsIfd).coerceAtMost(64)
        for (k in 0 until gn) {
            val e = gpsIfd + 2 + k * 12
            when (u16(e)) {
                0x0001 -> latRef = asciiOf(e, u16(e + 4)) ?: ""
                0x0002 -> lat = rational3(e)
                0x0003 -> lonRef = asciiOf(e, u16(e + 4)) ?: ""
                0x0004 -> lon = rational3(e)
            }
        }
        val la = lat ?: return miss("lat=null gn=$gn gps=$gpsIfd")
        val lo = lon ?: return miss("lon=null gn=$gn")
        if (la == 0.0 && lo == 0.0) return miss("zero latlon")
        val laSign = if (latRef.uppercase().startsWith("S")) -1.0 else 1.0
        val loSign = if (lonRef.uppercase().startsWith("W")) -1.0 else 1.0
        return Pair(la * laSign, lo * loSign)
    }

    /** 在 JPEG 头部逐段走，找到 APP1 里 "Exif" 标记之后的 TIFF 起点；遇到 SOS 就停。 */
    private fun findTiffStart(head: ByteArray): Int? {
        var at = 2
        while (at + 4 <= head.size) {
            if (head[at] != 0xFF.toByte()) return null
            val marker = head[at + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return null
            val len = ((head[at + 2].toInt() and 0xFF) shl 8) or (head[at + 3].toInt() and 0xFF)
            if (len < 2) return null
            if (marker == 0xE1) {
                val seg = at + 4
                if (seg + 6 <= head.size &&
                    head[seg] == 'E'.code.toByte() &&
                    head[seg + 1] == 'x'.code.toByte() &&
                    head[seg + 2] == 'i'.code.toByte() &&
                    head[seg + 3] == 'f'.code.toByte()
                ) {
                    return seg + 6
                }
            }
            at += 2 + len
        }
        return null
    }

    /** 手机内的照片拿不到 File，用 uri 读尺寸与大小，路径显示成「相册/文件名」。 */
    fun metaOfUri(context: Context, uri: Uri, fileName: String, sizeBytes: Long, album: String?): Meta {
        var width: Int? = null
        var height: Int? = null
        var taken: Long? = null
        var lat: Double? = null
        var lon: Double? = null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATA
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    if (c.getColumnIndex(MediaStore.Images.Media.WIDTH) >= 0) width = c.getInt(0).takeIf { it > 0 }
                    if (c.getColumnIndex(MediaStore.Images.Media.HEIGHT) >= 0) height = c.getInt(1).takeIf { it > 0 }
                    if (c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN) >= 0) taken = c.getLong(2).takeIf { it > 0 }
                }
            }
        }
        // 手机相册里带定位的照片，MediaStore 会存经纬度（API 29+ 才有这两列）
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media.LATITUDE,
                    MediaStore.Images.Media.LONGITUDE
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val la = c.getDouble(0)
                    val lo = c.getDouble(1)
                    if (la != 0.0 || lo != 0.0) {
                        lat = la
                        lon = lo
                    }
                }
            }
        }
        // 相册里的图同样能读到 EXIF：用 uri 开流给 ExifInterface，读不到就留空
        val exif = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        }.getOrNull()
        val camera = exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotBlank() }
        val lens = exif?.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotBlank() }
        val exposure = exif?.let { e ->
            buildList {
                rationalText(e.getAttribute(ExifInterface.TAG_F_NUMBER))?.let { add("f/${trimNum(it)}") }
                rationalText(e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))?.let { t ->
                    add(if (t > 0.0 && t < 0.5) "1/${Math.round(1.0 / t)}s" else "${trimNum(t)}s")
                }
                e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { add("ISO $it") }
                rationalText(e.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))?.let { add("${trimNum(it)}mm") }
            }.joinToString(" · ").takeIf { it.isNotBlank() }
        }
        return Meta(
            fileName = fileName,
            sizeBytes = sizeBytes,
            path = listOfNotNull(album, fileName).joinToString("/"),
            width = width,
            height = height,
            takenAt = taken,
            camera = camera, lens = lens, exposureLine = exposure,
            title = exif?.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.trim()?.takeIf { it.isNotBlank() },
            keywords = exif?.getAttribute(ExifInterface.TAG_USER_COMMENT)?.trim()?.takeIf { it.isNotBlank() },
            artist = exif?.getAttribute(ExifInterface.TAG_ARTIST)?.trim()?.takeIf { it.isNotBlank() },
            copyright = exif?.getAttribute(ExifInterface.TAG_COPYRIGHT)?.trim()?.takeIf { it.isNotBlank() },
            lat = lat, lon = lon,
            exifRows = exifRowsOf(exif)
        )
    }

    /** 把一段内容写进公共图片库。dir 形如 Pictures/连镜、DCIM/连镜/旅行。 */
    fun writeToGallery(
        context: Context,
        dir: String,
        displayName: String,
        mime: String,
        write: (OutputStream) -> Unit
    ): Result<Uri> {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, dir)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching {
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return Result.failure(IllegalStateException("系统没有给出可写的图片条目"))

        return runCatching {
            context.contentResolver.openOutputStream(uri)?.use(write)
                ?: error("打不开写入流")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        }.onFailure {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    fun copyFileToGallery(context: Context, src: File, dir: String, displayName: String): Result<Uri> =
        writeToGallery(context, dir, displayName, mimeOf(displayName)) { out ->
            src.inputStream().use { it.copyTo(out) }
        }

    fun copyUriToGallery(context: Context, src: Uri, dir: String, displayName: String): Result<Uri> =
        writeToGallery(context, dir, displayName, mimeOf(displayName)) { out ->
            context.contentResolver.openInputStream(src)?.use { it.copyTo(out) }
                ?: error("读不到原图")
        }

    /** 读 JPEG 的 EXIF 方向（1=正常，3=180°，6=顺时针 90°，8=逆时针 90°） */
    fun exifOrientationOf(bytes: ByteArray): Int = runCatching {
        androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
            .getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )
    }.getOrDefault(1)

    fun exifOrientationOf(file: File): Int = runCatching {
        androidx.exifinterface.media.ExifInterface(file.absolutePath)
            .getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )
    }.getOrDefault(1)

    /** 按 EXIF 方向把位图转正，竖拍照片不再横着显示 */
    fun applyExifOrientation(bitmap: android.graphics.Bitmap, orientation: Int): android.graphics.Bitmap {
        if (orientation == 1) return bitmap
        val matrix = android.graphics.Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return runCatching {
            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /** 直接写字节到公共图片库（应用相册 / 手机相册共用） */
    fun writeBytesToGallery(context: Context, dir: String, displayName: String, bytes: ByteArray): Result<Uri> =
        writeToGallery(context, dir, displayName, mimeOf(displayName)) { out -> out.write(bytes) }

    /** 写进用户选中的自定义文件夹（SAF 授权树，重名时自动加序号） */
    fun writeBytesToTree(context: Context, treeUri: Uri, displayName: String, bytes: ByteArray): Result<Uri> = runCatching {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        var name = displayName
        var uri = android.provider.DocumentsContract.createDocument(
            context.contentResolver, parent, mimeOf(name), name
        )
        var tries = 1
        while (uri == null && tries < 20) {
            val base = displayName.substringBeforeLast('.', displayName)
            val ext = displayName.substringAfterLast('.', "")
            name = if (ext.isNotEmpty()) base + " (" + tries + ")." + ext else base + " (" + tries + ")"
            uri = android.provider.DocumentsContract.createDocument(
                context.contentResolver, parent, mimeOf(name), name
            )
            tries++
        }
        val doc = uri ?: error("无法在该文件夹里新建文件")
        context.contentResolver.openOutputStream(doc)?.use { it.write(bytes) }
            ?: error("打不开写入流")
        doc
    }

    /** 把应用内文件移进某个应用相册目录（同一目录不重复搬；重名自动加序号） */
    fun moveAppFileToAlbum(context: Context, fileName: String, dir: String): Result<File> {
        val src = locateAppFile(fileName) ?: return Result.failure(IllegalStateException("找不到文件 $fileName"))
        val relative = dir.removePrefix("Pictures/").trim('/')
        val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), relative)
        if (src.parentFile?.absolutePath == targetDir.absolutePath) return Result.success(src)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return Result.failure(IllegalStateException("建不了文件夹 ${targetDir.name}"))
        }
        var target = File(targetDir, src.name)
        var index = 1
        while (target.exists()) {
            val base = src.name.substringBeforeLast('.', src.name)
            val ext = src.name.substringAfterLast('.', "")
            val name = if (ext.isNotEmpty()) "$base ($index).$ext" else "$base ($index)"
            target = File(targetDir, name)
            index++
        }
        val moved = runCatching {
            if (src.renameTo(target)) return@runCatching true
            target.outputStream().use { out -> src.inputStream().use { it.copyTo(out) } }
            src.delete()
        }.getOrDefault(false)
        if (!moved) {
            runCatching { target.delete() }
            return Result.failure(IllegalStateException("移动失败，文件可能被别的应用占用"))
        }
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath, src.absolutePath),
                arrayOf(mimeOf(target.name), mimeOf(src.name)),
                null
            )
        }
        return Result.success(target)
    }

    /** 应用内文件改名：File.renameTo 后扫一次，让相册立刻看到新名字。 */
    fun renameAppFile(context: Context, oldName: String, newName: String): Result<String> {
        val file = locateAppFile(oldName) ?: return Result.failure(IllegalStateException("找不到文件 $oldName"))
        val target = File(file.parentFile, newName)
        if (target.exists()) return Result.failure(IllegalStateException("同目录已有 $newName"))
        if (!file.renameTo(target)) return Result.failure(IllegalStateException("重命名失败，可能被别的应用占用"))
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(target.absolutePath), arrayOf(mimeOf(newName)), null
            )
        }
        return Result.success(newName)
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "nef", "nrw" -> "image/x-nikon-nef"
        "dng" -> "image/x-adobe-dng"
        else -> "image/jpeg"
    }

    fun fileSizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private fun parseExifDate(raw: String): Long? = runCatching {
        // 相机写的 EXIF 时间是本地时间，没有时区信息，按本地解析
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
    }.getOrNull()

    fun dateText(millis: Long?): String? = millis?.let {        SimpleDateFormat("yyyy年M月d日  EEEE", Locale.CHINA).format(Date(it)) +
            "\n" + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(it))
    }

    fun shortDateText(millis: Long?): String? = millis?.let {
        SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(it))
    }

    fun timeText(millis: Long?): String? = millis?.let {
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(it))
    }

    /** 手机相册路径前缀，供「添加到」面板展示。 */
    const val PHONE_FOLDER = "Pictures/$APP_ROOT"
    const val PHONE_ALBUM = "DCIM/$PHONE_ALBUM_ROOT"
}
