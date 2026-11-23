package com.catfee.rootauto.session

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 会话附件 (图片/文件), 文件本体在 files/attachments, 这里只存元数据 */
data class Attachment(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val path: String,
    val kind: String,
    val preview: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("mime", mime)
        .put("size", size)
        .put("path", path)
        .put("kind", kind)
        .put("preview", preview)

    companion object {
        fun fromJson(o: JSONObject): Attachment = Attachment(
            id = o.optString("id"),
            name = o.optString("name"),
            mime = o.optString("mime"),
            size = o.optLong("size", 0L),
            path = o.optString("path"),
            kind = o.optString("kind", "file"),
            preview = o.optString("preview", "")
        )
    }
}

/** 一条聊天消息 (统一 Chat/Agent) */
data class ChatMessage(
    val role: String,
    val content: String,
    val time: Long = System.currentTimeMillis(),
    val tokens: Int = 0,
    /** 本轮交互耗时 (含附件解析/工具调用), 毫秒 */
    val elapsedMs: Long = 0,
    /** 模型思维链, 用于聊天里的"思考过程"折叠展示 */
    val reasoning: String = "",
    /** 附件解析/OCR 后的隐藏上下文, 不直接展示在气泡里 */
    val context: String = "",
    val attachments: List<Attachment> = emptyList(),
    /** 稳定消息 ID, 用于 UI key、引用、编辑和分支 */
    val id: String = newId(),
    /** 引用回复的原文快照 */
    val quotedText: String = "",
    /** 因 Agent 被占用而失败时的用户消息 ID, 用于重试 */
    val retryAnchorId: String = ""
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        attachments.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("role", role)
            .put("content", content)
            .put("time", time)
            .put("tokens", tokens)
            .put("elapsed_ms", elapsedMs)
            .put("reasoning", reasoning)
            .put("context", context)
            .put("quoted_text", quotedText)
            .put("retry_anchor", retryAnchorId)
            .put("attachments", arr)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

        fun fromJson(o: JSONObject): ChatMessage {
            val list = ArrayList<Attachment>()
            o.optJSONArray("attachments")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(Attachment.fromJson(it)) }
                }
            }
            return ChatMessage(
                id = o.optString("id").ifBlank { newId() },
                role = o.optString("role", "assistant"),
                content = o.optString("content", ""),
                time = o.optLong("time", 0L),
                tokens = o.optInt("tokens", 0),
                elapsedMs = o.optLong("elapsed_ms", 0L),
                reasoning = o.optString("reasoning", ""),
                context = o.optString("context", ""),
                quotedText = o.optString("quoted_text", ""),
                attachments = list,
                retryAnchorId = o.optString("retry_anchor", "")
            )
        }
    }
}

/** Agent 时间线的一步 (持久化用, 与 AgentController.Step 解耦) */
data class StepRecord(
    val seq: Int,
    val type: String,
    val text: String,
    val tool: String,
    val time: Long,
    val contextChars: Int = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("seq", seq)
        .put("type", type)
        .put("text", text)
        .put("tool", tool)
        .put("time", time)
        .put("context_chars", contextChars)

    companion object {
        fun fromJson(o: JSONObject): StepRecord = StepRecord(
            seq = o.optInt("seq", 0),
            type = o.optString("type", ""),
            text = o.optString("text", ""),
            tool = o.optString("tool", ""),
            time = o.optLong("time", 0L),
            contextChars = o.optInt("context_chars", 0)
        )
    }
}

/**
 * 一个会话: chat 模式是聊天消息, agent 模式是任务时间线。
 * 两种模式共用 token / 上下文统计, 历史会话可直接继续。
 */
