package com.catfee.rootauto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.catfee.rootauto.a11y.UiObserverService
import com.catfee.rootauto.agent.AgentController
import com.catfee.rootauto.agent.AppMemory
import com.catfee.rootauto.agent.memory.MemoryCurator
import com.catfee.rootauto.console.ConsoleClient
import com.catfee.rootauto.console.ConsoleSettings
import com.catfee.rootauto.console.EnrollQr
import com.catfee.rootauto.console.RemoteConsoleService
import com.catfee.rootauto.ui.QrScannerDialog
import com.catfee.rootauto.device.DeviceCapabilities
import com.catfee.rootauto.device.DeviceMode
import com.catfee.rootauto.llm.DecisionClient
import com.catfee.rootauto.llm.DecisionSettings
import com.catfee.rootauto.llm.LlmClient
import com.catfee.rootauto.llm.LlmSettings
import com.catfee.rootauto.llm.UsageSettings
import com.catfee.rootauto.root.RootShell
import com.catfee.rootauto.root.ScreenGeometry
import com.catfee.rootauto.service.AgentStatusService
import com.catfee.rootauto.service.DisplaySettings
import com.catfee.rootauto.session.Attachment
import com.catfee.rootauto.session.AttachmentAnalyzer
import com.catfee.rootauto.session.ChatMessage
import com.catfee.rootauto.session.Session
import com.catfee.rootauto.session.SessionExporter
import com.catfee.rootauto.session.SessionManager
import com.catfee.rootauto.session.SessionSummary
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CatfeeAgents 控制台
 *
 * 一个统一的对话界面:
 *   - 普通聊天/问答/附件分析: 直接回答
 *   - 需要操作手机时: 在聊天框内请求一次授权, 本会话不再重复询问
 *   - 回复流式输出, 支持思考过程折叠、代码块、复制、历史会话与 token/上下文统计
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenGeometry.init(this)
        window.statusBarColor = AndroidColor.rgb(247, 248, 250)
        window.navigationBarColor = AndroidColor.rgb(247, 248, 250)
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars = true
        setContent {
            AgentStudioTheme {
                AppRoot(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sessionId = intent.getStringExtra("session_id").orEmpty()
        if (sessionId.isNotBlank()) {
            lifecycleScope.launch {
                SessionManager.open(this@MainActivity, sessionId)
            }
        }
    }
}

private const val DEFAULT_BASE_URL = ""
// 不把真实 API Key 提交到仓库; 首次使用请在 Settings 里填写
private const val DEFAULT_API_KEY = ""
private const val DEFAULT_MODEL = "deepseek-v4.1-flash"

private val Bg = Color(0xFFF7F8FA)
private val Ink = Color(0xFF111827)
private val Muted = Color(0xFF6B7280)
private val Line = Color(0xFFE5E7EB)
/** 品牌红: 与黑白红 Logo 一致, 不再使用蓝色 */
private val Accent = Color(0xFFE11D2E)
private val Success = Color(0xFF16A34A)
private val Warning = Color(0xFFD97706)
private val Danger = Color(0xFFDC2626)
private val Violet = Color(0xFF7C3AED)

@Composable
private fun AgentStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink,
            onPrimary = Color.White,
            background = Bg,
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink,
            surfaceVariant = Color(0xFFF1F3F5),
            onSurfaceVariant = Muted,
            outline = Line,
            error = Danger
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}

/**
 * 启动只保留窗口背景里的那一层 Logo:
 * 先用同色底把 Logo 淡出成纯背景, 再让主界面淡入, 避免两个 Logo 同屏或位置跳动。
 */
