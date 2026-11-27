package com.lutcamera.lutcamerapro.util

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.lutcamera.lutcamerapro.log.DoukLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 给已保存到手机的照片补写 GPS。
 *
 * 尼康机身在「连接至智能设备」模式下不一定接收蓝牙 GPS，本机就把手机定位直接写进
 * 刚从机身导入的照片（EXIF GPS 标签），这是不依赖相机固件的兜底方案。
 *
 * 两处关键细节：
 * 1) 只写经纬度的照片，在不少看图软件里会被判成「无效定位」——GPSDateStamp/GPSTimeStamp
 *    是 IFD 里判断定位新鲜度的依据，必须一起写；有海拔时补 GPSAltitude。
 * 2) MediaStore 回传的是 content:// URI。以前是「读全文件 → 写临时文件 → 回写整包」，
 *    一张 40MP 的 JPG 要搬三遍 20MB+。现在优先用 ParcelFileDescriptor 打开 "rw"，
 *    交给 ExifInterface 只改 APP1 段；只有原地写失败才退回临时文件。
 */
object PhotoGpsStamp {

    /** ExifInterface 能写 EXIF 的容器：JPEG 与 HEIF/HEIC。PNG/WebP 不在这里处理。 */
    private val WRITABLE_EXT = setOf("jpg", "jpeg", "heic", "heif")

    fun stamp(
        context: Context?,
        pathOrUri: String,
        latitude: Double,
        longitude: Double,
        altitude: Double? = null,
        timestampMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (pathOrUri.startsWith("content://", ignoreCase = true)) {
            return stampContentUri(context, pathOrUri, latitude, longitude, altitude, timestampMillis)
        }
        val file = File(pathOrUri)
        if (!file.exists() || file.length() == 0L) return false
        return stampFile(file, latitude, longitude, altitude, timestampMillis)
    }

    private fun stampContentUri(
        context: Context?,
        uriText: String,
        latitude: Double,
        longitude: Double,
        altitude: Double?,
        timestampMillis: Long
    ): Boolean {
        if (context == null) return false
        val uri = Uri.parse(uriText)
        // 路径 1：原地写。只改 APP1 段，不搬像素数据。
        val inPlace = runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                applyGps(exif, latitude, longitude, altitude, timestampMillis)
                exif.saveAttributes()
            } != null
        }.getOrDefault(false)
        if (inPlace) {
            DoukLog.i("Gps", "URI_STAMP_INPLACE", "uri" to uriText, "lat" to latitude, "lon" to longitude)
            return true
        }
        // 路径 2：回退到临时文件（个别 ROM 的 MediaStore 不支持 fd 原地改）。
        var temp: File? = null
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return false
            if (bytes.isEmpty()) return false
            val suffix = uri.lastPathSegment?.substringAfterLast('.', "jpg") ?: "jpg"
            temp = File.createTempFile("gps_", ".$suffix", context.cacheDir)
            temp.writeBytes(bytes)
            if (!stampFile(temp, latitude, longitude, altitude, timestampMillis)) return false
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

    private fun stampFile(
        file: File,
        latitude: Double,
        longitude: Double,
        altitude: Double?,
        timestampMillis: Long
    ): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val ext = file.extension.lowercase()
        if (ext !in WRITABLE_EXT) return false
        return runCatching {
            val exif = ExifInterface(file.absolutePath)
            applyGps(exif, latitude, longitude, altitude, timestampMillis)
            exif.saveAttributes()
            DoukLog.i(
                "Gps", "EXIF_STAMP", "file" to file.name,
                "lat" to latitude, "lon" to longitude, "alt" to (altitude ?: 0.0)
            )
            true
        }.getOrElse {
            DoukLog.w("Gps", "EXIF_STAMP_FAIL", "file" to file.name, "error" to (it.message ?: "?"))
            false
        }
    }

    /** 经纬度 + 时间戳 + 处理方式一次写完，缺任何一项都可能被下游当成无效定位。 */
    private fun applyGps(
        exif: ExifInterface,
        latitude: Double,
        longitude: Double,
        altitude: Double?,
        timestampMillis: Long
    ) {
        exif.setLatLong(latitude, longitude)
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, gpsDate(timestampMillis))
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, gpsTime(timestampMillis))
        // 手机定位属于 GPS 定位，标出来比留空更规范
        exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, "GPS")
        if (altitude != null && !altitude.isNaN()) exif.setAltitude(altitude)
    }

    /** GPS 时间戳按标准写 UTC（不是本地时区），否则跨时区浏览会整体偏移。 */
    private fun gpsDate(millis: Long): String =
        SimpleDateFormat("yyyy:MM:dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    private fun gpsTime(millis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))
}
