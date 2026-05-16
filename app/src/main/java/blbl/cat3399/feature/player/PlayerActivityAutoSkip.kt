package blbl.cat3399.feature.player

import android.os.SystemClock
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.SponsorBlockApi
import blbl.cat3399.core.api.video.VideoPlayStream
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val AUTO_SKIP_LOG_TAG = "PlayerAutoSkip"

internal fun PlayerActivity.autoSkipCategoryLabel(category: String?): String {
    val c = category?.trim().orEmpty()
    return when (c) {
        "" -> "此"
        "sponsor" -> "赞助/广告"
        "selfpromo" -> "推广"
        "exclusive_access" -> "品牌合作"
        "interaction" -> "三连提醒"
        "poi_highlight" -> "精彩时刻"
        "intro" -> "片头"
        "outro" -> "片尾"
        "preview" -> "预览"
        "padding" -> "填充内容"
        "filler" -> "离题"
        "music_offtopic" -> "非音乐"
        else -> c
    }
}

internal fun PlayerActivity.updateProgressUi() {
    val exo = player ?: return
    val duration = exo.duration.takeIf { it > 0 } ?: currentViewDurationMs ?: 0L
    val pos = exo.currentPosition.coerceAtLeast(0L)
    val uiPos = resolvePlayerUiPositionMs(pos, holdScrubPreviewPosMs, keySeekPreviewPosMs)
    val bufPos = resolveSeekUiBufferedPositionMs(exo.bufferedPosition, uiPos)

    val uiScrubbing = scrubbing || holdScrubPreviewPosMs != null || keySeekPreviewPosMs != null
    if (!uiScrubbing) {
        binding.tvTime.text = "${formatHms(pos)} / ${formatHms(duration)}"
        binding.tvSeekOsdTime.text = "${formatHms(pos)} / ${formatHms(duration)}"
    }

    val enabled = duration > 0
    binding.seekProgress.isEnabled = enabled
    binding.progressPersistentBottom.isEnabled = enabled
    binding.progressSeekOsd.isEnabled = enabled
    if (enabled) {
        val bufferedProgress =
            ((bufPos.toDouble() / duration.toDouble()) * PlayerActivity.SEEK_MAX)
                .toInt()
                .coerceIn(0, PlayerActivity.SEEK_MAX)
        binding.seekProgress.secondaryProgress = bufferedProgress
        binding.progressPersistentBottom.secondaryProgress = bufferedProgress
        binding.progressSeekOsd.secondaryProgress = bufferedProgress

        if (!uiScrubbing) {
            val p = ((pos.toDouble() / duration.toDouble()) * PlayerActivity.SEEK_MAX).toInt().coerceIn(0, PlayerActivity.SEEK_MAX)
            binding.seekProgress.progress = p
        }
        val pNow = ((uiPos.toDouble() / duration.toDouble()) * PlayerActivity.SEEK_MAX).toInt().coerceIn(0, PlayerActivity.SEEK_MAX)
        binding.progressPersistentBottom.progress = pNow
        binding.progressSeekOsd.progress = pNow
    } else {
        binding.seekProgress.secondaryProgress = 0
        binding.progressPersistentBottom.secondaryProgress = 0
        binding.progressPersistentBottom.progress = 0
        binding.progressSeekOsd.secondaryProgress = 0
        binding.progressSeekOsd.progress = 0
    }
    requestDanmakuSegmentsForPosition(pos, immediate = false)
    val markerDurationMs = exo.duration.takeIf { it > 0 } ?: currentViewDurationMs ?: 0L
    maybeUpdateAutoSkipSegmentMarkers(durationMs = markerDurationMs)
    maybeTickAutoSkipSegments(posMs = pos)
    maybeUpdateAutoNext(posMs = pos, durationMs = duration)
    updateBufferingOverlay()
}

internal fun PlayerActivity.cancelPendingAutoResume(reason: String) {
    if (reason == "back" || reason == "user_seek") autoResumeCancelledByUser = true
    dismissAutoResumeHint()
    autoResumeJob?.cancel()
    autoResumeJob = null
    trace?.log("resume:cancel", "reason=$reason")
}

