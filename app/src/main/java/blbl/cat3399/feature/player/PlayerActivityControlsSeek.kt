package blbl.cat3399.feature.player

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun PlayerActivity.seekRelative(deltaMs: Long) {
    seekRelative(deltaMs, danmakuImmediate = true)
}

internal fun PlayerActivity.seekRelative(deltaMs: Long, danmakuImmediate: Boolean) {
    val exo = player ?: return
    val duration = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
    val next = (exo.currentPosition + deltaMs).coerceIn(0L, duration)
    exo.seekTo(next)
    requestDanmakuSegmentsForPosition(next, immediate = danmakuImmediate)
}

internal fun PlayerActivity.startProgressLoop() {
    progressJob?.cancel()
    progressJob = lifecycleScope.launch {
        while (isActive) {
            updateProgressUi()
            delay(250)
        }
    }
}

internal fun PlayerActivity.toggleControls() {
    val willShow = osdMode == PlayerActivity.OsdMode.Hidden
    if (!willShow) {
        binding.settingsPanel.visibility = View.GONE
        binding.commentsPanel.visibility = View.GONE
    }
    setControlsVisible(willShow)
}

internal fun PlayerActivity.setControlsVisible(visible: Boolean) {
    val show = visible || isSidePanelVisible()
    seekOsdHideJob?.cancel()
    seekOsdHideJob = null
    seekOsdToken = 0L
    transientSeekOsdVisible = false
    osdMode = if (show) PlayerActivity.OsdMode.Full else PlayerActivity.OsdMode.Hidden

    binding.controlsRow.visibility = if (show) View.VISIBLE else View.GONE
    binding.tvTime.visibility = if (show) View.VISIBLE else View.GONE
    binding.topBar.visibility = if (show) View.VISIBLE else View.GONE
    binding.bottomBar.visibility = if (show) View.VISIBLE else View.GONE
    if (show) {
        applyBottomBarFullLayout()
    } else {
        binding.videoShotPreview.visibility = View.GONE
        videoShotFetchJob?.cancel()
    }
    updatePersistentBottomProgressBarVisibility()
    if (visible) noteUserInteraction() else autoHideJob?.cancel()
}

internal fun PlayerActivity.showSeekOsd() {
    if (isSidePanelVisible()) return
    if (osdMode == PlayerActivity.OsdMode.Full) {
        // Full OSD already has the progress bar; keep it alive.
        noteUserInteraction()
        return
    }

    val exo = player
    if (exo != null) {
        val duration = exo.duration.takeIf { it > 0 } ?: currentViewDurationMs ?: 0L
        // During hold-scrub we may have a preview position that should always win.
        // Do NOT gate on `scrubbing` here: other scrub-related flows may temporarily toggle it,
        // which would cause the OSD progress bar to jump between preview and actual position.
        val pos = (holdScrubPreviewPosMs ?: exo.currentPosition).coerceAtLeast(0L)
        val bufPos = exo.bufferedPosition.coerceAtLeast(0L)
        showSeekOsd(posMs = pos, durationMs = duration, bufferedPosMs = bufPos)
        return
    }

    transientSeekOsdVisible = true
    updatePersistentBottomProgressBarVisibility()
    scheduleHideSeekOsd()
}

