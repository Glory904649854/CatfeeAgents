package com.catfee.rootauto.agent

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.catfee.rootauto.a11y.UiObserverService
import com.catfee.rootauto.device.AppLauncher
import com.catfee.rootauto.device.Capabilities
import com.catfee.rootauto.device.DeviceCapabilities
import com.catfee.rootauto.llm.LlmSettings
import com.catfee.rootauto.ocr.LongOcr
import com.catfee.rootauto.root.DaemonClient
import com.catfee.rootauto.root.InputDispatcher
import com.catfee.rootauto.root.RootShell
import com.catfee.rootauto.root.ScreenGeometry
import com.catfee.rootauto.root.ScreenVision
import org.json.JSONArray
import org.json.JSONObject

/**
 * AgentTools —— Agent 可调用的原子工具集
 *
 * 功能描述:
 *   把「看屏幕 / 点 / 滑 / 输入 / 启应用 / 等」这些最底层的手机操作
 *   包装成 OpenAI function calling 的工具定义与执行器。
 *   工具尽量原子化, 由 LLM 自己组合出复杂流程, 而不是把业务逻辑写死。
 *
 * 参数说明:
 *   execute() 的 provider 仅用于视觉降级 (无障碍读不到内容时截图问模型)。
 *
 * 返回值说明:
 *   execute() 统一返回一段纯文本, 直接作为 tool 消息回灌给模型。
 *
 * 使用场景:
 *   ReActAgent 每一轮的工具调用。
 *
 * 修改时间: 2026-11-03
 */
object AgentTools {

    private const val TAG = "AgentTools"

    /** observe/read_page/see_screen 的去重状态必须按任务隔离, 不能让上一个会话污染当前会话。 */

    /** observe 之后等待界面稳定的默认时间, 太短会读到旧界面 */
    const val DEFAULT_SETTLE_MS = 800L

    /** 单次任务内的工具可见性状态, 不跨任务共享 */
    class Session {
        var agentMode: Boolean = false
        var agentBlocked: Boolean = false
        val activeGroups: MutableSet<String> = mutableSetOf()
        /** 本次 run 的设备能力快照, 由 ReActAgent 启动时刷新写入 */
        var caps: Capabilities = DeviceCapabilities.current()
        /** Web 任务策略: 设备即使有 Root, 也可以按任务禁用增强工具 */
        var allowRootTools: Boolean = true
        /** Web 任务策略: 是否允许截图/视觉工具 */
        var allowScreenshot: Boolean = true

        // 观察/验证/视觉缓存按一次 Agent run 隔离，避免跨会话返回过期结果。
        // lastObservedHash 同时供 observe 去重和 verify_changed 做动作前后比对。
        var lastObservedHash: String = ""
        var lastOcrHash: String = ""
        var lastReadPageHash: String = ""
        var lastSeeHash: String = ""
        var lastSeeQuestion: String = ""
        var lastSeeText: String = ""
        val mediaCache: MediaState.Cache = MediaState.Cache()
    }

    /**
     * 设备模式的核心工具: 覆盖观察、点击、输入、等待、校验、记忆和结束。
     * 其余能力放在 GROUP_TOOLS 里按需加载, 避免每轮给模型塞 50+ 个 schema。
     */
    private val CORE_TOOLS = setOf(
        "observe", "tap", "tap_node", "swipe", "input_text", "key",
        "launch_app", "open_url", "wait", "wait_for_text",
        "media_state", "media_speed", "wait_media",
        "find_text", "scroll_to_text", "get_activity", "ocr",
        "verify_text", "verify_changed", "note", "todo_write",
        "memory_read", "memory_write", "finish"
    )

    private val GROUP_TOOLS: Map<String, Set<String>> = mapOf(
        "inspect" to setOf("read_page", "screenshot", "long_ocr", "see_screen"),
        "form" to setOf(
            "list_options", "verify_option", "verify_question",
            "answer_form", "ask_llm", "second_opinion"
        ),
        "input" to setOf(
            "long_press", "double_tap", "swipe_direction",
            "clear_text", "set_text_id"
        ),
        "apps" to setOf(
            "launch_activity", "stop_app", "list_packages", "app_info", "get_prop"
        ),
        "system" to setOf("shell", "settings_get", "settings_put"),
        "files" to setOf("file_list", "file_read", "file_write"),
        "memory" to setOf("memory_list", "todo_read")
    )

    /** 只有 Root 才能可靠执行的工具 */
    private val ROOT_ONLY_TOOLS = setOf(
        "shell", "settings_put", "file_read", "file_write", "file_list", "stop_app"
    )

    /** 需要截图能力的工具 */
    private val SCREENSHOT_TOOLS = setOf(
        "screenshot", "ocr", "long_ocr", "see_screen",
        "verify_option", "verify_question", "answer_form"
    )

    /** 需要坐标/按键注入能力的工具 */
    private val GESTURE_TOOLS = setOf(
        "tap", "swipe", "long_press", "double_tap", "swipe_direction",
        "key", "media_speed", "wait_media", "scroll_to_text"
    )

    /** 需要无障碍读树/节点操作的工具 */
    private val A11Y_TOOLS = setOf(
        "observe", "read_page", "find_text", "tap_text", "tap_node", "tap_desc", "tap_id",
        "input_text", "clear_text", "set_text_id", "list_options",
        "verify_text", "verify_changed", "wait_for_text"
    )
    /** 能力不足时返回给 Agent 的可行动原因; null 表示可用 */
    fun capabilityBlockReason(session: Session, name: String): String? {
        val caps = session.caps
        if (name in ROOT_ONLY_TOOLS && !caps.root) {
            return "当前为非 Root 模式，$name 不可用。改用 observe/find_text/tap_text/" +
                "input_text 等无障碍工具，不要反复尝试系统级工具。"
        }
        if (name in ROOT_ONLY_TOOLS && !session.allowRootTools) {
            return "该 Web 任务未授权 Root 增强能力，$name 不可用。" +
                "请改用无障碍工具 observe/find_text/tap_text/input_text。"
        }
        if (name in SCREENSHOT_TOOLS && !caps.hasScreenshot) {
            return "当前设备截图不可用（未 Root，或 Android 版本低于 11），$name 不可用。" +
                "改用 observe/read_page/find_text。"
        }
        if (name in SCREENSHOT_TOOLS && !session.allowScreenshot) {
            return "该 Web 任务未授权截图/视觉能力，$name 不可用。" +
                "改用 observe/read_page/find_text。"
        }
        if (name in GESTURE_TOOLS && !caps.input) {
            return "当前设备没有可用的坐标/按键注入通道，$name 不可用。" +
                "请先开启无障碍服务，或改用 tap_text/tap_node。"
        }
        if (name in A11Y_TOOLS && !caps.accessibility) {
            return "无障碍服务未连接，$name 不可用。请让用户在系统设置-无障碍中开启 CatfeeAgents。"
        }
        if (name == "get_activity" && !caps.accessibility && !caps.root) {
            return "当前模式无法可靠读取前台 Activity，请改用 observe/read_page。"
        }
        return null
    }

