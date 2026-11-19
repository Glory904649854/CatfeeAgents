package com.catfee.rootauto.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.catfee.rootauto.a11y.UiObserverService
import com.catfee.rootauto.device.AppLauncher
import com.catfee.rootauto.llm.LlmClient
import com.catfee.rootauto.llm.LlmSettings
import com.catfee.rootauto.ocr.OcrEngine
import com.catfee.rootauto.root.DaemonClient
import com.catfee.rootauto.root.InputDispatcher
import com.catfee.rootauto.root.RootShell
import com.catfee.rootauto.root.ScreenGeometry
import com.catfee.rootauto.root.ScreenVision
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * ToolRuntime —— Agent 原子工具的实际执行器
 *
 * 功能描述:
 *   把「查找/点击/滑动/输入/等待/截图/OCR/视觉/应用管理/系统命令/文件/LLM」这些
 *   手机操作封装成纯文本返回的函数, 供 AgentTools 的工具定义统一调度。
 *   所有函数都尽量返回「人能读懂的一句话 + 关键数据」, 方便直接回灌给模型。
 *
 * 设计原则:
 *   1. 原子化: 一个工具只做一件事, 复杂流程交给 Agent 组合。
 *   2. 通用: 不绑定任何具体 App, 所有应用都用同一套。
 *   3. 可降级: 无障碍读不到的 WebView/iframe 用 OCR / 视觉工具补。
 *
 * 修改时间: 2026-11-03
 */
object ToolRuntime {

    private const val TAG = "ToolRuntime"

    /**
     * 验证缓存由 AgentTools.Session 持有, 这里不再保留全局状态。
     * 这样并行/连续会话不会互相污染 observe、ocr、verify_changed 的结果。
     */

    // ---------------- 观察 ----------------

    /**
     * 功能: 计算当前界面指纹 (文字/描述/id/坐标的哈希)
     * 返回: 16 进制哈希; 无无障碍服务时返回空串
     * 使用场景: observe 去重、verify_changed
     */
    fun currentScreenHash(): String {
        val tree = UiObserverService.instance?.dumpTree(800).orEmpty()
        if (tree.isEmpty()) return ""
        val sb = StringBuilder()
        tree.forEach { n ->
            if (n.text.isNotBlank() || n.desc.isNotBlank() || n.resourceId.isNotBlank()) {
                sb.append(n.text).append('|').append(n.desc).append('|')
                    .append(n.resourceId).append('|').append(n.bounds).append('\n')
            }
        }
        return Integer.toHexString(sb.toString().hashCode())
    }

    /**
     * 功能: 验证界面相对当前 Agent 会话内上一次 observe 是否发生变化
     * 返回: 变化/未变化 + 新指纹
     * 使用场景: 点击/输入/滑动后确认动作是否真的生效
     */
    fun verifyChanged(session: AgentTools.Session): String {
        val now = currentScreenHash()
        if (now.isEmpty()) return "无法读取界面树, 不能判断变化"
        val changed = session.lastObservedHash.isNotBlank() && now != session.lastObservedHash
        return if (session.lastObservedHash.isBlank()) {
            "没有可比较的 observe 指纹 (hash=$now), 请先 observe"
        } else if (changed) {
            "界面已变化 (hash=$now)"
        } else {
            "界面未变化 (hash=$now), 上一步可能无效"
        }
    }

    /**
     * 功能: 验证屏幕上的文字 (无障碍 + OCR 两种独立证据)
     * 参数: keyword 关键字
     * 返回: PASS/FAIL + 两种证据
     * 使用场景: 提交前确认提示、确认弹窗、题目是否完整
     */
    suspend fun verifyText(keyword: String): String {
        if (keyword.isBlank()) return "keyword 为空"
        val a11y = UiObserverService.instance?.dumpTree(800).orEmpty()
        val a11yHit = a11y.any { it.text.contains(keyword) || it.desc.contains(keyword) }
        val (bmp, err) = ScreenVision.captureBitmap()
        val ocrHit = if (bmp == null) false else try {
            OcrEngine.rowsOf(bmp).any { it.text.contains(keyword) }
        } finally {
            bmp.recycle()
        }
        val pass = a11yHit || ocrHit
        return "${if (pass) "PASS" else "FAIL"} 「$keyword」: 无障碍=$a11yHit, OCR=$ocrHit" +
            if (bmp == null) " (OCR 截图失败: $err)" else ""
    }

    /**
     * 功能: 验证某个坐标上的选项是否被选中 (按像素颜色)
     * 参数: x,y 选项圆圈/方框中心
     * 返回: 已选中/未选中 + 蓝色占比
     * 说明:
     *   多数 App 的选中态是蓝色实心, 未选中是白底蓝边。
     *   视觉模型对这种细粒度状态经常看错, 像素颜色比它可靠。
     * 使用场景: 每题作答后逐项确认
     */
    suspend fun verifyOption(x: Int, y: Int): String {
        val (bmp, err) = ScreenVision.captureBitmap()
        if (bmp == null) return "截图失败: $err"
        return try {
            val ratio = blueRatio(bmp, x, y)
            "${if (ratio > 40) "已选中" else "未选中"} ($x,$y): 蓝色占比 $ratio%"
        } finally {
            bmp.recycle()
        }
    }

