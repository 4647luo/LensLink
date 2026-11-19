package com.lutcamera.lutcamerapro.model

/**
 * 📷 相机主流品牌定义与默认网络 PTP 配置（当前仅保留尼康）
 */
enum class CameraBrand(
    val brandName: String,
    val shortName: String,
    val defaultModel: String,
    val vendorId: Int,
    val defaultApIp: String,
    val ptpPort: Int,
    val defaultFtpPort: Int,
    val shutterSpeeds: List<String>,
    val apertures: List<String>,
    val isos: List<String>
) {
    NIKON(
        brandName = "尼康 (Nikon)",
        shortName = "Nikon",
        defaultModel = "Nikon Z 系列相机",
        vendorId = 0x04B0,
        defaultApIp = "192.168.1.1",
        ptpPort = 15740,
        defaultFtpPort = 2121,
        shutterSpeeds = listOf(
            "1/32000s", "1/16000s", "1/8000s", "1/4000s", "1/2000s", "1/1000s", "1/500s", "1/250s", "1/125s",
            "1/60s", "1/30s", "1/15s", "1/8s", "1/4s", "1/2s", "1s", "2s", "4s", "8s", "15s", "30s", "Bulb (B门)"
        ),
        apertures = listOf(
            "F1.2", "F1.4", "F1.8", "F2.0", "F2.8", "F3.5", "F4.0", "F4.5", "F5.6", "F6.3", "F8.0", "F11", "F16", "F22", "F32"
        ),
        isos = listOf(
            "ISO 64", "ISO 100", "ISO 125", "ISO 160", "ISO 200", "ISO 400", "ISO 800", "ISO 1600", "ISO 3200",
            "ISO 6400", "ISO 12800", "ISO 25600", "ISO 51200", "ISO 102400"
        )
    );

    val displayName: String get() = brandName
    val englishName: String get() = shortName
    val defaultModels: List<String> get() = listOf(defaultModel)
    val defaultPort: Int get() = ptpPort

    companion object {
        fun fromVendorId(vendorId: Int): CameraBrand? {
            return entries.find { it.vendorId == vendorId }
        }
    }
}

/**
 * 📁 相机机身存储卡中的文件项模型
 */
data class CameraStorageFile(
    val handle: Int,
    val fileName: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val isRaw: Boolean,
    val isDownloaded: Boolean = false,
    /** 尼康走 PTP handle 下载；为空时按 handle 下载 */
    val downloadUrl: String? = null,
    /** 相机机身文件夹名（DCIM 下的 100NCZ 之类），图库按它分相册 */
    val folder: String? = null,
    /** 相机里的保护/锁定标记（PTP ObjectInfo 的 ProtectionStatus） */
    val isLocked: Boolean = false
) {
    val size: Long get() = sizeBytes
    val dateCreated: Long get() = dateModified
    val extension: String
        get() = fileName.substringAfterLast('.', "").lowercase()
    val isVideo: Boolean
        get() = extension in setOf("mov", "mp4")

    val sizeFormatted: String
        get() {
            if (sizeBytes <= 0L) return "--"
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1.0) "%.1f MB".format(mb) else "%d KB".format(sizeBytes / 1024)
        }
}
