package com.lutcamera.lutcamerapro.log

import android.content.Context
import com.lutcamera.lutcamerapro.BuildConfig
import com.lutcamera.lutcamerapro.ui.douk.DoukPrefs
import com.lutcamera.lutcamerapro.ui.douk.KnownDevice
import com.lutcamera.lutcamerapro.util.BoundedIo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 连镜预设：把设置项按组导出成 JSON。
 *
 * 设计目标：
 * - 不把 IP、蓝牙地址、密码之类的“本机专属”内容混进外观/编辑器组；
 * - 另一台设备导入时只覆盖文件里出现的组，没选的组保持原样；
 * - JSON 顶层带 schema/version，以后加字段不会把老预设读崩。
 */
object DoukPreset {

    const val SCHEMA = "lianjing-preset-v1"

    /** 1.0.0 之前的旧预设；只读兼容，导入后照常应用，导出统一写新 schema。 */
    private const val LEGACY_SCHEMA = "lenslink-preset-v1"

    data class Group(
        val key: String,
        val title: String,
        val subtitle: String
    )

    val groups = listOf(
        Group("device", "设备连接", "自动连接、已连接相机、蓝牙默认设备、设备页按钮"),
        Group("download", "下载与回传", "自动下载、图片/视频大小与格式、保存位置、锁定文件"),
        Group("general", "通用", "语言、GPS 附加方式、拍摄后快速预览"),
        Group("appearance", "外观", "深色模式、主题色、玻璃质感、悬浮连镜栏"),
        Group("gallery", "图库", "默认打开来源、是否始终显示原图"),
        Group("editor", "图像编辑", "导出格式/质量、预览长边、九宫格、按住对比"),
        Group("filters", "LUT 与滤镜", "已导入的 .cube / .xmp 原文件和分类归属，一起打包带走")
    )

    /** 默认全选；导出页可逐组取消。 */
    fun allSelection(): Set<String> = groups.map { it.key }.toSet()

    /** 只导出设置 JSON（兼容老版本、便于人工查看）。 */
    fun export(
        context: Context,
        out: OutputStream,
        selection: Set<String>,
        onDone: (String) -> Unit = {}
    ): Result<Int> = runCatching {
        val text = buildJson(selection)
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
        onDone(text)
        selection.size
    }

    /**
     * 导出跨设备预设包：`preset.json` + `luts/` 里所有 LUT/XMP 原文件。
     * 另一台手机导入同一份 zip，就能连滤镜文件、分类一起还原。
     */
    fun exportBundle(
        context: Context,
        out: OutputStream,
        selection: Set<String>,
        onDone: (String) -> Unit = {}
    ): Result<Int> = runCatching {
        val text = buildJson(selection)
        val files = if ("filters" in selection) DoukPrefs.importedResources(context) else emptyList()
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("preset.json"))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            files.forEach { file ->
                if (!file.isFile) return@forEach
                zip.putNextEntry(ZipEntry("luts/${file.name}"))
                BufferedInputStream(file.inputStream()).use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("README.txt"))
            val readme = buildString {
                appendLine("连镜 预设包")
                appendLine("preset.json：设置项")
                appendLine("luts/：LUT(.cube) 与滤镜(.xmp) 原文件，导入时自动复制回应用的滤镜目录")
                appendLine("导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())}")
                appendLine("版本：${BuildConfig.VERSION_NAME}")
            }
            zip.write(readme.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        onDone("${files.size}")
        selection.size
    }

    private fun buildJson(selection: Set<String>): String {
        val root = JSONObject()
        root.put("schema", SCHEMA)
        root.put("app", "连镜")
        root.put("version", BuildConfig.VERSION_NAME)
        root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
        val g = JSONObject()
        if ("device" in selection) g.put("device", deviceJson())
        if ("download" in selection) g.put("download", downloadJson())
        if ("general" in selection) g.put("general", generalJson())
        if ("appearance" in selection) g.put("appearance", appearanceJson())
        if ("gallery" in selection) g.put("gallery", galleryJson())
        if ("editor" in selection) g.put("editor", editorJson())
        if ("filters" in selection) g.put("filters", filtersJson())
        root.put("groups", g)
        return root.toString(2)
    }

