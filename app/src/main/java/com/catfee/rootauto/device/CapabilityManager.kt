package com.catfee.rootauto.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.catfee.rootauto.a11y.UiObserverService
import kotlinx.coroutines.delay

/** Agent 运行需要的系统能力; Root 只是增强项, 不是运行前提。 */
enum class Capability(val label: String) {
    ACCESSIBILITY("无障碍"),
    OVERLAY("悬浮窗"),
    NOTIFICATION("通知"),
    BATTERY("后台运行"),
    SCREENSHOT("截图"),
    ROOT("Root");

    companion object {
        fun parse(raw: String): Capability? {
            val v = raw.trim().lowercase()
            return when (v) {
                "accessibility", "a11y", "无障碍" -> ACCESSIBILITY
                "overlay", "float", "悬浮窗", "悬浮" -> OVERLAY
                "notification", "notify", "通知" -> NOTIFICATION
                "battery", "后台", "电池" -> BATTERY
                "screenshot", "capture", "截图" -> SCREENSHOT
                "root", "su" -> ROOT
                else -> null
            }
        }
    }
}

/**
 * 能力开启引导。
 *
 * 目标: 任务遇到能力缺失时, 先尝试自动开启或打开对应系统设置并等待,
 * 而不是直接结束任务。
 *
 * - 无障碍: Root 设备自动写回服务组件; 非 Root 打开系统无障碍详情页并轮询
 * - 悬浮窗/通知/后台: 打开对应系统设置并轮询
 * - Root: 无法由 App 自行开启, 只返回状态, 不阻塞任务
 */
object CapabilityManager {

    private const val POLL_MS = 600L

    fun isEnabled(context: Context, capability: Capability): Boolean = when (capability) {
        Capability.ACCESSIBILITY -> {
            DeviceCapabilities.refreshAccessibilityOnly()
            UiObserverService.instance != null
        }
        Capability.OVERLAY -> try {
            Settings.canDrawOverlays(context.applicationContext)
        } catch (_: Exception) {
            false
        }
        Capability.NOTIFICATION -> try {
            NotificationManagerCompat.from(context.applicationContext).areNotificationsEnabled()
        } catch (_: Exception) {
            false
        }
        Capability.BATTERY -> try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
        Capability.SCREENSHOT -> DeviceCapabilities.current().hasScreenshot
        Capability.ROOT -> DeviceCapabilities.current().root
    }

    fun openSettings(context: Context, capability: Capability): Boolean {
        val app = context.applicationContext
        val intents = when (capability) {
            Capability.ACCESSIBILITY -> listOf(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                    // 直接跳到 CatfeeAgents 的无障碍详情页; 部分 ROM 不认这个 extra, 会落到列表
                    putExtra(
                        Intent.EXTRA_COMPONENT_NAME,
                        ComponentName(app, UiObserverService::class.java).flattenToString()
                    )
                },
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
            Capability.OVERLAY -> listOf(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${app.packageName}")
                ),
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            )
            Capability.NOTIFICATION -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
                },
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${app.packageName}")
                }
            )
            Capability.BATTERY -> listOf(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${app.packageName}")
                ),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
            Capability.SCREENSHOT -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Capability.ROOT -> emptyList()
        }
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
                return true
            } catch (_: Exception) {
                // 尝试下一个 fallback
            }
        }
        return false
    }

    /** 系统设置里是否已勾选本应用的无障碍服务（不代表服务已连接） */
    private fun accessibilityInSettings(context: Context): Boolean = try {
        val resolver = context.contentResolver
        val enabled = Settings.Secure.getInt(
            resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
        ) == 1
        val services = Settings.Secure.getString(
            resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val component = "${context.packageName}/${UiObserverService::class.java.name}"
        enabled && services.contains(component)
    } catch (_: Exception) {
        false
    }

    /**
     * 尝试开启能力并等待用户操作。
     *
     * @param timeoutMs 等待上限; Root 能力直接返回当前状态, 不等待
     * @param onStatus 过程提示, 用于写入任务时间线/界面 hint
     * @return 最终是否可用
     */
    suspend fun ensure(
        context: Context,
        capability: Capability,
        timeoutMs: Long = 60_000,
        onStatus: suspend (String) -> Unit = {}
    ): Boolean {
        if (capability == Capability.ROOT) {
            val root = DeviceCapabilities.current().root
            onStatus(if (root) "Root 已授权" else "Root 未授权；继续使用无障碍模式")
            return root
        }
        if (isEnabled(context, capability)) return true

        // 无障碍先尝试 Root 静默恢复, 失败再打开设置
        if (capability == Capability.ACCESSIBILITY) {
            val caps = DeviceCapabilities.refresh(context, force = true)
            if (caps.root) {
                onStatus("正在通过 Root 恢复无障碍服务…")
                if (DeviceCapabilities.ensureAccessibility(context)) {
                    onStatus("无障碍已恢复")
                    return true
                }
            }
        }

        val opened = openSettings(context, capability)
        val listedButOffline = capability == Capability.ACCESSIBILITY &&
            accessibilityInSettings(context)
        onStatus(
            when {
                listedButOffline ->
                    "无障碍已勾选但服务未连接：请在设置里关闭再重新开启 CatfeeAgents，任务会自动继续"
                opened ->
                    "已打开${capability.label}设置，请在系统页面开启后返回；任务会自动继续"
                else ->
                    "无法自动打开${capability.label}设置，请手动开启"
            }
        )

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            delay(POLL_MS)
            if (isEnabled(context, capability)) {
                onStatus("${capability.label}已开启，继续任务")
                return true
            }
        }
        onStatus("等待${capability.label}开启超时（${timeoutMs / 1000}s）")
        return isEnabled(context, capability)
    }

    /** 给模型/界面看的简短能力摘要 */
    fun summary(context: Context): String = buildString {
        append("无障碍=").append(if (isEnabled(context, Capability.ACCESSIBILITY)) "开" else "关")
        append("; Root=").append(if (isEnabled(context, Capability.ROOT)) "有(可选)" else "无(可选)")
        append("; 悬浮窗=").append(if (isEnabled(context, Capability.OVERLAY)) "开" else "关")
        append("; 通知=").append(if (isEnabled(context, Capability.NOTIFICATION)) "开" else "关")
    }
}
