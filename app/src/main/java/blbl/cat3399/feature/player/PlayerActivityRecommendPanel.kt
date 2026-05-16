package blbl.cat3399.feature.player

import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayerCustomShortcutOpenVideoListTarget
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.feature.video.VideoCardAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BOTTOM_PANEL_LOAD_MORE_REMAINING_THRESHOLD = 2

internal fun PlayerActivity.isBottomCardPanelVisible(): Boolean = binding.recommendPanel.visibility == View.VISIBLE

internal fun PlayerActivity.initBottomCardPanel() {
    binding.recommendScrim.setOnClickListener { hideBottomCardPanel(restoreFocus = true) }
    binding.recommendPanel.setOnClickListener { hideBottomCardPanel(restoreFocus = true) }

    binding.tabPageList.setOnClickListener { selectBottomPanelKind(PlayerVideoListKind.PAGE, requestFocus = true) }
    binding.tabPartsList.setOnClickListener { selectBottomPanelKind(PlayerVideoListKind.PARTS, requestFocus = true) }
    binding.tabRecommendList.setOnClickListener { selectBottomPanelKind(PlayerVideoListKind.RECOMMEND, requestFocus = true) }

    fun switchTabOnFocus(kind: PlayerVideoListKind) {
        if (!isBottomCardPanelVisible()) return
        if (!BiliClient.prefs.tabSwitchFollowsFocus) return
        if (bottomCardPanelKind == kind) return
        selectBottomPanelKind(kind = kind, requestFocus = false)
    }

    binding.tabPageList.setOnFocusChangeListener { _, hasFocus ->
        if (hasFocus) switchTabOnFocus(PlayerVideoListKind.PAGE)
    }
    binding.tabPartsList.setOnFocusChangeListener { _, hasFocus ->
        if (hasFocus) switchTabOnFocus(PlayerVideoListKind.PARTS)
    }
    binding.tabRecommendList.setOnFocusChangeListener { _, hasFocus ->
        if (hasFocus) switchTabOnFocus(PlayerVideoListKind.RECOMMEND)
    }

    listOf(binding.tabPageList, binding.tabPartsList, binding.tabRecommendList).forEach { tab ->
        tab.setOnKeyListener { _, keyCode, event ->
            if (!isBottomCardPanelVisible()) return@setOnKeyListener false
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    hideBottomCardPanel(restoreFocus = true)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusBottomPanelDefaultItem()
                    true
                }
                else -> false
            }
        }
    }

    syncBottomPanelTabUi(kind = bottomCardPanelKind)

    binding.recyclerRecommend.layoutManager =
        LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
    binding.recyclerRecommend.itemAnimator = null
    binding.recyclerRecommend.adapter =
        VideoCardAdapter(
            onClick = { card, pos ->
                when (bottomCardPanelKind) {
                    PlayerVideoListKind.PAGE -> playBottomPanelPageIndex(pos)
                    PlayerVideoListKind.PARTS -> playBottomPanelPartsIndex(pos)
                    PlayerVideoListKind.RECOMMEND -> playBottomPanelRecommendCard(card)
                }
            },
            onLongClick = null,
            fixedItemWidthDimenRes = R.dimen.player_recommend_card_width,
            fixedItemMarginDimenRes = R.dimen.player_recommend_card_margin,
            stableIdKey = { card ->
                buildString {
                    append(card.bvid)
                    append('|')
                    append(card.cid ?: -1L)
                    append('|')
                    append(card.aid ?: -1L)
                    append('|')
                    append(card.epId ?: -1L)
                    append('|')
                    append(card.title)
                }
            },
        )

    binding.recyclerRecommend.addOnScrollListener(
        object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_DRAGGING && newState != RecyclerView.SCROLL_STATE_IDLE) return
                if (recyclerView.canScrollHorizontally(1)) return
                maybeLoadMoreBottomPanelItemsFromScroll()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dx <= 0) return
                maybeLoadMoreBottomPanelItemsFromScroll()
            }
        },
    )

    binding.recyclerRecommend.addOnChildAttachStateChangeListener(
        object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.setOnKeyListener { v, keyCode, event ->
                    if (!isBottomCardPanelVisible()) return@setOnKeyListener false
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            requestFocusBottomPanelActiveTab()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            // Keep focus from escaping to the player view / other controls.
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            val holder = binding.recyclerRecommend.findContainingViewHolder(v)
                            val pos =
                                holder?.bindingAdapterPosition?.takeIf { it != RecyclerView.NO_POSITION }
                                    ?: run {
                                        // Be conservative: never allow LEFT to bubble while the panel is visible.
                                        return@setOnKeyListener true
                                    }

                            if (pos <= 0) return@setOnKeyListener true
                            focusBottomPanelPosition(pos - 1)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val last = (binding.recyclerRecommend.adapter?.itemCount ?: 0) - 1
                            val holder = binding.recyclerRecommend.findContainingViewHolder(v)
                            val pos =
                                holder?.bindingAdapterPosition?.takeIf { it != RecyclerView.NO_POSITION }
                                    ?: run {
                                        // Be conservative: never allow RIGHT to bubble while the panel is visible.
                                        return@setOnKeyListener true
                                    }

                            if (pos >= last) {
                                val nextPos = pos + 1
                                ensurePlaylistIndexLoaded(kind = bottomCardPanelKind, index = nextPos) { available ->
                                    if (!available) return@ensurePlaylistIndexLoaded
                                    if (!isBottomCardPanelVisible() || bottomCardPanelKind == PlayerVideoListKind.RECOMMEND) return@ensurePlaylistIndexLoaded
                                    binding.recyclerRecommend.post { focusBottomPanelPosition(nextPos) }
                                }
                                return@setOnKeyListener true
                            }
                            focusBottomPanelPosition(pos + 1)
                            true
                        }
                        else -> false
                    }
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                view.setOnKeyListener(null)
            }
        },
    )
}

