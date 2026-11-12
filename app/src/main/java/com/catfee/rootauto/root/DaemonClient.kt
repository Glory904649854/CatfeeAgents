package com.catfee.rootauto.root

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * DaemonClient —— 与底层 native daemon 通信的客户端
 *
 * 功能描述:
 *   通过一条常驻的 root 进程 (su → catfee_daemon stdio) 与 native 层通信,
 *   以「一行请求 / 一行 JSON 响应」协议交互。
 *
 * 参数说明:
 *   无 (daemon 二进制路径固定在 Magisk 模块目录下)
 *
 * 返回值说明:
 *   各语义化方法返回 Boolean 或数据; call() 统一返回 JSONObject
 *
 * 使用场景:
 *   点击、滑动、按键、截图等所有需要内核级能力的操作。
 *   常驻进程只启动一次, 单次操作延迟约 1~5ms; 进程死亡后下次调用自动重启。
 *
 * 为什么不用 Unix Socket:
 *   Android SELinux 默认禁止 App 域连接 root 域创建的 socket,
 *   而「su 启动的子进程 + stdio」不受此限制, 且同样避免每次 fork 的开销。
 *
 * 修改时间: 2026-11-03
 */
object DaemonClient {

    private const val TAG = "DaemonClient"
    private const val DAEMON_BIN = "/data/adb/modules/catfee_root_auto/bin/catfee_daemon"
    private const val DAEMON_LOG = "/data/local/tmp/catfee_daemon.log"

    /** 单次请求响应超时: 超过即认为 daemon 卡死, 杀掉重启, 避免永久挂起 */
    private const val RESPONSE_TIMEOUT_MS = 15_000L

    /** 串行锁: daemon 协议是同步一问一答, 并发调用必须排队 */
    private val ioLock = Mutex()

    /** 常驻进程与读写流, 进程存活期间复用 (仅在 ioLock 保护下访问) */
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** 请求自增序号, 便于排查乱序响应 */
    private var seq = 0

    /**
     * 功能: 确保常驻进程可用 (内部使用, 调用方必须已持有 ioLock)
     * 参数: 无
     * 返回: true=进程可用
     * 说明: 不加锁, 因为 Mutex 不可重入, 由 call() 统一持锁
     */
    private fun ensureStartedLocked(): Boolean {
        if (process?.isAlive == true && writer != null && reader != null) return true
        if (!RootShell.isAvailable()) {
            closeQuietlyLocked()
            return false
        }
        try {
            closeQuietlyLocked()
            val p = ProcessBuilder("su", "-c", "$DAEMON_BIN stdio 2>$DAEMON_LOG").start()
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
            reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
            // 握手验证: 协议必须是 id|method, 裸 "ping" 会被 daemon 判定为缺少 method
            val probe = sendLocked("${++seq}|ping")
            val ok = probe.optBoolean("ok", false)
            if (!ok) {
                Log.e(TAG, "daemon 握手失败: $probe")
                closeQuietlyLocked()
            } else {
                val d = probe.optJSONObject("data")
                val w = d?.optInt("w", 0) ?: 0
                val h = d?.optInt("h", 0) ?: 0
                ScreenGeometry.setNatural(w, h)
            }
            return ok
        } catch (e: Exception) {
            Log.e(TAG, "启动 daemon 失败: ${e.message}")
            closeQuietlyLocked()
            return false
        }
    }

    /**
     * 功能: 发送一行请求并读取一行响应 (内部使用, 调用方必须已持有 ioLock)
     * 参数: line 完整协议行 (形如 "1|tap|540|1200")
     * 返回: 响应 JSONObject
     */
    private fun sendLocked(line: String): JSONObject {
        val w = writer ?: return JSONObject().put("ok", false).put("err", "daemon 未启动")
        val r = reader ?: return JSONObject().put("ok", false).put("err", "daemon 未启动")
        w.write(line)
        w.newLine()
        w.flush()
        val respLine = r.readLine() ?: return JSONObject().put("ok", false).put("err", "daemon 无响应")
        return try {
            JSONObject(respLine)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("err", "响应解析失败: $respLine")
        }
    }

