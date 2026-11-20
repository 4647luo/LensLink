package com.lutcamera.lutcamerapro.util

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.lutcamera.lutcamerapro.log.DoukLog
import java.io.File

/**
 * 给已保存到手机的照片补写 GPS。
 *
 * 尼康机身在「连接至智能设备」模式下不一定接收蓝牙 GPS，本机就把手机定位直接写进
 * 刚从机身导入的照片（EXIF GPS 标签），这是不依赖相机固件的兜底方案。
 * MediaStore 保存回传的是 content:// URI，不能直接按文件路径打开，这里先复制到临时文件写 EXIF，
 * 再把写好的字节回写媒体库。
 */
object PhotoGpsStamp {

    fun stamp(context: Context?, pathOrUri: String, latitude: Double, longitude: Double): Boolean {
        if (pathOrUri.startsWith("content://", ignoreCase = true)) {
            return stampContentUri(context, pathOrUri, latitude, longitude)
        }
        val file = File(pathOrUri)
        if (!file.exists() || file.length() == 0L) return false
        return stampFile(file, latitude, longitude)
    }

    private fun stampContentUri(
        context: Context?,
        uriText: String,
        latitude: Double,
        longitude: Double
    ): Boolean {
        if (context == null) return false
        val uri = Uri.parse(uriText)
        var temp: File? = null
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return false
            if (bytes.isEmpty()) return false
            val suffix = uri.lastPathSegment?.substringAfterLast('.', "jpg") ?: "jpg"
            temp = File.createTempFile("gps_", ".$suffix", context.cacheDir)
            temp.writeBytes(bytes)
            if (!stampFile(temp, latitude, longitude)) return false
            val written = temp.readBytes()
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(written) }
                ?: throw java.io.IOException("无法回写照片")
            DoukLog.i("Gps", "URI_STAMP", "uri" to uriText, "lat" to latitude, "lon" to longitude)
            true
        } catch (e: Exception) {
            DoukLog.w("Gps", "URI_STAMP_FAIL", "uri" to uriText, "error" to (e.message ?: "?"))
            false
        } finally {
            runCatching { temp?.delete() }
        }
    }

    private fun stampFile(file: File, latitude: Double, longitude: Double): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val ext = file.extension.lowercase()
        if (ext != "jpg" && ext != "jpeg") return false
        return try {
            val exif = ExifInterface(file.absolutePath)
            exif.setLatLong(latitude, longitude)
            exif.saveAttributes()
            DoukLog.i("Gps", "EXIF_STAMP", "file" to file.name, "lat" to latitude, "lon" to longitude)
            true
        } catch (e: Exception) {
            DoukLog.w("Gps", "EXIF_STAMP_FAIL", "file" to file.name, "error" to (e.message ?: "?"))
            false
        }
    }
}
