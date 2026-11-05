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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** 已删除会话的墓碑: 阻止删除后才到达的异步更新把文件写回来 */
    private val deletedIds = ConcurrentHashMap.newKeySet<String>()
    private val fileLock = Mutex()

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

    /** 读取会话快照: 当前会话用内存, 其他从文件加载 */
    suspend fun snapshot(context: Context, id: String): Session? =
        _current.value?.takeIf { it.id == id } ?: SessionStore.load(context, id)

    suspend fun open(context: Context, id: String) {
        if (id in deletedIds) return
        flush(context)
        val loaded = SessionStore.load(context, id) ?: return
        _current.value = loaded.copy(running = false)
    }

    /** 修改会话标题; 当前会话立即更新 UI, 历史会话回写文件 */
    suspend fun rename(context: Context, id: String, rawTitle: String) {
        val title = rawTitle.trim().replace('\n', ' ').take(60)
        if (title.isBlank()) return
        mutate(context, id, touch = false) {
            it.copy(title = title, titleAuto = false)
        }
    }

    /** 置顶/收藏/归档; 不改变 updatedAt, 避免“操作一下会话跳到最前” */
    suspend fun setFlags(
        context: Context,
        id: String,
        pinned: Boolean? = null,
        archived: Boolean? = null,
        favorite: Boolean? = null
    ) {
        mutate(context, id, touch = false) {
            it.copy(
                pinned = pinned ?: it.pinned,
                archived = archived ?: it.archived,
                favorite = favorite ?: it.favorite
            )
        }
    }

    /** 本会话模型覆盖; 空串表示跟随全局默认 */
    suspend fun setModel(context: Context, id: String, model: String) {
        mutate(context, id, touch = false) { it.copy(model = model.trim()) }
    }

    suspend fun setSummary(context: Context, id: String, summary: String) {
        mutate(context, id, touch = false) { it.copy(summary = summary.trim().take(400)) }
    }

    /** 自动标题只在 titleAuto=true 时生效, 不覆盖手动改名 */
    suspend fun applyAutoTitle(context: Context, id: String, rawTitle: String) {
        val title = rawTitle.trim().replace('\n', ' ').take(24)
        if (title.isBlank()) return
        mutate(context, id, touch = false) {
            if (it.titleAuto) it.copy(title = title, titleAuto = false) else it
        }
    }

    /** 按消息 id 替换内容 (编辑用户消息) */
    suspend fun replaceMessage(
        context: Context,
        id: String,
        messageId: String,
        transform: (ChatMessage) -> ChatMessage
    ) {
        mutate(context, id, touch = true) { s ->
            s.copy(messages = s.messages.map { if (it.id == messageId) transform(it) else it })
        }
    }

    /**
     * 截断到指定消息 (保留该消息本身), 并清空 result/steps/context。
     * 用于编辑/重生成: 旧回复和旧步骤不会再注入上下文。
     */
    suspend fun truncateAfter(context: Context, id: String, messageId: String): Session? =
        mutate(context, id, touch = true) { s ->
            val idx = s.messages.indexOfFirst { it.id == messageId }
            if (idx < 0) s else s.copy(
                messages = s.messages.take(idx + 1),
                steps = emptyList(),
                result = "",
                contextChars = 0
            )
        }

    /** 从某条消息分支出新会话; 不复制 result/步骤/手机授权 */
    suspend fun fork(context: Context, sourceId: String, messageId: String): Session? {
        if (sourceId in deletedIds) return null
        val source = _current.value?.takeIf { it.id == sourceId }
            ?: SessionStore.load(context, sourceId)
            ?: return null
        val idx = source.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return null
        val messages = source.messages.take(idx + 1)
        val now = System.currentTimeMillis()
        val branch = Session(
            id = java.util.UUID.randomUUID().toString().replace("-", "").take(16),
            title = "分支 · ${source.title}".take(40),
            mode = source.mode,
            createdAt = now,
            updatedAt = now,
            messages = messages,
            promptTokens = 0,
            completionTokens = 0,
            totalTokens = messages.sumOf { it.tokens },
            contextChars = 0,
            totalDurationMs = messages.sumOf { it.elapsedMs },
            result = "",
            phoneAllowed = false,
            titleAuto = false,
            model = source.model
        )
        fileLock.withLock { SessionStore.save(context, branch) }
        _current.value = branch
        refreshHistory(context)
        return branch
    }

    /** 通用会话变更: 串行落盘、取消旧防抖、可选是否刷新 updatedAt */
    private suspend fun mutate(
        context: Context,
        id: String,
        touch: Boolean,
        block: (Session) -> Session
    ): Session? {
        if (id in deletedIds) return null
        saveJobs.remove(id)?.cancel()
        var result: Session? = null
        val cur = _current.value
        if (cur != null && cur.id == id) {
            val next = block(cur).let {
                if (touch) it.copy(updatedAt = System.currentTimeMillis()) else it
            }
            _current.value = next
            result = next
            fileLock.withLock {
                if (id !in deletedIds) SessionStore.save(context, next)
            }
        } else {
            fileLock.withLock {
                if (id in deletedIds) return@withLock
                val s = SessionStore.load(context, id) ?: return@withLock
                val next = block(s).let {
                    if (touch) it.copy(updatedAt = System.currentTimeMillis()) else it
                }
                SessionStore.save(context, next)
                result = next
            }
        }
        refreshHistory(context)
        return result
    }

    /** 删除会话; 如果删的是当前会话, 自动换成一个新的空会话 */
    suspend fun delete(context: Context, id: String) {
        deletedIds.add(id)
        saveJobs.remove(id)?.cancel()
        // 和保存串行, 避免已开始的异步保存把删除后的文件又写回来
        fileLock.withLock { SessionStore.delete(context, id) }
        if (_current.value?.id == id) {
            _current.value = SessionStore.newSession("chat")
        }
        refreshHistory(context)
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
        if (id in deletedIds) return
        val cur = _current.value
        if (cur != null && cur.id == id) {
            updateCurrent(context, block)
            return
        }
        scope.launch {
            fileLock.withLock {
                if (id in deletedIds) return@withLock
                val s = SessionStore.load(context, id) ?: return@withLock
                val next = block(s).copy(updatedAt = System.currentTimeMillis())
                SessionStore.save(context, next)
            }
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
        if (cur.isEmpty || cur.id in deletedIds) return
        saveJobs.remove(cur.id)?.cancel()
        scope.launch {
            fileLock.withLock {
                if (cur.id !in deletedIds) SessionStore.save(context, cur)
            }
        }
    }

    private fun scheduleSave(context: Context, session: Session) {
        saveJobs.remove(session.id)?.cancel()
        saveJobs[session.id] = scope.launch {
            delay(350)
            fileLock.withLock {
                if (session.id in deletedIds) return@withLock
                SessionStore.save(context, session)
            }
            refreshHistory(context)
        }
    }
}
