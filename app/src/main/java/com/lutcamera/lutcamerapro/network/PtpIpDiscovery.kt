package com.lutcamera.lutcamerapro.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.Collections

/**
 * 相机 PTP/IP 服务的 mDNS 发现。
 *
 * 参考 Imaging Console（com.dongzhang.zconsole）：它同时浏览 `_ptpip._tcp` 与 `_ptp._tcp`
 * 两个服务类型，用 resolveService 拿到 host/port（TXT 里形如 `tcp/15740`），拿不到再退回
 * 192.168.1.1 / 192.168.1.10 / 192.168.0.1 / 192.168.0.10 四个固定地址。
 *
 * 对我们最有价值的是「机身到底报了什么地址」：二代机身（Z6II/Z7II 这类）换网段、
 * 或 DHCP 没按 .1 分配时，靠网关猜会连到错误地址上，最后表现为「端口没开」。
 */
object PtpIpDiscovery {

    /** 尼康 PTP/IP 的 Bonjour 服务类型，两种都见过，必须一起浏览。 */
    val SERVICE_TYPES = listOf("_ptpip._tcp", "_ptp._tcp")

    const val DEFAULT_PORT = 15740

    data class Service(
        val name: String,
        val host: String,
        val port: Int,
        val type: String,
        val txt: String
    ) {
        val endpoint: String get() = "$host:$port"
        override fun toString(): String = "$name@$endpoint($type${if (txt.isEmpty()) "" else " $txt"})"
    }

    /**
     * 浏览并解析相机 PTP/IP 服务。
     *
     * 以前固定等满整个窗口才解析，即使机身 300ms 就广播了也要白等 1.2–1.6 秒。
     * 现在「首个服务出现后只再等一个 400ms 收尾窗口」，让同一机身的第二种服务类型
     * （_ptpip._tcp 与 _ptp._tcp）也能进来，然后立刻返回；窗口内一台都没出现才用满 timeout。
     *
     * @param timeoutMs 最长浏览窗口；机身开始广播后通常 300–800ms 内就能看到
     * @return 解析成功的服务列表；没有发现返回空列表
     */
    suspend fun discover(context: Context, timeoutMs: Long = 2500): List<Service> =
        withContext(Dispatchers.IO) {
            val nsd = context.applicationContext
                .getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return@withContext emptyList()
            val seen = Collections.synchronizedList(ArrayList<NsdServiceInfo>())
            val started = ArrayList<NsdManager.DiscoveryListener>()
            // CONFLATED：只要「有过一次发现」这个信号，不需要排队。
            val firstHit = Channel<Unit>(Channel.CONFLATED)

            for (type in SERVICE_TYPES) {
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        com.lutcamera.lutcamerapro.log.DoukLog.w(
                            "PtpDiscovery", "START_FAILED", "type" to serviceType, "error" to errorCode
                        )
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

                    override fun onDiscoveryStarted(serviceType: String) {
                        com.lutcamera.lutcamerapro.log.DoukLog.i(
                            "PtpDiscovery", "BROWSING", "type" to serviceType
                        )
                    }

                    override fun onDiscoveryStopped(serviceType: String) = Unit

                    override fun onServiceFound(info: NsdServiceInfo) {
                        synchronized(seen) {
                            if (seen.none { it.serviceName == info.serviceName && it.serviceType == info.serviceType }) {
                                seen.add(info)
                            }
                        }
                        firstHit.trySend(Unit)
                    }

                    override fun onServiceLost(info: NsdServiceInfo) = Unit
                }
                runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                    .onSuccess { started.add(listener) }
                    .onFailure {
                        com.lutcamera.lutcamerapro.log.DoukLog.w(
                            "PtpDiscovery", "DISCOVER_EXCEPTION", "type" to type, "msg" to it.message
                        )
                    }
            }

            val hit = withTimeoutOrNull(timeoutMs) { firstHit.receive() }
            if (hit != null) delay(SETTLE_MS)
            for (l in started) runCatching { nsd.stopServiceDiscovery(l) }

            val snapshot = synchronized(seen) { ArrayList(seen) }
            // 多个服务并发解析：串行时每个 1.2s 超时会叠加，机身同时广播两个类型时会白等。
            val services = snapshot.map { info ->
                async(Dispatchers.IO) { resolve(nsd, info) }
            }.awaitAll().filterNotNull()
            com.lutcamera.lutcamerapro.log.DoukLog.i(
                "PtpDiscovery", "RESULT",
                "found" to services.size,
                "list" to services.joinToString(";"),
                "raw" to snapshot.size
            )
            services
        }

    /** 首个服务出现后的收尾窗口：等同一机身的第二种服务类型也进来。 */
    private const val SETTLE_MS = 400L

    /**
     * 只要地址列表的便捷入口：连接前和端口探测并发跑，拿到机身上报的真实 IP 再补探一次。
     */
    suspend fun endpoints(context: Context, timeoutMs: Long = 1500): List<String> =
        discover(context, timeoutMs).map { it.host }.distinct()

    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): Service? {
        val channel = Channel<NsdServiceInfo>(1)
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    com.lutcamera.lutcamerapro.log.DoukLog.w(
                        "PtpDiscovery", "RESOLVE_FAILED",
                        "name" to serviceInfo.serviceName, "error" to errorCode
                    )
                    channel.trySend(serviceInfo)
                    channel.close()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    channel.trySend(serviceInfo)
                    channel.close()
                }
            })
        }.onFailure {
            channel.close()
            return null
        }
        val resolved = withTimeoutOrNull(1200) { channel.receive() } ?: return null
        val host = (resolved.host as? Inet4Address)?.hostAddress
            ?: resolved.host?.hostAddress?.substringBefore('%')
            ?: return null
        if (!CameraLanProbe.isValidIpv4(host)) return null
        val port = if (resolved.port > 0) resolved.port else DEFAULT_PORT
        return Service(
            name = resolved.serviceName ?: "camera",
            host = host,
            port = port,
            type = resolved.serviceType ?: "",
            txt = txtOf(resolved)
        )
    }

    /** 机身 TXT 记录里常见 `tcp/15740`，解析出来只为写日志排错。 */
    private fun txtOf(info: NsdServiceInfo): String {
        val attrs = runCatching { info.attributes }.getOrNull() ?: return ""
        return attrs.entries.joinToString(" ") { (k, v) ->
            "$k=${runCatching { String(v, Charsets.UTF_8) }.getOrDefault("")}"
        }
    }
}
