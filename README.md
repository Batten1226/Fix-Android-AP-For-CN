# Fix Android AP For CN

在**国行小米设备**上，解锁被地区限制的**热点（SoftAP）**能力：Wi-Fi 7（802.11be / EHT）、
6 GHz 频段、5 GHz 160 MHz 大频宽、bridged AP（2.4+5 双频并发）、AP MLO 等。

---

## ⚠️ 免责声明（务必先读）

本工具会**绕过地区无线电法规**：例如中国大陆并未把 6 GHz 频段分配给 WLAN，
而本项目会强制设备改用其他国家的法规域。

- **使用前请自行确认你所在地区的无线电法规是否允许**使用相应频段与发射功率
- 因使用本工具导致的任何后果（设备异常、无线电干扰、法律合规问题）**由使用者自行承担**
- 本项目按 **GPL-3.0** 分发，**不提供任何担保**
- 需要 **root（Magisk）+ LSPosed**，会修改系统运行时的行为

---

## 目标能力与当前状态

> 下表是**实测**结果，不是设计目标。做不到的会明确标出来，不做永远失败的开关。

| 能力 | 状态 | 说明 |
|---|---|---|
| **6 GHz 热点** | ✅ **已实测可用** | 需要 Magisk 国码模块 + LSPosed 双钩子，**且必须显式指定 PSC 信道** |
| **6 GHz 160 MHz** | ✅ **已实测可用** | `SoftApInfo{frequency=6135}` + hostapd `AP-ENABLED` |
| 5 GHz **160 MHz** | ✅ **已可用** | `-w 160` 直接生效，无需改法规域 |
| 2.4 GHz | ✅ **已可用** | — |
| Wi-Fi 7 (**802.11be / EHT**) | ❌ **实测不可用** | 见下文「已知阻塞 · 2」 |
| **320 MHz** | ❌ **不可用** | EHT 专有带宽，随 11be |
| bridged AP（2.4+5 并发） | 🟡 钩子已实现，待验证 | `WifiServiceImpl.isFeatureSupported(41/42)` |
| AP **MLO** | 🟡 钩子已实现，待验证 | 总闸门是 `config_wifiSoftApMaxNumberMLDSupported=0` |
| 三频并发 2.4+5+6 | ❌ **硬件不支持** | 芯片只有 2 个射频（`#channels <= 2`） |
| 隐藏 SSID / 自定义 BSSID | ✅ 原生 API 支持 | `SoftApConfiguration.Builder` |
| WPA3 / WPA3 过渡 | ✅ 原生已开 | hostapd 已是 `WPA-PSK SAE` |

---

## 已知阻塞与解锁机制（全部为实测结论）

### 1. 6 GHz：✅ 已解锁 —— 三个部件缺一不可

单靠框架或单靠钩子都拿不到 6 GHz。实测可用的组合是：

**① Magisk 模块（驱动级国码）**
- `/sys/module/kiwi_v2/parameters/country_code` 是 `-r--r--r--`，**运行时无法写**，唯一注入点是模块加载瞬间的 `insmod` 命令行参数
- 文件覆盖路线**已实测无效**（Magisk 的模块挂载比驱动读配置更晚）
- 可用做法：`post-fs-data.sh` 里 `rmmod` → 等 Magisk 的 INI 覆盖层就绪 → `insmod … country_code=US`
- 覆盖 `WCNSS_qcom_cfg.ini`，**逐字节只改一行** `gCountryCodePriority=0 → 1`（驱动自己的国码优先）
- 效果：`iw reg get` 的 `phy#1 (self-managed)` 变成 `country US: DFS-FCC`，含 `(5945-7125 @160)` 与 **`(5925-7125 @320)`**

**② LSPosed 钩子 A —— 拦住框架的国码下发**
`WifiCountryCode#updateCountryCode` → `setResult(null)`。
依据是**国行 HyperOS 的特判**（`WifiCountryCode:419`）：只有 `ro.miui.build.region == "CN"` 才会走下发分支，非国行机型依赖 802.11d 根本不下发。
实测：不加钩子时驱动在开机约 10 秒被翻回 `CN`；加上后全程保持 `US: DFS-FCC`。

