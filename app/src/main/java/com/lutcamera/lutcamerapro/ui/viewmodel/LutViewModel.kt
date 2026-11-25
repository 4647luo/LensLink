package com.lutcamera.lutcamerapro.ui.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lutcamera.lutcamerapro.lut.CubeParser
import com.lutcamera.lutcamerapro.lut.LutData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

import com.lutcamera.lutcamerapro.lut.XmpData
import com.lutcamera.lutcamerapro.lut.XmpParser

data class XmpItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val xmpData: XmpData,
    val localFilePath: String? = null
)

data class LutItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    var name: String,
    var category: String = "Landscape",
    var isFavorite: Boolean = false,
    val lutData: LutData,
    val thumbnailBitmap: Bitmap,
    val localFilePath: String? = null
)

class LutViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>().applicationContext

    companion object {
        private const val PREFS_NAME = "lut_camera_prefs"
        private const val KEY_IMPORTED_LUTS_JSON = "key_imported_luts_json"
        private const val KEY_LAST_LUT_ID = "key_last_lut_id"
        private const val KEY_LAST_LUT_INTENSITY = "key_last_lut_intensity"
    }

    private var isInitialized = false

    var currentXmpData by mutableStateOf<XmpData?>(null)
        private set
    var currentXmpName by mutableStateOf("未选择 XMP")
        private set
    var xmpLibraryList = mutableStateOf<List<XmpItem>>(emptyList())
        private set

    var currentLutId by mutableStateOf("preset_identity")
        private set

    var currentLutName by mutableStateOf("Identity (Original)")
        private set

    var currentLutData by mutableStateOf<LutData>(createIdentityLut())
        private set

    var currentBitmap by mutableStateOf<Bitmap>(createTestBitmap())
        private set

    var lutIntensity by mutableFloatStateOf(0.7f)
        private set

    var hasImportedImage by mutableStateOf(false)
        private set

    var isComparingOriginal by mutableStateOf(false)
        private set

    var isShowGridLines by mutableStateOf(false)
        private set
    var isShowHistogram by mutableStateOf(false)
        private set
    var isHighlightWarning by mutableStateOf(false)
        private set
    var isShadowWarning by mutableStateOf(false)
        private set
    var isZoomed by mutableStateOf(false)
        private set

    var redHistogram by mutableStateOf(FloatArray(256))
        private set
    var greenHistogram by mutableStateOf(FloatArray(256))
        private set
    var blueHistogram by mutableStateOf(FloatArray(256))
        private set

    private val defaultPresetList = listOf(
        LutItem(id = "preset_identity", name = "Identity (Original)", category = "All", isFavorite = false, lutData = createIdentityLut(), thumbnailBitmap = generateThumbnail(createIdentityLut())),
        LutItem(id = "preset_bw", name = "Classic B&W", category = "Portrait", isFavorite = true, lutData = createBwLut(), thumbnailBitmap = generateThumbnail(createBwLut())),
        LutItem(id = "preset_warm", name = "Warm Film", category = "Landscape", isFavorite = true, lutData = createWarmLut(), thumbnailBitmap = generateThumbnail(createWarmLut())),
        LutItem(id = "preset_teal", name = "Teal & Orange", category = "Landscape", isFavorite = true, lutData = createTealOrangeLut(), thumbnailBitmap = generateThumbnail(createTealOrangeLut()))
    )

    var lutLibraryList = mutableStateOf<List<LutItem>>(defaultPresetList)
        private set

    var selectedCategory by mutableStateOf("All")
        private set

    init {
        initStorage()
    }

    fun initStorage() {
        if (isInitialized) return
        isInitialized = true

        viewModelScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedJsonStr = prefs.getString(KEY_IMPORTED_LUTS_JSON, null)
            val customItems = mutableListOf<LutItem>()

            if (!savedJsonStr.isNullOrEmpty()) {
                try {
                    val jsonArray = JSONArray(savedJsonStr)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val id = obj.getString("id")
                        val name = obj.getString("name")
                        val category = obj.optString("category", "custom")
                        val isFav = obj.optBoolean("isFavorite", true)
                        val filePath = obj.getString("filePath")

                        val file = File(filePath)
                        if (file.exists()) {
                            val lutData = file.inputStream().use { stream ->
                                CubeParser.parse(stream)
                            } ?: continue

                            val thumb = generateThumbnail(lutData)
                            customItems.add(
                                LutItem(
                                    id = id,
                                    name = name,
                                    category = category,
                                    isFavorite = isFav,
                                    lutData = lutData,
                                    thumbnailBitmap = thumb,
                                    localFilePath = filePath
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val fullList = defaultPresetList + customItems
            val savedLutId = prefs.getString(KEY_LAST_LUT_ID, "preset_identity")
            val savedIntensity = prefs.getFloat(KEY_LAST_LUT_INTENSITY, 0.7f)

            val matchedItem = fullList.find { it.id == savedLutId } ?: defaultPresetList.first()

            withContext(Dispatchers.Main) {
                lutLibraryList.value = fullList
                lutIntensity = savedIntensity
                currentLutId = matchedItem.id
                currentLutName = matchedItem.name
                currentLutData = matchedItem.lutData
            }
        }
    }

    fun updateCategory(category: String) { selectedCategory = category }
    @JvmName("setCategorySetting")
    fun setCategory(category: String) = updateCategory(category)
    fun toggleGridLines() { isShowGridLines = !isShowGridLines }
    fun toggleHistogram() { isShowHistogram = !isShowHistogram }
    fun toggleHighlightWarning() { isHighlightWarning = !isHighlightWarning }
    fun toggleShadowWarning() { isShadowWarning = !isShadowWarning }
    fun toggleZoom() { isZoomed = !isZoomed }

    fun updateIntensity(intensity: Float) {
        lutIntensity = intensity
        saveLastSelectedState()
    }

    fun updateLutIntensity(intensity: Float) = updateIntensity(intensity)

    fun selectLut(lutId: String) {
        lutLibraryList.value.find { it.id == lutId }?.let { updateSelectedLut(it) }
    }

    fun updateSelectedLut(item: LutItem) {
        currentLutId = item.id
        currentLutName = item.name
        currentLutData = item.lutData
        saveLastSelectedState()
    }

    fun updateSelectedLut(name: String, lutData: LutData) {
        val matched = lutLibraryList.value.find { it.name == name || it.lutData === lutData }
        currentLutId = matched?.id ?: "preset_identity"
        currentLutName = name
        currentLutData = lutData
        saveLastSelectedState()
    }

    fun toggleFavorite(lutId: String) {
        lutLibraryList.value = lutLibraryList.value.map { item ->
            if (item.id == lutId) item.copy(isFavorite = !item.isFavorite) else item
        }
        saveCustomLutsToPrefs()
    }

    fun renameLut(lutId: String, newName: String) {
        lutLibraryList.value = lutLibraryList.value.map { item ->
            if (item.id == lutId) item.copy(name = newName) else item
        }
        if (currentLutId == lutId) {
            currentLutName = newName
        }
        saveCustomLutsToPrefs()
    }

    fun updateLutCategory(lutId: String, newCategory: String) {
        lutLibraryList.value = lutLibraryList.value.map { item ->
            if (item.id == lutId) item.copy(category = newCategory) else item
        }
        saveCustomLutsToPrefs()
    }

    fun deleteLut(lutId: String) {
        val target = lutLibraryList.value.find { it.id == lutId }
        target?.localFilePath?.let { path ->
            try { File(path).delete() } catch (e: Exception) { e.printStackTrace() }
        }

        lutLibraryList.value = lutLibraryList.value.filter { it.id != lutId }

        if (currentLutId == lutId) {
            val defaultItem = defaultPresetList.first()
            updateSelectedLut(defaultItem)
        }

        saveCustomLutsToPrefs()
    }

    fun updateBitmap(bitmap: Bitmap) {
        currentBitmap = bitmap
        hasImportedImage = true
        calculateHistogram(bitmap)
    }

    private fun calculateHistogram(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            val rCounts = IntArray(256)
            val gCounts = IntArray(256)
            val bCounts = IntArray(256)

            val width = bitmap.width
            val height = bitmap.height
            val stepX = (width / 200).coerceAtLeast(1)
            val stepY = (height / 200).coerceAtLeast(1)

            for (y in 0 until height step stepY) {
                for (x in 0 until width step stepX) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    rCounts[r]++
                    gCounts[g]++
                    bCounts[b]++
                }
            }

            val maxCount = maxOf(rCounts.maxOrNull() ?: 1, gCounts.maxOrNull() ?: 1, bCounts.maxOrNull() ?: 1).toFloat()
            val rNormalized = FloatArray(256) { rCounts[it] / maxCount }
            val gNormalized = FloatArray(256) { gCounts[it] / maxCount }
            val bNormalized = FloatArray(256) { bCounts[it] / maxCount }

            withContext(Dispatchers.Main) {
                redHistogram = rNormalized
                greenHistogram = gNormalized
                blueHistogram = bNormalized
            }
        }
    }

    fun updateComparingOriginal(comparing: Boolean) {
        isComparingOriginal = comparing
    }

    fun importAndAddLutToLibrary(context: Context, uri: Uri) {
        importAndAddLutToLibrary(uri)
    }

    fun importAndAddLutToLibrary(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            var temporaryFile: File? = null
            var importedFile: File? = null
            var committed = false
            try {
                val rawFileName = getFileName(context, uri) ?: "imported.cube"
                val decodedFileName = try { URLDecoder.decode(rawFileName, "UTF-8") } catch (e: Exception) { rawFileName }
                val cleanName = decodedFileName.substringBeforeLast(".").ifBlank { "Imported LUT" }

                val lutsDir = File(context.filesDir, "custom_luts")
                if (!lutsDir.isDirectory && !lutsDir.mkdirs()) throw java.io.IOException("无法创建 LUT 保存目录")

                val newLutId = "custom_${java.util.UUID.randomUUID()}"
                val localFile = File(lutsDir, "$newLutId.cube")
                importedFile = localFile
                val stagingFile = File(lutsDir, "$newLutId.cube.part")
                temporaryFile = stagingFile

                val source = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法打开所选 LUT 文件")
                source.use { input ->
                    java.io.FileOutputStream(stagingFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val length = input.read(buffer)
                            if (length == -1) break
                            total += length
                            if (total > CubeParser.MAX_FILE_BYTES) throw CubeParser.ParseException("CUBE 文件超过 64 MiB 限制")
                            output.write(buffer, 0, length)
                        }
                        output.fd.sync()
                    }
                }

                val parsedLut = stagingFile.inputStream().use(CubeParser::parseOrThrow)

                val thumb = generateThumbnail(parsedLut)
                if (!stagingFile.renameTo(localFile)) throw java.io.IOException("无法保存已验证的 LUT 文件")
                val newItem = LutItem(
                    id = newLutId,
                    name = cleanName,
                    category = "custom",
                    isFavorite = true,
                    lutData = parsedLut,
                    thumbnailBitmap = thumb,
                    localFilePath = localFile.absolutePath
                )

                withContext(Dispatchers.Main) {
                    committed = true
                    lutLibraryList.value = lutLibraryList.value + newItem
                    updateSelectedLut(newItem)
                    saveCustomLutsToPrefs()
                    Toast.makeText(context, "已导入并保存 LUT: $cleanName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "导入失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                temporaryFile?.delete()
                if (!committed) importedFile?.delete()
            }
        }
    }

    fun updateSelectedXmp(item: XmpItem?) {
        currentXmpData = item?.xmpData
        currentXmpName = item?.name ?: "未选择 XMP"
    }

    fun importXmpPreset(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rawFileName = getFileName(context, uri) ?: "preset.xmp"
                val decodedFileName = try { URLDecoder.decode(rawFileName, "UTF-8") } catch (e: Exception) { rawFileName }
                val cleanName = decodedFileName.substringBeforeLast(".")

                val parsedXmp = context.contentResolver.openInputStream(uri)?.use { stream ->
                    XmpParser.parse(stream, cleanName)
                }

                if (parsedXmp != null) {
                    val item = XmpItem(name = parsedXmp.title, xmpData = parsedXmp)
                    withContext(Dispatchers.Main) {
                        xmpLibraryList.value = xmpLibraryList.value + item
                        updateSelectedXmp(item)
                        Toast.makeText(context, "✅ 成功导入 XMP 调色预设: ${parsedXmp.title}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "⚠️ 无法识别的 XMP 预设文件", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "导入 XMP 失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveCustomLutsToPrefs() {
        viewModelScope.launch(Dispatchers.IO) {
            val customItems = lutLibraryList.value.filter { it.localFilePath != null }
            val jsonArray = JSONArray()

            for (item in customItems) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("category", item.category)
                    put("isFavorite", item.isFavorite)
                    put("filePath", item.localFilePath)
                }
                jsonArray.put(obj)
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_IMPORTED_LUTS_JSON, jsonArray.toString()).apply()
        }
    }

    private fun saveLastSelectedState() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_LUT_ID, currentLutId)
            .putFloat(KEY_LAST_LUT_INTENSITY, lutIntensity)
            .apply()
    }

    fun saveCurrentLutImage(context: Context, callback: (Boolean) -> Unit) {
        if (!hasImportedImage) {
            Toast.makeText(context, "Please import photo first", Toast.LENGTH_SHORT).show()
            callback(false)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val filteredBmp = try {
                    com.lutcamera.lutcamerapro.gpu.GpuLutOffscreenFilter.applyLut(
                        originalBitmap = currentBitmap,
                        lutData = currentLutData,
                        intensity = lutIntensity,
                        xmpData = currentXmpData
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    applyLutToBitmap(currentBitmap, currentLutData, lutIntensity)
                }

                val filename = "LUT_${System.currentTimeMillis()}.jpg"
                val contentResolver = context.contentResolver

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LutCameraPro")
                }
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use { fos ->
                        filteredBmp.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "已成功保存到相册", Toast.LENGTH_SHORT).show()
                    callback(true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "保存失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    callback(false)
                }
            }
        }
    }

    private fun applyLutToBitmap(source: Bitmap, lutData: LutData, intensity: Float): Bitmap {
        val width = source.width
        val height = source.height
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val size = lutData.size
        val lut = lutData.data

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val red = AndroidColor.red(pixel) / 255.0f
            val green = AndroidColor.green(pixel) / 255.0f
            val blue = AndroidColor.blue(pixel) / 255.0f

            val rVal = red * (size - 1)
            val gVal = green * (size - 1)
            val bVal = blue * (size - 1)

            val rIndex = rVal.toInt().coerceIn(0, size - 2)
            val gIndex = gVal.toInt().coerceIn(0, size - 2)
            val bIndex = bVal.toInt().coerceIn(0, size - 2)

            val rf = rVal - rIndex
            val gf = gVal - gIndex
            val bf = bVal - bIndex

            val outR = trilinearSample(lut, size, rIndex, gIndex, bIndex, rf, gf, bf, 0)
            val outG = trilinearSample(lut, size, rIndex, gIndex, bIndex, rf, gf, bf, 1)
            val outB = trilinearSample(lut, size, rIndex, gIndex, bIndex, rf, gf, bf, 2)

            val finalR = red * (1 - intensity) + outR * intensity
            val finalG = green * (1 - intensity) + outG * intensity
            val finalB = blue * (1 - intensity) + outB * intensity

            pixels[i] = AndroidColor.rgb(
                (finalR * 255).toInt().coerceIn(0, 255),
                (finalG * 255).toInt().coerceIn(0, 255),
                (finalB * 255).toInt().coerceIn(0, 255)
            )
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun generateThumbnail(lutData: LutData): Bitmap {
        val width = 120
        val height = 90
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (x in 0 until width) {
            val r = (x / width.toFloat() * 255).toInt()
            val b = 255 - r
            paint.color = AndroidColor.rgb(r, 110, b)
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), paint)
        }
        return applyLutToBitmap(bitmap, lutData, 1.0f)
    }

    private fun getLutValue(lut: FloatArray, size: Int, r: Int, g: Int, b: Int, channel: Int): Float {
        val idx = (b * size * size + g * size + r) * 3 + channel
        return if (idx in lut.indices) lut[idx] else 0f
    }

    private fun trilinearSample(lut: FloatArray, size: Int, r: Int, g: Int, b: Int, rf: Float, gf: Float, bf: Float, channel: Int): Float {
        val c000 = getLutValue(lut, size, r, g, b, channel)
        val c100 = getLutValue(lut, size, r + 1, g, b, channel)
        val c010 = getLutValue(lut, size, r, g + 1, b, channel)
        val c110 = getLutValue(lut, size, r + 1, g + 1, b, channel)
        val c001 = getLutValue(lut, size, r, g, b + 1, channel)
        val c101 = getLutValue(lut, size, r + 1, g, b + 1, channel)
        val c011 = getLutValue(lut, size, r, g + 1, b + 1, channel)
        val c111 = getLutValue(lut, size, r + 1, g + 1, b + 1, channel)

        val c00 = c000 * (1 - rf) + c100 * rf
        val c10 = c010 * (1 - rf) + c110 * rf
        val c01 = c001 * (1 - rf) + c101 * rf
        val c11 = c011 * (1 - rf) + c111 * rf

        val c0 = c00 * (1 - gf) + c10 * gf
        val c1 = c01 * (1 - gf) + c11 * gf

        return c0 * (1 - bf) + c1 * bf
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) { name = cursor.getString(nameIndex) }
            }
        } catch (e: Exception) { e.printStackTrace() }
        if (name == null) { name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.cube" }
        return name
    }

    private fun createIdentityLut(): LutData {
        val size = 16
        val data = FloatArray(size * size * size * 3)
        var index = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    data[index++] = r / (size - 1).toFloat()
                    data[index++] = g / (size - 1).toFloat()
                    data[index++] = b / (size - 1).toFloat()
                }
            }
        }
        return LutData("Identity", size, data)
    }

    private fun createBwLut(): LutData {
        val size = 16
        val data = FloatArray(size * size * size * 3)
        var index = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val floatR = r / (size - 1).toFloat()
                    val floatG = g / (size - 1).toFloat()
                    val floatB = b / (size - 1).toFloat()
                    val gray = 0.2126f * floatR + 0.7152f * floatG + 0.0722f * floatB
                    data[index++] = gray; data[index++] = gray; data[index++] = gray
                }
            }
        }
        return LutData("Classic B&W", size, data)
    }

    private fun createWarmLut(): LutData {
        val size = 16
        val data = FloatArray(size * size * size * 3)
        var index = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val floatR = r / (size - 1).toFloat()
                    val floatG = g / (size - 1).toFloat()
                    val floatB = b / (size - 1).toFloat()
                    data[index++] = (floatR + 0.2f).coerceAtMost(1f)
                    data[index++] = floatG
                    data[index++] = (floatB - 0.1f).coerceAtLeast(0f)
                }
            }
        }
        return LutData("Warm Film", size, data)
    }

    private fun createTealOrangeLut(): LutData {
        val size = 16
        val data = FloatArray(size * size * size * 3)
        var index = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val floatR = r / (size - 1).toFloat()
                    val floatG = g / (size - 1).toFloat()
                    val floatB = b / (size - 1).toFloat()
                    data[index++] = (floatR + 0.15f).coerceAtMost(1f)
                    data[index++] = floatG
                    data[index++] = (floatB + 0.15f).coerceAtMost(1f)
                }
            }
        }
        return LutData("Teal & Orange", size, data)
    }

    private fun createTestBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (x in 0 until 800) {
            val r = (x / 800f * 255).toInt()
            val b = 255 - r
            paint.color = AndroidColor.rgb(r, 100, b)
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 600f, paint)
        }
        return bitmap
    }
}

