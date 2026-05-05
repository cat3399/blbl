package blbl.cat3399.feature.player

import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.core.api.SponsorBlockApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.popup.AppPopup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

private const val SPONSOR_SUBMIT_TAG = "PlayerSponsorSubmit"
private const val SPONSOR_SUBMIT_MARKER_TOLERANCE_MIN_MS = 800L
private const val SPONSOR_SUBMIT_FAST_SEEK_STEPS = 4

private data class SponsorSubmitCategoryOption(
    val key: String,
    val label: String,
)

private val SPONSOR_SUBMIT_CATEGORY_OPTIONS =
    listOf(
        SponsorSubmitCategoryOption("sponsor", "广告/赞助"),
        SponsorSubmitCategoryOption("selfpromo", "自我推广"),
        SponsorSubmitCategoryOption("interaction", "三连提醒"),
        SponsorSubmitCategoryOption("intro", "片头"),
        SponsorSubmitCategoryOption("outro", "片尾"),
        SponsorSubmitCategoryOption("preview", "预览"),
        SponsorSubmitCategoryOption("filler", "离题/填充"),
    )

internal fun PlayerActivity.initSponsorSubmitPanel() {
    binding.sponsorSubmitScrim.setOnClickListener { hideSponsorSubmitPanel(restorePlayback = true) }
    binding.sponsorSubmitPanel.setOnClickListener { focusSponsorSubmitTimeline() }
    binding.btnSponsorSubmitClose.setOnClickListener { hideSponsorSubmitPanel(restorePlayback = true) }
    binding.btnSponsorSubmitDelete.setOnClickListener { enterSponsorSubmitDeleteMode() }
    binding.btnSponsorSubmitUpload.setOnClickListener { showSponsorSubmitCategoryPicker() }
    binding.sponsorSubmitThumbnails.setFrameLoader(
        scope = lifecycleScope,
        loader = { timeSec ->
            val shot = currentVideoShot ?: return@setFrameLoader null
            val cache = videoShotImageCache ?: return@setFrameLoader null
            shot.getSpriteFrame(timeSec, cache)
        },
    )
    binding.sponsorSubmitTimeline.setTouchCallbacks(
        onCursorChanged = { timeMs, _ -> moveSponsorSubmitCursorTo(timeMs, loadThumbnails = true) },
        onMarkerDragStarted = { marker -> selectSponsorSubmitMarker(marker, loadThumbnails = false) },
        onMarkerDragged = { marker, timeMs, finished -> dragSponsorSubmitMarker(marker, timeMs, finished) },
        onMarkerClicked = { marker -> clickSponsorSubmitMarker(marker) },
    )
    binding.sponsorSubmitThumbnails.setTouchCallbacks(
        onThumbnailScrubbed = { timeMs, finished ->
            if (
                moveSponsorSubmitCursorTo(
                    timeMs = timeMs,
                    loadThumbnails = finished,
                    requestTimelineFocus = false,
                ) &&
                finished
            ) {
                noteUserInteraction()
            }
        },
        onThumbnailClicked = { timeMs -> clickSponsorSubmitThumbnail(timeMs) },
    )
    updateSponsorSubmitPanelUi(loadThumbnails = false)
}

internal fun PlayerActivity.isSponsorSubmitPanelVisible(): Boolean =
    binding.sponsorSubmitPanel.visibility == View.VISIBLE