@Composable
private fun AppRoot(activity: ComponentActivity) {
    var phase by rememberSaveable { mutableIntStateOf(0) }
    val cover by animateFloatAsState(
        targetValue = if (phase >= 1) 1f else 0f,
        animationSpec = tween(260),
        label = "splashCover"
    )
    val content by animateFloatAsState(
        targetValue = if (phase >= 2) 1f else 0f,
        animationSpec = tween(380),
        label = "appEnter"
    )

    Box(Modifier.fillMaxSize()) {
        // 同色遮罩: 盖掉窗口背景里的 Logo, 之后常驻在主界面下方
        Box(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .graphicsLayer { alpha = cover }
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = content
                    translationY = (1f - content) * 18.dp.toPx()
                }
        ) {
            AgentConsole(activity)
        }
    }

    LaunchedEffect(Unit) {
        if (phase == 0) {
            withFrameNanos { }
            phase = 1
            delay(260)
            phase = 2
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AgentConsole(activity: ComponentActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val imeVisible = WindowInsets.isImeVisible

    val current by SessionManager.current.collectAsState()
    val sessions by SessionManager.summaries.collectAsState()
    val agentStates by AgentController.states.collectAsState()
    val agent = agentStates[current?.id.orEmpty()] ?: AgentController.State()

    var task by remember {
        mutableStateOf(activity.intent?.getStringExtra("task")?.takeIf { it.isNotBlank() } ?: "")
    }
    var pendingAttachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var previewImages by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var previewIndex by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<SessionSummary?>(null) }
    var pendingRename by remember { mutableStateOf<SessionSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    var actionMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editText by remember { mutableStateOf("") }
    var quotedText by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var rootGranted by remember { mutableStateOf<Boolean?>(null) }
    var overlayEnabled by remember { mutableStateOf(DisplaySettings.overlayEnabled(activity)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(activity)) }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = Settings.canDrawOverlays(activity)
        overlayGranted = granted
        if (granted) {
            overlayEnabled = true
            DisplaySettings.setOverlayEnabled(activity, true)
            AgentStatusService.refreshOverlay(activity)
        }
    }

    fun requestOverlayPermission() {
        try {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        } catch (e: Exception) {
            try {
                overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
            }
        }
    }
    var hint by remember { mutableStateOf("") }

    // 打开设置时只查询授权状态, 不主动申请 root
    LaunchedEffect(showSettings) {
        if (showSettings) {
            rootGranted = withContext(Dispatchers.IO) { RootShell.appGrantedRoot() }
            overlayGranted = Settings.canDrawOverlays(activity)
            DeviceCapabilities.refresh(activity, force = true)
        }
    }

    // ---- LLM 配置 ----
    val saved = remember { LlmSettings.active(activity) }
    var baseUrl by remember { mutableStateOf(saved?.baseUrl ?: DEFAULT_BASE_URL) }
    var apiKey by remember { mutableStateOf(saved?.apiKey ?: DEFAULT_API_KEY) }
    var model by remember { mutableStateOf(saved?.model ?: DEFAULT_MODEL) }
    var testResult by remember { mutableStateOf("") }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchingModels by remember { mutableStateOf(false) }

    // ---- 内置 Web 控制台配置 (设备凭证, 不使用管理员 JWT) ----
    val consoleCfg = remember { ConsoleSettings.load(activity) }
    var consoleEnabled by remember { mutableStateOf(consoleCfg.enabled) }
    var consoleBase by remember { mutableStateOf(consoleCfg.baseUrl) }
    var consoleCode by remember { mutableStateOf("") }
    var consoleName by remember {
        mutableStateOf(
            consoleCfg.deviceName.ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}" }
        )
    }
    var consoleDeviceId by remember { mutableStateOf(consoleCfg.deviceId) }
    var consoleStatus by remember { mutableStateOf(consoleCfg.lastStatus) }
    var showConsoleScanner by remember { mutableStateOf(false) }
    val consoleConnected by ConsoleClient.connected.collectAsState()
    val consoleLiveStatus by ConsoleClient.status.collectAsState()
    LaunchedEffect(consoleLiveStatus) {
        if (consoleLiveStatus.isNotBlank()) consoleStatus = consoleLiveStatus
    }
    // 已绑定且启用时, 冷启动自动恢复长连接
    LaunchedEffect(Unit) {
        if (ConsoleSettings.load(activity).usable) {
            RemoteConsoleService.ensureStarted(activity)
        }
    }

    LaunchedEffect(Unit) {
        SessionManager.ensureSession(activity, "chat")
    }

    fun currentProvider() = LlmSettings.Provider(
        id = "main",
        name = "默认",
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim()
    )

    /** 本会话模型优先; 空则跟随全局默认 */
    fun providerFor(session: Session?): LlmSettings.Provider {
        val base = currentProvider()
        val override = session?.model?.trim().orEmpty()
        return if (override.isBlank()) base else base.copy(model = override)
    }

    fun saveConsole(
        enabled: Boolean = consoleEnabled,
        deviceId: String = consoleDeviceId,
        baseUrl: String = consoleBase,
        deviceName: String = consoleName
    ) {
        ConsoleSettings.save(
            activity,
            ConsoleSettings.Config(
                enabled = enabled,
                baseUrl = ConsoleSettings.normalizeBase(baseUrl),
                deviceId = deviceId,
                deviceSecret = ConsoleSettings.load(activity).deviceSecret,
                deviceName = deviceName.trim(),
                lastStatus = consoleStatus
            )
        )
    }

    /** 手动输入和扫码共用同一套绑定逻辑。 */
    fun bindConsole(
        base: String = consoleBase,
        code: String = consoleCode,
        name: String = consoleName
    ) {
        if (base.isBlank()) {
            consoleStatus = "请填写 Web 控制台地址"
            return
        }
        if (code.isBlank()) {
            consoleStatus = "请填写 Web 控制台生成的一次性注册码"
            return
        }
        consoleStatus = "正在绑定…"
        scope.launch {
            val res = ConsoleClient.enroll(activity, base, code, name)
            withContext(Dispatchers.Main) {
                res.onSuccess { cfg ->
                    consoleEnabled = true
                    consoleBase = cfg.baseUrl
                    consoleDeviceId = cfg.deviceId
                    consoleCode = ""
                    consoleName = cfg.deviceName.ifBlank { name }
                    consoleStatus = "已绑定 ${cfg.deviceId}"
                    RemoteConsoleService.ensureStarted(activity)
                }.onFailure {
                    consoleStatus = it.message ?: "绑定失败"
                }
            }
        }
    }

    fun resetComposer() {
        task = ""
        pendingAttachments = emptyList()
        previewImages = emptyList()
        quotedText = ""
        hint = ""
    }

    fun newSession() {
        SessionManager.flush(activity)
        SessionManager.newSession("chat")
        resetComposer()
        showMenu = false
    }

    // 切换/删除会话时清空输入框草稿和待发送附件, 避免旧会话内容残留
    var lastSessionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current?.id) {
        val id = current?.id ?: return@LaunchedEffect
        val previous = lastSessionId
        lastSessionId = id
        if (previous != null && previous != id) resetComposer()
    }

    var exportTarget by remember { mutableStateOf<SessionSummary?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) {
            scope.launch {
                val s = SessionManager.snapshot(activity, target.id)
                if (s == null) {
                    hint = "导出失败: 会话不存在"
                } else {
                    hint = if (SessionExporter.write(activity, s, uri)) {
                        "已导出到所选位置"
                    } else {
                        "导出失败"
                    }
                }
            }
        }
    }

    fun exportSession(s: SessionSummary) {
        exportTarget = s
        exportLauncher.launch(SessionExporter.safeFileName(s.title))
    }

    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val room = (6 - pendingAttachments.size).coerceAtLeast(0)
            val imported = ArrayList<Attachment>()
            uris.take(room).forEach { uri ->
                AttachmentAnalyzer.import(activity, uri)?.let { imported.add(it) }
            }
            withContext(Dispatchers.Main) {
                pendingAttachments = pendingAttachments + imported
                when {
                    uris.size > room -> hint = "最多同时添加 6 个附件"
                    imported.size < uris.size -> hint = "部分附件导入失败或超过 30MB"
                }
            }
        }
    }

    fun goalWithContext(msg: ChatMessage): String = buildString {
        if (msg.quotedText.isNotBlank()) {
            append("引用内容:\n").append(msg.quotedText.take(1_000)).append("\n\n用户: ")
        }
        append(msg.content)
        if (msg.context.isNotBlank()) {
            append("\n\n以下是该消息附件的本地解析内容, 请结合它回答:\n")
            append(msg.context.take(30_000))
        }
    }

    fun startTurn(
        session: Session,
        prior: String,
        goal: String,
        attachments: List<Attachment>
    ) {
        val provider = providerFor(session)
        if (!provider.usable) {
            hint = "LLM 配置不完整，点菜单进入 Settings 填写"
            showSettings = true
            return
        }
        val ok = AgentController.start(
            context = activity,
            provider = provider,
            goal = goal,
            sessionId = session.id,
            priorContext = prior,
            phoneAllowed = session.phoneAllowed,
            attachments = attachments
        )
        if (!ok) hint = "当前还有任务在运行，请先停止"
    }

    fun send() {
        if (agent.running) {
            hint = "当前会话还有任务在运行，先停止或等待完成"
            return
        }
        val text = task.trim()
        val attachments = pendingAttachments
        if (text.isEmpty() && attachments.isEmpty()) {
            hint = "先输入消息，或添加图片/文件"
            return
        }
        val session = current ?: SessionManager.newSession("chat")
        val provider = providerFor(session)
        if (!provider.usable) {
            hint = "LLM 配置不完整，点菜单进入 Settings 填写"
            showSettings = true
            return
        }
        // 全局默认仍保存; 会话级模型只影响本次请求, 不污染默认配置
        LlmSettings.upsert(activity, currentProvider())
        LlmSettings.setActiveId(activity, "main")
        keyboard?.hide()
        hint = ""

        val content = text.ifBlank { "请分析这些附件" }
        val quote = quotedText
        val userMessage = ChatMessage(
            "user", content, attachments = attachments, quotedText = quote
        )
        val prior = buildPriorContext(session)
        // 先把用户消息和附件发出去; 解析工作交给 AgentController,
        // 这样聊天区会立即出现"解析资源中"的工具步骤, 而不是发送前卡住。
        SessionManager.appendMessage(activity, session.id, userMessage)
        startTurn(
            session = session,
            prior = prior,
            goal = goalWithContext(userMessage),
            attachments = attachments
        )
        task = ""
        pendingAttachments = emptyList()
        quotedText = ""
    }

    fun regenerateFrom(msg: ChatMessage, retryHint: Boolean = false) {
        if (agent.running) {
            hint = "当前还有任务在运行，先停止后再重新生成"
            actionMessage = null
            return
        }
        val session = current ?: return
        val idx = session.messages.indexOfFirst { it.id == msg.id }
        if (idx < 0) return
        val anchor = if (msg.role == "assistant") {
            session.messages.take(idx).lastOrNull { it.role == "user" }
        } else {
            msg
        }
        if (anchor == null) {
            hint = "找不到对应的用户消息"
            return
        }
        actionMessage = null
        scope.launch {
            val updated = SessionManager.truncateAfter(activity, session.id, anchor.id) ?: return@launch
            val prior = buildPriorContext(
                updated.copy(messages = updated.messages.dropLast(1))
            )
            val goal = buildString {
                if (retryHint) {
                    append("上次因另一个会话的 Agent 占用手机，未能进入手机操作模式。")
                    append("请重新进入手机操作模式并继续该任务。\n\n")
                }
                append(goalWithContext(anchor))
            }
            startTurn(updated, prior, goal, emptyList())
        }
    }

    fun retryAgent(assistant: ChatMessage) {
        val anchorId = assistant.retryAnchorId
        if (anchorId.isBlank()) return
        val anchor = current?.messages?.firstOrNull { it.id == anchorId } ?: return
        actionMessage = null
        regenerateFrom(anchor, retryHint = true)
    }

    fun branchFrom(msg: ChatMessage) {
        val session = current ?: return
        actionMessage = null
        scope.launch {
            val branch = SessionManager.fork(activity, session.id, msg.id)
            hint = if (branch != null) "已从该消息创建分支会话" else "分支失败"
        }
    }

    fun stop() {
        AgentController.stop(current?.id.orEmpty(), "应用内按钮")
        hint = "已停止"
    }

    fun togglePause() {
        val sessionId = current?.id.orEmpty()
        if (sessionId.isBlank()) return
        val wasPaused = agent.paused
        val changed = if (wasPaused) {
            AgentController.resume(sessionId)
        } else {
            AgentController.pause(sessionId)
        }
        if (changed) hint = if (wasPaused) "已继续" else "已暂停"
    }

    // 通知权限
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 启动时补跑上次没整理完的记忆任务
        MemoryCurator.requestDrain(context.applicationContext)
    }

    // adb autostart
    var autoStarted by remember { mutableStateOf(false) }
    val autoStart = activity.intent?.getBooleanExtra("autostart", false) == true
    LaunchedEffect(Unit) {
        if (autoStart && !autoStarted && task.isNotBlank()) {
            autoStarted = true
            delay(220)
            send()
        }
    }

    val streaming = agent.streaming && agent.sessionId == current?.id
    val running = agent.running
    val pendingConfirm = agent.pendingConfirm?.takeIf { it.sessionId == current?.id }
    val liveSteps = if (streaming) agent.steps else emptyList()
    val savedSteps = current?.steps.orEmpty().map {
        AgentController.Step(it.seq, it.type, it.text, it.tool, it.time)
    }
    val allImages = current?.messages?.flatMap { m ->
        m.attachments.filter { it.kind == "image" }
    }.orEmpty()

    val listState = rememberLazyListState()
    val scrollSignal = current?.messages?.size ?: 0
    LaunchedEffect(
        current?.id,
        scrollSignal,
        streaming,
        pendingConfirm,
        agent.streamText.length / 40,
        agent.streamReasoning.length / 80
    ) {
        delay(35)
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) {
            // 给最后一项一个很大的 offset, 让列表真正贴到底部, 而不是只把最后一项的顶部对齐
            if (streaming) {
                listState.scrollToItem(count - 1, 1_000_000)
            } else {
                listState.animateScrollToItem(count - 1, 1_000_000)
            }
        }
    }

    // 输入法弹起会压缩聊天区高度, 但 LazyColumn 不会自动重新贴底,
    // 导致最后一条回复被输入框/键盘挡住。IME 高度变化后重新贴底;
    // 布局会分几帧完成, 连续校正几次, 避免动画被下一次测量打断。
    LaunchedEffect(imeBottom, imeVisible) {
        if (imeBottom > 0 || imeVisible) {
            repeat(4) {
                delay(120)
                val count = listState.layoutInfo.totalItemsCount
                if (count > 0) {
                    listState.scrollToItem(count - 1, 1_000_000)
                }
            }
        }
    }

    Scaffold(containerColor = Bg) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Header(
                running = running,
                onMenu = {
                    SessionManager.flush(activity)
                    scope.launch { SessionManager.refreshHistory(activity) }
                    showMenu = true
                }
            )
            current?.takeIf { !it.isEmpty }?.let { SessionLine(it) }

            // 每个会话使用独立的列表实例: 切换/删除会话时旧消息和动画层立即销毁,
            // 避免旧气泡残留在新会话的空状态下面。
            key(current?.id) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (current == null || (current!!.messages.isEmpty() && !streaming)) {
                    item { EmptyState() }
                }
                current?.messages?.forEach { msg ->
                    item(key = "m-${msg.id}") {
                        Box(Modifier.animateItem()) {
                            if (msg.role == "user") {
                                UserBubble(
                                    msg,
                                    onImageClick = { a ->
                                        val idx = allImages.indexOfFirst { it.id == a.id }
                                        previewImages = allImages
                                        previewIndex = if (idx >= 0) idx else 0
                                    },
                                    onFileClick = { a -> openAttachment(context, a) },
                                    onLongPress = { actionMessage = msg }
                                )
                            } else {
                                AssistantBubble(
                                    msg,
                                    onLongPress = { actionMessage = msg },
                                    onRetry = if (msg.retryAnchorId.isNotBlank()) {
                                        { retryAgent(msg) }
                                    } else null
                                )
                            }
                        }
                    }
                }
                if (streaming) {
                    item(key = "streaming") {
                        StreamingBubble(
                            reasoning = agent.streamReasoning,
                            content = agent.streamText,
                            tool = agent.currentTool
                        )
                    }
                }
                if (pendingConfirm != null) {
                    item(key = "confirm-${pendingConfirm.tool}") {
                        ApprovalCard(
                            pending = pendingConfirm,
                            onAllow = {
                                try {
                                    AgentStatusService.start(activity)
                                } catch (e: Exception) {
                                    hint = "通知服务启动失败: ${e.message}"
                                }
                                AgentController.answerConfirm(current?.id.orEmpty(), true)
                            },
                            onDeny = {
                                AgentController.answerConfirm(current?.id.orEmpty(), false)
                            }
                        )
                    }
                }
                val steps = if (streaming) liveSteps else savedSteps
                if (steps.isNotEmpty()) {
                    item(key = "activity") {
                        ActivitySection(
                            steps = steps,
                            running = streaming,
                            currentTool = agent.currentTool
                        )
                    }
                }
            }
            }

            Composer(
                task = task,
                onTaskChange = { task = it },
                model = current?.model?.ifBlank { model } ?: model,
                onModelClick = { showModelSheet = true },
                quotedText = quotedText,
                onClearQuote = { quotedText = "" },
                attachments = pendingAttachments,
                onRemoveAttachment = { id ->
                    pendingAttachments = pendingAttachments.filterNot { it.id == id }
                },
                onPickAttachments = { attachmentPicker.launch(arrayOf("*/*")) },
                running = running,
                paused = agent.paused,
                canSend = !running &&
                    (task.isNotBlank() || pendingAttachments.isNotEmpty()),
                onSend = { send() },
                onPauseResume = { togglePause() },
                onStop = { stop() },
                hint = hint,
                onFocused = {
                    // 输入框获得焦点后键盘会分几帧弹起, 连续贴底, 避免最后一条回复被挡住
                    scope.launch {
                        repeat(4) {
                            delay(150)
                            val count = listState.layoutInfo.totalItemsCount
                            if (count > 0) listState.scrollToItem(count - 1, 1_000_000)
                        }
                    }
                }
            )
        }
    }

    if (showMenu) {
        MenuSheet(
            sessions = sessions,
            currentId = current?.id.orEmpty(),
            runningSessionIds = agentStates.filter { it.value.running }.keys,
            onNew = { newSession() },
            onOpen = { id ->
                scope.launch {
                    SessionManager.open(activity, id)
                    showMenu = false
                }
            },
            onDelete = { pendingDelete = it },
            onRename = {
                pendingRename = it
                renameText = it.title
            },
            onPin = { s ->
                scope.launch { SessionManager.setFlags(activity, s.id, pinned = !s.pinned) }
            },
            onFavorite = { s ->
                scope.launch { SessionManager.setFlags(activity, s.id, favorite = !s.favorite) }
            },
            onArchive = { s ->
                scope.launch {
                    val archive = !s.archived
                    SessionManager.setFlags(activity, s.id, archived = archive)
                    if (archive && current?.id == s.id) {
                        SessionManager.newSession("chat")
                        resetComposer()
                    }
                }
            },
            onExport = { exportSession(it) },
            onSettings = {
                showMenu = false
                showSettings = true
            },
            onDismiss = { showMenu = false }
        )
    }

    if (showSettings) {
        SettingsSheet(
            sessions = sessions,
            baseUrl = baseUrl,
            onBaseUrl = {
                baseUrl = it
                fetchedModels = emptyList()
            },
            apiKey = apiKey,
            onApiKey = {
                apiKey = it
                fetchedModels = emptyList()
            },
            model = model,
            onModel = { model = it },
            models = fetchedModels,
            modelsLoading = fetchingModels,
            rootGranted = rootGranted,
            overlayEnabled = overlayEnabled,
            overlayGranted = overlayGranted,
            onOverlayEnabled = { enabled ->
                if (enabled && !Settings.canDrawOverlays(activity)) {
                    requestOverlayPermission()
                } else {
                    overlayEnabled = enabled
                    DisplaySettings.setOverlayEnabled(activity, enabled)
                    AgentStatusService.refreshOverlay(activity)
                }
            },
            onRequestOverlay = { requestOverlayPermission() },
            testResult = testResult,
            onSaveModel = {
                val p = currentProvider()
                LlmSettings.upsert(activity, p)
                LlmSettings.setActiveId(activity, "main")
                testResult = "已保存 · ${p.model}"
            },
            onTestModel = {
                if (!fetchingModels) {
                    val p = currentProvider()
                    LlmSettings.upsert(activity, p)
                    LlmSettings.setActiveId(activity, "main")
                    fetchingModels = true
                    testResult = "正在拉取模型列表…"
                    scope.launch {
                        val (models, err) = LlmClient.listModels(p)
                        withContext(Dispatchers.Main) {
                            fetchingModels = false
                            if (err.isNotEmpty()) {
                                fetchedModels = emptyList()
                                testResult = "连接失败: $err"
                            } else {
                                fetchedModels = models
                                testResult = "连接成功 · ${models.size} 个模型，点击下方模型选择"
                            }
                        }
                    }
                }
            },
            consoleEnabled = consoleEnabled,
            onConsoleEnabled = { enabled ->
                consoleEnabled = enabled
                saveConsole(enabled = enabled)
                if (enabled) {
                    if (consoleDeviceId.isBlank()) {
                        consoleStatus = "请先填写一次性注册码完成绑定"
                    } else {
                        consoleStatus = "正在连接…"
                        RemoteConsoleService.ensureStarted(activity)
                    }
                } else {
                    consoleStatus = "控制台连接已停用"
                    RemoteConsoleService.stop(activity)
                }
            },
            consoleBase = consoleBase,
            onConsoleBase = { consoleBase = it },
            consoleCode = consoleCode,
            onConsoleCode = { consoleCode = it },
            consoleName = consoleName,
            onConsoleName = { consoleName = it },
            consoleDeviceId = consoleDeviceId,
            consoleStatus = consoleStatus,
            consoleConnected = consoleConnected,
            onBindConsole = { bindConsole() },
            onScanConsole = { showConsoleScanner = true },
            onSaveConsole = {
                saveConsole()
                consoleStatus = "配置已保存"
            },
            onTestConsole = {
                scope.launch {
                    consoleStatus = "正在测试连接…"
                    val ok = ConsoleClient.heartbeat(activity)
                    withContext(Dispatchers.Main) {
                        consoleStatus = if (ok) {
                            "连接正常"
                        } else {
                            ConsoleClient.status.value.ifBlank { "连接失败" }
                        }
                    }
                }
            },
            onUnbindConsole = {
                RemoteConsoleService.stop(activity)
                ConsoleSettings.clear(activity)
                consoleEnabled = false
                consoleDeviceId = ""
                consoleCode = ""
                consoleStatus = "已解除绑定"
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showConsoleScanner) {
        QrScannerDialog(
            onResult = { raw ->
                showConsoleScanner = false
                val payload = EnrollQr.parse(raw)
                if (payload == null) {
                    consoleStatus = "二维码无效，请重新生成"
                } else {
                    consoleBase = payload.baseUrl
                    consoleCode = payload.code
                    if (!payload.name.isNullOrBlank()) consoleName = payload.name
                    bindConsole(payload.baseUrl, payload.code, payload.name ?: consoleName)
                }
            },
            onDismiss = { showConsoleScanner = false }
        )
    }

    if (showModelSheet) {
        current?.let { s ->
            ModelSheet(
                currentModel = s.model,
                globalModel = model,
                models = fetchedModels,
                onSelect = { selected ->
                    scope.launch { SessionManager.setModel(activity, s.id, selected) }
                    showModelSheet = false
                },
                onDismiss = { showModelSheet = false }
            )
        } ?: run { showModelSheet = false }
    }

    pendingDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除会话？") },
            text = {
                Text("将删除「${s.title}」及其消息、步骤和附件记录，不可恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        val deletingCurrent = current?.id == s.id
                        if (AgentController.isSessionRunning(s.id)) {
                            AgentController.stop(s.id, "删除会话")
                        }
                        scope.launch {
                            SessionManager.delete(activity, s.id)
                            if (deletingCurrent) {
                                resetComposer()
                                showMenu = false
                            }
                        }
                    }
                ) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    actionMessage?.let { msg ->
        MessageActionSheet(
            message = msg,
            onCopy = {
                clipboard.setText(AnnotatedString(msg.content))
                actionMessage = null
            },
            onQuote = {
                quotedText = msg.content.take(400)
                actionMessage = null
            },
            onEdit = {
                if (msg.role == "user") {
                    editingMessage = msg
                    editText = msg.content
                }
                actionMessage = null
            },
            onRegenerate = { regenerateFrom(msg) },
            onBranch = { branchFrom(msg) },
            onDismiss = { actionMessage = null }
        )
    }

    editingMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text("编辑消息") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    minLines = 3,
                    maxLines = 8,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newText = editText.trim()
                        val session = current
                        if (agent.running) {
                            hint = "当前还有任务在运行，先停止后再编辑"
                            editingMessage = null
                        } else if (newText.isNotEmpty() && session != null) {
                            scope.launch {
                                SessionManager.replaceMessage(activity, session.id, msg.id) {
                                    it.copy(content = newText)
                                }
                                val updated = SessionManager.truncateAfter(activity, session.id, msg.id)
                                    ?: return@launch
                                val edited = updated.messages.firstOrNull { it.id == msg.id }
                                    ?: return@launch
                                val prior = buildPriorContext(
                                    updated.copy(messages = updated.messages.dropLast(1))
                                )
                                startTurn(updated, prior, goalWithContext(edited), emptyList())
                                editingMessage = null
                            }
                        }
                    },
                    enabled = editText.isNotBlank()
                ) { Text("保存并重新生成") }
            },
            dismissButton = {
                TextButton(onClick = { editingMessage = null }) { Text("取消") }
            }
        )
    }

    pendingRename?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(60) },
                    singleLine = true,
                    label = { Text("标题") },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val title = renameText.trim()
                        if (title.isNotEmpty()) {
                            scope.launch {
                                SessionManager.rename(activity, s.id, title)
                            }
                        }
                        pendingRename = null
                    },
                    enabled = renameText.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text("取消") }
            }
        )
    }

    if (previewImages.isNotEmpty()) {
        ImagePreviewOverlay(
            images = previewImages,
            initialIndex = previewIndex,
            onClose = { previewImages = emptyList() }
        )
    }

    val exitRunning = current?.let { AgentController.isSessionRunning(it.id) } == true
    val exitOverlayOpen = showMenu || showSettings || showModelSheet ||
        showExitConfirm || previewImages.isNotEmpty() ||
        pendingDelete != null || pendingRename != null ||
        actionMessage != null || editingMessage != null

    // 主界面返回键/手势先确认, 避免误触直接退出; 弹层打开时交给弹层自己处理
    BackHandler(enabled = !exitOverlayOpen) {
        showExitConfirm = true
    }

    if (showExitConfirm) {
        val tint = if (exitRunning) Warning else Danger
        Dialog(
            onDismissRequest = { showExitConfirm = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 28.dp)
                    .widthIn(max = 360.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = Color.White,
                border = BorderStroke(1.dp, Line.copy(alpha = 0.85f)),
                shadowElevation = 18.dp
            ) {
                Column(Modifier.padding(24.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(tint.copy(alpha = 0.10f), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Logout,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = if (exitRunning) "任务还在运行" else "退出 CatfeeAgents",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Ink
                            )
                            Text(
                                text = if (exitRunning) "Agent 正在操作手机" else "Chat & phone agent",
                                fontSize = 10.5.sp,
                                color = Muted,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = if (exitRunning) {
                            "当前会话的 Agent 还在操作手机。退出会停止该任务，确定退出吗？"
                        } else {
                            "当前会话会保留，下次打开可以继续。确定退出吗？"
                        },
                        fontSize = 13.sp,
                        color = Muted,
                        lineHeight = 20.sp
                    )

                    if (exitRunning) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Warning.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, Warning.copy(alpha = 0.18f))
                        ) {
                            Text(
                                text = "退出会停止当前会话任务",
                                fontSize = 10.5.sp,
                                color = Warning,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showExitConfirm = false },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Line),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                            contentPadding = PaddingValues(horizontal = 18.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Text("取消", fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = {
                                showExitConfirm = false
                                if (exitRunning) AgentController.stop(current?.id.orEmpty(), "退出应用")
                                SessionManager.flush(activity)
                                activity.finish()
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tint,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Text("退出", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 会话上下文：不再用“继续”关键词判断，而是每次都给一份紧凑检查点；
 * 由模型结合当前用户消息做语义判断：新问题就处理新问题，追问/补充才沿用旧任务。
 */
private fun buildPriorContext(session: Session): String {
    val sb = StringBuilder()
    if (session.steps.isNotEmpty()) {
        sb.append("===== 会话上下文 =====\n")
        sb.append("说明: 以下来自同一会话之前, 只作参考, 不是本轮指令; 当前用户消息优先。\n")
        sb.append(
            "判断规则: 当前消息是新问题/新任务时, 直接处理新消息, 不要执行旧任务; " +
                "当前消息是在追问、补充、省略主语的“继续”时, 才沿用下面的目标/清单/进度。\n"
        )
        if (session.running) {
            sb.append("上次状态: 任务仍在运行或未正常结束\n")
        } else if (session.result.isBlank()) {
            sb.append("上次状态: 没有明确结束结果, 可能被停止/超时\n")
        } else {
            sb.append("上次状态: 已结束\n")
        }
        val originalGoal = session.messages
            .firstOrNull { it.role == "user" && it.content.isNotBlank() }
            ?.content
            ?.replace('\n', ' ')
            ?.take(800)
        if (!originalGoal.isNullOrBlank()) {
            sb.append("会话最初目标: ").append(originalGoal).append('\n')
        }
        val lastTodo = session.steps.lastOrNull {
            it.type == "todo" || it.tool == "todo_write"
        }?.text
        if (!lastTodo.isNullOrBlank()) {
            sb.append("上次任务清单:\n").append(lastTodo.take(1_000)).append('\n')
        }
        if (session.result.isNotBlank()) {
            sb.append("上次结果: ").append(session.result.take(450)).append('\n')
        }
        val recent = session.steps
            .filter { it.text.isNotBlank() && it.type in setOf("result", "system", "action", "error", "todo") }
            .takeLast(4)
        if (recent.isNotEmpty()) {
            sb.append("最近执行:\n")
            recent.forEach { s ->
                sb.append("- [").append(s.type).append("] ")
                    .append(s.text.replace('\n', ' ').take(200)).append('\n')
            }
        }
    }
    // 对话尾部始终带最近 4 条, 保证短问答/新问题的上下文连续
    session.messages.takeLast(4).forEach { m ->
        sb.append(if (m.role == "user") "用户: " else "助手: ")
        sb.append(m.content.replace('\n', ' ').take(300)).append('\n')
        if (m.context.isNotBlank()) {
            sb.append("[该消息附件的解析摘要] ")
                .append(m.context.replace('\n', ' ').take(400)).append('\n')
        }
    }
    if (session.steps.isEmpty() && session.result.isNotBlank()) {
        sb.append("最近一次结果: ").append(session.result.take(500)).append('\n')
    }
    return sb.toString().take(4_000)
}

/* ---------------- Header / session line ---------------- */

@Composable
private fun Header(running: Boolean, onMenu: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Line, RoundedCornerShape(12.dp))
        ) {
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = "CatfeeAgents",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                            color = Ink
                        )
                    ) { append("Catfee") }
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                            color = Accent
                        )
                    ) { append("Agents") }
                },
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    val transition = rememberInfiniteTransition(label = "run")
                    val a by transition.animateFloat(
                        0.3f, 1f,
                        infiniteRepeatable(tween(850), RepeatMode.Reverse),
                        label = "dot"
                    )
                    Box(Modifier.size(6.dp).alpha(a).background(Accent, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text("Working on this device", fontSize = 10.sp, color = Muted)
                } else {
                    Text("Chat & phone agent", fontSize = 10.sp, color = Muted)
                }
            }
        }
        IconButton(
            onClick = onMenu,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0xFFF1F3F5))
        ) {
            Icon(Icons.Rounded.Menu, contentDescription = "菜单", tint = Ink, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun SessionLine(session: Session) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            session.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!session.isEmpty) {
            Text(
                "${formatCount(session.totalTokens)} tok · ${formatCount(session.contextChars)} ctx" +
                    if (session.totalDurationMs > 0) " · ${formatDuration(session.totalDurationMs)}" else "",
                fontSize = 9.5.sp,
                color = Color(0xFF9CA3AF)
            )
        }
    }
}