internal fun PlayerActivity.showSeekOsd(posMs: Long, durationMs: Long, bufferedPosMs: Long) {
    if (isSidePanelVisible()) return
    val duration = durationMs.coerceAtLeast(0L)
    val pos = posMs.coerceAtLeast(0L)
    val bufPos = bufferedPosMs.coerceAtLeast(0L)

    // === 新增：判断是否有缩略图数据 ===
    val hasVideoShot = currentVideoShot != null

    if (osdMode == PlayerActivity.OsdMode.Full) {
        // Full OSD: update the real SeekBar + time (useful for hold-scrub preview).
        binding.tvTime.text = "${formatHms(pos)} / ${formatHms(duration)}"
        val enabled = duration > 0L
        binding.seekProgress.isEnabled = enabled
        if (enabled) {
            val bufferedProgress =
                ((bufPos.toDouble() / duration.toDouble()) * PlayerActivity.SEEK_MAX)
                    .toInt()
                    .coerceIn(0, PlayerActivity.SEEK_MAX)
            val pNow = ((pos.toDouble() / duration.toDouble()) * PlayerActivity.SEEK_MAX).toInt().coerceIn(0, PlayerActivity.SEEK_MAX)
            binding.seekProgress.secondaryProgress = bufferedProgress
            binding.seekProgress.progress = pNow

            // === 新增：在 Full 模式下更新并显示缩略图，跟随主进度条 ===
            if (hasVideoShot) {
                binding.videoShotPreview.visibility = View.VISIBLE
                updateVideoShotPreview(pNow, PlayerActivity.SEEK_MAX, pos, duration, binding.seekProgress)
            }
        }
        noteUserInteraction()
        return
    }

    binding.tvSeekOsdTime.text = "${formatHms(pos)} / ${formatHms(duration)}"

    val enabled = duration > 0L
    binding.progressSeekOsd.isEnabled = enabled
    if (enabled) {
        val bufferedProgress =
            ((bufPos.toDouble() / duration.toDouble()) * PlayerActivity.SEEK_MAX)
                .toInt()
                .coerceIn(0, PlayerActivity.SEEK_MAX)
        val pNow = ((pos.toDouble() / duration.toDouble()) * PlayerActivity.SEEK_MAX).toInt().coerceIn(0, PlayerActivity.SEEK_MAX)
        binding.progressSeekOsd.secondaryProgress = bufferedProgress
        binding.progressSeekOsd.progress = pNow

        // === 新增：在瞬时 OSD 模式下更新并显示缩略图，跟随瞬时进度条 ===
        if (hasVideoShot) {
            binding.videoShotPreview.visibility = View.VISIBLE
            updateVideoShotPreview(pNow, PlayerActivity.SEEK_MAX, pos, duration, binding.progressSeekOsd)
        }
    } else {
        binding.progressSeekOsd.secondaryProgress = 0
        binding.progressSeekOsd.progress = 0
        binding.videoShotPreview.visibility = View.GONE
    }

    transientSeekOsdVisible = true
    updatePersistentBottomProgressBarVisibility()
    scheduleHideSeekOsd()
}

internal fun PlayerActivity.ensureBottomBarConstraintSets() {
    if (bottomBarFullConstraints != null) return
    bottomBarFullConstraints =
        ConstraintSet().also { set ->
            set.clone(binding.bottomBar)
            set.setVisibility(R.id.controls_row, View.VISIBLE)
            set.setVisibility(R.id.tv_time, View.VISIBLE)
        }
}

internal fun PlayerActivity.applyBottomBarFullLayout() {
    ensureBottomBarConstraintSets()
    bottomBarFullConstraints?.applyTo(binding.bottomBar)
}

internal fun PlayerActivity.scheduleHideSeekOsd() {
    seekOsdHideJob?.cancel()
    val token = SystemClock.uptimeMillis()
    seekOsdToken = token
    seekOsdHideJob =
        lifecycleScope.launch {
            delay(PlayerActivity.SEEK_OSD_HIDE_DELAY_MS)
            if (seekOsdToken != token) return@launch
            transientSeekOsdVisible = false

            binding.videoShotPreview.visibility = View.GONE
            videoShotFetchJob?.cancel() // 及时取消异步请求

            updatePersistentBottomProgressBarVisibility()
        }
}

internal fun PlayerActivity.updatePersistentBottomProgressBarVisibility() {
    val enabled = BiliClient.prefs.playerPersistentBottomProgressEnabled
    val showControls = osdMode != PlayerActivity.OsdMode.Hidden || isSidePanelVisible()
    val persistentV = if (enabled && !showControls && !transientSeekOsdVisible) View.VISIBLE else View.GONE
    if (binding.progressPersistentBottom.visibility != persistentV) binding.progressPersistentBottom.visibility = persistentV

    val seekOsdV = if (!showControls && transientSeekOsdVisible) View.VISIBLE else View.GONE
    if (binding.seekOsdContainer.visibility != seekOsdV) binding.seekOsdContainer.visibility = seekOsdV
}

internal fun PlayerActivity.restartAutoHideTimer() {
    autoHideJob?.cancel()
    val exo = player ?: return
    if (osdMode != PlayerActivity.OsdMode.Full) return
    if (isSidePanelVisible()) return
    if (scrubbing) return
    if (!exo.isPlaying) return
    val token = lastInteractionAtMs
    autoHideJob = lifecycleScope.launch {
        delay(PlayerActivity.AUTO_HIDE_MS)
        if (token != lastInteractionAtMs) return@launch
        setControlsVisible(false)
    }
}

