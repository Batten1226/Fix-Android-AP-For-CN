package dev.fabhotspot.probe.probe

/**
 * 从 `dumpsys wifi` 里只挑出与 SoftAP / 6GHz / MLO 相关的行。
 *
 * 刻意**不保存整份 dumpsys** —— 它既巨大，又含周边 SSID/BSSID 等无关隐私。
 */
object DumpsysProbe {

    /** 顺序有意义：更具体的键放前面，"SoftAp" 这种宽泛的放最后。 */
    private val KEYS = listOf(
        "CMD_UPDATE_AP_CAPABILITY",
        "SupportedFeatures=",
        "MaximumSupportedClientNumber",
        "SupportedChannelListIn",
        "mCountryCodeFromDriver",
        "num SoftApManagers",
        "SoftApManager",
        "BridgedMode",
        "wifi_softap",
        "Wi-Fi standard:",
        "MLO Information",
        "Is TID-To-Link negotiation",
        "AP MLD Address",
        "Wifi is ",
        "SoftAp",
    )

    private const val MAX_PER_KEY = 40
    private const val MAX_LINE = 700
    private const val MAX_TOTAL = 320

    fun extract(dumpsys: String): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        var total = 0

        for (raw in dumpsys.lineSequence()) {
            if (total >= MAX_TOTAL) break
            val line = raw.trim()
            if (line.isEmpty()) continue

            var key: String? = null
            for (k in KEYS) {
                if (line.contains(k)) {
                    key = k
                    break
                }
            }
            if (key == null) continue

            val bucket = out.getOrPut(key) { mutableListOf() }
            if (bucket.size >= MAX_PER_KEY) continue

            val trimmed = if (line.length > MAX_LINE) line.take(MAX_LINE) + " …" else line
            if (bucket.contains(trimmed)) continue
            bucket += trimmed
            total++
        }
        return out
    }

    /** 从 dumpsys 原文里抠出 HAL 上报的 SoftAP 能力位掩码。 */
    fun extractApCapabilityMask(dumpsys: String): Long? =
        Regex("SupportedFeatures=(\\d+)").find(dumpsys)?.groupValues?.get(1)?.toLongOrNull()

    /** 从 dumpsys 原文里抠出 6GHz 可用信道列表内容（应为空字符串才说明被法规锁死）。 */
    fun extract6gChannelList(dumpsys: String): String? =
        Regex("SupportedChannelListIn6g\\[([^]]*)]").find(dumpsys)?.groupValues?.get(1)
}
