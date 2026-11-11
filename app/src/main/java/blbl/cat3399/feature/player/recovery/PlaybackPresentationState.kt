package blbl.cat3399.feature.player.recovery

/** Why a new source is being opened; this is independent of the user's play/pause intent. */
internal enum class PlaybackStartReason {
    Initial,
    ForegroundRestore,
    EngineSwitch,
}

internal sealed interface PlaybackPresentationState {
    data object Idle : PlaybackPresentationState
    data class AwaitingFrame(val reason: PlaybackStartReason) : PlaybackPresentationState
    data object Ready : PlaybackPresentationState
    data class Failed(val reason: PlaybackStartReason) : PlaybackPresentationState
    data class Background(val returnTo: PlaybackPresentationState) : PlaybackPresentationState
    data object Released : PlaybackPresentationState
}
