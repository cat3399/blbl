package blbl.cat3399.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import blbl.cat3399.R
import kotlin.math.min
import kotlin.math.roundToInt

class VideoShotPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    companion object {
        private const val DEFAULT_CONTENT_WIDTH = 16
        private const val DEFAULT_CONTENT_HEIGHT = 9
    }

    init {
        // On API 19, hardware acceleration can cause bitmap drawing issues in some cases.
        // Force software rendering for this view to ensure thumbnails display correctly.
        if (android.os.Build.VERSION.SDK_INT < 21) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val srcRect = Rect()
    private val dstRect = Rect()
    private var aspectWidth = DEFAULT_CONTENT_WIDTH
    private var aspectHeight = DEFAULT_CONTENT_HEIGHT
    private var shortEdgePx = context.resources.getDimensionPixelSize(R.dimen.player_videoshot_preview_short_edge_medium)

    internal var spriteFrame: SpriteFrame? = null
        set(value) {
            field = value
            invalidate()
        }
    internal var onFrameDrawFailure: ((Throwable) -> Unit)? = null

    internal fun setShortEdge(shortEdgePx: Int) {
        val target = shortEdgePx.coerceAtLeast(1)
        if (this.shortEdgePx == target) return
        this.shortEdgePx = target
        requestLayout()
    }

    internal fun setContentAspectRatio(width: Int, height: Int) {
        val targetWidth = width.coerceAtLeast(1)
        val targetHeight = height.coerceAtLeast(1)
        if (aspectWidth == targetWidth && aspectHeight == targetHeight) return
        aspectWidth = targetWidth
        aspectHeight = targetHeight
        requestLayout()
    }

    internal fun resetContentAspectRatio() {
        setContentAspectRatio(DEFAULT_CONTENT_WIDTH, DEFAULT_CONTENT_HEIGHT)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val aspectRatio = aspectWidth.toFloat() / aspectHeight.coerceAtLeast(1).toFloat()
        val shortEdge = shortEdgePx.coerceAtLeast(1)

        var desiredWidth: Int
        var desiredHeight: Int

        if (aspectRatio >= 1f) {
            desiredHeight = shortEdge
            desiredWidth = (shortEdge * aspectRatio).roundToInt().coerceAtLeast(1)
        } else {
            desiredWidth = shortEdge
            desiredHeight = (shortEdge / aspectRatio.coerceAtLeast(0.0001f)).roundToInt().coerceAtLeast(1)
        }

        val maxWidth =
            when (MeasureSpec.getMode(widthMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> Int.MAX_VALUE
                else -> MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            }
        val maxHeight =
            when (MeasureSpec.getMode(heightMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> Int.MAX_VALUE
                else -> MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
            }

        if (desiredWidth > maxWidth || desiredHeight > maxHeight) {
            val scale = min(maxWidth / desiredWidth.toFloat(), maxHeight / desiredHeight.toFloat())
            desiredWidth = (desiredWidth * scale).roundToInt().coerceAtLeast(1)
            desiredHeight = (desiredHeight * scale).roundToInt().coerceAtLeast(1)
        }

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = spriteFrame ?: return

        val bitmap: Bitmap = frame.bitmap

        updateDrawSourceRect(bitmap)

        dstRect.set(0, 0, width, height)

        try {
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        } catch (t: RuntimeException) {
            spriteFrame = null
            onFrameDrawFailure?.invoke(t)
        }
    }

    private fun updateDrawSourceRect(bitmap: Bitmap) {
        srcRect.set(0, 0, bitmap.width, bitmap.height)

        val sourceWidth = srcRect.width().coerceAtLeast(1)
        val sourceHeight = srcRect.height().coerceAtLeast(1)
        val targetAspect = aspectWidth.toFloat() / aspectHeight.coerceAtLeast(1).toFloat()
        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()

        if (kotlin.math.abs(sourceAspect - targetAspect) < 0.001f) return

        if (sourceAspect > targetAspect) {
            val croppedWidth = (sourceHeight * targetAspect).roundToInt().coerceIn(1, sourceWidth)
            val inset = ((sourceWidth - croppedWidth) / 2).coerceAtLeast(0)
            srcRect.left = inset
            srcRect.right = srcRect.left + croppedWidth
            return
        }

        val croppedHeight = (sourceWidth / targetAspect.coerceAtLeast(0.0001f)).roundToInt().coerceIn(1, sourceHeight)
        val inset = ((sourceHeight - croppedHeight) / 2).coerceAtLeast(0)
        srcRect.top = inset
        srcRect.bottom = srcRect.top + croppedHeight
    }
}
