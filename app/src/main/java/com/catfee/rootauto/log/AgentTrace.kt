package com.catfee.rootauto.log

import android.content.Context
import android.os.Build
import android.util.Log
import com.catfee.rootauto.console.ConsoleClient
import com.catfee.rootauto.console.ConsoleSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AgentTrace —— Agent 每一步的结构化日志，统一走内置 Catfee Web 控制台。
 *
 * 和旧版区别：
 *   - 不再请求第三方 /logReport，也不再保存 Agent JWT
 *   - 使用 ConsoleSettings 里的设备凭证，POST /api/agent/events
 *   - Web 任务使用服务端 taskId，事件直接进入任务时间线
 *   - 本地聊天任务用 local_ 前缀，只进日志，不影响 Web 任务状态
 *
 * 上报是异步批量：Agent 主循环只做 trySend，网络慢/断网不会阻塞操作。
 */
object AgentTrace {

    private const val TAG = "AgentTrace"
    private const val MAX_TEXT = 6_000
    private const val MAX_TOOL_RESULT = 2_000
    private const val MAX_ARGS = 800
    private const val MAX_REASONING = 2_000
    private const val BATCH_SIZE = 10
    private const val MAX_RETRY = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<JSONObject>(512)

    @Volatile private var senderStarted = false
    @Volatile private var senderContext: Context? = null

    /** 任务级的不可变身份, 每条事件都只能从这个对象取 taskId/goal/model/package。 */
    data class TraceContext(
        val taskId: String,
        val goal: String,
        val model: String,
        val packageName: String,
        val startedAt: Long,
        /** true 表示 Web 控制台下发的任务, 会同步任务状态 */
        val remote: Boolean
    )

    /** 一次 Agent run 的日志句柄。句柄本身不依赖 AgentTrace 的全局可变状态。 */
    class TraceRun internal constructor(
        val task: TraceContext,
        val enabled: Boolean
    ) {
        internal val finished = AtomicBoolean(false)
    }

    data class ToolTrace(
        val name: String,
        val arguments: String,
        val result: String,
        val elapsedMs: Long,
        val ok: Boolean = true
    )