internal fun PlayerActivity.showListPanelFromButton() {
    showListPanel(
        kind = preferredListPanelKindForPlaybackMode(),
        restoreFocusView = binding.btnListPanel,
        preferContentFocus = false,
    )
}

internal fun PlayerActivity.showListPanelFromShortcut(
    target: PlayerCustomShortcutOpenVideoListTarget = PlayerCustomShortcutOpenVideoListTarget.AUTO,
): Boolean {
    val hasAnyContext =
        partsListItems.isNotEmpty() ||
            pageListItems.isNotEmpty() ||
            currentBvid.isNotBlank()
    if (!hasAnyContext) return false
    showListPanel(
        kind = preferredListPanelKindForShortcutTarget(target),
        restoreFocusView = binding.btnListPanel,
        preferContentFocus = true,
    )
    return true
}

internal fun PlayerActivity.showListPanel(
    kind: PlayerVideoListKind,
    restoreFocusView: View,
    preferContentFocus: Boolean = false,
    openedFromMenuKey: Boolean = false,
) {
    onOverlayPanelShown(openedFromMenuKey = openedFromMenuKey)
    setControlsVisible(true)
    bottomCardPanelPreferContentFocus = preferContentFocus
    bottomCardPanelRestoreFocus = java.lang.ref.WeakReference(restoreFocusView)
    binding.recommendScrim.visibility = View.VISIBLE
    binding.recommendPanel.visibility = View.VISIBLE
    selectBottomPanelKind(kind = kind, requestFocus = true)
}

private fun PlayerActivity.selectBottomPanelKind(kind: PlayerVideoListKind, requestFocus: Boolean) {
    if (bottomCardPanelKind == kind) {
        syncBottomPanelTabUi(kind = kind)
        if (isBottomCardPanelVisible()) {
            refreshBottomCardPanelContent(requestFocus = requestFocus)
        } else if (requestFocus) {
            requestFocusBottomPanel()
        }
        return
    }
    bottomCardPanelKind = kind
    syncBottomPanelTabUi(kind = kind)
    if (isBottomCardPanelVisible()) {
        refreshBottomCardPanelContent(requestFocus = requestFocus)
    }
}