data class Session(
    val id: String,
    val title: String,
    val mode: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage> = emptyList(),
    val steps: List<StepRecord> = emptyList(),
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val contextChars: Int = 0,
    val totalDurationMs: Long = 0,
    val result: String = "",
    val running: Boolean = false,
    /** 本会话是否已允许 Agent 操作/读取手机 (确认一次后不再询问) */
    val phoneAllowed: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val favorite: Boolean = false,
    val summary: String = "",
    /** true 时允许自动标题覆盖; 手动改名后置 false */
    val titleAuto: Boolean = false,
    /** 本会话模型覆盖; 空=使用全局默认 */
    val model: String = "",
    /** 来源: local=手机本地创建, web=Web 控制台下发的远程任务 */
    val source: String = "local"
) {
    val isEmpty: Boolean get() = messages.isEmpty() && steps.isEmpty() && result.isBlank()

    fun toJson(): JSONObject {
        val msgArr = JSONArray()
        messages.forEach { msgArr.put(it.toJson()) }
        val stepArr = JSONArray()
        steps.forEach { stepArr.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("mode", mode)
            .put("created_at", createdAt)
            .put("updated_at", updatedAt)
            .put("messages", msgArr)
            .put("steps", stepArr)
            .put("prompt_tokens", promptTokens)
            .put("completion_tokens", completionTokens)
            .put("total_tokens", totalTokens)
            .put("context_chars", contextChars)
            .put("total_duration_ms", totalDurationMs)
            .put("result", result)
            .put("running", running)
            .put("phone_allowed", phoneAllowed)
            .put("pinned", pinned)
            .put("archived", archived)
            .put("favorite", favorite)
            .put("summary", summary)
            .put("title_auto", titleAuto)
            .put("model", model)
            .put("source", source)
    }

    fun toSummary(): SessionSummary = SessionSummary(
        id = id,
        title = title,
        mode = mode,
        updatedAt = updatedAt,
        totalTokens = totalTokens,
        contextChars = contextChars,
        totalDurationMs = totalDurationMs,
        messageCount = messages.size,
        stepCount = steps.size,
        resultPreview = (summary.ifBlank { result }).take(80),
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        pinned = pinned,
        archived = archived,
        favorite = favorite,
        summary = summary,
        model = model,
        source = source
    )

    companion object {
        fun fromJson(text: String): Session? {
            return try {
                val o = JSONObject(text)
                val messages = ArrayList<ChatMessage>()
                o.optJSONArray("messages")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { messages.add(ChatMessage.fromJson(it)) }
                    }
                }
                val steps = ArrayList<StepRecord>()
                o.optJSONArray("steps")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { steps.add(StepRecord.fromJson(it)) }
                    }
                }
                Session(
                    id = o.optString("id"),
                    title = o.optString("title", "Session"),
                    mode = o.optString("mode", "chat"),
                    createdAt = o.optLong("created_at", 0L),
                    updatedAt = o.optLong("updated_at", 0L),
                    messages = messages,
                    steps = steps,
                    promptTokens = o.optInt("prompt_tokens", 0),
                    completionTokens = o.optInt("completion_tokens", 0),
                    totalTokens = o.optInt("total_tokens", 0),
                    contextChars = o.optInt("context_chars", 0),
                    totalDurationMs = o.optLong("total_duration_ms", 0L),
                    result = o.optString("result", ""),
                    running = false,
                    phoneAllowed = o.optBoolean("phone_allowed", false),
                    pinned = o.optBoolean("pinned", false),
                    archived = o.optBoolean("archived", false),
                    favorite = o.optBoolean("favorite", false),
                    summary = o.optString("summary", ""),
                    titleAuto = o.optBoolean("title_auto", false),
                    model = o.optString("model", ""),
                    source = o.optString("source", "local")
                )
            } catch (e: Exception) {
                Log.w("Session", "parse session failed: ${e.message}")
                null
            }
        }
    }
}

