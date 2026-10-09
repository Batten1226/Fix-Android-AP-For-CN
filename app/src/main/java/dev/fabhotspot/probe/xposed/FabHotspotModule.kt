package dev.fabhotspot.probe.xposed

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

private const val TAG = "FabHotspotMod"

private const val PKG_SYSTEM = "android"

private const val CLS_SYSTEM_SERVICE_MANAGER = "com.android.server.SystemServiceManager"
private const val CLS_WIFI_NATIVE = "com.android.server.wifi.WifiNative"
private const val CLS_WIFI_SERVICE_IMPL = "com.android.server.wifi.WifiServiceImpl"
private const val CLS_WIFI_GLOBALS = "com.android.server.wifi.WifiGlobals"
private const val CLS_AP_CONFIG_UTIL = "com.android.server.wifi.util.ApConfigUtil"
private const val CLS_WIPHY_CAPS = "android.net.wifi.nl80211.DeviceWiphyCapabilities"
private const val CLS_COUNTRY_CODE = "com.android.server.wifi.WifiCountryCode"

/** Wifi 主line 服务类的包前缀，用于从 startService 的参数里认出它 */
private const val WIFI_SVC_PREFIX = "com.android.server.wifi."

private const val STD_11BE = 8

/** 桥接 AP / STA+桥接AP 并发 的 feature code（WifiServiceImpl.isFeatureSupported(int)） */
private val BRIDGED_FEATURE_CODES = intArrayOf(41, 42)

/** 国码强制开关：非空才生效。`setprop persist.fabhotspot.country US` 打开。 */
private const val PROP_FORCE_COUNTRY = "persist.fabhotspot.country"

/**
 * 目标国码。起 5G/6GHz 热点时框架会把框架认定的 CN 推给 AP 网卡、抹掉驱动自管区域，
 * 这里把它改写成目标国码。**必须与 Magisk 模块的 `country_code=US` 保持一致。**
 */
private const val TARGET_COUNTRY = "US"

/** 6GHz 的默认 PSC 信道：37（6135 MHz）。`SoftApConfiguration.BAND_6GHZ = 4`。 */
private const val BAND_6GHZ_ONLY = 4
private const val SIX_GHZ_PSC_FREQ = 6135
private const val SIX_GHZ_MIN = 5955
private const val SIX_GHZ_MAX = 7115

/**
 * fab-hotspot 的 LSPosed 模块（与体检 App 同一个 APK）。
 *
 * 钩子依据来自对 `service-wifi.jar` 的反编译（计划 §4.10 / §4.12）：
 *  - 11be 热点的唯一瓶颈：`ApConfigUtil.is11beAllowedForThisConfiguration` 里的
 *    `caps.isWifiStandardSupported(8)`，读的是 **AP 接口** 的 DeviceWiphyCapabilities。
 *  - AP MLO 闸门：`WifiGlobals.isMLDApSupported()` + `WifiNative.isMLDApSupportMLO()` /
 *    `isMultipleMLDSupportedOnSap()`；实测 `config_wifiSoftApMaxNumberMLDSupported = 0`。
 *  - bridged AP：`WifiServiceImpl.isFeatureSupported(41|42)`，实测两者都未置位。
 *  - 国码**默认不动**：实测会让驱动 `hdd_reg_notifier` 失败、SAP 所有频段都起不来（§4.12）。
 *
 * 踩过的坑（重要）：
 *  1. `handleLoadPackage("android")` 跑在 SystemServer.startBootstrapServices 阶段，
 *     `framework-wifi.jar` 的类已可用，但 `service-wifi.jar` 的类还不行 → 所以必须延后挂钩。
 *  2. `service-wifi.jar` 的类**不在** lpparam.classLoader、不在 boot classLoader、也不在
 *     ActivityThread/WifiManager 的加载器上（实测 5 个候选全部 false）→ 猜不出来。
 *  3. 解法：hook `SystemServiceManager#startService(Class)`，框架启动每个系统服务时会把
 *     Class 对象递进来，其中 `clazz.classLoader` 就是对的那个。
 */
class FabHotspotModule : IXposedHookLoadPackage {

    @Volatile
    private var wifiHooked = false

