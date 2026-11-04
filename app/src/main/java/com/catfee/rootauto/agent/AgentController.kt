package com.catfee.rootauto.agent

import android.content.Context
import com.catfee.rootauto.llm.LlmSettings
import com.catfee.rootauto.service.AgentStatusService
import com.catfee.rootauto.session.Attachment
import com.catfee.rootauto.session.AttachmentAnalyzer
import com.catfee.rootauto.session.ChatMessage
import com.catfee.rootauto.session.SessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * AgentController —— Agent 任务的唯一入口与状态中心
 *
 * 功能描述:
 *   把「启动/停止 Agent」从 Activity 里抽出来, 放到应用级单例:
 *     1. 用应用级 CoroutineScope 跑任务, Activity 退到后台/旋转屏幕都不会中断
 *     2. 用 StateFlow 暴露运行状态与步骤列表, 界面和通知栏各自订阅
 *     3. 启动时拉起前台服务, 保证后台执行不被系统回收
 *
 * 使用场景:
 *   MainActivity 的开始/停止按钮; AgentStatusService 读取状态更新通知。
 *
 * 修改时间: 2026-11-03
 */
object AgentController {

    /** 一条执行步骤 (思考/工具调用/工具结果/完成/失败) */
    data class Step(
        val seq: Int,
        val type: String,
        val text: String,
        val tool: String,
        val time: Long
    )

    /** 等待用户确认的设备操作 */
    data class PendingConfirm(
        val sessionId: String,
        val tool: String,
        val arguments: String
    )

    /** Agent 整体状态 */
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
        val pendingConfirm: PendingConfirm? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var seq = 0
    @Volatile private var activeSessionId = ""
    @Volatile private var appContextRef: Context? = null
    @Volatile private var confirmDeferred: CompletableDeferred<Boolean>? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 是否正在执行 */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * 功能: 给长时间运行的工具上报一条进度
     * 参数: type 事件类型; text 内容; tool 工具名
     * 使用场景: 长时间运行的工具需要让界面/通知栏实时看到当前进度
     */
    fun publishStep(type: String, text: String, tool: String) {
        appendStep(type, text, tool, System.currentTimeMillis())
    }

    /** 追加一条 Agent 事件 (LLM 心跳与主循环可能并发, 统一在这里串行分配 seq) */
    fun publishEvent(type: String, text: String, tool: String, time: Long) {
        appendStep(type, text, tool, time)
    }

    @Synchronized
    private fun appendStep(type: String, text: String, tool: String, time: Long) {
        seq++
        val step = Step(seq, type, text, tool, time)
        android.util.Log.i(
            "AgentStep",
            "#${step.seq} ${step.type}" +
                (if (step.tool.isNotBlank()) " ${step.tool}" else "") +
                ": ${step.text.take(300)}"
        )
        _state.update {
            it.copy(
                steps = it.steps + step,
                currentTool = if (tool.isNotBlank()) tool else it.currentTool
            )
        }
    }

