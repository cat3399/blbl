package blbl.cat3399.feature.player.danmaku

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.net.BiliClient
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.max

class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val engine = DanmakuEngine()

    private data class CachedBitmap(
        val bitmap: Bitmap,
        val widthPx: Int,
        val heightPx: Int,
    )

    private val bitmapCache = IdentityHashMap<Danmaku, CachedBitmap>()
    private val drawnThisFrame = IdentityHashMap<Danmaku, Boolean>()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }
    private val bitmapFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = sp(18f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bitmapStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
        textSize = bitmapFill.textSize
        typeface = Typeface.DEFAULT_BOLD
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = sp(18f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
        textSize = fill.textSize
        typeface = Typeface.DEFAULT_BOLD
    }

    private var positionProvider: (() -> Long)? = null
    private var configProvider: (() -> DanmakuConfig)? = null
    private var lastPositionMs: Long = 0L
    private var lastDrawUptimeMs: Long = 0L
    private var lastPositionChangeUptimeMs: Long = 0L

    fun setPositionProvider(provider: () -> Long) {
        positionProvider = provider
    }

    fun setConfigProvider(provider: () -> DanmakuConfig) {
        configProvider = provider
    }

    fun setDanmakus(list: List<Danmaku>) {
        AppLog.i("DanmakuView", "setDanmakus size=${list.size}")
        engine.setDanmakus(list)
        clearBitmaps()
        invalidate()
    }

    fun appendDanmakus(list: List<Danmaku>, maxItems: Int = 0, alreadySorted: Boolean = false) {
        if (list.isEmpty()) return
        if (alreadySorted) engine.appendDanmakusSorted(list) else engine.appendDanmakus(list)
        if (maxItems > 0) engine.trimToMax(maxItems)
        invalidate()
    }

    fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long) {
        val min = minTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val max = maxTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        engine.trimToTimeRange(min, max)
        invalidate()
    }

    fun notifySeek(positionMs: Long) {
        engine.seekTo(positionMs)
        lastPositionMs = positionMs
        lastDrawUptimeMs = SystemClock.uptimeMillis()
        lastPositionChangeUptimeMs = lastDrawUptimeMs
        clearBitmaps()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val provider = positionProvider ?: return
        val config = configProvider?.invoke() ?: defaultConfig()
        if (!config.enabled) {
            clearBitmaps()
            return
        }

        val prevPositionMs = lastPositionMs
        val positionMs = provider()
        val now = SystemClock.uptimeMillis()
        if (lastDrawUptimeMs == 0L) lastDrawUptimeMs = now
        if (lastPositionChangeUptimeMs == 0L) lastPositionChangeUptimeMs = now

        if (positionMs != prevPositionMs) {
            lastPositionChangeUptimeMs = now
        }
        lastPositionMs = positionMs
        lastDrawUptimeMs = now

        val textSizePx = sp(config.textSizeSp)
        fill.textSize = textSizePx
        stroke.textSize = textSizePx
        bitmapFill.textSize = textSizePx
        bitmapStroke.textSize = textSizePx

        val outlinePad = max(1f, stroke.strokeWidth / 2f)
        bitmapPaint.alpha = (config.opacity * 255).toInt().coerceIn(0, 255)

        val active = engine.update(
            width = width,
            height = height,
            positionMs = positionMs,
            paint = fill,
            outlinePaddingPx = outlinePad,
            speedLevel = config.speedLevel,
            area = config.area,
            topInsetPx = safeTopInsetPx(),
            bottomInsetPx = safeBottomInsetPx(),
        )

        drawnThisFrame.clear()
        for (a in active) {
            drawnThisFrame[a.danmaku] = true
            val cached = bitmapCache[a.danmaku]
            val bmp =
                if (cached != null) {
                    cached.bitmap
                } else {
                    val created = renderToBitmap(a.danmaku, a.textWidth, outlinePad)
                    bitmapCache[a.danmaku] = created
                    created.bitmap
                }
            canvas.drawBitmap(bmp, a.x, a.yTop, bitmapPaint)
        }

        // Recycle bitmaps that are no longer active.
        trimBitmapCache()

        // If playback time hasn't moved for a while, stop the loop to avoid wasting 60fps while paused/buffering.
        // PlayerActivity kicks `invalidate()` on resume/play state changes.
        if (now - lastPositionChangeUptimeMs >= STOP_WHEN_IDLE_MS) {
            postInvalidateDelayed(IDLE_POLL_MS)
            return
        }

        // Keep vsync loop while we have active danmaku; otherwise schedule lazily.
        if (active.isNotEmpty() || engine.hasPending()) {
            postInvalidateOnAnimation()
            return
        }
        val nextAt = engine.nextDanmakuTimeMs()
        if (nextAt != null && nextAt <= positionMs + 250) {
            postInvalidateOnAnimation()
            return
        }
        if (nextAt != null && nextAt > positionMs) {
            val delay = (nextAt - positionMs).coerceAtMost(750L)
            postInvalidateDelayed(delay)
        }
    }

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun safeTopInsetPx(): Int {
        // Use real window insets when possible (full-screen players may have 0 status-bar inset).
        val insetTop =
            ViewCompat.getRootWindowInsets(this)
                ?.getInsets(WindowInsetsCompat.Type.systemBars())
                ?.top
                ?: runCatching {
                    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
                    if (id > 0) resources.getDimensionPixelSize(id) else 0
                }.getOrDefault(0)
        // Keep a tiny padding to avoid clipping at the very top.
        return insetTop + dp(2f)
    }

    private fun safeBottomInsetPx(): Int {
        // Avoid player controller area; conservative default.
        return dp(52f)
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun renderToBitmap(danmaku: Danmaku, textWidth: Float, outlinePad: Float): CachedBitmap {
        val fm = bitmapFill.fontMetrics
        val textBoxHeight = (fm.descent - fm.ascent) + outlinePad * 2f

        val w = max(1, ceil(textWidth.toDouble()).toInt())
        val h = max(1, ceil(textBoxHeight.toDouble()).toInt())

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val color = (0xFF000000.toInt() or danmaku.color.toInt())
        bitmapFill.color = color
        bitmapStroke.color = 0xCC000000.toInt()

        val x = outlinePad
        val baseline = outlinePad - fm.ascent
        c.drawText(danmaku.text, x, baseline, bitmapStroke)
        c.drawText(danmaku.text, x, baseline, bitmapFill)

        return CachedBitmap(bitmap = bmp, widthPx = w, heightPx = h)
    }

    private fun trimBitmapCache() {
        val it = bitmapCache.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (drawnThisFrame[e.key] == true) continue
            runCatching { e.value.bitmap.recycle() }
            it.remove()
        }
    }

    private fun clearBitmaps() {
        val it = bitmapCache.values.iterator()
        while (it.hasNext()) {
            runCatching { it.next().bitmap.recycle() }
        }
        bitmapCache.clear()
        drawnThisFrame.clear()
    }

    override fun onDetachedFromWindow() {
        clearBitmaps()
        super.onDetachedFromWindow()
    }

    private fun defaultConfig(): DanmakuConfig {
        val prefs = BiliClient.prefs
        return DanmakuConfig(
            enabled = prefs.danmakuEnabled,
            opacity = prefs.danmakuOpacity,
            textSizeSp = prefs.danmakuTextSizeSp,
            speedLevel = prefs.danmakuSpeed,
            area = prefs.danmakuArea,
        )
    }

    private companion object {
        private const val STOP_WHEN_IDLE_MS = 450L
        private const val IDLE_POLL_MS = 250L
    }
}
