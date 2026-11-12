package com.catfee.rootauto.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.catfee.rootauto.a11y.UiObserverService
import com.catfee.rootauto.root.DaemonClient
import com.catfee.rootauto.root.RootShell
import kotlinx.coroutines.Dispatchers
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
        append("; Root=").append(if (root) "是" else "否")
        append("; 无障碍=").append(if (accessibility) "是" else "否")
        append("; 手势=").append(if (gestures) "是" else "否")
        append("; 截图=").append(
            when (screenshot) {
                ScreenshotSource.ROOT_DAEMON -> "Root daemon"
                ScreenshotSource.ACCESSIBILITY -> "无障碍系统截图"
                ScreenshotSource.NONE -> "不可用"
            }
        )
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

    suspend fun refresh(context: Context, force: Boolean = false): Capabilities =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!force && now - lastRefreshAt < CACHE_MS) return@withContext _state.value
            lastRefreshAt = now

            val root = RootShell.appGrantedRoot() == true
            val daemon = if (root) {
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
