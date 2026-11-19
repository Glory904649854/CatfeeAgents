package com.catfee.rootauto.agent.memory

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** 记忆类型: 入口 / 控件 / 坑 / 流程 / 偏好 */
enum class MemoryKind(val label: String) {
    ENTRY("入口"),
    CONTROL("控件"),
    PITFALL("坑"),
    FLOW("流程"),
    PREFERENCE("偏好");

    companion object {
        fun parse(raw: String): MemoryKind {
            val v = raw.trim().lowercase()
            return when {
                v in setOf("entry", "入口") -> ENTRY
                v in setOf("control", "控件", "按钮") -> CONTROL
                v in setOf("pitfall", "坑", "易错", "风险") -> PITFALL
                v in setOf("flow", "流程", "步骤") -> FLOW
                v in setOf("preference", "偏好", "习惯") -> PREFERENCE
                else -> ENTRY
            }
        }

        /** 从 "[坑] ..." 这类标签前缀解析 */
        fun fromLabel(label: String): MemoryKind =
            entries.firstOrNull { label.trim().startsWith(it.label) } ?: ENTRY
    }
}

fun newMemoryId(): String =
    "mem_" + UUID.randomUUID().toString().replace("-", "").take(10)

/**
 * 一条结构化记忆。
 *
 * 与旧的整段 markdown 相比, 每条记忆都有类型/页面/选择器/置信度,
 * 可以合并、验证、衰减、互链, 也能按任务相关性挑选注入。
 */
data class MemoryItem(
    var id: String = newMemoryId(),
    var kind: MemoryKind = MemoryKind.ENTRY,
    var page: String = "",
    var text: String = "",
    var selector: String = "",
    var precondition: String = "",
    var links: MutableList<String> = mutableListOf(),
    var confidence: Double = 0.6,
    var success: Int = 0,
    var fail: Int = 0,
    var createdAt: Long = System.currentTimeMillis(),
    var lastUsedAt: Long = 0L,
    var lastVerifiedAt: Long = 0L,
    var sourceRun: String = ""
) {
    fun toJson(): JSONObject {
        val linkArray = JSONArray()
        links.forEach { linkArray.put(it) }
        return JSONObject()
            .put("id", id)
            .put("kind", kind.name.lowercase())
            .put("page", page)
            .put("text", text)
            .put("selector", selector)
            .put("precondition", precondition)
            .put("links", linkArray)
            .put("confidence", confidence)
            .put("success", success)
            .put("fail", fail)
            .put("createdAt", createdAt)
            .put("lastUsedAt", lastUsedAt)
            .put("lastVerifiedAt", lastVerifiedAt)
            .put("sourceRun", sourceRun)
    }

    companion object {
        fun fromJson(o: JSONObject): MemoryItem? {
            val text = o.optString("text").trim()
            if (text.isBlank()) return null
            val links = mutableListOf<String>()
            o.optJSONArray("links")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i)
                    if (v.isNotBlank()) links.add(v)
                }
            }
            return MemoryItem(
                id = o.optString("id").ifBlank { newMemoryId() },
                kind = MemoryKind.parse(o.optString("kind")),
                page = o.optString("page").take(80),
                text = text.take(500),
                selector = o.optString("selector").take(160),
                precondition = o.optString("precondition").take(120),
                links = links.distinct().take(8).toMutableList(),
                confidence = o.optDouble("confidence", 0.6).coerceIn(0.0, 1.0),
                success = o.optInt("success", 0).coerceIn(0, 9999),
                fail = o.optInt("fail", 0).coerceIn(0, 9999),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                lastUsedAt = o.optLong("lastUsedAt", 0L),
                lastVerifiedAt = o.optLong("lastVerifiedAt", 0L),
                sourceRun = o.optString("sourceRun").take(80)
            )
        }
    }
}

/** 单个 App 的记忆库 */
data class MemoryStoreData(
    var pkg: String = "",
    var appVersion: String = "",
    var updatedAt: Long = 0L,
    val entries: MutableList<MemoryItem> = mutableListOf()
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("pkg", pkg)
            .put("appVersion", appVersion)
            .put("updatedAt", updatedAt)
            .put("entries", arr)
    }

    companion object {
        fun fromJson(pkg: String, o: JSONObject): MemoryStoreData {
            val store = MemoryStoreData(
                pkg = pkg,
                appVersion = o.optString("appVersion"),
                updatedAt = o.optLong("updatedAt", 0L)
            )
            o.optJSONArray("entries")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { MemoryItem.fromJson(it) }?.let {
                        store.entries.add(it)
                    }
                }
            }
            return store
        }
    }
}

/**
 * 记忆排序: 相关性 + 新近度 + 置信度 + 使用成功率。
 * 参考 Generative Agents 的 recency/importance/relevance, 这里用轻量关键词实现,
 * 不引入端侧向量库。
 */
object MemoryRank {

    private val asciiWord = Regex("[a-z0-9_]{2,}")

    /** 中文用二元组, 英文/数字用词, 这样不依赖分词器 */
    fun tokens(raw: String): Set<String> {
        val out = HashSet<String>()
        val text = raw.lowercase()
        asciiWord.findAll(text).forEach { out.add(it.value) }
        val cjk = buildString {
            text.forEach { if (it in '\u4e00'..'\u9fff') append(it) }
        }
        for (i in 0 until cjk.length - 1) out.add(cjk.substring(i, i + 2))
        return out
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var hit = 0
        for (t in a) if (t in b) hit++
        return hit.toDouble() / (a.size + b.size - hit).coerceAtLeast(1)
    }

