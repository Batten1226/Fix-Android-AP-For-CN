package dev.fabhotspot.probe.probe

import android.content.Context
import android.net.wifi.WifiManager
import dev.fabhotspot.probe.priv.Redact
import dev.fabhotspot.probe.priv.SuShell
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 1 的能力体检。**全程只读**：不调用任何 setter、不写任何 sysfs、不启停热点。
 * 唯一的写操作由调用方在体检结束后自行落盘一份报告文件。
 */
object CapabilityProbe {

    private val DEVICE_PROPS = listOf(
        "ro.product.model", "ro.product.device", "ro.build.fingerprint",
        "ro.build.version.release", "ro.build.version.sdk",
        "ro.mi.os.version.name", "ro.mi.os.version.incremental",
        "ro.miui.region", "ro.soc.model", "ro.board.platform",
    )

    private val READONLY_COMMANDS = listOf(
        "cat /sys/module/kiwi_v2/parameters/country_code",
        "cat /sys/module/kiwi_v2/parameters/fwpath",
        "cat /sys/module/kiwi_v2/parameters/con_mode",
        "cat /sys/module/kiwi_v2/parameters/enable_11d",
        "cat /sys/module/kiwi_v2/parameters/enable_dfs_chan_scan",
        "grep -i -E 'qca_cld3|kiwi|cnss' /proc/modules",
        "cmd wifi get-country-code",
        "cmd wifi get-softap-supported-features",
        "cmd wifi get-wifi-supported-features",
        "cmd wifi get-allowed-channel -b 8",
        "cmd wifi get-allowed-channel -b 15",
        "cmd wifi get-allowed-channel -b 31",
        "/vendor/bin/iw reg get",
        "/vendor/bin/iw dev",
        "ls -l /vendor/firmware_mnt/image/kiwi/",
        "ls -l /data/misc/apexdata/com.android.wifi/",
        "ls /data/adb/modules",
        "cat /data/vendor/wifi/hostapd/hostapd_wlan2.conf",
        "device_config list wifi",
    )

    data class CommandRecord(
        val command: String,
        val exitCode: Int,
        val timedOut: Boolean,
        val output: String,
    )

    /**
     * 单条命令在报告里最多保留的字符数。
     *
     * 计划要求"不保存整份 dumpsys"（它既巨大，又含周边 SSID/BSSID 等无关隐私）。
     * 实机验证过：不做限制时报告会膨胀到 1.2 MB，其中约 1 MB 是整份 `dumpsys wifi`。
     * 结构化信息已由 [DumpsysProbe.extract] 单独提取，原始件无需完整保留。
     */
    private const val MAX_STORED_OUTPUT = 8_000

    data class ProbeOutcome(val json: String, val text: String)