internal fun PlayerActivity.openSponsorSubmitPanel() {
    val engine = player
    if (engine == null) {
        AppToast.show(this, "播放器未就绪")
        return
    }
    val bvid = currentBvid.trim()
    val cid = currentCid
    val duration = resolveSponsorSubmitDurationMs()
    if (bvid.isBlank() || cid <= 0L || duration <= 0L) {
        AppToast.show(this, "当前视频暂不支持上传片段")
        return
    }

    hideBottomCardPanel(restoreFocus = false, dismissTarget = null)
    if (isSettingsPanelVisible()) hideSettingsPanel(dismissTarget = PlayerActivity.PanelDismissTarget.Fullscreen)
    if (isCommentsPanelVisible()) hideCommentsPanel(dismissTarget = PlayerActivity.PanelDismissTarget.Fullscreen)
    closeCommentImageViewer(restoreFocus = false)

    val wasPlaying = engine.isPlaying || engine.playWhenReady
    val position = engine.currentPosition.coerceIn(0L, duration)
    sponsorSubmitPanelState.reset(positionMs = position, wasPlaying = wasPlaying)

    engine.pause()
    setControlsVisible(false)
    autoHideJob?.cancel()
    binding.videoShotPreview.visibility = View.GONE
    binding.sponsorSubmitScrim.visibility = View.VISIBLE
    binding.sponsorSubmitPanel.visibility = View.VISIBLE
    binding.sponsorSubmitTimeline.post { focusSponsorSubmitTimeline() }
    updateSponsorSubmitPanelUi(loadThumbnails = true)
    AppLog.i(SPONSOR_SUBMIT_TAG, "open bvid=$bvid cid=$cid pos=${position}ms duration=${duration}ms")
}

internal fun PlayerActivity.hideSponsorSubmitPanel(restorePlayback: Boolean) {
    if (!isSponsorSubmitPanelVisible()) return
    val state = sponsorSubmitPanelState
    val shouldResume = restorePlayback && state.wasPlayingBeforeOpen

    sponsorSubmitUploadJob?.cancel()
    sponsorSubmitUploadJob = null
    state.submitting = false
    state.mode = SponsorSubmitInteractionMode.MARK
    state.selectedMarkerId = null
    state.movingMarkerId = null

    binding.sponsorSubmitScrim.visibility = View.GONE
    binding.sponsorSubmitPanel.visibility = View.GONE
    binding.sponsorSubmitThumbnails.clearThumbnails()
    setControlsVisible(true)
    if (shouldResume) {
        player?.play()
        restartAutoHideTimer()
    }
    binding.root.post {
        when {
            binding.btnSponsorSubmit.requestSponsorSubmitFocusIfUsable() -> Unit
            binding.btnPlayPause.requestSponsorSubmitFocusIfUsable() -> Unit
            else -> focusFirstControl()
        }
    }
}

internal fun PlayerActivity.dispatchSponsorSubmitPanelKey(event: KeyEvent): Boolean {
    if (!isSponsorSubmitPanelVisible()) return false
    val keyCode = event.keyCode
    if (!isSponsorSubmitHandledKey(keyCode)) return true
    if (event.action == KeyEvent.ACTION_UP) {
        if (
            isSponsorSubmitConfirmKey(keyCode) &&
            sponsorSubmitPanelState.mode == SponsorSubmitInteractionMode.MARK &&
            performSponsorSubmitFocusedButton()
        ) {
            return true
        }
        return true
    }
    if (event.action != KeyEvent.ACTION_DOWN) return true

    if (isInteractionKey(keyCode)) noteUserInteraction()

    val state = sponsorSubmitPanelState
    return when (keyCode) {
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_BUTTON_B,
        -> {
            finishOnSponsorSubmitBack()
            true
        }

        KeyEvent.KEYCODE_DPAD_UP -> {
            focusSponsorSubmitTimeline()
            true
        }

        KeyEvent.KEYCODE_DPAD_DOWN -> {
            if (state.mode == SponsorSubmitInteractionMode.MARK) focusSponsorSubmitDefaultButton() else focusSponsorSubmitTimeline()
            true
        }

        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        -> {
            val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
            if (binding.sponsorSubmitButtonRow.hasFocus() && state.mode == SponsorSubmitInteractionMode.MARK) {
                moveSponsorSubmitButtonFocus(direction)
            } else {
                moveSponsorSubmitCursor(direction = direction, fast = event.repeatCount > 0)
            }
            true
        }

        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        -> {
            if (state.mode == SponsorSubmitInteractionMode.MARK && hasSponsorSubmitFocusedButton()) {
                Unit
            } else {
                handleSponsorSubmitCenter()
            }
            true
        }

        else -> true
    }
}

private fun isSponsorSubmitConfirmKey(keyCode: Int): Boolean =
    when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        -> true
        else -> false
    }

private fun PlayerActivity.hasSponsorSubmitFocusedButton(): Boolean =
    when (currentFocus) {
        binding.btnSponsorSubmitUpload,
        binding.btnSponsorSubmitDelete,
        binding.btnSponsorSubmitClose,
        -> true
        else -> false
    }

