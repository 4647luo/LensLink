package com.lutcamera.lutcamerapro.ui.douk

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** 连接过的相机记录 */
data class KnownDevice(val ip: String, val model: String, val lastUsedAt: Long)

/**
 * 设置项的持久化。全部落在 SharedPreferences 里，键值都是普通类型，
 * 让设置页每次打开都能读到上次的选择，不必依赖 Compose 的内存状态。
 */
object DoukPrefs {
    private const val FILE = "douk_settings"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun ensure() {
        check(::sp.isInitialized) { "DoukPrefs 未初始化，需在 Application/Activity 启动时调 init" }
    }

    private fun str(key: String, def: String): String { ensure(); return sp.getString(key, def) ?: def }
    private fun putStr(key: String, value: String) { ensure(); sp.edit().putString(key, value).apply() }
    private fun flag(key: String, def: Boolean): Boolean { ensure(); return sp.getBoolean(key, def) }
    private fun putFlag(key: String, value: Boolean) { ensure(); sp.edit().putBoolean(key, value).apply() }
    private fun getInt(key: String, def: Int): Int { ensure(); return sp.getInt(key, def) }
    private fun putInt(key: String, value: Int) { ensure(); sp.edit().putInt(key, value).apply() }
    private fun getFloat(key: String, def: Float): Float { ensure(); return sp.getFloat(key, def) }
    private fun putFloat(key: String, value: Float) { ensure(); sp.edit().putFloat(key, value).apply() }

    // ===== 设备 =====
    /** 识别到连接过的相机时自动连接（默认开启：断线后每 20 秒探测一次历史相机） */
    var autoConnectKnown: Boolean
        get() = flag("auto_connect_known", true)
        set(v) = putFlag("auto_connect_known", v)

    /** 未连接时自动探测历史相机的间隔（秒） */
    var autoReconnectSeconds: Int
        get() = getInt("auto_reconnect_seconds", 20).coerceIn(10, 120)
        set(v) = putInt("auto_reconnect_seconds", v.coerceIn(10, 120))

    // ===== 监看 =====
    /** 手机端取景画质：流畅 / 标准 / 高清 */
    var monitorPreviewQuality: String
        get() = str("monitor_preview_quality", "标准")
        set(v) = putStr("monitor_preview_quality", v)

    /** 监视画面左栏按钮（逗号分隔的动作 id） */
    var monitorLeftActions: String
        get() = str("monitor_left_actions", "iso,shutter,aperture,ev")
        set(v) = putStr("monitor_left_actions", v)

    /** 监视画面右栏按钮（快门固定居中，其余动作竖排） */
    var monitorRightActions: String
        get() = str("monitor_right_actions", "grid,hist,wave,audio")
        set(v) = putStr("monitor_right_actions", v)

    /** 设备页是否显示「添加设备」按钮（关掉后走顶部 + 号或搜索） */
    var showAddDeviceButton: Boolean
        get() = flag("show_add_device_button", true)
        set(v) = putFlag("show_add_device_button", v)

