package com.lutcamera.lutcamerapro.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.lutcamera.lutcamerapro.ui.douk.DoukPrefs
import com.lutcamera.lutcamerapro.util.SecureStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/**
 * 在应用内直接加入相机热点（不跳系统设置）。
 *
 * 尼康机身开的热点没有外网，系统会弹「无法访问互联网」并可能自动切回有网 Wi‑Fi，
 * 所以这里用 [WifiNetworkSpecifier] 明确点名 SSID，拿到一个只属于相机的网络，
 * 并在连接期间**保持请求不释放**：一旦 unregister，系统会立刻把这条「无外网」
 * 的网络断掉，相机侧看到的就是连接失败、随即关闭热点。
 *
 * 与 [CameraNetworkBinder] 的分工：Binder 只被动监听、绝不断网；
 * 这里负责主动入网，退出连接时由 [release] 统一释放。
 */
object CameraHotspot {

    private const val KEY_SSID = "camera_wifi_ssid"
    private const val KEY_PASSWORD = "camera_wifi_password"

    /** 上次成功加入的相机热点，下次开机可以一键重连。 */
    data class Credential(val ssid: String, val password: String)

    /** 入网结果：成功时带回 Network 与从链路信息里读到的相机候选地址。 */
    sealed interface Result {
        data class Joined(val network: Network, val cameraIps: List<String>) : Result
        data class Failed(val message: String) : Result
    }

    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private var activeNetwork: Network? = null

    /** 入网成功后必须稳定这么久才算真的连上，见 [join]。 */
    private const val STABILITY_MS = 1_000L

    @Volatile
    private var joinedAtMs = 0L

    /** 最近一次入网后多少毫秒被系统断开；-1 表示没有掉线记录。 */
    @Volatile
    private var dropAfterMs = -1L

    fun lastDropAfterMs(): Long = dropAfterMs

    @Volatile
    private var lastError: String? = null

    fun saved(): Credential? {
        val ssid = runCatching { DoukPrefs.raw(KEY_SSID) }.getOrNull().orEmpty()
        if (ssid.isBlank()) return null
        // 口令是 Keystore 密文；1.1.25 及以前的明文会在这次读取里自动迁移。
        val pwd = runCatching { DoukPrefs.secureRaw(KEY_PASSWORD) }.getOrNull().orEmpty()
        return Credential(ssid, pwd)
    }

    fun remember(credential: Credential) {
        runCatching {
            DoukPrefs.putRaw(KEY_SSID, credential.ssid)
            // 口令加密后落盘，明文不再进 prefs。
            DoukPrefs.putSecureRaw(KEY_PASSWORD, credential.password)
        }
    }

    fun forget() {
        runCatching {
            DoukPrefs.putRaw(KEY_SSID, "")
            DoukPrefs.putRaw(KEY_PASSWORD, "")
        }
    }

    /** 当前是否由本应用持有一条相机热点连接。 */
    val isHolding: Boolean get() = activeNetwork != null

    /**
     * 尼康相机热点名：一代多为 `Nikon_WU2_...`，二代 `NIKON_Z 6II_...`，
     * 三代仍是 `NIKON_...`；这里只按前缀判断，避免把家里的路由器选进来。
     */
    fun isCameraSsid(ssid: String): Boolean {
        val s = ssid.trim().trim('"')
        if (s.isEmpty()) return false
        val upper = s.uppercase()
        // 尼康机身热点名均以 NIKON 开头（一代 Nikon_WU2、二代 NIKON_Z 6II、三代 NIKON_...）；
        // 不再用裸 "Z+数字" 前缀，避免把非尼康路由器列进候选。
        return upper.startsWith("NIKON")
    }

    /** 当前扫到的相机热点（需要定位/附近 Wi‑Fi 权限，拿不到就返回空）。 */
    fun nearbyCameraSsids(context: Context): List<String> =
        nearbySsids(context).filter { isCameraSsid(it) }

    /**
     * 附近所有 Wi‑Fi 名称，相机热点排在前面。
     *
     * 只给「尼康味」的名字会让用户手动输错（空格、下划线、后缀数字都容易看错），
     * 这里把所有能扫到的 SSID 都列出来，让用户点选，不再手打。
     */
    fun nearbySsids(context: Context): List<String> {
        val app = context.applicationContext
        // 读扫描结果在 Android 10+ 属于定位范围；没授权时 scanResults 会抛 SecurityException，
        // 这里先显式查一次权限，返回空列表由界面提示用户去授权。
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                app, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        val results = runCatching { wm.scanResults }.getOrNull() ?: return emptyList()
        val all = results.asSequence()
            .mapNotNull { it.SSID }
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val cameras = all.filter { isCameraSsid(it) }
        val others = all.filterNot { isCameraSsid(it) }
        return (cameras + others).take(10)
    }