    /**
     * 功能: 对外统一调用入口 (自动保证进程存活 + 串行 + 超时保护)
     * 参数: method 方法名; args 参数列表
     * 返回: 响应 JSONObject; 超时或异常返回 ok=false
     * 使用场景: 所有原子操作的底层通道
     */
    suspend fun call(method: String, vararg args: Any): JSONObject = withContext(Dispatchers.IO) {
        if (!RootShell.isAvailable()) {
            return@withContext JSONObject().put("ok", false)
                .put("err", "当前为非 Root 模式，uinput/daemon 不可用")
        }
        ioLock.withLock {
            try {
                if (!ensureStartedLocked()) {
                    return@withLock JSONObject().put("ok", false)
                        .put("err", "无法启动 daemon, 请确认已 root 且模块已安装")
                }
                val line = buildString {
                    append(++seq).append('|').append(method)
                    args.forEach { append('|').append(it) }
                }
                // readLine 是阻塞调用, 用 withTimeoutOrNull 包在 IO 线程上仍无法中断,
                // 因此配合 runInterruptible 让协程取消能真正打断阻塞读
                val resp = withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
                    runInterruptible { sendLocked(line) }
                }
                if (resp == null) {
                    Log.e(TAG, "调用 $method 超时(${RESPONSE_TIMEOUT_MS}ms), 重启 daemon")
                    closeQuietlyLocked()
                    return@withLock JSONObject().put("ok", false).put("err", "daemon 响应超时")
                }
                // 无响应错误也触发重启
                if (!resp.optBoolean("ok", false) && resp.optString("err").contains("无响应")) {
                    closeQuietlyLocked()
                }
                resp
            } catch (e: Exception) {
                Log.e(TAG, "调用 $method 失败: ${e.message}")
                closeQuietlyLocked()
                JSONObject().put("ok", false).put("err", "调用异常: ${e.message}")
            }
        }
    }

    /**
     * 功能: 关闭常驻进程, 释放资源 (内部使用, 调用方必须已持有 ioLock)
     * 参数: 无
     * 返回: 无
     */
    private fun closeQuietlyLocked() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            process?.let {
                it.destroy()
                // 给 daemon 一点时间退出, 避免僵尸进程堆积
                if (!it.waitFor(500, TimeUnit.MILLISECONDS)) it.destroyForcibly()
            }
        } catch (_: Exception) {
        }
        writer = null
        reader = null
        process = null
    }

    /**
     * 功能: 对外关闭接口
     * 参数: 无
     * 返回: 无
     * 使用场景: App 退出或任务结束时释放 daemon
     */
    suspend fun shutdown() = withContext(Dispatchers.IO) {
        ioLock.withLock { closeQuietlyLocked() }
    }

    // ---------------- 语义化封装 ----------------

    /** 点击 [x,y], 入参是当前显示方向坐标, 发送 daemon 前转 natural */
    suspend fun tap(x: Int, y: Int, holdMs: Int = 40): Boolean {
        screenSize()
        if (ScreenGeometry.current()?.contains(x, y) == false) return false
        val (nx, ny) = ScreenGeometry.toNatural(x, y) ?: (x to y)
        return call("tap", nx, ny, holdMs).optBoolean("ok", false)
    }

    /** 长按 [x,y], 默认按住 800ms */
    suspend fun longPress(x: Int, y: Int, holdMs: Int = 800): Boolean {
        screenSize()
        if (ScreenGeometry.current()?.contains(x, y) == false) return false
        val (nx, ny) = ScreenGeometry.toNatural(x, y) ?: (x to y)
        return call("longpress", nx, ny, holdMs).optBoolean("ok", false)
    }

    /** 双击 [x,y] */
    suspend fun doubleTap(x: Int, y: Int): Boolean {
        screenSize()
        if (ScreenGeometry.current()?.contains(x, y) == false) return false
        val (nx, ny) = ScreenGeometry.toNatural(x, y) ?: (x to y)
        return call("doubletap", nx, ny).optBoolean("ok", false)
    }

    /** 滑动: (x1,y1) → (x2,y2), durMs 总时长, 建议 200~600 */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int = 300): Boolean {
        screenSize()
        if (ScreenGeometry.current()?.contains(x1, y1) == false ||
            ScreenGeometry.current()?.contains(x2, y2) == false
        ) return false
        val (nx1, ny1) = ScreenGeometry.toNatural(x1, y1) ?: (x1 to y1)
        val (nx2, ny2) = ScreenGeometry.toNatural(x2, y2) ?: (x2 to y2)
        return call("swipe", nx1, ny1, nx2, ny2, durMs).optBoolean("ok", false)
    }

    /** 物理按键, 参数为 Linux KEY_* 码 (BACK=158, HOME=102, APP_SWITCH=580) */
    suspend fun keyevent(keycode: Int): Boolean =
        call("keyevent", keycode).optBoolean("ok", false)

    /**
     * 功能: 截图
     * 参数: path 落盘路径
     * 返回: Triple(成功, 宽, 高)
     * 使用场景: 视觉兜底、失败存证; 拿到的是 RGBA 原始数据(含 12 或 16 字节头)
     */
    suspend fun shot(path: String = "/data/local/tmp/catfee_shot.raw"): Triple<Boolean, Int, Int> {
        val r = call("shot", path)
        if (!r.optBoolean("ok", false)) return Triple(false, 0, 0)
        val d = r.optJSONObject("data") ?: return Triple(false, 0, 0)
        return Triple(true, d.optInt("w"), d.optInt("h"))
    }

    /**
     * 当前显示方向尺寸 (width, height)。
     * daemon 的 screen 返回 natural 尺寸; 这里缓存后交给 ScreenGeometry 按 rotation 换轴。
     */
    suspend fun screenSize(): Pair<Int, Int> {
        val r = call("screen")
        val d = r.optJSONObject("data")
        val nw = d?.optInt("w", 0) ?: 0
        val nh = d?.optInt("h", 0) ?: 0
        if (nw > 0 && nh > 0) ScreenGeometry.setNatural(nw, nh)
        // DisplayManager 在固定横屏 Activity 里可能谎报 0; 用 InputManager 的真实方向校准
        ScreenGeometry.refreshRotation()
        return ScreenGeometry.displaySize()
    }

    /** daemon 是否可用 */
    suspend fun ping(): Boolean = call("ping").optBoolean("ok", false)
}
