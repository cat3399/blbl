@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import blbl.cat3399.core.prefs.VideoFillMode

internal fun applyVideoFillMode(
    playerView: PlayerView,
    ijkAspect: AspectRatioFrameLayout,
    mode: VideoFillMode,
) {
    val resizeMode =
        when (mode) {
            VideoFillMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            VideoFillMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            VideoFillMode.Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
    playerView.resizeMode = resizeMode
    ijkAspect.resizeMode = resizeMode

    // Subtitles use the playback viewport, so zooming the video cannot crop them.
    val subtitles = playerView.subtitleView ?: return
    val overlay = playerView.overlayFrameLayout ?: return
    if (subtitles.parent !== overlay) {
        (subtitles.parent as? ViewGroup)?.removeView(subtitles)
        overlay.addView(
            subtitles,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }
}
