package com.catfee.rootauto.root

import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import com.catfee.rootauto.a11y.UiObserverService
import com.catfee.rootauto.device.DeviceCapabilities
import com.catfee.rootauto.device.ScreenshotSource
import com.catfee.rootauto.llm.LlmClient
import com.catfee.rootauto.llm.LlmSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ScreenVision —— 截图降级识别
 *
 * 功能描述:
 *   当无障碍读不到界面内容时 (纯 WebView 画布、Surface 播放器、自绘控件等),
 *   走「root 截图 → 转 JPEG → 交给视觉模型」这条降级通路。
 *
 * 为什么需要:
 *   无障碍是首选 (20~60ms, 几乎零成本); 但视频播放器/部分 H5 页面节点为空,
 *   此时只能靠视觉。截图的成本高 (约 1~3s + token), 所以严格作为兜底。
 *
 * 参数说明:
 *   见各方法注释。
 *
 * 返回值说明:
 *   VisionResult 含文字描述、失败原因与耗时。
 *
 * 使用场景:
 *   Agent 的 observe 工具在无障碍快照为空时自动调用;
 *   调试面板的「截图识别」按钮。
 *
 * 修改时间: 2026-11-03
 */
object ScreenVision {

    private const val TAG = "ScreenVision"

    /** 截图原始文件落盘路径: 按 App UID 隔离, daemon 写入后 chown 给本 App。 */
    private fun shotPath(): String =
        "/data/local/tmp/catfee_shot_${android.os.Process.myUid()}.raw"

    /** Web 任务策略: 是否允许截图/视觉; 由 AgentTools.execute 在每次运行前后设置。 */
    @Volatile
    private var captureAllowed = true

    /** Web 任务禁止 Root 时, 截图只能走无障碍系统截图, 不能走 daemon。 */
    @Volatile
    private var rootCaptureAllowed = true

    fun setCaptureAllowed(allowed: Boolean) {
        captureAllowed = allowed
    }

    fun setRootCaptureAllowed(allowed: Boolean) {
        rootCaptureAllowed = allowed
    }

    /** 送模型前的最长边, 越大越清晰但 token 越多; 1080 宽已足够读清单行文字 */
    private const val MAX_EDGE = 2400

    /** JPEG 质量, 85 在文字场景下基本无肉眼损失 */
    private const val JPEG_QUALITY = 85

    /**
     * 识别结果
     * @param ok     是否成功
     * @param text   模型对屏幕内容的描述
     * @param error  失败原因
     * @param elapsedMs 总耗时
     */
    data class VisionResult(
        val ok: Boolean,
        val text: String = "",
        val error: String = "",
        val elapsedMs: Long = 0
    )

    /** 送给视觉模型的截图: base64 + 实际图片尺寸 */
    data class JpegFrame(
        val base64: String,
        val width: Int,
        val height: Int,
        val error: String = ""
    )

