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
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
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
        private const val ACTION_REFRESH_OVERLAY = "com.catfee.rootauto.action.REFRESH_OVERLAY"

        @Volatile private var alive = false

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

        private fun phaseLabel(phase: String): String = when (phase) {
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
    private var wasRunning = false
    private var lastState: AgentController.State? = null

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayTicker: Job? = null
    private var overlayRemoveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
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
                        s.running, s.phase, s.stepNumber, s.pendingConfirm?.tool,
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
        when (intent?.action) {
            ACTION_STOP_AGENT -> AgentController.stopAgent()
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
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, AgentStatusService::class.java).setAction(ACTION_STOP_AGENT),
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
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
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
            val statusBar = resources.getIdentifier("status_bar_height", "dimen", "android")
                .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: 0
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (12 * resources.displayMetrics.density).toInt()
                // 避开顶部标题栏/菜单按钮, 固定在其下方
                y = statusBar + (64 * resources.displayMetrics.density).toInt()
                setTitle("Catfee Agent Status")
            }
            wm.addView(view, lp)
            overlayView = view
            overlayParams = lp
            view.findViewById<TextView>(R.id.overlay_title)?.maxWidth =
                (200 * resources.displayMetrics.density).toInt()
            view.findViewById<TextView>(R.id.overlay_detail)?.maxWidth =
                (200 * resources.displayMetrics.density).toInt()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "添加悬浮窗失败: ${e.message}")
            DisplaySettings.setOverlayEnabled(this, false)
            removeOverlay()
        }
    }

    private fun updateOverlay(s: AgentController.State) {
        val view = overlayView ?: return
        view.findViewById<TextView>(R.id.overlay_title)?.text =
            "Step ${s.stepNumber} · ${phaseLabel(s.phase)} · ${elapsedText(s.startedAtElapsed)}"
        val age = eventAgeSec(s)
        val detail = buildString {
            append(s.lastEventText.ifBlank { s.currentTool }.ifBlank { "运行中" }.take(60))
            if (age > 0) append(" · ").append(age).append("s前")
            if (s.phase == "waiting_llm" && age > 60) append(" · 等待较久")
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