    private var loggedCaps = false
    private var loggedCountry = false
    private var loggedApCc = false
    private var loggedSixGhzCh = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PKG_SYSTEM) return
        val cl = lpparam.classLoader ?: return

        log("==== fab-hotspot 模块加载进 system_server ====")

        // 1) framework-wifi.jar 的类：开机早期就可用，立即挂
        hook11BeCaps(cl)

        // 2) 等框架把 WifiService 的 Class 交出来，再用它的 classLoader 挂服务类
        hookServiceManager(cl)

        log("==== 首轮完成 ====")
    }

    // ------------------------------------------------ 11be（立即，framework 层）

    private fun hook11BeCaps(cl: ClassLoader) {
        hookAll(cl, CLS_WIPHY_CAPS, "isWifiStandardSupported") { param ->
            if ((param.args.getOrNull(0) as? Int) == STD_11BE) {
                param.result = true
                if (!loggedCaps) {
                    loggedCaps = true
                    log("★ DeviceWiphyCapabilities.isWifiStandardSupported(11BE) -> true")
                }
            }
        }
    }

    // ------------------------------------------- 从 startService 拿正确的类加载器

    private fun hookServiceManager(cl: ClassLoader) {
        val c = loadClass(CLS_SYSTEM_SERVICE_MANAGER, cl)
        if (c == null) {
            log("!! 找不到 $CLS_SYSTEM_SERVICE_MANAGER（用 lpparam 加载器）")
            return
        }
        runCatching {
            XposedBridge.hookAllMethods(c, "startService", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (wifiHooked) return
                    val arg0 = param.args.getOrNull(0) ?: return
                    val svcClass = when (arg0) {
                        is Class<*> -> arg0
                        is String -> if (arg0.startsWith(WIFI_SVC_PREFIX)) {
                            runCatching { Class.forName(arg0, false, cl) }.getOrNull()
                        } else null
                        else -> null
                    } ?: return

                    val name = svcClass.name ?: return
                    if (!name.startsWith(WIFI_SVC_PREFIX)) return

                    log("★ 捕获到 Wifi 服务类 $name，其 classLoader = ${svcClass.classLoader}")
                    if (arg0 is String) return // 字符串重载拿不到 Class 时忽略，等 Class 重载
                    wifiHooked = true
                    hookWifiService(svcClass.classLoader)
                }
            })
            log("hook  $CLS_SYSTEM_SERVICE_MANAGER#startService")
        }.onFailure { log("!! 失败 SystemServiceManager#startService : ${it.message}") }
    }

    // ------------------------------------------------- service 层（拿到加载器后）

    private fun hookWifiService(cl: ClassLoader?) {
        log("---- 开始挂 Wifi 服务类钩子（classLoader=$cl）----")

        // 11be 兜底：给 WifiNative 返回的 caps 补上 11BE 位
        hookAfterAll(cl, CLS_WIFI_NATIVE, "getDeviceWiphyCapabilities") { param ->
            val caps = param.result ?: return@hookAfterAll
            runCatching { XposedHelpers.callMethod(caps, "setWifiStandardSupport", STD_11BE, true) }
        }

        // AP MLO 闸门
        forceTrue(cl, CLS_WIFI_GLOBALS, "isMLDApSupported")
        forceTrue(cl, CLS_WIFI_NATIVE, "isMLDApSupportMLO")
        forceTrue(cl, CLS_WIFI_NATIVE, "isMultipleMLDSupportedOnSap")
        forceTrue(cl, CLS_AP_CONFIG_UTIL, "isIeee80211beSupported")
        forceTrue(cl, CLS_AP_CONFIG_UTIL, "is11beAllowedForThisConfiguration")
        forceTrue(cl, CLS_AP_CONFIG_UTIL, "hasAvailableMLD")
        hookAll(cl, CLS_AP_CONFIG_UTIL, "getMaximumSupportedMLD") { param -> param.result = 2 }

        // bridged AP
        hookAll(cl, CLS_WIFI_SERVICE_IMPL, "isFeatureSupported") { param ->
            val code = param.args.getOrNull(0) as? Int ?: return@hookAll
            if (code in BRIDGED_FEATURE_CODES) param.result = true
        }

        // 国码（可选，靠 persist 属性开关）
        hookCountryCode(cl)

        // ★ 6GHz 的关键：拦住框架把国码下发给 native
        hookCountrySuppress(cl)

        // ★ 6GHz 的关键之二：起 5G/6GHz 热点时框架推给 AP 网卡的国码要改写成目标国码
        hookApCountryCode(cl)

        // ★ 6GHz 的关键之三：自动补 6GHz 信道，让系统 UI / 无 -f 的路径也能起
        hookSixGhzDefaultChannel(cl)

        // 诊断：把最终交给原生 HAL 的 hwMode 参数打出来
        hookHostapdDiag(cl)

        log("---- Wifi 服务类钩子挂载完毕 ----")
    }

    /**
     * 诊断：`HostapdHalAidlImp.prepareHwModeParams` 决定 `enable80211BE` / `enable6GhzBand`，
     * 这些字段最终由原生 HAL 写成 hostapd 的 `ieee80211be=1` / `eht_*`。
     * 把它们的实际值打出来，才能知道 11be 是在哪一步被关掉的。
     */
    private fun hookHostapdDiag(cl: ClassLoader?) {
        hookAfterAll(cl, "com.android.server.wifi.HostapdHalAidlImp", "prepareHwModeParams") { param ->
            val r = param.result ?: return@hookAfterAll
            fun f(n: String): Any? = runCatching { XposedHelpers.getObjectField(r, n) }.getOrNull()
            log(
                "★ hwModeParams: 11BE=${f("enable80211BE")} 11AX=${f("enable80211AX")} " +
                    "11AC=${f("enable80211AC")} 6GHz=${f("enable6GhzBand")} " +
                    "maxBW=${f("maximumChannelBandwidth")}"
            )
        }
        // 确认我们挂在 ApConfigUtil.isIeee80211beSupported 上的钩子真的被调到
        hookAfterAll(cl, CLS_AP_CONFIG_UTIL, "isIeee80211beSupported") { param ->
            log("★ ApConfigUtil.isIeee80211beSupported() -> ${param.result}")
        }
    }

    private fun hookCountryCode(cl: ClassLoader?) {
        val forced = readProp(PROP_FORCE_COUNTRY)
        if (forced.isNullOrBlank()) {
            log("未设置 $PROP_FORCE_COUNTRY，国码保持系统默认")
            return
        }
        log("!! 国码强制为目标 = $forced（实测可能导致 SAP 在所有频段都起不来）")
        hookAfterAll(cl, CLS_COUNTRY_CODE, "getCountryCode") { param ->
            param.result = forced
            if (!loggedCountry) {
                loggedCountry = true
                log("★ WifiCountryCode.getCountryCode() -> $forced")
            }
        }
        hookAll(cl, CLS_COUNTRY_CODE, "setOverrideCountryCode") { param ->
            param.args[0] = forced
        }
    }

    /**
     * 国行 HyperOS 的国码特判：`WifiCountryCode.updateCountryCode` 在
     * `ro.miui.build.region == "CN"` 时会把国码**下发**给 native；非国行机型走 802.11d、
     * 根本不下发。实测：Magisk 模块让驱动以 `country_code=US` 起来后，开机约 10 秒
     * 正是被这一下覆盖回 `CN` 的（时间线：wall 7s 还是 `country US: DFS-FCC`，11s 变 `CN`）。
     *
     * 这里让这个派发点直接返回，框架不再下发国码，驱动就保持自管区域 US —— 6GHz 信道表随之解锁。
     * 代价是漫游时不再跟随电话网络的国码，改由驱动自己的 802.11d 处理；停用本模块即恢复。
     */
    private fun hookCountrySuppress(cl: ClassLoader?) {
        var logged = false
        hookAll(cl, CLS_COUNTRY_CODE, "updateCountryCode") { param ->
            if (!logged) {
                logged = true
                val picked = runCatching {
                    XposedHelpers.callMethod(param.thisObject, "pickCountryCode", false)
                }.getOrNull()
                log("★ 已拦截国码下发：WifiCountryCode.updateCountryCode（框架本想下发 $picked）")
            }
            param.setResult(null)
        }
    }

    /**
     * 第二个国码下发点。`SoftApManager.setCountryCode()`（Java 层 :529）在起 **5GHz / 6GHz**
     * 热点时（`band == 2 || band == 4`）会把 `mCountryCode`（框架认定的 CN）**直接推给 AP 网卡**
     * `wlan2`：`WifiNative.setApCountryCode` → `WifiVendorHal` → AIDL `IWifiApIface.setCountryCode`。
     * 2.4GHz 不受影响，所以这条路一直没暴露。
     *
     * 实测后果：起 6GHz 热点时能力表就在这一步从
     *   `SupportedChannelListIn6g[59 个信道] + mCountryCodeFromDriverUS`
     * 掉回
     *   `SupportedChannelListIn6g[] + mCountryCodeFromDriverCN`
     * 随后 `SoftApState{mState=14}` 启动失败。
     *
     * 这里把下发的国码改写成目标国码：既保留框架的状态机（仍返回成功、仍触发 change listener），
     * 又不会抹掉驱动自管区域。
     */
    private fun hookApCountryCode(cl: ClassLoader?) {
        hookAll(cl, CLS_WIFI_NATIVE, "setApCountryCode") { param ->
            val cc = param.args.getOrNull(1) as? String
            if (cc != null && !cc.equals(TARGET_COUNTRY, ignoreCase = true)) {
                if (!loggedApCc) {
                    loggedApCc = true
                    log("★ 改写 AP 网卡国码：$cc -> $TARGET_COUNTRY（否则会抹掉驱动自管区域 US）")
                }
                param.args[1] = TARGET_COUNTRY
            }
        }
    }

    /**
     * 让**系统热点 UI** 也能开 6 GHz —— 也就是"从图形界面一键启用"的那一步。
     *
     * 闸门在 `ApConfigUtil.updateApChannelConfig()`（:549）：
     * ```java
     * if (softApConfiguration.getChannel() == 0) {
     *     int freq = chooseApChannel(getBand(), ...);   // ← :560
     *     if (freq == -1) return FAILURE;               // ← :561，直接判失败
     *     builder.setChannel(convertFrequencyMhzToChannel(freq), convertFrequencyToBand(freq));
     * }
     * ```
     * UI 默认走"自动选信道"（`channel == 0`，小米还有 `setHotSpotFastAcs()`），
     * 而 6 GHz 下 `chooseApChannel` 返回 **-1**（驱动无法为 ACS 提供 6 GHz 频道，
     * hostapd 侧表现为 `ACS: Offloading to driver` → `Could not select hw_mode and channel`）。
     * 这就是「不带 `-f` 的 `start-softap -b 6` 必失败」「系统 UI 开 6GHz 必失败」的同一个根因。
     *
     * 这里只在 **band 恰好是 6 GHz（`BAND_6GHZ = 4`）** 且原结果不是合法 6 GHz 频率时，
     * 补上 PSC 信道 37（6135 MHz）。不涉及 6 GHz 时**完全不动**，
     * 因此 2.4 / 5 GHz、`-b any`（band=7）等既有行为一律不受影响。
     */
    private fun hookSixGhzDefaultChannel(cl: ClassLoader?) {
        hookAfterAll(cl, CLS_AP_CONFIG_UTIL, "chooseApChannel") { param ->
            val band = param.args.getOrNull(0) as? Int ?: return@hookAfterAll
            if (band != BAND_6GHZ_ONLY) return@hookAfterAll          // 只接管纯 6GHz
            val orig = param.result as? Int
            if (orig != null && orig in SIX_GHZ_MIN..SIX_GHZ_MAX) return@hookAfterAll  // 已给出合法 6GHz 频率
            if (!loggedSixGhzCh) {
                loggedSixGhzCh = true
                log("★ 6GHz 自动补信道：chooseApChannel(band=$band) 原结果=$orig -> $SIX_GHZ_PSC_FREQ (PSC ch37)")
            }
            param.result = SIX_GHZ_PSC_FREQ
        }
    }

    // ------------------------------------------------------------ 工具

    private fun loadClass(cn: String, cl: ClassLoader?): Class<*>? =
        runCatching { Class.forName(cn, false, cl) }.getOrNull()

    private inline fun hookAll(
        cl: ClassLoader?,
        className: String,
        methodName: String,
        crossinline before: (XC_MethodHook.MethodHookParam) -> Unit,
    ) {
        val c = loadClass(className, cl)
        if (c == null) {
            log("!! 找不到类 $className")
            return
        }
        runCatching {
            val unhooks = XposedBridge.hookAllMethods(c, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) = before(param)
            })
            // hookAllMethods 在找不到方法时不会抛，只是返回空集合 —— 必须把匹配数打出来
            log("hook  $className#$methodName -> 匹配 ${unhooks.size} 个方法")
        }.onFailure { log("!! 失败 $className#$methodName : ${it.message}") }
    }

    private inline fun hookAfterAll(
        cl: ClassLoader?,
        className: String,
        methodName: String,
        crossinline after: (XC_MethodHook.MethodHookParam) -> Unit,
    ) {
        val c = loadClass(className, cl)
        if (c == null) {
            log("!! 找不到类 $className")
            return
        }
        runCatching {
            val unhooks = XposedBridge.hookAllMethods(c, methodName, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = after(param)
            })
            log("hookA $className#$methodName -> 匹配 ${unhooks.size} 个方法")
        }.onFailure { log("!! 失败 $className#$methodName : ${it.message}") }
    }

    private fun forceTrue(cl: ClassLoader?, className: String, methodName: String) =
        hookAll(cl, className, methodName) { param -> param.result = true }

    private fun readProp(key: String): String? {
        val c = runCatching { Class.forName("android.os.SystemProperties") }.getOrNull() ?: return null
        return runCatching { XposedHelpers.callStaticMethod(c, "get", key, "") as? String }.getOrNull()
    }

    private fun log(msg: String) = Log.i(TAG, msg)
}