**③ LSPosed 钩子 B —— 改写 AP 网卡的下发国码**
`WifiNative#setApCountryCode(iface, cc)` 把 `cc` 改写成目标国码。
依据：`SoftApManager.setCountryCode()`（`:529`）在起 **5 GHz / 6 GHz** 热点时会把框架认定的 CN 直接推给 `wlan2`，能力表随即从 `In6g[59 个信道] / mCountryCodeFromDriverUS` 掉回 `In6g[] / mCountryCodeFromDriverCN`，SAP 启动失败。

**④ 必须显式指定 PSC 信道（否则 ACS 必失败）**
```bash
# -b 6 才是 6GHz（-b 8 报 Invalid band option）；-f 必须是最后一个参数
cmd wifi start-softap <ssid> wpa3 <pw> -b 6 -w 160 -f 6135   # ch37 = 6135 MHz
```
失败时 hostapd 报 `Configured channel (0) … not found from the channel list of the current mode (2) IEEE 802.11a` + `ACS: Offloading to driver`
—— 即**驱动返回不了 6 GHz 频道给 ACS**，不是信道表没解锁。非 PSC 信道（如 ch1 = 5955）也会失败。

> **⚠️ 必须避开 `cmd wifi force-country-code`**：框架级法规域覆盖会让驱动进入不一致状态，
> **所有频段**（包括本来能用的 5 GHz）的热点都起不来：
> ```
> kiwi_v2: [E] hdd_reg_notifier: Failed to set country
> kiwi_v2: [E] reg_get_band_from_cur_chan_list: Failed to retrieve the channel list
> ```

### 2. 802.11be：❌ 实测卡在**驱动代际**，单文件替换不可行

**框架侧早已全部放行**（LSPosed 钩子后诊断日志实测）：
```
★ hwModeParams: 11BE=true  11AX=true  11AC=true  6GHz=true  maxBW=7
```

**但 hostapd 发出的 EHT beacon 被内核/驱动拒绝**，而且是**所有频段**：
```
E hostapd: Failed to set beacon parameters → Interface initialization failed
（6 GHz / 5 GHz / 2.4 GHz 全部如此 —— 2.4 GHz 没有任何 EHT 带宽歧义，故排除「配置拼错」）
```

**驱动能力数据（`iw list`，按频段分开看，别只看 Band 1）：**
```
Band4 EHT PHY Capabilities (0x6200000000000000): 320MHz in 6GHz Supported  ← 固件有这个位
Band4 EHT MAC Capabilities (0x0000) / EHT MCS/NSS 全零 / max NSS: Rx=0 Tx=0  ← 但没有可用 EHT 速率
```

**与新一代驱动的实测对比**（取自 LineageOS `fuxi` 构建的 `vendor_dlkm`）：

| | 设备 stock | LineageOS |
|---|---|---|
| 大小 | 17,907,416 | **27,435,480** |
| vermagic | `5.15.78` | `5.15.211-g093e3da978e7` |
| `Disable eht cap for SAP/GO` | **0** | **1** |
| `Configure EHT mode` / `eht_mode` | 0 / 0 | **1 / 7** |
| `ap_mld` / `mlo_ap` / `eht_oper` | 0 / 27 / 1 | **3 / 59 / 6** |

⇒ **新一代 qcacld 驱动带 SAP/GO 专用的 EHT 能力处理，设备那份旧代没有。**

**⚠️ 但它无法直接替换 —— 已实测：**
1. vermagic **可以** patch（内核不再报 version magic 错误）
2. 但接着卡在 **`cnss2` 的符号**：`disagrees about version of symbol qmp_get`（CRC）以及
   `Unknown symbol msm_pcie_dsp_link_control` / `wlfw_aux_uc_info_resp_msg_v01_ei`（**符号在本机模块栈里根本不存在**，`err -2`）
3. ⇒ **换驱动 = 换整套（内核 + 全部 vendor 模块 + 固件），不是替换单个 `.ko`**

**⇒ 现实唯一路径**：等 Xiaomi 为 fuxi 出一份完整配套的新版 vendor + 内核（即新版 ROM）。
（旁证：本机 `ro.vendor.build.fingerprint` 的基线是 **Android 13**（`fuxi:13/TKQ1.221114.001`），
而系统是 Android 16 —— 整个 vendor 栈从未随系统升级过。）

---

## 硬件/系统前提（本项目的实测平台）

