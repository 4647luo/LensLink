package com.lutcamera.lutcamerapro.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.util.Collections
import java.net.NetworkInterface

/**
 * 连接选路：决定这次连接从哪张网卡、用哪个源地址发出去。
 *
 * 相机热点没有外网，不会被系统选成默认网络。只要手机同时有移动数据、VPN 或
 * 第二个 Wi-Fi，`new Socket()` 就会落到别的网络上，相机收不到握手，随后自己
 * 关掉 Wi-Fi —— 用户看到的是「相机上显示连接失败」。
 *
 * 选路优先级：
 * 1. 已经在相机子网的现成 Wi-Fi 网络（最快，绝大多数情况走这里）
 * 2. 主动 requestNetwork 要一个同子网的 Wi-Fi 网络（路由器/系统层把热点藏起来时兜底）
 * 3. 都没有 → 默认路由，并在上层给出「手机不在相机网段」的明确提示
 */
object CameraRouteResolver {

    data class Route(
        val network: Network?,
        val pinnedLocalIp: String?,
        val source: Source,
        val validated: Boolean,
        val localNets: String
    ) {
        enum class Source { EXISTING_NETWORK, REQUESTED_NETWORK, DEFAULT }

        val bound: Boolean get() = network != null

        fun describe(): String =
            "source=${source.name} bound=$bound validated=$validated " +
                "pinned=${pinnedLocalIp ?: "-"} localNets=$localNets"
    }

    /**
     * @param cameraIp 目标相机地址，用来做子网匹配与源地址钉死
     */
    suspend fun resolve(context: Context, cameraIp: String): Route {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        var network: Network? = CameraLanProbe.networkForIp(context, cameraIp)?.network
        // 兜底校验：VPN 网络带 Wi-Fi 传输时也会被子网匹配命中，但它会把包吞进 tun，
        // 相机收不到（日志表现为出口 172.17.x.x + ECONNABORTED）。这类网络直接作废。
        if (network != null) {
            val caps = cm?.getNetworkCapabilities(network)
            val usable = caps != null &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            if (!usable) network = null
        }
        var source = if (network != null) Route.Source.EXISTING_NETWORK else Route.Source.DEFAULT

        if (network == null) {
            network = CameraNetworkBinder.requestCameraNetwork(context, cameraIp)
            if (network != null) source = Route.Source.REQUESTED_NETWORK
        }

        val validated = network?.let {
            cm?.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true

        return Route(
            network = network,
            pinnedLocalIp = localAddressFor(cameraIp),
            source = source,
            validated = validated,
            localNets = CameraLanProbe.localNetworkSummary()
        )
    }

    /** 本机与相机同 /24 的地址；用纯函数算，避免各 ROM 对 NetworkInterface 的差异。 */
    fun localAddressFor(cameraIp: String): String? {
        val candidates = ArrayList<SubnetMath.LocalAddress>()
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                if (!SubnetMath.isWireless(nif.name)) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    addr.hostAddress?.let { candidates.add(SubnetMath.LocalAddress(nif.name, it)) }
                }
            }
        } catch (_: Exception) {
        }
        return SubnetMath.localAddressInSubnet(candidates, cameraIp)
    }
}
