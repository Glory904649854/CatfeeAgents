package com.catfee.rootauto.agent

import android.content.Context
import android.util.Log
import com.catfee.rootauto.device.DeviceCapabilities
import com.catfee.rootauto.llm.DecisionClient
import com.catfee.rootauto.llm.DecisionSettings
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

    /** 整体任务超时: 覆盖较长的手机操作流程, 同时避免任务无限运行 */
    private const val TOTAL_TIMEOUT_MS = 2 * 60 * 60 * 1000L

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
        "media_state", "media_speed", "wait_media",
        "verify_text", "verify_option", "verify_question", "verify_changed",
        "tap", "tap_text", "tap_node", "tap_id", "tap_desc", "double_tap", "long_press",
        "swipe", "swipe_direction", "key", "input_text", "clear_text", "set_text_id",
        "launch_app", "launch_activity", "stop_app", "open_url",
        "settings_get", "settings_put", "shell",
        "file_list", "file_read", "file_write",
        "app_info", "get_prop", "list_packages", "answer_form"
    )

    /** 完全相同且连续重复时大概率是卡死的动作类工具; 滚动/等待类不在此列 */
    private val REPEAT_BLOCK_TOOLS = setOf(
        "tap", "tap_text", "tap_node", "tap_id", "tap_desc", "double_tap", "long_press",
        "input_text", "clear_text", "set_text_id"
    )

    /** 每轮最多一个有副作用的工具调用。finish/观察/等待不占用 mutation 槽位。 */
    private val MUTATION_TOOLS = setOf(
        "tap", "tap_text", "tap_node", "tap_id", "tap_desc", "double_tap", "long_press",
        "swipe", "swipe_direction", "input_text", "clear_text", "set_text_id", "key",
        "launch_app", "launch_activity", "stop_app", "open_url", "media_speed",
        "settings_put", "file_write", "answer_form"
    )

    private val SETTLE_TOOLS = setOf(
        "tap", "tap_text", "tap_node", "tap_id", "tap_desc", "double_tap", "long_press",
        "swipe", "swipe_direction", "input_text", "clear_text", "set_text_id", "key",
        "launch_app", "launch_activity", "stop_app", "open_url", "media_speed", "answer_form"
    )

    private val READ_ONLY_TOOLS = setOf(
        "observe", "read_page", "find_text", "ocr", "see_screen", "get_activity", "screenshot",
        "wait", "wait_for_text", "wait_media", "media_state", "verify_text", "verify_option",
        "verify_question", "verify_changed", "ask_llm", "second_opinion", "tool_list", "load_tools",
        "shell", "note", "todo_read", "todo_write", "memory_read", "memory_list", "finish"
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
        你叫 CatfeeAgents, 同时是聊天助手和 Android 操作 Agent。

        【聊天模式】
        - 普通问答、解释、附件分析: 直接回答, 不调用工具, 不 observe, 不 finish。
        - 用户明确要求查看/操作手机时, 先调用 enter_agent_mode; 授权后设备工具才会出现。
        - 图片/文件已由 App 本地 OCR/解析, 解析文本附在用户消息里; 直接基于文本回答,
          不要假装看到了原始图片。
        - 回答简洁; 用户没有要求长篇时, 控制在几句话内。

        【手机任务模式】
        只有用户明确要求查看/操作手机时才进入。
        - 会话上下文里的旧任务/清单/结果只是参考; 当前用户消息优先。
          如果当前消息是新问题/新任务, 直接处理新消息, 不要执行旧任务;
          如果是在追问、补充、省略主语的“继续”, 才沿用旧任务清单和进度, 从未完成项继续,
          不要重新猜任务, 也不要从头重复已完成项。由你按语义判断, 不要只按关键词。
          如果检查点为空或互相矛盾, 先查 todo/记忆/页面进度; 仍无法确定就 finish 请用户
          明确要继续哪一项, 不要随机切换页面硬猜。
        - 每次只做一步。调用工具时 content 只写一句不超过 20 字的状态, 或直接留空;
          不要复述计划、解释原因、输出步骤清单; 最终 finish summary 不超过 3 句。
        - 坐标: observe 第一行给出 Screen: 宽x高 rotation=角度; tap/tap_node 一律用这个
          当前显示坐标, 不要用截图缩放后的像素或 0~1000 归一化值; 越界会被拒绝。
          see_screen 会同时给显示尺寸和图片尺寸, 按提示换算到显示坐标。
        - 工具返回“已发送点击”只代表注入成功, 不代表页面响应; 点击后必须
          observe/verify_changed 验证; 没变化就换 tap_node/find_text/ocr/坐标, 不要重复同一调用。
        - 同一控件或子目标连续 2 次无效就换方法; 3 次仍失败标记 blocked 并继续下一项;
          不要在一个按钮/一个弹窗上耗超过 5 步。
        - 仅当用户明确提出播放、暂停、控制、查看或处理音视频等媒体需求时, 才启用媒体工具。
          这类任务先用 media_state 读取当前时间、总时长、倍速和播放状态, 再根据用户目标
          选择 media_speed 或 wait_media; 不要猜测用户想要的倍速、观看比例或结束条件。
          wait_media 的 target_ratio 只有在用户或当前页面明确给出目标比例时才设置, 否则使用默认目标。
          需要等待时按播放进度分段等待, 不要拿旧页面的进度判断当前媒体, 也不要因控件暂时隐藏
          就擅自退出或切换页面。控制条/倍速菜单优先使用节点或文字定位, 坐标必须来自当前观察结果。
        - read_page 里标了 [屏外] 的节点不要直接 tap; 先 swipe/scroll_to_text 滚到屏幕内,
          再用新坐标/tap_node。坐标越界时不要重复试同一坐标。
        - 长任务先盘点: 先列出可执行项/未完成项并 todo_write; 逐项处理,
          每完成或失败都更新; 不要从上次页面直接假设当前任务。
        - 用户明确排除的标记/颜色/项目不要执行, 不要自行反转用户意图;
          指令含糊时按字面执行, 并在 finish 说明取捨。
        - 列表角标、颜色、图标只是线索, 不能单独作为完成/选中状态。状态以页面明确文字,
          进度指示、已显示结果或选项 checked 为准; 视觉判断与页面文字冲突时以文字为准。
        - 有明确的状态、进度或筛选入口时, 优先用它盘点, 不要逐个点列表项猜测;
          逐项执行后必须验证实际结果, 失败先换入口/换方法, 仍失败就标 blocked 继续下一项。
        - 观察要省: 先 observe; read_page/screenshot/long_ocr/see_screen 只在需要时用;
          同一页面连续 2 次无变化就禁止再观察, 改动作或 finish。
        - shell 只做只读查询; 禁止 input/sendevent 等 UI 注入; 点击/滑动/按键必须用原子工具。
        - 默认只有核心工具。需要长截图/视觉/表单/文件/shell 等高级能力时,
          先 load_tools 加载对应组 (inspect/form/input/apps/system/files/memory/all),
          不要因为工具不在列表就放弃; 不同 App 优先 tap_node(text/desc/id), 自绘/WebView 用 ocr,
          选项用 list_options, 不假设某个 App 的固定控件。
        - 仅当用户明确要求填写、回答、核对或提交表单/问卷等内容时, 才启用表单工具。
          先让相关内容显示在当前页面, 再按需 load_tools(form) 和 answer_form; 不要把普通页面、
          内容浏览或任何 App 默认当成答题任务。提交前确认目标、填写内容和页面状态; 提交后验证结果。
        - 提交/支付/删除/发送/改设置前后至少两种独立证据; 证据冲突就停下重读页面。
        - 要进入列表某项, 先回列表用 find_text 或 scroll_to_text 定位, 不要逐页翻。
        - 先处理弹窗; 单个子目标超过 15 步仍没接近就重新规划或标记 blocked,
          继续其他子目标, 不要整单卡死。
        - 完成、部分完成或无法继续都必须 finish; summary 写结果、关键数据、blocked 项, 不超过 3 句。
        - 任务完成后用 memory_write 记稳定入口/控件/坑(不超过 300 字),
          不记答案或一次性数据。
        - 不要做无关操作; 不要清数据、卸载应用、改系统设置。
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
            { _, _ -> true },
        claimAgentMode: () -> String? = { null },
        onPhase: (phase: String, step: Int, detail: String) -> Unit = { _, _, _ -> },
        awaitIfPaused: suspend () -> Unit = {}
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
        val caps = DeviceCapabilities.refresh(context)
        val nonRootHint = if (caps.root) {
            ""
        } else {
            "\n\n===== 非 Root 模式提示 =====\n" +
                "当前通过无障碍读屏和手势操作：启动 App 后如果出现开屏广告/弹窗，优先点击" +
                "「跳过 / 关闭 / 稍后再说 / 知道了」；不要用返回键退出 App，返回键可能直接回到桌面。" +
                "截图/OCR 使用系统无障碍截图，可能偶发限流；失败时改用 observe/read_page。"
        }
        val startPkg = AppMemory.currentPackage()
        val traceRun = AgentTrace.startTask(context, goal, provider.model, SYSTEM_PROMPT, startPkg)
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
        }
        val systemMessage = JSONObject().put("role", "system").put("content", SYSTEM_PROMPT)
        val messages = JSONArray().put(systemMessage).put(
            JSONObject().put("role", "user").put("content", firstUser)
        )
        var mutationCount = 0
        // 工具可见性按任务阶段变化: 聊天 -> enter_agent_mode -> 核心工具/按需组
        val toolSession = AgentTools.Session()
        toolSession.caps = caps
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
        var lastToolName = ""
        var lastToolArgs = ""
        var lastToolResult = ""
        var decisionFastRun = 0
        var decisionFailures = 0
        var decisionDisabled = false
        val decisionConfig = DecisionSettings.load(context)

        try {
            for (step in 1..MAX_STEPS) {
                currentStep = step
                mutationCount = 0
                awaitIfPaused()
                val stepStart = System.currentTimeMillis()
                if (System.currentTimeMillis() - started > TOTAL_TIMEOUT_MS) {
                    onPhase("error", step, "任务总时长超时")
                    onEvent(Event("error", "任务总时长超过 $TOTAL_TIMEOUT_MS ms, 已中断"))
                    AgentTrace.finish(context, traceRun, false, "超时中断", step)
                    return "超时中断"
                }
                onPhase("waiting_llm", step, "")
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
                        "用 verify_changed 确认, 或 find_text 重新定位, 换一种做法。")
                }
                if (noChangeCount >= 4) {
                    hints.append("\n\n[系统提醒] 当前子目标已连续 $noChangeCount 次无变化, 很可能点不到/控件不可用。 " +
                        "禁止继续重复同一个控件; 返回上层、换入口, 或标记 blocked 后继续其他子目标。")
                }
                if (observationOnly >= 3) {
                    hints.append("\n\n[系统提醒] 你已经连续观察 $observationOnly 次, 请给出实际动作或 finish, 不要只观察。")
                }
                if (observationOnly >= 5) {
                    hints.append("\n\n[系统提醒] 连续观察 $observationOnly 次仍未动作, 当前页面信息已经足够; " +
                        "立即执行一个动作, 或 finish 说明障碍。")
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
                // 复用同一个 system 消息；不要每轮 put(0) 生成孤儿历史并让裁剪越来越重。
                systemMessage.put(
                    "content",
                    SYSTEM_PROMPT +
                        nonRootHint +
                        "\n\n===== 任务目标 =====\n" + goal +
                        "\n\n===== 设备能力 =====\n" + caps.summary() +
                        (if (notes.isNotEmpty()) "\n\n===== 已记录的信息 =====\n${notes.toString().take(MAX_NOTES_CHARS)}" else "") +
                        hints.toString().take(MAX_TODO_CHARS + 2_000)
                )

                // 长任务保护: 网络抖动/限流时自动重试, 不要一次失败就整单结束
                val lastInput = messages.optJSONObject(messages.length() - 1)
                    ?.optString("content").orEmpty()
                val ctxChars = messages.toString().length
                val ctxMsgs = messages.length()
                val tools = AgentTools.definitions(toolSession)
                onContext(ctxMsgs, ctxChars)

                // DECISION 模式: 高置信度时只接管 observe / wait 这类安全动作,
                // 点击、输入、finish 仍必须由主模型决定, 避免分类器误操作。
                val fastDecision = try {
                    if (decisionConfig.enabled && decisionConfig.usable && !decisionDisabled &&
                        toolSession.agentMode && !toolSession.agentBlocked &&
                        step > 1 && decisionFastRun < decisionConfig.maxFastSteps &&
                        sameCallCount < 3 && noChangeCount < 2 &&
                        lastToolResult.isNotBlank()
                    ) {
                        onPhase("waiting_llm", step, "DECISION 快速判断")
                        val decisionState = JSONObject()
                            .put("goal", goal.take(600))
                            .put("step", step)
                            .put("last_tool", lastToolName.ifBlank { "none" })
                            .put("last_arguments", lastToolArgs.take(240))
                            .put("observation", lastToolResult.take(1400))
                            .put("foreground_app", AppMemory.currentPackage())
                        DecisionClient.decide(decisionConfig, decisionState).also { outcome ->
                            if (!outcome.ok) {
                                decisionFailures++
                                if (decisionFailures >= 2) {
                                    decisionDisabled = true
                                    Log.w(TAG, "DECISION disabled after repeated failures: ${outcome.error}")
                                }
                            }
                        }
                    } else {
                        null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "decision failed: ${e.message}")
                    decisionFailures++
                    if (decisionFailures >= 2) decisionDisabled = true
                    null
                }

                val fastAction = fastDecision?.takeIf {
                    it.ok && it.confidence >= decisionConfig.minConfidence &&
                        it.action in setOf("observe", "wait") &&
                        !(it.action == "observe" && lastToolName == "observe") &&
                        !(it.action == "wait" && lastToolName == "wait")
                }?.action

                var r: LlmClient.LlmResult
                if (fastAction != null) {
                    decisionFastRun++
                    val args = if (fastAction == "wait") "{\"ms\":800}" else "{}"
                    r = LlmClient.LlmResult(
                        ok = true,
                        toolCalls = listOf(
                            LlmClient.ToolCall(
                                id = "decision_${step}_${System.currentTimeMillis()}",
                                name = fastAction,
                                arguments = args
                            )
                        ),
                        elapsedMs = fastDecision?.latencyMs ?: 0
                    )
                    val pct = ((fastDecision?.confidence ?: 0.0) * 100).toInt()
                    val msg = "DECISION 快速路径: $fastAction (置信度 ${pct}%, " +
                        "${fastDecision?.latencyMs ?: 0}ms)，已跳过本轮主模型"
                    onEvent(Event("system", msg))
                    AgentTrace.event(context, traceRun, "info", "DECISION 快速路径 · Step $step", msg)
                } else {
                    decisionFastRun = 0
                    r = chatWithHeartbeat(
                        provider, messages, tools, step, ctxChars, onEvent,
                        onStreamContent, onStreamReasoning
                    )
                    var attempt = 1
                    while (!r.ok && attempt < 3) {
                        onPhase("waiting_llm", step, "请求失败, 第 ${attempt + 1} 次重试")
                        val retryMsg = "LLM 请求失败(第 $attempt 次): ${r.error}; 5 秒后重试"
                        onEvent(Event("system", retryMsg))
                        AgentTrace.event(context, traceRun, "warn", "LLM 重试 · Step $step", retryMsg)
                        kotlinx.coroutines.delay(5000)
                        awaitIfPaused()
                        attempt++
                        r = chatWithHeartbeat(
                            provider, messages, tools, step, ctxChars, onEvent,
                            onStreamContent, onStreamReasoning
                        )
                    }
                    if (!r.ok) {
                        onPhase("error", step, "LLM 请求失败")
                        onEvent(Event("error", "第 $step 轮请求失败(已重试 2 次): ${r.error}"))
                        AgentTrace.finish(context, traceRun, false, "LLM 请求失败: ${r.error}", step)
                        return "LLM 请求失败: ${r.error}"
                    }
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
                            context, traceRun, step, lastInput, r.content,
                            emptyList(), emptyList(), llmMs, r.reasoning, ctxChars, ctxMsgs
                        )
                        AgentTrace.finish(context, traceRun, true, r.content, step)
                        return r.content
                    }
                    if (toolSession.agentBlocked) {
                        if (step <= 2) {
                            messages.put(
                                JSONObject().put("role", "user")
                                    .put("content", "当前不能进入手机操作模式, 请直接用文字回答用户, 并提示稍后点重试。")
                            )
                            continue
                        }
                        return "当前不能进入手机操作模式：另一个会话的 Agent 正在操作手机。请稍后重试。"
                    }
                    if (step == 1) {
                        messages.put(
                            JSONObject().put("role", "user")
                                .put("content", "请直接回答用户, 或使用工具继续执行任务。")
                        )
                        continue
                    }
                    onEvent(Event("error", "第 $step 轮模型没有返回内容"))
                    AgentTrace.finish(context, traceRun, false, "模型未返回内容", step)
                    return "模型未返回内容"
                }

                // 助手消息必须原样回灌 (含 tool_calls), 否则下一轮接口会报错
                messages.put(assistantMessage(r.content, r.toolCalls))

                val toolTraces = ArrayList<AgentTrace.ToolTrace>()
                for (call in r.toolCalls) {
                    awaitIfPaused()
                    val t0 = System.currentTimeMillis()
                    onPhase("operating", step, call.name)
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
                            AgentTrace.finish(context, traceRun, false, "重复调用过多, 任务终止", step)
                            return "重复调用过多, 任务终止"
                        }
                        continue
                    }
                    if (call.name in MUTATION_TOOLS && mutationCount >= 1) {
                        val blocked = "BLOCKED: 本轮最多执行一个设备变更动作。请先观察/验证上一步结果, 下一轮再继续。"
                        onEvent(Event("system", blocked, tool = call.name))
                        toolTraces.add(AgentTrace.ToolTrace(call.name, call.arguments, blocked,
                            System.currentTimeMillis() - t0, false))
                        messages.put(toolMessage(call.id, blocked))
                        continue
                    }
                    if (!toolSession.agentMode && call.name != "enter_agent_mode") {
                        val blocked = "BLOCKED: 聊天模式没有设备工具。需要查看/操作手机时先调用 enter_agent_mode。"
                        onEvent(Event("system", blocked))
                        toolTraces.add(
                            AgentTrace.ToolTrace(
                                call.name, call.arguments, blocked,
                                System.currentTimeMillis() - t0, false
                            )
                        )
                        messages.put(toolMessage(call.id, blocked))
                        continue
                    }
                    if (!AgentTools.isToolEnabled(toolSession, call.name)) {
                        val blocked = "工具 ${call.name} 当前未加载。需要高级能力时先 load_tools " +
                            "加载对应组 (inspect/form/input/apps/system/files/memory/all)。"
                        onEvent(Event("system", blocked))
                        toolTraces.add(
                            AgentTrace.ToolTrace(
                                call.name, call.arguments, blocked,
                                System.currentTimeMillis() - t0, false
                            )
                        )
                        messages.put(toolMessage(call.id, blocked))
                        continue
                    }
                    if (call.name == "enter_agent_mode") {
                        if (toolSession.agentMode) {
                            val already = "已经在手机操作模式, 直接继续任务。"
                            onEvent(Event("system", already))
                            toolTraces.add(
                                AgentTrace.ToolTrace(
                                    call.name, call.arguments, already,
                                    System.currentTimeMillis() - t0
                                )
                            )
                            messages.put(toolMessage(call.id, already))
                            continue
                        }
                        if (!toolSession.caps.accessibility && !toolSession.caps.root) {
                            val blocked = "当前设备未开启无障碍且没有 Root，无法操作手机。" +
                                "请让用户到系统设置-无障碍中开启 CatfeeAgents 后重试。"
                            onEvent(Event("conflict", blocked, tool = call.name))
                            toolSession.agentBlocked = true
                            messages.put(toolMessage(call.id, blocked))
                            continue
                        }
                        val denied = claimAgentMode()
                        if (denied != null) {
                            toolSession.agentBlocked = true
                            val blocked = "$denied 本次没有进入手机操作模式。不要继续调用 enter_agent_mode, " +
                                "请直接用文字回答用户, 并提示他/她稍后点重试。"
                            onEvent(Event("conflict", blocked, tool = call.name))
                            toolTraces.add(
                                AgentTrace.ToolTrace(
                                    call.name, call.arguments, blocked,
                                    System.currentTimeMillis() - t0
                                )
                            )
                            messages.put(toolMessage(call.id, blocked))
                            continue
                        }
                        if (!phoneGranted) {
                            val allowed = ensurePhonePermission(call.name, call.arguments)
                            awaitIfPaused()
                            if (!allowed) {
                                AgentTrace.finish(context, traceRun, false, "用户未允许控制手机", step)
                                return "用户未允许控制手机"
                            }
                            phoneGranted = true
                        }
                        toolSession.agentMode = true
                        AgentTools.activateForGoal(toolSession, goal)
                        val entered = "已进入手机操作模式, 核心工具已加载。先 observe 确认页面; " +
                            "需要长截图/视觉/答题/文件/shell 等高级能力时调用 load_tools。"
                        onEvent(Event("result", entered, tool = call.name))
                        toolTraces.add(
                            AgentTrace.ToolTrace(
                                call.name, call.arguments, entered,
                                System.currentTimeMillis() - t0
                            )
                        )
                        messages.put(toolMessage(call.id, entered))
                        continue
                    }
                    if (call.name == "load_tools") {
                        val groups = try {
                            JSONObject(call.arguments).optString("groups")
                        } catch (e: Exception) {
                            ""
                        }
                        val loaded = AgentTools.loadGroups(toolSession, groups)
                        onEvent(Event("result", loaded, tool = call.name))
                        toolTraces.add(
                            AgentTrace.ToolTrace(
                                call.name, call.arguments, loaded,
                                System.currentTimeMillis() - t0
                            )
                        )
                        messages.put(toolMessage(call.id, loaded))
                        continue
                    }
                    if (call.name in DEVICE_TOOLS) usedDeviceTool = true
                    if (call.name in DEVICE_TOOLS && !phoneGranted) {
                        val allowed = ensurePhonePermission(call.name, call.arguments)
                        if (!allowed) {
                            AgentTrace.finish(context, traceRun, false, "用户未允许控制手机", step)
                            return "用户未允许控制手机"
                        }
                        phoneGranted = true
                    }
                    if (call.name == "finish") {
                        onPhase("done", step, "任务完成")
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
                            context, traceRun, step, lastInput, r.content,
                            r.toolCalls.map { it.name to it.arguments },
                            toolTraces, llmMs, r.reasoning, ctxChars, ctxMsgs
                        )
                        AgentTrace.finish(context, traceRun, true, summary, step)
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
                    if (call.name in MUTATION_TOOLS) mutationCount++
                    val result = AgentTools.execute(
                        context, provider, call.name, call.arguments, toolSession, awaitIfPaused
                    )
                    if (call.name == "memory_write" && !result.startsWith("写入失败")) {
                        memoryWritten = true
                    }
                    val elapsed = System.currentTimeMillis() - t0
                    val lowerResult = result.lowercase()
                    val toolOk = !(result.startsWith("失败") || result.startsWith("错误") ||
                        result.startsWith("Exception") || result.contains("执行失败") ||
                        result.startsWith("BLOCKED", ignoreCase = true) ||
                        result.contains("未检测到") || result.contains("未找到") ||
                        result.contains("未读到完整时长") || result.contains("没有前进") ||
                        result.contains("停滞") || result.contains("停在") ||
                        result.contains("坐标越界") || result.contains("坐标无法") ||
                        result.contains("点击失败") || result.contains("双击失败") ||
                        result.contains("长按失败") || result.contains("滑动失败") ||
                        result.contains("输入失败") || result.contains("写入失败") ||
                        result.contains("可能已暂停") || result.contains("读不到播放时间") ||
                        result.contains("没有找到倍速") || result.contains("不要用长 wait") ||
                        lowerResult.contains("timeout"))
                    toolTraces.add(
                        AgentTrace.ToolTrace(call.name, call.arguments, result, elapsed, toolOk)
                    )
                    onEvent(Event("result", result.take(400), tool = call.name))
                    lastToolName = call.name
                    lastToolArgs = call.arguments
                    lastToolResult = result
                    messages.put(toolMessage(call.id, result))
                    awaitIfPaused()
                    // 只有可能改变页面的动作才需要 settle；只读工具不能额外固定等待 800ms。
                    if (call.name in SETTLE_TOOLS) {
                        kotlinx.coroutines.delay(AgentTools.DEFAULT_SETTLE_MS)
                        awaitIfPaused()
                    }

                    // 无进展检测: 只读工具算观察, 动作工具看界面指纹是否变化
                    val readOnly = call.name in READ_ONLY_TOOLS
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
                    context, traceRun, step, lastInput, r.content,
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
            AgentTrace.finish(context, traceRun, false, "达到最大轮数, 任务未完成", MAX_STEPS)
            return "达到最大轮数, 任务未完成"
        } catch (e: CancellationException) {
            // 用户主动停止, 不算错误
            onEvent(Event("error", "任务已被用户停止"))
            AgentTrace.finish(context, traceRun, false, "用户停止", currentStep)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "任务执行异常: ${e.message}")
            onEvent(Event("error", "任务异常: ${e.message}"))
            AgentTrace.finish(context, traceRun, false, "任务异常: ${e.message}", currentStep)
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
