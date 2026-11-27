package com.lutcamera.lutcamerapro.network

/**
 * 尼康机身代际：决定热点地址、探测预算和失败提示。
 *
 * 三代（Z8/Z9/Zf/Z6III/Z5II/Z50II 这一批）的热点起得最快，基本落在
 * 192.168.1.1；二代（Z6II/Z7II/D780/D500 等 SnapBridge 世代）常见 .1 与 .10
 * 两种 DHCP 结果；一代（WU‑1a/b、D5300/D7100/J5）热点栈最弱，端口开放慢，
 * 探测窗口要给宽一点，否则第一次必然判成不可达。
 */
enum class NikonGeneration(val label: String) {
    GEN1("一代"),
    GEN2("二代"),
    GEN3("三代"),
    UNKNOWN("未知");

    /** 端口探测窗口：一代机身切服务更慢，给 1.2s；三代 600ms 足够。 */
    val probeTimeoutMs: Int
        get() = when (this) {
            GEN1 -> 1200
            GEN2 -> 900
            GEN3 -> 600
            UNKNOWN -> 900
        }

    /** 开机热点后建议等多久再发握手：一代机身要等端口真正 listen。 */
    val settleDelayMs: Long
        get() = when (this) {
            GEN1 -> 900L
            GEN2 -> 600L
            GEN3 -> 350L
            UNKNOWN -> 600L
        }

    val hint: String
        get() = when (this) {
            GEN1 -> "一代机身（如 D5300/D7100/J5）热点较弱：相机进入 Wi‑Fi 后等 2–3 秒再点连接，连接失败时把相机 Wi‑Fi 关掉重开一次"
            GEN2 -> "二代机身（Z6II/Z7II/D780 等）热点地址在 .1 与 .10 之间浮动，App 会两个都探；若相机显示「已与智能设备连接」，先在相机里断开旧设备"
            GEN3 -> "三代机身（Z8/Z9/Zf/Z6III 等）一般固定 192.168.1.1；若刚切换过「连接至智能设备」，等相机屏幕进入等待状态再连"
            UNKNOWN -> "确认相机已进入「连接至智能设备」的等待画面，且手机 WLAN 连的是相机热点"
        }

    companion object {
        private val GEN3_MODELS = listOf(
            "Z8", "Z9", "Zf", "Z6III", "Z5II", "Z50II", "Z6III", "Z7III"
        )
        private val GEN2_MODELS = listOf(
            "Z6II", "Z7II", "Z6", "Z7", "Z5", "Z50", "Zfc", "Z30",
            "D780", "D7500", "D500", "D850", "D750", "D610", "D810", "D7200", "D5600", "D5500"
        )
        private val GEN1_MODELS = listOf(
            "D5300", "D3300", "D5200", "D7100", "D3200", "J5", "J4", "J3", "S2", "S1", "AW1", "P900", "P7800", "COOLPIX"
        )

        /** 从机型名（可能带空格、下划线、固件后缀）判断代际。 */
        fun from(model: String?): NikonGeneration {
            val m = model?.uppercase()?.replace(" ", "")?.replace("_", "")?.replace("-", "") ?: ""
            if (m.isBlank()) return UNKNOWN
            // 先匹配更长的型号，避免 "Z6" 抢到 "Z6III"
            if (GEN3_MODELS.any { m.contains(it.replace(" ", "")) }) return GEN3
            if (GEN2_MODELS.any { m.contains(it.replace(" ", "")) }) return GEN2
            if (GEN1_MODELS.any { m.contains(it.replace(" ", "")) }) return GEN1
            // 只有 "Z" 系列但没识别出具体型号时按二代处理（最保守的探测预算）
            if (m.startsWith("Z")) return GEN2
            return UNKNOWN
        }
    }
}
