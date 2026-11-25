package com.lutcamera.lutcamerapro.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * 相机网络绑定。
 *
 * 相机热点没有外网，Android 不会把它当成默认网络：只要手机同时有移动数据、VPN
 * 或第二个 Wi-Fi，`new Socket()` 就会落到别的网络上去，表现为
 * `ECONNABORTED` 超时，而相机那边等不到 PTP/IP 握手，只好自己关掉 Wi-Fi
 * ——用户看到的就是「相机上显示连接失败，然后关闭 Wi-Fi」。
 *
 * 这里按目标 IP 的子网去「要」一个 Wi-Fi 网络，只接受与相机同子网的那个。
 * 注意不能加 `removeCapability(INTERNET)`：部分机身的热点实测带 internet 能力，
 * 加了过滤反而匹配不到，流量会漂到蜂窝数据上。
 */
object CameraNetworkBinder {

    /** 请求一个与 [targetHost] 同子网的 Wi-Fi 网络；超时或没有匹配网络时返回 null。 */
    suspend fun requestCameraNetwork(
        context: Context,
        targetHost: String,
        timeoutMs: Long = 3_500
    ): Network? {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val callbackRef = AtomicReference<ConnectivityManager.NetworkCallback?>()
        var granted: Network? = null
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build()
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            // 双网手机上另一个 Wi-Fi 也会满足请求，绑错网口会被相机复位
                            if (!matchesSubnet(targetHost, cm.getLinkProperties(network))) return
                            granted = network
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                    callbackRef.set(cb)
                    try {
                        // 只能「被动监听」已有 Wi‑Fi 网络，绝不能用 requestNetwork：
                        // requestNetwork 会让系统为了满足请求去维持/拉起某个 Wi‑Fi，
                        // 3.5 秒超时后 unregister 释放请求时，系统会把这条「没有外网」的
                        // 网络断掉 —— 表现就是「手机刚连上相机 Wi‑Fi 就自己断开，相机报连接失败」。
                        cm.registerNetworkCallback(request, cb)
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    cont.invokeOnCancellation {
                        runCatching { cm.unregisterNetworkCallback(cb) }
                    }
                }
            }
        } finally {
            callbackRef.get()?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            callbackRef.set(null)
        }
        return granted
    }

    /** 相机 IP 与网络任一 IPv4 地址同子网（按前缀掩码比对，前缀钳制 0–32）。 */
    private fun matchesSubnet(targetHost: String, lp: LinkProperties?): Boolean {
        if (lp == null) return false
        val target = runCatching { InetAddress.getByName(targetHost) as? Inet4Address }.getOrNull()
            ?: return true
        val t = ipv4ToInt(target)
        for (la in lp.linkAddresses) {
            val addr = la.address as? Inet4Address ?: continue
            val prefix = la.prefixLength.coerceIn(0, 32)
            val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
            if ((ipv4ToInt(addr) and mask) == (t and mask)) return true
        }
        return false
    }

    private fun ipv4ToInt(addr: Inet4Address): Int {
        val b = addr.address
        return ((b[0].toInt() and 0xff) shl 24) or
            ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or
            (b[3].toInt() and 0xff)
    }
}
