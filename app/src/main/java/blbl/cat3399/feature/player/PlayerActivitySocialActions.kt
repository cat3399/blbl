package blbl.cat3399.feature.player

import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.popup.AppPopup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import blbl.cat3399.core.model.VideoCard

internal fun PlayerActivity.applyOsdButtonsVisibility() {
    val enabled = BiliClient.prefs.playerOsdButtons.toSet()
    val subtitleSupported = player?.capabilities?.subtitlesSupported ?: true

    binding.btnPrev.visibility = if (enabled.contains(AppPrefs.PLAYER_OSD_BTN_PREV)) View.VISIBLE else View.GONE
    binding.btnPlayPause.visibility = View.VISIBLE
    binding.btnNext.visibility = if (enabled.contains(AppPrefs.PLAYER_OSD_BTN_NEXT)) View.VISIBLE else View.GONE
    binding.btnSubtitle.visibility = if (subtitleSupported && enabled.contains(AppPrefs.PLAYER_OSD_BTN_SUBTITLE)) View.VISIBLE else View.GONE
    binding.btnDanmaku.visibility = if (enabled.contains(AppPrefs.PLAYER_OSD_BTN_DANMAKU)) View.VISIBLE else View.GONE
    binding.btnComments.visibility = if (enabled.contains(AppPrefs.PLAYER_OSD_BTN_COMMENTS)) View.VISIBLE else View.GONE
    binding.btnDetail.visibility = if (enabled.contains(AppPrefs.PLAYER_OSD_BTN_DETAIL)) View.VISIBLE else View.GONE
    binding.btnUp.visibility = if (enabled.contains(AppPrefs.PLAYER_OSD_BTN_UP)) View.VISIBLE else View.GONE
    binding.btnLike.visibility = if (enabled.contains(AppPrefs.PLAYER_OSD_BTN_LIKE)) View.VISIBLE else View.GONE
    binding.btnCoin.visibility = if (enabled.contains(AppPrefs.PLAYER_OSD_BTN_COIN)) View.VISIBLE else View.GONE
    binding.btnFav.visibility = if (enabled.contains(AppPrefs.PLAYER_OSD_BTN_FAV)) View.VISIBLE else View.GONE
    binding.btnListPanel.visibility = if (enabled.contains(AppPrefs.PLAYER_OSD_BTN_LIST_PANEL)) View.VISIBLE else View.GONE
    binding.btnAdvanced.visibility = if (enabled.contains(AppPrefs.PLAYER_OSD_BTN_ADVANCED)) View.VISIBLE else View.GONE
    if (binding.btnLike.visibility != View.VISIBLE) cancelLikeButtonHoldGesture(resetTriggered = true)

    updateActionButtonsUi()
    updatePlaylistControls()

    // Focus fallback: avoid leaving focus on a hidden control after returning from Settings.
    val focused = currentFocus
    if (focused != null && focused.visibility != View.VISIBLE) {
        binding.btnPlayPause.post { focusFirstControl() }
    }
}

internal fun PlayerActivity.updateActionButtonsUi() {
    updateLikeButtonUi()
    updateCoinButtonUi()
    updateFavButtonUi()
}

internal fun PlayerActivity.updateLikeButtonUi() {
    val active = actionLiked
    val colorRes = if (active) R.color.blbl_blue else R.color.blbl_text
    binding.btnLike.imageTintList = ContextCompat.getColorStateList(this, colorRes)
    binding.btnLike.isEnabled = true
    binding.btnLike.alpha = 1.0f
}

internal fun PlayerActivity.updateCoinButtonUi() {
    val active = actionCoinCount > 0
    val colorRes = if (active) R.color.blbl_blue else R.color.blbl_text
    binding.btnCoin.imageTintList = ContextCompat.getColorStateList(this, colorRes)
    binding.btnCoin.isEnabled = true
    binding.btnCoin.alpha = 1.0f
}

internal fun PlayerActivity.updateFavButtonUi() {
    val active = actionFavored
    val colorRes = if (active) R.color.blbl_blue else R.color.blbl_text
    binding.btnFav.imageTintList = ContextCompat.getColorStateList(this, colorRes)
    binding.btnFav.isEnabled = true
    binding.btnFav.alpha = 1.0f
}

