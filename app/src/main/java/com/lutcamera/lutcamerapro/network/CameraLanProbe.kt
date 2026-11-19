package com.lutcamera.lutcamerapro.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

/**
 * 相机局域网探测。
 *
 * 尼康相机的 PTP/IP 端口固定 15740，但机身 IP 不固定：AP 直连时可能是
 * 192.168.1.1，也可能是 192.168.122.1、192.168.125.x 之类的网段（取决于机型/固件）。
 * 手机侧也可能同时连着两个 Wi-Fi（家用路由 + 相机热点），所以写死一个 IP 一定会失败。
 *
 * 参考 aero-shutter 的 DetectAll：相机自己开热点时它就是该网段的网关/DHCP 服务器，
 * 所以先把「当前 Wi-Fi 的默认网关、DHCP 服务器、相机默认 IP、历史 IP」并发探一遍，
 * 谁先回就连谁；都没有再去扫 /24 网段。历史 IP 不能串行探，否则一个失效 IP
 * 会白白拖慢后面的真实地址。
 */
object CameraLanProbe {

    const val PTP_PORT = 15740

    /** 探测到的一台相机。 */
    data class Found(
        val ip: String,
        val iface: String,
        val rttMs: Int
    )

    /** 单个地址端口是否可连（相机活着才返回 true）。 */
    fun isOpen(ip: String, timeoutMs: Int = 800): Boolean = isOpen(null, ip, timeoutMs)

