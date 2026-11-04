package com.catfee.rootauto.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.catfee.rootauto.root.DaemonClient
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

    data class Scan(
        val lines: List<OcrEngine.OcrLine>,
        val screens: Int,
        val approxHeight: Int
    )

    /**
     * 功能: 滚动扫描当前长页面
     * 参数: context; maxScreens 最多几屏; scrollPx 每屏滚动像素
     * 返回: 合并后的文字行 (y 为整页坐标)
     */
    suspend fun scan(
        context: Context,
        maxScreens: Int = 14,
        scrollPx: Int = 800
    ): Scan {
        scrollTop()
        val all = ArrayList<OcrEngine.OcrLine>()
        var prev: Bitmap? = null
        var offset = 0
        var screens = 0
        var lastHeight = 0
        var retriedNoScroll = false
        for (i in 0 until maxScreens.coerceIn(1, 24)) {
            val (bmp, err) = ScreenVision.captureBitmap()
            if (bmp == null) {
                Log.w(TAG, "截图失败: $err")
                break
            }
            lastHeight = bmp.height
            // 用相邻截图匹配真实滚动距离, 而不是假设固定 800px
            val shift = if (prev != null) estimateShift(prev!!, bmp, scrollPx) else 0
            if (i > 0 && shift <= 20) {
                bmp.recycle()
                if (!retriedNoScroll) {
                    // 可能是刚回顶部的惯性/下拉刷新, 再给一次慢速滚动机会
                    retriedNoScroll = true
                    DaemonClient.swipe(540, 1700, 540, 800, 900)
                    delay(1400)
                    continue
                }
                break
            }
            offset += shift
            val lines = try {
                PaddleOcrEngine.recognize(context, bmp)
            } catch (e: Exception) {
                emptyList()
            }
            for (l in lines) {
                // 只按通用的页面边界过滤固定工具栏 / 底部导航, 不针对任何具体 App 的文案
                if (l.box.top < 480 || l.box.bottom > bmp.height - 160) continue
                all.add(
                    OcrEngine.OcrLine(
                        l.text,
                        Rect(l.box.left, l.box.top + offset, l.box.right, l.box.bottom + offset)
                    )
                )
            }
            prev?.recycle()
            prev = bmp
            screens++
            DaemonClient.swipe(540, 1600, 540, (1600 - scrollPx).coerceAtLeast(200), 800)
            delay(1200)
        }
        prev?.recycle()
        // 去重: 同一文字在相邻 60px 内只保留一次
        val merged = ArrayList<OcrEngine.OcrLine>()
        all.sortedWith(compareBy({ it.box.top }, { it.box.left })).forEach { l ->
            val dup = merged.any {
                it.text == l.text && abs(it.box.centerY() - l.box.centerY()) < 60
            }
            if (!dup) merged.add(l)
        }
        Log.i(TAG, "滚动 ${screens} 屏, 原始 ${all.size} 行, 去重后 ${merged.size} 行")
        return Scan(merged, screens, offset + lastHeight)
    }

    /** 页面回顶部 */
    private suspend fun scrollTop() {
        repeat(12) {
            DaemonClient.swipe(540, 720, 540, 1900, 400)
            delay(350)
        }
        // 等惯性/下拉刷新完全停下
        delay(1500)
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
