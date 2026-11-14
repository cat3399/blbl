package blbl.cat3399.feature.video.comment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import blbl.cat3399.R
import kotlin.math.roundToInt

/**
 * Compact Bilibili user-level badge used in comment headers.
 *
 * The stepped background, LV glyph and seven-segment digit follow the official
 * badge proportions also used by PiliPlus. Drawing it locally keeps every level
 * crisp under the project's user-adjustable density instead of scaling bitmaps.
 */
internal class VideoCommentLevelView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        private val backgroundPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
        private val glyphPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
        private val rect = RectF()
        private val path = Path()
        private val cornerRadii = FloatArray(8)

        private var level: Int? = null
        private var seniorMember = false

        init {
            isClickable = false
            isFocusable = false
        }

        fun bind(level: Int?, isSeniorMember: Boolean) {
            val normalizedLevel = level?.takeIf { it in 0..6 }
            val normalizedSeniorMember = normalizedLevel != null && isSeniorMember
            val sizeChanged = seniorMember != normalizedSeniorMember
            val contentChanged = this.level != normalizedLevel || sizeChanged

            this.level = normalizedLevel
            seniorMember = normalizedSeniorMember
            visibility = if (normalizedLevel == null) GONE else VISIBLE
            contentDescription =
                normalizedLevel?.let {
                    context.getString(
                        if (normalizedSeniorMember) {
                            R.string.player_comment_level_senior_accessibility
                        } else {
                            R.string.player_comment_level_accessibility
                        },
                        it,
                    )
                }

            if (sizeChanged) requestLayout()
            if (contentChanged) invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val desiredHeight = resources.getDimensionPixelSize(R.dimen.player_comment_level_height)
            val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)
            val logicalWidth = if (seniorMember) LOGICAL_SENIOR_WIDTH else LOGICAL_WIDTH
            val desiredWidth = (measuredHeight * logicalWidth / LOGICAL_HEIGHT).roundToInt()
            setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                measuredHeight,
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val currentLevel = level ?: return
            if (width <= 0 || height <= 0) return

            val logicalWidth = if (seniorMember) LOGICAL_SENIOR_WIDTH else LOGICAL_WIDTH
            val scale = height / LOGICAL_HEIGHT
            val drawnWidth = logicalWidth * scale
            val translateX = ((width - drawnWidth) / 2f).coerceAtLeast(0f)

            backgroundPaint.color = videoCommentLevelColor(currentLevel)
            canvas.save()
            canvas.translate(translateX, 0f)
            canvas.scale(scale, scale)
            drawBackground(canvas, logicalWidth)
            drawLvGlyph(canvas)
            drawDigit(canvas, currentLevel)
            if (seniorMember) drawSeniorBolt(canvas)
            canvas.restore()
        }

        private fun drawBackground(canvas: Canvas, logicalWidth: Float) {
            drawRoundedRect(
                canvas = canvas,
                paint = backgroundPaint,
                left = 0f,
                top = 48f,
                right = logicalWidth,
                bottom = LOGICAL_HEIGHT,
                topLeft = 27f,
                topRight = 0f,
                bottomRight = 27f,
                bottomLeft = 27f,
            )
            drawRoundedRect(
                canvas = canvas,
                paint = backgroundPaint,
                left = 576f,
                top = 0f,
                right = logicalWidth,
                bottom = 49f,
                topLeft = 27f,
                topRight = 27f,
                bottomRight = 0f,
                bottomLeft = 0f,
            )
        }

        private fun drawLvGlyph(canvas: Canvas) {
            drawRoundedRect(
                canvas = canvas,
                paint = glyphPaint,
                left = 56f,
                top = 106f,
                right = 123f,
                bottom = DIGIT_BOTTOM,
                topLeft = SEGMENT_RADIUS,
                topRight = SEGMENT_RADIUS,
                bottomRight = 0f,
                bottomLeft = SEGMENT_RADIUS,
            )
            drawRoundedRect(
                canvas = canvas,
                paint = glyphPaint,
                left = 122f,
                top = DIGIT_BOTTOM_TOP,
                right = 256f,
                bottom = DIGIT_BOTTOM,
                topLeft = 0f,
                topRight = SEGMENT_RADIUS,
                bottomRight = SEGMENT_RADIUS,
                bottomLeft = 0f,
            )

            drawRoundedRect(
                canvas = canvas,
                paint = glyphPaint,
                left = 296f,
                top = 106f,
                right = 363f,
                bottom = 283f,
                topLeft = SEGMENT_RADIUS,
                topRight = SEGMENT_RADIUS,
                bottomRight = 0f,
                bottomLeft = 0f,
            )
            drawRoundedRect(
                canvas = canvas,
                paint = glyphPaint,
                left = 476f,
                top = 106f,
                right = 543f,
                bottom = 283f,
                topLeft = SEGMENT_RADIUS,
                topRight = SEGMENT_RADIUS,
                bottomRight = 0f,
                bottomLeft = 0f,
            )
            path.reset()
            path.moveTo(296f, 282f)
            path.lineTo(296f, 292f)
            path.quadTo(296f, 306f, 300f, 313f)
            path.lineTo(395f, 408f)
            path.quadTo(419.5f, 432.5f, 444f, 408f)
            path.lineTo(539f, 313f)
            path.quadTo(543f, 306f, 543f, 292f)
            path.lineTo(543f, 282f)
            path.lineTo(476f, 282f)
            path.lineTo(419.5f, 340f)
            path.lineTo(363f, 282f)
            path.close()
            canvas.drawPath(path, glyphPaint)
        }

        private fun drawDigit(canvas: Canvas, digit: Int) {
            if (digit == 1) {
                drawRoundedRect(
                    canvas = canvas,
                    paint = glyphPaint,
                    left = 673f,
                    top = DIGIT_BOTTOM_TOP,
                    right = 833f,
                    bottom = DIGIT_BOTTOM,
                    topLeft = SEGMENT_RADIUS,
                    topRight = SEGMENT_RADIUS,
                    bottomRight = SEGMENT_RADIUS,
                    bottomLeft = SEGMENT_RADIUS,
                )
                drawRoundedRect(
                    canvas = canvas,
                    paint = glyphPaint,
                    left = 673f,
                    top = DIGIT_TOP,
                    right = 787f,
                    bottom = DIGIT_TOP_BOTTOM,
                    topLeft = SEGMENT_RADIUS,
                    topRight = SEGMENT_RADIUS,
                    bottomRight = 0f,
                    bottomLeft = SEGMENT_RADIUS,
                )
                canvas.drawRect(719f, DIGIT_TOP_BOTTOM, 787f, DIGIT_BOTTOM_TOP, glyphPaint)
                return
            }

            val bits =
                when (digit) {
                    0 -> 0x7E
                    2 -> 0x6D
                    3 -> 0x79
                    4 -> 0x33
                    5 -> 0x5B
                    6 -> 0x5F
                    else -> 0x4F
                }
            drawSevenSegments(
                canvas = canvas,
                top = bits and 0x40 != 0,
                upperRight = bits and 0x20 != 0,
                lowerRight = bits and 0x10 != 0,
                bottom = bits and 0x08 != 0,
                lowerLeft = bits and 0x04 != 0,
                upperLeft = bits and 0x02 != 0,
                middle = bits and 0x01 != 0,
            )
        }

        private fun drawSevenSegments(
            canvas: Canvas,
            top: Boolean,
            upperRight: Boolean,
            lowerRight: Boolean,
            bottom: Boolean,
            lowerLeft: Boolean,
            upperLeft: Boolean,
            middle: Boolean,
        ) {
            if (top) {
                drawRoundedRect(
                    canvas = canvas,
                    paint = glyphPaint,
                    left = DIGIT_LEFT,
                    top = DIGIT_TOP,
                    right = DIGIT_RIGHT,
                    bottom = DIGIT_TOP_BOTTOM,
                    topLeft = SEGMENT_RADIUS,
                    topRight = SEGMENT_RADIUS,
                    bottomRight = if (upperRight) 0f else SEGMENT_RADIUS,
                    bottomLeft = if (upperLeft) 0f else SEGMENT_RADIUS,
                )
            }
            if (middle) {
                drawRoundedRect(
                    canvas = canvas,
                    paint = glyphPaint,
                    left = DIGIT_LEFT,
                    top = DIGIT_MIDDLE,
                    right = DIGIT_RIGHT,
                    bottom = DIGIT_MIDDLE_BOTTOM,
                    topLeft = if (upperLeft) 0f else SEGMENT_RADIUS,
                    topRight = if (upperRight) 0f else SEGMENT_RADIUS,
                    bottomRight = if (lowerRight) 0f else SEGMENT_RADIUS,
                    bottomLeft = if (lowerLeft) 0f else SEGMENT_RADIUS,
                )
            }
            if (bottom) {
                drawRoundedRect(
                    canvas = canvas,
                    paint = glyphPaint,
                    left = DIGIT_LEFT,
                    top = DIGIT_BOTTOM_TOP,
                    right = DIGIT_RIGHT,
                    bottom = DIGIT_BOTTOM,
                    topLeft = if (lowerLeft) 0f else SEGMENT_RADIUS,
                    topRight = if (lowerRight) 0f else SEGMENT_RADIUS,
                    bottomRight = SEGMENT_RADIUS,
                    bottomLeft = SEGMENT_RADIUS,
                )
            }
            if (upperLeft) {
                drawVerticalSegment(
                    canvas = canvas,
                    left = DIGIT_LEFT,
                    right = DIGIT_LEFT_COLUMN_RIGHT,
                    top = if (top) DIGIT_TOP_BOTTOM - 1f else DIGIT_TOP,
                    bottom = (if (middle) DIGIT_MIDDLE else if (lowerLeft) DIGIT_MIDDLE_CENTER else DIGIT_MIDDLE_BOTTOM) + 1f,
                    roundTop = !top,
                    roundBottom = !middle && !lowerLeft,
                )
            }
            if (upperRight) {
                drawVerticalSegment(
                    canvas = canvas,
                    left = DIGIT_RIGHT_COLUMN_LEFT,
                    right = DIGIT_RIGHT,
                    top = if (top) DIGIT_TOP_BOTTOM - 1f else DIGIT_TOP,
                    bottom = (if (middle) DIGIT_MIDDLE else if (lowerRight) DIGIT_MIDDLE_CENTER else DIGIT_MIDDLE_BOTTOM) + 1f,
                    roundTop = !top,
                    roundBottom = !middle && !lowerRight,
                )
            }
            if (lowerLeft) {
                drawVerticalSegment(
                    canvas = canvas,
                    left = DIGIT_LEFT,
                    right = DIGIT_LEFT_COLUMN_RIGHT,
                    top = (if (middle) DIGIT_MIDDLE_BOTTOM else if (upperLeft) DIGIT_MIDDLE_CENTER else DIGIT_MIDDLE) - 1f,
                    bottom = (if (bottom) DIGIT_BOTTOM_TOP else DIGIT_BOTTOM) + 1f,
                    roundTop = !middle && !upperLeft,
                    roundBottom = !bottom,
                )
            }
            if (lowerRight) {
                drawVerticalSegment(
                    canvas = canvas,
                    left = DIGIT_RIGHT_COLUMN_LEFT,
                    right = DIGIT_RIGHT,
                    top = (if (middle) DIGIT_MIDDLE_BOTTOM else if (upperRight) DIGIT_MIDDLE_CENTER else DIGIT_MIDDLE) - 1f,
                    bottom = (if (bottom) DIGIT_BOTTOM_TOP else DIGIT_BOTTOM) + 1f,
                    roundTop = !middle && !upperRight,
                    roundBottom = !bottom,
                )
            }
        }

        private fun drawVerticalSegment(
            canvas: Canvas,
            left: Float,
            right: Float,
            top: Float,
            bottom: Float,
            roundTop: Boolean,
            roundBottom: Boolean,
        ) {
            drawRoundedRect(
                canvas = canvas,
                paint = glyphPaint,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                topLeft = if (roundTop) SEGMENT_RADIUS else 0f,
                topRight = if (roundTop) SEGMENT_RADIUS else 0f,
                bottomRight = if (roundBottom) SEGMENT_RADIUS else 0f,
                bottomLeft = if (roundBottom) SEGMENT_RADIUS else 0f,
            )
        }

        private fun drawSeniorBolt(canvas: Canvas) {
            path.reset()
            path.moveTo(1015f, 72f)
            path.lineTo(1170f, 72f)
            path.lineTo(1095f, 208f)
            path.lineTo(1190f, 208f)
            path.lineTo(995f, 420f)
            path.lineTo(1053f, 267f)
            path.lineTo(958f, 267f)
            path.close()
            canvas.drawPath(path, glyphPaint)
        }

        private fun drawRoundedRect(
            canvas: Canvas,
            paint: Paint,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            topLeft: Float,
            topRight: Float,
            bottomRight: Float,
            bottomLeft: Float,
        ) {
            rect.set(left, top, right, bottom)
            cornerRadii[0] = topLeft
            cornerRadii[1] = topLeft
            cornerRadii[2] = topRight
            cornerRadii[3] = topRight
            cornerRadii[4] = bottomRight
            cornerRadii[5] = bottomRight
            cornerRadii[6] = bottomLeft
            cornerRadii[7] = bottomLeft
            path.reset()
            path.addRoundRect(rect, cornerRadii, Path.Direction.CW)
            canvas.drawPath(path, paint)
        }

        private companion object {
            private const val LOGICAL_WIDTH = 930f
            private const val LOGICAL_SENIOR_WIDTH = 1250f
            private const val LOGICAL_HEIGHT = 466f
            private const val SEGMENT_RADIUS = 20f

            private const val DIGIT_LEFT = 629f
            private const val DIGIT_RIGHT = 877f
            private const val DIGIT_LEFT_COLUMN_RIGHT = 697f
            private const val DIGIT_RIGHT_COLUMN_LEFT = 809f

            private const val DIGIT_TOP = 55f
            private const val DIGIT_TOP_BOTTOM = 123f
            private const val DIGIT_MIDDLE = 201f
            private const val DIGIT_MIDDLE_CENTER = 235f
            private const val DIGIT_MIDDLE_BOTTOM = 269f
            private const val DIGIT_BOTTOM_TOP = 347f
            private const val DIGIT_BOTTOM = 415f
        }
    }
