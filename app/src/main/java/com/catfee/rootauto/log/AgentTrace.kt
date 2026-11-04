package com.catfee.rootauto.log

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * AgentTrace —— Agent 每一步的远程日志
 *
 * 只写「任务/步骤/工具输入输出」这类结构化文本, 不传截图二进制。
 * 上报是异步的: Agent 主循环只做 trySend, 网络慢/断网不会阻塞操作。
 *
 * 服务端: clouddream-server /logReport/reports (content_type=json)
 * 鉴权:   LogSettings 里的 Agent JWT
 */
object AgentTrace {

    private const val TAG = "AgentTrace"
    private const val MAX_TEXT = 16_000
    private const val MAX_TOOL_RESULT = 8_000
    private const val MAX_ARGS = 4_000
    private const val MAX_SYSTEM = 12_000

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<JSONObject>(256)

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    @Volatile private var senderStarted = false
    @Volatile private var cfg: LogSettings.Config? = null
    @Volatile private var taskId = ""
    @Volatile private var startedAt = 0L
    @Volatile private var goal = ""
    @Volatile private var model = ""
    @Volatile private var pkg = ""

    data class ToolTrace(
        val name: String,
        val arguments: String,
        val result: String,
        val elapsedMs: Long,
        val ok: Boolean = true
    )

    /** 任务开始: 记录目标、模型、当前 App、系统提示词 */
    fun startTask(
        context: Context,
        goal: String,
        model: String,
        systemPrompt: String,
        packageName: String
    ) {
        cfg = LogSettings.config(context)
        _status.value = cfg?.lastStatus.orEmpty()
        if (cfg?.usable != true) return
        ensureSender(context.applicationContext)
        taskId = "T" + System.currentTimeMillis().toString(36).lowercase() +
            Random.nextInt(0x1000, 0xFFFF).toString(16)
        startedAt = System.currentTimeMillis()
        this.goal = goal
        this.model = model
        pkg = packageName
        val content = JSONObject()
            .put("task_id", taskId)
            .put("goal", goal.take(MAX_TEXT))
            .put("model", model)
            .put("package", packageName)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("system_prompt", systemPrompt.take(MAX_SYSTEM))
        enqueue(
            buildReport(
                title = "任务开始 · ${goal.trim().take(40)}",
                level = "info",
                content = content,
                tags = listOf("agent", "task-start", packageName.ifBlank { "unknown" }),
                extra = JSONObject()
                    .put("task_id", taskId)
                    .put("model", model)
                    .put("package", packageName)
            )
        )
    }

    /** 一轮 LLM + 工具执行结束 */
    fun step(
        context: Context,
        step: Int,
        input: String,
        output: String,
        plannedTools: List<Pair<String, String>>,
        tools: List<ToolTrace>,
        llmMs: Long,
        reasoning: String = "",
        contextChars: Int = 0,
        contextMessages: Int = 0
    ) {
        if (cfg?.usable != true) return
        val planned = JSONArray()
        plannedTools.forEach { (name, args) ->
            planned.put(JSONObject().put("name", name).put("arguments", args.take(MAX_ARGS)))
        }
        val toolArr = JSONArray()
        tools.forEach { t ->
            toolArr.put(
                JSONObject()
                    .put("name", t.name)
                    .put("arguments", t.arguments.take(MAX_ARGS))
                    .put("result", t.result.take(MAX_TOOL_RESULT))
                    .put("elapsed_ms", t.elapsedMs)
                    .put("ok", t.ok)
            )
        }
        val content = JSONObject()
            .put("task_id", taskId)
            .put("step", step)
            .put("goal", goal.take(MAX_TEXT))
            .put("model", model)
            .put("package", pkg)
            .put("llm_ms", llmMs)
            .put("context", JSONObject().put("chars", contextChars).put("messages", contextMessages))
            .put("input", JSONObject().put("last_message", input.take(MAX_TEXT)))
            .put(
                "output",
                JSONObject()
                    .put("content", output.take(MAX_TEXT))
                    .put("reasoning", reasoning.take(MAX_TEXT))
                    .put("tool_calls", planned)
            )
            .put("tools", toolArr)
        val failed = tools.any { !it.ok }
        val toolName = tools.firstOrNull()?.name ?: "think"
        enqueue(
            buildReport(
                title = "Step $step · $toolName",
                level = if (failed) "error" else "info",
                content = content,
                tags = listOf("agent", "step", pkg.ifBlank { "unknown" }),
                extra = JSONObject()
                    .put("task_id", taskId)
                    .put("step", step)
                    .put("package", pkg)
                    .put("model", model)
                    .put("llm_ms", llmMs)
                    .put("context_chars", contextChars)
                    .put("context_messages", contextMessages)
                    .put("tool_count", tools.size)
            )
        )
    }

