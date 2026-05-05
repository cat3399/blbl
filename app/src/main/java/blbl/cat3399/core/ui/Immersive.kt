package blbl.cat3399.core.ui

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object Immersive {
    @Suppress("DEPRECATION")
    private const val LEGACY_PLAYER_IMMERSIVE_FLAGS =
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

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
            val targetFlags = if (enabled && playerScreen) LEGACY_PLAYER_IMMERSIVE_FLAGS else View.SYSTEM_UI_FLAG_VISIBLE
            @Suppress("DEPRECATION")
            if (window.decorView.systemUiVisibility != targetFlags) {
                window.decorView.systemUiVisibility = targetFlags
            }
        } else {
            WindowDisplayPolicy.applyWindow(activity, fullscreen = enabled)
            WindowCompat.setDecorFitsSystemWindows(window, !enabled)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (enabled) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    /**
     * Sets up a listener on the decor view that immediately re-hides system bars whenever they
     * become visible (e.g. triggered by volume keys). Call once in onCreate for player screens.
     * [isFullscreenEnabled] is evaluated at each insets change to respect dynamic pref changes.
     */
    @Suppress("DEPRECATION")
    fun setupKeepHidden(activity: Activity, isFullscreenEnabled: () -> Boolean) {
        val window = activity.window ?: return
        if (Build.VERSION.SDK_INT < 21) {
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if (
                    isFullscreenEnabled() &&
                    visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0 &&
                    window.decorView.systemUiVisibility != LEGACY_PLAYER_IMMERSIVE_FLAGS
                ) {
                    apply(activity, enabled = true, playerScreen = true)
                }
            }
        } else {
            ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
                if (isFullscreenEnabled() && insets.isVisible(WindowInsetsCompat.Type.systemBars())) {
                    // Let child views consume this visible-bars insets pass first so edge-to-edge
                    // content can temporarily dodge the revealed system bars before we hide them again.
                    view.post {
                        if (!ViewCompat.isAttachedToWindow(view)) return@post
                        if (!isFullscreenEnabled()) return@post
                        val rootInsets = ViewCompat.getRootWindowInsets(view)
                        if (rootInsets?.isVisible(WindowInsetsCompat.Type.systemBars()) == true) {
                            apply(activity, enabled = true, playerScreen = true)
                        }
                    }
                }
                ViewCompat.onApplyWindowInsets(view, insets)
            }
        }
        WindowDisplayPolicy.requestApplyInsets(activity)
    }
}
