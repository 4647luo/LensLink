package com.lutcamera.lutcamerapro.util

import androidx.exifinterface.media.ExifInterface
import com.lutcamera.lutcamerapro.log.DoukLog
import java.io.File

/**
 * 给已保存到手机的照片补写 GPS。
 *
 * 尼康机身在「连接至智能设备」模式下不一定接收蓝牙 GPS，本机就把手机定位直接写进
 * 刚从机身导入的照片（EXIF GPS 标签），这是不依赖相机固件的兜底方案。
 */
object PhotoGpsStamp {

    fun stamp(path: String, latitude: Double, longitude: Double): Boolean {
        val file = File(path)
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
