package com.catfee.rootauto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.catfee.rootauto.MainActivity
import com.catfee.rootauto.agent.AgentController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * AgentStatusService —— 前台服务 + 通知栏实时步骤
 *
 * 功能描述:
 *   Agent 在后台操作其他 App 时, 主界面不可见; 本服务常驻通知栏,
 *   把「第几步 / 当前工具 / 最近几条步骤」实时显示出来, 点通知可回到 App 看完整时间线。
 *
 * 使用场景:
 *   AgentController.start() 时拉起; 任务结束或用户停止后自动收起。
 *
 * 修改时间: 2026-11-03
 */
class AgentStatusService : Service() {

    companion object {
        private const val CHANNEL_ID = "catfee_agent_status"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "AgentStatusService"

        /** 启动前台服务 (从 Activity 的前台上下文调用) */
        fun start(context: Context) {
            val intent = Intent(context, AgentStatusService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wasRunning = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 必须先 startForeground, 否则 Android 8+ 会抛异常
        startForeground(NOTIFICATION_ID, buildNotification("Agent 准备中", "等待第一步...", emptyList(), true))
        scope.launch {
            AgentController.state.collectLatest { s ->
                if (s.running) {
                    wasRunning = true
                    updateNotification(s)
                } else if (wasRunning) {
                    // 任务结束: 更新为最终状态, 保留通知让用户点回 App 看结果
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(
                        NOTIFICATION_ID,
                        buildNotification(
                            "Agent 任务结束",
                            s.result?.take(120) ?: "已停止",
                            s.steps.takeLast(6),
                            false
                        )
                    )
                    stopForeground(STOP_FOREGROUND_DETACH)
                    delay(1500)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(s: AgentController.State) {
        val title = "Agent 运行中 · 第 ${s.steps.size} 步"
        val current = s.steps.lastOrNull()
        val text = when {
            current == null -> "正在准备..."
            current.tool.isNotBlank() -> "${current.tool}: ${current.text.take(80)}"
            else -> current.text.take(100)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, text, s.steps.takeLast(6), true))
    }

    private fun buildNotification(
        title: String,
        text: String,
        steps: List<AgentController.Step>,
        ongoing: Boolean
    ): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val big = if (steps.isEmpty()) text else buildString {
            steps.forEach { st ->
                val tag = when (st.type) {
                    "think" -> "思考"
                    "tool" -> "动作"
                    "result" -> "结果"
                    "done" -> "完成"
                    "error" -> "失败"
                    "todo" -> "计划"
                    "system" -> "系统"
                    else -> st.type
                }
                append("#").append(st.seq).append(' ').append(tag)
                if (st.tool.isNotBlank()) append(' ').append(st.tool)
                append(": ").append(st.text.take(160)).append('\n')
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setContentIntent(pi)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Agent 执行状态",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "显示 Agent 当前步骤"
                        setShowBadge(false)
                    }
                )
            }
        }
    }
}
