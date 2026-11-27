package com.lutcamera.lutcamerapro.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.lutcamera.lutcamerapro.log.DoukLog

/**
 * 相机连接期间的网络监听。
 *
 * 现在的掉线判断只能等 socket 报错：相机热点自己关了、手机 Wi‑Fi 掉了，
 * 界面上要卡到下一次命令超时才反应过来。这里用系统回调把「网络没了」提前告诉链路层，
 * 立刻走 transportFailed，界面与重连都能马上动。
 *
 * 注意防误报：切换网络（例如换到另一个 Wi‑Fi、临时开 VPN）时也会触发 onLost，
 * 所以丢网后先看当前默认网络还是不是 Wi‑Fi，是就当作漫游，不判掉线。
 */
internal object CameraNetworkWatcher {

    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(context: Context, onLost: (String) -> Unit) {
        if (callback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                val active = runCatching { cm.activeNetwork }.getOrNull()
                val caps = runCatching { cm.getNetworkCapabilities(active) }.getOrNull()
                // 还挂在 Wi‑Fi 上：多半是换了个热点或漫游，不判相机掉线
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return
                DoukLog.w("NikonPtp", "NETWORK_LOST", "active" to (active?.toString() ?: "null"))
                runCatching { onLost("Wi‑Fi 连接已断开") }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onFailure {
                DoukLog.w("NikonPtp", "NETWORK_WATCH_FAIL", "msg" to (it.message ?: "?"))
                return
            }
        manager = cm
        callback = cb
    }

    fun stop() {
        val cm = manager
        val cb = callback
        manager = null
        callback = null
        if (cm != null && cb != null) runCatching { cm.unregisterNetworkCallback(cb) }
    }
}