/* ---------------- Chat bubbles ---------------- */

@Composable
private fun UserBubble(
    msg: ChatMessage,
    onImageClick: (Attachment) -> Unit,
    onFileClick: (Attachment) -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(msg.id) { detectTapGestures(onLongPress = { onLongPress() }) },
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .widthIn(max = 520.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (msg.attachments.isNotEmpty()) {
                AttachmentStrip(msg.attachments, onImageClick, onFileClick)
                Spacer(Modifier.height(6.dp))
            }
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp),
                color = Ink
            ) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                    if (msg.quotedText.isNotBlank()) {
                        Text(
                            "引用: ${msg.quotedText.replace('\n', ' ').take(160)}",
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = Color.White.copy(alpha = 0.62f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Text(
                        msg.content,
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    msg: ChatMessage,
    onLongPress: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(msg.id) { detectTapGestures(onLongPress = { onLongPress() }) },
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 560.dp),
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Line)
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
                if (msg.reasoning.isNotBlank()) {
                    ReasoningPanel(msg.reasoning, defaultExpanded = false)
                    Spacer(Modifier.height(8.dp))
                }
                MarkdownContent(msg.content)
                if (msg.retryAnchorId.isNotBlank() && onRetry != null) {
                    TextButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("重试进入手机模式", fontSize = 10.5.sp, color = Accent)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.time)),
                        fontSize = 8.5.sp,
                        color = Color(0xFF9CA3AF)
                    )
                    if (msg.elapsedMs > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            formatDuration(msg.elapsedMs),
                            fontSize = 8.5.sp,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                    if (msg.tokens > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text("${formatCount(msg.tokens)} tok", fontSize = 8.5.sp, color = Color(0xFF9CA3AF))
                    }
                    Spacer(Modifier.weight(1f))
                    CopyIcon(msg.content, tint = Color(0xFF9CA3AF))
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(reasoning: String, content: String, tool: String) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by transition.animateFloat(
        0.15f, 1f,
        infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "cursorAlpha"
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 580.dp),
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Line)
        ) {
            Column {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
                    if (reasoning.isNotBlank()) {
                        ReasoningPanel(reasoning, defaultExpanded = true)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (content.isBlank()) {
                        TypingRow(if (tool.isBlank()) "Thinking" else "Working · $tool")
                    } else {
                        MarkdownContent(content)
                        Box(
                            Modifier
                                .padding(top = 3.dp)
                                .size(width = 2.dp, height = 13.dp)
                                .alpha(cursorAlpha)
                                .background(Accent)
                        )
                    }
                }
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(1.5.dp),
                    color = Accent,
                    trackColor = Accent.copy(alpha = 0.06f)
                )
            }
        }
    }
}

