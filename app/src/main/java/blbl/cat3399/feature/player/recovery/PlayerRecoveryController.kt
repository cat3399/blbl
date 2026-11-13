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

private const val FRAME_TIMEOUT_MS = 15_000L
private const val LOADING_HINT_DELAY_MS = 500L

internal class PlayerRecoveryController(
    context: Context,
    private val binding: ActivityPlayerBinding,
    private val scope: CoroutineScope,
    private val surfaceView: () -> View?,
    private val showControls: () -> Unit,
    private val onRecovered: () -> Unit,
    private val onRetry: () -> Unit,
    private val onExit: () -> Unit,
    private val covers: RecoveryCoverSource = PlaybackCoverCache(context),
) {
    private val store = PlaybackRecoveryStore(context)
    private val owner = UUID.randomUUID().toString()
    private var cover: Bitmap? = null
    private var coverJob: Job? = null
    private var timeoutJob: Job? = null
    private var loadingHintJob: Job? = null
    private var loadingHintVisible = false
    private var lastSavedAtMs = 0L
    private var sourceCommitted = false
    private var rendered = false
    private val originalPlayUpId = binding.btnPlayPause.nextFocusUpId
    var record: PlaybackRecoveryRecord? = null
        private set
    var state: PlaybackPresentationState = PlaybackPresentationState.Idle
        private set
    var foreground = false
        private set
    val waiting: Boolean get() = state is PlaybackPresentationState.AwaitingFrame || failed
    val failed: Boolean get() = state is PlaybackPresentationState.Failed
    val desiredPlaying: Boolean get() = record?.playWhenReady ?: true
    val startReason: PlaybackStartReason
        get() = when (val current = state) {
            is PlaybackPresentationState.AwaitingFrame -> current.reason
            is PlaybackPresentationState.Failed -> current.reason
            else -> PlaybackStartReason.Initial
        }
    val keepControlsVisible: Boolean
        get() = foreground && waiting && (failed || startReason == PlaybackStartReason.ForegroundRestore || !desiredPlaying)
    val suppressBufferingOverlay: Boolean get() = state != PlaybackPresentationState.Ready
    private val released: Boolean get() = state == PlaybackPresentationState.Released

    init {
        binding.playbackRecovery.recoveryAction.setOnClickListener { retry() }
        binding.playbackRecovery.recoveryBack.setOnClickListener { onExit() }
    }

    fun begin(next: PlaybackRecoveryRecord, reason: PlaybackStartReason = PlaybackStartReason.Initial) {
        if (released) return
        if (reason == PlaybackStartReason.Initial) PlaybackFrameCache.clear()
        val resolvedReason = if (reason == PlaybackStartReason.ForegroundRestore && !next.hasRenderedFrame) {
            PlaybackStartReason.Initial
        } else reason
        loadingHintVisible = false
        state = PlaybackPresentationState.AwaitingFrame(resolvedReason)
        sourceCommitted = false
        rendered = false
        updateContent(if (reason == PlaybackStartReason.Initial) next.copy(hasRenderedFrame = false) else next)
        render(requestFocus = true)
        scheduleWaitingUi()
    }

    fun updateContent(next: PlaybackRecoveryRecord) {
        if (released) return
        val previous = record
        record = next
        if (previous?.contentKey != next.contentKey) {
            cover = null
            binding.recoveryImage.setImageDrawable(null)
        }
        PlaybackFrameCache.observe(next.contentKey, this) { bitmap ->
            // Drop the drawable before the cache recycles its bitmap, including when hidden.
            binding.recoveryImage.setImageDrawable(null)
            if (foreground && waiting && startReason == PlaybackStartReason.ForegroundRestore) {
                renderImage(bitmap ?: cover)
            }
        }
        if (previous?.contentKey != next.contentKey || previous.coverUrl != next.coverUrl) loadCover(next)
        render()
        save()
    }

    /** Returns true only when foreground entry needs a new source request. */
    fun resume(): Boolean {
        if (released) return false
        foreground = true
        val background = state as? PlaybackPresentationState.Background
        if (background != null) state = background.returnTo
        render(requestFocus = true)
        scheduleWaitingUi()
        return background?.returnTo is PlaybackPresentationState.AwaitingFrame
    }

    fun pause(positionMs: Long, playWhenReady: Boolean, settingsJson: String) {
        val current = record ?: return
        if (released || state is PlaybackPresentationState.Background) return
        record = current.copy(
            positionMs = if (rendered) positionMs.coerceAtLeast(0) else current.positionMs,
            playWhenReady = playWhenReady,
            settingsJson = settingsJson,
        )
        val returnTo = when (val previous = state) {
            is PlaybackPresentationState.Failed -> previous // Returning is not an implicit retry.
            is PlaybackPresentationState.AwaitingFrame -> previous
            PlaybackPresentationState.Ready -> PlaybackPresentationState.AwaitingFrame(PlaybackStartReason.ForegroundRestore)
            else -> PlaybackPresentationState.AwaitingFrame(PlaybackStartReason.Initial)
        }
        foreground = false
        state = PlaybackPresentationState.Background(returnTo)
        cancelWaitingUi()
        // PixelCopy reads only the video surface and completes asynchronously.
        PlaybackFrameCache.capture(current.contentKey, surfaceView(), rendered)
        rendered = false
        sourceCommitted = false
        hidePresentation()
        save()
    }

    fun sourcePreparing() {
        if (released || state is PlaybackPresentationState.Background) return
        rendered = false
        sourceCommitted = true
    }

    fun onFirstFrame() {
        if (!sourceCommitted || !foreground || released || state is PlaybackPresentationState.Background) return
        rendered = true
        record = record?.copy(hasRenderedFrame = true)
        val completedWait = waiting
        state = PlaybackPresentationState.Ready
        cancelWaitingUi()
        hidePresentation()
        save()
        if (completedWait) onRecovered()
    }

    fun onPlayIntentChanged(playing: Boolean, fromPlayer: Boolean = false) {
        if (!foreground || released || state is PlaybackPresentationState.Background) return
        if (fromPlayer && (!sourceCommitted || failed)) return
        if (desiredPlaying == playing) return
        record = record?.copy(playWhenReady = playing)
        render()
        // A duplicate engine event must not extend the timeout or start another request.
        scheduleWaitingUi()
        save()
    }

    fun retry() {
        val failure = state as? PlaybackPresentationState.Failed ?: return
        if (!foreground) return
        state = PlaybackPresentationState.AwaitingFrame(failure.reason)
        sourceCommitted = false
        rendered = false
        loadingHintVisible = false
        render(requestFocus = true)
        scheduleWaitingUi()
        onRetry()
    }

    fun fail() {
        val pending = state as? PlaybackPresentationState.AwaitingFrame ?: return
        state = PlaybackPresentationState.Failed(pending.reason)
        cancelWaitingUi()
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
        state = PlaybackPresentationState.Released
        foreground = false
        cancelWaitingUi()
        coverJob?.cancel()
        hidePresentation()
        if (exit) {
            store.clear(owner)
            PlaybackFrameCache.clear(this)
        } else PlaybackFrameCache.detach(this)
        cover = null
    }

    private fun save() {
        if (released) return
        record?.let { store.save(owner, it) }
        lastSavedAtMs = SystemClock.elapsedRealtime()
    }

    private fun cancelWaitingUi() {
        timeoutJob?.cancel()
        loadingHintJob?.cancel()
        timeoutJob = null
        loadingHintJob = null
    }

    private fun scheduleWaitingUi() {
        cancelWaitingUi()
        if (!foreground || state !is PlaybackPresentationState.AwaitingFrame || !desiredPlaying) return
        timeoutJob = scope.launch { delay(FRAME_TIMEOUT_MS); fail() }
        if (!loadingHintVisible) {
            loadingHintJob = scope.launch {
                delay(LOADING_HINT_DELAY_MS)
                loadingHintVisible = true
                render()
            }
        }
    }

    private fun render(requestFocus: Boolean = false) {
        if (!foreground || !waiting || released) {
            hidePresentation()
            return
        }
        val current = record ?: return
        val restoring = startReason == PlaybackStartReason.ForegroundRestore
        val showSpinner = loadingHintVisible && desiredPlaying && !failed
        val ui = binding.playbackRecovery
        renderImage(if (restoring) PlaybackFrameCache.get(current.contentKey) ?: cover else null)
        binding.bufferingOverlay.visibility = View.GONE
        val hadErrorFocus = ui.recoveryError.hasFocus()
        ui.root.visibility = if (failed || showSpinner) View.VISIBLE else View.GONE
        ui.recoveryProgress.visibility = if (showSpinner) View.VISIBLE else View.GONE
        ui.recoveryError.visibility = if (failed) View.VISIBLE else View.GONE
        binding.btnPlayPause.nextFocusUpId = if (failed) R.id.recovery_action else originalPlayUpId
        if (hadErrorFocus && !failed) binding.btnPlayPause.requestFocus()
        if (keepControlsVisible) {
            showControls()
            if (requestFocus) {
                val target = if (failed) ui.recoveryAction else binding.btnPlayPause
                target.requestFocus()
                target.post { if (keepControlsVisible && !binding.root.hasFocus()) target.requestFocus() }
            }
        }
    }

    private fun hidePresentation() {
        val hadFocus = binding.playbackRecovery.root.hasFocus()
        binding.playbackRecovery.root.visibility = View.GONE
        binding.playbackRecovery.recoveryError.visibility = View.GONE
        binding.playbackRecovery.recoveryProgress.visibility = View.GONE
        binding.recoveryImage.visibility = View.GONE
        binding.recoveryImage.setImageDrawable(null)
        binding.btnPlayPause.nextFocusUpId = originalPlayUpId
        if (hadFocus && foreground) binding.btnPlayPause.requestFocus()
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
                render()
            }
            apply(covers.load(current.contentKey))
            if (current.coverUrl.isNotBlank()) {
                try {
                    apply(covers.prefetch(current.contentKey, current.coverUrl))
                } catch (t: Exception) {
                    if (t is CancellationException) throw t
                }
            }
        }
    }
}
