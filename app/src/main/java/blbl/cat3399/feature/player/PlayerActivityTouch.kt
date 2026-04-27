package blbl.cat3399.feature.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.databinding.ViewPlayerTouchOverlayBinding
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun PlayerActivity.initTouchGestures() {
    if (!BiliClient.prefs.playerTouchGesturesEnabled) {
        releaseTouchGestures()
        return
    }
    if (touchController != null) return
    touchController =
        PlayerTouchController(VodPlayerTouchHost(this), requirePlayerTouchOverlayBinding(binding)).also { controller ->
            controller.install()
        }
}

internal fun PlayerActivity.isTouchLocked(): Boolean = touchController?.isTouchLocked() == true

internal fun PlayerActivity.onTouchOverlayStateChanged() {
    touchController?.onControlsStateChanged()
}

internal fun PlayerActivity.releaseTouchGestures() {
    touchController?.release()
    touchController = null
    tapSeekActiveDirection = 0
    tapSeekActiveUntilMs = 0L
}

internal data class PlayerTouchSeekSnapshot(
    val startPositionMs: Long,
    val durationMs: Long,
    val bufferedPositionMs: Long,
)

internal interface PlayerTouchGestureHost {
    val activity: Activity
    val binding: ActivityPlayerBinding
    val lifecycleScope: LifecycleCoroutineScope
    val player: BlblPlayerEngine?
    val controlsVisibleForTouch: Boolean
    val isSidePanelVisible: Boolean
    val isBottomCardPanelVisible: Boolean
    val isCommentImageViewerVisible: Boolean
    val seekDelegate: PlayerTouchSeekDelegate?
        get() = null
    val boostDelegate: PlayerTouchBoostDelegate?
        get() = null

    fun setControlsVisibleFromTouch(visible: Boolean)
    fun closeSidePanelFromTouch(): Boolean
    fun togglePlayPauseFromTouch()
    fun noteUserInteractionFromTouch()
    fun showTouchHint(text: String, hold: Boolean)
    fun scheduleHideTouchHint()
}

internal interface PlayerTouchSeekDelegate {
    var tapSeekActiveDirection: Int
    var tapSeekActiveUntilMs: Long

    fun smartSeekFromTouch(direction: Int)
    fun beginTouchSeek(): PlayerTouchSeekSnapshot?
    fun setTouchSeekPreviewPosition(positionMs: Long?)
    fun showTouchSeekPreview(posMs: Long, durationMs: Long, bufferedPositionMs: Long)
    fun finishTouchSeek(targetMs: Long, durationMs: Long, commit: Boolean)
}

internal interface PlayerTouchBoostDelegate {
    fun touchBoostSpeed(): Float
    fun touchBoostSpeedText(speed: Float): String
}

