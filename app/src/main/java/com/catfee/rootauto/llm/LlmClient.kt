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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * LlmClient —— OpenAI 兼容接口客户端
 *
 * 功能描述:
 *   对接任意 OpenAI 兼容的 /v1 接口, 提供三个能力:
 *     1. listModels  拉取 BaseUrl 下所有可用模型
 *     2. chat        普通对话 (支持多模态图片输入)
 *     3. chatWithTools 带 function calling 的对话, 供 Agent ReAct 循环使用
 *
 * 参数说明:
 *   所有方法都显式传入 Provider (BaseUrl / API Key / model), 便于多配置切换。
 *
 * 返回值说明:
 *   LlmResult 统一封装成功内容、工具调用、错误信息与耗时。
 *
 * 使用场景:
 *   Agent 每一轮「思考」; 设置页的模型拉取与拨测; 截图降级识别。
 *
 * 修改时间: 2026-11-03
 */
object LlmClient {

    private const val TAG = "LlmClient"

    /** 工具调用步骤的单次输出上限, 防止推理模型无限展开; 4096 足够容纳 tool_calls。 */
    private const val MAX_OUTPUT_TOKENS = 4_096

    /** 普通对话/工具调用总超时; 与 OkHttp callTimeout 及 Agent 调用方保持一致。 */
    private const val CHAT_TIMEOUT_MS = 180_000L

