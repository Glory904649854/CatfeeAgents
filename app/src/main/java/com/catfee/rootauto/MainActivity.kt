package com.catfee.rootauto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.catfee.rootauto.agent.AgentController
import com.catfee.rootauto.llm.LlmClient
import com.catfee.rootauto.llm.LlmSettings
import com.catfee.rootauto.log.AgentTrace
import com.catfee.rootauto.log.LogSettings
import com.catfee.rootauto.session.Attachment
import com.catfee.rootauto.session.AttachmentAnalyzer
import com.catfee.rootauto.session.ChatMessage
import com.catfee.rootauto.session.Session
import com.catfee.rootauto.session.SessionManager
import com.catfee.rootauto.session.SessionSummary
import kotlinx.coroutines.Dispatchers
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
        window.statusBarColor = AndroidColor.rgb(247, 248, 250)
        window.navigationBarColor = AndroidColor.rgb(247, 248, 250)
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars = true
        setContent {
            AgentStudioTheme {
                AgentConsole(this)
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
private val Accent = Color(0xFF2563EB)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentConsole(activity: ComponentActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    val current by SessionManager.current.collectAsState()
    val sessions by SessionManager.summaries.collectAsState()
    val agent by AgentController.state.collectAsState()

    var task by remember {
        mutableStateOf(activity.intent?.getStringExtra("task")?.takeIf { it.isNotBlank() } ?: "")
    }
    var pendingAttachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var previewImages by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var previewIndex by remember { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }

    // ---- LLM 配置 ----
    val saved = remember { LlmSettings.active(activity) }
    var baseUrl by remember { mutableStateOf(saved?.baseUrl ?: DEFAULT_BASE_URL) }
    var apiKey by remember { mutableStateOf(saved?.apiKey ?: DEFAULT_API_KEY) }
    var model by remember { mutableStateOf(saved?.model ?: DEFAULT_MODEL) }
    var testResult by remember { mutableStateOf("") }

    // ---- 远程日志配置 ----
    val logCfg = remember { LogSettings.config(activity) }
    var logEnabled by remember { mutableStateOf(logCfg.enabled) }
    var logBase by remember { mutableStateOf(logCfg.baseUrl) }
    var logToken by remember { mutableStateOf(logCfg.token) }
    var logProject by remember { mutableStateOf(logCfg.project) }
    var logCode by remember { mutableStateOf("") }
    var logStatus by remember { mutableStateOf(logCfg.lastStatus) }
    val traceStatus by AgentTrace.status.collectAsState()
    LaunchedEffect(traceStatus) {
        if (traceStatus.isNotBlank()) logStatus = traceStatus
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

    fun persistLog() {
        LogSettings.save(
            activity,
            LogSettings.Config(
                enabled = logEnabled,
                baseUrl = logBase.trim(),
                token = logToken.trim(),
                project = logProject,
                expiresAt = logCfg.expiresAt,
                lastStatus = logStatus
            )
        )
    }

    fun newSession() {
        SessionManager.flush(activity)
        SessionManager.newSession("chat")
        task = ""
        pendingAttachments = emptyList()
        hint = ""
        showMenu = false
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

    fun send() {
        if (current?.running == true) return
        val text = task.trim()
        val attachments = pendingAttachments
        if (text.isEmpty() && attachments.isEmpty()) {
            hint = "先输入消息，或添加图片/文件"
            return
        }
        val provider = currentProvider()
        if (!provider.usable) {
            hint = "LLM 配置不完整，点菜单进入 Settings 填写"
            showSettings = true
            return
        }
        LlmSettings.upsert(activity, provider)
        LlmSettings.setActiveId(activity, "main")
        persistLog()
        keyboard?.hide()
        hint = ""

        val session = current ?: SessionManager.newSession("chat")
        val prior = buildPriorContext(session)
        val content = text.ifBlank { "请分析这些附件" }
        // 先把用户消息和附件发出去; 解析工作交给 AgentController,
        // 这样聊天区会立即出现"解析资源中"的工具步骤, 而不是发送前卡住。
        SessionManager.appendMessage(
            activity,
            session.id,
            ChatMessage("user", content, attachments = attachments)
        )
        AgentController.start(
            context = activity,
            provider = provider,
            goal = content,
            sessionId = session.id,
            priorContext = prior,
            phoneAllowed = session.phoneAllowed,
            attachments = attachments
        )
        task = ""
        pendingAttachments = emptyList()
    }

    fun stop() {
        AgentController.stop()
        hint = "已停止"
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
    val running = current?.running == true
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

    Scaffold(containerColor = Bg) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding()
        ) {
            Header(
                running = running,
                onMenu = {
                    SessionManager.flush(activity)
                    scope.launch { SessionManager.refreshHistory(activity) }
                    showMenu = true
                }
            )
            current?.let { SessionLine(it) }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (current == null || (current!!.messages.isEmpty() && !streaming)) {
                    item { EmptyState() }
                }
                current?.messages?.forEach { msg ->
                    item(key = "m-${msg.time}-${msg.content.hashCode()}") {
                        Box(Modifier.animateItem()) {
                            if (msg.role == "user") {
                                UserBubble(
                                    msg,
                                    onImageClick = { a ->
                                        val idx = allImages.indexOfFirst { it.id == a.id }
                                        previewImages = allImages
                                        previewIndex = if (idx >= 0) idx else 0
                                    },
                                    onFileClick = { a -> openAttachment(context, a) }
                                )
                            } else {
                                AssistantBubble(msg)
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
                            onAllow = { AgentController.answerConfirm(true) },
                            onDeny = { AgentController.answerConfirm(false) }
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

            Composer(
                task = task,
                onTaskChange = { task = it },
                model = model,
                attachments = pendingAttachments,
                onRemoveAttachment = { id ->
                    pendingAttachments = pendingAttachments.filterNot { it.id == id }
                },
                onPickAttachments = { attachmentPicker.launch(arrayOf("*/*")) },
                running = running,
                canSend = !running &&
                    (task.isNotBlank() || pendingAttachments.isNotEmpty()),
                onSend = { send() },
                onStop = { stop() },
                hint = hint
            )
        }
    }

    if (showMenu) {
        MenuSheet(
            sessions = sessions,
            currentId = current?.id.orEmpty(),
            runningSessionId = if (agent.running) agent.sessionId else "",
            onNew = { newSession() },
            onOpen = { id ->
                scope.launch {
                    SessionManager.open(activity, id)
                    showMenu = false
                }
            },
            onSettings = {
                showMenu = false
                showSettings = true
            },
            onDismiss = { showMenu = false }
        )
    }

    if (showSettings) {
        SettingsSheet(
            baseUrl = baseUrl,
            onBaseUrl = { baseUrl = it },
            apiKey = apiKey,
            onApiKey = { apiKey = it },
            model = model,
            onModel = { model = it },
            testResult = testResult,
            onSaveModel = {
                val p = currentProvider()
                LlmSettings.upsert(activity, p)
                LlmSettings.setActiveId(activity, "main")
                persistLog()
                testResult = "已保存 · ${p.model}"
            },
            onTestModel = {
                val p = currentProvider()
                LlmSettings.upsert(activity, p)
                LlmSettings.setActiveId(activity, "main")
                testResult = "正在测试连接…"
                scope.launch {
                    val (models, err) = LlmClient.listModels(p)
                    withContext(Dispatchers.Main) {
                        testResult = if (err.isNotEmpty()) "连接失败: $err"
                        else "连接成功 · ${models.size} 个模型"
                    }
                }
            },
            logEnabled = logEnabled,
            onLogEnabled = { logEnabled = it },
            logBase = logBase,
            onLogBase = { logBase = it },
            logToken = logToken,
            onLogToken = { logToken = it },
            logCode = logCode,
            onLogCode = { logCode = it },
            logProject = logProject,
            logStatus = logStatus.ifBlank { traceStatus },
            onSaveLog = {
                persistLog()
                logStatus = "配置已保存"
            },
            onExchange = {
                persistLog()
                logStatus = "正在换取令牌…"
                scope.launch {
                    val res = LogSettings.exchange(activity, logBase, logCode)
                    withContext(Dispatchers.Main) {
                        res.onSuccess { r ->
                            logEnabled = true
                            logBase = r.config.baseUrl
                            logToken = r.config.token
                            logProject = r.config.project
                            logStatus = r.message
                            logCode = ""
                        }.onFailure {
                            logStatus = it.message ?: "换取失败"
                        }
                    }
                }
            },
            onTestLog = {
                persistLog()
                logStatus = "正在自检…"
                scope.launch {
                    val res = LogSettings.whoami(activity)
                    withContext(Dispatchers.Main) {
                        logStatus = res.getOrElse { it.message ?: "自检失败" }
                    }
                }
            },
            onDismiss = { showSettings = false }
        )
    }

    if (previewImages.isNotEmpty()) {
        ImagePreviewOverlay(
            images = previewImages,
            initialIndex = previewIndex,
            onClose = { previewImages = emptyList() }
        )
    }
}

private fun buildPriorContext(session: Session): String {
    val sb = StringBuilder()
    session.messages.takeLast(8).forEach { m ->
        sb.append(if (m.role == "user") "用户: " else "助手: ")
        sb.append(m.content.replace('\n', ' ').take(500)).append('\n')
        if (m.context.isNotBlank()) {
            sb.append("[该消息附件的解析摘要] ")
                .append(m.context.replace('\n', ' ').take(600)).append('\n')
        }
    }
    if (session.result.isNotBlank()) {
        sb.append("最近一次结果: ").append(session.result.take(800)).append('\n')
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
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.6).sp,
                                color = Ink
                            )
                        ) { append("Catfee") }
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Light,
                                fontStyle = FontStyle.Italic,
                                letterSpacing = 0.3.sp,
                                color = Accent
                            )
                        ) { append("Agents") }
                    },
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    Modifier
                        .padding(start = 2.dp, top = 4.dp)
                        .size(4.dp)
                        .background(Color(0xFFEF4444), CircleShape)
                )
            }
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
                    Text("Working on this device", fontSize = 10.sp, color = Accent)
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
                .border(1.dp, Line, CircleShape)
                .background(Color.White)
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
    onFileClick: (Attachment) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier.widthIn(max = 300.dp),
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
                Text(
                    msg.content,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun AssistantBubble(msg: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
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
            modifier = Modifier.widthIn(max = 330.dp),
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Accent.copy(alpha = 0.2f))
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
            Box(Modifier.size(6.dp).alpha(a).background(Accent, CircleShape))
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
                        "允许控制手机？",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF92400E)
                    )
                    Text(
                        "Agent 想${permissionAction(pending.tool)}。允许后本会话不再询问。",
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
                        .background(Accent.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Terminal, null, tint = Accent, modifier = Modifier.size(14.dp))
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
                            color = Accent
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
    attachments: List<Attachment>,
    onRemoveAttachment: (String) -> Unit,
    onPickAttachments: () -> Unit,
    running: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    hint: String
) {
    val focusSource = remember { MutableInteractionSource() }
    val focused by focusSource.collectIsFocusedAsState()
    val borderAmount by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "focus")
    Surface(modifier = Modifier.fillMaxWidth(), color = Bg) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = Color.White,
                border = BorderStroke(1.dp, lerp(Line, Accent.copy(alpha = 0.5f), borderAmount))
            ) {
                Column(Modifier.padding(10.dp)) {
                    AnimatedVisibility(
                        visible = attachments.isNotEmpty(),
                        enter = fadeIn(tween(160)) + expandVertically(tween(180)),
                        exit = fadeOut(tween(120)) + shrinkVertically(tween(160))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            attachments.forEach { a ->
                                PendingAttachment(a) { onRemoveAttachment(a.id) }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AttachmentButton(onPickAttachments)
                        Spacer(Modifier.width(6.dp))
                        BasicTextField(
                            value = task,
                            onValueChange = onTaskChange,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp, max = 120.dp),
                            textStyle = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp, color = Ink),
                            cursorBrush = SolidColor(Accent),
                            maxLines = 4,
                            interactionSource = focusSource,
                            decorationBox = { inner ->
                                Box {
                                    if (task.isEmpty()) {
                                        Text(
                                            "发消息，或让 Agent 操作手机…",
                                            fontSize = 14.5.sp,
                                            color = Color(0xFF9CA3AF)
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        Spacer(Modifier.width(6.dp))
                        if (running) {
                            StopButton(onStop)
                        } else {
                            RunButton(canSend, onSend)
                        }
                    }
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ModelChip(model)
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (hint.isNotBlank()) {
                Text(
                    hint,
                    fontSize = 10.5.sp,
                    color = Muted,
                    modifier = Modifier.padding(start = 6.dp, top = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachmentButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(90), label = "attachScale")
    Box(
        modifier = Modifier
            .size(38.dp)
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(Accent.copy(alpha = 0.08f))
            .clickable(
                interactionSource = source,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.AddPhotoAlternate,
            contentDescription = "添加图片或文件",
            tint = Accent,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun ModelChip(model: String) {
    Surface(shape = RoundedCornerShape(50), color = Color(0xFFF3F4F6)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(4.dp).background(Success, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(
                model.ifBlank { "model" },
                fontSize = 9.sp,
                color = Muted,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp)
            )
        }
    }
}

@Composable
private fun RunButton(enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(90), label = "scale")
    Box(
        modifier = Modifier
            .size(38.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (enabled) Ink else Color(0xFFE5E7EB))
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
            tint = if (enabled) Color.White else Color(0xFF9CA3AF),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0xFFFEF2F2),
        border = BorderStroke(1.dp, Color(0xFFFECACA)),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Stop, null, tint = Danger, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text("Stop", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Danger)
        }
    }
}

@Composable
private fun PendingAttachment(a: Attachment, onRemove: () -> Unit) {
    Box {
        if (a.kind == "image") {
            AttachmentThumb(a, 64.dp)
        } else {
            FileChip(a)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color(0xFF111827))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(11.dp))
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
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF1F3F5),
        border = BorderStroke(1.dp, Line)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = a.name,
                modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)),
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
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF1F3F5),
        border = BorderStroke(1.dp, Line)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Description, null, tint = Accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Column {
                Text(
                    a.name,
                    fontSize = 9.5.sp,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp)
                )
                Text(
                    formatSize(a.size),
                    fontSize = 8.sp,
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
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${pagerState.currentPage + 1} / ${images.size}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                ) {
                    Icon(Icons.Rounded.Close, "关闭预览", tint = Color.White, modifier = Modifier.size(19.dp))
                }
            }
            Text(
                "双指缩放/旋转 · 双击还原 · 左右滑动切换",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 34.dp),
                color = Color.White.copy(alpha = 0.55f),
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
    runningSessionId: String,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Spacer(Modifier.height(10.dp)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
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
            if (sessions.isEmpty()) {
                Text("还没有历史会话，发第一条消息后会自动保存。", fontSize = 10.5.sp, color = Muted)
            }
            sessions.forEach { s ->
                SessionRow(
                    summary = s,
                    selected = s.id == currentId,
                    running = s.id == runningSessionId,
                    onClick = { onOpen(s.id) }
                )
                Spacer(Modifier.height(7.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Line)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onSettings)
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Settings, null, tint = Ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(9.dp))
                Text("Settings", fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f))
                Text("模型 / 日志", fontSize = 9.5.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun SessionRow(
    summary: SessionSummary,
    selected: Boolean,
    running: Boolean,
    onClick: () -> Unit
) {
    val time = remember(summary.updatedAt) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(summary.updatedAt))
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        color = if (selected) Accent.copy(alpha = 0.04f) else Color.White,
        border = BorderStroke(1.dp, if (selected) Accent.copy(alpha = 0.3f) else Line)
    ) {
        Row(
            modifier = Modifier.padding(11.dp),
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
        }
    }
}

/* ---------------- Settings ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    baseUrl: String,
    onBaseUrl: (String) -> Unit,
    apiKey: String,
    onApiKey: (String) -> Unit,
    model: String,
    onModel: (String) -> Unit,
    testResult: String,
    onSaveModel: () -> Unit,
    onTestModel: () -> Unit,
    logEnabled: Boolean,
    onLogEnabled: (Boolean) -> Unit,
    logBase: String,
    onLogBase: (String) -> Unit,
    logToken: String,
    onLogToken: (String) -> Unit,
    logCode: String,
    onLogCode: (String) -> Unit,
    logProject: String,
    logStatus: String,
    onSaveLog: () -> Unit,
    onExchange: () -> Unit,
    onTestLog: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Spacer(Modifier.height(10.dp)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .imePadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Settings",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, "关闭", tint = Muted)
                }
            }
            Text("模型接入与远程日志。所有配置仅保存在本机。", fontSize = 12.sp, color = Muted)

            SectionLabel("Model")
            StudioField("Base URL", baseUrl, onBaseUrl, "https://api.example.com/v1")
            StudioField("API Key", apiKey, onApiKey, "sk-...", mono = true)
            StudioField("Model", model, onModel, "deepseek-v4.1-flash", mono = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSaveModel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink)
                ) { Text("Save", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = onTestModel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Line)
                ) { Text("Test", fontSize = 12.sp, color = Ink) }
            }
            if (testResult.isNotBlank()) {
                Text(testResult, fontSize = 11.sp, color = Muted)
            }

            HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Line)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Remote trace", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text("把每一步的输入/输出上报到日志系统", fontSize = 11.sp, color = Muted)
                }
                Switch(
                    checked = logEnabled,
                    onCheckedChange = onLogEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Ink,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFD1D5DB),
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }
            Spacer(Modifier.height(10.dp))
            StudioField("Log Base URL", logBase, onLogBase, LogSettings.DEFAULT_BASE_URL)
            StudioField(
                "Agent Token", logToken, onLogToken,
                "粘贴 JWT，或在下方用 6 位校验码换取",
                mono = true, minLines = 2
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StudioField(
                    "6 位校验码", logCode, onLogCode, "000000",
                    mono = true, modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onExchange,
                    enabled = logCode.trim().length == 6,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ink,
                        disabledContainerColor = Color(0xFFE5E7EB),
                        disabledContentColor = Color(0xFF9CA3AF)
                    )
                ) { Text("Exchange", fontSize = 12.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onTestLog, contentPadding = PaddingValues(0.dp)) {
                    Text("Test token", fontSize = 11.sp, color = Accent)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSaveLog, contentPadding = PaddingValues(0.dp)) {
                    Text("Save trace config", fontSize = 11.sp, color = Muted)
                }
            }
            if (logProject.isNotBlank()) {
                Text(
                    "Bound project · $logProject",
                    fontSize = 11.sp, color = Success, lineHeight = 16.sp
                )
            }
            if (logStatus.isNotBlank()) {
                Text(
                    logStatus,
                    fontSize = 11.sp, color = Muted, lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                "校验码在日志系统「接入凭证」生成，一次性有效；换取成功后会自动启用上报。",
                fontSize = 10.sp, color = Color(0xFF9CA3AF), lineHeight = 15.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
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
    else -> type
}

private fun typeColor(type: String): Color = when (type) {
    "think" -> Accent
    "tool" -> Warning
    "result" -> Color(0xFF64748B)
    "done" -> Success
    "error" -> Danger
    "todo" -> Violet
    "system" -> Color(0xFFEA580C)
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
