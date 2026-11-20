package com.catfee.rootauto.root

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ModuleInstaller —— Magisk 模块自安装
 *
 * 功能描述:
 *   把 APK 内置的 daemon 二进制与启动脚本写入 Magisk 模块目录,
 *   免去用户手动打包 zip 刷入的步骤。
 *
 * 参数说明:
 *   context 用于读取 assets
 *
 * 返回值说明:
 *   install() 返回 InstallResult, 区分「已安装」「需重启」「失败原因」
 *
 * 使用场景:
 *   首次使用时的初始化引导; 也可用于版本升级后重装 daemon
 *
 * 注意:
 *   写入 /data/adb/modules 需要 root, 且 Magisk 只在模块目录含
 *   module.prop + (service.sh 等) 时才会在下次开机加载。
 *   本实现采用「直接落盘 + 立即启动」, 重启后由 service.sh 托管。
 *
 * 修改时间: 2026-11-03
 */
object ModuleInstaller {

    private const val TAG = "ModuleInstaller"
    private const val MODULE_ID = "catfee_root_auto"
    private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    private const val MODULE_BIN = "$MODULE_DIR/bin/catfee_daemon"

    /** 安装结果 */
    data class InstallResult(
        val success: Boolean,
        val message: String
    )

    /**
     * 功能: 检查模块是否已安装
     * 参数: 无
     * 返回: true=模块目录与二进制均存在
     */
    suspend fun isInstalled(): Boolean {
        val r = RootShell.exec("test -x $MODULE_BIN && echo YES")
        return r.stdout.contains("YES")
    }

    /**
     * 功能: 安装/更新 Magisk 模块并立即启动 daemon
     * 参数: context 用于读取 assets 里的 daemon 二进制
     * 返回: InstallResult
     * 使用场景: App 内「初始化」按钮
     */
    suspend fun install(context: Context): InstallResult = withContext(Dispatchers.IO) {
        try {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val abiDir = when {
                abi.startsWith("arm64") -> "arm64-v8a"
                abi.startsWith("armeabi") -> "armeabi-v7a"
                else -> "arm64-v8a"
            }
            val assetPath = "daemon/$abiDir/catfee_daemon"

            // 1. 读 assets 中的二进制
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            if (bytes.isEmpty()) {
                return@withContext InstallResult(false, "assets 中缺少 daemon 二进制: $assetPath")
            }

            // 2. 先落到可写临时目录 (App 无权直写 /data/adb)
            val tmp = File(context.cacheDir, "catfee_daemon")
            tmp.writeBytes(bytes)
            tmp.setExecutable(true, false)

            // 3. root 拷贝到模块目录并授权
            // daemon 由 App 通过 su + stdio 按需启动, 不再开机常驻全局 socket。
            // service.sh 只负责清理旧版本可能遗留的全局可读 socket / 截图。
            val serviceSh = buildString {
                append("#!/system/bin/sh\n")
                append("rm -f /data/local/tmp/catfee_daemon.sock\n")
                append("rm -f /data/local/tmp/catfee_shot.raw\n")
                append("rm -f /data/local/tmp/catfee_shot_*.raw\n")
            }
            val script = buildString {
                append("mkdir -p $MODULE_DIR/bin\n")
                append("cp -f ${tmp.absolutePath} $MODULE_BIN\n")
                append("chmod 0755 $MODULE_BIN\n")
                // module.prop 决定 Magisk 是否加载该模块
                append("cat > $MODULE_DIR/module.prop <<'EOF'\n")
                append("id=$MODULE_ID\n")
                append("name=CatfeeAgents 底层能力模块\n")
                append("version=1.0.0\n")
                append("versionCode=10000\n")
                append("author=CatfeeAgents\n")
                append("description=提供 uinput 输入注入与截图能力\n")
                append("EOF\n")
                // service.sh 只做旧版本敏感文件清理
                append("cat > $MODULE_DIR/service.sh <<'CATFEE_EOF'\n")
                append(serviceSh)
                append("CATFEE_EOF\n")
                append("chmod 0755 $MODULE_DIR/service.sh\n")
                append("echo INSTALL_OK\n")
            }
            val r = RootShell.exec(script)
            Log.i(TAG, "安装输出: ${r.stdout} / ${r.stderr}")

            if (!r.stdout.contains("INSTALL_OK")) {
                return@withContext InstallResult(false, "写入模块目录失败: ${r.stderr.take(200)}")
            }

            // 4. 立即拉起 daemon, 免去先重启
            val ok = DaemonClient.ping()
            if (ok) {
                InstallResult(true, "模块已安装，daemon 已就绪")
            } else {
                InstallResult(true, "模块已安装，但 daemon 启动失败，请检查 root 授权或重启设备")
            }
        } catch (e: Exception) {
            Log.e(TAG, "安装模块异常: ${e.message}")
            InstallResult(false, "安装异常: ${e.message}")
        }
    }
}
