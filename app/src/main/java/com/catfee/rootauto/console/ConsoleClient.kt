package com.catfee.rootauto.console

import android.content.Context
import android.os.Build
import android.util.Log
import com.catfee.rootauto.BuildConfig
import com.catfee.rootauto.device.DeviceCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * ConsoleClient —— 内置 Catfee Web 控制台的设备客户端。
 *
 * 使用设备凭证连接：
 *   HTTP  /api/agent/enroll, /api/agent/events, /api/agent/heartbeat
 *   WS    /api/agent/_ws
 *
 * 不保存管理员 Token 或 JWT；Web 撤销设备后连接立即失效。
 */
object ConsoleClient {

    private const val TAG = "ConsoleClient"
    private const val WS_PATH = "/api/agent/_ws"
    private const val HEARTBEAT_MS = 30_000L

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventSequence = AtomicLong(0)
    private val finalStatus = ConcurrentHashMap<String, String>()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile private var started = false
    @Volatile private var authFailed = false
    private var webSocket: WebSocket? = null
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null

    private fun setStatus(context: Context, text: String) {
        _status.value = text
        ConsoleSettings.updateStatus(context.applicationContext, text)
    }

    private fun wsUrl(baseUrl: String): String {
        val base = ConsoleSettings.normalizeBase(baseUrl)
        val wsBase = when {
            base.startsWith("https://") -> base.replaceFirst("https://", "wss://")
            base.startsWith("http://") -> base.replaceFirst("http://", "ws://")
            else -> "ws://$base"
        }
        return wsBase.trimEnd('/') + WS_PATH
    }

    private fun capabilities(): JSONArray {
        val caps = DeviceCapabilities.current()
        val arr = JSONArray()
        if (caps.accessibility) arr.put("accessibility")
        if (caps.gestures) arr.put("gestures")
        if (caps.root) arr.put("root")
        if (caps.daemon) arr.put("daemon")
        if (caps.hasScreenshot) arr.put("screenshot")
        if (caps.hasScreenshot) arr.put("ocr")
        return arr
    }

