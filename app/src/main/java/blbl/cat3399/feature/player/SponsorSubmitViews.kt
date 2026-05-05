package blbl.cat3399.feature.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

class SponsorSubmitThumbnailStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {
    private val thumbnailLayoutManager = LinearLayoutManager(context, HORIZONTAL, false)
    private val thumbnailAdapter =
        SponsorSubmitThumbnailAdapter(
            onClick = { position, timeMs -> clickThumbnail(position, timeMs) },
        )
    private val snapHelper = LinearSnapHelper()
    private val placeholderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xCC000000.toInt()
        }
    private val selectedHaloPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x3300A1D6
        }
    private val selectedStrokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(4f)
            color = playerFocusStrokeColor()
        }
    private val overlayRect = RectF()

    private var selectedIndex: Int = -1
    private var aspectWidth: Int = 16
    private var aspectHeight: Int = 9
    private var durationMs: Long = 0L
    private var itemSlotWidthPx: Int = 1
    private var onThumbnailScrubbed: ((timeMs: Long, finished: Boolean) -> Unit)? = null
    private var onThumbnailClicked: ((timeMs: Long) -> Unit)? = null
    private var userScrollActive = false
    private var suppressScrollCallbacks = false
    private var lastReportedTimeMs: Long? = null

    init {
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        itemAnimator = null
        layoutManager = thumbnailLayoutManager
        adapter = thumbnailAdapter
        snapHelper.attachToRecyclerView(this)
        addItemDecoration(
            object : ItemDecoration() {
                override fun onDrawOver(
                    canvas: Canvas,
                    parent: RecyclerView,
                    state: State,
                ) {
                    drawFixedSelection(canvas)
                }
            },
        )
        addOnScrollListener(
            object : OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    when (newState) {
                        SCROLL_STATE_DRAGGING -> {
                            userScrollActive = true
                            this@SponsorSubmitThumbnailStripView.parent?.requestDisallowInterceptTouchEvent(true)
                        }

                        SCROLL_STATE_IDLE -> {
                            if (userScrollActive) {
                                reportCenteredThumbnail(finished = true)
                                userScrollActive = false
                                this@SponsorSubmitThumbnailStripView.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (suppressScrollCallbacks) return
                    updateCenteredSelection()
                    if (userScrollActive) reportCenteredThumbnail(finished = false)
                }
            },
        )
    }

    internal fun setFrameLoader(
        scope: CoroutineScope,
        loader: suspend (timeSec: Int) -> SpriteFrame?,
    ) {
        thumbnailAdapter.setFrameLoader(scope, loader)
    }

    internal fun setTouchCallbacks(
        onThumbnailScrubbed: ((timeMs: Long, finished: Boolean) -> Unit)?,
        onThumbnailClicked: ((timeMs: Long) -> Unit)?,
    ) {
        this.onThumbnailScrubbed = onThumbnailScrubbed
        this.onThumbnailClicked = onThumbnailClicked
    }

    fun setContentAspectRatio(width: Int, height: Int) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (aspectWidth == w && aspectHeight == h) return
        aspectWidth = w
        aspectHeight = h
        thumbnailAdapter.setContentAspectRatio(w, h)
        updateItemMetrics()
    }

    internal fun setThumbnailTimes(
        timesSec: List<Int>,
        selectedIndex: Int,
        durationMs: Long,
    ) {
        this.durationMs = durationMs.coerceAtLeast(0L)
        thumbnailAdapter.submit(timesSec)
        this.selectedIndex = selectedIndex.takeIf { it in timesSec.indices } ?: -1
        thumbnailAdapter.setSelectedIndex(this.selectedIndex)
        updateItemMetrics()
        if (!userScrollActive) centerSelectedThumbnail()
    }

    internal fun clearThumbnails() {
        thumbnailAdapter.submit(emptyList())
        selectedIndex = -1
        durationMs = 0L
        userScrollActive = false
        suppressScrollCallbacks = false
        lastReportedTimeMs = null
        invalidateItemDecorations()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateItemMetrics()
        if (!userScrollActive) post { centerSelectedThumbnail() }
    }

    override fun onDraw(canvas: Canvas) {
        if (thumbnailAdapter.itemCount == 0) {
            drawEmpty(canvas)
            return
        }
        super.onDraw(canvas)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun clickThumbnail(position: Int, timeMs: Long) {
        if (position !in 0 until thumbnailAdapter.itemCount) return
        selectedIndex = position
        thumbnailAdapter.setSelectedIndex(position)
        centerSelectedThumbnail()
        onThumbnailScrubbed?.invoke(timeMs, true)
        onThumbnailClicked?.invoke(timeMs)
        performClick()
    }

    private fun centerSelectedThumbnail() {
        val index = selectedIndex.takeIf { it in 0 until thumbnailAdapter.itemCount } ?: return
        if (width <= 0 || itemSlotWidthPx <= 0) {
            post { centerSelectedThumbnail() }
            return
        }
        suppressScrollCallbacks = true
        thumbnailLayoutManager.scrollToPositionWithOffset(index, 0)
        post {
            updateCenteredSelection()
            suppressScrollCallbacks = false
        }
    }

    private fun reportCenteredThumbnail(finished: Boolean) {
        val timeMs = centeredTimeMs() ?: return
        updateCenteredSelection()
        if (!finished && lastReportedTimeMs == timeMs) return
        lastReportedTimeMs = timeMs
        onThumbnailScrubbed?.invoke(timeMs, finished)
    }

    private fun updateCenteredSelection() {
        val position = centeredPosition() ?: return
        if (position == selectedIndex) return
        selectedIndex = position
        thumbnailAdapter.setSelectedIndex(position)
    }

    private fun centeredPosition(): Int? {
        val count = childCount
        if (count <= 0) return null
        val centerX = width / 2f
        var bestPosition = NO_POSITION
        var bestDistance = Float.MAX_VALUE
        for (i in 0 until count) {
            val child = getChildAt(i)
            val position = getChildAdapterPosition(child)
            if (position == NO_POSITION) continue
            val childCenter = childCenterX(child)
            val distance = abs(childCenter - centerX)
            if (distance < bestDistance) {
                bestDistance = distance
                bestPosition = position
            }
        }
        return bestPosition.takeIf { it != NO_POSITION }
    }

    private fun centeredTimeMs(): Long? {
        val itemCount = thumbnailAdapter.itemCount
        if (itemCount <= 0 || childCount <= 0) return null
        val centerX = width / 2f
        var leftPosition = NO_POSITION
        var leftCenter = -Float.MAX_VALUE
        var rightPosition = NO_POSITION
        var rightCenter = Float.MAX_VALUE
        var nearestPosition = NO_POSITION
        var nearestDistance = Float.MAX_VALUE

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val position = getChildAdapterPosition(child)
            if (position == NO_POSITION) continue
            val childCenter = childCenterX(child)
            val distance = abs(childCenter - centerX)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestPosition = position
            }
            if (childCenter <= centerX && childCenter > leftCenter) {
                leftCenter = childCenter
                leftPosition = position
            }
            if (childCenter >= centerX && childCenter < rightCenter) {
                rightCenter = childCenter
                rightPosition = position
            }
        }

        if (leftPosition != NO_POSITION && rightPosition != NO_POSITION) {
            val leftTime = thumbnailAdapter.timeMsAt(leftPosition)
            val rightTime = thumbnailAdapter.timeMsAt(rightPosition)
            if (leftTime == null || rightTime == null) return null
            if (leftPosition == rightPosition || abs(rightCenter - leftCenter) < 0.001f) return leftTime
            val fraction = ((centerX - leftCenter) / (rightCenter - leftCenter)).coerceIn(0f, 1f)
            return (
                leftTime.toDouble() +
                    (rightTime - leftTime).toDouble() * fraction.toDouble()
            ).roundToLong().coerceToDuration()
        }

        return thumbnailAdapter.timeMsAt(nearestPosition)?.coerceToDuration()
    }

    private fun childCenterX(child: View): Float =
        (thumbnailLayoutManager.getDecoratedLeft(child) + thumbnailLayoutManager.getDecoratedRight(child)) / 2f

    private fun Long.coerceToDuration(): Long {
        val maxMs = durationMs.takeIf { it > 0L } ?: return coerceAtLeast(0L)
        return coerceIn(0L, maxMs)
    }

    private fun drawEmpty(canvas: Canvas) {
        val radius = dp(10f)
        dstRect.set(dp(6f), dp(6f), width - dp(6f), height - dp(6f))
        canvas.drawRoundRect(dstRect, radius, radius, placeholderPaint)
    }

    private fun drawFixedSelection(canvas: Canvas) {
        if (thumbnailAdapter.itemCount <= 0) return
        val rect = centerSelectionRect() ?: return
        val radius = dp(12f)
        val halo = RectF(rect).apply { inset(-dp(5f), -dp(5f)) }
        canvas.drawRoundRect(halo, radius + dp(5f), radius + dp(5f), selectedHaloPaint)
        val strokeRect = RectF(rect)
        val inset = selectedStrokePaint.strokeWidth / 2f
        strokeRect.inset(inset, inset)
        canvas.drawRoundRect(strokeRect, radius, radius, selectedStrokePaint)
    }

    private fun centerSelectionRect(): RectF? {
        if (width <= 0 || height <= 0) return null
        val verticalPadding = dp(5f)
        val selectedScale = 1.07f
        val contentHeight = (height - verticalPadding * 2f).coerceAtLeast(1f)
        val aspect = aspectWidth.toFloat() / aspectHeight.coerceAtLeast(1).toFloat()
        val baseHeight = contentHeight / selectedScale
        val baseWidth = baseHeight * aspect.coerceAtLeast(0.0001f)
        val selectedWidth = (baseWidth * selectedScale).coerceAtMost(width.toFloat())
        val selectedHeight = (baseHeight * selectedScale).coerceAtMost(contentHeight)
        val left = (width - selectedWidth) / 2f
        val top = verticalPadding + ((contentHeight - selectedHeight) / 2f).coerceAtLeast(0f)
        overlayRect.set(left, top, left + selectedWidth, top + selectedHeight)
        return overlayRect
    }

    private fun updateItemMetrics() {
        if (width <= 0 || height <= 0) return
        val gap = dp(10f)
        val verticalPadding = dp(5f)
        val selectedScale = 1.07f
        val contentHeight = (height - verticalPadding * 2f).coerceAtLeast(1f)
        val aspect = aspectWidth.toFloat() / aspectHeight.coerceAtLeast(1).toFloat()
        val itemHeight = contentHeight / selectedScale
        val itemWidth = itemHeight * aspect.coerceAtLeast(0.0001f)
        val slotWidth = (itemWidth + gap).roundToInt().coerceAtLeast(1)
        if (itemSlotWidthPx != slotWidth) {
            itemSlotWidthPx = slotWidth
            thumbnailAdapter.setItemSlotWidth(slotWidth)
        }
        val sidePadding = ((width - slotWidth) / 2f).roundToInt().coerceAtLeast(0)
        if (paddingLeft != sidePadding || paddingRight != sidePadding) {
            setPadding(sidePadding, paddingTop, sidePadding, paddingBottom)
        }
        invalidateItemDecorations()
    }

    private val dstRect = RectF()
    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun playerFocusStrokeColor(): Int = ContextCompat.getColor(context, R.color.blbl_blue)
}