    /**
     * 读取并应用预设。只应用 JSON 里实际存在的组；返回 (应用组数, 应用组名)。
     * 支持新版 .zip（含 LUT 原文件）与老版 .json。
     * IO 阻塞，调用方应在 Dispatchers.IO 执行。
     */
    fun import(context: Context, input: InputStream): Result<Pair<Int, List<String>>> = runCatching {
        val buffered = BufferedInputStream(input)
        buffered.mark(4)
        val header = ByteArray(4)
        val headerRead = buffered.read(header)
        buffered.reset()
        val isZip = headerRead >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        if (!isZip) {
            applyJson(JSONObject(BoundedIo.readUtf8WithLimit(buffered, MAX_PRESET_JSON_BYTES)))
        } else {
            var presetText: String? = null
            val lutDir = DoukPrefs.lutDir(context)
            var lutCount = 0
            var entryCount = 0
            var totalUncompressed = 0L
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    require(entryCount <= MAX_PRESET_ENTRIES) { "预设包条目过多" }
                    val name = entry.name
                    when {
                        name == "preset.json" -> {
                            presetText = BoundedIo.readUtf8WithLimit(zip, MAX_PRESET_JSON_BYTES)
                        }
                        name.startsWith("luts/") && !entry.isDirectory -> {
                            // 只取文件名，挡掉 ../ 之类的路径穿越
                            val fileName = File(name).name
                            require(fileName.isNotBlank() && fileName != "." && fileName != "..") {
                                "预设包包含非法 LUT 文件名"
                            }
                            require(entry.size <= MAX_PRESET_ENTRY_BYTES) { "LUT 条目超过 32MB" }
                            val target = File(lutDir, fileName)
                            val temporary = File(lutDir, "$fileName.part")
                            try {
                                target.parentFile?.mkdirs()
                                val copied = temporary.outputStream().use { out ->
                                    BoundedIo.copyWithLimit(zip, out, MAX_PRESET_ENTRY_BYTES)
                                }
                                totalUncompressed += copied
                                require(totalUncompressed <= BoundedIo.MAX_PRESET_TOTAL_BYTES) {
                                    "预设包解压后超过 128MB"
                                }
                                // 压缩比超过 100 的条目基本只能是 ZIP 炸弹；正常 LUT 文本远低于此。
                                if (entry.compressedSize > 0 && copied / entry.compressedSize > 100) {
                                    error("LUT 条目压缩比异常")
                                }
                                if (!temporary.renameTo(target)) {
                                    temporary.copyTo(target, overwrite = true)
                                }
                                lutCount++
                            } finally {
                                temporary.delete()
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val text = presetText ?: error("预设包里缺少 preset.json")
            applyJson(JSONObject(text), importedLutCount = lutCount)
        }
    }

    private const val MAX_PRESET_JSON_BYTES = 8L * 1024 * 1024
    private const val MAX_PRESET_ENTRIES = 256
    private const val MAX_PRESET_ENTRY_BYTES = BoundedIo.MAX_PRESET_ENTRY_BYTES

    private fun applyJson(
        root: JSONObject,
        importedLutCount: Int = 0
    ): Pair<Int, List<String>> {
        require(root.optString("schema").let { it == SCHEMA || it == LEGACY_SCHEMA }) {
            "不是连镜预设文件（schema=${root.optString("schema")}）"
        }
        val g = root.optJSONObject("groups") ?: JSONObject()
        val applied = mutableListOf<String>()
        g.optJSONObject("device")?.let { applyDevice(it); applied += "设备连接" }
        g.optJSONObject("download")?.let { applyDownload(it); applied += "下载与回传" }
        g.optJSONObject("general")?.let { applyGeneral(it); applied += "通用" }
        g.optJSONObject("appearance")?.let { applyAppearance(it); applied += "外观" }
        g.optJSONObject("gallery")?.let { applyGallery(it); applied += "图库" }
        g.optJSONObject("editor")?.let { applyEditor(it); applied += "图像编辑" }
        g.optJSONObject("filters")?.let { applyFilters(it); applied += "LUT 与滤镜" }
        DoukLog.i("Preset", "IMPORT_OK", "groups" to applied.size, "names" to applied.joinToString("/"),
            "luts" to importedLutCount)
        return applied.size to applied
    }

    // ---------------------------------------------------------------- 导出

    private fun deviceJson() = JSONObject().apply {
        put("autoConnectKnown", DoukPrefs.autoConnectKnown)
        put("showAddDeviceButton", DoukPrefs.showAddDeviceButton)
        put("showBleGpsCard", DoukPrefs.showBleGpsCard)
        put("bleDefaultName", DoukPrefs.bleDefaultName.cleanName())
        put("knownDevices", JSONArray().apply {
            DoukPrefs.knownDevices().distinctBy { it.ip }.forEach { d ->
                put(
                    JSONObject()
                        .put("ip", d.ip.cleanName())
                        .put("model", d.model.cleanName())
                        .put("lastUsedAt", d.lastUsedAt)
                )
            }
        })
        put("bleDevices", JSONArray().apply {
            DoukPrefs.bleDevices()
                .map { Triple(it.name.cleanName(), it.address.cleanName(), it.lastUsedAt) }
                .distinctBy { it.first to it.second }
                .forEach { (name, address, lastUsedAt) ->
                    put(JSONObject().put("name", name).put("address", address).put("lastUsedAt", lastUsedAt))
                }
        })
    }

    private fun downloadJson() = JSONObject().apply {
        put("autoDownload", DoukPrefs.autoDownload)
        put("size", DoukPrefs.downloadSize)
        put("format", DoukPrefs.downloadFormat)
        put("dir", DoukPrefs.downloadDir)
        put("videoSize", DoukPrefs.downloadVideoSize)
        put("videoFormat", DoukPrefs.downloadVideoFormat)
        put("saveFormat", DoukPrefs.saveFormat)
        put("lockedOnly", DoukPrefs.lockedOnly)
        put("lockedSize", DoukPrefs.lockedSize)
        put("lockedFormat", DoukPrefs.lockedFormat)
        put("lockedDir", DoukPrefs.lockedDir)
        put("lockedVideoSize", DoukPrefs.lockedVideoSize)
        put("lockedVideoFormat", DoukPrefs.lockedVideoFormat)
        put("lockedAutoImport", DoukPrefs.lockedAutoImport)
        put("lockedAskBeforeImport", DoukPrefs.lockedAskBeforeImport)
        put("lockedNewOnly", DoukPrefs.lockedNewOnly)
    }

    private fun generalJson() = JSONObject().apply {
        put("languageFollowSystem", DoukPrefs.languageFollowSystem)
        put("language", DoukPrefs.language)
        put("gpsEnabled", DoukPrefs.gpsEnabled)
        put("gpsCamera", DoukPrefs.gpsAttachCamera)
        put("gpsImport", DoukPrefs.gpsAttachImport)
        put("gpsBle", DoukPrefs.gpsAttachBle)
        put("capturePreview", DoukPrefs.capturePreview)
    }

    private fun appearanceJson() = JSONObject().apply {
        put("darkMode", DoukPrefs.darkMode)
        put("themeColor", DoukPrefs.themeColor)
        put("glassEffect", DoukPrefs.glassEffect)
        put("floatingBar", DoukPrefs.floatingBar)
    }

    private fun galleryJson() = JSONObject().apply {
        put("showOriginal", DoukPrefs.galleryShowOriginal)
        put("defaultSource", DoukPrefs.galleryDefaultSource)
    }

    private fun editorJson() = JSONObject().apply {
        put("format", DoukPrefs.editorFormat)
        put("quality", DoukPrefs.editorQuality)
        put("preview", DoukPrefs.editorPreview)
        put("grid", DoukPrefs.editorGrid)
        put("compare", DoukPrefs.editorCompare)
    }

    private fun filtersJson() = JSONObject().apply {
        DoukPrefs.filterCategories().forEach { (file, category) -> put(file, category) }
    }

    // ---------------------------------------------------------------- 导入

    private fun applyDevice(o: JSONObject) {
        if (o.has("autoConnectKnown")) DoukPrefs.autoConnectKnown = o.getBoolean("autoConnectKnown")
        if (o.has("showAddDeviceButton")) DoukPrefs.showAddDeviceButton = o.getBoolean("showAddDeviceButton")
        if (o.has("showBleGpsCard")) DoukPrefs.showBleGpsCard = o.getBoolean("showBleGpsCard")
        o.optString("bleDefaultName", "").takeIf { it.isNotBlank() }?.let { DoukPrefs.bleDefaultName = it }
        o.optJSONArray("knownDevices")?.let { arr ->
            val devices = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { d ->
                    KnownDevice(
                        d.optString("ip"),
                        d.optString("model", "尼康相机"),
                        d.optLong("lastUsedAt", 0L)
                    )
                }
            }.filter { it.ip.isNotBlank() }
            DoukPrefs.replaceKnownDevices(devices)
        }
        o.optJSONArray("bleDevices")?.let { arr ->
            val devices = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { d ->
                    DoukPrefs.KnownBleDevice(
                        d.optString("name"),
                        d.optString("address"),
                        d.optLong("lastUsedAt", 0L)
                    )
                }
            }.filter { it.name.isNotBlank() || it.address.isNotBlank() }
            DoukPrefs.replaceBleDevices(devices)
        }
    }

    private fun applyDownload(o: JSONObject) {
        if (o.has("autoDownload")) DoukPrefs.autoDownload = o.getBoolean("autoDownload")
        o.optString("size")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.downloadSize = it }
        o.optString("format")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.downloadFormat = it }
        o.optString("dir")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.downloadDir = it }
        o.optString("videoSize")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.downloadVideoSize = it }
        o.optString("videoFormat")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.downloadVideoFormat = it }
        o.optString("saveFormat")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.saveFormat = it }
        if (o.has("lockedOnly")) DoukPrefs.lockedOnly = o.getBoolean("lockedOnly")
        o.optString("lockedSize")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.lockedSize = it }
        o.optString("lockedFormat")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.lockedFormat = it }
        o.optString("lockedDir")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.lockedDir = it }
        o.optString("lockedVideoSize")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.lockedVideoSize = it }
        o.optString("lockedVideoFormat")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.lockedVideoFormat = it }
        if (o.has("lockedAutoImport")) DoukPrefs.lockedAutoImport = o.getBoolean("lockedAutoImport")
        if (o.has("lockedAskBeforeImport")) DoukPrefs.lockedAskBeforeImport = o.getBoolean("lockedAskBeforeImport")
        if (o.has("lockedNewOnly")) DoukPrefs.lockedNewOnly = o.getBoolean("lockedNewOnly")
    }

    private fun applyGeneral(o: JSONObject) {
        if (o.has("languageFollowSystem")) DoukPrefs.languageFollowSystem = o.getBoolean("languageFollowSystem")
        o.optString("language")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.language = it }
        if (o.has("gpsEnabled")) DoukPrefs.gpsEnabled = o.getBoolean("gpsEnabled")
        if (o.has("gpsCamera")) DoukPrefs.gpsAttachCamera = o.getBoolean("gpsCamera")
        if (o.has("gpsImport")) DoukPrefs.gpsAttachImport = o.getBoolean("gpsImport")
        if (o.has("gpsBle")) DoukPrefs.gpsAttachBle = o.getBoolean("gpsBle")
        if (o.has("capturePreview")) DoukPrefs.capturePreview = o.getBoolean("capturePreview")
    }

    private fun applyAppearance(o: JSONObject) {
        o.optString("darkMode")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.darkMode = it }
        o.optString("themeColor")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.themeColor = it }
        if (o.has("glassEffect")) DoukPrefs.glassEffect = o.getBoolean("glassEffect")
        if (o.has("floatingBar")) DoukPrefs.floatingBar = o.getBoolean("floatingBar")
    }

    private fun applyGallery(o: JSONObject) {
        if (o.has("showOriginal")) DoukPrefs.galleryShowOriginal = o.getBoolean("showOriginal")
        o.optString("defaultSource")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.galleryDefaultSource = it }
    }

    private fun applyEditor(o: JSONObject) {
        o.optString("format")?.takeIf { it.isNotBlank() }?.let { DoukPrefs.editorFormat = it }
        if (o.has("quality")) o.optInt("quality", 0).takeIf { it > 0 }?.let { DoukPrefs.editorQuality = it }
        if (o.has("preview")) o.optInt("preview", 0).takeIf { it > 0 }?.let { DoukPrefs.editorPreview = it }
        if (o.has("grid")) DoukPrefs.editorGrid = o.getBoolean("grid")
        if (o.has("compare")) DoukPrefs.editorCompare = o.getBoolean("compare")
    }

    private fun applyFilters(o: JSONObject) {
        val keys = o.keys()
        while (keys.hasNext()) {
            val file = keys.next()
            val category = o.optString(file, "")
            if (category.isNotBlank()) DoukPrefs.setFilterCategory(file, category)
        }
    }

    /** 蓝牙广播名里常有相机填充的 NUL，导出前清掉，避免预设把乱码带到另一台设备。 */
    private fun String.cleanName(): String = replace("\u0000", "").trim()
}
