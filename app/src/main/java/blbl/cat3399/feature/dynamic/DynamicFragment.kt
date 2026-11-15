package blbl.cat3399.feature.dynamic

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Following
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.GridSpanPolicy
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.parkFocusForDataSetReset
import blbl.cat3399.core.ui.requestFocusFirstItemOrSelfAfterRefresh
import blbl.cat3399.core.ui.uiScaler
import blbl.cat3399.core.ui.unparkFocusAfterDataSetReset
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.databinding.FragmentDynamicBinding
import blbl.cat3399.databinding.FragmentDynamicLoginBinding
import blbl.cat3399.feature.following.openUpDetailFromVideoCard
import blbl.cat3399.feature.login.QrLoginActivity
import blbl.cat3399.feature.player.VideoCardPlaylistPage
import blbl.cat3399.feature.video.VideoCardActionController
import blbl.cat3399.feature.video.VideoCardAdapter
import blbl.cat3399.feature.video.VideoCardDismissBehavior
import blbl.cat3399.feature.video.VideoCardVisibilityFilter
import blbl.cat3399.feature.video.buildPagedVideoCardPlaybackHandle
import blbl.cat3399.feature.video.defaultVideoCardPlaylistItem
import blbl.cat3399.feature.video.openVideoDetailFromPlaybackHandle
import blbl.cat3399.feature.video.openVideoFromPlaybackHandle
import blbl.cat3399.feature.video.removeVideoCardAndRestoreFocus
import blbl.cat3399.ui.BackPressHandler
import blbl.cat3399.ui.RefreshKeyHandler
import blbl.cat3399.ui.SidebarFocusHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class DynamicFragment : Fragment(), RefreshKeyHandler, BackPressHandler {
    private data class FeedContinuationCursor(
        val selectedMid: Long,
        val page: Int,
        val offset: String?,
    )

    private var _bindingLogin: FragmentDynamicLoginBinding? = null
    private var _binding: FragmentDynamicBinding? = null

    private lateinit var followAdapter: FollowingAdapter
    private lateinit var videoAdapter: VideoCardAdapter

    private val loggedIn: Boolean
        get() = BiliClient.cookies.hasSessData()

    private val loadedStableKeys = HashSet<String>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var nextOffset: String? = null
    private var nextPage: Int = 1
    private var requestToken: Int = 0
    private var dynamicGridController: DpadGridController? = null

    private var userMid: Long = 0L
    private var followPage: Int = 1
    private var followIsLoadingMore: Boolean = false
    private var followEndReached: Boolean = false
    private var followRequestToken: Int = 0
    private var currentFollowingListOrder: String = BiliClient.prefs.followingListOrder
    private var currentRecentUpdatePriorityEnabled: Boolean = BiliClient.prefs.dynamicFollowingRecentUpdateDotEnabled
    private var recentUpdatePriorityMids: Set<Long> = emptySet()
    private val consumingRecentUpdateMids = HashSet<Long>()
    private val consumedRecentUpdateMids = HashSet<Long>()
    private val loadedFollowMids = HashSet<Long>()
    private var followingListController: DpadGridController? = null

    private var pendingFocusFirstFeedCardAfterRefresh: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (!loggedIn) {
            _bindingLogin = FragmentDynamicLoginBinding.inflate(inflater, container, false)
            return _bindingLogin!!.root
        }
        _binding = FragmentDynamicBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!loggedIn) {
            _bindingLogin?.btnLogin?.setOnClickListener {
                startActivity(Intent(requireContext(), QrLoginActivity::class.java))
            }
            return
        }

        val binding = _binding ?: return

        followAdapter = FollowingAdapter(::onFollowingClicked)
        binding.recyclerFollowing.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerFollowing.adapter = followAdapter
        binding.recyclerFollowing.clearOnScrollListeners()
        binding.recyclerFollowing.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (followIsLoadingMore || followEndReached) return
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val totalCount = followAdapter.itemCount
                    if (totalCount <= 0) return
                    if (totalCount - lastVisible - 1 <= 6) loadMoreFollowings()
                }
            },
        )
        followingListController?.release()
        followingListController =
            DpadGridController(
                recyclerView = binding.recyclerFollowing,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            // No header/tab in Dynamic. Keep focus inside the list.
                            return false
                        }

                        override fun onLeftEdge(): Boolean {
                            // Let MainActivity handle entering sidebar when appropriate.
                            return false
                        }

                        override fun onRightEdge() = Unit

                        override fun canLoadMore(): Boolean = !followEndReached

                        override fun loadMore() {
                            loadMoreFollowings()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                        consumeRightEdge = false,
                    ),
            ).also { it.install() }
        applyUiMode()

        val actionController =
            VideoCardActionController(
                context = requireContext(),
                scope = viewLifecycleOwner.lifecycleScope,
                dismissBehavior = VideoCardDismissBehavior.LocalNotInterested,
                onOpenDetail = { _, pos -> openDetail(pos) },
                onOpenUp = { card -> openUpDetailFromVideoCard(card) },
                onCardRemoved = { stableKey ->
                    _binding?.recyclerDynamic?.removeVideoCardAndRestoreFocus(
                        adapter = videoAdapter,
                        stableKey = stableKey,
                        isAlive = { _binding != null && isResumed },
                    )
                },
            )
        videoAdapter =
            VideoCardAdapter(
                onClick = { _, pos ->
                    requireContext().openVideoFromPlaybackHandle(
                        playbackHandle = playbackHandle(),
                        position = pos,
                        openDetailBeforePlay = BiliClient.prefs.playerOpenDetailBeforePlay,
                    )
                },
                onLongClick = { card, _ ->
                    openUpDetailFromVideoCard(card)
                    true
                },
                actionDelegate = actionController,
            )
        binding.recyclerDynamic.setHasFixedSize(true)
        binding.recyclerDynamic.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth())
        binding.recyclerDynamic.adapter = videoAdapter
        (binding.recyclerDynamic.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        dynamicGridController?.release()
        dynamicGridController =
            DpadGridController(
                recyclerView = binding.recyclerDynamic,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            // No header/tab in Dynamic. Keep focus inside the grid.
                            return false
                        }

                        override fun onLeftEdge(): Boolean {
                            return focusSelectedFollowingIfAvailable()
                        }

                        override fun onRightEdge() = Unit

                        override fun canLoadMore(): Boolean = !endReached

                        override fun loadMore() {
                            loadMoreFeed()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                        enableCenterLongPressToLongClick = true,
                    ),
            ).also { it.install() }
        binding.recyclerDynamic.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (isLoadingMore || endReached) return
                    if (binding.swipeRefresh.isRefreshing) return

                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = videoAdapter.itemCount
                    if (total <= 0) return

                    if (total - lastVisible - 1 <= 8) {
                        loadMoreFeed()
                    }
                }
            },
        )

        binding.swipeRefresh.setOnRefreshListener {
            pendingFocusFirstFeedCardAfterRefresh = true
            parkFocusForDataSetReset(followingListController, dynamicGridController)
            loadAll(resetFeed = true)
        }
        binding.swipeRefresh.isRefreshing = true
        loadAll(resetFeed = true)
    }

    private var baseFollowings: List<Following> = emptyList()
    private var followItems: List<FollowingAdapter.FollowingUi> = emptyList()
    private var selectedMid: Long = FollowingAdapter.MID_ALL

    private fun onFollowingClicked(following: FollowingAdapter.FollowingUi) {
        selectedMid = following.mid
        followAdapter.submit(followItems, selected = selectedMid)
        resetAndLoadFeed()
        maybeConsumeRecentUpdate(following)
    }

    private fun renderFollowings() {
        followItems = buildFollowingUiList()
        if (selectedMid != FollowingAdapter.MID_ALL && followItems.none { it.mid == selectedMid }) {
            selectedMid = FollowingAdapter.MID_ALL
        }
        followAdapter.submit(followItems, selected = selectedMid)
    }

    private fun buildFollowingUiList(): List<FollowingAdapter.FollowingUi> {
        val items = ArrayList<FollowingAdapter.FollowingUi>(baseFollowings.size + 1)
        items += FollowingAdapter.FollowingUi(FollowingAdapter.MID_ALL, "所有", null, isAll = true)
        val orderedFollowings = orderedFollowings()
        for (following in orderedFollowings) {
            val mid = following.mid
            val hasRecentUpdate = recentUpdatePriorityMids.contains(mid)
            items +=
                FollowingAdapter.FollowingUi(
                    mid = mid,
                    name = following.name,
                    avatarUrl = following.avatarUrl,
                    showRecentUpdateDot =
                        currentRecentUpdatePriorityEnabled &&
                            hasRecentUpdate &&
                            !consumedRecentUpdateMids.contains(mid),
                )
        }
        return items
    }

    private fun orderedFollowings(): List<Following> {
        if (!currentRecentUpdatePriorityEnabled) return baseFollowings
        if (baseFollowings.isEmpty()) return baseFollowings
        val updated = ArrayList<Following>(baseFollowings.size)
        val normal = ArrayList<Following>(baseFollowings.size)
        for (following in baseFollowings) {
            if (recentUpdatePriorityMids.contains(following.mid)) {
                updated += following
            } else {
                normal += following
            }
        }
        return updated + normal
    }

    private fun maybeConsumeRecentUpdate(following: FollowingAdapter.FollowingUi) {
        if (!currentRecentUpdatePriorityEnabled) return
        if (following.isAll || !following.showRecentUpdateDot) return
        val mid = following.mid.takeIf { it > 0L } ?: return
        if (!consumingRecentUpdateMids.add(mid)) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                BiliApi.consumeDynamicRecentUpdate(hostMid = mid)
                if (_binding == null) return@launch
                consumedRecentUpdateMids.add(mid)
                renderFollowings()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.w("Dynamic", "consume recent update failed mid=$mid", t)
            } finally {
                consumingRecentUpdateMids.remove(mid)
            }
        }
    }

    private fun loadAll(resetFeed: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val nav = BiliApi.nav()
                val data = nav.optJSONObject("data")
                val mid = data?.optLong("mid") ?: 0L
                val isLogin = data?.optBoolean("isLogin") ?: false
                AppLog.i("Dynamic", "nav isLogin=$isLogin mid=$mid")
                if (!isLogin || mid <= 0) {
                    _binding?.swipeRefresh?.isRefreshing = false
                    context?.let { AppToast.show(it, "登录态失效，请重新登录") }
                    return@launch
                }

                userMid = mid
                resetAndLoadFollowings()
                if (resetFeed) resetAndLoadFeed() else loadMoreFeed()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("Dynamic", "load failed", t)
                _binding?.swipeRefresh?.isRefreshing = false
                context?.let { AppToast.show(it, getString(R.string.error_load_retry)) }
            } finally {
                if (!resetFeed) _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    private suspend fun resetAndLoadFollowings() {
        if (_binding == null) return
        if (!this::followAdapter.isInitialized) return
        if (userMid <= 0) return

        loadedFollowMids.clear()
        currentFollowingListOrder = BiliClient.prefs.followingListOrder
        currentRecentUpdatePriorityEnabled = BiliClient.prefs.dynamicFollowingRecentUpdateDotEnabled
        recentUpdatePriorityMids = emptySet()
        consumedRecentUpdateMids.clear()
        followPage = 1
        followEndReached = false
        followIsLoadingMore = false
        followingListController?.clearPendingFocusAfterLoadMore()
        val token = ++followRequestToken
        if (selectedMid == 0L) selectedMid = FollowingAdapter.MID_ALL
        baseFollowings = emptyList()
        renderFollowings()

        followIsLoadingMore = true
        try {
            val res =
                coroutineScope {
                    val recentUpdateDeferred =
                        if (currentRecentUpdatePriorityEnabled) {
                            async {
                                runCatching { BiliApi.dynamicRecentUpdateUpMids() }
                                    .onFailure { AppLog.w("Dynamic", "load recent update up list failed", it) }
                                    .getOrElse { emptySet() }
                            }
                        } else {
                            null
                        }
                    val followingPage =
                        BiliApi.followingsPage(vmid = userMid, pn = followPage, ps = 50, order = currentFollowingListOrder)
                    recentUpdatePriorityMids = recentUpdateDeferred?.await().orEmpty()
                    followingPage
                }
            if (token != followRequestToken) return

            val filtered = res.items.filter { loadedFollowMids.add(it.mid) }
            if (filtered.isEmpty()) {
                followEndReached = true
                context?.let { AppToast.show(it, "暂无关注") }
                return
            }

            baseFollowings = filtered
            renderFollowings()
            followPage += 1
            followEndReached = !res.hasMore
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLog.e("Dynamic", "load followings failed page=$followPage", t)
            context?.let { AppToast.show(it, getString(R.string.error_following_load_retry)) }
        } finally {
            if (token == followRequestToken) {
                followIsLoadingMore = false
            }
        }
    }

    private fun loadMoreFollowings() {
        if (followIsLoadingMore || followEndReached) return
        if (userMid <= 0) return
        val token = followRequestToken
        val targetPage = followPage.coerceAtLeast(1)
        followIsLoadingMore = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = BiliApi.followingsPage(vmid = userMid, pn = targetPage, ps = 50, order = currentFollowingListOrder)
                if (token != followRequestToken) return@launch

                val filtered = res.items.filter { loadedFollowMids.add(it.mid) }
                if (filtered.isNotEmpty()) {
                    baseFollowings = baseFollowings + filtered
                    renderFollowings()
                }
                followPage = targetPage + 1
                followEndReached = !res.hasMore
                _binding?.let { b ->
                    b.recyclerFollowing.postIfAlive(isAlive = { _binding === b && isResumed }) {
                        followingListController?.consumePendingFocusAfterLoadMore()
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("Dynamic", "load followings failed page=$targetPage", t)
            } finally {
                if (token == followRequestToken) followIsLoadingMore = false
            }
        }
    }

    private fun resetAndLoadFeed() {
        dynamicGridController?.clearPendingFocusAfterLoadMore()
        loadedStableKeys.clear()
        nextOffset = null
        nextPage = 1
        endReached = false
        isLoadingMore = false
        requestToken++
        videoAdapter.submit(emptyList())
        _binding?.swipeRefresh?.isRefreshing = true
        loadMoreFeed()
    }

    private fun loadMoreFeed() {
        if (isLoadingMore || endReached) return
        if (selectedMid == 0L) {
            endReached = true
            _binding?.swipeRefresh?.isRefreshing = false
            return
        }
        val token = requestToken
        isLoadingMore = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (selectedMid == FollowingAdapter.MID_ALL) {
                    var currentOffset = nextOffset
                    while (true) {
                        val page = BiliApi.dynamicAllVideo(offset = currentOffset)
                        if (token != requestToken) return@launch
                        val visibleItems = VideoCardVisibilityFilter.filterVisibleFresh(page.items, loadedStableKeys)
                        nextOffset = page.nextOffset
                        endReached = nextOffset == null
                        if (visibleItems.isNotEmpty() || endReached || nextOffset == currentOffset || page.items.isEmpty()) {
                            visibleItems.forEach { loadedStableKeys.add(it.stableKey()) }
                            videoAdapter.append(visibleItems)
                            break
                        }
                        currentOffset = nextOffset
                    }
                } else {
                    var targetPage = nextPage.coerceAtLeast(1)
                    while (true) {
                        val page =
                            BiliApi.spaceArcSearchPage(
                                mid = selectedMid,
                                pn = targetPage,
                                ps = 30,
                            )
                        if (token != requestToken) return@launch

                        val visibleItems = VideoCardVisibilityFilter.filterVisibleFresh(page.items, loadedStableKeys)
                        nextPage = targetPage + 1
                        endReached = !page.hasMore
                        if (visibleItems.isNotEmpty() || endReached || page.items.isEmpty()) {
                            visibleItems.forEach { loadedStableKeys.add(it.stableKey()) }
                            videoAdapter.append(visibleItems)
                            break
                        }
                        targetPage = nextPage
                    }
                }
                _binding?.let { b ->
                    b.recyclerDynamic.postIfAlive(isAlive = { _binding === b && isResumed }) {
                        if (pendingFocusFirstFeedCardAfterRefresh) {
                            pendingFocusFirstFeedCardAfterRefresh = false
                            val recycler = b.recyclerDynamic
                            val isUiAlive = { _binding === b && isResumed }
                            recycler.requestFocusFirstItemOrSelfAfterRefresh(
                                itemCount = videoAdapter.itemCount,
                                smoothScroll = false,
                                isAlive = isUiAlive,
                                onDone = { unparkFocusAfterDataSetReset(followingListController, dynamicGridController) },
                            )
                            return@postIfAlive
                        }
                        dynamicGridController?.consumePendingFocusAfterLoadMore()
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("Dynamic", "load feed failed mid=$selectedMid", t)
                context?.let { AppToast.show(it, getString(R.string.error_load_retry)) }
            } finally {
                if (token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private fun spanCountForWidth(): Int {
        val prefs = BiliClient.prefs
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return GridSpanPolicy.dynamicSpanCountForWidthDp(
            widthDp = widthDp,
            dynamicOverrideSpanCount = prefs.dynamicGridSpanCount,
            globalOverrideSpanCount = prefs.gridSpanCount,
        )
    }

    private fun focusSelectedFollowingIfAvailable(): Boolean {
        val b = _binding ?: return false
        val recyclerFollowing = b.recyclerFollowing
        val isUiAlive = { _binding === b && isResumed }
        recyclerFollowing.postIfAlive(isAlive = isUiAlive) {
            val recycler = recyclerFollowing
            val selectedChild =
                (0 until recycler.childCount)
                    .map { recycler.getChildAt(it) }
                    .firstOrNull { it?.isSelected == true }
            if (selectedChild != null) {
                selectedChild.requestFocus()
                return@postIfAlive
            }

            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                return@postIfAlive
            }

            if ((recycler.adapter?.itemCount ?: 0) <= 0) {
                recycler.requestFocus()
                return@postIfAlive
            }

            recycler.scrollToPosition(0)
            recycler.postIfAlive(isAlive = isUiAlive) {
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
            }
        }
        return true
    }

    override fun onDestroyView() {
        _bindingLogin = null
        dynamicGridController?.release()
        dynamicGridController = null
        followingListController?.release()
        followingListController = null
        followRequestToken++
        _binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        (_binding?.recyclerDynamic?.layoutManager as? GridLayoutManager)?.let { lm ->
            val desired = spanCountForWidth()
            if (lm.spanCount != desired) lm.spanCount = desired
        }
    }

    override fun handleBackPressed(): Boolean {
        // Dynamic page has no tabs: Back in content should go to sidebar (focus-based navigation).
        val root = _binding?.root ?: _bindingLogin?.root ?: return false
        val focused = activity?.currentFocus ?: return false
        if (!FocusTreeUtils.isDescendantOf(focused, root)) return false
        return (activity as? SidebarFocusHost)?.requestFocusSidebarSelectedNav() == true
    }

    override fun handleRefreshKey(): Boolean {
        if (!isResumed) return false
        if (!loggedIn) {
            AppToast.show(requireContext(), "请先登录")
            _bindingLogin?.btnLogin?.requestFocus()
            return true
        }

        val b = _binding ?: return false
        if (b.swipeRefresh.isRefreshing) return true
        pendingFocusFirstFeedCardAfterRefresh = true
        parkFocusForDataSetReset(followingListController, dynamicGridController)
        b.swipeRefresh.isRefreshing = true
        loadAll(resetFeed = true)
        return true
    }

    private fun applyUiMode() {
        val binding = _binding ?: return
        val uiScale = UiScale.factor(requireContext())
        val scaler = requireContext().uiScaler(uiScale)
        fun scaledPx(id: Int): Int = scaler.scaledDimenPx(id)

        val width =
            scaledPx(
                blbl.cat3399.R.dimen.dynamic_following_panel_width,
            )
        val margin =
            scaledPx(
                blbl.cat3399.R.dimen.dynamic_following_panel_margin,
            )
        val padding =
            scaledPx(
                blbl.cat3399.R.dimen.dynamic_following_list_padding,
            )

        val cardLp = binding.cardFollowing.layoutParams
        var changed = false
        if (cardLp.width != width.coerceAtLeast(1)) {
            cardLp.width = width.coerceAtLeast(1)
            changed = true
        }
        val mlp = cardLp as? ViewGroup.MarginLayoutParams
        if (mlp != null && (mlp.leftMargin != margin || mlp.topMargin != margin || mlp.rightMargin != margin || mlp.bottomMargin != margin)) {
            mlp.setMargins(margin, margin, margin, margin)
            changed = true
        }
        if (changed) binding.cardFollowing.layoutParams = cardLp

        if (binding.recyclerFollowing.paddingLeft != padding || binding.recyclerFollowing.paddingTop != padding || binding.recyclerFollowing.paddingRight != padding || binding.recyclerFollowing.paddingBottom != padding) {
            binding.recyclerFollowing.setPadding(padding, padding, padding, padding)
        }
    }

    companion object {
        fun newInstance() = DynamicFragment()
    }

    private fun openDetail(position: Int) {
        requireContext().openVideoDetailFromPlaybackHandle(playbackHandle(), position)
    }

    private fun playbackHandle() =
        buildPagedVideoCardPlaybackHandle(
            source = "Dynamic",
            cardsProvider = videoAdapter::snapshot,
            nextCursorProvider = {
                FeedContinuationCursor(selectedMid = selectedMid, page = nextPage, offset = nextOffset)
            },
            hasMoreProvider = { selectedMid != 0L && !endReached },
        ) { cursor ->
            if (cursor.selectedMid == FollowingAdapter.MID_ALL) {
                val page = BiliApi.dynamicAllVideo(offset = cursor.offset)
                val nextOffset = page.nextOffset
                VideoCardPlaylistPage(
                    cards = page.items,
                    nextCursor =
                        FeedContinuationCursor(
                            selectedMid = cursor.selectedMid,
                            page = 1,
                            offset = nextOffset,
                        ),
                    hasMore = nextOffset != null,
                    canAdvance = nextOffset != null && nextOffset != cursor.offset && page.items.isNotEmpty(),
                )
            } else {
                val pageNum = cursor.page.coerceAtLeast(1)
                val page = BiliApi.spaceArcSearchPage(mid = cursor.selectedMid, pn = pageNum, ps = 30)
                VideoCardPlaylistPage(
                    cards = page.items,
                    nextCursor =
                        FeedContinuationCursor(
                            selectedMid = cursor.selectedMid,
                            page = pageNum + 1,
                            offset = null,
                        ),
                    hasMore = page.hasMore,
                    canAdvance = page.hasMore && page.items.isNotEmpty(),
                )
            }
        }
}