    /**
     * 功能: 截屏并转成可直接送模型的 base64 JPEG
     * 返回: JpegFrame, 含缩放后的图片宽高 (用于给视觉模型说明坐标空间)
     * 使用场景: 视觉兜底、失败存证
     */
    suspend fun captureJpegFrame(): JpegFrame = withContext(Dispatchers.IO) {
        try {
            // 非 Root 下用 DisplayManager 校准; Root 下保留 dumpsys 校准。
            ScreenGeometry.refreshRotation()
        } catch (e: Exception) {
            Log.w(TAG, "刷新屏幕方向失败: ${e.message}")
        }
        val (bmp, err) = captureBitmap()
        if (bmp == null) return@withContext JpegFrame("", 0, 0, err)
        try {
            val scaled = scaleDown(bmp)
            val w = scaled.width
            val h = scaled.height
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            JpegFrame(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), w, h)
        } catch (e: Exception) {
            Log.e(TAG, "截图转码失败: ${e.message}")
            JpegFrame("", 0, 0, e.message ?: "未知错误")
        }
    }

    /**
     * 功能: 截屏并直接返回原始 Bitmap (不缩放、不压缩)
     * 返回: (Bitmap, 失败原因); 失败时 Bitmap 为 null
     * 使用场景: 本地 OCR (ML Kit) 需要原图坐标, 缩放会让坐标偏移
     * 注意: 调用方负责 recycle 返回的 Bitmap
     */
    suspend fun captureBitmap(): Pair<Bitmap?, String> = withContext(Dispatchers.IO) {
        if (!captureAllowed) return@withContext null to "当前任务未授权截图/视觉能力"
        // 截图前临时隐藏本应用悬浮状态条, 避免它进入 OCR/视觉结果干扰模型
        val hidden = com.catfee.rootauto.service.AgentStatusService.hideOverlayForCapture()
        try {
            if (hidden) delay(90)
            captureRaw()
        } finally {
            if (hidden) com.catfee.rootauto.service.AgentStatusService.showOverlayAfterCapture()
        }
    }

    private suspend fun captureRaw(): Pair<Bitmap?, String> = withContext(Dispatchers.IO) {
        val caps = DeviceCapabilities.current()

        // 1) Root daemon 是首选: 更快, 且不受无障碍截图频率限制。
        if (caps.daemon && rootCaptureAllowed) {
            val (bmp, err) = captureRootBitmap()
            if (bmp != null) return@withContext bmp to ""
            Log.w(TAG, "Root 截图失败, 尝试无障碍截图: $err")
        }

        // 2) Android 11+ 无障碍系统截图。
        if (caps.screenshot == ScreenshotSource.ACCESSIBILITY ||
            (caps.accessibility && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        ) {
            val bmp = UiObserverService.instance?.takeScreenshotBitmap()
            if (bmp != null) return@withContext bmp to ""
            return@withContext null to
                "无障碍截图失败（系统限流、页面禁止截图或服务刚重连）"
        }

        null to "当前模式不支持截图：未 Root，且无障碍截图不可用（需要 Android 11+）"
    }

    /** Root daemon 截图: 读取 /data/local/tmp 下的 raw RGBA 文件 */
    private suspend fun captureRootBitmap(): Pair<Bitmap?, String> = withContext(Dispatchers.IO) {
        val path = shotPath()
        try {
            val (shotOk, w, h) = DaemonClient.shot(path)
            if (!shotOk || w <= 0 || h <= 0) return@withContext null to "截图失败"
            val f = File(path)
            if (!f.exists() || f.length() <= 0) {
                return@withContext null to "截图文件不存在或不可读"
            }
            val bytes = try {
                f.readBytes()
            } finally {
                // 截图属于敏感数据, 读完立即让 daemon 删除; 不留在全局目录
                DaemonClient.removeShot(path)
            }
            // 头部 12 或 16 字节 (Android 12 起含 colorspace), 按总长度反推
            val expect12 = 12L + w.toLong() * h * 4
            val expect16 = 16L + w.toLong() * h * 4
            val headerLen = when (bytes.size.toLong()) {
                expect12 -> 12
                expect16 -> 16
                else -> return@withContext null to "截图尺寸异常: ${bytes.size} (期望 $expect12 或 $expect16)"
            }
            val bmp = rgbaToBitmap(bytes, headerLen, w, h)
                ?: return@withContext null to "截图数据转 Bitmap 失败"
            bmp to ""
        } catch (e: Exception) {
            Log.e(TAG, "截图失败: ${e.message}")
            null to (e.message ?: "未知错误")
        }
    }

    /** 把 RGBA 原始字节转成 Bitmap */
    private fun rgbaToBitmap(bytes: ByteArray, headerLen: Int, w: Int, h: Int): Bitmap? {
        return try {
            val count = w * h
            val pixels = IntArray(count)
            var p = headerLen
            for (i in 0 until count) {
                // screencap 输出为 RGBA_8888, 小端排列, Android Bitmap 需要 ARGB
                val r = bytes[p].toInt() and 0xFF
                val g = bytes[p + 1].toInt() and 0xFF
                val b = bytes[p + 2].toInt() and 0xFF
                val a = bytes[p + 3].toInt() and 0xFF
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                p += 4
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.e(TAG, "RGBA 转 Bitmap 异常: ${e.message}")
            null
        }
    }

    /** 等比缩小到最长边不超过 MAX_EDGE */
    private fun scaleDown(src: Bitmap): Bitmap {
        val maxEdge = maxOf(src.width, src.height)
        if (maxEdge <= MAX_EDGE) return src
        val ratio = MAX_EDGE.toFloat() / maxEdge
        val nw = (src.width * ratio).toInt().coerceAtLeast(1)
        val nh = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    /**
     * 功能: 截屏后交给视觉模型描述屏幕内容
     * 参数:
     *   provider 视觉能力可用的 LLM 配置
     *   question 想问模型的问题, 默认要求按结构输出可点击元素
     * 返回: VisionResult
     * 使用场景: Agent observe 在无障碍节点为空时兜底
     */
    suspend fun describeScreen(
        provider: LlmSettings.Provider,
        question: String = ""
    ): VisionResult = withContext(Dispatchers.IO) {
        if (!captureAllowed) {
            return@withContext VisionResult(false, error = "当前任务未授权截图/视觉能力")
        }
        val started = System.currentTimeMillis()
        val (displayW, displayH) = ScreenGeometry.displaySize()
        val frame = captureJpegFrame()
        if (frame.base64.isEmpty()) {
            return@withContext VisionResult(
                false, error = frame.error, elapsedMs = System.currentTimeMillis() - started
            )
        }
        val prompt = buildString {
            append("当前显示坐标尺寸: ${displayW}x${displayH}, rotation=${ScreenGeometry.rotationLabel()}。\n")
            append("这张截图的图片尺寸: ${frame.width}x${frame.height}。\n")
            append(if (question.isBlank()) defaultQuestion() else question)
            append(
                "\n坐标要求: 输出**当前显示坐标** x∈[0,${displayW - 1}], y∈[0,${displayH - 1}], " +
                    "不要输出 0~1000 归一化坐标。"
            )
            if (frame.width != displayW || frame.height != displayH) {
                append(
                    "\n注意: 截图被缩放过, 视觉估算的像素要按比例换算: " +
                        "x * ${displayW}/${frame.width}, y * ${displayH}/${frame.height}。"
                )
            }
            append("\n不要用超出显示尺寸的数字。")
        }
        try {
            val messages = JSONArray().put(
                JSONObject().put("role", "user").put(
                    "content", JSONArray()
                        .put(
                            JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${frame.base64}"))
                        )
                        .put(JSONObject().put("type", "text").put("text", prompt))
                )
            )
            val r = LlmClient.chat(provider, messages)
            VisionResult(
                ok = r.ok,
                text = r.content,
                error = r.error,
                elapsedMs = System.currentTimeMillis() - started
            )
        } catch (e: Exception) {
            Log.e(TAG, "视觉识别失败: ${e.message}")
            VisionResult(false, error = e.message ?: "未知错误", elapsedMs = System.currentTimeMillis() - started)
        }
    }

    /**
     * 默认提问模板: 强制模型输出「可点击元素 + 绝对像素坐标」。
     * 以前用 0~1000 归一化, 模型经常直接把归一化值当像素 tap, 导致点到屏幕外;
     * 现在图片不再缩到 1080, 坐标空间与真实屏幕一致, 并要求绝对像素。
     */
    private fun defaultQuestion(): String = """
        你是 Android 手机的视觉识别模块。请阅读这张手机截图, 输出:
        1) 当前页面在做什么 (一句话)
        2) 所有可点击或可读的控件, 逐行按格式输出:
           名称 | 类型(按钮/输入框/文本/图标) | 中心坐标(x,y, 绝对像素)
        3) 如果当前页面有题目, 把题干与所有选项原文完整抄出来
        4) 如果看到颜色、勾选、进度、禁用态等视觉状态, 描述具体证据
           (看到什么文字/图标/形状/位置), 不要只给一个“已完成/未完成”的结论。
        只输出上述内容, 不要额外解释。
    """.trimIndent()
}
