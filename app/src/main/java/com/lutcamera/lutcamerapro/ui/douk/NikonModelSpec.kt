package com.lutcamera.lutcamerapro.ui.douk

/**
 * 尼康 Z 卡口机型能力表。
 *
 * 四种连接方式并不是每台机器都齐活的，差异来自官方菜单结构本身：
 *
 * - **较早机型**（Z7II / Z6II / Z7 / Z6 / Z5 / Z50 / Zfc / Z30）：网络菜单里只有
 *   「连接至智能设备 → Wi-Fi 连接」，相机自己开热点（AP 直连）。原生菜单不提供 STA 模式，
 *   也没有「USB 数据连接」菜单（插线默认就是 PTP）。
 * - **较新机型**（Z9 / Z8 / Z6III / Z5II / Zf / Z50II / ZR）：菜单扩成
 *   「配对（Bluetooth） / 选择照片以上传 / Wi-Fi 连接（AP mode） / Wi-Fi 连接（STA mode） /
 *   关机时上传」。STA 让相机加入手机所在的路由器，不用切热点；蓝牙用于定位推送；
 *   USB 则要在「设定菜单 → USB 数据连接」里先选 MTP/PTP，否则只被识别成读卡器。
 * - **单反**（D6 / D780）：只有 USB PTP 可用；无线要靠 WT 传输器，不走机身自带 Wi-Fi。
 *
 * 机型字符串取自 PTP `GetDeviceInfo`，尼康写法不统一（`Z 50_2` / `Z50_2` / `Z 6_3`），
 * 所以识别前统一去掉空格与下划线。
 */
object NikonModelSpec {

    enum class Gen { THIRD, SECOND, DSLR }

    data class Spec(
        val id: String,
        val label: String,
        val gen: Gen,
        val sta: Boolean,
        val usbMtpMenu: Boolean,
        val bleGps: Boolean,
        val note: String
    ) {
        val generation: String
            get() = when (gen) {
                Gen.THIRD -> "较新机型"
                Gen.SECOND -> "较早机型"
                Gen.DSLR -> "单反"
            }
        val ap: Boolean get() = gen != Gen.DSLR
    }

    private val third = listOf(
        Spec("z9", "Z9", Gen.THIRD, sta = true, usbMtpMenu = true, bleGps = true,
            note = "机身 Wi-Fi 6 + 有线 LAN，STA 与 AP 都可用，蓝牙可推定位"),
        Spec("z8", "Z8", Gen.THIRD, sta = true, usbMtpMenu = true, bleGps = true,
            note = "相机 C 固件 3.00 起开放 STA 与蓝牙定位"),
        Spec("z63", "Z6III", Gen.THIRD, sta = true, usbMtpMenu = true, bleGps = true,
            note = "原生支持 STA 模式，部分版本 USB 需手动切 MTP/PTP"),
        Spec("z52", "Z5II", Gen.THIRD, sta = true, usbMtpMenu = true, bleGps = true,
            note = "原生支持 STA 与蓝牙定位"),
        Spec("zf", "Z f", Gen.THIRD, sta = true, usbMtpMenu = true, bleGps = true,
            note = "相机 C 固件 3.00 起支持 STA"),
        Spec("z502", "Z50II", Gen.THIRD, sta = true, usbMtpMenu = true, bleGps = true,
            note = "DX 机型，STA 与蓝牙定位齐全"),
        Spec("zr", "ZR", Gen.THIRD, sta = true, usbMtpMenu = true, bleGps = true,
            note = "视频向机型，网络菜单与 Z6III 同构")
    )

