package com.lutcamera.lutcamerapro.network

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * 检测本机是否正在跑「个人热点」（SoftAP）。
 *
 * 热点网卡拿不到 ConnectivityManager 里的 Network，因此在 `allNetworks` 里查不到；
 * 反过来，只要某张 wlan/ap 网卡有 IPv4 却不在 `allNetworks` 的链路属性里，就是本机热点。
 *
 * 为什么值得单独查一次：单射频机型做热点时再让 Wi‑Fi 去连相机热点，系统会给一次
 * DHCP、两三秒后把这条件网络收回（日志里是 `teardown wifi` + `WifiNetworkFactory reset`），
 * 相机那边随即断链。提前拦住比连上再断更容易看懂。
 */
object SoftApDetector {

    data class Ap(val iface: String, val ip: String)

    fun active(context: Context?): Ap? {
        val app = context?.applicationContext ?: return null
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        // 已被系统当成「网络」管理的网卡（STA / 双 Wi‑Fi）不在此列，避免把第二个 STA 误判成热点
        val managed = runCatching {
            cm.allNetworks.mapNotNull { cm.getLinkProperties(it)?.interfaceName }.toSet()
        }.getOrNull() ?: return null
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).firstNotNullOfOrNull { nif ->
                if (!nif.isUp || nif.isLoopback) return@firstNotNullOfOrNull null
                val name = nif.name
                val looksHotspot = name.startsWith("wlan1") || name.startsWith("ap") ||
                    name.startsWith("softap") || name.startsWith("swlan")
                if (!looksHotspot) return@firstNotNullOfOrNull null
                if (name in managed) return@firstNotNullOfOrNull null
                val v4 = Collections.list(nif.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress } ?: return@firstNotNullOfOrNull null
                Ap(name, v4.hostAddress ?: return@firstNotNullOfOrNull null)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 形如 `wlan1 192.168.125.60`；没有热点时返回 null。 */
    fun describe(context: Context?): String? = active(context)?.let { "${it.iface} ${it.ip}" }
}
