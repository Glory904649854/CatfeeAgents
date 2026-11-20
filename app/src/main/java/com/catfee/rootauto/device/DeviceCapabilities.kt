package com.catfee.rootauto.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.catfee.rootauto.a11y.UiObserverService
import com.catfee.rootauto.root.DaemonClient
import com.catfee.rootauto.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 截图来源: Root daemon 优先, Android 11+ 用无障碍系统截图 */
enum class ScreenshotSource { ROOT_DAEMON, ACCESSIBILITY, NONE }

/** 设备实际可用的执行模式 */
enum class DeviceMode { FULL, ACCESSIBILITY, CHAT_ONLY }

/**
 * 一次任务/一次界面渲染使用的设备能力快照。
 * Root 是增强项, 不是运行前提。
 */
data class Capabilities(
    val root: Boolean = false,
    val daemon: Boolean = false,
    val accessibility: Boolean = false,
    val gestures: Boolean = false,
    val screenshot: ScreenshotSource = ScreenshotSource.NONE,
    val overlay: Boolean = false,
    val androidSdk: Int = Build.VERSION.SDK_INT
) {
    val input: Boolean get() = daemon || gestures
    val hasScreenshot: Boolean get() = screenshot != ScreenshotSource.NONE

    val mode: DeviceMode
        get() = when {
            root || daemon -> DeviceMode.FULL
            accessibility -> DeviceMode.ACCESSIBILITY
            else -> DeviceMode.CHAT_ONLY
        }

    fun modeLabel(): String = when (mode) {
        DeviceMode.FULL -> "完整模式"
        DeviceMode.ACCESSIBILITY -> "无障碍模式"
        DeviceMode.CHAT_ONLY -> "仅聊天模式"
    }

    /** 给 Agent system prompt 用的能力摘要 */
    fun summary(): String = buildString {
        append("模式=").append(modeLabel())
        append("; Root=").append(if (root) "是(增强)" else "否(可选, 不是运行前提)")
        append("; 无障碍=").append(if (accessibility) "是" else "否")
        append("; 手势=").append(if (gestures) "是" else "否")
        append("; 截图=").append(
            when (screenshot) {
                ScreenshotSource.ROOT_DAEMON -> "Root daemon"
                ScreenshotSource.ACCESSIBILITY -> "无障碍系统截图"
                ScreenshotSource.NONE -> "不可用"
            }
        )
        if (!accessibility) {
            append("; 提示=先调用 request_capability(name=accessibility), 不要直接结束任务")
        }
    }
}

/**
 * 设备能力探测与共享状态。
 *
 * 非 Root 设备会进入 ACCESSIBILITY 模式; 只有无障碍也未开启时才降级为 CHAT_ONLY。
 * 能力探测不主动申请 Root、不弹授权。
 */
object DeviceCapabilities {

    private val _state = MutableStateFlow(Capabilities())
    val state: StateFlow<Capabilities> = _state.asStateFlow()

    @Volatile
    private var lastRefreshAt: Long = 0L

    private const val CACHE_MS = 1_500L

    fun current(): Capabilities = _state.value

    suspend fun refresh(
        context: Context,
        force: Boolean = false,
        /** Web 任务未授权 Root 时不要通过 su 拉起 daemon 探测 */
        probeDaemon: Boolean = true
    ): Capabilities =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!force && now - lastRefreshAt < CACHE_MS) return@withContext _state.value
            lastRefreshAt = now

            val root = RootShell.appGrantedRoot() == true
            val daemon = if (root && probeDaemon) {
                try {
                    withTimeoutOrNull(1_500) { DaemonClient.ping() } == true
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
            val accessibility = UiObserverService.instance != null
            val gestures = accessibility && Build.VERSION.SDK_INT >= 24
            val screenshot = when {
                daemon -> ScreenshotSource.ROOT_DAEMON
                accessibility && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    ScreenshotSource.ACCESSIBILITY
                else -> ScreenshotSource.NONE
            }
            val overlay = try {
                Settings.canDrawOverlays(context.applicationContext)
            } catch (e: Exception) {
                false
            }

            val caps = Capabilities(
                root = root,
                daemon = daemon,
                accessibility = accessibility,
                gestures = gestures,
                screenshot = screenshot,
                overlay = overlay,
                androidSdk = Build.VERSION.SDK_INT
            )
            _state.value = caps
            caps
        }

    /**
     * Root 设备上如果无障碍被系统关闭, 用 root 把本应用的服务重新加回
     * enabled_accessibility_services。非 Root / 未授权时直接返回 false。
     *
     * 典型场景: 覆盖安装、force-stop 后部分 ROM 会重置无障碍授权,
     * 此时 input_text / observe / tap_text 会被能力过滤掉, Agent 无法输入文字。
     */
    suspend fun ensureAccessibility(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (UiObserverService.instance != null) return@withContext true
        if (RootShell.appGrantedRoot() != true) return@withContext false

        val component = "com.catfee.rootauto/com.catfee.rootauto.a11y.UiObserverService"
        val current = RootShell.execText("settings get secure enabled_accessibility_services")
        val components = current.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "null" }
            .toMutableSet()
        components.add(component)
        val value = components.joinToString(":")

        val result = RootShell.exec(
            "settings put secure enabled_accessibility_services $value; " +
                "settings put secure accessibility_enabled 1",
            8_000
        )
        if (!result.ok) return@withContext false

        repeat(12) {
            delay(250)
            if (UiObserverService.instance != null) {
                refresh(context, force = true)
                return@withContext true
            }
        }
        false
    }

    /** 轻量刷新: 仅用于无障碍连接/断开后更新, 不重新探测 Root daemon */
    fun refreshAccessibilityOnly() {
        val old = _state.value
        val accessibility = UiObserverService.instance != null
        val screenshot = when {
            old.daemon -> ScreenshotSource.ROOT_DAEMON
            accessibility && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                ScreenshotSource.ACCESSIBILITY
            else -> ScreenshotSource.NONE
        }
        _state.value = old.copy(
            accessibility = accessibility,
            gestures = accessibility && Build.VERSION.SDK_INT >= 24,
            screenshot = screenshot
        )
    }
}
