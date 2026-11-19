package com.catfee.rootauto

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.catfee.rootauto.a11y.UiObserverService
import com.catfee.rootauto.agent.MediaState
import com.catfee.rootauto.agent.ToolRuntime
import com.catfee.rootauto.console.ConsoleClient
import com.catfee.rootauto.console.ConsoleSettings
import com.catfee.rootauto.console.RemoteConsoleService
import com.catfee.rootauto.device.DeviceCapabilities
import com.catfee.rootauto.llm.DecisionSettings
import com.catfee.rootauto.llm.LlmSettings
import com.catfee.rootauto.ocr.OcrEngine
import com.catfee.rootauto.ocr.PaddleOcrEngine
import com.catfee.rootauto.root.ScreenGeometry
import com.catfee.rootauto.root.ScreenVision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * DebugReceiver —— adb 调试入口
 *
 * 不切换前台界面, 直接读取当前窗口:
 *   am broadcast -a com.catfee.rootauto.DEBUG --es cmd dump
 *   am broadcast -a com.catfee.rootauto.DEBUG --es cmd ocr
 *   am broadcast -a com.catfee.rootauto.DEBUG --es cmd verifyq \
 *     --es options 'A:100,1481;B:100,1632'
 *
 * 结果输出到 logcat, tag 为 CatfeeDebug。
 */
class DebugReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CatfeeDebug"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd")?.trim().orEmpty()
        Log.i(TAG, "cmd=$cmd")
        when (cmd) {
            "dump" -> dumpTree()
            "dumpfocus" -> dumpFocus()
            "clipset" -> clipSet(context, intent)
            "settext" -> {
                val text = intent.getStringExtra("text").orEmpty()
                val ok = UiObserverService.instance?.setTextOnFocused(text) ?: false
                val line = "SETTEXT ok=$ok text=$text"
                Log.i(TAG, line)
                debugOut(context, line)
            }
            "paste" -> scope.launch {
                val text = intent.getStringExtra("text").orEmpty()
                val x = intent.getIntExtra("x", 630)
                val y = intent.getIntExtra("y", 1700)
                val result = ToolRuntime.pasteText(context.applicationContext, text, x, y)
                Log.i(TAG, "PASTE=$result")
                debugOut(context, "PASTE=$result")
            }
            "dumpcaps" -> scope.launch { dumpCaps(context) }
            "coords" -> scope.launch {
                ScreenGeometry.displaySize()
                Log.i(TAG, "===== COORDS =====\n" + ScreenGeometry.describe())
            }
            "media" -> scope.launch {
                Log.i(TAG, "===== MEDIA =====\n" + MediaState.describe())
            }
            "mediaspeed" -> scope.launch {
                Log.i(
                    TAG,
                    "===== MEDIASPEED =====\n" +
                        MediaState.setSpeed(intent.getFloatExtra("speed", 2.0f).toDouble())
                )
            }
            "waitmedia" -> scope.launch {
                Log.i(
                    TAG,
                    "===== WAITMEDIA =====\n" + MediaState.waitMedia(
                        intent.getFloatExtra(
                            "ratio",
                            intent.getDoubleExtra("ratio", 1.0).toFloat()
                        ).toDouble(),
                        intent.getLongExtra("max", 300_000L)
                    )
                )
            }
            "ocr" -> scope.launch { runOcr() }
            "provision" -> provision(context, intent)
            "activateprovider" -> {
                val id = intent.getStringExtra("id")?.trim().orEmpty()
                if (id.isNotBlank()) {
                    LlmSettings.setActiveId(context.applicationContext, id)
                    Log.i(TAG, "provider active=$id")
                }
            }
            "removeprovider" -> {
                val id = intent.getStringExtra("id")?.trim().orEmpty()
                if (id.isNotBlank()) {
                    LlmSettings.remove(context.applicationContext, id)
                    Log.i(TAG, "provider removed id=$id active=${LlmSettings.activeId(context)}")
                }
            }
            "consolebind" -> scope.launch { bindConsole(context, intent) }
            "consolestart" -> {
                RemoteConsoleService.ensureStarted(context.applicationContext)
                Log.i(TAG, "console service start requested")
            }
            "consolestop" -> {
                RemoteConsoleService.stop(context.applicationContext)
                Log.i(TAG, "console service stopped")
            }
            "consoleunbind" -> {
                RemoteConsoleService.stop(context.applicationContext)
                ConsoleSettings.clear(context.applicationContext)
                Log.i(TAG, "console unbound")
            }
            "consolestatus" -> dumpConsole(context)
            "dumpconfig" -> dumpConfig(context)
            "paddleocr" -> scope.launch { runPaddleOcr(context) }
            "longocr" -> scope.launch { runLongOcr(context) }
            "listopts" -> Log.i(
                TAG,
                "===== LISTOPTIONS =====\n" +
                    ToolRuntime.listOptions(intent.getStringExtra("keyword").orEmpty())
            )
            "verifyq" -> scope.launch { runVerifyQuestion(intent) }
            "answerform" -> scope.launch {
                val r = com.catfee.rootauto.agent.FormSolver.solve(
                    context.applicationContext,
                    intent.getStringExtra("title").orEmpty(),
                    3,
                    true,
                    intent.getBooleanExtra("reset", true),
                    onLog = { line -> Log.i(TAG, line) }
                )
                Log.i(TAG, "===== ANSWERFORM submitted=${r.submitted} answered=${r.answered} msg=${r.message} =====")
            }
            else -> Log.w(TAG, "未知调试命令: $cmd")
        }
    }

    /**
     * 运行时写入 LLM / DECISION 配置, 用于换机或批量调试。
     * Key 只从 adb 参数读取并写入 App 的加密偏好, 不落源码。
     *
     * am broadcast -n com.catfee.rootauto/.DebugReceiver \
     *   -a com.catfee.rootauto.DEBUG --es cmd provision \
     *   --es baseUrl ... --es apiKey ... --es model ... \
     *   --es decisionBaseUrl ... --es decisionApiKey ... --es decisionModel ...
     */
    private fun provision(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "provision 仅在 debug 包可用")
            return
        }
        val app = context.applicationContext
        val baseUrl = intent.getStringExtra("baseUrl")?.trim().orEmpty()
        val apiKey = intent.getStringExtra("apiKey")?.trim().orEmpty()
        val model = intent.getStringExtra("model")?.trim().orEmpty()
        // 默认覆盖 main; 测试时可写独立 provider, 不影响用户已保存的默认配置
        val providerId = intent.getStringExtra("providerId")?.trim().orEmpty().ifBlank { "main" }
        val activate = intent.getBooleanExtra("activate", true)

        if (baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()) {
            LlmSettings.upsert(
                app,
                LlmSettings.Provider(
                    id = providerId,
                    name = if (providerId == "main") "默认" else providerId,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model
                )
            )
            if (activate) LlmSettings.setActiveId(app, providerId)
            Log.i(
                TAG,
                "provision provider=$providerId active=${LlmSettings.activeId(app)} " +
                    "model=$model keyLen=${apiKey.length}"
            )
        } else {
            Log.w(TAG, "provision main 参数不完整, 跳过")
        }

        val decisionBaseUrl = intent.getStringExtra("decisionBaseUrl")?.trim().orEmpty()
        val decisionApiKey = intent.getStringExtra("decisionApiKey")?.trim().orEmpty()
        val decisionModel = intent.getStringExtra("decisionModel")?.trim().orEmpty()
        if (decisionBaseUrl.isNotBlank() && decisionApiKey.isNotBlank() && decisionModel.isNotBlank()) {
            val saved = DecisionSettings.save(
                app,
                DecisionSettings.Config(
                    enabled = intent.getBooleanExtra("decisionEnabled", true),
                    baseUrl = decisionBaseUrl,
                    apiKey = decisionApiKey,
                    model = decisionModel,
                    minConfidence = intent.getDoubleExtra(
                        "minConfidence", DecisionSettings.DEFAULT_MIN_CONFIDENCE
                    ),
                    maxFastSteps = intent.getIntExtra(
                        "maxFastSteps", DecisionSettings.DEFAULT_MAX_FAST_STEPS
                    )
                )
            )
            Log.i(
                TAG,
                "provision decision: saved=$saved model=$decisionModel keyLen=${decisionApiKey.length}"
            )
        } else {
            Log.w(TAG, "provision decision 参数不完整, 跳过")
        }
    }

    /**
     * 用 Web 控制台一次性注册码绑定设备, 便于换机/批量调试。
     * am broadcast -a com.catfee.rootauto.DEBUG --es cmd consolebind \
     *   --es baseUrl http://192.168.1.100:9777 --es code xxx --es name "测试机"
     */
    private suspend fun bindConsole(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "consolebind 仅在 debug 包可用")
            return
        }
        val app = context.applicationContext
        val baseUrl = intent.getStringExtra("baseUrl")?.trim().orEmpty()
        val code = intent.getStringExtra("code")?.trim().orEmpty()
        val name = intent.getStringExtra("name")?.trim().orEmpty()
        val res = ConsoleClient.enroll(app, baseUrl, code, name)
        res.onSuccess {
            RemoteConsoleService.ensureStarted(app)
            Log.i(
                TAG,
                "CONSOLE bound deviceId=${it.deviceId} base=${it.baseUrl} " +
                    "connected=${ConsoleClient.connected.value}"
            )
        }.onFailure {
            Log.w(TAG, "CONSOLE bind failed: ${it.message}")
        }
    }

    /** 输出控制台绑定状态, 不打印 deviceSecret */
    private fun dumpConsole(context: Context) {
        val cfg = ConsoleSettings.load(context.applicationContext)
        Log.i(
            TAG,
            "CONSOLE enabled=${cfg.enabled} base=${cfg.baseUrl} deviceId=${cfg.deviceId} " +
                "name=${cfg.deviceName} usable=${cfg.usable} " +
                "connected=${ConsoleClient.connected.value} status=${ConsoleClient.status.value}"
        )
    }

    /** 输出配置摘要 (不含 Key 明文), 便于换机后核对 */
    private fun dumpConfig(context: Context) {
        val app = context.applicationContext
        val main = LlmSettings.active(app)
        val decision = DecisionSettings.load(app)
        Log.i(
            TAG,
            "CONFIG main baseUrl=${main?.baseUrl.orEmpty()} model=${main?.model.orEmpty()} " +
                "keyLen=${main?.apiKey?.length ?: 0}"
        )
        Log.i(
            TAG,
            "CONFIG decision enabled=${decision.enabled} baseUrl=${decision.baseUrl} " +
                "model=${decision.model} keyLen=${decision.apiKey.length} " +
                "minConf=${decision.minConfidence} maxFast=${decision.maxFastSteps}"
        )
    }

    /** 输出设备能力摘要, 便于排查非 Root 模式 */
    private suspend fun dumpCaps(context: Context) {
        val caps = DeviceCapabilities.refresh(context.applicationContext, force = true)
        Log.i(
            TAG,
            "CAPS mode=${caps.modeLabel()} root=${caps.root} daemon=${caps.daemon} " +
                "a11y=${caps.accessibility} gestures=${caps.gestures} " +
                "screenshot=${caps.screenshot} sdk=${caps.androidSdk} " +
                "input=${caps.input} overlay=${caps.overlay}"
        )
    }

    /** 输出完整无障碍树 */
    private fun dumpTree() {        val svc = UiObserverService.instance
        if (svc == null) {
            Log.w(TAG, "无障碍服务未连接")
            return
        }
        val tree = svc.dumpTree()
        Log.i(TAG, "===== TREE ${tree.size} =====")
        tree.forEach { n ->
            Log.i(
                TAG,
                "T|${n.index}|${n.parent}|${n.depth}|${n.cls}|${n.text}|${n.desc}|" +
                    "${n.resourceId.substringAfterLast('/')}|${n.clickable}|${n.editable}|" +
                    "${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom}"
            )
        }
        Log.i(TAG, "===== END =====")
    }

    private fun debugOut(context: Context, text: String) {
        try {
            File(context.filesDir, "debug.log").appendText(text + "\n")
        } catch (_: Exception) {
        }
    }

    /** 检查当前焦点节点是否可编辑; 用于排查微信这类无障碍树为空的 App */
    private fun dumpFocus() {
        val svc = UiObserverService.instance
        if (svc == null) {
            Log.w(TAG, "无障碍服务未连接")
            return
        }
        val root = svc.rootInActiveWindow
        val focus = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val rect = android.graphics.Rect()
        focus?.getBoundsInScreen(rect)
        val line = "FOCUS rootPkg=${root?.packageName} children=${root?.childCount} " +
            "node=${focus?.className} pkg=${focus?.packageName} " +
            "editable=${focus?.isEditable} text=${focus?.text} bounds=$rect"
        Log.i(TAG, line)
        debugOut(svc, line)
    }

    /** 测试后台能否写剪贴板 (微信输入兜底方案) */
    private fun clipSet(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text").orEmpty()
        try {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("catfee", text))
            Log.i(TAG, "CLIPBOARD set ok len=${text.length}")
        } catch (e: Exception) {
            Log.w(TAG, "CLIPBOARD set failed: ${e.message}")
        }
    }

    /** 截图并 OCR */
    private suspend fun runOcr() {
        val (bmp, err) = ScreenVision.captureBitmap()
        if (bmp == null) {
            Log.w(TAG, "OCR 截图失败: $err")
            return
        }
        try {
            val rows = OcrEngine.rowsOf(bmp)
            Log.i(TAG, "===== OCR ${rows.size} =====")
            val sb = StringBuilder("OCR ${rows.size}\n")
            rows.forEachIndexed { i, r ->
                Log.i(TAG, "O|$i|${r.box.left},${r.box.top},${r.box.right},${r.box.bottom}|${r.text}")
                sb.append("O|${r.box.left},${r.box.top},${r.box.right},${r.box.bottom}|${r.text}\n")
            }
            UiObserverService.instance?.let { debugOut(it, sb.toString()) }
            Log.i(TAG, "===== OCR END =====")
        } finally {
            bmp.recycle()
        }
    }

    /** 本地 PP-OCR 识别当前屏幕 */
    private suspend fun runPaddleOcr(context: Context) {
        val (bmp, err) = ScreenVision.captureBitmap()
        if (bmp == null) {
            Log.w(TAG, "PP-OCR 截图失败: $err")
            return
        }
        try {
            val lines = PaddleOcrEngine.recognize(context.applicationContext, bmp)
            Log.i(TAG, "===== PADDLE ${lines.size} =====")
            lines.forEach {
                Log.i(TAG, "P|${it.box.left},${it.box.top},${it.box.right},${it.box.bottom}|${it.text}")
            }
            Log.i(TAG, "===== PADDLE END =====")
        } finally {
            bmp.recycle()
        }
    }

    /** 滚动拼接 OCR 当前长页面 */
    private suspend fun runLongOcr(context: Context) {
        val scan = com.catfee.rootauto.ocr.LongOcr.scan(context.applicationContext, 18)
        Log.i(TAG, "===== LONGOCR ${scan.screens} screens ${scan.lines.size} lines =====")
        scan.lines.forEach {
            Log.i(TAG, "L|${it.box.left},${it.box.top},${it.box.right},${it.box.bottom}|${it.text}")
        }
        Log.i(TAG, "===== LONGOCR END =====")
    }

    /** 批量核对选项选中态: --es options "A:100,1481;B:100,1632" */
    private suspend fun runVerifyQuestion(intent: Intent) {
        val raw = intent.getStringExtra("options").orEmpty()
        val arr = org.json.JSONArray()
        raw.split(';').forEach { part ->
            val seg = part.split(':')
            if (seg.size == 2) {
                val xy = seg[1].split(',')
                if (xy.size == 2) {
                    arr.put(
                        org.json.JSONObject()
                            .put("letter", seg[0].trim())
                            .put("x", xy[0].trim().toIntOrNull() ?: -1)
                            .put("y", xy[1].trim().toIntOrNull() ?: -1)
                    )
                }
            }
        }
        Log.i(TAG, "===== VERIFYQ =====\n" + ToolRuntime.verifyQuestion(arr))
    }
}
