@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.VideoFillMode
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.feature.live.LivePlayerActivity
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class VideoFillSettingsTest {
    @Before
    fun setUp() {
        BiliClient.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun preferencesDefaultToFitAndPersistAllModes() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = BiliClient.prefs
        assertEquals(VideoFillMode.Fit.prefValue, prefs.playerVideoFillMode)
        for (mode in VideoFillMode.ordered) {
            prefs.playerVideoFillMode = mode.prefValue
            assertEquals(mode.prefValue, AppPrefs(context).playerVideoFillMode)
        }
        prefs.playerVideoFillMode = "unknown"
        assertEquals(VideoFillMode.Fit.prefValue, prefs.playerVideoFillMode)
        context.getSharedPreferences("blbl_prefs", Context.MODE_PRIVATE)
            .edit().putString("player_video_fill_mode", "invalid_import").apply()
        assertEquals(VideoFillMode.Fit.prefValue, prefs.playerVideoFillMode)
    }

    @Test
    fun vodSelectionKeepsPlaybackAndHonorsGlobalSaveSwitch() {
        val activity = Robolectric.buildActivity(PlayerActivity::class.java).get()
        activity.binding = binding()
        activity.session = vodSession()
        // Any interaction with the decoder would interrupt this test immediately.
        activity.player = Proxy.newProxyInstance(
            BlblPlayerEngine::class.java.classLoader,
            arrayOf(BlblPlayerEngine::class.java),
        ) { _, method, _ -> error("Fill change must not call the player: ${method.name}") } as BlblPlayerEngine

        for (saveGlobally in listOf(false, true)) {
            BiliClient.prefs.playerVideoFillMode = VideoFillMode.Fit.prefValue
            BiliClient.prefs.playerSettingsApplyToGlobal = saveGlobally
            activity.applyVideoFillModeSetting(VideoFillMode.Zoom)
            assertEquals(VideoFillMode.Zoom, activity.session.videoFillMode)
            assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, activity.binding.playerView.resizeMode)
            assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, activity.binding.ijkAspect.resizeMode)
            assertEquals(if (saveGlobally) "zoom" else "fit", BiliClient.prefs.playerVideoFillMode)
        }
        BiliClient.prefs.playerSettingsApplyToGlobal = false
        activity.applyVideoFillModeSetting(VideoFillMode.Stretch)
        assertEquals(VideoFillMode.Stretch, activity.session.videoFillMode)
        assertEquals(VideoFillMode.Zoom, vodSession().videoFillMode)
    }

    @Test
    fun liveSelectionHonorsGlobalSaveSwitch() {
        val activity = Robolectric.buildActivity(LivePlayerActivity::class.java).get()
        activity.binding = binding()
        for (saveGlobally in listOf(false, true)) {
            BiliClient.prefs.playerVideoFillMode = VideoFillMode.Fit.prefValue
            BiliClient.prefs.playerSettingsApplyToGlobal = saveGlobally
            activity.applyVideoFillModeSetting(VideoFillMode.Stretch)
            assertEquals(VideoFillMode.Stretch, activity.session.videoFillMode)
            assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, activity.binding.playerView.resizeMode)
            assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, activity.binding.ijkAspect.resizeMode)
            assertEquals(if (saveGlobally) "stretch" else "fit", BiliClient.prefs.playerVideoFillMode)
        }
        BiliClient.prefs.playerSettingsApplyToGlobal = false
        activity.applyVideoFillModeSetting(VideoFillMode.Zoom)
        assertEquals(VideoFillMode.Zoom, activity.session.videoFillMode)
        assertEquals(VideoFillMode.Stretch, LivePlayerActivity.LiveSession().videoFillMode)
    }

    @Test
    fun fillMenuAcceptsTouchAndRemoteSelection() {
        val activity = Robolectric.buildActivity(PlayerActivity::class.java).get()
        activity.setTheme(R.style.Theme_Blbl)
        PlayerOsdSizing.applyTheme(activity)
        activity.binding = binding()
        activity.session = vodSession()
        activity.setContentView(activity.binding.root)
        val entry = PlayerSettingsAdapter.SettingItem(PlayerSettingKeys.VIDEO_FILL_MODE, "画面填充", null)

        activity.handleSettingsItemClick(entry)
        choiceRow(activity, 1).performClick()
        assertEquals(VideoFillMode.Zoom, activity.session.videoFillMode)
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)

        activity.handleSettingsItemClick(entry)
        assertEquals(View.VISIBLE, choiceRow(activity, 1).findViewById<View>(R.id.tv_check).visibility)
        val stretchRow = choiceRow(activity, 2)
        stretchRow.layout(0, 0, 640, 80)
        assertTrue(stretchRow.requestFocus())
        stretchRow.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        stretchRow.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(VideoFillMode.Stretch, activity.session.videoFillMode)
    }

    @Test
    fun bothSessionsKeepTheSelectedModeWhenSwitchingEngines() {
        val liveActivity = Robolectric.buildActivity(LivePlayerActivity::class.java).get()
        for (mode in VideoFillMode.ordered) {
            for (engine in PlayerEngineKind.entries) {
                val vod = vodSession().copy(videoFillMode = mode, engineKind = engine)
                val restoredVod = vodSession().restoreFromEngineSwitchJsonString(vod.toEngineSwitchJsonString())
                assertEquals(mode, restoredVod.videoFillMode)
                assertEquals(engine, restoredVod.engineKind)
                with(liveActivity) {
                    val live = LivePlayerActivity.LiveSession(videoFillMode = mode, engineKind = engine)
                    val restoredLive = LivePlayerActivity.LiveSession().restoreFromEngineSwitchJsonString(live.toEngineSwitchJsonString())
                    assertEquals(mode, restoredLive.videoFillMode)
                    assertEquals(engine, restoredLive.engineKind)
                }
            }
        }
    }

    @Test
    fun oldSessionPayloadKeepsDefaultAndUnknownModeFallsBackToFit() {
        val vod = vodSession().copy(videoFillMode = VideoFillMode.Zoom)
        val payload = JSONObject(vod.toEngineSwitchJsonString()).apply { remove("videoFillMode") }
        assertEquals(VideoFillMode.Zoom, vod.restoreFromEngineSwitchJsonString(payload.toString()).videoFillMode)
        payload.put("videoFillMode", "unknown")
        assertEquals(VideoFillMode.Fit, vod.restoreFromEngineSwitchJsonString(payload.toString()).videoFillMode)
        with(Robolectric.buildActivity(LivePlayerActivity::class.java).get()) {
            val live = LivePlayerActivity.LiveSession(videoFillMode = VideoFillMode.Stretch)
            val livePayload = JSONObject(live.toEngineSwitchJsonString()).apply { remove("videoFillMode") }
            assertEquals(VideoFillMode.Stretch, live.restoreFromEngineSwitchJsonString(livePayload.toString()).videoFillMode)
            livePayload.put("videoFillMode", "unknown")
            assertEquals(VideoFillMode.Fit, live.restoreFromEngineSwitchJsonString(livePayload.toString()).videoFillMode)
        }
    }

    private fun binding(): ActivityPlayerBinding {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Blbl)
        context.theme.applyStyle(R.style.ThemeOverlay_Blbl_PlayerOsd_Normal, true)
        return ActivityPlayerBinding.inflate(LayoutInflater.from(context))
    }

    private fun choiceRow(activity: PlayerActivity, position: Int): View {
        val recycler = activity.window.decorView.findViewById<RecyclerView>(R.id.recycler)
        @Suppress("UNCHECKED_CAST")
        val adapter = recycler.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
        return adapter.createViewHolder(recycler, adapter.getItemViewType(position)).also {
            adapter.bindViewHolder(it, position)
        }.itemView
    }

    private fun vodSession() = PlayerSessionSettings(
        playbackSpeed = 1f,
        preferCodec = "AVC",
        seamlessQualitySwitchEnabled = false,
        preferAudioId = 30280,
        preferredQn = 80,
        targetQn = 0,
        playbackModeOverride = null,
        subtitleEnabled = true,
        subtitleLangOverride = null,
        subtitleTextSizeSp = 26f,
        subtitleBottomPaddingFraction = 0.16f,
        subtitleBackgroundOpacity = 0.1f,
        audioBalanceLevel = AudioBalanceLevel.Off,
        persistentBottomProgressEnabled = true,
        danmaku = LivePlayerActivity.LiveSession().danmaku,
        debugEnabled = false,
        engineKind = PlayerEngineKind.ExoPlayer,
        videoFillMode = VideoFillMode.fromPrefValue(BiliClient.prefs.playerVideoFillMode),
    )
}
