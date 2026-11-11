@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package blbl.cat3399.feature.player.recovery

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import blbl.cat3399.R
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.RECOVERY_SAVED_RECORD
import blbl.cat3399.feature.player.restorePlaybackRecovery
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PlaybackRecoveryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val video = PlaybackRecoveryRecord(entryKey = "vod:BV1:0:0", bvid = "BV1", cid = 11,
        title = "当前视频", coverUrl = "", positionMs = 42_000, hasRenderedFrame = true)

    @Before
    fun reset() {
        PlaybackFrameCache.clear()
        context.getSharedPreferences("playback_recovery", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun clearFrame() = PlaybackFrameCache.clear()

    @Test
    fun captureBoundsPreserveLandscapePortraitAndUltrawideAspect() {
        assertEquals(960 to 540, boundedFrameSize(3840, 2160))
        assertEquals(303 to 540, boundedFrameSize(2160, 3840))
        assertEquals(960 to 270, boundedFrameSize(3840, 1080))
        assertEquals(320 to 240, boundedFrameSize(320, 240))
        assertNull(boundedFrameSize(0, 2160))
        assertNull(boundedFrameSize(3840, -1))
    }

    @Test
    fun appWideSlotCountsInFlightBitmapAndRecyclesPreviousBeforeAllocating() {
        val owner = Any()
        PlaybackFrameCache.observe(video.contentKey, owner) {}
        val first = PlaybackFrameCache.beginCapture(video.contentKey, 3840, 2160)!!
        assertTrue(first.bitmap.byteCount <= 960 * 540 * 4)
        assertNull(PlaybackFrameCache.beginCapture(video.contentKey, 3840, 2160))
        PlaybackFrameCache.completeCapture(first, true)
        assertSame(first.bitmap, PlaybackFrameCache.get(video.contentKey))
        val second = PlaybackFrameCache.beginCapture(video.contentKey, 1920, 1080)!!
        assertTrue(first.bitmap.isRecycled)
        assertNull(PlaybackFrameCache.get(video.contentKey))
        PlaybackFrameCache.completeCapture(second, true)
        PlaybackFrameCache.clear(owner)
        assertTrue(second.bitmap.isRecycled)
    }

    @Test
    fun lateScreenshotCannotReplaceNewContentOrOutliveExit() {
        val owner = Any()
        PlaybackFrameCache.observe("first", owner) {}
        val old = PlaybackFrameCache.beginCapture("first", 3840, 2160)!!
        PlaybackFrameCache.observe("second", owner) {}
        assertFalse(old.bitmap.isRecycled) // PixelCopy still owns this destination.
        assertNull(PlaybackFrameCache.beginCapture("second", 1920, 1080))
        PlaybackFrameCache.completeCapture(old, true)
        assertTrue(old.bitmap.isRecycled)
        assertNull(PlaybackFrameCache.get("second"))
        val next = PlaybackFrameCache.beginCapture("second", 1920, 1080)!!
        PlaybackFrameCache.clear(owner)
        PlaybackFrameCache.completeCapture(next, true)
        assertTrue(next.bitmap.isRecycled)
    }

    @Test
    fun failedOrUnrenderedCaptureUsesFallback() {
        PlaybackFrameCache.observe(video.contentKey, this) {}
        PlaybackFrameCache.capture(video.contentKey, View(context), false)
        assertNull(PlaybackFrameCache.get(video.contentKey))
        val failed = PlaybackFrameCache.beginCapture(video.contentKey, 1920, 1080)!!
        PlaybackFrameCache.completeCapture(failed, false)
        assertTrue(failed.bitmap.isRecycled)
        assertNull(PlaybackFrameCache.get(video.contentKey))
    }

    @Test
    fun newOwnerDetachesOldDrawableAndOldOwnerCannotClearItsFrame() {
        val first = Any()
        val second = Any()
        var oldDrawable: Bitmap? = null
        PlaybackFrameCache.observe(video.contentKey, first) { oldDrawable = it }
        val capture = PlaybackFrameCache.beginCapture(video.contentKey, 1920, 1080)!!
        PlaybackFrameCache.completeCapture(capture, true)
        assertSame(capture.bitmap, oldDrawable)
        PlaybackFrameCache.observe(video.contentKey, second) {}
        assertNull(oldDrawable)
        PlaybackFrameCache.clear(first)
        assertSame(capture.bitmap, PlaybackFrameCache.get(video.contentKey))
        PlaybackFrameCache.clear(second)
        assertTrue(capture.bitmap.isRecycled)
    }

    @Test
    fun persistedCheckpointRestoresLatestContentPositionPauseAndSession() {
        val saved = video.copy(cid = 22, playWhenReady = false, settingsJson = "{\"videoFillMode\":\"zoom\"}")
        PlaybackRecoveryStore(context).save("old", saved)
        val store = PlaybackRecoveryStore(context) // Simulate a new process-owned store.
        assertEquals(saved, store.load(video.entryKey))
        assertEquals(saved, store.load(saved.contentKey))
        assertNull(store.load("vod:BV2:0:0"))
        store.save("new", saved)
        store.clear("old")
        assertEquals(saved, store.load(saved.entryKey))
        store.clear("new")
        assertNull(store.load(saved.entryKey))
        assertNull(PlaybackRecoveryRecord.fromJson("broken"))
    }

    @Test
    fun liveCheckpointNeverMatchesAnotherRoom() {
        val live = PlaybackRecoveryRecord(entryKey = "live:123", live = true, roomId = 123, playWhenReady = false)
        PlaybackRecoveryStore(context).save("live", live)
        assertEquals(live, PlaybackRecoveryStore(context).load("live:123"))
        assertNull(PlaybackRecoveryStore(context).load("live:124"))
        assertNull(PlaybackRecoveryStore(context).load(video.entryKey))
    }

    @Test
    fun savedActivityStateOverridesLaunchContentAndRestoresPausedPosition() {
        val activity = Robolectric.buildActivity(PlayerActivity::class.java).get()
        activity.intent = Intent().putExtra(PlayerActivity.EXTRA_BVID, "BV1")
        val saved = video.copy(bvid = "BV2", cid = 88, positionMs = 123_000, playWhenReady = false)
        activity.restorePlaybackRecovery(Bundle().apply { putString(RECOVERY_SAVED_RECORD, saved.toJson()) })
        assertEquals("BV2", activity.intent.getStringExtra(PlayerActivity.EXTRA_BVID))
        assertEquals(88L, activity.intent.getLongExtra(PlayerActivity.EXTRA_CID, -1))
        assertEquals(123_000L, activity.intent.getLongExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_POSITION_MS, -1))
        assertFalse(activity.intent.getBooleanExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_PLAY_WHEN_READY, true))
    }

    @Test
    fun coldLaunchRestoresDiskCheckpointAndExplicitEngineSwitchKeepsItsPosition() {
        PlaybackRecoveryStore(context).save("old", video.copy(playWhenReady = false))
        val activity = Robolectric.buildActivity(PlayerActivity::class.java).get()
        activity.intent = Intent().putExtra(PlayerActivity.EXTRA_BVID, "BV1").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        activity.restorePlaybackRecovery(null)
        assertEquals(42_000L, activity.intent.getLongExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_POSITION_MS, -1))
        activity.intent.putExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_POSITION_MS, 55_000L)
            .putExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_PLAY_WHEN_READY, true)
        activity.restorePlaybackRecovery(null)
        assertEquals(55_000L, activity.intent.getLongExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_POSITION_MS, -1))
        assertTrue(activity.intent.getBooleanExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_PLAY_WHEN_READY, false))
    }

    @Test
    fun recreationUsesLatestCheckpointInsteadOfOldEngineSwitchIntent() {
        val activity = Robolectric.buildActivity(PlayerActivity::class.java).get()
        activity.intent = Intent().putExtra(PlayerActivity.EXTRA_BVID, "BV1")
            .putExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_POSITION_MS, 5_000L)
            .putExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_PLAY_WHEN_READY, true)
            .putExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_SESSION_JSON, "old-settings")
        val latest = video.copy(positionMs = 90_000, playWhenReady = false, settingsJson = "latest-settings")
        activity.restorePlaybackRecovery(Bundle().apply { putString(RECOVERY_SAVED_RECORD, latest.toJson()) })
        assertEquals(90_000L, activity.intent.getLongExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_POSITION_MS, -1))
        assertFalse(activity.intent.getBooleanExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_PLAY_WHEN_READY, true))
        assertEquals("latest-settings", activity.intent.getStringExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_SESSION_JSON))
    }

    @Test
    fun controlsAppearImmediatelyWhileCoverRequestIsPending() = runTest {
        val gate = CompletableDeferred<Bitmap?>()
        val ui = fixture(backgroundScope, object : RecoveryCoverSource {
            override suspend fun load(key: String): Bitmap? = gate.await()
            override suspend fun prefetch(key: String, url: String): Bitmap? = null
        })
        ui.controller.begin(video, PlaybackStartReason.ForegroundRestore)
        ui.controller.resume()
        assertEquals(View.GONE, ui.binding.playbackRecovery.root.visibility)
        assertEquals(View.VISIBLE, ui.binding.bottomBar.visibility)
        assertTrue(ui.shown > 0)
        assertTrue(ui.controller.keepControlsVisible)
        assertEquals(View.GONE, ui.binding.recoveryImage.visibility)
        assertTrue(ui.binding.btnPlayPause.hasFocus())
        ui.controller.release(exit = true)
    }

    @Test
    fun frameWinsOverCoverAndSurvivesActivityRecreation() = runTest {
        val owner = Any()
        PlaybackFrameCache.observe(video.contentKey, owner) {}
        val frame = PlaybackFrameCache.beginCapture(video.contentKey, 1920, 1080)!!
        PlaybackFrameCache.completeCapture(frame, true)
        PlaybackFrameCache.detach(owner)
        val cover = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val ui = fixture(backgroundScope, fixedCover(cover))
        ui.controller.begin(video, PlaybackStartReason.ForegroundRestore)
        ui.controller.resume()
        runCurrent()
        assertSame(frame.bitmap, (ui.binding.recoveryImage.drawable as BitmapDrawable).bitmap)
        ui.controller.release(exit = false)
        assertFalse(frame.bitmap.isRecycled)
        val replacement = fixture(backgroundScope, fixedCover(cover))
        replacement.controller.begin(video, PlaybackStartReason.ForegroundRestore)
        replacement.controller.resume()
        assertSame(frame.bitmap, (replacement.binding.recoveryImage.drawable as BitmapDrawable).bitmap)
        replacement.controller.release(exit = true)
        assertTrue(frame.bitmap.isRecycled)
    }

    @Test
    fun coverAppearsWhenNoFrameAndRemainsUntilActualFirstFrame() = runTest {
        val cover = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val ui = fixture(backgroundScope, fixedCover(cover))
        ui.controller.begin(video, PlaybackStartReason.ForegroundRestore)
        ui.controller.resume()
        runCurrent()
        assertSame(cover, (ui.binding.recoveryImage.drawable as BitmapDrawable).bitmap)
        ui.controller.onFirstFrame() // A callback from the previous source is insufficient.
        assertTrue(ui.controller.waiting)
        ui.controller.sourcePreparing()
        assertEquals(View.VISIBLE, ui.binding.recoveryImage.visibility)
        ui.controller.onFirstFrame()
        assertFalse(ui.controller.waiting)
        assertFalse(ui.controller.keepControlsVisible)
        assertEquals(View.GONE, ui.binding.recoveryImage.visibility)
        assertEquals(View.GONE, ui.binding.playbackRecovery.root.visibility)
        assertEquals(1, ui.recovered)
        ui.controller.release(exit = true)
    }

    @Test
    fun pausedWakeRetainsPositionAndPlayEntryWithoutStartingOrTimingOut() = runTest {
        val ui = fixture(backgroundScope)
        ui.controller.begin(video)
        ui.controller.resume()
        ui.controller.sourcePreparing()
        ui.controller.onFirstFrame()
        ui.controller.onPlayIntentChanged(false)
        ui.controller.pause(67_000, false, "paused-settings")
        ui.controller.onPlayIntentChanged(true, fromPlayer = true)
        ui.controller.checkpoint(0, "paused-settings") // Decoder released after onPause.
        ui.controller.resume()
        advanceTimeBy(20_000)
        runCurrent()
        assertFalse(ui.controller.desiredPlaying)
        assertFalse(ui.controller.failed)
        assertEquals(67_000L, ui.controller.record!!.positionMs)
        assertEquals(View.GONE, ui.binding.playbackRecovery.root.visibility)
        assertTrue(ui.binding.btnPlayPause.hasFocus())
        assertEquals(0, ui.retries)
        clickWithRemote(ui.binding.btnPlayPause)
        assertTrue(ui.controller.desiredPlaying)
        assertEquals(1, ui.plays)
        assertEquals(0, ui.retries)
        ui.controller.release(exit = true)
    }

    @Test
    fun timeoutKeepsControlsAndRemoteCanRetryOrExit() = runTest {
        val ui = fixture(backgroundScope)
        ui.controller.begin(video, PlaybackStartReason.ForegroundRestore)
        ui.controller.resume()
        runCurrent()
        advanceTimeBy(15_001)
        runCurrent()
        assertTrue(ui.controller.failed)
        assertTrue(ui.controller.keepControlsVisible)
        assertEquals("重试", ui.binding.playbackRecovery.recoveryAction.text.toString())
        assertTrue(ui.binding.playbackRecovery.recoveryAction.hasFocus())
        clickWithRemote(ui.binding.playbackRecovery.recoveryAction)
        assertFalse(ui.controller.failed)
        assertEquals(1, ui.retries)
        clickWithRemote(ui.binding.playbackRecovery.recoveryBack)
        assertEquals(1, ui.exits)
        ui.controller.release(exit = true)
    }

    @Test
    fun backgroundFirstFrameAndTimeoutCannotDismissRecoveryOrChangeIntent() = runTest {
        val ui = fixture(backgroundScope)
        ui.controller.begin(video)
        ui.controller.resume()
        ui.controller.sourcePreparing()
        ui.controller.pause(0, true, "")
        ui.controller.onFirstFrame()
        ui.controller.onPlayIntentChanged(false, fromPlayer = true)
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(ui.controller.state is PlaybackPresentationState.Background)
        assertFalse(ui.controller.keepControlsVisible)
        assertEquals(View.GONE, ui.binding.playbackRecovery.root.visibility)
        assertFalse(ui.controller.failed)
        assertTrue(ui.controller.desiredPlaying)
        assertEquals(42_000L, ui.controller.record!!.positionMs)
        ui.controller.release(exit = true)
    }

    @Test
    fun changingContentDropsOldImageImmediately() = runTest {
        val ui = fixture(backgroundScope, fixedCover(Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)))
        ui.controller.begin(video, PlaybackStartReason.ForegroundRestore)
        ui.controller.resume()
        runCurrent()
        assertEquals(View.VISIBLE, ui.binding.recoveryImage.visibility)
        ui.controller.begin(video.copy(bvid = "BV2", cid = 2, title = "下一个视频"))
        assertEquals(View.GONE, ui.binding.recoveryImage.visibility)
        assertEquals(View.GONE, ui.binding.playbackRecovery.root.visibility)
        ui.controller.release(exit = true)
    }

    @Test
    fun freshEntryOnlyShowsDelayedLoadingWithoutCoverControlsOrFocusChange() = runTest {
        val ui = fixture(backgroundScope, fixedCover(Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)))
        ui.binding.btnAdvanced.requestFocus()
        ui.controller.begin(video)
        ui.controller.resume()
        runCurrent()
        assertEquals(View.GONE, ui.binding.playbackRecovery.root.visibility)
        assertFalse(ui.controller.record!!.hasRenderedFrame)
        assertFalse(ui.controller.keepControlsVisible)
        advanceTimeBy(501)
        runCurrent()
        assertEquals(View.VISIBLE, ui.binding.playbackRecovery.root.visibility)
        assertEquals(View.VISIBLE, ui.binding.playbackRecovery.recoveryProgress.visibility)
        assertEquals(View.GONE, ui.binding.recoveryImage.visibility)
        assertEquals(View.GONE, ui.binding.playbackRecovery.recoveryError.visibility)
        assertTrue(ui.binding.btnAdvanced.hasFocus())
        assertEquals(0, ui.shown)
        ui.controller.release(exit = true)
    }

    @Test
    fun fastFirstFrameCancelsLoadingHintAndDoesNotFlashRecovery() = runTest {
        val ui = fixture(backgroundScope)
        ui.controller.begin(video)
        ui.controller.resume()
        ui.controller.sourcePreparing()
        ui.controller.onFirstFrame()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(PlaybackPresentationState.Ready, ui.controller.state)
        assertEquals(View.GONE, ui.binding.playbackRecovery.root.visibility)
        assertFalse(ui.controller.failed)
        assertTrue(ui.controller.record!!.hasRenderedFrame)
        ui.controller.release(exit = true)
    }

    @Test
    fun initialLoadInterruptedBeforeFirstFrameContinuesLoadingOnReturn() = runTest {
        val ui = fixture(backgroundScope)
        ui.controller.begin(video)
        ui.controller.resume()
        ui.controller.pause(0, true, "")
        assertEquals(View.GONE, ui.binding.playbackRecovery.root.visibility)
        assertTrue(ui.controller.resume())
        assertEquals(PlaybackStartReason.Initial, ui.controller.startReason)
        advanceTimeBy(501)
        runCurrent()
        assertEquals(View.VISIBLE, ui.binding.playbackRecovery.recoveryProgress.visibility)
        assertEquals(View.GONE, ui.binding.playbackRecovery.recoveryError.visibility)
        assertFalse(ui.controller.keepControlsVisible)
        ui.controller.release(exit = true)
    }

    @Test
    fun newContentAfterRecoveryStartsAsInitialLoad() = runTest {
        val ui = fixture(backgroundScope)
        ui.controller.begin(video, PlaybackStartReason.ForegroundRestore)
        ui.controller.resume()
        assertTrue(ui.controller.keepControlsVisible)
        ui.controller.begin(video.copy(bvid = "BV2", cid = 99))
        assertEquals(PlaybackStartReason.Initial, ui.controller.startReason)
        assertFalse(ui.controller.keepControlsVisible)
        assertEquals(View.GONE, ui.binding.playbackRecovery.root.visibility)
        ui.controller.release(exit = true)
    }

    @Test
    fun normalPauseKeepsRealFrameAndDoesNotEnterRecovery() = runTest {
        val ui = fixture(backgroundScope)
        ui.controller.begin(video)
        ui.controller.resume()
        ui.controller.sourcePreparing()
        ui.controller.onFirstFrame()
        ui.controller.onPlayIntentChanged(false)
        assertEquals(PlaybackPresentationState.Ready, ui.controller.state)
        assertEquals(View.GONE, ui.binding.playbackRecovery.root.visibility)
        assertFalse(ui.controller.desiredPlaying)
        assertEquals(0, ui.retries)
        ui.controller.onPlayIntentChanged(true)
        assertEquals(PlaybackPresentationState.Ready, ui.controller.state)
        assertEquals(0, ui.retries)
        ui.controller.release(exit = true)
    }

    @Test
    fun failureSurvivesBackgroundAndOnlyExplicitRetryStartsAnotherAttempt() = runTest {
        val ui = fixture(backgroundScope)
        ui.controller.begin(video)
        ui.controller.resume()
        ui.controller.fail()
        assertEquals("播放失败", ui.binding.playbackRecovery.recoveryStatus.text.toString())
        ui.controller.pause(0, true, "")
        assertFalse(ui.controller.resume())
        assertTrue(ui.controller.failed)
        assertEquals(0, ui.retries)
        clickWithRemote(ui.binding.playbackRecovery.recoveryAction)
        ui.controller.retry() // A repeated click is not a second request.
        assertEquals(1, ui.retries)
        assertEquals(PlaybackStartReason.Initial, ui.controller.startReason)
        assertFalse(ui.controller.failed)
        ui.controller.release(exit = true)
    }

    @Test
    fun engineSwitchUsesSameSpinnerAndFailureUi() = runTest {
        val ui = fixture(backgroundScope)
        ui.controller.begin(video, PlaybackStartReason.EngineSwitch)
        ui.controller.resume()
        advanceTimeBy(501)
        runCurrent()
        assertEquals(View.VISIBLE, ui.binding.playbackRecovery.recoveryProgress.visibility)
        assertEquals(View.GONE, ui.binding.playbackRecovery.recoveryError.visibility)
        assertFalse(ui.controller.keepControlsVisible)
        ui.controller.fail()
        assertEquals("播放失败", ui.binding.playbackRecovery.recoveryStatus.text.toString())
        ui.controller.release(exit = true)
    }

    @Test
    fun duplicatePlayEventsDoNotPostponeRecoveryTimeout() = runTest {
        val ui = fixture(backgroundScope)
        ui.controller.begin(video, PlaybackStartReason.ForegroundRestore)
        ui.controller.resume()
        ui.controller.sourcePreparing()
        runCurrent()
        advanceTimeBy(14_000)
        ui.controller.onPlayIntentChanged(true, fromPlayer = true)
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(ui.controller.failed)
        ui.controller.release(exit = true)
    }

    @Test
    fun explicitNewLaunchDoesNotInheritOldPausedSessionOfSameVideo() {
        PlaybackRecoveryStore(context).save("old", video.copy(playWhenReady = false))
        val activity = Robolectric.buildActivity(PlayerActivity::class.java).get()
        activity.intent = Intent().putExtra(PlayerActivity.EXTRA_BVID, "BV1")
        activity.restorePlaybackRecovery(null)
        assertEquals(PlaybackStartReason.Initial, activity.initialPlaybackStartReason)
        assertNull(activity.restoredPlaybackRecord)
        assertFalse(activity.intent.hasExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_POSITION_MS))
        assertFalse(activity.intent.hasExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_PLAY_WHEN_READY))
    }

    @Test
    fun playPauseDuringLoadingAndBufferingOnlyChangesPlayIntent() = runTest {
        val ui = fixture(backgroundScope)
        val activity = Robolectric.buildActivity(PlayerActivity::class.java).get()
        activity.binding = ui.binding
        activity.playbackRecovery = ui.controller
        var playing = true
        val changes = mutableListOf<Boolean>()
        activity.player = Proxy.newProxyInstance(BlblPlayerEngine::class.java.classLoader,
            arrayOf(BlblPlayerEngine::class.java)) { _, method, args ->
            when (method.name) {
                "getPlaybackState" -> Player.STATE_BUFFERING
                "getPlayWhenReady" -> playing
                "isPlaying" -> false
                "setPlayWhenReady" -> { playing = args!![0] as Boolean; changes.add(playing); null }
                else -> error("Play/pause must not restart a source: ${method.name}")
            }
        } as BlblPlayerEngine
        ui.controller.begin(video)
        ui.controller.resume()
        activity.togglePlayPause(showControls = false)
        assertFalse(playing)
        activity.togglePlayPause(showControls = false)
        assertTrue(playing)
        ui.controller.sourcePreparing()
        ui.controller.onFirstFrame()
        activity.togglePlayPause(showControls = false)
        assertFalse(playing)
        assertEquals(listOf(false, true, false), changes)
        // Explicit media play/pause commands must not toggle an already selected intent.
        activity.setPlaybackIntent(false)
        activity.setPlaybackIntent(false)
        assertFalse(playing)
        activity.setPlaybackIntent(true)
        activity.setPlaybackIntent(true)
        assertTrue(playing)
        assertEquals(0, ui.retries)
        ui.controller.release(exit = true)
    }

    @Test
    fun playAfterFailedPausedLoadStartsOneRetryWithPlayingIntent() = runTest {
        val ui = fixture(backgroundScope)
        val activity = Robolectric.buildActivity(PlayerActivity::class.java).get()
        activity.binding = ui.binding
        activity.playbackRecovery = ui.controller
        activity.player = Proxy.newProxyInstance(BlblPlayerEngine::class.java.classLoader,
            arrayOf(BlblPlayerEngine::class.java)) { _, method, _ ->
            error("Retry should request a source before touching the failed engine: ${method.name}")
        } as BlblPlayerEngine
        ui.controller.begin(video.copy(playWhenReady = false))
        ui.controller.resume()
        ui.controller.fail()
        activity.setPlaybackIntent(true)
        assertTrue(ui.controller.desiredPlaying)
        assertFalse(ui.controller.failed)
        assertEquals(1, ui.retries)
        assertEquals(View.GONE, ui.binding.playbackRecovery.recoveryError.visibility)
        ui.controller.release(exit = true)
    }

    @Test
    fun coverOnDiskIsAvailableToNewCacheInstanceWithoutNetwork(): Unit = runBlocking {
        val first = PlaybackCoverCache(context)
        val file = first.fileFor(video.contentKey)
        file.parentFile!!.mkdirs()
        val image = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
        val restored = PlaybackCoverCache(context).load(video.contentKey)
        assertNotNull(restored)
        assertTrue(restored!!.width <= 960 && restored.height <= 540)
        assertNull(PlaybackCoverCache(context).load("different-content"))
        file.delete()
    }

    private fun fixedCover(bitmap: Bitmap?) = object : RecoveryCoverSource {
        override suspend fun load(key: String) = bitmap
        override suspend fun prefetch(key: String, url: String) = bitmap
    }

    private class Ui(val binding: ActivityPlayerBinding) {
        lateinit var controller: PlayerRecoveryController
        var shown = 0
        var recovered = 0
        var retries = 0
        var plays = 0
        var exits = 0
    }

    private fun fixture(scope: CoroutineScope, covers: RecoveryCoverSource = fixedCover(null)): Ui {
        val themed = ContextThemeWrapper(context, R.style.Theme_Blbl).apply {
            theme.applyStyle(R.style.ThemeOverlay_Blbl_PlayerOsd_Normal, true)
        }
        val ui = Ui(ActivityPlayerBinding.inflate(LayoutInflater.from(themed)))
        ui.controller = PlayerRecoveryController(context, ui.binding, scope, surfaceView = { null },
            showControls = { ui.shown++; ui.binding.topBar.visibility = View.VISIBLE; ui.binding.bottomBar.visibility = View.VISIBLE },
            onRecovered = { ui.recovered++ }, onRetry = { ui.retries++ }, onExit = { ui.exits++ }, covers = covers)
        ui.binding.btnPlayPause.setOnClickListener {
            ui.plays++
            ui.controller.onPlayIntentChanged(!ui.controller.desiredPlaying)
        }
        return ui
    }

    private fun clickWithRemote(view: View) {
        view.layout(0, 0, 200, 80)
        view.requestFocus()
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
    }
}
