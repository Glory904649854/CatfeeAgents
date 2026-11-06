package com.catfee.rootauto.agent

import android.graphics.Rect
import com.catfee.rootauto.a11y.UiObserverService
import com.catfee.rootauto.ocr.OcrEngine
import com.catfee.rootauto.root.DaemonClient
import com.catfee.rootauto.root.InputDispatcher
import com.catfee.rootauto.root.ScreenVision
import kotlinx.coroutines.delay

/**
 * MediaState —— 通用媒体播放状态
 *
 * 目标:
 *   视频/音频/课件播放页不靠猜。读取页面上的当前时间、总时长、倍速和播放状态,
 *   用「总时长 - 当前时间」计算还要等多久; 不用写死某个 App 的播放器控件。
 *
 * 数据来源:
 *   1. 无障碍树里的时间文本 (如 "00:15" / "01:10", "00:15 / 01:10");
 *   2. 无障碍读不到时, 用当前屏幕 OCR 兜底;
 *   3. 倍速文本支持 2x / 2.0x / 2倍 / 倍速 等常见写法。
 *
 * 注意:
 *   - 不依赖固定 resource-id, 只按文本/控件类型/位置关系识别;
 *   - 悬浮状态条的时间 (Step 12 · 操作中 · 03:42) 会被过滤, 不当作视频时间;
 *   - 读不到倍速时 waitMedia 按 1.0x 计算, 宁可多等, 不会提前离开。
 */
object MediaState {

    data class State(
        val hasMedia: Boolean,
        val playing: Boolean?,
        val positionSec: Int?,
        val durationSec: Int?,
        val speed: Double?,
        val speedText: String,
        val source: String,
        val details: String,
        val surface: Rect?
    )

    private data class TimeHit(
        val sec: Int,
        val text: String,
        val x: Int,
        val y: Int,
        val id: String
    )

    private data class SpeedHit(
        val value: Double?,
        val text: String,
        val x: Int,
        val y: Int,
        val selected: Boolean,
        val control: Boolean
    )

    private val TIME_RE = Regex("""(?<!\d)(\d{1,2}):(\d{2})(?::(\d{2}))?(?!\d)""")
    private val SPEED_RE = Regex("""(?i)(\d+(?:\.\d+)?)\s*(?:x|×|倍)""")

    /** 日期/日期时间, 这些不能当成视频时长 */
    private val DATE_RE = Regex("""\d{4}\s*[/-]\s*\d{1,2}\s*[/-]\s*\d{1,2}""")

    // 播放器控制条会自动隐藏; 缓存最近一次读到的总时长/当前时间,
    // 之后只剩一个小计时器时也能算出剩余时间, 不再点画面 (点画面可能暂停视频)。
    @Volatile
    private var lastDurationSec: Int? = null

    @Volatile
    private var lastPositionSec: Int? = null

    @Volatile
    private var lastSpeedX: Int? = null

    @Volatile
    private var lastSpeedY: Int? = null

    /** 当前屏幕的媒体状态; 只读, 不点击画面 */
    suspend fun read(): State = readOnce()

