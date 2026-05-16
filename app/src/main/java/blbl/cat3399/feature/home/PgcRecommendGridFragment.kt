package blbl.cat3399.feature.home

import android.os.Bundle
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiSeason
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.TabContentSwitchFocusHost
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.TabSwitchFocusTarget
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.postIfAttached
import blbl.cat3399.core.ui.requestFocusAdapterPositionReliable
import blbl.cat3399.core.ui.requestFocusFirstItemOrSelfAfterRefresh
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.feature.my.BangumiFollowAdapter
import blbl.cat3399.feature.my.BangumiDetailActivity
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class PgcRecommendGridFragment : Fragment(), RefreshKeyHandler, TabSwitchFocusTarget {
    private data class PgcPagingKey(
        val cursor: String? = null,
        val page: Int = 1,
    )

    private data class FetchedPage(
        val items: List<BangumiSeason>,
        val nextKey: PgcPagingKey,
        val hasNext: Boolean,
    )

    private sealed interface PgcGridSource {
        val isDrama: Boolean
        val logName: String

        suspend fun fetch(key: PgcPagingKey): FetchedPage

        data object BangumiRecommend : PgcGridSource {
            override val isDrama: Boolean = false
            override val logName: String = "bangumi_recommend"

            override suspend fun fetch(key: PgcPagingKey): FetchedPage {
                val res = BiliApi.pgcBangumiPage(cursor = key.cursor)
                return FetchedPage(
                    items = res.items,
                    nextKey = key.copy(cursor = res.nextCursor),
                    hasNext = res.hasNext,
                )
            }
        }

        data object CinemaRecommend : PgcGridSource {
            override val isDrama: Boolean = true
            override val logName: String = "cinema_recommend"

            override suspend fun fetch(key: PgcPagingKey): FetchedPage {
                val res = BiliApi.pgcCinemaTabPage(cursor = key.cursor)
                return FetchedPage(
                    items = res.items,
                    nextKey = key.copy(cursor = res.nextCursor),
                    hasNext = res.hasNext,
                )
            }
        }

        data class BangumiSearch(private val keyword: String) : PgcGridSource {
            override val isDrama: Boolean = false
            override val logName: String = "bangumi_search"

            override suspend fun fetch(key: PgcPagingKey): FetchedPage {
                return fetchSearchPage(keyword = keyword, key = key, isDrama = false)
            }
        }

        data class CinemaSearch(private val keyword: String) : PgcGridSource {
            override val isDrama: Boolean = true
            override val logName: String = "cinema_search"

            override suspend fun fetch(key: PgcPagingKey): FetchedPage {
                return fetchSearchPage(keyword = keyword, key = key, isDrama = true)
            }
        }
    }

    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private val source: PgcGridSource by lazy { sourceFromArguments(requireArguments()) }

    private lateinit var adapter: BangumiFollowAdapter
    private val loadedSeasonIds = HashSet<Long>()

    private var nextKey: PgcPagingKey = PgcPagingKey()
    private var hasNext: Boolean = true
    private var isLoadingMore: Boolean = false
    private var requestToken: Int = 0
    private var initialLoadTriggered: Boolean = false

    private var pendingRestorePosition: Int? = null
    private var pendingFocusFirstCardAfterRefresh: Boolean = false

    private var pendingFocusFirstCardFromTab: Boolean = false
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false
    private var pendingFocusFirstCardFromBackToTab0: Boolean = false
    private var lastFocusedAdapterPosition: Int? = null
    private var dpadGridController: DpadGridController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter =
                BangumiFollowAdapter { position, season ->
                    pendingRestorePosition = position
                    openBangumiDetail(season)
                }
        }

        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForPgc())
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.clearOnScrollListeners()
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            focusSelectedTabIfAvailable()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            return switchToPrevTabFromContentEdge()
                        }

                        override fun onRightEdge() {
                            switchToNextTabFromContentEdge()
                        }

                        override fun canLoadMore(): Boolean = hasNext

                        override fun loadMore() {
                            loadNextPage()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                    ),
            ).also { it.install() }
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (isLoadingMore || !hasNext) return
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val last = lm.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    if (total <= 0) return
                    if (total - last - 1 <= 8) loadNextPage()
                }
            },
        )

        binding.swipeRefresh.setOnRefreshListener { resetAndLoad(fromUserRefresh = true) }
    }

    override fun onResume() {
        super.onResume()
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForPgc()
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

    private fun spanCountForPgc(): Int = BiliClient.prefs.pgcGridSpanCount.coerceIn(1, 6)

    override fun requestFocusFirstCardFromTab(): Boolean {
        pendingFocusFirstCardFromTab = true
        pendingFocusFirstCardFromContentSwitch = false
        pendingFocusFirstCardFromBackToTab0 = false
        pendingRestorePosition = null
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
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    private fun maybeConsumePendingFocusFirstCard(): Boolean {
        if (!pendingFocusFirstCardFromTab && !pendingFocusFirstCardFromContentSwitch && !pendingFocusFirstCardFromBackToTab0) return false
        if (!isAdded || _binding == null) return false
        if (!isResumed) return false
        if (!this::adapter.isInitialized) return false
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
            parentView?.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout)
        if (pendingFocusFirstCardFromTab) {
            if (focused == null || tabLayout == null || !FocusTreeUtils.isDescendantOf(focused, tabLayout)) {
                pendingFocusFirstCardFromTab = false
            }
        }

        if (adapter.itemCount <= 0) {
            binding.recycler.requestFocus()
            return true
        }

        val targetPosition = resolvePendingFocusTarget(itemCount = adapter.itemCount)
        val recycler = binding.recycler
        recycler.requestFocusAdapterPositionReliable(
            position = targetPosition,
            smoothScroll = false,
            isAlive = { _binding != null && isResumed },
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
        isLoadingMore = false
        nextKey = PgcPagingKey()
        hasNext = true
        loadedSeasonIds.clear()
        requestToken++
        dpadGridController?.clearPendingFocusAfterLoadMore()
        if (fromUserRefresh) {
            pendingFocusFirstCardAfterRefresh = true
            pendingRestorePosition = null
            clearPendingFocusFlags()
            dpadGridController?.parkFocusForDataSetReset()
        }
        adapter.submit(emptyList())
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || !hasNext) return
        val token = requestToken
        isLoadingMore = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val fetched = source.fetch(nextKey)
                if (token != requestToken) return@launch

                nextKey = fetched.nextKey
                hasNext = fetched.hasNext

                val filtered = fetched.items.filter { loadedSeasonIds.add(it.seasonId) }
                if (isRefresh) adapter.submit(filtered) else adapter.append(filtered)

                _binding?.let { b ->
                    b.recycler.postIfAlive(isAlive = { _binding === b && isResumed }) {
                        if (isRefresh && pendingFocusFirstCardAfterRefresh) {
                            pendingFocusFirstCardAfterRefresh = false
                            clearPendingFocusFlags()
                            pendingRestorePosition = null
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
                            return@postIfAlive
                        }
                        maybeConsumePendingFocusFirstCard()
                        dpadGridController?.consumePendingFocusAfterLoadMore()
                    }
                }
                restoreFocusIfNeeded()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("PgcRecommend", "load failed source=${source.logName} key=$nextKey", t)
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                if (token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private fun openBangumiDetail(season: BangumiSeason) {
        if (!isAdded) return
        startActivity(
            Intent(requireContext(), BangumiDetailActivity::class.java)
                .putExtra(BangumiDetailActivity.EXTRA_SEASON_ID, season.seasonId)
                .putExtra(BangumiDetailActivity.EXTRA_IS_DRAMA, source.isDrama),
        )
    }

    private fun restoreFocusIfNeeded() {
        val pos = pendingRestorePosition ?: return
        if (_binding == null) return
        if (pos < 0 || pos >= adapter.itemCount) return
        val recycler = binding.recycler
        recycler.postIfAlive(isAlive = { _binding != null }) {
            recycler.scrollToPosition(pos)
            recycler.postIfAlive(isAlive = { _binding != null }) {
                recycler.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                pendingRestorePosition = null
            }
        }
    }

    private fun focusSelectedTabIfAvailable(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout) ?: return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val pos = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        tabStrip.getChildAt(pos)?.requestFocus() ?: return false
        return true
    }

    private fun switchToNextTabFromContentEdge(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout) ?: return false
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
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout) ?: return false
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
        pendingRestorePosition = null
        pendingFocusFirstCardFromTab = false
        pendingFocusFirstCardFromContentSwitch = false
        dpadGridController?.release()
        dpadGridController = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val ARG_SEARCH_KEYWORD = "search_keyword"

        private const val KIND_BANGUMI = 1
        private const val KIND_CINEMA = 2
        private const val KIND_SEARCH_BANGUMI = 3
        private const val KIND_SEARCH_CINEMA = 4

        private fun sourceFromArguments(args: Bundle): PgcGridSource {
            val keyword = args.getString(ARG_SEARCH_KEYWORD).orEmpty().trim()
            return when (args.getInt(ARG_KIND)) {
                KIND_BANGUMI -> PgcGridSource.BangumiRecommend
                KIND_SEARCH_BANGUMI -> PgcGridSource.BangumiSearch(keyword)
                KIND_SEARCH_CINEMA -> PgcGridSource.CinemaSearch(keyword)
                else -> PgcGridSource.CinemaRecommend
            }
        }

        private suspend fun fetchSearchPage(
            keyword: String,
            key: PgcPagingKey,
            isDrama: Boolean,
        ): FetchedPage {
            if (keyword.isBlank()) {
                return FetchedPage(
                    items = emptyList(),
                    nextKey = key.copy(page = key.page + 1),
                    hasNext = false,
                )
            }

            val res =
                if (isDrama) {
                    BiliApi.searchMediaFt(keyword = keyword, page = key.page, order = "totalrank")
                } else {
                    BiliApi.searchMediaBangumi(keyword = keyword, page = key.page, order = "totalrank")
                }
            return FetchedPage(
                items = res.items,
                nextKey = key.copy(page = key.page + 1),
                hasNext = res.items.isNotEmpty() && (res.pages <= 0 || res.page < res.pages),
            )
        }

        fun newBangumi(): PgcRecommendGridFragment = PgcRecommendGridFragment().apply {
            arguments = Bundle().apply { putInt(ARG_KIND, KIND_BANGUMI) }
        }

        fun newCinema(): PgcRecommendGridFragment = PgcRecommendGridFragment().apply {
            arguments = Bundle().apply { putInt(ARG_KIND, KIND_CINEMA) }
        }

        fun newSearchBangumi(keyword: String): PgcRecommendGridFragment = PgcRecommendGridFragment().apply {
            arguments =
                Bundle().apply {
                    putInt(ARG_KIND, KIND_SEARCH_BANGUMI)
                    putString(ARG_SEARCH_KEYWORD, keyword.trim())
                }
        }

        fun newSearchCinema(keyword: String): PgcRecommendGridFragment = PgcRecommendGridFragment().apply {
            arguments =
                Bundle().apply {
                    putInt(ARG_KIND, KIND_SEARCH_CINEMA)
                    putString(ARG_SEARCH_KEYWORD, keyword.trim())
                }
        }
    }
}
