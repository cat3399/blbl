package blbl.cat3399.core.ui

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object Immersive {
    /**
     * @param playerScreen Pass true only for full-screen player activities.
     *   On API 19, non-player screens always show system bars regardless of [enabled],
     *   because edge-to-edge layout requires insets handling that those screens don't implement.
     */
    fun apply(activity: Activity, enabled: Boolean, playerScreen: Boolean = false) {
        val window = activity.window ?: return
        if (Build.VERSION.SDK_INT < 21) {
            // On API 19, only player screens go immersive. All other screens keep system bars
            // visible so UI elements are not obscured.
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (enabled && playerScreen) {
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