    /** 拉模型列表超时 */
    private const val MODELS_TIMEOUT_MS = 20_000L

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** 复用一个 OkHttpClient, 避免每次调用都新建连接池 */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(CHAT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(CHAT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 一次工具调用请求 */
    data class ToolCall(val id: String, val name: String, val arguments: String)

    /**
     * 统一返回结构
     * @param ok         是否成功
     * @param content    助手文本内容 (可能为空, 尤其是纯工具调用时)
     * @param toolCalls  模型请求调用的工具列表
     * @param reasoning  推理模型的思维链 (部分模型返回, 仅用于日志展示)
     * @param error      失败原因
     * @param elapsedMs  耗时毫秒
     */
    data class LlmResult(
        val ok: Boolean,
        val content: String = "",
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoning: String = "",
        val error: String = "",
        val elapsedMs: Long = 0,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0
    )

    /** 把用户填的 BaseUrl 规范成不带结尾斜杠、且以 /v1 结尾的形式 */
    private fun normalizeBase(raw: String): String {
        var b = raw.trim().trimEnd('/')
        if (b.isEmpty()) return ""
        // 用户可能填 http://host:13000 或 http://host:13000/v1, 统一补 /v1
        if (!b.endsWith("/v1")) b = "$b/v1"
        return b
    }

    /** 构造鉴权请求头 */
    private fun Request.Builder.auth(key: String) = apply {
        if (key.isNotBlank()) header("Authorization", "Bearer ${key.trim()}")
    }

    /**
     * 功能: 拉取该 BaseUrl 下所有模型
     * 参数: provider 需含 baseUrl / apiKey (model 可空)
     * 返回: (模型 id 列表, 错误信息) —— 成功时错误信息为空串
     * 使用场景: 设置页「拉取模型」按钮, 也用于拨测第一层连通性
     */
    suspend fun listModels(provider: LlmSettings.Provider): Pair<List<String>, String> =
        withContext(Dispatchers.IO) {
            val base = normalizeBase(provider.baseUrl)
            if (base.isEmpty()) return@withContext emptyList<String>() to "BaseUrl 为空"
            try {
                val req = Request.Builder()
                    .url("$base/models")
                    .auth(provider.apiKey)
                    .get()
                    .build()
                // 与 post() 一样用 ERR_CODE_ 前缀区分业务错误, 避免返回类型混用
                val body = withTimeoutOrNull(MODELS_TIMEOUT_MS) {
                    runInterruptible {
                        http.newCall(req).execute().use { resp ->
                            val text = resp.body?.string() ?: ""
                            if (!resp.isSuccessful) "ERR_CODE_${resp.code}: ${text.take(200)}" else text
                        }
                    }
                } ?: return@withContext emptyList<String>() to "拉取模型超时"
                if (body.startsWith("ERR_CODE_")) {
                    return@withContext emptyList<String>() to body.removePrefix("ERR_CODE_")
                }
                val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
                val ids = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                }
                if (ids.isEmpty()) emptyList<String>() to "接口返回的模型列表为空"
                else ids to ""
            } catch (e: Exception) {
                Log.e(TAG, "拉取模型失败: ${e.message}")
                emptyList<String>() to (e.message ?: "未知错误")
            }
        }

    /**
     * 功能: 普通对话, 支持多模态 (文本 + 图片 base64)
     * 参数:
     *   provider   LLM 配置
     *   messages   已构造好的 OpenAI 消息数组
     *   timeoutMs  超时, 默认 180s
     * 返回: LlmResult
     * 使用场景: 截图降级识别 (把图片交给视觉模型读屏)
     */
    suspend fun chat(
        provider: LlmSettings.Provider,
        messages: JSONArray,
        timeoutMs: Long = CHAT_TIMEOUT_MS
    ): LlmResult = post(provider, buildBody(provider, messages, null, null), timeoutMs)

    /**
     * 功能: 带工具调用的对话
     * 参数:
     *   provider   LLM 配置
     *   messages   消息数组 (含历史)
     *   tools      工具定义数组 (OpenAI function 格式)
     * 返回: LlmResult, 其中 toolCalls 非空表示模型要求执行动作
     * 使用场景: Agent ReAct 循环的主入口
     */
    suspend fun chatWithTools(
        provider: LlmSettings.Provider,
        messages: JSONArray,
        tools: JSONArray,
        timeoutMs: Long = CHAT_TIMEOUT_MS
    ): LlmResult = post(provider, buildBody(provider, messages, tools, "auto"), timeoutMs)

    /** 构造请求体 JSON */
    private fun buildBody(
        provider: LlmSettings.Provider,
        messages: JSONArray,
        tools: JSONArray?,
        toolChoice: String?
    ): String {
        val body = JSONObject()
            .put("model", provider.model)
            .put("messages", messages)
            .put("temperature", 0.2)
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
            if (toolChoice != null) body.put("tool_choice", toolChoice)
            // 工具调用步骤限制输出, 防止推理模型无限展开; 4096 足够容纳工具参数和简短说明。
            body.put("max_tokens", MAX_OUTPUT_TOKENS)
        }
        return body.toString()
    }

