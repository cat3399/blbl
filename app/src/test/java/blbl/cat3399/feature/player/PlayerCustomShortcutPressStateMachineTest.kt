package blbl.cat3399.feature.player

import blbl.cat3399.core.prefs.PlayerCustomShortcutTrigger
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerCustomShortcutPressStateMachineTest {
    @Test
    fun noBindingPassesThroughImmediately() {
        val machine = PlayerCustomShortcutPressStateMachine<String, String>()

        val result =
            machine.onInitialDown(
                keyCode = 10,
                downToken = "down",
                shortAction = null,
                longAction = null,
            )

        assertEquals(PlayerCustomShortcutPressResult.NotHandled, result)
        assertEquals(0, machine.pendingKeyCode)
    }

    @Test
    fun shortOnlyRunsImmediatelyWithoutStartingPendingPress() {
        val machine = PlayerCustomShortcutPressStateMachine<String, String>()

        val result =
            machine.onInitialDown(
                keyCode = 10,
                downToken = "down",
                shortAction = "short",
                longAction = null,
            )

        assertEquals(
            PlayerCustomShortcutPressResult.RunAction(
                trigger = PlayerCustomShortcutTrigger.SHORT_PRESS,
                action = "short",
            ),
            result,
        )
        assertEquals(0, machine.pendingKeyCode)
    }

    @Test
    fun longOnlyTapRunsDefaultShortPress() {
        val machine = PlayerCustomShortcutPressStateMachine<String, String>()

        val downResult =
            machine.onInitialDown(
                keyCode = 10,
                downToken = "original-down",
                shortAction = null,
                longAction = "long",
            )
        val upResult = machine.onKeyUp(10)

        assertEquals(PlayerCustomShortcutPressResult.AwaitLongPress, downResult)
        assertEquals(PlayerCustomShortcutPressResult.RunDefaultShortPress("original-down"), upResult)
        assertEquals(0, machine.pendingKeyCode)
    }

    @Test
    fun shortAndLongTapRunsConfiguredShortPress() {
        val machine = PlayerCustomShortcutPressStateMachine<String, String>()

        machine.onInitialDown(
            keyCode = 10,
            downToken = "down",
            shortAction = "short",
            longAction = "long",
        )
        val result = machine.onKeyUp(10)

        assertEquals(
            PlayerCustomShortcutPressResult.RunAction(
                trigger = PlayerCustomShortcutTrigger.SHORT_PRESS,
                action = "short",
            ),
            result,
        )
    }

    @Test
    fun longPressRunsLongActionAndConsumesFollowingKeyUp() {
        val machine = PlayerCustomShortcutPressStateMachine<String, String>()

        machine.onInitialDown(
            keyCode = 10,
            downToken = "down",
            shortAction = "short",
            longAction = "long",
        )
        val timeoutResult = machine.onLongPressTimeout(10)
        val upResult = machine.onKeyUp(10)

        assertEquals(
            PlayerCustomShortcutPressResult.RunAction(
                trigger = PlayerCustomShortcutTrigger.LONG_PRESS,
                action = "long",
            ),
            timeoutResult,
        )
        assertEquals(PlayerCustomShortcutPressResult.Consumed, upResult)
        assertEquals(0, machine.pendingKeyCode)
    }

    @Test
    fun repeatsAreConsumedOnlyForPendingKey() {
        val machine = PlayerCustomShortcutPressStateMachine<String, String>()
        machine.onInitialDown(
            keyCode = 10,
            downToken = "down",
            shortAction = null,
            longAction = "long",
        )

        assertEquals(PlayerCustomShortcutPressResult.Consumed, machine.onRepeat(10))
        assertEquals(PlayerCustomShortcutPressResult.NotHandled, machine.onRepeat(11))
        assertEquals(PlayerCustomShortcutPressResult.NotHandled, machine.onKeyUp(11))
        assertEquals(10, machine.pendingKeyCode)
    }
}