private fun PlayerActivity.performSponsorSubmitFocusedButton(): Boolean {
    val focused = currentFocus
    return when (focused) {
        binding.btnSponsorSubmitUpload -> {
            if (!binding.btnSponsorSubmitUpload.isEnabled) return false
            showSponsorSubmitCategoryPicker()
            true
        }
        binding.btnSponsorSubmitDelete -> {
            if (!binding.btnSponsorSubmitDelete.isEnabled) return false
            enterSponsorSubmitDeleteMode()
            true
        }
        binding.btnSponsorSubmitClose -> {
            hideSponsorSubmitPanel(restorePlayback = true)
            true
        }
        else -> false
    }
}

private fun isSponsorSubmitHandledKey(keyCode: Int): Boolean =
    when (keyCode) {
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        -> true

        else -> false
    }

private fun PlayerActivity.finishOnSponsorSubmitBack() {
    val state = sponsorSubmitPanelState
    when (state.mode) {
        SponsorSubmitInteractionMode.MARK -> hideSponsorSubmitPanel(restorePlayback = true)
        SponsorSubmitInteractionMode.DELETE,
        SponsorSubmitInteractionMode.MOVE,
        -> {
            state.mode = SponsorSubmitInteractionMode.MARK
            state.selectedMarkerId = null
            state.movingMarkerId = null
            focusSponsorSubmitTimeline()
            updateSponsorSubmitPanelUi(loadThumbnails = false)
        }
    }
}

private fun PlayerActivity.handleSponsorSubmitCenter() {
    val state = sponsorSubmitPanelState
    when (state.mode) {
        SponsorSubmitInteractionMode.DELETE -> deleteSelectedSponsorSubmitMarker()
        SponsorSubmitInteractionMode.MOVE -> {
            state.mode = SponsorSubmitInteractionMode.MARK
            state.selectedMarkerId = null
            state.movingMarkerId = null
            updateSponsorSubmitPanelUi(loadThumbnails = false)
        }

        SponsorSubmitInteractionMode.MARK -> {
            val nearest = state.draft.nearestMarkerAt(state.cursorMs, sponsorSubmitMarkerToleranceMs())
            if (nearest != null) {
                state.mode = SponsorSubmitInteractionMode.MOVE
                state.selectedMarkerId = nearest.id
                state.movingMarkerId = nearest.id
                state.cursorMs = nearest.timeMs
                focusSponsorSubmitTimeline()
                updateSponsorSubmitPanelUi(loadThumbnails = true)
                return
            }

            placeSponsorSubmitMarkerAtCursor(loadThumbnails = false)
        }
    }
}

private fun PlayerActivity.placeSponsorSubmitMarkerAtCursor(loadThumbnails: Boolean) {
    val state = sponsorSubmitPanelState
    when (val result = state.draft.placeMarker(state.cursorMs, resolveSponsorSubmitDurationMs())) {
        is SponsorSubmitMarkResult.Placed -> {
            state.selectedMarkerId = result.marker.id
            updateSponsorSubmitPanelUi(loadThumbnails = loadThumbnails)
        }

        SponsorSubmitMarkResult.EndBeforeStart -> {
            AppToast.show(this, "结束点需要晚于开始点")
            updateSponsorSubmitPanelUi(loadThumbnails = false)
        }

        SponsorSubmitMarkResult.NoCapacity -> {
            AppToast.show(this, "最多支持${state.draft.maxSegments}段")
            updateSponsorSubmitPanelUi(loadThumbnails = false)
        }
    }
}

private fun PlayerActivity.moveSponsorSubmitCursorTo(
    timeMs: Long,
    loadThumbnails: Boolean,
    requestTimelineFocus: Boolean = true,
): Boolean {
    val state = sponsorSubmitPanelState
    if (state.submitting) return false
    val duration = resolveSponsorSubmitDurationMs()
    if (duration <= 0L) return false

    if (state.mode == SponsorSubmitInteractionMode.MOVE) {
        state.mode = SponsorSubmitInteractionMode.MARK
        state.selectedMarkerId = null
        state.movingMarkerId = null
    }
    state.cursorMs = timeMs.coerceIn(0L, duration)
    if (state.mode == SponsorSubmitInteractionMode.DELETE) {
        state.selectedMarkerId =
            state.draft.nearestMarkerAt(state.cursorMs, sponsorSubmitMarkerToleranceMs())?.id
                ?: state.selectedMarkerId
    }
    if (requestTimelineFocus) focusSponsorSubmitTimeline()
    updateSponsorSubmitPanelUi(loadThumbnails = loadThumbnails)
    return true
}

