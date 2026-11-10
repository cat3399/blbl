@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player

import android.app.Application
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.media3.ui.AspectRatioFrameLayout
import blbl.cat3399.R
import blbl.cat3399.core.prefs.VideoFillMode
import blbl.cat3399.databinding.ActivityPlayerBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class VideoFillLayoutTest {
    @Test
    fun bothRenderLayoutsFitCropAndStretchOnlyTheVideo() {
        for (layout in listOf(R.layout.activity_player, R.layout.activity_player_texture)) {
            val binding = inflate(layout)
            binding.ijkAspect.visibility = View.VISIBLE
            val exoAspect = binding.playerView.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            for (ratio in listOf(4f / 3f, 16f / 9f, 9f / 16f, 21f / 9f)) {
                exoAspect.setAspectRatio(ratio)
                binding.ijkAspect.setAspectRatio(ratio)
                // Returning to Fit also checks that the previous zoom/stretch is removed.
                for (mode in VideoFillMode.ordered + VideoFillMode.Fit) {
                    applyVideoFillMode(binding.playerView, binding.ijkAspect, mode)
                    measureAndLayout(binding.root)
                    assertVideoBounds(exoAspect, ratio, mode)
                    assertVideoBounds(binding.ijkAspect, ratio, mode)
                    assertEquals(Rect(0, 0, WIDTH, HEIGHT), bounds(binding.ijkViewport))
                    assertTrue(binding.ijkViewport.clipChildren)
                    assertTrue(binding.playerView.clipChildren)
                    val subtitles = requireNotNull(binding.playerView.subtitleView)
                    assertSame(binding.playerView.overlayFrameLayout, subtitles.parent)
                    assertEquals(Rect(0, 0, WIDTH, HEIGHT), bounds(subtitles))
                    assertEquals(Rect(0, 0, WIDTH, HEIGHT), bounds(binding.danmakuView))
                    assertSame(binding.root, binding.bottomBar.parent)
                    assertSame(binding.root, binding.settingsPanel.parent)
                }
            }
        }
    }

    @Test
    fun croppedIjkFrameStaysCenteredInsideTheHdPlaybackArea() {
        for (layout in listOf(R.layout.activity_player, R.layout.activity_player_texture)) {
            val binding = inflate(layout)
            binding.ijkAspect.visibility = View.VISIBLE
            binding.ijkAspect.setAspectRatio(4f / 3f)
            binding.playerView.layoutParams = (binding.playerView.layoutParams as ConstraintLayout.LayoutParams).apply {
                width = 960
                height = 540
                horizontalBias = 0f
                verticalBias = 0f
                leftMargin = 40
                topMargin = 30
            }
            applyVideoFillMode(binding.playerView, binding.ijkAspect, VideoFillMode.Zoom)
            measureAndLayout(binding.root)

            assertEquals(bounds(binding.playerView), bounds(binding.ijkViewport))
            assertEquals(Rect(0, -90, 960, 630), bounds(binding.ijkAspect))
            assertEquals(Rect(0, 0, 960, 540), bounds(requireNotNull(binding.playerView.subtitleView)))
            assertEquals(bounds(binding.playerView), bounds(binding.danmakuView))
        }
    }

    private fun assertVideoBounds(view: View, ratio: Float, mode: VideoFillMode) {
        when (mode) {
            VideoFillMode.Fit -> {
                assertTrue(view.width <= WIDTH && view.height <= HEIGHT)
                assertTrue(view.width == WIDTH || view.height == HEIGHT)
                assertEquals(ratio.toDouble(), view.width.toDouble() / view.height, 0.005)
            }
            VideoFillMode.Zoom -> {
                assertTrue(view.width >= WIDTH && view.height >= HEIGHT)
                assertTrue(view.width == WIDTH || view.height == HEIGHT)
                assertEquals(ratio.toDouble(), view.width.toDouble() / view.height, 0.005)
            }
            VideoFillMode.Stretch -> assertEquals(Rect(0, 0, WIDTH, HEIGHT), bounds(view))
        }
        assertEquals((WIDTH - view.width) / 2, view.left)
        assertEquals((HEIGHT - view.height) / 2, view.top)
    }

    private fun inflate(layout: Int): ActivityPlayerBinding {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Blbl)
        context.theme.applyStyle(R.style.ThemeOverlay_Blbl_PlayerOsd_Normal, true)
        return ActivityPlayerBinding.bind(LayoutInflater.from(context).inflate(layout, FrameLayout(context), false))
    }

    private fun measureAndLayout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, HEIGHT)
    }

    private fun bounds(view: View) = Rect(view.left, view.top, view.right, view.bottom)

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
    }
}
