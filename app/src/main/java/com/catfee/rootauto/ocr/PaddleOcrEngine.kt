package com.catfee.rootauto.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PaddleOcrEngine —— 本地 PP-OCRv5 mobile 推理
 *
 * 检测: ch_PP-OCRv5_det_mobile.onnx (DB)
 * 识别: ch_PP-OCRv5_rec_mobile.onnx (CTC, 字典在 ONNX metadata 里)
 *
 * 为什么不用 ML Kit:
 *   ML Kit 对浅灰小题号/小字容易整行漏检; PP-OCR 的检测对小字更敏感。
 *   模型和 ONNX Runtime 都在本地, 不联网、不上传截图。
 *
 * 参数:
 *   det 输入按最长边 1600 缩放; 识别高度 48, 宽度按比例动态。
 *
 * 修改时间: 2026-11-04
 */
object PaddleOcrEngine {

    private const val TAG = "PaddleOcr"
    private const val DET_ASSET = "ocr/ch_PP-OCRv5_det_mobile.onnx"
    private const val REC_ASSET = "ocr/ch_PP-OCRv5_rec_mobile.onnx"
    private const val DET_MAX_SIDE = 1600
    private const val DET_THRESH = 0.3f
    private const val BOX_THRESH = 0.5f
    private const val UNCLIP_RATIO = 1.6f
    private const val REC_HEIGHT = 48
    private const val REC_MAX_WIDTH = 960

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    @Volatile
    private var detSession: OrtSession? = null

    @Volatile
    private var recSession: OrtSession? = null

    @Volatile
    private var recChars: List<String> = emptyList()

    fun isReady(): Boolean = detSession != null && recSession != null

    private fun ensure(context: Context) {
        if (isReady()) return
        synchronized(this) {
            if (detSession == null) {
                detSession = env.createSession(readAsset(context, DET_ASSET), sessionOptions())
                Log.i(TAG, "det 模型已加载")
            }
            if (recSession == null) {
                val s = env.createSession(readAsset(context, REC_ASSET), sessionOptions())
                val meta = s.metadata.customMetadata ?: emptyMap()
                val raw = meta["character"].orEmpty()
                val chars = raw.split("\n").toMutableList()
                chars.add(" ")
                recChars = chars
                recSession = s
                Log.i(TAG, "rec 模型已加载, 字典 ${chars.size} 字")
            }
        }
    }

