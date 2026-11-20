package com.catfee.rootauto.console

import android.content.Context
import android.util.Log
import com.catfee.rootauto.agent.AgentController
import com.catfee.rootauto.agent.AppMemory
import com.catfee.rootauto.device.Capability
import com.catfee.rootauto.device.CapabilityManager
import com.catfee.rootauto.device.DeviceCapabilities
import com.catfee.rootauto.llm.LlmSettings
import com.catfee.rootauto.session.SessionManager
import org.json.JSONArray
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
    /** commandId 去重: WebSocket 重连或 App 重启后服务端都可能重发 */
    private val processedCommands = ConcurrentHashMap<String, Boolean>()
    /** 正在处理中的命令, 防止同一条命令并发执行 */
    private val inFlightCommands = ConcurrentHashMap<String, Boolean>()
    private const val CMD_PREF = "catfee_remote_commands"
    private const val CMD_KEY = "processed_ids"
    private const val CMD_KEEP = 200
    private val cmdLock = Any()

    suspend fun handle(context: Context, message: JSONObject) {
        if (message.optString("type") != "command") return
        val commandId = message.optString("commandId")
        val rawTaskId = message.optString("taskId")
        val commandType = message.optString("commandType")
        val payload = message.optJSONObject("payload") ?: JSONObject()
        if (commandId.isBlank()) return
        // 记忆编辑/删除不属于任务, Web 端用 __memory__ 作为占位 taskId
        if (rawTaskId.isBlank() && !commandType.startsWith("MEMORY_")) return
        val taskId = rawTaskId.ifBlank { "__memory__" }

        // 记忆编辑也会进入模型上下文, 同样要求设备端显式开启远程控制。
        if (commandType.startsWith("MEMORY_") &&
            !ConsoleSettings.load(context).remoteAutoApprove
        ) {
            ConsoleClient.sendCommandAck(
                context, commandId, taskId, "rejected", "remote control disabled on device"
            )
            return
        }

        // 已成功执行过的命令直接 ack; 被拒绝/失败的命令不写入去重表,
        // 这样用户开启远程控制后点“重试”仍然能重新执行。
        if (isCommandProcessed(context, commandId) ||
            inFlightCommands.putIfAbsent(commandId, true) != null
        ) {
            ConsoleClient.sendCommandAck(context, commandId, taskId, "applied", "duplicate ignored")
            return
        }

        var accepted = false
        try {
            accepted = when (commandType) {
                "TASK_ASSIGN" -> assign(context, commandId, taskId, payload)
                "TASK_PAUSE" -> control(context, commandId, taskId, "pause")
                "TASK_RESUME" -> control(context, commandId, taskId, "resume")
                "TASK_STOP" -> control(context, commandId, taskId, "stop")
                "MEMORY_UPDATE" -> memoryUpdate(context, commandId, taskId, payload)
                "MEMORY_DELETE" -> memoryDelete(context, commandId, taskId, payload)
                else -> {
                    ConsoleClient.sendCommandAck(
                        context, commandId, taskId, "rejected", "unsupported command: $commandType"
                    )
                    false
                }
            }
        } finally {
            if (accepted) markCommandProcessed(context, commandId)
            inFlightCommands.remove(commandId)
        }
    }

    private suspend fun memoryUpdate(
        context: Context,
        commandId: String,
        taskId: String,
        payload: JSONObject
    ): Boolean {
        val pkg = payload.optString("package")
        val entry = payload.optJSONObject("entry")
        if (pkg.isBlank() || entry == null) {
            ConsoleClient.sendCommandAck(context, commandId, taskId, "rejected", "package/entry 为空")
            return false
        }
        val ok = AppMemory.applyRemoteEntry(context, pkg, entry)
        ConsoleClient.sendCommandAck(
            context, commandId, taskId,
            if (ok) "applied" else "rejected",
            if (ok) "memory updated" else "entry invalid"
        )
        return ok
    }

    private suspend fun memoryDelete(
        context: Context,
        commandId: String,
        taskId: String,
        payload: JSONObject
    ): Boolean {
        val pkg = payload.optString("package")
        val entryId = payload.optString("entryId")
        if (pkg.isBlank() || entryId.isBlank()) {
            ConsoleClient.sendCommandAck(context, commandId, taskId, "rejected", "package/entryId 为空")
            return false
        }
        val ok = AppMemory.removeEntry(context, pkg, entryId)
        ConsoleClient.sendCommandAck(
            context, commandId, taskId,
            if (ok) "applied" else "rejected",
            if (ok) "memory deleted" else "entry not found"
        )
        return ok
    }

    private suspend fun assign(
        context: Context,
        commandId: String,
        taskId: String,
        payload: JSONObject
    ): Boolean {
        val provider = LlmSettings.active(context)
        if (provider == null || !provider.usable) {
            ConsoleClient.sendCommandAck(context, commandId, taskId, "rejected", "LLM 配置不完整")
            ConsoleClient.emitEvent(
                context, taskId, "task.failed", "error",
                JSONObject().put("summary", "设备 LLM 配置不完整").put("error", "LLM 配置不完整")
            )
            return false
        }

        val goal = payload.optString("goal").ifBlank { payload.optString("title") }
        if (goal.isBlank()) {
            ConsoleClient.sendCommandAck(context, commandId, taskId, "rejected", "任务目标为空")
            return false
        }

        // 设备端必须显式开启远程控制; 默认拒绝, 避免控制台账号被盗后静默接管手机。
        if (!ConsoleSettings.load(context).remoteAutoApprove) {
            ConsoleClient.sendCommandAck(
                context, commandId, taskId, "rejected", "remote control disabled on device"
            )
            ConsoleClient.emitEvent(
                context, taskId, "task.failed", "error",
                JSONObject()
                    .put("summary", "设备未允许远程控制")
                    .put(
                        "error",
                        "请在手机 App 的 设置 → Web 控制台 中开启「允许远程任务直接操作手机」后重新下发"
                    )
            )
            return false
        }

        val policy = payload.optJSONObject("policy") ?: JSONObject()
        val allowRoot = policy.optBoolean("allowRoot", false)
        val allowScreenshot = policy.optBoolean("allowScreenshot", true)

        // 无障碍被系统重置时不要直接失败: 在策略允许时先尝试 Root 恢复,
        // 否则只打开系统设置等待用户开启。远程任务无人值守时最多等 90 秒。
        val capsBefore = DeviceCapabilities.refresh(
            context, force = true, probeDaemon = allowRoot
        )
        if (!capsBefore.accessibility) {
            ConsoleClient.emitEvent(
                context, taskId, "task.notice", "warn",
                JSONObject().put("summary", "无障碍未开启，正在尝试自动开启并等待…")
            )
            val ready = CapabilityManager.ensure(
                context, Capability.ACCESSIBILITY, 90_000, allowRoot = allowRoot
            ) { msg ->
                ConsoleClient.emitEvent(
                    context, taskId, "task.notice", "info",
                    JSONObject().put("summary", msg)
                )
            }
            if (!ready) {
                ConsoleClient.sendCommandAck(
                    context, commandId, taskId, "rejected", "accessibility not enabled"
                )
                ConsoleClient.emitEvent(
                    context, taskId, "task.failed", "error",
                    JSONObject()
                        .put("summary", "无障碍未开启，已等待 90 秒")
                        .put(
                            "error",
                            "请到系统设置-无障碍开启 CatfeeAgents 后重新下发；" +
                                "Root 设备可尝试通过控制台重新连接触发自动恢复"
                        )
                )
                return false
            }
            ConsoleClient.emitEvent(
                context, taskId, "task.notice", "info",
                JSONObject().put("summary", "无障碍已开启，任务继续")
            )
        }

        // -1 = 不限制时长, 由 Web 端手动暂停/停止
        val timeoutMs = policy.optLong("timeoutMs", -1L)
        val sessionId = "remote_$taskId"
        sessions[taskId] = sessionId
        // 在设备本地落一条 WEB 会话, 任务结束后可在 App 历史里回看步骤和结果。
        SessionManager.ensureRemoteSession(
            context,
            sessionId,
            payload.optString("title"),
            goal
        )

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
            attachments = emptyList(),
            traceTaskId = taskId,
            allowRootTools = allowRoot,
            allowScreenshot = allowScreenshot,
            timeoutMs = timeoutMs,
            // 到这里说明设备已显式开启远程控制, 不再逐任务弹确认
            phoneAllowed = true
        )
        if (!ok) {
            SessionManager.setResult(context, sessionId, "设备忙碌，任务未启动")
            ConsoleClient.emitEvent(
                context, taskId, "task.failed", "error",
                JSONObject().put("summary", "设备忙碌，任务未启动").put("error", "AgentController 启动失败")
            )
            Log.w(TAG, "remote task start failed task=$taskId")
        }
        return ok
    }

    /** 只对“真正执行成功”的 commandId 做持久化去重, 失败/被拒绝的命令允许重试。 */
    private fun isCommandProcessed(context: Context, commandId: String): Boolean {
        if (processedCommands.containsKey(commandId)) return true
        synchronized(cmdLock) {
            val prefs = context.applicationContext
                .getSharedPreferences(CMD_PREF, Context.MODE_PRIVATE)
            val arr = try {
                JSONArray(prefs.getString(CMD_KEY, "[]"))
            } catch (_: Exception) {
                JSONArray()
            }
            for (i in 0 until arr.length()) {
                if (arr.optString(i) == commandId) return true
            }
            return false
        }
    }

    private fun markCommandProcessed(context: Context, commandId: String) {
        processedCommands[commandId] = true
        synchronized(cmdLock) {
            val prefs = context.applicationContext
                .getSharedPreferences(CMD_PREF, Context.MODE_PRIVATE)
            val arr = try {
                JSONArray(prefs.getString(CMD_KEY, "[]"))
            } catch (_: Exception) {
                JSONArray()
            }
            val next = JSONArray().put(commandId)
            val start = maxOf(0, arr.length() - (CMD_KEEP - 1))
            for (i in start until arr.length()) {
                val id = arr.optString(i)
                if (id != commandId) next.put(id)
            }
            prefs.edit().putString(CMD_KEY, next.toString()).apply()
        }
    }

    private suspend fun control(
        context: Context,
        commandId: String,
        taskId: String,
        action: String
    ): Boolean {
        val sessionId = sessions[taskId]
        if (sessionId == null) {
            ConsoleClient.sendCommandAck(context, commandId, taskId, "rejected", "task not running on device")
            return false
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
        if (!ok) return false

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
        return true
    }
}
