package blbl.cat3399.core.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.feature.live.LivePlayerActivity
import blbl.cat3399.feature.player.PlayerActivity

object ThemePresets {
    data class Spec(
        val baseThemeRes: Int,
        val overlayRes: Int? = null,
    )

    fun applyTo(activity: Activity) {
        val spec = resolve(activity)
        activity.setTheme(spec.baseThemeRes)
        spec.overlayRes?.let { activity.theme.applyStyle(it, true) }
    }

    fun resolve(context: Context): Spec {
        // Player is frozen for this iteration: do not follow preset.
        val activity = context.findActivity()
        if (activity is PlayerActivity || activity is LivePlayerActivity) {
            return Spec(baseThemeRes = R.style.Theme_Blbl_Player_Fixed, overlayRes = null)
        }

        val preset =
            AppPrefs.normalizeThemePreset(
                runCatching { BiliClient.prefs.themePreset }.getOrNull(),
            )

        return when (preset) {
            AppPrefs.THEME_PRESET_TV_PINK ->
                Spec(
                    baseThemeRes = R.style.Theme_Blbl_Base_PinkLight,
                    overlayRes = R.style.ThemeOverlay_Blbl_Accent_TvPink,
                )

            AppPrefs.THEME_PRESET_TV_PINK_ILLUSTRATION ->
                Spec(
                    baseThemeRes = R.style.Theme_Blbl_Base_TvPinkIllustration,
                    overlayRes = R.style.ThemeOverlay_Blbl_Accent_Classic,
                )

            else ->
                Spec(
                    baseThemeRes = R.style.Theme_Blbl_Base_Dark,
                    overlayRes = R.style.ThemeOverlay_Blbl_Accent_Violet,
                )
        }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }
}
