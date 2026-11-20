package com.lutcamera.lutcamerapro.network.nikon.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import com.lutcamera.lutcamerapro.MainActivity

/**
 * 相机连接保活服务。
 *
 * 尼康的智能设备模式在相机休眠后仍会接受位置写入，但 Android 会把后台进程冻结；
 * Wi‑Fi / USB 的 PTP 长连接同样怕进程被回收。前台服务把同一进程钉住，
 * [NikonBleGpsClient]、[NikonBleRemoteClient] 和 PTP 心跳都能继续跑。
 * 这个服务本身不持有连接，只负责把进程钉在当前状态。
 *
 * 除了前台服务，这里还拿一把 PARTIAL_WAKE_LOCK：前台服务只提高进程优先级，
 * 不保证 CPU 在深度休眠时继续调度 BLE 回调和定位；熄屏后相机断开、定位停更
 * 多数是 CPU 被挂起导致的。WakeLock 只在本服务存活期间持有，停止连接立即释放。
 */
class DoukBleKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var latestText: String = "正在保持相机连接"

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
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestText = intent?.getStringExtra(EXTRA_TEXT) ?: latestText
        val notification = buildNotification(latestText)
        startForeground(NOTIFICATION_ID, notification)
        acquireWakeLock()
        return START_STICKY
    }

    /**
     * 用户在最近任务里划掉应用时，START_STICKY 不保证立即重建。
     * 这里抓紧进程还活着的窗口再把自己拉起来；失败就交给系统重建。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restart = Intent(this, DoukBleKeepAliveService::class.java)
            .putExtra(EXTRA_TEXT, latestText)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restart)
            } else {
                startService(restart)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LensLink:KeepAlive")
            lock.setReferenceCounted(false)
            // 没有超时：连接断开后 stop() 会立刻释放；超时反而会把长连接晾在后台
            lock.acquire()
            wakeLock = lock
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return builder
            .setContentTitle("连镜 LensLink")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pending)
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

        /** 是否已加入系统电池优化白名单；MIUI 还会额外冻结未授权应用。 */
        fun isBatteryOptimizationIgnored(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }

        /**
         * 申请电池优化白名单。部分 ROM 没有这个系统弹窗，会直接抛 ActivityNotFound；
         * 这时退回到应用详情页，让用户手动把「省电策略」改成「无限制」。
         */
        fun requestIgnoreBatteryOptimizations(context: Context) {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(direct) }.isSuccess) return
            openAppDetails(context)
        }

        /** 打开系统应用详情页：小米的自启动、省电策略都在这一页附近。 */
        fun openAppDetails(context: Context) {
            val details = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(details) }
        }

        fun formatBatteryStatus(context: Context): String =
            if (isBatteryOptimizationIgnored(context)) "已加入电池优化白名单" else "未加入，熄屏后可能被系统冻结"
    }
}
