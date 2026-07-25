package blbl.cat3399.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsStateTest {
    @Test
    fun contentFocusIsRememberedPerSection() {
        val state = SettingsState()

        state.currentSectionIndex = 0
        state.rememberFocusedRightId(SettingId.ImageQuality)
        state.currentSectionIndex = 1
        state.rememberFocusedRightId(SettingId.GridSpanCount)

        assertEquals(SettingId.GridSpanCount, state.lastFocusedRightIdForCurrentSection())

        state.currentSectionIndex = 0
        assertEquals(SettingId.ImageQuality, state.lastFocusedRightIdForCurrentSection())
    }

    @Test
    fun contentFocusIsNotRememberedBeforeASectionIsActive() {
        val state = SettingsState()

        state.rememberFocusedRightId(SettingId.ImageQuality)

        assertNull(state.lastFocusedRightIdForCurrentSection())
        state.currentSectionIndex = 0
        assertNull(state.lastFocusedRightIdForCurrentSection())
    }
}