    /**
     * 功能: 一次验证一道题所有选项的选中状态
     * 参数: options JSON 数组, 每项 { letter, x, y }
     * 返回: 逐项状态 + 汇总 (已选中: A,C / 未选中: B,D)
     * 使用场景:
     *   多选题逐项点完后, 一次调用核对整题; 再调一次就是"每题检查两次"的第二遍。
     *   只截一张图, 比逐个 verify_option 更快、更一致 (避免截图时机不同)。
     */
    suspend fun verifyQuestion(options: JSONArray): String {
        if (options.length() == 0) return "options 为空, 需要 [{letter,x,y}, ...]"
        if (options.length() > 12) return "选项太多 (${options.length()}), 最多 12 个"
        val (bmp, err) = ScreenVision.captureBitmap()
        if (bmp == null) return "截图失败: $err"
        return try {
            val selected = ArrayList<String>()
            val unselected = ArrayList<String>()
            val lines = StringBuilder()
            for (i in 0 until options.length()) {
                val o = options.optJSONObject(i) ?: continue
                val letter = o.optString("letter").ifBlank { "?" }
                val x = o.optInt("x", -1)
                val y = o.optInt("y", -1)
                if (x < 0 || y < 0) {
                    lines.append("  $letter: 坐标无效\n")
                    continue
                }
                val ratio = blueRatio(bmp, x, y)
                // 未选中选项的蓝边也能采到 20%~30%, 阈值必须高于它
                val on = ratio > 40
                if (on) selected.add(letter) else unselected.add(letter)
                lines.append("  $letter (${x},${y}): ")
                    .append(if (on) "已选中" else "未选中")
                    .append(", 蓝色 ").append(ratio).append("%\n")
            }
            "整题核对:\n$lines" +
                "汇总: 已选中 [${selected.joinToString(",")}], " +
                "未选中 [${unselected.joinToString(",")}]"
        } finally {
            bmp.recycle()
        }
    }

