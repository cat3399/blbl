package blbl.cat3399.feature.video.comment

import android.graphics.Bitmap
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class VideoCommentImageView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : AppCompatImageView(context, attrs, defStyleAttr) {
        var onNavigatePrevious: (() -> Unit)? = null
        var onNavigateNext: (() -> Unit)? = null
        var onBlankAreaTap: (() -> Unit)? = null
        var onZoomStateChanged: ((Boolean) -> Unit)? = null

        private val drawMatrix = Matrix()
        private val contentRect = RectF()
        private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val density = resources.displayMetrics.density
        private val scaleDetector =
            ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                        multiTouchActive = true
                        return hasDrawableMetrics()
                    }

                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        multiTouchActive = true
                        setZoomAround(
                            newScale = zoomScale * detector.scaleFactor,
                            focusX = detector.focusX,
                            focusY = detector.focusY,
                        )
                        return true
                    }
                },
            )

        private var baseScale = 1f
        private var zoomScale = MIN_SCALE
        private var offsetX = 0f
        private var offsetY = 0f
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false
        private var multiTouchActive = false
        private var multiTouchGesture = false

        init {
            scaleType = ScaleType.MATRIX
            isClickable = true
        }

        fun isZoomed(): Boolean = zoomScale > MIN_SCALE + SCALE_EPSILON

        fun setSourceDimensions(width: Int?, height: Int?) {
            updateLayoutDimensionRatio(imageDimensionRatio(width = width, height = height))
        }

        fun resetViewport() {
            val wasZoomed = isZoomed()
            zoomScale = MIN_SCALE
            offsetX = 0f
            offsetY = 0f
            dragging = false
            multiTouchActive = false
            multiTouchGesture = false
            updateMatrix()
            notifyZoomStateIfChanged(wasZoomed)
        }

        fun toggleDpadZoom() {
            if (isZoomed()) {
                resetViewport()
            } else {
                setZoomAround(
                    newScale = DPAD_TOGGLE_SCALE,
                    focusX = width / 2f,
                    focusY = height / 2f,
                )
            }
        }

        fun panLeft(): Boolean = panBy(stepPx(horizontal = true), 0f)

        fun panRight(): Boolean = panBy(-stepPx(horizontal = true), 0f)

        fun panUp(): Boolean = panBy(0f, stepPx(horizontal = false))

        fun panDown(): Boolean = panBy(0f, -stepPx(horizontal = false))

        override fun setImageDrawable(drawable: Drawable?) {
            super.setImageDrawable(drawable)
            scheduleViewportReset()
        }

        override fun setImageBitmap(bm: Bitmap?) {
            super.setImageBitmap(bm)
            if (bm != null) {
                setSourceDimensions(width = bm.width, height = bm.height)
            }
            scheduleViewportReset()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateMatrix()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    dragging = false
                    multiTouchActive = false
                    multiTouchGesture = false
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    multiTouchActive = true
                    multiTouchGesture = true
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!multiTouchActive && !scaleDetector.isInProgress) {
                        val x = event.x
                        val y = event.y
                        val totalDx = x - downX
                        val totalDy = y - downY
                        if (!dragging && (abs(totalDx) > touchSlopPx || abs(totalDy) > touchSlopPx)) {
                            dragging = true
                        }
                        if (dragging && isZoomed()) {
                            panBy(deltaX = x - lastX, deltaY = y - lastY)
                        }
                        lastX = x
                        lastY = y
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    val remainingPointers = event.pointerCount - 1
                    multiTouchActive = remainingPointers > 1
                    dragging = false
                    val nextIndex =
                        when {
                            remainingPointers <= 0 -> -1
                            event.actionIndex == 0 -> 1
                            else -> 0
                        }
                    if (nextIndex in 0 until event.pointerCount) {
                        downX = event.getX(nextIndex)
                        downY = event.getY(nextIndex)
                        lastX = downX
                        lastY = downY
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val x = event.x
                    val y = event.y
                    val totalDx = x - downX
                    val totalDy = y - downY
                    val isHorizontalSwipe =
                        !multiTouchGesture &&
                            !scaleDetector.isInProgress &&
                            !isZoomed() &&
                            dragging &&
                            abs(totalDx) >= swipeThresholdPx &&
                            abs(totalDx) > abs(totalDy) * SWIPE_DIRECTION_RATIO
                    val isTap =
                        !multiTouchGesture &&
                            !scaleDetector.isInProgress &&
                            !dragging &&
                            abs(totalDx) <= touchSlopPx &&
                            abs(totalDy) <= touchSlopPx
                    if (isHorizontalSwipe) {
                        if (totalDx > 0f) {
                            onNavigatePrevious?.invoke()
                        } else {
                            onNavigateNext?.invoke()
                        }
                    } else if (isTap) {
                        handleTap(x = x, y = y)
                    }
                    dragging = false
                    multiTouchActive = false
                    multiTouchGesture = false
                    performClick()
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    multiTouchActive = false
                    multiTouchGesture = false
                }
            }

            return true
        }

        override fun performClick(): Boolean = super.performClick()

        private fun handleTap(x: Float, y: Float) {
            if (!isZoomed()) {
                val edgeWidth = width * EDGE_TAP_RATIO
                when {
                    x <= edgeWidth -> {
                        onNavigatePrevious?.invoke()
                        return
                    }

                    x >= width - edgeWidth -> {
                        onNavigateNext?.invoke()
                        return
                    }
                }
            }

            if (!contentRect.contains(x, y)) {
                onBlankAreaTap?.invoke()
            }
        }

        private fun panBy(deltaX: Float, deltaY: Float): Boolean {
            if (!isZoomed() || !hasDrawableMetrics()) return false
            val prevX = offsetX
            val prevY = offsetY
            offsetX += deltaX
            offsetY += deltaY
            updateMatrix()
            return abs(offsetX - prevX) > 0.5f || abs(offsetY - prevY) > 0.5f
        }

        private fun setZoomAround(newScale: Float, focusX: Float, focusY: Float) {
            if (!hasDrawableMetrics()) return
            val clampedScale = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
            val oldScale = zoomScale
            if (abs(clampedScale - oldScale) < 0.0001f) return

            val wasZoomed = isZoomed()
            val focusDx = focusX - width / 2f
            val focusDy = focusY - height / 2f
            val scaleRatio = if (oldScale > 0f) clampedScale / oldScale else 1f
            offsetX = focusDx - (focusDx - offsetX) * scaleRatio
            offsetY = focusDy - (focusDy - offsetY) * scaleRatio
            zoomScale = clampedScale
            if (!isZoomed()) {
                zoomScale = MIN_SCALE
                offsetX = 0f
                offsetY = 0f
            }
            updateMatrix()
            notifyZoomStateIfChanged(wasZoomed)
        }

        private fun stepPx(horizontal: Boolean): Float {
            val size = if (horizontal) width.toFloat() else height.toFloat()
            return max(size * DPAD_PAN_STEP_RATIO, density * DPAD_PAN_MIN_DP)
        }

        private fun updateMatrix() {
            if (!hasDrawableMetrics()) {
                drawMatrix.reset()
                imageMatrix = drawMatrix
                contentRect.set(0f, 0f, 0f, 0f)
                return
            }

            val drawable = drawable ?: return
            val drawableWidth = drawable.intrinsicWidth.toFloat()
            val drawableHeight = drawable.intrinsicHeight.toFloat()
            baseScale = min(width.toFloat() / drawableWidth, height.toFloat() / drawableHeight)
            val drawnWidth = drawableWidth * baseScale * zoomScale
            val drawnHeight = drawableHeight * baseScale * zoomScale
            val maxOffsetX = ((drawnWidth - width).coerceAtLeast(0f)) / 2f
            val maxOffsetY = ((drawnHeight - height).coerceAtLeast(0f)) / 2f
            offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
            offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)

            val left = (width - drawnWidth) / 2f + offsetX
            val top = (height - drawnHeight) / 2f + offsetY
            val scale = baseScale * zoomScale

            drawMatrix.reset()
            drawMatrix.setScale(scale, scale)
            drawMatrix.postTranslate(left, top)
            imageMatrix = drawMatrix
            contentRect.set(left, top, left + drawnWidth, top + drawnHeight)
        }

        private fun hasDrawableMetrics(): Boolean {
            val drawable = drawable ?: return false
            return width > 0 &&
                height > 0 &&
                drawable.intrinsicWidth > 0 &&
                drawable.intrinsicHeight > 0
        }

        private fun updateLayoutDimensionRatio(ratio: String?) {
            val params = layoutParams as? ConstraintLayout.LayoutParams ?: return
            if (params.dimensionRatio == ratio) return
            params.dimensionRatio = ratio
            layoutParams = params
        }

        private fun notifyZoomStateIfChanged(previousZoomed: Boolean) {
            val currentZoomed = isZoomed()
            if (currentZoomed != previousZoomed) {
                onZoomStateChanged?.invoke(currentZoomed)
            }
        }

        private fun scheduleViewportReset() {
            post { resetViewport() }
        }

        private companion object {
            private const val MIN_SCALE = 1f
            private const val MAX_SCALE = 3f
            private const val DPAD_TOGGLE_SCALE = 2f
            private const val SCALE_EPSILON = 0.01f
            private const val EDGE_TAP_RATIO = 0.2f
            private const val SWIPE_MIN_DP = 32f
            private const val SWIPE_DIRECTION_RATIO = 1.25f
            private const val DPAD_PAN_STEP_RATIO = 0.12f
            private const val DPAD_PAN_MIN_DP = 48f
        }

        private val swipeThresholdPx: Float
            get() = max(touchSlopPx * 2f, density * SWIPE_MIN_DP)
    }