@Composable
private fun TypingRow(label: String) {
    val transition = rememberInfiniteTransition(label = "typing")
    val a1 by transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(620), RepeatMode.Reverse), label = "t1")
    val a2 by transition.animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(620), RepeatMode.Reverse, StartOffset(140)), label = "t2"
    )
    val a3 by transition.animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(620), RepeatMode.Reverse, StartOffset(280)), label = "t3"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf(a1, a2, a3).forEach { a ->
            Box(Modifier.size(6.dp).alpha(a).background(Muted, CircleShape))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, fontSize = 10.sp, color = Muted)
    }
}

@Composable
private fun ReasoningPanel(reasoning: String, defaultExpanded: Boolean) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "rArrow")
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Psychology, null, tint = Color(0xFF9CA3AF), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text("Thinking", fontSize = 10.sp, color = Color(0xFF9CA3AF))
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Rounded.ExpandMore, null, tint = Color(0xFF9CA3AF),
                modifier = Modifier.size(14.dp).rotate(arrow)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160))
        ) {
            Text(
                reasoning.take(6000),
                modifier = Modifier
                    .padding(top = 5.dp)
                    .heightIn(max = 190.dp)
                    .verticalScroll(rememberScrollState()),
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = Color(0xFF6B7280)
            )
        }
    }
}

@Composable
private fun CopyIcon(text: String, tint: Color) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        modifier = Modifier.size(24.dp)
    ) {
        Icon(
            if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = "复制",
            tint = if (copied) Success else tint,
            modifier = Modifier.size(13.dp)
        )
    }
}

/* ---------------- Markdown ---------------- */

private sealed interface MdBlock {
    data class TextBlock(val text: String) : MdBlock
    data class CodeBlock(val lang: String, val code: String) : MdBlock
}

private fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.split('\n')
    val out = ArrayList<MdBlock>()
    val buf = StringBuilder()
    var i = 0
    fun flush() {
        if (buf.isNotEmpty()) {
            out.add(MdBlock.TextBlock(buf.toString().trimEnd()))
            buf.setLength(0)
        }
    }
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            flush()
            val lang = line.trim().removePrefix("```").trim()
            i++
            val code = StringBuilder()
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                code.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) i++
            out.add(MdBlock.CodeBlock(lang, code.toString().trimEnd()))
        } else {
            buf.append(line).append('\n')
            i++
        }
    }
    flush()
    return out
}

@Composable
private fun MarkdownContent(content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parseMarkdown(content).forEach { block ->
            when (block) {
                is MdBlock.CodeBlock -> CodeBlock(block.lang, block.code)
                is MdBlock.TextBlock -> MarkdownText(block.text)
            }
        }
    }
}

@Composable
private fun MarkdownText(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        text.split('\n').forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(2.dp))
                line.startsWith("### ") -> Text(
                    markdownLite(line.removePrefix("### ")),
                    fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ink
                )
                line.startsWith("## ") -> Text(
                    markdownLite(line.removePrefix("## ")),
                    fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Ink
                )
                line.startsWith("# ") -> Text(
                    markdownLite(line.removePrefix("# ")),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink
                )
                line.startsWith("- ") || line.startsWith("* ") -> Row(verticalAlignment = Alignment.Top) {
                    Text("•", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(end = 5.dp))
                    Text(markdownLite(line.drop(2)), fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFF1F2937))
                }
                else -> Text(
                    markdownLite(line),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Color(0xFF1F2937)
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = Color(0xFF0F172A)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    lang.ifBlank { "code" },
                    fontSize = 9.sp,
                    color = Color(0xFF94A3B8),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.weight(1f))
                CopyIcon(code, tint = Color(0xFF94A3B8))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 10.dp, bottom = 9.dp)
            ) {
                Text(
                    code,
                    fontSize = 10.5.sp,
                    lineHeight = 16.sp,
                    color = Color(0xFFE2E8F0),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/* ---------------- Approval ---------------- */

@Composable
private fun ApprovalCard(
    pending: AgentController.PendingConfirm,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    val caps by DeviceCapabilities.state.collectAsState()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFFFFBEB),
        border = BorderStroke(1.dp, Color(0xFFFDE68A))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Warning.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Lock, null, tint = Warning, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (caps.root) "允许控制手机？" else "允许使用无障碍操作手机？",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF92400E)
                    )
                    Text(
                        "Agent 想${permissionAction(pending.tool)}。" +
                            if (caps.root) {
                                "允许后本会话不再询问。"
                            } else {
                                "将通过无障碍读屏和手势执行；截图/OCR 受系统限制。允许后本会话不再询问。"
                            },
                        fontSize = 10.5.sp,
                        color = Color(0xFF92400E).copy(alpha = 0.8f)
                    )
                }
            }
            if (pending.arguments.isNotBlank() && pending.arguments != "{}") {
                Text(
                    pending.arguments.take(160),
                    modifier = Modifier.padding(top = 7.dp),
                    fontSize = 9.5.sp,
                    color = Color(0xFFB45309),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAllow,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink)
                ) { Text("允许", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFFCD34D))
                ) { Text("拒绝", fontSize = 12.sp, color = Color(0xFF92400E)) }
            }
        }
    }
}

