package com.catfee.rootauto.root

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * RootShell —— root 命令执行封装
 *
 * 功能描述:
 *   基于 libsu 执行 root 命令, 返回标准输出/错误输出与退出码。
 *   libsu 会维持一个 root 域常驻 shell, 避免每次调用都弹授权、也避免
 *   Runtime.exec("su") 常见的死锁问题。
 *
 * 使用场景:
 *   启动 App、结束进程、读取系统属性、查询前台 Activity 等系统级操作。
 *
 * 修改时间: 2026-11-03
 */
object RootShell {

    private const val TAG = "Shell"

    /** 命令执行结果 */
    data class Result(
        val code: Int,
        val stdout: String,
        val stderr: String
    ) {
        /** 是否执行成功(退出码为 0) */
        val ok: Boolean get() = code == 0
    }

    /**
     * 功能: 执行 root 命令
     * 参数:
     *   cmd     要执行的命令字符串
     *   timeoutMs 超时毫秒数, 默认 15 秒, 避免卡死整个 Agent
     * 返回: Result 包含退出码与输出
     * 使用场景: 几乎所有需要 shell 的系统操作
     */
    suspend fun exec(cmd: String, timeoutMs: Long = 15_000): Result = withContext(Dispatchers.IO) {
        try {
            // libsu 的 exec() 是阻塞调用, 用 runInterruptible 让协程超时能真正打断它
            val result = withTimeoutOrNull(timeoutMs) {
                runInterruptible {
                    // libsu 5.x: out/err 直接返回行列表, 无需外部缓冲
                    val r = Shell.cmd(cmd).exec()
                    Result(
                        code = r.code,
                        stdout = r.out.joinToString("\n"),
                        stderr = r.err.joinToString("\n")
                    )
                }
            }
            result ?: Result(-1, "", "命令执行超时(${timeoutMs}ms): $cmd")
        } catch (e: Exception) {
            Log.e(TAG, "执行命令失败: $cmd => ${e.message}")
            Result(-1, "", e.message ?: "未知错误")
        }
    }

    /**
     * 功能: 执行命令并只取标准输出(自动 trim)
     * 参数: cmd 命令字符串
     * 返回: 标准输出文本, 失败返回空串
     * 使用场景: 只需读结果的简单查询
     */
    suspend fun execText(cmd: String): String = exec(cmd).stdout.trim()

    /**
     * 功能: 检查 root 权限是否可用
     * 参数: 无
     * 返回: true=已获得 root
     * 使用场景: App 启动时的前置检查
     */
    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            Log.w(TAG, "检查 root 失败: ${e.message}")
            false
        }
    }

    /**
     * 功能: 只查询 App 当前是否已被授予 root, 不主动申请/弹授权
     * 返回: true=已授权, false/null=未授权或设备无 root
     * 使用场景: 设置页的 ROOT 状态标志
     */
    fun appGrantedRoot(): Boolean? = try {
        // isAppGrantedRoot() 在部分环境会返回 null; 回退到 getShell() 实际探测一次。
        // 已授权的设备直接复用常驻 shell, 不会重复弹窗。
        Shell.isAppGrantedRoot() ?: Shell.getShell().isRoot
    } catch (e: Exception) {
        Log.w(TAG, "查询 root 授权失败: ${e.message}")
        false
    }
}
