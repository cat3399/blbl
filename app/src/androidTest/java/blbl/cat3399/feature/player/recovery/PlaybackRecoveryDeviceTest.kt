@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player.recovery

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.feature.live.LivePlayerActivity
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin
import java.io.File
import kotlinx.coroutines.runBlocking

/** Opt-in device acceptance runner. Run on a dedicated test install with -PapplicationIdSuffix=.recoverytest. */
class PlaybackRecoveryDeviceTest : Instrumentation() {
    private lateinit var args: Bundle
    private lateinit var activity: Activity
    private val results = StringBuilder()
    private var resumeSnapshot: Snapshot? = null
    private val testPrefs get() = targetContext.getSharedPreferences("recovery_device_test", 0)
    private data class Snapshot(val image: Boolean, val frame: Boolean, val failed: Boolean, val playing: Boolean, val controls: Boolean, val focus: Boolean)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        try {
            check(targetContext.packageName.endsWith(".recoverytest")) { "Use the dedicated .recoverytest install" }
            val app = targetContext.applicationContext as Application
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(a: Activity) {
                    if (a is PlayerActivity || a is LivePlayerActivity) {
                        activity = a
                        val ui = binding(a)
                        val controller = recovery(a)
                        val bitmap = (ui.recoveryImage.drawable as? BitmapDrawable)?.bitmap
                        resumeSnapshot = Snapshot(ui.recoveryImage.visibility == View.VISIBLE,
                            bitmap != null && bitmap === controller.record?.let { PlaybackFrameCache.get(it.contentKey) },
                            ui.playbackRecovery.recoveryError.visibility == View.VISIBLE, controller.desiredPlaying,
                            ui.bottomBar.visibility == View.VISIBLE, a.currentFocus != null)
                    }
                }
                override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(a: Activity) = Unit
                override fun onActivityPaused(a: Activity) = Unit
                override fun onActivityStopped(a: Activity) = Unit
                override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(a: Activity) = Unit
            })
            when (args.getString("phase", "matrix")) {
                "after_reboot" -> afterReboot()
                else -> matrix()
            }
            note("PASS")
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", results.toString()) })
        } catch (t: Throwable) {
            note("FAIL ${t.stackTraceToString()}")
            if (::activity.isInitialized) screenshot("failure")
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", results.toString()) })
        }
    }

    private fun matrix() {
        val live = args.getString("media", "vod") == "live"
        val ijk = args.getString("engine", "exo") == "ijk"
        if (ijk) {
            note("Installing IJK if needed")
            runBlocking { IjkPlayerPlugin.installIfNeeded(targetContext) {} }
        }
        main { BiliClient.prefs.playerEngineKind = if (ijk) AppPrefs.PLAYER_ENGINE_IJK else AppPrefs.PLAYER_ENGINE_EXO }
        val record = runBlocking {
            if (live) {
                val room = BiliApi.liveRecommend().first()
                PlaybackRecoveryRecord(entryKey = "live:${room.roomId}", live = true, roomId = room.roomId,
                    title = room.title, coverUrl = room.coverUrl.orEmpty())
            } else {
                val card = BiliApi.popular(ps = 5).first()
                PlaybackRecoveryRecord(entryKey = PlaybackRecoveryRecord.vodKey(card.bvid, card.aid ?: 0, card.cid ?: 0, 0),
                    bvid = card.bvid, cid = card.cid ?: 0, aid = card.aid ?: 0, title = card.title, coverUrl = card.coverUrl.orEmpty())
            }
        }
        launch(record)
        check(resumeSnapshot?.failed == false) { "Fresh entry incorrectly showed failure: $resumeSnapshot" }
        main {
            check(recovery().startReason == PlaybackStartReason.Initial)
            check(!recovery().keepControlsVisible)
        }
        note("Fresh entry uses normal loading without recovery overlay")
        await("initial first frame", 45_000) { !recovery().waiting && engine().isPlaying }
        await("local cover", 30_000) { PlaybackCoverCache(targetContext).fileFor(recovery().record!!.contentKey).exists() }
        note("Initial playback ${engine().kind} live=$live")

        for (fallback in listOf("frame", "cover", "controls")) {
            openSettings()
            await("capture", 5_000) { PlaybackFrameCache.get(recovery().record!!.contentKey) != null }
            val frame = main { PlaybackFrameCache.get(recovery().record!!.contentKey)!! }
            check(frame.width <= 960 && frame.height <= 540 && frame.byteCount <= 960 * 540 * 4)
            note("Captured ${frame.width}x${frame.height} ${frame.byteCount} bytes")
            main {
                if (fallback != "frame") PlaybackFrameCache.clear()
                if (fallback == "controls") {
                    PlaybackCoverCache(targetContext).fileFor(recovery().record!!.contentKey).delete()
                    field(recovery(), "cover", null)
                    recovery().record?.let { recovery().updateContent(it.copy(coverUrl = "")) }
                    binding().recoveryImage.setImageDrawable(null)
                    binding().recoveryImage.visibility = View.GONE
                }
            }
            resumeSnapshot = null
            key(KeyEvent.KEYCODE_BACK)
            await("return from settings", 8_000) { recovery().foreground && resumeSnapshot != null }
            val snapshot = resumeSnapshot!!
            check(snapshot.focus) { "No remote focus on return: $snapshot" }
            check(!snapshot.failed && snapshot.playing && snapshot.controls) { snapshot.toString() }
            when (fallback) {
                "frame" -> check(snapshot.frame) { "Missing static frame: $snapshot" }
                "cover" -> check(snapshot.image && !snapshot.frame) { "Missing cover: $snapshot" }
                else -> check(!snapshot.image) { "Controls-only fallback not selected: $snapshot" }
            }
            note("Settings return $fallback $snapshot")
            await("recovered frame", 45_000) { !recovery().waiting && engine().isPlaying }
        }

        main { recovery().onPlayIntentChanged(false); engine().pause() }
        val pausedPosition = main { engine().currentPosition }
        key(KeyEvent.KEYCODE_SLEEP)
        await("sleep pause", 10_000) { !recovery().foreground }
        SystemClock.sleep(1_000)
        resumeSnapshot = null
        key(KeyEvent.KEYCODE_WAKEUP)
        await("wake", 12_000) { recovery().foreground && resumeSnapshot != null }
        check(!resumeSnapshot!!.playing && !resumeSnapshot!!.failed && resumeSnapshot!!.controls) { resumeSnapshot.toString() }
        SystemClock.sleep(3_000)
        main {
            check(!engine().playWhenReady && !engine().isPlaying) { "Paused content started on wake" }
            if (!live) check(kotlin.math.abs(recovery().record!!.positionMs - pausedPosition) < 1_500)
        }
        screenshot("paused-wake")
        note("Paused wake retained pause at $pausedPosition")

        // Resume with a real remote center press, then verify the actual decoder's first frame.
        main { binding().btnPlayPause.requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("remote play", 45_000) { !recovery().waiting && engine().isPlaying }
        key(KeyEvent.KEYCODE_SLEEP)
        await("playing sleep", 10_000) { !recovery().foreground }
        SystemClock.sleep(1_000)
        key(KeyEvent.KEYCODE_WAKEUP)
        await("playing wake first frame", 45_000) { recovery().foreground && !recovery().waiting && engine().isPlaying }
        note("Playing wake recovered")

        // Fault injection: stop the decoder after entering recovery and wait for the real timeout.
        main {
            recovery().begin(recovery().record!!.copy(positionMs = engine().currentPosition, playWhenReady = true), PlaybackStartReason.ForegroundRestore)
            engine().stop()
        }
        await("recovery timeout", 18_000) { recovery().failed }
        main {
            check(binding().bottomBar.visibility == View.VISIBLE)
            check(binding().playbackRecovery.recoveryAction.hasFocus())
        }
        screenshot("timeout")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("remote retry first frame", 45_000) { !recovery().waiting && engine().isPlaying }
        note("Timeout and remote retry recovered")

        // Leave a paused, persisted playback checkpoint for a subsequent real TV reboot.
        main { recovery().onPlayIntentChanged(false); engine().pause() }
        openSettings()
        await("pre-reboot frame", 5_000) { PlaybackFrameCache.get(recovery().record!!.contentKey) != null }
        val checkpoint = main { recovery().record!! }
        testPrefs.edit().putString("checkpoint", checkpoint.toJson()).commit()
        note("READY_FOR_REBOOT live=$live position=${checkpoint.positionMs} paused=${!checkpoint.playWhenReady}")
    }

    private fun afterReboot() {
        val saved = PlaybackRecoveryRecord.fromJson(testPrefs.getString("checkpoint", null))!!
        check(runBlocking { PlaybackCoverCache(targetContext).load(saved.contentKey) } != null)
        launch(saved, fromHistory = true)
        await("cold resume", 10_000) { resumeSnapshot != null }
        check(!resumeSnapshot!!.frame)
        check(!resumeSnapshot!!.playing && !resumeSnapshot!!.failed && resumeSnapshot!!.controls) { resumeSnapshot.toString() }
        await("cover or paused frame", 15_000) { binding().recoveryImage.visibility == View.VISIBLE || !recovery().waiting }
        SystemClock.sleep(3_000)
        main {
            check(!engine().playWhenReady && !engine().isPlaying)
            check(recovery().record!!.positionMs == saved.positionMs)
        }
        screenshot("after-reboot")
        note("Reboot restored local cover and paused position=${saved.positionMs} live=${saved.live}")
        main {
            recovery().begin(recovery().record!!, PlaybackStartReason.ForegroundRestore)
            recovery().fail()
            binding().playbackRecovery.recoveryBack.requestFocus()
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("remote exit", 5_000) { activity.isFinishing }
        note("Remote back exited")
    }

    private fun launch(saved: PlaybackRecoveryRecord, fromHistory: Boolean = false) {
        val intent = if (saved.live) Intent(targetContext, LivePlayerActivity::class.java)
            .putExtra(LivePlayerActivity.EXTRA_ROOM_ID, saved.roomId)
            .putExtra(LivePlayerActivity.EXTRA_TITLE, saved.title)
            .putExtra(LivePlayerActivity.EXTRA_COVER, saved.coverUrl)
        else Intent(targetContext, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_BVID, saved.bvid).putExtra(PlayerActivity.EXTRA_CID, saved.cid)
            .putExtra(PlayerActivity.EXTRA_AID, saved.aid).putExtra(PlayerActivity.EXTRA_TITLE, saved.title)
            .putExtra(PlayerActivity.EXTRA_COVER, saved.coverUrl)
        if (fromHistory) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        activity = startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openSettings() {
        main { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        await("settings pause", 10_000) { !recovery().foreground }
        SystemClock.sleep(500)
    }

    private fun recovery(a: Activity = activity): PlayerRecoveryController = readField(a, "playbackRecovery") as PlayerRecoveryController
    private fun binding(a: Activity = activity): ActivityPlayerBinding = readField(a, "binding") as ActivityPlayerBinding
    private fun engine(): BlblPlayerEngine = readField(activity, "player") as BlblPlayerEngine
    private fun readField(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
    private fun field(value: Any, name: String, next: Any?) { value.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(value, next) }
    private fun key(code: Int) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("input keyevent $code")).use { it.readBytes() }
    }
    private fun note(message: String) {
        results.appendLine(message)
        sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }
    private fun await(label: String, timeout: Long, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            if (main(predicate)) return
            SystemClock.sleep(100)
        }
        error("Timed out: $label")
    }
    private fun screenshot(label: String) {
        runCatching {
            val bitmap = uiAutomation.takeScreenshot() ?: return
            File(targetContext.filesDir, "recovery-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        var error: Throwable? = null
        runOnMainSync { try { result = block() } catch (t: Throwable) { error = t } }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST") return result as T
    }
}
