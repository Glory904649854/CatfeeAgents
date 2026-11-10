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
import java.util.Collections
import java.util.WeakHashMap
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
    private val queue = Channel<QueuedReport>(256)

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

    /** 任务级的不可变身份, 每条报告都只能从这个对象取 taskId/goal/model/package。 */
    data class TraceContext(
        val taskId: String,
        val goal: String,
        val model: String,
        val packageName: String,
        val startedAt: Long
    )

    /** 一次 Agent run 的日志句柄。句柄本身不依赖 AgentTrace 的可变当前任务。 */
    class TraceRun internal constructor(
        val task: TraceContext,
        internal val config: LogSettings.Config,
        internal val statusContext: Context
    ) {
        val enabled: Boolean get() = config.usable
    }

    private data class QueuedReport(
        val report: JSONObject,
        /** 入队时捕获, 发送阶段绝不从全局可变任务字段读取。 */
        val task: TraceContext,
        val config: LogSettings.Config,
        val statusContext: Context
    )

    /** 仅为旧的无 handle API 保留的兼容映射; ReActAgent 不使用它。 */
    private val legacyRuns = Collections.synchronizedMap(WeakHashMap<Context, TraceRun>())

    data class ToolTrace(
        val name: String,
        val arguments: String,
        val result: String,
        val elapsedMs: Long,
        val ok: Boolean = true
    )

    /** 任务开始: 记录目标、模型、当前 App、系统提示词。返回本次 run 的日志句柄。 */
    fun startTask(
        context: Context,
        goal: String,
        model: String,
        systemPrompt: String,
        packageName: String
    ): TraceRun {
        val config = LogSettings.config(context)
        _status.value = config.lastStatus
        val task = TraceContext(
            taskId = "T" + System.currentTimeMillis().toString(36).lowercase() +
                Random.nextInt(0x1000, 0xFFFF).toString(16),
            goal = goal,
            model = model,
            packageName = packageName,
            startedAt = System.currentTimeMillis()
        )
        val run = TraceRun(task, config, context.applicationContext)
        // 只服务旧调用方; 新调用方始终显式传 run, 因而不会串线。
        legacyRuns[run.statusContext] = run
        if (!run.enabled) return run

        ensureSender(run.statusContext)
        val content = JSONObject()
            .put("task_id", task.taskId)
            .put("goal", task.goal.take(MAX_TEXT))
            .put("model", task.model)
            .put("package", task.packageName)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("system_prompt", systemPrompt.take(MAX_SYSTEM))
        enqueue(
            run,
            buildReport(
                title = "任务开始 · ${task.goal.trim().take(40)}",
                level = "info",
                content = content,
                tags = listOf("agent", "task-start", task.packageName.ifBlank { "unknown" }),
                extra = JSONObject()
                    .put("task_id", task.taskId)
                    .put("model", task.model)
                    .put("package", task.packageName)
            )
        )
        return run
    }

    /** 一轮 LLM + 工具执行结束。 */
    fun step(
        context: Context,
        run: TraceRun,
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
        if (!run.enabled) return
        val task = run.task
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
            .put("task_id", task.taskId)
            .put("step", step)
            .put("goal", task.goal.take(MAX_TEXT))
            .put("model", task.model)
            .put("package", task.packageName)
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
            run,
            buildReport(
                title = "Step $step · $toolName",
                level = if (failed) "error" else "info",
                content = content,
                tags = listOf("agent", "step", task.packageName.ifBlank { "unknown" }),
                extra = JSONObject()
                    .put("task_id", task.taskId)
                    .put("step", step)
                    .put("package", task.packageName)
                    .put("model", task.model)
                    .put("llm_ms", llmMs)
                    .put("context_chars", contextChars)
                    .put("context_messages", contextMessages)
                    .put("tool_count", tools.size)
            )
        )
    }

    /** 任务结束 / 失败 / 用户停止。 */
    fun finish(context: Context, run: TraceRun, success: Boolean, result: String, steps: Int) {
        if (!run.enabled) return
        val task = run.task
        val content = JSONObject()
            .put("task_id", task.taskId)
            .put("goal", task.goal.take(MAX_TEXT))
            .put("success", success)
            .put("result", result.take(MAX_TEXT))
            .put("steps", steps)
            .put("duration_ms", System.currentTimeMillis() - task.startedAt)
        enqueue(
            run,
            buildReport(
                title = (if (success) "任务完成" else "任务结束") + " · " +
                    task.goal.trim().take(40),
                level = if (success) "success" else "error",
                content = content,
                tags = listOf("agent", "task-finish", task.packageName.ifBlank { "unknown" }),
                extra = JSONObject()
                    .put("task_id", task.taskId)
                    .put("success", success)
                    .put("steps", steps)
            )
        )
    }

    /** 重试/系统干预等旁路事件。 */
    fun event(context: Context, run: TraceRun, level: String, title: String, message: String) {
        if (!run.enabled) return
        val task = run.task
        enqueue(
            run,
            buildReport(
                title = title.take(200),
                level = level,
                content = JSONObject()
                    .put("task_id", task.taskId)
                    .put("goal", task.goal.take(MAX_TEXT))
                    .put("message", message.take(MAX_TEXT)),
                tags = listOf("agent", "event", task.packageName.ifBlank { "unknown" }),
                extra = JSONObject().put("task_id", task.taskId).put("package", task.packageName)
            )
        )
    }

    // 兼容旧调用方: 新代码必须使用上面的 handle overload, 旧调用方按 Context 取最近一次 run。
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
        legacyRuns[context.applicationContext]?.let {
            step(context, it, step, input, output, plannedTools, tools, llmMs,
                reasoning, contextChars, contextMessages)
        }
    }

    fun finish(context: Context, success: Boolean, result: String, steps: Int) {
        legacyRuns[context.applicationContext]?.let { finish(context, it, success, result, steps) }
    }

    fun event(context: Context, level: String, title: String, message: String) {
        legacyRuns[context.applicationContext]?.let { event(context, it, level, title, message) }
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
            .put("source", "CatfeeAgents/Android")
            .put("extra", extra)
    }

    private fun enqueue(run: TraceRun, report: JSONObject) {
        if (!run.enabled) return
        val queued = QueuedReport(report, run.task, run.config, run.statusContext)
        if (!queue.trySend(queued).isSuccess) {
            Log.w(TAG, "日志队列已满, 丢弃一条")
        }
    }

    private fun ensureSender(context: Context) {
        if (senderStarted) return
        synchronized(this) {
            if (senderStarted) return
            senderStarted = true
            scope.launch {
                for (queued in queue) {
                    send(queued)
                }
            }
        }
    }

    private fun send(queued: QueuedReport) {
        val c = queued.config
        if (!c.usable) return
        val url = c.baseUrl.trimEnd('/') + "/logReport/reports"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${c.token}")
            .post(queued.report.toString().toRequestBody(jsonMedia))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val ok = resp.isSuccessful && text.contains("\"code\":0")
                val status = if (ok) {
                    "上报成功 · ${queued.report.optString("title").take(40)}"
                } else {
                    "上报失败 HTTP ${resp.code}: ${text.take(120)}"
                }
                _status.value = status
                LogSettings.updateStatus(queued.statusContext, status)
            }
        } catch (e: Exception) {
            val status = "上报失败: ${e.message}"
            _status.value = status
            LogSettings.updateStatus(queued.statusContext, status)
            Log.w(TAG, status)
        }
    }
}
