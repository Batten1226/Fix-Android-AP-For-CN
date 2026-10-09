package dev.fabhotspot.probe.probe

import android.net.wifi.WifiManager

/**
 * 用反射把框架里真实存在（或不存在）的能力位/方法挖出来。
 * 这是对计划 §4.8 里用 grep 挖出的名字做**运行时交叉验证** ——
 * 静态字符串有/无不是能力证据（详见 §4.9 的教训），必须实测。
 */
object ReflectionProbe {

    private fun cls(name: String): Class<*>? =
        try {
            Class.forName(name)
        } catch (_: Throwable) {
            null
        }

    fun intConstants(className: String): Map<String, Int> {
        val out = sortedMapOf<String, Int>()
        val c = cls(className) ?: return out
        for (f in c.fields) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            if (f.type != Int::class.javaPrimitiveType) continue
            try {
                out[f.name] = f.getInt(null)
            } catch (_: Throwable) {
            }
        }
        return out
    }

    fun longConstants(className: String): Map<String, Long> {
        val out = sortedMapOf<String, Long>()
        val c = cls(className) ?: return out
        for (f in c.fields) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            if (f.type != Long::class.javaPrimitiveType) continue
            try {
                out[f.name] = f.getLong(null)
            } catch (_: Throwable) {
            }
        }
        return out
    }

    /** 列出公开方法签名，可按前缀过滤。 */
    fun publicMethods(className: String, prefix: String = ""): List<String> {
        val c = cls(className) ?: return emptyList()
        return c.methods.asSequence()
            .filter { it.name.startsWith(prefix) }
            .map { m -> m.name + "(" + m.parameterTypes.joinToString(", ") { it.simpleName } + ")" }
            .distinct()
            .sorted()
            .toList()
    }

    /**
     * 位掩码解码：把 mask 中置位的常量挑出来。
     * 注意：复合常量（多位置位）若全部位都在 mask 内也会被列出，属于已知的宽松匹配。
     */
    fun decodeMask(mask: Long, constants: Map<String, Long>): List<String> =
        constants.entries
            .filter { it.value != 0L && (it.value and mask) == it.value }
            .map { "${it.key}=0x${it.value.toString(16)}" }
            .sorted()

    data class CallResult(val label: String, val outcome: String)

    fun probeWifiManager(mgr: WifiManager): List<CallResult> {
        val rows = mutableListOf<CallResult>()

        fun add(label: String, block: () -> Any?) {
            val outcome = try {
                "ok: " + (block()?.toString() ?: "null")
            } catch (t: Throwable) {
                t.javaClass.simpleName + ": " + (t.message ?: "")
            }
            rows += CallResult(label, outcome)
        }

        add("WifiManager.isWifiEnabled") { mgr.isWifiEnabled }
        add("WifiManager.is6GHzBandSupported()") { mgr.is6GHzBandSupported }
        // getSupportedFeatures() 已 deprecated，在 API 36 的 SDK stub 里可能不可见 —— 故用反射
        add("WifiManager.getSupportedFeatures()") {
            val v = WifiManager::class.java.getMethod("getSupportedFeatures").invoke(mgr) as Int
            "0x" + v.toString(16)
        }

        val standards = intConstants("android.net.wifi.ScanResult")
            .filterKeys { it.startsWith("WIFI_STANDARD_") }
        for ((name, value) in standards) {
            add("isWifiStandardSupported($name=$value)") { mgr.isWifiStandardSupported(value) }
        }

        // 隐藏 / 系统 API —— 预期可能抛 SecurityException 或 NoSuchMethodException。
        // 如实记录失败本身就是体检结果（说明这条路要从 root/app_process 走）。
        add("getSoftApSupportedFeatures() [隐藏API]") {
            WifiManager::class.java.getMethod("getSoftApSupportedFeatures").invoke(mgr)
        }
        add("getCountryCode() [需定位权限]") {
            WifiManager::class.java.getMethod("getCountryCode").invoke(mgr)
        }
        return rows
    }

    fun softApBuilderMethods(): List<String> =
        publicMethods("android.net.wifi.SoftApConfiguration\$Builder", "set")

    fun softApConfigurationGetters(): List<String> =
        (publicMethods("android.net.wifi.SoftApConfiguration", "get") +
            publicMethods("android.net.wifi.SoftApConfiguration", "is")).sorted()
}
