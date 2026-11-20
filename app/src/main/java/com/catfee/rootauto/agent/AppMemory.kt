package com.catfee.rootauto.agent

import android.content.Context
import android.util.Log
import com.catfee.rootauto.a11y.UiObserverService
import com.catfee.rootauto.agent.memory.MemoryInjector
import com.catfee.rootauto.agent.memory.MemoryItem
import com.catfee.rootauto.agent.memory.MemoryKind
import com.catfee.rootauto.agent.memory.MemoryLineCodec
import com.catfee.rootauto.agent.memory.MemoryStore
import com.catfee.rootauto.console.ConsoleClient
import com.catfee.rootauto.root.RootShell
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AppMemory —— 按 App 隔离的结构化操作记忆（对外门面）。
 *
 * 存储升级为 memory_store/<pkg>.json, 每条记忆包含:
 * 类型 / 页面 / 选择器 / 置信度 / 成功失败次数 / 最后验证时间 / 互链。
 *
 * - 注入: 按任务相关性和新近度挑选 top-K, 不再固定塞前 800 字
 * - 写入: 合并到相似条目, 不再尾部追加后被截断丢弃
 * - 旧版 memory/<pkg>.md 会自动迁移
 */
object AppMemory {

    private const val TAG = "AgentMemory"

    /** 注入预算: core memory */
    const val MAX_INJECT = 800

    /** 编辑器一次可编辑的渲染长度 */
    const val MAX_WRITE = 4000

    /** UI 列表用的摘要 */
    data class MemoryEntry(
        val pkg: String,
        val chars: Int,
        val preview: String,
        val updatedAt: Long,
        val entryCount: Int = 0
    )

    /** 当前前台 App 的包名 */
    suspend fun currentPackage(): String {
        UiObserverService.instance?.currentPackage()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        if (!RootShell.isAvailable()) return ""
        val line = if (ToolRuntime.rootAllowed()) {
            RootShell.execText("dumpsys window 2>/dev/null | grep -m1 mCurrentFocus")
        } else {
            ""
        }
        val inner = line.substringAfter('{', "").substringBefore('}')
        val target = inner.split(' ').lastOrNull { it.contains('/') }.orEmpty()
        return target.substringBefore('/').trim()
    }

    /** 注入用: 按当前目标/页面挑选 core memory */
    fun read(context: Context, pkg: String): String {
        if (pkg.isBlank()) return ""
        return MemoryInjector.render(context, pkg, "", "", MAX_INJECT).text
    }

    /** 工具 memory_read 用: 按强度列出全部条目 */
    fun readForTool(context: Context, pkg: String): String {
        if (pkg.isBlank()) return ""
        return MemoryInjector.renderAll(context, pkg, 1600).text
    }

    /**
     * 兼容旧接口: 写入一段文本。
     * 每行会解析成结构化条目并合并; append=false 时先清空。
     */
    fun write(context: Context, pkg: String, content: String, append: Boolean): String {
        if (pkg.isBlank()) return "包名为空, 无法确定写到哪个 App"
        if (content.isBlank()) return "content 为空"
        val items = MemoryLineCodec.parse(content).ifEmpty {
            listOf(
                MemoryItem(
                    kind = MemoryKind.ENTRY,
                    text = content.trim().take(300),
                    confidence = 0.6
                )
            )
        }
        val store = MemoryStore.mutate(context, pkg) { target ->
            target.appVersion = MemoryStore.appVersion(context, pkg)
            if (!append) target.entries.clear()
            items.forEach { MemoryStore.upsert(target, it, verified = false, runId = "manual") }
        }
        ConsoleClient.syncMemories(context, pkg)
        return "已写入 ${items.size} 条记忆到 $pkg (当前 ${store.entries.size} 条)"
    }

    /** 结构化写入: 供 memory_write 工具 / Console 编辑使用 */
    fun addEntry(
        context: Context,
        pkg: String,
        kind: String,
        page: String,
        text: String,
        selector: String,
        confidence: Double = 0.7,
        precondition: String = "",
        links: List<String> = emptyList()
    ): String {
        if (pkg.isBlank()) return "包名为空"
        if (text.isBlank()) return "text 为空"
        val store = MemoryStore.mutate(context, pkg) { target ->
            target.appVersion = MemoryStore.appVersion(context, pkg)
            MemoryStore.upsert(
                target,
                MemoryItem(
                    kind = MemoryKind.parse(kind),
                    page = page.take(80),
                    text = text.trim().take(300),
                    selector = selector.take(140),
                    precondition = precondition.take(100),
                    confidence = confidence.coerceIn(0.3, 0.95),
                    links = links.distinct().take(8).toMutableList()
                ),
                verified = false,
                runId = "manual"
            )
        }
        ConsoleClient.syncMemories(context, pkg)
        return "已保存记忆 ($pkg, 共 ${store.entries.size} 条)"
    }

