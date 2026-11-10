package blbl.cat3399.feature.player.recovery

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import blbl.cat3399.R
import blbl.cat3399.databinding.ActivityPlayerBinding
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class PlayerRecoveryController(
    context: Context,
    private val binding: ActivityPlayerBinding,
    private val scope: CoroutineScope,
    private val surfaceView: () -> View?,
    private val showControls: () -> Unit,
    private val onRecovered: () -> Unit,
    private val onRetry: () -> Unit,
    private val onExit: () -> Unit,
    private val timeoutMs: Long = 15_000L,
    private val covers: RecoveryCoverSource = PlaybackCoverCache(context),
) {
    private val store = PlaybackRecoveryStore(context)
    private val owner = UUID.randomUUID().toString()
    private var cover: Bitmap? = null
    private var coverJob: Job? = null
    private var timeoutJob: Job? = null
    private var lastSavedAtMs = 0L
    private var sourceCommitted = false
    private var rendered = false
    private var released = false
    private val originalPlayUpId = binding.btnPlayPause.nextFocusUpId
    var record: PlaybackRecoveryRecord? = null
        private set
    var foreground = false
        private set
    var waiting = false
        private set
    var failed = false
        private set
    val keepControlsVisible: Boolean get() = waiting
    val desiredPlaying: Boolean get() = record?.playWhenReady ?: true

    init {
        binding.playbackRecovery.recoveryAction.setOnClickListener {
            if (!failed) record = record?.copy(playWhenReady = true)
            failed = false
            sourceCommitted = false
            render()
            scheduleTimeout()
            onRetry()
        }
        binding.playbackRecovery.recoveryBack.setOnClickListener { onExit() }
    }

    fun begin(next: PlaybackRecoveryRecord) {
        updateContent(next)
        rendered = false
        sourceCommitted = false
        waiting = true
        failed = false
        render(requestFocus = true)
        scheduleTimeout()
        save()
    }

    fun updateContent(next: PlaybackRecoveryRecord) {
        val previous = record
        record = next
        if (previous?.contentKey != next.contentKey) {
            cover = null
            binding.recoveryImage.setImageDrawable(null)
        }
        PlaybackFrameCache.observe(next.contentKey, this) { bitmap ->
            // Drop the old drawable before the cache recycles its bitmap.
            if (bitmap == null) binding.recoveryImage.setImageBitmap(cover)
            if (waiting && !released) renderImage(bitmap ?: cover)
        }
        if (previous?.contentKey != next.contentKey || previous.coverUrl != next.coverUrl) {
            loadCover(next)
        }
        if (waiting) render()
        save()
    }

    fun resume() {
        foreground = true
        if (waiting) {
            render(requestFocus = true)
            scheduleTimeout()
        }
    }

    fun pause(positionMs: Long, playWhenReady: Boolean, settingsJson: String) {
        val current = record ?: return
        record = current.copy(
            positionMs = if (rendered) positionMs.coerceAtLeast(0) else current.positionMs,
            playWhenReady = playWhenReady,
            settingsJson = settingsJson,
        )
        foreground = false
        timeoutJob?.cancel()
        // The request is asynchronous and only copies a scaled video surface, never the 4K window.
        PlaybackFrameCache.capture(current.contentKey, surfaceView(), rendered)
        rendered = false
        waiting = true
        sourceCommitted = false
        render()
        save()
    }

    fun sourcePreparing() {
        rendered = false
        sourceCommitted = true
    }

    fun onFirstFrame() {
        if (!sourceCommitted || released) return
        rendered = true
        if (!foreground) return
        waiting = false
        failed = false
        timeoutJob?.cancel()
        binding.recoveryImage.visibility = View.GONE
        binding.recoveryImage.setImageDrawable(null)
        val hadFocus = binding.playbackRecovery.root.hasFocus()
        binding.playbackRecovery.root.visibility = View.GONE
        binding.btnPlayPause.nextFocusUpId = originalPlayUpId
        if (hadFocus) binding.btnPlayPause.requestFocus()
        onRecovered()
    }

    fun onPlayIntentChanged(playing: Boolean, fromPlayer: Boolean = false) {
        if (!foreground || released) return
        if (fromPlayer && !sourceCommitted) return
        record = record?.copy(playWhenReady = playing)
        if (waiting) {
            render()
            scheduleTimeout()
        }
        save()
    }

    fun fail() {
        if (!waiting || released || !foreground) return
        failed = true
        timeoutJob?.cancel()
        render(requestFocus = true)
    }

    fun updateProgress(positionMs: Long, settingsJson: String) {
        if (!foreground || !rendered || SystemClock.elapsedRealtime() - lastSavedAtMs < 5_000) return
        record = record?.copy(positionMs = positionMs.coerceAtLeast(0), settingsJson = settingsJson)
        save()
    }

    fun checkpoint(positionMs: Long, settingsJson: String): PlaybackRecoveryRecord? {
        record = record?.let { it.copy(positionMs = if (rendered) positionMs.coerceAtLeast(0) else it.positionMs, settingsJson = settingsJson) }
        save()
        return record
    }

    fun release(exit: Boolean) {
        released = true
        foreground = false
        timeoutJob?.cancel()
        coverJob?.cancel()
        binding.recoveryImage.setImageDrawable(null)
        if (exit) {
            store.clear(owner)
            PlaybackFrameCache.clear(this)
        } else {
            PlaybackFrameCache.detach(this)
        }
        cover = null
    }

    private fun save() {
        if (released) return
        record?.let { store.save(owner, it) }
        lastSavedAtMs = SystemClock.elapsedRealtime()
    }

    private fun scheduleTimeout() {
        timeoutJob?.cancel()
        if (!foreground || !waiting || !desiredPlaying || failed || released) return
        timeoutJob = scope.launch {
            delay(timeoutMs)
            fail()
        }
    }

    private fun render(requestFocus: Boolean = false) {
        if (!waiting || released) return
        val current = record ?: return
        renderImage(PlaybackFrameCache.get(current.contentKey) ?: cover)
        val ui = binding.playbackRecovery
        ui.root.visibility = View.VISIBLE
        ui.recoveryTitle.text = current.title.ifBlank { if (current.live) "直播间 ${current.roomId}" else "视频 ${current.bvid.ifBlank { "av${current.aid}" }}" }
        ui.recoveryStatus.text = when {
            failed -> "画面恢复失败，请重试"
            desiredPlaying -> "正在恢复画面…"
            else -> "已暂停"
        }
        ui.recoveryAction.visibility = if (failed || !desiredPlaying) View.VISIBLE else View.GONE
        ui.recoveryAction.text = if (failed) "重试" else "播放"
        binding.btnPlayPause.nextFocusUpId = if (ui.recoveryAction.visibility == View.VISIBLE) R.id.recovery_action else R.id.recovery_back
        showControls()
        if (requestFocus && foreground) {
            val target = if (ui.recoveryAction.visibility == View.VISIBLE) ui.recoveryAction else binding.btnPlayPause
            target.requestFocus()
            target.post { if (waiting && foreground && !released && !binding.root.hasFocus()) target.requestFocus() }
        }
    }

    private fun renderImage(bitmap: Bitmap?) {
        binding.recoveryImage.setImageBitmap(bitmap)
        binding.recoveryImage.visibility = if (bitmap == null) View.GONE else View.VISIBLE
    }

    private fun loadCover(current: PlaybackRecoveryRecord) {
        coverJob?.cancel()
        coverJob = scope.launch {
            fun apply(bitmap: Bitmap?) {
                if (bitmap == null || released || record?.contentKey != current.contentKey) return
                cover = bitmap
                if (waiting) render()
            }
            apply(covers.load(current.contentKey))
            if (current.coverUrl.isNotBlank()) {
                try {
                    apply(covers.prefetch(current.contentKey, current.coverUrl))
                } catch (t: Exception) {
                    if (t is CancellationException) throw t
                    // Controls and a previously cached cover remain available during network failure.
                }
            }
        }
    }
}
