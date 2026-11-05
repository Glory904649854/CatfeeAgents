package com.catfee.rootauto.service

import android.content.Context

/** 显示相关设置: 目前只有悬浮状态条 */
object DisplaySettings {
    private const val PREF = "catfee_display_pref"
    private const val KEY_OVERLAY = "overlay_enabled"

    fun overlayEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERLAY, true)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_OVERLAY, enabled)
            .apply()
    }
}
