package com.catfee.rootauto.session

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话状态中心:
 *   - 当前会话 (StateFlow) 给 Compose 渲染
 *   - 历史摘要列表
 *   - Agent / Chat 都通过这里追加消息、步骤、token、上下文长度
 *   - 文件写入 350ms 防抖, 避免每一步都同步落盘
 */
object SessionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveJobs = ConcurrentHashMap<String, Job>()

    private val _current = MutableStateFlow<Session?>(null)
    val current: StateFlow<Session?> = _current.asStateFlow()

    private val _summaries = MutableStateFlow<List<SessionSummary>>(emptyList())
    val summaries: StateFlow<List<SessionSummary>> = _summaries.asStateFlow()

    fun newSession(mode: String): Session {
        val s = SessionStore.newSession(mode)
        _current.value = s
        return s
    }

    suspend fun refreshHistory(context: Context) {
        _summaries.value = SessionStore.list(context)
    }

    suspend fun open(context: Context, id: String) {
        flush(context)
        val loaded = SessionStore.load(context, id) ?: return
        _current.value = loaded.copy(running = false)
    }

    /** 首次进入 App: 新建一个空会话 (不落盘, 发第一条消息才出现在历史里) */
    suspend fun ensureSession(context: Context, mode: String): Session {
        _current.value?.let { return it }
        refreshHistory(context)
        return newSession(mode)
    }

    @Synchronized
    fun updateCurrent(context: Context, block: (Session) -> Session) {
        val cur = _current.value ?: return
        val next = block(cur).copy(updatedAt = System.currentTimeMillis())
        _current.value = next
        if (!next.isEmpty) scheduleSave(context, next)
    }

    /** 当前会话直接更新; 如果已经切走, 仍把结果写回原会话文件 */
    fun updateById(context: Context, id: String, block: (Session) -> Session) {
        val cur = _current.value
        if (cur != null && cur.id == id) {
            updateCurrent(context, block)
            return
        }
        scope.launch {
            val s = SessionStore.load(context, id) ?: return@launch
            val next = block(s).copy(updatedAt = System.currentTimeMillis())
            SessionStore.save(context, next)
            refreshHistory(context)
        }
    }

    fun appendMessage(context: Context, sessionId: String, message: ChatMessage) {
        updateById(context, sessionId) { s ->
            val title = if (s.messages.isEmpty() && message.role == "user") {
                message.content.trim().replace('\n', ' ').take(32)
                    .ifBlank { s.title }
            } else {
                s.title
            }
            s.copy(title = title, messages = s.messages + message)
        }
    }

    fun appendStep(
        context: Context,
        sessionId: String,
        type: String,
        text: String,
        tool: String,
        time: Long,
        contextChars: Int = 0
    ) {
        updateById(context, sessionId) { s ->
            val step = StepRecord(
                seq = s.steps.size + 1,
                type = type,
                text = text,
                tool = tool,
                time = time,
                contextChars = contextChars
            )
            val steps = (s.steps + step).takeLast(2000)
            s.copy(steps = steps)
        }
    }

    fun addUsage(context: Context, sessionId: String, prompt: Int, completion: Int, total: Int) {
        if (prompt <= 0 && completion <= 0 && total <= 0) return
        updateById(context, sessionId) { s ->
            s.copy(
                promptTokens = s.promptTokens + prompt,
                completionTokens = s.completionTokens + completion,
                totalTokens = s.totalTokens + total
            )
        }
    }

    fun addDuration(context: Context, sessionId: String, elapsedMs: Long) {
        if (elapsedMs <= 0) return
        updateById(context, sessionId) { s ->
            s.copy(totalDurationMs = s.totalDurationMs + elapsedMs)
        }
    }

    /** 附件解析完成后, 把解析文本补到最近一条 user 消息的隐藏上下文里 */
    fun setLastUserContext(context: Context, sessionId: String, contextText: String) {
        if (contextText.isBlank()) return
        updateById(context, sessionId) { s ->
            val msgs = s.messages.toMutableList()
            val idx = msgs.indexOfLast { it.role == "user" }
            if (idx >= 0) {
                msgs[idx] = msgs[idx].copy(context = contextText)
            }
            s.copy(messages = msgs)
        }
    }

    fun setContextChars(context: Context, sessionId: String, chars: Int) {
        if (chars <= 0) return
        updateById(context, sessionId) { it.copy(contextChars = chars) }
    }

    fun setRunning(context: Context, sessionId: String, running: Boolean) {
        updateById(context, sessionId) { it.copy(running = running) }
    }

    fun setPhoneAllowed(context: Context, sessionId: String, allowed: Boolean) {
        updateById(context, sessionId) { it.copy(phoneAllowed = allowed) }
    }

    fun setResult(context: Context, sessionId: String, result: String) {
        updateById(context, sessionId) { it.copy(result = result, running = false) }
    }

    /** 切换会话/退出前把当前会话立即落盘 */
    fun flush(context: Context) {
        val cur = _current.value ?: return
        if (cur.isEmpty) return
        saveJobs.remove(cur.id)?.cancel()
        scope.launch { SessionStore.save(context, cur) }
    }

    private fun scheduleSave(context: Context, session: Session) {
        saveJobs.remove(session.id)?.cancel()
        saveJobs[session.id] = scope.launch {
            delay(350)
            SessionStore.save(context, session)
            refreshHistory(context)
        }
    }
}
