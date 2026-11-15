package blbl.cat3399.feature.player

import blbl.cat3399.core.prefs.PlayerCustomShortcutTrigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerCustomShortcutPressControllerTest {
    @Test
    fun unboundKeyBypassesPolicyAndStateMachine() = runTest {
        var canDispatchCalls = 0
        var executeCalls = 0
        val controller =
            PlayerCustomShortcutPressController<String, String>(
                scope = this,
                longPressTimeoutMillis = 100,
                isEligibleKey = { true },
                bindingsForKey = { PlayerCustomShortcutBindings() },
                canDispatch = {
                    canDispatchCalls += 1
                    true
                },
                copyEventToken = { "$it-copy" },
                executeAction = { _, _, _ -> executeCalls += 1 },
            )

        assertEquals(
            PlayerCustomShortcutDispatchResult.NotHandled,
            controller.onKeyDown(keyCode = 10, repeatCount = 0, eventToken = "down"),
        )
        assertEquals(
            PlayerCustomShortcutDispatchResult.NotHandled,
            controller.onKeyUp(keyCode = 10, eventToken = "up"),
        )
        assertEquals(0, canDispatchCalls)
        assertEquals(0, executeCalls)
    }

    @Test
    fun longOnlyTapReturnsOriginalShortPressEvents() = runTest {
        val controller =
            PlayerCustomShortcutPressController<String, String>(
                scope = this,
                longPressTimeoutMillis = 100,
                isEligibleKey = { true },
                bindingsForKey = { PlayerCustomShortcutBindings(longAction = "long") },
                canDispatch = { true },
                copyEventToken = { "$it-copy" },
                executeAction = { _, _, _ -> error("tap must not run the long action") },
            )

        assertEquals(
            PlayerCustomShortcutDispatchResult.Consumed,
            controller.onKeyDown(keyCode = 10, repeatCount = 0, eventToken = "down"),
        )
        assertEquals(
            PlayerCustomShortcutDispatchResult.RunDefaultShortPress(
                downEvent = "down-copy",
                upEvent = "up-copy",
            ),
            controller.onKeyUp(keyCode = 10, eventToken = "up"),
        )
    }

    @Test
    fun longPressExecutesOnceAndConsumesKeyUp() = runTest {
        val executed = mutableListOf<Triple<Int, PlayerCustomShortcutTrigger, String>>()
        val controller =
            PlayerCustomShortcutPressController<String, String>(
                scope = this,
                longPressTimeoutMillis = 100,
                isEligibleKey = { true },
                bindingsForKey = { PlayerCustomShortcutBindings(longAction = "long") },
                canDispatch = { true },
                copyEventToken = { it },
                executeAction = { keyCode, trigger, action ->
                    executed += Triple(keyCode, trigger, action)
                },
            )

        controller.onKeyDown(keyCode = 10, repeatCount = 0, eventToken = "down")
        advanceTimeBy(100)
        runCurrent()

        assertEquals(
            listOf(Triple(10, PlayerCustomShortcutTrigger.LONG_PRESS, "long")),
            executed,
        )
        assertEquals(
            PlayerCustomShortcutDispatchResult.Consumed,
            controller.onKeyUp(keyCode = 10, eventToken = "up"),
        )
    }
}
