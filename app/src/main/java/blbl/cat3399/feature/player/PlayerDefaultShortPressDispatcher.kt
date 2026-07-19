package blbl.cat3399.feature.player

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import blbl.cat3399.core.log.AppLog

internal enum class PlayerDefaultShortPressStrategy {
    SystemAudio,
    PlayerDefault,
    KeyEventReplay,
}

internal object PlayerDefaultShortPressRegistry {
    fun strategyFor(keyCode: Int): PlayerDefaultShortPressStrategy {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE,
            -> PlayerDefaultShortPressStrategy.SystemAudio

            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_CAPTIONS,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            -> PlayerDefaultShortPressStrategy.PlayerDefault

            else -> PlayerDefaultShortPressStrategy.KeyEventReplay
        }
    }
}

internal object PlayerDefaultShortPressDispatcher {
    private const val TAG = "PlayerShortcut"

    fun dispatchAndConsume(
        context: Context,
        downEvent: KeyEvent,
        upEvent: KeyEvent,
        dispatchDefaultEvent: (KeyEvent) -> Boolean,
    ): Boolean {
        val keyCode = downEvent.keyCode
        val strategy = PlayerDefaultShortPressRegistry.strategyFor(keyCode)
        val fallbackHandled =
            when (strategy) {
                PlayerDefaultShortPressStrategy.SystemAudio ->
                    dispatchSystemAudio(context = context, keyCode = keyCode) ||
                        replayDefaultEvents(
                            downEvent = downEvent,
                            upEvent = upEvent,
                            dispatchDefaultEvent = dispatchDefaultEvent,
                        )

                PlayerDefaultShortPressStrategy.PlayerDefault,
                PlayerDefaultShortPressStrategy.KeyEventReplay,
                ->
                    // Both re-enter the player's normal dispatcher. Registered player keys hit
                    // its explicit branches; unregistered keys continue to super.dispatchKeyEvent.
                    replayDefaultEvents(
                        downEvent = downEvent,
                        upEvent = upEvent,
                        dispatchDefaultEvent = dispatchDefaultEvent,
                    )
            }
        AppLog.d(
            TAG,
            "default short key=$keyCode strategy=$strategy fallbackHandled=${if (fallbackHandled) 1 else 0}",
        )
        // The physical ACTION_DOWN was already consumed while waiting for the long-press
        // decision, so the matching ACTION_UP must remain consumed after fallback dispatch.
        return true
    }

    private fun dispatchSystemAudio(context: Context, keyCode: Int): Boolean {
        val direction =
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
                KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
                KeyEvent.KEYCODE_VOLUME_MUTE,
                KeyEvent.KEYCODE_MUTE,
                -> AudioManager.ADJUST_TOGGLE_MUTE

                else -> return false
            }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching {
            audioManager.adjustSuggestedStreamVolume(
                direction,
                AudioManager.USE_DEFAULT_STREAM_TYPE,
                AudioManager.FLAG_SHOW_UI,
            )
            true
        }.onFailure { throwable ->
            AppLog.w(TAG, "system audio short press failed key=$keyCode", throwable)
        }.getOrDefault(false)
    }

    private fun replayDefaultEvents(
        downEvent: KeyEvent,
        upEvent: KeyEvent,
        dispatchDefaultEvent: (KeyEvent) -> Boolean,
    ): Boolean {
        val downHandled = dispatchDefaultEvent(downEvent)
        val upHandled = dispatchDefaultEvent(upEvent)
        return downHandled || upHandled
    }
}
