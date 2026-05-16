package blbl.cat3399.core.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs

object WindowDisplayPolicy {
    private data class Options(
        val fullscreen: Boolean,
        val avoidDisplayCutout: Boolean,
    )

    fun applyWindow(activity: Activity, fullscreen: Boolean = currentOptions(activity).fullscreen) {
        val window = activity.window ?: return
        val options = currentOptions(activity).copy(fullscreen = fullscreen)
        applyCutoutMode(window, options)
        WindowCompat.setDecorFitsSystemWindows(window, !fullscreen)
    }

    fun reapply(activity: Activity) {
        applyWindow(activity)
        requestApplyInsets(activity)
    }

    fun requestApplyInsets(activity: Activity) {
        ViewCompat.requestApplyInsets(activity.window?.decorView ?: return)
    }

    private fun applyCutoutMode(
        window: Window,
        options: Options,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val mode = desiredCutoutMode(options)
        val attrs = window.attributes ?: return
        if (attrs.layoutInDisplayCutoutMode == mode) return
        attrs.layoutInDisplayCutoutMode = mode
        window.attributes = attrs
    }

    private fun desiredCutoutMode(options: Options): Int {
        return if (options.avoidDisplayCutout) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        } else {
            allowCutoutMode()
        }
    }

    private fun allowCutoutMode(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun currentOptions(context: Context): Options {
        val prefs =
            try {
                BiliClient.prefs
            } catch (_: UninitializedPropertyAccessException) {
                AppPrefs(context.applicationContext)
            }
        return Options(
            fullscreen = prefs.fullscreenEnabled,
            avoidDisplayCutout = prefs.avoidDisplayCutout,
        )
    }
}
