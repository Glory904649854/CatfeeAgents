#!/system/bin/sh
##########################################################################
# service.sh —— Magisk 开机自启脚本 (晚启动阶段执行, 此时 system 已就绪)
# 功能: 拉起 daemon 常驻服务, 并授权 App 使用
# 修改时间: 2026-11-03
##########################################################################

MODDIR=${0%/*}
BIN="$MODDIR/bin/catfee_daemon"
LOG="/data/local/tmp/catfee_daemon.log"
SOCK="/data/local/tmp/catfee_daemon.sock"

# 等待 system_server 完全就绪, 否则 wm size / screencap 可能失败
for i in $(seq 1 30); do
  if [ "$(getprop sys.boot_completed)" = "1" ]; then
    break
  fi
  sleep 2
done

# 清理残留
rm -f "$SOCK"
[ -f "$LOG" ] && mv -f "$LOG" "$LOG.old"

if [ ! -x "$BIN" ]; then
  echo "daemon 二进制不存在或不可执行: $BIN" > "$LOG"
  exit 1
fi

# 常驻模式启动 (后台, 日志重定向)
"$BIN" serve "$SOCK" >> "$LOG" 2>&1 &

# 授权所有 App 可读写 socket 与截图文件
sleep 1
chmod 0666 "$SOCK" 2>/dev/null
touch /data/local/tmp/catfee_shot.raw
chmod 0666 /data/local/tmp/catfee_shot.raw 2>/dev/null

echo "daemon 已启动: pid=$!" >> "$LOG"

# 存活守护: daemon 意外退出时自动重启, 保证长期可用
(
  while true; do
    sleep 30
    if ! pgrep -f "catfee_daemon serve" > /dev/null 2>&1; then
      echo "$(date) daemon 已退出, 正在重启" >> "$LOG"
      "$BIN" serve "$SOCK" >> "$LOG" 2>&1 &
      sleep 1
      chmod 0666 "$SOCK" 2>/dev/null
    fi
  done
) &
