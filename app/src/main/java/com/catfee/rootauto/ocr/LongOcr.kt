package com.catfee.rootauto.ocr

import com.catfee.rootauto.root.ScreenGeometry
import com.catfee.rootauto.root.InputDispatcher
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.catfee.rootauto.root.ScreenVision
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * LongOcr —— 长页面滚动拼接识别
 *
 * 从页面顶部开始逐屏截图 + OCR, 把每屏文字坐标加上累计滚动偏移,
 * 合并成一份"整页 OCR"; 重复行按文字 + y 距离去重。
 *
 * 效果等价于长截图, 但不用在内存里保存一张几万像素的巨图,
 * 也不会因为滚动位置不对只看到答案、看不到题目。
 *
 * 修改时间: 2026-11-04
 */
object LongOcr {

    private const val TAG = "LongOcr"

    /** OCR 行的坐标空间。扫描结果的 y 是 documentY，截图/点击使用 viewportY。 */
    enum class CoordinateSpace { VIEWPORT, DOCUMENT }

    data class Scan(
        val lines: List<OcrEngine.OcrLine>,
        val screens: Int,
        val approxHeight: Int,
        val coordinateSpace: CoordinateSpace = CoordinateSpace.DOCUMENT
    )

    data class ViewportGeometry(
        val width: Int,
        val height: Int,
        val documentY: Int,
        val viewportY: Int
    )

    /**
     * 功能: 滚动扫描当前长页面
     * 参数: context; maxScreens 最多几屏; scrollPx 每屏滚动像素
     * 返回: 合并后的文字行 (y 为整页坐标)
     */
    suspend fun scan(
        context: Context,
        maxScreens: Int = 14,
        scrollPx: Int = 800,
        awaitIfPaused: suspend () -> Unit = {}
    ): Scan {
        scrollTop()
        val all = ArrayList<OcrEngine.OcrLine>()
        var previous: Bitmap? = null
        var documentY = 0
        var screens = 0
        var lastHeight = 0
        var retriedNoScroll = false
        var lastViewport: Pair<Int, Int>? = null
        val limit = maxScreens.coerceIn(1, 24)
        try {
            for (i in 0 until limit) {
                awaitIfPaused()
                val (bmp, err) = ScreenVision.captureBitmap()
                if (bmp == null) {
                    Log.w(TAG, "截图失败: $err")
                    break
                }
                lastHeight = bmp.height
                lastViewport = bmp.width to bmp.height
                // documentY 是本屏顶部在整页中的位置；viewportY 只存在于当前截图。
                val shift = if (previous != null) estimateShift(previous!!, bmp, scrollPx) else 0
                if (i > 0 && shift <= 20) {
                    bmp.recycle()
                    if (!retriedNoScroll) {
                        retriedNoScroll = true
                        val (w, h) = ScreenGeometry.displaySize()
                        val y1 = (h * 0.78f).toInt()
                        val y2 = (h * 0.38f).toInt()
                        if (!InputDispatcher.swipe(w / 2, y1, w / 2, y2, 850)) break
                        delay(1100)
                        continue
                    }
                    break
                }
                documentY += shift
                val fast = try {
                    OcrEngine.recognize(bmp, 1)
                } catch (e: Exception) {
                    emptyList()
                }
                // ML Kit 质量门槛：不能只按行数判断，避免一屏只有导航/噪声时跳过 PP-OCR。
                val fastQuality = quality(fast, bmp.width, bmp.height)
                val lines = if (fastQuality >= MLKIT_QUALITY_THRESHOLD) {
                    fast
                } else {
                    try {
                        val paddle = PaddleOcrEngine.recognize(context, bmp)
                        if (quality(paddle, bmp.width, bmp.height) > fastQuality) paddle else fast
                    } catch (e: Exception) {
                        fast
                    }
                }
                for (line in lines) {
                    // 这里的 line.box 是 viewportY；写入 Scan 前转换为 documentY。
                    if (line.box.top < 480 || line.box.bottom > bmp.height - 160) continue
                    all.add(
                        OcrEngine.OcrLine(
                            line.text,
                            Rect(line.box.left, line.box.top + documentY, line.box.right, line.box.bottom + documentY)
                        )
                    )
                }
                previous?.recycle()
                previous = bmp
                screens++
                val (w, h) = ScreenGeometry.displaySize()
                val startY = (h * 0.78f).toInt()
                val endY = (h * (1f - scrollPx.toFloat() / h).coerceIn(0.18f, 0.62f)).toInt()
                if (!InputDispatcher.swipe(w / 2, startY, w / 2, endY, 500)) break
                delay(850)
            }
        } finally {
            previous?.recycle()
        }
        // 去重: 同一文字在相邻 60px 内只保留一次
        val merged = ArrayList<OcrEngine.OcrLine>()
        all.sortedWith(compareBy({ it.box.top }, { it.box.left })).forEach { line ->
            val dup = merged.any {
                it.text == line.text && abs(it.box.centerY() - line.box.centerY()) < 60
            }
            if (!dup) merged.add(line)
        }
        val approxHeight = if (lastViewport != null) documentY + lastViewport!!.second else documentY
        Log.i(TAG, "滚动 ${screens} 屏, 原始 ${all.size} 行, 去重后 ${merged.size} 行, documentY=$documentY")
        return Scan(merged, screens, approxHeight)
    }

