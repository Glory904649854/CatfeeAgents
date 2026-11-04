package com.catfee.rootauto.log

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * LogSettings —— 远程日志上报配置
 *
 * 对接 clouddream-server 的 /logReport 模块:
 *   - 6 位校验码 POST /logReport/exchange 换 30 天 Agent JWT
 *   - JWT 只绑定一个项目节点, 后续用 Bearer 上报
 *   - Token 与 LLM Key 一样用 EncryptedSharedPreferences 保存
 */
object LogSettings {

    private const val TAG = "LogSettings"
    private const val PREF_FILE = "catfee_agent_log"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_PROJECT = "project"
    private const val KEY_EXPIRES = "expires"
    private const val KEY_STATUS = "last_status"

    const val DEFAULT_BASE_URL = "https://api.catfee.cn"

    data class Config(
        val enabled: Boolean = false,
        val baseUrl: String = DEFAULT_BASE_URL,
        val token: String = "",
        val project: String = "",
        val expiresAt: String = "",
        val lastStatus: String = ""
    ) {
        val usable: Boolean
            get() = enabled && baseUrl.isNotBlank() && token.isNotBlank()
    }

    data class ExchangeResult(val config: Config, val message: String)

    private var prefs: SharedPreferences? = null

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

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

    fun config(context: Context): Config {
        val p = prefs(context)
        return Config(
            enabled = p.getBoolean(KEY_ENABLED, false),
            baseUrl = p.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty(),
            token = p.getString(KEY_TOKEN, "").orEmpty(),
            project = p.getString(KEY_PROJECT, "").orEmpty(),
            expiresAt = p.getString(KEY_EXPIRES, "").orEmpty(),
            lastStatus = p.getString(KEY_STATUS, "").orEmpty()
        )
    }

    fun save(context: Context, cfg: Config) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, cfg.enabled)
            .putString(KEY_BASE_URL, cfg.baseUrl.trim())
            .putString(KEY_TOKEN, cfg.token.trim())
            .putString(KEY_PROJECT, cfg.project)
            .putString(KEY_EXPIRES, cfg.expiresAt)
            .putString(KEY_STATUS, cfg.lastStatus)
            .apply()
    }

    fun updateStatus(context: Context, status: String) {
        prefs(context).edit().putString(KEY_STATUS, status.take(300)).apply()
    }

    private fun normalizeBase(raw: String): String {
        var b = raw.trim().trimEnd('/')
        if (b.isEmpty()) b = DEFAULT_BASE_URL
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "https://$b"
        return b
    }

    /**
     * 6 位校验码换取 Agent JWT。
     * 成功后会直接落盘 (enabled=true)。
     */
    suspend fun exchange(context: Context, baseUrl: String, code: String): Result<ExchangeResult> =
        withContext(Dispatchers.IO) {
            val base = normalizeBase(baseUrl)
            val trimmed = code.trim()
            if (!Regex("^\\d{6}$").matches(trimmed)) {
                return@withContext Result.failure(Exception("校验码是 6 位数字"))
            }
            val body = JSONObject().put("code", trimmed).toString()
            val req = Request.Builder()
                .url("$base/logReport/exchange")
                .post(body.toRequestBody(jsonMedia))
                .build()
            try {
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val json = try {
                        JSONObject(text)
                    } catch (e: Exception) {
                        return@withContext Result.failure(
                            Exception("接口返回异常 (HTTP ${resp.code})")
                        )
                    }
                    val data = json.optJSONObject("data")
                    if (json.optInt("code", -1) != 0 || data == null) {
                        return@withContext Result.failure(
                            Exception(json.optString("message", "换取令牌失败"))
                        )
                    }
                    val token = data.optString("token")
                    if (token.isBlank()) {
                        return@withContext Result.failure(Exception("接口没有返回 token"))
                    }
                    val path = data.optString("project_path_text")
                    val cfg = Config(
                        enabled = true,
                        baseUrl = base,
                        token = token,
                        project = path,
                        expiresAt = data.optString("token_expires_at"),
                        lastStatus = "令牌已获取 · $path"
                    )
                    save(context, cfg)
                    Result.success(ExchangeResult(cfg, "已绑定: $path"))
                }
            } catch (e: Exception) {
                Result.failure(Exception("换取失败: ${e.message}"))
            }
        }

    /** 自检: 确认 token 对应项目与有效期 */
    suspend fun whoami(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val cfg = config(context)
        if (cfg.token.isBlank()) return@withContext Result.failure(Exception("还没有令牌"))
        val req = Request.Builder()
            .url("${normalizeBase(cfg.baseUrl)}/logReport/whoami")
            .header("Authorization", "Bearer ${cfg.token}")
            .get()
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return@withContext Result.failure(Exception("接口返回异常 (HTTP ${resp.code})"))
                }
                if (json.optInt("code", -1) != 0) {
                    return@withContext Result.failure(
                        Exception(json.optString("message", "令牌无效 (HTTP ${resp.code})"))
                    )
                }
                val data = json.optJSONObject("data")
                val project = data?.optJSONObject("project")?.optString("name").orEmpty()
                val expires = data?.optJSONObject("token")?.optString("expires_at").orEmpty()
                Result.success(
                    "令牌有效" +
                        (if (project.isNotBlank()) " · $project" else "") +
                        (if (expires.isNotBlank()) " · 到期 $expires" else "")
                )
            }
        } catch (e: Exception) {
            Result.failure(Exception("自检失败: ${e.message}"))
        }
    }
}
