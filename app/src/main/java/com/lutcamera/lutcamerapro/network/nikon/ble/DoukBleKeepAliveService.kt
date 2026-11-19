package com.lutcamera.lutcamerapro.network.nikon.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 相机连接保活服务。
 *
 * 尼康的智能设备模式在相机休眠后仍会接受位置写入，但 Android 会把后台进程冻结；
 * Wi‑Fi / USB 的 PTP 长连接同样怕进程被回收。前台服务把同一进程钉住，
 * [NikonBleGpsClient]、[NikonBleRemoteClient] 和 PTP 心跳都能继续跑。
 * 这个服务本身不持有连接，只负责把进程钉在当前状态。
 */
class DoukBleKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // 渠道已存在时也更新名字/描述，旧安装升级后文案保持一致
            val channel = NotificationChannel(
                CHANNEL_ID,
                "相机连接保活",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "保持蓝牙 GPS、蓝牙遥控或 Wi‑Fi / USB 相机连接不被系统冻结"
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(intent?.getStringExtra(EXTRA_TEXT) ?: "正在保持相机连接")
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("连镜 LensLink")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "douk_ble_gps"
        private const val NOTIFICATION_ID = 1201
        const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            val intent = Intent(context, DoukBleKeepAliveService::class.java)
                .putExtra(EXTRA_TEXT, text)
            // 后台时 startForegroundService 可能被系统拒绝；不能让保活失败反过来崩溃界面
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DoukBleKeepAliveService::class.java)) }
        }
    }
}