private fun PlayerActivity.clickSponsorSubmitThumbnail(timeMs: Long) {
    if (!moveSponsorSubmitCursorTo(timeMs, loadThumbnails = false)) return
    if (sponsorSubmitPanelState.mode == SponsorSubmitInteractionMode.MARK) {
        handleSponsorSubmitCenter()
    } else {
        updateSponsorSubmitPanelUi(loadThumbnails = true)
    }
}

private fun PlayerActivity.selectSponsorSubmitMarker(marker: SponsorSubmitMarker, loadThumbnails: Boolean): Boolean {
    val state = sponsorSubmitPanelState
    if (state.submitting) return false

    state.cursorMs = marker.timeMs
    state.selectedMarkerId = marker.id
    state.movingMarkerId = null
    focusSponsorSubmitTimeline()
    updateSponsorSubmitPanelUi(loadThumbnails = loadThumbnails)
    return true
}

private fun PlayerActivity.dragSponsorSubmitMarker(
    marker: SponsorSubmitMarker,
    timeMs: Long,
    finished: Boolean,
) {
    val state = sponsorSubmitPanelState
    if (state.submitting) return
    val duration = resolveSponsorSubmitDurationMs()
    if (duration <= 0L) return

    state.selectedMarkerId = marker.id
    if (state.mode == SponsorSubmitInteractionMode.DELETE) {
        state.cursorMs = state.draft.markerById(marker.id)?.timeMs ?: marker.timeMs
        updateSponsorSubmitPanelUi(loadThumbnails = finished)
        return
    }

    state.mode = SponsorSubmitInteractionMode.MOVE
    state.movingMarkerId = marker.id
    state.draft.moveMarker(marker.id, timeMs, duration)
    state.cursorMs = state.draft.markerById(marker.id)?.timeMs ?: timeMs.coerceIn(0L, duration)
    if (finished) {
        state.mode = SponsorSubmitInteractionMode.MARK
        state.movingMarkerId = null
    }
    updateSponsorSubmitPanelUi(loadThumbnails = finished)
}

private fun PlayerActivity.clickSponsorSubmitMarker(marker: SponsorSubmitMarker) {
    if (!selectSponsorSubmitMarker(marker, loadThumbnails = false)) return
    if (sponsorSubmitPanelState.mode == SponsorSubmitInteractionMode.DELETE) {
        deleteSelectedSponsorSubmitMarker()
    } else {
        sponsorSubmitPanelState.mode = SponsorSubmitInteractionMode.MARK
        updateSponsorSubmitPanelUi(loadThumbnails = true)
    }
}

private fun PlayerActivity.enterSponsorSubmitDeleteMode() {
    val state = sponsorSubmitPanelState
    val ordered = state.draft.orderedMarkers()
    if (ordered.isEmpty()) {
        updateSponsorSubmitPanelUi(loadThumbnails = false)
        return
    }
    val selected =
        state.draft.nearestMarkerAt(state.cursorMs, sponsorSubmitMarkerToleranceMs())
            ?: ordered.first()
    state.mode = SponsorSubmitInteractionMode.DELETE
    state.movingMarkerId = null
    state.selectedMarkerId = selected.id
    state.cursorMs = selected.timeMs
    focusSponsorSubmitTimeline()
    updateSponsorSubmitPanelUi(loadThumbnails = true)
}

