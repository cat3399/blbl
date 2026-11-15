package blbl.cat3399.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFocusKeyStateTest {
    @Test
    fun handledPressConsumesRepeatsAndMatchingKeyUp() {
        val state = SettingsFocusKeyState()
        var initialDownCount = 0

        assertTrue(
            state.onDown(keyCode = 22, repeatCount = 0) {
                initialDownCount++
                true
            },
        )
        assertTrue(state.onDown(keyCode = 22, repeatCount = 1) { error("repeat must not navigate again") })
        assertTrue(state.onUp(22))
        assertFalse(state.onUp(22))
        assertEquals(1, initialDownCount)
    }

    @Test
    fun repeatedDownCanBecomeHandledAfterFocusReachesBoundary() {
        val state = SettingsFocusKeyState()
        var position = 0

        assertFalse(state.onDown(keyCode = 21, repeatCount = 0) { false })
        assertFalse(
            state.onDown(keyCode = 21, repeatCount = 1) {
                shouldStopSettingsVerticalFocus(
                    position = position,
                    itemCount = 2,
                    direction = SettingsVerticalDirection.Down,
                )
            },
        )

        position = 1
        assertTrue(
            state.onDown(keyCode = 21, repeatCount = 2) {
                shouldStopSettingsVerticalFocus(
                    position = position,
                    itemCount = 2,
                    direction = SettingsVerticalDirection.Down,
                )
            },
        )
        assertTrue(state.onDown(keyCode = 21, repeatCount = 3) { error("handled repeat must stay latched") })
        assertTrue(state.onUp(21))
    }

    @Test
    fun verticalBoundaryStopsAtBothEndsAndDuringTransientLayout() {
        assertTrue(shouldStopSettingsVerticalFocus(0, 3, SettingsVerticalDirection.Up))
        assertFalse(shouldStopSettingsVerticalFocus(1, 3, SettingsVerticalDirection.Up))
        assertFalse(shouldStopSettingsVerticalFocus(1, 3, SettingsVerticalDirection.Down))
        assertTrue(shouldStopSettingsVerticalFocus(2, 3, SettingsVerticalDirection.Down))
        assertTrue(shouldStopSettingsVerticalFocus(-1, 3, SettingsVerticalDirection.Down))
    }
}
