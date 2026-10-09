package dev.fabhotspot.probe.priv

/**
 * 报告脱敏。
 *
 * 实机确认过的泄露点：
 *  - /data/vendor/wifi/hostapd/hostapd_wlan2.conf 里的 wpa_passphrase / sae_password / ssid2(hex)
 *  - /data/misc/apexdata/com.android.wifi/WifiConfigStoreSoftAp.xml 里的 Passphrase / WifiSsid
 *  - 客户端 MAC
 * 这些内容一律不得进入报告文件。
 */
object Redact {

    private val MAC = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")

    private val SECRET_LINE = Regex("(?m)^\\s*(wpa_passphrase|sae_password)\\s*=.*$")
    private val SSID2_LINE = Regex("(?m)^\\s*ssid2?\\s*=.*$")
    private val XML_PASSPHRASE =
        Regex("(?s)<string name=\"Passphrase\">.*?</string>")
    private val XML_SSID =
        Regex("(?s)<string name=\"WifiSsid\">.*?</string>")
    private val XML_MAC =
        Regex("(?s)<string name=\"ClientMacAddress\">.*?</string>")

    fun secrets(text: String): String {
        var out = text
        out = out.replace(SECRET_LINE) { "  <redacted-secret>" }
        out = out.replace(SSID2_LINE) { "  <redacted-ssid>" }
        out = out.replace(XML_PASSPHRASE) { "<string name=\"Passphrase\"><redacted></string>" }
        out = out.replace(XML_SSID) { "<string name=\"WifiSsid\"><redacted></string>" }
        out = out.replace(XML_MAC) { "<string name=\"ClientMacAddress\"><redacted></string>" }
        return MAC.replace(out, "xx:xx:xx:xx:xx:xx")
    }
}
