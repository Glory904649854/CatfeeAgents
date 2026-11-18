package com.catfee.rootauto.console

import android.content.Context
import android.util.Log
import com.catfee.rootauto.agent.AgentController
import com.catfee.rootauto.device.DeviceCapabilities
import com.catfee.rootauto.llm.LlmSettings
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 处理 Web 控制台下发的任务与控制指令。
 * 所有任务仍通过 AgentController 执行；Web 只表达意图。
 */
object RemoteTaskRunner {

    private const val TAG = "RemoteTaskRunner"

    /** taskId -> 本地 AgentController sessionId */
    private val sessions = ConcurrentHashMap<String, String>()
    /** commandId 去重: WebSocket 重连时服务端可能重发 */
    private val processedCommands = ConcurrentHashMap<String, Boolean>()

    suspend fun handle(context: Context, message: JSONObject) {
        if (message.optString("type") != "command") return
        val commandId = message.optString("commandId")
        val taskId = message.optString("taskId")
        val commandType = message.optString("commandType")
        val payload = message.optJSONObject("payload") ?: JSONObject()
        if (commandId.isBlank() || taskId.isBlank()) return

        if (processedCommands.putIfAbsent(commandId, true) != null) {
            ConsoleClient.sendCommandAck(context, commandId, taskId, "applied", "duplicate ignored")
            return
        }

        when (commandType) {
            "TASK_ASSIGN" -> assign(context, commandId, taskId, payload)
            "TASK_PAUSE" -> control(context, commandId, taskId, "pause")
            "TASK_RESUME" -> control(context, commandId, taskId, "resume")
            "TASK_STOP" -> control(context, commandId, taskId, "stop")
            else -> ConsoleClient.sendCommandAck(
                context, commandId, taskId, "rejected", "unsupported command: $commandType"
            )
        }
    }

    private suspend fun assign(
        context: Context,
        commandId: String,
        taskId: String,
        payload: JSONObject
    ) {
        val provider = LlmSettings.active(context)
        if (provider == null || !provider.usable) {
            ConsoleClient.sendCommandAck(context, commandId, taskId, "rejected", "LLM 配置不完整")
            ConsoleClient.emitEvent(
                context, taskId, "task.failed", "error",
                JSONObject().put("summary", "设备 LLM 配置不完整").put("error", "LLM 配置不完整")
            )
            return
        }

        val goal = payload.optString("goal").ifBlank { payload.optString("title") }
        if (goal.isBlank()) {
            ConsoleClient.sendCommandAck(context, commandId, taskId, "rejected", "任务目标为空")
            return
        }

        // Root 设备覆盖安装/强停后无障碍可能被系统重置。
        // 先尝试恢复, 否则 input_text / observe 等工具会被能力过滤掉, Agent 只能盲点。
        val capsBefore = DeviceCapabilities.refresh(context, force = true)
        val accessibilityReady = DeviceCapabilities.ensureAccessibility(context)
        if (!capsBefore.accessibility && accessibilityReady) {
            ConsoleClient.emitEvent(
                context, taskId, "task.notice", "info",
                JSONObject().put("summary", "已通过 Root 自动开启无障碍服务，文字输入可用")
            )
        } else if (!accessibilityReady) {
            ConsoleClient.emitEvent(
                context, taskId, "task.notice", "warn",
                JSONObject().put("summary", "无障碍未开启：文字输入和读屏工具不可用")
            )
        }

        val policy = payload.optJSONObject("policy") ?: JSONObject()
        val allowRoot = policy.optBoolean("allowRoot", false)
        val allowScreenshot = policy.optBoolean("allowScreenshot", true)
        // -1 = 不限制时长, 由 Web 端手动暂停/停止
        val timeoutMs = policy.optLong("timeoutMs", -1L)
        val sessionId = "remote_$taskId"
        sessions[taskId] = sessionId

        ConsoleClient.sendCommandAck(context, commandId, taskId, "accepted", "accepted")
        ConsoleClient.emitEvent(
            context, taskId, "task.accepted", "info",
            JSONObject().put("summary", "设备已接收任务").put("title", payload.optString("title"))
        )

        val ok = AgentController.start(
            context = context.applicationContext,
            provider = provider,
            goal = goal,
            sessionId = sessionId,
            priorContext = "",
            phoneAllowed = true,
            attachments = emptyList(),
            traceTaskId = taskId,
            allowRootTools = allowRoot,
            allowScreenshot = allowScreenshot,
            timeoutMs = timeoutMs
        )
        if (!ok) {
            ConsoleClient.emitEvent(
                context, taskId, "task.failed", "error",
                JSONObject().put("summary", "设备忙碌，任务未启动").put("error", "AgentController 启动失败")
            )
            Log.w(TAG, "remote task start failed task=$taskId")
        }
    }

    private suspend fun control(
        context: Context,
        commandId: String,
        taskId: String,
        action: String
    ) {
        val sessionId = sessions[taskId]
        if (sessionId == null) {
            ConsoleClient.sendCommandAck(context, commandId, taskId, "rejected", "task not running on device")
            return
        }

        val ok = when (action) {
            "pause" -> AgentController.pause(sessionId)
            "resume" -> AgentController.resume(sessionId)
            "stop" -> {
                ConsoleClient.markFinalStatus(taskId, "task.stopped")
                AgentController.stop(sessionId, "Web 控制台")
                true
            }
            else -> false
        }

        ConsoleClient.sendCommandAck(
            context, commandId, taskId, if (ok) "applied" else "rejected",
            if (ok) "applied" else "state does not allow $action"
        )
        if (!ok) return

        val type = when (action) {
            "pause" -> "task.paused"
            "resume" -> "task.resumed"
            else -> "task.stopped"
        }
        val summary = when (action) {
            "pause" -> "控制台暂停任务"
            "resume" -> "控制台继续任务"
            else -> "控制台停止任务"
        }
        ConsoleClient.emitEvent(
            context, taskId, type, "info", JSONObject().put("summary", summary)
        )
    }
}
