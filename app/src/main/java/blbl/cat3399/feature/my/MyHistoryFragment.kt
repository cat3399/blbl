package blbl.cat3399.feature.my

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
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.HistoryEntry
import blbl.cat3399.core.model.LiveRoomCard
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.paging.PagedGridStateMachine
import blbl.cat3399.core.paging.appliedOrNull
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.requestFocusAdapterPositionReliable
import blbl.cat3399.core.ui.requestFocusFirstItemOrSelfAfterRefresh
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.feature.following.openUpDetailFromVideoCard
import blbl.cat3399.feature.live.LivePlayerActivity
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.VideoCardPlaylistPage
import blbl.cat3399.feature.video.VideoCardActionController
import blbl.cat3399.feature.video.VideoCardDismissBehavior
import blbl.cat3399.feature.video.VideoCardVisibilityFilter
import blbl.cat3399.feature.video.buildPagedVideoCardPlaybackHandle
import blbl.cat3399.feature.video.historyVideoCardPlaylistItem
import blbl.cat3399.feature.video.openVideoDetailFromPlaybackHandle
import blbl.cat3399.feature.video.openVideoFromPlaybackHandle
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MyHistoryFragment : Fragment(), MyTabSwitchFocusTarget, RefreshKeyHandler {
    private enum class RefreshPresentation {
        ResetToServerOrder,
        PreserveCurrentOrder,
    }

    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: HistoryAdapter

    private val loadedKeys = HashSet<String>()
    private val paging = PagedGridStateMachine<BiliApi.HistoryCursor?>(initialKey = null)
    private var initialLoadTriggered: Boolean = false

    private var pendingFocusFirstItemFromTabSwitch: Boolean = false
    private var pendingFocusFirstItemAfterRefresh: Boolean = false
    private var dpadGridController: DpadGridController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        AppLog.d("MyHistory", "onCreateView t=${SystemClock.uptimeMillis()}")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            val actionController =
                VideoCardActionController(
                    context = requireContext(),
                    scope = viewLifecycleOwner.lifecycleScope,
                    dismissBehavior = VideoCardDismissBehavior.DeleteHistory,
                    onOpenDetail = { card, pos -> openDetail(adapter.videoPosition(card) ?: pos) },
                    onOpenUp = { card -> openUpDetailFromVideoCard(card) },
                    onCardRemoved = { stableKey ->
                        removeHistoryEntryAndRestoreFocus(stableKey)
                    },
                )
            adapter =
                HistoryAdapter(
                    onVideoClick = { _, pos ->
                        requireContext().openVideoFromPlaybackHandle(
                            playbackHandle = historyPlaybackHandle(),
                            position = pos,
                            openDetailBeforePlay = BiliClient.prefs.playerOpenDetailBeforePlay,
                            canOpenDetail = { it.bvid.isNotBlank() && (it.epId == null || it.epId <= 0L) },
                            configurePlayerIntent = { card ->
                                card.epId?.let { putExtra(PlayerActivity.EXTRA_EP_ID, it) }
                                card.aid?.let { putExtra(PlayerActivity.EXTRA_AID, it) }
                                card.seasonId?.let { putExtra(PlayerActivity.EXTRA_SEASON_ID, it) }
                                card.progressSec?.takeIf { it >= 5L }?.let { sec ->
                                    putExtra(PlayerActivity.EXTRA_START_POSITION_MS, sec * 1000L)
                                }
                            },
                        )
                    },
                    onLiveClick = ::openLive,
                    actionDelegate = actionController,
                )
        }
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth(resources))
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
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
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            focusSelectedMyTabIfAvailable()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            return switchToPrevMyTabFromContentEdge()
                        }

                        override fun onRightEdge() {
                            switchToNextMyTabFromContentEdge()
                        }

                        override fun canLoadMore(): Boolean = !paging.snapshot().endReached

                        override fun loadMore() {
                            loadNextPage()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                        enableCenterLongPressToLongClick = true,
                    ),
            ).also { it.install() }
        binding.swipeRefresh.setOnRefreshListener {
            pendingFocusFirstItemAfterRefresh = true
            dpadGridController?.parkFocusForDataSetReset()
            resetAndLoad()
        }
    }

    override fun onResume() {
        super.onResume()
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth(resources)
        maybeTriggerInitialLoad()
        silentRefreshForProgressDataSource()
        maybeConsumePendingFocusFirstItemFromTabSwitch()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        pendingFocusFirstItemAfterRefresh = true
        dpadGridController?.parkFocusForDataSetReset()
        b.swipeRefresh.isRefreshing = true
        resetAndLoad()
        return true
    }

    override fun requestFocusFirstItemFromTabSwitch(): Boolean {
        pendingFocusFirstItemFromTabSwitch = true
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstItemFromTabSwitch()
    }

    private fun maybeConsumePendingFocusFirstItemFromTabSwitch(): Boolean {
        if (!pendingFocusFirstItemFromTabSwitch) return false
        if (!isAdded || _binding == null) return false
        if (!isResumed) return false
        if (!this::adapter.isInitialized) return false

        val focused = activity?.currentFocus
        if (focused != null && focused != binding.recycler && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
            pendingFocusFirstItemFromTabSwitch = false
            return false
        }

        if (adapter.itemCount <= 0) {
            binding.recycler.requestFocus()
            return true
        }

        val recycler = binding.recycler
        recycler.postIfAlive(isAlive = { _binding != null }) {
            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                pendingFocusFirstItemFromTabSwitch = false
                return@postIfAlive
            }
            recycler.scrollToPosition(0)
            recycler.postIfAlive(isAlive = { _binding != null }) {
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
                pendingFocusFirstItemFromTabSwitch = false
            }
        }
        return true
    }

    private fun maybeTriggerInitialLoad() {
        if (initialLoadTriggered) return
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
        if (pendingFocusFirstItemAfterRefresh) {
            dpadGridController?.parkFocusForDataSetReset()
        }
        paging.reset()
        loadedKeys.clear()
        dpadGridController?.clearPendingFocusAfterLoadMore()
        adapter.submit(emptyList())
        loadNextPage(isRefresh = true)
    }

    private fun silentRefreshForProgressDataSource() {
        val b = _binding ?: return
        if (!initialLoadTriggered) return
        if (adapter.itemCount <= 0) return
        if (b.swipeRefresh.isRefreshing) return
        if (paging.snapshot().isLoading) return
        paging.reset()
        loadedKeys.clear()
        dpadGridController?.clearPendingFocusAfterLoadMore()
        loadNextPage(
            isRefresh = true,
            refreshPresentation = RefreshPresentation.PreserveCurrentOrder,
        )
    }

    private data class FetchedPage(
        val items: List<HistoryEntry>,
        val nextCursor: BiliApi.HistoryCursor?,
    )

    private fun loadNextPage(
        isRefresh: Boolean = false,
        refreshPresentation: RefreshPresentation = RefreshPresentation.ResetToServerOrder,
    ) {
        val preserveCurrentOrderRefresh = isRefresh && refreshPresentation == RefreshPresentation.PreserveCurrentOrder
        val startSnap = paging.snapshot()
        if (startSnap.isLoading || startSnap.endReached) return
        val startGen = startSnap.generation
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result =
                    paging.loadNextPage(
                        isRefresh = isRefresh,
                        fetch = { cursor ->
                            var c = cursor
                            var fetchedPage: FetchedPage? = null
                            while (fetchedPage == null) {
                                val page =
                                    BiliApi.historyCursor(
                                        max = c?.max ?: 0,
                                        business = c?.business,
                                        viewAt = c?.viewAt ?: 0,
                                        ps = 24,
                                    )

                                val nextCursor = page.cursor
                                val filtered = filterFreshHistoryEntries(page.items)
                                if (filtered.isNotEmpty() || nextCursor == null || nextCursor == c || page.items.isEmpty()) {
                                    fetchedPage = FetchedPage(items = filtered, nextCursor = nextCursor)
                                } else {
                                    c = nextCursor
                                }
                            }
                            checkNotNull(fetchedPage)
                        },
                        reduce = { _, fetched ->
                            PagedGridStateMachine.Update(
                                items = fetched.items,
                                nextKey = fetched.nextCursor,
                                endReached = fetched.nextCursor == null,
                            )
                        },
                    )

                val applied = result.appliedOrNull() ?: return@launch
                if (applied.items.isEmpty()) {
                    if (applied.isRefresh) {
                        if (preserveCurrentOrderRefresh) {
                            resetLoadedKeysFromAdapter()
                        } else {
                            adapter.submit(emptyList())
                        }
                    }
                    if (applied.isRefresh && pendingFocusFirstItemAfterRefresh) {
                        pendingFocusFirstItemAfterRefresh = false
                        _binding?.recycler?.postIfAlive(isAlive = { _binding != null && isResumed }) {
                            val recycler = binding.recycler
                            val isUiAlive = { _binding != null && isResumed }
                            recycler.requestFocusFirstItemOrSelfAfterRefresh(
                                itemCount = 0,
                                smoothScroll = false,
                                isAlive = isUiAlive,
                            )
                        }
                    }
                    return@launch
                }
                if (applied.isRefresh) {
                    if (preserveCurrentOrderRefresh) {
                        adapter.updateExistingEntriesPreservingOrder(applied.items)
                        resetLoadedKeysFromAdapter()
                    } else {
                        applied.items.forEach { loadedKeys.add(it.stableKey()) }
                        adapter.submit(applied.items)
                    }
                } else {
                    applied.items.forEach { loadedKeys.add(it.stableKey()) }
                    adapter.append(applied.items)
                }
                _binding?.recycler?.postIfAlive(isAlive = { _binding != null }) {
                    if (pendingFocusFirstItemAfterRefresh && applied.isRefresh) {
                        pendingFocusFirstItemAfterRefresh = false
                        val recycler = binding.recycler
                        val isUiAlive = { _binding != null && isResumed }
                        recycler.requestFocusFirstItemOrSelfAfterRefresh(
                            itemCount = adapter.itemCount,
                            smoothScroll = false,
                            isAlive = isUiAlive,
                        )
                        return@postIfAlive
                    }
                    maybeConsumePendingFocusFirstItemFromTabSwitch()
                    dpadGridController?.consumePendingFocusAfterLoadMore()
                }

            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (preserveCurrentOrderRefresh) resetLoadedKeysFromAdapter()
                AppLog.e("MyHistory", "load failed", t)
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                if (paging.snapshot().generation == startGen) _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    private fun resetLoadedKeysFromAdapter() {
        loadedKeys.clear()
        loadedKeys.addAll(adapter.stableKeysSnapshot())
    }

    override fun onDestroyView() {
        initialLoadTriggered = false
        dpadGridController?.release()
        dpadGridController = null
        _binding = null
        super.onDestroyView()
    }

    private fun openDetail(position: Int) {
        requireContext().openVideoDetailFromPlaybackHandle(historyPlaybackHandle(), position)
    }

    private fun openLive(room: LiveRoomCard) {
        if (!room.isLive) {
            AppToast.show(requireContext(), "直播未开播")
            return
        }
        startActivity(
            Intent(requireContext(), LivePlayerActivity::class.java)
                .putExtra(LivePlayerActivity.EXTRA_ROOM_ID, room.roomId)
                .putExtra(LivePlayerActivity.EXTRA_TITLE, room.title)
                .putExtra(LivePlayerActivity.EXTRA_UNAME, room.uname),
        )
    }

    private fun filterFreshHistoryEntries(entries: List<HistoryEntry>): List<HistoryEntry> {
        if (entries.isEmpty()) return emptyList()
        val seen = HashSet<String>(entries.size)
        return entries.filter { entry ->
            val stableKey = entry.stableKey()
            if (loadedKeys.contains(stableKey)) return@filter false
            if (!seen.add(stableKey)) return@filter false
            if (entry is HistoryEntry.Video && VideoCardVisibilityFilter.filterVisible(listOf(entry.card)).isEmpty()) {
                return@filter false
            }
            true
        }
    }

    private fun removeHistoryEntryAndRestoreFocus(stableKey: String) {
        val recycler = _binding?.recycler ?: return
        val removedIndex = adapter.removeByStableKey(stableKey)
        if (removedIndex < 0) return

        recycler.postIfAlive(isAlive = { _binding != null && isResumed }) {
            val itemCount = adapter.itemCount
            if (itemCount <= 0) {
                recycler.requestFocus()
                return@postIfAlive
            }

            recycler.requestFocusAdapterPositionReliable(
                position = removedIndex.coerceIn(0, itemCount - 1),
                smoothScroll = false,
                isAlive = { _binding != null && isResumed },
                onFocused = {},
            )
        }
    }

    private fun historyPlaybackHandle() =
        buildPagedVideoCardPlaybackHandle(
            source = "MyHistory",
            cardsProvider = adapter::videoSnapshot,
            nextCursorProvider = { paging.snapshot().nextKey },
            hasMoreProvider = { !paging.snapshot().endReached },
            playlistItemFactory = ::historyVideoCardPlaylistItem,
        ) { cursor ->
            val page =
                BiliApi.historyCursor(
                    max = cursor?.max ?: 0,
                    business = cursor?.business,
                    viewAt = cursor?.viewAt ?: 0,
                    ps = 24,
                )
            val nextCursor = page.cursor
            VideoCardPlaylistPage(
                cards = page.items.videoCards(),
                nextCursor = nextCursor,
                hasMore = nextCursor != null,
                canAdvance = nextCursor != null && nextCursor != cursor && page.items.isNotEmpty(),
            )
        }

    private fun List<HistoryEntry>.videoCards(): List<VideoCard> = mapNotNull { (it as? HistoryEntry.Video)?.card }
}