internal fun PlayerActivity.refreshActionButtonStatesFromServer(
    bvid: String,
    aid: Long?,
) {
    val enabled = BiliClient.prefs.playerOsdButtons.toSet()
    if (
        !enabled.contains(AppPrefs.PLAYER_OSD_BTN_LIKE) &&
        !enabled.contains(AppPrefs.PLAYER_OSD_BTN_COIN) &&
        !enabled.contains(AppPrefs.PLAYER_OSD_BTN_FAV)
    ) {
        return
    }

    if (!BiliClient.cookies.hasSessData()) return
    val requestBvid = bvid.trim().takeIf { it.isNotBlank() } ?: return
    val requestAid = aid?.takeIf { it > 0L }

    socialStateFetchJob?.cancel()
    val token = ++socialStateFetchToken
    val baselineLiked = actionLiked
    val baselineCoinCount = actionCoinCount
    val baselineFavored = actionFavored

    socialStateFetchJob =
        lifecycleScope.launch {
            try {
                val (liked, coins, favoured) =
                    withContext(Dispatchers.IO) {
                        coroutineScope {
                            val likedJob =
                                async {
                                    runCatching { BiliApi.archiveHasLike(bvid = requestBvid, aid = requestAid) }.getOrNull()
                                }
                            val coinsJob =
                                async {
                                    runCatching { BiliApi.archiveCoins(bvid = requestBvid, aid = requestAid) }.getOrNull()
                                }
                            val favouredJob =
                                async {
                                    runCatching { BiliApi.archiveFavoured(bvid = requestBvid, aid = requestAid) }.getOrNull()
                                }
                            Triple(likedJob.await(), coinsJob.await(), favouredJob.await())
                        }
                    }

                if (token != socialStateFetchToken) return@launch
                if (currentBvid != requestBvid) return@launch
                if (requestAid != null && currentAid != requestAid) return@launch

                var changed = false
                liked?.let { value ->
                    if (tripleActionJob?.isActive != true && likeActionJob?.isActive != true && actionLiked == baselineLiked) {
                        actionLiked = value
                        changed = true
                    }
                }
                coins?.let { value ->
                    if (tripleActionJob?.isActive != true && coinActionJob?.isActive != true && actionCoinCount == baselineCoinCount) {
                        actionCoinCount = value.coerceIn(0, 2)
                        changed = true
                    }
                }
                favoured?.let { value ->
                    if (
                        tripleActionJob?.isActive != true &&
                        favDialogJob?.isActive != true &&
                        favApplyJob?.isActive != true &&
                        actionFavored == baselineFavored
                    ) {
                        actionFavored = value
                        changed = true
                    }
                }
                if (changed) updateActionButtonsUi()
            } finally {
                if (token == socialStateFetchToken) socialStateFetchJob = null
            }
        }
}

internal fun PlayerActivity.onLikeButtonClicked(showControls: Boolean = true) {
    if (tripleActionJob?.isActive == true) return
    if (likeActionJob?.isActive == true) return
    val requestBvid = currentBvid.trim().takeIf { it.isNotBlank() } ?: return
    val targetLike = !actionLiked
    if (showControls) setControlsVisible(true)

    likeActionJob =
        lifecycleScope.launch {
            try {
                updateLikeButtonUi()
                BiliApi.archiveLike(bvid = requestBvid, aid = currentAid, like = targetLike)
                if (currentBvid != requestBvid) return@launch
                actionLiked = targetLike
                AppToast.show(this@onLikeButtonClicked, if (targetLike) "点赞成功" else "已取消赞")
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                val e = t as? BiliApiException
                if (targetLike && e?.apiCode == 65006) {
                    if (currentBvid != requestBvid) return@launch
                    actionLiked = true
                    AppToast.show(this@onLikeButtonClicked, "已点赞")
                } else {
                    val msg = e?.apiMessage?.takeIf { it.isNotBlank() } ?: (t.message ?: "操作失败")
                    AppToast.show(this@onLikeButtonClicked, msg)
                }
            } finally {
                likeActionJob = null
                updateLikeButtonUi()
            }
        }
}