    private fun sessionOptions() = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(4)
        setInterOpNumThreads(1)
    }

    private fun readAsset(context: Context, path: String): ByteArray =
        context.assets.open(path).use { it.readBytes() }

    /**
     * 功能: 识别整张截图
     * 返回: 文字行 + 原图坐标
     */
    suspend fun recognize(context: Context, bitmap: Bitmap): List<OcrEngine.OcrLine> =
        withContext(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            try {
                ensure(context)
                val boxes = detect(bitmap)
                if (boxes.isEmpty()) return@withContext emptyList()
                val lines = recognizeBoxes(bitmap, boxes)
                    .filter { it.text.isNotBlank() }
                    .sortedWith(compareBy({ it.box.top }, { it.box.left }))
                Log.i(
                    TAG,
                    "PP-OCRv5 完成: ${lines.size} 行, ${System.currentTimeMillis() - started}ms"
                )
                lines
            } catch (e: Exception) {
                Log.e(TAG, "PP-OCR 识别失败: ${e.message}")
                emptyList()
            }
        }

    // ---------------- 检测 ----------------

    private fun detect(bitmap: Bitmap): List<Rect> {
        val session = detSession ?: return emptyList()
        val origW = bitmap.width
        val origH = bitmap.height
        val scale = min(1f, DET_MAX_SIDE.toFloat() / max(origW, origH))
        var detW = ((origW * scale).toInt() / 32) * 32
        var detH = ((origH * scale).toInt() / 32) * 32
        detW = max(32, detW)
        detH = max(32, detH)
        val scaled = Bitmap.createScaledBitmap(bitmap, detW, detH, true)
        try {
            val pixels = IntArray(detW * detH)
            scaled.getPixels(pixels, 0, detW, 0, 0, detW, detH)
            val plane = detW * detH
            val data = FloatArray(3 * plane)
            val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
            val std = floatArrayOf(0.229f, 0.224f, 0.225f)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                data[i] = (r - mean[0]) / std[0]
                data[plane + i] = (g - mean[1]) / std[1]
                data[2 * plane + i] = (b - mean[2]) / std[2]
            }
            val inputName = session.inputNames.first()
            val tensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(data), longArrayOf(1, 3, detH.toLong(), detW.toLong())
            )
            val result = session.run(mapOf(inputName to tensor))
            val out = result.get(0) as OnnxTensor
            val shape = out.info.shape
            val outH = shape[2].toInt()
            val outW = shape[3].toInt()
            val prob = FloatArray(outH * outW)
            out.floatBuffer.get(prob)
            result.close()
            tensor.close()

            val boxes = boxesFromProb(prob, outW, outH)
            val sx = origW.toFloat() / detW
            val sy = origH.toFloat() / detH
            return boxes.mapNotNull { r ->
                val left = (r[0] * sx).toInt().coerceIn(0, origW - 1)
                val top = (r[1] * sy).toInt().coerceIn(0, origH - 1)
                val right = (r[2] * sx).toInt().coerceIn(left + 1, origW)
                val bottom = (r[3] * sy).toInt().coerceIn(top + 1, origH)
                if (right - left < 6 || bottom - top < 6) null
                else Rect(left, top, right, bottom)
            }.let { mergeBoxes(it) }
        } finally {
            scaled.recycle()
        }
    }

    /** DB 概率图 -> 连通域框 (水平文本行) */
    private fun boxesFromProb(prob: FloatArray, w: Int, h: Int): List<FloatArray> {
        val mask = BooleanArray(prob.size)
        for (i in prob.indices) mask[i] = prob[i] > DET_THRESH
        val visited = BooleanArray(prob.size)
        val queue = IntArray(prob.size)
        val boxes = ArrayList<FloatArray>()
        for (start in prob.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = Int.MAX_VALUE
            var maxX = -1
            var minY = Int.MAX_VALUE
            var maxY = -1
            var count = 0
            var sum = 0f
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                count++
                sum += prob[p]
                if (x > 0 && !visited[p - 1] && mask[p - 1]) {
                    visited[p - 1] = true; queue[tail++] = p - 1
                }
                if (x + 1 < w && !visited[p + 1] && mask[p + 1]) {
                    visited[p + 1] = true; queue[tail++] = p + 1
                }
                if (y > 0 && !visited[p - w] && mask[p - w]) {
                    visited[p - w] = true; queue[tail++] = p - w
                }
                if (y + 1 < h && !visited[p + w] && mask[p + w]) {
                    visited[p + w] = true; queue[tail++] = p + w
                }
            }
            if (count < 4) continue
            if (sum / count < BOX_THRESH) continue
            val bw = (maxX - minX + 1).toFloat()
            val bh = (maxY - minY + 1).toFloat()
            if (bw < 4 || bh < 4) continue
            val distance = bw * bh * UNCLIP_RATIO / (2f * (bw + bh))
            boxes.add(
                floatArrayOf(
                    (minX - distance).coerceAtLeast(0f),
                    (minY - distance).coerceAtLeast(0f),
                    (maxX + distance).coerceAtMost(w - 1f),
                    (maxY + distance).coerceAtMost(h - 1f)
                )
            )
        }
        return boxes
    }

    /** 合并同一行被切开的相邻框 */
    private fun mergeBoxes(input: List<Rect>): List<Rect> {
        val list = input.sortedWith(compareBy({ it.top }, { it.left })).toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i]
                    val b = list[j]
                    val overlapY = min(a.bottom, b.bottom) - max(a.top, b.top)
                    val minH = min(a.height(), b.height())
                    val gap = if (b.left > a.right) b.left - a.right else a.left - b.right
                    if (overlapY > minH / 2 && gap in -8..18) {
                        list[i] = Rect(
                            min(a.left, b.left), min(a.top, b.top),
                            max(a.right, b.right), max(a.bottom, b.bottom)
                        )
                        list.removeAt(j)
                        changed = true
                        break@outer
                    }
                }
            }
        }
        return list
    }

    // ---------------- 识别 ----------------

    private fun recognizeBoxes(bitmap: Bitmap, boxes: List<Rect>): List<OcrEngine.OcrLine> {
        val session = recSession ?: return emptyList()
        val out = ArrayList<OcrEngine.OcrLine>()
        val batchSize = 6
        boxes.chunked(batchSize).forEach { batch ->
            val crops = ArrayList<Bitmap>()
            val widths = ArrayList<Int>()
            val usedBoxes = ArrayList<Rect>()
            for (box in batch) {
                val w = box.width()
                val h = box.height()
                if (w < 6 || h < 6) continue
                val crop = try {
                    Bitmap.createBitmap(bitmap, box.left, box.top, w, h)
                } catch (e: Exception) {
                    continue
                }
                val targetW = ((REC_HEIGHT.toFloat() * w / h).roundToInt())
                    .coerceIn(16, REC_MAX_WIDTH)
                val resized = Bitmap.createScaledBitmap(crop, targetW, REC_HEIGHT, true)
                if (resized !== crop) crop.recycle()
                crops.add(resized)
                widths.add(targetW)
                usedBoxes.add(box)
            }
            if (crops.isEmpty()) return@forEach
            val maxW = widths.maxOrNull() ?: REC_MAX_WIDTH
            val n = crops.size
            val data = FloatArray(n * 3 * REC_HEIGHT * maxW)
            for (idx in crops.indices) {
                val bmp = crops[idx]
                val cw = widths[idx]
                val pixels = IntArray(cw * REC_HEIGHT)
                bmp.getPixels(pixels, 0, cw, 0, 0, cw, REC_HEIGHT)
                val plane = REC_HEIGHT * maxW
                val base = idx * 3 * plane
                for (y in 0 until REC_HEIGHT) {
                    for (x in 0 until cw) {
                        val p = pixels[y * cw + x]
                        val r = ((p shr 16) and 0xFF) / 127.5f - 1f
                        val g = ((p shr 8) and 0xFF) / 127.5f - 1f
                        val b = (p and 0xFF) / 127.5f - 1f
                        val offset = base + y * maxW + x
                        data[offset] = r
                        data[offset + plane] = g
                        data[offset + 2 * plane] = b
                    }
                }
                bmp.recycle()
            }
            try {
                val inputName = session.inputNames.first()
                val tensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(data),
                    longArrayOf(n.toLong(), 3, REC_HEIGHT.toLong(), maxW.toLong())
                )
                val result = session.run(mapOf(inputName to tensor))
                val outTensor = result.get(0) as OnnxTensor
                val shape = outTensor.info.shape
                val steps = shape[1].toInt()
                val classes = shape[2].toInt()
                val all = FloatArray(n * steps * classes)
                outTensor.floatBuffer.get(all)
                result.close()
                tensor.close()
                for (idx in crops.indices) {
                    val decoded = ctcDecode(all, idx * steps * classes, steps, classes)
                    if (decoded.first.isBlank()) continue
                    out.add(OcrEngine.OcrLine(decoded.first, Rect(usedBoxes[idx])))
                }
            } catch (e: Exception) {
                Log.e(TAG, "rec 推理失败: ${e.message}")
            } finally {
                crops.forEach { if (!it.isRecycled) it.recycle() }
            }
        }
        return out
    }

    private fun ctcDecode(data: FloatArray, offset: Int, steps: Int, classes: Int): Pair<String, Float> {
        val sb = StringBuilder()
        var prev = 0
        var confSum = 0f
        var confCount = 0
        for (t in 0 until steps) {
            var bestIdx = 0
            var bestVal = -Float.MAX_VALUE
            val base = offset + t * classes
            for (c in 0 until classes) {
                val v = data[base + c]
                if (v > bestVal) {
                    bestVal = v
                    bestIdx = c
                }
            }
            if (bestIdx != 0 && bestIdx != prev) {
                val ci = bestIdx - 1
                if (ci in recChars.indices) sb.append(recChars[ci])
            }
            if (bestIdx != 0) {
                confSum += bestVal
                confCount++
            }
            prev = bestIdx
        }
        return sb.toString() to if (confCount == 0) 0f else confSum / confCount
    }
}
