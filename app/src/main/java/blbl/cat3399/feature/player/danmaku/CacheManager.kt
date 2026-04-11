package blbl.cat3399.feature.player.danmaku

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import blbl.cat3399.BlblApp
import blbl.cat3399.R
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Process
import blbl.cat3399.core.emote.EmoteBitmapLoader
import blbl.cat3399.core.emote.ReplyEmotePanelRepository
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.isHighLiked
import blbl.cat3399.feature.player.danmaku.model.DanmakuInlineSegment
import blbl.cat3399.feature.player.danmaku.model.DanmakuItem
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal data class CacheStyle(
    val textSizePx: Float,
    val fontWeight: DanmakuFontWeight,
    val strokeWidthPx: Float,
    val outlinePadPx: Float,
    val showHighLikeIcon: Boolean,
    val generation: Int,
)

internal class CacheManager(
    private val density: Float,
    private val mainLooper: Looper,
    private val onRenderSign: () -> Unit,
) {
    companion object {
        private const val TAG = "DanmakuCache"
        private val HIGH_LIKE_ICON_COLOR = Color.parseColor("#F6C343")

        private const val MSG_BUILD_CACHE = 2001
        private const val MSG_CLEAR = 2002
        private const val MSG_RELEASE = 2099

        private const val CACHE_POOL_MAX_BYTES: Long = 50L * 1024L * 1024L
        private const val CACHE_POOL_MAX_COUNT: Int = 72

        private const val MAX_RELEASE_PER_DRAIN = 24
    }

    private val mainHandler = Handler(mainLooper)

    private val thread: HandlerThread =
        HandlerThread("Danmaku-Cache").apply {
            start()
            runCatching { Process.setThreadPriority(threadId, Process.THREAD_PRIORITY_BACKGROUND) }
        }

    private val handler: Handler = CacheHandler(thread.looper)

    private val pool = BitmapPool(maxBytes = CACHE_POOL_MAX_BYTES, maxCount = CACHE_POOL_MAX_COUNT)

    private val queueDepth = AtomicInteger(0)
    private val releaseQueue: ConcurrentLinkedQueue<PendingRelease> = ConcurrentLinkedQueue()

    private val bitmapCreated = AtomicLong(0L)
    private val bitmapReused = AtomicLong(0L)
    private val bitmapPutToPool = AtomicLong(0L)
    private val bitmapRecycled = AtomicLong(0L)

    // Cache draw tools (cache thread only).
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        isSubpixelText = true
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        isSubpixelText = true
    }
    private val fontMetrics = Paint.FontMetrics()
    private val emotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val placeholderFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val placeholderStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
    }
    private val emoteRect = RectF()
    private val inlineLikeIcon by lazy(LazyThreadSafetyMode.NONE) {
        AppCompatResources.getDrawable(BlblApp.instance, R.drawable.ic_action_like)?.mutate()?.apply {
            DrawableCompat.setTint(DrawableCompat.wrap(this), HIGH_LIKE_ICON_COLOR)
        }
    }

    fun queueDepth(): Int = queueDepth.get().coerceAtLeast(0)

    fun poolSnapshot(): PoolSnapshot = pool.snapshot()

    fun statsSnapshot(): StatsSnapshot =
        StatsSnapshot(
            bitmapCreated = bitmapCreated.get(),
            bitmapReused = bitmapReused.get(),
            bitmapPutToPool = bitmapPutToPool.get(),
            bitmapRecycled = bitmapRecycled.get(),
        )

    fun requestBuildCache(
        item: DanmakuItem,
        textWidthPx: Float,
        style: CacheStyle,
        releaseAtFrameId: Int,
    ) {
        val payload = CacheRequest(item = item, textWidthPx = textWidthPx, style = style, releaseAtFrameId = releaseAtFrameId)
        queueDepth.incrementAndGet()
        handler.obtainMessage(MSG_BUILD_CACHE, payload).sendToTarget()
    }

    fun enqueueRelease(bitmap: Bitmap?, releaseAtFrameId: Int) {
        if (bitmap == null) return
        if (bitmap.isRecycled) return
        releaseQueue.add(PendingRelease(bitmap = bitmap, releaseAtFrameId = releaseAtFrameId))
    }

    fun drainReleasedBitmaps(currentFrameId: Int) {
        var drained = 0
        while (drained < MAX_RELEASE_PER_DRAIN) {
            val head = releaseQueue.peek() ?: break
            if (head.releaseAtFrameId > currentFrameId) break
            releaseQueue.poll()
            drained++
            val bmp = head.bitmap
            if (bmp.isRecycled) continue
            val pooled = pool.tryPut(bmp)
            if (!pooled) {
                runCatching { bmp.recycle() }
                bitmapRecycled.incrementAndGet()
            } else {
                bitmapPutToPool.incrementAndGet()
            }
        }
    }

    fun clear() {
        handler.removeCallbacksAndMessages(null)
        handler.sendEmptyMessage(MSG_CLEAR)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        handler.sendEmptyMessage(MSG_RELEASE)
    }

    private inner class CacheHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_BUILD_CACHE -> {
                    val req = msg.obj as? CacheRequest ?: return
                    queueDepth.decrementAndGet()
                    buildCache(req)
                }
                MSG_CLEAR -> {
                    queueDepth.set(0)
                    pool.clear()
                    releaseQueue.clear()
                }
                MSG_RELEASE -> {
                    removeCallbacksAndMessages(null)
                    queueDepth.set(0)
                    pool.clear()
                    releaseQueue.clear()
                    runCatching { thread.quitSafely() }
                }
            }
        }
    }

    private fun buildCache(req: CacheRequest) {
        val item = req.item
        val style = req.style

        // Skip if already has a matching cache.
        val existing = item.cacheBitmap
        if (existing != null && !existing.isRecycled && item.cacheGeneration == style.generation) {
            item.cacheState = blbl.cat3399.feature.player.danmaku.model.DanmakuCacheState.Rendered
            return
        }

        if (style.textSizePx <= 0f || !style.textSizePx.isFinite()) return

        val outlinePad = style.outlinePadPx.coerceAtLeast(0f)
        val strokeWidth = style.strokeWidthPx.coerceAtLeast(0f)

        val desiredTypeface = style.fontWeight.typeface
        if (fill.typeface != desiredTypeface) fill.typeface = desiredTypeface
        if (stroke.typeface != desiredTypeface) stroke.typeface = desiredTypeface

        fill.textSize = style.textSizePx
        stroke.textSize = style.textSizePx
        stroke.strokeWidth = strokeWidth

        fill.getFontMetrics(fontMetrics)
        val textHeightPx = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(1f)
        val boxHeight = ceil(textHeightPx + outlinePad * 2f).toInt().coerceAtLeast(1)
        val boxWidth = ceil(req.textWidthPx.coerceAtLeast(outlinePad * 2f)).toInt().coerceAtLeast(1)

        val bmp =
            pool.acquire(minWidth = boxWidth, minHeight = boxHeight)
                ?.also { bitmapReused.incrementAndGet() }
                ?: runCatching { Bitmap.createBitmap(boxWidth, boxHeight, Bitmap.Config.ARGB_8888) }.getOrNull()
                    ?.also { bitmapCreated.incrementAndGet() }
                ?: return

        // Always clear when reusing.
        runCatching { bmp.eraseColor(0x00000000) }

        val canvas = Canvas(bmp)

        val danmaku = item.data
        val rgb = danmaku.color and 0xFFFFFF
        stroke.color = (0xCC shl 24) or 0x000000
        fill.color = (0xFF shl 24) or rgb

        // Placeholder colors (alpha is applied at draw-time; keep opaque-ish here).
        placeholderFill.color = (0x22 shl 24) or 0x000000
        placeholderStroke.color = (0x66 shl 24) or 0xFFFFFF

        val baseline = outlinePad - fontMetrics.ascent
        val text = danmaku.text
        val drawStrokeEnabled = strokeWidth > 0.01f
        if (text.isNotBlank()) {
            val segments =
                item.inlineSegments
                    ?: run {
                        val parsed = parseInlineSegments(item, style)
                        if (parsed != null && shouldCacheInlineSegments(item)) item.inlineSegments = parsed
                        parsed
                    }
            if (segments == null) {
                if (drawStrokeEnabled) canvas.drawText(text, outlinePad, baseline, stroke)
                canvas.drawText(text, outlinePad, baseline, fill)
            } else {
                val emoteSizePx = textHeightPx
                val emoteTop = outlinePad
                val r = (emoteSizePx * 0.18f).coerceIn(2f, 10f)
                val highLikeGapPx = inlineIconGapPx(emoteSizePx)
                var cursorX = outlinePad
                for (seg in segments) {
                    when (seg) {
                        is DanmakuInlineSegment.Text -> {
                            if (seg.end > seg.start) {
                                if (drawStrokeEnabled) canvas.drawText(text, seg.start, seg.end, cursorX, baseline, stroke)
                                canvas.drawText(text, seg.start, seg.end, cursorX, baseline, fill)
                                cursorX += fill.measureText(text, seg.start, seg.end)
                            }
                        }
                        is DanmakuInlineSegment.Emote -> {
                            val eb = EmoteBitmapLoader.getCached(seg.url)
                            if (eb != null && !eb.isRecycled) {
                                emoteRect.set(cursorX, emoteTop, cursorX + emoteSizePx, emoteTop + emoteSizePx)
                                canvas.drawBitmap(eb, null, emoteRect, emotePaint)
                            } else {
                                // Best-effort prefetch; loader deduplicates.
                                EmoteBitmapLoader.prefetch(seg.url)
                                emoteRect.set(cursorX, emoteTop, cursorX + emoteSizePx, emoteTop + emoteSizePx)
                                canvas.drawRoundRect(emoteRect, r, r, placeholderFill)
                                canvas.drawRoundRect(emoteRect, r, r, placeholderStroke)
                            }
                            cursorX += emoteSizePx
                        }
                        DanmakuInlineSegment.HighLikeIcon -> {
                            drawInlineLikeIcon(cursorX, emoteTop, emoteSizePx, canvas)
                            cursorX += emoteSizePx + highLikeGapPx
                        }
                    }
                }
            }
        }

        val old = item.cacheBitmap
        item.cacheBitmap = bmp
        item.cacheGeneration = style.generation
        item.cacheState = blbl.cat3399.feature.player.danmaku.model.DanmakuCacheState.Rendered
        if (old != null && old != bmp) {
            enqueueRelease(old, releaseAtFrameId = req.releaseAtFrameId)
        }

        // Signal renderer to refresh. Throttle is intentionally handled by UI frame pacing.
        mainHandler.post {
            runCatching { onRenderSign() }.onFailure { AppLog.w(TAG, "renderSign failed", it) }
        }
    }

    private fun parseInlineSegments(
        item: DanmakuItem,
        style: CacheStyle,
    ): List<DanmakuInlineSegment>? {
        val text = item.data.text
        var i = 0
        var lastTextStart = 0
        var hasInline = false
        val out = ArrayList<DanmakuInlineSegment>(8)
        if (style.showHighLikeIcon && item.data.isHighLiked) {
            out.add(DanmakuInlineSegment.HighLikeIcon)
            hasInline = true
        }
        val canParseEmote = ReplyEmotePanelRepository.version() > 0 && text.contains('[')
        while (i < text.length) {
            if (!canParseEmote) break
            val open = text.indexOf('[', startIndex = i)
            if (open < 0) break
            val close = text.indexOf(']', startIndex = open + 1)
            if (close < 0) break
            val token = text.substring(open, close + 1)
            val url = ReplyEmotePanelRepository.urlForToken(token)
            if (url != null && url.startsWith("http")) {
                hasInline = true
                if (open > lastTextStart) out.add(DanmakuInlineSegment.Text(start = lastTextStart, end = open))
                out.add(DanmakuInlineSegment.Emote(url = url))
                lastTextStart = close + 1
            }
            i = close + 1
        }
        if (!hasInline) return null
        if (lastTextStart < text.length) out.add(DanmakuInlineSegment.Text(start = lastTextStart, end = text.length))
        return out
    }

    private fun shouldCacheInlineSegments(item: DanmakuItem): Boolean {
        val text = item.data.text
        return !text.contains('[') || ReplyEmotePanelRepository.version() > 0
    }

    private fun inlineIconGapPx(iconSizePx: Float): Float = (iconSizePx * 0.14f).coerceAtLeast(density * 2f)

    private fun drawInlineLikeIcon(
        left: Float,
        top: Float,
        sizePx: Float,
        canvas: Canvas,
    ) {
        val icon = inlineLikeIcon ?: return
        val right = (left + sizePx).roundToInt()
        val bottom = (top + sizePx).roundToInt()
        icon.setBounds(left.roundToInt(), top.roundToInt(), right, bottom)
        icon.alpha = 255
        icon.draw(canvas)
    }

    private data class CacheRequest(
        val item: DanmakuItem,
        val textWidthPx: Float,
        val style: CacheStyle,
        val releaseAtFrameId: Int,
    )

    private data class PendingRelease(
        val bitmap: Bitmap,
        val releaseAtFrameId: Int,
    )

    private class BitmapPool(
        private val maxBytes: Long,
        private val maxCount: Int,
    ) {
        private val pool = ArrayDeque<Bitmap>()
        private var pooledBytes: Long = 0L

        @Synchronized
        fun acquire(minWidth: Int, minHeight: Int): Bitmap? {
            if (pool.isEmpty()) return null
            val it = pool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (b.isRecycled) {
                    it.remove()
                    continue
                }
                if (!isReusable(b, minWidth, minHeight)) continue
                it.remove()
                pooledBytes -= b.allocationByteCount.toLong().coerceAtLeast(0L)
                return b
            }
            return null
        }

        @Synchronized
        fun tryPut(bitmap: Bitmap): Boolean {
            if (bitmap.isRecycled) return true
            val bytes = bitmap.allocationByteCount.toLong().coerceAtLeast(0L)
            if (bytes <= 0L) return false
            if (bytes > maxBytes) return false
            if (pool.size >= maxCount) return false
            if (pooledBytes + bytes > maxBytes) return false
            pool.addLast(bitmap)
            pooledBytes += bytes
            return true
        }

        @Synchronized
        fun clear() {
            val it = pool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                it.remove()
                runCatching { if (!b.isRecycled) b.recycle() }
            }
            pooledBytes = 0L
        }

        @Synchronized
        fun snapshot(): PoolSnapshot =
            PoolSnapshot(
                count = pool.size,
                bytes = pooledBytes,
                maxBytes = maxBytes,
            )

        private fun isReusable(bitmap: Bitmap, minWidth: Int, minHeight: Int): Boolean {
            if (bitmap.config != Bitmap.Config.ARGB_8888) return false
            if (bitmap.width < minWidth || bitmap.height < minHeight) return false
            val dw = bitmap.width - minWidth
            val dh = bitmap.height - minHeight
            return dw <= 48 && dh <= 24
        }
    }

    internal data class PoolSnapshot(
        val count: Int,
        val bytes: Long,
        val maxBytes: Long,
    )

    internal data class StatsSnapshot(
        val bitmapCreated: Long,
        val bitmapReused: Long,
        val bitmapPutToPool: Long,
        val bitmapRecycled: Long,
    )
}
