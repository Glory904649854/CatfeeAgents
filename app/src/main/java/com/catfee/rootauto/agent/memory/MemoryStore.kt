package com.catfee.rootauto.agent.memory

import android.content.Context
import android.util.Log
import com.catfee.rootauto.BuildConfig
import org.json.JSONObject
import java.io.File

/**
 * 结构化记忆存储。
 *
 * 每个 App 一个 JSON:
 *   files/memory_store/<pkg>.json
 *
 * 旧的 files/memory/<pkg>.md 会在首次读取时自动迁移成结构化条目，
 * 原文件保留为 .md.bak，避免用户数据丢失。
 */
object MemoryStore {

    private const val TAG = "AgentMemory"
    private const val DIR_NAME = "memory_store"
    private const val MAX_ENTRIES = 40
    private const val MAX_ENTRY_TEXT = 500

    /** 跨 App 的通用经验（开屏广告、权限弹窗、键盘行为等） */
    const val GLOBAL_PKG = "__global__"

    private val lock = Any()

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun safeName(pkg: String): String =
        pkg.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun file(context: Context, pkg: String): File =
        File(dir(context), safeName(pkg) + ".json")

    private fun legacyFile(context: Context, pkg: String): File =
        File(File(context.filesDir, "memory"), safeName(pkg) + ".md")

    fun load(context: Context, pkg: String): MemoryStoreData = synchronized(lock) {
        loadLocked(context, pkg)
    }

    fun save(context: Context, store: MemoryStoreData) = synchronized(lock) {
        saveLocked(context, store)
    }

    fun mutate(
        context: Context,
        pkg: String,
        block: (MemoryStoreData) -> Unit
    ): MemoryStoreData = synchronized(lock) {
        val store = loadLocked(context, pkg)
        block(store)
        prune(store)
        saveLocked(context, store)
        store
    }

    fun delete(context: Context, pkg: String): Boolean = synchronized(lock) {
        var ok = false
        try {
            val f = file(context, pkg)
            if (f.exists()) ok = f.delete()
            val legacy = legacyFile(context, pkg)
            if (legacy.exists()) {
                legacy.delete()
                ok = true
            }
            val bak = File(legacy.parentFile, legacy.name + ".bak")
            if (bak.exists()) bak.delete()
        } catch (e: Exception) {
            Log.w(TAG, "删除记忆失败: ${e.message}")
        }
        ok
    }

    fun packages(context: Context): List<String> = synchronized(lock) {
        val names = HashSet<String>()
        dir(context).listFiles()?.forEach { f ->
            if (f.name.endsWith(".json")) names.add(f.nameWithoutExtension)
        }
        // 兼容还没迁移的 md
        File(context.filesDir, "memory").listFiles()?.forEach { f ->
            if (f.name.endsWith(".md")) names.add(f.nameWithoutExtension)
        }
        names.sorted()
    }

    fun all(context: Context): List<MemoryStoreData> = synchronized(lock) {
        packages(context).map { loadLocked(context, it) }
    }

    fun appVersion(context: Context, pkg: String): String {
        if (pkg == GLOBAL_PKG) return BuildConfig.VERSION_NAME
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /** 找同一条记忆: 选择器一致, 或同类型且文本高度相似 */
    fun findSimilar(store: MemoryStoreData, incoming: MemoryItem): MemoryItem? =
        store.entries.firstOrNull { MemoryRank.similar(it, incoming) }

    /**
     * 写入或合并一条记忆。
     * verified=true 表示它来自一次真实成功执行, 会提升置信度和成功次数。
     */
    fun upsert(
        store: MemoryStoreData,
        incoming: MemoryItem,
        verified: Boolean,
        runId: String
    ): MemoryItem {
        val text = incoming.text.trim().take(MAX_ENTRY_TEXT)
        incoming.text = text
        val existing = findSimilar(store, incoming)
        if (existing != null) {
            MemoryRank.mergeInto(existing, incoming, verified, runId)
            return existing
        }
        incoming.text = text
        incoming.confidence = incoming.confidence.coerceIn(0.0, 1.0)
        if (verified) {
            incoming.success = maxOf(incoming.success, 1)
            incoming.lastVerifiedAt = System.currentTimeMillis()
        }
        store.entries.add(incoming)
        return incoming
    }

    fun touch(context: Context, pkg: String, ids: Collection<String>, success: Boolean) {
        if (ids.isEmpty()) return
        mutate(context, pkg) { store ->
            val now = System.currentTimeMillis()
            store.entries.filter { it.id in ids }.forEach { item ->
                item.lastUsedAt = now
                if (success) {
                    item.success += 1
                    item.lastVerifiedAt = now
                    item.confidence = (item.confidence + 0.02).coerceAtMost(0.97)
                }
            }
        }
    }

    private fun loadLocked(context: Context, pkg: String): MemoryStoreData {
        val f = file(context, pkg)
        if (f.exists()) {
            return try {
                MemoryStoreData.fromJson(pkg, JSONObject(f.readText()))
            } catch (e: Exception) {
                Log.w(TAG, "解析记忆失败 ${f.name}: ${e.message}")
                MemoryStoreData(pkg = pkg)
            }
        }
        val legacy = legacyFile(context, pkg)
        if (legacy.exists()) {
            return migrateLocked(context, pkg, legacy)
        }
        return MemoryStoreData(pkg = pkg)
    }

    private fun migrateLocked(context: Context, pkg: String, legacy: File): MemoryStoreData {
        val store = MemoryStoreData(pkg = pkg)
        try {
            val items = MemoryLineCodec.parse(legacy.readText())
            items.forEach { item ->
                item.confidence = 0.5
                upsert(store, item, verified = false, runId = "legacy-md")
            }
            store.appVersion = appVersion(context, pkg)
            store.updatedAt = System.currentTimeMillis()
            saveLocked(context, store)
            // 保留原文件, 方便用户回看/回滚
            legacy.renameTo(File(legacy.parentFile, legacy.name + ".bak"))
            Log.i(TAG, "已迁移旧记忆 $pkg -> ${items.size} 条")
        } catch (e: Exception) {
            Log.w(TAG, "迁移旧记忆失败 $pkg: ${e.message}")
        }
        return store
    }

    private fun saveLocked(context: Context, store: MemoryStoreData) {
        try {
            store.updatedAt = System.currentTimeMillis()
            val f = file(context, store.pkg)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(store.toJson().toString())
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.w(TAG, "保存记忆失败 ${store.pkg}: ${e.message}")
        }
    }

    /** 遗忘/去重/上限保护: 每条 App 最多 40 条, 长期失效的自动淘汰 */
    private fun prune(store: MemoryStoreData) {
        val now = System.currentTimeMillis()
        store.entries.removeAll { item ->
            val anchor = item.lastVerifiedAt.takeIf { it > 0 } ?: item.createdAt
            val days = ((now - anchor) / 86_400_000.0).coerceAtLeast(0.0)
            (item.success == 0 && item.fail >= 2) ||
                (item.success == 0 && item.confidence < 0.22 && days > 45)
        }
        val ids = store.entries.map { it.id }.toSet()
        store.entries.forEach { it.links.retainAll(ids) }
        if (store.entries.size > MAX_ENTRIES) {
            val keep = store.entries
                .sortedByDescending { MemoryRank.strength(it, now) }
                .take(MAX_ENTRIES)
            store.entries.clear()
            store.entries.addAll(keep)
        }
    }
}