    private val second = listOf(
        Spec("z72", "Z7II", Gen.SECOND, sta = true, usbMtpMenu = false, bleGps = false,
            note = "优先直连热点；STA 要在「设定菜单 → 连接至 PC」选照相机控制，控制通道受限"),
        Spec("z62", "Z6II", Gen.SECOND, sta = true, usbMtpMenu = false, bleGps = false,
            note = "优先直连热点；USB 插线即 PTP，没有 USB 数据连接菜单"),
        Spec("z7", "Z7", Gen.SECOND, sta = true, usbMtpMenu = false, bleGps = false,
            note = "优先直连热点；STA 走「连接至 PC」，不推荐"),
        Spec("z6", "Z6", Gen.SECOND, sta = true, usbMtpMenu = false, bleGps = false,
            note = "优先直连热点；STA 走「连接至 PC」，不推荐"),
        Spec("z5", "Z5", Gen.SECOND, sta = true, usbMtpMenu = false, bleGps = false,
            note = "优先直连热点；STA 走「连接至 PC」，不推荐"),
        Spec("z50", "Z50", Gen.SECOND, sta = true, usbMtpMenu = false, bleGps = false,
            note = "优先直连热点；STA 走「连接至 PC」，不推荐"),
        Spec("zfc", "Z fc", Gen.SECOND, sta = true, usbMtpMenu = false, bleGps = false,
            note = "优先直连热点；STA 走「连接至 PC」，不推荐"),
        Spec("z30", "Z30", Gen.SECOND, sta = true, usbMtpMenu = false, bleGps = false,
            note = "优先直连热点；STA 走「连接至 PC」，不推荐")
    )

    private val dslr = listOf(
        Spec("d6", "D6", Gen.DSLR, sta = false, usbMtpMenu = true, bleGps = false,
            note = "无线需 WT-6 传输器；机身自带 Wi-Fi 不提供 PTP/IP 智能设备模式"),
        Spec("d780", "D780", Gen.DSLR, sta = false, usbMtpMenu = true, bleGps = false,
            note = "USB PTP 可用；无线同样依赖 WT 传输器")
    )

    val catalog: List<Spec> = third + second + dslr

    /** 机型清单，用于界面上直接列出型号（不出现代际说法）。 */
    val thirdNames: String = third.joinToString("、") { it.label }
    val secondNames: String = second.joinToString("、") { it.label }
    val dslrNames: String = dslr.joinToString("、") { it.label }
    val wirelessStaNames: String get() = thirdNames
    val computerNames: String get() = secondNames

    /**
     * 尼康部分机型/固件会把型号写成 `Z50II`、`Z 6III` 这类罗马数字，
     * 而能力表 id 用的是 `z502`、`z63` 这种 PTP 写法，先做一层别名归一。
     * 只收真实存在、容易和短 id 混淆的型号，避免把未知机型硬塞进错误档位。
     */
    private val aliases = mapOf(
        "z50ii" to "z502",
        "z5ii" to "z52",
        "z6ii" to "z62",
        "z6iii" to "z63",
        "z7ii" to "z72"
    )

    /** `Z 50_2` / `Z50_2` / `Z 6_3` 统一成 `z502` / `z63` 这种纯字母数字串。 */
    private fun norm(model: String): String =
        model.lowercase()
            .removePrefix("nikon")
            .filter { it.isLetterOrDigit() }

    /**
     * 判断 id 是否作为独立型号片段出现。
     * `z50iimock` 里的 `z5` 后面紧跟 `0`，`z50` 后面紧跟 `i`，都不能算命中；
     * 否则短 id 会把更长的新机型抢走（Z50II 被识别成 Z5 就是这么来的）。
     */
    private fun containsModelId(key: String, id: String): Boolean {
        var start = key.indexOf(id)
        while (start >= 0) {
            val before = if (start > 0) key[start - 1] else null
            val after = if (start + id.length < key.length) key[start + id.length] else null
            val beforeOk = before == null || !before.isDigit()
            val afterOk = after == null || !after.isDigit()
            if (beforeOk && afterOk) return true
            start = key.indexOf(id, start + 1)
        }
        return false
    }

    fun of(model: String?): Spec? {
        if (model.isNullOrBlank()) return null
        val key = norm(model)

        // 1. 精确命中 PTP 写法：Z 50_2 → z502、Z 6_3 → z63
        catalog.firstOrNull { it.id == key }?.let { return it }

        // 2. 罗马数字别名：Z50II → z50ii → z502；Z6III → z6iii → z63
        aliases.entries
            .filter { key == it.key || key.contains(it.key) }
            .maxByOrNull { it.key.length }
            ?.let { alias -> catalog.firstOrNull { it.id == alias.value } }
            ?.let { return it }

        // 3. 兜底：按最长 id 匹配，且必须是独立片段，避免 z5 抢走 z50ii
        return catalog
            .filter { containsModelId(key, it.id) }
            .maxByOrNull { it.id.length }
    }