internal fun PlayerActivity.onCoinButtonClicked(showControls: Boolean = true) {
    if (tripleActionJob?.isActive == true) return
    if (coinActionJob?.isActive == true) return
    if (actionCoinCount >= 2) return
    val requestBvid = currentBvid.trim().takeIf { it.isNotBlank() } ?: return
    if (showControls) setControlsVisible(true)

    coinActionJob =
        lifecycleScope.launch {
            try {
                updateCoinButtonUi()
                BiliApi.coinAdd(bvid = requestBvid, aid = currentAid, multiply = 1, selectLike = false)
                if (currentBvid != requestBvid) return@launch
                actionCoinCount = (actionCoinCount + 1).coerceAtMost(2)
                AppToast.show(this@onCoinButtonClicked, "投币成功")
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                val e = t as? BiliApiException
                if (e?.apiCode == 34005) {
                    if (currentBvid != requestBvid) return@launch
                    actionCoinCount = 2
                    AppToast.show(this@onCoinButtonClicked, "已达到投币上限")
                } else {
                    val msg = e?.apiMessage?.takeIf { it.isNotBlank() } ?: (t.message ?: "操作失败")
                    AppToast.show(this@onCoinButtonClicked, msg)
                }
            } finally {
                coinActionJob = null
                updateCoinButtonUi()
            }
        }
}