private fun permissionAction(tool: String): String = when {
    tool == "enter_agent_mode" -> "进入手机操作模式"
    tool == "observe" || tool == "read_page" || tool == "ocr" ||
        tool == "long_ocr" || tool == "screenshot" || tool == "see_screen" ||
        tool == "get_activity" || tool == "find_text" || tool == "list_options" ->
        "读取当前屏幕内容"
    tool.startsWith("tap") || tool == "double_tap" || tool == "long_press" ||
        tool == "swipe" || tool == "swipe_direction" -> "在屏幕上点击/滑动"
    tool == "key" || tool.contains("input") || tool.contains("text") -> "输入文字或按键"
    tool == "launch_app" || tool == "launch_activity" || tool == "open_url" -> "打开应用"
    tool == "answer_form" -> "填写并提交页面表单"
    else -> "执行设备操作"
}

/* ---------------- Activity ---------------- */

@Composable
private fun ActivitySection(
    steps: List<AgentController.Step>,
    running: Boolean,
    currentTool: String
) {
    var expanded by remember { mutableStateOf(false) }
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "aArrow")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.animateContentSize(tween(200))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0xFFF1F3F5)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Terminal, null, tint = Muted, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Activity · ${steps.size} steps",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Ink
                    )
                    if (running) {
                        Text(
                            if (currentTool.isBlank()) "Working…" else "Working · $currentTool",
                            fontSize = 9.sp,
                            color = Muted
                        )
                    }
                }
                Icon(
                    Icons.Rounded.ExpandMore, null, tint = Color(0xFF9CA3AF),
                    modifier = Modifier.size(17.dp).rotate(arrow)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    steps.forEach { StepCard(it) }
                }
            }
        }
    }
}

@Composable
private fun StepCard(step: AgentController.Step) {
    var expanded by remember { mutableStateOf(false) }
    val accent = typeColor(step.type)
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "sArrow")
    val time = remember(step.time) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(step.time))
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(tween(180))
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = Bg,
        border = BorderStroke(1.dp, Line.copy(alpha = 0.7f))
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(typeLabel(step.type), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = accent)
                if (step.tool.isNotBlank()) {
                    Spacer(Modifier.width(5.dp))
                    Surface(shape = RoundedCornerShape(4.dp), color = Color.White) {
                        Text(
                            step.tool,
                            Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            fontSize = 8.5.sp, color = Muted, fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(time, fontSize = 8.5.sp, color = Color(0xFF9CA3AF))
                Icon(
                    Icons.Rounded.ExpandMore, null, tint = Color(0xFF9CA3AF),
                    modifier = Modifier.size(14.dp).rotate(arrow)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                step.text,
                fontSize = 10.5.sp,
                lineHeight = 16.sp,
                color = Color(0xFF4B5563),
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                fontFamily = if (step.type == "tool" || step.type == "result") {
                    FontFamily.Monospace
                } else FontFamily.Default
            )
        }
    }
}

/* ---------------- Empty ---------------- */

@Composable
private fun EmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Line)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(70.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, Line, RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.height(14.dp))
            Text("开始对话", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "可以聊天、分析图片/文件；需要时 Agent 会请求一次授权，然后自己操作手机。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp
            )
        }
    }
}

/* ---------------- Composer ---------------- */

@Composable
private fun Composer(
    task: String,
    onTaskChange: (String) -> Unit,
    model: String,
    onModelClick: () -> Unit,
    quotedText: String,
    onClearQuote: () -> Unit,
    attachments: List<Attachment>,
    onRemoveAttachment: (String) -> Unit,
    onPickAttachments: () -> Unit,
    running: Boolean,
    paused: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    hint: String,
    onFocused: () -> Unit = {}
) {
    val focusSource = remember { MutableInteractionSource() }
    val focused by focusSource.collectIsFocusedAsState()
    val borderAmount by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "focus")
    Surface(modifier = Modifier.fillMaxWidth(), color = Bg) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 5.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = Color.White,
                border = BorderStroke(1.dp, lerp(Color(0xFFECEEF2), Accent.copy(alpha = 0.42f), borderAmount))
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    if (quotedText.isNotBlank()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF3F4F6)
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "引用: ${quotedText.replace('\n', ' ')}",
                                    fontSize = 10.sp,
                                    color = Muted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = onClearQuote, modifier = Modifier.size(26.dp)) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        "取消引用",
                                        tint = Muted,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = attachments.isNotEmpty(),
                        enter = fadeIn(tween(160)) + expandVertically(tween(180)),
                        exit = fadeOut(tween(120)) + shrinkVertically(tween(160))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(start = 2.dp, end = 2.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            attachments.forEach { a ->
                                PendingAttachment(a) { onRemoveAttachment(a.id) }
                            }
                        }
                    }
                    // 多行输入时 + / 发送按钮贴顶, 不随文本高度一起居中
                    Row(verticalAlignment = Alignment.Top) {
                        AttachmentButton(onPickAttachments)
                        Spacer(Modifier.width(2.dp))
                        BasicTextField(
                            value = task,
                            onValueChange = onTaskChange,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp, max = 120.dp)
                                .onFocusChanged { if (it.isFocused) onFocused() },
                            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = Ink),
                            cursorBrush = SolidColor(Accent),
                            maxLines = 4,
                            interactionSource = focusSource,
                            decorationBox = { inner ->
                                Box(Modifier.padding(vertical = 9.dp)) {
                                    if (task.isEmpty()) {
                                        Text(
                                            "发消息或描述任务…",
                                            fontSize = 15.sp,
                                            color = Color(0xFF9AA0A6),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        Spacer(Modifier.width(4.dp))
                        if (running) {
                            PauseResumeButton(paused, onPauseResume)
                            Spacer(Modifier.width(4.dp))
                            StopButton(onStop)
                        } else {
                            RunButton(canSend, onSend)
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onModelClick)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(5.dp).background(Success, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    model.ifBlank { "未配置模型" },
                    fontSize = 9.5.sp,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("切换", fontSize = 9.sp, color = Color(0xFF9CA3AF))
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = "切换本会话模型",
                    tint = Color(0xFF9CA3AF),
                    modifier = Modifier.size(13.dp)
                )
            }
            if (hint.isNotBlank()) {
                Text(
                    hint,
                    fontSize = 10.5.sp,
                    color = Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp, top = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachmentButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg by animateFloatAsState(if (pressed) 1f else 0f, tween(110), label = "attachBg")
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(lerp(Color(0xFFF3F4F6), Color(0xFFE8EAED), bg))
            .clickable(
                interactionSource = source,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.Add,
            contentDescription = "添加图片或文件",
            tint = Ink,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun RunButton(enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(90), label = "scale")
    Box(
        modifier = Modifier
            .size(36.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (enabled) Ink else Color(0xFFF1F3F4))
            .clickable(
                enabled = enabled,
                interactionSource = source,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.ArrowUpward,
            contentDescription = "发送",
            tint = if (enabled) Color.White else Color(0xFFB6BAC0),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun PauseResumeButton(paused: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(90), label = "pauseScale")
    Box(
        modifier = Modifier
            .size(36.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (paused) Violet else Color(0xFF374151))
            .clickable(
                interactionSource = source,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
            contentDescription = if (paused) "继续" else "暂停",
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Ink)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.Stop,
            contentDescription = "停止",
            tint = Color.White,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun PendingAttachment(a: Attachment, onRemove: () -> Unit) {
    Box {
        if (a.kind == "image") {
            AttachmentThumb(a, 58.dp)
        } else {
            FileChip(a)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(1.dp, Line, CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Close, "移除附件", tint = Ink, modifier = Modifier.size(11.dp))
        }
    }
}

@Composable
private fun AttachmentStrip(
    attachments: List<Attachment>,
    onImageClick: (Attachment) -> Unit,
    onFileClick: (Attachment) -> Unit
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        attachments.forEach { a ->
            if (a.kind == "image") {
                AttachmentThumb(a, 92.dp) { onImageClick(a) }
            } else {
                FileChip(a) { onFileClick(a) }
            }
        }
    }
}

@Composable
private fun AttachmentThumb(
    a: Attachment,
    size: androidx.compose.ui.unit.Dp,
    onClick: (() -> Unit)? = null
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, a.path) {
        value = withContext(Dispatchers.IO) {
            decodeSampledBitmap(a.path, 360)?.asImageBitmap()
        }
    }
    Surface(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFF1F3F5),
        border = BorderStroke(1.dp, Color(0xFFE8EAED))
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = a.name,
                modifier = Modifier.size(size).clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Description, null, tint = Muted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun FileChip(a: Attachment, onClick: (() -> Unit)? = null) {
    Surface(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFF8F9FB),
        border = BorderStroke(1.dp, Color(0xFFF0F1F4))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Accent.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Description, null, tint = Accent, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(7.dp))
            Column {
                Text(
                    a.name,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 118.dp)
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    formatSize(a.size),
                    fontSize = 8.5.sp,
                    color = Muted
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun openAttachment(context: android.content.Context, a: Attachment) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(a.path)
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, a.mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开该文件: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ImagePreviewOverlay(
    images: List<Attachment>,
    initialIndex: Int,
    onClose: () -> Unit
) {
    if (images.isEmpty()) return
    BackHandler(onBack = onClose)
    var shown by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, images.lastIndex)
    ) { images.size }
    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(pagerState.currentPage) { zoomed = false }

    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.96f, animationSpec = tween(200)),
        exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.97f, animationSpec = tween(160))
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xF20B0F1A))) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !zoomed
            ) { page ->
                ZoomableImage(
                    a = images[page],
                    modifier = Modifier.fillMaxSize()
                ) { zoomed = it }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${pagerState.currentPage + 1} / ${images.size}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Icon(Icons.Rounded.Close, "关闭预览", tint = Color.White, modifier = Modifier.size(19.dp))
                }
            }
            Text(
                "双指缩放/旋转 · 双击还原 · 左右滑动切换",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    a: Attachment,
    modifier: Modifier = Modifier,
    onZoomChanged: (Boolean) -> Unit = {}
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, a.path) {
        value = withContext(Dispatchers.IO) {
            decodeSampledBitmap(a.path, 2400)?.asImageBitmap()
        }
    }
    var scale by remember(a.path) { mutableFloatStateOf(1f) }
    var rotation by remember(a.path) { mutableFloatStateOf(0f) }
    var offset by remember(a.path) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, rotationChange ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        rotation += rotationChange
        if (scale > 1.01f) offset += panChange
        onZoomChanged(scale > 1.01f)
    }
    Box(
        modifier = modifier
            .transformable(transform)
            .pointerInput(a.path) {
                detectTapGestures(onDoubleTap = {
                    scale = 1f
                    rotation = 0f
                    offset = Offset.Zero
                    onZoomChanged(false)
                })
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = a.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        rotationZ = rotation,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                contentScale = ContentScale.Fit
            )
        } else {
            Text("加载中…", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
    }
}

