# Fix Android AP For CN — v0.4.0

在**国行小米 13（`fuxi`）**上解锁被地区限制的**热点（SoftAP）**能力。本版本的核心成果：

**6 GHz 热点现在可用（含 6 GHz 160 MHz）**，而且完全不使用会打垮热点的框架级法规域覆盖。

> ⚠️ **免责声明（务必先读）**
> 本工具**绕过地区无线电法规**：中国大陆未把 6 GHz 分配给 WLAN（分配给了 IMT/5G-A）。
> **使用前请自行确认所在地区的无线电法规是否允许使用相应频段与发射功率。**
> 因使用本工具导致的任何后果（设备异常、无线电干扰、法律合规问题）**由使用者自行承担**。
> 本项目以 **GPL-3.0** 发布，**不提供任何担保**；需要 **root（Magisk）** 与 **LSPosed**。

---

## 能力状态（全部为实测结果，非设计目标）

| 能力 | 状态 | 说明 |
|---|---|---|
| **6 GHz 热点** | ✅ 可用（**含系统热点 UI**） | Magisk 模块 + LSPosed 钩子；6 GHz 信道由钩子自动补，UI 与命令行都可用 |
| **6 GHz 160 MHz** | ✅ 可用 | `SoftApInfo{frequency=6135, bandwidth=6}` + hostapd `AP-ENABLED` |
| **5 GHz 160 MHz** | ✅ 可用 | `-w 160` 直接生效，无需任何修改 |
| **2.4 GHz** | ✅ 可用 | — |
| Wi-Fi 7（802.11be / EHT） | ❌ 不可用 | 卡在**驱动代际**，详见下方「已知限制」 |
| **320 MHz** | ❌ 不可用 | EHT 专有带宽，随 11be |
| bridged AP（2.4+5 并发） | ❌ **实测不生效** | 只起 1 个 AP 网卡，被降级成跨频段 ACS 单 AP；硬闸门是 `config_wifiBridgedSoftApSupported=false` |
| bridged **5+6** 并发 | ❌ **框架直接拒绝** | `-b bridged_5_6` → 0 网卡、无 conf、hostapd 未被调用 |
| bridged 2.4+6 | ❌ **降级成单 AP** | 同 2.4+5，最终停在 2462（2.4G 11n） |
| AP MLO | ❌ **实测不生效** | 无 `ap_mld`/`mld` 键、1 个网卡、`ieee80211be` 未进 conf；**MLO 前提是 11be，随 11be 一起不可用** |
| 三频并发 2.4+5+6 | ❌ 硬件不支持 | 芯片只有 2 个射频 |

---

## 用户需要安装的东西（前置依赖）

| # | 依赖 | 是否必须 | 说明 |
|---|---|---|---|
| 1 | **Magisk**（root） | ✅ 必须 | 用于安装 6 GHz 国码模块。实测版本 **30.7**（KernelSU 未验证） |
| 2 | **LSPosed** | ✅ 必须 | 用于加载两个国码钩子。**作用域必须勾 `system`**（不是 `android`） |
| 3 | **Android 14+** | ✅ 必须 | APK 的 `minSdk 34` |
| 4 | 本仓库的 **APK** | ✅ 必须 | 它同时是「体检 App」和「LSPosed 模块」 |
| 5 | 本仓库的 **Magisk 模块 zip** | ✅ 必须 | 6 GHz 的驱动级国码来源 |

**未验证的机型**：即使同为骁龙 8 Gen 2，不同机型/ROM 的 wifi 资源、法规库（`regdb_xiaomi.bin`）、BDF 都可能不同。**请只在自己承担风险的前提下在其他设备上尝试。**

---

## 安装顺序（顺序很重要）

### 第 1 步：装 APK

```bash
adb install -r FixAndroidAP-v0.3.0-probe-lsposed.apk
```

### 第 2 步：装 Magisk 国码模块（6 GHz 的驱动级国码）

Magisk → 左上角菜单 → **从本地安装** → 选 `fabhotspot_6g-v0.3.0-magisk.zip` → **重启**

模块只做两件事：
- 覆盖 `WCNSS_qcom_cfg.ini`，**逐字节只改一行** `gCountryCodePriority=0 → 1`
- 在 `post-fs-data` 阶段 `rmmod` → 等覆盖层就绪 → `insmod … country_code=US`

**回滚**：Magisk → 模块 → 停用 `FabHotspot 6GHz Driver Country` → 重启。

### 第 3 步：启用 LSPosed 模块

1. LSPosed 管理器 →「模块」→ 启用 **Fix Android AP For CN**
2. **作用域勾选「系统框架」（`system`）** —— Wifi 服务跑在 `system_server` 里，必须勾
3. **重启**（钩子只在开机时加载）

**验证**：`adb logcat -s FabHotspotMod` 应看到每个钩子的匹配方法数。

### 第 4 步：开一个 6 GHz 热点

**首选：直接用系统热点 UI** —— 在设置里把热点频段选为 6 GHz 即可。
（本版本加了钩子自动补 PSC 信道，所以 UI 的"自动选信道"也能用。）

命令行方式（备选 / 用于验证）：