private class SponsorSubmitThumbnailAdapter(
    private val onClick: (position: Int, timeMs: Long) -> Unit,
) : RecyclerView.Adapter<SponsorSubmitThumbnailAdapter.Vh>() {
    private val timesSec = ArrayList<Int>()
    private var selectedIndex: Int = -1
    private var aspectWidth: Int = 16
    private var aspectHeight: Int = 9
    private var itemSlotWidth: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    private var scope: CoroutineScope? = null
    private var frameLoader: (suspend (timeSec: Int) -> SpriteFrame?)? = null

    init {
        setHasStableIds(true)
    }

    fun setFrameLoader(
        scope: CoroutineScope,
        loader: suspend (timeSec: Int) -> SpriteFrame?,
    ) {
        this.scope = scope
        frameLoader = loader
    }

    fun submit(times: List<Int>) {
        if (timesSec == times) return
        timesSec.clear()
        timesSec.addAll(times)
        if (selectedIndex !in timesSec.indices) selectedIndex = -1
        notifyDataSetChanged()
    }

    fun setSelectedIndex(index: Int) {
        selectedIndex = index.takeIf { it in timesSec.indices } ?: -1
    }

    fun setContentAspectRatio(width: Int, height: Int) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (aspectWidth == w && aspectHeight == h) return
        aspectWidth = w
        aspectHeight = h
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    fun setItemSlotWidth(width: Int) {
        val safeWidth = width.coerceAtLeast(1)
        if (itemSlotWidth == safeWidth) return
        itemSlotWidth = safeWidth
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    fun timeMsAt(position: Int): Long? =
        timesSec.getOrNull(position)?.coerceAtLeast(0)?.toLong()?.times(1000L)

    override fun getItemId(position: Int): Long = timesSec[position].toLong()

    override fun getItemCount(): Int = timesSec.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val view = SponsorSubmitThumbnailItemView(parent.context)
        view.layoutParams =
            RecyclerView.LayoutParams(
                itemSlotWidth.coerceAtLeast(1),
                RecyclerView.LayoutParams.MATCH_PARENT,
            )
        return Vh(view)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        val timeSec = timesSec[position].coerceAtLeast(0)
        val boundScope = scope
        val boundLoader = frameLoader
        holder.bind(
            timeSec = timeSec,
            aspectWidth = aspectWidth,
            aspectHeight = aspectHeight,
            itemSlotWidth = itemSlotWidth,
            scope = boundScope,
            frameLoader = boundLoader,
            onClick = { onClick(position, timeSec.toLong() * 1000L) },
        )
    }

    override fun onViewRecycled(holder: Vh) {
        holder.clear()
    }

    class Vh(private val view: SponsorSubmitThumbnailItemView) : RecyclerView.ViewHolder(view) {
        private var frameJob: Job? = null
        private var boundTimeSec: Int = -1

        fun bind(
            timeSec: Int,
            aspectWidth: Int,
            aspectHeight: Int,
            itemSlotWidth: Int,
            scope: CoroutineScope?,
            frameLoader: (suspend (timeSec: Int) -> SpriteFrame?)?,
            onClick: () -> Unit,
        ) {
            boundTimeSec = timeSec
            frameJob?.cancel()
            view.layoutParams =
                (view.layoutParams ?: RecyclerView.LayoutParams(itemSlotWidth, RecyclerView.LayoutParams.MATCH_PARENT)).also {
                    it.width = itemSlotWidth.coerceAtLeast(1)
                    it.height = RecyclerView.LayoutParams.MATCH_PARENT
                }
            view.setContentAspectRatio(aspectWidth, aspectHeight)
            view.setFrame(null)
            view.setOnClickListener { onClick() }

            if (scope == null || frameLoader == null) return
            frameJob =
                scope.launch {
                    val frame =
                        try {
                            frameLoader(timeSec)
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            null
                        }
                    if (boundTimeSec == timeSec && frame != null) {
                        view.setFrame(frame)
                    }
                }
        }

        fun clear() {
            frameJob?.cancel()
            frameJob = null
            boundTimeSec = -1
            view.setOnClickListener(null)
            view.setFrame(null)
        }
    }
}

