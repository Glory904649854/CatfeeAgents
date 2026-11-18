package com.catfee.rootauto.agent

import android.content.Context
import android.os.SystemClock
import com.catfee.rootauto.llm.LlmClient
import com.catfee.rootauto.llm.LlmSettings
import com.catfee.rootauto.root.ScreenGeometry
import com.catfee.rootauto.service.AgentStatusService
import com.catfee.rootauto.session.Attachment
import com.catfee.rootauto.session.AttachmentAnalyzer
import com.catfee.rootauto.session.ChatMessage
import com.catfee.rootauto.session.SessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * AgentController —— 每个会话独立运行态 + 全局唯一 Agent 槽位
 *
 * Chat 可以多会话并发; 设备操作同一时间只允许一个会话占用。
 */
object AgentController {

    data class Step(
        val seq: Int,
        val type: String,
        val text: String,
        val tool: String,
        val time: Long
    )

    data class PendingConfirm(
        val sessionId: String,
        val tool: String,
        val arguments: String
    )

    data class State(
        val running: Boolean = false,
        val goal: String = "",
        val steps: List<Step> = emptyList(),
        val currentTool: String = "",
        val result: String? = null,
        val sessionId: String = "",
        val streaming: Boolean = false,
        val streamText: String = "",
        val streamReasoning: String = "",
        val pendingConfirm: PendingConfirm? = null,
        val blockedByAgent: Boolean = false,
        val blockedReason: String = "",
        val phase: String = "idle",
        val stepNumber: Int = 0,
        val startedAtWall: Long = 0L,
        val startedAtElapsed: Long = 0L,
        val lastEventAtElapsed: Long = 0L,
        val phaseStartedAtElapsed: Long = 0L,
        val lastEventText: String = "",
        val terminalKind: String = "",
        val sessionTitle: String = "",
        val paused: Boolean = false
    )