    /** 读取一次, 不做任何点击 */
    private suspend fun readOnce(): State {
        try {
            // 先刷新横竖屏; 播放器页的坐标空间必须和 tap 一致
            DaemonClient.screenSize()
        } catch (_: Exception) {
        }

        val tree = UiObserverService.instance?.dumpTree(1000).orEmpty()
        var source = "无障碍"
        var surface = findSurface(tree)
        var timeHits = collectTimeHits(tree)
        var speedHits = collectSpeedHits(tree)
        var playing = detectPlaying(tree)
        var pair = pickTimePair(timeHits, surface)

        // 无障碍只读到当前时间、没有总时长时也用 OCR 补一次
        if (pair == null) {
            val (ocrTimes, ocrSpeeds) = readOcr()
            if (ocrTimes.isNotEmpty()) {
                timeHits = timeHits + ocrTimes
                source = if (tree.isEmpty()) "OCR" else "无障碍+OCR"
                pair = pickTimePair(timeHits, surface)
            }
            if (speedHits.isEmpty()) speedHits = ocrSpeeds
        }

        var position = pair?.first
        var duration = pair?.second
        if (pair != null) {
            lastPositionSec = position
            lastDurationSec = duration
        } else {
            // 控制条隐藏时通常只能读到当前时间 (或画面上的小计时器);
            // 用缓存的总时长补全, 不点击画面, 避免把播放暂停。
            val cachedDur = lastDurationSec
            if (cachedDur != null && timeHits.isNotEmpty()) {
                val all = timeHits.map { it.sec }
                val current = all.filter { it in 1 until cachedDur }.maxOrNull()
                    ?: if (all.any { it >= cachedDur }) cachedDur else null
                val previous = lastPositionSec
                // 新视频一般从 00:xx 开始; 当前时间突然远小于上一位置时清掉旧时长,
                // 避免把上一个视频的 17:44 套到新视频上, 误判“已经播完”。
                if (current != null && previous != null && current + 30 < previous) {
                    lastDurationSec = null
                    lastPositionSec = null
                } else if (current != null && current > cachedDur + 30) {
                    lastDurationSec = null
                    lastPositionSec = null
                } else {
                    position = current ?: previous
                    duration = cachedDur
                    if (position != null) lastPositionSec = position
                }
            }
        }
        val currentSpeed = pickCurrentSpeed(speedHits)
        val speedText = speedHits.map { it.text }.distinct().joinToString(", ")

        val hasMedia = surface != null || speedHits.isNotEmpty() || playing != null ||
            timeHits.size >= 2 ||
            timeHits.any { it.id.contains("time", true) || it.id.contains("duration", true) }

        if (!hasMedia) {
            lastPositionSec = null
            lastDurationSec = null
            lastSpeedX = null
            lastSpeedY = null
        }

        return State(
            hasMedia = hasMedia,
            playing = playing,
            positionSec = position,
            durationSec = duration,
            speed = currentSpeed,
            speedText = speedText,
            source = source,
            details = buildDetails(
                playing, position, duration, currentSpeed, speedText,
                timeHits, speedHits, surface, source
            ),
            surface = surface
        )
    }

    /** 给模型的紧凑描述 */
    suspend fun describe(): String = read().details

    /**
     * 按媒体进度等待。
     *
     * @param targetRatio 目标进度, 默认 1.0 (等到结束); 页面要求 90% 时传 0.9
     * @param maxWaitMs   单次最长等待, 默认 5 分钟; 没到目标可再次调用
     */
    suspend fun waitMedia(targetRatio: Double = 1.0, maxWaitMs: Long = 300_000L): String {
        val before = read()
        if (!before.hasMedia) {
            return "未检测到媒体播放信息; 先 observe/media_state 确认当前确实是播放页。\n" +
                before.details
        }
        val pos = before.positionSec
        val dur = before.durationSec
        if (pos == null || dur == null || dur <= 0) {
            // 时长未知时只做一次短等待并重新采样, 不盲目等待
            val ms = maxWaitMs.coerceIn(1_000L, 300_000L).coerceAtMost(30_000L)
            waitWithProgress(ms)
            val after = read()
            return "未读到完整时长, 已等待 ${ms / 1000}s, 请根据新进度判断。\n" + after.details
        }

        val target = (dur * targetRatio.coerceIn(0.1, 1.0)).toInt().coerceAtLeast(1)
        if (pos >= target) {
            return "已到达目标进度: ${fmt(pos)} / ${fmt(dur)} (目标 ${targetRatio * 100}%)。\n" +
                before.details
        }

        // 页面只显示“倍速”控件、不显示当前倍速时, 用 2~3 秒的实际进度差测速,
        // 这样 2x/1.5x 都能算准, 不依赖某个 App 的控件文案。
        var speed = before.speed
        var measured = false
        if (speed == null) {
            val probe = measureSpeed(before)
            if (probe == null) {
                return "播放进度没有前进, 可能已暂停/缓冲/未开始; 先点播放按钮或检查播放器。\n" +
                    before.details
            }
            speed = probe
            measured = true
        }

        var usedSpeed = (speed ?: 1.0).coerceAtLeast(0.1)
        val budget = maxWaitMs.coerceIn(1_000L, 300_000L)
        val started = System.currentTimeMillis()
        var waited = 0L
        var current: Int = pos
        var stalled = 0

        // 分 15 秒一段等待: 每段都重新读进度。
        // 连续两段没有前进才认为暂停/缓冲, 避免一次旧界面读数误判。
        while (waited < budget) {
            val chunk = minOf(15_000L, budget - waited)
            waitWithProgress(chunk)
            waited += chunk
            val st = read()
            val np = st.positionSec
            if (np == null) {
                return "等待 ${waited / 1000}s 后读不到播放时间; " +
                    "页面可能已结束、跳转或被暂停。\n" + st.details
            }
            if (np <= current) {
                stalled++
                if (stalled >= 2) {
                    return "已等待 ${waited / 1000}s, 播放进度停在 ${fmt(np)}; " +
                        "可能已暂停/缓冲/播放器失去焦点。先检查播放按钮, 不要退出播放页。\n" + st.details
                }
            } else {
                stalled = 0
                current = np
            }
            val goal = ((st.durationSec ?: dur) * targetRatio.coerceIn(0.1, 1.0))
                .toInt().coerceAtLeast(1)
            if (current >= goal) {
                return buildString {
                    append("已到达目标: ").append(fmt(current)).append(" / ")
                        .append(fmt(st.durationSec ?: dur))
                    append("; 已等待 ").append(waited / 1000).append("s")
                    if (measured) append("; 实测倍速 ").append(fmtSpeed(usedSpeed))
                    append('\n').append(st.details)
                }
            }
            val elapsed = ((System.currentTimeMillis() - started) / 1000.0).coerceAtLeast(1.0)
            val observed = (current - pos) / elapsed
            if (observed in 0.2..4.0) usedSpeed = observed
        }

        val st = read()
        val np = st.positionSec ?: current
        val goal = ((st.durationSec ?: dur) * targetRatio.coerceIn(0.1, 1.0)).toInt()
        val left = ((goal - np) / usedSpeed).toLong().coerceAtLeast(0L)
        return buildString {
            append("已等待 ").append(waited / 1000).append("s")
            if (measured) append("; 实测倍速 ").append(fmtSpeed(usedSpeed))
            append("; 播放进度 ").append(fmt(pos)).append(" -> ").append(fmt(np))
            if (left > 0) append("; 还需约 ").append(left).append("s, 可再次 wait_media")
            else append("; 已接近目标")
            append('\n').append(st.details)
        }
    }