```bash
# -b 6 才是 6GHz（-b 8 会报 Invalid band option）；-f 必须是最后一个参数
adb shell su -c 'cmd wifi start-softap MyAP wpa3 <密码> -b 6 -w 160 -f 6135'
# 不带 -f 也可以（钩子会自动补信道 37）
adb shell su -c 'cmd wifi start-softap MyAP wpa3 <密码> -b 6 -w 160'
```

- `-f 6135` = 信道 37；`-f 6295` = 信道 69。**非 PSC 信道（如 5955）会失败。**
- 成功标志：`dumpsys wifi` 里出现 `SoftApInfo{bandwidth=6, frequency=6135, ...}`，
  且 `/data/vendor/wifi/hostapd/hostapd_wlan2.conf` 里有 `channel=37` + `op_class=134`
- **为什么必须指定信道**：6 GHz 下 ACS 会把频道选择下放给驱动，而驱动返回不了 6 GHz 频道，
  hostapd 报 `Could not select hw_mode and channel`。

### 不要做的事

❌ **不要用 `cmd wifi force-country-code`** —— 框架级法规域覆盖会让驱动进入不一致状态，
**所有频段**（含本来能用的 5 GHz）的热点都起不来：
```
kiwi_v2: [E] hdd_reg_notifier: Failed to set country
```

❌ **不要用 `persist.fabhotspot.country`**（模块里仍保留但默认关闭）—— 同样的原因。

---


## 验收：真机端到端（2026-10-09）

客户端 **MediaTek MT7925**（Kali Linux，真 Wi-Fi 7 卡）连到手机热点：

```
iw dev wlan0 link
Connected to 6a:aa:c6:8b:84:5e (on wlan0)
    SSID: Xiaomi13-6G
    freq: 6135.0
    signal: -48 dBm
    rx bitrate: 2161.3 MBit/s 160MHz HE-MCS 10 HE-NSS 2 HE-GI 0 HE-DCM 0
    tx bitrate: 576.4 MBit/s 160MHz HE-MCS 3 HE-NSS 2 HE-GI 0 HE-DCM 0
    RX: 90050 bytes (651 packets)   TX: 9424 bytes (66 packets)
```

| 判据 | 结果 | 结论 |
|---|---|---|
| 6GHz 连通 | `freq: 6135.0`，BSSID 与手机上报一致，有真实流量 | ✅ **端到端通过** |
| 160 MHz | `160MHz`，`2161.3 MBit/s` ≈ 2x2 HE-MCS10 @160MHz 的理论值 | ✅ **带宽真实生效** |
| Wi-Fi 7 (11be) | 协商到 **HE (802.11ax)**，全程无 `EHT` | ❌ **AP 侧未启用** |

**⭐ 关键**：MT7925 是真 Wi-Fi 7 客户端，所以"只有 HE、没有 EHT"**不能归咎于客户端** ——
这证实了上文「11be 卡在驱动代际」的结论，**假阴性已排除**。

**补充判据**：`rx 2161.3 MBit/s` 恰好是 `2x2 / HE MCS10 / 160MHz` 的理论速率，
说明手机侧的 160 MHz 不是配置上写着，而是**实际协商成功**。

> Windows（RTL8852CE）扫不到 6GHz 属于**法规域问题**：该机 `802.11d: 禁用`，
> 且中国大陆未给 WLAN 分配 6GHz；客户端 `iw reg set US` 后即可扫到（与 Kali 侧一致）。

---

## 已知限制：Wi-Fi 7（11be）与 320 MHz ❌

**框架侧早已全部放行**（诊断日志实测 `hwModeParams 11BE=true`），但 hostapd 发出的 EHT beacon
在 **6 GHz / 5 GHz / 2.4 GHz 三个频段全部**被拒：
```
E hostapd: Failed to set beacon parameters → Interface initialization failed
```

驱动能力数据显示：`320MHz in 6GHz Supported` 位存在，但 `EHT MAC Capabilities (0x0000)`、
`EHT MCS/NSS` 全零 —— **没有可用的 EHT 速率**。

与同机型新一代驱动（取自 LineageOS 构建）对比，设备 stock 驱动**缺少 `Disable eht cap for SAP/GO`
等 SAP 侧 EHT 代码**；但尝试替换时：
1. vermagic **可以** patch
2. 却卡在 `cnss2` 的符号：`disagrees about version of symbol qmp_get` + `Unknown symbol msm_pcie_dsp_link_control`
   （**本机模块栈里根本没有那些符号**）

**⇒ 换驱动 = 换整套（内核 + 全部 vendor 模块 + 固件），单文件替换不可行。**
现实唯一路径是等 Xiaomi 为该机型发布完整配套的新版 ROM。

**附带事实**：本机 `ro.vendor.build.fingerprint` 的基线是 **Android 13**，而系统是 Android 16 ——
整个 vendor 栈从未随系统升级过，这也解释了 hostapd 的 AIDL 只有 v1。

---

## License

[GPL-3.0](https://github.com/Batten1226/Fix-Android-AP-For-CN/blob/main/LICENSE) — 没有任何担保。