    /**
     * 主动发起一次 Wi‑Fi 扫描再读结果。
     *
     * 以前「重新扫描」只是把 `scanResults` 的旧缓存又读一遍，用户刚打开相机热点时
     * 列表里根本看不到它。这里显式 startScan，等系统把新结果填进来（约 1.2 秒）。
     */
    suspend fun scanNearbySsids(context: Context, waitMs: Long = 1_300): List<String> {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context.applicationContext, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm != null) runCatching { wm.startScan() }
        if (waitMs > 0) kotlinx.coroutines.delay(waitMs)
        return nearbySsids(context)
    }

    /**
     * 手机当前连着的 Wi‑Fi 名称。
     *
     * 用户常常已经手动在系统设置里连上相机热点，只是回来填名字时记错；
     * 这个值可以直接当默认值。Android 10 起读 SSID 需要定位权限，拿不到就返回空。
     */
    fun currentSsid(context: Context): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val raw = runCatching { wm.connectionInfo?.ssid }.getOrNull() ?: return null
        val ssid = raw.trim().trim('"')
        return ssid.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != "0x" }
    }

    /**
     * 加入相机热点。
     *
     * 两轮入网的总预算：WPA2 先试 22 秒，失败再换 WPA3 试 16 秒。
     * 以前两轮都按 28 秒算，最坏要等将近一分钟；机身热点实际 3–8 秒就能关联上，
     * 留 22 秒已经足够覆盖系统弹窗 + DHCP 慢的情况。
     *
     * @param timeoutMs 首轮（WPA2）预算
     */
    suspend fun join(
        context: Context,
        credential: Credential,
        timeoutMs: Long = 22_000
    ): Result {
        val ssid = credential.ssid.trim().trim('"')
        if (ssid.isBlank()) return Result.Failed("请先选择或填写相机热点名称")
        release()
        lastError = null
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Result.Failed("系统网络服务不可用")

        val hasPassword = credential.password.isNotBlank()
        // 先 WPA2，部分三代机身默认 WPA3：失败后再换一次，不做无意义的多次重试
        val specifiers = buildList {
            add(specifier(ssid, credential.password, wpa3 = false))
            if (hasPassword) add(specifier(ssid, credential.password, wpa3 = true))
        }
        for ((index, spec) in specifiers.withIndex()) {
            // WPA3 只是补试，预算再收一点，避免用户在两轮都失败时干等一分钟。
            val budget = if (index == 0) timeoutMs else minOf(timeoutMs, 16_000L)
            val startedAt = System.currentTimeMillis()
            val outcome = requestOnce(cm, spec, budget)
            if (outcome != null) {
                val ips = cameraIps(cm, outcome)
                activeNetwork = outcome
                joinedAtMs = System.currentTimeMillis()
                dropAfterMs = -1L
                remember(credential)
                com.lutcamera.lutcamerapro.log.DoukLog.i(
                    "CameraHotspot", "JOINED",
                    "ssid" to ssid,
                    "wpa3" to (index == 1),
                    "tookMs" to (System.currentTimeMillis() - startedAt),
                    "ips" to ips.joinToString(",")
                )
                // 拿到网络不等于留得住：平板自己在开热点、或系统 WLAN 助理在抢网卡时，
                // 系统会先给一次 DHCP 再把这条无外网网络收回去。等一小拍确认它还在，
                // 才能把「热点被系统踢掉」和「密码错 / 相机没进等待态」分开说清楚。
                kotlinx.coroutines.delay(STABILITY_MS)
                if (activeNetwork !== outcome) {
                    val dropped = dropAfterMs
                    com.lutcamera.lutcamerapro.log.DoukLog.w(
                        "CameraHotspot", "JOIN_UNSTABLE",
                        "ssid" to ssid, "afterMs" to dropped
                    )
                    releasePending()
                    return Result.Failed(
                        "相机热点「$ssid」连上后 ${(dropped.coerceAtLeast(0) / 1000)} 秒就被系统断开了。\n" +
                            "这通常是本机还开着「个人热点」，或系统的 WLAN 助理/智能选网把这张网卡抢走了。" +
                            "请先在系统设置里关闭个人热点（设置 › 热点与连接 › 个人热点），" +
                            "确认相机仍停在「连接至智能设备」的等待画面，再回来点一次连接。"
                    )
                }
                return Result.Joined(outcome, ips)
            }
            // 这一次没入网：把挂着的请求先收掉，否则下一轮会同时存在两个 specifier
            releasePending()
        }
        val why = lastError ?: "系统未能加入「$ssid」"
        com.lutcamera.lutcamerapro.log.DoukLog.w(
            "CameraHotspot", "JOIN_FAILED", "ssid" to ssid, "why" to why
        )
        // 没有带密码时几乎必然是 WPA2 被拒：直接点名，别再让用户按「排查顺序」猜
        if (credential.password.isBlank() && !why.contains("密码")) {
            return Result.Failed(
                "没能加入「$ssid」：这次没有带密码。尼康相机热点默认是 WPA2，" +
                    "要在对话框里填相机屏幕上显示的 8 位密码（只有机身明确提示「开放/无密码」时才留空）。"
            )
        }
        return Result.Failed(
            "没能加入「$ssid」：$why\n" +
                "排查顺序：1) 相机是否进入「连接至智能设备」等待画面；2) 相机热点密码是否与机身上显示的一致；" +
                "3) 系统弹窗出现时选「连接」而不是「取消」。"
        )
    }

    private fun specifier(ssid: String, password: String, wpa3: Boolean): WifiNetworkSpecifier {
        val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (password.isNotBlank()) {
            if (wpa3) builder.setWpa3Passphrase(password) else builder.setWpa2Passphrase(password)
        }
        return builder.build()
    }

    /** 单次请求；成功返回 Network，失败返回 null 并把原因留在 [lastError]。 */
    private suspend fun requestOnce(
        cm: ConnectivityManager,
        specifier: WifiNetworkSpecifier,
        timeoutMs: Long
    ): Network? {
        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    /**
                     * 只有拿到 IPv4 才算可用：机身 DHCP 偶尔比 onAvailable 晚 1–2 秒，
                     * 以前只认 onAvailable，这一拍拿不到地址就只能干等到超时；
                     * 现在链路属性一更新（DHCP 回来）就再判一次。
                     */
                    fun ready(network: Network) {
                        val lp = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: return
                        if (lp.linkAddresses.none { it.address is Inet4Address }) return
                        if (cont.isActive) cont.resume(network)
                    }

                    override fun onAvailable(network: Network) = ready(network)

                    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                        ready(network)

                    override fun onUnavailable() {
                        lastError = "相机热点拒绝入网（密码错误、信号太弱，或相机不在等待状态）"
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onLost(network: Network) {
                        if (activeNetwork == network) {
                            activeNetwork = null
                            if (joinedAtMs > 0L) {
                                dropAfterMs = System.currentTimeMillis() - joinedAtMs
                                com.lutcamera.lutcamerapro.log.DoukLog.w(
                                    "CameraHotspot", "JOIN_DROPPED", "afterMs" to dropAfterMs
                                )
                            }
                        }
                    }
                }
                activeCallback = callback
                try {
                    cm.requestNetwork(request, callback)
                } catch (e: Exception) {
                    lastError = e.message ?: "请求网络失败"
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    runCatching { cm.unregisterNetworkCallback(callback) }
                    if (activeCallback === callback) activeCallback = null
                }
            }
        }
        if (outcome == null && lastError == null) {
            lastError = "等待入网超时（相机热点未响应，或系统弹窗没有确认）"
        }
        return outcome
    }

    /** 从链路信息里推断相机地址：网关 + DHCP 服务器 + 本机网段低号地址。 */
    private fun cameraIps(cm: ConnectivityManager, network: Network): List<String> {
        val lp: LinkProperties = runCatching { cm.getLinkProperties(network) }.getOrNull()
            ?: return emptyList()
        val self = lp.linkAddresses.mapNotNull { (it.address as? Inet4Address)?.hostAddress }
        val out = LinkedHashSet<String>()
        for (route in lp.routes) {
            if (!route.isDefaultRoute) continue
            val gw = (route.gateway as? Inet4Address)?.hostAddress ?: continue
            if (gw !in self) out.add(gw)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (lp.dhcpServerAddress as? Inet4Address)?.hostAddress?.takeIf { it !in self }?.let { out.add(it) }
        }
        for (ip in self) {
            val prefix = ip.split('.').take(3).joinToString(".")
            for (last in 1..8) out.add("$prefix.$last")
        }
        return out.toList()
    }

    /** 释放主动入网请求：系统会随即断开这条相机热点（用户主动断开连接时调用）。 */
    fun release() {
        releasePending()
        activeNetwork = null
        com.lutcamera.lutcamerapro.log.DoukLog.i("CameraHotspot", "RELEASED")
    }

    private fun releasePending() {
        val cb = activeCallback ?: return
        activeCallback = null
        runCatching {
            val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        }
    }

    /** 由 DoukState 初始化时注入，避免 release 时拿不到 Context。 */
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
        // 旧版本把热点口令明文写在 prefs 里，初始化时清一次，避免明文长期留盘。
        // 注意：1.1.26 起口令用 Keystore 加密后仍存在同一个 key（密文带 enc1: 前缀），
        // 无条件清空会把密文一起清掉 —— 冷启动后 saved() 只剩空口令，连 WPA2 热点必然超时。
        runCatching {
            val stored = DoukPrefs.raw(KEY_PASSWORD)
            if (stored != null && stored.isNotEmpty() && !SecureStore.isEncrypted(stored)) {
                DoukPrefs.putRaw(KEY_PASSWORD, "")
                com.lutcamera.lutcamerapro.log.DoukLog.i("CameraHotspot", "LEGACY_PASSWORD_PURGED")
            }
        }
    }
}