private fun PlayerActivity.syncBottomPanelTabUi(kind: PlayerVideoListKind) {
    binding.tabPageList.isSelected = kind == PlayerVideoListKind.PAGE
    binding.tabPartsList.isSelected = kind == PlayerVideoListKind.PARTS
    binding.tabRecommendList.isSelected = kind == PlayerVideoListKind.RECOMMEND
}

private fun PlayerActivity.preferredListPanelKindForPlaybackMode(): PlayerVideoListKind {
    return when (resolvedPlaybackMode()) {
        AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND -> PlayerVideoListKind.RECOMMEND
        AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST_THEN_RECOMMEND ->
            when {
                partsListItems.isNotEmpty() || partsListFetchJob?.isActive == true -> PlayerVideoListKind.PARTS
                currentBvid.isNotBlank() -> PlayerVideoListKind.RECOMMEND
                else -> PlayerVideoListKind.PARTS
            }
        AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST -> PlayerVideoListKind.PARTS
        else -> PlayerVideoListKind.PAGE
    }
}

private fun PlayerActivity.preferredListPanelKindForShortcutTarget(target: PlayerCustomShortcutOpenVideoListTarget): PlayerVideoListKind {
    return when (target) {
        PlayerCustomShortcutOpenVideoListTarget.AUTO -> preferredListPanelKindForPlaybackMode()
        PlayerCustomShortcutOpenVideoListTarget.PAGE -> PlayerVideoListKind.PAGE
        PlayerCustomShortcutOpenVideoListTarget.PARTS -> PlayerVideoListKind.PARTS
        PlayerCustomShortcutOpenVideoListTarget.RECOMMEND -> PlayerVideoListKind.RECOMMEND
    }
}

internal fun PlayerActivity.hideBottomCardPanel(
    restoreFocus: Boolean,
    dismissTarget: PlayerActivity.PanelDismissTarget? = PlayerActivity.PanelDismissTarget.ResumeOsd,
) {
    if (!isBottomCardPanelVisible() && binding.recommendScrim.visibility != View.VISIBLE) return
    binding.recommendScrim.visibility = View.GONE
    binding.recommendPanel.visibility = View.GONE
    bottomCardPanelPreferContentFocus = false
    (binding.recyclerRecommend.adapter as? VideoCardAdapter)?.submit(emptyList())
    binding.tvListPanelEmpty.visibility = View.GONE
    binding.recyclerRecommend.visibility = View.VISIBLE
    dismissTarget?.let { onLastOverlayPanelDismissed(it) }
    if (!restoreFocus || dismissTarget == PlayerActivity.PanelDismissTarget.Fullscreen) return

    val target = bottomCardPanelRestoreFocus?.get()
    binding.root.post {
        when {
            target != null && target.requestOsdFocusIfUsable() -> Unit
            binding.btnListPanel.requestOsdFocusIfUsable() -> Unit
            binding.btnPlayPause.requestOsdFocusIfUsable() -> Unit
            else -> focusFirstControl()
        }
    }
}

internal fun PlayerActivity.notifyPageListPanelChanged() {
    if (!isBottomCardPanelVisible()) return
    if (bottomCardPanelKind != PlayerVideoListKind.PAGE) return
    val requestFocus = shouldRequestBottomPanelContentFocusAfterAsyncUpdate(kind = PlayerVideoListKind.PAGE)
    refreshBottomCardPanelContent(
        requestFocus = requestFocus,
        preserveScrollPosition = isPlaylistLoadMoreRunning(PlayerVideoListKind.PAGE) && !requestFocus,
    )
}