    /** 任务开始: 记录目标、模型、当前 App、系统提示词。 */
    fun startTask(
        context: Context,
        goal: String,
        model: String,
        systemPrompt: String,
        packageName: String,
        traceTaskId: String = ""
    ): TraceRun {
        val remote = traceTaskId.isNotBlank()
        val task = TraceContext(
            taskId = traceTaskId.ifBlank {
                "local_" + System.currentTimeMillis().toString(36) +
                    UUID.randomUUID().toString().take(6)
            },
            goal = goal,
            model = model,
            packageName = packageName,
            startedAt = System.currentTimeMillis(),
            remote = remote
        )
        val enabled = ConsoleSettings.load(context).usable
        val run = TraceRun(task, enabled)
        if (!enabled) return run

        ensureSender(context)
        val payload = JSONObject()
            .put("summary", "任务开始 · ${goal.trim().take(40)}")
            .put("taskId", task.taskId)
            .put("goal", clip(goal, MAX_TEXT))
            .put("model", model)
            .put("package", packageName)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("systemPrompt", clip(systemPrompt, MAX_TEXT))
        enqueue(context, run, eventType(run, "start"), "info", payload)
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
            planned.put(JSONObject().put("name", name).put("arguments", clip(args, MAX_ARGS)))
        }
        val toolArr = JSONArray()
        tools.forEach { t ->
            toolArr.put(
                JSONObject()
                    .put("name", t.name)
                    .put("arguments", clip(t.arguments, MAX_ARGS))
                    .put("result", clip(t.result, MAX_TOOL_RESULT))
                    .put("elapsedMs", t.elapsedMs)
                    .put("ok", t.ok)
            )
        }
        val failed = tools.any { !it.ok }
        val toolName = tools.firstOrNull()?.name ?: "think"
        val payload = JSONObject()
            .put("summary", "Step $step · $toolName")
            .put("taskId", task.taskId)
            .put("step", step)
            .put("goal", clip(task.goal, MAX_TEXT))
            .put("model", task.model)
            .put("package", task.packageName)
            .put("llmMs", llmMs)
            .put("durationMs", System.currentTimeMillis() - task.startedAt)
            .put("context", JSONObject().put("chars", contextChars).put("messages", contextMessages))
            .put("input", JSONObject().put("lastMessage", clip(input, MAX_TEXT)))
            .put(
                "output",
                JSONObject()
                    .put("content", clip(output, MAX_TEXT))
                    .put("reasoning", clip(reasoning, MAX_REASONING))
                    .put("toolCalls", planned)
            )
            .put("tools", toolArr)
        enqueue(context, run, "agent.task.step", if (failed) "error" else "info", payload)
    }

    /** 任务结束 / 失败 / 用户停止。 */
    fun finish(context: Context, run: TraceRun, success: Boolean, result: String, steps: Int) {
        if (!run.enabled) return
        if (!run.finished.compareAndSet(false, true)) return
        val task = run.task

        if (task.remote) {
            // 控制台 STOP 已经显式上报 task.stopped, 这里不再重复写一条状态事件。
            if (ConsoleClient.consumeFinalStatus(task.taskId) != null) return
        }

        val stopped = !success && (result.contains("用户停止") || result == "已停止")
        val type = when {
            task.remote && stopped -> "task.stopped"
            task.remote && success -> "task.succeeded"
            task.remote -> "task.failed"
            stopped -> "agent.task.stopped"
            success -> "agent.task.succeeded"
            else -> "agent.task.failed"
        }
        val level = when {
            success -> "info"
            stopped -> "warn"
            else -> "error"
        }
        val title = when {
            success -> "任务完成"
            stopped -> "任务已停止"
            else -> "任务失败"
        }
        val payload = JSONObject()
            .put("summary", "$title · ${task.goal.trim().take(40)}")
            .put("taskId", task.taskId)
            .put("goal", clip(task.goal, MAX_TEXT))
            .put("success", success)
            .put("stopped", stopped)
            .put("result", clip(result, MAX_TEXT))
            .put("steps", steps)
            .put("durationMs", System.currentTimeMillis() - task.startedAt)
        enqueue(context, run, type, level, payload)
    }

    /** 重试/系统干预等旁路事件。 */
    fun event(context: Context, run: TraceRun, level: String, title: String, message: String) {
        if (!run.enabled) return
        val task = run.task
        val payload = JSONObject()
            .put("summary", title.take(200))
            .put("taskId", task.taskId)
            .put("goal", clip(task.goal, MAX_TEXT))
            .put("message", clip(message, MAX_TEXT))
            .put("package", task.packageName)
        enqueue(
            context,
            run,
            "agent.event",
            ConsoleClient.normalizeLevel(level),
            payload
        )
    }

    private fun eventType(run: TraceRun, stage: String): String = when (stage) {
        "start" -> if (run.task.remote) "task.started" else "agent.task.started"
        else -> "agent.event"
    }

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"

    private fun enqueue(
        context: Context,
        run: TraceRun,
        type: String,
        level: String,
        payload: JSONObject
    ) {
        if (!run.enabled) return
        val event = JSONObject()
            .put("id", "and_" + UUID.randomUUID().toString().replace("-", "").take(20))
            .put("taskId", run.task.taskId)
            .put("runId", run.task.taskId)
            .put("sequence", ConsoleClient.nextSequence())
            .put("type", type)
            .put("level", ConsoleClient.normalizeLevel(level))
            .put("payload", payload)
            .put("occurredAt", java.time.Instant.now().toString())
        if (!queue.trySend(event).isSuccess) {
            Log.w(TAG, "日志队列已满, 丢弃一条: $type")
        }
        ensureSender(context)
    }

    private fun ensureSender(context: Context) {
        senderContext = context.applicationContext
        if (senderStarted) return
        synchronized(this) {
            if (senderStarted) return
            senderStarted = true
            scope.launch {
                while (true) {
                    val first = queue.receiveCatching().getOrNull() ?: break
                    val batch = JSONArray().put(first)
                    while (batch.length() < BATCH_SIZE) {
                        val next = queue.tryReceive().getOrNull() ?: break
                        batch.put(next)
                    }
                    if (batch.length() > 0) {
                        sendBatch(senderContext ?: context.applicationContext, batch)
                    }
                }
            }
        }
    }

    private suspend fun sendBatch(context: Context, batch: JSONArray) {
        repeat(MAX_RETRY) { attempt ->
            if (ConsoleClient.uploadEvents(context, batch)) return
            if (attempt < MAX_RETRY - 1) delay(1_000L * (attempt + 1))
        }
        Log.w(TAG, "日志批量上报失败, 丢弃 ${batch.length()} 条")
    }
}