    private class Run(
        val key: String,
        val sessionId: String,
        val context: Context,
        val state: MutableStateFlow<State>,
        val seq: AtomicInteger = AtomicInteger(0)
    ) {
        var job: Job? = null
        @Volatile var confirm: CompletableDeferred<Boolean>? = null
        @Volatile var pauseGate: CompletableDeferred<Unit>? = null
        @Volatile var paused: Boolean = false
        @Volatile var pausePhase: String = "waiting_llm"
        @Volatile var agentClaimed: Boolean = false
        @Volatile var conflictReason: String = ""
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runs = ConcurrentHashMap<String, Run>()

    private val agentSlot = Any()
    @Volatile private var agentOwnerKey: String? = null
    @Volatile private var appContextRef: Context? = null

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    /** 只给通知栏使用: 当前 Agent owner 的最终/运行态; chat 不占用 */
    private val _agentState = MutableStateFlow<State?>(null)
    val agentState: StateFlow<State?> = _agentState.asStateFlow()

    val isRunning: Boolean get() = runs.isNotEmpty()

    fun isSessionRunning(sessionId: String): Boolean =
        runs.containsKey(keyOf(sessionId))

    fun agentBusySessionId(): String? {
        val key = agentOwnerKey ?: return null
        return runs[key]?.sessionId
    }

    private fun keyOf(sessionId: String): String = sessionId.ifBlank { "__default__" }

    fun publishStep(type: String, text: String, tool: String) {
        val key = agentOwnerKey ?: return
        runs[key]?.let { appendStep(it, type, text, tool, System.currentTimeMillis()) }
    }

    fun publishEvent(type: String, text: String, tool: String, time: Long) {
        val key = agentOwnerKey ?: return
        runs[key]?.let { appendStep(it, type, text, tool, time) }
    }

    private fun appendStep(run: Run, type: String, text: String, tool: String, time: Long) {
        val seq = run.seq.incrementAndGet()
        val step = Step(seq, type, text, tool, time)
        android.util.Log.i(
            "AgentStep",
            "[${run.sessionId}] #$seq $type" +
                (if (tool.isNotBlank()) " $tool" else "") +
                ": ${text.take(300)}"
        )
        val now = SystemClock.elapsedRealtime()
        run.state.update {
            val nextPhase = when (type) {
                "tool" -> "operating"
                "think" -> "waiting_llm"
                "done" -> "done"
                "error" -> "error"
                else -> it.phase
            }
            it.copy(
                steps = it.steps + step,
                currentTool = if (tool.isNotBlank()) tool else it.currentTool,
                phase = if (run.paused) "paused" else nextPhase,
                paused = run.paused,
                phaseStartedAtElapsed = if (run.paused || nextPhase != it.phase) now else it.phaseStartedAtElapsed,
                lastEventAtElapsed = now,
                lastEventText = if (run.paused) "已暂停, 等待继续" else text.take(160)
            )
        }
        publishState(run)
    }

    /** ReActAgent 上报真实阶段; 不写入 steps, 只更新状态栏/悬浮窗 */
    private fun updatePhase(run: Run, phase: String, step: Int, detail: String) {
        val now = SystemClock.elapsedRealtime()
        run.state.update {
            if (run.paused) {
                it.copy(
                    paused = true,
                    lastEventAtElapsed = now,
                    lastEventText = "已暂停, 等待继续"
                )
            } else {
                it.copy(
                    paused = false,
                    phase = phase,
                    stepNumber = if (step > 0) step else it.stepNumber,
                    phaseStartedAtElapsed = if (phase != it.phase) now else it.phaseStartedAtElapsed,
                    lastEventAtElapsed = now,
                    lastEventText = detail.ifBlank { it.lastEventText }.take(160)
                )
            }
        }
        publishState(run)
    }

    private fun publishState(run: Run) {
        _states.update { it + (run.key to run.state.value) }
        if (run.agentClaimed) _agentState.value = run.state.value
    }

    /**
     * 启动一次会话运行。不同会话可以同时跑 chat; 同一会话只允许一个 run。
     * Agent 槽位在 ReActAgent 真正调用 enter_agent_mode 时才 claim。
     */
    fun start(
        context: Context,
        provider: LlmSettings.Provider,
        goal: String,
        sessionId: String = "",
        priorContext: String = "",
        phoneAllowed: Boolean = false,
        attachments: List<Attachment> = emptyList(),
        /** Web 控制台任务 ID; 非空时日志直接挂到该任务时间线 */
        traceTaskId: String = "",
        /** Web 任务策略: 是否允许使用设备 Root 增强工具 */
        allowRootTools: Boolean = true,
        /** Web 任务策略: 是否允许截图/视觉工具 */
        allowScreenshot: Boolean = true,
        /** Web 任务策略: 超时毫秒; <0 不限制, 0 使用 Agent 默认预算 */
        timeoutMs: Long = 0
    ): Boolean {
        val key = keyOf(sessionId)
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val state = MutableStateFlow(
            State(
                running = true,
                goal = goal,
                currentTool = "",
                sessionId = sessionId,
                streaming = true,
                phase = "waiting_llm",
                stepNumber = 0,
                startedAtWall = nowWall,
                startedAtElapsed = nowElapsed,
                lastEventAtElapsed = nowElapsed,
                phaseStartedAtElapsed = nowElapsed,
                lastEventText = "准备开始",
                sessionTitle = goal.replace('\n', ' ').take(36)
            )
        )
        val run = Run(key, sessionId, context.applicationContext, state)
        if (runs.putIfAbsent(key, run) != null) return false

        val appContext = context.applicationContext
        appContextRef = appContext
        ScreenGeometry.init(appContext)
        // 新运行开始时清掉上一轮 Agent 的终态, 避免状态服务误显示旧结果
        if (agentOwnerKey == null) _agentState.value = null
        publishState(run)
        if (sessionId.isNotBlank()) {
            SessionManager.setRunning(appContext, sessionId, true)
        }
        run.job = scope.launch {
            executeRun(
                run, provider, goal, priorContext, phoneAllowed, attachments,
                traceTaskId, allowRootTools, allowScreenshot, timeoutMs
            )
        }
        return true
    }

    private suspend fun executeRun(
        run: Run,
        provider: LlmSettings.Provider,
        goal: String,
        priorContext: String,
        phoneAllowed: Boolean,
        attachments: List<Attachment>,
        traceTaskId: String,
        allowRootTools: Boolean,
        allowScreenshot: Boolean,
        timeoutMs: Long
    ) {
        val appContext = run.context
        val sessionId = run.sessionId
        val startedAt = System.currentTimeMillis()
        var phoneGranted = phoneAllowed
        var runTokens = 0
        var result = ""

        fun trace(type: String, text: String, tool: String) {
            appendStep(run, type, text, tool, System.currentTimeMillis())
            if (sessionId.isNotBlank()) {
                SessionManager.appendStep(
                    appContext, sessionId, type, text, tool, System.currentTimeMillis()
                )
            }
        }

        try {
            awaitIfPaused(sessionId)
            var effectiveGoal = goal
            if (attachments.isNotEmpty()) {
                awaitIfPaused(sessionId)
                trace("tool", "开始解析 ${attachments.size} 个附件", "analyze_resources")
                val attachmentContext = try {
                    AttachmentAnalyzer.analyzeAll(appContext, attachments) { progress ->
                        trace("tool", progress, "analyze_resources")
                    }
                } catch (e: Exception) {
                    "附件解析失败: ${e.message}"
                }
                trace(
                    "result",
                    "附件解析完成: ${attachmentContext.length} 字符",
                    "analyze_resources"
                )
                if (attachmentContext.isNotBlank()) {
                    if (sessionId.isNotBlank()) {
                        SessionManager.setLastUserContext(appContext, sessionId, attachmentContext)
                    }
                    effectiveGoal = buildString {
                        append(goal)
                        append("\n\n以下是用户上传附件的本地解析内容, 请结合它回答/判断:\n")
                        append(attachmentContext)
                    }.take(30_000)
                }
            }

            result = try {
                ReActAgent.run(
                    context = appContext,
                    provider = provider,
                    goal = effectiveGoal,
                    priorContext = priorContext,
                    onEvent = { ev ->
                        if (ev.type == "conflict") run.conflictReason = ev.content
                        appendStep(run, ev.type, ev.content, ev.tool, ev.timestamp)
                        val heartbeat = ev.type == "system" &&
                            ev.content.startsWith("Step ") &&
                            ev.content.contains("等待模型响应")
                        if (sessionId.isNotBlank() && !heartbeat) {
                            SessionManager.appendStep(
                                appContext, sessionId, ev.type, ev.content, ev.tool, ev.timestamp
                            )
                        }
                    },
                    onUsage = { prompt, completion, total ->
                        runTokens += total
                        if (sessionId.isNotBlank()) {
                            SessionManager.addUsage(appContext, sessionId, prompt, completion, total)
                        }
                    },
                    onContext = { _, chars ->
                        if (sessionId.isNotBlank()) {
                            SessionManager.setContextChars(appContext, sessionId, chars)
                        }
                    },
                    onStreamContent = { piece ->
                        run.state.update { it.copy(streamText = it.streamText + piece) }
                        publishState(run)
                    },
                    onStreamReasoning = { piece ->
                        run.state.update { it.copy(streamReasoning = it.streamReasoning + piece) }
                        publishState(run)
                    },
                    ensurePhonePermission = { tool, args ->
                        if (phoneGranted) {
                            true
                        } else {
                            val deferred = CompletableDeferred<Boolean>()
                            run.confirm = deferred
                            val waitStart = SystemClock.elapsedRealtime()
                            run.state.update {
                                it.copy(
                                    pendingConfirm = PendingConfirm(sessionId, tool, args),
                                    phase = "waiting_confirm",
                                    phaseStartedAtElapsed = waitStart,
                                    lastEventAtElapsed = waitStart,
                                    lastEventText = "等待用户允许控制手机"
                                )
                            }
                            publishState(run)
                            val allowed = deferred.await()
                            run.confirm = null
                            val resumed = SystemClock.elapsedRealtime()
                            run.state.update {
                                it.copy(
                                    pendingConfirm = null,
                                    phase = if (allowed) "waiting_llm" else "error",
                                    phaseStartedAtElapsed = resumed,
                                    lastEventAtElapsed = resumed,
                                    lastEventText = if (allowed) "用户已允许控制手机" else "用户拒绝控制手机"
                                )
                            }
                            publishState(run)
                            if (allowed) {
                                phoneGranted = true
                                if (sessionId.isNotBlank()) {
                                    SessionManager.setPhoneAllowed(appContext, sessionId, true)
                                }
                            }
                            allowed
                        }
                    },
                    claimAgentMode = { claimAgentMode(run) },
                    onPhase = { phase, step, detail ->
                        updatePhase(run, phase, step, detail)
                    },
                    awaitIfPaused = { awaitIfPaused(sessionId) },
                    traceTaskId = traceTaskId,
                    allowRootTools = allowRootTools,
                    allowScreenshot = allowScreenshot,
                    timeoutMs = timeoutMs
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                "已停止"
            } catch (e: Exception) {
                "任务异常: ${e.message}"
            }

            val conflict = run.conflictReason.isNotBlank()
            val finalResult = if (conflict && !result.startsWith("暂未进入手机操作模式")) {
                "暂未进入手机操作模式：另一个会话的 Agent 正在操作手机。\n\n$result"
            } else {
                result
            }
            val reasoningText = run.state.value.streamReasoning
            val elapsed = System.currentTimeMillis() - startedAt
            val terminal = when {
                conflict -> "blocked"
                finalResult == "已停止" -> "stopped"
                finalResult.startsWith("任务异常") ||
                    finalResult.startsWith("LLM 请求失败") ||
                    finalResult.startsWith("超时") ||
                    finalResult.startsWith("模型未返回") ||
                    finalResult.startsWith("配置不完整") -> "error"
                else -> "done"
            }
            val endElapsed = SystemClock.elapsedRealtime()
            run.state.update {
                it.copy(
                    running = false,
                    result = finalResult,
                    currentTool = "",
                    streaming = false,
                    streamText = "",
                    streamReasoning = "",
                    pendingConfirm = null,
                    blockedByAgent = conflict,
                    blockedReason = run.conflictReason,
                    phase = terminal,
                    terminalKind = terminal,
                    paused = false,
                    lastEventAtElapsed = endElapsed
                )
            }
            publishState(run)

            if (sessionId.isNotBlank()) {
                SessionManager.setResult(appContext, sessionId, finalResult)
                SessionManager.addDuration(appContext, sessionId, elapsed)
                if (finalResult.isNotBlank()) {
                    val anchorId = if (conflict) {
                        SessionManager.snapshot(appContext, sessionId)
                            ?.messages?.lastOrNull { it.role == "user" }?.id.orEmpty()
                    } else {
                        ""
                    }
                    SessionManager.appendMessage(
                        appContext,
                        sessionId,
                        ChatMessage(
                            "assistant",
                            finalResult,
                            tokens = runTokens,
                            elapsedMs = elapsed,
                            reasoning = reasoningText.take(12_000),
                            retryAnchorId = anchorId
                        )
                    )
                }
                if (finalResult.isNotBlank() &&
                    finalResult != "已停止" &&
                    !finalResult.startsWith("任务异常") &&
                    !finalResult.startsWith("用户未允许") &&
                    !finalResult.startsWith("配置不完整")
                ) {
                    scope.launch {
                        generateTitleAndSummary(appContext, provider, sessionId, goal, finalResult)
                    }
                }
            }
        } finally {
            run.confirm?.complete(false)
            run.confirm = null
            runs.remove(run.key)
            _states.update { it - run.key }
            if (sessionId.isNotBlank()) {
                SessionManager.setRunning(appContext, sessionId, false)
            }
            if (run.agentClaimed) {
                _agentState.value = run.state.value
                synchronized(agentSlot) {
                    if (agentOwnerKey == run.key) agentOwnerKey = null
                }
            }
        }
    }

    /**
     * 尝试占用全局 Agent 槽位。返回 null 表示成功, 否则为冲突原因。
     * 必须在申请手机权限之前调用。
     */
    private fun claimAgentMode(run: Run): String? {
        synchronized(agentSlot) {
            val owner = agentOwnerKey
            if (owner != null && owner != run.key) {
                return "另一个会话的 Agent 正在操作手机, 当前会话暂时不能进入手机操作模式。"
            }
            agentOwnerKey = run.key
            run.agentClaimed = true
        }
        _agentState.value = run.state.value
        appContextRef?.let { ctx ->
            try {
                AgentStatusService.start(ctx)
            } catch (e: Exception) {
                android.util.Log.w("AgentController", "启动通知服务失败: ${e.message}")
            }
        }
        return null
    }

    private fun releaseAgentMode(key: String) {
        synchronized(agentSlot) {
            if (agentOwnerKey == key) agentOwnerKey = null
        }
    }

    /** 任务结束后异步生成会话标题/摘要; 不阻塞任务完成 */
    private suspend fun generateTitleAndSummary(
        context: Context,
        provider: LlmSettings.Provider,
        sessionId: String,
        goal: String,
        result: String
    ) {
        if (!provider.usable) return
        val session = SessionManager.snapshot(context, sessionId) ?: return
        val firstUser = session.messages.firstOrNull { it.role == "user" }
            ?.content?.take(600) ?: goal.take(600)
        try {
            if (session.titleAuto) {
                val prompt = "根据下面这轮对话生成一个 4~12 个汉字的中文标题。\n" +
                    "只输出标题本身, 不要引号、句号、解释或换行。\n" +
                    "用户: $firstUser\n助手: ${result.take(600)}"
                val r = withTimeoutOrNull(15_000) {
                    LlmClient.chat(
                        provider,
                        JSONArray()
                            .put(JSONObject().put("role", "system")
                                .put("content", "你是简洁的会话标题生成器。"))
                            .put(JSONObject().put("role", "user").put("content", prompt))
                    )
                }
                val title = r?.content?.lineSequence()?.firstOrNull()
                    ?.trim()?.trim('"', '“', '”', '。', '.', '\n', '\r')?.take(24).orEmpty()
                if (title.isNotBlank()) {
                    SessionManager.applyAutoTitle(context, sessionId, title)
                }
            }
            if (session.summary.isBlank() && session.messages.size >= 6) {
                val transcript = session.messages.takeLast(12).joinToString("\n") { m ->
                    (if (m.role == "user") "用户: " else "助手: ") +
                        m.content.replace('\n', ' ').take(500)
                }
                val prompt = "用不超过 300 字总结这段对话, 保留任务目标、已完成事项、关键结论或结果; " +
                    "不要编造。\n$transcript"
                val r = withTimeoutOrNull(20_000) {
                    LlmClient.chat(
                        provider,
                        JSONArray()
                            .put(JSONObject().put("role", "system")
                                .put("content", "你是会话摘要器, 只输出摘要正文。"))
                            .put(JSONObject().put("role", "user").put("content", prompt))
                    )
                }
                val summary = r?.content?.trim()?.take(400).orEmpty()
                if (summary.isNotBlank()) {
                    SessionManager.setSummary(context, sessionId, summary)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("AgentController", "生成标题/摘要失败: ${e.message}")
        }
    }

    /** 用户在聊天框内允许/拒绝某个会话的设备操作 */
    fun answerConfirm(sessionId: String, allow: Boolean) {
        val run = runs[keyOf(sessionId)] ?: return
        val deferred = run.confirm
        run.confirm = null
        run.state.update { it.copy(pendingConfirm = null) }
        publishState(run)
        deferred?.complete(allow)
    }

    /** 停止指定会话; 不传 sessionId 时停止全部 */
    fun stop(sessionId: String = "") {
        val targets = if (sessionId.isBlank()) {
            runs.values.toList()
        } else {
            listOfNotNull(runs[keyOf(sessionId)])
        }
        targets.forEach { stopRun(it) }
    }

    /** 暂停指定会话; 不取消 Job, 也不释放 Agent owner。 */
    fun pause(sessionId: String = ""): Boolean {
        val run = runs[keyOf(sessionId)] ?: return false
        synchronized(run) {
            if (!run.state.value.running || run.paused) return false
            run.paused = true
            run.pausePhase = run.state.value.phase
            run.pauseGate = CompletableDeferred()
        }
        val now = SystemClock.elapsedRealtime()
        run.state.update {
            it.copy(
                paused = true,
                phase = "paused",
                phaseStartedAtElapsed = now,
                lastEventAtElapsed = now,
                lastEventText = "已暂停, 等待继续"
            )
        }
        publishState(run)
        return true
    }

    /** 继续指定会话; 不创建新 Job。 */
    fun resume(sessionId: String = ""): Boolean {
        val run = runs[keyOf(sessionId)] ?: return false
        val gate: CompletableDeferred<Unit>?
        val phase: String
        synchronized(run) {
            if (!run.state.value.running || !run.paused) return false
            run.paused = false
            phase = run.pausePhase.ifBlank { "waiting_llm" }
            gate = run.pauseGate
            run.pauseGate = null
        }
        val now = SystemClock.elapsedRealtime()
        run.state.update {
            it.copy(
                paused = false,
                phase = phase,
                phaseStartedAtElapsed = now,
                lastEventAtElapsed = now,
                lastEventText = "已继续"
            )
        }
        publishState(run)
        gate?.complete(Unit)
        return true
    }

    /** Agent owner 专用暂停入口; sessionId 必须匹配当前 owner。 */
    fun pauseAgent(sessionId: String): Boolean {
        val key = agentOwnerKey ?: return false
        if (key != keyOf(sessionId)) return false
        return pause(sessionId)
    }

    /** Agent owner 专用继续入口; sessionId 必须匹配当前 owner。 */
    fun resumeAgent(sessionId: String): Boolean {
        val key = agentOwnerKey ?: return false
        if (key != keyOf(sessionId)) return false
        return resume(sessionId)
    }

    /** 挂起点; 暂停时等待继续, 不取消当前 Job。 */
    suspend fun awaitIfPaused(sessionId: String = "") {
        val run = runs[keyOf(sessionId)] ?: return
        while (true) {
            val gate = synchronized(run) {
                if (!run.paused || !run.state.value.running) null else run.pauseGate
            } ?: return
            try {
                gate.await()
            } catch (_: CancellationException) {
                throw CancellationException("任务已取消")
            }
        }
    }

    /** 通知栏/悬浮窗停止按钮: 只停 Agent owner, 不影响其他 chat。 */
    fun stopAgent(sessionId: String = "") {
        val key = agentOwnerKey ?: return
        if (sessionId.isNotBlank() && key != keyOf(sessionId)) return
        runs[key]?.let { stopRun(it) }
    }

    private fun stopRun(run: Run) {
        run.confirm?.complete(false)
        run.confirm = null
        synchronized(run) {
            run.paused = false
            run.pauseGate?.complete(Unit)
            run.pauseGate = null
        }
        run.job?.cancel()
        val now = SystemClock.elapsedRealtime()
        run.state.update {
            it.copy(
                running = false,
                currentTool = "",
                streaming = false,
                streamText = "",
                streamReasoning = "",
                pendingConfirm = null,
                phase = "stopped",
                terminalKind = "stopped",
                paused = false,
                lastEventAtElapsed = now,
                lastEventText = "用户已停止"
            )
        }
        publishState(run)
        if (run.sessionId.isNotBlank()) {
            SessionManager.setRunning(run.context, run.sessionId, false)
            SessionManager.setResult(run.context, run.sessionId, "已停止")
        }
        if (run.agentClaimed) {
            _agentState.value = run.state.value
            releaseAgentMode(run.key)
        }
    }
}
