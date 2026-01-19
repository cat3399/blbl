package blbl.cat3399.feature.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.content.ContextCompat
import blbl.cat3399.R

class SegmentedSeekBar : AppCompatSeekBar {
    private val segmentPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.blbl_blue)
            alpha = 170
        }

    private var segments: List<SegmentMark> = emptyList()

    private val tmpRect = RectF()

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    fun setSegments(segments: List<SegmentMark>) {
        this.segments = segments
        invalidate()
    }

    fun clearSegments() {
        setSegments(emptyList())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty()) return

        val bounds = progressDrawable?.bounds ?: return
        val width = bounds.width().toFloat()
        if (width <= 1f) return

        val top = bounds.top.toFloat()
        val bottom = bounds.bottom.toFloat()

        val leftBase = bounds.left.toFloat()
        val rightBase = bounds.right.toFloat()

        for (seg in segments) {
            val start = seg.startFraction.coerceIn(0f, 1f)
            val end = seg.endFraction.coerceIn(0f, 1f)
            if (end <= start) continue
            val l = leftBase + (rightBase - leftBase) * start
            val r = leftBase + (rightBase - leftBase) * end
            tmpRect.set(l, top, r, bottom)
            canvas.drawRect(tmpRect, segmentPaint)
        }
    }
}
