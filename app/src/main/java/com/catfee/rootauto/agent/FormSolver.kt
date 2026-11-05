package com.catfee.rootauto.agent

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.catfee.rootauto.a11y.UiObserverService
import com.catfee.rootauto.llm.LlmClient
import com.catfee.rootauto.llm.LlmSettings
import com.catfee.rootauto.ocr.LongOcr
import com.catfee.rootauto.ocr.OcrEngine
import com.catfee.rootauto.root.DaemonClient
import com.catfee.rootauto.root.ScreenVision
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * FormSolver —— 通用选项式表单/测验执行器
 *
 * 适用于任何 App 的「题干 + A/B/C/D 选项」页面:
 *   1. OCR 逐屏收集题目
 *   2. 三个模型各自独立作答并给出置信度, 按多数票/最高置信度定答案
 *   3. 按当前屏 OCR 坐标点击选项 (每次滚动后重新取坐标, 不复用旧值)
 *   4. 每题连续 verifyTimes 次像素核对选中态, 不一致就补点
 *   5. 全部题目完成后从头再复查一遍
 *   6. 提交并确认结果
 *
 * 不依赖任何具体 App 的包名/控件 id; 只要页面文字能被 OCR 读到、选项圆圈是蓝色实心即可。
 *
 * 修改时间: 2026-11-04
 */
object FormSolver {

    private const val TAG = "FormSolver"

    /** 当前任务的 applicationContext, 供 OCR 合并使用 */
    @Volatile
    private var ctx: Context? = null

    data class Opt(
        val letter: String,
        val text: String,
        /** 点击坐标: 整行中心 (整行通常都可点) */
        val tx: Int,
        val ty: Int,
        /** 核对坐标: 选项圆圈/方框中心 */
        val vx: Int = tx,
        val vy: Int = ty
    )
    data class Question(
        val number: Int,
        val type: String,
        val stem: String,
        val options: List<Opt>,
        val bottomY: Int
    )
    data class Result(val submitted: Boolean, val answered: Int, val message: String)

    /** 单模型对某题的答案 + 自评置信度 */
    data class Vote(val letters: List<String>, val confidence: Int, val model: String)

    /** 三道独立作答使用的模型 (用户指定) */
    private val QUIZ_MODELS = listOf(
        "deepseek-v4.1-flash",
        "glm-5.3-flash",
        "kimi-k2.6"
    )

