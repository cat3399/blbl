package blbl.cat3399.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerOsdVisibilityPolicyTest {
    @Test
    fun requestedOsdShowsWithoutOverlayOrTouchLock() {
        assertTrue(
            shouldShowPlayerOsd(
                requestedVisible = true,
                hasOverlayPanel = false,
                touchLocked = false,
            ),
        )
    }

    @Test
    fun overlayPanelSuppressesRequestedOsd() {
        assertFalse(
            shouldShowPlayerOsd(
                requestedVisible = true,
                hasOverlayPanel = true,
                touchLocked = false,
            ),
        )
    }

    @Test
    fun hiddenRequestAndTouchLockKeepOsdHidden() {
        assertFalse(
            shouldShowPlayerOsd(
                requestedVisible = false,
                hasOverlayPanel = false,
                touchLocked = false,
            ),
        )
        assertFalse(
            shouldShowPlayerOsd(
                requestedVisible = true,
                hasOverlayPanel = false,
                touchLocked = true,
            ),
        )
    }
}
