package com.catfee.rootauto.console

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.catfee.rootauto.R

/**
 * 保持与内置 Web 控制台的长连接。
 * 远程任务、日志补传、心跳都依赖它；退出 App 后仍然工作。
 */
class RemoteConsoleService : Service() {

    companion object {
        private const val TAG = "RemoteConsoleService"
        private const val CHANNEL_ID = "catfee_console"
        private const val NOTIFICATION_ID = 1002

        fun ensureStarted(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, RemoteConsoleService::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "启动控制台服务失败: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, RemoteConsoleService::class.java)
                )
            } catch (_: Exception) {
            }
            ConsoleClient.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        ConsoleClient.start(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ConsoleClient.start(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        ConsoleClient.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Catfee 控制台连接",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持设备与 Catfee Web 控制台的长连接"
            }
        )
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Catfee 控制台")
            .setContentText("设备连接服务运行中")
            .setOngoing(true)
            .build()
    }
}
