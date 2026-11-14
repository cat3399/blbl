package blbl.cat3399.feature.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerDefaultShortPressRegistryTest {
    @Test
    fun volumeKeysUseSystemAudioStrategy() {
        listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE,
        ).forEach { keyCode ->
            assertEquals(
                PlayerDefaultShortPressStrategy.SystemAudio,
                PlayerDefaultShortPressRegistry.strategyFor(keyCode),
            )
        }
    }

    @Test
    fun commonPlayerKeysUsePlayerDefaultStrategy() {
        listOf(
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_CAPTIONS,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
        ).forEach { keyCode ->
            assertEquals(
                PlayerDefaultShortPressStrategy.PlayerDefault,
                PlayerDefaultShortPressRegistry.strategyFor(keyCode),
            )
        }
    }

    @Test
    fun unregisteredKeysKeepKeyEventReplayFallback() {
        assertEquals(
            PlayerDefaultShortPressStrategy.KeyEventReplay,
            PlayerDefaultShortPressRegistry.strategyFor(KeyEvent.KEYCODE_HOME),
        )
        assertEquals(
            PlayerDefaultShortPressStrategy.KeyEventReplay,
            PlayerDefaultShortPressRegistry.strategyFor(KeyEvent.KEYCODE_F1),
        )
    }
}
