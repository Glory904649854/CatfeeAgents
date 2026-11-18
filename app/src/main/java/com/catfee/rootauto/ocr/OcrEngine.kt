package com.catfee.rootauto.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * OcrEngine —— 离线中文 OCR
 *
 * 功能描述:
 *   把屏幕截图识别成「文字 + 屏幕坐标」的行集合。
 *   部分 H5 页面运行在跨域 iframe 里, 无障碍树只能看到一个
 *   空的 frame_container, 读不到题干和选项; 此时 OCR 是唯一能拿到题目原文与
 *   精确点击坐标的本地能力 (不依赖网络, 不依赖 GMS)。
 *
 * 参数说明:
 *   见 recognize() / rows()。
 *
 * 返回值说明:
 *   OcrLine 为单行文字与包围盒; OcrRow 为同一水平行上的多个片段合并结果。
 *
 * 使用场景:
 *   iframe 内文字识别、选项状态判断、按文字点击 iframe 内元素。
 *
 * 修改时间: 2026-11-03
 */
object OcrEngine {

    private const val TAG = "OcrEngine"

    /** 一行识别结果 (坐标是截图像素, 与屏幕坐标一致) */
    data class OcrLine(val text: String, val box: Rect)

    /** 同一水平行合并后的结果, parts 保留每个片段的精确坐标 */
    data class OcrRow(val text: String, val box: Rect, val parts: List<OcrLine>) {
        val cx: Int get() = box.centerX()
        val cy: Int get() = box.centerY()
    }

    /** 复用 recognizer, 避免每次识别都重新加载模型 */
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 功能: 识别一张 Bitmap 中的全部文字行
     * 参数: bitmap 原始截图 (调用方负责 recycle)
     * 返回: 按从上到下、从左到右排序的行
     * 使用场景: 逐屏读题
     */
    suspend fun recognize(bitmap: Bitmap, scale: Int = 1): List<OcrLine> = withContext(Dispatchers.IO) {
        val factor = scale.coerceIn(1, 3)
        val input = if (factor > 1) {
            Bitmap.createScaledBitmap(bitmap, bitmap.width * factor, bitmap.height * factor, true)
        } else {
            bitmap
        }
        try {
            val image = InputImage.fromBitmap(input, 0)
            // ML Kit 的 Task 不能阻塞主线程, 这里在 IO 线程 await
            val result = Tasks.await(recognizer.process(image), 30, TimeUnit.SECONDS)
            val lines = ArrayList<OcrLine>()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    val text = line.text.trim()
                    if (text.isNotEmpty()) {
                        // 放大后坐标要映射回原图
                        val mapped = if (factor > 1) Rect(
                            box.left / factor, box.top / factor,
                            box.right / factor, box.bottom / factor
                        ) else Rect(box)
                        lines.add(OcrLine(text, mapped))
                    }
                }
            }
            lines.sortWith(compareBy({ it.box.top }, { it.box.left }))
            lines
        } catch (e: Exception) {
            android.util.Log.e(TAG, "OCR 识别失败: ${e.message}")
            emptyList()
        } finally {
            if (input !== bitmap) input.recycle()
        }
    }

    /**
     * 功能: 把同一水平行上的文字片段合并成一行
     * 参数:
     *   lines        recognize() 的输出
     *   yTolerance   两段文字中心点 y 差在此范围内视为同一行
     * 返回: 合并后的行, 从上到下
     * 使用场景: 选项的字母圆圈与选项文字经常被识别成两个片段,
     *           合并后才能得到「A 对」这样的完整行, 同时保留字母圆圈的坐标。
     */
    fun rows(lines: List<OcrLine>, yTolerance: Int = 26): List<OcrRow> {
        val groups = ArrayList<MutableList<OcrLine>>()
        val questionStart = Regex("^\\d{1,3}\\s*[.、．]")
        for (line in lines) {
            val last = groups.lastOrNull()
            // 题号行不能和上一题最后一行合并, 否则行首不再是题号, 整题会被漏掉
            val startsQuestion = questionStart.containsMatchIn(line.text.trim())
            if (!startsQuestion && last != null &&
                kotlin.math.abs(last.last().box.centerY() - line.box.centerY()) <= yTolerance
            ) {
                last.add(line)
            } else {
                groups.add(mutableListOf(line))
            }
        }
        return groups.map { parts ->
            val sorted = parts.sortedBy { it.box.left }
            val box = Rect(sorted.first().box)
            sorted.drop(1).forEach { box.union(it.box) }
            OcrRow(sorted.joinToString(" ") { it.text }, box, sorted)
        }
    }

    /**
     * 功能: 截屏 + OCR 一步完成
     * 参数: bitmap 由 ScreenVision.captureBitmap() 提供
     * 返回: 合并后的行
     * 使用场景: 扫描列表项状态、答题前读题
     */
    suspend fun rowsOf(bitmap: Bitmap, scale: Int = 1): List<OcrRow> = rows(recognize(bitmap, scale))

    /**
     * 功能: PP-OCR + ML Kit 合并识别
     * 说明: PP-OCR 对浅色小题号更敏感, ML Kit 对选项字母/短文本更全;
     *       两者结果合并后按行分组, 由解析器按题号/选项字母去重。
     */
    suspend fun bestRows(context: Context, bitmap: Bitmap): List<OcrRow> = coroutineScope {
        // Paddle 和 ML Kit 互不依赖, 并行跑, 把两路合并的墙钟耗时压到约一半。
        val paddle = async(Dispatchers.Default) {
            try {
                PaddleOcrEngine.recognize(context, bitmap)
            } catch (e: Exception) {
                emptyList()
            }
        }
        val ml = async(Dispatchers.Default) {
            try {
                recognize(bitmap, 2)
            } catch (e: Exception) {
                emptyList()
            }
        }
        // 两个引擎各自有序, 合并后必须重排, 否则 rows() 会把不同 y 的行错并成一行
        val all = (paddle.await() + ml.await())
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
        if (all.isEmpty()) emptyList() else rows(all)
    }

    /**
     * 功能: 在 OCR 结果中按关键字查找一行
     * 参数: rows 行集合; keyword 关键字; exact 是否完全相等
     * 返回: 命中的第一行, 未命中返回 null
     * 使用场景: 点击 iframe 内的「由此作答」「提交」等按钮
     */
    fun findRow(rows: List<OcrRow>, keyword: String, exact: Boolean = false): OcrRow? {
        return rows.firstOrNull {
            if (exact) it.text.trim() == keyword else it.text.contains(keyword)
        }
    }
}
