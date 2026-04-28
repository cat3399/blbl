package blbl.cat3399.core.ui

import android.os.Build
import android.view.RoundedCorner
import androidx.core.view.WindowInsetsCompat
import kotlin.math.ceil
import kotlin.math.sqrt

object ScreenCornerInsets {
    fun safeLeftInsetForCircle(
        insets: WindowInsetsCompat,
        circleRadius: Float,
        circleCenterX: Float,
        circleCenterY: Float,
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0
        val topLeftCorner = insets.toWindowInsets()?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT) ?: return 0
        if (topLeftCorner.radius <= 0) return 0

        val radius = topLeftCorner.radius.toFloat()
        val safeRadius = radius - circleRadius
        if (safeRadius <= 0f) {
            return ceil(radius - circleCenterX).toInt().coerceAtLeast(0)
        }

        val verticalOffset = radius - circleCenterY
        if (verticalOffset >= safeRadius) {
            return ceil(radius - circleCenterX).toInt().coerceAtLeast(0)
        }

        val horizontalReach = sqrt((safeRadius * safeRadius - verticalOffset * verticalOffset).coerceAtLeast(0f))
        val minCircleCenterX = radius - horizontalReach
        return ceil(minCircleCenterX - circleCenterX).toInt().coerceAtLeast(0)
    }

    fun safeRightInset(insets: WindowInsetsCompat): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0
        val windowInsets = insets.toWindowInsets() ?: return 0
        val topRightRadius = windowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0
        val bottomRightRadius = windowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
        return maxOf(topRightRadius, bottomRightRadius) / 3
    }
}
