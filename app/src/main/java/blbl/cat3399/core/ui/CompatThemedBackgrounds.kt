package blbl.cat3399.core.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import blbl.cat3399.R
import kotlin.math.roundToInt

object CompatThemedBackgrounds {
    fun popupAction(context: Context): Drawable =
        outlinedSurface(
            context = context,
            strokeColorAttr = R.attr.blblFocusStrokeColor,
            strokeFallbackRes = R.color.blbl_focus_stroke_white,
            cornerRadiusPx = dp(context, 12f).toFloat(),
        )

    fun settingEntry(context: Context): Drawable =
        outlinedSurface(
            context = context,
            strokeColorAttr = R.attr.blblFocusStrokeFocusOnlyColor,
            strokeFallbackRes = R.color.blbl_focus_stroke_focus_only_white,
            cornerRadiusPx = dp(context, 14f).toFloat(),
        )

    fun playerUpQuickChip(context: Context): Drawable =
        accentChip(
            context = context,
            cornerRadiusPx = context.resources.getDimension(R.dimen.player_up_quick_chip_radius),
        )

    fun playerUpQuickFollow(context: Context): Drawable =
        accentChip(
            context = context,
            cornerRadiusPx = context.resources.getDimension(R.dimen.player_up_quick_follow_radius),
        )

    private fun outlinedSurface(
        context: Context,
        @AttrRes strokeColorAttr: Int,
        @ColorRes strokeFallbackRes: Int,
        cornerRadiusPx: Float,
    ): Drawable {
        val fillColor = ThemeColor.resolve(context, com.google.android.material.R.attr.colorSurface, R.color.blbl_surface)
        val focusStrokeColor =
            ThemeColor.resolveForState(
                context = context,
                attr = strokeColorAttr,
                stateSet = intArrayOf(android.R.attr.state_focused),
                fallbackRes = strokeFallbackRes,
            )
        val strokeWidthPx = dp(context, 2f)
        return stateList(
            focused = roundedRect(fillColor, focusStrokeColor, strokeWidthPx, cornerRadiusPx),
            pressed = roundedRect(fillColor, focusStrokeColor, strokeWidthPx, cornerRadiusPx),
            default = roundedRect(fillColor, Color.TRANSPARENT, strokeWidthPx, cornerRadiusPx),
        )
    }

    private fun accentChip(
        context: Context,
        cornerRadiusPx: Float,
    ): Drawable {
        val accent = ThemeColor.resolve(context, R.attr.blblAccent, R.color.blbl_blue)
        val strokeWidthPx = dp(context, 2f)
        return stateList(
            focused =
                roundedRect(
                    fillColor = ContextCompat.getColor(context, R.color.blbl_focus_fill),
                    strokeColor = accent,
                    strokeWidthPx = strokeWidthPx,
                    cornerRadiusPx = cornerRadiusPx,
                ),
            pressed =
                roundedRect(
                    fillColor = ContextCompat.getColor(context, R.color.blbl_focus_fill_pressed),
                    strokeColor = accent,
                    strokeWidthPx = strokeWidthPx,
                    cornerRadiusPx = cornerRadiusPx,
                ),
            default = roundedRect(Color.TRANSPARENT, Color.TRANSPARENT, 0, cornerRadiusPx),
        )
    }

    private fun stateList(
        focused: Drawable,
        pressed: Drawable,
        default: Drawable,
    ): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), default)
        }

    private fun roundedRect(
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Int,
        cornerRadiusPx: Float,
    ): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = cornerRadiusPx
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).roundToInt()
}