    /**
     * 功能: 完成当前页面的选项式表单/测验
     * 参数: context; title 标题(给 LLM 上下文); verifyTimes 每题核对次数; onSubmit 是否提交
     * 返回: Result
     */
    suspend fun solve(
        context: Context,
        title: String,
        verifyTimes: Int = 3,
        onSubmit: Boolean = true,
        reset: Boolean = true,
        providerOverride: LlmSettings.Provider? = null,
        onLog: (String) -> Unit = {}
    ): Result {
        val provider = providerOverride ?: LlmSettings.active(context)
            ?: return Result(false, 0, "LLM 配置为空")
        if (!provider.usable) return Result(false, 0, "LLM 配置不完整")
        ctx = context.applicationContext
        val (w, h) = DaemonClient.screenSize()
        val checks = verifyTimes.coerceIn(1, 5)
        if (reset) resetIfNeeded(onLog)

        // ---------- 收集题目 (滚动拼接整页) ----------
        val known = LinkedHashMap<Int, Question>()
        fun merge(q: Question) {
            val old = known[q.number]
            known[q.number] = if (old == null) {
                q
            } else {
                old.copy(
                    stem = if (q.stem.length > old.stem.length) q.stem else old.stem,
                    options = (old.options + q.options).distinctBy { it.letter }.sortedBy { it.letter },
                    bottomY = q.bottomY
                )
            }
        }
        val longScan = LongOcr.scan(context, 18)
        val longQs = parseQuestions(OcrEngine.rows(longScan.lines), w, h)
        longQs.forEach { merge(it) }
        log(
            onLog,
            "长页面识别 ${longScan.screens} 屏, ${longQs.size} 题 ${known.keys.sorted()}"
        )
        if (longQs.isEmpty()) {
            // 极端情况下回退到逐屏滚动
            scrollTop()
            var noNew = 0
            for (screen in 0 until 24) {
                val qs = parseQuestions(ocrRows(), w, h)
                val before = known.size
                qs.forEach { merge(it) }
                if (known.size == before) noNew++ else noNew = 0
                log(onLog, "收集第 ${screen + 1} 屏, 累计 ${known.size} 题")
                if (noNew >= 5) break
                scrollCollect()
            }
        }
        // 无障碍树里通常保留了完整题干, 用来补 OCR 漏掉的小题号 (如 3、7、8)
        val treeQs = collectTreeQuestions()
        for (q in treeQs) {
            val old = known[q.number]
            if (old == null) {
                known[q.number] = q
            } else {
                known[q.number] = old.copy(
                    stem = if (q.stem.length > old.stem.length) q.stem else old.stem
                )
            }
        }
        log(onLog, "无障碍补全后: ${known.size} 题 ${known.keys.sorted()}")
        val questions = known.values.sortedBy { it.number }
        if (questions.isEmpty()) return Result(false, 0, "没有识别到题目")
        log(onLog, "题目收集完成: ${questions.size} 题 ${questions.map { it.number }}")

        // ---------- 三模型独立作答 + 投票 ----------
        val answers = HashMap<Int, List<String>>()
        questions.chunked(5).forEach { batch ->
            answers.putAll(askConsensus(provider, title, batch, onLog))
        }
        log(onLog, "答案: ${answers.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}")

        // ---------- 逐屏点击: 选项跨屏也能逐个确认 ----------
        // 不要求整题/全部选项同屏; 每个目标选项连续 checks 次采样为选中即确认。
        val confirmed = HashMap<Int, MutableSet<String>>()
        val answered = LinkedHashSet<Int>()

        suspend fun isSelected(o: Opt): Boolean {
            val (bmp, _) = ScreenVision.captureBitmap()
            if (bmp == null) return false
            return try {
                // 圆圈可能比 OCR 行中心高/低一点, 上下各扩 28px 采样
                blueRatio(bmp, o.vx, o.vy) > 40 ||
                    blueRatio(bmp, o.vx, o.vy - 28) > 40 ||
                    blueRatio(bmp, o.vx, o.vy + 28) > 40
            } finally {
                bmp.recycle()
            }
        }

        suspend fun processScreen(qs: List<Question>) {
            for (q in qs) {
                val ans = answers[q.number] ?: continue
                if (q.number in answered) continue
                val visible = q.options.filter { it.letter in ans }
                if (visible.isEmpty()) continue
                val conf = confirmed.getOrPut(q.number) { HashSet() }
                // 多选: 清掉可见的多余选中
                if (q.type.contains("多选") || q.type.contains("不定项")) {
                    for (o in q.options.filter { it.letter !in ans }) {
                        if (isSelected(o)) {
                            DaemonClient.tap(o.tx, o.ty)
                            delay(300)
                        }
                    }
                }
                for (o in visible) {
                    if (o.letter in conf) continue
                    var ok = false
                    for (attempt in 0 until 2) {
                        if (!isSelected(o)) {
                            DaemonClient.tap(o.tx, o.ty)
                            delay(400)
                        }
                        var run = 0
                        repeat(checks) {
                            delay(300)
                            if (isSelected(o)) run++ else run = 0
                        }
                        if (run >= checks) {
                            ok = true
                            break
                        }
                    }
                    if (ok) {
                        conf.add(o.letter)
                        log(onLog, "第 ${q.number} 题 ${o.letter} 已确认")
                    } else {
                        log(onLog, "第 ${q.number} 题 ${o.letter} 未确认")
                    }
                }
                if (conf.containsAll(ans)) {
                    answered.add(q.number)
                    log(onLog, "第 ${q.number} 题通过 ($checks 次一致)")
                }
            }
        }

        scrollTop()
        var lastProgress = 0
        var idle = 0
        for (screen in 0 until 45) {
            processScreen(parseQuestions(ocrRows(), w, h))
            if (answered.size >= questions.size) break
            val progress = confirmed.values.sumOf { it.size }
            if (progress > lastProgress) {
                lastProgress = progress
                idle = 0
            } else {
                idle++
            }
            if (idle >= 10) {
                idle = 0
                scrollTop()
            } else {
                swipeUp()
            }
        }

        val unanswered = questions.filter { it.number !in answered }
        if (unanswered.isNotEmpty()) {
            return Result(
                false,
                answered.size,
                "未答完 ${unanswered.size} 题 (${unanswered.map { it.number }}), 不提交"
            )
        }
        if (!onSubmit) {
            return Result(true, answered.size, "已作答 ${answered.size}/${questions.size} 题, 按设置未提交")
        }
        // ---------- 提交 ----------
        val submit = findSubmit(ocrRows())
            ?: return Result(false, answered.size, "未找到提交按钮")
        val part = submit.parts.firstOrNull {
            it.text.contains("提交") || it.text.contains("交卷")
        }
        val tx = part?.box?.centerX() ?: submit.cx
        val ty = part?.box?.centerY() ?: submit.cy
        log(onLog, "点击「${part?.text?.trim() ?: submit.text.take(20)}」提交")
        DaemonClient.tap(tx, ty)
        delay(1500)
        confirmDialog(onLog)
        delay(2500)
        val after = ocrRows().joinToString(" ") { it.text }
        val ok = after.contains("已完成") || after.contains("提交成功") ||
            after.contains("得分") || after.contains("成绩") || after.contains("已提交")
        log(onLog, "提交后: ${after.take(180)}")
        return Result(ok, answered.size, if (ok) "已提交, 页面出现完成/成绩提示" else "已提交, 未读到明确成功提示")
    }

