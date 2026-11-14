package blbl.cat3399.feature.player

import blbl.cat3399.core.prefs.PlayerCustomShortcutTrigger

internal sealed interface PlayerCustomShortcutPressResult<out Action, out DownToken> {
    data object NotHandled : PlayerCustomShortcutPressResult<Nothing, Nothing>

    data object Consumed : PlayerCustomShortcutPressResult<Nothing, Nothing>

    data object AwaitLongPress : PlayerCustomShortcutPressResult<Nothing, Nothing>

    data class RunAction<Action>(
        val trigger: PlayerCustomShortcutTrigger,
        val action: Action,
    ) : PlayerCustomShortcutPressResult<Action, Nothing>

    data class RunDefaultShortPress<DownToken>(
        val downToken: DownToken,
    ) : PlayerCustomShortcutPressResult<Nothing, DownToken>
}

/**
 * Resolves one physical key press into short-press, long-press, or the key's normal
 * short-press behavior. Scheduling the long-press timeout stays with the Activity so
 * lifecycle cancellation remains explicit.
 */
internal class PlayerCustomShortcutPressStateMachine<Action, DownToken> {
    var pendingKeyCode: Int = 0
        private set

    private var shortAction: Action? = null
    private var longAction: Action? = null
    private var downToken: DownToken? = null
    private var longTriggered: Boolean = false

    fun onInitialDown(
        keyCode: Int,
        downToken: DownToken,
        shortAction: Action?,
        longAction: Action?,
    ): PlayerCustomShortcutPressResult<Action, DownToken> {
        clear()
        if (longAction == null) {
            return shortAction?.let {
                PlayerCustomShortcutPressResult.RunAction(
                    trigger = PlayerCustomShortcutTrigger.SHORT_PRESS,
                    action = it,
                )
            } ?: PlayerCustomShortcutPressResult.NotHandled
        }

        pendingKeyCode = keyCode
        this.downToken = downToken
        this.shortAction = shortAction
        this.longAction = longAction
        return PlayerCustomShortcutPressResult.AwaitLongPress
    }

    fun onRepeat(keyCode: Int): PlayerCustomShortcutPressResult<Action, DownToken> {
        return if (pendingKeyCode == keyCode) {
            PlayerCustomShortcutPressResult.Consumed
        } else {
            PlayerCustomShortcutPressResult.NotHandled
        }
    }

    fun onLongPressTimeout(keyCode: Int): PlayerCustomShortcutPressResult<Action, DownToken> {
        if (pendingKeyCode != keyCode || longTriggered) {
            return PlayerCustomShortcutPressResult.NotHandled
        }
        val action = longAction ?: return PlayerCustomShortcutPressResult.NotHandled
        longTriggered = true
        return PlayerCustomShortcutPressResult.RunAction(
            trigger = PlayerCustomShortcutTrigger.LONG_PRESS,
            action = action,
        )
    }

    fun onKeyUp(keyCode: Int): PlayerCustomShortcutPressResult<Action, DownToken> {
        if (pendingKeyCode != keyCode) return PlayerCustomShortcutPressResult.NotHandled

        if (longTriggered) {
            clear()
            return PlayerCustomShortcutPressResult.Consumed
        }

        val pendingShortAction = shortAction
        val pendingDownToken = downToken
        clear()
        return when {
            pendingShortAction != null ->
                PlayerCustomShortcutPressResult.RunAction(
                    trigger = PlayerCustomShortcutTrigger.SHORT_PRESS,
                    action = pendingShortAction,
                )

            pendingDownToken != null ->
                PlayerCustomShortcutPressResult.RunDefaultShortPress(pendingDownToken)

            else -> PlayerCustomShortcutPressResult.Consumed
        }
    }

    fun clear() {
        pendingKeyCode = 0
        shortAction = null
        longAction = null
        downToken = null
        longTriggered = false
    }
}
