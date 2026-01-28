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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val engine = DanmakuEngine()

    private class CachedBitmap(
        val bitmap: Bitmap,
    ) {
        var lastDrawFrameId: Int = 0
    }

    private enum class BitmapRecycleMode {
        SYNC,
        ASYNC,
    }

    private var bitmapCache = IdentityHashMap<Danmaku, CachedBitmap>()
    private val rendering = IdentityHashMap<Danmaku, Boolean>()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
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
    private val fontMetrics = Paint.FontMetrics()

    private var bitmapRenderScope: CoroutineScope? = null
    private var bitmapRenderQueue: Channel<BitmapRequest>? = null
    private var bitmapRenderGeneration: Int = 0

    private var positionProvider: (() -> Long)? = null
    private var configProvider: (() -> DanmakuConfig)? = null
    private var lastPositionMs: Long = 0L
    private var lastDrawUptimeMs: Long = 0L
    private var lastPositionChangeUptimeMs: Long = 0L
    private var lastLayoutConfig: DanmakuConfig? = null
    private var drawFrameId: Int = 0

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
            lastLayoutConfig = null
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

        if (shouldRestartLayout(lastLayoutConfig, config)) {
            // Config changes affect measurement/layout; restart to avoid incorrect widths/cached bitmaps.
            engine.seekTo(positionMs)
            clearBitmaps()
        }
        lastLayoutConfig = config

        val textSizePx = sp(config.textSizeSp)
        fill.textSize = textSizePx
        stroke.textSize = textSizePx

        val outlinePad = max(1f, stroke.strokeWidth / 2f)
        val opacityAlpha = (config.opacity * 255).roundToInt().coerceIn(0, 255)
        bitmapPaint.alpha = opacityAlpha

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

        drawFrameId++
        val frameId = drawFrameId
        fill.getFontMetrics(fontMetrics)
        val baselineOffset = outlinePad - fontMetrics.ascent
        var requested = 0
        var fallback = 0
        for (a in active) {
            val cached = bitmapCache[a.danmaku]
            if (cached != null) {
                cached.lastDrawFrameId = frameId
                canvas.drawBitmap(cached.bitmap, a.x, a.yTop, bitmapPaint)
                continue
            }

            if (requested < MAX_BITMAP_REQUESTS_PER_FRAME && rendering[a.danmaku] != true) {
                requested++
                rendering[a.danmaku] = true
                ensureBitmapRenderer()
                val queued =
                    bitmapRenderQueue
                        ?.trySend(
                            BitmapRequest(
                                danmaku = a.danmaku,
                                textWidth = a.textWidth,
                                outlinePad = outlinePad,
                                textSizePx = textSizePx,
                                generation = bitmapRenderGeneration,
                            ),
                        )?.isSuccess == true
                if (!queued) rendering.remove(a.danmaku)
            }
            if (fallback < MAX_FALLBACK_TEXT_PER_FRAME) {
                fallback++
                drawTextFallback(
                    canvas,
                    a.danmaku,
                    x = a.x,
                    yTop = a.yTop,
                    outlinePad = outlinePad,
                    baselineOffset = baselineOffset,
                    opacityAlpha = opacityAlpha,
                )
            }
        }

        // Recycle bitmaps that are no longer active.
        trimBitmapCache(frameId)

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

    private fun drawTextFallback(
        canvas: Canvas,
        danmaku: Danmaku,
        x: Float,
        yTop: Float,
        outlinePad: Float,
        baselineOffset: Float,
        opacityAlpha: Int,
    ) {
        if (danmaku.text.isBlank()) return

        val rgb = danmaku.color and 0xFFFFFF
        val strokeAlpha = ((opacityAlpha * 0xCC) / 255).coerceIn(0, 255)
        stroke.color = (strokeAlpha shl 24) or 0x000000
        fill.color = (opacityAlpha shl 24) or rgb

        val textX = x + outlinePad
        val baseline = yTop + baselineOffset
        canvas.drawText(danmaku.text, textX, baseline, stroke)
        canvas.drawText(danmaku.text, textX, baseline, fill)
    }

    private fun trimBitmapCache(frameId: Int) {
        val it = bitmapCache.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.lastDrawFrameId == frameId) continue
            runCatching { e.value.bitmap.recycle() }
            it.remove()
        }
    }

    private fun clearBitmaps() {
        clearBitmaps(recycleMode = BitmapRecycleMode.SYNC)
    }

    private fun recycleBitmapsFromCache(cache: IdentityHashMap<Danmaku, CachedBitmap>) {
        for (cached in cache.values) {
            runCatching {
                val bmp = cached.bitmap
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
        cache.clear()
    }

    private fun clearBitmaps(recycleMode: BitmapRecycleMode) {
        bitmapRenderGeneration++
        stopBitmapRenderer()
        rendering.clear()
        val cache = bitmapCache
        if (cache.isEmpty()) return
        bitmapCache = IdentityHashMap()
        when (recycleMode) {
            BitmapRecycleMode.SYNC -> recycleBitmapsFromCache(cache)
            BitmapRecycleMode.ASYNC -> recycleScope.launch { recycleBitmapsFromCache(cache) }
        }
    }

    private fun stopBitmapRenderer() {
        bitmapRenderQueue?.close()
        bitmapRenderScope?.cancel()
        bitmapRenderQueue = null
        bitmapRenderScope = null
    }

    private fun shouldRestartLayout(prev: DanmakuConfig?, now: DanmakuConfig): Boolean {
        if (prev == null) return false
        if (prev.textSizeSp != now.textSizeSp) return true
        if (prev.speedLevel != now.speedLevel) return true
        if (prev.area != now.area) return true
        return false
    }

    private data class BitmapRequest(
        val danmaku: Danmaku,
        val textWidth: Float,
        val outlinePad: Float,
        val textSizePx: Float,
        val generation: Int,
    )

    private fun ensureBitmapRenderer() {
        if (bitmapRenderScope != null) return

        val queue = Channel<BitmapRequest>(capacity = BITMAP_QUEUE_CAPACITY)
        bitmapRenderQueue = queue
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        bitmapRenderScope = scope
        repeat(BITMAP_RENDER_WORKERS) {
            scope.launch {
                val renderFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
                val renderStroke =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                        typeface = Typeface.DEFAULT_BOLD
                        color = 0xCC000000.toInt()
                    }
                val fm = Paint.FontMetrics()
                try {
                    for (req in queue) {
                        val created =
                            runCatching {
                                renderFill.textSize = req.textSizePx
                                renderStroke.textSize = req.textSizePx
                                renderFill.getFontMetrics(fm)
                                renderToBitmap(
                                    danmaku = req.danmaku,
                                    textWidth = req.textWidth,
                                    outlinePad = req.outlinePad,
                                    fontMetrics = fm,
                                    fill = renderFill,
                                    stroke = renderStroke,
                                )
                            }.getOrNull()
                        if (created == null) {
                            post { rendering.remove(req.danmaku) }
                            continue
                        }
                        post { commitRenderedBitmap(req, created) }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    AppLog.w("DanmakuView", "bitmap renderer crashed", t)
                }
            }
        }
    }

    private fun renderToBitmap(
        danmaku: Danmaku,
        textWidth: Float,
        outlinePad: Float,
        fontMetrics: Paint.FontMetrics,
        fill: Paint,
        stroke: Paint,
    ): CachedBitmap {
        val textBoxHeight = (fontMetrics.descent - fontMetrics.ascent) + outlinePad * 2f
        val w = max(1, ceil(textWidth.toDouble()).toInt())
        val h = max(1, ceil(textBoxHeight.toDouble()).toInt())

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val color = (0xFF000000.toInt() or (danmaku.color and 0xFFFFFF))
        fill.color = color

        val x = outlinePad
        val baseline = outlinePad - fontMetrics.ascent
        c.drawText(danmaku.text, x, baseline, stroke)
        c.drawText(danmaku.text, x, baseline, fill)

        return CachedBitmap(bitmap = bmp)
    }

    private fun commitRenderedBitmap(req: BitmapRequest, created: CachedBitmap) {
        rendering.remove(req.danmaku)
        if (!isAttachedToWindow || req.generation != bitmapRenderGeneration) {
            runCatching { created.bitmap.recycle() }
            return
        }

        val existing = bitmapCache[req.danmaku]
        if (existing != null) {
            runCatching { created.bitmap.recycle() }
            return
        }

        bitmapCache[req.danmaku] = created
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        clearBitmaps(recycleMode = BitmapRecycleMode.ASYNC)
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
        private const val MAX_BITMAP_REQUESTS_PER_FRAME = 8
        private const val MAX_FALLBACK_TEXT_PER_FRAME = 16
        private const val BITMAP_QUEUE_CAPACITY = 96
        private const val BITMAP_RENDER_WORKERS = 2

        private val recycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