internal fun PlayerActivity.noteUserInteraction() {
    lastInteractionAtMs = SystemClock.uptimeMillis()
    restartAutoHideTimer()
}

internal fun PlayerActivity.scheduleKeyScrubEnd() {
    keyScrubEndJob?.cancel()
    keyScrubEndJob =
        lifecycleScope.launch {
            delay(PlayerActivity.KEY_SCRUB_END_DELAY_MS)
            val pendingSeekTo = keyScrubPendingSeekToMs
            keyScrubPendingSeekToMs = null
            scrubbing = false
            val engine = player
            if (pendingSeekTo != null && engine?.kind == PlayerEngineKind.IjkPlayer) {
                engine.seekTo(pendingSeekTo)
                requestDanmakuSegmentsForPosition(pendingSeekTo, immediate = true)
                requestReportProgressOnce(reason = "user_seek_end")
            }
            restartAutoHideTimer()
        }
}

internal fun PlayerActivity.hasControlsFocus(): Boolean =
    binding.topBar.hasFocus() || binding.bottomBar.hasFocus() || binding.settingsPanel.hasFocus() || binding.commentsPanel.hasFocus()

internal fun PlayerActivity.focusFirstControl() {
    binding.btnPlayPause.post { binding.btnPlayPause.requestFocus() }
}

private fun PlayerActivity.requestFocusControlNow(view: View?): Boolean {
    val target = view ?: return false
    if (target.visibility != View.VISIBLE) return false
    if (!target.isEnabled) return false
    if (!target.isFocusable) return false
    return target.requestFocus()
}

internal fun PlayerActivity.focusDownKeyOsdTargetControl() {
    val target =
        when (BiliClient.prefs.playerDownKeyOsdFocusTarget) {
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_PREV -> binding.btnPrev
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_NEXT -> binding.btnNext
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_SUBTITLE -> binding.btnSubtitle
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_DANMAKU -> binding.btnDanmaku
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_COMMENTS -> binding.btnComments
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_DETAIL -> binding.btnDetail
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_UP -> binding.btnUp
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_LIKE -> binding.btnLike
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_COIN -> binding.btnCoin
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_FAV -> binding.btnFav
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_LIST_PANEL -> binding.btnListPanel
            AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_ADVANCED -> binding.btnAdvanced
            else -> binding.btnPlayPause
        }

    binding.controlsRow.post {
        if (requestFocusControlNow(target)) return@post
        if (requestFocusControlNow(binding.btnPlayPause)) return@post
        focusFirstControl()
    }
}

internal fun PlayerActivity.focusSeekBar() {
    binding.seekProgress.post { binding.seekProgress.requestFocus() }
}

internal fun PlayerActivity.focusAdvancedControl() {
    binding.btnAdvanced.post { binding.btnAdvanced.requestFocus() }
}

internal fun PlayerActivity.focusSettingsPanel() {
    binding.recyclerSettings.post {
        val child = binding.recyclerSettings.getChildAt(0)
        if (child != null) {
            child.requestFocus()
            return@post
        }

        binding.recyclerSettings.scrollToPosition(0)
        binding.recyclerSettings.post {
            val first = binding.recyclerSettings.getChildAt(0)
            (first ?: binding.recyclerSettings).requestFocus()
        }
    }
}

internal fun PlayerActivity.smartSeek(direction: Int) {
    smartSeek(direction, showControls = true, hintKind = SeekHintKind.Step)
}

internal enum class SeekHintKind {
    Step,
    Hold,
}

internal fun PlayerActivity.isSeekKey(keyCode: Int): Boolean {
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        -> true

        else -> false
    }
}

internal fun PlayerActivity.clearKeySeekPending() {
    keySeekHoldDetectJob?.cancel()
    keySeekHoldDetectJob = null
    keySeekPendingKeyCode = 0
    keySeekPendingDirection = 0
}

