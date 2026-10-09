#!/system/bin/sh
# fab-hotspot v3: 让「驱动自己的国码」压过框架推来的国码
#
# 原理（逐条都有实测支撑）：
#   * /sys/module/kiwi_v2/parameters/country_code 只读，唯一注入点是 insmod 命令行
#   * 但只设 country_code=US 无效 —— INI 里 gCountryCodePriority=0 表示「框架优先」，
#     而正常启动时框架推 CN 是成功的，所以驱动最终仍是 CN（v2 实测）
#   * 因此还需要把 INI 的 gCountryCodePriority 改成 1
#   * Magisk 的文件覆盖层比本脚本晚（v1 实测 4.8s 时文件还没挂上），
#     而驱动读 INI 在启动早期 —— 所以先 rmmod，等覆盖层就绪后再 insmod，
#     这样驱动读到的就是被覆盖过的 INI
#
# 失败时的表现：只写日志。若 rmmod 失败则原样跳过，WiFi 不受影响。

MODDIR=${0%/*}
LOG="$MODDIR/boot.log"
KO=/vendor/lib/modules/qca_cld3_kiwi_v2.ko
INI=/vendor/etc/wifi/kiwi_v2/WCNSS_qcom_cfg.ini
PARAMS="country_code=US"

{
  echo "===== post-fs-data $(date '+%F %T') uptime=$(cut -d' ' -f1 /proc/uptime) ====="

  # 1) 等驱动加载出来（正常情况下 init 在 ~3.2s 就加载了）
  i=0
  while [ $i -lt 100 ]; do
    grep -q '^kiwi_v2 ' /proc/modules && break
    sleep 0.1
    i=$((i + 1))
  done
  if ! grep -q '^kiwi_v2 ' /proc/modules; then
    echo "!! kiwi_v2 未加载，放弃"
    exit 0
  fi
  echo "kiwi_v2 已加载（等待 $i 次）"

  echo "--- 重载前 ---"
  echo "country_code 参数 = $(cat /sys/module/kiwi_v2/parameters/country_code 2>/dev/null)"
  echo "INI 里的 priority = $(grep '^gCountryCodePriority' $INI 2>/dev/null)"

  # 2) 卸载
  rmmod kiwi_v2 2>&1
  echo "rmmod exit=$?"

  i=0
  while [ $i -lt 50 ]; do
    grep -q '^kiwi_v2 ' /proc/modules || break
    sleep 0.1
    i=$((i + 1))
  done
  if grep -q '^kiwi_v2 ' /proc/modules; then
    echo "!! rmmod 未完成（引用计数非 0？），放弃本次重载"
    exit 0
  fi

  # 3) 等 Magisk 的 INI 覆盖层就绪（最长 45s）
  i=0
  while [ $i -lt 450 ]; do
    grep -q '^gCountryCodePriority=1' "$INI" && break
    sleep 0.1
    i=$((i + 1))
  done
  echo "INI 覆盖层就绪耗时 ${i}00ms（uptime=$(cut -d' ' -f1 /proc/uptime)）"
  echo "INI 里的 priority = $(grep '^gCountryCodePriority' $INI 2>/dev/null)"

  # 4) 带国码参数重载
  insmod $KO $PARAMS 2>&1
  echo "insmod exit=$?"

  echo "--- 重载后 ---"
  echo "country_code 参数 = $(cat /sys/module/kiwi_v2/parameters/country_code 2>/dev/null)"
  echo "enable_11d  参数 = $(cat /sys/module/kiwi_v2/parameters/enable_11d 2>/dev/null)"
} >> "$LOG" 2>&1
