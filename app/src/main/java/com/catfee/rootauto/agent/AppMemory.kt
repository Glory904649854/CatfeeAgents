package com.catfee.rootauto.agent

import android.content.Context
import android.util.Log
import com.catfee.rootauto.a11y.UiObserverService
import com.catfee.rootauto.root.RootShell
import java.io.File

/**
 * AppMemory —— 按 App 隔离的操作记忆
 *
 * 功能描述:
 *   把「某个 App 的稳定入口、关键控件、易错点」记成一份很短的 markdown,
 *   以包名为文件名保存。Agent 每轮自动读取当前前台 App 的记忆并注入 system,
 *   这样换到不同 App 时都能复用上一次摸索出来的路径, 不用每次重来。
 *
 * 约束:
 *   - 注入上限 800 字, 写入上限 1200 字, 避免记忆把上下文撑爆
 *   - 只记稳定事实 (入口/按钮/id/坑), 不记题目答案、一次性数据
 *
 * 使用场景:
 *   ReActAgent 每轮注入; Agent 的 memory_read / memory_write / memory_list 工具。
 *
 * 修改时间: 2026-11-03
 */
object AppMemory {

    private const val TAG = "AgentMemory"
    private const val MAX_INJECT = 800
    const val MAX_WRITE = 1200

    data class MemoryEntry(
        val pkg: String,
        val chars: Int,
        val preview: String,
        val updatedAt: Long
    )

    /** 当前前台 App 的包名 */
    suspend fun currentPackage(): String {
        // 优先走无障碍窗口信息: 每轮都查, root dumpsys 太慢, 长任务会被放大成几分钟开销
        UiObserverService.instance?.currentPackage()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        if (!RootShell.isAvailable()) return ""
        val line = RootShell.execText("dumpsys window 2>/dev/null | grep -m1 mCurrentFocus")
        val inner = line.substringAfter('{', "").substringBefore('}')
        val target = inner.split(' ').lastOrNull { it.contains('/') }.orEmpty()
        return target.substringBefore('/').trim()
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "memory").apply { if (!exists()) mkdirs() }

    private fun file(context: Context, pkg: String): File =
        File(dir(context), pkg.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".md")

    /** 读取记忆 (注入上限 800 字) */
    fun read(context: Context, pkg: String): String {
        if (pkg.isBlank()) return ""
        val f = file(context, pkg)
        if (!f.exists()) return ""
        return try {
            f.readText().take(MAX_INJECT)
        } catch (e: Exception) {
            Log.w(TAG, "读取记忆失败: ${e.message}")
            ""
        }
    }

    /** 写入记忆 (覆盖或追加, 上限 1200 字) */
    fun write(context: Context, pkg: String, content: String, append: Boolean): String {
        if (pkg.isBlank()) return "包名为空, 无法确定写到哪个 App"
        if (content.isBlank()) return "content 为空"
        return try {
            val f = file(context, pkg)
            val old = if (append && f.exists()) f.readText() else ""
            val merged = if (old.isBlank()) content else "$old\n$content"
            val trimmed = merged.take(MAX_WRITE)
            f.writeText(trimmed)
            "已写入 ${trimmed.length} 字到 ${f.name}" +
                if (merged.length > MAX_WRITE) " (超出 $MAX_WRITE 字已截断)" else ""
        } catch (e: Exception) {
            Log.w(TAG, "写入记忆失败: ${e.message}")
            "写入失败: ${e.message}"
        }
    }

    /** 列出已有记忆 */
    fun list(context: Context): String {
        val files = dir(context).listFiles()?.filter { it.name.endsWith(".md") }.orEmpty()
        if (files.isEmpty()) return "暂无 App 记忆"
        return files.sortedBy { it.name }.joinToString("\n") {
            "${it.nameWithoutExtension}: ${it.length()} 字节"
        }
    }

    /** UI 用: 结构化列出记忆 */
    fun listEntries(context: Context): List<MemoryEntry> {
        val files = dir(context).listFiles()?.filter { it.name.endsWith(".md") }.orEmpty()
        return files.sortedByDescending { it.lastModified() }.mapNotNull { f ->
            try {
                val raw = f.readText()
                MemoryEntry(
                    pkg = f.nameWithoutExtension,
                    chars = raw.length,
                    preview = raw.replace('\n', ' ').trim().take(100),
                    updatedAt = f.lastModified()
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /** UI 用: 读取完整记忆, 不做注入上限截断 */
    fun readRaw(context: Context, pkg: String): String {
        if (pkg.isBlank()) return ""
        return try {
            val f = file(context, pkg)
            if (f.exists()) f.readText() else ""
        } catch (e: Exception) {
            Log.w(TAG, "读取完整记忆失败: ${e.message}")
            ""
        }
    }

    fun delete(context: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return try {
            file(context, pkg).delete()
        } catch (e: Exception) {
            Log.w(TAG, "删除记忆失败: ${e.message}")
            false
        }
    }

    fun overwrite(context: Context, pkg: String, content: String): String =
        write(context, pkg, content, append = false)
}
