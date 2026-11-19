package com.catfee.rootauto.service

import android.content.Context

/** 显示相关设置: 目前只有悬浮状态条 */
object DisplaySettings {
    private const val PREF = "catfee_display_pref"
    private const val KEY_OVERLAY = "overlay_enabled"
    private const val KEY_X = "overlay_x"
    private const val KEY_Y = "overlay_y"

    fun overlayEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERLAY, true)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_OVERLAY, enabled)
            .apply()
    }

    /** 悬浮窗上次拖到的位置; 没有保存过返回 null */
    fun overlayPosition(context: Context): Pair<Int, Int>? {
        val pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!pref.contains(KEY_X) || !pref.contains(KEY_Y)) return null
        return pref.getInt(KEY_X, 0) to pref.getInt(KEY_Y, 0)
    }

    fun setOverlayPosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(KEY_X, x)
            .putInt(KEY_Y, y)
            .apply()
    }
}
