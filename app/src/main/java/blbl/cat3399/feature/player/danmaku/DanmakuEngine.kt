package blbl.cat3399.feature.player.danmaku

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import blbl.cat3399.BlblApp
import blbl.cat3399.R
import blbl.cat3399.core.emote.EmoteBitmapLoader
import blbl.cat3399.core.emote.ReplyEmotePanelRepository
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.model.isHighLiked
import blbl.cat3399.feature.player.danmaku.model.DanmakuCacheState
import blbl.cat3399.feature.player.danmaku.model.DanmakuItem
import blbl.cat3399.feature.player.danmaku.model.DanmakuInlineSegment
import blbl.cat3399.feature.player.danmaku.model.DanmakuKind
import blbl.cat3399.feature.player.danmaku.model.RenderSnapshot
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal interface DanmakuEngineMainApi {
    fun lastDrawCachedCount(): Int

    fun lastDrawFallbackCount(): Int

    fun stepTime(positionMs: Long, uiFrameId: Int)

    fun drainReleasedBitmaps(uiFrameId: Int)

    fun renderSnapshot(): RenderSnapshot

    fun draw(canvas: Canvas, snapshot: RenderSnapshot, config: DanmakuConfig)
}

internal interface DanmakuEngineActionApi {
    fun updateViewport(width: Int, height: Int, topInsetPx: Int, bottomInsetPx: Int)

    fun updateConfig(newConfig: DanmakuConfig)

    fun stepTime(positionMs: Long, uiFrameId: Int)

    fun currentPositionMs(): Long

    fun drainReleasedBitmaps(uiFrameId: Int)

    fun preAct()

    fun act()

    fun setDanmakus(list: List<Danmaku>)

    fun appendDanmakus(list: List<Danmaku>, alreadySorted: Boolean)

    fun trimToMax(maxItems: Int)

    fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long)

    fun seekTo(positionMs: Long)

    fun clear()

    fun release()
}

/**
 * Danmaku engine:
 * - Action thread owns timeline admission and lane selection.
 * - Main thread only consumes render snapshot and computes current scroll positions.
 */