    fun run(context: Context, log: (String) -> Unit = {}): ProbeOutcome {
        val records = mutableListOf<CommandRecord>()

        fun sh(command: String, timeoutMs: Long = 25_000): String {
            log(command)
            val r = SuShell.run(command, timeoutMs)
            val cleaned = Redact.secrets(r.output).trimEnd()
            val stored = if (cleaned.length > MAX_STORED_OUTPUT) {
                cleaned.take(MAX_STORED_OUTPUT) +
                    "\n…[已截断：原始 ${cleaned.length} 字符，结构化信息见对应字段]"
            } else {
                cleaned
            }
            records += CommandRecord(
                command = Redact.secrets(command),
                exitCode = r.exitCode,
                timedOut = r.timedOut,
                output = stored,
            )
            return r.output
        }

        log("检查 root 权限…")
        val idOut = sh("id")
        val rootId = if (idOut.contains("uid=0")) idOut.trim() else null

        val device = sortedMapOf<String, String>()
        for (p in DEVICE_PROPS) {
            val v = sh("getprop $p").trim()
            if (v.isNotEmpty()) device[p] = v
        }

        val kernel = sh("cat /proc/version").trim()
        val iwListRaw = sh("/vendor/bin/iw list", timeoutMs = 90_000)
        val iw = IwListParser.parse(iwListRaw)
        val dumpsysRaw = sh("dumpsys wifi", timeoutMs = 90_000)
        val dumpsys = DumpsysProbe.extract(dumpsysRaw)
        val apMask = DumpsysProbe.extractApCapabilityMask(dumpsysRaw)
        val chan6g = DumpsysProbe.extract6gChannelList(dumpsysRaw)

        for (c in READONLY_COMMANDS) sh(c, timeoutMs = 30_000)

        // ---- 反射：真实常量名与数值 ----
        log("反射读取能力位常量…")
        val softApCaps = ReflectionProbe.longConstants("android.net.wifi.SoftApCapability")
        val wmFeatures = ReflectionProbe.intConstants("android.net.wifi.WifiManager")
            .filterKeys { it.startsWith("WIFI_FEATURE_") }
        val standards = ReflectionProbe.intConstants("android.net.wifi.ScanResult")
            .filterKeys { it.startsWith("WIFI_STANDARD_") }

        val maskDecoded = apMask?.let { ReflectionProbe.decodeMask(it, softApCaps) } ?: emptyList()

        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val apiCalls = if (wm == null) emptyList() else ReflectionProbe.probeWifiManager(wm)

        val builderMethods = ReflectionProbe.softApBuilderMethods()
        val configGetters = ReflectionProbe.softApConfigurationGetters()

        // ---- 关键判定 ----
        val band6g = iw.bands.firstOrNull { (it.minFreq ?: 0) >= 5925 }
        val verdict6g = when {
            band6g == null -> "❌ iw list 里根本没有 6GHz 频段 → 固件/BDF 层硬阉，改国码无用"
            band6g.fullyDisabled ->
                "🟡 6GHz 频段被完整枚举（${band6g.channels.size} 个信道）但**全部 disabled** " +
                    "→ 法规层软件锁，改国码有真实胜算"
            else -> "✅ 6GHz 有 ${band6g.enabledCount} 个信道可用"
        }
        val verdictEht = if (iw.ehtApBands.isNotEmpty())
            "✅ 驱动宣告 EHT(11be) AP 能力，频段: ${iw.ehtApBands}"
        else
            "❌ iw list 未出现 'EHT Iftypes: AP'"

        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        // ---- 人读文本 ----
        val text = buildString {
            appendLine("fab-hotspot 能力体检报告")
            appendLine("生成时间: $ts")
            appendLine("=".repeat(72))
            appendLine()
            appendLine("[ROOT]")
            appendLine("  ${rootId ?: "❌ 未获得 root —— 大部分探测不可用，请先授权"}   (context=${rootContext(records)})")
            appendLine()
            appendLine("[设备]")
            device.forEach { (k, v) -> appendLine("  ${k.padEnd(34)} $v") }
            appendLine("  kernel".padEnd(36) + kernel.take(80))
            appendLine()
            appendLine("[★ 关键判定]")
            appendLine("  6GHz   : $verdict6g")
            appendLine("  EHT AP : $verdictEht")
            appendLine("  国码   : ${countryCode(records) ?: "?"}")
            appendLine("  HAL 能力位: SupportedFeatures=${apMask ?: "?"}")
            if (maskDecoded.isNotEmpty()) {
                appendLine("    解码为: ${maskDecoded.joinToString(", ")}")
            }
            appendLine("  SupportedChannelListIn6g = [${chan6g ?: "?"}]")
            appendLine()
            appendLine("[iw list 概要]")
            IwListParser.summarize(iw).lineSequence().forEach { appendLine("  $it") }
            appendLine()
            appendLine("[接口组合]")
            iw.ifaceCombinations.forEach { appendLine("  $it") }
            appendLine()
            if (iw.mloLines.isNotEmpty()) {
                appendLine("[iw list 中 MLO/MLD 行]")
                iw.mloLines.forEach { appendLine("  $it") }
                appendLine()
            }
            appendLine("[框架 API 实测]")
            apiCalls.forEach { appendLine("  ${it.label.padEnd(52)} ${it.outcome}") }
            appendLine()
            appendLine("[SoftApConfiguration.Builder 存在的 setter]")
            builderMethods.forEach { appendLine("  $it") }
            appendLine()
            appendLine("[SoftApCapability 常量（反射真实值）]")
            softApCaps.forEach { (k, v) -> appendLine("  ${k.padEnd(46)} 0x${v.toString(16)}") }
            appendLine()
            appendLine("[WifiManager.WIFI_FEATURE_* 常量]")
            wmFeatures.forEach { (k, v) -> appendLine("  ${k.padEnd(46)} 0x${v.toString(16)}") }
            appendLine()
            appendLine("[ScanResult.WIFI_STANDARD_* 常量]")
            standards.forEach { (k, v) -> appendLine("  ${k.padEnd(46)} $v") }
            appendLine()
            appendLine("[dumpsys wifi 摘录]")
            dumpsys.forEach { (k, lines) ->
                appendLine("  -- $k")
                lines.forEach { appendLine("     $it") }
            }
            appendLine()
            appendLine("[原始命令与输出]")
            records.forEach { r ->
                appendLine("-".repeat(72))
                appendLine("\$ ${r.command}    (exit=${r.exitCode}${if (r.timedOut) ", TIMEOUT" else ""})")
                if (r.output.isNotEmpty()) appendLine(r.output)
            }
        }

        // ---- JSON ----
        val json = JSONObject().apply {
            put("tool", "fab-hotspot-probe")
            put("phase", "1")
            put("generatedAt", ts)
            put("root", rootId ?: JSONObject.NULL)
            put("device", jsonOf(device))
            put("kernel", kernel)
            put("verdict", JSONObject().apply {
                put("band6g", verdict6g)
                put("ehtAp", verdictEht)
                put("countryCode", countryCode(records) ?: JSONObject.NULL)
                put("halApCapabilityMask", apMask ?: JSONObject.NULL)
                put("halApCapabilityDecoded", JSONArray(maskDecoded))
                put("supportedChannelListIn6g", chan6g ?: JSONObject.NULL)
            })
            put("iw", JSONObject().apply {
                put("bands", JSONArray(iw.bands.map { b ->
                    JSONObject().apply {
                        put("index", b.index)
                        put("channels", b.channels.size)
                        put("enabled", b.enabledCount)
                        put("disabled", b.disabledCount)
                        put("fullyDisabled", b.fullyDisabled)
                        put("minFreqMhz", b.minFreq ?: JSONObject.NULL)
                        put("maxFreqMhz", b.maxFreq ?: JSONObject.NULL)
                        put("freqs", JSONArray(b.channels.map { it.freqMhz }))
                    }
                }))
                put("ifaceCombinations", JSONArray(iw.ifaceCombinations))
                put("heApBands", JSONArray(iw.heApBands))
                put("ehtApBands", JSONArray(iw.ehtApBands))
                put("mloLines", JSONArray(iw.mloLines))
                put("deviceSupportLines", JSONArray(iw.deviceSupportLines))
            })
            put("apiCalls", JSONArray(apiCalls.map {
                JSONObject().put("label", it.label).put("outcome", it.outcome)
            }))
            put("softApBuilderMethods", JSONArray(builderMethods))
            put("softApConfigurationGetters", JSONArray(configGetters))
            put("softApCapabilityConstants", jsonOf(softApCaps))
            put("wifiManagerFeatureConstants", jsonOf(wmFeatures))
            put("wifiStandardConstants", jsonOf(standards))
            put("dumpsys", jsonOf(dumpsys))
            put("commands", JSONArray(records.map { r ->
                JSONObject().apply {
                    put("command", r.command)
                    put("exitCode", r.exitCode)
                    put("timedOut", r.timedOut)
                    put("output", r.output)
                }
            }))
        }.toString(2)

        return ProbeOutcome(json, text)
    }

    private fun jsonOf(map: Map<*, *>): JSONObject = JSONObject().apply {
        map.forEach { (k, v) -> put(k.toString(), jsonValue(v)) }
    }

    private fun jsonValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> jsonOf(v)
        is Collection<*> -> JSONArray(v.map { jsonValue(it) })
        else -> v
    }

    private fun rootContext(records: List<CommandRecord>): String =
        records.firstOrNull { it.command == "id" }?.output?.trim()?.take(120) ?: "?"

    private fun countryCode(records: List<CommandRecord>): String? =
        records.firstOrNull { it.command.startsWith("cmd wifi get-country-code") }
            ?.output?.trim()?.takeIf { it.isNotEmpty() }
}
