package blbl.cat3399.core.ui

import android.content.Context
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat

object ThemeColor {
    fun resolve(
        context: Context,
        @AttrRes attr: Int,
        @ColorRes fallbackRes: Int,
    ): Int {
        val typed = TypedValue()
        val ok = context.theme.resolveAttribute(attr, typed, true)
        if (!ok) return ContextCompat.getColor(context, fallbackRes)

        if (typed.resourceId != 0) {
            return runCatching { ContextCompat.getColor(context, typed.resourceId) }
                .getOrElse { ContextCompat.getColor(context, fallbackRes) }
        }

        val isColorInt = typed.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
        if (isColorInt) return typed.data

        return ContextCompat.getColor(context, fallbackRes)
    }

    fun applyBackground(
        view: View,
        @AttrRes attr: Int,
        @ColorRes fallbackRes: Int,
    ) {
        val typed = TypedValue()
        val ok = view.context.theme.resolveAttribute(attr, typed, true)
        if (!ok) {
            view.setBackgroundColor(ContextCompat.getColor(view.context, fallbackRes))
            return
        }

        when {
            typed.resourceId != 0 -> view.setBackgroundResource(typed.resourceId)
            typed.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> view.setBackgroundColor(typed.data)
            else -> view.setBackgroundColor(ContextCompat.getColor(view.context, fallbackRes))
        }
    }
}
