package blbl.cat3399.feature.search

import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.paging.PagedGridStateMachine
import blbl.cat3399.core.paging.appliedOrNull
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.popup.AppPopup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchInteractor(
    private val fragment: SearchFragment,
    private val state: SearchState,
) {
    lateinit var renderer: SearchRenderer

    private var suggestJob: Job? = null

    fun release() {
        suggestJob?.cancel()
        suggestJob = null
    }

    fun reloadHistory() {
        state.history = BiliClient.prefs.searchHistory
    }

    fun renderInputPanelFromState() {
        renderer.updateQueryUi()
        renderer.updateMiddleUi(historyMatches(state.query), extra = state.suggestKeywords)
        renderer.updateClearHistoryButton(state.query)
        renderer.updateHotUi(state.hotKeywords)
    }

    fun clearHistory() {
        BiliClient.prefs.clearSearchHistory()
        reloadHistory()
        state.suggestKeywords = emptyList()
        renderer.updateMiddleUi(historyMatches(state.query), extra = state.suggestKeywords)
        renderer.updateClearHistoryButton(state.query)
        renderer.focusFirstKey()
    }

    fun loadHotAndDefault() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val hint = BiliApi.searchDefaultText()
                state.defaultHint = hint
                renderer.updateQueryUi()
            }
        }
        if (state.hotKeywords.isNotEmpty()) {
            renderer.updateHotUi(state.hotKeywords)
            return
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val hot = BiliApi.searchHot(limit = 12)
                state.hotKeywords = hot
                renderer.updateHotUi(hot)
            }.onFailure {
                AppLog.w("Search", "load hot failed", it)
            }
        }
    }

    fun onKeyClicked(key: String) {
        if (renderer.isResultsVisible()) renderer.showInput()
        setQuery(state.query + key)
    }

    fun onKeywordClicked(keyword: String) {
        setQuery(keyword)
        performSearch()
    }

    fun onQueryTextChangedFromIme(text: String) {
        val trimmed = text.trim()
        if (state.query == trimmed) {
            renderer.updateQueryAlpha(query = trimmed)
            return
        }
        state.query = trimmed
        renderer.updateQueryAlpha(query = trimmed)
        scheduleMiddleList(trimmed)
    }

    fun setQuery(value: String) {
        val trimmed = value.trim()
        if (state.query == trimmed) return
        state.query = trimmed
        renderer.updateQueryUi()
        scheduleMiddleList(trimmed)
    }

    fun scheduleMiddleList(term: String) {
        suggestJob?.cancel()
        if (term.isBlank()) {
            state.suggestKeywords = emptyList()
            renderer.updateMiddleUi(historyMatches(term), extra = state.suggestKeywords)
            renderer.updateClearHistoryButton(term)
            return
        }
        state.suggestKeywords = emptyList()
        renderer.updateMiddleUi(historyMatches(term), extra = state.suggestKeywords)
        renderer.updateClearHistoryButton(term)
        suggestJob =
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                delay(200)
                runCatching { BiliApi.searchSuggest(term.lowercase()) }
                    .onSuccess {
                        state.suggestKeywords = it
                        renderer.updateMiddleUi(historyMatches(term), extra = state.suggestKeywords)
                    }
                    .onFailure {
                        AppLog.w("Search", "suggest failed term=${term.take(16)}", it)
                        state.suggestKeywords = emptyList()
                        renderer.updateMiddleUi(historyMatches(term), extra = state.suggestKeywords)
                    }
            }
    }

    fun performSearch() {
        val keyword = effectiveKeyword().trim()
        if (keyword.isBlank()) return

        // Ensure query and UI reflect the actual keyword used.
        setQuery(keyword)

        BiliClient.prefs.addSearchHistory(keyword)
        reloadHistory()

        renderer.hideImeAndClearQueryFocusIfNeeded()
        renderer.showResults()
        renderer.parkResultsFocusForDataSetReset()

        // New keyword: drop per-tab "memory" so switching tabs will refresh on-demand.
        state.clearAllTabMemories()
        state.clearAllFocusedResultPositions()
        state.clearPendingResultFocusRequests()
        for (i in SearchTab.entries.indices) {
            state.clearLoadedForTab(i)
            state.pagingForTab(i).reset()
            renderer.clearResultsForTab(i)
        }

        resetAndLoad()
        renderer.focusSelectedTabAfterShow()
    }

    fun onTabSelected(position: Int) {
        state.currentTabIndex = position
        if (!renderer.isResultsVisible()) return
        renderer.switchTab(position)
        if (!state.hasMemoryForTab(position)) {
            resetAndLoad()
        }
    }

    fun onTabReselected(position: Int) {
        state.currentTabIndex = position
        if (!renderer.isResultsVisible()) return
        renderer.switchTab(position)
        resetAndLoad()
    }

    fun showSortDialog() {
        val context = fragment.context ?: return
        when (state.tabForIndex(state.currentTabIndex)) {
            SearchTab.Video -> {
                val items = VideoOrder.entries
                val labels = items.map { context.getString(it.labelRes) }
                val checked = items.indexOf(state.currentVideoOrder).coerceAtLeast(0)
                AppPopup.singleChoice(
                    context = context,
                    title = context.getString(R.string.search_sort_title),
                    items = labels,
                    checkedIndex = checked,
                ) { which, _ ->
                    val picked = items.getOrNull(which) ?: return@singleChoice
                    if (picked != state.currentVideoOrder) {
                        state.currentVideoOrder = picked
                        renderer.updateSortUi()
                        resetAndLoad()
                    }
                }
            }

            SearchTab.Bangumi,
            SearchTab.Media,
            -> Unit

            SearchTab.Live -> {
                val items = LiveOrder.entries
                val labels = items.map { context.getString(it.labelRes) }
                val checked = items.indexOf(state.currentLiveOrder).coerceAtLeast(0)
                AppPopup.singleChoice(
                    context = context,
                    title = context.getString(R.string.search_sort_title),
                    items = labels,
                    checkedIndex = checked,
                ) { which, _ ->
                    val picked = items.getOrNull(which) ?: return@singleChoice
                    if (picked != state.currentLiveOrder) {
                        state.currentLiveOrder = picked
                        renderer.updateSortUi()
                        resetAndLoad()
                    }
                }
            }

            SearchTab.User -> {
                val items = UserOrder.entries
                val labels = items.map { context.getString(it.labelRes) }
                val checked = items.indexOf(state.currentUserOrder).coerceAtLeast(0)
                AppPopup.singleChoice(
                    context = context,
                    title = context.getString(R.string.search_sort_title),
                    items = labels,
                    checkedIndex = checked,
                ) { which, _ ->
                    val picked = items.getOrNull(which) ?: return@singleChoice
                    if (picked != state.currentUserOrder) {
                        state.currentUserOrder = picked
                        renderer.updateSortUi()
                        resetAndLoad()
                    }
                }
            }
        }
    }

    fun resetAndLoad() {
        if (!renderer.isResultsVisible()) return
        state.pendingFocusFirstResultCardAfterRefresh = true
        state.clearPendingResultFocusRequests()
        renderer.parkResultsFocusForDataSetReset()
        val tabIndex = state.currentTabIndex

        state.clearLoadedForTab(tabIndex)
        state.clearFocusedResultPositionForTab(tabIndex)
        renderer.clearResultsForTab(tabIndex)

        state.pagingForTab(tabIndex).reset()
        renderer.clearPendingFocusAfterLoadMore()

        renderer.scrollResultsToTop()
        val refreshToken = ++state.refreshUiToken
        renderer.setRefreshing(true)
        loadNextPage(isRefresh = true, refreshToken = refreshToken)
    }

    fun loadNextPage(isRefresh: Boolean = false, refreshToken: Int = state.refreshUiToken) {
        if (!renderer.isResultsVisible()) return

        val keyword = effectiveKeyword().trim()
        if (keyword.isBlank()) {
            if (isRefresh && state.refreshUiToken == refreshToken) renderer.setRefreshing(false)
            return
        }

        val tabIndexAtStart = state.currentTabIndex
        val tabAtStart = state.tabForIndex(tabIndexAtStart)
        val paging = state.pagingForTab(tabIndexAtStart)

        val startSnap = paging.snapshot()
        if (startSnap.isLoading || startSnap.endReached) {
            if (isRefresh && state.refreshUiToken == refreshToken) renderer.setRefreshing(false)
            return
        }

        val startGen = startSnap.generation
        val startPage = startSnap.nextKey

        val startAt = SystemClock.uptimeMillis()
        AppLog.d("Search", "load start tab=${tabAtStart.name} keyword=${keyword.take(12)} page=$startPage t=$startAt")

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (tabAtStart) {
                    SearchTab.Video -> {
                        loadPaged(
                            paging = paging,
                            isRefresh = isRefresh,
                            tabIndexAtStart = tabIndexAtStart,
                            tabAtStart = tabAtStart,
                            fetch = { page ->
                                BiliApi.searchVideo(keyword = keyword, page = page, order = state.currentVideoOrder.apiValue)
                            },
                            idOf = { it.bvid },
                            loaded = state.loadedBvids,
                            endReachedOf = { page, res, filtered ->
                                filtered.isEmpty() || (res.pages > 0 && page >= res.pages)
                            },
                            apply = { items, appliedRefresh ->
                                if (appliedRefresh) renderer.videoAdapter.submit(items) else renderer.videoAdapter.append(items)
                            },
                        )
                    }

                    SearchTab.Bangumi -> {
                        loadPaged(
                            paging = paging,
                            isRefresh = isRefresh,
                            tabIndexAtStart = tabIndexAtStart,
                            tabAtStart = tabAtStart,
                            fetch = { page ->
                                BiliApi.searchMediaBangumi(keyword = keyword, page = page, order = "totalrank")
                            },
                            idOf = { it.seasonId },
                            loaded = state.loadedBangumiSeasonIds,
                            endReachedOf = { page, res, filtered ->
                                filtered.isEmpty() || (res.pages > 0 && page >= res.pages)
                            },
                            apply = { items, appliedRefresh ->
                                if (appliedRefresh) renderer.mediaAdapter.submit(items) else renderer.mediaAdapter.append(items)
                            },
                        )
                    }

                    SearchTab.Media -> {
                        loadPaged(
                            paging = paging,
                            isRefresh = isRefresh,
                            tabIndexAtStart = tabIndexAtStart,
                            tabAtStart = tabAtStart,
                            fetch = { page ->
                                BiliApi.searchMediaFt(keyword = keyword, page = page, order = "totalrank")
                            },
                            idOf = { it.seasonId },
                            loaded = state.loadedMediaSeasonIds,
                            endReachedOf = { page, res, filtered ->
                                filtered.isEmpty() || (res.pages > 0 && page >= res.pages)
                            },
                            apply = { items, appliedRefresh ->
                                if (appliedRefresh) renderer.mediaAdapter.submit(items) else renderer.mediaAdapter.append(items)
                            },
                        )
                    }

                    SearchTab.Live -> {
                        loadPaged(
                            paging = paging,
                            isRefresh = isRefresh,
                            tabIndexAtStart = tabIndexAtStart,
                            tabAtStart = tabAtStart,
                            fetch = { page ->
                                BiliApi.searchLiveRoom(keyword = keyword, page = page, order = state.currentLiveOrder.apiValue)
                            },
                            idOf = { it.roomId },
                            loaded = state.loadedRoomIds,
                            endReachedOf = { page, res, filtered ->
                                filtered.isEmpty() || (res.pages > 0 && page >= res.pages)
                            },
                            apply = { items, appliedRefresh ->
                                if (appliedRefresh) renderer.liveAdapter.submit(items) else renderer.liveAdapter.append(items)
                            },
                        )
                    }

                    SearchTab.User -> {
                        loadPaged(
                            paging = paging,
                            isRefresh = isRefresh,
                            tabIndexAtStart = tabIndexAtStart,
                            tabAtStart = tabAtStart,
                            fetch = { page ->
                                BiliApi.searchUser(keyword = keyword, page = page, order = state.currentUserOrder.apiValue)
                            },
                            idOf = { it.mid },
                            loaded = state.loadedMids,
                            endReachedOf = { page, res, filtered ->
                                filtered.isEmpty() || (res.pages > 0 && page >= res.pages)
                            },
                            apply = { items, appliedRefresh ->
                                if (appliedRefresh) renderer.userAdapter.submit(items) else renderer.userAdapter.append(items)
                            },
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("Search", "load failed tab=${tabAtStart.name} page=$startPage", t)
                fragment.context?.let { AppToast.show(it, "搜索失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                val stillSameRefresh = isRefresh && state.refreshUiToken == refreshToken
                val stillSameGen = paging.snapshot().generation == startGen
                if (stillSameRefresh && stillSameGen) renderer.setRefreshing(false)
            }
        }
    }

    private suspend fun <Item, Id> loadPaged(
        paging: PagedGridStateMachine<Int>,
        isRefresh: Boolean,
        tabIndexAtStart: Int,
        tabAtStart: SearchTab,
        fetch: suspend (page: Int) -> BiliApi.PagedResult<Item>,
        idOf: (Item) -> Id,
        loaded: MutableSet<Id>,
        endReachedOf: (page: Int, res: BiliApi.PagedResult<Item>, filtered: List<Item>) -> Boolean,
        apply: (items: List<Item>, isRefresh: Boolean) -> Unit,
    ) {
        var latestFetched: BiliApi.PagedResult<Item>? = null
        val result =
            paging.loadNextPage(
                isRefresh = isRefresh,
                fetch = { page ->
                    fetch(page).also { latestFetched = it }
                },
                reduce = { page, res ->
                    val list = res.items
                    if (list.isEmpty()) {
                        PagedGridStateMachine.Update(
                            items = emptyList<Item>(),
                            nextKey = page,
                            endReached = true,
                        )
                    } else {
                        val seen = HashSet<Id>(list.size)
                        val filtered =
                            list.filter { item ->
                                val id = idOf(item)
                                if (loaded.contains(id)) return@filter false
                                seen.add(id)
                            }
                        PagedGridStateMachine.Update(
                            items = filtered,
                            nextKey = page + 1,
                            endReached = endReachedOf(page, res, filtered),
                        )
                    }
                },
            )

        val applied = result.appliedOrNull() ?: return
        latestFetched ?: return

        // If the user has switched tabs and started a new refresh, avoid applying side effects.
        if (state.currentTabIndex != tabIndexAtStart || state.tabForIndex(state.currentTabIndex) != tabAtStart) return
        if (!renderer.isResultsVisible()) return

        val items = applied.items
        items.forEach { loaded.add(idOf(it)) }

        apply(items, applied.isRefresh)
        renderer.onResultsApplied()
        state.markMemoryForTab(tabIndexAtStart)
    }

    private fun effectiveKeyword(): String {
        return state.query.ifBlank { state.defaultHint?.trim().orEmpty() }
    }

    private fun historyMatches(term: String, limit: Int = 12): List<String> {
        val history = state.history
        if (history.isEmpty()) return emptyList()
        val t = term.trim()
        val list =
            if (t.isBlank()) {
                history
            } else {
                history.filter { it.contains(t, ignoreCase = true) }
            }
        return list.take(limit)
    }
}
