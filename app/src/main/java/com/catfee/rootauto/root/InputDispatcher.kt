package com.catfee.rootauto.root

import android.os.SystemClock
import com.catfee.rootauto.a11y.UiObserverService
import com.catfee.rootauto.device.DeviceCapabilities
import com.catfee.rootauto.service.OverlayTouchGuard
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * InputDispatcher —— 输入注入的统一入口
 *
 * 策略:
 *   - Root daemon 可用时保持低延迟 uinput; 横屏仍优先无障碍手势避免坐标换算问题。
 *   - 无 Root 时全部走无障碍 dispatchGesture, 这是非 Root 设备的主路径。
 *   - 无障碍手势是异步的, 连续手势之间留最小间隔, 避免后一击取消前一击。
 */
object InputDispatcher {

    private val gestureLock = Any()
    private var lastGestureAt = 0L
    private const val GESTURE_GAP_MS = 90L

    /** Web 任务策略: 禁止 Root 时不能用 daemon/uinput 注入, 只能走无障碍手势。 */
    @Volatile
    private var rootChannelAllowed = true

    fun setRootChannelAllowed(allowed: Boolean) {
        rootChannelAllowed = allowed
    }

    private fun daemonUsable(): Boolean =
        rootChannelAllowed && DeviceCapabilities.current().daemon

    private fun rotated(): Boolean =
        ScreenGeometry.current()?.rotated == true

    /**
     * 当前该用无障碍手势时返回 service:
     * - 无 daemon: 必须用无障碍
     * - 有 daemon + 横屏: 沿用原来的无障碍优先
     * - 有 daemon + 竖屏: 返回 null, 保持 uinput 低延迟
     */
    private fun a11yForGesture(): UiObserverService? {
        // 以服务实例为准; DeviceCapabilities 的快照可能还没刷新
        val svc = UiObserverService.instance ?: return null
        if (daemonUsable() && !rotated()) return null
        return svc
    }

    private suspend fun waitGestureGap() {
        val since = synchronized(gestureLock) {
            val now = SystemClock.elapsedRealtime()
            val wait = (GESTURE_GAP_MS - (now - lastGestureAt)).coerceAtLeast(0L)
            lastGestureAt = now + wait
            wait
        }
        if (since > 0) delay(since)
    }

    /**
     * 悬浮窗让路: 手势路径如果会命中状态条, 注入期间把状态条设为不可触摸,
     * 点击就会穿透到下层 App; 手势结束(含滑动/长按时长)后恢复按钮可点。
     * 不会命中时零开销, 不加任何延迟。
     */
    private suspend fun <T> passThroughOverlay(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        settleMs: Long,
        block: suspend () -> T
    ): T {
        OverlayTouchGuard.markGesture()
        if (!OverlayTouchGuard.intersects(x1, y1, x2, y2)) return block()
        OverlayTouchGuard.setTouchable(false)
        // 给 WindowManager 一点时间应用新的窗口标志
        delay(40)
        try {
            return block()
        } finally {
            // 任务被取消时也要恢复, 否则悬浮窗会一直不可触摸
            withContext(NonCancellable) {
                delay(settleMs + 140)
                OverlayTouchGuard.setTouchable(true)
            }
        }
    }

    suspend fun tap(x: Int, y: Int): Boolean = passThroughOverlay(x, y, x, y, 80L) {
        val svc = a11yForGesture()
        if (svc != null) {
            waitGestureGap()
            if (svc.gestureTap(x, y)) return@passThroughOverlay true
        }
        if (!daemonUsable()) return@passThroughOverlay false
        DaemonClient.tap(x, y)
    }

    suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int = 300
    ): Boolean = passThroughOverlay(x1, y1, x2, y2, durationMs.toLong()) {
        val svc = a11yForGesture()
        if (svc != null) {
            waitGestureGap()
            if (svc.gestureSwipe(x1, y1, x2, y2, durationMs.toLong())) {
                return@passThroughOverlay true
            }
        }
        if (!daemonUsable()) return@passThroughOverlay false
        DaemonClient.swipe(x1, y1, x2, y2, durationMs)
    }

    suspend fun longPress(x: Int, y: Int, holdMs: Int): Boolean =
        passThroughOverlay(x, y, x, y, holdMs.toLong()) {
            val svc = a11yForGesture()
            if (svc != null) {
                waitGestureGap()
                if (svc.gestureLongPress(x, y, holdMs.toLong())) {
                    return@passThroughOverlay true
                }
            }
            if (!daemonUsable()) return@passThroughOverlay false
            DaemonClient.longPress(x, y, holdMs)
        }

    suspend fun doubleTap(x: Int, y: Int): Boolean =
        passThroughOverlay(x, y, x, y, 520L) {
            val svc = a11yForGesture()
            if (svc != null) {
                waitGestureGap()
                if (!svc.gestureTap(x, y)) return@passThroughOverlay false
                delay(120)
                waitGestureGap()
                return@passThroughOverlay svc.gestureTap(x, y)
            }
            if (!daemonUsable()) return@passThroughOverlay false
            DaemonClient.doubleTap(x, y)
        }
}