internal fun PlayerActivity.notifyPartsListPanelChanged() {
    refreshPlayerInfoPanelContent()
    if (!isBottomCardPanelVisible()) return
    if (bottomCardPanelKind != PlayerVideoListKind.PARTS) return
    val requestFocus = shouldRequestBottomPanelContentFocusAfterAsyncUpdate(kind = PlayerVideoListKind.PARTS)
    refreshBottomCardPanelContent(
        requestFocus = requestFocus,
        preserveScrollPosition = isPlaylistLoadMoreRunning(PlayerVideoListKind.PARTS) && !requestFocus,
    )
}

private fun PlayerActivity.refreshBottomCardPanelContent(
    requestFocus: Boolean,
    preserveScrollPosition: Boolean = false,
) {
    if (!isBottomCardPanelVisible()) return
    val kind = bottomCardPanelKind
    if (kind == PlayerVideoListKind.RECOMMEND) {
        ensureRecommendCardsLoaded()
    }
    val cards = cardsForBottomPanel(kind)
    (binding.recyclerRecommend.adapter as? VideoCardAdapter)?.submit(cards)

    val hasItems = cards.isNotEmpty()
    binding.recyclerRecommend.visibility = if (hasItems) View.VISIBLE else View.GONE
    binding.tvListPanelEmpty.visibility = if (hasItems) View.GONE else View.VISIBLE
    if (!hasItems) {
        binding.tvListPanelEmpty.text =
            when (kind) {
                PlayerVideoListKind.PAGE -> "暂无视频列表"
                PlayerVideoListKind.PARTS ->
                    if (partsListFetchJob?.isActive == true) {
                        "加载中…"
                    } else {
                        "暂无合集/分P"
                    }
                PlayerVideoListKind.RECOMMEND ->
                    if (relatedVideosFetchJob?.isActive == true) {
                        "加载中…"
                    } else {
                        "暂无推荐视频"
                    }
            }
        if (requestFocus) binding.tvListPanelEmpty.post { requestFocusBottomPanelActiveTab() }
        return
    }

    if (!preserveScrollPosition) {
        val focusIndex =
            when (kind) {
                PlayerVideoListKind.PAGE -> pageListIndex
                PlayerVideoListKind.PARTS -> partsListIndex
                PlayerVideoListKind.RECOMMEND -> 0
            }.takeIf { it >= 0 } ?: 0
        val safeFocusIndex = focusIndex.coerceIn(0, (cards.size - 1).coerceAtLeast(0))
        binding.recyclerRecommend.scrollToPosition(safeFocusIndex)
    }
    if (requestFocus) {
        binding.recyclerRecommend.post { requestFocusBottomPanel() }
    }
}

private fun PlayerActivity.maybeLoadMoreBottomPanelItemsFromScroll() {
    if (!isBottomCardPanelVisible()) return

    val kind = bottomCardPanelKind
    if (kind == PlayerVideoListKind.RECOMMEND) return
    if (!hasMorePlaylistItems(kind) || isPlaylistLoadMoreRunning(kind)) return

    val lm = binding.recyclerRecommend.layoutManager as? LinearLayoutManager ?: return
    val lastVisible = lm.findLastVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION } ?: return
    val total = binding.recyclerRecommend.adapter?.itemCount ?: 0
    if (total <= 0) return

    if (total - lastVisible - 1 <= BOTTOM_PANEL_LOAD_MORE_REMAINING_THRESHOLD) {
        loadMorePlaylist(kind = kind)
    }
}

private fun PlayerActivity.cardsForBottomPanel(kind: PlayerVideoListKind): List<VideoCard> {
    return when (kind) {
        PlayerVideoListKind.PAGE -> resolvePlaylistUiCards(items = pageListItems, uiCards = pageListUiCards)
        PlayerVideoListKind.PARTS -> resolvePlaylistUiCards(items = partsListItems, uiCards = partsListUiCards)
        PlayerVideoListKind.RECOMMEND -> recommendCardsForCurrentVideo()
    }
}