    /** 启动设备长连接；未绑定或已禁用时不做任何事。 */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        val config = ConsoleSettings.load(context)
        if (!config.usable) {
            setStatus(context, "未绑定 Web 控制台")
            return
        }
        started = true
        authFailed = false
        val app = context.applicationContext
        connectJob = scope.launch { connectLoop(app) }
    }

    @Synchronized
    fun stop() {
        started = false
        authFailed = false
        heartbeatJob?.cancel()
        connectJob?.cancel()
        connectJob = null
        try {
            webSocket?.close(1000, "client stopped")
        } catch (_: Exception) {
        }
        webSocket = null
        _connected.value = false
    }

    private suspend fun connectLoop(context: Context) {
        var backoff = 1_000L
        while (scope.isActive && started && !authFailed) {
            val config = ConsoleSettings.load(context)
            if (!config.usable) {
                setStatus(context, "未绑定 Web 控制台")
                break
            }
            val closed = CompletableDeferred<Unit>()
            setStatus(context, "连接中…")
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    backoff = 1_000L
                    val hello = JSONObject()
                        .put("type", "hello")
                        .put("deviceId", config.deviceId)
                        .put("secret", config.deviceSecret)
                        .put("agentVersion", BuildConfig.VERSION_NAME)
                        .put("androidVersion", Build.VERSION.RELEASE)
                        .put("model", Build.MODEL)
                        .put("capabilities", capabilities())
                    webSocket.send(hello.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(context, text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _connected.value = false
                    setStatus(context, "连接失败: ${t.message ?: "网络错误"}")
                    if (!closed.isCompleted) closed.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _connected.value = false
                    if (code == 4001 || code == 4003) {
                        authFailed = true
                        setStatus(context, "设备认证失败或已被撤销，请重新绑定")
                    } else {
                        setStatus(context, "连接已断开 ($code)")
                    }
                    if (!closed.isCompleted) closed.complete(Unit)
                }
            }
            webSocket = http.newWebSocket(
                Request.Builder().url(wsUrl(config.baseUrl)).build(),
                listener
            )
            closed.await()
            webSocket = null
            heartbeatJob?.cancel()
            if (!started || authFailed) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun handleMessage(context: Context, text: String) {
        val message = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        when (message.optString("type")) {
            "hello_ack" -> {
                _connected.value = true
                setStatus(context, "已连接 Web 控制台")
                startHeartbeat(context)
            }
            "error" -> {
                val code = message.optString("code")
                if (code == "AUTH_FAILED") authFailed = true
                setStatus(context, "控制台错误: ${message.optString("message", code)}")
            }
            "command" -> {
                scope.launch {
                    RemoteTaskRunner.handle(context.applicationContext, message)
                }
            }
        }
    }

    private fun startHeartbeat(context: Context) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && started && _connected.value) {
                delay(HEARTBEAT_MS)
                send(JSONObject().put("type", "heartbeat"))
            }
        }
    }

    private fun send(json: JSONObject): Boolean = try {
        webSocket?.send(json.toString()) == true
    } catch (e: Exception) {
        Log.w(TAG, "WS 发送失败: ${e.message}")
        false
    }

    /**
     * 用 Web 设备页生成的一次性注册码完成绑定。
     */
    suspend fun enroll(
        context: Context,
        baseUrl: String,
        enrollmentCode: String,
        deviceName: String
    ): Result<ConsoleSettings.Config> = withContext(Dispatchers.IO) {
        val base = ConsoleSettings.normalizeBase(baseUrl)
        val code = enrollmentCode.trim()
        if (base.isBlank()) return@withContext Result.failure(Exception("请填写 Web 控制台地址"))
        if (code.isBlank()) return@withContext Result.failure(Exception("请填写设备注册码"))

        val caps = DeviceCapabilities.refresh(context, force = true)
        val body = JSONObject()
            .put("token", code)
            .put("name", deviceName.trim().ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}" })
            .put("model", Build.MODEL)
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("agentVersion", BuildConfig.VERSION_NAME)
            .put("capabilities", capabilities())
            .toString()

        try {
            val request = Request.Builder()
                .url("$base/api/agent/enroll")
                .post(body.toRequestBody(jsonMedia))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        Exception("注册接口返回异常 (HTTP ${response.code})")
                    )
                }
                if (!response.isSuccessful) {
                    val message = json.optString("statusMessage")
                        .ifBlank { json.optString("message") }
                        .ifBlank { json.optJSONObject("data")?.optString("message").orEmpty() }
                        .ifBlank { "HTTP ${response.code}" }
                    return@withContext Result.failure(Exception(message))
                }
                val deviceId = json.optString("deviceId")
                val deviceSecret = json.optString("deviceSecret")
                if (deviceId.isBlank() || deviceSecret.isBlank()) {
                    return@withContext Result.failure(Exception("注册接口没有返回设备凭证"))
                }
                val config = ConsoleSettings.Config(
                    enabled = true,
                    baseUrl = base,
                    deviceId = deviceId,
                    deviceSecret = deviceSecret,
                    deviceName = deviceName.trim(),
                    lastStatus = "已绑定 $deviceId"
                )
                // 重新绑定要覆盖旧的 authFailed/started 状态，否则撤销后重绑不会重连
                stop()
                ConsoleSettings.save(context, config)
                _status.value = config.lastStatus
                start(context)
                Result.success(config)
            }
        } catch (e: Exception) {
            Result.failure(Exception("绑定失败: ${e.message}"))
        }
    }

    suspend fun heartbeat(context: Context): Boolean = withContext(Dispatchers.IO) {
        val config = ConsoleSettings.load(context)
        if (!config.usable) return@withContext false
        try {
            val request = Request.Builder()
                .url("${ConsoleSettings.normalizeBase(config.baseUrl)}/api/agent/heartbeat")
                .header("x-device-id", config.deviceId)
                .header("x-device-secret", config.deviceSecret)
                .post("{}".toRequestBody(jsonMedia))
                .build()
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    setStatus(context, "已连接 Web 控制台")
                    true
                } else {
                    setStatus(context, "心跳失败 HTTP ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            setStatus(context, "心跳失败: ${e.message}")
            false
        }
    }

    /** 批量上报事件到 Web 控制台。 */
    suspend fun uploadEvents(context: Context, events: JSONArray): Boolean = withContext(Dispatchers.IO) {
        val config = ConsoleSettings.load(context)
        if (!config.usable || events.length() == 0) return@withContext false
        val body = JSONObject().put("events", events).toString()
        try {
            val request = Request.Builder()
                .url("${ConsoleSettings.normalizeBase(config.baseUrl)}/api/agent/events")
                .header("x-device-id", config.deviceId)
                .header("x-device-secret", config.deviceSecret)
                .post(body.toRequestBody(jsonMedia))
                .build()
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    setStatus(context, "日志上报正常")
                    true
                } else {
                    if (response.code == 401 || response.code == 403) {
                        authFailed = true
                        setStatus(context, "设备认证失败或已被撤销，请重新绑定")
                    } else {
                        setStatus(context, "日志上报失败 HTTP ${response.code}")
                    }
                    false
                }
            }
        } catch (e: Exception) {
            setStatus(context, "日志上报失败: ${e.message}")
            false
        }
    }

    suspend fun emitEvent(
        context: Context,
        taskId: String,
        type: String,
        level: String,
        payload: JSONObject
    ) {
        val event = JSONObject()
            .put("id", "and_" + UUID.randomUUID().toString().replace("-", "").take(16))
            .put("taskId", taskId)
            .put("runId", taskId)
            .put("sequence", eventSequence.incrementAndGet())
            .put("type", type)
            .put("level", normalizeLevel(level))
            .put("payload", payload)
            .put("occurredAt", java.time.Instant.now().toString())
        uploadEvents(context, JSONArray().put(event))
    }

    fun sendCommandAck(
        context: Context,
        commandId: String,
        taskId: String,
        status: String,
        detail: String
    ) {
        send(
            JSONObject()
                .put("type", "command_ack")
                .put("commandId", commandId)
                .put("taskId", taskId)
                .put("status", status)
                .put("detail", detail)
        )
    }

    fun markFinalStatus(taskId: String, eventType: String) {
        if (taskId.isNotBlank()) finalStatus[taskId] = eventType
    }

    fun consumeFinalStatus(taskId: String): String? = finalStatus.remove(taskId)

    fun normalizeLevel(level: String): String = when (level.lowercase()) {
        "debug", "info", "warn", "error", "security" -> level.lowercase()
        "success" -> "info"
        else -> "info"
    }

    fun nextSequence(): Long = eventSequence.incrementAndGet()
}
