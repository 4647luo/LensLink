package com.lutcamera.lutcamerapro.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.lutcamera.lutcamerapro.log.DoukLog
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * 获取当前热点/网络下的真实 IPv4 地址
     */
    fun getLocalIpv4Address(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val sortedInterfaces = interfaces.sortedByDescending { nif ->
                val name = nif.name.lowercase()
                when {
                    name.startsWith("wlan") -> 4
                    name.startsWith("ap") -> 3
                    name.startsWith("swlan") -> 3
                    name.startsWith("rndis") -> 2
                    name.startsWith("eth") -> 1
                    else -> 0
                }
            }

            for (nif in sortedInterfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val addresses = Collections.list(nif.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DoukLog.w("Net", "LOCAL_IP_FAIL", "error" to (e.message ?: "?"))
        }
        return "192.168.1.2"
    }

    /**
     * 获取 WiFi 网关 IP (AP 模式下通常为相机的 IP，如 192.168.122.1 或 192.168.1.1)
     */
    fun getWifiGatewayIp(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcpInfo = wifiManager?.dhcpInfo
            if (dhcpInfo != null && dhcpInfo.gateway != 0) {
                val gateway = dhcpInfo.gateway
                return "${gateway and 0xFF}.${(gateway shr 8) and 0xFF}.${(gateway shr 16) and 0xFF}.${(gateway shr 24) and 0xFF}"
            }
        } catch (e: Exception) {
            DoukLog.w("Net", "GATEWAY_FAIL", "error" to (e.message ?: "?"))
        }
        return "192.168.122.1"
    }

    /**
     * 检测当前是否连接 Wi-Fi
     */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 将应用进程绑定到 Wi-Fi 网络（防止移动蜂窝数据开启时，Android 系统将无外网的相机 Wi-Fi 请求路由至蜂窝数据）
     */
    fun bindToWifi(context: Context): Boolean {
        return try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    cm.bindProcessToNetwork(network)
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 解除 Wi-Fi 网络绑定
     */
    fun unbindWifi(context: Context) {
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {}
    }
}
