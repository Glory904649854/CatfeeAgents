#!/system/bin/sh
##########################################################################
# service.sh —— 模块升级/开机清理
#
# 旧版本会在 /data/local/tmp 启动全局可读的 socket，并把截图写成 0666。
# 现在 App 通过 su + stdio 按需启动 daemon，不需要开机常驻服务。
# 这里只清理旧版本遗留的敏感文件和 socket。
##########################################################################

rm -f /data/local/tmp/catfee_daemon.sock
rm -f /data/local/tmp/catfee_shot.raw
rm -f /data/local/tmp/catfee_shot_*.raw
