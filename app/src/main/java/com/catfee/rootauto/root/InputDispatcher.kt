package com.catfee.rootauto.root

import com.catfee.rootauto.a11y.UiObserverService
import kotlinx.coroutines.delay

/**
 * InputDispatcher —— 输入注入的统一入口
 *
 * 为什么需要:
 *   uinput 接收的是设备 natural 坐标。普通竖屏页面直接使用 daemon 最快;
 *   但视频播放器/固定横屏 Activity 的触摸坐标系可能已经是 2340x1080,
 *   各 ROM 对 SurfaceOrientation 的换算又不一致, 直接 uinput 很容易点错。
 *
 * 策略:
 *   - 屏幕处于旋转状态 (90/180/270) 时, 优先用无障碍 dispatchGesture,
 *     它接收当前显示坐标, 由系统完成旋转;
 *   - 竖屏仍走 uinput, 保留低延迟;
 *   - 无障碍手势不可用时回退 daemon, 不丢失能力。
 */
object InputDispatcher {

    private fun rotated(): Boolean =
        ScreenGeometry.current()?.rotated == true

    suspend fun tap(x: Int, y: Int): Boolean {
        if (rotated()) {
            val svc = UiObserverService.instance
            if (svc?.gestureTap(x, y) == true) return true
        }
        return DaemonClient.tap(x, y)
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Boolean {
        if (rotated()) {
            val svc = UiObserverService.instance
            if (svc?.gestureSwipe(x1, y1, x2, y2, durationMs.toLong()) == true) return true
        }
        return DaemonClient.swipe(x1, y1, x2, y2, durationMs)
    }

    suspend fun longPress(x: Int, y: Int, holdMs: Int): Boolean {
        if (rotated()) {
            val svc = UiObserverService.instance
            if (svc?.gestureLongPress(x, y, holdMs.toLong()) == true) return true
        }
        return DaemonClient.longPress(x, y, holdMs)
    }

    suspend fun doubleTap(x: Int, y: Int): Boolean {
        if (rotated()) {
            val svc = UiObserverService.instance ?: return false
            // 不要在一次手势部分成功后回退 daemon.doubleTap，否则会把已发送的
            // 第一击再叠加成三击。旋转坐标系下两击必须整体由同一后端完成。
            val first = svc.gestureTap(x, y)
            if (!first) return false
            delay(120)
            return svc.gestureTap(x, y)
        }
        return DaemonClient.doubleTap(x, y)
    }
}