    private fun groupAvailable(session: Session, group: String): Boolean {
        val tools = GROUP_TOOLS[group].orEmpty()
        return tools.any { capabilityBlockReason(session, it) == null }
    }

    /** 按任务目标自动加载常用高级组; 模型仍可用 load_tools 手动补 */
    fun activateForGoal(session: Session, goal: String) {
        val text = goal.lowercase()
        fun hit(vararg keys: String) = keys.any { text.contains(it) }

        if (hit("答题", "题目", "考试", "测验", "问卷", "作业", "表单", "选项", "提交", "填空")) {
            session.activeGroups.add("form")
            session.activeGroups.add("inspect")
        }
        if (hit("视频", "图片", "截图", "ocr", "识别", "长页", "长截图", "画面", "视觉", "看看", "看一下")) {
            session.activeGroups.add("inspect")
        }
        if (hit("长按", "双击", "清空", "输入框", "方向滑动", "滑动方向")) {
            session.activeGroups.add("input")
        }
        if (hit("包名", "activity", "应用信息", "卸载", "安装", "停止应用", "启动activity", "属性")) {
            session.activeGroups.add("apps")
        }
        if (hit("shell", "设置", "权限", "系统", "adb")) {
            session.activeGroups.add("system")
        }
        if (hit("文件", "目录", "路径", "读写", "保存到")) {
            session.activeGroups.add("files")
        }
        if (hit("记忆", "计划", "清单")) {
            session.activeGroups.add("memory")
        }
        if (hit("全部工具", "所有工具", "高级工具")) {
            session.activeGroups.add("all")
        }
        // 当前设备能力不足的组不要自动加载, 否则模型会反复尝试不可用工具
        session.activeGroups.removeAll { group ->
            group != "all" && !groupAvailable(session, group)
        }
    }

    /** 当前会话是否允许调用某个工具 (防止模型幻觉出未加载的工具) */
    fun isToolEnabled(session: Session, name: String): Boolean {
        if (session.agentBlocked) return false
        if (name == "enter_agent_mode" || name == "load_tools") return true
        if (!session.agentMode) return false
        if (capabilityBlockReason(session, name) != null) return false
        if (name in CORE_TOOLS) return true
        if ("all" in session.activeGroups) {
            return GROUP_TOOLS.values.any { name in it }
        }
        return session.activeGroups.any { name in GROUP_TOOLS[it].orEmpty() }
    }

    /** 处理 load_tools 的参数, 返回给模型的结果 */
    fun loadGroups(session: Session, csv: String): String {
        val names = csv.split(',', '，', ' ', '\n')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (names.isEmpty()) return "load_tools 需要 groups, 例如 inspect,form 或 all"
        if ("all" in names) {
            session.activeGroups.add("all")
            return "已加载全部高级工具组, 下一轮生效。"
        }
        val known = GROUP_TOOLS.keys
        val availableGroups = names.filter { it in known && groupAvailable(session, it) }.toSet()
        val unavailableGroups = names.filter { it in known && !groupAvailable(session, it) }
        val unknown = names.filter { it !in known }
        session.activeGroups.addAll(availableGroups)
        return buildString {
            if (availableGroups.isNotEmpty()) {
                append("已加载工具组: ").append(availableGroups.joinToString(", "))
                    .append("; 下一轮生效。")
            }
            if (unavailableGroups.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append("当前设备能力不足, 未加载: ")
                    .append(unavailableGroups.joinToString(", "))
            }
            if (unknown.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append("未知工具组: ").append(unknown.joinToString(", "))
                    .append("。可用: ").append(known.joinToString(", ")).append(", all")
            }
        }
    }

    /**
     * 功能: 生成当前模式/已加载组对应的工具定义
     * 参数: session 本次任务的工具可见性
     * 返回: JSONArray, 可直接放入请求体 tools 字段
     */
    fun definitions(session: Session): JSONArray {
        if (session.agentBlocked) return JSONArray()
        if (!session.agentMode) {
            return JSONArray().put(enterAgentModeDefinition())
        }
        val allowed = HashSet(CORE_TOOLS)
        if ("all" in session.activeGroups) {
            GROUP_TOOLS.values.forEach { allowed.addAll(it) }
        } else {
            session.activeGroups.forEach { allowed.addAll(GROUP_TOOLS[it].orEmpty()) }
        }
        val out = JSONArray()
        for (i in 0 until allDefinitions.length()) {
            val item = allDefinitions.optJSONObject(i) ?: continue
            val name = item.optJSONObject("function")?.optString("name").orEmpty()
            if (name in allowed && capabilityBlockReason(session, name) == null) out.put(item)
        }
        out.put(loadToolsDefinition())
        return out
    }

    private fun enterAgentModeDefinition(): JSONObject = tool(
        "enter_agent_mode",
        "当用户明确要求查看或操作手机时调用。调用后 App 会请求一次授权, 并加载设备工具; 普通聊天/问答不要调用。",
        obj()
    )

    private fun loadToolsDefinition(): JSONObject = tool(
        "load_tools",
        "加载当前任务需要的高级工具组, 下一轮生效。组: inspect(完整页面/截图/长截图/视觉), form(答题/选项/校验/子模型), input(长按/双击/方向滑动/清空/按id输入), apps(Activity/停止App/包名/应用信息/属性), system(shell/设置读写), files(文件读写), memory(记忆列表/计划读取), all(全部)。",
        obj()
            .put("groups", prop("string", "逗号分隔的组名, 如 inspect,form; 或 all"))
            .also { it.put("required", JSONArray().put("groups")) }
    )