internal fun PlayerActivity.showAutoResumeHint(targetMs: Long) {
    dismissAutoResumeHint()
    val timeText = formatHms(targetMs.coerceAtLeast(0L))
    val msg = "已续播到上次播放位置（$timeText），3秒内按返回从头播放"
    autoResumeHintVisible = true
    autoResumeHintText = msg
    autoResumeUndoTargetMs = targetMs.coerceAtLeast(0L)
    autoResumeUndoDeadlineElapsedMs = SystemClock.elapsedRealtime() + PlayerActivity.AUTO_RESUME_BACK_RESTART_WINDOW_MS
    // Reuse the existing bottom "seek hint" component for consistent look & feel.
    showSeekHint(msg, hold = true)
    autoResumeHintTimeoutJob?.cancel()
    autoResumeHintTimeoutJob =
        lifecycleScope.launch {
            delay(PlayerActivity.AUTO_RESUME_BACK_RESTART_WINDOW_MS)
            if (!isActive) return@launch
            if (SystemClock.elapsedRealtime() >= autoResumeUndoDeadlineElapsedMs) {
                dismissAutoResumeHint()
            }
        }
}

internal fun PlayerActivity.dismissAutoResumeHint() {
    if (!autoResumeHintVisible) return
    val msg = autoResumeHintText
    autoResumeHintVisible = false
    autoResumeHintText = null
    autoResumeHintTimeoutJob?.cancel()
    autoResumeHintTimeoutJob = null
    autoResumeUndoDeadlineElapsedMs = 0L
    autoResumeUndoTargetMs = -1L
    // tvSeekHint is shared; only hide it if we are still showing our own message.
    if (msg != null && binding.tvSeekHint.text?.toString() == msg) {
        seekHintJob?.cancel()
        binding.tvSeekHint.visibility = View.GONE
    }
}

internal fun PlayerActivity.tryRollbackAutoResumeOnBack(): Boolean {
    if (!autoResumeHintVisible) return false
    val deadline = autoResumeUndoDeadlineElapsedMs
    if (deadline <= 0L) return false
    if (SystemClock.elapsedRealtime() > deadline) return false
    val exo = player ?: return false
    autoResumeCancelledByUser = true
    trace?.log("resume:rollback", "from=${autoResumeUndoTargetMs.coerceAtLeast(0L)}ms")
    dismissAutoResumeHint()
    exo.seekTo(0L)
    showSeekHint("已从头播放", hold = false)
    return true
}

internal fun PlayerActivity.cancelPendingAutoSkip(reason: String, markIgnored: Boolean) {
    autoSkipPending?.let { pending ->
        if (markIgnored) {
            autoSkipHandledSegmentIds.add(pending.segment.id)
        }
    }
    autoSkipPending = null
    dismissAutoSkipHint()
    trace?.log("skipseg:cancel", "reason=$reason ignored=${if (markIgnored) 1 else 0}")
}

internal fun PlayerActivity.showAutoSkipHint(segment: SkipSegment) {
    dismissAutoSkipHint()
    val label = autoSkipCategoryLabel(segment.category)
    val range = "${formatHms(segment.startMs)}→${formatHms(segment.endMs)}"
    val msg = "将要跳过${label}片段（$range），按返回取消"
    autoSkipHintVisible = true
    autoSkipHintText = msg
    showSeekHint(msg, hold = true)
}

internal fun PlayerActivity.dismissAutoSkipHint() {
    if (!autoSkipHintVisible) return
    val msg = autoSkipHintText
    autoSkipHintVisible = false
    autoSkipHintText = null
    // tvSeekHint is shared; only hide it if we are still showing our own message.
    if (msg != null && binding.tvSeekHint.text?.toString() == msg) {
        seekHintJob?.cancel()
        binding.tvSeekHint.visibility = View.GONE
    }
}

internal fun PlayerActivity.maybeUpdateAutoSkipSegmentMarkers(durationMs: Long) {
    val enabled = BiliClient.prefs.playerAutoSkipSegmentsEnabled
    if (!enabled || durationMs <= 0L || autoSkipSegments.isEmpty()) {
        if (autoSkipMarkersShown) {
            binding.seekProgress.clearSegments()
            binding.progressPersistentBottom.clearSegments()
            binding.progressSeekOsd.clearSegments()
            autoSkipMarkersShown = false
        }
        autoSkipMarkersDirty = false
        autoSkipMarkersDurationMs = durationMs
        return
    }

    if (!autoSkipMarkersDirty && autoSkipMarkersShown && autoSkipMarkersDurationMs == durationMs) return
    autoSkipMarkersDurationMs = durationMs
    autoSkipMarkersDirty = false

    val marks = buildAutoSkipSegmentMarks(autoSkipSegments, durationMs)
    binding.seekProgress.setSegments(marks)
    binding.progressPersistentBottom.setSegments(marks)
    binding.progressSeekOsd.setSegments(marks)
    autoSkipMarkersShown = marks.isNotEmpty()
}