private fun PlayerActivity.deleteSelectedSponsorSubmitMarker() {
    val state = sponsorSubmitPanelState
    val before = state.draft.orderedMarkers()
    if (before.isEmpty()) {
        state.mode = SponsorSubmitInteractionMode.MARK
        state.selectedMarkerId = null
        updateSponsorSubmitPanelUi(loadThumbnails = false)
        return
    }
    val selectedId = state.selectedMarkerId ?: before.first().id
    val selectedIndex = before.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
    if (!state.draft.deleteMarkerOrPair(selectedId)) return

    val after = state.draft.orderedMarkers()
    if (after.isEmpty()) {
        state.mode = SponsorSubmitInteractionMode.MARK
        state.selectedMarkerId = null
        state.movingMarkerId = null
        updateSponsorSubmitPanelUi(loadThumbnails = false)
        return
    }

    val next = after[selectedIndex.coerceAtMost(after.lastIndex)]
    state.selectedMarkerId = next.id
    state.cursorMs = next.timeMs
    updateSponsorSubmitPanelUi(loadThumbnails = true)
}

private fun PlayerActivity.moveSponsorSubmitCursor(direction: Int, fast: Boolean) {
    val state = sponsorSubmitPanelState
    val duration = resolveSponsorSubmitDurationMs()
    if (duration <= 0L) return

    when (state.mode) {
        SponsorSubmitInteractionMode.DELETE -> {
            moveSponsorSubmitMarkerSelection(direction)
            return
        }

        SponsorSubmitInteractionMode.MOVE -> {
            val markerId = state.movingMarkerId ?: return
            val target = sponsorSubmitSeekTarget(state.cursorMs, direction, fast, duration)
            state.draft.moveMarker(markerId, target, duration)
            val moved = state.draft.markerById(markerId)?.timeMs ?: target.coerceIn(0L, duration)
            state.cursorMs = moved
            updateSponsorSubmitPanelUi(loadThumbnails = true)
        }

        SponsorSubmitInteractionMode.MARK -> {
            val target = sponsorSubmitSeekTarget(state.cursorMs, direction, fast, duration)
            state.cursorMs = target
            updateSponsorSubmitPanelUi(loadThumbnails = true)
        }
    }
}

private fun PlayerActivity.moveSponsorSubmitMarkerSelection(direction: Int) {
    val state = sponsorSubmitPanelState
    val ordered = state.draft.orderedMarkers()
    if (ordered.isEmpty()) {
        state.mode = SponsorSubmitInteractionMode.MARK
        state.selectedMarkerId = null
        updateSponsorSubmitPanelUi(loadThumbnails = false)
        return
    }
    val currentIndex = ordered.indexOfFirst { it.id == state.selectedMarkerId }.takeIf { it >= 0 } ?: 0
    val next = ordered[(currentIndex + direction).coerceIn(0, ordered.lastIndex)]
    state.selectedMarkerId = next.id
    state.cursorMs = next.timeMs
    updateSponsorSubmitPanelUi(loadThumbnails = true)
}

private fun PlayerActivity.sponsorSubmitSeekTarget(
    currentMs: Long,
    direction: Int,
    fast: Boolean,
    durationMs: Long,
): Long {
    val safeDirection = if (direction >= 0) 1 else -1
    var cursor = currentMs.coerceIn(0L, durationMs)
    val stepCount = if (fast) SPONSOR_SUBMIT_FAST_SEEK_STEPS else 1
    repeat(stepCount) {
        cursor =
            nextVideoShotTimeMs(cursor, safeDirection)
                ?: (cursor + fallbackSponsorSubmitSeekStepMs() * safeDirection)
        cursor = cursor.coerceIn(0L, durationMs)
    }
    return cursor
}

private fun PlayerActivity.nextVideoShotTimeMs(currentMs: Long, direction: Int): Long? {
    val times = currentVideoShot?.times ?: return null
    if (times.isEmpty()) return null
    val guard = 50L
    return if (direction > 0) {
        times.firstOrNull { it.toLong() * 1000L > currentMs + guard }?.toLong()?.times(1000L)
    } else {
        times.lastOrNull { it.toLong() * 1000L < currentMs - guard }?.toLong()?.times(1000L)
    }
}

private fun PlayerActivity.fallbackSponsorSubmitSeekStepMs(): Long =
    BiliClient.prefs.playerShortSeekStepSeconds.coerceAtLeast(1).toLong() * 1000L

