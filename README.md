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
| 5 GHz **160 MHz** | ✅ **已可用** | `-w 160` 直接生效，无需改法规域 |
| **6 GHz** 频段 | 🟡 **信道列表可解锁，热点起不来** | 见下文「已知阻塞」 |
| Wi-Fi 7 (**802.11be / EHT**) | 🟡 框架已放行，卡在原生 HAL | 见下文「已知阻塞」 |
| bridged AP（2.4+5 并发） | 🟡 钩子已实现，待验证 | `WifiServiceImpl.isFeatureSupported(41/42)` |
| AP **MLO** | 🟡 钩子已实现，待验证 | 总闸门是 `config_wifiSoftApMaxNumberMLDSupported=0` |
| 三频并发 2.4+5+6 | ❌ **硬件不支持** | 芯片只有 2 个射频（`#channels <= 2`） |
| 隐藏 SSID / 自定义 BSSID | ✅ 原生 API 支持 | `SoftApConfiguration.Builder` |
| WPA3 / WPA3 过渡 | ✅ 原生已开 | hostapd 已是 `WPA-PSK SAE` |

---

## 已知阻塞（诚实记录）

### 1. 6 GHz：热点起不来，卡在驱动

用框架自带的调试命令改法规域，**信道列表会立刻出现**：

```bash
cmd wifi force-country-code enabled US
# 之后 HAL 的 SupportedChannelListIn6g 从 [] 变成 58 个信道
# 驱动 iw reg get 也从 country CN 变成 country US: DFS-FCC，含 (5925-7125 @320)
```

但**热点在所有频段都起不来**（连本来能用的 5 GHz 也失败），驱动日志给出根因：

```
kiwi_v2: [E] hdd_convert_nl80211_to_reg_band_mask: band: 2 not supported
kiwi_v2: [E] hdd_reg_notifier: Failed to set country
kiwi_v2: [E] reg_get_band_from_cur_chan_list: Failed to retrieve the channel list
kiwi_v2: [E:SAP] sap_get_freq_list: No active channels present for the current region
```

**结论**：框架级法规域覆盖会让驱动进入不一致状态。要真正起 6 GHz 热点，
需要**驱动级**的国码来源（sysfs / INI / 开机脚本），而不是框架 hook。
（`NL80211_BAND_6GHZ = 2`，`band: 2 not supported` 指向驱动的法规带掩码不含 6 GHz。）

### 2. 802.11be：框架已放行，卡在原生 HAL 的映射

LSPosed 钩子已经让所有框架侧闸门通过，诊断日志证明**框架确实把 11be 交给了原生 HAL**：

```
★ hwModeParams: 11BE=true  11AX=true  11AC=true  6GHz=true  maxBW=7
```

但最终写出的 `hostapd_wlan2.conf` 里**只有 `ieee80211ax=1`，没有任何 EHT 键**。
`freqlist` / `ieee80211ax` 这些键在 Java 层搜不到，是**原生 HAL 生成的** ——
所以这一层的封锁 LSPosed 够不到，需要 Magisk 模块级手段（patch `hostapd` / `libwifi-hal`）。

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

### 可选：打开法规域钩子（有风险）

```bash
# 打开
adb shell su -c 'setprop persist.fabhotspot.country US'
# 关闭
adb shell su -c 'setprop persist.fabhotspot.country ""'
```

**默认是关闭的。** 原因见上文「已知阻塞 1」—— 框架级法规域改动会让驱动拒绝设置国码，
可能导致**整台 WiFi 都不可用**。恢复：关掉 LSPosed 模块作用域后重启。

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
    └── FabHotspotModule.kt      LSPosed 钩子
```

**体检 App 是零写入的**：不调 setter、不写 sysfs、不启停热点。

---

## 许可证

[GPL-3.0](LICENSE)
