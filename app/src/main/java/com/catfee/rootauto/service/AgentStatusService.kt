package com.catfee.rootauto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.catfee.rootauto.MainActivity
import com.catfee.rootauto.R
import com.catfee.rootauto.agent.AgentController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * AgentStatusService —— 前台通知 + 可选悬浮状态条
 *
 * 通知/悬浮窗只跟随 Agent owner，不跟随并发 chat。
 */
class AgentStatusService : Service() {

    companion object {
        // v2: 旧 channel 已是 LOW 且重要性不可提升; 新 channel 用 DEFAULT 以便状态栏显示
        private const val CHANNEL_ID = "catfee_agent_status_v2"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "AgentStatusService"
        private const val ACTION_STOP_AGENT = "com.catfee.rootauto.action.STOP_AGENT"
        private const val ACTION_PAUSE_AGENT = "com.catfee.rootauto.action.PAUSE_AGENT"
        private const val ACTION_RESUME_AGENT = "com.catfee.rootauto.action.RESUME_AGENT"
        private const val ACTION_REFRESH_OVERLAY = "com.catfee.rootauto.action.REFRESH_OVERLAY"
        private const val EXTRA_SESSION_ID = "session_id"

        @Volatile private var alive = false
        @Volatile private var instance: AgentStatusService? = null

        fun start(context: Context) {
            val intent = Intent(context, AgentStatusService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 设置页切换悬浮窗时调用; 服务没在跑就忽略 */
        fun refreshOverlay(context: Context) {
            if (!alive) return
            try {
                context.startService(
                    Intent(context, AgentStatusService::class.java).setAction(ACTION_REFRESH_OVERLAY)
                )
            } catch (_: Exception) {
            }
        }

        /**
         * 截图/OCR 前临时隐藏悬浮状态条, 避免状态条文字进入识别结果。
         * 返回 true 表示当前确实有悬浮窗, 调用方截图后要 restore。
         */
        fun hideOverlayForCapture(): Boolean {
            val service = instance ?: return false
            val view = service.overlayView ?: return false
            service.captureHandler.post { view.visibility = View.INVISIBLE }
            return true
        }

        fun showOverlayAfterCapture() {
            val service = instance ?: return
            service.captureHandler.post { service.overlayView?.visibility = View.VISIBLE }
        }

        private fun phaseLabel(phase: String): String = when (phase) {
            "paused" -> "已暂停"
            "waiting_llm" -> "等待模型"
            "operating" -> "操作中"
            "waiting_confirm" -> "等待确认"
            "done" -> "已完成"
            "stopped" -> "已停止"
            "error" -> "异常"
            "blocked" -> "被占用"
            else -> "准备中"
        }

        private fun phaseColor(phase: String): Int = when (phase) {
            "paused" -> Color.parseColor("#7C3AED")
            "waiting_llm" -> Color.parseColor("#D97706")
            "operating" -> Color.parseColor("#64748B")
            "waiting_confirm" -> Color.parseColor("#EA580C")
            "done" -> Color.parseColor("#16A34A")
            "error" -> Color.parseColor("#DC2626")
            "blocked" -> Color.parseColor("#D97706")
            else -> Color.parseColor("#9CA3AF")
        }

        private fun elapsedText(startedAtElapsed: Long): String {
            if (startedAtElapsed <= 0) return ""
            val sec = ((SystemClock.elapsedRealtime() - startedAtElapsed) / 1000).coerceAtLeast(0)
            return String.format("%02d:%02d", sec / 60, sec % 60)
        }

        private fun eventAgeSec(state: AgentController.State): Long =
            if (state.lastEventAtElapsed <= 0) 0
            else ((SystemClock.elapsedRealtime() - state.lastEventAtElapsed) / 1000).coerceAtLeast(0)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val captureHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var wasRunning = false
    private var lastState: AgentController.State? = null

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayWm: WindowManager? = null
    private var overlayTicker: Job? = null
    private var overlayRemoveJob: Job? = null
    /** 悬浮窗「×」第一次点击后的确认截止时间; 0 表示未进入确认态 */
    private var stopConfirmUntil = 0L
    private var lastOverlayBounds: Rect? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        instance = this
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                title = "Agent 准备中",
                text = "等待第一步…",
                state = AgentController.State(running = true, phase = "waiting_llm"),
                ongoing = true
            )
        )
        scope.launch {
            AgentController.agentState
                .distinctUntilChangedBy { s ->
                    if (s == null) emptyList<Any>() else listOf(
                        s.running, s.paused, s.phase, s.stepNumber, s.pendingConfirm?.tool,
                        s.terminalKind, s.lastEventText, s.result
                    )
                }
                .collectLatest { s ->
                    if (s == null) return@collectLatest
                    lastState = s
                    if (s.running) {
                        wasRunning = true
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIFICATION_ID, buildNotification(
                            title = "Agent · 第 ${s.stepNumber} 步 · ${phaseLabel(s.phase)}",
                            text = s.lastEventText.ifBlank { "运行中" },
                            state = s,
                            ongoing = true
                        ))
                        syncOverlay(s)
                    } else if (wasRunning || s.terminalKind.isNotBlank()) {
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(
                            NOTIFICATION_ID,
                            buildNotification(
                                title = finalTitle(s),
                                text = s.result?.take(120).orEmpty().ifBlank { phaseLabel(s.phase) },
                                state = s,
                                ongoing = false
                            )
                        )
                        syncOverlay(s)
                        delay(1500)
                        if (AgentController.agentBusySessionId() == null) {
                            stopForeground(STOP_FOREGROUND_DETACH)
                            stopSelf()
                        }
                    }
                }
        }
        // 每 5 秒刷新“最后事件 Xs 前”，让用户能看出是否卡住
        scope.launch {
            while (isActive) {
                delay(5000)
                val s = lastState ?: continue
                if (!s.running) continue
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                        title = "Agent · 第 ${s.stepNumber} 步 · ${phaseLabel(s.phase)}",
                        text = s.lastEventText.ifBlank { "运行中" },
                        state = s,
                        ongoing = true
                    )
                )
            }
        }
        // Agent 注入手势命中悬浮窗时临时让点击穿透, 手势结束自动恢复
        scope.launch {
            OverlayTouchGuard.touchable.collect { touchable ->
                applyOverlayTouchable(touchable)
            }
        }
        // 服务被系统重启但没有真实 Agent 状态时，不要留“准备中”假象
        scope.launch {
            delay(20_000)
            if (lastState == null && AgentController.agentBusySessionId() == null) {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                        "Agent 状态已结束",
                        "进程可能已退出",
                        AgentController.State(phase = "stopped", terminalKind = "stopped"),
                        ongoing = false
                    )
                )
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        when (intent?.action) {
            ACTION_STOP_AGENT -> if (sessionId.isNotBlank()) AgentController.stopAgent(sessionId, "通知栏")
            ACTION_PAUSE_AGENT -> if (sessionId.isNotBlank()) AgentController.pauseAgent(sessionId)
            ACTION_RESUME_AGENT -> if (sessionId.isNotBlank()) AgentController.resumeAgent(sessionId)
            ACTION_REFRESH_OVERLAY -> {
                lastState?.let { syncOverlay(it) }
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转后重新贴边，避免悬浮窗跑到屏幕外
        if (overlayView != null) {
            removeOverlay()
            lastState?.takeIf { it.running }?.let { syncOverlay(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        alive = false
        instance = null
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    private fun finalTitle(s: AgentController.State): String = when (s.terminalKind) {
        "done" -> "Agent 已完成"
        "stopped" -> "用户已停止"
        "error" -> "Agent 异常"
        "blocked" -> "Agent 被其他会话占用"
        else -> "Agent 已结束"
    }

    private fun buildNotification(
        title: String,
        text: String,
        state: AgentController.State,
        ongoing: Boolean
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (state.sessionId.isNotBlank()) putExtra("session_id", state.sessionId)
        }
        val openPi = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sessionId = state.sessionId
        val requestBase = 10_000 + (sessionId.hashCode() and 0x0FFF)
        fun actionIntent(action: String) = Intent(this, AgentStatusService::class.java).apply {
            setAction(action)
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        val stopPi = PendingIntent.getService(
            this, requestBase + 1, actionIntent(ACTION_STOP_AGENT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseResumeAction = if (state.paused) ACTION_RESUME_AGENT else ACTION_PAUSE_AGENT
        val pauseResumePi = PendingIntent.getService(
            this, requestBase + 2, actionIntent(pauseResumeAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val useful = state.steps
            .filterNot {
                it.text.startsWith("等待中") || it.text.startsWith("等待「") ||
                    (it.type == "system" && it.text.startsWith("Step "))
            }
            .takeLast(8)
        val big = if (useful.isEmpty()) text else buildString {
            useful.forEach { st ->
                val tag = when (st.type) {
                    "think" -> "思考"
                    "tool" -> "动作"
                    "result" -> "结果"
                    "done" -> "完成"
                    "error" -> "失败"
                    "conflict" -> "冲突"
                    "todo" -> "计划"
                    "system" -> "系统"
                    else -> st.type
                }
                append("#").append(st.seq).append(' ').append(tag)
                if (st.tool.isNotBlank()) append(' ').append(st.tool)
                append(": ").append(st.text.take(160)).append('\n')
            }
        }
        val age = eventAgeSec(state)
        val contentText = buildString {
            append(text.take(80))
            if (ongoing && age > 0) append(" · ").append(age).append("s前")
            if (ongoing && state.phase == "waiting_llm" && age > 60) {
                append(" · 等待模型较久")
            }
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setContentIntent(openPi)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(if (state.startedAtWall > 0) state.startedAtWall else System.currentTimeMillis())
            .setUsesChronometer(ongoing && state.startedAtWall > 0)
            .addAction(android.R.drawable.ic_menu_view, "打开", openPi)
        if (ongoing) {
            builder.addAction(
                if (state.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (state.paused) "继续" else "暂停",
                pauseResumePi
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    // ---------------- 悬浮状态条 ----------------

    private fun syncOverlay(s: AgentController.State) {
        overlayRemoveJob?.cancel()
        overlayRemoveJob = null
        val enabled = DisplaySettings.overlayEnabled(this) &&
            Settings.canDrawOverlays(this)
        if (!enabled) {
            removeOverlay()
            return
        }
        if (!s.running) {
            if (overlayView != null) {
                updateOverlay(s)
                overlayRemoveJob = scope.launch {
                    delay(3000)
                    removeOverlay()
                }
            }
            return
        }
        if (overlayView == null) {
            attachOverlay()
        }
        updateOverlay(s)
        startOverlayTicker()
    }

    private fun attachOverlay() {
        if (overlayView != null) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val view = LayoutInflater.from(this).inflate(R.layout.agent_status_overlay, null)
            // 状态条不向无障碍暴露: 读屏/页面快照不该把悬浮窗当成内容。
            // 触摸不受影响, 暂停/停止按钮仍可正常点击。
            view.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val savedPosition = DisplaySettings.overlayPosition(this)
            val density = resources.displayMetrics.density
            val overlayWidth = minOf(
                (300 * density).toInt(),
                resources.displayMetrics.widthPixels - (32 * density).toInt()
            )
            val lp = WindowManager.LayoutParams(
                overlayWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                // 绝对坐标, 便于拖动; 没保存过时在 post 里放到右侧居中
                gravity = Gravity.TOP or Gravity.START
                x = savedPosition?.first ?: 0
                y = savedPosition?.second ?: 0
                setTitle("Catfee Agent Status")
            }
            wm.addView(view, lp)
            overlayView = view
            overlayParams = lp
            overlayWm = wm
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateOverlayBounds()
            }
            view.post {
                val dm = resources.displayMetrics
                val maxX = (dm.widthPixels - view.width).coerceAtLeast(0)
                val maxY = (dm.heightPixels - view.height).coerceAtLeast(0)
                if (savedPosition == null) {
                    lp.x = (dm.widthPixels - view.width - (10 * dm.density).toInt())
                        .coerceIn(0, maxX)
                    lp.y = ((dm.heightPixels - view.height) / 2).coerceIn(0, maxY)
                } else {
                    lp.x = lp.x.coerceIn(0, maxX)
                    lp.y = lp.y.coerceIn(0, maxY)
                }
                wm.updateViewLayout(view, lp)
                updateOverlayBounds()
                setupOverlayDrag(view, wm, lp)
            }
            val overlayTextWidth =
                (overlayWidth - (80 * resources.displayMetrics.density).toInt()).coerceAtLeast(160)
            view.findViewById<TextView>(R.id.overlay_title)?.maxWidth = overlayTextWidth
            view.findViewById<TextView>(R.id.overlay_detail)?.maxWidth = overlayTextWidth
            view.findViewById<View>(R.id.overlay_pause)?.setOnClickListener {
                val s = lastState ?: return@setOnClickListener
                if (s.sessionId.isBlank()) return@setOnClickListener
                if (s.paused) AgentController.resumeAgent(s.sessionId)
                else AgentController.pauseAgent(s.sessionId)
            }
            view.findViewById<View>(R.id.overlay_stop)?.setOnClickListener { btn ->
                val s = lastState ?: return@setOnClickListener
                if (s.sessionId.isBlank()) return@setOnClickListener
                // Agent 注入的点击刚到达时忽略, 避免穿透失败/时序问题时误停
                if (OverlayTouchGuard.gestureJustHappened()) return@setOnClickListener
                val now = SystemClock.elapsedRealtime()
                if (now > stopConfirmUntil) {
                    // 两步确认: 第一次点击只进入确认态, 防止误触和 Agent 点击
                    stopConfirmUntil = now + 3_000
                    (btn as? TextView)?.apply {
                        text = "确认"
                        textSize = 9f
                    }
                    scope.launch {
                        delay(3_100)
                        if (SystemClock.elapsedRealtime() >= stopConfirmUntil) {
                            resetStopButton(btn)
                        }
                    }
                    return@setOnClickListener
                }
                stopConfirmUntil = 0
                resetStopButton(btn)
                AgentController.stopAgent(s.sessionId, "悬浮窗")
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "添加悬浮窗失败: ${e.message}")
            DisplaySettings.setOverlayEnabled(this, false)
            removeOverlay()
        }
    }

    private fun resetStopButton(view: View?) {
        (view as? TextView)?.apply {
            text = "×"
            textSize = 14f
        }
    }

    /** 悬浮窗可触摸状态变更: 注入手势命中时临时加上 NOT_TOUCHABLE, 让点击穿透到下层 App */
    private fun applyOverlayTouchable(touchable: Boolean) {
        val wm = overlayWm ?: return
        val view = overlayView ?: return
        val lp = overlayParams ?: return
        val flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (flags == lp.flags) return
        lp.flags = flags
        try {
            wm.updateViewLayout(view, lp)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "更新悬浮窗触摸状态失败: ${e.message}")
        }
    }

    private fun updateOverlayBounds() {
        val view = overlayView
        if (view == null || view.width <= 0 || view.height <= 0) {
            OverlayTouchGuard.updateBounds(null)
            return
        }
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        OverlayTouchGuard.updateBounds(rect)
        if (rect != lastOverlayBounds) {
            lastOverlayBounds = Rect(rect)
            android.util.Log.i(TAG, "overlay bounds=$rect")
        }
    }

    /** 拖动悬浮窗: 按住标题/详情区域即可拖, 暂停/停止按钮仍可点击 */
    private fun setupOverlayDrag(
        view: View,
        wm: WindowManager,
        lp: WindowManager.LayoutParams
    ) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val dm = resources.displayMetrics
                        val maxX = (dm.widthPixels - view.width).coerceAtLeast(0)
                        val maxY = (dm.heightPixels - view.height).coerceAtLeast(0)
                        lp.x = (startX + dx).toInt().coerceIn(0, maxX)
                        lp.y = (startY + dy).toInt().coerceIn(0, maxY)
                        wm.updateViewLayout(view, lp)
                        updateOverlayBounds()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        DisplaySettings.setOverlayPosition(this, lp.x, lp.y)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun updateOverlay(s: AgentController.State) {
        val view = overlayView ?: return
        view.findViewById<TextView>(R.id.overlay_title)?.text =
            "Step ${s.stepNumber} · ${phaseLabel(s.phase)}"
        view.findViewById<TextView>(R.id.overlay_time)?.text =
            elapsedText(s.startedAtElapsed)
        view.findViewById<TextView>(R.id.overlay_pause)?.apply {
            text = if (s.paused) "▶" else "Ⅱ"
            contentDescription = if (s.paused) "继续" else "暂停"
            isEnabled = s.running
            alpha = if (s.running) 1f else 0.45f
        }
        view.findViewById<TextView>(R.id.overlay_stop)?.apply {
            isEnabled = s.running
            alpha = if (s.running) 1f else 0.45f
        }
        val age = eventAgeSec(s)
        val raw = s.lastEventText.ifBlank { s.currentTool }.ifBlank { "运行中" }.trim()
        val detailText = if (raw.startsWith("{") || raw.startsWith("[")) {
            s.currentTool.ifBlank { "执行中" }
        } else {
            raw.take(38)
        }
        val detail = buildString {
            append(detailText)
            if (age > 0) append(" · ").append(age).append("s前")
        }
        view.findViewById<TextView>(R.id.overlay_detail)?.text = detail
        view.findViewById<View>(R.id.overlay_dot)?.backgroundTintList =
            ColorStateList.valueOf(phaseColor(s.phase))
    }

    private fun startOverlayTicker() {
        if (overlayTicker?.isActive == true) return
        overlayTicker = scope.launch {
            while (isActive) {
                delay(1000)
                lastState?.let { if (overlayView != null) updateOverlay(it) }
            }
        }
    }

    private fun removeOverlay() {
        overlayTicker?.cancel()
        overlayTicker = null
        overlayRemoveJob?.cancel()
        overlayRemoveJob = null
        val view = overlayView ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (_: Exception) {
        }
        overlayView = null
        overlayParams = null
        overlayWm = null
        stopConfirmUntil = 0
        lastOverlayBounds = null
        OverlayTouchGuard.updateBounds(null)
        OverlayTouchGuard.setTouchable(true)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Agent 执行状态",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "显示 Agent 当前步骤和阶段"
                        setShowBadge(false)
                    }
                )
            }
        }
    }
}