private fun decodeSampledBitmap(path: String, maxSide: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/* ---------------- Menu ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuSheet(
    sessions: List<SessionSummary>,
    currentId: String,
    runningSessionIds: Set<String>,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (SessionSummary) -> Unit,
    onRename: (SessionSummary) -> Unit,
    onPin: (SessionSummary) -> Unit,
    onFavorite: (SessionSummary) -> Unit,
    onArchive: (SessionSummary) -> Unit,
    onExport: (SessionSummary) -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }

    val visible = remember(sessions, query, filter) {
        val q = query.trim()
        sessions.filter { s ->
            val inTab = when (filter) {
                0 -> !s.archived
                1 -> s.pinned && !s.archived
                2 -> s.favorite && !s.archived
                else -> s.archived
            }
            inTab && (q.isEmpty() ||
                s.title.contains(q, ignoreCase = true) ||
                s.summary.contains(q, ignoreCase = true) ||
                s.resultPreview.contains(q, ignoreCase = true))
        }.sortedWith(
            compareByDescending<SessionSummary> { it.pinned }
                .thenByDescending { it.favorite }
                .thenByDescending { it.updatedAt }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            // 会话列表很长时, 向下拖到顶再向上拖容易把整个菜单甩掉;
            // 禁止手势隐藏, 关闭仍走右上角 X / 返回键。
            confirmValueChange = { it != SheetValue.Hidden }
        ),
        containerColor = Color.White,
        sheetMaxWidth = 640.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Spacer(Modifier.height(10.dp)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CatfeeAgents",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    modifier = Modifier.weight(1f)
                )
                // 设置固定在顶部, 会话再长也不用滚到底部找
                TextButton(
                    onClick = onSettings,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(Icons.Rounded.Settings, null, tint = Ink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("设置", fontSize = 11.sp, color = Ink)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, "关闭", tint = Muted)
                }
            }
            Text("一个对话界面，聊天或让 Agent 操作手机。", fontSize = 11.sp, color = Muted)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onNew,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink)
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("新建会话", fontSize = 12.sp)
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Line)
            Text("历史会话", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Muted)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索标题、摘要或结果…", fontSize = 11.sp, color = Color(0xFF9CA3AF)) },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, null, tint = Muted, modifier = Modifier.size(17.dp))
                },
                shape = RoundedCornerShape(14.dp),
                textStyle = TextStyle(fontSize = 12.sp, color = Ink),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ink,
                    unfocusedBorderColor = Line,
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    cursorColor = Ink
                )
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFF3F4F6))
                    .padding(3.dp)
            ) {
                listOf("最近", "置顶", "收藏", "归档").forEachIndexed { idx, label ->
                    SettingsTab(label, filter == idx, Modifier.weight(1f)) { filter = idx }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (visible.isEmpty()) {
                Text(
                    when {
                        query.isNotBlank() -> "没有匹配的会话。"
                        filter == 3 -> "没有已归档的会话。"
                        filter == 1 -> "还没有置顶会话。"
                        filter == 2 -> "还没有收藏会话。"
                        else -> "还没有历史会话，发第一条消息后会自动保存。"
                    },
                    fontSize = 10.5.sp,
                    color = Muted
                )
            }
            visible.forEach { s ->
                SessionRow(
                    summary = s,
                    selected = s.id == currentId,
                    running = s.id in runningSessionIds,
                    onClick = { onOpen(s.id) },
                    onPin = { onPin(s) },
                    onFavorite = { onFavorite(s) },
                    onArchive = { onArchive(s) },
                    onRename = { onRename(s) },
                    onExport = { onExport(s) },
                    onDelete = { onDelete(s) }
                )
                Spacer(Modifier.height(7.dp))
            }
        }
    }
}

@Composable
private fun SessionRow(
    summary: SessionSummary,
    selected: Boolean,
    running: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onFavorite: () -> Unit,
    onArchive: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val time = remember(summary.updatedAt) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(summary.updatedAt))
    }
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        color = if (selected) Accent.copy(alpha = 0.04f) else Color.White,
        border = BorderStroke(1.dp, if (selected) Accent.copy(alpha = 0.3f) else Line)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Accent.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.ChatBubbleOutline, null, tint = Accent, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (summary.pinned) {
                        Icon(Icons.Rounded.PushPin, "已置顶", tint = Accent, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    if (summary.favorite) {
                        Icon(Icons.Rounded.Star, "已收藏", tint = Warning, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    if (summary.archived) {
                        Icon(Icons.Rounded.Archive, "已归档", tint = Muted, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        summary.title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (running) {
                        Box(Modifier.size(6.dp).background(Success, CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text("running", fontSize = 8.sp, color = Success)
                    }
                }
                if (summary.summary.isNotBlank()) {
                    Text(
                        summary.summary,
                        fontSize = 9.5.sp,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                Text(
                    "$time · ${formatCount(summary.totalTokens)} tok · " +
                        "${formatCount(summary.contextChars)} ctx" +
                        if (summary.totalDurationMs > 0) {
                            " · ${formatDuration(summary.totalDurationMs)}"
                        } else {
                            ""
                        } + " · ${summary.messageCount} msgs",
                    fontSize = 9.sp,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "会话操作",
                        tint = Color(0xFF9CA3AF),
                        modifier = Modifier.size(17.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (summary.pinned) "取消置顶" else "置顶", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Rounded.PushPin, null, modifier = Modifier.size(16.dp)) },
                        onClick = { menuOpen = false; onPin() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (summary.favorite) "取消收藏" else "收藏", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Star, null, modifier = Modifier.size(16.dp)) },
                        onClick = { menuOpen = false; onFavorite() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (summary.archived) "恢复" else "归档", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Archive, null, modifier = Modifier.size(16.dp)) },
                        onClick = { menuOpen = false; onArchive() }
                    )
                    DropdownMenuItem(
                        text = { Text("重命名", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(16.dp)) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("导出 Markdown", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Share, null, modifier = Modifier.size(16.dp)) },
                        onClick = { menuOpen = false; onExport() }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", fontSize = 12.sp, color = Danger) },
                        leadingIcon = {
                            Icon(Icons.Rounded.DeleteOutline, null, tint = Danger, modifier = Modifier.size(16.dp))
                        },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

/* ---------------- Settings ---------------- */

private data class AppAboutInfo(
    val versionName: String,
    val versionCode: String,
    val applicationId: String
)

private fun loadAppAboutInfo(context: android.content.Context): AppAboutInfo {
    val applicationId = context.packageName
    return try {
        val packageInfo: PackageInfo = context.packageManager.getPackageInfo(applicationId, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        AppAboutInfo(
            versionName = packageInfo.versionName.orEmpty().ifBlank { "未知" },
            versionCode = versionCode.toString(),
            applicationId = packageInfo.packageName.ifBlank { applicationId }
        )
    } catch (_: PackageManager.NameNotFoundException) {
        AppAboutInfo("未知", "未知", applicationId)
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.5.sp, color = Muted, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = 10.5.sp,
            color = Ink,
            fontFamily = if (label == "Application ID") FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AboutCapabilityRow(label: String, description: String, enabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(if (enabled) Success else Color(0xFFD1D5DB), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = Ink)
            Text(description, fontSize = 9.5.sp, color = Muted, lineHeight = 14.sp)
        }
        Text(
            if (enabled) "已就绪" else "未开启",
            fontSize = 9.5.sp,
            color = if (enabled) Success else Muted
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    sessions: List<SessionSummary>,
    baseUrl: String,
    onBaseUrl: (String) -> Unit,
    apiKey: String,
    onApiKey: (String) -> Unit,
    model: String,
    onModel: (String) -> Unit,
    models: List<String>,
    modelsLoading: Boolean,
    rootGranted: Boolean?,
    overlayEnabled: Boolean,
    overlayGranted: Boolean,
    onOverlayEnabled: (Boolean) -> Unit,
    onRequestOverlay: () -> Unit,
    testResult: String,
    onSaveModel: () -> Unit,
    onTestModel: () -> Unit,
    consoleEnabled: Boolean,
    onConsoleEnabled: (Boolean) -> Unit,
    consoleBase: String,
    onConsoleBase: (String) -> Unit,
    consoleCode: String,
    onConsoleCode: (String) -> Unit,
    consoleName: String,
    onConsoleName: (String) -> Unit,
    consoleDeviceId: String,
    consoleStatus: String,
    consoleConnected: Boolean,
    onBindConsole: () -> Unit,
    onScanConsole: () -> Unit,
    onSaveConsole: () -> Unit,
    onTestConsole: () -> Unit,
    onUnbindConsole: () -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val caps by DeviceCapabilities.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var memoryEntries by remember { mutableStateOf<List<AppMemory.MemoryEntry>>(emptyList()) }
    var editingMemory by remember { mutableStateOf<AppMemory.MemoryEntry?>(null) }
    var memoryDraft by remember { mutableStateOf("") }
    val price = remember { UsageSettings.load(context) }
    var priceIn by remember { mutableStateOf(price.inputPerMillion.toString()) }
    var priceOut by remember { mutableStateOf(price.outputPerMillion.toString()) }
    val aboutInfo = remember { loadAppAboutInfo(context) }

    fun reloadMemory() {
        scope.launch {
            memoryEntries = withContext(Dispatchers.IO) { AppMemory.listEntries(context) }
        }
    }

    LaunchedEffect(tab) {
        scrollState.scrollTo(0)
        if (tab == 2) reloadMemory()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            // 设置页内容长, 下滑翻页时容易误触把整页甩掉;
            // 关闭统一走右上角 X / 返回键。
            confirmValueChange = { it != SheetValue.Hidden }
        ),
        containerColor = Color.White,
        sheetMaxWidth = 640.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Spacer(Modifier.height(10.dp)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .imePadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "设置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    modifier = Modifier.weight(1f)
                )
                ModeBadge(caps.mode)
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, "关闭", tint = Muted)
                }
            }
            Text(
                when (tab) {
                    0 -> "配置模型接口和可用模型。"
                    1 -> "绑定内置 Web 控制台，远程查看日志和控制任务。"
                    2 -> "管理每个 App 的简短操作记忆。"
                    3 -> "查看会话 token、上下文和耗时。"
                    else -> "了解 CatfeeAgents、能力状态和隐私说明。"
                },
                fontSize = 12.sp,
                color = Muted
            )
            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFF3F4F6))
                    .padding(3.dp)
            ) {
                SettingsTab("模型", tab == 0, Modifier.weight(1f)) { tab = 0 }
                SettingsTab("控制台", tab == 1, Modifier.weight(1f)) { tab = 1 }
                SettingsTab("记忆", tab == 2, Modifier.weight(1f)) { tab = 2 }
                SettingsTab("统计", tab == 3, Modifier.weight(1f)) { tab = 3 }
                SettingsTab("关于", tab == 4, Modifier.weight(1f)) { tab = 4 }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CapabilityChip("无障碍", caps.accessibility)
                CapabilityChip("手势", caps.gestures)
                CapabilityChip("截图", caps.hasScreenshot)
                CapabilityChip("Root", caps.root)
                CapabilityChip("悬浮窗", overlayGranted)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("显示悬浮状态窗", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
                    Text(
                        "在其他 App 上显示步骤、阶段、耗时和控制按钮；默认开启，截图/OCR 可能看到它。",
                        fontSize = 9.5.sp, color = Muted, lineHeight = 14.sp
                    )
                }
                Switch(
                    checked = overlayEnabled && overlayGranted,
                    onCheckedChange = { onOverlayEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Ink,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFD1D5DB),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }
            if (!overlayGranted) {
                TextButton(
                    onClick = onRequestOverlay,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("授予悬浮窗权限", fontSize = 10.5.sp, color = Accent)
                }
            }
            Spacer(Modifier.height(14.dp))

            if (tab == 0) {
                Text(
                    "接口",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Muted,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                StudioField("Base URL", baseUrl, onBaseUrl, "https://api.example.com/v1")
                StudioField("API Key", apiKey, onApiKey, "sk-...", mono = true)
                StudioField("模型 ID", model, onModel, "deepseek-v4.1-flash", mono = true)
                OutlinedButton(
                    onClick = onTestModel,
                    enabled = !modelsLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Line),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Ink,
                        disabledContentColor = Color(0xFF9CA3AF)
                    )
                ) {
                    Text(if (modelsLoading) "拉取中…" else "拉取模型列表", fontSize = 12.sp)
                }
                if (testResult.isNotBlank()) {
                    val failed = testResult.startsWith("连接失败") || testResult.contains("失败:")
                    Text(
                        testResult,
                        fontSize = 11.sp,
                        color = if (failed) Danger else Muted,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Button(
                    onClick = onSaveModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink)
                ) { Text("保存模型配置", fontSize = 12.sp) }

                if (models.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "可用模型",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Muted,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ModelPicker(models, model) { id ->
                        onModel(id)
                        onSaveModel()
                    }
                    Text(
                        "点击模型即可切换并保存。",
                        fontSize = 10.sp,
                        color = Color(0xFF9CA3AF),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    Text(
                        "拉取后会在这里列出模型，可直接点击选择。",
                        fontSize = 10.sp,
                        color = Color(0xFF9CA3AF),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Spacer(Modifier.height(18.dp))
                DecisionModeSection()
            } else if (tab == 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Web 控制台", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(
                            "日志、任务和远程指令统一走内置控制台，不再使用第三方 JWT。",
                            fontSize = 11.sp, color = Muted, lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = consoleEnabled,
                        onCheckedChange = onConsoleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Ink,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFD1D5DB),
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "连接配置",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Muted,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                StudioField(
                    "控制台地址", consoleBase, onConsoleBase,
                    "http://192.168.1.100:9777"
                )
                StudioField(
                    "设备名称", consoleName, onConsoleName,
                    "${Build.MANUFACTURER} ${Build.MODEL}"
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFFAFBFC),
                    border = BorderStroke(1.dp, Line)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "一次性注册码", fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold, color = Ink
                        )
                        Text(
                            "在 Web 控制台「设备」页生成，可手动输入或扫码绑定，绑定成功后立即失效。",
                            fontSize = 10.sp, color = Muted, lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StudioField(
                                "注册码", consoleCode, onConsoleCode, "粘贴注册码",
                                mono = true, modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = onBindConsole,
                                enabled = consoleCode.isNotBlank(),
                                modifier = Modifier.padding(bottom = 8.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Ink,
                                    disabledContainerColor = Color(0xFFE5E7EB),
                                    disabledContentColor = Color(0xFF9CA3AF)
                                )
                            ) { Text("绑定", fontSize = 12.sp) }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onScanConsole,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF3F4F6),
                        contentColor = Ink
                    )
                ) {
                    Icon(
                        Icons.Rounded.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("扫码绑定", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onTestConsole, contentPadding = PaddingValues(0.dp)) {
                        Text("测试连接", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Ink)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onSaveConsole, contentPadding = PaddingValues(0.dp)) {
                        Text("保存配置", fontSize = 11.sp, color = Muted)
                    }
                }

                if (consoleDeviceId.isNotBlank() || consoleStatus.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFFAFBFC),
                        border = BorderStroke(1.dp, Line)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            if (consoleDeviceId.isNotBlank()) {
                                Text(
                                    if (consoleConnected) "已连接 Web 控制台" else "已绑定设备",
                                    fontSize = 11.sp,
                                    color = if (consoleConnected) Success else Muted
                                )
                                Text(
                                    "设备 ID · $consoleDeviceId",
                                    fontSize = 10.sp, color = Muted,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            if (consoleStatus.isNotBlank()) {
                                Text(
                                    consoleStatus,
                                    fontSize = 11.sp, color = Muted, lineHeight = 16.sp,
                                    modifier = Modifier.padding(
                                        top = if (consoleDeviceId.isNotBlank()) 4.dp else 0.dp
                                    )
                                )
                            }
                        }
                    }
                }
                if (consoleDeviceId.isNotBlank()) {
                    TextButton(
                        onClick = onUnbindConsole,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text("解除绑定", fontSize = 11.sp, color = Danger)
                    }
                }
                Text(
                    "设备只保存自己的 deviceId / deviceSecret；Web 撤销设备后长连接立即失效。",
                    fontSize = 10.sp, color = Color(0xFF9CA3AF), lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else if (tab == 2) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("App 记忆", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(
                            "按任务相关性注入核心记忆；每条有类型、置信度、使用次数，可在下方直接编辑。",
                            fontSize = 10.5.sp, color = Muted, lineHeight = 15.sp
                        )
                    }
                    TextButton(onClick = { reloadMemory() }, contentPadding = PaddingValues(0.dp)) {
                        Text("刷新", fontSize = 11.sp, color = Accent)
                    }
                }
                Spacer(Modifier.height(10.dp))

                val editing = editingMemory
                if (editing != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFFAFBFC),
                        border = BorderStroke(1.dp, Line)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    editing.pkg,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Ink,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { editingMemory = null },
                                    contentPadding = PaddingValues(0.dp)
                                ) { Text("返回列表", fontSize = 10.5.sp, color = Muted) }
                            }
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = memoryDraft,
                                onValueChange = { if (it.length <= AppMemory.MAX_WRITE) memoryDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 6,
                                maxLines = 12,
                                textStyle = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, color = Ink),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Ink,
                                    unfocusedBorderColor = Line,
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White,
                                    cursorColor = Ink
                                )
                            )
                            Text(
                                "${memoryDraft.length}/${AppMemory.MAX_WRITE} 字",
                                fontSize = 9.5.sp,
                                color = if (memoryDraft.length >= AppMemory.MAX_WRITE) Warning else Muted,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Row(
                                modifier = Modifier.padding(top = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                AppMemory.overwrite(context, editing.pkg, memoryDraft)
                                            }
                                            reloadMemory()
                                            editingMemory = null
                                        }
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Ink)
                                ) { Text("保存", fontSize = 12.sp) }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                AppMemory.delete(context, editing.pkg)
                                            }
                                            reloadMemory()
                                            editingMemory = null
                                        }
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, Line)
                                ) { Text("删除", fontSize = 12.sp, color = Danger) }
                            }
                        }
                    }
                } else {
                    if (memoryEntries.isEmpty()) {
                        Text(
                            "暂无 App 记忆。Agent 完成后会自动写入，也可以在长任务中让它 memory_write。",
                            fontSize = 10.5.sp, color = Muted, lineHeight = 16.sp
                        )
                    }
                    memoryEntries.forEach { entry ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingMemory = entry
                                    memoryDraft = ""
                                    scope.launch {
                                        memoryDraft = withContext(Dispatchers.IO) {
                                            AppMemory.readRaw(context, entry.pkg)
                                        }
                                    }
                                },
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFFFAFBFC),
                            border = BorderStroke(1.dp, Line)
                        ) {
                            Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        entry.pkg,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Ink,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${entry.entryCount} 条",
                                        fontSize = 9.sp,
                                        color = Muted
                                    )
                                }
                                if (entry.preview.isNotBlank()) {
                                    Text(
                                        entry.preview,
                                        fontSize = 9.5.sp,
                                        color = Muted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                    }
                }
            } else if (tab == 3) {
                val totalTokens = sessions.sumOf { it.totalTokens }
                val totalPrompt = sessions.sumOf { it.promptTokens }
                val totalCompletion = sessions.sumOf { it.completionTokens }
                val totalDuration = sessions.sumOf { it.totalDurationMs }
                val avgCtx = if (sessions.isEmpty()) 0 else sessions.sumOf { it.contextChars } / sessions.size
                val priceInValue = priceIn.toDoubleOrNull() ?: 0.0
                val priceOutValue = priceOut.toDoubleOrNull() ?: 0.0
                val estimated = totalPrompt / 1_000_000.0 * priceInValue +
                    totalCompletion / 1_000_000.0 * priceOutValue

                Text("总览", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Muted)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFFAFBFC),
                    border = BorderStroke(1.dp, Line)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        UsageStatRow("会话数", sessions.size.toString())
                        UsageStatRow("总 tokens", formatCount(totalTokens))
                        UsageStatRow(
                            "prompt / completion",
                            "${formatCount(totalPrompt)} / ${formatCount(totalCompletion)}"
                        )
                        UsageStatRow("累计耗时", formatDuration(totalDuration))
                        UsageStatRow("平均上下文", formatCount(avgCtx))
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("估算单价（每 1M tokens）", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Muted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioField(
                        "输入", priceIn, { priceIn = it.filter { c -> c.isDigit() || c == '.' } },
                        "0", mono = true, modifier = Modifier.weight(1f)
                    )
                    StudioField(
                        "输出", priceOut, { priceOut = it.filter { c -> c.isDigit() || c == '.' } },
                        "0", mono = true, modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "估算: ${String.format(Locale.US, "%.4f", estimated)}",
                        fontSize = 11.sp, color = if (estimated > 0) Ink else Muted,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            UsageSettings.save(
                                context,
                                priceInValue,
                                priceOutValue
                            )
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("保存单价", fontSize = 10.5.sp, color = Accent) }
                }

                Spacer(Modifier.height(14.dp))
                Text("按会话", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Muted)
                Spacer(Modifier.height(6.dp))
                sessions.sortedByDescending { it.totalTokens }.take(20).forEach { s ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFAFBFC),
                        border = BorderStroke(1.dp, Line)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.title,
                                    fontSize = 11.sp,
                                    color = Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${s.model.ifBlank { "默认模型" }} · ${formatCount(s.totalTokens)} tok · " +
                                        formatDuration(s.totalDurationMs),
                                    fontSize = 9.sp,
                                    color = Muted
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                }
            } else {
                Text(
                    "CatfeeAgents",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
                Text(
                    "通用 Agent 工作台",
                    fontSize = 11.sp,
                    color = Muted,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(Modifier.height(14.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFFAFBFC),
                    border = BorderStroke(1.dp, Line)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("应用信息", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Muted)
                        Spacer(Modifier.height(5.dp))
                        AboutInfoRow("版本", aboutInfo.versionName)
                        AboutInfoRow("Version Code", aboutInfo.versionCode)
                        AboutInfoRow("Application ID", aboutInfo.applicationId)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("能力状态", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Muted)
                Spacer(Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFFAFBFC),
                    border = BorderStroke(1.dp, Line)
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                        AboutCapabilityRow("对话与模型", "通过已配置的 LLM 接口进行对话和任务规划。", model.isNotBlank())
                        AboutCapabilityRow("无障碍操作", "读取界面并执行 Agent 规划的设备操作。", UiObserverService.instance != null)
                        AboutCapabilityRow("ROOT 能力", "在设备已授权时执行需要 ROOT 的系统操作。", rootGranted == true)
                        AboutCapabilityRow("悬浮状态窗", "在其他应用上显示任务步骤和运行状态。", overlayEnabled && overlayGranted)
                        AboutCapabilityRow("本地会话与记忆", "在设备本地保存会话、附件摘要和 App 记忆。", true)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("隐私说明", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Muted)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFFAFBFC),
                    border = BorderStroke(1.dp, Line)
                ) {
                    Text(
                        "会话、附件解析结果和 App 记忆默认保存在本设备。仅当你配置并启用模型接口或远程日志时，相关请求/日志才会发送到对应服务；API Key 和日志凭证由你自行管理。ROOT、无障碍和悬浮窗能力均遵循系统授权状态。",
                        fontSize = 10.5.sp,
                        color = Muted,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    message: ChatMessage,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        sheetMaxWidth = 640.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Spacer(Modifier.height(10.dp)) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("消息操作", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(
                message.content.replace('\n', ' ').take(80),
                fontSize = 10.5.sp,
                color = Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
            Spacer(Modifier.height(10.dp))
            MessageActionRow(Icons.Rounded.ContentCopy, "复制") { onCopy() }
            MessageActionRow(Icons.Rounded.FormatQuote, "引用回复") { onQuote() }
            if (message.role == "user") {
                MessageActionRow(Icons.Rounded.Edit, "编辑并重新生成") { onEdit() }
            } else {
                MessageActionRow(Icons.Rounded.Refresh, "重新生成") { onRegenerate() }
            }
            MessageActionRow(Icons.Rounded.CallSplit, "从这里分支") { onBranch() }
        }
    }
}

@Composable
private fun MessageActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, color = Ink)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(
    currentModel: String,
    globalModel: String,
    models: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = buildList {
        add("" to "跟随全局默认（${globalModel.ifBlank { "未配置" }}）")
        models.distinct().forEach { add(it to it) }
        if (currentModel.isNotBlank() && models.none { it == currentModel }) {
            add(currentModel to "$currentModel（当前）")
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            // 模型列表可能很长, 下滑浏览时不要误触关闭
            confirmValueChange = { it != SheetValue.Hidden }
        ),
        containerColor = Color.White,
        sheetMaxWidth = 640.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Spacer(Modifier.height(10.dp)) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本会话模型", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text("只影响当前会话；全局默认在 设置 → 模型 修改。", fontSize = 10.5.sp, color = Muted)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, "关闭", tint = Muted)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (models.isEmpty()) {
                Text(
                    "还没有拉取模型列表。可以在 设置 → 模型 点「拉取模型列表」后回到这里切换。",
                    fontSize = 10.5.sp,
                    color = Muted,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))
            }
            options.forEach { (value, label) ->
                val selected = value == currentModel
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(value) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) Accent.copy(alpha = 0.05f) else Color.White,
                    border = BorderStroke(1.dp, if (selected) Accent.copy(alpha = 0.25f) else Line)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = Ink,
                            fontFamily = if (value.isBlank()) FontFamily.Default else FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (selected) {
                            Icon(Icons.Rounded.Check, null, tint = Accent, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun UsageStatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.5.sp, color = Muted, modifier = Modifier.weight(1f))
        Text(value, fontSize = 10.5.sp, color = Ink)
    }
}

@Composable
private fun CapabilityChip(label: String, ok: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (ok) Success.copy(alpha = 0.08f) else Color(0xFFF1F3F5),
        border = BorderStroke(1.dp, if (ok) Success.copy(alpha = 0.22f) else Line)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(5.dp)
                    .background(if (ok) Success else Color(0xFF9CA3AF), CircleShape)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "$label ${if (ok) "已就绪" else "未开启"}",
                fontSize = 8.5.sp,
                color = if (ok) Success else Muted
            )
        }
    }
}

