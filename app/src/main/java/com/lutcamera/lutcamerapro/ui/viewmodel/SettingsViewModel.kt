package com.lutcamera.lutcamerapro.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.lutcamera.lutcamerapro.network.CameraConnectController

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("lut_camera_settings", Context.MODE_PRIVATE)

    // ==================== 相机设置 ====================
    var cameraName by mutableStateOf(prefs.getString("cameraName", "Nikon Z 系列相机")!!)
        private set
    var connectionMode by mutableStateOf(prefs.getString("connectionMode", "WiFi AP 直连")!!)
        private set
    var nikonIp by mutableStateOf(prefs.getString("nikonIp", "192.168.1.1")!!)
        private set
    var autoConnect by mutableStateOf(prefs.getBoolean("autoConnect", true))
        private set

    // ==================== 自动处理设置 ====================
    var autoDownload by mutableStateOf(prefs.getBoolean("autoDownload", true))
        private set
    var autoApplyLut by mutableStateOf(prefs.getBoolean("autoApplyLut", true))
        private set
    var autoApplyBorder by mutableStateOf(prefs.getBoolean("autoApplyBorder", false))
        private set
    var autoSave by mutableStateOf(prefs.getBoolean("autoSave", true))
        private set
    var keepOriginalJpg by mutableStateOf(prefs.getBoolean("keepOriginalJpg", false))
        private set
    var autoReconnect by mutableStateOf(prefs.getBoolean("autoReconnect", true))
        private set
    var downloadMode by mutableStateOf(prefs.getString("downloadMode", "原图 JPG")!!)
        private set
    var saveModeName by mutableStateOf(prefs.getString("saveMode", "DUAL")!!)
        private set

    // ==================== 渲染设置 ====================
    var gpuRenderMode by mutableStateOf(prefs.getString("gpuRenderMode", "自动调度")!!)
        private set
    var cacheFormat by mutableStateOf(prefs.getString("cacheFormat", "专属格式 (RGBA16F)")!!)
        private set
    var previewResolution by mutableStateOf(prefs.getString("previewResolution", "高清 (1080P)")!!)
        private set
    // 成片导出尺寸与实时预览解耦；原始 JPG 始终保留相机回传的完整字节。
    var exportResolution by mutableStateOf(prefs.getString("exportResolution", "原始分辨率")!!)
        private set
    var performanceOptimize by mutableStateOf(prefs.getString("performanceOptimize", "均衡模式")!!)
        private set

    // ==================== 省电模式 ====================
    var powerSaveMode by mutableStateOf(prefs.getString("powerSaveMode", "智能省电")!!)
        private set

    // ==================== 其他 ====================
    var themeMode by mutableStateOf(prefs.getString("themeMode", "跟随系统")!!)
        private set

    // ==================== 更新方法 ====================
    fun updateConnectionMode(mode: String) {
        connectionMode = mode
        prefs.edit().putString("connectionMode", mode).apply()
        // 联动底层网络管线
        if (mode == "FTP 热点推流") {
            CameraConnectController.currentMode = CameraConnectController.Mode.FTP_PUSH
        } else {
            CameraConnectController.currentMode = CameraConnectController.Mode.WIRELESS_AP
        }
    }

    fun updateNikonIp(ip: String) {
        nikonIp = ip
        prefs.edit().putString("nikonIp", ip).apply()
    }

    @JvmName("setNikonIpSetting")
    fun setNikonIp(ip: String) = updateNikonIp(ip)

    fun toggleAutoConnect(enabled: Boolean) {
        autoConnect = enabled
        prefs.edit().putBoolean("autoConnect", enabled).apply()
    }

    fun toggleAutoDownload(enabled: Boolean) {
        autoDownload = enabled
        prefs.edit().putBoolean("autoDownload", enabled).apply()
    }

    fun toggleAutoApplyLut(enabled: Boolean) {
        autoApplyLut = enabled
        prefs.edit().putBoolean("autoApplyLut", enabled).apply()
    }

    @JvmName("setAutoApplyLutSetting")
    fun setAutoApplyLut(enabled: Boolean) = toggleAutoApplyLut(enabled)

    fun toggleAutoApplyBorder(enabled: Boolean) {
        autoApplyBorder = enabled
        prefs.edit().putBoolean("autoApplyBorder", enabled).apply()
    }

    @JvmName("setAutoApplyBorderSetting")
    fun setAutoApplyBorder(enabled: Boolean) = toggleAutoApplyBorder(enabled)

    fun toggleAutoSave(enabled: Boolean) {
        autoSave = enabled
        prefs.edit().putBoolean("autoSave", enabled).apply()
    }

    fun toggleKeepOriginalJpg(enabled: Boolean) {
        keepOriginalJpg = enabled
        prefs.edit().putBoolean("keepOriginalJpg", enabled).apply()
    }

    fun updateSaveMode(modeName: String) {
        saveModeName = modeName
        prefs.edit().putString("saveMode", modeName).apply()
    }

    fun setSaveMode(modeKey: String) = updateSaveMode(modeKey)

    fun toggleAutoReconnect(enabled: Boolean) {
        autoReconnect = enabled
        prefs.edit().putBoolean("autoReconnect", enabled).apply()
    }

    fun updateGpuRenderMode(mode: String) {
        gpuRenderMode = mode
        prefs.edit().putString("gpuRenderMode", mode).apply()
    }

    fun updateCacheFormat(format: String) {
        cacheFormat = format
        prefs.edit().putString("cacheFormat", format).apply()
    }

    fun updatePreviewResolution(resolution: String) {
        previewResolution = resolution
        prefs.edit().putString("previewResolution", resolution).apply()
    }

    fun updateExportResolution(resolution: String) {
        exportResolution = resolution
        prefs.edit().putString("exportResolution", resolution).apply()
    }

    fun updatePerformanceOptimize(optimize: String) {
        performanceOptimize = optimize
        prefs.edit().putString("performanceOptimize", optimize).apply()
    }

    fun updatePowerSaveMode(mode: String) {
        powerSaveMode = mode
        prefs.edit().putString("powerSaveMode", mode).apply()
    }
}
