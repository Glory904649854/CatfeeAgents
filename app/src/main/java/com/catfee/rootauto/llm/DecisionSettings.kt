package com.catfee.rootauto.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.catfee.rootauto.BuildConfig

/**
 * DecisionSettings —— DECISION 加速模式的配置
 *
 * 默认关闭。开启后使用百炼 decision 接口做快速动作路由,
 * 只有 observe / wait 这类安全动作会在高置信度时跳过主模型。
 * Base URL / Key 默认值来自 local.properties (BuildConfig), 不写入仓库。
 */
object DecisionSettings {

    private const val TAG = "DecisionSettings"
    private const val PREF_FILE = "catfee_decision_pref"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_MIN_CONFIDENCE = "min_confidence"
    private const val KEY_MAX_FAST_STEPS = "max_fast_steps"

    val DEFAULT_BASE_URL: String = BuildConfig.DECISION_BASE_URL.ifBlank {
        "https://llm-cev4lnmqq9bzm5of.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
    }
    val DEFAULT_API_KEY: String = BuildConfig.DECISION_API_KEY
    val DEFAULT_MODEL: String = BuildConfig.DECISION_MODEL.ifBlank { "decision-model-preview" }
    const val DEFAULT_MIN_CONFIDENCE = 0.75
    const val DEFAULT_MAX_FAST_STEPS = 3

    data class Config(
        val enabled: Boolean = false,
        val baseUrl: String = DEFAULT_BASE_URL,
        val apiKey: String = DEFAULT_API_KEY,
        val model: String = DEFAULT_MODEL,
        val minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
        val maxFastSteps: Int = DEFAULT_MAX_FAST_STEPS
    ) {
        val usable: Boolean
            get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
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
        return try {
            val p = prefs(context)
            Config(
                enabled = p.getBoolean(KEY_ENABLED, false),
                baseUrl = p.getString(KEY_BASE_URL, null)?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: DEFAULT_BASE_URL,
                apiKey = p.getString(KEY_API_KEY, null)?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: DEFAULT_API_KEY,
                model = p.getString(KEY_MODEL, null)?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: DEFAULT_MODEL,
                minConfidence = p.getFloat(KEY_MIN_CONFIDENCE, DEFAULT_MIN_CONFIDENCE.toFloat())
                    .toDouble().coerceIn(0.5, 1.0),
                maxFastSteps = p.getInt(KEY_MAX_FAST_STEPS, DEFAULT_MAX_FAST_STEPS)
                    .coerceIn(1, 8)
            )
        } catch (e: Exception) {
            Log.e(TAG, "读取配置失败: ${e.message}")
            Config()
        }
    }

    fun save(context: Context, config: Config): Boolean {
        return try {
            prefs(context).edit()
                .putBoolean(KEY_ENABLED, config.enabled)
                .putString(KEY_BASE_URL, config.baseUrl.trim())
                .putString(KEY_API_KEY, config.apiKey.trim())
                .putString(KEY_MODEL, config.model.trim())
                .putFloat(KEY_MIN_CONFIDENCE, config.minConfidence.toFloat())
                .putInt(KEY_MAX_FAST_STEPS, config.maxFastSteps)
                .apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存配置失败: ${e.message}")
            false
        }
    }
}
