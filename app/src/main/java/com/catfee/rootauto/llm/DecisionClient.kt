package com.catfee.rootauto.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DecisionClient —— 百炼 decision 接口客户端
 *
 * 与主 LLM 不同, 这个接口不生成文本/工具参数, 只根据 state 对固定 questions
 * 返回 choice / score / noul 概率。适合做低延迟路由:
 *   observe / wait / act / finish
 *
 * 注意: 鉴权头使用原始 Key, 不加 Bearer (与该部署的 /systemone 接口一致)。
 */
object DecisionClient {

    private const val TAG = "DecisionClient"

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    data class Outcome(
        val ok: Boolean = false,
        val action: String = "",
        val confidence: Double = 0.0,
        val latencyMs: Long = 0,
        val inputTokens: Int = 0,
        val error: String = ""
    )

    private fun endpoint(raw: String): String {
        val base = raw.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return if (base.endsWith("/systemone")) base else "$base/systemone"
    }

    /**
     * 请求一次快速决策。
     * state 建议包含: goal / step / last_tool / observation / foreground_app
     */
    suspend fun decide(
        config: DecisionSettings.Config,
        state: JSONObject,
        timeoutMs: Long = 2_500
    ): Outcome = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val url = endpoint(config.baseUrl)
        if (url.isEmpty()) return@withContext Outcome(error = "DECISION Base URL 为空")
        if (config.apiKey.isBlank()) return@withContext Outcome(error = "DECISION Key 为空")
        if (config.model.isBlank()) return@withContext Outcome(error = "DECISION 模型为空")

        val questions = JSONObject().put(
            "next_action",
            JSONObject()
                .put("type", "choice")
                .put(
                    "instructions",
                    "根据任务目标、当前步骤和最近一次页面观察，选择下一步最合适的动作。"
                )
                .put(
                    "criteria",
                    JSONObject()
                        .put("observe", "需要重新读取当前页面或确认上一步操作结果")
                        .put("wait", "页面正在加载、动画未结束，或需要短暂等待")
                        .put("act", "需要点击、输入、滑动、启动应用等具体设备操作")
                        .put("finish", "任务已经完成，可以立即结束")
                )
        )

        val body = JSONObject()
            .put("model", config.model)
            .put("state", state)
            .put("questions", questions)
            .toString()

        try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", config.apiKey.trim())
                .post(body.toRequestBody(jsonMedia))
                .build()

            val raw = withTimeoutOrNull(timeoutMs) {
                runInterruptible {
                    http.newCall(request).execute().use { response ->
                        val text = response.body?.string() ?: ""
                        if (!response.isSuccessful) {
                            "ERR_CODE_${response.code}: ${text.take(300)}"
                        } else {
                            text
                        }
                    }
                }
            } ?: return@withContext Outcome(
                error = "DECISION 请求超时(${timeoutMs}ms)",
                latencyMs = System.currentTimeMillis() - started
            )

            val elapsed = System.currentTimeMillis() - started
            if (raw.startsWith("ERR_CODE_")) {
                return@withContext Outcome(
                    error = raw.removePrefix("ERR_CODE_"),
                    latencyMs = elapsed
                )
            }

            val root = JSONObject(raw)
            val answer = root.optJSONObject("answers")?.optJSONObject("next_action")
                ?: return@withContext Outcome(
                    error = "响应缺少 answers.next_action",
                    latencyMs = elapsed
                )
            val action = answer.optString("choice").trim()
            val probabilities = answer.optJSONObject("probabilities")
            val fromProbability = if (probabilities != null && action.isNotEmpty()) {
                probabilities.optDouble(action, 0.0)
            } else {
                0.0
            }
            val confidence = answer.optDouble("confidence", fromProbability)
                .takeIf { !it.isNaN() } ?: fromProbability
            val tokens = root.optJSONObject("usage")?.optInt("input_tokens", 0) ?: 0

            Outcome(
                ok = action.isNotEmpty(),
                action = action,
                confidence = confidence.coerceIn(0.0, 1.0),
                latencyMs = elapsed,
                inputTokens = tokens,
                error = if (action.isEmpty()) "响应缺少 choice" else ""
            )
        } catch (e: Exception) {
            Log.w(TAG, "DECISION 请求失败: ${e.message}")
            Outcome(
                error = e.message ?: "DECISION 请求失败",
                latencyMs = System.currentTimeMillis() - started
            )
        }
    }
}
