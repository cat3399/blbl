package blbl.cat3399.feature.player

import blbl.cat3399.core.prefs.PlayerCustomShortcutTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class PlayerCustomShortcutBindings<Action>(
    val shortAction: Action? = null,
    val longAction: Action? = null,
) {
    val isEmpty: Boolean
        get() = shortAction == null && longAction == null
}

internal sealed interface PlayerCustomShortcutDispatchResult<out EventToken> {
    data object NotHandled : PlayerCustomShortcutDispatchResult<Nothing>

    data object Consumed : PlayerCustomShortcutDispatchResult<Nothing>

    data class RunDefaultShortPress<EventToken>(
        val downEvent: EventToken,
        val upEvent: EventToken,
    ) : PlayerCustomShortcutDispatchResult<EventToken>
}

/**
 * Owns the timing and pending-press state shared by VOD and live players.
 * Keys without an effective binding return before input policy checks or state-machine mutation.
 */
internal class PlayerCustomShortcutPressController<Action, EventToken>(
    private val scope: CoroutineScope,
    private val longPressTimeoutMillis: Long,
    private val isEligibleKey: (Int) -> Boolean,
    private val bindingsForKey: (Int) -> PlayerCustomShortcutBindings<Action>,
    private val canDispatch: () -> Boolean,
    private val copyEventToken: (EventToken) -> EventToken,
    private val executeAction: (keyCode: Int, trigger: PlayerCustomShortcutTrigger, action: Action) -> Unit,
) {
    private val machine = PlayerCustomShortcutPressStateMachine<Action, EventToken>()
    private var longPressJob: Job? = null

    fun onKeyDown(
        keyCode: Int,
        repeatCount: Int,
        eventToken: EventToken,
    ): PlayerCustomShortcutDispatchResult<EventToken> {
        if (!isEligibleKey(keyCode)) return PlayerCustomShortcutDispatchResult.NotHandled

        if (
            machine.pendingKeyCode != 0 &&
            (machine.pendingKeyCode != keyCode || repeatCount == 0)
        ) {
            clear()
        }
        if (repeatCount != 0) {
            return resolve(machine.onRepeat(keyCode), keyCode = keyCode)
        }

        val bindings = bindingsForKey(keyCode)
        if (bindings.isEmpty) return PlayerCustomShortcutDispatchResult.NotHandled
        if (!canDispatch()) return PlayerCustomShortcutDispatchResult.NotHandled

        val result =
            machine.onInitialDown(
                keyCode = keyCode,
                downToken = copyEventToken(eventToken),
                shortAction = bindings.shortAction,
                longAction = bindings.longAction,
            )
        if (result is PlayerCustomShortcutPressResult.AwaitLongPress) {
            longPressJob =
                scope.launch {
                    delay(longPressTimeoutMillis)
                    if (!canDispatch()) {
                        clear()
                        return@launch
                    }
                    val timeoutResult = machine.onLongPressTimeout(keyCode)
                    if (timeoutResult is PlayerCustomShortcutPressResult.RunAction) {
                        executeAction(keyCode, timeoutResult.trigger, timeoutResult.action)
                    }
                }
            return PlayerCustomShortcutDispatchResult.Consumed
        }
        return resolve(result, keyCode = keyCode)
    }

    fun onKeyUp(
        keyCode: Int,
        eventToken: EventToken,
    ): PlayerCustomShortcutDispatchResult<EventToken> {
        if (!isEligibleKey(keyCode) || machine.pendingKeyCode != keyCode) {
            return PlayerCustomShortcutDispatchResult.NotHandled
        }
        longPressJob?.cancel()
        longPressJob = null
        return resolve(
            result = machine.onKeyUp(keyCode),
            keyCode = keyCode,
            upEventToken = eventToken,
        )
    }

    fun clear() {
        longPressJob?.cancel()
        longPressJob = null
        machine.clear()
    }

    private fun resolve(
        result: PlayerCustomShortcutPressResult<Action, EventToken>,
        keyCode: Int,
        upEventToken: EventToken? = null,
    ): PlayerCustomShortcutDispatchResult<EventToken> {
        return when (result) {
            PlayerCustomShortcutPressResult.NotHandled -> PlayerCustomShortcutDispatchResult.NotHandled
            PlayerCustomShortcutPressResult.Consumed,
            PlayerCustomShortcutPressResult.AwaitLongPress,
            -> PlayerCustomShortcutDispatchResult.Consumed

            is PlayerCustomShortcutPressResult.RunAction -> {
                if (canDispatch()) {
                    executeAction(keyCode, result.trigger, result.action)
                }
                PlayerCustomShortcutDispatchResult.Consumed
            }

            is PlayerCustomShortcutPressResult.RunDefaultShortPress -> {
                if (upEventToken != null && canDispatch()) {
                    PlayerCustomShortcutDispatchResult.RunDefaultShortPress(
                        downEvent = result.downToken,
                        upEvent = copyEventToken(upEventToken),
                    )
                } else {
                    PlayerCustomShortcutDispatchResult.Consumed
                }
            }
        }
    }
}