    // ---------------- 解析 ----------------

    /** 选项草稿: 首行 top / 末行 bottom 用于算垂直中心; cx 是圆圈, tapX 是整行文字中心 */
    private data class OptDraft(
        val letter: String,
        var text: String,
        val top: Int,
        var bottom: Int,
        val cx: Int,
        val tapX: Int
    )

    private fun parseQuestions(rows: List<OcrEngine.OcrRow>, w: Int, h: Int): List<Question> {
        val start = Regex(
            "^(\\d{1,3})\\s*[.、．]\\s*[\\[【(（]?\\s*(单选|多选|判断|不定项|填空|简答|论述)"
        )
        // OCR 常把 "A." 读成 "Al"、"B." 读成 "B|"、把 "C" 读成小写 "c";
        // 这些都要当成选项前缀吃掉, 否则整个选项会被漏掉。
        val optRx = Regex("^([A-Ha-h])\\s*(?:[.、|｜丨:：)）]|[lI1])?\\s*(.*)$")
        val out = ArrayList<Question>()
        var number = -1
        var type = ""
        val stem = StringBuilder()
        val drafts = ArrayList<OptDraft>()
        var bottom = 0

        fun flush() {
            if (number > 0 && drafts.isNotEmpty()) {
                val opts = drafts.map { d ->
                    // 圆圈垂直居中于整个选项块 (单行 = 首行中心, 多行 = 首行到末行中点)
                    val cy = (d.top + d.bottom) / 2
                    Opt(d.letter, d.text, d.tapX, cy, d.cx, cy)
                }
                out.add(
                    Question(
                        number, type, stem.toString().trim(),
                        opts.distinctBy { it.letter }, bottom
                    )
                )
            }
            number = -1
            type = ""
            stem.setLength(0)
            drafts.clear()
            bottom = 0
        }

        for (row in rows) {
            if (row.box.top < 100) continue
            val text = row.text.trim()
            if (text.isEmpty()) continue
            val s = start.find(text)
            if (s != null) {
                flush()
                number = s.groupValues[1].toIntOrNull() ?: -1
                type = s.groupValues[2]
                bottom = row.box.bottom
                continue
            }
            if (number <= 0) continue
            val o = optRx.find(text)
            val isOpt = o != null && row.box.left < w * 0.35 &&
                (!o.groupValues[2].isBlank() || text.length <= 3)
            if (isOpt) {
                val letter = o!!.groupValues[1].uppercase()
                val label = o.groupValues[2].trim()
                // 选项圆圈通常在左侧 ~10% 屏宽; OCR 行起点已含字母时在行首 +40
                val x = if (row.box.left > w * 0.15) (w * 0.10).toInt() else row.box.left + 40
                drafts.add(OptDraft(letter, label, row.box.top, row.box.bottom, x, row.box.centerX()))
                bottom = maxOf(bottom, row.box.bottom)
                continue
            }
            // 判断题的 OCR 经常只读到「对/错」, 读不到 A/B 字母; 按题型补字母
            if (type.contains("判断")) {
                val t = text.trim()
                val letter = when {
                    t == "对" || t == "正确" || t == "是" -> "A"
                    t == "错" || t == "错误" || t == "否" -> "B"
                    else -> null
                }
                if (letter != null && drafts.none { it.letter == letter }) {
                    val x = if (row.box.left > w * 0.15) (w * 0.10).toInt() else row.box.left + 40
                    drafts.add(OptDraft(letter, t, row.box.top, row.box.bottom, x, row.box.centerX()))
                    bottom = maxOf(bottom, row.box.bottom)
                    continue
                }
            }
            // 选项的续行: 补全选项文字, 同时扩展该选项的垂直范围
            // 只接受紧贴上一行的续行; 距离过远的是下一选项被 OCR 漏掉字母的重复行, 不能算进来
            if (drafts.isNotEmpty() && row.box.left < w * 0.85 &&
                row.box.top - drafts.last().bottom <= 60 &&
                !text.startsWith("我的答案") &&
                !text.startsWith("正确答案") && !text.contains("得分")
            ) {
                val d = drafts.last()
                if (text != d.text && !d.text.endsWith(text)) {
                    d.text = (d.text + " " + text).trim()
                }
                d.bottom = maxOf(d.bottom, row.box.bottom)
                bottom = maxOf(bottom, row.box.bottom)
            } else if (drafts.isEmpty() && text.length in 4..160) {
                stem.append(text)
            }
        }
        flush()
        return out
    }

