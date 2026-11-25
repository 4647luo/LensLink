package com.lutcamera.lutcamerapro.gpu

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import com.lutcamera.lutcamerapro.log.Log
import androidx.exifinterface.media.ExifInterface
import com.lutcamera.lutcamerapro.lut.LutData
import com.lutcamera.lutcamerapro.lut.XmpData
import com.lutcamera.lutcamerapro.model.BorderState
import com.lutcamera.lutcamerapro.ui.screens.connect.wired.SaveMode
import com.lutcamera.lutcamerapro.util.BoundedIo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** Saves untouched camera JPEGs separately from rendered, orientation-corrected exports. */
object ImageAutoProcessor {
    private const val TAG = "ImageAutoProcessor"
    private val processingMutex = Mutex()

    /**
     * True means all requested outputs were published. DUAL preserves its original if
     * rendering fails later. Callbacks receive published content URIs, including partial success.
     */
    suspend fun processIncomingCameraPhoto(
        context: Context,
        imageStream: InputStream,
        currentLut: LutData?,
        intensity: Float,
        autoApplyLut: Boolean,
        autoApplyBorder: Boolean,
        saveMode: SaveMode,
        borderState: BorderState? = null,
        currentXmp: XmpData? = null,
        sourceFileName: String? = null,
        originalRelativePath: String = "Pictures/LutCameraPro/Originals",
        originalMaxLongEdge: Int? = null,
        onOriginalSaved: ((String) -> Unit)? = null,
        onOutputSaved: ((String) -> Unit)? = null,
        onProcessingError: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        processingMutex.withLock {
            val ownedBitmaps = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<Bitmap, Boolean>()
            )
            fun own(bitmap: Bitmap): Bitmap = bitmap.also { ownedBitmaps.add(it) }
            try {
                // 相机 JPEG 正常只有 5–30MB；上限只挡匿名 FTP / 异常文件，不把 200MB 全读进来。
                val imageBytes = BoundedIo.readBytesWithLimit(imageStream, BoundedIo.MAX_FTP_BYTES)
                require(PhotoExportPolicy.isSupportedJpeg(imageBytes, sourceFileName)) {
                    "当前成片处理仅支持完整 JPG，RAW/HEIF 请使用原始文件下载"
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码相机照片" }
                require(PhotoExportPolicy.isPlausibleInput(bounds.outWidth, bounds.outHeight)) {
                    "照片尺寸异常（${bounds.outWidth}×${bounds.outHeight}）"
                }
                val originalExif = try {
                    ExifInterface(ByteArrayInputStream(imageBytes))
                } catch (e: IOException) {
                    Log.w(TAG, "照片没有可读取的 EXIF", e)
                    null
                }
                val timestamp = System.currentTimeMillis()
                val plan = PhotoExportPolicy.plan(saveMode.name)
                coroutineContext.ensureActive()
                if (plan.saveOriginal) {
                    val uri = publishToGallery(
                        context, PhotoExportPolicy.originalName(sourceFileName, timestamp),
                        originalRelativePath
                    ) { pendingUri ->
                        (context.contentResolver.openOutputStream(pendingUri)
                            ?: throw IOException("无法写入原始 JPG")).use { output ->
                            if (originalMaxLongEdge != null && originalMaxLongEdge > 0) {
                                // 「仅缩略图」：只保存长边受限的预览，避免 45MP 原图占满手机。
                                val sample = decodeSampledForLongEdge(imageBytes, originalMaxLongEdge)
                                var scaled: Bitmap? = null
                                try {
                                    scaled = scaleToLongEdge(sample, originalMaxLongEdge)
                                    check(scaled.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                                        "缩略图编码失败"
                                    }
                                } finally {
                                    if (scaled != null && scaled !== sample && !scaled.isRecycled) scaled.recycle()
                                    if (!sample.isRecycled) sample.recycle()
                                }
                            } else {
                                output.write(imageBytes)
                            }
                            output.flush()
                        }
                    }
                    notifySaved(onOriginalSaved, uri)
                }
                if (!plan.saveOutput) return@withLock true

                coroutineContext.ensureActive()
                // Live-view quality must never silently lower a saved photograph's quality.
                val exportResolution = context.getSharedPreferences("lut_camera_settings", Context.MODE_PRIVATE)
                    .getString("exportResolution", PhotoExportPolicy.ORIGINAL_RESOLUTION)
                    ?: PhotoExportPolicy.ORIGINAL_RESOLUTION
                val orientation = originalExif?.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                ) ?: ExifInterface.ORIENTATION_NORMAL
                val swapsAxes = orientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
                val uprightWidth = if (swapsAxes) bounds.outHeight else bounds.outWidth
                val uprightHeight = if (swapsAxes) bounds.outWidth else bounds.outHeight
                val target = PhotoExportPolicy.targetSize(uprightWidth, uprightHeight, exportResolution)
                val decodeTargetWidth = if (swapsAxes) target.height else target.width
                val decodeTargetHeight = if (swapsAxes) target.width else target.height
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = PhotoExportPolicy.sampleSize(
                        bounds.outWidth, bounds.outHeight, decodeTargetWidth, decodeTargetHeight
                    )
                }
                var bitmap = own(BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
                    ?: throw IOException("照片解码失败"))
                bitmap = own(normalizeOrientation(bitmap, orientation))
                if (bitmap.width != target.width || bitmap.height != target.height) {
                    bitmap = own(Bitmap.createScaledBitmap(bitmap, target.width, target.height, true))
                }
                // Release larger intermediates before GPU read-back and border allocations.
                ownedBitmaps.filter { it !== bitmap }.forEach { if (!it.isRecycled) it.recycle() }
                coroutineContext.ensureActive()

                if (autoApplyLut && (currentLut != null || currentXmp != null)) {
                    val rendered = own(GpuLutOffscreenFilter.applyLut(
                        originalBitmap = bitmap,
                        lutData = currentLut,
                        intensity = intensity.coerceIn(0f, 1f),
                        xmpData = currentXmp
                    ))
                    if (rendered !== bitmap) {
                        bitmap.recycle()
                        bitmap = rendered
                    }
                }
                if (autoApplyBorder) {
                    requireNotNull(borderState) { "未选择成片边框" }
                    val bordered = own(WatermarkExportEngine.generateWatermark(bitmap, originalExif, borderState)
                        ?: throw IOException("边框生成失败"))
                    if (bordered !== bitmap) {
                        bitmap.recycle()
                        bitmap = bordered
                    }
                }
                coroutineContext.ensureActive()
                val outputUri = publishToGallery(
                    context, "LUT_PRO_$timestamp.jpg", "Pictures/LutCameraPro/Edited"
                ) { pendingUri ->
                    (context.contentResolver.openOutputStream(pendingUri)
                        ?: throw IOException("无法写入成片")).use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) { "成片编码失败" }
                        output.flush()
                    }
                    (context.contentResolver.openFileDescriptor(pendingUri, "rw")
                        ?: throw IOException("无法写入成片拍摄参数")).use { descriptor ->
                        val targetExif = ExifInterface(descriptor.fileDescriptor)
                        if (originalExif != null) copyExifAttributes(originalExif, targetExif)
                        targetExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                        targetExif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, bitmap.width.toString())
                        targetExif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, bitmap.height.toString())
                        targetExif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, bitmap.width.toString())
                        targetExif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, bitmap.height.toString())
                        targetExif.saveAttributes()
                    }
                }
                notifySaved(onOutputSaved, outputUri)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "照片未全部处理成功；已保存的原图不会删除", e)
                notifyError(onProcessingError, e.message ?: "照片处理或相册保存失败，请重试")
                false
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "照片尺寸超出当前可用内存；已保存的原图不会删除", e)
                notifyError(onProcessingError, "照片超出当前可用内存，请选择 1080P 成片后重试")
                false
            } finally {
                ownedBitmaps.forEach { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    private fun normalizeOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                else -> return source
            }
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** 「仅缩略图」用：按长边目标先做采样解码，避免 45MP 原图直接进内存。 */
    private fun decodeSampledForLongEdge(bytes: ByteArray, maxLongEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码相机照片" }
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / (sample * 2) >= maxLongEdge * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("缩略图解码失败")
    }

    private fun scaleToLongEdge(source: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= maxLongEdge) return source
        val ratio = maxLongEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun publishToGallery(context: Context, fileName: String, relativePath: String, write: (Uri) -> Unit): Uri {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }) ?: throw IOException("无法创建相册文件")
        try {
            write(uri)
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null) > 0) { "无法完成相册保存" }
            return uri
        } catch (failure: Throwable) {
            try { resolver.delete(uri, null, null) } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private fun notifySaved(callback: ((String) -> Unit)?, uri: Uri) {
        try { callback?.invoke(uri.toString()) } catch (e: Exception) {
            // The file is published: a UI notification must not trigger a duplicate retry.
            Log.e(TAG, "照片已保存，但状态通知失败", e)
        }
    }

    private fun notifyError(callback: ((String) -> Unit)?, message: String) {
        try { callback?.invoke(message) } catch (e: Exception) {
            Log.e(TAG, "照片处理失败，错误通知未完成", e)
        }
    }

    private fun copyExifAttributes(sourceExif: ExifInterface, targetExif: ExifInterface) {
        val attributes = arrayOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_FLASH,
            ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_COLOR_SPACE
        )
        attributes.forEach { tag -> sourceExif.getAttribute(tag)?.let { targetExif.setAttribute(tag, it) } }
    }
}

