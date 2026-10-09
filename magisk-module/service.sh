#!/system/bin/sh
# fab-hotspot: 开机后记录最终生效状态（一次性，写完即退出）
#
# 关注三件事：
#   1. country_code 参数是否真的被驱动接受（应为 US）
#   2. 驱动的自管 regulatory 域是否变成 US（iw reg get 的 phy#0 self-managed 段）
#   3. 6GHz 信道表是否被解锁（SupportedChannelListIn6g 是否非空）

MODDIR=${0%/*}
LOG="$MODDIR/boot.log"

# 等系统起来（最长 ~60s）
i=0
while [ $i -lt 120 ]; do
  [ "$(getprop sys.boot_completed)" = "1" ] && break
  sleep 0.5
  i=$((i + 1))
done
sleep 15

{
  echo "===== service $(date '+%F %T') uptime=$(cut -d' ' -f1 /proc/uptime) ====="

  echo "--- 驱动参数 ---"
  echo "country_code = $(cat /sys/module/kiwi_v2/parameters/country_code 2>/dev/null)"
  echo "enable_11d   = $(cat /sys/module/kiwi_v2/parameters/enable_11d 2>/dev/null)"

  echo "--- iw reg get ---"
  /vendor/bin/iw reg get 2>&1

  echo "--- iw dev ---"
  /vendor/bin/iw dev 2>&1

  echo "--- cmd wifi 国码 ---"
  cmd wifi get-country-code 2>&1

  echo "--- 6GHz 信道表 ---"
  dumpsys wifi 2>/dev/null | grep -o 'SupportedChannelListIn6g[^]]*]' | head -1
  dumpsys wifi 2>/dev/null | grep -o 'SupportedChannelListIn60g[^]]*]' | head -1

  echo "--- dmesg 里的 regulatory 线索 ---"
  dmesg 2>/dev/null | grep -iE 'REGULATORY|reg_is_fcc|11d_state|Failed to set country' | tail -15

  echo "--- dmesg 里驱动加载 ---"
  dmesg 2>/dev/null | grep -iE 'Hard Unloading|Loading driver' | tail -5
} >> "$LOG" 2>&1
