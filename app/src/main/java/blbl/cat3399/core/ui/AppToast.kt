package blbl.cat3399.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import blbl.cat3399.R

object AppToast {
    private const val DURATION_SHORT_MS: Long = 2000L
    private const val DURATION_LONG_MS: Long = 3500L
    private const val ANIM_IN_MS: Long = 180L
    private const val ANIM_OUT_MS: Long = 160L

    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context, text: CharSequence) {
        showInternal(context = context, text = text, durationMs = DURATION_SHORT_MS)
    }

    fun showLong(context: Context, text: CharSequence) {
        showInternal(context = context, text = text, durationMs = DURATION_LONG_MS)
    }

    private fun showInternal(context: Context, text: CharSequence, durationMs: Long) {
        if (text.isBlank()) return

        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showInternal(context = context, text = text, durationMs = durationMs) }
            return
        }

        val activity =
            context.findActivity()
                ?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (activity == null) {
            showFallbackSystemToast(context = context, text = text, long = durationMs > DURATION_SHORT_MS)
            return
        }

        val root = activity.findViewById<FrameLayout>(android.R.id.content)
        dismissCurrent(root)

        val view = LayoutInflater.from(root.context).inflate(R.layout.view_app_toast, root, false)
        view.isClickable = false
        view.isFocusable = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        view.findViewById<ImageView>(R.id.iv_app_icon).setImageDrawable(
            runCatching { root.context.applicationInfo.loadIcon(root.context.packageManager) }
                .getOrElse { ContextCompat.getDrawable(root.context, R.mipmap.ic_launcher) },
        )

        val tvText = view.findViewById<TextView>(R.id.tv_text)
        tvText.text = text
        tvText.maxWidth =
            (root.context.resources.displayMetrics.widthPixels * 0.70f)
                .toInt()
                .coerceAtLeast(dp(root.context, 200f))

        val insetsBottom =
            ViewCompat.getRootWindowInsets(root)
                ?.getInsets(WindowInsetsCompat.Type.systemBars())
                ?.bottom
                ?: 0

        val lp =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                val marginH = dp(root.context, 24f)
                val marginB = dp(root.context, 32f) + insetsBottom
                setMargins(marginH, 0, marginH, marginB)
            }

        root.addView(view, lp)
        root.setTag(R.id.tag_app_toast_view, view)

        val startOffsetPx = dp(root.context, 16f).toFloat()
        view.alpha = 0f
        view.translationY = startOffsetPx
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ANIM_IN_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        val hideRunnable = Runnable { hideInternal(root = root, view = view) }
        root.setTag(R.id.tag_app_toast_hide_runnable, hideRunnable)
        mainHandler.postDelayed(hideRunnable, durationMs)
    }

    private fun hideInternal(root: FrameLayout, view: View) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hideInternal(root = root, view = view) }
            return
        }

        if (root.getTag(R.id.tag_app_toast_view) !== view) return

        val endOffsetPx = dp(root.context, 12f).toFloat()
        view.animate()
            .alpha(0f)
            .translationY(endOffsetPx)
            .setDuration(ANIM_OUT_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (root.getTag(R.id.tag_app_toast_view) === view) {
                    dismissCurrent(root)
                }
            }
            .start()
    }

    private fun dismissCurrent(root: FrameLayout) {
        val hide = root.getTag(R.id.tag_app_toast_hide_runnable) as? Runnable
        if (hide != null) mainHandler.removeCallbacks(hide)

        val current = root.getTag(R.id.tag_app_toast_view) as? View
        if (current != null) root.removeView(current)

        root.setTag(R.id.tag_app_toast_view, null)
        root.setTag(R.id.tag_app_toast_hide_runnable, null)
    }

    private fun showFallbackSystemToast(context: Context, text: CharSequence, long: Boolean) {
        Toast.makeText(context.applicationContext, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }
}

