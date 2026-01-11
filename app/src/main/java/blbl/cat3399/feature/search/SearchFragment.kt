package blbl.cat3399.feature.search

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.databinding.FragmentSearchBinding
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.video.VideoCardAdapter
import blbl.cat3399.ui.BackPressHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchFragment : Fragment(), BackPressHandler {
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var keyAdapter: SearchKeyAdapter
    private lateinit var suggestAdapter: SearchSuggestAdapter
    private lateinit var hotAdapter: SearchHotAdapter
    private lateinit var resultAdapter: VideoCardAdapter

    private var defaultHint: String? = null
    private var query: String = ""
    private var history: List<String> = emptyList()

    private var suggestJob: Job? = null

    private var lastFocusedKeyPos: Int = 0
    private var lastFocusedSuggestPos: Int = 0

    private var currentTabIndex: Int = 0
    private var currentOrder: Order = Order.TotalRank

    private val loadedBvids = HashSet<String>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var page: Int = 1
    private var requestToken: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        reloadHistory()
        setupInput()
        setupResults()
        loadHotAndDefault()

        if (savedInstanceState == null) {
            showInput()
            focusFirstKey()
        }
    }

    override fun handleBackPressed(): Boolean {
        val b = _binding ?: return false
        val resultsVisible = b.panelResults.visibility == View.VISIBLE
        AppLog.d("Back", "SearchFragment handleBackPressed resultsVisible=$resultsVisible")
        if (!resultsVisible) return false
        b.panelResults.visibility = View.GONE
        b.panelInput.visibility = View.VISIBLE
        focusFirstKey()
        return true
    }

    private fun setupInput() {
        keyAdapter = SearchKeyAdapter { key ->
            if (binding.panelResults.visibility == View.VISIBLE) showInput()
            setQuery(query + key)
        }
        suggestAdapter = SearchSuggestAdapter { keyword ->
            setQuery(keyword)
            performSearch()
        }
        hotAdapter = SearchHotAdapter { keyword ->
            setQuery(keyword)
            performSearch()
        }

        binding.recyclerKeys.adapter = keyAdapter
        binding.recyclerKeys.layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 6)
        (binding.recyclerKeys.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        keyAdapter.submit(KEYS)
        binding.recyclerKeys.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.onFocusChangeListener =
                        View.OnFocusChangeListener { v, hasFocus ->
                            if (!hasFocus) return@OnFocusChangeListener
                            val holder = binding.recyclerKeys.findContainingViewHolder(v) ?: return@OnFocusChangeListener
                            val pos =
                                holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                                    ?: return@OnFocusChangeListener
                            lastFocusedKeyPos = pos
                        }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.onFocusChangeListener = null
                }
            },
        )

        binding.recyclerSuggest.adapter = suggestAdapter
        binding.recyclerSuggest.layoutManager =
            StaggeredGridLayoutManager(1, StaggeredGridLayoutManager.VERTICAL).apply {
                gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
            }
        (binding.recyclerSuggest.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recyclerSuggest.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val holder = binding.recyclerSuggest.findContainingViewHolder(v) ?: return@setOnKeyListener false
                        val pos =
                            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                                ?: return@setOnKeyListener false

                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                focusLastKey()
                                true
                            }

                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (pos == 0) return@setOnKeyListener true
                                // Top edge: don't escape to sidebar.
                                if (!binding.recyclerSuggest.canScrollVertically(-1)) {
                                    val lm =
                                        binding.recyclerSuggest.layoutManager as? StaggeredGridLayoutManager
                                            ?: return@setOnKeyListener false
                                    val first = IntArray(lm.spanCount)
                                    lm.findFirstVisibleItemPositions(first)
                                    if (first.any { it == pos }) return@setOnKeyListener true
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val last = (binding.recyclerSuggest.adapter?.itemCount ?: 0) - 1
                                if (pos != last) return@setOnKeyListener false
                                if (binding.btnClearHistory.visibility == View.VISIBLE) {
                                    binding.btnClearHistory.requestFocus()
                                    return@setOnKeyListener true
                                }
                                // Bottom edge: don't escape to sidebar.
                                true
                            }

                            else -> false
                        }
                    }

                    view.onFocusChangeListener =
                        View.OnFocusChangeListener { v, hasFocus ->
                            if (!hasFocus) return@OnFocusChangeListener
                            val holder = binding.recyclerSuggest.findContainingViewHolder(v) ?: return@OnFocusChangeListener
                            val pos =
                                holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                                    ?: return@OnFocusChangeListener
                            lastFocusedSuggestPos = pos
                        }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.setOnKeyListener(null)
                    view.onFocusChangeListener = null
                }
            },
        )

        binding.recyclerHot.adapter = hotAdapter
        binding.recyclerHot.layoutManager =
            StaggeredGridLayoutManager(1, StaggeredGridLayoutManager.VERTICAL).apply {
                gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
            }
        (binding.recyclerHot.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recyclerHot.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val holder = binding.recyclerHot.findContainingViewHolder(v) ?: return@setOnKeyListener false
                        val pos =
                            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                                ?: return@setOnKeyListener false

                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (!focusHistoryAt(pos) && !focusLastHistory()) focusLastKey()
                                true
                            }

                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (pos == 0) return@setOnKeyListener true
                                // Top edge: don't escape to sidebar.
                                if (!binding.recyclerHot.canScrollVertically(-1)) {
                                    val lm =
                                        binding.recyclerHot.layoutManager as? StaggeredGridLayoutManager
                                            ?: return@setOnKeyListener false
                                    val first = IntArray(lm.spanCount)
                                    lm.findFirstVisibleItemPositions(first)
                                    if (first.any { it == pos }) return@setOnKeyListener true
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                // Bottom edge: don't escape to sidebar.
                                val last = (binding.recyclerHot.adapter?.itemCount ?: 0) - 1
                                if (pos != last) return@setOnKeyListener false
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

        binding.btnClear.setOnClickListener {
            setQuery("")
        }
        binding.btnClear.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return@setOnKeyListener false
            // Top edge: don't escape to sidebar.
            true
        }
        binding.btnBackspace.setOnClickListener {
            if (query.isNotEmpty()) setQuery(query.dropLast(1))
        }
        binding.btnBackspace.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return@setOnKeyListener false
            // Top edge: don't escape to sidebar.
            true
        }
        binding.btnSearch.setOnClickListener { performSearch() }
        binding.btnSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return@setOnKeyListener false
            // Bottom edge: don't escape to sidebar.
            true
        }
        binding.btnClearHistory.setOnClickListener {
            BiliClient.prefs.clearSearchHistory()
            reloadHistory()
            updateMiddleUi(historyMatches(query), extra = emptyList())
            updateClearHistoryButton(query)
            focusFirstKey()
        }
        binding.btnClearHistory.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> focusLastHistoryItem()
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    focusLastKey()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Bottom edge: don't escape to sidebar.
                    true
                }
                else -> false
            }
        }

        updateQueryUi()
        updateMiddleUi(historyMatches(query), extra = emptyList())
        updateClearHistoryButton(query)
    }

    private fun setupResults() {
        resultAdapter =
            VideoCardAdapter { card ->
                startActivity(
                    Intent(requireContext(), PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_BVID, card.bvid)
                        .putExtra(PlayerActivity.EXTRA_CID, card.cid ?: -1L),
                )
            }
        binding.recyclerResults.adapter = resultAdapter
        binding.recyclerResults.setHasFixedSize(true)
        binding.recyclerResults.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth())
        (binding.recyclerResults.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recyclerResults.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
	                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
	                        if (binding.panelResults.visibility != View.VISIBLE) return@setOnKeyListener false

	                        val lm = binding.recyclerResults.layoutManager as? GridLayoutManager ?: return@setOnKeyListener false
	                        val holder = binding.recyclerResults.findContainingViewHolder(v) ?: return@setOnKeyListener false
	                        val pos = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnKeyListener false

	                        when (keyCode) {
	                            KeyEvent.KEYCODE_DPAD_UP -> {
	                                if (!binding.recyclerResults.canScrollVertically(-1)) {
	                                    if (pos < lm.spanCount) {
	                                        focusSelectedTab()
	                                        return@setOnKeyListener true
	                                    }
	                                }
	                                false
	                            }

	                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
	                                val itemView = binding.recyclerResults.findContainingItemView(v) ?: return@setOnKeyListener false
	                                val next = FocusFinder.getInstance().findNextFocus(binding.recyclerResults, itemView, View.FOCUS_RIGHT)
	                                if (next == null || !isDescendantOf(next, binding.recyclerResults)) {
	                                    if (switchToNextTabIfAvailable()) return@setOnKeyListener true
	                                }
	                                false
	                            }

	                            KeyEvent.KEYCODE_DPAD_DOWN -> {
	                                val itemView = binding.recyclerResults.findContainingItemView(v) ?: return@setOnKeyListener false
	                                val next = FocusFinder.getInstance().findNextFocus(binding.recyclerResults, itemView, View.FOCUS_DOWN)
	                                if (next == null || !isDescendantOf(next, binding.recyclerResults)) {
	                                    if (binding.recyclerResults.canScrollVertically(1)) {
	                                        // Focus-search failed but the list can still scroll; scroll a bit to let
	                                        // RecyclerView lay out the next row, and keep focus inside the list.
	                                        val dy = (itemView.height * 0.8f).toInt().coerceAtLeast(1)
	                                        binding.recyclerResults.scrollBy(0, dy)
	                                        return@setOnKeyListener true
	                                    }

	                                    // Bottom edge: keep focus inside the list (avoid escaping to sidebar) and
	                                    // trigger loading more if possible.
	                                    if (!endReached) loadNextPage()
	                                    return@setOnKeyListener true
	                                }
	                                false
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
        binding.recyclerResults.clearOnScrollListeners()
        binding.recyclerResults.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (isLoadingMore || endReached) return
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = resultAdapter.itemCount
                    if (total <= 0) return
                    if (total - lastVisible - 1 <= 8) loadNextPage()
                }
            },
        )

        binding.tabLayout.removeAllTabs()
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.search_tab_video))
	        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.search_tab_media))
	        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.search_tab_live))
	        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.search_tab_user))
	        binding.tabLayout.post {
	            binding.tabLayout.enableDpadTabFocus()
	            val tabStrip = binding.tabLayout.getChildAt(0) as? ViewGroup ?: return@post
	            for (i in 0 until tabStrip.childCount) {
	                tabStrip.getChildAt(i).setOnKeyListener { _, keyCode, event ->
	                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
	                    when (keyCode) {
	                        KeyEvent.KEYCODE_DPAD_UP -> {
	                            // Prevent focus escaping to sidebar when pressing UP on top controls.
	                            true
	                        }

	                        KeyEvent.KEYCODE_DPAD_DOWN -> {
	                            focusFirstResultCardFromTab()
	                            true
	                        }

	                        else -> false
	                    }
	                }
	            }
	        }
	        binding.tabLayout.addOnTabSelectedListener(
	            object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
	                override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
	                    currentTabIndex = tab.position
                    switchTab(tab.position)
                }

                override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit

                override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            },
        )

        binding.btnSort.setOnClickListener { showSortDialog() }
        binding.btnSort.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return@setOnKeyListener false
            // Prevent focus escaping to sidebar when pressing UP on top controls.
            true
        }
	        binding.tvSort.text = getString(currentOrder.labelRes)

	        binding.swipeRefresh.setOnRefreshListener { resetAndLoad() }
	    }

    private fun loadHotAndDefault() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val hint = BiliApi.searchDefaultText()
                defaultHint = hint
                updateQueryUi()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val hot = BiliApi.searchHot(limit = 12)
                hotAdapter.submit(hot)
            }.onFailure {
                AppLog.w("Search", "load hot failed", it)
            }
        }
    }

    private fun setQuery(value: String) {
        val trimmed = value.trim()
        if (query == trimmed) return
        query = trimmed
        updateQueryUi()
        scheduleMiddleList(trimmed)
    }

    private fun updateQueryUi() {
        val hintText = defaultHint ?: getString(R.string.tab_search)
        val showingHint = query.isBlank()
        binding.tvQuery.text = if (showingHint) hintText else query
        binding.tvQuery.alpha = if (showingHint) 0.65f else 1f
    }

    private fun scheduleMiddleList(term: String) {
        suggestJob?.cancel()
        if (term.isBlank()) {
            updateMiddleUi(historyMatches(term), extra = emptyList())
            updateClearHistoryButton(term)
            return
        }
        updateMiddleUi(historyMatches(term), extra = emptyList())
        updateClearHistoryButton(term)
        suggestJob =
            viewLifecycleOwner.lifecycleScope.launch {
                delay(200)
                runCatching { BiliApi.searchSuggest(term.lowercase()) }
                    .onSuccess { updateMiddleUi(historyMatches(term), extra = it) }
                    .onFailure {
                        AppLog.w("Search", "suggest failed term=${term.take(16)}", it)
                        updateMiddleUi(historyMatches(term), extra = emptyList())
                    }
            }
    }

    private fun updateMiddleUi(history: List<String>, extra: List<String>) {
        val merged = LinkedHashMap<String, String>()
        for (s in history) {
            val key = s.trim().lowercase()
            if (key.isBlank()) continue
            if (merged[key] == null) merged[key] = s
        }
        for (s in extra) {
            val key = s.trim().lowercase()
            if (key.isBlank()) continue
            if (merged[key] == null) merged[key] = s
        }
        val list = merged.values.toList()
        binding.recyclerSuggest.visibility = if (list.isNotEmpty()) View.VISIBLE else View.INVISIBLE
        suggestAdapter.submit(list)
    }

    private fun updateClearHistoryButton(term: String) {
        val show = term.isBlank() && history.isNotEmpty()
        binding.btnClearHistory.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun performSearch() {
        val keyword = query.ifBlank { defaultHint?.trim().orEmpty() }
        if (keyword.isBlank()) return
        setQuery(keyword)
        BiliClient.prefs.addSearchHistory(keyword)
        reloadHistory()
        showResults()
        resetAndLoad()
        focusSelectedTabAfterShow()
    }

    private fun showInput() {
        binding.panelResults.visibility = View.GONE
        binding.panelInput.visibility = View.VISIBLE
    }

    private fun showResults() {
        binding.panelInput.visibility = View.GONE
        binding.panelResults.visibility = View.VISIBLE
    }

    private fun focusFirstKey() {
        val b = _binding ?: return
        b.recyclerKeys.post {
            b.recyclerKeys.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                ?: b.recyclerKeys.post {
                    b.recyclerKeys.scrollToPosition(0)
                    b.recyclerKeys.post { b.recyclerKeys.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                }
        }
    }

    private fun reloadHistory() {
        history = BiliClient.prefs.searchHistory
    }

    private fun historyMatches(term: String, limit: Int = 12): List<String> {
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

	    private fun focusSelectedTab(): Boolean {
	        val tabStrip = binding.tabLayout.getChildAt(0) as? ViewGroup ?: return false
	        val pos = binding.tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
	        val tabView = tabStrip.getChildAt(pos) ?: return false
	        tabView.requestFocus()
	        return true
	    }

	    private fun focusFirstResultCardFromTab(): Boolean {
	        if (binding.panelResults.visibility != View.VISIBLE) return false
	        if (currentTabIndex != 0) return true
	        if (resultAdapter.itemCount <= 0) return true
	        binding.recyclerResults.post {
	            val vh = binding.recyclerResults.findViewHolderForAdapterPosition(0)
	            if (vh != null) {
	                vh.itemView.requestFocus()
	                return@post
	            }
	            binding.recyclerResults.scrollToPosition(0)
	            binding.recyclerResults.post {
	                binding.recyclerResults.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: binding.recyclerResults.requestFocus()
	            }
	        }
	        return true
	    }

	    private fun switchToNextTabIfAvailable(): Boolean {
	        if (binding.panelResults.visibility != View.VISIBLE) return false
	        if (binding.tabLayout.tabCount <= 1) return false
	        val tabStrip = binding.tabLayout.getChildAt(0) as? ViewGroup ?: return false
	        val cur = binding.tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
	        val next = (cur + 1) % binding.tabLayout.tabCount
	        binding.tabLayout.getTabAt(next)?.select() ?: return false
	        binding.tabLayout.post { tabStrip.getChildAt(next)?.requestFocus() }
	        return true
	    }

	    private fun isDescendantOf(view: View, ancestor: View): Boolean {
	        var current: View? = view
	        while (current != null) {
	            if (current == ancestor) return true
	            current = current.parent as? View
	        }
	        return false
	    }

	    private fun focusKeyAt(pos: Int): Boolean {
	        val count = binding.recyclerKeys.adapter?.itemCount ?: return false
	        if (count <= 0) return false
	        val safePos = pos.coerceIn(0, count - 1)
        binding.recyclerKeys.scrollToPosition(safePos)
        binding.recyclerKeys.post {
            binding.recyclerKeys.findViewHolderForAdapterPosition(safePos)?.itemView?.requestFocus()
        }
        return true
    }

    private fun focusLastKey(): Boolean = focusKeyAt(lastFocusedKeyPos).also { if (!it) focusFirstKey() }

    private fun focusHistoryAt(pos: Int): Boolean {
        val count = binding.recyclerSuggest.adapter?.itemCount ?: return false
        if (count <= 0) return false
        val safePos = pos.coerceIn(0, count - 1)
        binding.recyclerSuggest.scrollToPosition(safePos)
        binding.recyclerSuggest.post {
            binding.recyclerSuggest.findViewHolderForAdapterPosition(safePos)?.itemView?.requestFocus()
        }
        return true
    }

    private fun focusLastHistory(): Boolean = focusHistoryAt(lastFocusedSuggestPos)

    private fun focusLastHistoryItem(): Boolean {
        val count = binding.recyclerSuggest.adapter?.itemCount ?: return false
        if (count <= 0) return false
        val last = count - 1
        binding.recyclerSuggest.scrollToPosition(last)
        binding.recyclerSuggest.post {
            binding.recyclerSuggest.findViewHolderForAdapterPosition(last)?.itemView?.requestFocus()
        }
        return true
    }

    private fun focusSelectedTabAfterShow() {
        binding.tabLayout.post {
            if (binding.panelResults.visibility == View.VISIBLE) {
                focusSelectedTab()
            }
        }
    }

    private fun switchTab(pos: Int) {
        if (binding.panelResults.visibility != View.VISIBLE) return
        when (pos) {
            0 -> {
                binding.tvResultsPlaceholder.visibility = View.GONE
                binding.swipeRefresh.visibility = View.VISIBLE
                resetAndLoad()
            }
            else -> {
                binding.swipeRefresh.isRefreshing = false
                binding.swipeRefresh.visibility = View.GONE
                binding.tvResultsPlaceholder.visibility = View.VISIBLE
            }
        }
    }

    private fun resetAndLoad() {
        if (binding.panelResults.visibility != View.VISIBLE) return
        if (currentTabIndex != 0) return
        loadedBvids.clear()
        endReached = false
        isLoadingMore = false
        page = 1
        requestToken++
        binding.recyclerResults.scrollToPosition(0)
        binding.swipeRefresh.isRefreshing = true
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || endReached) return
        if (currentTabIndex != 0) return
        val keyword = query.ifBlank { defaultHint?.trim().orEmpty() }
        if (keyword.isBlank()) return
        val token = requestToken
        isLoadingMore = true
        val startAt = SystemClock.uptimeMillis()
        AppLog.d("Search", "load start keyword=${keyword.take(12)} page=$page order=${currentOrder.apiValue} t=$startAt")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = BiliApi.searchVideo(keyword = keyword, page = page, order = currentOrder.apiValue)
                if (token != requestToken) return@launch

                val list = res.items
                if (list.isEmpty()) {
                    endReached = true
                    return@launch
                }

	                val filtered = list.filter { loadedBvids.add(it.bvid) }
	                if (page == 1) resultAdapter.submit(filtered) else resultAdapter.append(filtered)
	                page++

                if (res.pages in 1..page && page > res.pages) endReached = true
                if (filtered.isEmpty()) endReached = true

                AppLog.i("Search", "load ok add=${filtered.size} total=${resultAdapter.itemCount} cost=${SystemClock.uptimeMillis() - startAt}ms")
            } catch (t: Throwable) {
                AppLog.e("Search", "load failed page=$page", t)
                Toast.makeText(requireContext(), "搜索失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show()
            } finally {
                if (isRefresh && token == requestToken) binding.swipeRefresh.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private fun showSortDialog() {
        if (currentTabIndex != 0) {
            Toast.makeText(requireContext(), getString(R.string.search_unsupported), Toast.LENGTH_SHORT).show()
            return
        }
        val items = Order.entries
        val labels = items.map { getString(it.labelRes) }.toTypedArray()
        val checked = items.indexOf(currentOrder).coerceAtLeast(0)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.search_sort_title))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val picked = items.getOrNull(which) ?: return@setSingleChoiceItems
                if (picked != currentOrder) {
                    currentOrder = picked
                    binding.tvSort.text = getString(currentOrder.labelRes)
                    resetAndLoad()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun spanCountForWidth(): Int {
        val override = blbl.cat3399.core.net.BiliClient.prefs.gridSpanCount
        if (override > 0) return override.coerceIn(1, 6)
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return when {
            widthDp >= 1100 -> 4
            widthDp >= 800 -> 3
            else -> 2
        }
    }

    override fun onResume() {
        super.onResume()
        (binding.recyclerResults.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth()
    }

    override fun onDestroyView() {
        suggestJob?.cancel()
        suggestJob = null
        _binding = null
        super.onDestroyView()
    }

    enum class Order(
        val apiValue: String,
        val labelRes: Int,
    ) {
        TotalRank("totalrank", R.string.search_sort_totalrank),
        Click("click", R.string.search_sort_click),
        PubDate("pubdate", R.string.search_sort_pubdate),
        Dm("dm", R.string.search_sort_dm),
        Stow("stow", R.string.search_sort_stow),
        Scores("scores", R.string.search_sort_scores),
    }

    companion object {
        private val KEYS =
            listOf(
                "A", "B", "C", "D", "E", "F",
                "G", "H", "I", "J", "K", "L",
                "M", "N", "O", "P", "Q", "R",
                "S", "T", "U", "V", "W", "X",
                "Y", "Z", "1", "2", "3", "4",
                "5", "6", "7", "8", "9", "0",
            )

        fun newInstance() = SearchFragment()
    }
}