    /** 绑定到指定 Wi-Fi 网络探测；network 为 null 时走系统默认路由。 */
    private fun isOpen(network: Network?, ip: String, timeoutMs: Int): Boolean {
        return try {
            val socket = network?.socketFactory?.createSocket() ?: Socket()
            socket.use { s ->
                s.connect(InetSocketAddress(ip, PTP_PORT), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 连接前优先探测的地址：当前局域网网关 / DHCP 服务器 + 历史 IP + 尼康默认 IP。
     *
     * 相机开热点时网关就是相机；手机开热点时网关是手机，探一下最多浪费一个 300ms
     * 的并发窗口，不会拖慢真实相机地址。
     */
    fun priorityTargets(context: Context, known: List<String> = emptyList()): List<String> {
        val out = LinkedHashSet<String>()
        out.addAll(gatewayAddresses(context))
        known.map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it) }
        // Nikon 机身默认 AP 地址；不同机型还会走网关，两个都并发探。
        out.add("192.168.1.1")
        out.add("192.168.0.1")
        return out.filter { isValidIpv4(it) }
    }

    /** 当前 Wi-Fi / 有线网络的默认网关与 DHCP 服务器地址。 */
    private fun gatewayAddresses(context: Context): List<String> = activeLanNetworks(context)
        .mapNotNull { it.network }
        .distinct()
        .flatMap { network -> gatewayAddresses(context, network) }
        .distinct()

    private fun gatewayAddresses(context: Context, network: Network): List<String> {
        val out = ArrayList<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return emptyList()
            val lp = cm.getLinkProperties(network) ?: return emptyList()
            val selfIps = lp.linkAddresses.mapNotNull { (it.address as? Inet4Address)?.hostAddress }
            for (route in lp.routes) {
                if (!route.isDefaultRoute) continue
                val gateway = (route.gateway as? Inet4Address)?.hostAddress ?: continue
                if (gateway !in selfIps && isValidIpv4(gateway)) out.add(gateway)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val dhcp = (lp.dhcpServerAddress as? Inet4Address)?.hostAddress
                if (dhcp != null && dhcp !in selfIps && isValidIpv4(dhcp)) out.add(dhcp)
            }
        } catch (_: Exception) {
        }
        return out
    }

    /**
     * 扫描本机所有 IPv4 网段里的 15740 端口。
     *
     * @param onProgress 已扫描地址数 / 总数，用于界面显示进度
     * @return 命中的相机列表（按响应速度排序）
     */
    suspend fun scan(
        context: Context,
        timeoutMs: Int = 300,
        preferred: List<String> = emptyList(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Found> = withContext(Dispatchers.IO) {
        val nets = activeLanNetworks(context)
        // 先并发探「网关/历史 IP/默认 IP」：相机 IP 多数时候不变，命中就不必扫 254 个地址。
        // 这里必须并发，串行探 5 个失效历史 IP 会白白多花 1.5 秒。
        val preferredHits = probeCandidates(context, preferred, timeoutMs, nets)
        if (preferredHits.isNotEmpty()) return@withContext preferredHits

        // 没有 ConnectivityManager 网络时回退到网卡列表；有网络时按每个网络各自扫描，
        // 这样手机同时连着家用 Wi-Fi 和相机热点也不会扫错网段。
        val scanNets: List<LanNet> = if (nets.isNotEmpty()) nets else localSubnets().map {
            LanNet(it.iface, it.ip, 24, null)
        }
        if (scanNets.isEmpty()) return@withContext emptyList()

        val candidates = ArrayList<Candidate>()
        for (net in scanNets) {
            val prefix = net.ip.split('.').take(3).joinToString(".")
            val selfLast = net.ip.split('.').last().toIntOrNull() ?: -1
            val order = (1..254).sortedBy { last ->
                when (last) {
                    1 -> 0
                    2 -> 1
                    100 -> 2
                    selfLast -> 900
                    else -> 10
                }
            }
            for (last in order) {
                if (last == selfLast) continue
                candidates.add(Candidate("$prefix.$last", net))
            }
        }

        val found = Collections.synchronizedList(ArrayList<Found>())
        val total = candidates.size
        var done = 0
        // 每批 96 个并发；命中第一台就停，不再把剩余网段扫完。
        for (chunk in candidates.chunked(96)) {
            if (found.isNotEmpty()) break
            coroutineScope {
                for (candidate in chunk) {
                    launch {
                        val start = System.currentTimeMillis()
                        if (isOpen(candidate.net.network, candidate.ip, timeoutMs)) {
                            found.add(Found(candidate.ip, candidate.net.iface,
                                (System.currentTimeMillis() - start).toInt()))
                        }
                    }
                }
            }
            done += chunk.size
            if (found.isEmpty()) onProgress(done, total)
        }
        found.sortedBy { it.rttMs }
    }

    /** 向后兼容的旧入口；没有 Context 时只能走系统默认路由。 */
    suspend fun scan(
        timeoutMs: Int = 300,
        preferred: List<String> = emptyList(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Found> = withContext(Dispatchers.IO) {
        val preferredHits = probeCandidates(null, preferred, timeoutMs, emptyList())
        if (preferredHits.isNotEmpty()) return@withContext preferredHits
        val subnets = localSubnets()
        if (subnets.isEmpty()) return@withContext emptyList()
        // 简化回退：按网卡地址生成候选，走默认路由。
        val candidates = subnets.flatMap { sub ->
            val prefix = sub.ip.split('.').take(3).joinToString(".")
            val selfLast = sub.ip.split('.').last().toIntOrNull() ?: -1
            (1..254).filter { it != selfLast }.map { Candidate("$prefix.$it", LanNet(sub.iface, sub.ip, 24, null)) }
        }
        val found = Collections.synchronizedList(ArrayList<Found>())
        for (chunk in candidates.chunked(96)) {
            if (found.isNotEmpty()) break
            coroutineScope {
                for (candidate in chunk) {
                    launch {
                        val start = System.currentTimeMillis()
                        if (isOpen(null, candidate.ip, timeoutMs)) {
                            found.add(Found(candidate.ip, candidate.net.iface,
                                (System.currentTimeMillis() - start).toInt()))
                        }
                    }
                }
            }
            onProgress(found.size, candidates.size)
        }
        found.sortedBy { it.rttMs }
    }

    /** 并发探测一组候选地址；命中多个时按 RTT 排序。 */
    private suspend fun probeCandidates(
        context: Context?,
        ips: List<String>,
        timeoutMs: Int,
        nets: List<LanNet>
    ): List<Found> = coroutineScope {
        val clean = ips.map { it.trim() }.filter { it.isNotEmpty() && isValidIpv4(it) }.distinct()
        clean.map { ip ->
            async(Dispatchers.IO) {
                val matches = context?.let { matchingNets(ip, nets) } ?: emptyList()
                val ordered = matches.sortedBy { if (it.validated) 1 else 0 }
                val start = System.currentTimeMillis()
                // 同网段可能有多个 Wi-Fi（家用 + 相机热点），逐个绑定试，任一通即命中。
                val hit = ordered.firstOrNull { isOpen(it.network, ip, timeoutMs) }
                if (hit != null || (ordered.isEmpty() && isOpen(null, ip, timeoutMs))) {
                    Found(ip, hit?.iface ?: "preferred", (System.currentTimeMillis() - start).toInt())
                } else null
            }
        }.awaitAll().filterNotNull().sortedBy { it.rttMs }
    }

    /** 找出 IP 所属的 Wi-Fi/有线网络：按 LinkProperties 的 IPv4 子网匹配，不看默认路由。 */
    fun networkForIp(context: Context, ip: String, nets: List<LanNet> = activeLanNetworks(context)): LanNet? {
        // 同一网段同时存在家用 Wi-Fi 和相机热点时，优先选未验证网络（相机热点通常没有互联网）。
        return matchingNets(ip, nets).minByOrNull { if (it.validated) 1 else 0 }
    }

    private fun matchingNets(ip: String, nets: List<LanNet>): List<LanNet> {
        val target = try { InetAddress.getByName(ip) as? Inet4Address } catch (_: Exception) { null }
            ?: return emptyList()
        return nets.filter { net ->
            val base = try { InetAddress.getByName(net.ip) as? Inet4Address } catch (_: Exception) { null }
                ?: return@filter false
            val mask = if (net.prefix in 1..32) (-1 shl (32 - net.prefix)) else -1
            (ipv4ToInt(target) and mask) == (ipv4ToInt(base) and mask)
        }
    }

    /** Inet4Address 没有 toInt()，按网络字节序转成 32 位整数做子网比较。 */
    private fun ipv4ToInt(address: Inet4Address): Int {
        val b = address.address
        return ((b[0].toInt() and 0xff) shl 24) or
            ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or
            (b[3].toInt() and 0xff)
    }

    /** 当前所有 Wi-Fi / 有线网络及其 IPv4 地址。相机热点通常不是默认网络，必须显式绑定。 */
    fun activeLanNetworks(context: Context): List<LanNet> {
        val out = ArrayList<LanNet>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return emptyList()
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
                val lp = cm.getLinkProperties(network) ?: continue
                for (link in lp.linkAddresses) {
                    val addr = link.address as? Inet4Address ?: continue
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                        if (out.none { it.ip == ip }) out.add(
                            LanNet(
                                "wlan", ip, link.prefixLength, network,
                                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    data class LanNet(
        val iface: String,
        val ip: String,
        val prefix: Int,
        val network: Network?,
        val validated: Boolean = false
    )
    private data class Candidate(val ip: String, val net: LanNet)

    /** 本机所有非回环 IPv4 地址 + 所在接口名。 */
    private fun localSubnets(): List<Subnet> {
        val result = ArrayList<Subnet>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                // 只关心 Wi-Fi / 热点 / 有线网卡；蜂窝网段没有意义。
                // 部分 ROM 的热点接口叫 softap0 / wifi0，旧实现会直接漏掉。
                if (!(name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("ap") ||
                        name.startsWith("softap") || name.startsWith("swlan") ||
                        name.startsWith("eth") || name.startsWith("rndis")
                ) ) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            result.add(Subnet(ip, nif.name))
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result.distinctBy { it.ip }
    }

    private data class Subnet(val ip: String, val iface: String)

    /** 本机当前 Wi-Fi 网段列表，供界面提示用。 */
    fun localNetworkSummary(): String {
        val subs = localSubnets()
        if (subs.isEmpty()) return "未检测到局域网连接"
        return subs.joinToString("；") { "${it.iface} ${it.ip.substringBeforeLast('.')}.x" }
    }

    /** 用 InetAddress 解析，避免手写校验漏洞。 */
    fun isValidIpv4(ip: String): Boolean = try {
        val addr = InetAddress.getByName(ip.trim())
        addr is Inet4Address && ip.trim().count { it == '.' } == 3
    } catch (_: Exception) {
        false
    }
}