private fun PlayerActivity.recommendCardsForCurrentVideo(): List<VideoCard> {
    val requestBvid = currentBvid.trim()
    if (requestBvid.isBlank()) return emptyList()
    return relatedVideosCache?.takeIf { it.bvid == requestBvid }?.items.orEmpty()
}

internal fun PlayerActivity.ensureRecommendCardsLoadedForAutoNext() {
    ensureRecommendCardsLoaded()
}

private fun PlayerActivity.ensureRecommendCardsLoaded() {
    val requestBvid = currentBvid.trim()
    if (requestBvid.isBlank()) return

    // Treat "cached but empty" as resolved to avoid repeated loads.
    val cacheHit = relatedVideosCache?.takeIf { it.bvid == requestBvid }
    if (cacheHit != null) return

    if (relatedVideosFetchJob?.isActive == true) return
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
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                if (token != relatedVideosFetchToken) return@launch
                if (currentBvid.trim() != requestBvid) return@launch

                relatedVideosCache = PlayerActivity.RelatedVideosCache(bvid = requestBvid, items = emptyList())
                val e = t as? BiliApiException
                val msg = e?.apiMessage?.takeIf { it.isNotBlank() } ?: (t.message ?: "加载推荐视频失败")
                if (isBottomCardPanelVisible() && bottomCardPanelKind == PlayerVideoListKind.RECOMMEND) {
                    AppToast.show(this@ensureRecommendCardsLoaded, msg)
                } else {
                    AppLog.w("Player", "recommend:load_failed $msg")
                }
            } finally {
                if (token == relatedVideosFetchToken) relatedVideosFetchJob = null
                if (currentBvid.trim() == requestBvid) refreshPlayerInfoPanelContent()
                if (isBottomCardPanelVisible() && bottomCardPanelKind == PlayerVideoListKind.RECOMMEND && currentBvid.trim() == requestBvid) {
                    refreshBottomCardPanelContent(requestFocus = shouldRequestBottomPanelContentFocusAfterAsyncUpdate(kind = PlayerVideoListKind.RECOMMEND))
                }
            }
        }
}

private fun PlayerActivity.resolvePlaylistUiCards(items: List<PlayerPlaylistItem>, uiCards: List<VideoCard>): List<VideoCard> {
    if (items.isEmpty()) return emptyList()
    if (uiCards.isNotEmpty() && uiCards.size == items.size) return uiCards
    return items.mapIndexed { index, item ->
        val title =
            item.title?.trim()?.takeIf { it.isNotBlank() }
                ?: "视频 ${index + 1}"
        VideoCard(
            bvid = item.bvid,
            cid = item.cid,
            aid = item.aid,
            epId = item.epId,
            title = title,
            coverUrl = "",
            durationSec = 0,
            ownerName = "",
            ownerFace = null,
            ownerMid = null,
            view = null,
            danmaku = null,
            pubDate = null,
            pubDateText = null,
        )
    }
}

private fun PlayerActivity.requestFocusBottomPanelActiveTab() {
    val tab = currentBottomPanelTabView()
    tab.post { tab.requestFocus() }
}

private fun PlayerActivity.requestFocusBottomPanel() {
    if (bottomCardPanelPreferContentFocus) {
        focusBottomPanelDefaultItem()
    } else {
        requestFocusBottomPanelActiveTab()
    }
}

private fun PlayerActivity.shouldRequestBottomPanelContentFocusAfterAsyncUpdate(kind: PlayerVideoListKind): Boolean {
    if (!isBottomCardPanelVisible()) return false
    if (bottomCardPanelKind != kind) return false
    if (!bottomCardPanelPreferContentFocus) return false

    val focused = currentFocus ?: return true
    if (!FocusTreeUtils.isDescendantOf(focused, binding.recommendPanel)) return false
    return focused === currentBottomPanelTabView() || focused === binding.tvListPanelEmpty
}

