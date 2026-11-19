package com.lutcamera.lutcamerapro.network

import android.content.Context
import android.graphics.Bitmap
import com.lutcamera.lutcamerapro.lut.LutData
import com.lutcamera.lutcamerapro.model.BorderState
import com.lutcamera.lutcamerapro.ui.screens.connect.wired.SaveMode
import kotlinx.coroutines.flow.StateFlow

object CameraConnectController {

    enum class Mode(val title: String) {
        WIRELESS_AP("无线 AP 直连"),
        WIRELESS_STA("无线 STA 局域网"),
        FTP_PUSH("FTP 热点推流"),
        WIRED_USB("USB 线控图传")
    }

    var currentMode: Mode = Mode.WIRELESS_AP

    val ptpClient: NikonPtpClient get() = NikonPtpClient.defaultInstance
    val ftpReceiver: FtpPushReceiver get() = FtpPushReceiver
    val usbPtpClient: UsbPtpClient get() = UsbPtpClient.defaultInstance

    /**
     * 启动相机通信与处理管线
     */
    fun startCameraPipeline(
        context: Context,
        currentLutData: LutData?,
        intensity: Float,
        autoApplyLut: Boolean,
        autoApplyBorder: Boolean = false,
        saveMode: SaveMode = SaveMode.DUAL,
        borderState: BorderState = BorderState(),
        cameraIp: String = "192.168.1.1",
        onPhotoProcessed: (String) -> Unit
    ) {
        stopAllPipelines()

        // 统一配置 FTP 服务器
        ftpReceiver.autoApplyBorder = autoApplyBorder
        ftpReceiver.saveMode = saveMode
        ftpReceiver.borderState = borderState
        ftpReceiver.startListening(
            context = context,
            currentLut = currentLutData,
            intensity = intensity,
            autoApplyLut = autoApplyLut,
            onPhotoProcessed = onPhotoProcessed
        )

        when (currentMode) {
            Mode.WIRELESS_AP, Mode.WIRELESS_STA, Mode.FTP_PUSH -> {
                ptpClient.startNikonWiWatch(
                    context = context,
                    cameraIp = cameraIp,
                    listener = object : NikonPtpClient.NikonEventListener {
                        override fun onPhotoReceived(fileName: String, jpegBytes: ByteArray) {
                            onPhotoProcessed(fileName)
                        }
                    }
                )
            }
            Mode.WIRED_USB -> {
                // USB 模式由 WiredViewModel 直接驱动 UsbPtpClient
            }
        }
    }

    fun stopAllPipelines() {
        ftpReceiver.stopListening()
        ptpClient.stopNikonWatch()
        usbPtpClient.disconnect()
    }
}