    /** 统一 POST 到 /chat/completions 并解析结果 */
    private suspend fun post(
        provider: LlmSettings.Provider,
        bodyJson: String,
        timeoutMs: Long
    ): LlmResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val base = normalizeBase(provider.baseUrl)
        if (base.isEmpty()) return@withContext LlmResult(false, error = "BaseUrl 为空")
        if (provider.model.isBlank()) return@withContext LlmResult(false, error = "未选择模型")
        try {
            val req = Request.Builder()
                .url("$base/chat/completions")
                .auth(provider.apiKey)
                .post(bodyJson.toRequestBody(jsonMedia))
                .build()
            // 先用一处可中断的阻塞调用, 再统一解析
            val raw: Any? = withTimeoutOrNull(timeoutMs) {
                runInterruptible {
                    http.newCall(req).execute().use { resp ->
                        val text = resp.body?.string() ?: ""
                        if (!resp.isSuccessful) {
                            "ERR_CODE_${resp.code}: ${text.take(300)}"
                        } else text
                    }
                }
            }
            val elapsed = System.currentTimeMillis() - started
            if (raw == null) return@withContext LlmResult(false, error = "请求超时(${timeoutMs}ms)", elapsedMs = elapsed)
            val text = raw as String
            if (text.startsWith("ERR_CODE_")) {
                return@withContext LlmResult(false, error = text.removePrefix("ERR_CODE_"), elapsedMs = elapsed)
            }
            parseResponse(text, elapsed)
        } catch (e: Exception) {
            Log.e(TAG, "LLM 请求异常: ${e.message}")
            LlmResult(false, error = e.message ?: "未知错误", elapsedMs = System.currentTimeMillis() - started)
        }
    }

    /**
     * 流式 chat/completions (SSE)。
     * 边读边回调 content/reasoning, 工具调用按 index 累积; 最终返回完整 LlmResult。
     * 服务端不支持 SSE (返回普通 JSON) 时自动按普通响应解析。
     */
    suspend fun chatWithToolsStream(
        provider: LlmSettings.Provider,
        messages: JSONArray,
        tools: JSONArray,
        onReasoning: (String) -> Unit = {},
        onContent: (String) -> Unit = {},
        timeoutMs: Long = CHAT_TIMEOUT_MS
    ): LlmResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val base = normalizeBase(provider.baseUrl)
        if (base.isEmpty()) return@withContext LlmResult(false, error = "BaseUrl 为空")
        if (provider.model.isBlank()) return@withContext LlmResult(false, error = "未选择模型")

        val body = JSONObject()
            .put("model", provider.model)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
        if (tools.length() > 0) {
            body.put("tools", tools)
            body.put("tool_choice", "auto")
            body.put("max_tokens", MAX_OUTPUT_TOKENS)
        }
        try {
            val req = Request.Builder()
                .url("$base/chat/completions")
                .auth(provider.apiKey)
                .post(body.toString().toRequestBody(jsonMedia))
                .build()

            val result: LlmResult? = withTimeoutOrNull(timeoutMs) {
                runInterruptible {
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val err = resp.body?.string().orEmpty().take(300)
                            return@runInterruptible LlmResult(
                                false, error = "HTTP ${resp.code}: $err",
                                elapsedMs = System.currentTimeMillis() - started
                            )
                        }
                        val contentType = resp.header("Content-Type").orEmpty()
                        val source = resp.body?.source()
                            ?: return@runInterruptible LlmResult(
                                false, error = "响应为空",
                                elapsedMs = System.currentTimeMillis() - started
                            )
                        if (!contentType.contains("text/event-stream")) {
                            val text = source.readUtf8()
                            return@runInterruptible parseResponse(
                                text, System.currentTimeMillis() - started
                            )
                        }

                        val content = StringBuilder()
                        val reasoning = StringBuilder()
                        val toolMap = linkedMapOf<Int, StreamToolCall>()
                        var promptTokens = 0
                        var completionTokens = 0
                        var totalTokens = 0
                        var streamError = ""

                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank() || line.startsWith(":")) continue
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            val obj = try {
                                JSONObject(data)
                            } catch (e: Exception) {
                                continue
                            }
                            obj.optJSONObject("error")?.let {
                                streamError = it.optString("message", "流式错误")
                            }
                            obj.optJSONObject("usage")?.let { u ->
                                promptTokens = u.optInt(
                                    "prompt_tokens", u.optInt("input_tokens", 0)
                                )
                                completionTokens = u.optInt(
                                    "completion_tokens", u.optInt("output_tokens", 0)
                                )
                                totalTokens = u.optInt(
                                    "total_tokens", promptTokens + completionTokens
                                )
                            }
                            val delta = obj.optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                                ?: continue
                            if (!delta.isNull("content")) {
                                val piece = delta.optString("content", "")
                                if (piece.isNotEmpty()) {
                                    content.append(piece)
                                    onContent(piece)
                                }
                            }
                            val reasonPiece = if (!delta.isNull("reasoning_content")) {
                                delta.optString("reasoning_content", "")
                            } else if (!delta.isNull("reasoning")) {
                                delta.optString("reasoning", "")
                            } else {
                                ""
                            }
                            if (reasonPiece.isNotEmpty()) {
                                reasoning.append(reasonPiece)
                                onReasoning(reasonPiece)
                            }
                            delta.optJSONArray("tool_calls")?.let { arr ->
                                for (i in 0 until arr.length()) {
                                    val tc = arr.optJSONObject(i) ?: continue
                                    val idx = tc.optInt("index", i)
                                    val slot = toolMap.getOrPut(idx) { StreamToolCall() }
                                    if (!tc.isNull("id")) slot.id = tc.optString("id")
                                    tc.optJSONObject("function")?.let { fn ->
                                        if (!fn.isNull("name")) slot.name = fn.optString("name")
                                        if (!fn.isNull("arguments")) {
                                            slot.args.append(fn.optString("arguments"))
                                        }
                                    }
                                }
                            }
                        }

                        if (streamError.isNotBlank()) {
                            return@runInterruptible LlmResult(
                                false, error = streamError,
                                elapsedMs = System.currentTimeMillis() - started
                            )
                        }
                        val calls = toolMap.entries.sortedBy { it.key }.map { (i, s) ->
                            ToolCall(
                                id = s.id.ifBlank { "call_$i" },
                                name = s.name,
                                arguments = s.args.toString().ifBlank { "{}" }
                            )
                        }
                        LlmResult(
                            ok = true,
                            content = content.toString(),
                            toolCalls = calls,
                            reasoning = reasoning.toString(),
                            elapsedMs = System.currentTimeMillis() - started,
                            promptTokens = promptTokens,
                            completionTokens = completionTokens,
                            totalTokens = totalTokens
                        )
                    }
                }
            }
            result ?: LlmResult(
                false, error = "流式请求超时(${timeoutMs}ms)",
                elapsedMs = System.currentTimeMillis() - started
            )
        } catch (e: Exception) {
            LlmResult(
                false, error = e.message ?: "流式请求失败",
                elapsedMs = System.currentTimeMillis() - started
            )
        }
    }

    private class StreamToolCall {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    /** 解析 chat/completions 响应为标准结果 */
    private fun parseResponse(text: String, elapsed: Long): LlmResult {
        return try {
            val root = JSONObject(text)
            val choice = root.optJSONArray("choices")?.optJSONObject(0)
                ?: return LlmResult(false, error = "响应缺少 choices", elapsedMs = elapsed)
            val msg = choice.optJSONObject("message")
                ?: return LlmResult(false, error = "响应缺少 message", elapsedMs = elapsed)
            // 注意: Android 的 org.json 对 JSON null 调 optString 会返回字符串 "null",
            // 必须先用 isNull 判断, 否则界面会显示 "思考: null"
            val content = if (msg.isNull("content")) "" else msg.optString("content", "").orEmpty()
            val reasoning = if (msg.isNull("reasoning")) "" else msg.optString("reasoning", "").orEmpty()
            val calls = mutableListOf<ToolCall>()
            msg.optJSONArray("tool_calls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val fn = c.optJSONObject("function") ?: continue
                    calls.add(
                        ToolCall(
                            id = c.optString("id", "call_$i"),
                            name = fn.optString("name", ""),
                            arguments = fn.optString("arguments", "{}")
                        )
                    )
                }
            }
            val usage = root.optJSONObject("usage")
            val promptTokens = usage?.optInt(
                "prompt_tokens",
                usage.optInt("input_tokens", 0)
            ) ?: 0
            val completionTokens = usage?.optInt(
                "completion_tokens",
                usage.optInt("output_tokens", 0)
            ) ?: 0
            val totalTokens = usage?.optInt(
                "total_tokens",
                promptTokens + completionTokens
            ) ?: (promptTokens + completionTokens)
            LlmResult(
                ok = true,
                content = content,
                toolCalls = calls,
                reasoning = reasoning,
                elapsedMs = elapsed,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析响应失败: ${e.message}")
            LlmResult(false, error = "解析响应失败: ${e.message}", elapsedMs = elapsed)
        }
    }
}