internal fun PlayerActivity.maybeTickAutoSkipSegments(posMs: Long) {
    if (!BiliClient.prefs.playerAutoSkipSegmentsEnabled) return
    val exo = player ?: return
    if (!exo.playWhenReady) return
    if (scrubbing) return
    if (autoResumeHintVisible) return

    val now = SystemClock.elapsedRealtime()
    autoSkipPending?.let { pending ->
        if (pending.token != autoSkipToken) {
            autoSkipPending = null
            dismissAutoSkipHint()
            return
        }
        if (autoSkipHandledSegmentIds.contains(pending.segment.id)) {
            autoSkipPending = null
            dismissAutoSkipHint()
            return
        }
        if (now < pending.dueAtElapsedMs) return

        val seg = pending.segment
        if (posMs < seg.startMs) return
        if (posMs >= seg.endMs) {
            autoSkipPending = null
            dismissAutoSkipHint()
            return
        }

        val durationMs = exo.duration.takeIf { it > 0 }
        val targetMs = durationMs?.let { seg.endMs.coerceIn(0L, (it - 500L).coerceAtLeast(0L)) } ?: seg.endMs
        autoSkipHandledSegmentIds.add(seg.id)
        autoSkipPending = null
        dismissAutoSkipHint()
        trace?.log("skipseg:seek", "to=${targetMs}ms id=${seg.id} cat=${seg.category ?: ""} src=${seg.source}")
        exo.seekTo(targetMs)
        showSeekHint("已跳过${autoSkipCategoryLabel(seg.category)}片段", hold = false)
        return
    }

    if (autoSkipSegments.isEmpty()) return
    val windowEndMs = posMs + PlayerActivity.AUTO_SKIP_START_WINDOW_MS
    val candidate = findAutoSkipCandidate(autoSkipSegments, autoSkipHandledSegmentIds, posMs, windowEndMs) ?: return

    autoSkipPending = PendingAutoSkip(token = autoSkipToken, segment = candidate, dueAtElapsedMs = now + PlayerActivity.AUTO_SKIP_DELAY_MS)
    trace?.log("skipseg:pending", "id=${candidate.id} cat=${candidate.category ?: ""} src=${candidate.source}")
    showAutoSkipHint(candidate)
}

internal fun PlayerActivity.maybeStartAutoSkipSegments(
    playStream: VideoPlayStream,
    bvid: String,
    cid: Long,
    playbackToken: Int,
) {
    if (!BiliClient.prefs.playerAutoSkipSegmentsEnabled) return
    if (playbackToken != autoSkipToken) return
    if (bvid.isBlank() || cid <= 0L) return

    autoSkipFetchJob?.cancel()
    autoSkipFetchJob = null

    val clipSegments = extractClipInfoSegmentsFromPlayStream(playStream)
    setAutoSkipSegments(playbackToken, clipSegments)

    autoSkipFetchJob =
        lifecycleScope.launch {
            val sbResult =
                withContext(Dispatchers.IO) {
                    SponsorBlockApi.skipSegments(bvid = bvid, cid = cid)
                }
            if (!isActive) return@launch
            if (playbackToken != autoSkipToken) return@launch

            val detail = sbResult.detail?.takeIf { it.isNotBlank() } ?: "-"
            trace?.log("skipseg:fetch", "state=${sbResult.state.name.lowercase()} count=${sbResult.segments.size} detail=$detail")
            if (sbResult.state == SponsorBlockApi.FetchState.ERROR) {
                AppLog.w(AUTO_SKIP_LOG_TAG, "skipSegments failed bvid=$bvid cid=$cid detail=$detail")
            }

            setAutoSkipSegments(playbackToken, mergeAutoSkipSegments(clipSegments, sbResult.segments))
        }
}

internal fun PlayerActivity.extractClipInfoSegmentsFromPlayStream(playStream: VideoPlayStream): List<SkipSegment> {
    val out = ArrayList<SkipSegment>(playStream.clipSegments.size)
    for (segment in playStream.clipSegments) {
        val startMs = segment.startMs.coerceAtLeast(0L)
        val endMs = segment.endMs.coerceAtLeast(0L)
        if (endMs <= startMs) continue
        val category = segment.category.trim().ifBlank { "clip" }
        val id = "pgc:$category:$startMs-$endMs"
        out.add(
            SkipSegment(
                id = id,
                startMs = startMs,
                endMs = endMs,
                category = category,
                source = "pgc_clip",
                actionType = "skip",
            ),
        )
    }
    return out
}

