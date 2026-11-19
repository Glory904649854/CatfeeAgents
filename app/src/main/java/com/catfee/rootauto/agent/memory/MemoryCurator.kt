package com.catfee.rootauto.agent.memory

import android.content.Context
import android.util.Log
import com.catfee.rootauto.console.ConsoleClient
import com.catfee.rootauto.llm.LlmClient
import com.catfee.rootauto.llm.LlmSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 记忆整理器（后台 "sleep-time" 整理）。
 *
 * 任务结束后把轨迹摘要入队, 由轻量 LLM 输出 ADD/UPDATE/DELETE/NOOP 操作,
 * 再合并到结构化记忆里; 不再直接往 markdown 尾部追加。
 *
 * 参考 Mem0 的记忆更新流程与 Letta 的后台整理:
 * - 先看现有记忆, 再决定新增/更新/删除
 * - 新记忆合并到相似条目, 冲突时更新而不是堆叠
 * - 整理异步执行, 不阻塞任务返回
 */
object MemoryCurator {

    private const val TAG = "MemoryCurator"
    private const val PENDING_DIR = "memory_pending"
    private const val MAX_PENDING = 12
    private const val MAX_PROMPT_ENTRIES = 25

    data class Record(
        val taskId: String,
        val pkg: String,
        val goal: String,
        val result: String,
        val success: Boolean,
        val notes: String,
        val failures: List<String>,
        val appVersion: String,
        val createdAt: Long
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainLock = Mutex()

    @Volatile
    private var lastDrainAt = 0L

    /** 任务结束后调用: 写入待整理队列并异步触发整理 */
    fun enqueue(context: Context, record: Record) {
        if (record.pkg.isBlank() || record.pkg == context.packageName) return
        if (record.taskId.isBlank()) return
        if (record.goal.isBlank() && record.result.isBlank()) return
        try {
            val dir = pendingDir(context).apply { if (!exists()) mkdirs() }
            val name = "${record.createdAt}_${record.taskId.takeLast(10)}.json"
            val f = File(dir, safeName(name))
            if (f.exists()) return
            val obj = JSONObject()
                .put("taskId", record.taskId)
                .put("pkg", record.pkg)
                .put("goal", record.goal.take(800))
                .put("result", record.result.take(1200))
                .put("success", record.success)
                .put("notes", record.notes.take(1500))
                .put("failures", JSONArray(record.failures.take(8)))
                .put("appVersion", record.appVersion)
                .put("createdAt", record.createdAt)
            f.writeText(obj.toString())

            // 队列上限: 丢最旧的, 避免长期堆积烧 token
            val files = dir.listFiles()?.filter { it.name.endsWith(".json") }
                ?.sortedBy { it.name }.orEmpty()
            if (files.size > MAX_PENDING) {
                files.take(files.size - MAX_PENDING).forEach { it.delete() }
            }
            requestDrain(context)
        } catch (e: Exception) {
            Log.w(TAG, "入队失败: ${e.message}")
        }
    }

    /** App 启动或任务结束时调用; 没有待整理记录时立即返回 */
    fun requestDrain(context: Context) {
        val app = context.applicationContext
        scope.launch { drain(app) }
    }

    private suspend fun drain(context: Context) {
        if (!drainLock.tryLock()) return
        try {
            val provider = LlmSettings.active(context) ?: return
            if (!provider.usable) return
            val files = pendingDir(context).listFiles()
                ?.filter { it.name.endsWith(".json") }
                ?.sortedBy { it.name }
                .orEmpty()
            if (files.isEmpty()) return

            // 每次最多整理 2 条, 控制后台成本; 其余留到下次
            files.take(2).forEach { file ->
                if (System.currentTimeMillis() - lastDrainAt < 3_000) return@forEach
                val record = parseRecord(file)
                if (record == null) {
                    file.delete()
                    return@forEach
                }
                try {
                    curate(context, provider, record)
                } catch (e: Exception) {
                    Log.w(TAG, "整理失败 ${file.name}: ${e.message}")
                } finally {
                    lastDrainAt = System.currentTimeMillis()
                    file.delete()
                }
                delay(800)
            }
        } finally {
            drainLock.unlock()
        }
    }

    private suspend fun curate(
        context: Context,
        provider: LlmSettings.Provider,
        record: Record
    ) {
        val store = MemoryStore.load(context, record.pkg)
        val versionChanged = record.appVersion.isNotBlank() &&
            store.appVersion.isNotBlank() && store.appVersion != record.appVersion

        val existing = buildString {
            store.entries.take(MAX_PROMPT_ENTRIES).forEach { item ->
                append("- id=").append(item.id)
                append(" kind=").append(item.kind.name.lowercase())
                if (item.page.isNotBlank()) append(" page=").append(item.page)
                append(" text=").append(item.text.take(160))
                if (item.selector.isNotBlank()) append(" selector=").append(item.selector.take(80))
                append(" conf=").append(String.format("%.2f", item.confidence))
                append(" ok=").append(item.success).append(" fail=").append(item.fail)
                if (item.links.isNotEmpty()) append(" links=").append(item.links.joinToString(","))
                append('\n')
            }
        }.ifBlank { "(空)" }

        val failures = if (record.failures.isEmpty()) "(无)" else
            record.failures.joinToString("\n") { "- ${it.take(180)}" }

        val prompt = """
            App: ${record.pkg} (版本 ${record.appVersion.ifBlank { "未知" }})
            本次任务目标: ${record.goal.take(500)}
            是否成功: ${if (record.success) "是" else "否"}
            任务结果: ${record.result.take(800)}
            过程便签:
            ${record.notes.take(1000).ifBlank { "(无)" }}
            失败点:
            $failures
            ${if (versionChanged) "注意: App 版本从 ${store.appVersion} 变为 ${record.appVersion}, 界面上可能已失效的旧条目要 update 或 delete。" else ""}

            现有记忆:
            $existing

            只输出 JSON, 不要解释:
            {"ops":[
              {"op":"add","entry":{"kind":"entry|control|pitfall|flow|preference","page":"页面/路径","text":"稳定事实,≤120字","selector":"text=/desc=/id=/xy=","precondition":"","confidence":0.6,"links":[]}},
              {"op":"update","id":"mem_xxx","entry":{"text":"新描述","selector":"...","confidence":0.8}},
              {"op":"delete","id":"mem_xxx","reason":"已失效"},
              {"op":"noop"}
            ]}

            规则:
            - 只记稳定入口、关键控件、容易误点的坑、可复用的操作流程; 不记答案、一次性数据、用户隐私、临时弹窗。
            - 同一条记忆合并成一个 update, 不要重复 add。
            - 失败点要记成 kind=pitfall, 说明"做什么会失败/会跳到哪里"。
            - 如果本次是失败任务, 置信度不要超过 0.55。
            - links 只能引用现有记忆的 id。
            - 没有值得记的内容就输出 {"ops":[{"op":"noop"}]}。
        """.trimIndent()

        val messages = JSONArray().put(
            JSONObject().put("role", "system")
                .put("content", "你是 Android App 操作记忆整理器。只输出合法 JSON。")
        ).put(JSONObject().put("role", "user").put("content", prompt))

        val r = LlmClient.chat(provider, messages, 30_000)
        if (!r.ok) {
            Log.w(TAG, "记忆整理请求失败: ${r.error}")
            return
        }
        val ops = parseOps(r.content) ?: run {
            Log.w(TAG, "记忆整理输出不是合法 JSON")
            return
        }

        var changed = false
        MemoryStore.mutate(context, record.pkg) { target ->
            target.appVersion = record.appVersion.ifBlank { target.appVersion }
            for (i in 0 until ops.length()) {
                val op = ops.optJSONObject(i) ?: continue
                when (op.optString("op").lowercase()) {
                    "add" -> {
                        val entry = op.optJSONObject("entry") ?: continue
                        val item = itemFromCurator(entry, record)
                        if (item == null || item.text.isBlank()) continue
                        val existing = MemoryStore.findSimilar(target, item)
                        if (existing != null) {
                            MemoryRank.mergeInto(existing, item, verified = record.success, record.taskId)
                            changed = true
                        } else {
                            MemoryStore.upsert(target, item, verified = record.success, record.taskId)
                            changed = true
                        }
                    }
                    "update" -> {
                        val id = op.optString("id")
                        val entry = op.optJSONObject("entry") ?: continue
                        val item = target.entries.firstOrNull { it.id == id } ?: continue
                        val patch = itemFromCurator(entry, record)
                        if (patch != null) {
                            if (patch.text.isNotBlank()) item.text = patch.text
                            if (patch.page.isNotBlank()) item.page = patch.page
                            if (patch.selector.isNotBlank()) item.selector = patch.selector
                            if (patch.precondition.isNotBlank()) item.precondition = patch.precondition
                            if (patch.kind != MemoryKind.ENTRY) item.kind = patch.kind
                            item.confidence = maxOf(item.confidence, patch.confidence)
                            item.lastVerifiedAt = System.currentTimeMillis()
                            patch.links.forEach { if (it !in item.links) item.links.add(it) }
                            item.links = item.links.distinct().take(8).toMutableList()
                            changed = true
                        }
                    }
                    "delete" -> {
                        val id = op.optString("id")
                        if (target.entries.removeAll { it.id == id }) changed = true
                    }
                }
            }
        }

        if (changed) {
            Log.i(TAG, "记忆已更新: ${record.pkg}")
            ConsoleClient.syncMemories(context, record.pkg)
        }
    }

    private fun itemFromCurator(entry: JSONObject, record: Record): MemoryItem? {
        val text = entry.optString("text").trim()
            .replace(Regex("\\*\\*"), "")
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .trim()
        if (text.length < 6 || text.endsWith("**")) return null
        val links = mutableListOf<String>()
        entry.optJSONArray("links")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optString(i)
                if (v.isNotBlank()) links.add(v)
            }
        }
        val maxConf = if (record.success) 0.92 else 0.55
        return MemoryItem(
            kind = MemoryKind.parse(entry.optString("kind")),
            page = entry.optString("page").take(80),
            text = text.take(300),
            selector = entry.optString("selector").take(140),
            precondition = entry.optString("precondition").take(100),
            links = links.distinct().take(8).toMutableList(),
            confidence = entry.optDouble("confidence", 0.6)
                .coerceIn(0.3, maxConf),
            sourceRun = record.taskId
        )
    }

    private fun parseOps(raw: String): JSONArray? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(raw.substring(start, end + 1)).optJSONArray("ops")
        } catch (e: Exception) {
            null
        }
    }

    private fun pendingDir(context: Context): File =
        File(context.filesDir, PENDING_DIR)

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun parseRecord(file: File): Record? = try {
        val o = JSONObject(file.readText())
        val failures = mutableListOf<String>()
        o.optJSONArray("failures")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optString(i)
                if (v.isNotBlank()) failures.add(v)
            }
        }
        Record(
            taskId = o.optString("taskId"),
            pkg = o.optString("pkg"),
            goal = o.optString("goal"),
            result = o.optString("result"),
            success = o.optBoolean("success", false),
            notes = o.optString("notes"),
            failures = failures,
            appVersion = o.optString("appVersion"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
    } catch (e: Exception) {
        null
    }
}