internal fun PlayerActivity.beginKeySeekPending(keyCode: Int, direction: Int, showControls: Boolean) {
    clearKeySeekPending()
    keySeekPendingKeyCode = keyCode
    keySeekPendingDirection = direction

    // Delay to decide tap vs hold:
    // - If repeats arrive, dispatchKeyEvent() will start hold immediately and clear this pending state.
    // - If no repeats (some remotes/media keys), start hold after the long-press timeout.
    val timeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    keySeekHoldDetectJob =
        lifecycleScope.launch {
            delay(timeoutMs)
            if (keySeekPendingKeyCode != keyCode || keySeekPendingDirection != direction) return@launch
            if (holdSeekJob != null) return@launch
            showSeekOsd()
            if (direction < 0) {
                // Long-press LEFT: always use preview-scrub rewind (independent of hold-seek mode setting).
                startHoldScrub(direction = direction, showControls = showControls)
            } else {
                startHoldSeek(direction = direction, showControls = showControls)
            }
            // Once we enter hold, a later ACTION_UP should only stop the hold (no step seek).
            clearKeySeekPending()
        }
}

internal fun PlayerActivity.smartSeek(direction: Int, showControls: Boolean, hintKind: SeekHintKind) {
    val now = SystemClock.uptimeMillis()
    val sameDir = direction == smartSeekDirection
    val within = now - smartSeekLastAtMs <= PlayerActivity.SMART_SEEK_WINDOW_MS
    val continued = sameDir && within
    smartSeekStreak = if (continued) (smartSeekStreak + 1) else 1
    smartSeekDirection = direction
    smartSeekLastAtMs = now

    if (showControls) {
        if (osdMode != PlayerActivity.OsdMode.Full && !isSidePanelVisible()) setControlsVisible(true) else noteUserInteraction()
    } else {
        noteUserInteraction()
    }

    val step = smartSeekStepMs(smartSeekStreak)
    seekRelative(step * direction)
    smartSeekTotalMs = if (continued) (smartSeekTotalMs + step) else step
    if (hintKind == SeekHintKind.Step) showSeekStepHint(direction, smartSeekTotalMs)
}

internal fun PlayerActivity.smartSeekStepMs(streak: Int): Long {
    val baseStepsSec = intArrayOf(2, 3, 5, 7, 10, 15, 25, 35, 45)
    val idx = (streak - 1).coerceAtLeast(0)
    val sec =
        if (idx < baseStepsSec.size) {
            baseStepsSec[idx]
        } else {
            val extra = idx - (baseStepsSec.size - 1)
            (baseStepsSec.last() + (10 * extra)).coerceAtMost(300)
        }
    return sec * 1000L
}

internal fun PlayerActivity.startHoldSeek(direction: Int, showControls: Boolean) {
    // Speed-hold seek is forward-only; long-press LEFT is handled by preview-scrub (startHoldScrub).
    if (direction <= 0) return
    if (holdSeekJob?.isActive == true) return
    val engine = player ?: return

    if (showControls) {
        if (osdMode != PlayerActivity.OsdMode.Full && !isSidePanelVisible()) setControlsVisible(true) else noteUserInteraction()
    } else {
        noteUserInteraction()
    }

    val holdSpeed = holdSeekSpeed()
    val holdMode = BiliClient.prefs.playerHoldSeekMode
    holdPrevSpeed = engine.playbackSpeed
    holdPrevPlayWhenReady = engine.playWhenReady
    holdScrubPreviewPosMs = null
    if (holdMode == AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB || holdMode == AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME) {
        val fixedStepMs =
            if (holdMode == AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME) {
                PlayerActivity.HOLD_SCRUB_FIXED_TIME_STEP_MS
            } else {
                null
            }
        startHoldScrubSeek(engine = engine, direction = direction, speed = holdSpeed, fixedStepMs = fixedStepMs)
        return
    }
    showSeekHoldHint(direction, holdSpeed)
    engine.setPlaybackSpeed(holdSpeed)
    engine.playWhenReady = true
    holdSeekJob = lifecycleScope.launch { kotlinx.coroutines.awaitCancellation() }
}

