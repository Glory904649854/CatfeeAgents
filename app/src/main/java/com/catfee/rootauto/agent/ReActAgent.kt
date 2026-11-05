package com.catfee.rootauto.agent

import android.content.Context
import android.util.Log
import com.catfee.rootauto.llm.LlmClient
import com.catfee.rootauto.llm.LlmSettings
import com.catfee.rootauto.log.AgentTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * ReActAgent —— 最小可用的系统操作 Agent
 *
 * 功能描述:
 *   给定一个自然语言任务目标, 循环执行「观察 → 思考 → 动作」:
 *     1. 把当前屏幕内容 + 历史动作 发给 LLM
 *     2. LLM 返回 tool_calls, 本地逐个执行
 *     3. 执行结果作为 tool 消息回灌, 进入下一轮
 *   直到 LLM 调用 finish 工具, 或达到最大轮数/超时。
 *
 * 参数说明:
 *   见 run()。
 *
 * 返回值说明:
 *   onEvent 回调三种事件: 思考文本、动作描述、最终结论。
 *
 * 使用场景:
 *   调试面板的任务执行按钮。
 *
 * 修改时间: 2026-11-03
 */
object ReActAgent {

    private const val TAG = "ReActAgent"

    /**
     * 最大轮数
     * 说明: 手机上一个动作就是一轮, 且长列表要滚动多次才能读全,
     *       30 轮对遍历长列表这类任务明显不够, 实测会撞上限;
     *       大上限配合总超时, 既能覆盖多步任务, 又不至于无限烧 token。
     */
    const val MAX_STEPS = 3000

    /** 整体任务超时: 批量刷课/看视频是小时级任务, 给 8 小时 */
    private const val TOTAL_TIMEOUT_MS = 8 * 60 * 60 * 1000L

    /** 历史消息上限 (含 system), 超过则滑动窗口丢弃最老的对话, 防止上下文爆掉 */
    private const val MAX_MESSAGES = 60

    /**
     * 上下文总字符预算。
     * 只按条数裁剪会被超长的 OCR/长页结果撑爆: 80 条 × 几万字一样会超模型上下文,
     * 表现为请求很慢、超时重试、甚至模型忘记任务目标。
     * 实测长任务会顶到 48K 字符, 模型注意力明显下降; 收紧到 40K。
     */
    private const val MAX_CONTEXT_CHARS = 40_000

    /** 单条工具结果回灌上限, 超出部分截断并提示 Agent 用更精确的工具重读 */
    private const val MAX_TOOL_MESSAGE_CHARS = 10_000

    /** 便签/计划每轮都进 system, 需要限制总长度, 否则长任务会自己把上下文撑爆 */
    private const val MAX_NOTES_CHARS = 4_000
    private const val MAX_TODO_CHARS = 3_000

    /**
     * 会读取/控制手机的工具有哪些。
     * 普通聊天与附件分析不应碰这些; 首次调用前必须由 App 在聊天框内请求用户确认。
     */
    private val DEVICE_TOOLS = setOf(
        "observe", "read_page", "screenshot", "ocr", "long_ocr", "see_screen",
        "get_activity", "find_text", "list_options", "scroll_to_text", "wait_for_text",
        "verify_text", "verify_option", "verify_question", "verify_changed",
        "tap", "tap_text", "tap_id", "tap_desc", "double_tap", "long_press",
        "swipe", "swipe_direction", "key", "input_text", "clear_text", "set_text_id",
        "launch_app", "launch_activity", "stop_app", "open_url",
        "settings_get", "settings_put", "shell",
        "file_list", "file_read", "file_write",
        "app_info", "get_prop", "list_packages", "answer_form"
    )

    /** 完全相同且连续重复时大概率是卡死的动作类工具; 滚动/等待类不在此列 */
    private val REPEAT_BLOCK_TOOLS = setOf(
        "tap", "tap_text", "tap_id", "tap_desc", "double_tap", "long_press",
        "input_text", "clear_text", "set_text_id"
    )

