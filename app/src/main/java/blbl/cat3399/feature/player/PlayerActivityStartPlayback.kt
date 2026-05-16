package blbl.cat3399.feature.player

import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.video.VideoDetail
import blbl.cat3399.core.api.video.VideoPlayStream
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiEpisode
import blbl.cat3399.core.model.BangumiSeasonDetail
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.util.parseBangumiRedirectUrl
import blbl.cat3399.core.util.pgcAccessBadgeTextOf
import blbl.cat3399.feature.my.BangumiDetailActivity
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.ExoPlayerEngine
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import blbl.cat3399.feature.player.engine.PlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.prefs.AppPrefs
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val RISK_CONTROL_USER_HINT = "当前账号可能被风控,请尽量联系开发者!"
private val riskControlUserHintShown = AtomicBoolean(false)

internal data class VodPageDuration(
    val cid: Long,
    val durationSec: Long,
)

internal fun resolveCurrentVodDurationMs(
    viewDurationSec: Long?,
    pageDurations: List<VodPageDuration>,
    cid: Long,
): Long? {
    val pageDuration =
        cid.takeIf { it > 0L }?.let { safeCid ->
            pageDurations.firstOrNull { it.cid == safeCid }?.durationSec
        }?.takeIf { it > 0L }
    if (pageDuration != null) return pageDuration * 1000L

    val onlyPageDuration = pageDurations.singleOrNull()?.durationSec?.takeIf { it > 0L }
    if (onlyPageDuration != null) return onlyPageDuration * 1000L

    if (pageDurations.size > 1) return null
    return viewDurationSec?.takeIf { it > 0L }?.times(1000L)
}

private suspend fun <T> runSuspendCatchingNonCancellation(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        Result.failure(throwable)
    }

private fun PlayerActivity.startPlaybackFailureMessage(throwable: Throwable): String {
    val apiError = throwable as? BiliApiException
    return when {
        apiError?.apiCode == -404 -> "当前视频暂时无法播放（接口返回 -404）"
        !throwable.message.isNullOrBlank() -> "加载播放信息失败：${throwable.message}"
        else -> "加载播放信息失败：未知错误"
    }
}

private fun PlayerActivity.finishAfterStartPlaybackFailure(throwable: Throwable) {
    if (!handlePlayUrlErrorIfNeeded(throwable)) {
        AppToast.showLong(this, startPlaybackFailureMessage(throwable))
    }
    if (!isFinishing) finish()
}

private fun resolveCurrentVodDurationMsFromDetail(detail: VideoDetail, cid: Long): Long? {
    val pageDurations =
        detail.pages.mapNotNull { page ->
            val durationSec = page.durationSec?.toLong()?.takeIf { it > 0L } ?: return@mapNotNull null
            VodPageDuration(cid = page.cid, durationSec = durationSec)
        }
    val viewDurationSec = detail.durationSec?.takeIf { it > 0L }
    return resolveCurrentVodDurationMs(
        viewDurationSec = viewDurationSec,
        pageDurations = pageDurations,
        cid = cid,
    )
}

private fun PlayerActivity.showPlaybackTitleHintIfFullscreen(rawTitle: String?): Boolean {
    if (osdMode != PlayerActivity.OsdMode.Hidden) return false
    val fallbackTitle =
        binding.tvTitle.text
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "-" }
            ?: currentMainTitle?.trim()?.takeIf { it.isNotBlank() && it != "-" }
            ?: return false
    val title = formatAutoNextHintTitle(rawTitle, fallbackTitle = fallbackTitle)
    showSeekHint("正在播放 $title", hold = false, hideDelayMs = PlayerActivity.PLAYBACK_TITLE_HINT_HIDE_DELAY_MS)
    return true
}

