package blbl.cat3399.feature.live

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
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.tv.TvMode
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
    private val parentAreaId: Int by lazy { requireArguments().getInt(ARG_PARENT_AREA_ID, 0) }
    private val areaId: Int by lazy { requireArguments().getInt(ARG_AREA_ID, 0) }
    private val title: String? by lazy { requireArguments().getString(ARG_TITLE) }
    private val enableTabFocus: Boolean by lazy { requireArguments().getBoolean(ARG_ENABLE_TAB_FOCUS, true) }

    private val loadedRoomIds = HashSet<Long>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false

    private var page: Int = 1
    private var requestToken: Int = 0

    private var pendingFocusFirstCardFromTab: Boolean = false
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false
    private var pendingFocusNextCardAfterLoadMoreFromDpad: Boolean = false
    private var pendingFocusNextCardAfterLoadMoreFromPos: Int = RecyclerView.NO_POSITION

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveGridBinding.inflate(inflater, container, false)
        AppLog.d("LiveGrid", "onCreateView src=$source pid=$parentAreaId title=${title.orEmpty()} t=${SystemClock.uptimeMillis()}")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter =
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

        adapter.setTvMode(TvMode.isEnabled(requireContext()))
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth())
        (binding.recycler.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.clearOnScrollListeners()
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (isLoadingMore || endReached) return
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
        binding.recycler.post { updateRecyclerSpanCountIfNeeded() }
        binding.recycler.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val itemView = v
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (!binding.recycler.canScrollVertically(-1)) {
                                    val lm = binding.recycler.layoutManager as? GridLayoutManager ?: return@setOnKeyListener false
                                    val holder = binding.recycler.findContainingViewHolder(itemView) ?: return@setOnKeyListener false
                                    val pos =
                                        holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                                            ?: return@setOnKeyListener false
                                    if (pos < lm.spanCount) {
                                        return@setOnKeyListener if (enableTabFocus) {
                                            focusSelectedTabIfAvailable()
                                        } else {
                                            focusBackButtonIfAvailable()
                                        }
                                    }
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_LEFT)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    if (!enableTabFocus) {
                                        focusBackButtonIfAvailable()
                                        return@setOnKeyListener true
                                    }
                                    if (!switchToPrevTabFromContentEdge()) return@setOnKeyListener false
                                    return@setOnKeyListener true
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_RIGHT)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    if (!enableTabFocus) return@setOnKeyListener true
                                    if (!switchToNextTabFromContentEdge()) return@setOnKeyListener false
                                    return@setOnKeyListener true
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_DOWN)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    if (binding.recycler.canScrollVertically(1)) {
                                        // Focus-search failed but the list can still scroll; scroll a bit to let
                                        // RecyclerView lay out the next row, and keep focus inside the list.
                                        val dy = (itemView.height * 0.8f).toInt().coerceAtLeast(1)
                                        binding.recycler.scrollBy(0, dy)
                                        binding.recycler.post {
                                            if (_binding == null) return@post
                                            tryFocusNextDownFromCurrent()
                                        }
                                        return@setOnKeyListener true
                                    }
                                    if (!endReached) {
                                        val holder = binding.recycler.findContainingViewHolder(v)
                                        val pos =
                                            holder?.bindingAdapterPosition
                                                ?.takeIf { it != RecyclerView.NO_POSITION }
                                                ?: RecyclerView.NO_POSITION
                                        if (pos != RecyclerView.NO_POSITION) {
                                            pendingFocusNextCardAfterLoadMoreFromDpad = true
                                            pendingFocusNextCardAfterLoadMoreFromPos = pos
                                        }
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

        binding.swipeRefresh.setOnRefreshListener { resetAndLoad() }
    }

    override fun onResume() {
        super.onResume()
        if (this::adapter.isInitialized) adapter.setTvMode(TvMode.isEnabled(requireContext()))
        updateRecyclerSpanCountIfNeeded(force = true)
        maybeTriggerInitialLoad()
        maybeConsumePendingFocusFirstCard()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        resetAndLoad()
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
        resetAndLoad()
        initialLoadTriggered = true
    }

    private fun resetAndLoad() {
        loadedRoomIds.clear()
        endReached = false
        isLoadingMore = false
        page = 1
        requestToken++
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || endReached) return
        val token = requestToken
        isLoadingMore = true
        val startAt = SystemClock.uptimeMillis()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val areaPageSize = 30
                val fetched =
                    when (source) {
                        SRC_FOLLOWING -> {
                            val res = BiliApi.liveFollowing(page = page, pageSize = 10)
                            if (!res.hasMore) endReached = true
                            res.items
                        }
                        SRC_AREA -> {
                            val list =
                                BiliApi.liveAreaRooms(
                                    parentAreaId = parentAreaId,
                                    areaId = areaId,
                                    page = page,
                                    pageSize = areaPageSize,
                                )
                            if (list.isEmpty() || list.size < areaPageSize) endReached = true
                            list
                        }
                        else -> BiliApi.liveRecommend(page = page)
                    }

                if (token != requestToken) return@launch

                val filteredByArea =
                    if (source == SRC_RECOMMEND && parentAreaId > 0) fetched.filter { it.parentAreaId == parentAreaId } else fetched
                val filtered = filteredByArea.filter { loadedRoomIds.add(it.roomId) }

                if (filtered.isNotEmpty()) {
                    if (page == 1) adapter.submit(filtered) else adapter.append(filtered)
                }
                _binding?.recycler?.post {
                    maybeConsumePendingFocusFirstCard()
                    maybeConsumePendingFocusNextCardAfterLoadMoreFromDpad()
                }

                if (source == SRC_RECOMMEND) {
                    // Recommend endpoint can return lots of unrelated areas; keep fetching a bit when filtered empty.
                    if (parentAreaId > 0 && filteredByArea.isEmpty()) {
                        if (page >= 8) endReached = true
                    } else if (fetched.isEmpty() || filtered.isEmpty() && page >= 8) {
                        // Conservative end guard.
                        endReached = true
                    }
                } else if (source == SRC_AREA) {
                    // Conservative end guard for empty-after-dedup.
                    if (filtered.isEmpty() && page >= 8) endReached = true
                }

                page++
                AppLog.i("LiveGrid", "load ok src=$source pid=$parentAreaId page=${page - 1} add=${filtered.size} total=${adapter.itemCount} cost=${SystemClock.uptimeMillis() - startAt}ms")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("LiveGrid", "load failed src=$source pid=$parentAreaId page=$page", t)
                context?.let { Toast.makeText(it, "加载失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show() }
            } finally {
                if (isRefresh && token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private fun spanCountForWidth(): Int {
        val override = blbl.cat3399.core.net.BiliClient.prefs.gridSpanCount
        if (override > 0) return override.coerceIn(1, 6)
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return autoSpanCountForWidthDp(widthDp)
    }

    private fun autoSpanCountForWidthDp(widthDp: Float): Int {
        val override = blbl.cat3399.core.net.BiliClient.prefs.gridSpanCount
        if (override > 0) return override.coerceIn(1, 6)
        val minCardWidthDp = if (TvMode.isEnabled(requireContext())) 210f else 168f
        val auto = (widthDp / minCardWidthDp).toInt()
        return auto.coerceIn(2, 6)
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
    }

    override fun requestFocusFirstCardFromTab(): Boolean {
        pendingFocusFirstCardFromTab = true
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    override fun requestFocusFirstCardFromContentSwitch(): Boolean {
        pendingFocusFirstCardFromContentSwitch = true
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    private fun maybeConsumePendingFocusFirstCard(): Boolean {
        if (!pendingFocusFirstCardFromTab && !pendingFocusFirstCardFromContentSwitch) return false
        if (!isAdded || _binding == null) return false
        if (!isResumed) return false

        val focused = activity?.currentFocus
        if (focused != null && focused != binding.recycler && isDescendantOf(focused, binding.recycler)) {
            pendingFocusFirstCardFromTab = false
            pendingFocusFirstCardFromContentSwitch = false
            return false
        }

        val parentView = parentFragment?.view
        val tabLayout =
            parentView?.findViewById<com.google.android.material.tabs.TabLayout?>(blbl.cat3399.R.id.tab_layout)
        if (pendingFocusFirstCardFromTab) {
            if (focused == null || tabLayout == null || !isDescendantOf(focused, tabLayout)) {
                pendingFocusFirstCardFromTab = false
            }
        }

        if (!this::adapter.isInitialized) return false
        if (adapter.itemCount <= 0) {
            binding.recycler.requestFocus()
            return true
        }

        val recycler = binding.recycler
        recycler.post outer@{
            if (_binding == null) return@outer
            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                pendingFocusFirstCardFromTab = false
                pendingFocusFirstCardFromContentSwitch = false
                return@outer
            }
            recycler.scrollToPosition(0)
            recycler.post inner@{
                if (_binding == null) return@inner
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                pendingFocusFirstCardFromTab = false
                pendingFocusFirstCardFromContentSwitch = false
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

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current == ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun clearPendingFocusNextCardAfterLoadMoreFromDpad() {
        pendingFocusNextCardAfterLoadMoreFromDpad = false
        pendingFocusNextCardAfterLoadMoreFromPos = RecyclerView.NO_POSITION
    }

    private fun maybeConsumePendingFocusNextCardAfterLoadMoreFromDpad(): Boolean {
        if (!pendingFocusNextCardAfterLoadMoreFromDpad) return false
        if (!isAdded || _binding == null || !isResumed || !this::adapter.isInitialized) {
            clearPendingFocusNextCardAfterLoadMoreFromDpad()
            return false
        }

        val recycler = binding.recycler
        val lm = recycler.layoutManager as? GridLayoutManager
        if (lm == null) {
            clearPendingFocusNextCardAfterLoadMoreFromDpad()
            return false
        }

        val anchorPos = pendingFocusNextCardAfterLoadMoreFromPos
        if (anchorPos == RecyclerView.NO_POSITION) {
            clearPendingFocusNextCardAfterLoadMoreFromDpad()
            return false
        }

        val focused = activity?.currentFocus
        if (focused != null && !isDescendantOf(focused, recycler)) {
            clearPendingFocusNextCardAfterLoadMoreFromDpad()
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
        clearPendingFocusNextCardAfterLoadMoreFromDpad()
        if (candidatePos == null) return false

        recycler.findViewHolderForAdapterPosition(candidatePos)?.itemView?.requestFocus()
            ?: run {
                recycler.scrollToPosition(candidatePos)
                recycler.post { recycler.findViewHolderForAdapterPosition(candidatePos)?.itemView?.requestFocus() }
            }
        return true
    }

    private fun tryFocusNextDownFromCurrent() {
        val b = _binding ?: return
        if (!isResumed) return
        val recycler = b.recycler
        val focused = activity?.currentFocus ?: return
        if (!isDescendantOf(focused, recycler)) return
        val itemView = recycler.findContainingItemView(focused) ?: return
        val next = FocusFinder.getInstance().findNextFocus(recycler, itemView, View.FOCUS_DOWN)
        if (next != null && isDescendantOf(next, recycler)) {
            next.requestFocus()
        }
    }

    private fun switchToNextTabFromContentEdge(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(blbl.cat3399.R.id.tab_layout) ?: return false
        if (tabLayout.tabCount <= 1) return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val cur = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        val next = cur + 1
        if (next >= tabLayout.tabCount) return false
        tabLayout.getTabAt(next)?.select() ?: return false
        tabLayout.post {
            (parentFragment as? LiveGridTabSwitchFocusHost)?.requestFocusCurrentPageFirstCardFromContentSwitch()
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
        tabLayout.getTabAt(prev)?.select() ?: return false
        tabLayout.post {
            (parentFragment as? LiveGridTabSwitchFocusHost)?.requestFocusCurrentPageFirstCardFromContentSwitch()
                ?: tabStrip.getChildAt(prev)?.requestFocus()
        }
        return true
    }

    override fun onDestroyView() {
        initialLoadTriggered = false
        clearPendingFocusNextCardAfterLoadMoreFromDpad()
        lastSpanCountContentWidthPx = -1
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_SOURCE = "source"
        private const val ARG_PARENT_AREA_ID = "parent_area_id"
        private const val ARG_AREA_ID = "area_id"
        private const val ARG_TITLE = "title"
        private const val ARG_ENABLE_TAB_FOCUS = "enable_tab_focus"

        const val SRC_RECOMMEND = "recommend"
        const val SRC_FOLLOWING = "following"
        const val SRC_AREA = "area"

        fun newRecommend() = LiveGridFragment().apply { arguments = Bundle().apply { putString(ARG_SOURCE, SRC_RECOMMEND) } }

        fun newFollowing() = LiveGridFragment().apply { arguments = Bundle().apply { putString(ARG_SOURCE, SRC_FOLLOWING) } }

        fun newArea(parentAreaId: Int, areaId: Int, title: String, enableTabFocus: Boolean = true) =
            LiveGridFragment().apply {
                arguments =
                    Bundle().apply {
                        putString(ARG_SOURCE, SRC_AREA)
                        putInt(ARG_PARENT_AREA_ID, parentAreaId)
                        putInt(ARG_AREA_ID, areaId)
                        putString(ARG_TITLE, title)
                        putBoolean(ARG_ENABLE_TAB_FOCUS, enableTabFocus)
                    }
            }

        fun newRecommendFiltered(parentAreaId: Int, title: String) =
            LiveGridFragment().apply {
                arguments =
                    Bundle().apply {
                        putString(ARG_SOURCE, SRC_RECOMMEND)
                        putInt(ARG_PARENT_AREA_ID, parentAreaId)
                        putString(ARG_TITLE, title)
                    }
            }
    }
}