| 项 | 值 |
|---|---|
| 机型 | Xiaomi 13（`fuxi`，国行 `2211133C`） |
| SoC | Snapdragon 8 Gen 2（SM8550） |
| WLAN 芯片 | Qualcomm **WCN7850**（FastConnect 7800，硬件支持 11be + 6 GHz） |
| 系统 | HyperOS 3.0 / **Android 16** |
| 内核 | Linux 5.15（`android13-8`） |
| root | Magisk 30.7 |
| 钩子框架 | LSPosed（作用域必须是 **`system`**） |

> 其他机型**未经验证**。即使同为骁龙 8 Gen 2，不同 ROM 的 wifi 配置资源、
> 法规库（`regdb_xiaomi.bin`）、BDF 都可能不同。

---

## 构建

需要：JDK 17、Android SDK（compileSdk 36、build-tools 36.0.0）、Gradle 8.14.x。

```bash
./gradlew assembleDebug
```

生成的 APK 同时是：

1. **能力体检 App** —— 只读探测设备被限制了哪些能力，导出 JSON 报告
2. **LSPosed 模块** —— 在 `system_server` 里挂钩 Wifi 服务

### 使用体检 App

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.fabhotspot.probe/.MainActivity --ez autorun true
```

报告会写到应用外部目录：`/sdcard/Android/data/dev.fabhotspot.probe/files/fabhotspot-report.json`

### 启用 LSPosed 模块

1. 打开 LSPosed 管理器 →「模块」→ 启用 **Fix Android AP For CN**
2. **作用域勾选「系统框架」（`system`）** —— Wifi 主line 服务跑在 `system_server` 里，必须勾
3. 重启手机（`system_server` 的钩子只在开机时加载）

验证：`adb logcat -s FabHotspotMod` 应看到每个钩子的匹配方法数。

### 安装 Magisk 国码模块（6 GHz 的必要条件之一）

```bash
# 打包（必须用 zipfile 写正斜杠，PowerShell 的 Compress-Archive 会用反斜杠导致 Magisk 解错）
python .analysis/make_module_zip.py          # 产物: .analysis/fabhotspot_6g.zip
adb push .analysis/fabhotspot_6g.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/fab6g.zip'
adb reboot
```

**回滚**：Magisk → 模块 → 停用 **FabHotspot 6GHz Driver Country**，重启即可。
模块只做两件事：覆盖 `WCNSS_qcom_cfg.ini` 的 `gCountryCodePriority=0→1`（逐字节只改一行），
以及在 `post-fs-data` 里 `rmmod` → 等覆盖层就绪 → `insmod … country_code=US`。

> **不要用** `persist.fabhotspot.country` 那个旧的"法规域钩子"（`FabHotspotModule` 里仍保留、
> 但默认关闭）：框架级法规域改动会让驱动进入不一致状态，**所有频段**的热点都可能起不来。
> 6 GHz 必须走上面的**驱动级国码**路线。

---

## 项目结构

```
app/src/main/java/dev/fabhotspot/probe/
├── MainActivity.kt              Compose 入口
├── ui/ProbeScreen.kt            体检界面
├── probe/
│   ├── CapabilityProbe.kt       体检编排（只读，产出 JSON 报告）
│   ├── IwListParser.kt          解析 iw list（频段/信道/接口组合/EHT）
│   ├── DumpsysProbe.kt          从 dumpsys wifi 里挑关键行
│   └── ReflectionProbe.kt       反射读框架常量与 API
├── priv/SuShell.kt              唯一提权出口
├── priv/Redact.kt               报告脱敏（口令/MAC）
└── xposed/
    └── FabHotspotModule.kt      LSPosed 钩子（含 6 GHz 的两个国码钩子）

magisk-module/                   6 GHz 国码模块（Magisk）
├── module.prop
├── post-fs-data.sh              rmmod → 等 INI 覆盖层就绪 → insmod country_code=US
├── service.sh                   开机后把生效状态写进 boot.log
└── system/vendor/etc/wifi/kiwi_v2/WCNSS_qcom_cfg.ini
                                 只改一行：gCountryCodePriority=0 → 1
```

**体检 App 是零写入的**：不调 setter、不写 sysfs、不启停热点。
**Magisk 模块是唯一会改系统配置的地方**，且可用 Magisk 的「停用」一键回滚。

---

## 许可证

[GPL-3.0](LICENSE)