    /** 供工具层格式化 */
    fun format(state: State): String = state.details

    /**
     * 通用倍速切换:
     *   1. 先调用 read() 让隐藏的控制条显示出来;
     *   2. 点页面上的“倍速/速度”控件;
     *   3. 在新出现的选项里按数值找目标 (2x / 2.0x / 2倍 都识别);
     *   4. 点目标选项并返回结果。
     * 不依赖具体 App 的控件 id。
     */
    suspend fun setSpeed(target: Double): String {
        val want = target.coerceIn(0.5, 4.0)
        val first = read()
        if (!first.hasMedia) {
            return "未检测到媒体播放信息; 先 observe/media_state 确认当前是播放页。\n" + first.details
        }
        if (first.speed != null && kotlin.math.abs(first.speed - want) < 0.05) {
            return "当前已是 ${fmtSpeed(want)}。\n" + first.details
        }

        var tree = UiObserverService.instance?.dumpTree(1000).orEmpty()
        var hits = collectSpeedHits(tree)
        var control = hits.firstOrNull { it.control && it.value == null }
            ?: hits.firstOrNull { it.control }
        var options = hits.filter { it.value != null }

        // 控制条已隐藏: 用上一次读到的倍速控件坐标直接点, 不点画面中心 (避免暂停)
        if (control == null && options.isEmpty()) {
            val sx = lastSpeedX
            val sy = lastSpeedY
            if (sx == null || sy == null) {
                return "播放器控制条已隐藏, 读不到倍速控件。不要退出/重进; " +
                    "保持播放并用 wait_media 按当前倍速等待。"
            }
            InputDispatcher.tap(sx, sy)
            delay(600)
            tree = UiObserverService.instance?.dumpTree(1000).orEmpty()
            hits = collectSpeedHits(tree)
            control = hits.firstOrNull { it.control && it.value == null }
                ?: hits.firstOrNull { it.control }
            options = hits.filter { it.value != null }
        }

        if (options.isEmpty() && control != null) {
            // 打开倍速菜单
            InputDispatcher.tap(control.x, control.y)
            delay(700)
            tree = UiObserverService.instance?.dumpTree(1000).orEmpty()
            hits = collectSpeedHits(tree)
            options = hits.filter { it.value != null }
        }

        if (options.isEmpty()) {
            return "没有找到倍速选项; 当前可见: " +
                (hits.map { it.text }.distinct().joinToString(", ").ifBlank { "无" }) +
                "。不要退出播放页; 继续用 wait_media 按当前实测倍速等待。"
        }

        val option = options.firstOrNull { it.value != null && kotlin.math.abs(it.value - want) < 0.01 }
            ?: if (want == 1.0) options.firstOrNull {
                it.text.contains("正常") || it.text.contains("原速") || it.text == "1x"
            } else null

        if (option == null) {
            return "未找到 ${fmtSpeed(want)} 选项; 当前可选: " +
                options.joinToString(", ") { it.text } +
                "。不要退出播放页; 继续用 wait_media 按当前实测倍速等待。"
        }

        // 倍速选项优先用无障碍点击父容器: 自定义播放器的坐标映射可能偏移,
        // ACTION_CLICK 不受横屏/缩放影响。
        val svc = UiObserverService.instance
        val clicked = svc?.clickParentByText(option.text) == true ||
            svc?.clickByText(option.text, exact = true) == true
        if (!clicked) InputDispatcher.tap(option.x, option.y)
        delay(600)

        // 选完后菜单通常还开着, 点菜单左侧的视频空白处关闭它;
        // 菜单会消费这次外部点击, 一般不会触发播放/暂停。
        val afterTree = UiObserverService.instance?.dumpTree(1000).orEmpty()
        val stillOptions = collectSpeedHits(afterTree).filter { it.value != null }
        if (stillOptions.isNotEmpty()) {
            val surface = first.surface
            val x = surface?.let { it.left + it.width() / 3 } ?: 800
            val y = surface?.centerY() ?: 540
            InputDispatcher.tap(x, y)
            delay(300)
        }
        return "已点击 ${option.text} (目标 ${fmtSpeed(want)}); " +
            "请再调用 media_state 确认倍速, 然后用 wait_media 等待进度。\n" +
            "可见倍速: " + options.joinToString(", ") { it.text }
    }