/** 历史列表用的轻量摘要 */
data class SessionSummary(
    val id: String,
    val title: String,
    val mode: String,
    val updatedAt: Long,
    val totalTokens: Int,
    val contextChars: Int,
    val totalDurationMs: Long,
    val messageCount: Int,
    val stepCount: Int,
    val resultPreview: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val favorite: Boolean = false,
    val summary: String = "",
    val model: String = "",
    val source: String = "local"
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("mode", mode)
        .put("updated_at", updatedAt)
        .put("total_tokens", totalTokens)
        .put("context_chars", contextChars)
        .put("total_duration_ms", totalDurationMs)
        .put("message_count", messageCount)
        .put("step_count", stepCount)
        .put("result_preview", resultPreview)
        .put("prompt_tokens", promptTokens)
        .put("completion_tokens", completionTokens)
        .put("pinned", pinned)
        .put("archived", archived)
        .put("favorite", favorite)
        .put("summary", summary)
        .put("model", model)
        .put("source", source)

    companion object {
        fun fromJson(o: JSONObject): SessionSummary = SessionSummary(
            id = o.optString("id"),
            title = o.optString("title", "Session"),
            mode = o.optString("mode", "chat"),
            updatedAt = o.optLong("updated_at", 0L),
            totalTokens = o.optInt("total_tokens", 0),
            contextChars = o.optInt("context_chars", 0),
            totalDurationMs = o.optLong("total_duration_ms", 0L),
            messageCount = o.optInt("message_count", 0),
            stepCount = o.optInt("step_count", 0),
            resultPreview = o.optString("result_preview", ""),
            promptTokens = o.optInt("prompt_tokens", 0),
            completionTokens = o.optInt("completion_tokens", 0),
            pinned = o.optBoolean("pinned", false),
            archived = o.optBoolean("archived", false),
            favorite = o.optBoolean("favorite", false),
            summary = o.optString("summary", ""),
            model = o.optString("model", ""),
            source = o.optString("source", "local")
        )
    }
}

/**
 * 会话持久化: sessions/<id>.json 全量, sessions/<id>.meta 列表摘要。
 * 历史列表只读 meta, 不解析大文件。
 */
object SessionStore {

    private const val TAG = "SessionStore"
    private const val DIR = "sessions"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun sessionFile(context: Context, id: String): File =
        File(dir(context), "$id.json")

    private fun metaFile(context: Context, id: String): File =
        File(dir(context), "$id.meta")

    fun newSession(mode: String): Session {
        val now = System.currentTimeMillis()
        return Session(
            id = UUID.randomUUID().toString().replace("-", "").take(16),
            title = if (mode == "agent") "新任务" else "新会话",
            mode = mode,
            createdAt = now,
            updatedAt = now,
            titleAuto = true
        )
    }

    suspend fun list(context: Context): List<SessionSummary> = withContext(Dispatchers.IO) {
        try {
            dir(context).listFiles { f -> f.name.endsWith(".meta") }
                ?.mapNotNull { f ->
                    try {
                        SessionSummary.fromJson(JSONObject(f.readText()))
                    } catch (e: Exception) {
                        null
                    }
                }
                ?.sortedByDescending { it.updatedAt }
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "list failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun load(context: Context, id: String): Session? = withContext(Dispatchers.IO) {
        try {
            val f = sessionFile(context, id)
            if (!f.exists()) null else Session.fromJson(f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "load $id failed: ${e.message}")
            null
        }
    }

    suspend fun save(context: Context, session: Session) = withContext(Dispatchers.IO) {
        try {
            val full = sessionFile(context, session.id)
            val tmp = File(full.parentFile, "${session.id}.tmp")
            tmp.writeText(session.toJson().toString())
            if (!tmp.renameTo(full)) {
                full.writeText(tmp.readText())
                tmp.delete()
            }
            val meta = metaFile(context, session.id)
            val metaTmp = File(meta.parentFile, "${session.id}.meta.tmp")
            metaTmp.writeText(session.toSummary().toJson().toString())
            if (!metaTmp.renameTo(meta)) {
                meta.writeText(metaTmp.readText())
                metaTmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "save ${session.id} failed: ${e.message}")
        }
    }

    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        try {
            sessionFile(context, id).delete()
            metaFile(context, id).delete()
        } catch (e: Exception) {
            Log.w(TAG, "delete $id failed: ${e.message}")
        }
    }
}
