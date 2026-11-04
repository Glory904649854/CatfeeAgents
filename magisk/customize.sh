#!/system/bin/sh
##########################################################################
# customize.sh —— Magisk 模块安装脚本
# 功能: 安装时按设备 ABI 选择正确的 daemon 二进制并设置权限
# 修改时间: 2026-11-03
##########################################################################

SKIPUNZIP=1

# 安装阶段: 解压全部文件
unzip -o "$ZIPFILE" -d "$MODPATH" >&2

# 按设备 ABI 挑选二进制
ABI=$(getprop ro.product.cpu.abi)
case "$ABI" in
  arm64*)  ARCH_DIR="arm64-v8a" ;;
  armeabi*) ARCH_DIR="armeabi-v7a" ;;
  x86_64*) ARCH_DIR="x86_64" ;;
  *)       ARCH_DIR="arm64-v8a" ;;
esac

ui_print "- 设备 ABI: $ABI, 选用 $ARCH_DIR"

mkdir -p "$MODPATH/bin"
if [ -f "$MODPATH/lib/$ARCH_DIR/catfee_daemon" ]; then
  cp -f "$MODPATH/lib/$ARCH_DIR/catfee_daemon" "$MODPATH/bin/catfee_daemon"
  chmod 0755 "$MODPATH/bin/catfee_daemon"
else
  ui_print "! 未找到 $ARCH_DIR 的 daemon 二进制, 模块将不可用"
fi

# 清理分架构目录, 减小模块体积
rm -rf "$MODPATH/lib"

# 权限
chmod 0755 "$MODPATH/service.sh"
chmod 0644 "$MODPATH/module.prop"

ui_print "- 安装完成, 重启后生效"