internal fun PlayerActivity.startHoldScrub(direction: Int, showControls: Boolean) {
    if (holdSeekJob?.isActive == true) return
    val engine = player ?: return

    if (showControls) {
        if (osdMode != PlayerActivity.OsdMode.Full && !isSidePanelVisible()) setControlsVisible(true) else noteUserInteraction()
    } else {
        noteUserInteraction()
    }

    val holdSpeed = holdSeekSpeed()
    val holdMode = BiliClient.prefs.playerHoldSeekMode
    holdPrevSpeed = engine.playbackSpeed
    holdPrevPlayWhenReady = engine.playWhenReady
    holdScrubPreviewPosMs = null
    val fixedStepMs =
        if (holdMode == AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME) {
            PlayerActivity.HOLD_SCRUB_FIXED_TIME_STEP_MS
        } else {
            null
        }
    startHoldScrubSeek(engine = engine, direction = direction, speed = holdSpeed, fixedStepMs = fixedStepMs)
}

internal fun PlayerActivity.startHoldScrubSeek(engine: BlblPlayerEngine, direction: Int, speed: Float, fixedStepMs: Long?) {
    val duration = engine.duration.takeIf { it > 0 } ?: currentViewDurationMs ?: 0L
    if (duration <= 0L) {
        // Unknown duration: cannot show an actual progress bar scrub; fall back to speed-hold.
        if (direction <= 0) return
        showSeekHoldHint(direction, speed)
        engine.setPlaybackSpeed(speed)
        engine.playWhenReady = true
        holdSeekJob = lifecycleScope.launch { kotlinx.coroutines.awaitCancellation() }
        return
    }

    scrubbing = true
    keyScrubEndJob?.cancel()
    keyScrubEndJob = null

    engine.pause()

    val initial = engine.currentPosition.coerceIn(0L, duration)
    holdScrubPreviewPosMs = initial
    showSeekOsd(posMs = initial, durationMs = duration, bufferedPosMs = engine.bufferedPosition)

    val tickMs = PlayerActivity.HOLD_SCRUB_TICK_MS
    val stepMs =
        fixedStepMs?.coerceAtLeast(1L)
            ?: holdScrubStepMs(durationMs = duration, tickMs = tickMs).coerceAtLeast(1L)
    val deltaMs = stepMs * direction.toLong()
    holdSeekJob =
        lifecycleScope.launch {
            while (isActive) {
                val current = holdScrubPreviewPosMs ?: initial
                val next = (current + deltaMs).coerceIn(0L, duration)
                holdScrubPreviewPosMs = next
                showSeekOsd(posMs = next, durationMs = duration, bufferedPosMs = engine.bufferedPosition)
                delay(tickMs)
            }
        }
}

internal fun PlayerActivity.stopHoldSeek() {
    val engine = player
    val scrubTarget = holdScrubPreviewPosMs
    holdScrubPreviewPosMs = null
    if (scrubTarget != null) {
        scrubbing = false
        keyScrubEndJob?.cancel()
        keyScrubEndJob = null
    }
    holdSeekJob?.cancel()
    holdSeekJob = null
    if (engine != null) {
        engine.setPlaybackSpeed(holdPrevSpeed)
        if (scrubTarget != null) {
            engine.seekTo(scrubTarget)
            val duration = engine.duration.takeIf { it > 0 } ?: currentViewDurationMs ?: 0L
            if (duration > 0L) showSeekOsd(posMs = scrubTarget, durationMs = duration, bufferedPosMs = engine.bufferedPosition)
        }
        engine.playWhenReady = holdPrevPlayWhenReady
        val pos = (scrubTarget ?: engine.currentPosition).coerceAtLeast(0L)
        requestDanmakuSegmentsForPosition(pos, immediate = true)
    }
    scheduleHideSeekHint()
}

internal fun PlayerActivity.showSeekStepHint(direction: Int, totalMs: Long) {
    val sec = (kotlin.math.abs(totalMs) / 1000L).coerceAtLeast(1L)
    val text = if (direction > 0) "快进 ${sec}s" else "后退 ${sec}s"
    showSeekHint(text, hold = false)
}

internal fun PlayerActivity.showSeekHoldHint(direction: Int, speed: Float) {
    val s = holdSeekSpeedText(speed)
    val text = if (direction > 0) "快进 x$s" else "后退 x$s"
    showSeekHint(text, hold = true)
}

internal fun PlayerActivity.holdSeekSpeed(): Float {
    val v = BiliClient.prefs.playerHoldSeekSpeed
    val fallback = AppPrefs.PLAYER_HOLD_SEEK_SPEED_DEFAULT
    if (!v.isFinite()) return fallback
    return v.coerceIn(1.5f, 4.0f)
}