private class SponsorSubmitThumbnailItemView(context: Context) : View(context) {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xCC000000.toInt()
        }
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val clipPath = Path()
    private var frame: SpriteFrame? = null
    private var aspectWidth: Int = 16
    private var aspectHeight: Int = 9

    init {
        isClickable = true
        isFocusable = false
    }

    fun setFrame(frame: SpriteFrame?) {
        this.frame = frame
        invalidate()
    }

    fun setContentAspectRatio(width: Int, height: Int) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (aspectWidth == w && aspectHeight == h) return
        aspectWidth = w
        aspectHeight = h
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = thumbnailRect()
        val radius = dp(9f)
        val itemFrame = frame
        if (itemFrame == null) {
            canvas.drawRoundRect(rect, radius, radius, placeholderPaint)
            return
        }

        clipPath.reset()
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val checkpoint = canvas.save()
        canvas.clipPath(clipPath)
        updateDrawSourceRect(itemFrame.srcRect)
        canvas.drawBitmap(itemFrame.spriteSheet, srcRect, rect, bitmapPaint)
        canvas.restoreToCount(checkpoint)
    }

    private fun thumbnailRect(): RectF {
        val verticalPadding = dp(5f)
        val selectedScale = 1.07f
        val contentHeight = (height - verticalPadding * 2f).coerceAtLeast(1f)
        val aspect = aspectWidth.toFloat() / aspectHeight.coerceAtLeast(1).toFloat()
        val itemHeight = contentHeight / selectedScale
        val itemWidth = itemHeight * aspect.coerceAtLeast(0.0001f)
        val left = (width - itemWidth) / 2f
        val top = verticalPadding + ((contentHeight - itemHeight) / 2f).coerceAtLeast(0f)
        dstRect.set(left, top, left + itemWidth, top + itemHeight)
        return dstRect
    }

    private fun updateDrawSourceRect(cellRect: Rect) {
        srcRect.set(cellRect)
        val sourceWidth = cellRect.width().coerceAtLeast(1)
        val sourceHeight = cellRect.height().coerceAtLeast(1)
        val targetAspect = aspectWidth.toFloat() / aspectHeight.coerceAtLeast(1).toFloat()
        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        if (abs(sourceAspect - targetAspect) < 0.001f) return

        if (sourceAspect > targetAspect) {
            val croppedWidth = (sourceHeight * targetAspect).roundToInt().coerceIn(1, sourceWidth)
            val inset = ((sourceWidth - croppedWidth) / 2).coerceAtLeast(0)
            srcRect.left = cellRect.left + inset
            srcRect.right = srcRect.left + croppedWidth
            return
        }

        val croppedHeight = (sourceWidth / targetAspect.coerceAtLeast(0.0001f)).roundToInt().coerceIn(1, sourceHeight)
        val inset = ((sourceHeight - croppedHeight) / 2).coerceAtLeast(0)
        srcRect.top = cellRect.top + inset
        srcRect.bottom = srcRect.top + croppedHeight
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}

class SponsorSubmitTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val trackPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x66000000
        }
    private val selectedRangePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.blbl_blue)
            alpha = 150
        }
    private val cursorPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    private val startPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.blbl_blue)
        }
    private val endPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.blbl_red)
        }
    private val markerStrokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
            color = 0xFFFFFFFF.toInt()
            strokeJoin = Paint.Join.ROUND
        }
    private val selectedStrokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2.4f)
            color = playerFocusStrokeColor()
            strokeJoin = Paint.Join.ROUND
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFFFFFF.toInt()
            textSize = dp(13f)
        }
    private val tmpRect = RectF()
    private val markerPath = Path()
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var durationMs: Long = 0L
    private var cursorMs: Long = 0L
    private var markers: List<SponsorSubmitMarker> = emptyList()
    private var selectedMarkerId: Long? = null
    private var movingMarkerId: Long? = null
    private var onCursorChanged: ((timeMs: Long, finished: Boolean) -> Unit)? = null
    private var onMarkerDragStarted: ((SponsorSubmitMarker) -> Unit)? = null
    private var onMarkerDragged: ((marker: SponsorSubmitMarker, timeMs: Long, finished: Boolean) -> Unit)? = null
    private var onMarkerClicked: ((SponsorSubmitMarker) -> Unit)? = null
    private var touchDown = false
    private var touchDragged = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var activeMarkerId: Long? = null

    private data class TimelineMetrics(
        val left: Float,
        val right: Float,
        val centerY: Float,
        val trackHeight: Float,
    )

    init {
        isClickable = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
    }

    internal fun setTouchCallbacks(
        onCursorChanged: ((timeMs: Long, finished: Boolean) -> Unit)?,
        onMarkerDragStarted: ((SponsorSubmitMarker) -> Unit)?,
        onMarkerDragged: ((marker: SponsorSubmitMarker, timeMs: Long, finished: Boolean) -> Unit)?,
        onMarkerClicked: ((SponsorSubmitMarker) -> Unit)?,
    ) {
        this.onCursorChanged = onCursorChanged
        this.onMarkerDragStarted = onMarkerDragStarted
        this.onMarkerDragged = onMarkerDragged
        this.onMarkerClicked = onMarkerClicked
    }

    internal fun setState(
        durationMs: Long,
        cursorMs: Long,
        markers: List<SponsorSubmitMarker>,
        selectedMarkerId: Long?,
        movingMarkerId: Long?,
    ) {
        this.durationMs = durationMs.coerceAtLeast(0L)
        this.cursorMs = cursorMs.coerceAtLeast(0L)
        this.markers = markers
        this.selectedMarkerId = selectedMarkerId
        this.movingMarkerId = movingMarkerId
        invalidate()
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val metrics = timelineMetrics() ?: return
        tmpRect.set(
            metrics.left,
            metrics.centerY - metrics.trackHeight / 2f,
            metrics.right,
            metrics.centerY + metrics.trackHeight / 2f,
        )
        canvas.drawRoundRect(tmpRect, metrics.trackHeight / 2f, metrics.trackHeight / 2f, trackPaint)

        drawCompleteRanges(canvas, metrics.left, metrics.right, metrics.centerY, metrics.trackHeight)
        drawCursor(canvas, metrics.left, metrics.right, metrics.centerY)
        drawMarkers(canvas, metrics.left, metrics.right, metrics.centerY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0L) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                requestFocus()
                touchDown = true
                touchDragged = false
                touchDownX = event.x
                touchDownY = event.y
                isPressed = true

                val marker = markerForTouch(event.x, event.y)
                activeMarkerId = marker?.id
                if (marker != null) {
                    onMarkerDragStarted?.invoke(marker)
                } else {
                    onCursorChanged?.invoke(timeForTouchX(event.x), false)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touchDown) return false
                if (!touchDragged && movedPastTouchSlop(event.x, event.y)) {
                    touchDragged = true
                }

                val marker = activeMarker()
                if (marker != null) {
                    if (touchDragged) {
                        onMarkerDragged?.invoke(marker, timeForTouchX(event.x), false)
                    }
                } else {
                    onCursorChanged?.invoke(timeForTouchX(event.x), false)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!touchDown) return false
                val marker = activeMarker()
                val wasDragged = touchDragged
                val upTime = timeForTouchX(event.x)
                resetTouchState()
                if (marker != null) {
                    if (wasDragged) {
                        onMarkerDragged?.invoke(marker, upTime, true)
                    } else {
                        onMarkerClicked?.invoke(marker)
                        performClick()
                    }
                } else {
                    onCursorChanged?.invoke(upTime, true)
                    if (!wasDragged) performClick()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                resetTouchState()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun timelineMetrics(): TimelineMetrics? {
        val left = paddingLeft + dp(18f)
        val right = width - paddingRight - dp(18f)
        if (right <= left) return null
        return TimelineMetrics(
            left = left,
            right = right,
            centerY = height * 0.52f,
            trackHeight = dp(8f),
        )
    }

    private fun markerForTouch(x: Float, y: Float): SponsorSubmitMarker? {
        val metrics = timelineMetrics() ?: return null
        val hitRadius = dp(22f)
        val top = metrics.centerY - dp(31f)
        val bottom = metrics.centerY + dp(28f)
        if (y < top || y > bottom) return null
        return markers
            .map { marker -> marker to abs(xForTime(marker.timeMs, metrics.left, metrics.right) - x) }
            .filter { (_, distance) -> distance <= hitRadius }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    private fun activeMarker(): SponsorSubmitMarker? {
        val id = activeMarkerId ?: return null
        return markers.firstOrNull { it.id == id }
    }

    private fun timeForTouchX(x: Float): Long {
        val metrics = timelineMetrics() ?: return 0L
        val duration = durationMs.coerceAtLeast(0L)
        if (duration <= 0L) return 0L
        val fraction = ((x - metrics.left) / (metrics.right - metrics.left).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return (duration.toDouble() * fraction.toDouble()).roundToInt().toLong().coerceIn(0L, duration)
    }

    private fun movedPastTouchSlop(x: Float, y: Float): Boolean =
        abs(x - touchDownX) > touchSlopPx || abs(y - touchDownY) > touchSlopPx

    private fun resetTouchState() {
        touchDown = false
        touchDragged = false
        activeMarkerId = null
        isPressed = false
    }

    private fun drawCompleteRanges(canvas: Canvas, left: Float, right: Float, centerY: Float, trackHeight: Float) {
        val byPair = markers.groupBy { it.pairId }
        for ((_, pairMarkers) in byPair) {
            val start = pairMarkers.firstOrNull { it.kind == SponsorSubmitMarkerKind.START } ?: continue
            val end = pairMarkers.firstOrNull { it.kind == SponsorSubmitMarkerKind.END } ?: continue
            if (end.timeMs <= start.timeMs) continue
            val x1 = xForTime(start.timeMs, left, right)
            val x2 = xForTime(end.timeMs, left, right)
            tmpRect.set(x1, centerY - trackHeight / 2f, x2, centerY + trackHeight / 2f)
            canvas.drawRoundRect(tmpRect, trackHeight / 2f, trackHeight / 2f, selectedRangePaint)
        }
    }

    private fun drawCursor(canvas: Canvas, left: Float, right: Float, centerY: Float) {
        val x = xForTime(cursorMs, left, right)
        val active = isFocused
        val h = dp(if (active) 36f else 29f)
        val halfWidth = dp(if (active) 2.1f else 1.45f)
        cursorPaint.color = if (active) playerFocusStrokeColor() else 0x99FFFFFF.toInt()
        tmpRect.set(x - halfWidth, centerY - h / 2f, x + halfWidth, centerY + h / 2f)
        canvas.drawRoundRect(tmpRect, halfWidth, halfWidth, cursorPaint)
    }

    private fun drawMarkers(canvas: Canvas, left: Float, right: Float, centerY: Float) {
        for (marker in markers) {
            val highlighted = marker.id == selectedMarkerId || marker.id == movingMarkerId
            val x = xForTime(marker.timeMs, left, right)
            val markerHeight = dp(13f)
            val halfWidth = markerHeight / sqrt(3f)
            val top = centerY - dp(21f)
            buildRoundedTrianglePath(
                path = markerPath,
                tipX = x,
                tipY = top,
                halfWidth = halfWidth,
                height = markerHeight,
                cornerRadius = markerHeight * 0.28f,
            )
            val paint = if (marker.kind == SponsorSubmitMarkerKind.START) startPaint else endPaint
            canvas.drawPath(markerPath, paint)
            canvas.drawPath(markerPath, markerStrokePaint)
            if (highlighted) canvas.drawPath(markerPath, selectedStrokePaint)
            val label = if (marker.kind == SponsorSubmitMarkerKind.START) "始" else "终"
            val textWidth = textPaint.measureText(label)
            canvas.drawText(label, x - textWidth / 2f, top + markerHeight + dp(16f), textPaint)
        }
    }

    private fun buildRoundedTrianglePath(
        path: Path,
        tipX: Float,
        tipY: Float,
        halfWidth: Float,
        height: Float,
        cornerRadius: Float,
    ) {
        val tip = PointF(tipX, tipY)
        val left = PointF(tipX - halfWidth, tipY + height)
        val right = PointF(tipX + halfWidth, tipY + height)
        val points = arrayOf(tip, right, left)

        path.reset()
        for (i in points.indices) {
            val current = points[i]
            val prev = points[(i - 1 + points.size) % points.size]
            val next = points[(i + 1) % points.size]
            val start = pointToward(current, prev, cornerRadius)
            val end = pointToward(current, next, cornerRadius)
            if (i == 0) {
                path.moveTo(start.x, start.y)
            } else {
                path.lineTo(start.x, start.y)
            }
            path.quadTo(current.x, current.y, end.x, end.y)
        }
        path.close()
    }

    private fun pointToward(from: PointF, to: PointF, distance: Float): PointF {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001f)
        val ratio = (distance / length).coerceIn(0f, 0.45f)
        return PointF(from.x + dx * ratio, from.y + dy * ratio)
    }

    private fun xForTime(timeMs: Long, left: Float, right: Float): Float {
        val duration = durationMs.coerceAtLeast(1L)
        val fraction = (timeMs.toDouble() / duration.toDouble()).coerceIn(0.0, 1.0)
        return left + ((right - left) * fraction).toFloat()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun playerFocusStrokeColor(): Int = ContextCompat.getColor(context, R.color.blbl_blue)
}