    // ---------------- 识别 ----------------

    private fun collectTimeHits(tree: List<UiObserverService.TreeNode>): List<TimeHit> {
        val out = ArrayList<TimeHit>()
        for (n in tree) {
            val label = (n.text + " " + n.desc).trim()
            if (!isPlainTimeLabel(label) || isOverlay(label)) continue
            val values = parseTimes(label)
            if (values.isEmpty()) continue
            values.forEach { sec ->
                out.add(TimeHit(sec, label, n.bounds.centerX(), n.bounds.centerY(), n.resourceId))
            }
        }
        return out
    }

    private fun collectSpeedHits(tree: List<UiObserverService.TreeNode>): List<SpeedHit> {
        val out = ArrayList<SpeedHit>()
        for (n in tree) {
            val label = (n.text + " " + n.desc).trim()
            if (label.isBlank() || label.length > 30 || isOverlay(label)) continue
            val m = SPEED_RE.find(label)
            val control = label.contains("倍速") || label.contains("speed", true) ||
                n.resourceId.contains("speed", true)
            if (m == null && !control) continue
            val sx = n.bounds.centerX()
            val sy = n.bounds.centerY()
            if (control) {
                lastSpeedX = sx
                lastSpeedY = sy
            }
            out.add(
                SpeedHit(
                    value = m?.groupValues?.getOrNull(1)?.toDoubleOrNull(),
                    text = label,
                    x = sx,
                    y = sy,
                    selected = n.selected || n.checked,
                    control = control
                )
            )
        }
        return out
    }

    private fun detectPlaying(tree: List<UiObserverService.TreeNode>): Boolean? {
        for (n in tree) {
            val label = (n.text + " " + n.desc).trim()
            if (label.isBlank()) continue
            // “暂停/播放”经常是按钮的动作描述, 不代表当前状态 (暂停时仍显示“暂停”);
            // 只认明确的状态文案, 其余交给 waitMedia 用播放进度判断。
            if (label == "播放中" || label.equals("playing", true)) return true
            if (label == "已暂停" || label == "继续播放" || label.equals("paused", true)) return false
        }
        return null
    }

