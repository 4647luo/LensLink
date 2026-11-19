package com.lutcamera.lutcamerapro.ui.screens.connect.sdcard

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.lutcamera.lutcamerapro.gpu.GpuLutOffscreenFilter
import com.lutcamera.lutcamerapro.lut.LutData
import com.lutcamera.lutcamerapro.model.BorderState
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

sealed class ImportResult {
    data class Success(val fileName: String, val isRaw: Boolean) : ImportResult()
    data class Failed(val fileName: String, val error: String) : ImportResult()
}

/**
 * 🚀 高性能流水线 SD 读卡器批量导入引擎 (彻底杜绝大批量拉取 OOM 与闪退)
 */
object SdImportProcessor {

    private const val TAG = "SdImportProcessor"
    private const val MAX_RENDER_DIMENSION = 3840 // 4K 超清渲染输出上限
                fun processAndSavePhoto(
        context: Context,
        item: SdPhotoItem,
        saveMode: SdSaveMode,
        autoApplyLut: Boolean,
        currentLut: LutData?,
        lutIntensity: Float
    ): ImportResult {
        val resolver = context.contentResolver

        try {
            // ================= 1. RAW 文件流式直存（零堆内存占用，绝不参与 GPU 渲染）=================
                if (item.isRaw) {
                if (saveMode != SdSaveMode.LUT_ONLY) {
                    val saved = streamSaveRawToMediaStore(context, item.uri, item.name)
                    if (!saved) {
                        return ImportResult.Failed(item.name, "RAW 保存失败 (存储空间不足或权限受限)")
                    }
                }
                return ImportResult.Success(item.name, isRaw = true)
            }

            // ================= 2. JPG 原图流式保存 =================
                if (saveMode == SdSaveMode.DUAL || saveMode == SdSaveMode.ORIGINAL_ONLY) {
                val originalSaved = streamSaveJpgToMediaStore(context, item.uri, item.name, isOriginal = true)
                if (!originalSaved && saveMode == SdSaveMode.ORIGINAL_ONLY) {
                    return ImportResult.Failed(item.name, "原图保存失败")
                }
            }

            // ================= 3. JPG LUT 调色渲染 =================
            if ((saveMode == SdSaveMode.DUAL || saveMode == SdSaveMode.LUT_ONLY) && autoApplyLut && currentLut != null) {
                var inputBmp: Bitmap? = null
                var renderedBmp: Bitmap? = null

                try {
                    // 安全受限解析
                    inputBmp = decodeUriSafely(context, item.uri, MAX_RENDER_DIMENSION)
                    if (inputBmp == null) {
                        return ImportResult.Failed(item.name, "图片解码异常")
                    }

                    // GPU 离屏调色
                    renderedBmp = GpuLutOffscreenFilter.applyLut(
                        originalBitmap = inputBmp,
                        lutData = currentLut,
                        intensity = lutIntensity
                    )

                    // 写入系统相册 (保存为 LUT 成品图)
                    val lutName = "LUT_${item.name}"
                    val saveOk = saveRenderedBitmapToMediaStore(context, renderedBmp, lutName)
                    if (!saveOk) {
                        return ImportResult.Failed(item.name, "成品保存失败")
                    }
                } finally {
                    // 立即显式释放位图，杜绝任何形式的堆内存溢出！
                inputBmp?.recycle()
                    renderedBmp?.recycle()
                }
            }

            return ImportResult.Success(item.name, isRaw = false)

        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "大批量拉取单张内存超限: ${item.name}", oom)
            System.gc()
            return ImportResult.Failed(item.name, "Out of Memory (Skipped)")
        } catch (e: Exception) {
            Log.e(TAG, "拉取处理异常: ${item.name}", e)
            return ImportResult.Failed(item.name, e.message ?: "未知异常")
        }
    }

    /**
     * 流式管道写入 RAW 文件到系统相册 (避开 Bitmap 解码，不占用 JVM 堆内存)
     */
    private fun streamSaveRawToMediaStore(context: Context, sourceUri: Uri, fileName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/x-raw")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/LutCamera_RAW")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val destUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false

                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    context.contentResolver.openOutputStream(destUri)?.use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(destUri, values, null, null)
                true
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "LutCamera_RAW")
                if (!dir.exists()) dir.mkdirs()
                val targetFile = File(dir, fileName)

                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "RAW 流式写入失败: $fileName", e)
            false
        }
    }

    /**
     * 流式管道写入 JPG 原始流     */
    private fun streamSaveJpgToMediaStore(context: Context, sourceUri: Uri, fileName: String, isOriginal: Boolean): Boolean {
        return try {
            val finalName = if (isOriginal) "ORIG_${fileName}" else fileName

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, finalName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/LutCamera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val destUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false

                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    context.contentResolver.openOutputStream(destUri)?.use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(destUri, values, null, null)
                true
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "LutCamera")
                if (!dir.exists()) dir.mkdirs()
                val targetFile = File(dir, finalName)

                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "JPG 原图写入失败: $fileName", e)
            false
        }
    }

    /**
     * 保存调色位图到相册     */
    private fun saveRenderedBitmapToMediaStore(context: Context, bitmap: Bitmap, fileName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/LutCamera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val destUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false

                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(destUri, values, null, null)
                true
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "LutCamera")
                if (!dir.exists()) dir.mkdirs()
                val targetFile = File(dir, fileName)

                FileOutputStream(targetFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "LUT 位图写入失败: $fileName", e)
            false
        }
    }

    /**
     * 严格限制最大边距防止内存爆炸     */
    private fun decodeUriSafely(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val resolver = context.contentResolver

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOptions)
        }

        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

        var sampleSize = 1
        while (boundsOptions.outWidth / sampleSize > maxDimension || boundsOptions.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        }
    }
}


