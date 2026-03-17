package blbl.cat3399.core.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import blbl.cat3399.feature.player.SpriteFrame
import kotlin.math.roundToInt

class VideoShotPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 对应 FilterQuality.Low，开启双线性过滤让缩放平滑
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val srcRect = Rect()
    private val dstRect = Rect()

    var spriteFrame: SpriteFrame? = null
        set(value) {
            field = value
            // 当帧改变时，触发重新测量宽高和重绘
            requestLayout()
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val frame = spriteFrame
        if (frame == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        // 获取高度的限制（XML 里我们会设置具体的 dp 值，比如 100dp）
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        // 1. 计算纵横比：确保预览框比例正确
        // 假设 frame.srcRect 是 Compose 的 IntRect
        val srcWidth = frame.srcRect.width().toFloat()
        val srcHeight = frame.srcRect.height().toFloat()
        val aspectRatio = if (srcHeight > 0) srcWidth / srcHeight else 16f / 9f

        // 2. 根据高度和比例推算宽度
        val desiredWidth = (heightSize * aspectRatio).roundToInt()

        // 3. 决定最终的宽高
        val resolvedWidth = resolveSize(desiredWidth, widthMeasureSpec)
        val resolvedHeight = resolveSize(heightSize, heightMeasureSpec)

        setMeasuredDimension(resolvedWidth, resolvedHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = spriteFrame ?: return

        // 注意：如果你的 spriteSheet 是 Compose 的 ImageBitmap，需要转成 Android Bitmap
        // 如果你的数据层已经改成了 android.graphics.Bitmap，直接拿来用即可
        val bitmap: Bitmap = frame.spriteSheet // 视你的数据结构而定

        // 映射源区域 (srcOffset, srcSize)
        srcRect.set(
            frame.srcRect.left,
            frame.srcRect.top,
            frame.srcRect.right,
            frame.srcRect.bottom
        )

        // 映射目标区域 (dstSize)
        dstRect.set(0, 0, width, height)

        // 直接绘制大图的局部区域到画布，零像素拷贝！
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }
}