    /** 采样一个选项圆圈的蓝色占比 (0~100) */
    private fun blueRatio(bmp: android.graphics.Bitmap, x: Int, y: Int, radius: Int = 16): Int {
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
                val rr = (px shr 16) and 0xFF
                val gg = (px shr 8) and 0xFF
                val bb = px and 0xFF
                total++
                if (bb > 150 && bb > rr + 30 && bb > gg + 10) blue++
                dx += 4
            }
            dy += 4
        }
        return if (total == 0) 0 else blue * 100 / total
    }

    /** 当前前台包名/Activity */
    suspend fun currentActivity(): String {
        val guess = UiObserverService.instance?.currentComponentGuess().orEmpty()
        if (guess.isNotBlank()) return guess
        if (!RootShell.isAvailable()) return "读取失败: 当前为非 Root 模式且无障碍不可用"
        val line = RootShell.execText("dumpsys window 2>/dev/null | grep -m1 mCurrentFocus")
        val inner = line.substringAfter('{', "").substringBefore('}')
        val target = inner.split(' ').lastOrNull { it.contains('/') }.orEmpty()
        return if (target.isBlank()) "读取失败: $line" else target
    }

    /** 当前显示尺寸; 非 Root 走 DisplayManager, 不触发 su */
    private suspend fun displaySize(): Pair<Int, Int> {
        try {
            ScreenGeometry.refreshRotation()
        } catch (e: Exception) {
            Log.w(TAG, "刷新方向失败: ${e.message}")
        }
        return ScreenGeometry.displaySize()
    }

    /** 按文字/描述查找节点, 无障碍无命中时自动降级 OCR (微信等自绘/隐藏节点 App) */
    suspend fun findText(keyword: String, exact: Boolean, byDesc: Boolean): String {
        val svc = UiObserverService.instance ?: return "无障碍服务未连接"
        if (keyword.isBlank()) return "keyword 为空"
        val tree = svc.dumpTree(800)
        val hits = tree.filter { n ->
            val v = if (byDesc) n.desc else n.text
            if (exact) v == keyword else v.contains(keyword)
        }.take(20)
        if (hits.isEmpty()) return findTextByOcr(keyword, exact)
        return buildString {
            append("找到 ${hits.size} 个「$keyword」:\n")
            hits.forEach { n ->
                append("  [${n.index}] ")
                append(n.cls)
                if (n.text.isNotBlank()) append(" text=\"").append(n.text).append('"')
                if (n.desc.isNotBlank()) append(" desc=\"").append(n.desc).append('"')
                if (n.resourceId.isNotBlank()) append(" id=").append(n.resourceId.substringAfterLast('/'))
                append(" clickable=").append(n.clickable)
                append(" bounds=(").append(n.bounds.centerX()).append(',').append(n.bounds.centerY()).append(')')
                append('\n')
            }
        }
    }

    /** 无障碍树读不到时用 OCR 找文字, 返回坐标供 tap 使用 */
    private suspend fun findTextByOcr(keyword: String, exact: Boolean): String {
        val (bmp, err) = ScreenVision.captureBitmap()
        if (bmp == null) {
            return "未找到「$keyword」(无障碍无命中, OCR 截图失败: $err)"
        }
        return try {
            val rows = OcrEngine.rowsOf(bmp)
            val hits = rows.filter {
                if (exact) it.text.trim() == keyword else it.text.contains(keyword)
            }.take(12)
            if (hits.isEmpty()) {
                "未找到「$keyword」(无障碍树和 OCR 均无命中)"
            } else {
                buildString {
                    append("OCR 找到 ${hits.size} 个「$keyword」(无障碍树无命中):\n")
                    hits.forEach { r ->
                        append("  \"").append(r.text.take(80)).append("\" (")
                        append(r.cx).append(',').append(r.cy).append(")\n")
                    }
                    append("可用 tap(x,y) 点击上面的坐标")
                }
            }
        } finally {
            bmp.recycle()
        }
    }

    /**
     * 功能: 列出当前屏幕的选项/单选框/复选框
     * 参数: keyword 选项文字关键字, 空=匹配「选项」或 RadioButton/CheckBox
     * 返回: 每个选项的文字、选中状态、中心坐标、是否可点击
     * 使用场景:
     *   单选题/多选题优先用这个读状态, 它来自无障碍的 checked/selected/「已选择」,
     *   比 OCR 坐标猜像素可靠; 滚动后重新调用, 不要复用旧坐标。
     */
    fun listOptions(keyword: String): String {
        val svc = UiObserverService.instance ?: return "无障碍服务未连接"
        val tree = svc.dumpTree(1000)
        val kw = keyword.ifBlank { "选项" }
        // 不强制 visible/bounds: WebView 虚拟节点经常报 visible=false 或负坐标,
        // 但文字里的「已选择」仍然可靠; 坐标无效时提示需要滚动。
        val hits = tree.filter { n ->
            n.text.contains(kw) || n.desc.contains(kw) ||
                n.cls.contains("RadioButton") || n.cls.contains("CheckBox")
        }.sortedWith(
            compareBy(
                { if (it.bounds.isEmpty) Int.MAX_VALUE else it.bounds.top },
                { if (it.bounds.isEmpty) Int.MAX_VALUE else it.bounds.left }
            )
        ).take(30)
        if (hits.isEmpty()) return "未找到选项节点。如果页面是纯画布/iframe, 改用 ocr + verify_question"
        return buildString {
            append("选项 ${hits.size} 个 (按屏幕位置排序):\n")
            hits.forEachIndexed { i, n ->
                val label = n.text.ifBlank { n.desc }.replace("\n", " ").take(80)
                val on = n.checked || n.selected ||
                    n.text.contains("已选择") || n.text.contains("已勾选") ||
                    n.text.contains("选中")
                append("[").append(i + 1).append("] ").append(label)
                append(" | ").append(if (on) "已选中" else "未选中")
                if (!n.bounds.isEmpty) {
                    append(" | center=(").append(n.bounds.centerX()).append(',').append(n.bounds.centerY()).append(')')
                } else {
                    append(" | 坐标无效(需滚动后重读)")
                }
                append(" | clickable=").append(n.clickable)
                if (n.resourceId.isNotBlank()) append(" | id=").append(n.resourceId.substringAfterLast('/'))
                append('\n')
            }
        }
    }

    /** 截图并写入 App cache, 返回尺寸/路径 */
    suspend fun screenshot(context: Context): String {
        val (bmp, err) = ScreenVision.captureBitmap()
        if (bmp == null) return "截图失败: $err"
        return try {
            val f = File(context.cacheDir, "catfee_screenshot.png")
            FileOutputStream(f).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            "截图成功: ${bmp.width}x${bmp.height} -> ${f.absolutePath}"
        } catch (e: Exception) {
            "截图保存失败: ${e.message}"
        } finally {
            bmp.recycle()
        }
    }

    /** 本地 OCR 当前屏幕, 返回前 60 行文字与坐标 */
    suspend fun ocr(session: AgentTools.Session): String {
        val (bmp, err) = ScreenVision.captureBitmap()
        if (bmp == null) return "OCR 截图失败: $err"
        return try {
            val rows = OcrEngine.rowsOf(bmp)
            if (rows.isEmpty()) return "OCR 未识别到文字"
            val full = rows.joinToString("\n") { "${it.text}@${it.box.centerX()},${it.box.centerY()}" }
            val hash = Integer.toHexString(full.hashCode())
            if (hash == session.lastOcrHash) {
                return "OCR 内容未变化 (hash=$hash), 不要再重复 OCR; 需要定位请用 find_text 或直接操作"
            }
            session.lastOcrHash = hash
            buildString {
                append("OCR ${rows.size} 行 (hash=").append(hash).append("):\n")
                rows.take(60).forEach { r ->
                    append("  (").append(r.box.centerX()).append(',').append(r.box.centerY()).append(") ")
                    append(r.text).append('\n')
                }
            }
        } finally {
            bmp.recycle()
        }
    }

    /**
     * 等待某个文字出现
     *   无障碍每秒查一次; OCR 每 10 秒补一次 (视频完成提示在 WebView 里, 无障碍可能读不到)。
     *   单次最长 5 分钟, 等待期间每 15 秒发一条进度, 避免界面长时间无反馈。
     */
    suspend fun waitForText(
        keyword: String,
        timeoutMs: Long,
        awaitIfPaused: suspend () -> Unit = {}
    ): String {
        if (keyword.isBlank()) return "keyword 为空"
        val start = System.currentTimeMillis()
        val timeout = timeoutMs.coerceIn(500, 300_000)
        var lastOcr = 0L
        var lastReport = 0L
        while (System.currentTimeMillis() - start < timeout) {
            awaitIfPaused()
            val tree = UiObserverService.instance?.dumpTree(600).orEmpty()
            if (tree.any { it.text.contains(keyword) || it.desc.contains(keyword) }) {
                return "已出现「$keyword」(无障碍), 耗时 ${System.currentTimeMillis() - start}ms"
            }
            val now = System.currentTimeMillis()
            if (now - lastOcr >= 10_000) {
                lastOcr = now
                val (bmp, _) = ScreenVision.captureBitmap()
                if (bmp != null) {
                    try {
                        val hit = OcrEngine.rowsOf(bmp).any { it.text.contains(keyword) }
                        if (hit) {
                            return "已出现「$keyword」(OCR), 耗时 ${now - start}ms"
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
            }
            if (now - lastReport >= 15_000) {
                lastReport = now
                AgentController.publishStep(
                    "system",
                    "等待「$keyword」中 · ${(now - start) / 1000}s / ${timeout / 1000}s",
                    "wait_for_text"
                )
            }
            delay(1000)
            awaitIfPaused()
        }
        return "等待超时(${timeout}ms), 未出现「$keyword」"
    }

    // ---------------- 点击 ----------------

    /** 节点中心是否在当前显示区域内; WebView 会把屏幕外元素也放进无障碍树 */
    private fun onScreen(n: UiObserverService.TreeNode, w: Int, h: Int): Boolean {
        if (n.bounds.width() <= 0 || n.bounds.height() <= 0) return false
        return n.bounds.centerX() in 0 until w && n.bounds.centerY() in 0 until h
    }

    private fun offscreenHint(label: String): String =
        "「$label」命中节点在屏幕外; 先 swipe/scroll_to_text 滚动到可见区域, 再用新坐标/tap_node。"

    private data class ClickPick(
        val target: UiObserverService.TreeNode?,
        val ambiguous: List<UiObserverService.TreeNode> = emptyList()
    )

    /**
     * 选择当前可见且最可能可点击的节点; 重复文案无法消歧时不盲点第一个。
     */
    private fun pickClickTarget(
        tree: List<UiObserverService.TreeNode>,
        w: Int,
        h: Int,
        matches: (UiObserverService.TreeNode) -> Boolean
    ): ClickPick {
        val candidates = tree.filter { matches(it) && it.visible && onScreen(it, w, h) }
        if (candidates.isEmpty()) return ClickPick(null)
        val ranked = candidates.map { node ->
            val target = clickableAncestor(tree, node) ?: node
            val score = (if (node.visible) 40 else 0) +
                (if (node.clickable) 35 else 0) +
                (if (target.clickable) 20 else 0) +
                (if (node.cls.contains("Button", true)) 10 else 0) +
                (if (node.text.isNotBlank()) 5 else 0)
            Triple(node, target, score)
        }.sortedByDescending { it.third }
        val best = ranked.first()
        val ties = ranked.filter { it.third == best.third }
            .map { it.second }
            .distinctBy { "${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}" }
        return if (ties.size > 1) ClickPick(null, ties) else ClickPick(best.second)
    }

    private fun ambiguousHint(label: String, nodes: List<UiObserverService.TreeNode>): String =
        "找到多个「$label」候选, 未盲点; 请用 tap_node 的 index/id 或先滚动/观察区分: " +
            nodes.take(5).joinToString("; ") { "#${it.index}@${it.bounds.centerX()},${it.bounds.centerY()}" }

    /** 按文字点击; 找不到时返回候选 */
    suspend fun tapText(keyword: String, exact: Boolean): String {
        val svc = UiObserverService.instance ?: return "无障碍服务未连接"
        val (w, h) = displaySize()
        val tree = svc.dumpTree(800)
        val matches: (UiObserverService.TreeNode) -> Boolean = {
            val v = if (it.text.isNotBlank()) it.text else it.desc
            if (exact) v == keyword else v.contains(keyword)
        }
        val picked = pickClickTarget(tree, w, h, matches)
        if (picked.target == null) {
            if (picked.ambiguous.isNotEmpty()) return ambiguousHint(keyword, picked.ambiguous)
            if (tree.any(matches)) return offscreenHint(keyword)
            // 微信等 App 不暴露无障碍节点时, 用 OCR 找到文字再点坐标
            return tapTextByOcr(keyword, exact)
        }
        val target = picked.target
        val cx = target.bounds.centerX()
        val cy = target.bounds.centerY()
        if (!onScreen(target, w, h)) return offscreenHint(keyword)
        ScreenGeometry.invalid(cx, cy)?.let { return "找到「$keyword」但 $it" }
        overlayTapBlocked(cx, cy)?.let { return it }
        val ok = InputDispatcher.tap(cx, cy)
        return if (ok) "已发送点击「$keyword」($cx,$cy); 请用 observe/verify_changed 确认界面是否变化"
        else "点击失败: 坐标无法注入或 daemon 无响应"
    }

    /**
     * 无障碍无法输入时: 写剪贴板 → 点输入框聚焦 → 点输入法工具栏里的剪贴板建议。
     * 微信这类不暴露节点、但允许后台写剪贴板的 App 可以用这条路径。
     * 如果输入法没有剪贴板建议, 再退回长按输入框 → 点「粘贴」。
     */
    suspend fun pasteText(context: Context, text: String, x: Int, y: Int): String {
        try {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("CatfeeAgents", text))
        } catch (e: Exception) {
            return "剪贴板写入失败: ${e.message}; 无法自动输入"
        }
        // 先点输入框让它聚焦并弹出键盘
        if (UiObserverService.instance?.systemOverlayAt(x, y) == true) {
            return "输入框被系统通知横幅覆盖; 请先 wait 3 秒再试"
        }
        InputDispatcher.tap(x, y)
        delay(900)

        // 键盘弹出后输入框会上移; 输入法窗口不可见时按屏幕高度估算输入框位置
        val (sw, sh) = displaySize()
        val hint = UiObserverService.instance?.focusedInputHint()
        val px = hint?.x ?: (sw / 2)
        val py = hint?.y ?: (sh * 0.59f).toInt()
        dLog(context, "pasteText hint=$hint px=$px py=$py")

        val needle = text.trim().take(6)
        val suggestion = findSuggestionHit(needle, py)
        if (suggestion != null) {
            // OCR 行可能把整个输入法工具栏合并成一行, 必须点具体词块的位置
            val ok = InputDispatcher.tap(suggestion.box.centerX(), suggestion.box.centerY())
            delay(700)
            if (ok && pastedConfirmed(needle, py)) {
                return "已通过输入法剪贴板建议粘贴 ${text.length} 个字符; 输入框已出现内容"
            }
            dLog(context, "pasteText suggestion tap ok=$ok but not confirmed")
        }

        // 输入法没有剪贴板建议时的兜底: 长按 → 粘贴
        val longPressOk = InputDispatcher.longPress(px, py, 650)
        dLog(context, "pasteText longPress=$longPressOk at $px,$py")
        if (!longPressOk) {
            return "剪贴板已写入, 但长按输入框失败"
        }
        delay(1100)
        val tapped = tapTextByOcr("粘贴", exact = false)
        if (!tapped.startsWith("未找到")) {
            delay(500)
            return "已通过长按粘贴 ${text.length} 个字符; 请 observe/OCR 确认输入框内容"
        }
        // 部分 ROM 的截图不包含系统弹出菜单, OCR 看不到「粘贴」;
        // 菜单固定在输入框上方, 第一项就是粘贴, 按输入框位置盲点。
        val blindX = (sw * 0.07f).toInt()
        val blindY = (py - 80).coerceAtLeast(0)
        val blindOk = InputDispatcher.tap(blindX, blindY)
        dLog(context, "pasteText blind tap=$blindOk at $blindX,$blindY")
        delay(500)
        return if (blindOk) {
            "已通过剪贴板粘贴 ${text.length} 个字符 (菜单坐标); 请 observe/OCR 确认输入框内容"
        } else {
            "剪贴板已写入, 但粘贴菜单点击失败"
        }
    }

    /** 调试输出到 files/debug.log; vivo 的 logcat 缓冲区经常拿不到应用日志 */
    private fun dLog(context: Context, text: String) {
        if (!com.catfee.rootauto.BuildConfig.DEBUG) return
        try {
            File(context.filesDir, "debug.log").appendText(text + "\n")
        } catch (_: Exception) {
        }
    }

    /** 粘贴后确认: 输入框区域出现文本, 或出现发送按钮 */
    private suspend fun pastedConfirmed(needle: String, inputY: Int): Boolean {
        val (bmp, _) = ScreenVision.captureBitmap()
        if (bmp == null) return false
        return try {
            val rows = OcrEngine.rowsOf(bmp)
            rows.any { it.text.contains("发送") } ||
                rows.any {
                    it.text.contains(needle) &&
                        it.cy in (inputY - 220)..(inputY + 180)
                }
        } finally {
            bmp.recycle()
        }
    }

    /** 在输入框下方的输入法工具栏区域找剪贴板建议; 返回具体词块而不是整行 */
    private suspend fun findSuggestionHit(
        needle: String,
        inputY: Int
    ): OcrEngine.OcrLine? {
        if (needle.isBlank()) return null
        val (bmp, _) = ScreenVision.captureBitmap()
        if (bmp == null) return null
        return try {
            val rows = OcrEngine.rowsOf(bmp)
            val row = rows.firstOrNull { r ->
                r.text.contains(needle) && r.cy > inputY && r.cy < inputY + 480
            } ?: rows.firstOrNull { r -> r.text.contains(needle) && r.cy > inputY }
            val part = row?.parts?.firstOrNull { it.text.contains(needle) }
            part ?: row?.let { OcrEngine.OcrLine(it.text, it.box) }
        } finally {
            bmp.recycle()
        }
    }

    /** 点击点被系统通知横幅覆盖时返回可行动提示, 否则 null */
    private fun overlayTapBlocked(x: Int, y: Int): String? =
        if (UiObserverService.instance?.systemOverlayAt(x, y) == true) {
            "目标被系统通知横幅覆盖 ($x,$y); 请先 wait 3 秒或换位置, 不要重复点击"
        } else {
            null
        }

    /** 无障碍无命中时用 OCR 找到文字并点坐标 */
    private suspend fun tapTextByOcr(keyword: String, exact: Boolean): String {
        val (bmp, err) = ScreenVision.captureBitmap()
        if (bmp == null) return "未找到「$keyword」(无障碍无命中, OCR 截图失败: $err)"
        val hit = try {
            OcrEngine.rowsOf(bmp).firstOrNull {
                if (exact) it.text.trim() == keyword else it.text.contains(keyword)
            }
        } finally {
            bmp.recycle()
        }
        if (hit == null) return "未找到「$keyword」, 无障碍树和 OCR 都没有"
        val ok = InputDispatcher.tap(hit.cx, hit.cy)
        return if (ok) {
            "OCR 命中「${hit.text.take(40)}」(${hit.cx},${hit.cy}), 已发送点击; " +
                "请 observe/verify_changed 确认"
        } else {
            "OCR 命中「${hit.text.take(40)}」但点击失败"
        }
    }

    /** 按 contentDescription 点击 */
    suspend fun tapDesc(keyword: String): String {
        val svc = UiObserverService.instance ?: return "无障碍服务未连接"
        val (w, h) = displaySize()
        val tree = svc.dumpTree(800)
        val matches: (UiObserverService.TreeNode) -> Boolean = { it.desc.contains(keyword) }
        val picked = pickClickTarget(tree, w, h, matches)
        if (picked.target == null) {
            if (picked.ambiguous.isNotEmpty()) return ambiguousHint(keyword, picked.ambiguous)
            return if (tree.any(matches)) offscreenHint(keyword)
            else "未找到描述包含「$keyword」的节点"
        }
        val target = picked.target
        val cx = target.bounds.centerX()
        val cy = target.bounds.centerY()
        if (!onScreen(target, w, h)) return offscreenHint(keyword)
        ScreenGeometry.invalid(cx, cy)?.let { return "找到「$keyword」但 $it" }
        overlayTapBlocked(cx, cy)?.let { return it }
        val ok = InputDispatcher.tap(cx, cy)
        return if (ok) "已发送点击描述「$keyword」($cx,$cy); 请确认界面变化"
        else "点击失败: 坐标无法注入或 daemon 无响应"
    }

    /** 按 resource-id 后缀点击 */
    suspend fun tapId(idSuffix: String): String {
        val svc = UiObserverService.instance ?: return "无障碍服务未连接"
        val (w, h) = displaySize()
        val tree = svc.dumpTree(800)
        val matches: (UiObserverService.TreeNode) -> Boolean = { it.resourceId.endsWith(idSuffix) }
        val picked = pickClickTarget(tree, w, h, matches)
        if (picked.target == null) {
            if (picked.ambiguous.isNotEmpty()) return ambiguousHint("id=$idSuffix", picked.ambiguous)
            return if (tree.any(matches)) offscreenHint("id=$idSuffix")
            else "未找到 id 以「$idSuffix」结尾的节点"
        }
        val target = picked.target
        val cx = target.bounds.centerX()
        val cy = target.bounds.centerY()
        if (!onScreen(target, w, h)) return offscreenHint("id=$idSuffix")
        ScreenGeometry.invalid(cx, cy)?.let { return "找到 id=$idSuffix 但 $it" }
        overlayTapBlocked(cx, cy)?.let { return it }
        val ok = InputDispatcher.tap(cx, cy)
        return if (ok) "已发送点击 id=$idSuffix ($cx,$cy); 请确认界面变化"
        else "点击失败: 坐标无法注入或 daemon 无响应"
    }

    suspend fun longPress(x: Int, y: Int, holdMs: Int): String =
        if (InputDispatcher.longPress(x, y, holdMs.coerceIn(100, 10_000))) "已长按 ($x,$y)" else "长按失败"

    suspend fun doubleTap(x: Int, y: Int): String =
        if (InputDispatcher.doubleTap(x, y)) "已双击 ($x,$y)" else "双击失败"

    /** 按方向滑动; distance 为屏幕短边的比例 (0~1) */
    suspend fun swipeDirection(direction: String, distance: Float, durationMs: Int): String {
        val (w, h) = displaySize()
        val d = (minOf(w, h) * distance.coerceIn(0.1f, 0.9f)).toInt()
        val cx = w / 2
        val cy = h / 2
        val (x1, y1, x2, y2) = when (direction.lowercase()) {
            "up" -> intArrayOf(cx, cy + d / 2, cx, cy - d / 2)
            "down" -> intArrayOf(cx, cy - d / 2, cx, cy + d / 2)
            "left" -> intArrayOf(cx + d / 2, cy, cx - d / 2, cy)
            "right" -> intArrayOf(cx - d / 2, cy, cx + d / 2, cy)
            else -> return "不支持的方向: $direction (up/down/left/right)"
        }
        val ok = InputDispatcher.swipe(x1, y1, x2, y2, durationMs.coerceIn(80, 3000))
        return if (ok) "已向 $direction 滑动" else "滑动失败"
    }

    // ---------------- 输入 ----------------

    suspend fun clearText(): String {
        val svc = UiObserverService.instance ?: return "无障碍服务未连接"
        return if (svc.setTextOnFocused("")) "已清空当前输入框" else "清空失败: 当前没有可编辑输入框"
    }

    suspend fun setTextId(idSuffix: String, text: String): String {
        val svc = UiObserverService.instance ?: return "无障碍服务未连接"
        return if (svc.setTextById(idSuffix, text)) "已写入 id=$idSuffix" else "写入失败: 未找到可编辑的 id=$idSuffix"
    }

    // ---------------- 滚动 ----------------

    /** 反复上滑直到文字出现 */
    suspend fun scrollToText(keyword: String, maxScrolls: Int): String {
        if (keyword.isBlank()) return "keyword 为空"
        repeat(maxScrolls.coerceIn(1, 30)) { i ->
            val tree = UiObserverService.instance?.dumpTree(800).orEmpty()
            if (tree.any { it.text.contains(keyword) || it.desc.contains(keyword) }) {
                return "第 ${i + 1} 次滚动前已看到「$keyword」"
            }
            // 无障碍树为空的 App (如微信) 用 OCR 判断当前屏
            if (tree.none { it.text.isNotBlank() || it.desc.isNotBlank() } &&
                ocrContains(keyword)
            ) {
                return "第 ${i + 1} 次滚动前 OCR 看到「$keyword」"
            }
            val (w, h) = displaySize()
            val startY = (h * 0.82f).toInt()
            val endY = (h * 0.34f).toInt()
            val moved = InputDispatcher.swipe(w / 2, startY, w / 2, endY, 420)
            if (!moved) return "第 ${i + 1} 次滚动失败, 未找到「$keyword」"
            delay(700)
        }
        val tree = UiObserverService.instance?.dumpTree(800).orEmpty()
        return when {
            tree.any { it.text.contains(keyword) || it.desc.contains(keyword) } ->
                "滚动 $maxScrolls 次后找到「$keyword」"
            tree.none { it.text.isNotBlank() || it.desc.isNotBlank() } &&
                ocrContains(keyword) -> "滚动 $maxScrolls 次后 OCR 找到「$keyword」"
            else -> "滚动 $maxScrolls 次仍未找到「$keyword」"
        }
    }

    /** OCR 当前屏幕是否包含关键词; 仅用于无障碍树为空的降级路径 */
    private suspend fun ocrContains(keyword: String): Boolean {
        val (bmp, _) = ScreenVision.captureBitmap()
        if (bmp == null) return false
        return try {
            OcrEngine.rowsOf(bmp).any { it.text.contains(keyword) }
        } finally {
            bmp.recycle()
        }
    }

    // ---------------- 应用/系统 ----------------

    suspend fun launchActivity(context: Context, component: String): String {
        if (component.isBlank() || !component.contains('/')) return "component 格式应为 包名/Activity"
        val err = AppLauncher.launchActivity(context, component)
        if (err == null) return "已启动 $component"
        if (RootShell.isAvailable()) {
            val r = RootShell.exec("am start -n $component 2>&1")
            if (r.ok && !r.stdout.contains("Error")) return "已启动 $component"
            return "启动失败: ${(r.stdout + r.stderr).take(200)}"
        }
        return "启动失败: $err"
    }

    suspend fun stopApp(pkg: String): String {
        if (pkg.isBlank()) return "包名为空"
        if (!RootShell.isAvailable()) return "当前为非 Root 模式，stop_app 不可用"
        val r = RootShell.exec("am force-stop $pkg")
        return if (r.ok) "已停止 $pkg" else "停止失败: ${r.stderr.take(160)}"
    }

    @Suppress("DEPRECATION")
    suspend fun listPackages(context: Context, keyword: String): String {
        if (!RootShell.isAvailable()) {
            val apps = try {
                context.packageManager.getInstalledApplications(0)
            } catch (e: Exception) {
                return "读取应用列表失败: ${e.message}"
            }
            val lines = apps.map { it.packageName }
                .filter { keyword.isBlank() || it.contains(keyword, ignoreCase = true) }
                .sorted()
                .take(80)
                .map { "package:$it" }
            return if (lines.isEmpty()) "没有匹配的包" else lines.joinToString("\n")
        }
        val cmd = if (keyword.isBlank()) "pm list packages" else "pm list packages | grep -i '$keyword'"
        val r = RootShell.exec(cmd)
        val lines = r.stdout.lines().filter { it.isNotBlank() }.take(80)
        return if (lines.isEmpty()) "没有匹配的包" else lines.joinToString("\n")
    }

    suspend fun appInfo(context: Context, pkg: String): String {
        if (pkg.isBlank()) return "包名为空"
        if (!RootShell.isAvailable()) {
            return try {
                val pm = context.packageManager
                val info = pm.getPackageInfo(pkg, 0)
                val app = info.applicationInfo ?: return "读取包信息失败: applicationInfo 为空"
                "包名: ${info.packageName}\n版本: ${info.versionName} (${info.longVersionCode})\n" +
                    "应用名: ${pm.getApplicationLabel(app)}\n" +
                    "targetSdk: ${app.targetSdkVersion}\nuid: ${app.uid}"
            } catch (e: Exception) {
                "读取包信息失败: ${e.message}"
            }
        }
        val r = RootShell.exec("dumpsys package $pkg | head -60")
        return r.stdout.ifBlank { r.stderr }.take(3000)
    }

    suspend fun openUrl(context: Context, url: String): String {
        if (url.isBlank()) return "URL 为空"
        val err = AppLauncher.openUrl(context, url)
        if (err == null) return "已打开 $url"
        if (RootShell.isAvailable()) {
            val r = RootShell.exec("am start -a android.intent.action.VIEW -d '$url' 2>&1")
            if (r.ok && !r.stdout.contains("Error")) return "已打开 $url"
            return "打开失败: ${(r.stdout + r.stderr).take(200)}"
        }
        return "打开失败: $err"
    }

    private val shellUiInject = Regex(
        """(?i)\b(input\s+(tap|swipe|motionevent|keyevent|text)|sendevent)\b"""
    )

    /** shell 里不允许 sleep/长等待, 否则会占用 20s 超时并绕过播放进度等待 */
    private val shellSleep = Regex("""(?i)\bsleep\b""")

    suspend fun shell(cmd: String): String {
        if (cmd.isBlank()) return "命令为空"
        if (!RootShell.isAvailable()) {
            return "当前为非 Root 模式，shell 不可用。请改用 observe / read_page / " +
                "tap_text / input_text 等无障碍工具。"
        }
        if (shellUiInject.containsMatchIn(cmd)) {
            return "shell 禁止注入 UI 事件。请改用 tap / tap_node / swipe / key / input_text; " +
                "shell 只用于只读查询。"
        }
        if (shellSleep.containsMatchIn(cmd)) {
            return "shell 不允许 sleep/长等待。视频等待请用 wait_media 按播放进度等待; " +
                "页面加载/缓冲用 wait(ms<=30000)。"
        }
        val r = RootShell.exec(cmd, 20_000)
        val out = (r.stdout + if (r.stderr.isNotBlank()) "\n[stderr]\n" + r.stderr else "").trim()
        return out.take(4000).ifBlank { "命令执行完成, 无输出 (code=${r.code})" }
    }

    suspend fun getProp(key: String): String {
        if (key.isBlank()) return "key 为空"
        if (!RootShell.isAvailable()) {
            val v = when (key) {
                "ro.build.version.release" -> Build.VERSION.RELEASE
                "ro.build.version.sdk" -> Build.VERSION.SDK_INT.toString()
                "ro.product.model" -> Build.MODEL
                "ro.product.manufacturer" -> Build.MANUFACTURER
                "ro.product.brand" -> Build.BRAND
                "ro.product.device" -> Build.DEVICE
                "ro.product.name" -> Build.PRODUCT
                "ro.hardware" -> Build.HARDWARE
                "ro.build.type" -> Build.TYPE
                "ro.build.tags" -> Build.TAGS
                "ro.build.fingerprint" -> Build.FINGERPRINT
                else -> return "当前为非 Root 模式，无法读取全部系统属性: $key"
            }
            return "$key=$v"
        }
        val v = RootShell.execText("getprop $key")
        return "$key=$v"
    }

    suspend fun settingsGet(context: Context, namespace: String, key: String): String {
        if (!RootShell.isAvailable()) {
            val ns = namespace.ifBlank { "system" }
            val cr = context.contentResolver
            val v = try {
                when (ns) {
                    "secure" -> Settings.Secure.getString(cr, key)
                    "global" -> Settings.Global.getString(cr, key)
                    else -> Settings.System.getString(cr, key)
                }
            } catch (e: Exception) {
                null
            }
            return "$ns/$key=${v ?: "(不可读)"}"
        }
        val v = RootShell.execText("settings get ${namespace.ifBlank { "system" }} $key")
        return "$namespace/$key=$v"
    }

    suspend fun settingsPut(namespace: String, key: String, value: String): String {
        if (key.isBlank()) return "key 为空"
        if (!RootShell.isAvailable()) return "当前为非 Root 模式，settings_put 不可用"
        val r = RootShell.exec("settings put ${namespace.ifBlank { "system" }} $key '$value'")
        return if (r.ok) "已写入 $namespace/$key=$value" else "写入失败: ${r.stderr.take(160)}"
    }

    suspend fun fileRead(path: String): String {
        if (path.isBlank()) return "path 为空"
        if (!RootShell.isAvailable()) return "当前为非 Root 模式，任意路径文件读取不可用"
        val r = RootShell.exec("cat '$path'", 10_000)
        return (r.stdout.ifBlank { r.stderr }).take(5000)
    }

    suspend fun fileWrite(path: String, content: String): String {
        if (path.isBlank()) return "path 为空"
        if (!RootShell.isAvailable()) return "当前为非 Root 模式，任意路径文件写入不可用"
        val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
        val r = RootShell.exec("echo '$b64' | base64 -d > '$path'", 10_000)
        return if (r.ok) "已写入 ${content.length} 字符到 $path" else "写入失败: ${r.stderr.take(160)}"
    }

    suspend fun fileList(path: String): String {
        if (!RootShell.isAvailable()) return "当前为非 Root 模式，任意路径文件列表不可用"
        val p = path.ifBlank { "/data/local/tmp" }
        val r = RootShell.exec("ls -la '$p'", 10_000)
        return (r.stdout.ifBlank { r.stderr }).take(4000)
    }

    // ---------------- LLM ----------------

    /**
     * 功能: 让 LLM 回答一个子问题, 可临时指定模型
     * 参数: provider 当前配置; prompt 问题; model 可选模型名 (空=用当前模型)
     * 返回: 模型回答
     * 使用场景: Agent 做题/读题/总结时, 可切换到更强的中文模型
     */
    suspend fun askLlm(provider: LlmSettings.Provider, prompt: String, model: String): String {
        if (prompt.isBlank()) return "prompt 为空"
        val p = if (model.isBlank()) provider else provider.copy(model = model)
        val messages = JSONArray().put(
            JSONObject().put("role", "system").put(
                "content",
                "你是一个严谨的答题与信息分析助手。先在心里逐步分析, 再给出简洁结论; 不确定时说明原因。"
            )
        ).put(JSONObject().put("role", "user").put("content", prompt))
        val r = LlmClient.chat(p, messages, 180_000)
        return if (r.ok) r.content else "LLM 调用失败: ${r.error}"
    }

    /**
     * 功能: 同一问题问两个模型, 返回两份答案供 Agent 对比
     * 参数: provider 当前配置; prompt 问题; modelB 第二模型 (默认 glm-5.2)
     * 返回: 两个模型的回答
     * 使用场景: 做题/关键判断需要第二意见时
     */
    suspend fun secondOpinion(provider: LlmSettings.Provider, prompt: String, modelB: String): String {
        if (prompt.isBlank()) return "prompt 为空"
        val second = modelB.ifBlank { "glm-5.2" }
        val a = askLlm(provider, prompt, "")
        val b = askLlm(provider, prompt, second)
        return "模型A(${provider.model}):\n$a\n\n模型B($second):\n$b"
    }

    /** 工具清单 (给 Agent/用户查看) */
    fun catalog(): String = """
        |观察: observe, read_page, find_text, list_options, get_activity, screenshot, ocr, long_ocr, see_screen, wait_for_text
        |点击: tap, tap_text, tap_desc, tap_id, long_press, double_tap, swipe, swipe_direction
        |输入: input_text, clear_text, set_text_id, key
        |滚动: scroll_to_text, swipe_direction
        |应用: launch_app, launch_activity, stop_app, list_packages, app_info, open_url
        |系统: shell, get_prop, settings_get, settings_put, file_read, file_write, file_list
        |验证: verify_text, verify_option, verify_question, verify_changed
        |LLM: ask_llm, second_opinion
        |表单: answer_form
        |流程: todo_write, todo_read, note, wait, finish
        |记忆: memory_read, memory_write, memory_search, memory_delete, memory_list
    """.trimMargin()

    // ---------------- 内部 ----------------

    /** 向上找最近的可点击祖先 */
    private fun clickableAncestor(
        tree: List<UiObserverService.TreeNode>,
        node: UiObserverService.TreeNode
    ): UiObserverService.TreeNode? {
        var cur: UiObserverService.TreeNode? = node
        var guard = 0
        while (cur != null && guard < 8) {
            if (cur.clickable) return cur
            cur = tree.getOrNull(cur.parent)
            guard++
        }
        return null
    }
}