    /**
     * 功能: 生成全部工具定义 (OpenAI function 格式)
     * 参数: 无
     * 返回: JSONArray
     * 使用场景: 由 definitions(session) 过滤后发给模型
     */
    private fun buildAllDefinitions(): JSONArray = JSONArray()
        .put(tool("observe", "获取当前屏幕的界面元素列表。每次操作后都应调用它确认结果。", obj()))
        .put(
            tool(
                "tap", "按绝对坐标点击屏幕。优先使用 tap_text, 只有在拿不到文本时才用坐标。",
                obj()
                    .put("x", prop("integer", "横坐标(像素)"))
                    .put("y", prop("integer", "纵坐标(像素)"))
                    .also { it.put("required", JSONArray().put("x").put("y")) }
            )
        )
        .put(
            tool(
                "tap_text", "按文本内容点击控件, 比坐标点击更稳定。",
                obj()
                    .put("text", prop("string", "控件上显示的文字或描述, 支持部分匹配"))
                    .put("exact", prop("boolean", "true=完全相等, 默认 false 表示包含匹配"))
                    .also { it.put("required", JSONArray().put("text")) }
            )
        )
        .put(
            tool(
                "tap_node", "按文字/描述/id 点击控件, 比坐标点击稳定。",
                obj()
                    .put("target", prop("string", "要匹配的文字/描述/id 后缀"))
                    .put("by", prop("string", "text=文字(默认), desc=描述, id=resource-id 后缀"))
                    .put("exact", prop("boolean", "仅 by=text 时生效"))
                    .also { it.put("required", JSONArray().put("target")) }
            )
        )
        .put(
            tool(
                "swipe", "滑动屏幕。上滑看更多内容时 y1 大于 y2。",
                obj()
                    .put("x1", prop("integer", "起点横坐标"))
                    .put("y1", prop("integer", "起点纵坐标"))
                    .put("x2", prop("integer", "终点横坐标"))
                    .put("y2", prop("integer", "终点纵坐标"))
                    .put("duration_ms", prop("integer", "滑动时长, 默认 300 毫秒"))
                    .also { it.put("required", JSONArray().put("x1").put("y1").put("x2").put("y2")) }
            )
        )
        .put(
            tool(
                "input_text", "向当前聚焦的输入框写入文本, 支持中文。会先清空原有内容。",
                obj()
                    .put("text", prop("string", "要写入的完整文本"))
                    .also { it.put("required", JSONArray().put("text")) }
            )
        )
        .put(
            tool(
                "key", "发送系统按键。back=返回, home=回桌面, enter=回车, recent=最近任务。",
                obj()
                    .put("name", prop("string", "按键名: back / home / enter / recent"))
                    .also { it.put("required", JSONArray().put("name")) }
            )
        )
        .put(
            tool(
                "launch_app", "按包名启动一个应用。",
                obj()
                    .put("package", prop("string", "应用包名"))
                    .also { it.put("required", JSONArray().put("package")) }
            )
        )
        .put(
            tool(
                "wait", "等待一段时间, 用于页面加载、动画结束或用户明确要求的短暂等待。处理媒体播放时优先使用 wait_media。单次最长 5 分钟, 可重复调用。",
                obj()
                    .put("ms", prop("integer", "等待毫秒数, 建议 1000~5000"))
                    .also { it.put("required", JSONArray().put("ms")) }
            )
        )
        .put(
            tool(
                "read_page", "导出当前页面完整无障碍树 (文字/描述/坐标/可点击), 比 observe 更全, 适合扫描列表。",
                obj()
            )
        )
        .put(
            tool(
                "find_text", "按文字/描述查找控件, 返回候选列表 (坐标/id/是否可点击)。点不到时先用它。",
                obj()
                    .put("keyword", prop("string", "关键字"))
                    .put("exact", prop("boolean", "true=完全相等, 默认 false"))
                    .put("by_desc", prop("boolean", "true=查 contentDescription, 默认 false 查 text"))
                    .also { it.put("required", JSONArray().put("keyword")) }
            )
        )
        .put(
            tool(
                "list_options", "列出当前屏幕的选项/单选框/复选框: 文字、是否选中、中心坐标。每题优先用它核对; 滚动后重新调用, 不要复用旧坐标。",
                obj().put("keyword", prop("string", "选项文字关键字, 可空 (默认匹配「选项」/RadioButton/CheckBox)"))
            )
        )
        .put(tool("get_activity", "读取当前前台包名/Activity。", obj()))
        .put(tool("screenshot", "截屏并返回尺寸/路径。", obj()))
        .put(
            tool(
                "ocr", "本地 OCR 当前屏幕, 返回文字+坐标。WebView/iframe 无障碍读不到时用它。",
                obj()
            )
        )
        .put(
            tool(
                "long_ocr", "长页面滚动拼接 OCR: 从顶部逐屏截图, 合并整页文字+坐标。题目/列表长于一屏时用它, 避免滚到下面只看到答案。",
                obj().put("max_screens", prop("integer", "最多滚动几屏, 默认 14"))
            )
        )
        .put(
            tool(
                "wait_for_text", "等待某个文字出现 (无障碍每秒 + OCR 每10秒), 适合等待页面状态或用户指定的结果。单次最长 5 分钟。",
                obj()
                    .put("keyword", prop("string", "关键字, 如 已完成"))
                    .put("timeout_ms", prop("integer", "超时毫秒, 默认 8000, 最大 300000 (5分钟)"))
                    .also { it.put("required", JSONArray().put("keyword")) }
            )
        )
        .put(
            tool(
                "media_state",
                "读取当前媒体播放页的当前时间/总时长/倍速/播放状态, 以及时间文本和倍速控件坐标。仅在用户提出音视频相关需求时使用, 不要靠猜。",
                obj()
            )
        )
        .put(
            tool(
                "media_speed",
                "切换当前媒体播放器的倍速。会自动显示控制条、点“倍速/速度”控件并选择目标值; 仅按用户明确要求的目标倍速调用, 不要自己猜菜单坐标。",
                obj()
                    .put("speed", prop("number", "目标倍速, 如 2.0、1.5、1.0"))
                    .also { it.put("required", JSONArray().put("speed")) }
            )
        )
        .put(
            tool(
                "wait_media",
                "按当前时间与总时长计算剩余时间并等待, 用于用户明确提出的媒体播放等待。target_ratio 仅在用户或当前页面明确给出目标比例时设置, 否则使用默认目标。单次最长 5 分钟, 没到目标可再次调用。",
                obj()
                    .put("target_ratio", prop("number", "目标进度 0.1~1.0, 默认 1.0"))
                    .put("max_wait_ms", prop("integer", "单次最长等待毫秒, 默认 300000"))
            )
        )
        .put(
            tool(
                "tap_desc", "按 contentDescription 点击控件。",
                obj().put("keyword", prop("string", "描述关键字"))
                    .also { it.put("required", JSONArray().put("keyword")) }
            )
        )
        .put(
            tool(
                "tap_id", "按 resource-id 后缀点击控件。",
                obj().put("id_suffix", prop("string", "id 后缀, 如 btn_login"))
                    .also { it.put("required", JSONArray().put("id_suffix")) }
            )
        )
        .put(
            tool(
                "long_press", "长按坐标。",
                obj()
                    .put("x", prop("integer", "横坐标"))
                    .put("y", prop("integer", "纵坐标"))
                    .put("hold_ms", prop("integer", "按住毫秒, 默认 800"))
                    .also { it.put("required", JSONArray().put("x").put("y")) }
            )
        )
        .put(
            tool(
                "double_tap", "双击坐标。",
                obj()
                    .put("x", prop("integer", "横坐标"))
                    .put("y", prop("integer", "纵坐标"))
                    .also { it.put("required", JSONArray().put("x").put("y")) }
            )
        )
        .put(
            tool(
                "swipe_direction", "按方向上/下/左/右滑动, 距离按屏幕短边比例。",
                obj()
                    .put("direction", prop("string", "up / down / left / right"))
                    .put("distance", prop("number", "0.1~0.9, 默认 0.6"))
                    .put("duration_ms", prop("integer", "默认 400"))
                    .also { it.put("required", JSONArray().put("direction")) }
            )
        )
        .put(
            tool(
                "scroll_to_text", "反复上滑直到文字出现。",
                obj()
                    .put("keyword", prop("string", "关键字"))
                    .put("max_scrolls", prop("integer", "最多滚动次数, 默认 8"))
                    .also { it.put("required", JSONArray().put("keyword")) }
            )
        )
        .put(tool("clear_text", "清空当前焦点输入框。", obj()))
        .put(
            tool(
                "set_text_id", "按 resource-id 后缀给输入框写入文本 (不依赖焦点)。",
                obj()
                    .put("id_suffix", prop("string", "id 后缀"))
                    .put("text", prop("string", "要写入的文本"))
                    .also { it.put("required", JSONArray().put("id_suffix").put("text")) }
            )
        )
        .put(
            tool(
                "launch_activity", "按 包名/Activity 精确启动页面。",
                obj().put("component", prop("string", "如 com.example.app/.MainActivity"))
                    .also { it.put("required", JSONArray().put("component")) }
            )
        )
        .put(
            tool(
                "stop_app", "强制停止应用。",
                obj().put("package", prop("string", "包名"))
                    .also { it.put("required", JSONArray().put("package")) }
            )
        )
        .put(
            tool(
                "list_packages", "列出已安装包名, 可按关键字过滤。",
                obj().put("keyword", prop("string", "过滤关键字, 可空"))
            )
        )
        .put(
            tool(
                "app_info", "读取应用包信息 (版本/权限/Activity)。",
                obj().put("package", prop("string", "包名"))
                    .also { it.put("required", JSONArray().put("package")) }
            )
        )
        .put(
            tool(
                "open_url", "用系统默认浏览器打开 URL。",
                obj().put("url", prop("string", "URL"))
                    .also { it.put("required", JSONArray().put("url")) }
            )
        )
        .put(
            tool(
                "shell", "执行 root shell 命令并返回输出。仅用于通用系统查询/操作, 不要执行破坏性命令。",
                obj().put("command", prop("string", "命令"))
                    .also { it.put("required", JSONArray().put("command")) }
            )
        )
        .put(
            tool(
                "get_prop", "读取系统属性。",
                obj().put("key", prop("string", "属性名"))
                    .also { it.put("required", JSONArray().put("key")) }
            )
        )
        .put(
            tool(
                "settings_get", "读取 Settings (system/secure/global)。",
                obj()
                    .put("namespace", prop("string", "system/secure/global"))
                    .put("key", prop("string", "键"))
                    .also { it.put("required", JSONArray().put("key")) }
            )
        )
        .put(
            tool(
                "settings_put", "写入 Settings (需要 root)。",
                obj()
                    .put("namespace", prop("string", "system/secure/global"))
                    .put("key", prop("string", "键"))
                    .put("value", prop("string", "值"))
                    .also { it.put("required", JSONArray().put("key").put("value")) }
            )
        )
        .put(
            tool(
                "file_read", "读取文件内容 (root)。",
                obj().put("path", prop("string", "路径"))
                    .also { it.put("required", JSONArray().put("path")) }
            )
        )
        .put(
            tool(
                "file_write", "写入文件 (root, 覆盖)。",
                obj()
                    .put("path", prop("string", "路径"))
                    .put("content", prop("string", "内容"))
                    .also { it.put("required", JSONArray().put("path").put("content")) }
            )
        )
        .put(
            tool(
                "file_list", "列目录 (root)。",
                obj().put("path", prop("string", "路径, 默认 /data/local/tmp"))
            )
        )
        .put(
            tool(
                "ask_llm", "让 LLM 回答一个子问题, 可临时指定模型 (如 glm-5.2 / kimi-k2.6)。适合做题、总结、分析。",
                obj()
                    .put("prompt", prop("string", "问题/指令"))
                    .put("model", prop("string", "可选模型名, 空=当前模型"))
                    .also { it.put("required", JSONArray().put("prompt")) }
            )
        )
        .put(
            tool(
                "verify_text", "验证屏幕是否出现某段文字。同时用无障碍和 OCR 两种证据, 返回 PASS/FAIL。提交/确认前必查。",
                obj().put("keyword", prop("string", "要确认的文字"))
                    .also { it.put("required", JSONArray().put("keyword")) }
            )
        )
        .put(
            tool(
                "verify_option", "按像素颜色验证某个坐标的选项是否已选中 (蓝色实心=选中)。比视觉模型猜复选框状态可靠。",
                obj()
                    .put("x", prop("integer", "选项圆圈/方框中心 x"))
                    .put("y", prop("integer", "选项圆圈/方框中心 y"))
                    .also { it.put("required", JSONArray().put("x").put("y")) }
            )
        )
        .put(
            tool(
                "verify_question", "一次核对一道题所有选项的选中状态 (只截一张图)。多选题点完后调用; 再调一次就是第二遍复核。",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties", JSONObject().put(
                            "options", JSONObject()
                                .put("type", "array")
                                .put(
                                    "items", JSONObject()
                                        .put("type", "object")
                                        .put(
                                            "properties", JSONObject()
                                                .put("letter", prop("string", "选项字母, 如 A"))
                                                .put("x", prop("integer", "选项圆圈中心 x"))
                                                .put("y", prop("integer", "选项圆圈中心 y"))
                                        )
                                        .put(
                                            "required",
                                            JSONArray().put("letter").put("x").put("y")
                                        )
                                )
                        )
                    )
                    .put("required", JSONArray().put("options"))
            )
        )
        .put(
            tool(
                "verify_changed", "验证当前界面相对上一次 observe 是否发生变化。动作后没变化就说明上一步可能无效。",
                obj()
            )
        )
        .put(
            tool(
                "second_opinion", "同一问题问两个模型 (当前模型 + 第二模型), 返回两份答案。关键题目/判断用来交叉验证。",
                obj()
                    .put("prompt", prop("string", "问题/指令"))
                    .put("model_b", prop("string", "第二模型, 默认 glm-5.2"))
                    .also { it.put("required", JSONArray().put("prompt")) }
            )
        )
        .put(
            tool(
                "todo_write", "维护任务清单。多步骤任务先列清单, 每完成一项就更新状态; 清单会注入每轮上下文, 防止漏项。",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties", JSONObject().put(
                            "items", JSONObject()
                                .put("type", "array")
                                .put(
                                    "items", JSONObject()
                                        .put("type", "object")
                                        .put(
                                            "properties", JSONObject()
                                                .put("task", prop("string", "子任务"))
                                                .put(
                                                    "status", JSONObject()
                                                        .put("type", "string")
                                                        .put(
                                                            "enum", JSONArray()
                                                                .put("pending")
                                                                .put("in_progress")
                                                                .put("completed")
                                                        )
                                                )
                                        )
                                        .put(
                                            "required", JSONArray().put("task").put("status")
                                        )
                                )
                        )
                    )
                    .put("required", JSONArray().put("items"))
            )
        )
        .put(tool("todo_read", "读取当前任务清单。", obj()))
        .put(
            tool(
                "memory_read", "读取某个 App 的操作记忆 (按包名隔离)。不传包名则读当前前台 App。每轮 system 也会自动注入当前 App 记忆。",
                obj().put("package", prop("string", "包名, 如 com.example.app; 可空"))
            )
        )
        .put(
            tool(
                "memory_write", "写入某个 App 的简洁操作记忆 (≤1200 字)。只记稳定入口/关键控件/易错点, 不要记题目答案。",
                obj()
                    .put("package", prop("string", "包名; 空=当前前台 App"))
                    .put("content", prop("string", "简洁记忆内容"))
                    .put("append", prop("boolean", "true=追加, 默认覆盖"))
                    .also { it.put("required", JSONArray().put("content")) }
            )
        )
        .put(tool("memory_list", "列出已保存记忆的 App 包名与大小。", obj()))
        .put(
            tool(
                "answer_form", "通用选项式表单/测验: 先让题目显示在当前页, 再调用。内部 OCR 读题、三模型独立作答并投票、每题 3 次像素核对、整页复查后提交。任何 App 都能用。",
                obj()
                    .put("title", prop("string", "标题/上下文, 可空"))
                    .put("verify_times", prop("integer", "每题核对次数, 默认 3"))
                    .put("submit", prop("boolean", "是否提交, 默认 true"))
                    .put("reset", prop("boolean", "提交前先点重做/重新作答, 默认 true"))
            )
        )
        .put(tool("tool_list", "列出当前 Agent 可用的全部原子工具。", obj()))
        .put(
            tool(
                "see_screen", "截图并调用视觉模型读屏。无障碍读不到 WebView/iframe 内容时用它读文字/布局; 不要用它判断复选框是否选中, 选中态用 verify_option。",
                obj()
                    .put("question", prop("string", "想问视觉模型的问题, 可空"))
            )
        )
        .put(
            tool(
                "note", "把关键信息记录到长期便签。上下文被裁剪后便签仍会保留, 最后汇总时使用。",
                obj()
                    .put("text", prop("string", "要记录的内容"))
                    .also { it.put("required", JSONArray().put("text")) }
            )
        )
        .put(
            tool(
                "finish", "任务完成或无法继续时调用, 给出最终结论。",
                obj()
                    .put("summary", prop("string", "任务结果说明, 包含已完成什么、关键数据"))
                    .also { it.put("required", JSONArray().put("summary")) }
            )
        )

    /** 全部工具只构建一次, 每轮按 Session 过滤 */
    private val allDefinitions: JSONArray by lazy { buildAllDefinitions() }

    /** 构造一个 function 工具定义 */
    private fun tool(name: String, desc: String, params: JSONObject) = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("parameters", params)
        )

    /** 省略 properties 的空参数对象 */
    private fun obj() = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())

    /** 单个参数描述 */
    private fun prop(type: String, desc: String) =
        JSONObject().put("type", type).put("description", desc)

    /**
     * 功能: 执行一次工具调用
     * 参数:
     *   context  用于读取屏幕尺寸等
     *   provider 视觉降级时使用的 LLM 配置, 可为 null (则不降级)
     *   name     工具名
     *   argsJson 模型给出的参数 JSON 字符串
     * 返回: 回灌给模型的文本结果
     * 使用场景: ReActAgent 循环中的工具执行阶段
     */
    suspend fun execute(
        context: Context,
        provider: LlmSettings.Provider?,
        name: String,
        argsJson: String,
        session: Session = Session(),
        awaitIfPaused: suspend () -> Unit = {}
    ): String {
        awaitIfPaused()
        // 模型偶尔会返回空串或非法 JSON, 统一兜底成空对象
        val args = try {
            if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
        } catch (e: Exception) {
            Log.w(TAG, "参数解析失败: $argsJson => ${e.message}")
            JSONObject()
        }
        return try {
            when (name) {
                "observe" -> observe(context, provider, session)
                "read_page" -> readPage(session)
                "see_screen" -> {
                    if (provider == null || !provider.usable) {
                        "视觉识别失败: LLM 配置不完整"
                    } else {
                        val q = args.optString("question").trim()
                        val screenHash = ToolRuntime.currentScreenHash()
                        if (screenHash.isNotBlank() && screenHash == session.lastSeeHash &&
                            q == session.lastSeeQuestion && session.lastSeeText.isNotBlank()
                        ) {
                            "屏幕未变化 (hash=$screenHash), 不要重复 see_screen; " +
                                "直接使用上一次视觉结果, 或用 ocr/find_text 定位。"
                        } else {
                            val v = if (q.isEmpty()) {
                                ScreenVision.describeScreen(provider)
                            } else {
                                ScreenVision.describeScreen(provider, q)
                            }
                            if (v.ok) {
                                if (screenHash.isNotBlank()) {
                                    session.lastSeeHash = screenHash
                                    session.lastSeeQuestion = q
                                    session.lastSeeText = v.text
                                }
                                "[视觉识别, ${v.elapsedMs}ms]\n${v.text}"
                            } else {
                                "视觉识别失败: ${v.error}"
                            }
                        }
                    }
                }
                "note" -> "已记录"
                "verify_text" -> ToolRuntime.verifyText(args.optString("keyword"))
                "verify_option" -> ToolRuntime.verifyOption(
                    args.optInt("x"), args.optInt("y")
                )
                "verify_question" -> ToolRuntime.verifyQuestion(
                    args.optJSONArray("options") ?: JSONArray()
                )
                "verify_changed" -> ToolRuntime.verifyChanged(session)
                "second_opinion" -> {
                    if (provider == null || !provider.usable) {
                        "LLM 配置不完整"
                    } else {
                        ToolRuntime.secondOpinion(
                            provider,
                            args.optString("prompt"),
                            args.optString("model_b")
                        )
                    }
                }
                "todo_write", "todo_read" -> "由 Agent 循环直接处理"
                "memory_read" -> {
                    val pkg = args.optString("package").ifBlank { AppMemory.currentPackage() }
                    val mem = AppMemory.read(context, pkg)
                    if (mem.isBlank()) "「$pkg」暂无记忆" else "「$pkg」记忆:\n$mem"
                }
                "memory_write" -> {
                    val pkg = args.optString("package").ifBlank { AppMemory.currentPackage() }
                    AppMemory.write(
                        context,
                        pkg,
                        args.optString("content"),
                        args.optBoolean("append", false)
                    )
                }
                "memory_list" -> AppMemory.list(context)
                "find_text" -> ToolRuntime.findText(
                    args.optString("keyword"),
                    args.optBoolean("exact", false),
                    args.optBoolean("by_desc", false)
                )
                "list_options" -> ToolRuntime.listOptions(args.optString("keyword"))
                "get_activity" -> ToolRuntime.currentActivity()
                "screenshot" -> ToolRuntime.screenshot(context)
                "ocr" -> ToolRuntime.ocr(session)
                "long_ocr" -> {
                    val scan = LongOcr.scan(
                        context, args.optInt("max_screens", 14)
                    )
                    buildString {
                        append("滚动 ").append(scan.screens).append(" 屏, ")
                            .append(scan.lines.size).append(" 行:\n")
                        scan.lines.take(200).forEach { l ->
                            append('(').append(l.box.centerX()).append(',')
                                .append(l.box.centerY()).append(") ")
                                .append(l.text).append('\n')
                        }
                    }
                }
                "wait_for_text" -> ToolRuntime.waitForText(
                    args.optString("keyword"),
                    args.optLong("timeout_ms", 8000),
                    awaitIfPaused
                )
                "media_state" -> MediaState.describe(session.mediaCache)
                "media_speed" -> MediaState.setSpeed(
                    args.optDouble("speed", 2.0), session.mediaCache
                )
                "wait_media" -> MediaState.waitMedia(
                    args.optDouble("target_ratio", 1.0),
                    args.optLong("max_wait_ms", 300_000L),
                    awaitIfPaused,
                    session.mediaCache
                )
                "tap_node" -> when (args.optString("by", "text").lowercase()) {
                    "id" -> ToolRuntime.tapId(args.optString("target"))
                    "desc" -> ToolRuntime.tapDesc(args.optString("target"))
                    else -> ToolRuntime.tapText(
                        args.optString("target"),
                        args.optBoolean("exact", false)
                    )
                }
                "tap_desc" -> ToolRuntime.tapDesc(args.optString("keyword"))
                "tap_id" -> ToolRuntime.tapId(args.optString("id_suffix"))
                "long_press" -> {
                    val x = args.optInt("x")
                    val y = args.optInt("y")
                    val bad = invalidCoord(x, y)
                    if (bad != null) bad
                    else {
                        val r = ToolRuntime.longPress(x, y, args.optInt("hold_ms", 800))
                        if (r.startsWith("已长按")) "$r; 请确认界面变化" else r
                    }
                }
                "double_tap" -> {
                    val x = args.optInt("x")
                    val y = args.optInt("y")
                    val bad = invalidCoord(x, y)
                    if (bad != null) bad
                    else {
                        val r = ToolRuntime.doubleTap(x, y)
                        if (r.startsWith("已双击")) "$r; 请确认界面变化" else r
                    }
                }
                "swipe_direction" -> ToolRuntime.swipeDirection(
                    args.optString("direction", "up"),
                    args.optDouble("distance", 0.6).toFloat(),
                    args.optInt("duration_ms", 400)
                )
                "scroll_to_text" -> ToolRuntime.scrollToText(
                    args.optString("keyword"),
                    args.optInt("max_scrolls", 8)
                )
                "clear_text" -> ToolRuntime.clearText()
                "set_text_id" -> ToolRuntime.setTextId(
                    args.optString("id_suffix"), args.optString("text")
                )
                "launch_activity" -> ToolRuntime.launchActivity(context, args.optString("component"))
                "stop_app" -> ToolRuntime.stopApp(args.optString("package"))
                "list_packages" -> ToolRuntime.listPackages(context, args.optString("keyword"))
                "app_info" -> ToolRuntime.appInfo(context, args.optString("package"))
                "open_url" -> ToolRuntime.openUrl(context, args.optString("url"))
                "shell" -> ToolRuntime.shell(args.optString("command"))
                "get_prop" -> ToolRuntime.getProp(args.optString("key"))
                "settings_get" -> ToolRuntime.settingsGet(
                    context, args.optString("namespace", "system"), args.optString("key")
                )
                "settings_put" -> ToolRuntime.settingsPut(
                    args.optString("namespace", "system"),
                    args.optString("key"),
                    args.optString("value")
                )
                "file_read" -> ToolRuntime.fileRead(args.optString("path"))
                "file_write" -> ToolRuntime.fileWrite(
                    args.optString("path"), args.optString("content")
                )
                "file_list" -> ToolRuntime.fileList(args.optString("path"))
                "ask_llm" -> {
                    if (provider == null || !provider.usable) {
                        "LLM 配置不完整"
                    } else {
                        ToolRuntime.askLlm(
                            provider,
                            args.optString("prompt"),
                            args.optString("model")
                        )
                    }
                }
                "answer_form" -> {
                    val r = FormSolver.solve(
                        context,
                        args.optString("title"),
                        args.optInt("verify_times", 3),
                        args.optBoolean("submit", true),
                        args.optBoolean("reset", true),
                        providerOverride = provider,
                        onLog = { Log.i(TAG, it) },
                        awaitIfPaused = awaitIfPaused
                    )
                    "submitted=${r.submitted}, answered=${r.answered}, ${r.message}"
                }
                "tool_list" -> ToolRuntime.catalog()
                "tap" -> {
                    val x = args.optInt("x")
                    val y = args.optInt("y")
                    val bad = invalidCoord(x, y)
                    if (bad != null) {
                        bad
                    } else {
                        val ok = InputDispatcher.tap(x, y)
                        if (ok) "已发送点击 ($x,$y); 请用 observe/verify_changed 确认界面变化"
                        else "点击失败: 坐标无法注入或 daemon 无响应"
                    }
                }
                "tap_text" -> ToolRuntime.tapText(
                    args.optString("text"),
                    args.optBoolean("exact", false)
                )
                "swipe" -> {
                    val x1 = args.optInt("x1")
                    val y1 = args.optInt("y1")
                    val x2 = args.optInt("x2")
                    val y2 = args.optInt("y2")
                    val bad = invalidCoord(x1, y1) ?: invalidCoord(x2, y2)
                    if (bad != null) {
                        bad
                    } else {
                        val ok = InputDispatcher.swipe(
                            x1, y1, x2, y2,
                            args.optInt("duration_ms", 300)
                        )
                        if (ok) "已发送滑动; 请用 observe 确认页面变化" else "滑动失败"
                    }
                }
                "input_text" -> {
                    val text = args.optString("text")
                    val svc = UiObserverService.instance
                    when {
                        svc == null -> "输入失败: 无障碍服务未连接"
                        text.isEmpty() -> "输入失败: text 为空"
                        svc.setTextOnFocused(text) -> "已输入 ${text.length} 个字符"
                        else -> "输入失败: 当前没有可编辑的输入框, 请先点击输入框"
                    }
                }
                "key" -> keyEvent(args.optString("name"))
                "launch_app" -> launchApp(context, args.optString("package"))
                "wait" -> {
                    // 单次最多 5 分钟; 分段等待并每 15 秒发进度, 避免界面长时间无反馈
                    val ms = args.optInt("ms", 1000).coerceIn(200, 300_000).toLong()
                    // 播放页的长等待必须基于播放进度, 不能盲等 60s/300s。
                    // 这里只拦截“长 wait”, 短 wait 仍可用于缓冲/加载。
                    if (ms > 30_000L) {
                        val media = try {
                            MediaState.read(session.mediaCache)
                        } catch (_: Exception) {
                            null
                        }
                        if (media?.hasMedia == true) {
                            return "检测到视频/播放页; 不要用长 wait 盲等。请用 wait_media " +
                                "按当前时间/总时长分段等待到目标进度 (如 target_ratio=0.9), " +
                                "它会在播放暂停/缓冲时提前返回。"
                        }
                    }
                    val start = System.currentTimeMillis()
                    var last = 0L
                    while (System.currentTimeMillis() - start < ms) {
                        awaitIfPaused()
                        val remain = ms - (System.currentTimeMillis() - start)
                        kotlinx.coroutines.delay(minOf(1000L, remain).coerceAtLeast(50L))
                        val elapsed = System.currentTimeMillis() - start
                        if (elapsed - last >= 15_000) {
                            last = elapsed
                            AgentController.publishStep(
                                "system",
                                "等待中 · ${elapsed / 1000}s / ${ms / 1000}s",
                                "wait"
                            )
                        }
                    }
                    "已等待 ${ms}ms"
                }
                else -> "未知工具: $name"
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行工具 $name 异常: ${e.message}")
            "工具执行异常: ${e.message}"
        }
    }

    /**
     * 功能: 用 root 重新启用本应用的无障碍服务
     * 参数: 无
     * 返回: true=已下发恢复命令
     * 使用场景: Agent 启动或 observe 时发现无障碍掉线
     * 说明: 直接写 secure 设置项, 系统会自动重新绑定服务;
     *       需要 root, 无 root 时返回 false 交由用户手动处理。
     */
    private suspend fun tryRestoreA11y(context: Context): Boolean {
        if (!RootShell.isAvailable()) {
            // 非 Root 无法写 secure settings, 只能把用户带到系统设置页
            try {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.w(TAG, "打开无障碍设置失败: ${e.message}")
            }
            return false
        }
        return try {
            val svc = "com.catfee.rootauto/com.catfee.rootauto.a11y.UiObserverService"
            val r = RootShell.exec(
                "settings put secure enabled_accessibility_services $svc; " +
                    "settings put secure accessibility_enabled 1"
            )
            if (!r.ok) {
                Log.w(TAG, "恢复无障碍失败: ${r.stderr}")
                return false
            }
            // 系统重新绑定需要一点时间, 给个短等待提高下一轮成功率
            kotlinx.coroutines.delay(1200)
            UiObserverService.instance != null
        } catch (e: Exception) {
            Log.e(TAG, "恢复无障碍异常: ${e.message}")
            false
        }
    }

    /**
     * 功能: 导出完整无障碍树 (文字/描述/id/坐标/可点击)
     * 返回: 紧凑文本
     * 使用场景: 列表扫描、找控件、比 observe 更全
     */
    /** 坐标越界直接拒绝, 并把当前显示方向/尺寸告诉模型; 避免反复点屏幕外 */
    private suspend fun invalidCoord(x: Int, y: Int): String? {
        try {
            ScreenGeometry.refreshRotation()
        } catch (e: Exception) {
            Log.w(TAG, "刷新方向失败: ${e.message}")
        }
        val (w, h) = ScreenGeometry.displaySize()
        return ScreenGeometry.invalid(x, y) ?: if (x !in 0 until w || y !in 0 until h) {
            "坐标越界: ($x,$y), 当前显示 ${w}x${h}。tap 坐标必须是当前显示方向像素; " +
                "不要用 0~1000 归一化值。该控件大概率在屏幕外, 先 swipe/scroll_to_text 滚动后再定位; " +
                "可改用 tap_node / find_text。越界坐标不会执行。"
        } else null
    }

    private suspend fun readPage(session: Session): String {
        val svc = UiObserverService.instance ?: return "无障碍服务未连接"
        // 先校准旋转, 否则横屏播放器的 2340x1080 坐标会被当成竖屏坐标
        try {
            ScreenGeometry.refreshRotation()
        } catch (e: Exception) {
            Log.w(TAG, "刷新方向失败: ${e.message}")
        }
        val (screenW, screenH) = ScreenGeometry.displaySize()
        val tree = svc.dumpTree(1000)
        if (tree.isEmpty()) return "当前页面没有可读节点"
        val text = buildString {
            append("Screen: ${screenW}x${screenH} rotation=${ScreenGeometry.rotationLabel()}")
            val act = svc.currentActivityName()
            if (act.isNotBlank()) append(" | Activity: ").append(act)
            append('\n')
            append("节点 ${tree.size} 个:\n")
            tree.take(300).forEach { n ->
                if (n.text.isBlank() && n.desc.isBlank() && n.resourceId.isBlank()) return@forEach
                append('[').append(n.index).append("] ").append(n.cls)
                if (n.text.isNotBlank()) append(" \"").append(n.text).append('"')
                if (n.desc.isNotBlank()) append(" desc=\"").append(n.desc).append('"')
                if (n.resourceId.isNotBlank()) append(" id=").append(n.resourceId.substringAfterLast('/'))
                if (n.clickable) append(" 可点击")
                val cx = n.bounds.centerX()
                val cy = n.bounds.centerY()
                append(" (").append(cx).append(',').append(cy).append(')')
                if (cx !in 0 until screenW || cy !in 0 until screenH) {
                    append(" [屏外, 需先 swipe/scroll_to_text]")
                }
                append('\n')
            }
        }
        val hash = Integer.toHexString(text.hashCode())
        if (hash == session.lastReadPageHash) {
            return "页面未变化 (hash=$hash)。不要重复 read_page; " +
                "需要定位用 find_text / scroll_to_text, 确认动作是否生效用 verify_changed。"
        }
        session.lastReadPageHash = hash
        return text
    }

    /**
     * 功能: 采集界面快照; 无障碍为空时自动降级为截图 + 视觉识别
     * 参数: context; provider 可为 null
     * 返回: 界面描述文本
     * 使用场景: observe 工具
     */
    private suspend fun observe(
        context: Context,
        provider: LlmSettings.Provider?,
        session: Session
    ): String {
        // 顺手刷新前台 Activity, 让模型知道自己在哪个界面
        // Root 下用 dumpsys 校准; 非 Root 用无障碍事件缓存近似。
        val focus = if (RootShell.isAvailable()) {
            RootShell.execText("dumpsys window 2>/dev/null | grep -m1 mCurrentFocus")
        } else {
            ""
        }
        val svc = UiObserverService.instance
        if (svc == null) {
            // 无障碍被系统杀掉很常见(force-stop / 省电清理), 有 root 就直接拉起来,
            // 否则把用户带到系统设置页并明确失败。
            val restored = tryRestoreA11y(context)
            if (!restored) {
                return "无障碍服务未连接, 且自动恢复失败。请让用户在系统设置-无障碍中手动开启 CatfeeAgents。"
            }
            return "无障碍服务刚被重新拉起, 请稍后再次 observe。"
        }
        svc.cachedActivity = extractFocusTarget(focus).ifBlank { svc.currentComponentGuess() }
        // 先校准当前显示方向/尺寸, 非 Root 下走 DisplayManager
        try {
            ScreenGeometry.refreshRotation()
        } catch (e: Exception) {
            Log.w(TAG, "刷新方向失败: ${e.message}")
        }
        val (screenW, screenH) = ScreenGeometry.displaySize()
        val snap = svc.snapshot()
        if (snap != null && snap.nodes.isNotEmpty()) {
            // 播放页的 observe 已看到当前/总时长时先缓存, 控制条隐藏后 wait_media 仍能算剩余
            try {
                MediaState.noteVisibleTimes(snap.nodes, session.mediaCache)
            } catch (e: Exception) {
                Log.w(TAG, "缓存媒体时间失败: ${e.message}")
            }
            val text = snap.toPromptText()
            val hash = Integer.toHexString(text.hashCode())
            if (hash == session.lastObservedHash) {
                return "界面未变化 (hash=$hash)。不要重复 observe; " +
                    "需要定位用 find_text, 需要完整树用 read_page, 确认动作是否生效用 verify_changed。"
            }
            session.lastObservedHash = hash
            return "$text\nhash=$hash"
        }
        // snapshot 为空但完整树还有文字时, 用完整树降级, 不要直接走 40s 的视觉模型
        val tree = svc.dumpTree(1000)
        val labeled = tree.filter {
            it.text.isNotBlank() || it.desc.isNotBlank() || it.resourceId.isNotBlank()
        }.take(120)
        if (labeled.isNotEmpty()) {
            val text = buildString {
                append("Screen: ${screenW}x${screenH} rotation=${ScreenGeometry.rotationLabel()}")
                if (svc.currentActivityName().isNotBlank()) {
                    append(" | Activity: ").append(svc.currentActivityName())
                }
                append('\n')
                labeled.forEach { n ->
                    append('[').append(n.index).append("] ").append(n.cls)
                    if (n.text.isNotBlank()) append(" \"").append(n.text).append('"')
                    if (n.desc.isNotBlank()) append(" desc=\"").append(n.desc).append('"')
                    if (n.resourceId.isNotBlank()) {
                        append(" id=").append(n.resourceId.substringAfterLast('/'))
                    }
                    if (n.clickable) append(" 可点击")
                    val cx = n.bounds.centerX()
                    val cy = n.bounds.centerY()
                    append(" (").append(cx).append(',').append(cy).append(')')
                    if (cx !in 0 until screenW || cy !in 0 until screenH) {
                        append(" [屏外, 需先滑动]")
                    }
                    append('\n')
                }
            }
            val hash = Integer.toHexString(text.hashCode())
            if (hash == session.lastObservedHash) {
                return "界面未变化 (hash=$hash)。不要重复 observe; " +
                    "需要定位用 find_text, 需要完整树用 read_page, 确认动作是否生效用 verify_changed。"
            }
            session.lastObservedHash = hash
            return "$text\nhash=$hash"
        }
        // 完整树也没有可用文字 (纯画布 / Surface / 自绘控件) → 视觉降级
        if (provider == null || !provider.usable) {
            return "无障碍读到 0 个元素, 且未配置可用的视觉模型, 无法降级识别。"
        }
        val v = ScreenVision.describeScreen(provider)
        return if (v.ok) "[视觉降级识别, 耗时 ${v.elapsedMs}ms]\n${v.text}"
        else "无障碍读到 0 个元素, 视觉降级也失败: ${v.error}"
    }

    /**
     * 功能: 从 dumpsys 的 mCurrentFocus 行里提取 "包名/Activity"
     * 参数: line 形如 "mCurrentFocus=Window{4f4f213 u0 com.a/com.a.BActivity}"
     * 返回: "com.a/com.a.BActivity", 解析失败返回空串
     * 使用场景: observe 时告诉模型当前界面
     */
    private fun extractFocusTarget(line: String): String {
        // 花括号里是 "hash u0 包名/Activity", 取最后一段有效内容
        val inner = line.substringAfter('{', "").substringBefore('}')
        val parts = inner.split(' ').filter { it.contains('/') }
        return parts.lastOrNull().orEmpty()
    }

    /**
     * 功能: 发送系统按键
     * 参数: name 语义化按键名
     * 返回: 结果描述
     * 说明: 走 daemon 的 uinput, 比 adb input 更快且不依赖 adb
     */
    private suspend fun keyEvent(name: String): String {
        val key = name.lowercase()
        val code = when (key) {
            "back" -> 158
            "home" -> 102
            "enter" -> 28
            "recent" -> 580
            else -> return "不支持的按键: $name"
        }

        if (DeviceCapabilities.current().daemon) {
            if (DaemonClient.keyevent(code)) return "已发送按键 $name"
        }

        // 非 Root / daemon 不可用: 用无障碍全局动作
        val svc = UiObserverService.instance
            ?: return "当前无 Root 且无障碍未连接，无法发送按键"
        return when (key) {
            "back" -> if (svc.globalBack()) "已发送按键 back（无障碍）" else "按键发送失败"
            "home" -> if (svc.globalHome()) "已发送按键 home（无障碍）" else "按键发送失败"
            "recent" -> if (svc.globalRecents()) "已发送按键 recent（无障碍）" else "按键发送失败"
            "enter" -> if (svc.imeEnter()) {
                "已发送回车（无障碍 IME）"
            } else {
                "当前页面不支持 IME 回车；请点击页面上的搜索/确认按钮"
            }
            else -> "不支持的按键: $name"
        }
    }

    /**
     * 功能: 按包名启动应用
     * 参数: context; pkg 包名
     * 返回: 结果描述
     * 说明: 优先 PackageManager Intent（非 Root 可用），Root 下失败再用 monkey 兜底。
     */
    private suspend fun launchApp(context: Context, pkg: String): String {
        if (pkg.isBlank()) return "启动失败: 包名为空"
        val intentError = AppLauncher.launchPackage(context, pkg)
        if (intentError == null) return "已启动 $pkg"

        if (RootShell.isAvailable()) {
            val r = RootShell.exec(
                "monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1 | tail -2"
            )
            val ok = r.stdout.contains("Events injected: 1") || r.stdout.contains("injected")
            if (ok) return "已启动 $pkg"
            return "启动 $pkg 可能失败: ${r.stdout.take(160)}"
        }
        return intentError
    }
}