    fun relevance(query: String, item: MemoryItem): Double {
        val q = tokens(query)
        if (q.isEmpty()) return 0.0
        val hay = tokens(item.text + " " + item.page + " " + item.selector + " " + item.precondition)
        if (hay.isEmpty()) return 0.0
        var hit = 0
        for (t in q) {
            if (t.length <= 2 && t !in hay) continue
            if (t in hay) hit++
        }
        return (hit.toDouble() / q.size).coerceIn(0.0, 1.0)
    }

    /** 30 天半衰 */
    fun recency(ts: Long, now: Long = System.currentTimeMillis()): Double {
        if (ts <= 0) return 0.25
        val days = ((now - ts) / 86_400_000.0).coerceAtLeast(0.0)
        return exp(-days / 30.0)
    }

    fun usage(item: MemoryItem): Double =
        (item.success + 1.0) / (item.success + item.fail + 2.0)

    fun score(
        item: MemoryItem,
        query: String,
        now: Long = System.currentTimeMillis()
    ): Double =
        0.45 * relevance(query, item) +
            0.20 * recency(item.lastVerifiedAt.takeIf { it > 0 } ?: item.createdAt, now) +
            0.20 * item.confidence +
            0.15 * usage(item)

    /** 遗忘曲线: 用于淘汰长期未验证且没成功过的条目 */
    fun strength(item: MemoryItem, now: Long = System.currentTimeMillis()): Double {
        val anchor = item.lastVerifiedAt.takeIf { it > 0 } ?: item.createdAt
        val days = ((now - anchor) / 86_400_000.0).coerceAtLeast(0.0)
        val decay = exp(-days / 45.0)
        return item.confidence * decay * (1.0 + min(item.success, 10) * 0.08)
    }

    /** 同一条记忆的判定: 选择器一致, 或同类型且文本高度相似 */
    fun similar(a: MemoryItem, b: MemoryItem): Boolean {
        if (a.selector.isNotBlank() && b.selector.isNotBlank() &&
            a.selector.equals(b.selector, ignoreCase = true)
        ) return true
        if (a.kind != b.kind) return false
        val ta = tokens(a.text)
        val tb = tokens(b.text)
        return jaccard(ta, tb) >= 0.72
    }

    fun mergeInto(target: MemoryItem, incoming: MemoryItem, verified: Boolean, runId: String) {
        val now = System.currentTimeMillis()
        if (incoming.text.length > target.text.length) target.text = incoming.text
        if (incoming.page.isNotBlank()) target.page = incoming.page
        if (incoming.selector.isNotBlank()) target.selector = incoming.selector
        if (incoming.precondition.isNotBlank()) target.precondition = incoming.precondition
        target.confidence = max(target.confidence, incoming.confidence)
        incoming.links.forEach { if (it !in target.links) target.links.add(it) }
        target.links = target.links.distinct().take(8).toMutableList()
        target.lastUsedAt = now
        if (verified) {
            target.success += 1
            target.lastVerifiedAt = now
            target.confidence = min(0.97, target.confidence + 0.03)
        }
        if (runId.isNotBlank() && target.sourceRun.isBlank()) target.sourceRun = runId
    }
}

/**
 * 文本行编解码: 设备端记忆编辑器和 memory_write 都走这个格式。
 * 格式: [坑] 页面 | 正文 | selector
 */
object MemoryLineCodec {

    fun render(item: MemoryItem): String = buildString {
        append('[').append(item.kind.label).append("] ")
        if (item.page.isNotBlank()) append(item.page).append(" | ")
        append(item.text)
        if (item.selector.isNotBlank()) append(" | ").append(item.selector)
    }

    fun parse(content: String): List<MemoryItem> {
        val rawText = content.trim()
        if (rawText.isBlank()) return emptyList()

        // 结构化行(编辑器/[kind] 前缀)逐行解析; 普通 markdown 段落整体作为一条,
        // 避免把「关键控件**」这类标题拆成碎片记忆。
        val structured = rawText.lineSequence().any { line ->
            val t = line.trim()
            t.startsWith("[") && t.contains("]")
        }
        if (!structured) {
            val clean = rawText
                .replace(Regex("\\*\\*"), "")
                .replace(Regex("(?m)^#{1,6}\\s*"), "")
                .replace(Regex("(?m)^[-*•]\\s*"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (clean.length < 8) return emptyList()
            return listOf(
                MemoryItem(kind = MemoryKind.ENTRY, text = clean.take(500), confidence = 0.6)
            )
        }

        val out = ArrayList<MemoryItem>()
        content.split('\n').forEach { raw ->
            val line = raw.trim().trimStart('-', '*', '•').trim()
            if (line.isBlank()) return@forEach
            // 丢弃标题/分隔线/过短碎片
            if (line.startsWith("#") || line.endsWith("**") || line.length < 6) return@forEach
            val tagEnd = if (line.startsWith("[")) line.indexOf(']') else -1
            val kind = if (tagEnd > 1) {
                MemoryKind.fromLabel(line.substring(1, tagEnd))
            } else {
                MemoryKind.ENTRY
            }
            val rest = if (tagEnd > 0) line.substring(tagEnd + 1).trim() else line
            if (rest.isBlank()) return@forEach
            val parts = rest.split('|').map { it.trim() }
            var page = ""
            var text = rest
            var selector = ""
            when {
                parts.size >= 3 -> {
                    page = parts[0]
                    text = parts[1]
                    selector = parts[2]
                }
                parts.size == 2 -> {
                    text = parts[0]
                    selector = parts[1]
                }
            }
            if (text.isBlank() || text.length < 4) return@forEach
            out.add(
                MemoryItem(
                    kind = kind,
                    page = page.take(80),
                    text = text.take(300),
                    selector = selector.take(140),
                    confidence = 0.6
                )
            )
        }
        return out
    }
}