@Composable
private fun ModeBadge(mode: DeviceMode) {
    val (label, active) = when (mode) {
        DeviceMode.FULL -> "完整模式" to true
        DeviceMode.ACCESSIBILITY -> "无障碍模式" to true
        DeviceMode.CHAT_ONLY -> "仅聊天模式" to false
    }
    val color = when (mode) {
        DeviceMode.FULL -> Success
        DeviceMode.ACCESSIBILITY -> Warning
        DeviceMode.CHAT_ONLY -> Muted
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(5.dp).background(color, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) color else Muted,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RootBadge(granted: Boolean?) {
    val active = granted == true
    val label = when (granted) {
        true -> "已 ROOT"
        false -> "未 ROOT"
        null -> "检测中"
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) Success.copy(alpha = 0.10f) else Color(0xFFF1F3F5),
        border = BorderStroke(1.dp, if (active) Success.copy(alpha = 0.25f) else Line)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(5.dp)
                    .background(if (active) Success else Color(0xFF9CA3AF), CircleShape)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) Success else Muted,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DecisionModeSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { DecisionSettings.load(context) }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var baseUrl by rememberSaveable { mutableStateOf(initial.baseUrl) }
    var apiKey by rememberSaveable { mutableStateOf(initial.apiKey) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var minConfidence by rememberSaveable { mutableStateOf("%.2f".format(initial.minConfidence)) }
    var maxFastSteps by rememberSaveable { mutableStateOf(initial.maxFastSteps.toString()) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    fun config() = DecisionSettings.Config(
        enabled = enabled,
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        minConfidence = minConfidence.toDoubleOrNull()
            ?.coerceIn(0.5, 1.0) ?: DecisionSettings.DEFAULT_MIN_CONFIDENCE,
        maxFastSteps = maxFastSteps.toIntOrNull()
            ?.coerceIn(1, 8) ?: DecisionSettings.DEFAULT_MAX_FAST_STEPS
    )

    fun save() {
        if (DecisionSettings.save(context, config())) {
            result = "已保存 DECISION 配置"
        } else {
            result = "保存失败，请检查本机存储"
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFFAFBFC),
        border = BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "DECISION 加速",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink
                    )
                    Text(
                        "默认关闭。高置信度时用 decision 接口接管 observe / wait，跳过本轮主模型；" +
                            "点击、输入和完成仍由主模型决定。",
                        fontSize = 9.5.sp,
                        color = Muted,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        save()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Ink,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFD1D5DB),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }

            if (enabled) {
                Spacer(Modifier.height(12.dp))
                StudioField(
                    "DECISION Base URL", baseUrl, { baseUrl = it },
                    DecisionSettings.DEFAULT_BASE_URL, mono = true, minLines = 2
                )
                StudioField(
                    "DECISION Key", apiKey, { apiKey = it },
                    "sk-...", mono = true
                )
                StudioField(
                    "DECISION 模型", model, { model = it },
                    "decision-model-preview", mono = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioField(
                        "最小置信度", minConfidence, { minConfidence = it },
                        "0.80", mono = true, modifier = Modifier.weight(1f)
                    )
                    StudioField(
                        "最多连续快速步", maxFastSteps, { maxFastSteps = it },
                        "3", mono = true, modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            if (testing) return@OutlinedButton
                            testing = true
                            result = ""
                            val cfg = config()
                            scope.launch {
                                try {
                                    val state = JSONObject()
                                        .put("goal", "测试 DECISION 接口连通性")
                                        .put("step", 2)
                                        .put("last_tool", "observe")
                                        .put("observation", "当前页面有登录按钮和「稍后再说」弹窗")
                                        .put("foreground_app", "com.example.app")
                                    val outcome = DecisionClient.decide(cfg, state, 4_000)
                                    result = if (outcome.ok) {
                                        "连接成功：${outcome.action} " +
                                            "${(outcome.confidence * 100).toInt()}% · " +
                                            "${outcome.latencyMs}ms · ${outcome.inputTokens} tokens"
                                    } else {
                                        "连接失败：${outcome.error}"
                                    }
                                } finally {
                                    testing = false
                                }
                            }
                        },
                        enabled = !testing,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Line),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Ink,
                            disabledContentColor = Color(0xFF9CA3AF)
                        )
                    ) {
                        Text(if (testing) "测试中…" else "测试连接", fontSize = 11.sp)
                    }
                    Button(
                        onClick = { save() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink)
                    ) {
                        Text("保存 DECISION 配置", fontSize = 11.sp)
                    }
                }
                if (result.isNotBlank()) {
                    val ok = result.startsWith("连接成功") || result.startsWith("已保存")
                    Text(
                        result,
                        fontSize = 10.5.sp,
                        color = if (ok) Success else Danger,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Text(
                    "Base URL 填到 /compatible-mode/v1 即可，接口会自动拼接 /systemone。" +
                        "Key 仅保存在本机加密偏好中。",
                    fontSize = 9.5.sp,
                    color = Color(0xFF9CA3AF),
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Ink else Muted
        )
    }
}

@Composable
private fun ModelPicker(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFFAFBFC),
        border = BorderStroke(1.dp, Line)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${models.size} 个模型", fontSize = 10.sp, color = Muted)
                Spacer(Modifier.weight(1f))
                Text("点击选择", fontSize = 9.5.sp, color = Color(0xFF9CA3AF))
            }
            HorizontalDivider(color = Line.copy(alpha = 0.7f))
            models.forEachIndexed { index, id ->
                val isSelected = id == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(id) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) Accent else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSelected) Accent else Color(0xFFD1D5DB),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        id,
                        fontSize = 12.sp,
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Text("当前", fontSize = 9.sp, color = Accent)
                    }
                }
                if (index != models.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 38.dp),
                        color = Line.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = Muted,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
    )
}

