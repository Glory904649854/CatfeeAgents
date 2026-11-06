package com.catfee.rootauto.root

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.Surface

/**
 * 统一坐标空间:
 *   - display: 当前方向下用户看到的坐标 (横屏时 2340x1080)
 *   - natural: 设备物理方向坐标 (竖屏 1080x2340), uinput 使用这一套
 *
 * 以前 daemon 只按 natural 建 uinput 轴, 但 observe/screenshot 给的是旋转后的
 * display 坐标; 横屏视频页所有 tap 都被截断。这里统一做转换。
 */
object ScreenGeometry {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedNatural: Pair<Int, Int>? = null

    /**
     * 当前旋转。DisplayManager 在部分 ROM 的固定横屏 Activity 里会一直返回 0,
     * 但实际画面和 uinput 轴已经转了 90°/270°。所以这里缓存一份,
     * 由 refreshRotation() 用 dumpsys input 的 SurfaceOrientation 校准。
     */
    @Volatile
    private var cachedRotation: Int = Surface.ROTATION_0

    @Volatile
    private var lastRotationCheck: Long = 0L

    private const val ROTATION_TTL_MS = 2_000L

    fun init(context: Context) {
        appContext = context.applicationContext
        cachedRotation = displayRotation()
    }

    /** daemon 的 wm size 是 natural 尺寸, 缓存起来作为 uinput 的坐标基准 */
    fun setNatural(w: Int, h: Int) {
        if (w > 0 && h > 0) cachedNatural = w to h
    }

    data class Info(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val naturalWidth: Int,
        val naturalHeight: Int
    ) {
        val rotated: Boolean get() = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

        fun contains(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

        /**
         * display -> natural, 交给 uinput 前必须转。
         * ROTATION_90 表示屏幕相对自然方向逆时针转了 90°:
         * 逻辑左上角对应自然右上角, 所以 x_n = naturalW - y, y_n = x。
         */
        fun toNatural(x: Int, y: Int): Pair<Int, Int> {
            val (nx, ny) = when (rotation) {
                Surface.ROTATION_90 -> (naturalWidth - y) to x
                Surface.ROTATION_180 -> (naturalWidth - x) to (naturalHeight - y)
                Surface.ROTATION_270 -> y to (naturalHeight - x)
                else -> x to y
            }
            return nx.coerceIn(0, naturalWidth - 1) to ny.coerceIn(0, naturalHeight - 1)
        }

        fun describe(): String =
            "display=${width}x${height} rotation=${rotation * 90} natural=${naturalWidth}x${naturalHeight}"
    }

    fun rotation(): Int = cachedRotation

    private fun displayRotation(): Int {
        val ctx = appContext ?: return Surface.ROTATION_0
        return try {
            val dm = ctx.getSystemService(DisplayManager::class.java)
            dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        } catch (e: Exception) {
            Surface.ROTATION_0
        }
    }

    /**
     * 用 InputManager 的实际方向校准旋转。
     *
     * 为什么不用 DisplayManager:
     *   部分 App 的固定横屏播放页, 实际 surface 已经是 2340x1080,
     *   但 DisplayManager 仍可能返回 ROTATION_0; 这会让 tap 校验按竖屏尺寸拒绝横屏坐标,
     *   或把横屏点击坐标原样送给 uinput, 点到错误位置 (视频页反复被点退出)。
     *   dumpsys input 的 SurfaceOrientation 与真实触摸轴一致。
     *
     * 2 秒缓存, 避免每次点击都 fork 一次 dumpsys。
     */
    suspend fun refreshRotation(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRotationCheck < ROTATION_TTL_MS) return
        lastRotationCheck = now
        val fromInput = try {
            val out = RootShell.execText(
                "dumpsys input 2>/dev/null | grep -m1 SurfaceOrientation"
            )
            Regex("""SurfaceOrientation:\s*([0-3])""")
                .find(out)?.groupValues?.getOrNull(1)?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
        cachedRotation = when (fromInput) {
            0, 1, 2, 3 -> fromInput
            else -> displayRotation()
        }
    }

    fun current(): Info? {
        val rotation = rotation()
        val natural = cachedNatural
        if (natural != null && natural.first > 0 && natural.second > 0) {
            val rotated = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
            val w = if (rotated) natural.second else natural.first
            val h = if (rotated) natural.first else natural.second
            return Info(w, h, rotation, natural.first, natural.second)
        }

        val ctx = appContext ?: return null
        return try {
            val dm = ctx.getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            val p = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(p)
            if (p.x <= 0 || p.y <= 0) return null
            val rotated = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
            val nw = if (rotated) p.y else p.x
            val nh = if (rotated) p.x else p.y
            Info(p.x, p.y, rotation, nw, nh)
        } catch (e: Exception) {
            null
        }
    }

    /** 当前显示坐标尺寸 (横屏时宽高互换) */
    fun displaySize(): Pair<Int, Int> =
        current()?.let { it.width to it.height } ?: (1080 to 2340)

    /** 坐标越界原因; null 表示合法 */
    fun invalid(x: Int, y: Int): String? {
        val info = current() ?: return null
        if (info.contains(x, y)) return null
        return "坐标越界: ($x,$y), 当前 ${info.describe()}。tap 坐标必须是当前显示方向像素; " +
            "不要用 0~1000 归一化值, 越界坐标不会执行。"
    }

    fun toNatural(x: Int, y: Int): Pair<Int, Int>? =
        current()?.toNatural(x, y)

    fun describe(): String = current()?.describe() ?: "display=unknown"

    fun rotationLabel(): Int = rotation() * 90
}
