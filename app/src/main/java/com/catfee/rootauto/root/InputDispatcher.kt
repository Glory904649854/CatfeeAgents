package com.catfee.rootauto.root

import android.os.SystemClock
import com.catfee.rootauto.a11y.UiObserverService
import com.catfee.rootauto.device.DeviceCapabilities
import kotlinx.coroutines.delay

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

    private fun rotated(): Boolean =
        ScreenGeometry.current()?.rotated == true

    /**
     * 当前该用无障碍手势时返回 service:
     * - 无 daemon: 必须用无障碍
     * - 有 daemon + 横屏: 沿用原来的无障碍优先
     * - 有 daemon + 竖屏: 返回 null, 保持 uinput 低延迟
     */
    private fun a11yForGesture(): UiObserverService? {
        val caps = DeviceCapabilities.current()
        if (!caps.accessibility) return null
        if (caps.daemon && !rotated()) return null
        return UiObserverService.instance
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

    suspend fun tap(x: Int, y: Int): Boolean {
        val svc = a11yForGesture()
        if (svc != null) {
            waitGestureGap()
            if (svc.gestureTap(x, y)) return true
        }
        if (!DeviceCapabilities.current().daemon) return false
        return DaemonClient.tap(x, y)
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Boolean {
        val svc = a11yForGesture()
        if (svc != null) {
            waitGestureGap()
            if (svc.gestureSwipe(x1, y1, x2, y2, durationMs.toLong())) return true
        }
        if (!DeviceCapabilities.current().daemon) return false
        return DaemonClient.swipe(x1, y1, x2, y2, durationMs)
    }

    suspend fun longPress(x: Int, y: Int, holdMs: Int): Boolean {
        val svc = a11yForGesture()
        if (svc != null) {
            waitGestureGap()
            if (svc.gestureLongPress(x, y, holdMs.toLong())) return true
        }
        if (!DeviceCapabilities.current().daemon) return false
        return DaemonClient.longPress(x, y, holdMs)
    }

    suspend fun doubleTap(x: Int, y: Int): Boolean {
        val svc = a11yForGesture()
        if (svc != null) {
            waitGestureGap()
            if (!svc.gestureTap(x, y)) return false
            delay(120)
            waitGestureGap()
            return svc.gestureTap(x, y)
        }
        if (!DeviceCapabilities.current().daemon) return false
        return DaemonClient.doubleTap(x, y)
    }
}
