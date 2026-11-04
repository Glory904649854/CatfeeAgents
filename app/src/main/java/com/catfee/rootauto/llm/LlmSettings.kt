package com.catfee.rootauto.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * LlmSettings —— LLM 接入配置的持久化
 *
 * 功能描述:
 *   保存云端 OpenAI 兼容接口的 BaseUrl / API Key / 模型名, 支持多套配置切换。
 *   Key 属于敏感信息, 使用 EncryptedSharedPreferences 加密落盘。
 *
 * 数据结构:
 *   providers: JSON 数组, 每项 { id, name, baseUrl, apiKey, model }
 *   activeId : 当前选中的配置 id
 *
 * 使用场景:
 *   Agent 每轮调用 LLM 前读取当前生效配置; 用户在设置页增删改。
 *
 * 修改时间: 2026-11-03
 */
object LlmSettings {

    private const val TAG = "LlmSettings"
    private const val PREF_FILE = "catfee_llm_pref"
    private const val KEY_PROVIDERS = "providers"
    private const val KEY_ACTIVE_ID = "active_id"

    /** 单套 LLM 配置 */
    data class Provider(
        val id: String,
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String
    ) {
        /** 是否字段齐全, 可用于调用 */
        val usable: Boolean
            get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    }

    private var prefs: SharedPreferences? = null

    /**
     * 功能: 初始化并取得加密偏好存储
     * 参数: context 任意 Context (内部取 applicationContext)
     * 返回: SharedPreferences 实例, 失败时降级为普通偏好存储
     * 使用场景: Application 启动时调用一次; 内部也做了懒加载兜底
     */
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
            // 部分机型 Keystore 异常时不能影响主流程, 降级为明文存储
            Log.e(TAG, "加密存储不可用, 降级为普通存储: ${e.message}")
            app.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        }
        prefs = p
        return p
    }

    /**
     * 功能: 读取全部配置
     * 参数: context
     * 返回: Provider 列表, 无数据时返回空列表
     * 使用场景: 设置页展示、模型切换
     */
    fun list(context: Context): List<Provider> {
        return try {
            val raw = prefs(context).getString(KEY_PROVIDERS, null) ?: return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Provider(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    baseUrl = o.optString("baseUrl"),
                    apiKey = o.optString("apiKey"),
                    model = o.optString("model")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取配置失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 功能: 覆盖保存全部配置
     * 参数: context; list 新的配置列表
     * 返回: 是否保存成功
     * 使用场景: 增删改之后统一回写
     */
    fun saveAll(context: Context, list: List<Provider>): Boolean {
        return try {
            val arr = JSONArray()
            list.forEach { p ->
                arr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("baseUrl", p.baseUrl)
                        .put("apiKey", p.apiKey)
                        .put("model", p.model)
                )
            }
            prefs(context).edit().putString(KEY_PROVIDERS, arr.toString()).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存配置失败: ${e.message}")
            false
        }
    }

    /**
     * 功能: 新增或按 id 覆盖一套配置
     * 参数: context; provider 配置对象
     * 返回: 保存后的最新列表
     * 使用场景: 用户点「保存」时调用
     */
    fun upsert(context: Context, provider: Provider): List<Provider> {
        val list = list(context).toMutableList()
        val idx = list.indexOfFirst { it.id == provider.id }
        if (idx >= 0) list[idx] = provider else list.add(provider)
        saveAll(context, list)
        return list
    }

    /**
     * 功能: 删除一套配置
     * 参数: context; id 配置 id
     * 返回: 删除后的最新列表
     */
    fun remove(context: Context, id: String): List<Provider> {
        val list = list(context).filterNot { it.id == id }
        saveAll(context, list)
        if (activeId(context) == id) setActiveId(context, list.firstOrNull()?.id ?: "")
        return list
    }

    /** 当前选中的配置 id */
    fun activeId(context: Context): String =
        prefs(context).getString(KEY_ACTIVE_ID, "") ?: ""

    /**
     * 功能: 设置当前生效配置
     * 参数: context; id 配置 id
     * 返回: 无
     */
    fun setActiveId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    /**
     * 功能: 取当前生效的完整配置
     * 参数: context
     * 返回: Provider, 未配置时返回 null
     * 使用场景: LLM 调用入口
     */
    fun active(context: Context): Provider? {
        val list = list(context)
        if (list.isEmpty()) return null
        val id = activeId(context)
        return list.firstOrNull { it.id == id } ?: list.first()
    }
}