    /**
     * 功能: 启动一次 Agent 任务
     * 参数: context; provider LLM 配置; goal 自然语言任务目标
     * 返回: 无
     * 使用场景: 主界面「开始执行」
     */
    fun start(
        context: Context,
        provider: LlmSettings.Provider,
        goal: String,
        sessionId: String = "",
        priorContext: String = "",
        phoneAllowed: Boolean = false,
        attachments: List<Attachment> = emptyList()
    ) {
        if (isRunning) return
        seq = 0
        activeSessionId = sessionId
        val startedAt = System.currentTimeMillis()
        _state.value = State(
            running = true,
            goal = goal,
            currentTool = "",
            sessionId = sessionId,
            streaming = true
        )
        if (sessionId.isNotBlank()) {
            SessionManager.setRunning(context.applicationContext, sessionId, true)
        }
        // 先拉起前台服务, 保证切到其他 App 后任务继续跑、通知栏能看到进度
        AgentStatusService.start(context)
        val appContext = context.applicationContext
        appContextRef = appContext
        job = scope.launch {
            var phoneGranted = phoneAllowed
            var runTokens = 0
            fun trace(type: String, text: String, tool: String) {
                publishStep(type, text, tool)
                if (sessionId.isNotBlank()) {
                    SessionManager.appendStep(
                        appContext, sessionId, type, text, tool, System.currentTimeMillis()
                    )
                }
            }
            var effectiveGoal = goal
            if (attachments.isNotEmpty()) {
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
                        SessionManager.setLastUserContext(
                            appContext, sessionId, attachmentContext
                        )
                    }
                    effectiveGoal = buildString {
                        append(goal)
                        append("\n\n以下是用户上传附件的本地解析内容, 请结合它回答/判断:\n")
                        append(attachmentContext)
                    }.take(30_000)
                }
            }
            val result = try {
                ReActAgent.run(
                    context = appContext,
                    provider = provider,
                    goal = effectiveGoal,
                    priorContext = priorContext,
                    onEvent = { ev ->
                        publishEvent(ev.type, ev.content, ev.tool, ev.timestamp)
                        // 纯 UI 心跳不写入会话历史, 否则历史里全是"等待模型响应"
                        val heartbeat = ev.type == "system" &&
                            ev.content.startsWith("Step ") &&
                            ev.content.contains("等待模型响应")
                        if (sessionId.isNotBlank() && !heartbeat) {
                            SessionManager.appendStep(
                                appContext, sessionId,
                                ev.type, ev.content, ev.tool, ev.timestamp
                            )
                        }
                    },
                    onUsage = { prompt, completion, total ->
                        runTokens += total
                        if (sessionId.isNotBlank()) {
                            SessionManager.addUsage(
                                appContext, sessionId, prompt, completion, total
                            )
                        }
                    },
                    onContext = { _, chars ->
                        if (sessionId.isNotBlank()) {
                            SessionManager.setContextChars(appContext, sessionId, chars)
                        }
                    },
                    onStreamContent = { piece ->
                        _state.update { it.copy(streamText = it.streamText + piece) }
                    },
                    onStreamReasoning = { piece ->
                        _state.update {
                            it.copy(streamReasoning = it.streamReasoning + piece)
                        }
                    },
                    ensurePhonePermission = { tool, args ->
                        if (phoneGranted) {
                            true
                        } else {
                            val deferred = CompletableDeferred<Boolean>()
                            confirmDeferred = deferred
                            _state.update {
                                it.copy(
                                    pendingConfirm = PendingConfirm(sessionId, tool, args)
                                )
                            }
                            val allowed = deferred.await()
                            confirmDeferred = null
                            _state.update { it.copy(pendingConfirm = null) }
                            if (allowed) {
                                phoneGranted = true
                                if (sessionId.isNotBlank()) {
                                    SessionManager.setPhoneAllowed(
                                        appContext, sessionId, true
                                    )
                                }
                            }
                            allowed
                        }
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                "已停止"
            } catch (e: Exception) {
                "任务异常: ${e.message}"
            }
            val reasoningText = _state.value.streamReasoning
            val elapsed = System.currentTimeMillis() - startedAt
            _state.update {
                it.copy(
                    running = false,
                    result = result,
                    currentTool = "",
                    streaming = false,
                    streamText = "",
                    streamReasoning = "",
                    pendingConfirm = null
                )
            }
            if (sessionId.isNotBlank()) {
                SessionManager.setResult(appContext, sessionId, result)
                SessionManager.addDuration(appContext, sessionId, elapsed)
                if (result.isNotBlank()) {
                    SessionManager.appendMessage(
                        appContext,
                        sessionId,
                        ChatMessage(
                            "assistant",
                            result,
                            tokens = runTokens,
                            elapsedMs = elapsed,
                            reasoning = reasoningText.take(12_000)
                        )
                    )
                }
            }
            activeSessionId = ""
            // 通知栏由 AgentStatusService 监听 running=false 后自行收尾
        }
    }

    /** 用户在聊天框内允许/拒绝设备操作 */
    fun answerConfirm(allow: Boolean) {
        val deferred = confirmDeferred
        confirmDeferred = null
        _state.update { it.copy(pendingConfirm = null) }
        deferred?.complete(allow)
    }

    /**
     * 功能: 停止当前任务
     * 使用场景: 主界面「停止」
     */
    fun stop() {
        val sid = activeSessionId
        val ctx = appContextRef
        confirmDeferred?.complete(false)
        confirmDeferred = null
        job?.cancel()
        job = null
        if (sid.isNotBlank() && ctx != null) {
            SessionManager.setRunning(ctx, sid, false)
            SessionManager.setResult(ctx, sid, "已停止")
        }
        _state.update {
            it.copy(
                running = false,
                currentTool = "",
                streaming = false,
                streamText = "",
                streamReasoning = "",
                pendingConfirm = null
            )
        }
    }
}