internal fun buildAutoSkipSegmentMarks(segments: List<SkipSegment>, durationMs: Long): List<SegmentMark> {
    val durF = durationMs.toFloat()
    if (durF <= 0f) return emptyList()
    return segments.mapNotNull { seg ->
        val start = seg.startMs.coerceAtLeast(0L)
        val end = seg.endMs.coerceAtLeast(0L)
        val s = (start.toFloat() / durF).coerceIn(0f, 1f)
        if (seg.isPoi()) {
            SegmentMark(startFraction = s, endFraction = s, style = SegmentMarkStyle.POI)
        } else {
            if (end <= start) return@mapNotNull null
            val e = (end.toFloat() / durF).coerceIn(0f, 1f)
            if (e <= s) return@mapNotNull null
            SegmentMark(startFraction = s, endFraction = e, style = SegmentMarkStyle.SKIP)
        }
    }
}

internal fun findAutoSkipCandidate(
    segments: List<SkipSegment>,
    handledSegmentIds: Set<String>,
    posMs: Long,
    windowEndMs: Long,
): SkipSegment? =
    segments.firstOrNull { seg ->
        if (handledSegmentIds.contains(seg.id)) return@firstOrNull false
        if (!seg.isAutoSkippable()) return@firstOrNull false
        when {
            posMs >= seg.startMs && posMs < seg.endMs -> true
            seg.startMs in posMs..windowEndMs -> true
            seg.startMs > windowEndMs -> false
            else -> false
        }
    }

internal fun mergeAutoSkipSegments(
    clipSegments: List<SkipSegment>,
    sponsorBlockSegments: List<SponsorBlockApi.Segment>,
): List<SkipSegment> {
    val merged = LinkedHashMap<String, SkipSegment>(clipSegments.size + sponsorBlockSegments.size)
    for (seg in clipSegments) merged[seg.id] = seg
    for (sb in sponsorBlockSegments) {
        val id =
            sb.uuid?.takeIf { it.isNotBlank() }?.let { "sb:$it" }
                ?: "sb:${sb.category.orEmpty()}:${sb.actionType.orEmpty()}:${sb.startMs}-${sb.endMs}"
        merged[id] =
            SkipSegment(
                id = id,
                startMs = sb.startMs,
                endMs = sb.endMs,
                category = sb.category,
                source = "sponsorblock",
                actionType = sb.actionType,
            )
    }
    return merged.values.toList()
}

internal fun PlayerActivity.setAutoSkipSegments(token: Int, segments: List<SkipSegment>) {
    if (token != autoSkipToken) return
    autoSkipSegments = segments.sortedBy { it.startMs }
    autoSkipMarkersDirty = true
    trace?.log("skipseg:set", "count=${autoSkipSegments.size}")
}

internal fun PlayerActivity.extractResumeCandidateFromPlayStream(playStream: VideoPlayStream): ResumeCandidate? {
    val time = playStream.resume?.rawTime?.takeIf { it > 0 } ?: return null
    val hint =
        when {
            time >= 10_000L -> RawTimeUnitHint.MILLIS_LIKELY
            else -> RawTimeUnitHint.UNKNOWN
        }
    return ResumeCandidate(rawTime = time, rawTimeUnitHint = hint, source = "playurl")
}

internal fun PlayerActivity.normalizeResumePositionMs(raw: Long, hint: RawTimeUnitHint, durationMs: Long?): Long? {
    if (raw <= 0) return null
    val dur = durationMs?.takeIf { it > 0 }
    if (dur != null) {
        return when {
            raw in 1..dur -> raw
            raw * 1000 in 1..dur -> raw * 1000
            else -> raw
        }
    }
    return when (hint) {
        RawTimeUnitHint.MILLIS_LIKELY -> raw
        RawTimeUnitHint.SECONDS_LIKELY -> raw * 1000
        RawTimeUnitHint.UNKNOWN -> if (raw >= 10_000L) raw else raw * 1000
    }
}

internal fun PlayerActivity.shouldAutoResumeTo(positionMs: Long, durationMs: Long?): Boolean {
    if (positionMs < 5_000L) return false
    val dur = durationMs?.takeIf { it > 0 } ?: return true
    return positionMs < (dur - 10_000L).coerceAtLeast(0L)
}