internal fun PlayerActivity.onFavButtonClicked(showControls: Boolean = true) {
    if (tripleActionJob?.isActive == true) return
    if (favDialogJob?.isActive == true || favApplyJob?.isActive == true) return
    val selfMid = BiliClient.cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
    if (selfMid == null) {
        AppToast.show(this, "请先登录后再收藏")
        return
    }
    val aid = currentAid?.takeIf { it > 0L }
    if (aid == null) {
        AppToast.show(this, "未获取到 aid，暂不支持收藏")
        return
    }

    val requestBvid = currentBvid
    val requestAid = aid
    if (showControls) setControlsVisible(true)
    val restoreFocusView = currentFocus

    fun restoreFocusAfterPopup() {
        val target = if (showControls) binding.btnFav else restoreFocusView
        target?.post { target.requestFocus() }
    }

    favDialogJob =
        lifecycleScope.launch {
            try {
                updateFavButtonUi()
                val folders =
                    withContext(Dispatchers.IO) {
                        BiliApi.favFoldersWithState(upMid = selfMid, rid = requestAid)
                    }
                if (currentBvid != requestBvid) return@launch
                if (folders.isEmpty()) {
                    AppToast.show(this@onFavButtonClicked, "未获取到收藏夹")
                    return@launch
                }

                val initial = folders.filter { it.favState }.map { it.mediaId }.toSet()

                actionFavored = initial.isNotEmpty()
                updateFavButtonUi()

                val labels =
                    folders.map { folder ->
                        if (folder.favState) "${folder.title}（已收藏）" else folder.title
                    }
                AppPopup.singleChoice(
                    context = this@onFavButtonClicked,
                    title = "选择收藏夹",
                    items = labels,
                    checkedIndex = 0,
                    onDismiss = { restoreFocusAfterPopup() },
                ) { index, _ ->
                    val picked = folders.getOrNull(index)
                    if (picked == null) {
                        restoreFocusAfterPopup()
                        return@singleChoice
                    }

                    val nextSelected = initial.toMutableSet()
                    if (nextSelected.contains(picked.mediaId)) nextSelected.remove(picked.mediaId) else nextSelected.add(picked.mediaId)
                    val add = (nextSelected - initial).toList()
                    val del = (initial - nextSelected).toList()
                    if (add.isNotEmpty() || del.isNotEmpty()) {
                        applyFavSelection(rid = requestAid, add = add, del = del, selected = nextSelected.toSet())
                    }
                    restoreFocusAfterPopup()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                val e = t as? BiliApiException
                val msg = e?.apiMessage?.takeIf { it.isNotBlank() } ?: (t.message ?: "加载收藏夹失败")
                AppToast.show(this@onFavButtonClicked, msg)
            } finally {
                favDialogJob = null
                updateFavButtonUi()
            }
        }
}

internal fun PlayerActivity.applyFavSelection(
    rid: Long,
    add: List<Long>,
    del: List<Long>,
    selected: Set<Long>,
) {
    if (favApplyJob?.isActive == true) return
    favApplyJob =
        lifecycleScope.launch {
            try {
                updateFavButtonUi()
                BiliApi.favResourceDeal(rid = rid, addMediaIds = add, delMediaIds = del)
                actionFavored = selected.isNotEmpty()
                updateFavButtonUi()
                AppToast.show(this@applyFavSelection, "收藏已更新")
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                val e = t as? BiliApiException
                val msg = e?.apiMessage?.takeIf { it.isNotBlank() } ?: (t.message ?: "操作失败")
                AppToast.show(this@applyFavSelection, msg)
            } finally {
                favApplyJob = null
                updateFavButtonUi()
            }
        }
}

internal fun PlayerActivity.updatePlaylistControls() {
    val hasListPanel =
        partsListItems.isNotEmpty() ||
            (pageListItems.isNotEmpty() && pageListIndex in pageListItems.indices) ||
            currentBvid.isNotBlank()
    val playbackMode = resolvedPlaybackMode()

    fun hasControlContext(kind: PlayerVideoListKind): Boolean {
        return when (kind) {
            PlayerVideoListKind.PAGE -> pageListItems.isNotEmpty() && pageListIndex in pageListItems.indices
            PlayerVideoListKind.PARTS -> partsListItems.isNotEmpty() && partsListIndex in partsListItems.indices
            PlayerVideoListKind.RECOMMEND -> currentBvid.isNotBlank()
        }
    }

    val prevEnabled =
        when (playbackMode) {
            AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST -> hasControlContext(PlayerVideoListKind.PARTS)
            AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST_THEN_RECOMMEND ->
                hasControlContext(PlayerVideoListKind.PARTS) || hasControlContext(PlayerVideoListKind.PAGE)
            else -> hasControlContext(PlayerVideoListKind.PAGE)
        }
    val nextEnabled =
        when (playbackMode) {
            AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND -> hasControlContext(PlayerVideoListKind.RECOMMEND)
            AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST -> hasControlContext(PlayerVideoListKind.PARTS)
            AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST_THEN_RECOMMEND ->
                hasControlContext(PlayerVideoListKind.PARTS) || hasControlContext(PlayerVideoListKind.RECOMMEND)
            AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST -> hasControlContext(PlayerVideoListKind.PAGE)
            else -> hasControlContext(PlayerVideoListKind.PAGE)
        }

    run {
        val enabled = prevEnabled
        binding.btnPrev.isEnabled = enabled
        binding.btnPrev.alpha = if (enabled) 1.0f else 0.35f
    }
    run {
        val enabled = nextEnabled
        binding.btnNext.isEnabled = enabled
        binding.btnNext.alpha = if (enabled) 1.0f else 0.35f
    }

    binding.btnListPanel.isEnabled = hasListPanel
    binding.btnListPanel.alpha = if (hasListPanel) 1.0f else 0.35f
}

internal fun PlayerActivity.updateUpButton() {
    val enabled = currentUpMid > 0L
    val alpha = if (enabled) 1.0f else 0.35f
    binding.btnUp.isEnabled = enabled
    binding.btnUp.alpha = alpha
    updateUpQuickCardUi()
}

internal fun PlayerActivity.pickRecommendedVideo(items: List<VideoCard>, excludeBvid: String): VideoCard? {
    val safeExclude = excludeBvid.trim()
    return items.firstOrNull { it.bvid.isNotBlank() && it.bvid != safeExclude }
        ?: items.firstOrNull { it.bvid.isNotBlank() }
}

internal fun PlayerActivity.playRecommendedNext(userInitiated: Boolean) {
    val requestBvid = currentBvid.trim()
    if (requestBvid.isBlank()) {
        if (userInitiated) AppToast.show(this, "暂无推荐视频")
        finish()
        return
    }

    val cached = relatedVideosCache?.takeIf { it.bvid == requestBvid }?.items.orEmpty()
    val cachedPicked = pickRecommendedVideo(cached, excludeBvid = requestBvid)
    if (cachedPicked != null) {
        startPlayback(
            bvid = cachedPicked.bvid,
            cidExtra = cachedPicked.cid?.takeIf { it > 0 },
            epIdExtra = null,
            aidExtra = null,
            initialTitle = cachedPicked.title.takeIf { it.isNotBlank() },
            startedFromList = PlayerVideoListKind.RECOMMEND,
        )
        return
    }

    val activeJob = relatedVideosFetchJob
    if (activeJob?.isActive == true) {
        lifecycleScope.launch {
            activeJob.join()
            if (currentBvid.trim() != requestBvid) return@launch

            val refreshed = relatedVideosCache?.takeIf { it.bvid == requestBvid }?.items.orEmpty()
            val picked = pickRecommendedVideo(refreshed, excludeBvid = requestBvid)
            if (picked != null) {
                startPlayback(
                    bvid = picked.bvid,
                    cidExtra = picked.cid?.takeIf { it > 0 },
                    epIdExtra = null,
                    aidExtra = null,
                    initialTitle = picked.title.takeIf { it.isNotBlank() },
                    startedFromList = PlayerVideoListKind.RECOMMEND,
                )
            } else {
                if (userInitiated) AppToast.show(this@playRecommendedNext, "暂无推荐视频")
                finish()
            }
        }
        return
    }

    val token = ++relatedVideosFetchToken
    relatedVideosFetchJob =
        lifecycleScope.launch {
            try {
                val list =
                    withContext(Dispatchers.IO) {
                        BiliApi.archiveRelated(bvid = requestBvid, aid = currentAid)
                    }
                if (token != relatedVideosFetchToken) return@launch
                if (currentBvid.trim() != requestBvid) return@launch

                relatedVideosCache = PlayerActivity.RelatedVideosCache(bvid = requestBvid, items = list)
                val picked = pickRecommendedVideo(list, excludeBvid = requestBvid)
                if (picked == null) {
                    if (userInitiated) AppToast.show(this@playRecommendedNext, "暂无推荐视频")
                    finish()
                    return@launch
                }

                startPlayback(
                    bvid = picked.bvid,
                    cidExtra = picked.cid?.takeIf { it > 0 },
                    epIdExtra = null,
                    aidExtra = null,
                    initialTitle = picked.title.takeIf { it.isNotBlank() },
                    startedFromList = PlayerVideoListKind.RECOMMEND,
                )
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                if (userInitiated) {
                    val e = t as? BiliApiException
                    val msg = e?.apiMessage?.takeIf { it.isNotBlank() } ?: (t.message ?: "加载推荐视频失败")
                    AppToast.show(this@playRecommendedNext, msg)
                }
                if (currentBvid.trim() == requestBvid) finish()
            } finally {
                if (token == relatedVideosFetchToken) relatedVideosFetchJob = null
            }
        }
}

internal fun PlayerActivity.playNextByPlaybackMode(userInitiated: Boolean) {
    when (resolvedPlaybackMode()) {
        AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND -> playRecommendedNext(userInitiated = userInitiated)
        AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST_THEN_RECOMMEND -> playPartsNextThenRecommended(userInitiated = userInitiated)
        AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST -> playPartsNext(userInitiated = userInitiated)
        AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST -> playNext(userInitiated = userInitiated)

        // Special rule: when mode is exit/none/loop-one, manual Next defaults to Page List.
        AppPrefs.PLAYER_PLAYBACK_MODE_EXIT,
        AppPrefs.PLAYER_PLAYBACK_MODE_NONE,
        AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE,
        -> playNext(userInitiated = userInitiated)

        else -> playNext(userInitiated = userInitiated)
    }
}

internal fun PlayerActivity.playPrevByPlaybackMode(userInitiated: Boolean) {
    when (resolvedPlaybackMode()) {
        AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST -> playPartsPrev(userInitiated = userInitiated)
        AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST_THEN_RECOMMEND ->
            if (partsListItems.isNotEmpty() && partsListIndex in partsListItems.indices) {
                playPartsPrev(userInitiated = userInitiated)
            } else {
                playPrev(userInitiated = userInitiated)
            }
        else -> playPrev(userInitiated = userInitiated)
    }
}

internal fun PlayerActivity.playNext(userInitiated: Boolean) {
    val list = pageListItems
    if (list.isEmpty() || pageListIndex !in list.indices) {
        if (userInitiated) AppToast.show(this, "无下一个视频，已退出播放器")
        finish()
        return
    }
    val next = pageListIndex + 1
    if (next !in list.indices) {
        if (userInitiated) AppToast.show(this, "无下一个视频，已退出播放器")
        finish()
        return
    }
    playPageListIndex(next)
}

internal fun PlayerActivity.playPrev(userInitiated: Boolean) {
    val list = pageListItems
    if (list.isEmpty() || pageListIndex !in list.indices) {
        if (userInitiated) AppToast.show(this, "暂无上一个视频")
        return
    }
    val prev = pageListIndex - 1
    if (prev !in list.indices) {
        if (userInitiated) AppToast.show(this, "已是第一个视频")
        return
    }
    playPageListIndex(prev)
}

internal fun PlayerActivity.playPartsNext(userInitiated: Boolean) {
    val list = partsListItems
    if (list.isEmpty() || partsListIndex !in list.indices) {
        if (userInitiated) AppToast.show(this, "无下一个视频，已退出播放器")
        finish()
        return
    }
    val next = partsListIndex + 1
    if (next !in list.indices) {
        if (userInitiated) AppToast.show(this, "无下一个视频，已退出播放器")
        finish()
        return
    }
    playPartsListIndex(next)
}

internal fun PlayerActivity.playPartsNextThenRecommended(userInitiated: Boolean) {
    val list = partsListItems
    if (list.isNotEmpty() && partsListIndex in list.indices) {
        val next = partsListIndex + 1
        if (next in list.indices) {
            playPartsListIndex(next)
            return
        }
    }
    playRecommendedNext(userInitiated = userInitiated)
}

internal fun PlayerActivity.playPartsPrev(userInitiated: Boolean) {
    val list = partsListItems
    if (list.isEmpty() || partsListIndex !in list.indices) {
        if (userInitiated) AppToast.show(this, "暂无上一个视频")
        return
    }
    val prev = partsListIndex - 1
    if (prev !in list.indices) {
        if (userInitiated) AppToast.show(this, "已是第一个视频")
        return
    }
    playPartsListIndex(prev)
}

internal fun PlayerActivity.playPageListIndex(index: Int) {
    val list = pageListItems
    val item = list.getOrNull(index) ?: return
    if (item.bvid.isBlank() && (item.aid ?: 0L) <= 0L) return

    // Avoid pointless reload when list has only one item.
    if (index == pageListIndex) {
        val exo = player ?: return
        exo.seekTo(0)
        exo.playWhenReady = true
        exo.play()
        return
    }

    pageListIndex = index.coerceIn(0, list.lastIndex)
    pageListToken?.let { PlayerPlaylistStore.updateIndex(it, pageListIndex) }
    updatePlaylistControls()
    startPlayback(
        bvid = item.bvid,
        cidExtra = item.cid?.takeIf { it > 0 },
        epIdExtra = item.epId?.takeIf { it > 0 },
        aidExtra = item.aid?.takeIf { it > 0 },
        seasonIdExtra = item.seasonId?.takeIf { it > 0 },
        initialTitle = item.title,
        startedFromList = PlayerVideoListKind.PAGE,
    )
}

internal fun PlayerActivity.playPartsListIndex(index: Int) {
    val list = partsListItems
    val item = list.getOrNull(index) ?: return
    if (item.bvid.isBlank() && (item.aid ?: 0L) <= 0L) return

    // Avoid pointless reload when list has only one item.
    if (index == partsListIndex) {
        val exo = player ?: return
        exo.seekTo(0)
        exo.playWhenReady = true
        exo.play()
        return
    }

    partsListIndex = index.coerceIn(0, list.lastIndex)
    updatePlaylistControls()
    startPlayback(
        bvid = item.bvid,
        cidExtra = item.cid?.takeIf { it > 0 },
        epIdExtra = item.epId?.takeIf { it > 0 },
        aidExtra = item.aid?.takeIf { it > 0 },
        seasonIdExtra = item.seasonId?.takeIf { it > 0 },
        initialTitle = item.title,
        startedFromList = PlayerVideoListKind.PARTS,
    )
}