    /** 从无障碍树收集题干, 补 OCR 漏掉的小题号 */
    private fun collectTreeQuestions(): List<Question> {
        val tree = UiObserverService.instance?.dumpTree(1500).orEmpty()
        val rx = Regex(
            "^(\\d{1,3})\\s*[.、．]\\s*[\\[【(（]?\\s*(单选|多选|判断|不定项|填空|简答|论述)"
        )
        val out = LinkedHashMap<Int, Question>()
        for (n in tree) {
            val t = n.text.trim()
            val m = rx.find(t) ?: continue
            val number = m.groupValues[1].toIntOrNull() ?: continue
            val type = m.groupValues[2]
            val stem = t.substring(m.range.last + 1).trim()
            val q = Question(
                number, type, stem, emptyList(),
                if (n.bounds.isEmpty) 0 else n.bounds.bottom
            )
            val old = out[number]
            if (old == null || stem.length > old.stem.length) out[number] = q
        }
        return out.values.toList()
    }

    // ---------------- 作答 / 核对 ----------------

    /**
     * 功能: 三个模型独立作答 + 投票
     * 规则: 答案字母集合相同的票数最多者胜出 (2/3 即多数);
     *       三票全部分歧时取自评置信度最高的答案;
     *       模型漏答某题会强制重试, 保证每个模型都表态。
     */
    private suspend fun askConsensus(
        provider: LlmSettings.Provider,
        title: String,
        batch: List<Question>,
        onLog: (String) -> Unit
    ): Map<Int, List<String>> {
        val perModel = LinkedHashMap<String, Map<Int, Vote>>()
        for (model in QUIZ_MODELS) {
            var got = askModel(provider.copy(model = model), title, batch, false, onLog)
            var missing = batch.filter { it.number !in got }
            var attempt = 0
            // 漏答/整批失败: 强制该模型补答, 最多两轮
            while (missing.isNotEmpty() && attempt < 2) {
                attempt++
                log(onLog, "$model 漏答 ${missing.map { it.number }}, 强制补答第 $attempt 轮")
                val more = askModel(provider.copy(model = model), title, missing, true, onLog)
                if (more.isNotEmpty()) got = got + more
                // 整批仍失败时逐题问, 避免一道题格式错误拖垮整批
                if (more.isEmpty()) {
                    for (q in missing) {
                        got = got + askModel(
                            provider.copy(model = model), title, listOf(q), true, onLog
                        )
                    }
                }
                missing = batch.filter { it.number !in got }
            }
            if (missing.isNotEmpty()) log(onLog, "$model 始终未答 ${missing.map { it.number }}")
            perModel[model] = got
            log(onLog, "$model: " + batch.joinToString(", ") { q ->
                val v = got[q.number]
                "${q.number}=${v?.letters ?: "-"}(${v?.confidence ?: 0})"
            })
        }

        val merged = HashMap<Int, List<String>>()
        for (q in batch) {
            val votes = perModel.values.mapNotNull { it[q.number] }
            if (votes.isEmpty()) {
                log(onLog, "第 ${q.number} 题三个模型都没有答案")
                continue
            }
            // 按答案字母集合分组; 票数相同看平均置信度
            val best = votes.groupBy { it.letters.toSet() }.entries.maxWithOrNull(
                compareBy<Map.Entry<Set<String>, List<Vote>>> { it.value.size }
                    .thenBy { it.value.map { v -> v.confidence }.average() }
            )!!
            merged[q.number] = best.value.first().letters
            log(
                onLog,
                "第 ${q.number} 题投票 " +
                    votes.joinToString("; ") { "${it.model}=${it.letters}(${it.confidence})" } +
                    " -> ${best.key} (${best.value.size}票, 置信度" +
                    "${best.value.map { it.confidence }.average().toInt()})"
            )
        }
        return merged
    }