internal fun PlayerActivity.maybeScheduleAutoResume(
    playStream: VideoPlayStream,
    bvid: String,
    cid: Long,
    playbackToken: Int,
) {
    if (!BiliClient.prefs.playerAutoResumeEnabled) return
    if (autoResumeCancelledByUser) return
    if (playbackToken != autoResumeToken) return
    val engine = player ?: return

    pendingIntentResumeCandidate?.let { cand ->
        val expectedCid = pendingIntentResumeCid
        val expectedEpId = pendingIntentResumeEpId
        val epId = currentEpId
        val cidMatches = expectedCid == null || expectedCid == cid
        val epIdMatches = expectedEpId == null || (epId != null && epId == expectedEpId)
        pendingIntentResumeCandidate = null
        pendingIntentResumeCid = null
        pendingIntentResumeEpId = null
        if (cidMatches && epIdMatches) {
            scheduleAutoResume(engine = engine, candidate = cand, playbackToken = playbackToken)
            return
        }
    }

    val strictCidMatch = isMultiPagePlaylist(partsListItems, currentBvid)
    extractResumeCandidateFromPlayStream(playStream)?.let { cand ->
        val lastCid = playStream.resume?.lastCid
        when {
            lastCid != null && lastCid != cid -> Unit
            strictCidMatch && lastCid == null -> Unit
            else -> {
                scheduleAutoResume(engine = engine, candidate = cand, playbackToken = playbackToken)
                return
            }
        }
    }

    autoResumeJob?.cancel()
    autoResumeJob =
        lifecycleScope.launch {
            val playerInfo = runCatching { BiliApi.videoPlayerInfo(bvid = bvid, cid = cid) }.getOrNull() ?: return@launch
            if (!isActive) return@launch
            if (playbackToken != autoResumeToken) return@launch
            if (autoResumeCancelledByUser) return@launch
            val lastCid = playerInfo.resume?.lastCid?.takeIf { it > 0 }
            if (lastCid != null && lastCid != cid) return@launch
            if (strictCidMatch && lastCid == null) return@launch
            val resume = playerInfo.resume?.rawTime?.takeIf { it > 0 } ?: return@launch
            val hint =
                when {
                    resume >= 10_000L -> RawTimeUnitHint.MILLIS_LIKELY
                    else -> RawTimeUnitHint.UNKNOWN
                }
            val cand = ResumeCandidate(rawTime = resume, rawTimeUnitHint = hint, source = "videoPlayerInfo")
            scheduleAutoResume(engine = engine, candidate = cand, playbackToken = playbackToken)
        }
}

internal fun PlayerActivity.scheduleAutoResume(engine: BlblPlayerEngine, candidate: ResumeCandidate, playbackToken: Int) {
    if (autoResumeCancelledByUser) return
    autoResumeJob?.cancel()
    dismissAutoResumeHint()
    trace?.log("resume:pending", "src=${candidate.source} raw=${candidate.rawTime}")

    val previewDurationMs = engine.duration.takeIf { it > 0 } ?: currentViewDurationMs
    val previewTargetMs = normalizeResumePositionMs(candidate.rawTime, candidate.rawTimeUnitHint, previewDurationMs)
    if (previewTargetMs == null) return
    if (!shouldAutoResumeTo(previewTargetMs, previewDurationMs)) return

    autoResumeJob =
        lifecycleScope.launch {
            // Seeking too early (while the beginning is still buffering) can cause some long videos to get stuck
            // with a black screen. Wait until the player becomes READY, then seek immediately.
            val readyDeadlineAtMs = SystemClock.elapsedRealtime() + 30_000L
            while (isActive) {
                if (autoResumeCancelledByUser) return@launch
                if (playbackToken != autoResumeToken) return@launch
                val p = player ?: return@launch
                if (p !== engine) return@launch
                val state = p.playbackState
                if (state == Player.STATE_READY) break
                if (state == Player.STATE_ENDED) return@launch
                if (SystemClock.elapsedRealtime() >= readyDeadlineAtMs) return@launch
                delay(50L)
            }
            if (!isActive) return@launch
            if (autoResumeCancelledByUser) return@launch
            if (playbackToken != autoResumeToken) return@launch
            val p = player ?: return@launch
            if (p !== engine) return@launch

            val durationMs = p.duration.takeIf { it > 0 } ?: currentViewDurationMs
            val targetMs = normalizeResumePositionMs(candidate.rawTime, candidate.rawTimeUnitHint, durationMs) ?: return@launch
            if (!shouldAutoResumeTo(targetMs, durationMs)) return@launch
            val clamped = durationMs?.let { dur -> targetMs.coerceIn(0L, (dur - 500L).coerceAtLeast(0L)) } ?: targetMs
            trace?.log("resume:seek", "to=${clamped}ms src=${candidate.source}")
            p.seekTo(clamped)
            showAutoResumeHint(targetMs = clamped)
        }
}
