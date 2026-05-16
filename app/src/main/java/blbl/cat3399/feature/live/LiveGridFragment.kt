package blbl.cat3399.feature.live

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.LiveRoomCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.paging.PagedGridStateMachine
import blbl.cat3399.core.paging.appliedOrNull
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.GridViewportFillMonitor
import blbl.cat3399.core.ui.GridSpanPolicy
import blbl.cat3399.core.ui.TabContentSwitchFocusHost
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.postDelayedIfAlive
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.postIfAttached
import blbl.cat3399.core.ui.installGridViewportFillMonitor
import blbl.cat3399.core.ui.requestFocusAdapterPositionReliable
import blbl.cat3399.core.ui.requestFocusFirstItemOrSelfAfterRefresh
import blbl.cat3399.databinding.FragmentLiveGridBinding
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class LiveGridFragment : Fragment(), LivePageFocusTarget, RefreshKeyHandler {
    private var _binding: FragmentLiveGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: LiveRoomAdapter

    private var initialLoadTriggered: Boolean = false
    private var lastSpanCountContentWidthPx: Int = -1

    private val source: String by lazy { requireArguments().getString(ARG_SOURCE) ?: SRC_RECOMMEND }
    private val enableTabFocus: Boolean by lazy { requireArguments().getBoolean(ARG_ENABLE_TAB_FOCUS, true) }
    private val searchKeyword: String by lazy { requireArguments().getString(ARG_SEARCH_KEYWORD).orEmpty().trim() }

    private val loadedRoomIds = HashSet<Long>()
    private val paging = PagedGridStateMachine(initialKey = 1)

    private var pendingFocusFirstCardFromTab: Boolean = false
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false
    private var pendingFocusFirstCardFromBackToTab0: Boolean = false
    private var pendingFocusFirstCardAfterRefresh: Boolean = false
    private var lastFocusedAdapterPosition: Int? = null
    private var dpadGridController: DpadGridController? = null
    private var viewportFillMonitor: GridViewportFillMonitor? = null
    private var pendingRestorePosition: Int? = null
    private var pendingRestoreAttemptsLeft: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveGridBinding.inflate(inflater, container, false)
        AppLog.d("LiveGrid", "onCreateView src=$source t=${SystemClock.uptimeMillis()}")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter =
                LiveRoomAdapter { position, room ->
                    if (!room.isLive) {
                        AppToast.show(requireContext(), "未开播")
                        return@LiveRoomAdapter
                    }
                    // Restore focus to the clicked card after returning from LivePlayerActivity.
                    pendingRestorePosition = position
                    // Returning from another Activity can take a few frames before RecyclerView lays out children.
                    pendingRestoreAttemptsLeft = 24
                    startActivity(
                        Intent(requireContext(), LivePlayerActivity::class.java)
                            .putExtra(LivePlayerActivity.EXTRA_ROOM_ID, room.roomId)
                            .putExtra(LivePlayerActivity.EXTRA_TITLE, room.title)
                            .putExtra(LivePlayerActivity.EXTRA_UNAME, room.uname),
                    )
                }
        }

        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth())
        (binding.recycler.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.clearOnScrollListeners()
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val s = paging.snapshot()
                    if (s.isLoading || s.endReached) return
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    if (total <= 0) return
                    if (total - lastVisible - 1 <= 8) loadNextPage()
                }
            },
        )
        binding.recycler.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val widthChanged = (right - left) != (oldRight - oldLeft)
            if (widthChanged) updateRecyclerSpanCountIfNeeded()
        }
        val createdBinding = binding
        createdBinding.recycler.postIfAlive(isAlive = { _binding === createdBinding && isAdded }) { updateRecyclerSpanCountIfNeeded() }
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            return if (enableTabFocus) {
                                focusSelectedTabIfAvailable()
                            } else {
                                focusBackButtonIfAvailable()
                            }
                        }

                        override fun onLeftEdge(): Boolean {
                            if (!enableTabFocus) {
                                focusBackButtonIfAvailable()
                                return true
                            }
                            return switchToPrevTabFromContentEdge()
                        }

                        override fun onRightEdge() {
                            if (enableTabFocus) switchToNextTabFromContentEdge()
                        }

                        override fun canLoadMore(): Boolean = !paging.snapshot().endReached

                        override fun loadMore() {
                            loadNextPage()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                    ),
            ).also { it.install() }
        viewportFillMonitor?.release()
        viewportFillMonitor =
            binding.recycler.installGridViewportFillMonitor(
                isEnabled = { _binding != null && isResumed },
                canLoadMore = {
                    val s = paging.snapshot()
                    !s.isLoading && !s.endReached
                },
                loadMore = { loadNextPage() },
            )

        binding.swipeRefresh.setOnRefreshListener { resetAndLoad(fromUserRefresh = true) }
    }

    override fun onResume() {
        super.onResume()
        updateRecyclerSpanCountIfNeeded(force = true)
        viewportFillMonitor?.scheduleCheck()
        maybeTriggerInitialLoad()
        restoreFocusIfNeeded()
        maybeConsumePendingFocusFirstCard()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        resetAndLoad(fromUserRefresh = true)
        return true
    }

    private fun maybeTriggerInitialLoad() {
        if (initialLoadTriggered) return
        if (!this::adapter.isInitialized) return
        if (adapter.itemCount != 0) {
            initialLoadTriggered = true
            return
        }
        if (binding.swipeRefresh.isRefreshing) return
        binding.swipeRefresh.isRefreshing = true
        resetAndLoad(fromUserRefresh = false)
        initialLoadTriggered = true
    }

    private fun resetAndLoad(fromUserRefresh: Boolean = false) {
        if (fromUserRefresh) {
            pendingFocusFirstCardAfterRefresh = true
            pendingRestorePosition = null
            pendingRestoreAttemptsLeft = 0
            clearPendingFocusFlags()
            dpadGridController?.parkFocusForDataSetReset()
        }
        paging.reset()
        loadedRoomIds.clear()
        loadNextPage(isRefresh = true)
    }

    private data class FetchedPage(
        val items: List<LiveRoomCard>,
        val hasMore: Boolean?,
    )

    private fun loadNextPage(isRefresh: Boolean = false) {
        val startSnap = paging.snapshot()
        if (startSnap.isLoading || startSnap.endReached) return
        val startGen = startSnap.generation
        val startPage = startSnap.nextKey
        val startAt = SystemClock.uptimeMillis()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result =
                    paging.loadNextPage(
                        isRefresh = isRefresh,
                        fetch = { page ->
                            when (source) {
                                SRC_FOLLOWING -> {
                                    val res = BiliApi.liveFollowing(page = page, pageSize = 10)
                                    FetchedPage(items = res.items, hasMore = res.hasMore)
                                }
                                SRC_SEARCH -> {
                                    val keyword = searchKeyword
                                    if (keyword.isBlank()) {
                                        FetchedPage(items = emptyList(), hasMore = false)
                                    } else {
                                        val res = BiliApi.searchLiveRoom(keyword = keyword, page = page, order = "online")
                                        FetchedPage(
                                            items = res.items,
                                            hasMore = res.items.isNotEmpty() && (res.pages <= 0 || res.page < res.pages),
                                        )
                                    }
                                }
                                else -> FetchedPage(items = BiliApi.liveRecommend(page = page), hasMore = null)
                            }
                        },
                        reduce = { page, fetched ->
                            val seen = HashSet<Long>(fetched.items.size)
                            val filtered =
                                fetched.items.filter {
                                    if (loadedRoomIds.contains(it.roomId)) return@filter false
                                    seen.add(it.roomId)
                                }
                            val endReached =
                                when (source) {
                                    SRC_FOLLOWING -> fetched.hasMore == false
                                    SRC_SEARCH -> fetched.hasMore == false
                                    else -> fetched.items.isEmpty() || (filtered.isEmpty() && page >= 8)
                                }
                            PagedGridStateMachine.Update(
                                items = filtered,
                                nextKey = page + 1,
                                endReached = endReached,
                            )
                        },
                    )

                val applied = result.appliedOrNull() ?: return@launch
                applied.items.forEach { loadedRoomIds.add(it.roomId) }
                if (applied.isRefresh) adapter.submit(applied.items) else if (applied.items.isNotEmpty()) adapter.append(applied.items)
                _binding?.let { b ->
                    b.recycler.postIfAlive(isAlive = { _binding === b && isResumed }) {
                        if (pendingFocusFirstCardAfterRefresh && applied.isRefresh) {
                            pendingFocusFirstCardAfterRefresh = false
                            clearPendingFocusFlags()
                            pendingRestorePosition = null
                            pendingRestoreAttemptsLeft = 0
                            lastFocusedAdapterPosition = adapter.itemCount.takeIf { it > 0 }?.let { 0 }

                            val recycler = b.recycler
                            val isUiAlive = { _binding === b && isResumed }
                            recycler.requestFocusFirstItemOrSelfAfterRefresh(
                                itemCount = adapter.itemCount,
                                smoothScroll = false,
                                isAlive = isUiAlive,
                                onDone = { focusedFirstItem ->
                                    if (focusedFirstItem) lastFocusedAdapterPosition = 0
                                    dpadGridController?.unparkFocusAfterDataSetReset()
                                },
                            )
                            viewportFillMonitor?.scheduleCheck()
                            return@postIfAlive
                        }
                        restoreFocusIfNeeded()
                        maybeConsumePendingFocusFirstCard()
                        dpadGridController?.consumePendingFocusAfterLoadMore()
                        viewportFillMonitor?.scheduleCheck()
                    }
                }
                AppLog.i(
                    "LiveGrid",
                    "load ok src=$source page=$startPage add=${applied.items.size} total=${adapter.itemCount} cost=${SystemClock.uptimeMillis() - startAt}ms",
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("LiveGrid", "load failed src=$source page=$startPage", t)
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                if (isRefresh && paging.snapshot().generation == startGen) _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    private fun spanCountForWidth(): Int {
        val override = BiliClient.prefs.gridSpanCount
        if (override > 0) return override.coerceIn(1, 6)
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return GridSpanPolicy.autoSpanCountForWidthDp(
            widthDp = widthDp,
            overrideSpanCount = override,
            uiScale = UiScale.factor(requireContext()),
        )
    }

    private fun autoSpanCountForWidthDp(widthDp: Float): Int {
        val override = BiliClient.prefs.gridSpanCount
        if (override > 0) return override.coerceIn(1, 6)
        return GridSpanPolicy.autoSpanCountForWidthDp(
            widthDp = widthDp,
            overrideSpanCount = override,
            uiScale = UiScale.factor(requireContext()),
        )
    }

    private fun updateRecyclerSpanCountIfNeeded(force: Boolean = false) {
        val b = _binding ?: return
        val recycler = b.recycler
        val lm = recycler.layoutManager as? GridLayoutManager ?: return
        val contentWidthPx = (recycler.width - recycler.paddingLeft - recycler.paddingRight).coerceAtLeast(0)
        if (contentWidthPx <= 0) return
        if (!force && contentWidthPx == lastSpanCountContentWidthPx) return
        lastSpanCountContentWidthPx = contentWidthPx

        val dm = resources.displayMetrics
        val contentWidthDp = contentWidthPx / dm.density
        val span = autoSpanCountForWidthDp(contentWidthDp)
        if (span != lm.spanCount) lm.spanCount = span
        viewportFillMonitor?.scheduleCheck()
    }

    override fun requestFocusFirstCardFromTab(): Boolean {
        pendingFocusFirstCardFromTab = true
        pendingFocusFirstCardFromContentSwitch = false
        pendingFocusFirstCardFromBackToTab0 = false
        pendingRestorePosition = null
        pendingRestoreAttemptsLeft = 0
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    override fun requestFocusFirstCardFromContentSwitch(): Boolean {
        pendingFocusFirstCardFromContentSwitch = true
        pendingFocusFirstCardFromTab = false
        pendingFocusFirstCardFromBackToTab0 = false
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    override fun requestFocusFirstCardFromBackToTab0(): Boolean {
        pendingFocusFirstCardFromBackToTab0 = true
        pendingFocusFirstCardFromTab = false
        pendingFocusFirstCardFromContentSwitch = false
        pendingRestorePosition = null
        pendingRestoreAttemptsLeft = 0
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    private fun maybeConsumePendingFocusFirstCard(): Boolean {
        if (!pendingFocusFirstCardFromTab && !pendingFocusFirstCardFromContentSwitch && !pendingFocusFirstCardFromBackToTab0) return false
        if (!isAdded || _binding == null) return false
        if (!isResumed) return false
        if (pendingRestorePosition != null) return false

        val focused = activity?.currentFocus
        if (focused != null && focused != binding.recycler && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
            // If we are already focused inside the grid, consider the request satisfied, except for
            // "Back -> tab0 content" which must deterministically land on the first card.
            val holder = binding.recycler.findContainingViewHolder(focused)
            val pos = holder?.bindingAdapterPosition?.takeIf { it != RecyclerView.NO_POSITION }
            if (pendingFocusFirstCardFromBackToTab0) {
                if (pos == 0) {
                    lastFocusedAdapterPosition = 0
                    clearPendingFocusFlags()
                    return false
                }
            } else {
                rememberFocusedAdapterPositionFromView(focused)
                clearPendingFocusFlags()
                return false
            }
        }

        val parentView = parentFragment?.view
        val tabLayout =
            parentView?.findViewById<com.google.android.material.tabs.TabLayout?>(blbl.cat3399.R.id.tab_layout)
        if (pendingFocusFirstCardFromTab) {
            if (focused == null || tabLayout == null || !FocusTreeUtils.isDescendantOf(focused, tabLayout)) {
                pendingFocusFirstCardFromTab = false
            }
        }

        if (!this::adapter.isInitialized) return false
        if (adapter.itemCount <= 0) {
            binding.recycler.requestFocus()
            return true
        }

        val targetPosition = resolvePendingFocusTarget(itemCount = adapter.itemCount)
        val b = _binding ?: return false
        val recycler = b.recycler
        val isUiAlive = { _binding === b && isResumed }
        recycler.requestFocusAdapterPositionReliable(
            position = targetPosition,
            smoothScroll = false,
            isAlive = isUiAlive,
            onFocused = {
                lastFocusedAdapterPosition = targetPosition
                clearPendingFocusFlags()
            },
        )
        return true
    }

    private fun resolvePendingFocusTarget(itemCount: Int): Int {
        if (pendingFocusFirstCardFromTab || pendingFocusFirstCardFromBackToTab0) return 0
        if (!pendingFocusFirstCardFromContentSwitch) return 0
        val saved = lastFocusedAdapterPosition ?: return 0
        return saved.coerceIn(0, itemCount - 1)
    }

    private fun clearPendingFocusFlags() {
        pendingFocusFirstCardFromTab = false
        pendingFocusFirstCardFromContentSwitch = false
        pendingFocusFirstCardFromBackToTab0 = false
    }

    private fun captureCurrentFocusedAdapterPosition() {
        val recycler = _binding?.recycler ?: return
        val focused = activity?.currentFocus ?: return
        rememberFocusedAdapterPositionFromView(focused, recycler)
    }

    private fun rememberFocusedAdapterPositionFromView(
        focusedView: View,
        recycler: RecyclerView = binding.recycler,
    ) {
        val holder = recycler.findContainingViewHolder(focusedView) ?: return
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        lastFocusedAdapterPosition = position
    }

    private fun restoreFocusIfNeeded(): Boolean {
        // Mirror the working "MyFavFoldersFragment"/"MyBangumiFollowFragment" restore pattern:
        // keep a pending position, scrollToPosition, then requestFocus on that ViewHolder.
        val pos = pendingRestorePosition ?: return false
        val b = _binding ?: return false
        if (!isResumed) return false
        if (!this::adapter.isInitialized) return false

        val itemCount = adapter.itemCount
        if (pos < 0 || (itemCount > 0 && pos >= itemCount)) {
            pendingRestorePosition = null
            pendingRestoreAttemptsLeft = 0
            return false
        }

        val recycler = b.recycler
        val isUiAlive = { _binding === b && isResumed }
        // Do not "give up" just because some other view currently holds focus (e.g. app menu / back button).
        // Returning from another Activity can reset focus unexpectedly; our goal is to bring it back to the grid.

        // If data isn't ready yet, keep pending. Make sure focus doesn't "disappear" completely.
        if (itemCount <= 0) {
            // Keep focus inside this page so the user doesn't accidentally navigate to other pages.
            focusBackButtonIfAvailable() || focusSelectedTabIfAvailable() || recycler.requestFocus()
            return false
        }

        // Fast-path: already laid out.
        recycler.findViewHolderForAdapterPosition(pos)?.itemView?.let { target ->
            if (target.requestFocus()) {
                pendingRestorePosition = null
                pendingRestoreAttemptsLeft = 0
                return true
            }
            // requestFocus can fail briefly right after returning from another Activity; keep pending and retry.
        }

        recycler.postIfAlive(isAlive = isUiAlive) {
            recycler.scrollToPosition(pos)
            recycler.postIfAlive(isAlive = isUiAlive) inner@{
                val vh = recycler.findViewHolderForAdapterPosition(pos)
                if (vh != null) {
                    if (vh.itemView.requestFocus()) {
                        pendingRestorePosition = null
                        pendingRestoreAttemptsLeft = 0
                        return@inner
                    }
                    // If focus request fails, keep pending and retry below.
                }

                // Still not laid out (vh == null) OR requestFocus failed; retry a few times.
                pendingRestoreAttemptsLeft--
                if (pendingRestoreAttemptsLeft <= 0) {
                    // Fallback: focus something visible (prefer the first card) so focus highlight shows.
                    recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true ||
                        focusBackButtonIfAvailable() ||
                        focusSelectedTabIfAvailable() ||
                        recycler.requestFocus()
                    pendingRestorePosition = null
                    pendingRestoreAttemptsLeft = 0
                } else {
                    recycler.postDelayedIfAlive(delayMillis = 16L, isAlive = isUiAlive) { restoreFocusIfNeeded() }
                }
            }
        }
        return true
    }

    private fun focusSelectedTabIfAvailable(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(blbl.cat3399.R.id.tab_layout) ?: return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val pos = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        tabStrip.getChildAt(pos)?.requestFocus() ?: return false
        return true
    }

    private fun focusBackButtonIfAvailable(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val back = parentView.findViewById<View?>(blbl.cat3399.R.id.btn_back) ?: return false
        return back.requestFocus()
    }

    private fun switchToNextTabFromContentEdge(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(blbl.cat3399.R.id.tab_layout) ?: return false
        if (tabLayout.tabCount <= 1) return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val cur = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        val next = cur + 1
        if (next >= tabLayout.tabCount) return false
        captureCurrentFocusedAdapterPosition()
        tabLayout.getTabAt(next)?.select() ?: return false
        tabLayout.postIfAttached {
            (parentFragment as? TabContentSwitchFocusHost)?.requestFocusCurrentPagePrimaryItemFromContentSwitch()
                ?: tabStrip.getChildAt(next)?.requestFocus()
        }
        return true
    }

    private fun switchToPrevTabFromContentEdge(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(blbl.cat3399.R.id.tab_layout) ?: return false
        if (tabLayout.tabCount <= 1) return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val cur = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        val prev = cur - 1
        if (prev < 0) return false
        captureCurrentFocusedAdapterPosition()
        tabLayout.getTabAt(prev)?.select() ?: return false
        tabLayout.postIfAttached {
            (parentFragment as? TabContentSwitchFocusHost)?.requestFocusCurrentPagePrimaryItemFromContentSwitch()
                ?: tabStrip.getChildAt(prev)?.requestFocus()
        }
        return true
    }

    override fun onDestroyView() {
        initialLoadTriggered = false
        dpadGridController?.release()
        dpadGridController = null
        viewportFillMonitor?.release()
        viewportFillMonitor = null
        lastSpanCountContentWidthPx = -1
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_SOURCE = "source"
        private const val ARG_ENABLE_TAB_FOCUS = "enable_tab_focus"
        private const val ARG_SEARCH_KEYWORD = "search_keyword"

        const val SRC_RECOMMEND = "recommend"
        const val SRC_FOLLOWING = "following"
        const val SRC_SEARCH = "search"

        fun newRecommend() = LiveGridFragment().apply { arguments = Bundle().apply { putString(ARG_SOURCE, SRC_RECOMMEND) } }

        fun newFollowing() = LiveGridFragment().apply { arguments = Bundle().apply { putString(ARG_SOURCE, SRC_FOLLOWING) } }

        fun newSearch(keyword: String) = LiveGridFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SOURCE, SRC_SEARCH)
                putString(ARG_SEARCH_KEYWORD, keyword.trim())
            }
        }
    }
}