    private const val MLKIT_QUALITY_THRESHOLD = 8

    /** ML Kit 质量门槛：有内容、覆盖主要视口、且不是只有极短噪声行。 */
    private fun quality(lines: List<OcrEngine.OcrLine>, width: Int, height: Int): Int {
        if (lines.isEmpty() || width <= 0 || height <= 0) return 0
        val usable = lines.filter {
            it.text.trim().length >= 2 && it.box.top >= 120 && it.box.bottom <= height - 80
        }
        if (usable.isEmpty()) return 0
        val yBands = usable.map { it.box.centerY() / max(1, height / 8) }.distinct().size
        val chars = usable.sumOf { it.text.trim().length }.coerceAtMost(80)
        return usable.size.coerceAtMost(8) + yBands.coerceAtMost(8) + (chars / 12).coerceAtMost(6)
    }

    /** 页面回顶部 */
    private suspend fun scrollTop() {
        repeat(10) {
            val (w, h) = ScreenGeometry.displaySize()
            val y1 = (h * 0.22f).toInt()
            val y2 = (h * 0.90f).toInt()
            if (!InputDispatcher.swipe(w / 2, y1, w / 2, y2, 300)) return
            delay(250)
        }
        // 等惯性/下拉刷新完全停下
        delay(700)
    }

    /** 两屏是否几乎相同 (页面到底, 没滚动) */
    private fun sameScreen(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        var sum = 0L
        var n = 0
        var y = 300
        while (y < a.height - 200) {
            var x = 80
            while (x < a.width - 80) {
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)
                sum += abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)).toLong()
                sum += abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)).toLong()
                sum += abs((pa and 0xFF) - (pb and 0xFF)).toLong()
                n += 3
                x += 40
            }
            y += 40
        }
        return n > 0 && sum.toDouble() / n < 8.0
    }

    /**
     * 功能: 估算相邻两屏的真实滚动距离
     * 原理: 上一屏 y=d 处的内容 == 当前屏 y=0 处; 在候选 d 上做像素差,
     *       取差值最小且足够可信的 d; 不可信时回退到标称滚动距离。
     */
    private fun estimateShift(prev: Bitmap, curr: Bitmap, nominal: Int): Int {
        // 完全相同 = 页面到底/滚不动
        if (sameScreen(prev, curr)) return 0
        // 只在标称距离附近搜索, 避免重复的「对/错」样式造成误匹配
        val minD = max(80, nominal - 300)
        val maxD = min(prev.height - 300, nominal + 300)
        if (maxD <= minD) return nominal
        val y0 = 300
        val window = 200
        val x0 = 80
        val x1 = min(prev.width, curr.width) - 80
        var bestD = nominal
        var bestScore = Double.MAX_VALUE
        var d = minD
        while (d <= maxD) {
            var sum = 0L
            var n = 0
            var y = 0
            while (y <= window) {
                val pa = d + y0 + y
                val pb = y0 + y
                if (pa >= prev.height || pb >= curr.height) break
                var x = x0
                while (x < x1) {
                    val a = prev.getPixel(x, pa)
                    val b = curr.getPixel(x, pb)
                    sum += abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)).toLong()
                    sum += abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)).toLong()
                    sum += abs((a and 0xFF) - (b and 0xFF)).toLong()
                    n += 3
                    x += 24
                }
                y += 10
            }
            if (n > 0) {
                val score = sum.toDouble() / n
                if (score < bestScore) {
                    bestScore = score
                    bestD = d
                }
            }
            d += 8
        }
        return if (bestScore < 30.0) bestD else nominal
    }
}