    /** 单模型作答一批题; 漏答的题由调用方强制补答 */
    private suspend fun askModel(
        provider: LlmSettings.Provider,
        title: String,
        batch: List<Question>,
        strict: Boolean,
        onLog: (String) -> Unit
    ): Map<Int, Vote> {
        val sb = StringBuilder()
        sb.append("只输出 JSON: {\"answers\":[{\"q\":1,\"letters\":[\"A\"],\"confidence\":85}]}\n")
        sb.append("判断题 A=对 B=错; 多选返回所有正确字母; confidence 是正确概率(0-100)。\n")
        if (strict) sb.append("下面每道题都必须给出答案, 不允许留空。\n")
        if (title.isNotBlank()) sb.append("标题: ").append(title).append('\n')
        for (q in batch) {
            sb.append(q.number).append(".[").append(q.type).append("] ").append(q.stem).append('\n')
            q.options.forEach { sb.append(it.letter).append(' ').append(it.text).append('\n') }
            sb.append('\n')
        }
        val system = if (strict) {
            "你是严谨的答题助手。逐题分析后必须作答, 即使不确定也要选最合理的; 只输出 JSON。"
        } else {
            "你是严谨的答题助手。逐题独立分析后作答, 不确定时选最合理的; 只输出 JSON。"
        }
        val messages = JSONArray().put(
            JSONObject().put("role", "system").put("content", system)
        ).put(JSONObject().put("role", "user").put("content", sb.toString()))
        val r = LlmClient.chat(provider, messages, 180_000)
        if (!r.ok) {
            log(onLog, "${provider.model} 调用失败: ${r.error.take(120)}")
            return emptyMap()
        }
        val raw = r.content.trim()
        val json = raw.substringAfter('{', "").substringBeforeLast('}', "")
        if (json.isEmpty()) {
            log(onLog, "${provider.model} 返回非 JSON: ${raw.take(120)}")
            return emptyMap()
        }
        return try {
            val obj = JSONObject("{$json}")
            val arr = obj.optJSONArray("answers") ?: return emptyMap()
            val map = HashMap<Int, Vote>()
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val q = a.optInt("q", -1)
                if (q <= 0) continue
                val la = a.optJSONArray("letters") ?: continue
                val letters = ArrayList<String>()
                for (j in 0 until la.length()) {
                    normalize(la.optString(j)).forEach { letters.add(it) }
                }
                if (letters.isEmpty()) continue
                // 兼容 0-1 概率和 0-100 两种写法
                var conf = a.optDouble("confidence", 60.0)
                if (conf <= 1.0) conf *= 100
                map[q] = Vote(
                    letters.distinct(),
                    conf.toInt().coerceIn(0, 100),
                    provider.model
                )
            }
            map
        } catch (e: Exception) {
            log(onLog, "${provider.model} 解析失败: ${e.message}")
            emptyMap()
        }
    }

    private fun normalize(raw: String): List<String> {
        val out = ArrayList<String>()
        raw.trim().forEach { ch ->
            when {
                ch in 'A'..'H' -> out.add(ch.toString())
                ch in 'a'..'h' -> out.add(ch.uppercaseChar().toString())
                ch == '对' || ch == '是' || ch == '√' -> out.add("A")
                ch == '错' || ch == '否' || ch == '×' -> out.add("B")
            }
        }
        return out
    }

    private suspend fun tapOptions(q: Question, ans: List<String>) {
        for (letter in ans) {
            val o = q.options.firstOrNull { it.letter == letter } ?: continue
            Log.i(TAG, "点击第 ${q.number} 题 $letter @ ${o.tx},${o.ty}")
            DaemonClient.tap(o.tx, o.ty)
            delay(300)
        }
    }

    /**
     * 功能: 核对某题选中态, 要求连续 verifyTimes 次一致
     * 说明: 每次重新 OCR, 用当前屏坐标采样; 发现不一致就纠正并重新计数,
     *       最多尝试 verifyTimes * 4 轮, 避免"第一次纠正 + 后两次通过"被判失败。
     */
    private suspend fun verifyQuestion(
        number: Int,
        ans: List<String>,
        times: Int,
        onLog: (String) -> Unit
    ): Boolean {
        var pass = 0
        var attempt = 0
        val maxAttempts = times * 4
        while (pass < times && attempt < maxAttempts) {
            attempt++
            delay(700)
            val (bmp, _) = ScreenVision.captureBitmap()
            if (bmp == null) {
                pass = 0
                continue
            }
            try {
                val c = ctx
                val rows = if (c != null) OcrEngine.bestRows(c, bmp) else OcrEngine.rowsOf(bmp, 2)
                val filtered = rows.filterNot {
                    it.text.contains("CatfeeAgent") || it.text.contains("CatfeeRootAuto") || it.text.contains("超级用户")
                }
                val (w, h) = DaemonClient.screenSize()
                val q = parseQuestions(filtered, w, h).firstOrNull { it.number == number }
                if (q == null) {
                    pass = 0
                    continue
                }
                val pixel = q.options.filter { blueRatio(bmp, it.vx, it.vy) > 40 }.map { it.letter }
                // 无障碍里「选项X;undefined;已选择」也是可靠证据; 按 y 坐标映射回可见选项
                val a11y = a11ySelected(q)
                val selected = (pixel + a11y).toSet()
                if (selected == ans.toSet()) {
                    pass++
                } else {
                    pass = 0
                    log(onLog, "第 $number 题核对: 期望 $ans, 实际 $selected")
                    // 取消多余选中 (复选框/可切换选项)
                    val extra = selected - ans.toSet()
                    for (letter in extra) {
                        val o = q.options.firstOrNull { it.letter == letter } ?: continue
                        DaemonClient.tap(o.tx, o.ty)
                        delay(300)
                    }
                    // 补齐缺失项
                    for (letter in ans.filter { it !in selected }) {
                        val o = q.options.firstOrNull { it.letter == letter } ?: continue
                        DaemonClient.tap(o.tx, o.ty)
                        delay(300)
                    }
                    delay(400)
                }
            } finally {
                bmp.recycle()
            }
        }
        return pass >= times
    }

    /**
     * 从无障碍树读取选中项, 按 y 坐标映射到当前可见选项。
     * 用于 WebView 选项不是蓝色实心、像素判断失效的场景。
     */
    private fun a11ySelected(q: Question): Set<String> {
        val tree = UiObserverService.instance?.dumpTree(1200) ?: return emptySet()
        val out = HashSet<String>()
        for (n in tree) {
            val t = n.text
            if (t.isBlank()) continue
            if (t.contains("未选中") || t.contains("未选择")) continue
            val looksLikeOption = t.contains("选项") || t.contains("选中") || t.contains("勾选")
            if (!looksLikeOption) continue
            val on = n.checked || n.selected ||
                t.contains("已选择") || t.contains("已勾选") || t.contains("选中")
            if (!on || n.bounds.isEmpty) continue
            val cy = n.bounds.centerY()
            val opt = q.options.minByOrNull { kotlin.math.abs(it.ty - cy) } ?: continue
            if (kotlin.math.abs(opt.ty - cy) <= 100) out.add(opt.letter)
        }
        return out
    }

    private fun blueRatio(bmp: Bitmap, x: Int, y: Int, radius: Int = 16): Int {
        var blue = 0
        var total = 0
        var dy = -radius
        while (dy <= radius) {
            var dx = -radius
            while (dx <= radius) {
                val px = bmp.getPixel(
                    (x + dx).coerceIn(0, bmp.width - 1),
                    (y + dy).coerceIn(0, bmp.height - 1)
                )
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                total++
                if (b > 150 && b > r + 30 && b > g + 10) blue++
                dx += 4
            }
            dy += 4
        }
        return if (total == 0) 0 else blue * 100 / total
    }

    // ---------------- 通用辅助 ----------------

    private suspend fun ocrRows(): List<OcrEngine.OcrRow> {
        val c = ctx ?: return emptyList()
        val (bmp, _) = ScreenVision.captureBitmap()
        if (bmp == null) return emptyList()
        return try {
            OcrEngine.bestRows(c, bmp).filterNot {
                it.text.contains("CatfeeAgent") || it.text.contains("CatfeeRootAuto") || it.text.contains("超级用户") ||
                    it.text.contains("已被授予")
            }
        } finally {
            bmp.recycle()
        }
    }

    private suspend fun scrollTop() {
        // 用「任务点已完成」作为顶部标志, 到顶就停, 避免手势过冲触发下拉刷新
        repeat(12) {
            val rows = ocrRows()
            if (rows.any { it.text.contains("任务点已完成") }) {
                delay(500)
                return
            }
            DaemonClient.swipe(540, 800, 540, 1900, 500)
            delay(900)
        }
        delay(800)
    }

    /**
     * 功能: 如果当前是已批阅/成绩页, 点「重做」并确认, 再点「由此作答」
     * 说明: 重做后旧答案可能保留, 逐题核对时会按目标答案纠正残留选项。
     */
    private suspend fun resetIfNeeded(onLog: (String) -> Unit) {
        repeat(8) {
            DaemonClient.swipe(540, 720, 540, 1900, 300)
            delay(220)
        }
        delay(600)
        val redo = ocrRows().firstOrNull { it.text.contains("重做") } ?: return
        log(onLog, "检测到「${redo.text.take(20)}」, 先重置")
        DaemonClient.tap(redo.cx, redo.cy)
        delay(2000)

        // 处理「确认重做」弹窗: 只点按钮本身, 不能点题干 (题干里也有"确认重做")
        for (round in 0 until 3) {
            val rows = ocrRows()
            val parts = rows.flatMap { it.parts }
            val cancel = parts.firstOrNull {
                val t = it.text.trim()
                t == "取消" || t == "否"
            }
            val confirm = if (cancel != null) {
                parts.filter {
                    val t = it.text.trim()
                    (t == "确定" || t == "确认" || t == "确定重做" || t == "确认重做") &&
                        kotlin.math.abs(it.box.centerY() - cancel.box.centerY()) < 150 &&
                        it.box.centerX() > cancel.box.centerX()
                }.minByOrNull { kotlin.math.abs(it.box.centerY() - cancel.box.centerY()) }
            } else {
                null
            }
            if (confirm != null) {
                log(onLog, "重做确认: 点击「${confirm.text.trim()}」")
                DaemonClient.tap(confirm.box.centerX(), confirm.box.centerY())
                delay(2600)
                break
            }
            // 按钮被 OCR 合并成一行时点右半边
            val merged = rows.firstOrNull {
                it.text.contains("取消") &&
                    (it.text.contains("确定") || it.text.contains("确认"))
            }
            if (merged != null) {
                log(onLog, "重做确认: 合并按钮行, 点右侧")
                DaemonClient.tap(merged.box.left + merged.box.width() * 3 / 4, merged.cy)
                delay(2600)
                break
            }
            delay(1500)
        }

        // 进入作答页: 已显示题目就不再点「由此作答」(它可能折叠内容)
        for (round in 0 until 4) {
            val r = ocrRows()
            if (r.any { Regex("^\\d{1,3}\\s*[.、．]").containsMatchIn(it.text.trim()) }) break
            val start = r.firstOrNull {
                it.text.contains("由此作答") || it.text.contains("开始答题")
            }
            if (start != null) {
                log(onLog, "点击「${start.text.take(10)}」进入答题")
                DaemonClient.tap(start.cx, start.cy)
                delay(2600)
                break
            }
            delay(1200)
        }
        log(onLog, "已进入可作答状态")
    }

    private suspend fun swipeUp() {
        DaemonClient.swipe(540, 1600, 540, 1050, 500)
        // 等 WebView 惯性完全停下, 否则 OCR 坐标与点击时的位置不一致
        delay(2000)
    }

    /** 收集阶段用整屏滚动, 更快覆盖长页面 */
    private suspend fun scrollCollect() {
        DaemonClient.swipe(540, 1900, 540, 700, 450)
        delay(900)
    }

    private fun findSubmit(rows: List<OcrEngine.OcrRow>): OcrEngine.OcrRow? =
        rows.firstOrNull {
            (it.text.contains("提交") || it.text.contains("交卷")) &&
                !it.text.contains("提交后") && !it.text.contains("提交之") &&
                it.box.top > 800
        }

    private suspend fun confirmDialog(onLog: (String) -> Unit) {
        val rows = ocrRows()
        for (row in rows.asReversed()) {
            val part = row.parts.firstOrNull {
                val t = it.text.trim()
                t == "确定" || t == "确认" || t == "确定提交" || t == "确认提交"
            } ?: continue
            log(onLog, "确认弹窗: 点击「${part.text.trim()}」")
            DaemonClient.tap(part.box.centerX(), part.box.centerY())
            return
        }
    }

    private fun log(onLog: (String) -> Unit, msg: String) {
        Log.i(TAG, msg)
        onLog(msg)
    }
}