internal fun PlayerActivity.holdSeekSpeedText(speed: Float): String {
    val v = speed.takeIf { it.isFinite() } ?: AppPrefs.PLAYER_HOLD_SEEK_SPEED_DEFAULT
    val fixed = String.format(Locale.US, "%.2f", v)
    return fixed.trimEnd('0').trimEnd('.')
}

internal fun PlayerActivity.holdScrubStepMs(durationMs: Long, tickMs: Long): Long {
    val duration = durationMs.coerceAtLeast(0L)
    if (duration <= 0L) return 0L
    val tick = tickMs.coerceAtLeast(1L)
    val step =
        if (duration < PlayerActivity.HOLD_SCRUB_SHORT_VIDEO_THRESHOLD_MS) {
            // Short videos: fixed speed (independent of hold seek speed).
            (PlayerActivity.HOLD_SCRUB_SHORT_SPEED_MS_PER_S.toDouble() * tick.toDouble() / 1000.0).roundToInt().toLong()
        } else {
            // Long videos: traverse from 0% -> 100% in about PlayerActivity.HOLD_SCRUB_TRAVERSE_MS.
            (duration.toDouble() * tick.toDouble() / PlayerActivity.HOLD_SCRUB_TRAVERSE_MS.toDouble()).roundToInt().toLong()
        }
    return step.coerceAtLeast(1L)
}

internal fun PlayerActivity.showSeekHint(text: String, hold: Boolean) {
    binding.tvSeekHint.text = text
    binding.tvSeekHint.visibility = View.VISIBLE
    seekHintJob?.cancel()
    if (!hold) scheduleHideSeekHint()
}

internal fun PlayerActivity.scheduleHideSeekHint() {
    seekHintJob?.cancel()
    seekHintJob =
        lifecycleScope.launch {
            delay(PlayerActivity.SEEK_HINT_HIDE_DELAY_MS)
            binding.tvSeekHint.visibility = View.GONE
        }
}

internal fun PlayerActivity.edgeDirection(x: Float, width: Float): Int {
    return when {
        x < width * PlayerActivity.EDGE_TAP_THRESHOLD -> -1
        x > width * (1f - PlayerActivity.EDGE_TAP_THRESHOLD) -> +1
        else -> 0
    }
}

internal fun PlayerActivity.isInteractionKey(keyCode: Int): Boolean {
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_INFO,
        KeyEvent.KEYCODE_GUIDE,
        -> true

        else -> false
    }
}

internal fun PlayerActivity.updatePlayPauseIcon(isPlaying: Boolean) {
    binding.btnPlayPause.setImageResource(
        if (isPlaying) blbl.cat3399.R.drawable.ic_player_pause else blbl.cat3399.R.drawable.ic_player_play,
    )
}

internal fun PlayerActivity.updateVideoShotPreview(
    progress: Int,
    max: Int,
    positionMs: Long,
    durationMs: Long,
    trackView: View // 传入当前正在使用的进度条控件
) {
    val previewView = binding.videoShotPreview ?: return
    val shot = currentVideoShot ?: return
    val cache = videoShotImageCache ?: return

    videoShotFetchJob?.cancel()
    videoShotFetchJob = lifecycleScope.launch {
        delay(16) // 16ms 防抖
        val frame = shot.getSpriteFrame((positionMs / 1000).toInt(), cache)

        previewView.spriteFrame = frame

        // === 处理 X 轴随 trackView 平移 ===
        val containerWidth = trackView.width
        val imageWidth = previewView.width

        if (containerWidth > 0 && imageWidth > 0 && durationMs > 0) {
            val progressRatio = progress.toDouble() / max.toDouble()

            // 基于指定的 trackView 的内部宽度计算相对坐标
            var rawOffset = (containerWidth * progressRatio) - (imageWidth / 2.0)

            // 如果 trackView（如 progressSeekOsd）有 marginStart，补偿它的 left 坐标差异
            rawOffset += trackView.left

            // 限制边界：不能超出屏幕左边，也不能超出屏幕右边
            val minOffset = 0.0
            val parentWidth = (previewView.parent as? View)?.width ?: containerWidth
            val maxOffset = (parentWidth - imageWidth).toDouble()

            previewView.translationX = rawOffset.coerceIn(minOffset, maxOffset).toFloat()
        }
    }
}