private fun PlayerActivity.currentBottomPanelTabView(): View {
    return when (bottomCardPanelKind) {
        PlayerVideoListKind.PAGE -> binding.tabPageList
        PlayerVideoListKind.PARTS -> binding.tabPartsList
        PlayerVideoListKind.RECOMMEND -> binding.tabRecommendList
    }
}

private fun PlayerActivity.focusBottomPanelDefaultItem() {
    val count = binding.recyclerRecommend.adapter?.itemCount ?: 0
    if (count <= 0) return
    val pos =
        when (bottomCardPanelKind) {
            PlayerVideoListKind.PAGE -> pageListIndex
            PlayerVideoListKind.PARTS -> partsListIndex
            PlayerVideoListKind.RECOMMEND -> 0
        }.takeIf { it >= 0 } ?: 0
    focusBottomPanelPosition(pos.coerceIn(0, count - 1))
}

private fun PlayerActivity.playBottomPanelRecommendCard(card: VideoCard) {
    val bvid = card.bvid.trim()
    if (bvid.isBlank()) return
    hideBottomCardPanel(restoreFocus = false)
    startPlayback(
        bvid = bvid,
        cidExtra = card.cid?.takeIf { it > 0 },
        epIdExtra = null,
        aidExtra = null,
        initialTitle = card.title.takeIf { it.isNotBlank() },
        startedFromList = PlayerVideoListKind.RECOMMEND,
    )
    setControlsVisible(true)
    requestFocusAfterListPanelAction()
}

private fun PlayerActivity.playBottomPanelPageIndex(index: Int) {
    val list = pageListItems
    if (list.isEmpty() || index !in list.indices) return
    hideBottomCardPanel(restoreFocus = false)
    playPageListIndex(index)
    setControlsVisible(true)
    requestFocusAfterListPanelAction()
}

private fun PlayerActivity.playBottomPanelPartsIndex(index: Int) {
    val list = partsListItems
    if (list.isEmpty() || index !in list.indices) return
    hideBottomCardPanel(restoreFocus = false)
    playPartsListIndex(index)
    setControlsVisible(true)
    requestFocusAfterListPanelAction()
}

internal fun PlayerActivity.ensureBottomCardPanelFocus() {
    if (!isBottomCardPanelVisible()) return
    val focused = currentFocus
    val inPanel = focused != null && FocusTreeUtils.isDescendantOf(focused, binding.recommendPanel)
    if (inPanel) return
    binding.recommendPanel.post {
        val count = binding.recyclerRecommend.adapter?.itemCount ?: 0
        if (count > 0) {
            focusBottomPanelDefaultItem()
        } else {
            requestFocusBottomPanelActiveTab()
        }
    }
}

private fun View.requestOsdFocusIfUsable(): Boolean {
    if (!isAttachedToWindow) return false
    if (visibility != View.VISIBLE) return false
    if (!isEnabled) return false
    if (!isFocusable) return false
    return requestFocus()
}

private fun PlayerActivity.requestFocusAfterListPanelAction() {
    binding.root.post {
        when {
            binding.btnListPanel.requestOsdFocusIfUsable() -> Unit
            binding.btnPlayPause.requestOsdFocusIfUsable() -> Unit
            else -> focusFirstControl()
        }
    }
}

private fun PlayerActivity.focusBottomPanelPosition(position: Int) {
    val adapter = binding.recyclerRecommend.adapter ?: return
    val count = adapter.itemCount
    if (position !in 0 until count) return

    binding.recyclerRecommend.findViewHolderForAdapterPosition(position)?.itemView?.requestOsdFocusIfUsable()
        ?: run {
            binding.recyclerRecommend.scrollToPosition(position)
            binding.recyclerRecommend.postIfAlive(
                isAlive = { isBottomCardPanelVisible() && binding.recyclerRecommend.isAttachedToWindow },
            ) {
                binding.recyclerRecommend.findViewHolderForAdapterPosition(position)?.itemView?.requestOsdFocusIfUsable()
            }
        }
}
