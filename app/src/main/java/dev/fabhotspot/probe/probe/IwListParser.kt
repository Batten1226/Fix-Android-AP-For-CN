package dev.fabhotspot.probe.probe

/**
 * 解析 `/vendor/bin/iw list`。
 *
 * 最关键的一条：6GHz 频段里所有信道是否都被标记 (disabled)。
 * 若信道被完整枚举但全 disabled => 法规层软件锁（可尝试改国码）。
 * 若频段根本不出现      => 固件/BDF 层硬阉（改国码也救不回来）。
 */
object IwListParser {

    data class Channel(
        val freqMhz: Int,
        val num: Int,
        val disabled: Boolean,
        val radar: Boolean,
        val noIr: Boolean,
    )

    data class Band(val index: Int, val channels: List<Channel>) {
        val enabledCount get() = channels.count { !it.disabled }
        val disabledCount get() = channels.count { it.disabled }
        val fullyDisabled get() = channels.isNotEmpty() && disabledCount == channels.size
        val minFreq get() = channels.minOfOrNull { it.freqMhz }
        val maxFreq get() = channels.maxOfOrNull { it.freqMhz }
    }

    data class Result(
        val bands: List<Band>,
        val ifaceCombinations: List<String>,
        val ehtApBands: List<Int>,
        val heApBands: List<Int>,
        val mloLines: List<String>,
        val deviceSupportLines: List<String>,
    ) {
        fun band(index: Int) = bands.firstOrNull { it.index == index }
    }

    private val BAND_RE = Regex("^\\s*Band (\\d+):\\s*$")
    private val CHAN_RE = Regex("^\\s*\\*\\s*(\\d+) MHz \\[(\\d+)\\](.*)$")

    fun parse(text: String): Result {
        val bands = mutableListOf<Band>()
        val chans = mutableListOf<Channel>()
        val combos = mutableListOf<String>()
        val ehtAp = mutableListOf<Int>()
        val heAp = mutableListOf<Int>()
        val mlo = mutableListOf<String>()
        val devSupport = mutableListOf<String>()

        var cur = -1
        var inCombos = false

        fun flush() {
            if (cur >= 0) bands += Band(cur, chans.toList())
            chans.clear()
        }

        for (line in text.lineSequence()) {
            val bandMatch = BAND_RE.find(line)
            if (bandMatch != null) {
                flush()
                cur = bandMatch.groupValues[1].toInt()
                inCombos = false
                continue
            }

            if (line.contains("valid interface combinations")) {
                inCombos = true
                continue
            }

            if (inCombos) {
                val t = line.trim()
                if (t.isEmpty()) {
                    inCombos = false
                } else if (t.startsWith("*") || t.startsWith("total")) {
                    combos += t
                }
                continue
            }

            val chanMatch = CHAN_RE.find(line)
            if (chanMatch != null) {
                val rest = chanMatch.groupValues[3]
                chans += Channel(
                    freqMhz = chanMatch.groupValues[1].toInt(),
                    num = chanMatch.groupValues[2].toInt(),
                    disabled = rest.contains("(disabled)"),
                    radar = rest.contains("radar detection"),
                    noIr = rest.contains("no IR") || rest.contains("no-ir"),
                )
                continue
            }

            if (line.contains("Device supports") || line.contains("Device has") ||
                line.contains("Device accepts")
            ) {
                devSupport += line.trim()
            }

            if (cur >= 0) {
                if (line.contains("EHT Iftypes: AP") && cur !in ehtAp) ehtAp += cur
                if (line.contains("HE Iftypes: AP") && cur !in heAp) heAp += cur
            }

            if (line.contains("MLO", ignoreCase = true) || line.contains("MLD", ignoreCase = true)) {
                mlo += line.trim()
            }
        }
        flush()

        return Result(
            bands = bands,
            ifaceCombinations = combos.distinct(),
            ehtApBands = ehtAp,
            heApBands = heAp,
            mloLines = mlo.distinct(),
            deviceSupportLines = devSupport.distinct(),
        )
    }

    fun summarize(r: Result): String = buildString {
        appendLine("bands found : ${r.bands.map { it.index }.joinToString(", ")}")
        for (b in r.bands) {
            append("  Band ${b.index}: ${b.channels.size} ch")
            append("  (可用 ${b.enabledCount} / disabled ${b.disabledCount})")
            append("  freq ${b.minFreq}..${b.maxFreq} MHz")
            if (b.fullyDisabled) append("   <<< 全频段 disabled")
            appendLine()
        }
        appendLine("HE  AP bands : ${r.heApBands}")
        appendLine("EHT AP bands : ${r.ehtApBands}      <<< 非空即驱动宣告 11be AP 能力")
        appendLine("MLO/MLD 行数 : ${r.mloLines.size}")
        appendLine("接口组合条数 : ${r.ifaceCombinations.size}")
    }
}