    private fun findSurface(tree: List<UiObserverService.TreeNode>): Rect? {
        return tree.asSequence()
            .filter { n ->
                val s = (n.cls + " " + n.resourceId).lowercase()
                (s.contains("surfaceview") || s.contains("textureview") ||
                    s.contains("videoview") || s.contains("cvplayer") ||
                    s.contains("player")) &&
                    n.bounds.width() > 200 && n.bounds.height() > 120
            }
            .maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }
            ?.bounds
    }

    private suspend fun readOcr(): Pair<List<TimeHit>, List<SpeedHit>> {
        val (bmp, _) = ScreenVision.captureBitmap()
        if (bmp == null) return emptyList<TimeHit>() to emptyList()
        return try {
            val rows = OcrEngine.rowsOf(bmp)
            val times = ArrayList<TimeHit>()
            val speeds = ArrayList<SpeedHit>()
            val imgW = bmp.width
            val imgH = bmp.height
            rows.forEach { r ->
                val label = r.text.trim()
                if (label.isBlank() || label.length > 30 || isOverlay(label)) return@forEach
                val cx = r.box.centerX()
                val cy = r.box.centerY()
                if (isPlainTimeLabel(label)) {
                    val values = parseTimes(label)
                    // 只认可画面底部的计时器: 当前时间在左/右边缘, 或一行里同时有当前/总时长。
                    // 视频画面里的时间戳/日期 (如 "2024/7/24 17:19") 不能当时长。
                    val bottom = cy > imgH * 0.60
                    val atEdge = cx < imgW * 0.30 || cx > imgW * 0.70
                    if (bottom && (atEdge || values.size >= 2)) {
                        values.forEach { sec ->
                            times.add(TimeHit(sec, label, cx, cy, ""))
                        }
                    }
                }
                val m = SPEED_RE.find(label)
                val control = label.contains("倍速") || label.contains("speed", true)
                if (m != null || control) {
                    speeds.add(
                        SpeedHit(
                            m?.groupValues?.getOrNull(1)?.toDoubleOrNull(),
                            label, r.box.centerX(), r.box.centerY(), false, control
                        )
                    )
                }
            }
            times to speeds
        } catch (_: Exception) {
            emptyList<TimeHit>() to emptyList()
        } finally {
            bmp.recycle()
        }
    }

    /**
     * 只接受“纯时间文本”或“时间/时间”这种控件文本。
     * 视频画面里的 "2024/7/24 17:19"、题目里的 "01:04"、WPS 时间戳等都不算。
     */
    private fun isPlainTimeLabel(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 24) return false
        if (DATE_RE.containsMatchIn(t)) return false
        val rest = TIME_RE.replace(t, "")
            .replace(Regex("""[\s/|:：\-—~～]+"""), "")
        return rest.isEmpty()
    }

    private fun parseTimes(text: String): List<Int> {
        return TIME_RE.findAll(text).mapNotNull { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val b = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val c = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            val sec = if (c != null) a * 3600 + b * 60 + c else a * 60 + b
            // 过滤课程列表里的日期/时间段 (如 09:00); 媒体时长通常不超过 12 小时
            if (sec in 1..(12 * 3600)) sec else null
        }.toList()
    }

    private fun pickTimePair(hits: List<TimeHit>, surface: Rect?): Pair<Int, Int>? {
        if (hits.isEmpty()) return null
        // 1) 同一行的时间文本最可能是 "当前时间  总时长"
        val groups = ArrayList<MutableList<TimeHit>>()
        hits.sortedBy { it.y }.forEach { h ->
            val g = groups.firstOrNull { kotlin.math.abs(it.first().y - h.y) <= 40 }
            if (g != null) g.add(h) else groups.add(mutableListOf(h))
        }
        val candidates = ArrayList<Pair<Int, Int>>()
        for (g in groups) {
            if (g.size < 2) continue
            val sorted = g.sortedBy { it.x }
            val a = sorted.first().sec
            val b = sorted.last().sec
            val lo = minOf(a, b)
            val hi = maxOf(a, b)
            if (hi > 0) candidates.add(lo to hi)
        }
        if (candidates.isNotEmpty()) {
            // 优先时长更长的一组; 同时尽量靠近画面底部 (控制条通常在底部)
            return candidates.maxWithOrNull(
                compareBy<Pair<Int, Int>> { it.second }
                    .thenBy {
                        if (surface == null) 0 else -kotlin.math.abs(it.second - surface.bottom)
                    }
            )
        }
        // 2) 单个文本里同时有时间 (已被 parseTimes 拆开, 同 y 分组会命中)
        // 3) 只有两个时间时直接按大小配对
        if (hits.size == 2) {
            val a = hits[0].sec
            val b = hits[1].sec
            if (maxOf(a, b) > 0) return minOf(a, b) to maxOf(a, b)
        }
        return null
    }

    private fun pickCurrentSpeed(hits: List<SpeedHit>): Double? {
        val selected = hits.firstOrNull { it.selected && it.value != null }
        if (selected != null) return selected.value
        val values = hits.mapNotNull { it.value }.distinct()
        // 只有一个倍速值时才是当前倍速; 菜单里同时列出多个选项时不要猜
        return if (values.size == 1) values.first() else null
    }

    private fun buildDetails(
        playing: Boolean?,
        position: Int?,
        duration: Int?,
        speed: Double?,
        speedText: String,
        timeHits: List<TimeHit>,
        speedHits: List<SpeedHit>,
        surface: Rect?,
        source: String
    ): String = buildString {
        append("媒体状态: ")
        append(
            when (playing) {
                true -> "播放中"
                false -> "暂停"
                else -> "未识别"
            }
        )
        append(" | 位置 ").append(fmt(position)).append(" / ").append(fmt(duration))
        if (position != null && duration != null && duration > 0) {
            append(" (").append(position * 100 / duration).append("%)")
        }
        val shownSpeed = speed?.let { fmtSpeed(it) }
            ?: speedText.takeIf { it.isNotBlank() && it != "倍速" }
            ?: "未知"
        append(" | 倍速 ").append(shownSpeed)
        if (position != null && duration != null && duration > position) {
            val sp = speed ?: 1.0
            append(" | 剩余约 ").append(((duration - position) / sp).toInt()).append("s")
        }
        append('\n')
        if (timeHits.isNotEmpty()) {
            append("时间文本: ")
            timeHits.take(6).forEach { h ->
                append('"').append(h.text).append("\"@(").append(h.x).append(',')
                    .append(h.y).append(") ")
            }
            append('\n')
        }
        if (speedHits.isNotEmpty()) {
            append("倍速控件: ")
            speedHits.take(8).forEach { h ->
                append('"').append(h.text).append("\"@(").append(h.x).append(',')
                    .append(h.y).append(')')
                if (h.selected) append("[已选]")
                if (h.control) append("[控件]")
                append(' ')
            }
            append('\n')
        }
        if (surface != null) {
            append("画面区域: (").append(surface.centerX()).append(',')
                .append(surface.centerY()).append(") ")
                .append(surface.width()).append('x').append(surface.height()).append('\n')
        }
        append("来源: ").append(source)
    }

    private fun isOverlay(text: String): Boolean {
        return text.contains("Step ") || text.contains("操作中") ||
            text.contains("等待模型") || text.contains("等待确认") ||
            text.contains("CatfeeAgents")
    }

    private fun fmt(sec: Int?): String {
        if (sec == null) return "?"
        val s = sec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, r)
        else String.format("%02d:%02d", m, r)
    }

    private fun fmtSpeed(v: Double): String =
        if (v == v.toInt().toDouble()) "${v.toInt()}x" else String.format("%.2fx", v)

    /** 用 2.5 秒的播放进度差估算实际倍速; 暂停/缓冲/跳转时返回 null */
    private suspend fun measureSpeed(before: State): Double? {
        val p0 = before.positionSec ?: return null
        val t0 = System.currentTimeMillis()
        delay(2_500)
        val after = read()
        val p1 = after.positionSec ?: return null
        val elapsed = ((System.currentTimeMillis() - t0) / 1000.0).coerceAtLeast(1.0)
        val sp = (p1 - p0) / elapsed
        return if (sp in 0.2..4.0) sp else null
    }

    private suspend fun waitWithProgress(ms: Long) {
        val start = System.currentTimeMillis()
        var last = 0L
        while (System.currentTimeMillis() - start < ms) {
            val remain = ms - (System.currentTimeMillis() - start)
            delay(remain.coerceIn(50L, 1_000L))
            val elapsed = System.currentTimeMillis() - start
            if (elapsed - last >= 15_000) {
                last = elapsed
                AgentController.publishStep(
                    "system",
                    "等待媒体 · ${elapsed / 1000}s / ${ms / 1000}s",
                    "wait_media"
                )
            }
        }
    }
}
