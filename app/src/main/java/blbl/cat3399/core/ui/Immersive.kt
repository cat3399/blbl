package blbl.cat3399.core.ui

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object Immersive {
    fun apply(activity: Activity, enabled: Boolean) {
        val window = activity.window ?: return
        if (Build.VERSION.SDK_INT < 21) {
            // WindowCompat.setDecorFitsSystemWindows is a no-op below API 30, and
            // WindowInsetsControllerCompat does not set the LAYOUT_* flags on API 19.
            // Drive systemUiVisibility directly so content extends under the bars.
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (enabled) {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        } else {
            WindowDisplayPolicy.applyWindow(activity, fullscreen = enabled)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (enabled) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        WindowDisplayPolicy.requestApplyInsets(activity)
    }
}
