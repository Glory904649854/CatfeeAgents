package com.catfee.rootauto.agent.memory

import android.content.Context

/**
 * 记忆检索与注入。
 *
 * P0/P2 的核心: 不再整块注入最旧的 800 字, 而是按
 * 相关性 + 新近度 + 置信度 + 使用率 打分, 只取 top-K 作为 core memory;
 * 其余条目留在归档里, 由 memory_search 工具按需检索。
 */
object MemoryInjector {

    data class Rendered(val text: String, val ids: List<String>)

    private data class Candidate(
        val item: MemoryItem,
        val global: Boolean,
        val key: String,
        val score: Double
    )

    /**
     * 生成注入到 system 的 core memory。
     *
     * @param goal 当前任务目标
     * @param pageHint 当前页面/最近观察, 用于提高同页面记忆的权重
     * @param budget 字符预算
     */
    fun render(
        context: Context,
        pkg: String,
        goal: String,
        pageHint: String = "",
        budget: Int = 800
    ): Rendered {
        val candidates = collect(context, pkg, "$goal $pageHint")
        if (candidates.isEmpty()) return Rendered("", emptyList())

        val now = System.currentTimeMillis()
        val selected = ArrayList<Candidate>()
        val selectedKeys = HashSet<String>()
        val sb = StringBuilder()

        fun appendCandidate(c: Candidate, oneHop: Boolean) {
            if (c.key in selectedKeys) return
            val line = format(c.item, c.global, oneHop)
            if (sb.length + line.length + 1 > budget) return
            sb.append(line).append('\n')
            selected.add(c)
            selectedKeys.add(c.key)
        }

        candidates.forEach { if (sb.length < budget) appendCandidate(it, oneHop = false) }

        // P3: 一跳互链。被选中的条目若链接了其它记忆, 在预算允许时一起带上。
        val byKey = candidates.associateBy { it.key }
        selected.toList().forEach { c ->
            c.item.links.forEach { link ->
                val linked = byKey[link] ?: candidates.firstOrNull {
                    it.item.id == link && it.global == c.global
                }
                if (linked != null) appendCandidate(linked, oneHop = true)
            }
        }

        if (sb.isEmpty()) return Rendered("", emptyList())
        return Rendered(sb.toString().trimEnd(), selected.map { it.item.id })
    }

    /** 不做相关性筛选, 按强度渲染全部条目; 用于 memory_read / 编辑器和 Console 同步展示 */
    fun renderAll(context: Context, pkg: String, budget: Int = 4000): Rendered {
        val stores = loadStores(context, pkg)
        val now = System.currentTimeMillis()
        val items = stores.flatMap { (store, global) ->
            store.entries.map { Candidate(it, global, keyOf(store.pkg, it.id), MemoryRank.strength(it, now)) }
        }.sortedByDescending { it.score }

        val sb = StringBuilder()
        val ids = ArrayList<String>()
        items.forEach { c ->
            if (sb.length >= budget) return@forEach
            val line = format(c.item, c.global, oneHop = false)
            if (sb.length + line.length + 1 <= budget) {
                sb.append(line).append('\n')
                ids.add(c.item.id)
            }
        }
        return Rendered(sb.toString().trimEnd(), ids)
    }

    /** 关键词检索归档记忆; 供 memory_search 使用 */
    fun search(context: Context, pkg: String, query: String, limit: Int = 8): List<MemoryItem> {
        val stores = loadStores(context, pkg)
        return stores.flatMap { (store, _) -> store.entries }
            .map { it to MemoryRank.relevance(query, it) }
            .filter { it.second > 0.05 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    fun format(item: MemoryItem, global: Boolean = false, oneHop: Boolean = false): String {
        // 记忆可能来自页面/控制台, 统一压成单行并标记为参考数据, 降低提示词注入面。
        val cleanText = item.text
            .replace('\n', ' ')
            .replace('\r', ' ')
            .filter { it.code >= 32 }
            .trim()
        return buildString {
            append("[参考·")
            if (global) append("全局·")
            append(item.kind.label)
            append("] ")
            if (oneHop) append("↳ ")
            if (item.page.isNotBlank()) append(item.page).append(": ")
            append(cleanText.take(160))
            if (item.selector.isNotBlank()) append(" (").append(item.selector.take(90)).append(')')
            if (item.precondition.isNotBlank()) append(" {").append(item.precondition.take(50)).append('}')
            append(" ·").append((item.confidence * 100).toInt()).append('%')
        }
    }

    private fun collect(context: Context, pkg: String, query: String): List<Candidate> {
        val stores = loadStores(context, pkg)
        return stores.flatMap { (store, global) ->
            store.entries.map { item ->
                Candidate(
                    item = item,
                    global = global,
                    key = keyOf(store.pkg, item.id),
                    score = MemoryRank.score(item, query) + if (global) -0.03 else 0.0
                )
            }
        }.filter { it.score > 0.12 }
            .sortedByDescending { it.score }
    }

    /**
     * 同时加载 App 记忆和全局记忆。
     * 返回 (store, isGlobal); 传入 GLOBAL_PKG 时只加载一次。
     */
    private fun loadStores(context: Context, pkg: String): List<Pair<MemoryStoreData, Boolean>> {
        val out = ArrayList<Pair<MemoryStoreData, Boolean>>()
        if (pkg.isNotBlank() && pkg != MemoryStore.GLOBAL_PKG) {
            out.add(MemoryStore.load(context, pkg) to false)
        }
        val global = MemoryStore.load(context, MemoryStore.GLOBAL_PKG)
        if (global.entries.isNotEmpty()) out.add(global to true)
        return out
    }

    private fun keyOf(pkg: String, id: String): String = "$pkg::$id"
}
