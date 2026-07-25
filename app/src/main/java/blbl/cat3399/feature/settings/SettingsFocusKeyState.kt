package blbl.cat3399.feature.settings

/**
 * Keeps a handled DPAD focus-navigation press paired until key-up.
 *
 * Focus may move to another view during the initial key-down. Tracking the physical press here
 * prevents repeat events and the matching key-up from being delivered to that new focus target.
 */
internal class SettingsFocusKeyState {
    private var consumedKeyCode: Int? = null

    fun onDown(
        keyCode: Int,
        repeatCount: Int,
        handleDown: () -> Boolean,
    ): Boolean {
        if (repeatCount > 0 && consumedKeyCode == keyCode) return true

        if (repeatCount == 0) consumedKeyCode = null
        if (!handleDown()) return false

        consumedKeyCode = keyCode
        return true
    }

    fun onUp(keyCode: Int): Boolean {
        if (consumedKeyCode != keyCode) return false
        consumedKeyCode = null
        return true
    }
}

internal enum class SettingsVerticalDirection {
    Up,
    Down,
}

internal fun shouldStopSettingsVerticalFocus(
    position: Int,
    itemCount: Int,
    direction: SettingsVerticalDirection,
): Boolean {
    if (position !in 0 until itemCount) return true
    return when (direction) {
        SettingsVerticalDirection.Up -> position == 0
        SettingsVerticalDirection.Down -> position == itemCount - 1
    }
}