internal class DanmakuEngine(
    private val displayMetrics: DisplayMetrics,
    private val cacheManager: CacheManager,
) : DanmakuEngineMainApi, DanmakuEngineActionApi {
    private val density: Float = displayMetrics.density.takeIf { it.isFinite() && it > 0f } ?: 1f
    // ---- Data ----
    private val actionStateLock = Any()
    private var items: MutableList<DanmakuItem> = mutableListOf()
    private var index: Int = 0
    private val active: ArrayList<DanmakuItem> = ArrayList(64)
    private val pending: ArrayDeque<PendingSpawn> = ArrayDeque()

    // Monotonic time within a session (action thread).
    private var lastNowMs: Int = 0

    // ---- Viewport / Config (action thread writes; main reads) ----
    @Volatile private var viewportWidth: Int = 0
    @Volatile private var viewportHeight: Int = 0
    @Volatile private var viewportTopInsetPx: Int = 0
    @Volatile private var viewportBottomInsetPx: Int = 0

    @Volatile
    private var config: DanmakuConfig =
        DanmakuConfig(
            enabled = true,
            opacity = 1f,
            textSizeSp = 18f,
            fontWeight = DanmakuFontWeight.Bold,
            strokeWidthPx = 4,
            speedLevel = 4,
            area = 1f,
            laneDensity = DanmakuLaneDensity.Standard,
            showHighLikeIcon = true,
        )

    @Volatile private var textSizePx: Float = sp(18f)
    @Volatile private var strokeWidthPx: Float = 4f
    @Volatile private var outlinePadPx: Float = 2f

    @Volatile private var cacheStyleGeneration: Int = 0
    @Volatile private var measureGeneration: Int = 0
    @Volatile private var debugPendingCount: Int = 0
    @Volatile private var debugNextAtMs: Int? = null

    @Volatile private var lastDrawCachedCount: Int = 0
    @Volatile private var lastDrawFallbackCount: Int = 0

    override fun lastDrawCachedCount(): Int = lastDrawCachedCount

    override fun lastDrawFallbackCount(): Int = lastDrawFallbackCount

    // ---- Time (main writes; action reads) ----
    @Volatile private var currentPositionMs: Long = 0L
    @Volatile private var currentUiFrameId: Int = 0

    // ---- Render snapshot (double buffer) ----
    private val snapshotA = RenderSnapshot()
    private val snapshotB = RenderSnapshot()
    @Volatile private var latestSnapshot: RenderSnapshot = snapshotA
    private var snapshotDirty: Boolean = true
    private var rebuildRequested: Boolean = true

    // ---- Layout state (action thread only) ----
    private val actionFontMetrics = Paint.FontMetrics()
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private var configuredLaneCount: Int = 0
    private var configuredLaneHeightPx: Float = 0f
    private var configuredTopInsetPx: Int = 0
    private var configuredUsableHeightPx: Int = 0
    private var configuredMaxYTopPx: Float = 0f
    private var laneLastScroll: Array<DanmakuItem?> = emptyArray()
    private var laneLastTop: Array<DanmakuItem?> = emptyArray()
    private var laneLastBottom: Array<DanmakuItem?> = emptyArray()
    private var cacheProbeCursor: Int = 0

    // ---- Draw (main thread only) ----
    private val drawFontMetrics = Paint.FontMetrics()
    private val drawFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        isSubpixelText = true
    }
    private val drawStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        isSubpixelText = true
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Smooth-first by default for TVs (may be adjusted by overload strategies later).
        isFilterBitmap = true
    }
    private val emotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val emotePlaceholderFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val emotePlaceholderStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density) // ~1dp
    }
    private val emoteTmpRectF = RectF()
    private val inlineLikeIcon by lazy(LazyThreadSafetyMode.NONE) {
        AppCompatResources.getDrawable(BlblApp.instance, R.drawable.ic_action_like)?.mutate()?.apply {
            DrawableCompat.setTint(DrawableCompat.wrap(this), HIGH_LIKE_ICON_COLOR)
        }
    }

    override fun updateViewport(width: Int, height: Int, topInsetPx: Int, bottomInsetPx: Int) {
        viewportWidth = width.coerceAtLeast(0)
        viewportHeight = height.coerceAtLeast(0)
        viewportTopInsetPx = topInsetPx.coerceAtLeast(0)
        viewportBottomInsetPx = bottomInsetPx.coerceAtLeast(0)
        rebuildRequested = true
    }

    override fun updateConfig(newConfig: DanmakuConfig) {
        synchronized(actionStateLock) {
            config = newConfig
            val tsPx = sp(newConfig.textSizeSp).coerceAtLeast(1f)
            val newStrokeWidthPx = newConfig.strokeWidthPx.coerceAtLeast(0).toFloat()
            val newTypeface = newConfig.fontWeight.typeface

            val oldTs = textSizePx
            val oldStrokeWidthPx = strokeWidthPx
            val oldTypeface = actionPaint.typeface

            textSizePx = tsPx
            strokeWidthPx = newStrokeWidthPx
            outlinePadPx = max(1f, newStrokeWidthPx / 2f)

            actionPaint.textSize = tsPx
            if (actionPaint.typeface != newTypeface) actionPaint.typeface = newTypeface

            val styleChanged = oldTs != tsPx || oldStrokeWidthPx != newStrokeWidthPx || oldTypeface != newTypeface
            if (styleChanged) {
                cacheStyleGeneration++
                measureGeneration++
                // Invalidate current caches to avoid mixing styles.
                val releaseAt = currentUiFrameId + 1
                val size = active.size
                for (i in 0 until size) {
                    val a = active[i]
                    val bmp = a.cacheBitmap
                    if (bmp != null) {
                        cacheManager.enqueueRelease(bmp, releaseAtFrameId = releaseAt)
                        a.cacheBitmap = null
                    }
                    a.cacheState = DanmakuCacheState.Init
                    a.cacheGeneration = -1
                }
            }
            rebuildRequested = true
        }
    }

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, displayMetrics)

    override fun stepTime(positionMs: Long, uiFrameId: Int) {
        currentPositionMs = positionMs.coerceAtLeast(0L)
        currentUiFrameId = uiFrameId
    }

    override fun currentPositionMs(): Long = currentPositionMs

    override fun drainReleasedBitmaps(uiFrameId: Int) {
        cacheManager.drainReleasedBitmaps(uiFrameId)
    }

    override fun preAct() {
        // No-op: action thread owns state, draw thread only consumes snapshots.
    }

    override fun act() {
        synchronized(actionStateLock) {
            val cfg = config
            if (!cfg.enabled) {
                clearActives()
                pending.clear()
                resetLaneState()
                debugPendingCount = 0
                debugNextAtMs = null
                publishEmptySnapshot()
                return
            }

            val width = viewportWidth
            val height = viewportHeight
            if (width <= 0 || height <= 0) {
                clearActives()
                pending.clear()
                resetLaneState()
                debugPendingCount = 0
                debugNextAtMs = null
                publishEmptySnapshot()
                return
            }

            val outlinePad = outlinePadPx
            val rawNowMs = currentPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val nowMs = if (rawNowMs >= lastNowMs) rawNowMs else lastNowMs
            lastNowMs = nowMs

            val topInset = viewportTopInsetPx.coerceIn(0, height)
            val bottomInset = viewportBottomInsetPx.coerceIn(0, height - topInset)
            val availableHeight = (height - topInset - bottomInset).coerceAtLeast(0)

            actionPaint.textSize = textSizePx
            actionPaint.getFontMetrics(actionFontMetrics)
            val textBoxHeight = (actionFontMetrics.descent - actionFontMetrics.ascent) + outlinePad * 2f
            val baseLaneHeight = max(18f, textBoxHeight * 1.15f)
            val laneHeight = max(textBoxHeight, baseLaneHeight * cfg.laneDensity.laneHeightFactor)
            val usableHeight = (availableHeight * cfg.area.coerceIn(0f, 1f)).toInt().coerceAtLeast(0)
            val laneCount = max(1, (usableHeight / laneHeight).toInt())
            val maxYTop = (topInset + usableHeight - textBoxHeight).toFloat().coerceAtLeast(topInset.toFloat())

            val geometryChanged =
                configuredLaneCount != laneCount ||
                    configuredTopInsetPx != topInset ||
                    configuredUsableHeightPx != usableHeight ||
                    kotlin.math.abs(configuredLaneHeightPx - laneHeight) > 0.01f ||
                    kotlin.math.abs(configuredMaxYTopPx - maxYTop) > 0.01f

            if (geometryChanged) {
                configuredLaneCount = laneCount
                configuredLaneHeightPx = laneHeight
                configuredTopInsetPx = topInset
                configuredUsableHeightPx = usableHeight
                configuredMaxYTopPx = maxYTop
                rebuildRequested = true
            }

            val rollingDurationMs = computeRollingDurationMs(speedLevel = cfg.speedLevel)
            val fixedDurationMs = FIXED_DURATION_MS

            if (rebuildRequested) {
                rebuildScene(
                    nowMs = nowMs,
                    width = width,
                    outlinePad = outlinePad,
                    rollingDurationMs = rollingDurationMs,
                    fixedDurationMs = fixedDurationMs,
                    laneCount = laneCount,
                    laneHeight = laneHeight,
                    topInset = topInset,
                    maxYTop = maxYTop,
                )
            } else {
                pruneExpired(width = width, nowMs = nowMs)
                processPendingItems(
                    nowMs = nowMs,
                    width = width,
                    outlinePad = outlinePad,
                    rollingDurationMs = rollingDurationMs,
                    fixedDurationMs = fixedDurationMs,
                    laneCount = laneCount,
                    laneHeight = laneHeight,
                    topInset = topInset,
                    maxYTop = maxYTop,
                )
                spawnNewItems(
                    nowMs = nowMs,
                    width = width,
                    outlinePad = outlinePad,
                    rollingDurationMs = rollingDurationMs,
                    fixedDurationMs = fixedDurationMs,
                    laneCount = laneCount,
                    laneHeight = laneHeight,
                    topInset = topInset,
                    maxYTop = maxYTop,
                )
            }

            requestCacheBuilds(outlinePad = outlinePad, cfg = cfg)
            debugPendingCount = pending.size
            debugNextAtMs = items.getOrNull(index)?.timeMs()
            publishSnapshotIfDirty(nowMs)
        }
    }

    override fun renderSnapshot(): RenderSnapshot =
        latestSnapshot.also {
            it.positionMs = currentPositionMs
            it.pendingCount = debugPendingCount
            it.nextAtMs = debugNextAtMs
        }

    override fun draw(canvas: Canvas, snapshot: RenderSnapshot, config: DanmakuConfig) {
        val cfg = config
        if (!cfg.enabled) return

        // Update draw paints lazily (main thread).
        val ts = textSizePx
        if (drawFill.textSize != ts) {
            drawFill.textSize = ts
            drawStroke.textSize = ts
        }
        val desiredTypeface = cfg.fontWeight.typeface
        if (drawFill.typeface != desiredTypeface) {
            drawFill.typeface = desiredTypeface
            drawStroke.typeface = desiredTypeface
        }
        if (drawStroke.strokeWidth != strokeWidthPx) {
            drawStroke.strokeWidth = strokeWidthPx
        }

        val outlinePad = outlinePadPx
        val opacityAlpha = (cfg.opacity * 255f).roundToInt().coerceIn(0, 255)
        bitmapPaint.alpha = opacityAlpha
        emotePaint.alpha = opacityAlpha
        run {
            val fillA = ((opacityAlpha * 0x22) / 255).coerceIn(0, 255)
            val strokeA = ((opacityAlpha * 0x66) / 255).coerceIn(0, 255)
            emotePlaceholderFill.color = (fillA shl 24) or 0x000000
            emotePlaceholderStroke.color = (strokeA shl 24) or 0xFFFFFF
        }

        drawFill.getFontMetrics(drawFontMetrics)
        val baselineOffset = outlinePad - drawFontMetrics.ascent
        val emoteSizePx = (drawFontMetrics.descent - drawFontMetrics.ascent).coerceAtLeast(1f)
        val styleGen = cacheStyleGeneration
        val width = viewportWidth.coerceAtLeast(0)
        val nowMs = currentPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        var cachedDrawn = 0
        var fallbackDrawn = 0
        for (i in 0 until snapshot.count) {
            val item = snapshot.items[i] ?: continue
            val x =
                when (item.kind) {
                    DanmakuKind.SCROLL -> scrollX(width = width, nowMs = nowMs, startTimeMs = item.startTimeMs, pxPerMs = item.pxPerMs)
                    DanmakuKind.TOP -> centerX(width = width, contentWidth = item.textWidthPx)
                    DanmakuKind.BOTTOM -> centerX(width = width, contentWidth = item.textWidthPx)
                }
            val yTop = snapshot.yTop[i]
            val bmp = item.cacheBitmap
            if (bmp != null && !bmp.isRecycled && item.cacheGeneration == styleGen) {
                canvas.drawBitmap(bmp, x, yTop, bitmapPaint)
                cachedDrawn++
                continue
            }
            fallbackDrawn++
            drawTextDirect(
                canvas = canvas,
                item = item,
                x = x,
                yTop = yTop,
                outlinePad = outlinePad,
                baselineOffset = baselineOffset,
                opacityAlpha = opacityAlpha,
                emoteSizePx = emoteSizePx,
            )
        }
        lastDrawCachedCount = cachedDrawn
        lastDrawFallbackCount = fallbackDrawn
    }

    override fun setDanmakus(list: List<Danmaku>) {
        synchronized(actionStateLock) {
            clearActives()
            resetLaneState()
            pending.clear()
            items =
                list
                    .sortedBy { it.timeMs }
                    .mapTo(ArrayList(list.size.coerceAtLeast(0))) { DanmakuItem(it) }
            index = 0
            lastNowMs = 0
            rebuildRequested = true
            debugPendingCount = 0
            debugNextAtMs = items.firstOrNull()?.timeMs()
            publishEmptySnapshot()
        }
    }

    override fun appendDanmakus(list: List<Danmaku>, alreadySorted: Boolean) {
        synchronized(actionStateLock) {
        if (list.isEmpty()) return
        if (items.isEmpty()) {
            setDanmakus(list)
            return
        }
        val newItems =
            if (alreadySorted) {
                list
            } else {
                list.sortedBy { it.timeMs }
            }
        val lastTime = items.lastOrNull()?.timeMs() ?: Int.MIN_VALUE
        if (newItems.firstOrNull()?.timeMs ?: Int.MIN_VALUE >= lastTime) {
            for (d in newItems) items.add(DanmakuItem(d))
            debugNextAtMs = items.getOrNull(index)?.timeMs()
            return
        }
        // Rare: merge & reset.
        for (d in newItems) items.add(DanmakuItem(d))
        items.sortBy { it.timeMs() }
        rebuildRequested = true
        seekTo(currentPositionMs)
        }
    }

    override fun trimToMax(maxItems: Int) {
        synchronized(actionStateLock) {
        if (maxItems <= 0) return
        val drop = items.size - maxItems
        if (drop <= 0) return
        items = items.subList(drop, items.size).toMutableList()
        index = (index - drop).coerceIn(0, items.size)
        debugNextAtMs = items.getOrNull(index)?.timeMs()
        }
    }

    override fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long) {
        synchronized(actionStateLock) {
        if (items.isEmpty()) return
        val min = minTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val max = maxTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (max <= min) return

        val start = lowerBound(min)
        val end = lowerBound(max)
        if (start <= 0 && end >= items.size) return
        if (start >= end) {
            items.clear()
            index = 0
            clearActives()
            resetLaneState()
            pending.clear()
            lastNowMs = 0
            rebuildRequested = true
            publishEmptySnapshot()
            return
        }
        items = items.subList(start, end).toMutableList()
        index = (index - start).coerceIn(0, items.size)
        rebuildRequested = true
        seekTo(currentPositionMs)
        }
    }

    override fun seekTo(positionMs: Long) {
        synchronized(actionStateLock) {
            val pos = positionMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            rebuildScene(
                nowMs = pos,
                width = viewportWidth.coerceAtLeast(0),
                outlinePad = outlinePadPx,
                rollingDurationMs = computeRollingDurationMs(config.speedLevel),
                fixedDurationMs = FIXED_DURATION_MS,
                laneCount = configuredLaneCount.coerceAtLeast(1),
                laneHeight = configuredLaneHeightPx.takeIf { it > 0f } ?: max(18f, textSizePx * 1.15f),
                topInset = configuredTopInsetPx,
                maxYTop = configuredMaxYTopPx.coerceAtLeast(configuredTopInsetPx.toFloat()),
            )
            publishSnapshotIfDirty(pos)
        }
    }

    override fun clear() {
        synchronized(actionStateLock) {
            clearActives()
            pending.clear()
            resetLaneState()
            rebuildRequested = true
            debugPendingCount = 0
            debugNextAtMs = null
            publishEmptySnapshot()
        }
    }

    override fun release() {
        synchronized(actionStateLock) {
            clear()
        }
    }

    private fun clearActives() {
        if (active.isEmpty()) return
        val releaseAt = currentUiFrameId + 1
        for (i in active.size - 1 downTo 0) {
            val a = active.removeAt(i)
            releaseItemCache(a, releaseAtFrameId = releaseAt)
        }
        cacheProbeCursor = 0
        snapshotDirty = true
    }

    private fun resetLaneState() {
        if (laneLastScroll.isNotEmpty()) java.util.Arrays.fill(laneLastScroll, null)
        if (laneLastTop.isNotEmpty()) java.util.Arrays.fill(laneLastTop, null)
        if (laneLastBottom.isNotEmpty()) java.util.Arrays.fill(laneLastBottom, null)
    }

    private fun publishEmptySnapshot() {
        val out = writableSnapshot()
        out.clear()
        latestSnapshot = out
        snapshotDirty = false
    }

    private fun writableSnapshot(): RenderSnapshot = if (latestSnapshot === snapshotA) snapshotB else snapshotA

    private fun publishSnapshotIfDirty(nowMs: Int) {
        if (!snapshotDirty) return
        val out = writableSnapshot()
        out.clear()
        out.ensureCapacity(active.size)
        out.positionMs = nowMs.toLong()
        out.pendingCount = pending.size
        out.nextAtMs = items.getOrNull(index)?.timeMs()
        var count = 0
        for (item in active) {
            out.items[count] = item
            out.yTop[count] = item.layoutTopPx
            out.textWidth[count] = item.textWidthPx
            count++
        }
        out.count = count
        latestSnapshot = out
        snapshotDirty = false
    }

    private fun rebuildScene(
        nowMs: Int,
        width: Int,
        outlinePad: Float,
        rollingDurationMs: Int,
        fixedDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
    ) {
        clearActives()
        pending.clear()
        ensureLaneBuffers(laneCount)
        resetLaneState()
        rebuildRequested = false
        lastNowMs = nowMs
        if (width <= 0 || laneCount <= 0) {
            index = lowerBound(nowMs)
            debugPendingCount = 0
            debugNextAtMs = items.getOrNull(index)?.timeMs()
            snapshotDirty = true
            return
        }
        index = lowerBound((nowMs - max(rollingDurationMs, fixedDurationMs)).coerceAtLeast(0))
        while (index < items.size && items[index].timeMs() <= nowMs) {
            val item = items[index]
            index++
            if (item.data.text.isBlank()) continue
            if (item.timeMs() < nowMs - max(rollingDurationMs, fixedDurationMs)) continue
            tryAdmitItem(
                item = item,
                nowMs = nowMs,
                width = width,
                outlinePad = outlinePad,
                rollingDurationMs = rollingDurationMs,
                fixedDurationMs = fixedDurationMs,
                laneCount = laneCount,
                laneHeight = laneHeight,
                topInset = topInset,
                maxYTop = maxYTop,
                allowPending = false,
            )
        }
        debugPendingCount = pending.size
        debugNextAtMs = items.getOrNull(index)?.timeMs()
        snapshotDirty = true
    }

    private fun pruneExpired(width: Int, nowMs: Int) {
        if (active.isEmpty()) return
        val size = active.size
        var write = 0
        val releaseAt = currentUiFrameId + 1
        for (read in 0 until size) {
            val a = active[read]
            val keep = !isExpired(a, width = width, nowMs = nowMs)
            if (!keep) {
                clearLaneReferenceIfMatch(a)
                releaseItemCache(a, releaseAtFrameId = releaseAt)
                snapshotDirty = true
                continue
            }
            if (write != read) active[write] = a
            write++
        }
        if (write < size) {
            active.subList(write, size).clear()
        }
        if (cacheProbeCursor >= active.size) cacheProbeCursor = 0
    }

    private fun processPendingItems(
        nowMs: Int,
        width: Int,
        outlinePad: Float,
        rollingDurationMs: Int,
        fixedDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
    ) {
        if (pending.isEmpty()) return
        val pendingCount = pending.size
        var processed = 0
        var indexInQueue = 0
        while (indexInQueue < pendingCount && pending.isNotEmpty()) {
            val entry = pending.removeFirst()
            indexInQueue++
            if (entry.nextTryMs > nowMs) {
                pending.addLast(entry)
                continue
            }
            if (processed >= MAX_PENDING_RETRY_PER_FRAME) {
                pending.addLast(entry)
                continue
            }
            processed++
            val admitted =
                tryAdmitItem(
                    item = entry.item,
                    nowMs = nowMs,
                    width = width,
                    outlinePad = outlinePad,
                    rollingDurationMs = rollingDurationMs,
                    fixedDurationMs = fixedDurationMs,
                    laneCount = laneCount,
                    laneHeight = laneHeight,
                    topInset = topInset,
                    maxYTop = maxYTop,
                    allowPending = false,
                )
            if (admitted) continue
            val age = nowMs - entry.firstTryMs
            if (entry.retryCount >= MAX_PENDING_RETRY_COUNT || age >= MAX_DELAY_MS) continue
            entry.retryCount += 1
            entry.nextTryMs = nowMs + DELAY_STEP_MS
            pending.addLast(entry)
        }
    }

    private fun spawnNewItems(
        nowMs: Int,
        width: Int,
        outlinePad: Float,
        rollingDurationMs: Int,
        fixedDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
    ) {
        skipOld(nowMs, rollingDurationMs)
        dropIfLagging(nowMs)
        var spawnAttempts = 0
        while (index < items.size && items[index].timeMs() <= nowMs) {
            if (spawnAttempts >= MAX_SPAWN_PER_FRAME) break
            val item = items[index]
            index++
            spawnAttempts++
            if (item.data.text.isBlank()) continue
            tryAdmitItem(
                item = item,
                nowMs = nowMs,
                width = width,
                outlinePad = outlinePad,
                rollingDurationMs = rollingDurationMs,
                fixedDurationMs = fixedDurationMs,
                laneCount = laneCount,
                laneHeight = laneHeight,
                topInset = topInset,
                maxYTop = maxYTop,
                allowPending = true,
            )
        }
    }

    private fun tryAdmitItem(
        item: DanmakuItem,
        nowMs: Int,
        width: Int,
        outlinePad: Float,
        rollingDurationMs: Int,
        fixedDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
        allowPending: Boolean,
    ): Boolean {
        val textWidth = measureTextWidth(item, outlinePad)
        val kind = kindOf(item.data)
        val marginPx = max(12f, (textSizePx + outlinePad * 2f) * 0.6f)
        val admitted =
            when (kind) {
                DanmakuKind.SCROLL ->
                    trySpawnScroll(
                        item = item,
                        nowMs = nowMs,
                        width = width,
                        textWidth = textWidth,
                        rollingDurationMs = rollingDurationMs,
                        laneCount = laneCount,
                        laneHeight = laneHeight,
                        topInset = topInset,
                        maxYTop = maxYTop,
                        marginPx = marginPx,
                    )
                DanmakuKind.TOP ->
                    trySpawnFixed(
                        item = item,
                        kind = DanmakuKind.TOP,
                        nowMs = nowMs,
                        textWidth = textWidth,
                        durationMs = fixedDurationMs,
                        laneCount = laneCount,
                        laneHeight = laneHeight,
                        topInset = topInset,
                        maxYTop = maxYTop,
                    )
                DanmakuKind.BOTTOM ->
                    trySpawnFixed(
                        item = item,
                        kind = DanmakuKind.BOTTOM,
                        nowMs = nowMs,
                        textWidth = textWidth,
                        durationMs = fixedDurationMs,
                        laneCount = laneCount,
                        laneHeight = laneHeight,
                        topInset = topInset,
                        maxYTop = maxYTop,
                    )
            }
        if (!admitted && allowPending) {
            enqueuePending(item = item, nowMs = nowMs)
        }
        return admitted
    }

    private fun trySpawnScroll(
        item: DanmakuItem,
        nowMs: Int,
        width: Int,
        textWidth: Float,
        rollingDurationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
        marginPx: Float,
    ): Boolean {
        val distancePx = (width.toFloat() + textWidth).coerceAtLeast(0f)
        val rawPx = distancePx / rollingDurationMs.toFloat()
        val shortPx = width.toFloat() / rollingDurationMs.toFloat()
        val maxPx = shortPx * MAX_LONG_SCROLL_SPEED_RATIO
        val pxNew = min(rawPx, maxPx)
        val durationMs =
            computeScrollDurationMs(
                distancePx = distancePx,
                pxPerMs = pxNew,
                fallbackDurationMs = rollingDurationMs,
            )
        for (lane in 0 until laneCount) {
            val prev = laneLastScroll[lane]
            if (prev != null && isExpired(prev, width = width, nowMs = nowMs)) {
                laneLastScroll[lane] = null
            }
            val rear = laneLastScroll[lane]
            if (rear == null) {
                activate(
                    item = item,
                    kind = DanmakuKind.SCROLL,
                    lane = lane,
                    textWidth = textWidth,
                    pxPerMs = pxNew,
                    durationMs = durationMs,
                    startTimeMs = nowMs,
                    layoutTopPx = (topInset.toFloat() + laneHeight * lane).coerceAtMost(maxYTop),
                )
                laneLastScroll[lane] = item
                return true
            }
            val tailPrev = scrollX(width = width, nowMs = nowMs, startTimeMs = rear.startTimeMs, pxPerMs = rear.pxPerMs) + rear.textWidthPx
            if (isScrollLaneAvailable(width.toFloat(), nowMs, rear, tailPrev, pxNew, marginPx)) {
                activate(
                    item = item,
                    kind = DanmakuKind.SCROLL,
                    lane = lane,
                    textWidth = textWidth,
                    pxPerMs = pxNew,
                    durationMs = durationMs,
                    startTimeMs = nowMs,
                    layoutTopPx = (topInset.toFloat() + laneHeight * lane).coerceAtMost(maxYTop),
                )
                laneLastScroll[lane] = item
                return true
            }
        }
        return false
    }

    private fun trySpawnFixed(
        item: DanmakuItem,
        kind: DanmakuKind,
        nowMs: Int,
        textWidth: Float,
        durationMs: Int,
        laneCount: Int,
        laneHeight: Float,
        topInset: Int,
        maxYTop: Float,
    ): Boolean {
        val lanes =
            when (kind) {
                DanmakuKind.TOP -> laneLastTop
                DanmakuKind.BOTTOM -> laneLastBottom
                DanmakuKind.SCROLL -> return false
            }
        for (lane in 0 until laneCount) {
            val prev = lanes[lane]
            if (prev != null && isExpired(prev, width = viewportWidth, nowMs = nowMs)) {
                lanes[lane] = null
            }
            if (lanes[lane] != null) continue
            activate(
                item = item,
                kind = kind,
                lane = lane,
                textWidth = textWidth,
                pxPerMs = 0f,
                durationMs = durationMs,
                startTimeMs = nowMs,
                layoutTopPx =
                    when (kind) {
                        DanmakuKind.TOP -> (topInset.toFloat() + laneHeight * lane).coerceAtMost(maxYTop)
                        DanmakuKind.BOTTOM -> (maxYTop - laneHeight * lane).coerceAtLeast(topInset.toFloat())
                        DanmakuKind.SCROLL -> topInset.toFloat()
                    },
            )
            lanes[lane] = item
            return true
        }
        return false
    }

    private fun requestCacheBuilds(
        outlinePad: Float,
        cfg: DanmakuConfig,
    ) {
        if (active.isEmpty()) return
        if (cacheManager.queueDepth() >= MAX_CACHE_QUEUE_DEPTH) return
        val style =
            CacheStyle(
                textSizePx = textSizePx,
                fontWeight = cfg.fontWeight,
                strokeWidthPx = strokeWidthPx,
                outlinePadPx = outlinePad,
                showHighLikeIcon = cfg.showHighLikeIcon,
                generation = cacheStyleGeneration,
            )
        val releaseAtFrameId = currentUiFrameId + 1
        val scanCount = min(active.size, MAX_CACHE_SCAN_PER_FRAME)
        var requested = 0
        for (offset in 0 until scanCount) {
            if (requested >= MAX_CACHE_REQUESTS_PER_FRAME) break
            if (cacheManager.queueDepth() >= MAX_CACHE_QUEUE_DEPTH) break
            val indexInActive = (cacheProbeCursor + offset) % active.size
            val item = active[indexInActive]
            val bmp = item.cacheBitmap
            val hasValidCache = bmp != null && !bmp.isRecycled && item.cacheGeneration == style.generation
            if (hasValidCache) continue
            if (item.cacheState == DanmakuCacheState.Rendering) continue
            if (!inlineImagesReadyOrPrefetch(item)) continue
            item.cacheState = DanmakuCacheState.Rendering
            cacheManager.requestBuildCache(
                item = item,
                textWidthPx = item.textWidthPx,
                style = style,
                releaseAtFrameId = releaseAtFrameId,
            )
            requested++
        }
        cacheProbeCursor = if (active.isEmpty()) 0 else (cacheProbeCursor + scanCount) % active.size
    }

    private fun releaseItemCache(item: DanmakuItem, releaseAtFrameId: Int) {
        val bmp = item.cacheBitmap
        if (bmp != null) {
            cacheManager.enqueueRelease(bmp, releaseAtFrameId = releaseAtFrameId)
            item.cacheBitmap = null
        }
        item.cacheState = DanmakuCacheState.Init
        item.cacheGeneration = -1
    }

    private fun clearLaneReferenceIfMatch(item: DanmakuItem) {
        when (item.kind) {
            DanmakuKind.SCROLL -> if (item.lane in laneLastScroll.indices && laneLastScroll[item.lane] === item) laneLastScroll[item.lane] = null
            DanmakuKind.TOP -> if (item.lane in laneLastTop.indices && laneLastTop[item.lane] === item) laneLastTop[item.lane] = null
            DanmakuKind.BOTTOM -> if (item.lane in laneLastBottom.indices && laneLastBottom[item.lane] === item) laneLastBottom[item.lane] = null
        }
    }

    private fun isExpired(
        item: DanmakuItem,
        width: Int,
        nowMs: Int,
    ): Boolean {
        val elapsed = nowMs - item.startTimeMs
        if (elapsed >= item.durationMs) return true
        if (item.kind != DanmakuKind.SCROLL) return false
        return scrollX(width = width, nowMs = nowMs, startTimeMs = item.startTimeMs, pxPerMs = item.pxPerMs) + item.textWidthPx < 0f
    }

    private fun inlineImagesReadyOrPrefetch(item: DanmakuItem): Boolean {
        val text = item.data.text
        if ((!config.showHighLikeIcon || !item.data.isHighLiked) && !text.contains('[')) return true
        val segments =
            item.inlineSegments
                ?: run {
                    val parsed = parseInlineSegments(item) ?: return true
                    if (shouldCacheInlineSegments(item)) item.inlineSegments = parsed
                    parsed
                }
        var ready = true
        for (seg in segments) {
            if (seg !is DanmakuInlineSegment.Emote) continue
            val bmp = EmoteBitmapLoader.getCached(seg.url)
            if (bmp != null && !bmp.isRecycled) continue
            ready = false
            EmoteBitmapLoader.prefetch(seg.url)
        }
        return ready
    }

    private fun scrollX(width: Int, nowMs: Int, startTimeMs: Int, pxPerMs: Float): Float {
        val elapsed = (nowMs - startTimeMs).coerceAtLeast(0)
        return width.toFloat() - elapsed * pxPerMs
    }

    private fun isScrollLaneAvailable(
        width: Float,
        nowMs: Int,
        front: DanmakuItem,
        tailPrev: Float,
        pxNew: Float,
        marginPx: Float,
    ): Boolean {
        val elapsedPrev = nowMs - front.startTimeMs
        val prevRemaining = front.durationMs - elapsedPrev
        if (prevRemaining <= 0) return true
        if (tailPrev + marginPx > width) return false
        val pxPrev = front.pxPerMs
        if (pxNew <= pxPrev) return true
        val gap0 = (width - tailPrev - marginPx).coerceAtLeast(0f)
        val maxSafe = (pxNew - pxPrev) * prevRemaining
        return gap0 >= maxSafe
    }

    private fun activate(
        item: DanmakuItem,
        kind: DanmakuKind,
        lane: Int,
        textWidth: Float,
        pxPerMs: Float,
        durationMs: Int,
        startTimeMs: Int,
        layoutTopPx: Float,
    ) {
        item.kind = kind
        item.lane = lane
        item.textWidthPx = textWidth
        item.pxPerMs = pxPerMs
        item.durationMs = durationMs
        item.startTimeMs = startTimeMs
        item.layoutTopPx = layoutTopPx
        active.add(item)
        snapshotDirty = true
    }

    private fun computeScrollDurationMs(distancePx: Float, pxPerMs: Float, fallbackDurationMs: Int): Int {
        val safeFallback = fallbackDurationMs.coerceAtLeast(1)
        if (!distancePx.isFinite() || distancePx <= 0f) return safeFallback
        if (!pxPerMs.isFinite() || pxPerMs <= 0f) return safeFallback
        val travel = ceil((distancePx / pxPerMs).toDouble()).toLong().coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        return max(safeFallback, travel)
    }

    private fun skipOld(nowMs: Int, rollingDurationMs: Int) {
        val ignoreBefore = nowMs - rollingDurationMs
        while (index < items.size && items[index].timeMs() < ignoreBefore) {
            index++
        }
    }

    private fun dropIfLagging(nowMs: Int) {
        val dropBefore = nowMs - MAX_CATCH_UP_LAG_MS
        while (index < items.size && items[index].timeMs() < dropBefore) {
            index++
        }
    }

    private fun enqueuePending(item: DanmakuItem, nowMs: Int) {
        if (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(
            PendingSpawn(
                item = item,
                nextTryMs = nowMs + DELAY_STEP_MS,
                firstTryMs = nowMs,
                retryCount = 0,
            ),
        )
    }

    private data class PendingSpawn(
        val item: DanmakuItem,
        var nextTryMs: Int,
        val firstTryMs: Int,
        var retryCount: Int,
    )

    private fun ensureLaneBuffers(laneCount: Int) {
        if (laneLastScroll.size != laneCount) laneLastScroll = arrayOfNulls(laneCount)
        if (laneLastTop.size != laneCount) laneLastTop = arrayOfNulls(laneCount)
        if (laneLastBottom.size != laneCount) laneLastBottom = arrayOfNulls(laneCount)
    }

    private fun kindOf(d: Danmaku): DanmakuKind =
        when (d.mode) {
            5 -> DanmakuKind.TOP
            4 -> DanmakuKind.BOTTOM
            else -> DanmakuKind.SCROLL
        }

    private fun measureTextWidth(item: DanmakuItem, outlinePad: Float): Float {
        if (item.measureGeneration == measureGeneration && item.measuredWidthPx.isFinite() && item.measuredWidthPx >= 0f) {
            return item.measuredWidthPx
        }
        val text = item.data.text
        val width =
            if (text.isBlank()) {
                outlinePad * 2f
            } else if ((!config.showHighLikeIcon || !item.data.isHighLiked) && !text.contains('[')) {
                actionPaint.measureText(text) + outlinePad * 2f
            } else {
                measureTextWidthWithInlineSegments(item = item, paint = actionPaint, outlinePad = outlinePad)
            }
        item.measuredWidthPx = width
        item.measureGeneration = measureGeneration
        return width
    }

    private fun measureTextWidthWithInlineSegments(item: DanmakuItem, paint: Paint, outlinePad: Float): Float {
        val text = item.data.text
        paint.getFontMetrics(actionFontMetrics)
        val emoteSizePx = (actionFontMetrics.descent - actionFontMetrics.ascent).coerceAtLeast(1f)

        var w = 0f
        if (config.showHighLikeIcon && item.data.isHighLiked) {
            w += emoteSizePx + inlineIconGapPx(emoteSizePx)
        }
        if (!text.contains('[')) return w + paint.measureText(text) + outlinePad * 2f
        var i = 0
        while (i < text.length) {
            val open = text.indexOf('[', startIndex = i)
            if (open < 0) {
                w += paint.measureText(text, i, text.length)
                break
            }
            val close = text.indexOf(']', startIndex = open + 1)
            if (close < 0) {
                w += paint.measureText(text, i, text.length)
                break
            }
            if (open > i) {
                w += paint.measureText(text, i, open)
            }
            val token = text.substring(open, close + 1)
            val url = ReplyEmotePanelRepository.urlForToken(token)
            if (url != null && url.startsWith("http")) {
                w += emoteSizePx
            } else {
                w += paint.measureText(text, open, close + 1)
            }
            i = close + 1
        }
        return w + outlinePad * 2f
    }

    private fun parseInlineSegments(item: DanmakuItem): List<DanmakuInlineSegment>? {
        val text = item.data.text
        var i = 0
        var lastTextStart = 0
        var hasInline = false
        val out = ArrayList<DanmakuInlineSegment>(8)
        if (config.showHighLikeIcon && item.data.isHighLiked) {
            out.add(DanmakuInlineSegment.HighLikeIcon)
            hasInline = true
        }
        while (i < text.length) {
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

    private fun drawTextDirect(
        canvas: Canvas,
        item: DanmakuItem,
        x: Float,
        yTop: Float,
        outlinePad: Float,
        baselineOffset: Float,
        opacityAlpha: Int,
        emoteSizePx: Float,
    ) {
        val text = item.data.text
        if (text.isBlank()) return

        val drawStrokeEnabled = strokeWidthPx > 0.01f
        val rgb = item.data.color and 0xFFFFFF
        if (drawStrokeEnabled) {
            val strokeAlpha = ((opacityAlpha * 0xCC) / 255).coerceIn(0, 255)
            drawStroke.color = (strokeAlpha shl 24) or 0x000000
        }
        drawFill.color = (opacityAlpha shl 24) or rgb

        val textX = x + outlinePad
        val baseline = yTop + baselineOffset

        val segments =
            item.inlineSegments
                ?: run {
                    val parsed = parseInlineSegments(item)
                    if (parsed != null && shouldCacheInlineSegments(item)) item.inlineSegments = parsed
                    parsed
        }
        if (segments == null) {
            if (drawStrokeEnabled) canvas.drawText(text, textX, baseline, drawStroke)
            canvas.drawText(text, textX, baseline, drawFill)
            return
        }

        val emoteTop = yTop + outlinePad
        val r = (emoteSizePx * 0.18f).coerceIn(2f, 10f)
        val highLikeGapPx = inlineIconGapPx(emoteSizePx)
        var cursorX = textX
        for (seg in segments) {
            when (seg) {
                is DanmakuInlineSegment.Text -> {
                    if (seg.end > seg.start) {
                        if (drawStrokeEnabled) canvas.drawText(text, seg.start, seg.end, cursorX, baseline, drawStroke)
                        canvas.drawText(text, seg.start, seg.end, cursorX, baseline, drawFill)
                        cursorX += drawFill.measureText(text, seg.start, seg.end)
                    }
                }
                is DanmakuInlineSegment.Emote -> {
                    val bmp = EmoteBitmapLoader.getCached(seg.url)
                    if (bmp != null) {
                        emoteTmpRectF.set(cursorX, emoteTop, cursorX + emoteSizePx, emoteTop + emoteSizePx)
                        canvas.drawBitmap(bmp, null, emoteTmpRectF, emotePaint)
                    } else {
                        // Prefetch is throttled elsewhere (later step). For now, do a best-effort prefetch.
                        EmoteBitmapLoader.prefetch(seg.url)
                        emoteTmpRectF.set(cursorX, emoteTop, cursorX + emoteSizePx, emoteTop + emoteSizePx)
                        canvas.drawRoundRect(emoteTmpRectF, r, r, emotePlaceholderFill)
                        canvas.drawRoundRect(emoteTmpRectF, r, r, emotePlaceholderStroke)
                    }
                    cursorX += emoteSizePx
                }
                DanmakuInlineSegment.HighLikeIcon -> {
                    drawInlineLikeIcon(cursorX, emoteTop, emoteSizePx, canvas, opacityAlpha)
                    cursorX += emoteSizePx + highLikeGapPx
                }
            }
        }
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
        alpha: Int,
    ) {
        val icon = inlineLikeIcon ?: return
        icon.setBounds(left.roundToInt(), top.roundToInt(), (left + sizePx).roundToInt(), (top + sizePx).roundToInt())
        icon.alpha = alpha.coerceIn(0, 255)
        icon.draw(canvas)
    }

    private fun lowerBound(pos: Int): Int {
        var l = 0
        var r = items.size
        while (l < r) {
            val m = (l + r) ushr 1
            if (items[m].timeMs() < pos) l = m + 1 else r = m
        }
        return l
    }

    private fun centerX(width: Int, contentWidth: Float): Float {
        if (width <= 0) return 0f
        val x = (width.toFloat() - contentWidth) / 2f
        return x.coerceAtLeast(0f)
    }

    private fun computeRollingDurationMs(speedLevel: Int): Int {
        // Keep the speed scale aligned with the project's previous implementation.
        // (User feedback: new 10 ~= old 4 was too slow.)
        val speed = speedMultiplier(speedLevel)
        val duration = (DEFAULT_ROLLING_DURATION_MS / speed).toInt()
        return duration.coerceIn(MIN_ROLLING_DURATION_MS, MAX_ROLLING_DURATION_MS)
    }

    private fun speedMultiplier(level: Int): Float =
        when (min(10, max(1, level))) {
            1 -> 0.6f
            2 -> 0.8f
            3 -> 0.9f
            4 -> 1.0f
            5 -> 1.2f
            6 -> 1.4f
            7 -> 1.6f
            8 -> 1.9f
            9 -> 2.2f
            else -> 2.6f
        }

    private companion object {
        private val HIGH_LIKE_ICON_COLOR = Color.parseColor("#F6C343")
        private const val DEFAULT_ROLLING_DURATION_MS = 6_000f
        private const val MIN_ROLLING_DURATION_MS = 2_000
        private const val MAX_ROLLING_DURATION_MS = 20_000

        private const val FIXED_DURATION_MS = 4_000
        private const val MAX_LONG_SCROLL_SPEED_RATIO = 1.5f

        private const val DELAY_STEP_MS = 220
        private const val MAX_DELAY_MS = 1_600
        private const val MAX_PENDING = 260
        private const val MAX_SPAWN_PER_FRAME = 48
        private const val MAX_PENDING_RETRY_PER_FRAME = 48
        private const val MAX_PENDING_RETRY_COUNT = 1
        private const val MAX_CATCH_UP_LAG_MS = 1_200

        private const val MAX_CACHE_REQUESTS_PER_FRAME = 8
        private const val MAX_CACHE_SCAN_PER_FRAME = 16
        private const val MAX_CACHE_QUEUE_DEPTH = 48
    }
}