@Composable
private fun StudioField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    mono: Boolean = false,
    minLines: Int = 1,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        placeholder = {
            if (placeholder.isNotBlank()) {
                Text(placeholder, fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
        },
        minLines = minLines,
        maxLines = if (minLines > 1) 4 else 1,
        singleLine = minLines == 1,
        textStyle = TextStyle(
            fontSize = 12.sp,
            color = Ink,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        ),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Ink,
            unfocusedBorderColor = Line,
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            cursorColor = Ink
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    )
}

/* ---------------- helpers ---------------- */

private fun typeLabel(type: String): String = when (type) {
    "think" -> "Thinking"
    "tool" -> "Action"
    "result" -> "Result"
    "done" -> "Done"
    "error" -> "Error"
    "todo" -> "Plan"
    "system" -> "System"
    "conflict" -> "Conflict"
    else -> type
}

private fun typeColor(type: String): Color = when (type) {
    "think" -> Muted
    "tool" -> Ink
    "result" -> Color(0xFF64748B)
    "done" -> Success
    "error" -> Danger
    "todo" -> Violet
    "system" -> Muted
    "conflict" -> Warning
    else -> Muted
}

private fun formatCount(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.getDefault(), "%.1fK", n / 1_000.0)
    else -> n.toString()
}

private fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> String.format(Locale.getDefault(), "%.1fs", ms / 1000.0)
    ms < 60_000 -> String.format(Locale.getDefault(), "%.1fs", ms / 1000.0)
    else -> {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        if (s == 0L) "${m}m" else "${m}m ${s}s"
    }
}

/** 轻量 Markdown: **加粗** 与 `行内代码` */
private fun markdownLite(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0xFFF1F3F5)
                    )
                ) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}
