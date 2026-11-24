package com.lutcamera.lutcamerapro.network

/**
 * 子网计算。纯 JVM 实现，不依赖 Android，便于单元测试。
 *
 * 相机热点几乎都是 /24，但 LinkProperties 上报的前缀偶尔是 0、32 这类异常值，
 * 所以前缀一律钳制到 0–32 再算掩码。
 */
internal object SubnetMath {

    data class LocalAddress(val iface: String, val ip: String)

    /** 严格解析点分十进制 IPv4；解析不了返回 null，绝不做 DNS 解析。 */
    fun ipv4ToInt(ip: String): Int? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var out = 0
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
            val v = part.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out = (out shl 8) or v
        }
        return out
    }

    /** 两个地址是否同一子网。任一解析失败时按「不匹配」处理，避免误绑到别的网口。 */
    fun sameSubnet(a: String, b: String, prefix: Int): Boolean {
        val x = ipv4ToInt(a) ?: return false
        val y = ipv4ToInt(b) ?: return false
        val p = prefix.coerceIn(0, 32)
        val mask = if (p == 0) 0 else -1 shl (32 - p)
        return (x and mask) == (y and mask)
    }

    /**
     * 从本机地址里挑一个与 [target] 同 /24 的。
     * 优先 wlan 系列接口；同等条件下取第一个。
     */
    fun localAddressInSubnet(candidates: List<LocalAddress>, target: String, prefix: Int = 24): String? {
        if (ipv4ToInt(target) == null) return null
        val preferred = candidates.filter { isWireless(it.iface) }
        return (preferred + candidates).firstOrNull { sameSubnet(it.ip, target, prefix) }?.ip
    }

    fun isWireless(iface: String): Boolean {
        val n = iface.lowercase()
        return n.startsWith("wlan") || n.startsWith("wifi") || n.startsWith("ap") ||
            n.startsWith("softap") || n.startsWith("swlan") ||
            n.startsWith("eth") || n.startsWith("rndis")
    }
}