internal fun PlayerActivity.resetPlaybackStateForNewMedia(
    engine: BlblPlayerEngine,
    preservePartsList: Boolean,
) {
    cancelPlayUrlAutoRefresh(reason = "new_media")
    traceFirstFrameLogged = false
    lastAvailableQns = emptyList()
    lastAvailableAudioIds = emptyList()
    session = session.copy(actualQn = 0)
    session = session.copy(actualAudioId = 0)
    currentViewDurationMs = null
    debug.reset()
    clearKeySeekPending()
    cancelDeferredKeySeekPreview()
    holdScrubPreviewPosMs = null
    scrubbing = false
    resetBufferingOverlayState()
    subtitleAvailabilityKnown = false
    subtitleAvailable = false
    subtitleConfig = null
    subtitleItems = emptyList()
    setUpQuickCardOwner(mid = 0L, name = null, avatar = null, followed = null)
    danmakuShield = null
    cancelDanmakuLoading(reason = "new_media")
    danmakuLoadedSegments.clear()
    danmakuSegmentItems.clear()
    binding.danmakuView.setDanmakus(emptyList())
    binding.danmakuView.notifySeek(0L)

    likeActionJob?.cancel()
    likeActionJob = null
    coinActionJob?.cancel()
    coinActionJob = null
    favDialogJob?.cancel()
    favDialogJob = null
    favApplyJob?.cancel()
    favApplyJob = null
    tripleActionJob?.cancel()
    tripleActionJob = null
    cancelLikeButtonHoldGesture(resetTriggered = true)
    socialStateFetchJob?.cancel()
    socialStateFetchJob = null
    socialStateFetchToken++
    actionLiked = false
    actionCoinCount = 0
    actionFavored = false
    updateActionButtonsUi()

    if (!preservePartsList) {
        partsListFetchJob?.cancel()
        partsListFetchJob = null
        partsListLoadMoreJob?.cancel()
        partsListLoadMoreJob = null
        partsListLoadMoreCallbacks.clear()
        partsListFetchToken++
        partsListSource = null
        partsListItems = emptyList()
        partsListUiCards = emptyList()
        partsListIndex = -1
        partsListContinuation = null
    }

    relatedVideosFetchJob?.cancel()
    relatedVideosFetchJob = null
    relatedVideosFetchToken++
    relatedVideosCache = null
    resetPlayerInfoPanelState()

    commentsFetchJob?.cancel()
    commentsFetchJob = null
    commentsFetchToken++
    commentsPage = 1
    commentsTotalCount = -1
    commentsEndReached = false
    commentsItems.clear()

    commentThreadFetchJob?.cancel()
    commentThreadFetchJob = null
    commentThreadFetchToken++
    commentThreadRootRpid = 0L
    commentThreadReturnFocusRpid = 0L
    commentThreadPage = 1
    commentThreadTotalCount = -1
    commentThreadEndReached = false
    commentThreadItems.clear()

    currentVideoShot = null
    videoShotFetchJob?.cancel()
    videoShotFetchJob = null
    videoShotImageCache?.clear()
    videoShotImageCache = null
    currentVideoContentWidth = null
    currentVideoContentHeight = null
    binding.videoShotPreview.spriteFrame = null
    binding.videoShotPreview.resetContentAspectRatio()
    binding.videoShotPreview.visibility = View.GONE

    binding.settingsPanel.visibility = View.GONE
    binding.commentsPanel.visibility = View.GONE
    binding.playerInfoPanel.visibility = View.GONE
    hideBottomCardPanel(restoreFocus = false, dismissTarget = null)
    hideSponsorSubmitPanel(restorePlayback = false)
    menuRevealedPanelSessionActive = false
    binding.recyclerComments.visibility = View.VISIBLE
    binding.recyclerCommentThread.visibility = View.GONE
    binding.rowCommentSort.visibility = View.VISIBLE
    binding.tvCommentsHint.visibility = View.GONE
    (binding.recyclerComments.adapter as? PlayerCommentsAdapter)?.setItems(emptyList())
    (binding.recyclerCommentThread.adapter as? PlayerCommentsAdapter)?.setItems(emptyList())

    playbackConstraints = PlaybackConstraints()
    decodeFallbackAttemptCount = 0
    lastPickedDash = null
    engine.stop()
    (engine as? ExoPlayerEngine)?.exoPlayer?.let { applySubtitleEnabled(it) }
    applyPlaybackMode(engine)
    updateSubtitleButton()
    updateDanmakuButton()
    updateUpButton()
    (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
}

internal fun PlayerActivity.startPlayback(
    bvid: String?,
    cidExtra: Long?,
    epIdExtra: Long?,
    aidExtra: Long?,
    seasonIdExtra: Long? = null,
    initialTitle: String?,
    startedFromList: PlayerVideoListKind? = null,
    showTitleHint: Boolean = false,
) {
    val engine = player ?: return
    val pendingSeekMs = pendingStartPositionMs
    val pendingPlayWhenReady = pendingStartPlayWhenReady
    pendingStartPositionMs = null
    pendingStartPlayWhenReady = null
    val prevBvid = currentBvid.trim()
    val prevAid = currentAid?.takeIf { it > 0 }
    val safeBvid = bvid?.trim().orEmpty()
    val safeAid = aidExtra?.takeIf { it > 0 }
    val startFromList = startedFromList
    if (safeBvid.isBlank() && safeAid == null) return

    val sameMedia =
        (safeBvid.isNotBlank() && safeBvid == prevBvid) ||
            (safeBvid.isBlank() && safeAid != null && safeAid == prevAid)
    if (!sameMedia) {
        currentMainTitle = null
    }

    cancelPendingAutoResume(reason = "new_media")
    autoResumeToken++
    autoResumeCancelledByUser = false
    clearAutoNextState(reason = "new_media", resetUserCancellation = true)
    cancelPendingAutoSkip(reason = "new_media", markIgnored = false)
    autoSkipFetchJob?.cancel()
    autoSkipFetchJob = null
    autoSkipSegments = emptyList()
    autoSkipHandledSegmentIds.clear()
    autoSkipPending = null
    binding.seekProgress.clearSegments()
    binding.progressPersistentBottom.clearSegments()
    autoSkipMarkersDirty = true
    autoSkipMarkersDurationMs = -1L
    autoSkipMarkersShown = false
    autoSkipToken++
    stopReportProgressLoop(flush = false, reason = "new_media")
    reportToken++
    lastReportAtMs = 0L
    lastReportedProgressSec = -1L

    loadJob?.cancel()
    loadJob = null

    currentBvid = safeBvid
    currentEpId = epIdExtra
    currentAid = safeAid
    currentSeasonId =
        seasonIdExtra?.takeIf { it > 0L }
            ?: parseBangumiSeasonIdFromSource(pageListSource)
    currentCid = -1L
    currentVideoIsPortrait = null

    trace =
        PlayerActivity.PlaybackTrace(
            buildString {
                val token = safeBvid.takeLast(8).ifBlank { safeAid?.toString(16) ?: "unknown" }
                append(token)
                append('-')
                append((System.currentTimeMillis() and 0xFFFF).toString(16))
            },
    )

    binding.tvOnline.text = "-人正在观看"
    binding.tvViewCount.text = "-"
    binding.llViewMeta.visibility = View.VISIBLE
    binding.tvPubdate.text = ""
    binding.tvPubdate.visibility = View.GONE
    resetPlaybackStateForNewMedia(
        engine = engine,
        preservePartsList = startFromList == PlayerVideoListKind.PARTS,
    )
    updateTopTitleUi(placeholder = initialTitle)
    var titleHintShown = false
    if (showTitleHint) {
        titleHintShown = showPlaybackTitleHintIfFullscreen(initialTitle)
    }

    updatePlaylistControls()
    maybeWarmUpAutoNextTarget()

    val handler =
        playbackUncaughtHandler
            ?: CoroutineExceptionHandler { _, throwable ->
                AppLog.e("Player", "uncaught", throwable)
                AppToast.showLong(this@startPlayback, "播放失败：${throwable.message}")
                finish()
            }

    loadJob =
        lifecycleScope.launch(handler) {
            val startupJobs = mutableListOf<Job>()
            try {
                trace?.log("view:start")
                val detailJob =
                    async(Dispatchers.IO) {
                        runCatching {
                            if (safeBvid.isNotBlank()) {
                                BiliApi.videoDetail(safeBvid)
                            } else {
                                BiliApi.videoDetail(safeAid ?: 0L)
                            }
                        }.getOrNull()
                    }
                val detail = detailJob.await() ?: error("view detail missing")
                trace?.log("view:done")

                val bangumiRedirect = parseBangumiRedirectUrl(detail.redirectUrl.orEmpty())
                val isAlreadyPgc =
                    currentEpId != null ||
                        pageListSource?.trim().orEmpty().startsWith("Bangumi:")
                if (bangumiRedirect != null && !isAlreadyPgc) {
                    startActivity(
                        Intent(this@startPlayback, BangumiDetailActivity::class.java)
                            .putExtra(BangumiDetailActivity.EXTRA_IS_DRAMA, false)
                            .apply {
                                bangumiRedirect.epId?.let { epId ->
                                    putExtra(BangumiDetailActivity.EXTRA_EP_ID, epId)
                                    putExtra(BangumiDetailActivity.EXTRA_CONTINUE_EP_ID, epId)
                                }
                                bangumiRedirect.seasonId?.let { seasonId ->
                                    putExtra(BangumiDetailActivity.EXTRA_SEASON_ID, seasonId)
                                }
                            },
                    )
                    finish()
                    return@launch
                }

                detail.title?.trim()?.takeIf { it.isNotBlank() }?.let { currentMainTitle = it }
                updateTopTitleUi(placeholder = initialTitle)
                if (showTitleHint && !titleHintShown) {
                    titleHintShown = showPlaybackTitleHintIfFullscreen(initialTitle ?: currentMainTitle)
                }
                applyUpInfo(detail)
                applyTitleMeta(detail)
                applyPlayerInfoVideoDetail(detail)

                val resolvedBvid =
                    detail.bvid.trim().takeIf { it.isNotBlank() }
                        ?: safeBvid
                if (resolvedBvid.isNotBlank()) currentBvid = resolvedBvid

                val cid = cidExtra ?: detail.cid?.takeIf { it > 0 } ?: error("cid missing")
                val aid = detail.aid?.takeIf { it > 0 }
                currentAid = currentAid ?: aid ?: safeAid
                currentCid = cid
                currentViewDurationMs = resolveCurrentVodDurationMsFromDetail(detail = detail, cid = cid)
                refreshActionButtonStatesFromServer(bvid = resolvedBvid, aid = currentAid)
                if (isCommentsPanelVisible() && !isCommentThreadVisible()) ensureCommentsLoaded()
                AppLog.i("Player", "start bvid=$resolvedBvid cid=$cid")
                trace?.log("cid:resolved", "cid=$cid aid=${aid ?: -1} duration=${currentViewDurationMs ?: -1}ms")

                if (startFromList != PlayerVideoListKind.PARTS || partsListItems.isEmpty() || partsListIndex !in partsListItems.indices) {
                    refreshPartsListFromDetail(detail, bvid = resolvedBvid)
                    updateTopTitleUi(placeholder = initialTitle)
                    if (showTitleHint && !titleHintShown) {
                        titleHintShown = showPlaybackTitleHintIfFullscreen(initialTitle ?: currentMainTitle)
                    }
                }
                if (startFromList == PlayerVideoListKind.PAGE) {
                    updatePageListIndexForCurrentMedia(bvid = resolvedBvid, aid = currentAid, cid = cid)
                }
                updatePlaylistControls()
                refreshPlayerInfoPanelContent()
                syncPlayerInfoPanelVisibility()

                requestOnlineWatchingText(bvid = resolvedBvid, cid = cid)
                applyPerVideoPreferredQn(detail, cid = cid)

                val playJob =
                    async {
                        runSuspendCatchingNonCancellation {
                            val (qn, fnval) = playUrlParamsForSession()
                            trace?.log("playurl:start", "qn=$qn fnval=$fnval")
                            playbackConstraints = PlaybackConstraints()
                            decodeFallbackAttemptCount = 0
                            lastPickedDash = null
                            loadPlayableWithTryLookFallback(
                                bvid = resolvedBvid,
                                aid = currentAid,
                                cid = cid,
                                epId = currentEpId,
                                qn = qn,
                                fnval = fnval,
                                constraints = playbackConstraints,
                            ).also { trace?.log("playurl:done") }
                        }
                    }.also(startupJobs::add)
                val dmJob =
                    async(Dispatchers.IO) {
                        trace?.log("danmakuMeta:start")
                        prepareDanmakuMeta(cid, currentAid ?: aid, trace)
                            .also { trace?.log("danmakuMeta:done", "segTotal=${it.segmentTotal} segMs=${it.segmentSizeMs}") }
                    }.also(startupJobs::add)

                val videoShotJob =
                    if (BiliClient.prefs.playerVideoShotPreviewSize != AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_OFF) {
                        async(Dispatchers.IO) {
                            trace?.log("videoShot:start")
                            runCatching {
                                BiliApi.videoShot(
                                    bvid = resolvedBvid,
                                    cid = cid,
                                    needJsonArrayIndex = true,
                                ).let { VideoShot.fromVideoShot(it) }
                            }.onFailure { t ->
                                AppLog.w("Player", "load videoShot failed bvid=$resolvedBvid cid=$cid", t)
                            }.getOrNull().also { result ->
                                currentVideoShot = result
                                videoShotImageCache = if (result != null) VideoShotImageCache() else null
                                trace?.log("videoShot:done", "ok=${result != null}")
                            }
                        }
                            .also(startupJobs::add)
                    } else {
                        trace?.log("videoShot:skip", "reason=pref_off")
                        null
                    }

                val subtitleSupported = engine.capabilities.subtitlesSupported
                val subJob =
                    if (subtitleSupported) {
                        async(Dispatchers.IO) {
                            trace?.log("subtitle:start")
                            prepareSubtitleConfig(detail, resolvedBvid, cid, trace)
                                .also { trace?.log("subtitle:done", "ok=${it != null}") }
                        }
                            .also(startupJobs::add)
                    } else {
                        null
                    }

                trace?.log("playurl:await")
                val (playStream, playable) = playJob.await().getOrThrow()
                trace?.log("playurl:awaitDone")
                playStream.durationMs?.let { durationMs ->
                    currentViewDurationMs = durationMs
                    trace?.log("duration:playurl", "duration=${durationMs}ms")
                }
                showRiskControlBypassHintIfNeeded(playStream)
                lastAvailableQns = parseDashVideoQnList(playStream)
                lastAvailableAudioIds = parseDashAudioIdList(playStream, constraints = playbackConstraints)
                logPlayUrlTrackSummary(source = "start", stream = playStream, constraints = playbackConstraints)
                if (subtitleSupported) {
                    trace?.log("subtitle:await")
                    subtitleConfig = subJob?.await()
                    trace?.log("subtitle:awaitDone", "ok=${subtitleConfig != null}")
                    subtitleAvailabilityKnown = true
                    subtitleAvailable = subtitleConfig != null
                } else {
                    subtitleConfig = null
                    subtitleAvailabilityKnown = true
                    subtitleAvailable = false
                }
                (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
                (engine as? ExoPlayerEngine)?.exoPlayer?.let { applySubtitleEnabled(it) }

                trace?.log("player:setSource:start", "kind=${engine.kind.prefValue}")
                if (engine.kind == PlayerEngineKind.IjkPlayer && playable !is Playable.Dash) {
                    startupJobs.forEach { it.cancel() }
                    AppToast.showLong(this@startPlayback, "IjkPlayer 内核仅支持 DASH（音视频分离）流，请切回 ExoPlayer")
                    return@launch
                }
                when (playable) {
                    is Playable.Dash -> {
                        lastPickedDash = playable
                        debug.cdnHost = runCatching { Uri.parse(playable.videoUrl).host }.getOrNull()
                        logPickedPlayable(source = "start", playable = playable)
                        engine.setSource(PlaybackSource.Vod(playable = playable, subtitle = subtitleConfig, durationMs = currentViewDurationMs))
                        applyResolutionFallbackIfNeeded(requestedQn = session.targetQn, actualQn = playable.qn)
                        applyAudioFallbackIfNeeded(requestedAudioId = session.targetAudioId, actualAudioId = playable.audioId)
                    }

                    is Playable.VideoOnly -> {
                        lastPickedDash = null
                        session = session.copy(actualAudioId = 0)
                        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
                        debug.cdnHost = runCatching { Uri.parse(playable.videoUrl).host }.getOrNull()
                        logPickedPlayable(source = "start", playable = playable)
                        engine.setSource(PlaybackSource.Vod(playable = playable, subtitle = subtitleConfig, durationMs = currentViewDurationMs))
                        applyResolutionFallbackIfNeeded(requestedQn = session.targetQn, actualQn = playable.qn)
                    }

                    is Playable.Progressive -> {
                        lastPickedDash = null
                        session = session.copy(actualAudioId = 0)
                        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
                        debug.cdnHost = runCatching { Uri.parse(playable.url).host }.getOrNull()
                        logPickedPlayable(source = "start", playable = playable)
                        engine.setSource(PlaybackSource.Vod(playable = playable, subtitle = subtitleConfig, durationMs = currentViewDurationMs))
                    }
                }
                trace?.log("player:setSource:done")
                schedulePlayUrlAutoRefresh(playable, reason = "start_playback")
                trace?.log("player:prepare")
                engine.prepare()
                trace?.log("player:playWhenReady")
                engine.playWhenReady = pendingPlayWhenReady ?: true
                if (pendingSeekMs != null && pendingSeekMs > 0L) {
                    engine.seekTo(pendingSeekMs)
                }
                updateSubtitleButton()
                maybeScheduleAutoResume(
                    playStream = playStream,
                    bvid = resolvedBvid,
                    cid = cid,
                    playbackToken = autoResumeToken,
                )
                maybeStartAutoSkipSegments(
                    playStream = playStream,
                    bvid = resolvedBvid,
                    cid = cid,
                    playbackToken = autoSkipToken,
                )

                trace?.log("danmakuMeta:await")
                val dmMeta = dmJob.await()
                trace?.log("danmakuMeta:awaitDone")
                applyDanmakuMeta(dmMeta)
                videoShotJob?.await()
                requestDanmakuSegmentsForPosition(engine.currentPosition.coerceAtLeast(0L), immediate = true)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) return@launch
                startupJobs.forEach { it.cancel() }
                AppLog.e("Player", "start failed", throwable)
                finishAfterStartPlaybackFailure(throwable)
            }
        }
}

internal fun PlayerActivity.updatePageListIndexForCurrentMedia(
    bvid: String,
    aid: Long?,
    cid: Long?,
) {
    val list = pageListItems
    if (list.isEmpty()) return
    val idx = pickPlaylistIndexForCurrentMedia(list, bvid = bvid, aid = aid, cid = cid)
    if (idx !in list.indices) return
    if (idx == pageListIndex) return
    pageListIndex = idx.coerceIn(0, list.lastIndex)
    pageListToken?.let { PlayerPlaylistStore.updateIndex(it, pageListIndex) }
}

internal suspend fun PlayerActivity.refreshPartsListFromDetail(detail: VideoDetail, bvid: String) {
    val safeBvid = bvid.trim()
    val aid = currentAid ?: detail.aid?.takeIf { it > 0 }
    val cid = currentCid.takeIf { it > 0 }
    partsListLoadMoreJob?.cancel()
    partsListLoadMoreJob = null
    partsListLoadMoreCallbacks.clear()

    if (isPgcLikePlayback()) {
        val existing = partsListItems
        if (existing.isNotEmpty()) {
            val idx = pickPlaylistIndexForCurrentMedia(existing, bvid = safeBvid, aid = aid, cid = cid)
            if (idx in existing.indices) {
                partsListIndex = idx
                return
            }
        }

        partsListSource = null
        partsListItems = emptyList()
        partsListUiCards = emptyList()
        partsListIndex = -1
        partsListContinuation = null

        val reused =
            tryApplyPgcPartsListFromBangumiPageList(
                requestBvid = safeBvid,
                requestAid = aid,
                requestCid = cid,
            )
        if (!reused) {
            schedulePgcPartsListFetch(
                requestBvid = safeBvid,
                requestAid = aid,
                requestCid = cid,
                requestEpId = currentEpId?.takeIf { it > 0L },
                requestSeasonId = currentSeasonId?.takeIf { it > 0L },
            )
        }
        return
    }

    partsListSource = null
    partsListItems = emptyList()
    partsListUiCards = emptyList()
    partsListIndex = -1
    partsListContinuation = null

    if (safeBvid.isBlank()) return

    val parsedMulti = parseMultiPagePlaylistFromDetailWithUiCards(detail, bvid = safeBvid, aid = aid)
    if (parsedMulti.items.size > 1) {
        val idx = pickPlaylistIndexForCurrentMedia(parsedMulti.items, bvid = safeBvid, aid = aid, cid = cid)
        val safeIndex = idx.takeIf { it in parsedMulti.items.indices } ?: 0
        applyPartsList(parsed = parsedMulti, index = safeIndex, source = "MultiPage")
        return
    }

    val ugcSeason = detail.ugcSeason ?: return
    val seasonId = ugcSeason.id?.takeIf { it > 0 } ?: return
    val totalFromView = ugcSeason.epCount?.takeIf { it > 0 }
    val mid = ugcSeason.ownerMid?.takeIf { it > 0 }

    val parsedFromView = parseUgcSeasonPlaylistFromDetailWithUiCards(ugcSeason)
    val idxFromView = pickPlaylistIndexForCurrentMedia(parsedFromView.items, bvid = safeBvid, aid = aid, cid = cid)
    if (idxFromView >= 0) {
        val continuation =
            mid?.let { ownerMid ->
                val hasMore = totalFromView?.let { parsedFromView.items.size < it } ?: parsedFromView.items.isNotEmpty()
                buildUgcSeasonPartsContinuation(
                    seedCards = parsedFromView.uiCards,
                    mid = ownerMid,
                    seasonId = seasonId,
                    nextPage = 1,
                    hasMore = hasMore,
                )
            }
        applyPartsList(parsed = parsedFromView, index = idxFromView, source = "UgcSeason", continuation = continuation)
        return
    }

    val safeMid = mid ?: return

    val archivesPage =
        withContext(Dispatchers.IO) {
            runCatching { BiliApi.ugcSeasonArchives(mid = safeMid, seasonId = seasonId, pageSize = 200) }.getOrNull()
        } ?: return

    val parsedFromApi = parseVideoCardsToPlaylistParsed(archivesPage.items, ::videoCardToPlaylistItem)
    val idxFromApi = pickPlaylistIndexForCurrentMedia(parsedFromApi.items, bvid = safeBvid, aid = aid, cid = cid)
    if (idxFromApi >= 0) {
        val totalFromApi = archivesPage.totalCount
        val continuation =
            buildUgcSeasonPartsContinuation(
                seedCards = parsedFromApi.uiCards,
                mid = safeMid,
                seasonId = seasonId,
                nextPage = 2,
                hasMore = totalFromApi?.let { parsedFromApi.items.size < it } ?: (parsedFromApi.items.size >= 200),
            )
        applyPartsList(parsed = parsedFromApi, index = idxFromApi, source = "UgcSeason", continuation = continuation)
    }
}

private fun PlayerActivity.buildUgcSeasonPartsContinuation(
    seedCards: List<VideoCard>,
    mid: Long,
    seasonId: Long,
    nextPage: Int,
    hasMore: Boolean,
): PlayerPlaylistContinuation? {
    return buildFreshVideoCardPlaylistContinuation(
        seedCards = seedCards,
        nextCursor = nextPage.coerceAtLeast(1),
        hasMore = hasMore,
        playlistItemFactory = { card ->
            PlayerPlaylistItem(
                bvid = card.bvid,
                cid = card.cid,
                aid = card.aid,
                title = card.title,
            )
        },
    ) { pageNum ->
        val safePageNum = pageNum.coerceAtLeast(1)
        val archivesPage = BiliApi.ugcSeasonArchives(mid = mid, seasonId = seasonId, pageNum = safePageNum, pageSize = 200)
        val parsed = parseVideoCardsToPlaylistParsed(archivesPage.items, ::videoCardToPlaylistItem)
        val totalCount = archivesPage.totalCount
        val hasNext = totalCount?.let { safePageNum * 200 < it } ?: (parsed.uiCards.size >= 200)
        VideoCardPlaylistPage(
            cards = parsed.uiCards,
            nextCursor = safePageNum + 1,
            hasMore = hasNext,
            canAdvance = hasNext && parsed.uiCards.isNotEmpty(),
        )
    }
}

private fun videoCardToPlaylistItem(card: VideoCard): PlayerPlaylistItem =
    PlayerPlaylistItem(
        bvid = card.bvid,
        cid = card.cid,
        aid = card.aid,
        title = card.title,
    )

private fun PlayerActivity.tryApplyPgcPartsListFromBangumiPageList(
    requestBvid: String,
    requestAid: Long?,
    requestCid: Long?,
): Boolean {
    val src = pageListSource?.trim().orEmpty()
    if (!src.startsWith("Bangumi:")) return false
    val items = pageListItems
    if (items.isEmpty()) return false

    val idxFromCurrent = pickPlaylistIndexForCurrentMedia(items, bvid = requestBvid, aid = requestAid, cid = requestCid)
    val idx =
        when {
            idxFromCurrent in items.indices -> idxFromCurrent
            pageListIndex in items.indices -> pageListIndex
            else -> 0
        }
    val uiCards =
        pageListUiCards
            .takeIf { it.isNotEmpty() && it.size == items.size }
            ?: emptyList()
    applyPartsList(parsed = PlaylistParsed(items = items, uiCards = uiCards), index = idx, source = src)
    return partsListItems.isNotEmpty() && partsListIndex in partsListItems.indices
}

private data class PgcPartsListResolved(
    val seasonId: Long,
    val parsed: PlaylistParsed,
    val index: Int,
    val source: String,
)

private fun PlayerActivity.schedulePgcPartsListFetch(
    requestBvid: String,
    requestAid: Long?,
    requestCid: Long?,
    requestEpId: Long?,
    requestSeasonId: Long?,
) {
    val safeEpId = requestEpId?.takeIf { it > 0L }
    val safeSeasonId = requestSeasonId?.takeIf { it > 0L }
    if (safeEpId == null && safeSeasonId == null) return

    partsListFetchJob?.cancel()
    val token = ++partsListFetchToken
    partsListFetchJob =
        lifecycleScope.launch {
            try {
                val detail =
                    withContext(Dispatchers.IO) {
                        if (safeSeasonId != null) {
                            BiliApi.bangumiSeasonDetail(seasonId = safeSeasonId)
                        } else {
                            BiliApi.bangumiSeasonDetailByEpId(epId = safeEpId ?: 0L)
                        }
                    }
                if (token != partsListFetchToken) return@launch
                if (safeEpId != null && currentEpId != safeEpId) return@launch

                val resolved =
                    resolvePgcPartsListFromSeasonDetail(
                        detail = detail,
                        requestEpId = safeEpId,
                        requestBvid = requestBvid,
                        requestAid = requestAid,
                        requestCid = requestCid,
                    ) ?: return@launch

                if (token != partsListFetchToken) return@launch
                if (safeEpId != null && currentEpId != safeEpId) return@launch

                if (resolved.seasonId > 0L) {
                    if (currentSeasonId == null || currentSeasonId == 0L) {
                        currentSeasonId = resolved.seasonId
                    }
                }

                applyPartsList(parsed = resolved.parsed, index = resolved.index, source = resolved.source)
                updatePlaylistControls()
                notifyPartsListPanelChanged()
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                if (token != partsListFetchToken) return@launch
                if (safeEpId != null && currentEpId != safeEpId) return@launch
                AppLog.w("Player", "pgc:partsList:load_failed", t)
                val e = t as? BiliApiException
                val msg = e?.apiMessage?.takeIf { it.isNotBlank() } ?: (t.message ?: "加载剧集列表失败")
                if (isBottomCardPanelVisible() && bottomCardPanelKind == PlayerVideoListKind.PARTS) {
                    AppToast.show(this@schedulePgcPartsListFetch, msg)
                }
            } finally {
                if (token == partsListFetchToken) partsListFetchJob = null
                if (token == partsListFetchToken) notifyPartsListPanelChanged()
            }
        }
    notifyPartsListPanelChanged()
}

private fun PlayerActivity.resolvePgcPartsListFromSeasonDetail(
    detail: BangumiSeasonDetail,
    requestEpId: Long?,
    requestBvid: String,
    requestAid: Long?,
    requestCid: Long?,
): PgcPartsListResolved? {
    val seasonId = detail.seasonId.takeIf { it > 0L } ?: return null

    val extras =
        buildList {
            detail.extraSections.forEach { section -> addAll(section.episodes) }
        }
    val inExtras = requestEpId != null && extras.any { it.epId == requestEpId }
    val useExtras = inExtras && extras.isNotEmpty()
    val picked = if (useExtras) extras else detail.episodes
    val listKind = if (useExtras) "extra" else "main"
    if (picked.isEmpty()) return null

    val items = ArrayList<PlayerPlaylistItem>(picked.size)
    val cards = ArrayList<VideoCard>(picked.size)
    for (i in picked.indices) {
        val ep = picked[i]
        val cid = ep.cid?.takeIf { it > 0L } ?: continue
        val bvid = ep.bvid?.trim().orEmpty()
        val aid = ep.aid?.takeIf { it > 0L }
        val epId = ep.epId.takeIf { it > 0L }
        if (bvid.isBlank() && aid == null) continue

        val card =
            bangumiEpToPartsVideoCard(
                ep = ep,
                defaultIndex = i,
                isExtrasList = useExtras,
            )
        items.add(
            PlayerPlaylistItem(
                bvid = bvid,
                cid = cid,
                epId = epId,
                aid = aid,
                title = card.title,
                seasonId = seasonId,
            ),
        )
        cards.add(card)
    }
    if (items.isEmpty()) return null

    val idxFromMedia = pickPlaylistIndexForCurrentMedia(items, bvid = requestBvid, aid = requestAid, cid = requestCid)
    val idxFromEpId = requestEpId?.let { id -> items.indexOfFirst { it.epId == id } } ?: -1
    val index =
        when {
            idxFromMedia in items.indices -> idxFromMedia
            idxFromEpId in items.indices -> idxFromEpId
            else -> 0
        }

    return PgcPartsListResolved(
        seasonId = seasonId,
        parsed = PlaylistParsed(items = items, uiCards = cards),
        index = index.coerceIn(0, items.lastIndex),
        source = "Bangumi:$seasonId:$listKind",
    )
}

private val PGC_EP_INDEX_ONLY_REGEX = Regex("^\\d+(?:\\.\\d+)?$")
private val PGC_EP_NUMBER_REGEX = Regex("\\d+(?:\\.\\d+)?")

private fun parsePgcEpisodeNumber(raw: String?): Double? {
    val s = raw?.trim().orEmpty()
    if (s.isBlank()) return null
    s.toDoubleOrNull()?.let { return it }
    val match = PGC_EP_NUMBER_REGEX.find(s) ?: return null
    return match.value.toDoubleOrNull()
}

private fun formatPgcEpisodeNumber(number: Double): String {
    val asLong = number.toLong()
    if (number == asLong.toDouble()) return asLong.toString()
    return number.toString()
}

private fun bangumiEpToPartsVideoCard(
    ep: BangumiEpisode,
    defaultIndex: Int,
    isExtrasList: Boolean,
): VideoCard {
    val rawTitle = ep.title.trim().takeIf { it.isNotBlank() } ?: "-"
    val episodeNumberText =
        when {
            PGC_EP_INDEX_ONLY_REGEX.matches(rawTitle) -> rawTitle
            else -> parsePgcEpisodeNumber(rawTitle)?.let { formatPgcEpisodeNumber(it) }
        } ?: parsePgcEpisodeNumber(ep.longTitle)?.let { formatPgcEpisodeNumber(it) }
            ?: (defaultIndex + 1).toString()

    val longTitle = ep.longTitle.trim().takeIf { it.isNotBlank() }
    val fallbackTitle =
        if (PGC_EP_INDEX_ONLY_REGEX.matches(rawTitle)) {
            "第${rawTitle}话"
        } else {
            rawTitle
        }
    val title = longTitle ?: fallbackTitle.takeIf { it.isNotBlank() } ?: "第${defaultIndex + 1}集"

    return VideoCard(
        bvid = ep.bvid.orEmpty(),
        cid = ep.cid,
        aid = ep.aid,
        epId = ep.epId,
        title = title,
        coverUrl = ep.coverUrl.orEmpty(),
        durationSec = 0,
        ownerName = "",
        ownerFace = null,
        ownerMid = null,
        view = null,
        danmaku = null,
        pubDate = null,
        pubDateText = null,
        coverLeftBottomText = episodeNumberText.takeIf { !isExtrasList },
        accessBadgeText = pgcAccessBadgeTextOf(ep.badge),
    )
}

private fun PlayerActivity.applyPartsList(
    parsed: PlaylistParsed,
    index: Int,
    source: String,
    continuation: PlayerPlaylistContinuation? = null,
) {
    val items = parsed.items
    if (items.isEmpty() || index !in items.indices) return
    val uiCards =
        parsed.uiCards
            .takeIf { it.isNotEmpty() && it.size == items.size }
            ?: emptyList()
    partsListSource = source
    partsListItems = items
    partsListUiCards = uiCards
    partsListIndex = index
    partsListContinuation = continuation
}

internal fun PlayerActivity.handlePlaybackEnded(engine: BlblPlayerEngine) {
    val now = android.os.SystemClock.uptimeMillis()
    if (now - lastEndedActionAtMs < 350) return
    lastEndedActionAtMs = now

    val mode = resolvedPlaybackMode()

    when (mode) {
        AppPrefs.PLAYER_PLAYBACK_MODE_NONE -> Unit

        AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> {
            restartCurrentPlaybackFromBeginning(engine = engine, showControls = false, showHint = false)
        }

        AppPrefs.PLAYER_PLAYBACK_MODE_EXIT -> finish()

        AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST,
        AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST,
        AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST_THEN_RECOMMEND,
        AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND,
        -> {
            if (autoNextCancelledByUser) {
                trace?.log("autonext:ended", "action=stay mode=$mode")
                return
            }

            // When any OSD/panels are visible, do NOT auto-next and do NOT show the hint.
            // We only show "即将播放 ..." and start the auto-next countdown window after the UI is fully closed.
            if (isAutoNextUiBlocked()) {
                armAutoNextAfterEnded(reason = "ended_ui_blocked")
                pauseAutoNextAfterEnded(reason = "ended_ui_blocked")
                trace?.log("autonext:ended", "action=defer mode=$mode reason=ui_blocked")
                return
            }

            // If the hint was already shown during playback, keep the old behavior: transition immediately.
            // Otherwise, show it now and delay by the auto-next countdown window so BACK can cancel.
            if (autoNextHintVisible) {
                val target = autoNextPending ?: resolveAutoNextTargetByPlaybackMode(preloadRecommendation = false)
                if (target != null) {
                    trace?.log("autonext:ended", "action=play mode=$mode target=${target.javaClass.simpleName}")
                    playAutoNextTarget(target)
                } else {
                    playNextByPlaybackMode(userInitiated = false)
                }
                return
            }

            armAutoNextAfterEnded(reason = "ended_no_hint")
            maybeStartAutoNextAfterEndedCountdown()
        }

        else -> Unit
    }
}

internal fun PlayerActivity.applyPerVideoPreferredQn(detail: VideoDetail, cid: Long) {
    val prefs = BiliClient.prefs

    val dim = detail.pages.firstOrNull { it.cid == cid }?.dimension ?: detail.dimension

    val width = dim?.width ?: 0
    val height = dim?.height ?: 0
    val rotate = dim?.rotate ?: 0
    val (effectiveW, effectiveH) =
        if (rotate == 1) {
            height to width
        } else {
            width to height
        }

    val isPortraitVideo = (effectiveW > 0 && effectiveH > 0 && effectiveH > effectiveW)
    currentVideoIsPortrait = isPortraitVideo
    val preferredQn = if (isPortraitVideo) prefs.playerPreferredQnPortrait else prefs.playerPreferredQn
    if (session.preferredQn != preferredQn) {
        session = session.copy(preferredQn = preferredQn)
    }
}

internal fun PlayerActivity.handlePlayUrlErrorIfNeeded(t: Throwable): Boolean {
    val e = t as? BiliApiException ?: return false
    if (!isRiskControl(e)) return false
    showRiskControlUserHintOnce()
    return true
}

internal fun PlayerActivity.showRiskControlBypassHintIfNeeded(stream: VideoPlayStream) {
    if (stream.riskControl?.bypassed != true) return
    showRiskControlUserHintOnce()
}

internal fun PlayerActivity.isRiskControl(e: BiliApiException): Boolean {
    if (e.apiCode == -412 || e.apiCode == -352) return true
    val m = e.apiMessage
    return m.contains("风控") || m.contains("拦截") || m.contains("风险")
}

private fun PlayerActivity.showRiskControlUserHintOnce() {
    if (!riskControlUserHintShown.compareAndSet(false, true)) return
    AppToast.showLong(this, RISK_CONTROL_USER_HINT)
}
