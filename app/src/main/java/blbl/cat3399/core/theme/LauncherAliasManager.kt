package blbl.cat3399.core.theme

import android.content.Context

@Deprecated(
    message = "Launcher aliases are stable compatibility components and must not be synced from the foreground UI.",
    level = DeprecationLevel.ERROR,
)
object LauncherAliasManager {
    @Suppress("UNUSED_PARAMETER")
    fun sync(context: Context) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun sync(context: Context, preset: String) = Unit
}