    /**
     * 任务事件
     * @param type    think=模型思考文本 / action=执行了某工具 / done=任务结束 / error=出错
     * @param content 事件内容
     */
    data class Event(
        val type: String,
        val content: String,
        /** 产生该事件的工具名 (思考/完成/失败时为空) */
        val tool: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 系统提示词 —— 决定 Agent 的行为准则
     * 说明: 刻意写得短而硬, 手机上步骤多, 提示词越短每轮 token 越省, 决策也越不容易跑偏。
     */
    private val SYSTEM_PROMPT = """
        最高优先级: 你同时是通用聊天助手和手机操作 Agent。
        - 普通聊天、问答、解释、分析用户上传的图片/文件: 直接用自然语言回答,
          不要调用任何工具, 不要调用 observe, 也不要调用 finish。
        - 只有用户明确要求查看/操作手机 (点击、输入、打开 App、做任务) 时, 才使用下面的手机操作规则。
          首次设备操作会由 App 弹确认卡, 用户允许后本会话不再询问。
        - 用户上传的图片已经由 App 本地 OCR, 文件已经本地解析; 解析文本会附在用户消息里,
          直接基于这些文字回答, 不要假装看到了原始图片。
        - 当前任务目标优先于历史会话和 App 记忆。历史里的旧目标只作参考, 不要因为旧记忆/旧会话
          切回答题、刷课等旧任务; 用户现在让你看视频就只看视频。
        - tap 坐标必须是当前屏幕像素; 先确认 x < 屏幕宽, y < 屏幕高。
          see_screen 返回的是绝对像素; 如果拿到的是 0~1000 归一化值, 先换算再点。
          越界坐标会被直接拒绝, 不要反复重试同一个越界坐标。

        你是一个通用的 Android 手机操作 Agent。你通过工具直接控制这台真机, 可以操作任意 App 完成用户目标。
        工具覆盖: 观察(无障碍/OCR/截图/视觉)、点击(坐标/文字/描述/id/长按/双击)、滑动、输入、
        查找与等待、滚动找文字、启停应用、Activity 跳转、shell/属性/设置/文件、LLM 子问答。

        工作方式:
        1. 先 observe 或 get_activity 确认当前界面; 每次操作后重新确认, 不要凭想象连续操作。
        2. 点击优先 tap_text / tap_desc / tap_id; 只有拿不到文本时才用 tap 坐标。
        3. 无障碍读不到内容 (WebView / iframe / 自绘控件) 时, 调用 ocr 看文字+坐标;
           需要理解题目/图片时调用 see_screen 让视觉模型读屏。
        4. 列表内容需要滚动才能看全: 用 swipe_direction 或 scroll_to_text, 再重新观察。
        5. 看到题目、进度、数量、选项等关键信息, 用 note 记录下来; 最后用 finish 汇总。
        6. 连续两轮界面没有变化, 换一种做法 (返回、刷新、换入口、用 OCR/视觉)。
        7. 做测验/表单/问卷时, 必须一题一题做, 不要一把梭:
           - 先用 todo_write 把每道题列成清单项 (pending)
           - 每题: ocr 读全题干和选项 -> ask_llm 作答 (不确定时用 second_opinion 问第二个模型)
             -> 用 list_options 拿选项的当前坐标和选中状态 (滚动后必须重新调用, 不要复用旧坐标)
             -> tap 选项
             -> 连续调用 list_options 3 次核对本题所有选项的选中态, 3 次结果一致才通过
           - 如果 list_options 找不到选项节点 (纯画布/iframe), 才退回 verify_question 连续 3 次
           - 本题 3 次核对一致后, 再把 todo 该项标为 completed
           - 全部题目完成后, 从头逐题再复查一遍 (重新 ocr + list_options 三次), 确认没有漏题/错选
           - 最后才 verify_text 确认提交按钮/弹窗, 提交
        8. 关键或不可逆操作 (提交、支付、删除、发送、答题、改设置) 前后必须验证:
           至少两种独立证据 (文字 + 像素/视觉), 重要操作至少检查两次;
           两次证据冲突时不要继续, 重新读页面再判断。
        9. 多步骤任务先用 todo_write 列清单, 每完成一项更新状态; 清单会每轮注入上下文。
        10. list_options 优先 (无障碍 checked/已选择, 最可靠);
            verify_option/verify_question 是像素兜底, 页面没有无障碍选项节点时才用;
            verify_text 确认文字/弹窗, verify_changed 判断动作是否真的改变了界面;
            不要用 see_screen 猜复选框状态。
        11. 不要连续 observe/ocr 超过 2 次; 如果结果提示"界面未变化/OCR 未变化",
            换 find_text / verify_changed / 直接操作, 不要重复。
        12. 操作前先核对页面身份: 用 get_activity / read_page 看标题和 Activity,
            确认是目标任务再操作; 不是目标就先返回列表定位, 不要在错误页面里继续做。
        13. 要进入列表中的某一项 (聊天/商品/设置项等) 时, 先回到上层列表,
            用 find_text / scroll_to_text 定位后点击; 不要用「下一节/上一节」逐页翻找目标。
        14. 遇到「未保存/未提交/确认退出」弹窗, 先处理弹窗 (保存/提交/确定/取消),
            处理完再继续导航; 不要无视弹窗继续点。
        15. 已执行超过 15 步仍没接近目标时, 停下来用 todo_write 重新规划, 或 finish 说明障碍。
        16. 任务完成或确实无法继续时, 必须调用 finish, 在 summary 写清已完成什么、关键数据和障碍。
        17. 每轮会注入「当前 App 记忆」(按包名隔离, 很短)。先按记忆里的入口/按钮/坑操作,
            不要重新摸索; 记忆与当前页面不一致时, 以 observe/OCR 为准, 并顺手更新记忆。
        18. 完成一个 App 的任务后, 用 memory_write 把本次发现的稳定入口/关键控件/易错点
            写成简洁记忆 (≤600 字, 按包名隔离); 不要记题目答案或一次性数据。
        19. 看视频等长时间等待用 wait_for_text("已完成", 300000), 超时后先 OCR/verify_text
            看是否暂停或弹窗, 处理后再继续等; 不要用大量 wait 小步空转。
        20. 整页选项式测验/表单: 先让题目显示在当前页, 调用 answer_form(title, verify_times=3, submit=true);
            它内部让 deepseek-v4.1-flash、glm-5.3-flash、kimi-k2.6 各自独立作答并投票,
            每题 3 次核对、整页复查后提交。失败再按第 7 条手动逐题做。
        21. 题目/列表长于一屏时, 先用 long_ocr 滚动拼接识别整页, 再分析/作答;
            不要只靠单屏 ocr, 否则滚到下面可能只看到选项、看不到题干。

        注意:
        - 坐标是像素, 屏幕尺寸在 observe 结果第一行。
        - 不要执行与任务无关的操作; 不要 clear 应用数据、卸载应用、改系统设置。
    """.trimIndent()

    /**
     * 功能: 运行一次任务
     * 参数:
     *   context   应用上下文
     *   provider  LLM 配置
     *   goal      用户的任务目标 (自然语言)
     *   onEvent   过程回调, 用于界面实时展示
     * 返回: 最终的 finish summary (或中断原因)
     * 使用场景: 点「开始执行」按钮
     */
    suspend fun run(
        context: Context,
        provider: LlmSettings.Provider,
        goal: String,
        priorContext: String = "",
        onEvent: (Event) -> Unit,
        onUsage: (prompt: Int, completion: Int, total: Int) -> Unit = { _, _, _ -> },
        onContext: (messages: Int, chars: Int) -> Unit = { _, _ -> },
        onStreamContent: (String) -> Unit = {},
        onStreamReasoning: (String) -> Unit = {},
        ensurePhonePermission: suspend (tool: String, args: String) -> Boolean =
            { _, _ -> true }
    ): String {
        if (!provider.usable) {
            onEvent(Event("error", "LLM 配置不完整, 请先填写并保存 BaseUrl / Key / 模型名"))
            return "配置不完整"
        }
        if (goal.isBlank()) {
            onEvent(Event("error", "任务目标为空"))
            return "任务目标为空"
        }

        val started = System.currentTimeMillis()
        val startPkg = AppMemory.currentPackage()
        AgentTrace.startTask(context, goal, provider.model, SYSTEM_PROMPT, startPkg)
        var currentStep = 0
        var phoneGranted = false
        var lastCallSig = ""
        var sameCallCount = 0
        var targetPkg = startPkg
        var memoryWritten = false
        var usedDeviceTool = false
        val firstUser = buildString {
            append("任务目标: ").append(goal)
            if (priorContext.isNotBlank()) {
                append("\n\n===== 之前会话的上下文 =====\n")
                append(priorContext.take(6_000))
            }
            append("\n请先观察屏幕, 然后开始。")
        }
        val messages = JSONArray().put(
            JSONObject().put("role", "system").put("content", SYSTEM_PROMPT)
        ).put(
            JSONObject().put("role", "user").put("content", firstUser)
        )
        val tools = AgentTools.definitions()
        // 长期便签: 历史消息会被裁剪, 但便签每轮都注入 system, 保证关键数据不丢
        val notes = StringBuilder()
        // 任务清单: 多步骤任务由 Agent 自己维护, 每轮注入, 防止漏项
        val todo = StringBuilder()
        // 无进展检测
        var lastSig = ""
        var repeatSig = 0
        var noChangeCount = 0
        var observationOnly = 0
        var lastScreenHash = ""
        var repeatHintSent = false
        var noChangeHintSent = false

        try {
            for (step in 1..MAX_STEPS) {
                currentStep = step
                val stepStart = System.currentTimeMillis()
                if (System.currentTimeMillis() - started > TOTAL_TIMEOUT_MS) {
                    onEvent(Event("error", "任务总时长超过 $TOTAL_TIMEOUT_MS ms, 已中断"))
                    AgentTrace.finish(context, false, "超时中断", step)
                    return "超时中断"
                }
                trimHistory(messages)
                val hints = StringBuilder()
                if (todo.isNotEmpty()) {
                    hints.append("\n\n===== 当前计划 =====\n").append(todo.toString().take(MAX_TODO_CHARS))
                }
                if (repeatSig >= 2) {
                    hints.append("\n\n[系统提醒] 你已经连续重复同一调用 $repeatSig 次, 禁止继续重复, 换方法或 finish。")
                }
                if (noChangeCount >= 2) {
                    hints.append("\n\n[系统提醒] 最近 $noChangeCount 个动作没有改变界面, 上一步可能无效; " +
                        "用 verify_changed 确认, 或 find_text/read_page 重新定位, 换一种做法。")
                }
                if (observationOnly >= 3) {
                    hints.append("\n\n[系统提醒] 你已经连续观察 $observationOnly 次, 请给出实际动作或 finish, 不要只观察。")
                }
                // 按当前前台 App 注入它的简短记忆 (入口/关键控件/坑)
                val fgPkg = AppMemory.currentPackage()
                if (fgPkg.isNotBlank() && fgPkg != context.packageName) {
                    targetPkg = fgPkg
                }
                val appMem = AppMemory.read(context, fgPkg)
                if (appMem.isNotBlank()) {
                    hints.append("\n\n===== 当前 App 记忆 (").append(fgPkg).append(") =====\n").append(appMem)
                    Log.i(TAG, "memory inject pkg=$fgPkg len=${appMem.length}")
                }
                // 每轮刷新 system 里的便签/计划/提醒/App 记忆
                messages.put(
                    0,
                    JSONObject().put("role", "system").put(
                        "content",
                        SYSTEM_PROMPT +
                            "\n\n===== 任务目标 =====\n" + goal +
                            (if (notes.isNotEmpty()) "\n\n===== 已记录的信息 =====\n$notes" else "") +
                            hints
                    )
                )

                // 长任务保护: 网络抖动/限流时自动重试, 不要一次失败就整单结束
                val lastInput = messages.optJSONObject(messages.length() - 1)
                    ?.optString("content").orEmpty()
                val ctxChars = messages.toString().length
                val ctxMsgs = messages.length()
                onContext(ctxMsgs, ctxChars)
                var r = chatWithHeartbeat(
                    provider, messages, tools, step, ctxChars, onEvent,
                    onStreamContent, onStreamReasoning
                )
                var attempt = 1
                while (!r.ok && attempt < 3) {
                    val retryMsg = "LLM 请求失败(第 $attempt 次): ${r.error}; 5 秒后重试"
                    onEvent(Event("system", retryMsg))
                    AgentTrace.event(context, "warn", "LLM 重试 · Step $step", retryMsg)
                    kotlinx.coroutines.delay(5000)
                    attempt++
                    r = chatWithHeartbeat(
                        provider, messages, tools, step, ctxChars, onEvent,
                        onStreamContent, onStreamReasoning
                    )
                }
                if (!r.ok) {
                    onEvent(Event("error", "第 $step 轮请求失败(已重试 2 次): ${r.error}"))
                    AgentTrace.finish(context, false, "LLM 请求失败: ${r.error}", step)
                    return "LLM 请求失败: ${r.error}"
                }
                val llmMs = r.elapsedMs
                onUsage(r.promptTokens, r.completionTokens, r.totalTokens)
                if (r.content.isNotBlank() && r.toolCalls.isNotEmpty()) {
                    onEvent(Event("think", r.content))
                }

                // 没有工具调用: 普通聊天/问答/附件分析到此结束, 直接把回答作为最终结果
                if (r.toolCalls.isEmpty()) {
                    if (r.content.isNotBlank()) {
                        if (!memoryWritten && usedDeviceTool) {
                            autoWriteMemory(
                                context, provider, targetPkg, goal, r.content,
                                notes.toString(), onEvent
                            )
                        }
                        AgentTrace.step(
                            context, step, lastInput, r.content,
                            emptyList(), emptyList(), llmMs, r.reasoning, ctxChars, ctxMsgs
                        )
                        AgentTrace.finish(context, true, r.content, step)
                        return r.content
                    }
                    if (step == 1) {
                        messages.put(
                            JSONObject().put("role", "user")
                                .put("content", "请直接回答用户, 或使用工具继续执行任务。")
                        )
                        continue
                    }
                    onEvent(Event("error", "第 $step 轮模型没有返回内容"))
                    AgentTrace.finish(context, false, "模型未返回内容", step)
                    return "模型未返回内容"
                }

                // 助手消息必须原样回灌 (含 tool_calls), 否则下一轮接口会报错
                messages.put(assistantMessage(r.content, r.toolCalls))

                val toolTraces = ArrayList<AgentTrace.ToolTrace>()
                for (call in r.toolCalls) {
                    val t0 = System.currentTimeMillis()
                    onEvent(Event("tool", call.arguments, tool = call.name))
                    val callSig = call.name + "|" + call.arguments
                    if (callSig == lastCallSig) {
                        sameCallCount++
                    } else {
                        lastCallSig = callSig
                        sameCallCount = 1
                    }
                    if (call.name in REPEAT_BLOCK_TOOLS && sameCallCount >= 3) {
                        val blocked = "重复调用已阻止: 同一工具+参数已连续 $sameCallCount 次。" +
                            "请换方法/坐标, 或 finish 说明障碍。"
                        onEvent(Event("system", blocked))
                        toolTraces.add(
                            AgentTrace.ToolTrace(call.name, call.arguments, blocked, 0, false)
                        )
                        messages.put(toolMessage(call.id, blocked))
                        if (sameCallCount >= 6) {
                            AgentTrace.finish(context, false, "重复调用过多, 任务终止", step)
                            return "重复调用过多, 任务终止"
                        }
                        continue
                    }
                    if (call.name in DEVICE_TOOLS) usedDeviceTool = true
                    if (call.name in DEVICE_TOOLS && !phoneGranted) {
                        val allowed = ensurePhonePermission(call.name, call.arguments)
                        if (!allowed) {
                            AgentTrace.finish(context, false, "用户未允许控制手机", step)
                            return "用户未允许控制手机"
                        }
                        phoneGranted = true
                    }
                    if (call.name == "finish") {
                        val summary = parseSummary(call.arguments)
                        toolTraces.add(
                            AgentTrace.ToolTrace(
                                "finish", call.arguments, summary,
                                System.currentTimeMillis() - t0
                            )
                        )
                        if (!memoryWritten && usedDeviceTool) {
                            autoWriteMemory(
                                context, provider, targetPkg, goal, summary,
                                notes.toString(), onEvent
                            )
                        }
                        AgentTrace.step(
                            context, step, lastInput, r.content,
                            r.toolCalls.map { it.name to it.arguments },
                            toolTraces, llmMs, r.reasoning, ctxChars, ctxMsgs
                        )
                        AgentTrace.finish(context, true, summary, step)
                        onEvent(Event("done", summary))
                        return summary
                    }
                    if (call.name == "note") {
                        val text = try {
                            JSONObject(call.arguments).optString("text")
                        } catch (e: Exception) {
                            ""
                        }
                        if (text.isNotBlank()) {
                            notes.append("- ").append(text).append('\n')
                            if (notes.length > MAX_NOTES_CHARS) {
                                notes.delete(0, notes.length - MAX_NOTES_CHARS)
                            }
                        }
                        val noteResult = "已记录 ${text.take(200)}"
                        onEvent(Event("result", noteResult, tool = "note"))
                        toolTraces.add(
                            AgentTrace.ToolTrace(
                                "note", call.arguments, noteResult,
                                System.currentTimeMillis() - t0
                            )
                        )
                        messages.put(
                            JSONObject().put("role", "tool")
                                .put("tool_call_id", call.id)
                                .put("content", "已记录")
                        )
                        continue
                    }
                    if (call.name == "todo_write") {
                        val formatted = try {
                            val arr = JSONObject(call.arguments).optJSONArray("items")
                            val sb = StringBuilder()
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    val item = arr.optJSONObject(i) ?: continue
                                    val t = item.optString("task").trim()
                                    if (t.isEmpty()) continue
                                    val st = item.optString("status", "pending")
                                    val mark = when (st) {
                                        "completed" -> "[x]"
                                        "in_progress" -> "[~]"
                                        else -> "[ ]"
                                    }
                                    sb.append(mark).append(' ').append(t).append('\n')
                                }
                            }
                            sb.toString()
                        } catch (e: Exception) {
                            ""
                        }
                        val todoResult = if (formatted.isBlank()) {
                            "todo_write 参数错误: 需要 items 数组"
                        } else {
                            "计划已更新:\n$formatted"
                        }
                        toolTraces.add(
                            AgentTrace.ToolTrace(
                                "todo_write", call.arguments, todoResult,
                                System.currentTimeMillis() - t0,
                                formatted.isNotBlank()
                            )
                        )
                        if (formatted.isBlank()) {
                            messages.put(
                                JSONObject().put("role", "tool")
                                    .put("tool_call_id", call.id)
                                    .put("content", todoResult)
                            )
                        } else {
                            todo.setLength(0)
                            todo.append(formatted)
                            onEvent(Event("todo", formatted.take(600)))
                            messages.put(
                                JSONObject().put("role", "tool")
                                    .put("tool_call_id", call.id)
                                    .put("content", todoResult)
                            )
                        }
                        continue
                    }
                    if (call.name == "todo_read") {
                        val text = if (todo.isEmpty()) "(空)" else todo.toString()
                        onEvent(Event("result", text.take(300), tool = "todo_read"))
                        toolTraces.add(
                            AgentTrace.ToolTrace(
                                "todo_read", call.arguments, text,
                                System.currentTimeMillis() - t0
                            )
                        )
                        messages.put(
                            JSONObject().put("role", "tool")
                                .put("tool_call_id", call.id)
                                .put("content", text)
                        )
                        continue
                    }
                    val result = AgentTools.execute(context, provider, call.name, call.arguments)
                    if (call.name == "memory_write" && !result.startsWith("写入失败")) {
                        memoryWritten = true
                    }
                    val elapsed = System.currentTimeMillis() - t0
                    val toolOk = !(result.startsWith("失败") || result.startsWith("错误") ||
                        result.startsWith("Exception") || result.contains("执行失败"))
                    toolTraces.add(
                        AgentTrace.ToolTrace(call.name, call.arguments, result, elapsed, toolOk)
                    )
                    onEvent(Event("result", result.take(400), tool = call.name))
                    messages.put(toolMessage(call.id, result))
                    // 动作执行后给界面一点响应时间, 否则 observe/指纹可能读到旧界面
                    kotlinx.coroutines.delay(AgentTools.DEFAULT_SETTLE_MS)

                    // 无进展检测: 只读工具算观察, 动作工具看界面指纹是否变化
                    val readOnly = call.name in setOf(
                        "observe", "read_page", "find_text", "ocr", "see_screen",
                        "get_activity", "screenshot", "wait", "verify_text",
                        "verify_option", "verify_changed", "ask_llm", "second_opinion",
                        "tool_list", "note", "todo_read", "todo_write"
                    )
                    if (readOnly) observationOnly++ else observationOnly = 0
                    val sig = call.name + "|" + call.arguments
                    if (sig == lastSig) repeatSig++ else {
                        repeatSig = 0
                        repeatHintSent = false
                        lastSig = sig
                    }
                    if (!readOnly) {
                        val h = ToolRuntime.currentScreenHash()
                        if (h.isNotEmpty()) {
                            if (lastScreenHash.isNotEmpty() && h == lastScreenHash) {
                                noChangeCount++
                            } else {
                                noChangeCount = 0
                                noChangeHintSent = false
                            }
                            lastScreenHash = h
                        }
                    }
                }
                AgentTrace.step(
                    context, step, lastInput, r.content,
                    r.toolCalls.map { it.name to it.arguments },
                    toolTraces, llmMs, r.reasoning, ctxChars, ctxMsgs
                )
                // 用 user 消息做硬干预, 比 system 提醒更容易被模型重视
                if (repeatSig >= 2 && !repeatHintSent) {
                    val msg = "[系统干预] 你已经连续重复同一调用 $repeatSig 次。立即停止重复: " +
                        "换方法、返回上层列表、先处理弹窗, 或 finish 说明障碍。"
                    onEvent(Event("system", msg))
                    messages.put(JSONObject().put("role", "user").put("content", msg))
                    repeatHintSent = true
                }
                if (noChangeCount >= 2 && !noChangeHintSent) {
                    val msg = "[系统干预] 最近 $noChangeCount 个动作没有改变界面。先用 verify_changed/find_text 确认; " +
                        "如果是弹窗遮挡, 先处理弹窗; 不要继续同样的操作。"
                    onEvent(Event("system", msg))
                    messages.put(JSONObject().put("role", "user").put("content", msg))
                    noChangeHintSent = true
                }
                if (step % 15 == 0) {
                    val msg = "[系统检查点] 已执行 $step 步。请用 todo_read 检查计划: " +
                        "如果当前方法没有进展, 重新规划或 finish; 不要继续无效重复。"
                    onEvent(Event("system", msg))
                    messages.put(JSONObject().put("role", "user").put("content", msg))
                }
            }
            onEvent(Event("error", "达到最大轮数 $MAX_STEPS, 任务未完成"))
            AgentTrace.finish(context, false, "达到最大轮数, 任务未完成", MAX_STEPS)
            return "达到最大轮数, 任务未完成"
        } catch (e: CancellationException) {
            // 用户主动停止, 不算错误
            onEvent(Event("error", "任务已被用户停止"))
            AgentTrace.finish(context, false, "用户停止", currentStep)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "任务执行异常: ${e.message}")
            onEvent(Event("error", "任务异常: ${e.message}"))
            AgentTrace.finish(context, false, "任务异常: ${e.message}", currentStep)
            return "任务异常: ${e.message}"
        }
    }

    /**
     * 任务完成时自动整理一条 App 记忆。
     * 模型经常忘记调用 memory_write, 导致按包名隔离的记忆一直是空的;
     * 这里用一次轻量 LLM 提取稳定入口/控件/坑, 明确禁止题目答案和一次性数据。
     */
    private suspend fun autoWriteMemory(
        context: Context,
        provider: LlmSettings.Provider,
        pkg: String,
        goal: String,
        summary: String,
        notes: String,
        onEvent: (Event) -> Unit
    ) {
        if (pkg.isBlank() || pkg == context.packageName) return
        onEvent(Event("system", "整理 App 记忆…"))
        val prompt = """
            App 包名: $pkg
            任务目标: ${goal.take(500)}
            任务结果: ${summary.take(800)}
            过程便签: ${notes.take(1200)}

            请只提取这个 App 的稳定入口、关键控件、容易误点的位置/坑, 最多 300 字。
            禁止记录: 题目答案、课程进度、具体任务结果、临时弹窗、用户隐私、一次性数据。
            如果没有稳定信息, 只输出: 暂无稳定信息
        """.trimIndent()
        val messages = JSONArray().put(
            JSONObject().put("role", "system")
                .put("content", "你是 Android App 操作记忆整理器。只输出记忆正文, 不要解释。")
        ).put(JSONObject().put("role", "user").put("content", prompt))
        val r = try {
            LlmClient.chat(provider, messages, 20_000)
        } catch (e: Exception) {
            Log.w(TAG, "auto memory failed: ${e.message}")
            return
        }
        val text = r.content.trim()
        if (!r.ok || text.length < 8 || text.contains("暂无稳定")) return
        val writeResult = AppMemory.write(context, pkg, text.take(600), append = true)
        Log.i(TAG, "auto memory [$pkg]: $writeResult")
        if (!writeResult.startsWith("写入失败")) {
            onEvent(Event("system", "已更新 App 记忆 ($pkg)"))
        }
    }

    /**
     * 工具结果回灌消息。
     * 长页 OCR/整页树可能几万字, 原样塞回上下文会让后续每一轮都变慢; 超出上限时截断并明确提示。
     */
    private fun toolMessage(callId: String, content: String): JSONObject {
        val text = if (content.length > MAX_TOOL_MESSAGE_CHARS) {
            content.take(MAX_TOOL_MESSAGE_CHARS) +
                "\n...[工具结果过长已截断: 原长 ${content.length} 字符; " +
                "需要更多内容请用更精确的参数/范围重新读取]"
        } else {
            content
        }
        return JSONObject()
            .put("role", "tool")
            .put("tool_call_id", callId)
            .put("content", text)
    }

    /**
     * 带心跳的 LLM 调用。
     * 大上下文首字可能很慢, 若一直静默, 用户会以为 Agent 卡死;
     * 超过 20 秒就每隔 15 秒发一条系统事件, 同时暴露上下文规模。
     */
    private suspend fun chatWithHeartbeat(
        provider: LlmSettings.Provider,
        messages: JSONArray,
        tools: JSONArray,
        step: Int,
        ctxChars: Int,
        onEvent: (Event) -> Unit,
        onStreamContent: (String) -> Unit,
        onStreamReasoning: (String) -> Unit
    ): LlmClient.LlmResult = coroutineScope {
        val started = System.currentTimeMillis()
        var lastDelta = started
        var gotContent = false
        var gotReasoning = false
        val heartbeat = launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                val now = System.currentTimeMillis()
                if (now - lastDelta >= 20_000) {
                    lastDelta = now
                    onEvent(
                        Event(
                            "system",
                            "Step $step 等待模型响应 ${(now - started) / 1000}s · " +
                                "上下文 ${messages.length()} 条 / ${ctxChars} 字"
                        )
                    )
                }
            }
        }
        try {
            val r = LlmClient.chatWithToolsStream(
                provider, messages, tools,
                onReasoning = {
                    gotReasoning = true
                    lastDelta = System.currentTimeMillis()
                    onStreamReasoning(it)
                },
                onContent = {
                    gotContent = true
                    lastDelta = System.currentTimeMillis()
                    onStreamContent(it)
                }
            )
            // 部分服务端不支持 stream_options / SSE: 未产生任何增量时回退到普通请求
            if (!r.ok && !gotContent && !gotReasoning &&
                (r.error.contains("HTTP 4") || r.error.contains("stream", true) ||
                    r.error.contains("SSE", true))
            ) {
                val fallback = LlmClient.chatWithTools(provider, messages, tools)
                if (fallback.ok) {
                    if (fallback.reasoning.isNotBlank()) onStreamReasoning(fallback.reasoning)
                    if (fallback.content.isNotBlank()) onStreamContent(fallback.content)
                    return@coroutineScope fallback
                }
            }
            // 服务端不支持 SSE 时 chatWithToolsStream 会走普通 JSON, 这里补一次回调
            if (r.ok && !gotReasoning && r.reasoning.isNotBlank()) {
                onStreamReasoning(r.reasoning)
            }
            if (r.ok && !gotContent && r.content.isNotBlank()) {
                onStreamContent(r.content)
            }
            r
        } finally {
            heartbeat.cancel()
        }
    }

    /**
     * 功能: 构造 OpenAI 规范的 assistant 消息
     * 参数:
     *   content   助手文本 (可为空)
     *   toolCalls 工具调用列表
     * 返回: JSONObject
     * 说明: content 为空时必须传 null 而不是 "", 部分服务端会因此拒绝
     */
    private fun assistantMessage(
        content: String,
        toolCalls: List<LlmClient.ToolCall>
    ): JSONObject {
        val msg = JSONObject().put("role", "assistant")
        if (content.isBlank()) msg.put("content", JSONObject.NULL) else msg.put("content", content)
        val arr = JSONArray()
        toolCalls.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", c.name)
                        .put("arguments", c.arguments))
            )
        }
        msg.put("tool_calls", arr)
        return msg
    }

    /** 从 finish 的参数里取 summary, 解析失败时直接把原始参数当结论 */
    private fun parseSummary(args: String): String {
        return try {
            JSONObject(args).optString("summary").ifBlank { args }
        } catch (e: Exception) {
            args
        }
    }

    /**
     * 功能: 控制历史长度
     * 参数: messages 可变消息数组 (会原地修改)
     * 返回: 无
     * 说明: 同时按条数和总字符预算裁剪; 任务目标已放进 system, 不依赖最老的 user 消息。
     *       不能把 tool 消息和它前面的 assistant(tool_calls) 拆散, 否则接口报错,
     *       所以裁剪以 assistant 消息为边界, 整块丢弃最老的 assistant + 其工具结果。
     */
    private fun trimHistory(messages: JSONArray) {
        val beforeCount = messages.length()
        val beforeChars = totalChars(messages)

        if (messages.length() > MAX_MESSAGES) {
            val keep = ArrayList<JSONObject>()
            keep.add(messages.getJSONObject(0))
            // 找到第一个可以安全丢弃的点: 一条 assistant 消息
            var cut = 1
            for (i in 1 until messages.length()) {
                if (messages.getJSONObject(i).optString("role") == "assistant") {
                    cut = i
                    break
                }
            }
            // 从 cut 之后开始保留, 直到长度符合上限
            var start = maxOf(cut, messages.length() - MAX_MESSAGES + 1)
            // start 必须落在 assistant 上: 否则会留下没有 assistant(tool_calls) 的孤儿 tool 消息,
            // 部分 OpenAI 兼容服务端会直接报错, 表现为长任务突然卡住/失败。
            var adjusted = -1
            for (i in start until messages.length()) {
                if (messages.getJSONObject(i).optString("role") == "assistant") {
                    adjusted = i
                    break
                }
            }
            start = if (adjusted >= 0) adjusted else cut
            for (i in start until messages.length()) {
                keep.add(messages.getJSONObject(i))
            }
            rebuild(messages, keep)
        }

        // 再按总字符预算裁剪; 一次丢一个「assistant + 它后面的 tool 结果」块
        var guard = 0
        while (messages.length() > 2 &&
            totalChars(messages) > MAX_CONTEXT_CHARS &&
            guard++ < 20
        ) {
            var firstAssistant = -1
            for (i in 1 until messages.length()) {
                if (messages.getJSONObject(i).optString("role") == "assistant") {
                    firstAssistant = i
                    break
                }
            }
            if (firstAssistant <= 0) break
            var nextAssistant = messages.length()
            for (i in firstAssistant + 1 until messages.length()) {
                if (messages.getJSONObject(i).optString("role") == "assistant") {
                    nextAssistant = i
                    break
                }
            }
            val keep = ArrayList<JSONObject>()
            keep.add(messages.getJSONObject(0))
            val from = if (nextAssistant < messages.length()) nextAssistant else firstAssistant
            for (i in from until messages.length()) keep.add(messages.getJSONObject(i))
            if (keep.size >= messages.length()) break
            rebuild(messages, keep)
        }

        val afterChars = totalChars(messages)
        if (messages.length() != beforeCount || afterChars != beforeChars) {
            Log.i(
                TAG,
                "trim context: msgs $beforeCount->${messages.length()} chars $beforeChars->$afterChars"
            )
        }
    }

    private fun totalChars(messages: JSONArray): Int {
        var n = 0
        for (i in 0 until messages.length()) n += messages.getJSONObject(i).toString().length
        return n
    }

    private fun rebuild(messages: JSONArray, keep: List<JSONObject>) {
        while (messages.length() > 0) messages.remove(0)
        keep.forEach { messages.put(it) }
    }
}