private fun PlayerActivity.sponsorSubmitMarkerToleranceMs(): Long =
    max(SPONSOR_SUBMIT_MARKER_TOLERANCE_MIN_MS, fallbackSponsorSubmitSeekStepMs() / 2L)

private fun PlayerActivity.resolveSponsorSubmitDurationMs(): Long =
    player?.duration?.takeIf { it > 0L }
        ?: currentViewDurationMs?.takeIf { it > 0L }
        ?: 0L

private fun PlayerActivity.updateSponsorSubmitPanelUi(loadThumbnails: Boolean) {
    val state = sponsorSubmitPanelState
    val duration = resolveSponsorSubmitDurationMs()
    val markers = state.draft.orderedMarkers()
    val selectedMarkerId =
        when (state.mode) {
            SponsorSubmitInteractionMode.MARK -> null
            SponsorSubmitInteractionMode.DELETE -> state.selectedMarkerId
            SponsorSubmitInteractionMode.MOVE -> state.movingMarkerId
        }
    binding.sponsorSubmitTimeline.setState(
        durationMs = duration,
        cursorMs = state.cursorMs,
        markers = markers,
        selectedMarkerId = selectedMarkerId,
        movingMarkerId = state.movingMarkerId,
    )
    binding.tvSponsorSubmitStatus.text = buildSponsorSubmitStatusText(duration)

    val hasCompleteSegment = state.draft.completeSegments().isNotEmpty()
    val hasAnyMarker = state.draft.hasAnyMarker()
    binding.btnSponsorSubmitUpload.setSponsorSubmitEnabled(hasCompleteSegment && !state.submitting)
    binding.btnSponsorSubmitDelete.setSponsorSubmitEnabled(hasAnyMarker && !state.submitting)
    binding.btnSponsorSubmitClose.setSponsorSubmitEnabled(true)

    if (binding.sponsorSubmitButtonRow.hasFocus() && currentFocus?.isEnabled != true) {
        focusSponsorSubmitDefaultButton()
    }
    if (loadThumbnails) updateSponsorSubmitThumbnails() else updateSponsorSubmitThumbnailAspect()
}

private fun PlayerActivity.buildSponsorSubmitStatusText(durationMs: Long): String {
    val state = sponsorSubmitPanelState
    val completeCount = state.draft.completeSegments().size
    val modeText =
        when (state.mode) {
            SponsorSubmitInteractionMode.MARK -> if (completeCount == 0) "标记中" else "${completeCount}段"
            SponsorSubmitInteractionMode.DELETE -> "删除标记"
            SponsorSubmitInteractionMode.MOVE -> {
                val marker = state.draft.markerById(state.movingMarkerId)
                val kind = if (marker?.kind == SponsorSubmitMarkerKind.END) "结束点" else "开始点"
                "移动$kind"
            }
        }
    val draftText =
        state.draft.drafts().joinToString("  ") { draft ->
            val start = draft.start?.timeMs?.let(::formatHms) ?: "--:--"
            val end = draft.end?.timeMs?.let(::formatHms) ?: "--:--"
            "$start-$end"
        }.ifBlank { "未标记" }
    return "${formatHms(state.cursorMs)} / ${formatHms(durationMs)} · $modeText · $draftText"
}

private fun PlayerActivity.updateSponsorSubmitThumbnailAspect() {
    val shot = currentVideoShot
    val width = currentVideoContentWidth ?: shot?.fallbackAspectWidth ?: 16
    val height = currentVideoContentHeight ?: shot?.fallbackAspectHeight ?: 9
    binding.sponsorSubmitThumbnails.setContentAspectRatio(width, height)
}

private fun PlayerActivity.updateSponsorSubmitThumbnails() {
    updateSponsorSubmitThumbnailAspect()
    val shot = currentVideoShot
    val cache = videoShotImageCache
    if (shot == null || cache == null || shot.times.isEmpty()) {
        binding.sponsorSubmitThumbnails.clearThumbnails()
        return
    }

    val cursorSec = (sponsorSubmitPanelState.cursorMs / 1000L).toInt().coerceAtLeast(0)
    val centerIndex = shot.times.closestIndexTo(cursorSec)
    binding.sponsorSubmitThumbnails.setThumbnailTimes(
        timesSec = shot.times,
        selectedIndex = centerIndex,
        durationMs = resolveSponsorSubmitDurationMs(),
    )
}

