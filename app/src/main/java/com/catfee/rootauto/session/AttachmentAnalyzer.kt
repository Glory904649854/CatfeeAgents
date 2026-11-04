package com.catfee.rootauto.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import com.catfee.rootauto.ocr.OcrEngine
import com.catfee.rootauto.ocr.PaddleOcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * AttachmentAnalyzer —— 本地附件导入与解析
 *
 * 图片: PP-OCR / ML Kit 本地 OCR, 把文字交给 LLM 判断;
 * 文本: 直接读;
 * PDF: PdfRenderer 渲染前几页 + OCR;
 * DOCX/XLSX/PPTX: 解 zip 取 XML 文本;
 * 其他: 只返回文件信息。
 */
object AttachmentAnalyzer {

    private const val TAG = "AttachmentAnalyzer"
    private const val MAX_FILE_BYTES = 30L * 1024 * 1024
    private const val MAX_TEXT_CHARS = 30_000
    private const val MAX_PDF_PAGES = 6
    private const val MAX_IMAGE_SIDE = 2_000

    fun attachmentsDir(context: Context): File =
        File(context.filesDir, "attachments").apply { if (!exists()) mkdirs() }

    /** 把 content:// 复制到应用私有目录, 返回可持久化的附件元数据 */
    suspend fun import(context: Context, uri: Uri): Attachment? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            var name = "file"
            var size = 0L
            resolver.query(uri, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0 && c.moveToFirst()) name = c.getString(nameIdx) ?: name
                if (sizeIdx >= 0 && c.moveToFirst()) size = c.getLong(sizeIdx)
            }
            val mime = resolver.getType(uri) ?: guessMime(name)
            val id = UUID.randomUUID().toString().replace("-", "").take(16)
            val ext = name.substringAfterLast('.', "").take(12)
            val target = File(attachmentsDir(context), if (ext.isBlank()) id else "$id.$ext")
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext null
            if (target.length() > MAX_FILE_BYTES) {
                target.delete()
                return@withContext null
            }
            Attachment(
                id = id,
                name = name.take(120),
                mime = mime,
                size = if (size > 0) size else target.length(),
                path = target.absolutePath,
                kind = if (mime.startsWith("image/")) "image" else "file"
            )
        } catch (e: Exception) {
            Log.w(TAG, "import failed: ${e.message}")
            null
        }
    }

    /** 多个附件依次解析, 返回给 LLM 的隐藏上下文 */
    suspend fun analyzeAll(context: Context, attachments: List<Attachment>): String {
        if (attachments.isEmpty()) return ""
        val sb = StringBuilder()
        for (a in attachments) {
            if (sb.length >= MAX_TEXT_CHARS) break
            val text = analyze(context, a)
            sb.append("\n\n===== 附件: ").append(a.name).append(" (").append(a.mime)
                .append(", ").append(a.size).append(" bytes) =====\n")
            sb.append(text.take(MAX_TEXT_CHARS - sb.length))
        }
        return sb.toString().trim().take(MAX_TEXT_CHARS)
    }

    private suspend fun analyze(context: Context, a: Attachment): String = withContext(Dispatchers.IO) {
        try {
            val file = File(a.path)
            if (!file.exists()) return@withContext "附件文件已不存在"
            val ext = a.name.substringAfterLast('.', "").lowercase()
            when {
                a.mime.startsWith("image/") || ext in setOf("png", "jpg", "jpeg", "webp", "bmp") ->
                    ocrImage(context, file)
                a.mime.startsWith("text/") || ext in setOf("txt", "md", "json", "csv", "log", "xml", "html", "htm") ->
                    file.readText().take(MAX_TEXT_CHARS)
                ext == "pdf" -> pdfText(context, file)
                ext == "docx" -> docxText(file)
                ext == "xlsx" || ext == "xlsm" -> xlsxText(file)
                ext == "pptx" -> pptxText(file)
                else -> "暂不支持自动解析该格式 (${a.mime.ifBlank { ext }}), 只提供文件信息。"
            }
        } catch (e: Exception) {
            Log.w(TAG, "analyze ${a.name} failed: ${e.message}")
            "解析失败: ${e.message}"
        }
    }

    private suspend fun ocrImage(context: Context, file: File): String {
        val bmp = decodeSampled(file, MAX_IMAGE_SIDE) ?: return "图片解码失败"
        return try {
            val paddle = PaddleOcrEngine.recognize(context, bmp)
            val text = if (paddle.isNotEmpty()) {
                paddle.sortedWith(compareBy({ it.box.top }, { it.box.left }))
                    .joinToString("\n") { it.text }
            } else {
                OcrEngine.rowsOf(bmp, 2).joinToString("\n") { it.text }
            }
            if (text.isBlank()) "[图片 OCR] 未识别到文字" else "[图片 OCR]\n$text"
        } catch (e: Exception) {
            "[图片 OCR 失败] ${e.message}"
        } finally {
            bmp.recycle()
        }
    }

    private suspend fun pdfText(context: Context, file: File): String {
        val sb = StringBuilder()
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val pages = minOf(renderer.pageCount, MAX_PDF_PAGES)
                    for (i in 0 until pages) {
                        renderer.openPage(i).use { page ->
                            val w = 1400
                            val h = (w.toFloat() * page.height / page.width).toInt()
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val lines = PaddleOcrEngine.recognize(context, bmp)
                            sb.append("\n--- 第 ${i + 1} 页 ---\n")
                            if (lines.isEmpty()) {
                                sb.append("(本页未识别到文字)\n")
                            } else {
                                lines.sortedWith(compareBy({ it.box.top }, { it.box.left }))
                                    .forEach { sb.append(it.text).append('\n') }
                            }
                            bmp.recycle()
                        }
                        if (sb.length > MAX_TEXT_CHARS) break
                    }
                    if (renderer.pageCount > pages) {
                        sb.append("\n(仅解析前 $pages 页, 共 ${renderer.pageCount} 页)")
                    }
                }
            }
        } catch (e: Exception) {
            return "[PDF 解析失败] ${e.message}"
        }
        return "[PDF 文本]\n" + sb.toString().take(MAX_TEXT_CHARS)
    }

    private fun docxText(file: File): String {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return "DOCX 缺少 document.xml"
            val xml = zip.getInputStream(entry).bufferedReader().readText()
            val text = xml
                .replace(Regex("<w:p[ >]"), "\n")
                .replace(Regex("<w:tab[^>]*/>"), "\t")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
            return "[DOCX 文本]\n" + text.take(MAX_TEXT_CHARS)
        }
    }

    private fun xlsxText(file: File): String {
        ZipFile(file).use { zip ->
            val shared = StringBuilder()
            zip.getEntry("xl/sharedStrings.xml")?.let { e ->
                val xml = zip.getInputStream(e).bufferedReader().readText()
                Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(xml).forEach { m ->
                        shared.append(m.groupValues[1]).append('\n')
                    }
            }
            val sheets = StringBuilder()
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                .sortedBy { it.name }
                .take(3)
                .forEach { e ->
                    val xml = zip.getInputStream(e).bufferedReader().readText()
                    val texts = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(xml).map { it.groupValues[1] }.toList()
                    if (texts.isNotEmpty()) {
                        sheets.append("\n--- ${e.name} ---\n")
                        sheets.append(texts.joinToString(" | "))
                    }
                }
            val text = (shared.toString() + "\n" + sheets).trim()
            return "[XLSX 文本]\n" + text.take(MAX_TEXT_CHARS)
        }
    }

    private fun pptxText(file: File): String {
        ZipFile(file).use { zip ->
            val sb = StringBuilder()
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { it.name }
                .take(20)
                .forEach { e ->
                    val xml = zip.getInputStream(e).bufferedReader().readText()
                    val texts = Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(xml).map { it.groupValues[1] }.toList()
                    if (texts.isNotEmpty()) {
                        sb.append("\n--- ${e.name} ---\n")
                        sb.append(texts.joinToString("\n"))
                    }
                }
            return "[PPTX 文本]\n" + sb.toString().trim().take(MAX_TEXT_CHARS)
        }
    }

    private fun decodeSampled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "json" -> "application/json"
        "md" -> "text/markdown"
        "txt", "csv", "log", "xml", "html" -> "text/plain"
        else -> "application/octet-stream"
    }
}
