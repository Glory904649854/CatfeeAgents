package com.catfee.rootauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.catfee.rootauto.a11y.UiObserverService
import com.catfee.rootauto.agent.ToolRuntime
import com.catfee.rootauto.ocr.OcrEngine
import com.catfee.rootauto.ocr.PaddleOcrEngine
import com.catfee.rootauto.root.ScreenVision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * DebugReceiver —— adb 调试入口
 *
 * 不切换前台界面, 直接读取当前窗口:
 *   am broadcast -a com.catfee.rootauto.DEBUG --es cmd dump
 *   am broadcast -a com.catfee.rootauto.DEBUG --es cmd ocr
 *   am broadcast -a com.catfee.rootauto.DEBUG --es cmd verifyq \
 *     --es options 'A:100,1481;B:100,1632'
 *
 * 结果输出到 logcat, tag 为 CatfeeDebug。
 */
class DebugReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CatfeeDebug"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd")?.trim().orEmpty()
        Log.i(TAG, "cmd=$cmd")
        when (cmd) {
            "dump" -> dumpTree()
            "ocr" -> scope.launch { runOcr() }
            "paddleocr" -> scope.launch { runPaddleOcr(context) }
            "longocr" -> scope.launch { runLongOcr(context) }
            "listopts" -> Log.i(
                TAG,
                "===== LISTOPTIONS =====\n" +
                    ToolRuntime.listOptions(intent.getStringExtra("keyword").orEmpty())
            )
            "verifyq" -> scope.launch { runVerifyQuestion(intent) }
            "answerform" -> scope.launch {
                val r = com.catfee.rootauto.agent.FormSolver.solve(
                    context.applicationContext,
                    intent.getStringExtra("title").orEmpty(),
                    3,
                    true,
                    intent.getBooleanExtra("reset", true)
                ) { Log.i(TAG, it) }
                Log.i(TAG, "===== ANSWERFORM submitted=${r.submitted} answered=${r.answered} msg=${r.message} =====")
            }
            else -> Log.w(TAG, "未知调试命令: $cmd")
        }
    }

    /** 输出完整无障碍树 */
    private fun dumpTree() {
        val svc = UiObserverService.instance
        if (svc == null) {
            Log.w(TAG, "无障碍服务未连接")
            return
        }
        val tree = svc.dumpTree()
        Log.i(TAG, "===== TREE ${tree.size} =====")
        tree.forEach { n ->
            Log.i(
                TAG,
                "T|${n.index}|${n.parent}|${n.depth}|${n.cls}|${n.text}|${n.desc}|" +
                    "${n.resourceId.substringAfterLast('/')}|${n.clickable}|${n.editable}|" +
                    "${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom}"
            )
        }
        Log.i(TAG, "===== END =====")
    }

    /** 截图并 OCR */
    private suspend fun runOcr() {
        val (bmp, err) = ScreenVision.captureBitmap()
        if (bmp == null) {
            Log.w(TAG, "OCR 截图失败: $err")
            return
        }
        try {
            val rows = OcrEngine.rowsOf(bmp)
            Log.i(TAG, "===== OCR ${rows.size} =====")
            rows.forEachIndexed { i, r ->
                Log.i(TAG, "O|$i|${r.box.left},${r.box.top},${r.box.right},${r.box.bottom}|${r.text}")
            }
            Log.i(TAG, "===== OCR END =====")
        } finally {
            bmp.recycle()
        }
    }

    /** 本地 PP-OCR 识别当前屏幕 */
    private suspend fun runPaddleOcr(context: Context) {
        val (bmp, err) = ScreenVision.captureBitmap()
        if (bmp == null) {
            Log.w(TAG, "PP-OCR 截图失败: $err")
            return
        }
        try {
            val lines = PaddleOcrEngine.recognize(context.applicationContext, bmp)
            Log.i(TAG, "===== PADDLE ${lines.size} =====")
            lines.forEach {
                Log.i(TAG, "P|${it.box.left},${it.box.top},${it.box.right},${it.box.bottom}|${it.text}")
            }
            Log.i(TAG, "===== PADDLE END =====")
        } finally {
            bmp.recycle()
        }
    }

    /** 滚动拼接 OCR 当前长页面 */
    private suspend fun runLongOcr(context: Context) {
        val scan = com.catfee.rootauto.ocr.LongOcr.scan(context.applicationContext, 18)
        Log.i(TAG, "===== LONGOCR ${scan.screens} screens ${scan.lines.size} lines =====")
        scan.lines.forEach {
            Log.i(TAG, "L|${it.box.left},${it.box.top},${it.box.right},${it.box.bottom}|${it.text}")
        }
        Log.i(TAG, "===== LONGOCR END =====")
    }

    /** 批量核对选项选中态: --es options "A:100,1481;B:100,1632" */
    private suspend fun runVerifyQuestion(intent: Intent) {
        val raw = intent.getStringExtra("options").orEmpty()
        val arr = org.json.JSONArray()
        raw.split(';').forEach { part ->
            val seg = part.split(':')
            if (seg.size == 2) {
                val xy = seg[1].split(',')
                if (xy.size == 2) {
                    arr.put(
                        org.json.JSONObject()
                            .put("letter", seg[0].trim())
                            .put("x", xy[0].trim().toIntOrNull() ?: -1)
                            .put("y", xy[1].trim().toIntOrNull() ?: -1)
                    )
                }
            }
        }
        Log.i(TAG, "===== VERIFYQ =====\n" + ToolRuntime.verifyQuestion(arr))
    }
}