private fun List<Int>.closestIndexTo(target: Int): Int {
    if (isEmpty()) return 0
    var bestIndex = 0
    var bestDistance = Int.MAX_VALUE
    for (i in indices) {
        val distance = abs(this[i] - target)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = i
        }
    }
    return bestIndex
}

private fun PlayerActivity.focusSponsorSubmitTimeline(): Boolean =
    binding.sponsorSubmitTimeline.requestSponsorSubmitFocusIfUsable()

private fun PlayerActivity.focusSponsorSubmitDefaultButton() {
    val target =
        when {
            binding.btnSponsorSubmitUpload.isEnabled -> binding.btnSponsorSubmitUpload
            binding.btnSponsorSubmitDelete.isEnabled -> binding.btnSponsorSubmitDelete
            else -> binding.btnSponsorSubmitClose
        }
    target.post { target.requestSponsorSubmitFocusIfUsable() }
}

private fun PlayerActivity.moveSponsorSubmitButtonFocus(direction: Int) {
    val buttons =
        listOf(
            binding.btnSponsorSubmitUpload,
            binding.btnSponsorSubmitDelete,
            binding.btnSponsorSubmitClose,
        ).filter { it.visibility == View.VISIBLE && it.isEnabled }
    if (buttons.isEmpty()) return
    val currentIndex = buttons.indexOf(currentFocus).takeIf { it >= 0 } ?: buttons.indexOf(binding.btnSponsorSubmitClose).takeIf { it >= 0 } ?: 0
    val next = buttons[(currentIndex + direction).coerceIn(0, buttons.lastIndex)]
    next.requestSponsorSubmitFocusIfUsable()
}

private fun View.requestSponsorSubmitFocusIfUsable(): Boolean {
    if (!isAttachedToWindow) return false
    if (visibility != View.VISIBLE) return false
    if (!isEnabled) return false
    if (!isFocusable) return false
    return requestFocus()
}

private fun View.setSponsorSubmitEnabled(enabled: Boolean) {
    isEnabled = enabled
    alpha = if (enabled) 1.0f else 0.45f
}

private fun PlayerActivity.showSponsorSubmitCategoryPicker() {
    if (sponsorSubmitPanelState.draft.completeSegments().isEmpty()) {
        updateSponsorSubmitPanelUi(loadThumbnails = false)
        return
    }
    val restoreFocusView = currentFocus
    AppPopup.singleChoice(
        context = this,
        title = "上传类型",
        items = SPONSOR_SUBMIT_CATEGORY_OPTIONS.map { it.label },
        checkedIndex = 0,
        onDismiss = onDismiss@{
            if (!isSponsorSubmitPanelVisible()) return@onDismiss
            binding.root.post {
                when {
                    restoreFocusView?.requestSponsorSubmitFocusIfUsable() == true -> Unit
                    binding.btnSponsorSubmitUpload.requestSponsorSubmitFocusIfUsable() -> Unit
                    else -> focusSponsorSubmitTimeline()
                }
            }
        },
        onRestoreFocus = onRestoreFocus@{
            if (!isSponsorSubmitPanelVisible()) return@onRestoreFocus false
            restoreFocusView?.requestSponsorSubmitFocusIfUsable()
                ?: binding.btnSponsorSubmitUpload.requestSponsorSubmitFocusIfUsable()
        },
    ) { index, _ ->
        val category = SPONSOR_SUBMIT_CATEGORY_OPTIONS.getOrNull(index) ?: return@singleChoice
        submitSponsorSubmitSegments(category)
    }
}

