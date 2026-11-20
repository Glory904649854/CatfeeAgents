package com.catfee.rootauto.console

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * ConsoleSettings —— 内置 Catfee Web 控制台的设备身份。
 *
 * 和旧的 JWT 日志系统不同，这里只保存设备凭证：
 *   baseUrl + deviceId + deviceSecret
 * 管理员账号 / JWT 不会进入 APK。
 */
object ConsoleSettings {

    private const val TAG = "ConsoleSettings"
    private const val PREF_FILE = "catfee_console_pref"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_SECRET = "device_secret"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_STATUS = "last_status"
    private const val KEY_REMOTE_AUTO_APPROVE = "remote_auto_approve"

    data class Config(
        val enabled: Boolean = false,
        val baseUrl: String = "",
        val deviceId: String = "",
        val deviceSecret: String = "",
        val deviceName: String = "",
        val lastStatus: String = "",
        /** 默认 false: 远程任务和远程记忆编辑默认被设备拒绝，需本机显式开启 */
        val remoteAutoApprove: Boolean = false
    ) {
        val usable: Boolean
            get() = enabled && baseUrl.isNotBlank() && deviceId.isNotBlank() && deviceSecret.isNotBlank()
    }

    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        val app = context.applicationContext
        val p = try {
            val mk = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                PREF_FILE,
                mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "加密存储不可用, 降级为普通存储: ${e.message}")
            app.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        }
        prefs = p
        return p
    }

    fun load(context: Context): Config {
        val p = prefs(context)
        return Config(
            enabled = p.getBoolean(KEY_ENABLED, false),
            baseUrl = p.getString(KEY_BASE_URL, "").orEmpty(),
            deviceId = p.getString(KEY_DEVICE_ID, "").orEmpty(),
            deviceSecret = p.getString(KEY_DEVICE_SECRET, "").orEmpty(),
            deviceName = p.getString(KEY_DEVICE_NAME, "").orEmpty(),
            lastStatus = p.getString(KEY_STATUS, "").orEmpty(),
            remoteAutoApprove = p.getBoolean(KEY_REMOTE_AUTO_APPROVE, false)
        )
    }

    fun save(context: Context, config: Config) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_BASE_URL, normalizeBase(config.baseUrl))
            .putString(KEY_DEVICE_ID, config.deviceId.trim())
            .putString(KEY_DEVICE_SECRET, config.deviceSecret.trim())
            .putString(KEY_DEVICE_NAME, config.deviceName.trim())
            .putString(KEY_STATUS, config.lastStatus.take(300))
            .putBoolean(KEY_REMOTE_AUTO_APPROVE, config.remoteAutoApprove)
            .apply()
    }

    fun updateStatus(context: Context, status: String) {
        prefs(context).edit().putString(KEY_STATUS, status.take(300)).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun normalizeBase(raw: String): String {
        var value = raw.trim().trimEnd('/')
        if (value.isEmpty()) return ""
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://$value"
        }
        return value
    }
}
