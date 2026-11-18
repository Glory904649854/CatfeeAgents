package com.catfee.rootauto.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.catfee.rootauto.root.ScreenGeometry
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * UiObserverService —— 无障碍服务, 负责采集并压缩界面元素树
 *
 * 功能描述:
 *   常驻监听窗口变化, 对外提供 snapshot() 方法, 把当前界面的可操作元素
 *   压缩成一段紧凑文本交给 LLM 决策。
 *
 *   相比 uiautomator dump 的优势:
 *     uiautomator 每次调用要拉起 Java 进程, 单次 500ms~2s;
 *     Accessibility 直接读已缓存的内存树, 单次约 20~60ms, 差 10 倍以上。
 *
 * 使用场景:
 *   Agent 每一步 observe 的核心实现; 也是 findNode 等工具的底层。
 *
 * 修改时间: 2026-11-03
 */
class UiObserverService : AccessibilityService() {

    companion object {
        private const val TAG = "UiObserver"

        /** 单次快照最多保留的元素数, 超过按面积排序截断, 控制 token 成本 */
        private const val MAX_NODES = 80

        /** 面积小于该值的节点视为装饰性元素丢弃 */
        private const val MIN_AREA = 200

        /** 全局单例, 供工具层直接访问 */
        @Volatile
        var instance: UiObserverService? = null
            private set

        /** 诊断开关: 打开后逐节点打日志, 排查读不到元素时临时打开 */
        private const val DEBUG_TREE = false
    }

    /** 压缩后的单个界面元素 */
    data class UiNode(
        val id: Int,
        val cls: String,
        val text: String,
        val desc: String,
        val resourceId: String,
        val clickable: Boolean,
        val editable: Boolean,
        val checked: Boolean,
        val bounds: Rect
    ) {
        /** 中心点坐标, 供坐标点击兜底 */
        val cx: Int get() = bounds.centerX()
        val cy: Int get() = bounds.centerY()
    }

    /**
     * 完整节点树中的一层节点 (只拷贝数据, 不持有 AccessibilityNodeInfo)
     *
     * 相比 snapshot() 的 UiNode, 这里保留了 parent 关系与全部节点(包括容器),
     * 供页面结构解析使用 (列表项、卡片、标签等父子信息)。
     */
    data class TreeNode(
        val index: Int,
        val parent: Int,
        val depth: Int,
        val cls: String,
        val text: String,
        val desc: String,
        val resourceId: String,
        val clickable: Boolean,
        val editable: Boolean,
        val visible: Boolean,
        val checked: Boolean = false,
        val selected: Boolean = false,
        val bounds: Rect
    )

    /** 一次完整快照 */
    data class Snapshot(
        val packageName: String,
        val activity: String,
        val screenW: Int,
        val screenH: Int,
        val nodes: List<UiNode>
    ) {
        /**
         * 功能: 渲染为紧凑文本, 直接喂给 LLM
         * 返回: 形如 "[1] Button \"登录\" (540,1200)"
         */
        fun toPromptText(): String = buildString {
            append(
                "Screen: ${screenW}x${screenH} rotation=${ScreenGeometry.rotationLabel()} | " +
                    "App: $packageName | Activity: $activity\n"
            )
            // 保守压缩: 保留所有文字/描述/可输入/可点击/选中节点, 只去掉纯布局容器。
            // 这样不会丢掉可操作信息, 但能明显减少长页面/WebView 的上下文。
            val keepClasses = setOf(
                "Button", "TextView", "EditText", "ImageView", "ImageButton",
                "CheckBox", "RadioButton", "Switch", "ToggleButton", "Spinner",
                "SeekBar", "ProgressBar", "WebView"
            )
            val seen = HashSet<String>()
            val meaningful = nodes.asSequence().filter { n ->
                val cls = n.cls.substringAfterLast('.')
                val keep = n.text.isNotBlank() || n.desc.isNotBlank() || n.editable ||
                    n.clickable || n.checked ||
                    (cls in keepClasses && n.resourceId.isNotBlank())
                keep && seen.add("$cls|${n.text}|${n.desc}|${n.cx / 8},${n.cy / 8}")
            }.take(120).toList()
            meaningful.forEach { n ->
                append('[').append(n.id).append("] ")
                append(n.cls.substringAfterLast('.'))
                val label = n.text.ifBlank { n.desc }
                if (label.isNotBlank()) append(" \"").append(label).append('"')
                if (n.editable) append(" [可输入]")
                if (n.checked) append(" [已选中]")
                if (n.clickable) append(" 可点击")
                append(" (").append(n.cx).append(',').append(n.cy).append(')')
                if (n.resourceId.isNotBlank()) {
                    append(" id=").append(n.resourceId.substringAfterLast('/'))
                }
                append('\n')
            }
            if (nodes.size > meaningful.size) {
                append("...[已压缩: 原 ${nodes.size} 个节点, 保留 ${meaningful.size} 个有效节点]")
            }
        }
    }

