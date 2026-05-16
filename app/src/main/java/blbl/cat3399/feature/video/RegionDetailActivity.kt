package blbl.cat3399.feature.video

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.tv.RemoteKeys
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.GridViewportFillMonitor
import blbl.cat3399.core.ui.GridSpanPolicy
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.ui.installGridViewportFillMonitor
import blbl.cat3399.core.ui.requestFocusFirstItemOrSelfAfterRefresh
import blbl.cat3399.databinding.ActivityRegionDetailBinding
import blbl.cat3399.feature.following.UpDetailActivity
import blbl.cat3399.feature.player.VideoCardPlaylistPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RegionDetailActivity : BaseActivity() {
    private lateinit var binding: ActivityRegionDetailBinding

    private lateinit var adapter: VideoCardAdapter

    private val rid: Int by lazy { intent.getIntExtra(EXTRA_RID, -1).takeIf { it > 0 } ?: -1 }
    private val title: String by lazy { intent.getStringExtra(EXTRA_TITLE).orEmpty().trim() }

    private val loadedStableKeys = HashSet<String>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var page: Int = 1
    private var requestToken: Int = 0
    private var pendingFocusFirstItem: Boolean = false

    private var dpadGridController: DpadGridController? = null
    private var viewportFillMonitor: GridViewportFillMonitor? = null
    private var upFetchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegionDetailBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        if (rid <= 0) {
            AppToast.show(this, "缺少 rid")
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.text = title.ifBlank { "分区" }

        if (!this::adapter.isInitialized) {
            val actionController =
                VideoCardActionController(
                    context = this,
                    scope = lifecycleScope,
                    dismissBehavior = VideoCardDismissBehavior.LocalNotInterested,
                    onOpenDetail = { _, pos -> openDetail(pos) },
                    onOpenUp = { card -> openUpDetailFromVideoCard(card) },
                    onCardRemoved = { stableKey ->
                        binding.recycler.removeVideoCardAndRestoreFocus(
                            adapter = adapter,
                            stableKey = stableKey,
                            isAlive = { !isFinishing && !isDestroyed },
                        )
                    },
                )
            adapter =
                VideoCardAdapter(
                    onClick = { _, pos ->
                        openVideoFromPlaybackHandle(
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
        }

        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(this, spanCountForWidth())
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
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
                        isEnabled = { !isFinishing && !isDestroyed },
                        enableCenterLongPressToLongClick = true,
                    ),
            ).also { it.install() }
        viewportFillMonitor?.release()
        viewportFillMonitor =
            binding.recycler.installGridViewportFillMonitor(
                isEnabled = { !isFinishing && !isDestroyed },
                canLoadMore = { !isLoadingMore && !endReached },
                loadMore = { loadNextPage() },
            )

        binding.swipeRefresh.setOnRefreshListener {
            pendingFocusFirstItem = true
            dpadGridController?.parkFocusForDataSetReset()
            resetAndLoad()
        }

        if (savedInstanceState == null) {
            pendingFocusFirstItem = true
            binding.recycler.requestFocus()
            binding.swipeRefresh.isRefreshing = true
            resetAndLoad()
        }
    }

    override fun onResume() {
        super.onResume()
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth()
        viewportFillMonitor?.scheduleCheck()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && RemoteKeys.isRefreshKey(event.keyCode)) {
            if (binding.swipeRefresh.isRefreshing) return true
            pendingFocusFirstItem = true
            dpadGridController?.parkFocusForDataSetReset()
            binding.swipeRefresh.isRefreshing = true
            resetAndLoad()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        dpadGridController?.release()
        dpadGridController = null
        viewportFillMonitor?.release()
        viewportFillMonitor = null
        super.onDestroy()
    }

    private fun resetAndLoad() {
        pendingFocusFirstItem = true
        dpadGridController?.parkFocusForDataSetReset()
        loadedStableKeys.clear()
        isLoadingMore = false
        endReached = false
        page = 1
        requestToken++
        dpadGridController?.clearPendingFocusAfterLoadMore()
        adapter.submit(emptyList())
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || endReached) return
        val token = requestToken
        isLoadingMore = true
        val startAt = SystemClock.uptimeMillis()
        AppLog.d("RegionDetail", "load start rid=$rid page=$page refresh=$isRefresh t=$startAt")

        lifecycleScope.launch {
            try {
                var targetPage = page
                var loadedPage: Pair<List<VideoCard>, Boolean>? = null
                while (loadedPage == null) {
                    val res = BiliApi.regionLatestPage(rid = rid, pn = targetPage, ps = 24)
                    if (token != requestToken) return@launch
                    val hasMore = res.hasMore
                    val visibleItems = VideoCardVisibilityFilter.filterVisibleFresh(res.items, loadedStableKeys)
                    targetPage++
                    if (visibleItems.isNotEmpty() || !hasMore || res.items.isEmpty()) {
                        loadedPage = visibleItems to hasMore
                    }
                }
                val (visibleItems, hasMore) = checkNotNull(loadedPage)
                if (token != requestToken) return@launch
                visibleItems.forEach { loadedStableKeys.add(it.stableKey()) }
                if (isRefresh) adapter.submit(visibleItems) else adapter.append(visibleItems)
                maybeFocusFirstItem()
                endReached = !hasMore
                page = targetPage
                AppLog.i(
                    "RegionDetail",
                    "load ok rid=$rid add=${visibleItems.size} total=${adapter.itemCount} cost=${SystemClock.uptimeMillis() - startAt}ms",
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("RegionDetail", "load failed rid=$rid page=$page", t)
                AppToast.show(this@RegionDetailActivity, "加载失败，可查看 Logcat(标签 BLBL)")
            } finally {
                if (token == requestToken) binding.swipeRefresh.isRefreshing = false
                isLoadingMore = false
                viewportFillMonitor?.scheduleCheck()
            }
        }
    }

    private fun maybeFocusFirstItem() {
        if (!pendingFocusFirstItem) return
        val recycler = binding.recycler
        val isUiAlive = { !isFinishing && !isDestroyed }
        recycler.requestFocusFirstItemOrSelfAfterRefresh(
            itemCount = adapter.itemCount,
            smoothScroll = false,
            isAlive = isUiAlive,
            onDone = { pendingFocusFirstItem = false },
        )
    }

    private fun openUpDetailFromVideoCard(card: VideoCard) {
        val mid = card.ownerMid?.takeIf { it > 0L }
        if (mid != null) {
            startUpDetail(mid = mid, card = card)
            return
        }

        val safeAid = card.aid?.takeIf { it > 0L }
        if (card.bvid.isBlank() && safeAid == null) {
            AppToast.show(this, "未获取到 UP 主信息")
            return
        }
        if (upFetchJob?.isActive == true) return
        val requestBvid = card.bvid

        upFetchJob =
            lifecycleScope.launch {
                try {
                    val detail = if (requestBvid.isNotBlank()) BiliApi.videoDetail(requestBvid) else BiliApi.videoDetail(safeAid ?: 0L)
                    val viewMid = detail.owner?.mid ?: 0L
                    if (viewMid <= 0L) {
                        AppToast.show(this@RegionDetailActivity, "未获取到 UP 主信息")
                        return@launch
                    }
                    startUpDetail(mid = viewMid, card = card)
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                    AppToast.show(this@RegionDetailActivity, "未获取到 UP 主信息")
                } finally {
                    upFetchJob = null
                }
            }
    }

    private fun startUpDetail(mid: Long, card: VideoCard) {
        startActivity(
            Intent(this, UpDetailActivity::class.java)
                .putExtra(UpDetailActivity.EXTRA_MID, mid)
                .apply {
                    card.ownerName.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_NAME, it) }
                    card.ownerFace?.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_AVATAR, it) }
                },
        )
    }

    private fun spanCountForWidth(): Int {
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return GridSpanPolicy.fixedSpanCountForWidthDp(
            widthDp = widthDp,
            overrideSpanCount = BiliClient.prefs.gridSpanCount,
        )
    }

    private fun openDetail(position: Int) {
        openVideoDetailFromPlaybackHandle(playbackHandle(), position)
    }

    private fun playbackHandle() =
        buildPagedVideoCardPlaybackHandle(
            source = "RegionDetail:$rid",
            cardsProvider = adapter::snapshot,
            nextCursorProvider = { page },
            hasMoreProvider = { !endReached },
        ) { targetPage ->
            val pageNum = targetPage.coerceAtLeast(1)
            val res = BiliApi.regionLatestPage(rid = rid, pn = pageNum, ps = 24)
            VideoCardPlaylistPage(
                cards = res.items,
                nextCursor = pageNum + 1,
                hasMore = res.hasMore,
                canAdvance = res.hasMore && res.items.isNotEmpty(),
            )
        }

    companion object {
        const val EXTRA_RID: String = "rid"
        const val EXTRA_TITLE: String = "title"
    }
}
