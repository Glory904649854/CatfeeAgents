package com.catfee.rootauto.session

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把会话导出为可读 Markdown; 不包含隐藏 OCR 上下文、附件绝对路径和 phone_allowed */
object SessionExporter {

    fun safeFileName(title: String): String {
        val base = title.trim().replace(Regex("""[\\/:*?"<>|\n\r]"""), "_").take(40)
        return (base.ifBlank { "CatfeeAgents-session" }) + ".md"
    }

    fun toMarkdown(session: Session): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("# ").append(session.title).append("\n\n")
        if (session.summary.isNotBlank()) {
            sb.append("> ").append(session.summary.replace('\n', ' ')).append("\n\n")
        }
        sb.append("- 创建: ").append(fmt.format(Date(session.createdAt))).append("\n")
        sb.append("- 更新: ").append(fmt.format(Date(session.updatedAt))).append("\n")
        sb.append("- Tokens: ").append(session.totalTokens)
            .append(" (prompt ").append(session.promptTokens)
            .append(" / completion ").append(session.completionTokens).append(")\n")
        sb.append("- 累计耗时: ").append(session.totalDurationMs).append(" ms\n\n")

        session.messages.forEach { m ->
            if (m.role == "user") {
                sb.append("## 用户\n\n")
                if (m.quotedText.isNotBlank()) {
                    sb.append("> 引用: ").append(m.quotedText.replace('\n', ' ').take(300)).append("\n\n")
                }
                sb.append(m.content).append("\n\n")
                if (m.attachments.isNotEmpty()) {
                    sb.append("附件: ")
                        .append(m.attachments.joinToString(", ") { it.name })
                        .append("\n\n")
                }
            } else {
                sb.append("## 助手\n\n")
                sb.append(m.content).append("\n\n")
                if (m.reasoning.isNotBlank()) {
                    sb.append("<details><summary>思考过程</summary>\n\n")
                    sb.append(m.reasoning.take(8_000)).append("\n\n</details>\n\n")
                }
                if (m.tokens > 0 || m.elapsedMs > 0) {
                    sb.append("_").append(m.tokens).append(" tok · ")
                        .append(m.elapsedMs).append(" ms_\n\n")
                }
            }
        }

        if (session.steps.isNotEmpty()) {
            sb.append("## 执行步骤\n\n")
            session.steps.forEach { s ->
                sb.append("- [").append(s.type).append("] ")
                    .append(s.text.replace('\n', ' ').take(500)).append("\n")
            }
        }
        return sb.toString()
    }

    suspend fun write(context: Context, session: Session, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(toMarkdown(session).toByteArray(Charsets.UTF_8))
                } != null
            } catch (e: Exception) {
                false
            }
        }
}