    /** 最近一次窗口事件的包名, 用于快速取 Activity */
    @Volatile
    private var lastPackage: String = ""
    @Volatile
    private var lastPackageAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ScreenGeometry.init(this)
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.i(TAG, "无障碍服务已销毁")
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只记录包名/类名, 不做任何耗时处理, 避免拖慢系统
        try {
            event?.packageName?.let {
                lastPackage = it.toString()
                lastPackageAt = android.os.SystemClock.elapsedRealtime()
            }
            event?.className?.let { lastClass = it.toString() }
        } catch (e: Exception) {
            Log.w(TAG, "处理事件失败: ${e.message}")
        }
    }

    /**
     * 功能: 采集当前界面快照
     * 参数: 无
     * 返回: Snapshot, 失败返回 null (如服务未连接)
     * 使用场景: Agent observe; 每次决策前调用
     * 注意: 本方法全程不调用 recycle()。
     *       早期版本在遍历结束后统一回收 getChild 得到的节点, 实测会造成
     *       「同一页面只读到前几个元素」——Android 12 的节点对象池被提前腾空后,
     *       后续 getChild 会静默返回 null。节点生命周期交由系统管理。
     */
    @Suppress("DEPRECATION")
    fun snapshot(): Snapshot? {
        return try {
            val root = chooseRoot() ?: return null
            val collected = ArrayList<UiNode>(MAX_NODES * 2)
            val seenRect = HashSet<String>()
            val counter = intArrayOf(0)

            // 用深度优先而不是广度优先:
            //   之前的 BFS 把整层节点塞进队列, 长时间持有大量 AccessibilityNodeInfo 引用,
            //   会触发系统侧节点缓存驱逐, 之后 getChild 静默返回 null,
            //   实测表现为「5 个列表项只读到 2 个」且每次数量还不一样。
            //   DFS 立刻读完一个节点的信息就丢弃引用, 不会堆积, uiautomator 也是这么做的。
            walk(root, 0, collected, seenRect, counter)
            android.util.Log.i(
                TAG,
                "snapshot: root=${root.className} 直接子节点=${root.childCount} " +
                    "遍历=${counter[0]} 命中=${collected.size} 窗口数=${windows?.size ?: -1}"
            )

            // 不主动 recycle 任何节点: 节点生命周期由系统统一管理。

            // 按面积降序, 大元素通常更重要; 截断到上限
            Log.i(TAG, "snapshot: 命中 ${collected.size} 个元素")

            val top = collected
                .sortedByDescending { it.bounds.width().toLong() * it.bounds.height() }
                .take(MAX_NODES)
                .mapIndexed { idx, n -> n.copy(id = idx + 1) }

            val (displayW, displayH) = ScreenGeometry.displaySize()
            Snapshot(
                // 优先用当前活动窗口自己的包名; lastPackage 会被通知栏等 SystemUI 事件污染,
                // 只有拿不到 root 包名时才退回它
                packageName = root.packageName?.toString()?.takeIf { it.isNotBlank() }
                    ?: lastPackage,
                activity = cachedActivity,
                screenW = displayW,
                screenH = displayH,
                nodes = top
            )
        } catch (e: Exception) {
            Log.e(TAG, "采集快照失败: ${e.message}")
            null
        }
    }

    /**
     * 功能: 导出当前窗口的完整节点树 (含容器节点与父子关系)
     * 参数: maxNodes 保护上限, 防止异常页面把内存打爆
     * 返回: 按 DFS 顺序排列的节点列表; 服务未连接/拿不到根节点时返回空列表
     * 使用场景:
     *   列表页面里卡片/条目的信息分散在父子节点上,
     *   snapshot() 的扁平结构无法还原归属关系, 这里用带 parent 的树来解析。
     * 注意: 所有字段在遍历时立即拷贝, 不保留 node 引用。
     */
    @Suppress("DEPRECATION")
    fun dumpTree(maxNodes: Int = 600): List<TreeNode> {
        return try {
            val root = chooseRoot() ?: return emptyList()
            val out = ArrayList<TreeNode>(minOf(maxNodes, 256))
            // 栈元素: 节点 / 父节点下标 / 深度
            val stack = ArrayDeque<Triple<AccessibilityNodeInfo, Int, Int>>()
            stack.addLast(Triple(root, -1, 0))
            while (stack.isNotEmpty() && out.size < maxNodes) {
                val (node, parentIdx, depth) = stack.removeLast()
                val idx = out.size
                val rect = Rect()
                try {
                    node.getBoundsInScreen(rect)
                } catch (_: Exception) {
                    // 个别节点在窗口切换瞬间会失效, 跳过即可
                    continue
                }
                out.add(
                    TreeNode(
                        index = idx,
                        parent = parentIdx,
                        depth = depth,
                        cls = node.className?.toString()?.substringAfterLast('.') ?: "View",
                        text = node.text?.toString()?.trim().orEmpty(),
                        desc = node.contentDescription?.toString()?.trim().orEmpty(),
                        resourceId = node.viewIdResourceName.orEmpty(),
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        visible = node.isVisibleToUser,
                        checked = node.isChecked,
                        selected = node.isSelected,
                        bounds = rect
                    )
                )
                // 逆序入栈, 保证出栈后仍是左到右、上到下的自然顺序
                val children = ArrayList<AccessibilityNodeInfo>(node.childCount)
                for (i in 0 until node.childCount) {
                    try {
                        node.getChild(i)?.let { children.add(it) }
                    } catch (_: Exception) {
                    }
                }
                for (i in children.indices.reversed()) {
                    stack.addLast(Triple(children[i], idx, depth + 1))
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "导出节点树失败: ${e.message}")
            emptyList()
        }
    }

    /** 按 resource-id 后缀查找节点 (如 rv_knowledge / tv_sub_index) */
    @Suppress("DEPRECATION")
    private fun findByIdSuffix(
        node: AccessibilityNodeInfo,
        suffix: String,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 50) return null
        try {
            if (node.viewIdResourceName?.endsWith(suffix) == true) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val hit = findByIdSuffix(child, suffix, depth + 1)
                if (hit != null) return hit
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** 当前活动窗口的包名, 比每轮跑 root dumpsys 快很多 */
    fun currentPackage(): String {
        return try {
            val self = packageName
            val rootPkg = chooseRoot()?.packageName?.toString().orEmpty()
            if (rootPkg.isNotBlank() && rootPkg != self) return rootPkg
            // 根窗口是本应用(后台界面/悬浮层)或拿不到时, 用最近 5 秒的窗口事件包名兜底,
            // 否则前台 App 会被一直误报成 com.catfee.rootauto。
            val last = lastPackage
            val fresh = android.os.SystemClock.elapsedRealtime() - lastPackageAt in 0..5_000
            if (fresh && last.isNotBlank() && last != self && !isSystemOverlay(last)) return last
            rootPkg.ifBlank { last }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 功能: 对指定 id 的滚动容器执行「向前滚动一屏」
     * 参数: idSuffix resource-id 后缀
     * 返回: true=系统接受了滚动动作
     * 使用场景: 滚动容器翻页, 比坐标滑动更精确
     */
    fun scrollForwardByIdSuffix(idSuffix: String): Boolean {
        return try {
            val root = chooseRoot() ?: return false
            val node = findByIdSuffix(root, idSuffix, 0) ?: return false
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        } catch (e: Exception) {
            Log.w(TAG, "滚动 $idSuffix 失败: ${e.message}")
            false
        }
    }

    /** 对指定 id 的滚动容器执行「向后滚动一屏」 */
    fun scrollBackwardByIdSuffix(idSuffix: String): Boolean {
        return try {
            val root = chooseRoot() ?: return false
            val node = findByIdSuffix(root, idSuffix, 0) ?: return false
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        } catch (e: Exception) {
            Log.w(TAG, "回滚 $idSuffix 失败: ${e.message}")
            false
        }
    }

    /** 对第一个可滚动容器执行「向后滚动」, 用于回顶且不会触发下拉刷新 */
    fun scrollBackward(): Boolean {
        return try {
            val root = chooseRoot() ?: return false
            val node = firstScrollable(root) ?: return false
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        } catch (e: Exception) {
            Log.w(TAG, "回滚失败: ${e.message}")
            false
        }
    }

    private fun firstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            firstScrollable(child)?.let { return it }
        }
        return null
    }

    /**
     * 功能: 深度优先遍历节点树, 边读边存, 不保留节点引用
     * 参数:
     *   node      当前节点
     *   depth     当前深度
     *   out       收集结果
     *   seenRect  已用过的矩形去重集合
     *   counter   遍历计数(用于 id 与保护上限)
     * 返回: 无
     * 使用场景: snapshot 的唯一遍历入口
     */
    @Suppress("DEPRECATION")
    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: ArrayList<UiNode>,
        seenRect: HashSet<String>,
        counter: IntArray
    ) {
        if (depth > 40 || counter[0] > 3000) return
        counter[0]++
        try {
            val item = toUiNode(node, out.size, seenRect)
            if (item != null) out.add(item)
        } catch (e: Exception) {
            Log.w(TAG, "读取节点信息异常: ${e.message}")
        }
        try {
            val cc = node.childCount
            if (DEBUG_TREE) {
                Log.d(
                    TAG,
                    "${" ".repeat(depth)}${node.className?.toString()?.substringAfterLast('.')} " +
                        "child=$cc text=${node.text?.toString()?.take(24)} id=${node.viewIdResourceName}"
                )
            }
            for (i in 0 until cc) {
                // child 局部引用, 循环结束即可被回收
                val child = try {
                    node.getChild(i)
                } catch (e: Exception) {
                    null
                } ?: continue
                walk(child, depth + 1, out, seenRect, counter)
                if (counter[0] > 3000) return
            }
        } catch (e: Exception) {
            Log.w(TAG, "遍历子节点异常(depth=$depth): ${e.message}")
        }
    }

    /**
     * 功能: 选出「真正的应用窗口」作为遍历根节点
     * 参数: 无
     * 返回: 根节点, 找不到返回 null
     * 使用场景: snapshot / clickByText / setText 的统一起点
     * 说明:
     *   rootInActiveWindow 在 MIUI 上经常返回通知栏 / 状态栏等系统浮层,
     *   导致读到一堆 LinearLayout 却看不到应用内容。这里主动从 windows 里
     *   过滤出 TYPE_APPLICATION 的窗口, 取层级最高(最上层)的那个。
     */
    fun chooseRoot(): AccessibilityNodeInfo? {
        return try {
            val appWindows = windows
                ?.filter {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                }
                ?.filter { !isSystemOverlay(it.root?.packageName?.toString().orEmpty()) }
                ?.sortedByDescending { it.layer }
                .orEmpty()
            // 本应用的后台窗口层级可能高于前台 App(悬浮状态条/最近任务动画),
            // 优先取其他应用窗口; 只剩自己时才回退, 避免前台包名被误判成 com.catfee.rootauto。
            val external = appWindows.filter { it.root?.packageName?.toString() != packageName }
            val pool = external.ifEmpty { appWindows }
            for (w in pool) {
                val r = w.root
                if (r != null) return r
            }
            // 没有应用窗口时(如锁屏、桌面动画中)退回系统默认
            rootInActiveWindow
        } catch (e: Exception) {
            Log.w(TAG, "选择根窗口失败: ${e.message}")
            try {
                rootInActiveWindow
            } catch (_: Exception) {
                null
            }
        }
    }

    /** 系统浮层包名, 这些窗口不算应用内容 */
    private fun isSystemOverlay(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        return pkg == "com.android.systemui" || pkg.startsWith("com.miui.home")
    }

    /**
     * 功能: 将单个无障碍节点转换为 UiNode, 并按规则过滤
     * 参数:
     *   node    原始节点
     *   idx     临时序号
     *   seenRect 已出现过的 (可点击+文本) 组合, 用于去重
     * 返回: UiNode 或 null(被过滤)
     * 过滤规则:
     *   1. 不可见或 bounds 为空 → 丢
     *   2. 面积过小 → 丢
     *   3. 父子文本重复且父不可点击 → 丢父
     */
    private fun toUiNode(
        node: AccessibilityNodeInfo,
        idx: Int,
        seenRect: MutableSet<String>
    ): UiNode? {
        if (!node.isVisibleToUser) return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return null
        if (rect.width().toLong() * rect.height() < MIN_AREA) return null

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val editable = node.isEditable
        val hasLabel = text.isNotBlank() || desc.isNotBlank()

        // 只保留「有文字/描述」或「可输入」的节点。
        // 不加 isClickableAncestor 兜底: 无文本的可点击容器(LinearLayout/FrameLayout)
        // 对模型没有任何信息量, 猜测其上溯的祖先反而把 observe 输出塞满噪音。
        if (!hasLabel && !editable) return null
        val clickable = node.isClickable || isClickableAncestor(node)

        // 去重: 同一区域同一文本只保留一次(优先可点击的)
        val key = "${rect.left},${rect.top},${rect.right},${rect.bottom}|${text}|${desc}"
        if (hasLabel && !seenRect.add(key)) return null

        val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
        return UiNode(
            id = idx + 1,
            cls = cls,
            text = text,
            desc = desc,
            resourceId = node.viewIdResourceName.orEmpty(),
            clickable = clickable,
            editable = editable,
            checked = node.isChecked,
            bounds = rect
        )
    }

    /**
     * 功能: 判断节点的最近可点击祖先是否存在
     * 参数: node 当前节点
     * 返回: true=可通过祖先完成点击
     * 使用场景: 很多列表项的文本节点本身不可点击, 需点父容器;
     *           Agent 若只按节点自身 clickable 判断会漏掉大量目标
     * 注意: 严禁对 parent 调用 recycle()。
     *       父节点属于系统共享的 UI 树, 提前回收会让同一棵树里其他节点
     *       在后续 getChild 时返回 null (Android 12 节点池很小, 实测会把
     *       5 个列表项只读到 2 个); 循环只做读取, 由系统统一管理生命周期。
     */
    private fun isClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        return try {
            var p = node.parent
            var depth = 0
            var result = false
            while (p != null && depth < 4) {
                if (p.isClickable) {
                    result = true
                    break
                }
                p = p.parent
                depth++
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "判断可点击祖先失败: ${e.message}")
            false
        }
    }

    /**
     * 功能: 读取当前前台 Activity 名
     * 参数: 无
     * 返回: 形如 com.example.app/.MainActivity, 未刷新时为空串
     * 说明: 无障碍 API 拿不到 Activity 名, 由 Agent 通过 root dumpsys 定期刷新缓存,
     *       避免每次都执行 shell 命令拖慢 observe
     */
    fun currentActivityName(): String = cachedActivity

    /** 前台 Activity 缓存, 由 Agent 的 observe 流程定期刷新 */
    @Volatile
    var cachedActivity: String = ""

    /** 最近一次窗口事件的类名, 非 Root 下用于拼出 包名/类名 的近似 Activity */
    @Volatile
    private var lastClass: String = ""

    /** 非 Root 下的近似前台 Activity; 完整组件名不可靠时退回包名 */
    fun currentComponentGuess(): String {
        val pkg = currentPackage()
        if (pkg.isBlank()) return ""
        if (cachedActivity.isNotBlank()) return cachedActivity
        val cls = lastClass
        if (cls.isBlank()) return pkg
        return if (cls.startsWith(".")) "$pkg/$cls" else "$pkg/$cls"
    }

    /** 无障碍全局返回/Home/最近任务, 不需要 Root */
    fun globalBack(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_BACK)
    } catch (e: Exception) {
        false
    }

    fun globalHome(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_HOME)
    } catch (e: Exception) {
        false
    }

    fun globalRecents(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    } catch (e: Exception) {
        false
    }

    /** 对当前焦点输入框发送 IME 回车; 仅 API 30+ 且控件支持时可用 */
    fun imeEnter(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: return false
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } catch (e: Exception) {
            Log.w(TAG, "IME enter 失败: ${e.message}")
            false
        }
    }

    // 非 Root 截图有系统频率限制, 串行并留最小间隔
    private val screenshotMutex = Mutex()
    private var lastScreenshotAt = 0L

    /**
     * Android 11+ 无障碍系统截图。返回的是当前显示方向的软件 Bitmap。
     * 失败时返回 null: FLAG_SECURE、系统限流、服务未连接都可能失败。
     */
    suspend fun takeScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return screenshotMutex.withLock {
            val gap = 350L - (SystemClock.elapsedRealtime() - lastScreenshotAt)
            if (gap > 0) delay(gap)
            lastScreenshotAt = SystemClock.elapsedRealtime()
            suspendCancellableCoroutine { cont ->
                try {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                                var buffer: HardwareBuffer? = null
                                try {
                                    buffer = result.hardwareBuffer
                                    val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                    val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                    if (!cont.isCompleted) cont.resume(copy)
                                } catch (e: Exception) {
                                    Log.w(TAG, "无障碍截图转 Bitmap 失败: ${e.message}")
                                    if (!cont.isCompleted) cont.resume(null)
                                } finally {
                                    try {
                                        buffer?.close()
                                    } catch (_: Exception) {
                                    }
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                Log.w(TAG, "无障碍截图失败: code=$errorCode")
                                if (!cont.isCompleted) cont.resume(null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "无障碍截图请求异常: ${e.message}")
                    if (!cont.isCompleted) cont.resume(null)
                }
            }
        }
    }

    /**
     * 功能: 给当前获得输入焦点的输入框设置文本
     * 参数: text 要写入的内容 (支持中文)
     * 返回: true=写入成功
     * 使用场景: Agent 的 input_text 工具。
     *   为什么不用 adb/daemon 注入按键:
     *     中文无法用 KEYCODE 逐字模拟, 必须经过输入法, 而输入法联想会污染内容;
     *     ACTION_SET_TEXT 直接改控件的 Editable, 不经过输入法, 中文与特殊字符都能精确写入。
     */
    @Suppress("DEPRECATION")
    fun setTextOnFocused(text: String): Boolean {
        return try {
            val root = chooseRoot()
            if (root == null) {
                Log.w(TAG, "setText 失败: 拿不到根节点")
                return false
            }
            // 先找输入焦点; 部分 WebView 不上报焦点, 退化为找第一个可编辑节点
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditable(root, 0)
            if (target == null) {
                Log.w(TAG, "setText 失败: 没有可编辑控件")
                return false
            }
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            // 不 recycle: target 属于系统共享的 UI 树, 提前回收会导致同一棵树里
            // 后续 getChild 静默返回 null (详见 snapshot() 注释)
            Log.i(TAG, "setText=$ok, 长度=${text.length}")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "setText 异常: ${e.message}")
            false
        }
    }

    /**
     * 功能: 按 resource-id 后缀给输入框设置文本
     * 参数: idSuffix resource-id 后缀; text 要写入的内容
     * 返回: true=写入成功
     * 使用场景: Agent 的 set_text_id 工具 (不依赖当前焦点)
     */
    fun setTextById(idSuffix: String, text: String): Boolean {
        return try {
            val root = chooseRoot() ?: return false
            val target = findByIdSuffix(root, idSuffix, 0) ?: return false
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.w(TAG, "setTextById 失败: ${e.message}")
            false
        }
    }

    /** 广度优先找第一个 isEditable 的节点, 最多下探 30 层 */
    @Suppress("DEPRECATION")
    private fun findFirstEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 30) return null
        try {
            if (node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                // 不 recycle 未命中的 child: 它属于共享 UI 树, 回收会让后续遍历读到 null
                val hit = findFirstEditable(child, depth + 1)
                if (hit != null) return hit
            }
        } catch (e: Exception) {
            Log.w(TAG, "查找可编辑节点异常: ${e.message}")
        }
        return null
    }

    /**
     * 功能: 按文本内容查找节点并尝试点击
     * 参数:
     *   keyword 文本关键字 (包含匹配, 匹配 text 或 contentDescription)
     *   exact   true=完全相等, false=包含
     * 返回: true=找到并点击成功
     * 使用场景: Agent 的 tap_text 工具, 比坐标点击更稳 (不受分辨率/滚动影响)
     */
    @Suppress("DEPRECATION")
    fun clickByText(keyword: String, exact: Boolean = false): Boolean {
        return clickFirst { node ->
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (exact) text == keyword || desc == keyword
            else text.contains(keyword) || desc.contains(keyword)
        }
    }

    /**
     * 按文字找到节点后, 从父节点开始尝试 ACTION_CLICK。
     * 某些播放器的文字节点自身 clickable=true, 但点击事件挂在父容器上;
     * 直接点文字没有反应, 点父容器才会弹出倍速菜单。
     */
    @Suppress("DEPRECATION")
    fun clickParentByText(keyword: String): Boolean {
        return try {
            val root = chooseRoot() ?: return false
            val target = findFirst(root, { node ->
                node.text?.toString().orEmpty().contains(keyword) ||
                    node.contentDescription?.toString().orEmpty().contains(keyword)
            }, 0, 40) ?: return false
            var n: AccessibilityNodeInfo? = target.parent
            var depth = 0
            while (n != null && depth < 5) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                n = n.parent
                depth++
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "按父节点点击异常: ${e.message}")
            false
        }
    }

    /** 按 contentDescription 点击 (不匹配 text) */
    @Suppress("DEPRECATION")
    fun clickByDesc(keyword: String): Boolean {
        return clickFirst { node ->
            node.contentDescription?.toString().orEmpty().contains(keyword)
        }
    }

    /** 按 resource-id 后缀点击 */
    @Suppress("DEPRECATION")
    fun clickByIdSuffix(suffix: String): Boolean {
        return clickFirst { node ->
            node.viewIdResourceName.orEmpty().endsWith(suffix)
        }
    }

    /**
     * 用无障碍手势在屏幕坐标点击。
     *
     * 为什么需要:
     *   uinput 使用设备 natural 坐标, 而固定横屏 Activity (视频播放器) 的实际
     *   触摸坐标系是 2340x1080; 各 ROM 的旋转/固定方向处理不一致时, 直接换算
     *   会把点击送到错误位置。dispatchGesture 接收当前显示坐标, 由系统完成旋转,
     *   横屏播放器/弹窗都能点准。
     */
    fun gestureTap(x: Int, y: Int, durationMs: Long = 50L): Boolean {
        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(
                path, 0L, durationMs.coerceIn(30L, 2_000L)
            )
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "手势点击失败: ${e.message}")
            false
        }
    }

    /** 无障碍手势滑动, 坐标使用当前显示方向 */
    fun gestureSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            val stroke = GestureDescription.StrokeDescription(
                path, 0L, durationMs.coerceIn(80L, 5_000L)
            )
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "手势滑动失败: ${e.message}")
            false
        }
    }

    /** 无障碍手势长按 */
    fun gestureLongPress(x: Int, y: Int, holdMs: Long = 800L): Boolean {
        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(
                path, 0L, holdMs.coerceIn(300L, 10_000L)
            )
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "手势长按失败: ${e.message}")
            false
        }
    }

    /**
     * 用无障碍 ACTION_CLICK 点击节点或最近的可点击祖先。
     * 比坐标注入更稳: 横屏/视频页/不同分辨率下都不依赖 uinput 坐标映射。
     */
    @Suppress("DEPRECATION")
    private fun clickFirst(predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        return try {
            val root = chooseRoot() ?: return false
            val target = findFirst(root, predicate, 0, 40) ?: return false
            var n: AccessibilityNodeInfo? = target
            var depth = 0
            while (n != null && depth < 5) {
                if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                n = n.parent
                depth++
            }
            // 兜底: 给节点焦点, 让调用方再尝试坐标点击
            target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            false
        } catch (e: Exception) {
            Log.e(TAG, "无障碍点击异常: ${e.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun findFirst(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        depth: Int,
        limit: Int
    ): AccessibilityNodeInfo? {
        if (depth > limit) return null
        try {
            if (node.isVisibleToUser && predicate(node)) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val hit = findFirst(child, predicate, depth + 1, limit)
                if (hit != null) return hit
            }
        } catch (e: Exception) {
            Log.w(TAG, "查找点击节点异常: ${e.message}")
        }
        return null
    }

    /** 查找命中关键字的节点 (最多 40 个, 供外部按需使用) */
    @Suppress("DEPRECATION")
    private fun findNodes(
        node: AccessibilityNodeInfo,
        keyword: String,
        exact: Boolean,
        depth: Int,
        limit: Int
    ): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        if (depth > 40 || out.size >= limit) return out
        try {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val hit = if (exact) {
                text == keyword || desc == keyword
            } else {
                text.contains(keyword) || desc.contains(keyword)
            }
            if (hit && node.isVisibleToUser) out.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                // 不做 recycle, 原因同 isClickableAncestor
                out.addAll(findNodes(child, keyword, exact, depth + 1, limit - out.size))
                if (out.size >= limit) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "查找文本节点异常: ${e.message}")
        }
        return out
    }
}
