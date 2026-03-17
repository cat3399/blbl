package blbl.cat3399.feature.player

import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.core.api.BiliApi
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONObject
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.prefs.AppPrefs
import kotlinx.coroutines.withContext

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
    subtitleAvailabilityKnown = false
    subtitleAvailable = false
    subtitleConfig = null
    subtitleItems = emptyList()
    currentUpMid = 0L
    currentUpName = null
    currentUpAvatar = null
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
        partsListFetchToken++
        partsListSource = null
        partsListItems = emptyList()
        partsListUiCards = emptyList()
        partsListIndex = -1
    }

    relatedVideosFetchJob?.cancel()
    relatedVideosFetchJob = null
    relatedVideosFetchToken++
    relatedVideosCache = null

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

    binding.settingsPanel.visibility = View.GONE
    binding.commentsPanel.visibility = View.GONE
    hideBottomCardPanel(restoreFocus = false)
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

    updatePlaylistControls()

    val handler =
        playbackUncaughtHandler
            ?: CoroutineExceptionHandler { _, throwable ->
                AppLog.e("Player", "uncaught", throwable)
                AppToast.showLong(this@startPlayback, "播放失败：${throwable.message}")
                finish()
            }

    loadJob =
        lifecycleScope.launch(handler) {
            try {
                trace?.log("view:start")
                val viewJson =
                    async(Dispatchers.IO) {
                        runCatching {
                            if (safeBvid.isNotBlank()) {
                                BiliApi.view(safeBvid)
                            } else {
                                BiliApi.view(safeAid ?: 0L)
                            }
                        }.getOrNull()
                    }
                val viewData = viewJson.await()?.optJSONObject("data") ?: JSONObject()
                trace?.log("view:done")

                val bangumiRedirect = parseBangumiRedirectUrl(viewData.optString("redirect_url", ""))
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

                val title = viewData.optString("title", "").trim()
                if (title.isNotBlank()) currentMainTitle = title
                updateTopTitleUi(placeholder = initialTitle)
                currentViewDurationMs = viewData.optLong("duration", -1L).takeIf { it > 0 }?.times(1000L)
                applyUpInfo(viewData)
                applyTitleMeta(viewData)

                val resolvedBvid =
                    viewData.optString("bvid", "").trim().takeIf { it.isNotBlank() }
                        ?: safeBvid
                if (resolvedBvid.isNotBlank()) currentBvid = resolvedBvid

                val cid = cidExtra ?: viewData.optLong("cid").takeIf { it > 0 } ?: error("cid missing")
                val aid = viewData.optLong("aid").takeIf { it > 0 }
                currentAid = currentAid ?: aid ?: safeAid
                currentCid = cid
                refreshActionButtonStatesFromServer(bvid = resolvedBvid, aid = currentAid)
                if (isCommentsPanelVisible() && !isCommentThreadVisible()) ensureCommentsLoaded()
                AppLog.i("Player", "start bvid=$resolvedBvid cid=$cid")
                trace?.log("cid:resolved", "cid=$cid aid=${aid ?: -1}")

                if (startFromList != PlayerVideoListKind.PARTS || partsListItems.isEmpty() || partsListIndex !in partsListItems.indices) {
                    refreshPartsListFromView(viewData, bvid = resolvedBvid)
                    updateTopTitleUi(placeholder = initialTitle)
                }
                if (startFromList == PlayerVideoListKind.PAGE) {
                    updatePageListIndexForCurrentMedia(bvid = resolvedBvid, aid = currentAid, cid = cid)
                }
                updatePlaylistControls()

                requestOnlineWatchingText(bvid = resolvedBvid, cid = cid)
                applyPerVideoPreferredQn(viewData, cid = cid)

                val playJob =
                    async {
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
                val dmJob =
                    async(Dispatchers.IO) {
                        trace?.log("danmakuMeta:start")
                        prepareDanmakuMeta(cid, currentAid ?: aid, trace)
                            .also { trace?.log("danmakuMeta:done", "segTotal=${it.segmentTotal} segMs=${it.segmentSizeMs}") }
                    }

                val videoShotJob =
                    async(Dispatchers.IO) {
                        trace?.log("videoShot:start")
                        runCatching {
                            BiliApi.getWebVideoShot(bvid = resolvedBvid, cid = cid)
                                .let { VideoShot.fromVideoShot(it) }
                        }.onFailure { t ->
                            AppLog.w("Player", "load videoShot failed bvid=$resolvedBvid cid=$cid", t)
                        }.getOrNull().also { result ->
                            currentVideoShot = result
                            videoShotImageCache = VideoShotImageCache()
                            trace?.log("videoShot:done", "ok=${result != null}")
                        }
                    }

                val subtitleSupported = engine.capabilities.subtitlesSupported
                val subJob =
                    if (subtitleSupported) {
                        async(Dispatchers.IO) {
                            trace?.log("subtitle:start")
                            prepareSubtitleConfig(viewData, resolvedBvid, cid, trace)
                                .also { trace?.log("subtitle:done", "ok=${it != null}") }
                        }
                    } else {
                        null
                    }

                trace?.log("playurl:await")
                val (playJson, playable) = playJob.await()
                trace?.log("playurl:awaitDone")
                showRiskControlBypassHintIfNeeded(playJson)
                lastAvailableQns = parseDashVideoQnList(playJson)
                lastAvailableAudioIds = parseDashAudioIdList(playJson, constraints = playbackConstraints)
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
                    AppToast.showLong(this@startPlayback, "IjkPlayer 内核仅支持 DASH（音视频分离）流，请切回 ExoPlayer")
                    return@launch
                }
                when (playable) {
                    is Playable.Dash -> {
                        if (engine.kind == PlayerEngineKind.IjkPlayer) {
                            if (playable.videoTrackInfo.segmentBase == null || playable.audioTrackInfo.segmentBase == null) {
                                AppToast.showLong(this@startPlayback, "IjkPlayer 播放 DASH 需要 segment_base（initialization/index_range），当前流缺失，请切回 ExoPlayer")
                                return@launch
                            }
                        }
                        lastPickedDash = playable
                        debug.cdnHost = runCatching { Uri.parse(playable.videoUrl).host }.getOrNull()
                        AppLog.i(
                            "Player",
                            "picked DASH qn=${playable.qn} codecid=${playable.codecid} dv=${playable.isDolbyVision} a=${playable.audioKind}(${playable.audioId}) video=${playable.videoUrl}",
                        )
                        engine.setSource(PlaybackSource.Vod(playable = playable, subtitle = subtitleConfig, durationMs = currentViewDurationMs))
                        applyResolutionFallbackIfNeeded(requestedQn = session.targetQn, actualQn = playable.qn)
                        applyAudioFallbackIfNeeded(requestedAudioId = session.targetAudioId, actualAudioId = playable.audioId)
                    }

                    is Playable.VideoOnly -> {
                        lastPickedDash = null
                        session = session.copy(actualAudioId = 0)
                        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
                        debug.cdnHost = runCatching { Uri.parse(playable.videoUrl).host }.getOrNull()
                        AppLog.i(
                            "Player",
                            "picked VideoOnly qn=${playable.qn} codecid=${playable.codecid} dv=${playable.isDolbyVision} video=${playable.videoUrl}",
                        )
                        engine.setSource(PlaybackSource.Vod(playable = playable, subtitle = subtitleConfig, durationMs = currentViewDurationMs))
                        applyResolutionFallbackIfNeeded(requestedQn = session.targetQn, actualQn = playable.qn)
                    }

                    is Playable.Progressive -> {
                        lastPickedDash = null
                        session = session.copy(actualAudioId = 0)
                        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
                        debug.cdnHost = runCatching { Uri.parse(playable.url).host }.getOrNull()
                        AppLog.i("Player", "picked Progressive url=${playable.url}")
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
                    playJson = playJson,
                    bvid = resolvedBvid,
                    cid = cid,
                    playbackToken = autoResumeToken,
                )
                maybeStartAutoSkipSegments(
                    playJson = playJson,
                    bvid = resolvedBvid,
                    cid = cid,
                    playbackToken = autoSkipToken,
                )

                trace?.log("danmakuMeta:await")
                val dmMeta = dmJob.await()
                trace?.log("danmakuMeta:awaitDone")
                applyDanmakuMeta(dmMeta)
                videoShotJob.await()
                requestDanmakuSegmentsForPosition(engine.currentPosition.coerceAtLeast(0L), immediate = true)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) return@launch
                AppLog.e("Player", "start failed", throwable)
                if (!handlePlayUrlErrorIfNeeded(throwable)) {
                    AppToast.showLong(this@startPlayback, "加载播放信息失败：${throwable.message}")
                }
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

internal suspend fun PlayerActivity.refreshPartsListFromView(viewData: JSONObject, bvid: String) {
    val safeBvid = bvid.trim()
    val aid = currentAid ?: viewData.optLong("aid").takeIf { it > 0 }
    val cid = currentCid.takeIf { it > 0 }

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

    if (safeBvid.isBlank()) return

    val parsedMulti = parseMultiPagePlaylistFromViewWithUiCards(viewData, bvid = safeBvid, aid = aid)
    if (parsedMulti.items.size > 1) {
        val idx = pickPlaylistIndexForCurrentMedia(parsedMulti.items, bvid = safeBvid, aid = aid, cid = cid)
        val safeIndex = idx.takeIf { it in parsedMulti.items.indices } ?: 0
        applyPartsList(parsed = parsedMulti, index = safeIndex, source = "MultiPage")
        return
    }

    val ugcSeason = viewData.optJSONObject("ugc_season") ?: return
    val seasonId = ugcSeason.optLong("id").takeIf { it > 0 } ?: return

    val parsedFromView = parseUgcSeasonPlaylistFromViewWithUiCards(ugcSeason)
    val idxFromView = pickPlaylistIndexForCurrentMedia(parsedFromView.items, bvid = safeBvid, aid = aid, cid = cid)
    if (idxFromView >= 0) {
        applyPartsList(parsed = parsedFromView, index = idxFromView, source = "UgcSeason")
        return
    }

    val mid =
        ugcSeason.optLong("mid").takeIf { it > 0 }
            ?: viewData.optJSONObject("owner")?.optLong("mid")?.takeIf { it > 0 }
            ?: return

    val json =
        withContext(Dispatchers.IO) {
            runCatching { BiliApi.seasonsArchivesList(mid = mid, seasonId = seasonId, pageSize = 200) }.getOrNull()
        } ?: return

    val parsedFromApi = parseUgcSeasonPlaylistFromArchivesListWithUiCards(json)
    val idxFromApi = pickPlaylistIndexForCurrentMedia(parsedFromApi.items, bvid = safeBvid, aid = aid, cid = cid)
    if (idxFromApi >= 0) {
        applyPartsList(parsed = parsedFromApi, index = idxFromApi, source = "UgcSeason")
    }
}

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

private fun PlayerActivity.applyPartsList(parsed: PlaylistParsed, index: Int, source: String) {
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
}

internal fun PlayerActivity.handlePlaybackEnded(engine: BlblPlayerEngine) {
    val now = android.os.SystemClock.uptimeMillis()
    if (now - lastEndedActionAtMs < 350) return
    lastEndedActionAtMs = now

    when (resolvedPlaybackMode()) {
        AppPrefs.PLAYER_PLAYBACK_MODE_NONE -> Unit

        AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> {
            engine.seekTo(0)
            engine.playWhenReady = true
            engine.play()
        }

        AppPrefs.PLAYER_PLAYBACK_MODE_EXIT -> finish()

        AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST -> playNext(userInitiated = false)

        AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST -> playPartsNext(userInitiated = false)

        AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND -> {
            playRecommendedNext(userInitiated = false)
        }

        else -> Unit
    }
}

internal fun PlayerActivity.applyPerVideoPreferredQn(viewData: JSONObject, cid: Long) {
    val prefs = BiliClient.prefs

    var dim: JSONObject? = null
    val pages = viewData.optJSONArray("pages")
    if (pages != null) {
        for (i in 0 until pages.length()) {
            val page = pages.optJSONObject(i) ?: continue
            if (page.optLong("cid") != cid) continue
            dim = page.optJSONObject("dimension")
            if (dim != null) break
        }
    }
    dim = dim ?: viewData.optJSONObject("dimension")

    val width = dim?.optInt("width", 0) ?: 0
    val height = dim?.optInt("height", 0) ?: 0
    val rotate = dim?.optInt("rotate", 0) ?: 0
    val (effectiveW, effectiveH) =
        if (rotate == 1) {
            height to width
        } else {
            width to height
        }

    val isPortraitVideo = (effectiveW > 0 && effectiveH > 0 && effectiveH > effectiveW)
    val preferredQn = if (isPortraitVideo) prefs.playerPreferredQnPortrait else prefs.playerPreferredQn
    if (session.preferredQn != preferredQn) {
        session = session.copy(preferredQn = preferredQn)
    }
}

internal fun PlayerActivity.handlePlayUrlErrorIfNeeded(t: Throwable): Boolean {
    val e = t as? BiliApiException ?: return false
    if (!isRiskControl(e)) return false

    val prefs = BiliClient.prefs
    val savedVoucher = prefs.gaiaVgateVVoucher
    val savedAt = prefs.gaiaVgateVVoucherSavedAtMs
    val hasSavedVoucher = !savedVoucher.isNullOrBlank()

    // Keep this non-blocking: never show modal dialogs or auto-jump away from playback.
    // Users can choose to go to Settings manually if needed.
    val msg =
        buildString {
            append("B 站返回：").append(e.apiCode).append(" / ").append(e.apiMessage)
            if (e.apiCode == -352 && hasSavedVoucher) {
                append("\n")
                append("已记录 v_voucher，可到“设置 -> 风控验证”手动完成人机验证后重试。")
                if (savedAt > 0L) {
                    append("\n")
                    append("记录时间：").append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", savedAt))
                }
            } else {
                append("\n")
                append("可能触发风控，建议重新登录或稍后重试。")
            }
        }
    AppToast.showLong(this, msg)
    return true
}

internal fun PlayerActivity.showRiskControlBypassHintIfNeeded(playJson: JSONObject) {
    if (riskControlBypassHintShown) return
    if (!playJson.optBoolean("__blbl_risk_control_bypassed", false)) return
    riskControlBypassHintShown = true

    val code = playJson.optInt("__blbl_risk_control_code", 0)
    val message = playJson.optString("__blbl_risk_control_message", "")
    val msg =
        buildString {
            append("B 站返回：").append(code).append(" / ").append(message)
            append("\n\n")
            append("你的账号或网络环境可能触发风控，建议重新登录或稍后重试。")
            append("\n")
            append("如持续出现，请向作者反馈日志。")
        }
    AppToast.showLong(this, msg)
}

internal fun PlayerActivity.isRiskControl(e: BiliApiException): Boolean {
    if (e.apiCode == -412 || e.apiCode == -352) return true
    val m = e.apiMessage
    return m.contains("风控") || m.contains("拦截") || m.contains("风险")
}