    fun removeEntry(context: Context, pkg: String, id: String): Boolean {
        if (pkg.isBlank() || id.isBlank()) return false
        var removed = false
        MemoryStore.mutate(context, pkg) { store ->
            removed = store.entries.removeAll { it.id == id }
            store.entries.forEach { it.links.remove(id) }
        }
        if (removed) ConsoleClient.syncMemories(context, pkg)
        return removed
    }

    fun search(context: Context, pkg: String, query: String, limit: Int = 8): String {
        if (query.isBlank()) return "查询为空"
        val items = MemoryInjector.search(context, pkg, query, limit)
        if (items.isEmpty()) return "没有匹配的记忆"
        return items.joinToString("\n") {
            MemoryInjector.format(it)
        }
    }

    fun touch(context: Context, pkg: String, ids: Collection<String>, success: Boolean) {
        if (pkg.isBlank() || ids.isEmpty()) return
        MemoryStore.touch(context, pkg, ids, success)
    }

    /** 列出已有记忆 (包名 + 条数) */
    fun list(context: Context): String {
        val stores = MemoryStore.all(context)
        if (stores.isEmpty()) return "暂无 App 记忆"
        return stores.sortedBy { it.pkg }.joinToString("\n") {
            val name = if (it.pkg == MemoryStore.GLOBAL_PKG) "全局" else it.pkg
            "$name: ${it.entries.size} 条"
        }
    }

    /** UI 用: 结构化列出记忆摘要 */
    fun listEntries(context: Context): List<MemoryEntry> =
        MemoryStore.all(context)
            .sortedByDescending { it.updatedAt }
            .map { store ->
                val preview = store.entries
                    .joinToString(" ") { MemoryLineCodec.render(it) }
                    .replace('\n', ' ')
                    .take(120)
                MemoryEntry(
                    pkg = if (store.pkg == MemoryStore.GLOBAL_PKG) "全局" else store.pkg,
                    chars = preview.length,
                    preview = preview,
                    updatedAt = store.updatedAt,
                    entryCount = store.entries.size
                )
            }

    /** UI 编辑器用: 完整条目渲染成可编辑行 */
    fun readRaw(context: Context, pkg: String): String {
        val realPkg = if (pkg == "全局") MemoryStore.GLOBAL_PKG else pkg
        return MemoryInjector.renderAll(context, realPkg, MAX_WRITE).text
    }

    fun delete(context: Context, pkg: String): Boolean {
        val realPkg = if (pkg == "全局") MemoryStore.GLOBAL_PKG else pkg
        val ok = MemoryStore.delete(context, realPkg)
        ConsoleClient.syncMemories(context, realPkg)
        return ok
    }

    fun overwrite(context: Context, pkg: String, content: String): String {
        val realPkg = if (pkg == "全局") MemoryStore.GLOBAL_PKG else pkg
        return write(context, realPkg, content, append = false)
    }

    /** Console 同步用: 一个 App 的完整快照 */
    fun snapshot(context: Context, pkg: String): JSONObject {
        val store = MemoryStore.load(context, pkg)
        val arr = JSONArray()
        store.entries.take(80).forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("package", pkg)
            .put("appVersion", store.appVersion)
            .put("updatedAt", store.updatedAt)
            .put("entries", arr)
    }

    fun packages(context: Context): List<String> = MemoryStore.packages(context)

    /** 从 Console 下发更新一条记忆 */
    fun applyRemoteEntry(context: Context, pkg: String, entry: JSONObject): Boolean {
        val item = MemoryItem.fromJson(entry) ?: return false
        var ok = false
        MemoryStore.mutate(context, pkg) { store ->
            val existing = store.entries.firstOrNull { it.id == item.id }
            if (existing != null) {
                existing.kind = item.kind
                existing.page = item.page
                existing.text = item.text
                existing.selector = item.selector
                existing.precondition = item.precondition
                existing.confidence = item.confidence.coerceIn(0.0, 1.0)
                existing.links = item.links
                existing.lastVerifiedAt = System.currentTimeMillis()
                ok = true
            } else {
                MemoryStore.upsert(store, item, verified = false, runId = "console")
                ok = true
            }
        }
        if (ok) ConsoleClient.syncMemories(context, pkg)
        return ok
    }

    /** 删除旧版遗留文件（只有 md 时） */
    fun legacyFiles(context: Context): List<File> =
        File(context.filesDir, "memory").listFiles()?.filter { it.name.endsWith(".md") }.orEmpty()
}