private fun PlayerActivity.submitSponsorSubmitSegments(category: SponsorSubmitCategoryOption) {
    if (sponsorSubmitUploadJob?.isActive == true) return
    val bvid = currentBvid.trim()
    val cid = currentCid
    val duration = resolveSponsorSubmitDurationMs()
    val draftSegments = sponsorSubmitPanelState.draft.completeSegments()
    if (bvid.isBlank() || cid <= 0L || duration <= 0L || draftSegments.isEmpty()) {
        updateSponsorSubmitPanelUi(loadThumbnails = false)
        return
    }

    val apiSegments =
        draftSegments.map { segment ->
            SponsorBlockApi.SubmitSegment(
                startMs = segment.startMs,
                endMs = segment.endMs,
                category = category.key,
                actionType = "skip",
            )
        }
    for (segment in apiSegments) {
        AppLog.i(
            SPONSOR_SUBMIT_TAG,
            "upload request bvid=$bvid cid=$cid category=${segment.category} action=${segment.actionType} " +
                "startMs=${segment.startMs} endMs=${segment.endMs} durationMs=$duration",
        )
    }

    sponsorSubmitUploadJob =
        lifecycleScope.launch {
            sponsorSubmitPanelState.submitting = true
            updateSponsorSubmitPanelUi(loadThumbnails = false)
            try {
                val submitResult =
                    withContext(Dispatchers.IO) {
                        SponsorBlockApi.submitSkipSegments(
                            bvid = bvid,
                            cid = cid,
                            userId = BiliClient.prefs.sponsorBlockPrivateUserId,
                            videoDurationMs = duration,
                            segments = apiSegments,
                        )
                    }
                if (!isActive) return@launch
                logSponsorSubmitResultSegments(bvid = bvid, cid = cid, result = submitResult)

                if (submitResult.state == SponsorBlockApi.SubmitState.SUCCESS) {
                    if (currentBvid == bvid && currentCid == cid) {
                        sponsorSubmitPanelState.draft.deleteCompleteSegments()
                        sponsorSubmitPanelState.mode = SponsorSubmitInteractionMode.MARK
                        sponsorSubmitPanelState.selectedMarkerId = null
                        sponsorSubmitPanelState.movingMarkerId = null
                        applySponsorSubmitLocalAutoSkipSegments(apiSegments)
                    }
                    AppToast.show(this@submitSponsorSubmitSegments, "已上传${apiSegments.size}段片段")
                } else {
                    AppToast.showLong(this@submitSponsorSubmitSegments, submitResult.detail ?: "上传失败")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.w(SPONSOR_SUBMIT_TAG, "upload failed bvid=$bvid cid=$cid", t)
                AppToast.showLong(this@submitSponsorSubmitSegments, t.message ?: "上传失败")
            } finally {
                sponsorSubmitPanelState.submitting = false
                sponsorSubmitUploadJob = null
                if (isSponsorSubmitPanelVisible()) updateSponsorSubmitPanelUi(loadThumbnails = false)
            }
        }
}

private fun PlayerActivity.logSponsorSubmitResultSegments(
    bvid: String,
    cid: Long,
    result: SponsorBlockApi.SubmitResult,
) {
    val detail = result.detail?.takeIf { it.isNotBlank() } ?: "-"
    AppLog.i(
        SPONSOR_SUBMIT_TAG,
        "upload result bvid=$bvid cid=$cid state=${result.state.name.lowercase()} " +
            "http=${result.httpCode} count=${result.segments.size} detail=$detail",
    )
    result.segments.forEachIndexed { index, segment ->
        AppLog.i(
            SPONSOR_SUBMIT_TAG,
            "upload response[$index] bvid=$bvid cid=$cid uuid=${segment.uuid.orEmpty()} " +
                "category=${segment.category.orEmpty()} startMs=${segment.startMs} endMs=${segment.endMs}",
        )
    }
}

private fun PlayerActivity.applySponsorSubmitLocalAutoSkipSegments(
    submittedSegments: List<SponsorBlockApi.SubmitSegment>,
) {
    val localSubmitted =
        submittedSegments.map { segment ->
            SkipSegment(
                id = "sb:local:${currentBvid}:${currentCid}:${segment.category}:${segment.startMs}-${segment.endMs}",
                startMs = segment.startMs,
                endMs = segment.endMs,
                category = segment.category,
                source = "sponsorblock",
                actionType = segment.actionType,
            )
        }
    val next =
        (autoSkipSegments + localSubmitted)
            .associateBy { it.id }
            .values
            .toList()

    setAutoSkipSegments(autoSkipToken, next)
    maybeUpdateAutoSkipSegmentMarkers(durationMs = resolveSponsorSubmitDurationMs())
}
