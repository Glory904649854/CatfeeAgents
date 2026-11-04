package com.catfee.rootauto.root

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.catfee.rootauto.llm.LlmClient
import com.catfee.rootauto.llm.LlmSettings
import kotlinx.coroutines.Dispatchers
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

    /** 截图原始文件落盘路径 (root 域写入) */
    private const val SHOT_PATH = "/data/local/tmp/catfee_shot.raw"

    /** 送模型前的最长边, 越大越清晰但 token 越多; 1080 宽已足够读清单行文字 */
    private const val MAX_EDGE = 1080

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

    /**
     * 功能: 截屏并转成可直接送模型的 base64 JPEG
     * 参数: 无
     * 返回: (base64 字符串, 失败原因); 失败时 base64 为空
     * 使用场景: 视觉兜底、失败存证
     */
    suspend fun captureBase64Jpeg(): Pair<String, String> = withContext(Dispatchers.IO) {
        val (bmp, err) = captureBitmap()
        if (bmp == null) return@withContext "" to err
        try {
            val scaled = scaleDown(bmp)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to ""
        } catch (e: Exception) {
            Log.e(TAG, "截图转码失败: ${e.message}")
            "" to (e.message ?: "未知错误")
        }
    }

    /**
     * 功能: 截屏并直接返回原始 Bitmap (不缩放、不压缩)
     * 返回: (Bitmap, 失败原因); 失败时 Bitmap 为 null
     * 使用场景: 本地 OCR (ML Kit) 需要原图坐标, 缩放会让坐标偏移
     * 注意: 调用方负责 recycle 返回的 Bitmap
     */
    suspend fun captureBitmap(): Pair<Bitmap?, String> = withContext(Dispatchers.IO) {
        try {
            val (shotOk, w, h) = DaemonClient.shot(SHOT_PATH)
            if (!shotOk || w <= 0 || h <= 0) return@withContext null to "截图失败"
            val f = File(SHOT_PATH)
            // daemon 以 root 身份写文件; 通常已是 0644, 只有 App 读不到时才 chmod,
            // 避免每次 OCR 都触发一次 su 授权提示 (Magisk 的 toast 会遮挡截图)
            if (f.exists() && !f.canRead()) RootShell.exec("chmod 0644 $SHOT_PATH")
            if (!f.exists() || f.length() <= 0) return@withContext null to "截图文件不存在: $SHOT_PATH"
            val bytes = f.readBytes()
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
        question: String = DEFAULT_QUESTION
    ): VisionResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val (b64, err) = captureBase64Jpeg()
        if (b64.isEmpty()) {
            return@withContext VisionResult(false, error = err, elapsedMs = System.currentTimeMillis() - started)
        }
        try {
            val messages = JSONArray().put(
                JSONObject().put("role", "user").put(
                    "content", JSONArray()
                        .put(
                            JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
                        )
                        .put(JSONObject().put("type", "text").put("text", question))
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
     * 默认提问模板: 强制模型输出「可点击元素 + 大致坐标」, 便于后续 tap。
     * 坐标以 0~1000 归一化给出, 避免模型对绝对像素的漂移。
     */
    private val DEFAULT_QUESTION = """
        你是 Android 手机的视觉识别模块。请阅读这张手机截图, 输出:
        1) 当前页面在做什么 (一句话)
        2) 所有可点击或可读的控件, 逐行按格式输出:
           名称 | 类型(按钮/输入框/文本/图标) | 中心坐标(x,y, 以图片宽高的 0~1000 归一化)
        3) 如果当前页面有题目, 把题干与所有选项原文完整抄出来
        只输出上述内容, 不要额外解释。
    """.trimIndent()
}
