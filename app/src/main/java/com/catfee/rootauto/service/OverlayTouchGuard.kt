package com.catfee.rootauto.service

import android.graphics.Rect
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 悬浮窗触摸门控。
 *
 * 悬浮窗要一直挂在别的 App 上面，暂停/停止按钮必须可用；
 * 但 Agent 自己注入点击时，如果落点正好在悬浮窗上，点击会被窗口吃掉，
 * 严重时点到「×」直接把任务停掉。
 *
 * 这里维护悬浮窗的屏幕矩形：注入手势前，如果手势路径会命中悬浮窗，
 * 就临时把窗口设为不可触摸（点击穿透到下层 App），手势结束后恢复。
 * 用户平时点按钮不受影响。
 */
object OverlayTouchGuard {

    private val _touchable = MutableStateFlow(true)

    /** true=悬浮窗可触摸(默认); false=临时让点击穿透 */
    val touchable: StateFlow<Boolean> = _touchable

    @Volatile
    private var bounds: Rect? = null

    @Volatile
    private var lastGestureAt = 0L

    fun updateBounds(rect: Rect?) {
        bounds = rect?.let { Rect(it) }
    }

    /** 记录一次 Agent 手势注入, 用于识别「刚注入就到达」的点击 */
    fun markGesture() {
        lastGestureAt = SystemClock.elapsedRealtime()
    }

    fun gestureJustHappened(windowMs: Long = 250L): Boolean =
        SystemClock.elapsedRealtime() - lastGestureAt in 0..windowMs

    /** 手势路径(起终点包围盒, 含 slop)是否与悬浮窗相交 */
    fun intersects(x1: Int, y1: Int, x2: Int, y2: Int, slop: Int = 8): Boolean {
        val b = bounds ?: return false
        val probe = Rect(
            minOf(x1, x2) - slop,
            minOf(y1, y2) - slop,
            maxOf(x1, x2) + slop,
            maxOf(y1, y2) + slop
        )
        return Rect.intersects(b, probe)
    }

    fun setTouchable(touchable: Boolean) {
        if (_touchable.value != touchable) _touchable.value = touchable
    }
}