private class VodPlayerTouchHost(
    private val playerActivity: PlayerActivity,
) : PlayerTouchGestureHost, PlayerTouchSeekDelegate, PlayerTouchBoostDelegate {
    override val activity: Activity
        get() = playerActivity
    override val binding: ActivityPlayerBinding
        get() = playerActivity.binding
    override val lifecycleScope: LifecycleCoroutineScope
        get() = playerActivity.lifecycleScope
    override val player: BlblPlayerEngine?
        get() = playerActivity.player
    override val controlsVisibleForTouch: Boolean
        get() = playerActivity.osdMode == PlayerActivity.OsdMode.Full
    override val isSidePanelVisible: Boolean
        get() = playerActivity.isSidePanelVisible()
    override val isBottomCardPanelVisible: Boolean
        get() = playerActivity.isBottomCardPanelVisible()
    override val isCommentImageViewerVisible: Boolean
        get() = binding.commentImageViewer.visibility == View.VISIBLE
    override val seekDelegate: PlayerTouchSeekDelegate
        get() = this
    override val boostDelegate: PlayerTouchBoostDelegate
        get() = this

    override var tapSeekActiveDirection: Int
        get() = playerActivity.tapSeekActiveDirection
        set(value) {
            playerActivity.tapSeekActiveDirection = value
        }
    override var tapSeekActiveUntilMs: Long
        get() = playerActivity.tapSeekActiveUntilMs
        set(value) {
            playerActivity.tapSeekActiveUntilMs = value
        }

    override fun setControlsVisibleFromTouch(visible: Boolean) {
        playerActivity.setControlsVisible(visible)
    }

    override fun closeSidePanelFromTouch(): Boolean = playerActivity.onSidePanelBackPressed()

    override fun togglePlayPauseFromTouch() {
        binding.btnPlayPause.performClick()
    }

    override fun smartSeekFromTouch(direction: Int) {
        playerActivity.smartSeek(direction = direction, showControls = false, hintKind = SeekHintKind.Step)
    }

    override fun noteUserInteractionFromTouch() {
        playerActivity.noteUserInteraction()
    }

    override fun beginTouchSeek(): PlayerTouchSeekSnapshot? {
        val engine = playerActivity.player ?: return null
        val duration = engine.duration.takeIf { it > 0 } ?: playerActivity.currentViewDurationMs ?: return null
        playerActivity.cancelPendingAutoResume(reason = "user_seek")
        playerActivity.cancelPendingAutoSkip(reason = "user_seek", markIgnored = true)
        playerActivity.cancelPendingAutoNext(reason = "user_seek", markCancelledByUser = false)
        playerActivity.cancelDeferredKeySeekPreview(resetScrubbing = false)
        playerActivity.scrubbing = true
        playerActivity.keyScrubPendingSeekToMs = null
        playerActivity.keyScrubEndJob?.cancel()
        val startPos = engine.currentPosition.coerceIn(0L, duration)
        playerActivity.holdScrubPreviewPosMs = startPos
        return PlayerTouchSeekSnapshot(
            startPositionMs = startPos,
            durationMs = duration,
            bufferedPositionMs = engine.bufferedPosition.coerceAtLeast(0L),
        )
    }

    override fun setTouchSeekPreviewPosition(positionMs: Long?) {
        playerActivity.holdScrubPreviewPosMs = positionMs
    }

    override fun showTouchSeekPreview(posMs: Long, durationMs: Long, bufferedPositionMs: Long) {
        playerActivity.showSeekOsd(
            posMs = posMs,
            durationMs = durationMs,
            bufferedPosMs = bufferedPositionMs,
        )
    }

    override fun finishTouchSeek(targetMs: Long, durationMs: Long, commit: Boolean) {
        val engine = playerActivity.player
        playerActivity.holdScrubPreviewPosMs = null
        playerActivity.scrubbing = false
        if (commit && engine != null) {
            engine.seekTo(targetMs)
            playerActivity.requestDanmakuSegmentsForPosition(targetMs, immediate = true)
            playerActivity.requestReportProgressOnce(reason = "user_seek_end")
            playerActivity.showSeekOsd(
                posMs = targetMs,
                durationMs = durationMs,
                bufferedPosMs = engine.bufferedPosition.coerceAtLeast(0L),
            )
        } else if (!commit) {
            playerActivity.showSeekOsd()
        }
        playerActivity.restartAutoHideTimer()
    }

    override fun showTouchHint(text: String, hold: Boolean) {
        playerActivity.showSeekHint(text, hold)
    }

    override fun scheduleHideTouchHint() {
        playerActivity.scheduleHideSeekHint()
    }

    override fun touchBoostSpeed(): Float = playerActivity.holdSeekSpeed()

    override fun touchBoostSpeedText(speed: Float): String = playerActivity.holdSeekSpeedText(speed)
}

internal fun isSwipeGestureStartExcludedByEdge(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    edgeRatio: Float,
): Boolean {
    if (width <= 0f || height <= 0f) return false
    val clampedRatio = edgeRatio.coerceIn(0f, 0.5f)
    val excludedWidth = width * clampedRatio
    val excludedHeight = height * clampedRatio
    return x <= excludedWidth ||
        x >= width - excludedWidth ||
        y <= excludedHeight ||
        y >= height - excludedHeight
}

