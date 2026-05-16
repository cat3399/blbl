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
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BackButtonSizingHelper
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.GridViewportFillMonitor
import blbl.cat3399.core.ui.GridSpanPolicy
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.installGridViewportFillMonitor
import blbl.cat3399.databinding.FragmentLiveAreaDetailBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class LiveAreaDetailFragment : Fragment() {
    private var _binding: FragmentLiveAreaDetailBinding? = null
    private val binding get() = _binding!!

    private val parentAreaId: Int by lazy { requireArguments().getInt(ARG_PARENT_AREA_ID, 0) }
    private val areaId: Int by lazy { requireArguments().getInt(ARG_AREA_ID, 0) }
    private val parentTitle: String by lazy { requireArguments().getString(ARG_PARENT_TITLE).orEmpty() }
    private val areaTitle: String by lazy { requireArguments().getString(ARG_AREA_TITLE).orEmpty() }

    private lateinit var adapter: LiveRoomAdapter
    private val loadedRoomIds = HashSet<Long>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var page: Int = 1
    private var requestToken: Int = 0
    private var lastSpanCountContentWidthPx: Int = -1

    private var initialLoadTriggered: Boolean = false
    private var pendingFocusFirstItem: Boolean = false
    private var pendingRestoreRoomId: Long? = null
    private var pendingRestorePositionHint: Int? = null
    private var dpadGridController: DpadGridController? = null
    private var viewportFillMonitor: GridViewportFillMonitor? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveAreaDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.tvTitle.text =
            when {
                parentTitle.isBlank() -> areaTitle
                areaTitle.isBlank() -> parentTitle
                parentTitle == areaTitle -> areaTitle
                else -> "$parentTitle · $areaTitle"
            }
        applyBackButtonSizing()

        if (!::adapter.isInitialized) {
            adapter =
                LiveRoomAdapter { position, room ->
                    if (!room.isLive) {
                        AppToast.show(requireContext(), "未开播")
                        return@LiveRoomAdapter
                    }
                    // Restore focus to this room card after returning from LivePlayerActivity.
                    // Use roomId as the stable identity (positions can shift).
                    pendingRestoreRoomId = room.roomId
                    pendingRestorePositionHint = position
                    // Prioritize restoring to the clicked card after return.
                    pendingFocusFirstItem = false
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
        val createdBinding = binding
        createdBinding.recycler.postIfAlive(isAlive = { _binding === createdBinding && isAdded }) { updateRecyclerSpanCountIfNeeded() }

        // Keep focus inside this detail page and provide a consistent edge behavior, similar to favorites detail.
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            binding.btnBack.requestFocus()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            binding.btnBack.requestFocus()
                            return true
                        }

                        override fun onRightEdge() = Unit

                        override fun canLoadMore(): Boolean = !endReached

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
                canLoadMore = { !isLoadingMore && !endReached },
                loadMore = { loadNextPage() },
            )

        binding.swipeRefresh.setOnRefreshListener { resetAndLoad() }

        if (savedInstanceState == null) {
            pendingFocusFirstItem = true
            binding.recycler.requestFocus()
        }
    }

    override fun onDestroyView() {
        dpadGridController?.release()
        dpadGridController = null
        viewportFillMonitor?.release()
        viewportFillMonitor = null
        _binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        applyBackButtonSizing()
        updateRecyclerSpanCountIfNeeded(force = true)
        viewportFillMonitor?.scheduleCheck()
        maybeTriggerInitialLoad()
        // Make sure focus stays within this detail page on return.
        val b = _binding ?: return
        val isUiAlive = { _binding === b && isResumed }
        b.root.postIfAlive(isAlive = isUiAlive) {
            val cur = activity?.currentFocus
            if (cur == null || !FocusTreeUtils.isDescendantOf(cur, b.root)) {
                b.btnBack.requestFocus()
            }
        }
        b.recycler.postIfAlive(isAlive = isUiAlive) {
            restoreFocusIfNeeded()
            maybeFocusFirstItem()
        }
    }

    private fun applyBackButtonSizing() {
        val b = _binding ?: return
        val sidebarScale = UiScale.factor(requireContext())
        BackButtonSizingHelper.applySidebarSizing(
            view = b.btnBack,
            resources = b.root.resources,
            sidebarScale = sidebarScale,
        )
    }

    companion object {
        private const val ARG_PARENT_AREA_ID = "parent_area_id"
        private const val ARG_PARENT_TITLE = "parent_title"
        private const val ARG_AREA_ID = "area_id"
        private const val ARG_AREA_TITLE = "area_title"

        fun newInstance(parentAreaId: Int, parentTitle: String, areaId: Int, areaTitle: String): LiveAreaDetailFragment =
            LiveAreaDetailFragment().apply {
                arguments =
                    Bundle().apply {
                        putInt(ARG_PARENT_AREA_ID, parentAreaId)
                        putString(ARG_PARENT_TITLE, parentTitle)
                        putInt(ARG_AREA_ID, areaId)
                        putString(ARG_AREA_TITLE, areaTitle)
                    }
            }
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

    private fun resetAndLoad() {
        loadedRoomIds.clear()
        endReached = false
        isLoadingMore = false
        page = 1
        requestToken++
        clearPendingRestore()
        dpadGridController?.clearPendingFocusAfterLoadMore()
        adapter.submit(emptyList())
        loadNextPage(isRefresh = true)
    }

    private fun clearPendingRestore() {
        pendingRestoreRoomId = null
        pendingRestorePositionHint = null
    }

    private fun resolvePendingRestorePosition(itemCount: Int): Int? {
        val roomId = pendingRestoreRoomId ?: return null
        val hint = pendingRestorePositionHint
        if (hint != null && hint in 0 until itemCount) {
            if (adapter.getItemId(hint) == roomId) return hint
        }
        for (i in 0 until itemCount) {
            if (adapter.getItemId(i) == roomId) return i
        }
        return null
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || endReached) return
        val token = requestToken
        isLoadingMore = true
        val startAt = SystemClock.uptimeMillis()
        lifecycleScope.launch {
            try {
                val pageSize = 30
                val fetched =
                    BiliApi.liveAreaRooms(
                        parentAreaId = parentAreaId,
                        areaId = areaId,
                        page = page,
                        pageSize = pageSize,
                    )
                if (token != requestToken) return@launch
                if (fetched.isEmpty() || fetched.size < pageSize) endReached = true
                val filtered = fetched.filter { loadedRoomIds.add(it.roomId) }
                if (filtered.isNotEmpty()) {
                    if (page == 1) adapter.submit(filtered) else adapter.append(filtered)
                }
                _binding?.let { b ->
                    b.recycler.postIfAlive(isAlive = { _binding === b && isResumed }) {
                        dpadGridController?.consumePendingFocusAfterLoadMore()
                    }
                }
                restoreFocusIfNeeded()
                maybeFocusFirstItem()
                page++
                AppLog.i("LiveAreaDetail", "load ok pid=$parentAreaId aid=$areaId page=${page - 1} add=${filtered.size} total=${adapter.itemCount} cost=${SystemClock.uptimeMillis() - startAt}ms")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("LiveAreaDetail", "load failed pid=$parentAreaId aid=$areaId page=$page", t)
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                if (isRefresh && token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
                isLoadingMore = false
                viewportFillMonitor?.scheduleCheck()
            }
        }
    }

    private fun restoreFocusIfNeeded() {
        val b = _binding ?: return
        if (pendingRestoreRoomId == null) return
        if (!isResumed) return
        if (!::adapter.isInitialized) return

        val itemCount = adapter.itemCount
        if (itemCount <= 0) {
            // Keep pending; ensure we still have a visible focus target while data binds.
            val cur = activity?.currentFocus
            if (cur == null || !FocusTreeUtils.isDescendantOf(cur, b.root)) {
                b.btnBack.requestFocus()
            }
            return
        }

        val pos = resolvePendingRestorePosition(itemCount = itemCount)
        if (pos == null) {
            // The room no longer exists in this list (data changed). Give up and clear.
            clearPendingRestore()
            b.recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true || b.btnBack.requestFocus()
            return
        }

        pendingRestorePositionHint = pos
        val recycler = b.recycler

        val isUiAlive = { _binding === b && isResumed }

        recycler.postIfAlive(isAlive = isUiAlive) outer@{
            val resolved = resolvePendingRestorePosition(itemCount = adapter.itemCount) ?: run {
                clearPendingRestore()
                return@outer
            }
            recycler.scrollToPosition(resolved)
            recycler.postIfAlive(isAlive = isUiAlive) inner@{
                val resolved2 = resolvePendingRestorePosition(itemCount = adapter.itemCount) ?: run {
                    clearPendingRestore()
                    return@inner
                }
                tryRestoreFocusAtPosition(recycler = recycler, pos = resolved2, attemptsLeft = 12)
            }
        }
    }

    private fun tryRestoreFocusAtPosition(recycler: RecyclerView, pos: Int, attemptsLeft: Int) {
        val b = _binding ?: return
        if (!isAdded || !isResumed) return
        if (pendingRestoreRoomId == null) return

        val vh = recycler.findViewHolderForAdapterPosition(pos)
        if (vh != null && vh.itemView.requestFocus()) {
            clearPendingRestore()
            return
        }

        if (attemptsLeft <= 0) {
            recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true || b.btnBack.requestFocus()
            clearPendingRestore()
            return
        }

        recycler.postIfAlive(isAlive = { _binding === b && isResumed }) {
            tryRestoreFocusAtPosition(recycler = recycler, pos = pos, attemptsLeft = attemptsLeft - 1)
        }
    }

    private fun maybeFocusFirstItem() {
        if (pendingRestoreRoomId != null) return
        if (!pendingFocusFirstItem) return
        val b = _binding ?: return
        if (!isResumed) return
        if (!::adapter.isInitialized) return
        if (adapter.itemCount <= 0) return

        val recycler = b.recycler
        val focused = activity?.currentFocus
        if (focused != null && FocusTreeUtils.isDescendantOf(focused, recycler)) {
            pendingFocusFirstItem = false
            return
        }

        val isUiAlive = { _binding === b && isResumed }

        // Fast-path: already laid out.
        recycler.findViewHolderForAdapterPosition(0)?.itemView?.let { first ->
            if (first.requestFocus()) {
                pendingFocusFirstItem = false
                return
            }
        }

        recycler.postIfAlive(isAlive = isUiAlive) {
            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                pendingFocusFirstItem = false
                return@postIfAlive
            }

            recycler.scrollToPosition(0)
            recycler.postIfAlive(isAlive = isUiAlive) {
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true || b.btnBack.requestFocus()
                pendingFocusFirstItem = false
            }
        }
    }

}