    /** 三种无线/有线方式的机型专属步骤。 */
    fun steps(spec: Spec?, mode: ConnectMode): String = when (mode) {
        ConnectMode.WIFI_STA -> when {
            spec?.gen == Gen.THIRD ->
                "${spec.label}：网络菜单 → 连接至智能设备 → Wi-Fi STA，让相机连上同一个 Wi-Fi 或手机热点；" +
                    "加入后 IP 由路由器/热点分配，点「搜索相机设备」会自动探测 15740 端口。"
            spec?.gen == Gen.SECOND ->
                "${spec.label} 优先用「Wi-Fi AP 直连」。想走 STA 要在设定菜单 → 连接至 PC，类型选「照相机控制」，" +
                    "让相机连同一个 Wi-Fi 或手机热点（不推荐：这个模式下机身对第三方开放的控制指令受限）。"
            else ->
                "STA 需要机身菜单里有「Wi-Fi STA」这一项：在网络菜单 → 连接至智能设备 → Wi-Fi STA 里让相机连同一个 Wi-Fi 或热点；" +
                    "菜单里只有「Wi-Fi 连接」的机型请改用「Wi-Fi AP 直连」。"
        }

        ConnectMode.WIFI_AP -> when {
            spec?.gen == Gen.SECOND ->
                "${spec.label}：设定菜单 → 连接至智能设备 → Wi-Fi 连接 → 建立连接，机身屏幕显示 NIKON_… 名称和 8 位密码；" +
                    "手机连这个热点后回应用点「搜索相机设备」。系统提示「无法访问互联网」时选保持连接。"
            else ->
                "相机 MENU → 网络菜单 → 连接至智能设备 → Wi-Fi AP（或「Wi-Fi 连接」）→ 建立连接，机身屏幕显示 NIKON_… 名称和 8 位密码；" +
                    "手机连这个热点、输入密码。系统提示「无法访问互联网」时选保持连接，并关掉「WLAN+ / 智能网络切换」，否则会自动切回有网的路由器。"
        }

        ConnectMode.USB_PTP -> if (spec?.usbMtpMenu == true)
            "${spec.label}：设定菜单 → USB 数据连接 → 选「连接至计算机（照相机控制）」或 MTP/PTP；" +
                "机身屏幕出现「连接至计算机」就表示已进入 PTP，选了「MTP」或「优先上传」都拿不到控制通道。"
        else
            "插线即进入 PTP，不需要改菜单；机身屏幕出现「连接至计算机」就表示已被识别。"

        ConnectMode.BLUETOOTH -> if (spec?.bleGps == true)
            "${spec.label}：网络菜单 → 连接至智能设备 → 配对（Bluetooth）→ 开始配对，保持蓝牙连接项为 ON；" +
                "配对成功后本机可把手机定位推给相机，拍出的照片带 GPS。"
        else
            "蓝牙 GPS 推送要求机身的蓝牙提供标准 BLE 定位通道；只在 SnapBridge 里配对的机型推不了，请用 SnapBridge 管理定位。"
    }

    /** 机型明确不支持某方式时返回一句拦截提示，支持则返回 null。 */
    fun blocked(spec: Spec?, mode: ConnectMode): String? {
        if (spec == null) return null
        return when (mode) {
            ConnectMode.WIFI_STA -> if (!spec.sta) "${spec.label} 不支持 STA 模式" else null
            ConnectMode.WIFI_AP -> if (!spec.ap) "${spec.label} 不带机身 Wi-Fi 直连" else null
            ConnectMode.USB_PTP -> null
            ConnectMode.BLUETOOTH -> if (!spec.bleGps) "${spec.label} 的蓝牙不能推 GPS" else null
        }
    }

    /** 一行能力摘要，用在机型卡片上（只讲能力，不提几代机）。 */
    fun summary(spec: Spec): String = buildString {
        append(
            when (spec.gen) {
                Gen.THIRD -> "支持 STA 局域网"
                Gen.SECOND -> "推荐直连热点（STA 要走「连接至 PC」）"
                Gen.DSLR -> "无线需 WT 传输器"
            }
        )
        append(" · ")
        append(if (spec.bleGps) "蓝牙可推 GPS" else "蓝牙仅 SnapBridge")
        if (spec.usbMtpMenu) {
            append(" · USB 需选连接至计算机")
        }
    }
}