internal fun requirePlayerTouchOverlayBinding(binding: ActivityPlayerBinding): ViewPlayerTouchOverlayBinding {
    val existing = binding.root.findViewById<View>(R.id.player_touch_overlay_root)
    val overlayBinding =
        if (existing != null) {
            ViewPlayerTouchOverlayBinding.bind(existing)
        } else {
            ViewPlayerTouchOverlayBinding.bind(binding.playerTouchOverlayStub.inflate())
        }
    if (Build.VERSION.SDK_INT >= 26) {
        overlayBinding.root.defaultFocusHighlightEnabled = false
        overlayBinding.touchGestureLayer.defaultFocusHighlightEnabled = false
    }
    return overlayBinding
}

internal class PlayerTouchController(
    private val host: PlayerTouchGestureHost,
    private val overlayBinding: ViewPlayerTouchOverlayBinding,
) {
    private enum class TouchGestureMode {
        None,
        Seek,
        Brightness,
        Volume,
        Blocked,
    }

    private val activity: Activity
        get() = host.activity

    private val binding: ActivityPlayerBinding
        get() = host.binding

    private val touchSlopPx = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
    private val displayDensity = activity.resources.displayMetrics.density
    private val seekActivationThresholdPx =
        maxOf(
            touchSlopPx * PlayerActivity.TOUCH_GESTURE_SEEK_START_THRESHOLD_MULTIPLIER,
            displayDensity * PlayerActivity.TOUCH_GESTURE_SEEK_START_MIN_DP,
        )
    private val verticalActivationThresholdPx =
        maxOf(
            touchSlopPx * PlayerActivity.TOUCH_GESTURE_VERTICAL_START_THRESHOLD_MULTIPLIER,
            displayDensity * PlayerActivity.TOUCH_GESTURE_VERTICAL_START_MIN_DP,
        )
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val tapDetector =
        GestureDetector(
            activity,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (tapSuppressed || boostActive || gestureMode != TouchGestureMode.None) return true
                    if (host.isSidePanelVisible) return true
                    val width = gestureLayerWidth()
                    if (width <= 0f) return true
                    val dir = edgeDirection(e.x, width)
                    if (dir == 0) {
                        host.togglePlayPauseFromTouch()
                        return true
                    }
                    val seekDelegate = host.seekDelegate ?: return true
                    if (host.controlsVisibleForTouch) {
                        host.setControlsVisibleFromTouch(false)
                        return true
                    }

                    seekDelegate.smartSeekFromTouch(dir)
                    seekDelegate.tapSeekActiveDirection = dir
                    seekDelegate.tapSeekActiveUntilMs = android.os.SystemClock.uptimeMillis() + touchTapSeekActiveMs
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (tapSuppressed || boostActive || gestureMode != TouchGestureMode.None) return true
                    if (touchLocked) {
                        toggleLockedButtonVisibility()
                        return true
                    }
                    if (host.isSidePanelVisible) return host.closeSidePanelFromTouch()
                    if (host.controlsVisibleForTouch) {
                        host.setControlsVisibleFromTouch(false)
                        return true
                    }

                    val now = android.os.SystemClock.uptimeMillis()
                    val width = gestureLayerWidth()
                    val seekDelegate = host.seekDelegate
                    if (seekDelegate != null && width > 0f && now <= seekDelegate.tapSeekActiveUntilMs) {
                        val dir = edgeDirection(e.x, width)
                        if (dir != 0 && dir == seekDelegate.tapSeekActiveDirection) {
                            seekDelegate.smartSeekFromTouch(dir)
                            seekDelegate.tapSeekActiveUntilMs = now + touchTapSeekActiveMs
                            return true
                        }
                    }

                    host.setControlsVisibleFromTouch(true)
                    return true
                }
            },
        )

    private var gestureMode: TouchGestureMode = TouchGestureMode.None
    private var pointerDown = false
    private var tapSuppressed = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pendingRightEdgeBoostJob: Job? = null
    private var boostActive = false
    private var boostPrevSpeed = 1.0f
    private var boostPrevPlayWhenReady = false
    private var touchSeekStartPosMs = 0L
    private var touchSeekPreviewPosMs: Long? = null
    private var touchSeekDurationMs = 0L
    private var touchSeekBufferedPosMs = 0L
    private var swipeGestureStartAllowed = true
    private var volumeStart = 0
    private var brightnessStart = 0.5f
    private var touchLocked = false
    private var lockedButtonVisible = false
    private var lockUiHideJob: Job? = null

    fun install() {
        overlayBinding.root.visibility = View.VISIBLE
        overlayBinding.touchGestureLayer.visibility = View.VISIBLE
        overlayBinding.touchGestureLayer.setOnTouchListener(this::onTouch)
        overlayBinding.btnTouchLock.setOnClickListener {
            if (touchLocked) {
                unlockTouch()
            } else {
                lockTouch()
            }
        }
        updateLockUi()
    }

    fun isTouchLocked(): Boolean = touchLocked

    fun onControlsStateChanged() {
        if (!touchLocked) {
            lockedButtonVisible = false
            lockUiHideJob?.cancel()
        }
        updateLockUi()
    }

    fun onStop() {
        pendingRightEdgeBoostJob?.cancel()
        pendingRightEdgeBoostJob = null
        finishActiveGesture(commitSeek = false)
        stopBoostPlayback()
        pointerDown = false
        swipeGestureStartAllowed = true
        tapSuppressed = false
    }

    fun release() {
        onStop()
        lockUiHideJob?.cancel()
        overlayBinding.touchGestureLayer.setOnTouchListener(null)
        overlayBinding.touchGestureLayer.visibility = View.GONE
        overlayBinding.btnTouchLock.setOnClickListener(null)
        overlayBinding.btnTouchLock.visibility = View.GONE
        overlayBinding.root.visibility = View.GONE
    }

    private fun onTouch(v: View, event: MotionEvent): Boolean {
        if (touchLocked) {
            handleLockedTouch(event)
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            beginTracking(event)
        } else if (!pointerDown && event.actionMasked != MotionEvent.ACTION_CANCEL) {
            return false
        }

        val detectorHandled = tapDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!host.isSidePanelVisible) scheduleRightEdgeBoostIfNeeded()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                tapSuppressed = true
                pendingRightEdgeBoostJob?.cancel()
                finishActiveGesture(commitSeek = false)
                stopBoostPlayback()
                gestureMode = TouchGestureMode.Blocked
            }

            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_UP -> {
                finishTouch(cancelled = false)
                if (detectorHandled && !tapSuppressed) v.performClick()
            }

            MotionEvent.ACTION_CANCEL -> finishTouch(cancelled = true)
        }
        return true
    }

    private fun handleLockedTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerDown = true
                tapSuppressed = false
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                lastX = event.x
                lastY = event.y
                if (!tapSuppressed && hasExceededTouchSlop(event.x, event.y)) {
                    tapSuppressed = true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!tapSuppressed) toggleLockedButtonVisibility()
                pointerDown = false
                tapSuppressed = false
            }

            MotionEvent.ACTION_CANCEL -> {
                pointerDown = false
                tapSuppressed = false
            }
        }
    }

    private fun beginTracking(event: MotionEvent) {
        pointerDown = true
        tapSuppressed = false
        gestureMode = TouchGestureMode.None
        downX = event.x
        downY = event.y
        lastX = event.x
        lastY = event.y
        swipeGestureStartAllowed =
            !isSwipeGestureStartExcludedByEdge(
                x = event.x,
                y = event.y,
                width = gestureLayerWidth(),
                height = gestureLayerHeight(),
                edgeRatio = PlayerActivity.TOUCH_GESTURE_EXCLUDED_EDGE_RATIO,
            )
        pendingRightEdgeBoostJob?.cancel()
    }

    private fun handleMove(event: MotionEvent) {
        lastX = event.x
        lastY = event.y

        if (boostActive) return

        if (host.isSidePanelVisible) {
            if (!tapSuppressed && hasExceededTouchSlop(event.x, event.y)) {
                tapSuppressed = true
            }
            pendingRightEdgeBoostJob?.cancel()
            return
        }

        val dx = event.x - downX
        val dy = event.y - downY
        val absDx = abs(dx)
        val absDy = abs(dy)
        if (absDx > touchSlopPx || absDy > touchSlopPx) {
            pendingRightEdgeBoostJob?.cancel()
            tapSuppressed = true
        }

        when (gestureMode) {
            TouchGestureMode.Seek -> updateSeekGesture(event.x)
            TouchGestureMode.Brightness -> updateBrightnessGesture(event.y)
            TouchGestureMode.Volume -> updateVolumeGesture(event.y)
            TouchGestureMode.Blocked -> Unit
            TouchGestureMode.None -> {
                if (!swipeGestureStartAllowed) {
                    if (shouldBlockGestureRecognition(absDx = absDx, absDy = absDy)) {
                        gestureMode = TouchGestureMode.Blocked
                    }
                    return
                }

                val directionRatio = PlayerActivity.TOUCH_GESTURE_DIRECTION_RATIO
                if (absDx >= seekActivationThresholdPx && absDx >= absDy * directionRatio) {
                    if (!startSeekGesture()) {
                        gestureMode = TouchGestureMode.Blocked
                    } else {
                        updateSeekGesture(event.x)
                    }
                    return
                }

                val width = gestureLayerWidth().coerceAtLeast(1f)
                val sideThreshold = PlayerActivity.TOUCH_GESTURE_SIDE_VERTICAL_THRESHOLD
                val verticalGestureEligible = downX <= width * sideThreshold || downX >= width * (1f - sideThreshold)
                if (verticalGestureEligible && absDy >= verticalActivationThresholdPx && absDy >= absDx * directionRatio) {
                    if (downX <= width * sideThreshold) {
                        startBrightnessGesture()
                        updateBrightnessGesture(event.y)
                    } else {
                        startVolumeGesture()
                        updateVolumeGesture(event.y)
                    }
                    return
                }

                if (shouldBlockGestureRecognition(absDx = absDx, absDy = absDy)) {
                    gestureMode = TouchGestureMode.Blocked
                }
            }
        }
    }

    private fun finishTouch(cancelled: Boolean) {
        pointerDown = false
        pendingRightEdgeBoostJob?.cancel()
        pendingRightEdgeBoostJob = null

        if (boostActive) {
            stopBoostPlayback()
        }
        finishActiveGesture(commitSeek = !cancelled)
        swipeGestureStartAllowed = true

        if (cancelled) {
            tapSuppressed = false
        }
    }

    private fun finishActiveGesture(commitSeek: Boolean) {
        when (gestureMode) {
            TouchGestureMode.Seek -> finishSeekGesture(commitSeek = commitSeek)
            TouchGestureMode.Brightness,
            TouchGestureMode.Volume,
            -> host.scheduleHideTouchHint()

            TouchGestureMode.Blocked,
            TouchGestureMode.None,
            -> Unit
        }
        gestureMode = TouchGestureMode.None
    }

    private fun startSeekGesture(): Boolean {
        val seekDelegate = host.seekDelegate ?: return false
        val snapshot = seekDelegate.beginTouchSeek() ?: return false
        touchSeekDurationMs = snapshot.durationMs
        touchSeekBufferedPosMs = snapshot.bufferedPositionMs
        touchSeekStartPosMs = snapshot.startPositionMs
        touchSeekPreviewPosMs = touchSeekStartPosMs
        gestureMode = TouchGestureMode.Seek
        host.noteUserInteractionFromTouch()
        seekDelegate.showTouchSeekPreview(
            posMs = touchSeekStartPosMs,
            durationMs = touchSeekDurationMs,
            bufferedPositionMs = touchSeekBufferedPosMs,
        )
        return true
    }

    private fun updateSeekGesture(x: Float) {
        val seekDelegate = host.seekDelegate ?: return
        val width = gestureLayerWidth().coerceAtLeast(1f)
        val preview =
            (touchSeekStartPosMs + computeSeekDeltaMs(dx = x - downX, width = width, durationMs = touchSeekDurationMs))
                .coerceIn(0L, touchSeekDurationMs)
        if (preview == touchSeekPreviewPosMs) return
        touchSeekPreviewPosMs = preview
        seekDelegate.setTouchSeekPreviewPosition(preview)
        seekDelegate.showTouchSeekPreview(
            posMs = preview,
            durationMs = touchSeekDurationMs,
            bufferedPositionMs = touchSeekBufferedPosMs,
        )
    }

    private fun finishSeekGesture(commitSeek: Boolean) {
        val seekDelegate = host.seekDelegate ?: return
        val target = touchSeekPreviewPosMs ?: touchSeekStartPosMs
        touchSeekPreviewPosMs = null
        seekDelegate.finishTouchSeek(
            targetMs = target,
            durationMs = touchSeekDurationMs,
            commit = commitSeek,
        )
    }

    private fun startBrightnessGesture() {
        gestureMode = TouchGestureMode.Brightness
        brightnessStart = readCurrentBrightness()
        host.noteUserInteractionFromTouch()
    }

    private fun updateBrightnessGesture(y: Float) {
        val height = overlayBinding.touchGestureLayer.height.coerceAtLeast(1)
        val delta = ((downY - y) / height.toFloat()).coerceIn(-1f, 1f)
        val brightness =
            (brightnessStart + delta).coerceIn(
                PlayerActivity.TOUCH_GESTURE_MIN_BRIGHTNESS,
                1.0f,
            )
        val attrs = activity.window.attributes
        attrs.screenBrightness = brightness
        activity.window.attributes = attrs
        val percent = (brightness * 100f).roundToInt().coerceIn(0, 100)
        host.showTouchHint(activity.getString(R.string.player_touch_brightness_fmt, percent), hold = true)
    }

    private fun startVolumeGesture() {
        gestureMode = TouchGestureMode.Volume
        volumeStart = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        host.noteUserInteractionFromTouch()
    }

    private fun updateVolumeGesture(y: Float) {
        val manager = audioManager ?: return
        val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val height = overlayBinding.touchGestureLayer.height.coerceAtLeast(1)
        val deltaSteps = ((downY - y) / height.toFloat() * maxVolume.toFloat()).roundToInt()
        val volume = (volumeStart + deltaSteps).coerceIn(0, maxVolume)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        val percent = (volume * 100f / maxVolume.toFloat()).roundToInt().coerceIn(0, 100)
        host.showTouchHint(activity.getString(R.string.player_touch_volume_fmt, percent), hold = true)
    }

    private fun scheduleRightEdgeBoostIfNeeded() {
        if (host.boostDelegate == null) return
        val width = gestureLayerWidth()
        if (width <= 0f) return
        val threshold = PlayerActivity.TOUCH_GESTURE_EDGE_LONG_PRESS_THRESHOLD
        if (downX < width * (1f - threshold)) return
        pendingRightEdgeBoostJob?.cancel()
        pendingRightEdgeBoostJob =
            host.lifecycleScope.launch {
                delay(longPressTimeoutMs)
                if (!pointerDown || tapSuppressed || gestureMode != TouchGestureMode.None || boostActive) return@launch
                if (hasExceededTouchSlop(lastX, lastY)) return@launch
                startBoostPlayback()
            }
    }

    private fun startBoostPlayback() {
        val boostDelegate = host.boostDelegate ?: return
        val engine = host.player ?: return
        val speed = boostDelegate.touchBoostSpeed()
        boostPrevSpeed = engine.playbackSpeed
        boostPrevPlayWhenReady = engine.playWhenReady
        boostActive = true
        tapSuppressed = true
        engine.setPlaybackSpeed(speed)
        engine.playWhenReady = true
        host.noteUserInteractionFromTouch()
        host.showTouchHint(
            activity.getString(R.string.player_touch_boost_fmt, boostDelegate.touchBoostSpeedText(speed)),
            hold = true,
        )
        overlayBinding.touchGestureLayer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun stopBoostPlayback() {
        if (!boostActive) return
        boostActive = false
        host.player?.let { engine ->
            engine.setPlaybackSpeed(boostPrevSpeed)
            engine.playWhenReady = boostPrevPlayWhenReady
        }
        host.scheduleHideTouchHint()
    }

    private fun lockTouch() {
        if (touchLocked) {
            showLockedButtonTemporarily()
            return
        }
        touchLocked = true
        pendingRightEdgeBoostJob?.cancel()
        finishActiveGesture(commitSeek = false)
        stopBoostPlayback()
        host.setControlsVisibleFromTouch(false)
        host.showTouchHint(activity.getString(R.string.player_touch_locked), hold = false)
        showLockedButtonTemporarily()
    }

    private fun unlockTouch() {
        if (!touchLocked) return
        touchLocked = false
        lockedButtonVisible = false
        lockUiHideJob?.cancel()
        updateLockUi()
        host.showTouchHint(activity.getString(R.string.player_touch_unlocked), hold = false)
        host.setControlsVisibleFromTouch(true)
        host.noteUserInteractionFromTouch()
    }

    private fun toggleLockedButtonVisibility() {
        if (!touchLocked) return
        if (lockedButtonVisible) {
            lockedButtonVisible = false
            lockUiHideJob?.cancel()
            updateLockUi()
        } else {
            showLockedButtonTemporarily()
        }
    }

    private fun showLockedButtonTemporarily() {
        if (!touchLocked) return
        lockedButtonVisible = true
        updateLockUi()
        lockUiHideJob?.cancel()
        lockUiHideJob =
            host.lifecycleScope.launch {
                delay(PlayerActivity.TOUCH_LOCK_UI_HIDE_DELAY_MS)
                lockedButtonVisible = false
                updateLockUi()
            }
    }

    private fun updateLockUi() {
        val visible =
            if (touchLocked) {
                lockedButtonVisible
            } else {
                host.controlsVisibleForTouch &&
                    !host.isSidePanelVisible &&
                    !host.isBottomCardPanelVisible &&
                    !host.isCommentImageViewerVisible
            }
        overlayBinding.btnTouchLock.visibility = if (visible) View.VISIBLE else View.GONE
        overlayBinding.btnTouchLock.setImageResource(
            if (touchLocked) R.drawable.ic_player_lock else R.drawable.ic_player_unlock,
        )
        overlayBinding.btnTouchLock.contentDescription =
            activity.getString(
                if (touchLocked) R.string.player_touch_unlock else R.string.player_touch_lock,
            )
    }

    private fun gestureLayerWidth(): Float {
        return overlayBinding.touchGestureLayer.width.toFloat().takeIf { it > 0f }
            ?: binding.playerView.width.toFloat()
    }

    private fun gestureLayerHeight(): Float {
        return overlayBinding.touchGestureLayer.height.toFloat().takeIf { it > 0f }
            ?: binding.playerView.height.toFloat()
    }

    private fun hasExceededTouchSlop(x: Float, y: Float): Boolean {
        return abs(x - downX) > touchSlopPx || abs(y - downY) > touchSlopPx
    }

    private fun shouldBlockGestureRecognition(absDx: Float, absDy: Float): Boolean {
        return absDx > touchSlopPx * PlayerActivity.TOUCH_GESTURE_BLOCK_THRESHOLD_MULTIPLIER ||
            absDy > touchSlopPx * PlayerActivity.TOUCH_GESTURE_BLOCK_THRESHOLD_MULTIPLIER
    }

    private fun computeSeekDeltaMs(dx: Float, width: Float, durationMs: Long): Long {
        val fullWidthMs =
            (durationMs.toDouble() * PlayerActivity.TOUCH_GESTURE_SEEK_RATIO.toDouble())
                .roundToInt()
                .toLong()
                .coerceIn(
                    PlayerActivity.TOUCH_GESTURE_SEEK_MIN_FULL_WIDTH_MS,
                    PlayerActivity.TOUCH_GESTURE_SEEK_MAX_FULL_WIDTH_MS,
                )
        return (fullWidthMs.toDouble() * (dx.toDouble() / width.toDouble())).roundToInt().toLong()
    }

    private fun readCurrentBrightness(): Float {
        val fromWindow = activity.window.attributes.screenBrightness
        if (fromWindow >= 0f) return fromWindow.coerceIn(PlayerActivity.TOUCH_GESTURE_MIN_BRIGHTNESS, 1.0f)
        val fromSystem =
            runCatching {
                Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrNull()
        if (fromSystem != null) {
            return (fromSystem / 255f).coerceIn(PlayerActivity.TOUCH_GESTURE_MIN_BRIGHTNESS, 1.0f)
        }
        return 0.5f
    }

    private companion object {
        private const val touchTapSeekActiveMs = 1_200L
    }

    private fun edgeDirection(x: Float, width: Float): Int {
        return when {
            x < width * PlayerActivity.EDGE_TAP_THRESHOLD -> -1
            x > width * (1f - PlayerActivity.EDGE_TAP_THRESHOLD) -> +1
            else -> 0
        }
    }
}