    fun knownDevices(): List<KnownDevice> = str("known_devices", "")
        .lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size >= 2) KnownDevice(parts[0], parts[1], parts.getOrNull(2)?.toLongOrNull() ?: 0L) else null
        }

    fun rememberDevice(ip: String, model: String) {
        if (ip.isBlank()) return
        val others = knownDevices().filter { it.ip != ip }
        val updated = (listOf(KnownDevice(ip, model.ifBlank { "尼康相机" }, System.currentTimeMillis())) + others)
            .take(12)
        putStr("known_devices", updated.joinToString("\n") { "${it.ip}|${it.model}|${it.lastUsedAt}" })
    }

    fun forgetDevice(ip: String) {
        putStr("known_devices", knownDevices().filter { it.ip != ip }
            .joinToString("\n") { "${it.ip}|${it.model}|${it.lastUsedAt}" })
    }

    /** 预设导入：整表替换连接过的相机，保留文件里的顺序与时间。 */
    fun replaceKnownDevices(devices: List<KnownDevice>) {
        putStr("known_devices", devices.take(12)
            .joinToString("\n") { "${it.ip}|${it.model}|${it.lastUsedAt}" })
    }

    // ===== 蓝牙 =====
    /** 默认优先连接的蓝牙相机（按广播名认，地址会轮转） */
    var bleDefaultName: String
        get() = str("ble_default_name", "")
        set(v) = putStr("ble_default_name", v)

    data class KnownBleDevice(val name: String, val address: String, val lastUsedAt: Long)

    fun bleDevices(): List<KnownBleDevice> = str("ble_devices", "").lines()
        .filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split('|')
            if (p.size >= 2 && p[0].isNotBlank()) {
                KnownBleDevice(p[0], p[1], p.getOrNull(2)?.toLongOrNull() ?: 0L)
            } else null
        }

    fun rememberBleDevice(name: String, address: String) {
        if (name.isBlank() && address.isBlank()) return
        val others = bleDevices().filter { it.name != name }
        val updated = (listOf(KnownBleDevice(name.ifBlank { address }, address, System.currentTimeMillis())) + others)
            .take(8)
        putStr("ble_devices", updated.joinToString("\n") { "${it.name}|${it.address}|${it.lastUsedAt}" })
    }

    fun forgetBleDevice(name: String) {
        putStr("ble_devices", bleDevices().filter { it.name != name }
            .joinToString("\n") { "${it.name}|${it.address}|${it.lastUsedAt}" })
        if (bleDefaultName == name) bleDefaultName = ""
    }

    /** 预设导入：整表替换蓝牙相机记录。 */
    fun replaceBleDevices(devices: List<KnownBleDevice>) {
        putStr("ble_devices", devices.take(8)
            .joinToString("\n") { "${it.name}|${it.address}|${it.lastUsedAt}" })
    }

    // ===== 下载 =====
    var autoDownload: Boolean
        get() = flag("auto_download", false)
        set(v) = putFlag("auto_download", v)
    /** 原图 / 仅缩略图 */
    var downloadSize: String
        get() = str("download_size", "原图")
        set(v) = putStr("download_size", v)
    /** JPG / JPG + RAW / 全部格式 */
    var downloadFormat: String
        get() = str("download_format", "JPG")
        set(v) = putStr("download_format", v)
    /** 应用相册 / 系统相册 / Download */
    var downloadDir: String
        get() = str("download_dir", "应用相册")
        set(v) = putStr("download_dir", v)
    /** 视频回传大小：原视频 / 仅缩略图 */
    var downloadVideoSize: String
        get() = str("download_video_size", "原视频")
        set(v) = putStr("download_video_size", v)
    /** 「保存到」时从机身取哪些文件：JPG / RAW / JPG + RAW / 全部格式 */
    var saveFormat: String
        get() = str("save_format", "JPG + RAW")
        set(v) = putStr("save_format", v)
    /** 图片编辑器导出格式：JPG / PNG（WebP 已按用户要求去掉） */
    var editorFormat: String
        get() = str("editor_format", "JPG").let { if (it == "WebP" || it == "WEBP") "JPG" else it }
        set(v) = putStr("editor_format", v)
    /** 图库默认导出格式："" = 每次导出时再选，JPG / PNG = 导出不再弹格式选择 */
    var defaultExportFormat: String
        get() = str("default_export_format", "")
        set(v) = putStr("default_export_format", v)
    /** 编辑时默认用哪种源格式："" = 有 JPG+RAW 时先问，JPG / HEIC / RAW = 直接用 */
    var defaultEditFormat: String
        get() = str("default_edit_format", "")
        set(v) = putStr("default_edit_format", v)
    /** 图片编辑器导出质量：90 / 95 / 100 */
    var editorQuality: Int
        get() = str("editor_quality", "95").toIntOrNull()?.coerceIn(60, 100) ?: 95
        set(v) = putStr("editor_quality", v.coerceIn(60, 100).toString())
    /** 编辑预览长边：720 / 1080 / 1440 */
    var editorPreview: Int
        get() = str("editor_preview", "1080").toIntOrNull()?.coerceIn(480, 2048) ?: 1080
        set(v) = putStr("editor_preview", v.coerceIn(480, 2048).toString())
    /** 编辑页显示九宫格辅助线 */
    var editorGrid: Boolean
        get() = flag("editor_grid", false)
        set(v) = putFlag("editor_grid", v)
    /** 按住画面时显示原图对比 */
    var editorCompare: Boolean
        get() = flag("editor_compare", true)
        set(v) = putFlag("editor_compare", v)
    /** 视频回传格式：MOV / MP4 / 全部格式 */
    var downloadVideoFormat: String
        get() = str("download_video_format", "MOV")
        set(v) = putStr("download_video_format", v)

    /** 只下载相机里已锁定的照片 */
    var lockedOnly: Boolean
        get() = flag("locked_only", false)
        set(v) = putFlag("locked_only", v)
    var lockedSize: String
        get() = str("locked_size", "原图")
        set(v) = putStr("locked_size", v)
    var lockedFormat: String
        get() = str("locked_format", "JPG")
        set(v) = putStr("locked_format", v)
    var lockedDir: String
        get() = str("locked_dir", "应用相册")
        set(v) = putStr("locked_dir", v)
    /** 锁定文件回传 · 视频大小 */
    var lockedVideoSize: String
        get() = str("locked_video_size", "原视频")
        set(v) = putStr("locked_video_size", v)
    /** 锁定文件回传 · 视频格式 */
    var lockedVideoFormat: String
        get() = str("locked_video_format", "MOV")
        set(v) = putStr("locked_video_format", v)
    /** 连上相机后自动导入相机里所有保护/锁定照片 */
    var lockedAutoImport: Boolean
        get() = flag("locked_auto_import", false)
        set(v) = putFlag("locked_auto_import", v)
    /** 自动导入锁定照片前先弹确认；关闭后静默导入 */
    var lockedAskBeforeImport: Boolean
        get() = flag("locked_ask_before", true)
        set(v) = putFlag("locked_ask_before", v)

    /**
     * 连接后导入的锁定文件范围：
     * false = 相机内所有锁定文件；true = 仅本次连接后新增的锁定文件。
     * 换过两次 key：旧版默认 true，「相机内全部」模式下永远导不到已经锁好的照片；
     * 0.0.142 再换 key，把所有装机都拉回「相机内全部」，
     * 靠文件名+大小的判重保证以前下过的不会重复下载。
     */
    var lockedNewOnly: Boolean
        get() = flag("locked_new_only_v3", false)
        set(v) = putFlag("locked_new_only_v3", v)

    /** 已经导入过的锁定文件键（文件名|大小），跨会话去重，避免每次连接重复下载 */
    private var importedLockedKeys: MutableSet<String>
        get() = str("locked_imported_keys", "").split('\n')
            .filter { it.isNotBlank() }.toMutableSet()
        set(v) = putStr("locked_imported_keys", v.toList().takeLast(400).joinToString("\n"))

    fun isLockedImported(key: String): Boolean = key in importedLockedKeys

    fun markLockedImported(key: String) {
        val current = importedLockedKeys
        current.add(key)
        importedLockedKeys = current
    }

    /** 设备页是否显示蓝牙 GPS 卡片 */
    var showBleGpsCard: Boolean
        get() = flag("show_ble_gps_card", true)
        set(v) = putFlag("show_ble_gps_card", v)

    /** 设备页是否显示蓝牙遥控卡片 */
    var showBleRemoteCard: Boolean
        get() = flag("show_ble_remote_card", true)
        set(v) = putFlag("show_ble_remote_card", v)

    /** 已废弃：以前用 6 秒心跳强行不让相机待机，实测会把相机屏幕反复点亮，现固定 15 秒 */
    var wifiKeepAwake: Boolean
        get() = flag("wifi_keep_awake", false)
        set(v) = putFlag("wifi_keep_awake", v)

    /**
     * 进入监视时是否直接接管相机（0x9435/0x90C2）。
     * 默认 false：相机屏幕继续显示实时画面，不出「已连接至智能设备」弹窗；
     * 用户在监看面板里点「接管相机」后才切成控制模式。
     */
    var takeoverCamera: Boolean
        get() = flag("takeover_camera", false)
        set(v) = putFlag("takeover_camera", v)

    // ===== 通用 =====
    /** 语言跟随系统（中文 / 英文） */
    var languageFollowSystem: Boolean
        get() = flag("lang_follow_system", true)
        set(v) = putFlag("lang_follow_system", v)

    /** 语言选项：跟随系统 / 中文 / English */
    var language: String
        get() = str("language", "跟随系统")
        set(v) = putStr("language", v)

    /** GPS 两种附加方式（gpsEnabled 是总开关，关掉后下面全部不生效） */
    var gpsEnabled: Boolean
        get() = flag("gps_enabled", true)
        set(v) = putFlag("gps_enabled", v)
    var gpsAttachCamera: Boolean
        get() = flag("gps_camera", false)
        set(v) = putFlag("gps_camera", v)
    var gpsAttachImport: Boolean
        get() = flag("gps_import", true)
        set(v) = putFlag("gps_import", v)
    var gpsAttachBle: Boolean
        get() = flag("gps_ble", false)
        set(v) = putFlag("gps_ble", v)

    /** 外观：跟随系统 / 浅色 / 深色 */
    var darkMode: String
        get() = str("dark_mode", "跟随系统")
        set(v) = putStr("dark_mode", v)
    /** 主题色名称，见 DoukThemeColors；「自定义」时看 customAccentHue */
    var themeColor: String
        get() = str("theme_color", "暖橙")
        set(v) = putStr("theme_color", v)
    /** 自定义主题色相 0–360，只有 themeColor = 自定义 时生效 */
    var customAccentHue: Int
        get() = getInt("custom_accent_hue", 24)
        set(v) = putInt("custom_accent_hue", v.coerceIn(0, 360))
    var glassEffect: Boolean
        get() = flag("glass_effect", true)
        set(v) = putFlag("glass_effect", v)

    /** 高级材质卡片不透明度：固定 0.60，设置项已取消 */
    var materialAlpha: Float
        get() = 0.60f
        set(v) = putFloat("material_alpha", 0.60f)

    /** 卡片/底栏背后模糊半径（dp），默认 24dp，上限 40dp */
    var materialBlur: Float
        get() = getFloat("material_blur", 24f).coerceIn(0f, 40f)
        set(v) = putFloat("material_blur", v.coerceIn(0f, 40f))

    /** 界面对比度固定 80%，旧的可调项已取消 */
    var uiContrast: Float
        get() = 0.80f
        set(v) = putFloat("ui_contrast", 0.80f)
    /** 悬浮 Douk 栏 */
    var floatingBar: Boolean
        get() = flag("floating_bar", false)
        set(v) = putFlag("floating_bar", v)

    /** 拍完一张就在应用里显示 */
    var capturePreview: Boolean
        get() = flag("capture_preview", true)
        set(v) = putFlag("capture_preview", v)

    /** 设备页相机实物照片（应用私有目录里的图片路径） */
    var cameraPhotoPath: String
        get() = str("camera_photo", "")
        set(v) = putStr("camera_photo", v)

    /** 图库：每张图打开时都直接取原图（而不是相机侧缩略图） */
    var galleryShowOriginal: Boolean
        get() = flag("gallery_show_original", false)
        set(v) = putFlag("gallery_show_original", v)

    /** 图库默认打开的来源：相机内 / 应用内 / 手机内 */
    var galleryDefaultSource: String
        get() = str("gallery_default_source", "相机内")
        set(v) = putStr("gallery_default_source", v)

    /** 连上相机后在后台预读一批机身照片，打开图库直接有内容 */
    var galleryCameraPreload: Boolean
        get() = flag("gallery_camera_preload", true)
        set(v) = putFlag("gallery_camera_preload", v)

    /** 打开图库时一次性加载全部机身照片；关闭则先显示最新一批，下拉再续 */
    var galleryLoadAll: Boolean
        get() = flag("gallery_load_all", false)
        set(v) = putFlag("gallery_load_all", v)

    /** 图库网格列数：双指缩放连续切换 2 / 3 / 4 / 5 / 6 / 7 列 */
    var galleryColumns: Int
        get() = getInt("gallery_columns", 4).coerceIn(2, 7)
        set(v) = putInt("gallery_columns", v.coerceIn(2, 7))

    /** 图库分批显示的每批张数（默认 60，正好铺满平板两三屏） */
    var galleryPageSize: Int
        get() = getInt("gallery_page_size", 60)
        set(v) = putInt("gallery_page_size", v)

    // ===== 更新 =====
    /** 启动 App 时自动检查一次更新 */
    var checkUpdateOnLaunch: Boolean
        get() = flag("check_update_on_launch", true)
        set(v) = putFlag("check_update_on_launch", v)

    /** 上次启动自动检查时间，用于避免每次启动都请求 */
    var lastAutoUpdateCheckAt: Long
        get() {
            ensure()
            return sp.getLong("last_auto_update_check_at", 0L)
        }
        set(v) {
            ensure()
            sp.edit().putLong("last_auto_update_check_at", v).apply()
        }


    // ===== 控制页参数排序 / 显示 =====
    /** 拍摄参数顺序，key 用英文逗号连接；空串表示用默认顺序。 */
    var paramOrderStill: String
        get() = str("param_order_still", "")
        set(v) = putStr("param_order_still", v)

    /** 视频参数顺序。 */
    var paramOrderVideo: String
        get() = str("param_order_video", "")
        set(v) = putStr("param_order_video", v)

    /** 拍摄页被关掉显示的功能 key。 */
    var paramHiddenStill: String
        get() = str("param_hidden_still", "")
        set(v) = putStr("param_hidden_still", v)

    /** 视频页被关掉显示的功能 key。 */
    var paramHiddenVideo: String
        get() = str("param_hidden_video", "")
        set(v) = putStr("param_hidden_video", v)

    /** 拍摄页自定义卡片分组：id / 名称 / 包含的功能 key，JSON 数组。 */
    var paramGroupsStill: String
        get() = str("param_groups_still", "")
        set(v) = putStr("param_groups_still", v)

    /** 视频页自定义卡片分组。 */
    var paramGroupsVideo: String
        get() = str("param_groups_video", "")
        set(v) = putStr("param_groups_video", v)

    // ===== 资源 =====
    fun lutDir(context: Context): File = File(context.filesDir, "lut").apply { mkdirs() }

    fun importedResources(context: Context): List<File> =
        lutDir(context).listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()

    /**
     * 资源类型：LUT（.cube/.3dl/.look）还是滤镜（尼康 Picture Control .NP3/.NCP/.NP2）。
     * 只按扩展名判断，用户手动改过就以用户的为准。
     */
    fun resourceKind(fileName: String): String {
        val saved = str("res_kind_$fileName", "")
        if (saved.isNotEmpty()) return saved
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return if (ext in setOf("np3", "ncp", "np2")) "滤镜" else "LUT"
    }

    fun setResourceKind(fileName: String, kind: String) {
        putStr("res_kind_$fileName", kind)
    }

    /**
     * 适用范围：通用 / 图片 / 视频。
     * 默认猜测：文件名带 Log / 还原 / Rec709 的当作视频还原 LUT；尼康 Picture Control 当图片滤镜；
     * 其余当通用。用户可以在资源页随时改。
     */
    fun resourceUse(fileName: String): String {
        val saved = str("res_use_$fileName", "")
        if (saved.isNotEmpty()) return saved
        val lower = fileName.lowercase()
        val looksLog = listOf("log", "n-log", "nlog", "slog", "v-log", "还原", "rec709", "rec.709", "hlg")
            .any { lower.contains(it) }
        if (looksLog) return "视频"
        if (resourceKind(fileName) == "滤镜") return "图片"
        return "通用"
    }

    fun setResourceUse(fileName: String, use: String) {
        putStr("res_use_$fileName", use)
    }

    /** 导入滤镜的分类：文件名为 key，默认「未分类」；内置滤镜固定「自带」。 */
    fun filterCategory(fileName: String): String = str("filter_cat_$fileName", "未分类")

    fun setFilterCategory(fileName: String, category: String) {
        putStr("filter_cat_$fileName", category)
    }

    /** 所有已写入的滤镜分类，用于预设导出。 */
    fun filterCategories(): Map<String, String> {
        ensure()
        return sp.all.entries
            .filter { it.key.startsWith("filter_cat_") && it.value is String }
            .associate { it.key.removePrefix("filter_cat_") to (it.value as String) }
    }

    /** 目录递归大小，用于「储存」里展示占用 */
    fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun readableSize(bytes: Long): String = when {
        bytes <= 0 -> "0 MB"
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }
}
