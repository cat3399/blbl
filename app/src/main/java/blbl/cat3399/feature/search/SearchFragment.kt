package blbl.cat3399.feature.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiSeason
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.tv.TvMode
import blbl.cat3399.core.ui.SingleChoiceDialog
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.databinding.FragmentSearchBinding
import blbl.cat3399.feature.following.FollowingGridAdapter
import blbl.cat3399.feature.following.UpDetailActivity
import blbl.cat3399.feature.live.LivePlayerActivity
import blbl.cat3399.feature.live.LiveRoomAdapter
import blbl.cat3399.feature.my.BangumiFollowAdapter
import blbl.cat3399.feature.my.MyBangumiDetailFragment
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import blbl.cat3399.feature.video.VideoCardAdapter
import blbl.cat3399.ui.BackPressHandler
import blbl.cat3399.ui.RefreshKeyHandler
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SearchFragment : Fragment(), BackPressHandler, RefreshKeyHandler {
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var keyAdapter: SearchKeyAdapter
    private lateinit var suggestAdapter: SearchSuggestAdapter
    private lateinit var hotAdapter: SearchHotAdapter
    private lateinit var videoAdapter: VideoCardAdapter
    private lateinit var mediaAdapter: BangumiFollowAdapter
    private lateinit var liveAdapter: LiveRoomAdapter
    private lateinit var userAdapter: FollowingGridAdapter

    private var defaultHint: String? = null
    private var query: String = ""
    private var history: List<String> = emptyList()

    private var suggestJob: Job? = null
    private var isTvMode: Boolean = false
    private var ignoreQueryTextChanges: Boolean = false

    private var lastFocusedKeyPos: Int = 0
    private var lastFocusedSuggestPos: Int = 0

    private var lastAppliedUiScale: Float? = null

    private var currentTabIndex: Int = 0
    private var currentVideoOrder: VideoOrder = VideoOrder.TotalRank
    private var currentLiveOrder: LiveOrder = LiveOrder.Online
    private var currentUserOrder: UserOrder = UserOrder.Default
    private var currentMediaKind: MediaKind = MediaKind.Ft
    private var pendingFocusFirstResultCardFromTabSwitch: Boolean = false
    private var pendingFocusNextResultCardAfterLoadMoreFromDpad: Boolean = false
    private var pendingFocusNextResultCardAfterLoadMoreFromPos: Int = RecyclerView.NO_POSITION
    private var pendingRestoreMediaPos: Int? = null

    private val loadedBvids = HashSet<String>()
    private val loadedSeasonIds = HashSet<Long>()
    private val loadedRoomIds = HashSet<Long>()
    private val loadedMids = HashSet<Long>()

    private data class TabState(
        var isLoadingMore: Boolean = false,
        var endReached: Boolean = false,
        var page: Int = 1,
        var requestToken: Int = 0,
    )

    private val videoState = TabState()
    private val mediaState = TabState()
    private val liveState = TabState()
    private val userState = TabState()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        reloadHistory()
        setupInput()
        setupResults()
        applyUiScale()
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
        isTvMode = TvMode.isEnabled(requireContext())
        setupQueryInput()

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
        keyAdapter.setTvMode(isTvMode)
        suggestAdapter.setTvMode(isTvMode)
        hotAdapter.setTvMode(isTvMode)

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

    private fun setupQueryInput() {
        val input = binding.tvQuery

        input.setOnEditorActionListener { _, actionId, event ->
            val isEnter =
                event != null &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            if (actionId == EditorInfo.IME_ACTION_SEARCH || isEnter) {
                performSearch()
                true
            } else {
                false
            }
        }

        input.doAfterTextChanged {
            if (ignoreQueryTextChanges) return@doAfterTextChanged
            setQueryFromTextInput(it?.toString().orEmpty())
        }

        if (isTvMode) {
            input.apply {
                // TV mode defaults to on-screen keyboard + DPAD navigation.
                // Keep IME disabled on focus, but allow touch users to explicitly open the IME.
                isFocusable = false
                isFocusableInTouchMode = false
                isCursorVisible = false
                isLongClickable = false
                setTextIsSelectable(false)
                showSoftInputOnFocus = false
                setOnClickListener(null)
                setOnFocusChangeListener(null)
            }

            input.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    if (binding.panelResults.visibility == View.VISIBLE) showInput()

                    // Enable focus + editing only when the user explicitly touches the input.
                    input.isFocusable = true
                    input.isFocusableInTouchMode = true
                    input.isCursorVisible = true
                    input.isLongClickable = true
                    input.setTextIsSelectable(true)

                    input.requestFocus()
                    input.setSelection(input.text?.length ?: 0)
                    showIme(input)
                }
                false
            }
            return
        }

        input.setOnTouchListener(null)
        input.showSoftInputOnFocus = true
        input.isFocusable = true
        input.isFocusableInTouchMode = true

        input.setOnClickListener {
            if (binding.panelResults.visibility == View.VISIBLE) showInput()
            input.requestFocus()
            input.setSelection(input.text?.length ?: 0)
            showIme(input)
        }

        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) input.post { showIme(input) }
        }
    }

    private fun setQueryFromTextInput(value: String) {
        val trimmed = value.trim()
        if (query == trimmed) {
            binding.tvQuery.alpha = if (query.isBlank()) 0.65f else 1f
            return
        }
        query = trimmed
        binding.tvQuery.alpha = if (query.isBlank()) 0.65f else 1f
        scheduleMiddleList(trimmed)
    }

    private fun setupResults() {
        videoAdapter =
            VideoCardAdapter { card, pos ->
                val playlistItems =
                    videoAdapter.snapshot().map {
                        PlayerPlaylistItem(
                            bvid = it.bvid,
                            cid = it.cid,
                            title = it.title,
                        )
                    }
                val token = PlayerPlaylistStore.put(items = playlistItems, index = pos, source = "Search")
                startActivity(
                    Intent(requireContext(), PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_BVID, card.bvid)
                        .putExtra(PlayerActivity.EXTRA_CID, card.cid ?: -1L)
                        .putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, token)
                        .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, pos),
                )
            }
        videoAdapter.setTvMode(isTvMode)

        if (!::mediaAdapter.isInitialized) {
            mediaAdapter =
                BangumiFollowAdapter { position, season ->
                    pendingRestoreMediaPos = position
                    openBangumiDetail(season)
                }
        }
        if (!::liveAdapter.isInitialized) {
            liveAdapter =
                LiveRoomAdapter { room ->
                    if (!room.isLive) {
                        Toast.makeText(requireContext(), "未开播", Toast.LENGTH_SHORT).show()
                        return@LiveRoomAdapter
                    }
                    startActivity(
                        Intent(requireContext(), LivePlayerActivity::class.java)
                            .putExtra(LivePlayerActivity.EXTRA_ROOM_ID, room.roomId)
                            .putExtra(LivePlayerActivity.EXTRA_TITLE, room.title)
                            .putExtra(LivePlayerActivity.EXTRA_UNAME, room.uname),
                    )
                }
        }
        if (!::userAdapter.isInitialized) {
            userAdapter =
                FollowingGridAdapter { following ->
                    startActivity(
                        Intent(requireContext(), UpDetailActivity::class.java)
                            .putExtra(UpDetailActivity.EXTRA_MID, following.mid)
                            .putExtra(UpDetailActivity.EXTRA_NAME, following.name)
                            .putExtra(UpDetailActivity.EXTRA_AVATAR, following.avatarUrl)
                            .putExtra(UpDetailActivity.EXTRA_SIGN, following.sign),
                    )
                }
        }

        mediaAdapter.setTvMode(isTvMode)
        liveAdapter.setTvMode(isTvMode)
        userAdapter.setTvMode(isTvMode)

        binding.recyclerResults.adapter = adapterForTab(currentTabIndex)
        binding.recyclerResults.setHasFixedSize(true)
        binding.recyclerResults.layoutManager = GridLayoutManager(requireContext(), spanCountForCurrentTab())
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

	                            KeyEvent.KEYCODE_DPAD_LEFT -> {
	                                val itemView = binding.recyclerResults.findContainingItemView(v) ?: return@setOnKeyListener false
	                                val next = FocusFinder.getInstance().findNextFocus(binding.recyclerResults, itemView, View.FOCUS_LEFT)
	                                if (next == null || !isDescendantOf(next, binding.recyclerResults)) {
	                                    val switched = switchToPrevTabFromContentEdge()
	                                    return@setOnKeyListener switched
	                                }
	                                false
	                            }

	                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
	                                val itemView = binding.recyclerResults.findContainingItemView(v) ?: return@setOnKeyListener false
	                                val next = FocusFinder.getInstance().findNextFocus(binding.recyclerResults, itemView, View.FOCUS_RIGHT)
	                                if (next == null || !isDescendantOf(next, binding.recyclerResults)) {
	                                    if (switchToNextTabFromContentEdge()) return@setOnKeyListener true
	                                    return@setOnKeyListener true
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
	                                        binding.recyclerResults.post {
	                                            if (_binding == null) return@post
	                                            tryFocusNextDownFromCurrentResult()
	                                        }
	                                        return@setOnKeyListener true
	                                    }

	                                    // Bottom edge: keep focus inside the list (avoid escaping to sidebar) and
	                                    // trigger loading more if possible.
	                                    if (!stateForTab(currentTabIndex).endReached) {
	                                        pendingFocusNextResultCardAfterLoadMoreFromDpad = true
	                                        pendingFocusNextResultCardAfterLoadMoreFromPos = pos
	                                        loadNextPage()
	                                    }
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
                    val state = stateForTab(currentTabIndex)
                    if (state.isLoadingMore || state.endReached) return
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = recyclerView.adapter?.itemCount ?: 0
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
        val tabLayout = binding.tabLayout
        tabLayout.post {
            if (_binding == null) return@post
            tabLayout.enableDpadTabFocus()
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return@post
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
	        updateSortUi()

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
        binding.tvQuery.hint = hintText
        binding.tvQuery.alpha = if (query.isBlank()) 0.65f else 1f

        val current = binding.tvQuery.text?.toString().orEmpty()
        if (current == query) return
        ignoreQueryTextChanges = true
        binding.tvQuery.setText(query)
        if (binding.tvQuery.hasFocus()) {
            binding.tvQuery.setSelection(binding.tvQuery.text?.length ?: 0)
        }
        ignoreQueryTextChanges = false
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
        if (binding.tvQuery.hasFocus()) {
            hideIme(binding.tvQuery)
            binding.tvQuery.clearFocus()
        }
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

    private fun showIme(view: View) {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideIme(view: View) {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
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
            val b = _binding ?: return false
            val tabStrip = b.tabLayout.getChildAt(0) as? ViewGroup ?: return false
            val pos = b.tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
            val tabView = tabStrip.getChildAt(pos) ?: return false
            tabView.requestFocus()
            return true
        }

	    private fun focusFirstResultCardFromTab(): Boolean {
	        if (binding.panelResults.visibility != View.VISIBLE) return false
            pendingFocusFirstResultCardFromTabSwitch = true
            if (!isResumed) return true
            return maybeConsumePendingFocusFirstResultCardFromTabSwitch()
	    }

        private fun requestFocusResultsContentFromTabSwitch(): Boolean {
            if (binding.panelResults.visibility != View.VISIBLE) return false
            return requestFocusFirstResultCardFromTabSwitch()
        }

        private fun requestFocusFirstResultCardFromTabSwitch(): Boolean {
            pendingFocusFirstResultCardFromTabSwitch = true
            if (!isResumed) return true
            return maybeConsumePendingFocusFirstResultCardFromTabSwitch()
        }

        private fun maybeConsumePendingFocusFirstResultCardFromTabSwitch(): Boolean {
            if (!pendingFocusFirstResultCardFromTabSwitch) return false
            if (_binding == null || !isAdded || !isResumed) return false
            if (binding.panelResults.visibility != View.VISIBLE) {
                pendingFocusFirstResultCardFromTabSwitch = false
                return false
            }

            val focused = activity?.currentFocus
            if (focused != null && isDescendantOf(focused, binding.recyclerResults) && focused != binding.recyclerResults) {
                pendingFocusFirstResultCardFromTabSwitch = false
                return false
            }

            val adapter = binding.recyclerResults.adapter
            if (adapter == null || adapter.itemCount <= 0) {
                binding.recyclerResults.requestFocus()
                return true
            }

            val recycler = binding.recyclerResults
            recycler.post outer@{
                if (_binding == null) return@outer
                val vh = recycler.findViewHolderForAdapterPosition(0)
                if (vh != null) {
                    vh.itemView.requestFocus()
                    pendingFocusFirstResultCardFromTabSwitch = false
                    return@outer
                }
                recycler.scrollToPosition(0)
                recycler.post inner@{
                    if (_binding == null) return@inner
                    recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
                    pendingFocusFirstResultCardFromTabSwitch = false
                }
            }
            return true
        }

	    private fun switchToNextTabFromContentEdge(): Boolean {
	        if (binding.panelResults.visibility != View.VISIBLE) return false
	        if (binding.tabLayout.tabCount <= 1) return false
	        val tabStrip = binding.tabLayout.getChildAt(0) as? ViewGroup ?: return false
	        val cur = binding.tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
	        val next = cur + 1
            if (next >= binding.tabLayout.tabCount) return false
	        binding.tabLayout.getTabAt(next)?.select() ?: return false
            val tabLayout = binding.tabLayout
            tabLayout.post {
                if (_binding == null) return@post
                requestFocusResultsContentFromTabSwitch()
                    || tabStrip.getChildAt(next)?.requestFocus() == true
            }
            return true
        }

        private fun switchToPrevTabFromContentEdge(): Boolean {
            if (binding.panelResults.visibility != View.VISIBLE) return false
            if (binding.tabLayout.tabCount <= 1) return false
            val tabStrip = binding.tabLayout.getChildAt(0) as? ViewGroup ?: return false
            val cur = binding.tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
            val prev = cur - 1
            if (prev < 0) return false
            binding.tabLayout.getTabAt(prev)?.select() ?: return false
            val tabLayout = binding.tabLayout
            tabLayout.post {
                if (_binding == null) return@post
                requestFocusResultsContentFromTabSwitch()
                    || tabStrip.getChildAt(prev)?.requestFocus() == true
            }
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
        val recycler = binding.recyclerKeys
        recycler.scrollToPosition(safePos)
        recycler.post {
            if (_binding == null) return@post
            recycler.findViewHolderForAdapterPosition(safePos)?.itemView?.requestFocus()
        }
        return true
    }

    private fun focusLastKey(): Boolean = focusKeyAt(lastFocusedKeyPos).also { if (!it) focusFirstKey() }

    private fun focusHistoryAt(pos: Int): Boolean {
        val count = binding.recyclerSuggest.adapter?.itemCount ?: return false
        if (count <= 0) return false
        val safePos = pos.coerceIn(0, count - 1)
        val recycler = binding.recyclerSuggest
        recycler.scrollToPosition(safePos)
        recycler.post {
            if (_binding == null) return@post
            recycler.findViewHolderForAdapterPosition(safePos)?.itemView?.requestFocus()
        }
        return true
    }

    private fun focusLastHistory(): Boolean = focusHistoryAt(lastFocusedSuggestPos)

    private fun focusLastHistoryItem(): Boolean {
        val count = binding.recyclerSuggest.adapter?.itemCount ?: return false
        if (count <= 0) return false
        val last = count - 1
        val recycler = binding.recyclerSuggest
        recycler.scrollToPosition(last)
        recycler.post {
            if (_binding == null) return@post
            recycler.findViewHolderForAdapterPosition(last)?.itemView?.requestFocus()
        }
        return true
    }

    private fun focusSelectedTabAfterShow() {
        val tabLayout = binding.tabLayout
        tabLayout.post {
            val b = _binding ?: return@post
            if (b.panelResults.visibility == View.VISIBLE) {
                focusSelectedTab()
            }
        }
    }

    private fun switchTab(pos: Int) {
        if (binding.panelResults.visibility != View.VISIBLE) return
        binding.recyclerResults.adapter = adapterForTab(pos)
        (binding.recyclerResults.layoutManager as? GridLayoutManager)?.spanCount = spanCountForTab(pos)
        binding.recyclerResults.scrollToPosition(0)
        updateSortUi()

        binding.tvResultsPlaceholder.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE
        resetAndLoad()
    }

    private fun resetAndLoad() {
        val b = _binding ?: return
        if (b.panelResults.visibility != View.VISIBLE) return
        clearLoadedForTab(currentTabIndex)
        clearPendingFocusNextResultCardAfterLoadMoreFromDpad()
        val state = stateForTab(currentTabIndex)
        state.endReached = false
        state.isLoadingMore = false
        state.page = 1
        state.requestToken++
        b.recyclerResults.scrollToPosition(0)
        b.swipeRefresh.isRefreshing = true
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        val keyword = query.ifBlank { defaultHint?.trim().orEmpty() }
        if (keyword.isBlank()) return
        val tab = tabForIndex(currentTabIndex)
        val state = stateForTab(currentTabIndex)
        if (state.isLoadingMore || state.endReached) return
        val token = state.requestToken
        state.isLoadingMore = true
        val startAt = SystemClock.uptimeMillis()
        AppLog.d("Search", "load start tab=${tab.name} keyword=${keyword.take(12)} page=${state.page} t=$startAt")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (tab) {
                    Tab.Video -> {
                        val res = BiliApi.searchVideo(keyword = keyword, page = state.page, order = currentVideoOrder.apiValue)
                        if (token != state.requestToken || currentTabIndex != tab.index) return@launch
                        val list = res.items
                        if (list.isEmpty()) {
                            state.endReached = true
                            return@launch
                        }
                        val filtered = list.filter { loadedBvids.add(it.bvid) }
                        if (state.page == 1) videoAdapter.submit(filtered) else videoAdapter.append(filtered)
                        state.page++
                        if (res.pages in 1..state.page && state.page > res.pages) state.endReached = true
                        if (filtered.isEmpty()) state.endReached = true
                        AppLog.i("Search", "load ok tab=video add=${filtered.size} total=${videoAdapter.itemCount} cost=${SystemClock.uptimeMillis() - startAt}ms")
                    }

                    Tab.Media -> {
                        val res =
                            when (currentMediaKind) {
                                MediaKind.Ft -> BiliApi.searchMediaFt(keyword = keyword, page = state.page, order = "totalrank")
                                MediaKind.Bangumi -> BiliApi.searchMediaBangumi(keyword = keyword, page = state.page, order = "totalrank")
                            }
                        if (token != state.requestToken || currentTabIndex != tab.index) return@launch
                        val list = res.items
                        if (list.isEmpty()) {
                            state.endReached = true
                            return@launch
                        }
                        val filtered = list.filter { loadedSeasonIds.add(it.seasonId) }
                        if (state.page == 1) mediaAdapter.submit(filtered) else mediaAdapter.append(filtered)
                        state.page++
                        if (res.pages in 1..state.page && state.page > res.pages) state.endReached = true
                        if (filtered.isEmpty()) state.endReached = true
                        AppLog.i("Search", "load ok tab=media add=${filtered.size} cost=${SystemClock.uptimeMillis() - startAt}ms")
                    }

                    Tab.Live -> {
                        val res = BiliApi.searchLiveRoom(keyword = keyword, page = state.page, order = currentLiveOrder.apiValue)
                        if (token != state.requestToken || currentTabIndex != tab.index) return@launch
                        val list = res.items
                        if (list.isEmpty()) {
                            state.endReached = true
                            return@launch
                        }
                        val filtered = list.filter { loadedRoomIds.add(it.roomId) }
                        if (state.page == 1) liveAdapter.submit(filtered) else liveAdapter.append(filtered)
                        state.page++
                        if (res.pages in 1..state.page && state.page > res.pages) state.endReached = true
                        if (filtered.isEmpty()) state.endReached = true
                        AppLog.i("Search", "load ok tab=live add=${filtered.size} cost=${SystemClock.uptimeMillis() - startAt}ms")
                    }

                    Tab.User -> {
                        val res = BiliApi.searchUser(keyword = keyword, page = state.page, order = currentUserOrder.apiValue)
                        if (token != state.requestToken || currentTabIndex != tab.index) return@launch
                        val list = res.items
                        if (list.isEmpty()) {
                            state.endReached = true
                            return@launch
                        }
                        val filtered = list.filter { loadedMids.add(it.mid) }
                        if (state.page == 1) userAdapter.submit(filtered) else userAdapter.append(filtered)
                        state.page++
                        if (res.pages in 1..state.page && state.page > res.pages) state.endReached = true
                        if (filtered.isEmpty()) state.endReached = true
                        AppLog.i("Search", "load ok tab=user add=${filtered.size} cost=${SystemClock.uptimeMillis() - startAt}ms")
                    }
                }

                _binding?.recyclerResults?.post {
                    maybeConsumePendingFocusFirstResultCardFromTabSwitch()
                    maybeConsumePendingFocusNextResultCardAfterLoadMoreFromDpad()
                }
            } catch (t: Throwable) {
                AppLog.e("Search", "load failed tab=${tab.name} page=${state.page}", t)
                context?.let { Toast.makeText(it, "搜索失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show() }
            } finally {
                if (isRefresh && token == state.requestToken) _binding?.swipeRefresh?.isRefreshing = false
                state.isLoadingMore = false
	            }
	        }
	    }

    private fun clearPendingFocusNextResultCardAfterLoadMoreFromDpad() {
        pendingFocusNextResultCardAfterLoadMoreFromDpad = false
        pendingFocusNextResultCardAfterLoadMoreFromPos = RecyclerView.NO_POSITION
    }

    private fun maybeConsumePendingFocusNextResultCardAfterLoadMoreFromDpad(): Boolean {
        if (!pendingFocusNextResultCardAfterLoadMoreFromDpad) return false
        val b = _binding
        if (b == null || !isResumed) {
            clearPendingFocusNextResultCardAfterLoadMoreFromDpad()
            return false
        }

        val recycler = b.recyclerResults
        val adapter = recycler.adapter
        if (adapter == null) {
            clearPendingFocusNextResultCardAfterLoadMoreFromDpad()
            return false
        }
        val lm = recycler.layoutManager as? GridLayoutManager
        if (lm == null) {
            clearPendingFocusNextResultCardAfterLoadMoreFromDpad()
            return false
        }

        val anchorPos = pendingFocusNextResultCardAfterLoadMoreFromPos
        if (anchorPos == RecyclerView.NO_POSITION) {
            clearPendingFocusNextResultCardAfterLoadMoreFromDpad()
            return false
        }

        val focused = activity?.currentFocus
        if (focused != null && !isDescendantOf(focused, recycler)) {
            clearPendingFocusNextResultCardAfterLoadMoreFromDpad()
            return false
        }

        val spanCount = lm.spanCount.coerceAtLeast(1)
        val itemCount = adapter.itemCount
        val candidatePos =
            when {
                anchorPos + spanCount in 0 until itemCount -> anchorPos + spanCount
                anchorPos + 1 in 0 until itemCount -> anchorPos + 1
                else -> null
            }
        clearPendingFocusNextResultCardAfterLoadMoreFromDpad()
        if (candidatePos == null) return false

        recycler.findViewHolderForAdapterPosition(candidatePos)?.itemView?.requestFocus()
            ?: run {
                recycler.scrollToPosition(candidatePos)
                recycler.post { recycler.findViewHolderForAdapterPosition(candidatePos)?.itemView?.requestFocus() }
            }
        return true
    }

    private fun tryFocusNextDownFromCurrentResult() {
        val b = _binding ?: return
        if (!isResumed) return
        val recycler = b.recyclerResults
        val focused = activity?.currentFocus ?: return
        if (!isDescendantOf(focused, recycler)) return
        val itemView = recycler.findContainingItemView(focused) ?: return
        val next = FocusFinder.getInstance().findNextFocus(recycler, itemView, View.FOCUS_DOWN)
        if (next != null && isDescendantOf(next, recycler)) {
            next.requestFocus()
        }
    }

    private fun showSortDialog() {
        when (tabForIndex(currentTabIndex)) {
            Tab.Video -> {
                val items = VideoOrder.entries
                val labels = items.map { getString(it.labelRes) }
                val checked = items.indexOf(currentVideoOrder).coerceAtLeast(0)
                SingleChoiceDialog.show(
                    context = requireContext(),
                    title = getString(R.string.search_sort_title),
                    items = labels,
                    checkedIndex = checked,
                    negativeText = getString(android.R.string.cancel),
                ) { which, _ ->
                    val picked = items.getOrNull(which) ?: return@show
                    if (picked != currentVideoOrder) {
                        currentVideoOrder = picked
                        updateSortUi()
                        resetAndLoad()
                    }
                }
            }

            Tab.Live -> {
                val items = LiveOrder.entries
                val labels = items.map { getString(it.labelRes) }
                val checked = items.indexOf(currentLiveOrder).coerceAtLeast(0)
                SingleChoiceDialog.show(
                    context = requireContext(),
                    title = getString(R.string.search_sort_title),
                    items = labels,
                    checkedIndex = checked,
                    negativeText = getString(android.R.string.cancel),
                ) { which, _ ->
                    val picked = items.getOrNull(which) ?: return@show
                    if (picked != currentLiveOrder) {
                        currentLiveOrder = picked
                        updateSortUi()
                        resetAndLoad()
                    }
                }
            }

            Tab.User -> {
                val items = UserOrder.entries
                val labels = items.map { getString(it.labelRes) }
                val checked = items.indexOf(currentUserOrder).coerceAtLeast(0)
                SingleChoiceDialog.show(
                    context = requireContext(),
                    title = getString(R.string.search_sort_title),
                    items = labels,
                    checkedIndex = checked,
                    negativeText = getString(android.R.string.cancel),
                ) { which, _ ->
                    val picked = items.getOrNull(which) ?: return@show
                    if (picked != currentUserOrder) {
                        currentUserOrder = picked
                        updateSortUi()
                        resetAndLoad()
                    }
                }
            }

            Tab.Media -> {
                val items = MediaKind.entries
                val labels = items.map { getString(it.labelRes) }
                val checked = items.indexOf(currentMediaKind).coerceAtLeast(0)
                SingleChoiceDialog.show(
                    context = requireContext(),
                    title = getString(R.string.search_sort_title),
                    items = labels,
                    checkedIndex = checked,
                    negativeText = getString(android.R.string.cancel),
                ) { which, _ ->
                    val picked = items.getOrNull(which) ?: return@show
                    if (picked != currentMediaKind) {
                        currentMediaKind = picked
                        updateSortUi()
                        resetAndLoad()
                    }
                }
            }
        }
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
        isTvMode = TvMode.isEnabled(requireContext())
        videoAdapter.setTvMode(isTvMode)
        keyAdapter.setTvMode(isTvMode)
        suggestAdapter.setTvMode(isTvMode)
        hotAdapter.setTvMode(isTvMode)
        mediaAdapter.setTvMode(isTvMode)
        liveAdapter.setTvMode(isTvMode)
        userAdapter.setTvMode(isTvMode)
        applyUiScale()
        (binding.recyclerResults.layoutManager as? GridLayoutManager)?.spanCount = spanCountForCurrentTab()
        maybeConsumePendingFocusFirstResultCardFromTabSwitch()
        restoreMediaFocusIfNeeded()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.panelResults.visibility != View.VISIBLE) return false
        if (b.swipeRefresh.isRefreshing) return true
        resetAndLoad()
        return true
    }

    private fun restoreMediaFocusIfNeeded() {
        val pos = pendingRestoreMediaPos ?: return
        pendingRestoreMediaPos = null
        if (!isResumed) return
        if (_binding == null) return
        if (currentTabIndex != Tab.Media.index) return
        if (binding.panelResults.visibility != View.VISIBLE) return
        val adapter = binding.recyclerResults.adapter ?: return
        if (adapter.itemCount <= 0) return
        val safePos = pos.coerceIn(0, adapter.itemCount - 1)

        val recycler = binding.recyclerResults
        recycler.post outer@{
            if (_binding == null) return@outer
            recycler.findViewHolderForAdapterPosition(safePos)?.itemView?.requestFocus()
                ?: run {
                    recycler.scrollToPosition(safePos)
                    recycler.post { recycler.findViewHolderForAdapterPosition(safePos)?.itemView?.requestFocus() }
                }
        }
    }

    override fun onDestroyView() {
        suggestJob?.cancel()
        suggestJob = null
        clearPendingFocusNextResultCardAfterLoadMoreFromDpad()
        lastAppliedUiScale = null
        _binding = null
        super.onDestroyView()
    }

    private fun applyUiScale() {
        val b = _binding ?: return
        val newScale = UiScale.factor(requireContext(), isTvMode)
        val oldScale = lastAppliedUiScale ?: 1.0f
        if (newScale == oldScale) return

        fun rescalePx(valuePx: Int): Int = (valuePx.toFloat() / oldScale * newScale).roundToInt()
        fun rescalePxF(valuePx: Float): Float = (valuePx / oldScale * newScale)

        fun rescaleLayoutSize(view: View, width: Boolean = true, height: Boolean = true) {
            val lp = view.layoutParams ?: return
            var changed = false
            if (width && lp.width > 0) {
                val w = rescalePx(lp.width).coerceAtLeast(1)
                if (lp.width != w) {
                    lp.width = w
                    changed = true
                }
            }
            if (height && lp.height > 0) {
                val h = rescalePx(lp.height).coerceAtLeast(1)
                if (lp.height != h) {
                    lp.height = h
                    changed = true
                }
            }
            if (changed) view.layoutParams = lp
        }

        fun rescaleMargins(view: View, start: Boolean = true, top: Boolean = true, end: Boolean = true, bottom: Boolean = true) {
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            var changed = false
            if (start) {
                val ms = rescalePx(lp.marginStart).coerceAtLeast(0)
                if (lp.marginStart != ms) {
                    lp.marginStart = ms
                    changed = true
                }
            }
            if (top) {
                val mt = rescalePx(lp.topMargin).coerceAtLeast(0)
                if (lp.topMargin != mt) {
                    lp.topMargin = mt
                    changed = true
                }
            }
            if (end) {
                val me = rescalePx(lp.marginEnd).coerceAtLeast(0)
                if (lp.marginEnd != me) {
                    lp.marginEnd = me
                    changed = true
                }
            }
            if (bottom) {
                val mb = rescalePx(lp.bottomMargin).coerceAtLeast(0)
                if (lp.bottomMargin != mb) {
                    lp.bottomMargin = mb
                    changed = true
                }
            }
            if (changed) view.layoutParams = lp
        }

        fun rescalePadding(view: View, left: Boolean = true, top: Boolean = true, right: Boolean = true, bottom: Boolean = true) {
            val l = if (left) rescalePx(view.paddingLeft).coerceAtLeast(0) else view.paddingLeft
            val t = if (top) rescalePx(view.paddingTop).coerceAtLeast(0) else view.paddingTop
            val r = if (right) rescalePx(view.paddingRight).coerceAtLeast(0) else view.paddingRight
            val btm = if (bottom) rescalePx(view.paddingBottom).coerceAtLeast(0) else view.paddingBottom
            if (l != view.paddingLeft || t != view.paddingTop || r != view.paddingRight || btm != view.paddingBottom) {
                view.setPadding(l, t, r, btm)
            }
        }

        fun rescaleTextSize(textView: TextView) {
            val px = rescalePxF(textView.textSize).coerceAtLeast(1f)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        }

        fun rescaleCard(card: MaterialCardView) {
            val radius = (card.radius / oldScale * newScale).coerceAtLeast(0f)
            if (card.radius != radius) card.radius = radius
            val stroke = (card.strokeWidth.toFloat() / oldScale * newScale).roundToInt().coerceAtLeast(0)
            if (card.strokeWidth != stroke) card.strokeWidth = stroke
        }

        fun findFirstTextView(view: View): TextView? {
            if (view is TextView) return view
            val group = view as? ViewGroup ?: return null
            for (i in 0 until group.childCount) {
                val found = findFirstTextView(group.getChildAt(i))
                if (found != null) return found
            }
            return null
        }

        rescaleLayoutSize(b.ivSearch, width = true, height = true)
        rescaleMargins(b.ivSearch, start = true, top = true, end = false, bottom = false)

        rescaleLayoutSize(b.tvQuery, width = false, height = true)
        rescaleMargins(b.tvQuery, start = true, top = false, end = true, bottom = false)
        rescaleTextSize(b.tvQuery)

        rescaleMargins(b.panelInput, start = false, top = true, end = false, bottom = false)
        rescaleMargins(b.panelResults, start = false, top = true, end = false, bottom = false)

        rescaleMargins(b.panelKeyboard, start = true, top = false, end = true, bottom = false)
        rescaleMargins(b.recyclerKeys, start = false, top = true, end = false, bottom = false)

        listOf(b.btnClear, b.btnBackspace, b.btnSearch, b.btnClearHistory, b.btnSort).forEach(::rescaleCard)

        listOf(b.btnClear, b.btnBackspace, b.btnSearch, b.btnClearHistory, b.btnSort).forEach { btn ->
            rescaleLayoutSize(btn, width = false, height = true)
        }
        rescaleMargins(b.btnClear, start = false, top = false, end = true, bottom = false)
        rescaleMargins(b.btnSearch, start = false, top = true, end = false, bottom = false)
        rescaleMargins(b.btnClearHistory, start = false, top = true, end = false, bottom = false)
        rescaleMargins(b.btnSort, start = false, top = false, end = true, bottom = false)

        (b.btnClear.getChildAt(0) as? TextView)?.let(::rescaleTextSize)
        (b.btnBackspace.getChildAt(0) as? TextView)?.let(::rescaleTextSize)
        (b.btnSearch.getChildAt(0) as? TextView)?.let(::rescaleTextSize)
        (b.btnClearHistory.getChildAt(0) as? TextView)?.let(::rescaleTextSize)

        rescaleMargins(b.panelHistory, start = false, top = false, end = true, bottom = false)

        rescalePadding(b.recyclerSuggest, left = false, top = true, right = false, bottom = false)
        rescalePadding(b.recyclerHot, left = false, top = true, right = false, bottom = false)
        rescaleMargins(b.recyclerHot, start = false, top = false, end = true, bottom = false)

        rescaleMargins(b.tabLayout, start = true, top = false, end = true, bottom = false)
        run {
            val tabStrip = b.tabLayout.getChildAt(0) as? ViewGroup
            if (tabStrip != null) {
                for (i in 0 until tabStrip.childCount) {
                    val tabView = tabStrip.getChildAt(i)
                    findFirstTextView(tabView)?.let(::rescaleTextSize)
                }
            }
        }

        run {
            val sortContainer = b.btnSort.getChildAt(0) as? ViewGroup
            if (sortContainer != null) {
                rescalePadding(sortContainer, left = true, top = false, right = true, bottom = false)
                (sortContainer.getChildAt(0) as? ImageView)?.let { icon ->
                    rescaleLayoutSize(icon, width = true, height = true)
                    rescaleMargins(icon, start = false, top = false, end = true, bottom = false)
                }
            }
            rescaleTextSize(b.tvSort)
        }

        rescaleMargins(b.swipeRefresh, start = true, top = false, end = true, bottom = false)
        rescalePadding(b.recyclerResults, left = false, top = true, right = false, bottom = false)

        rescaleMargins(b.tvResultsPlaceholder, start = false, top = true, end = false, bottom = false)
        rescaleTextSize(b.tvResultsPlaceholder)

        lastAppliedUiScale = newScale
    }

    enum class VideoOrder(
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

    enum class LiveOrder(
        val apiValue: String,
        val labelRes: Int,
    ) {
        Online("online", R.string.search_sort_live_online),
        LiveTime("live_time", R.string.search_sort_live_time),
    }

    enum class UserOrder(
        val apiValue: String,
        val labelRes: Int,
    ) {
        Default("0", R.string.search_sort_user_default),
        Fans("fans", R.string.search_sort_user_fans),
        Level("level", R.string.search_sort_user_level),
    }

    enum class MediaKind(
        val searchType: String,
        val labelRes: Int,
    ) {
        Ft("media_ft", R.string.search_media_ft),
        Bangumi("media_bangumi", R.string.search_media_bangumi),
    }

    private enum class Tab(val index: Int) {
        Video(0),
        Media(1),
        Live(2),
        User(3),
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

    private fun tabForIndex(index: Int): Tab =
        when (index) {
            Tab.Video.index -> Tab.Video
            Tab.Media.index -> Tab.Media
            Tab.Live.index -> Tab.Live
            Tab.User.index -> Tab.User
            else -> Tab.Video
        }

    private fun stateForTab(index: Int): TabState =
        when (tabForIndex(index)) {
            Tab.Video -> videoState
            Tab.Media -> mediaState
            Tab.Live -> liveState
            Tab.User -> userState
        }

    private fun adapterForTab(index: Int): RecyclerView.Adapter<*> =
        when (tabForIndex(index)) {
            Tab.Video -> videoAdapter
            Tab.Media -> mediaAdapter
            Tab.Live -> liveAdapter
            Tab.User -> userAdapter
        }

    private fun spanCountForTab(index: Int): Int =
        when (tabForIndex(index)) {
            Tab.Media -> spanCountForBangumi()
            else -> spanCountForWidth()
        }

    private fun spanCountForCurrentTab(): Int = spanCountForTab(currentTabIndex)

    private fun spanCountForBangumi(): Int {
        val prefs = BiliClient.prefs
        val override = prefs.gridSpanCount
        if (override > 0) return override.coerceIn(1, 6)
        return when (spanCountForWidth()) {
            4 -> 6
            3 -> 4
            else -> 2
        }
    }

    private fun clearLoadedForTab(index: Int) {
        when (tabForIndex(index)) {
            Tab.Video -> {
                loadedBvids.clear()
                videoAdapter.submit(emptyList())
            }

            Tab.Media -> {
                loadedSeasonIds.clear()
                mediaAdapter.submit(emptyList())
            }

            Tab.Live -> {
                loadedRoomIds.clear()
                liveAdapter.submit(emptyList())
            }

            Tab.User -> {
                loadedMids.clear()
                userAdapter.submit(emptyList())
            }
        }
    }

    private fun updateSortUi() {
        val b = _binding ?: return
        when (tabForIndex(currentTabIndex)) {
            Tab.Video -> {
                b.btnSort.visibility = View.VISIBLE
                b.tvSort.text = getString(currentVideoOrder.labelRes)
            }

            Tab.Live -> {
                b.btnSort.visibility = View.VISIBLE
                b.tvSort.text = getString(currentLiveOrder.labelRes)
            }

            Tab.User -> {
                b.btnSort.visibility = View.VISIBLE
                b.tvSort.text = getString(currentUserOrder.labelRes)
            }

            Tab.Media -> {
                b.btnSort.visibility = View.VISIBLE
                b.tvSort.text = getString(currentMediaKind.labelRes)
            }
        }
    }

    private fun openBangumiDetail(season: BangumiSeason) {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        val isDrama = currentMediaKind == MediaKind.Ft
        // Use add+hide instead of replace so SearchFragment's view state (results panel, scroll, focus)
        // is preserved when returning from the detail page, matching the behavior of activity navigations.
        parentFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .hide(this)
            .add(
                R.id.main_container,
                MyBangumiDetailFragment.newInstance(
                    seasonId = season.seasonId,
                    isDrama = isDrama,
                    continueEpId = null,
                    continueEpIndex = null,
                ),
            )
            .addToBackStack(null)
            .commit()
    }
}
