package blbl.cat3399.core.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs

object LauncherAliasManager {
    private const val DARK_ALIAS = "blbl.cat3399.ui.MainLauncherDarkAlias"
    private const val TV_PINK_ALIAS = "blbl.cat3399.ui.MainLauncherTvPinkAlias"
    private const val TV_PINK_ILLUSTRATION_ALIAS = "blbl.cat3399.ui.MainLauncherTvPinkIllustrationAlias"

    fun sync(context: Context) {
        val preset = AppPrefs.normalizeThemePreset(runCatching { BiliClient.prefs.themePreset }.getOrNull())
        sync(context, preset)
    }

    fun sync(context: Context, preset: String) {
        val targetAlias =
            when (AppPrefs.normalizeThemePreset(preset)) {
                AppPrefs.THEME_PRESET_TV_PINK -> TV_PINK_ALIAS
                AppPrefs.THEME_PRESET_TV_PINK_ILLUSTRATION -> TV_PINK_ILLUSTRATION_ALIAS
                else -> DARK_ALIAS
            }
        val packageManager = context.packageManager
        val aliases = listOf(DARK_ALIAS, TV_PINK_ALIAS, TV_PINK_ILLUSTRATION_ALIAS)
        aliases.forEach { alias ->
            val desiredState =
                if (alias == targetAlias) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
            val component = ComponentName(context.packageName, alias)
            val currentState = packageManager.getComponentEnabledSetting(component)
            if (currentState == desiredState) return@forEach
            runCatching {
                packageManager.setComponentEnabledSetting(
                    component,
                    desiredState,
                    PackageManager.DONT_KILL_APP,
                )
            }.onFailure { t ->
                AppLog.w("LauncherAlias", "set state failed alias=$alias desired=$desiredState", t)
            }
        }
    }
}