    /** 任务结束 / 失败 / 用户停止 */
    fun finish(context: Context, success: Boolean, result: String, steps: Int) {
        if (cfg?.usable != true) return
        val content = JSONObject()
            .put("task_id", taskId)
            .put("goal", goal.take(MAX_TEXT))
            .put("success", success)
            .put("result", result.take(MAX_TEXT))
            .put("steps", steps)
            .put("duration_ms", System.currentTimeMillis() - startedAt)
        enqueue(
            buildReport(
                title = (if (success) "任务完成" else "任务结束") + " · " +
                    goal.trim().take(40),
                level = if (success) "success" else "error",
                content = content,
                tags = listOf("agent", "task-finish", pkg.ifBlank { "unknown" }),
                extra = JSONObject()
                    .put("task_id", taskId)
                    .put("success", success)
                    .put("steps", steps)
            )
        )
    }

    /** 重试/系统干预等旁路事件 */
    fun event(context: Context, level: String, title: String, message: String) {
        if (cfg?.usable != true) return
        enqueue(
            buildReport(
                title = title.take(200),
                level = level,
                content = JSONObject()
                    .put("task_id", taskId)
                    .put("goal", goal.take(MAX_TEXT))
                    .put("message", message.take(MAX_TEXT)),
                tags = listOf("agent", "event", pkg.ifBlank { "unknown" }),
                extra = JSONObject().put("task_id", taskId).put("package", pkg)
            )
        )
    }

    private fun buildReport(
        title: String,
        level: String,
        content: JSONObject,
        tags: List<String>,
        extra: JSONObject
    ): JSONObject {
        val tagArr = JSONArray()
        tags.filter { it.isNotBlank() }.forEach { tagArr.put(it.take(32)) }
        return JSONObject()
            .put("content_type", "json")
            .put("level", level)
            .put("title", title.take(255))
            .put("content", content)
            .put("tags", tagArr)
            .put("source", "CatfeeAgent/Android")
            .put("extra", extra)
    }

    private fun enqueue(report: JSONObject) {
        if (cfg?.usable != true) return
        if (!queue.trySend(report).isSuccess) {
            Log.w(TAG, "日志队列已满, 丢弃一条")
        }
    }

    private fun ensureSender(context: Context) {
        if (senderStarted) return
        synchronized(this) {
            if (senderStarted) return
            senderStarted = true
            scope.launch {
                for (report in queue) {
                    send(context, report)
                }
            }
        }
    }

    private fun send(context: Context, report: JSONObject) {
        val c = cfg ?: return
        if (!c.usable) return
        val url = c.baseUrl.trimEnd('/') + "/logReport/reports"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${c.token}")
            .post(report.toString().toRequestBody(jsonMedia))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val ok = resp.isSuccessful && text.contains("\"code\":0")
                val status = if (ok) {
                    "上报成功 · ${report.optString("title").take(40)}"
                } else {
                    "上报失败 HTTP ${resp.code}: ${text.take(120)}"
                }
                _status.value = status
                LogSettings.updateStatus(context, status)
            }
        } catch (e: Exception) {
            val status = "上报失败: ${e.message}"
            _status.value = status
            LogSettings.updateStatus(context, status)
            Log.w(TAG, status)
        }
    }
}
