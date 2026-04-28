package blbl.cat3399.feature.following

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Following
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.paging.PagedGridStateMachine
import blbl.cat3399.core.paging.appliedOrNull
import blbl.cat3399.core.tv.RemoteKeys
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.ScreenCornerInsets
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ActivityFollowingListBinding
import blbl.cat3399.feature.login.QrLoginActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class FollowingListActivity : BaseActivity() {
    private lateinit var binding: ActivityFollowingListBinding
    private lateinit var adapter: FollowingGridAdapter

    private var vmid: Long = 0L
    private var forceLoginUi: Boolean = false
    private var total: Int = 0
    private val loadedMids = HashSet<Long>()
    private val paging = PagedGridStateMachine(initialKey = 1)
    private var currentFollowingListOrder: String = BiliClient.prefs.followingListOrder

    private var dpadGridController: DpadGridController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFollowingListBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        applyDisplayCutoutInsets()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnLogin.setOnClickListener { startActivity(Intent(this, QrLoginActivity::class.java)) }

        adapter =
            FollowingGridAdapter { following ->
                startActivity(
                    Intent(this, UpDetailActivity::class.java)
                        .putExtra(UpDetailActivity.EXTRA_MID, following.mid)
                        .putExtra(UpDetailActivity.EXTRA_NAME, following.name)
                        .putExtra(UpDetailActivity.EXTRA_AVATAR, following.avatarUrl)
                        .putExtra(UpDetailActivity.EXTRA_SIGN, following.sign),
                )
            }

        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(this, spanCountForWidth())
        binding.recycler.adapter = adapter
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
                    val totalCount = adapter.itemCount
                    if (totalCount <= 0) return
                    if (totalCount - lastVisible - 1 <= 12) loadNextPage()
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
                            // Allow default focus-search (e.g. to back button if the system picks it).
                            return false
                        }

                        override fun onRightEdge() = Unit

                        override fun canLoadMore(): Boolean = !paging.snapshot().endReached

                        override fun loadMore() {
                            loadNextPage()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { !isFinishing && !isDestroyed },
                    ),
            ).also { it.install() }

        binding.swipeRefresh.setOnRefreshListener { resetAndLoad() }

        refreshLoginUi()
        if (binding.loginContainer.isVisible) {
            binding.btnLogin.requestFocus()
        } else {
            binding.swipeRefresh.isRefreshing = true
            resetAndLoad()
        }
    }

    override fun onResume() {
        super.onResume()
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        adapter.invalidateSizing()
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth()
        // If user just came back from login, allow re-checking.
        forceLoginUi = false
        refreshLoginUi()
        if (!binding.loginContainer.isVisible && adapter.itemCount == 0 && !binding.swipeRefresh.isRefreshing) {
            binding.swipeRefresh.isRefreshing = true
            resetAndLoad()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && RemoteKeys.isRefreshKey(event.keyCode)) {
            if (binding.loginContainer.isVisible) {
                AppToast.show(this, "请先登录")
                binding.btnLogin.requestFocus()
                return true
            }
            if (binding.swipeRefresh.isRefreshing) return true
            binding.swipeRefresh.isRefreshing = true
            resetAndLoad()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null && isNavKey(event.keyCode)) {
            ensureInitialFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun refreshLoginUi() {
        val loggedIn = !forceLoginUi && BiliClient.cookies.hasSessData()
        binding.loginContainer.isVisible = !loggedIn
        binding.swipeRefresh.isVisible = loggedIn
        if (!loggedIn) {
            binding.swipeRefresh.isRefreshing = false
            vmid = 0L
        }
    }

    private fun ensureInitialFocus() {
        if (currentFocus != null) return
        if (binding.loginContainer.isVisible) {
            binding.btnLogin.requestFocus()
            return
        }
        if (adapter.itemCount <= 0) {
            binding.recycler.requestFocus()
            return
        }
        focusGridAt(0)
    }

    private fun focusGridAt(position: Int) {
        val recycler = binding.recycler
        recycler.post outer@{
            if (isFinishing || isDestroyed) return@outer
            val vh = recycler.findViewHolderForAdapterPosition(position)
            if (vh != null) {
                vh.itemView.requestFocus()
                return@outer
            }
            recycler.scrollToPosition(position)
            recycler.post inner@{
                if (isFinishing || isDestroyed) return@inner
                recycler.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() ?: recycler.requestFocus()
            }
        }
    }

    private fun resetAndLoad() {
        currentFollowingListOrder = BiliClient.prefs.followingListOrder
        paging.reset()
        loadedMids.clear()
        total = 0
        dpadGridController?.clearPendingFocusAfterLoadMore()
        adapter.submit(emptyList())
        loadNextPage(isRefresh = true)
    }

    private data class FetchedPage(
        val items: List<Following>,
        val total: Int,
        val hasMore: Boolean,
    )

    private fun loadNextPage(isRefresh: Boolean = false) {
        val startSnap = paging.snapshot()
        if (startSnap.isLoading || startSnap.endReached) return
        val startGen = startSnap.generation
        val startPage = startSnap.nextKey
        lifecycleScope.launch {
            try {
                var latestFetched: FetchedPage? = null
                val result =
                    paging.loadNextPage(
                        isRefresh = isRefresh,
                        fetch = { page ->
                            val mid = ensureUserMid()
                            if (mid == null) {
                                null
                            } else {
                                val res = BiliApi.followingsPage(vmid = mid, pn = page, ps = 50, order = currentFollowingListOrder)
                                FetchedPage(items = res.items, total = res.total, hasMore = res.hasMore).also { latestFetched = it }
                            }
                        },
                        reduce = { page, fetched ->
                            if (fetched.items.isEmpty()) {
                                PagedGridStateMachine.Update(
                                    items = emptyList<Following>(),
                                    nextKey = page,
                                    endReached = true,
                                )
                            } else {
                                val seen = HashSet<Long>(fetched.items.size)
                                val filtered =
                                    fetched.items.filter {
                                        if (loadedMids.contains(it.mid)) return@filter false
                                        seen.add(it.mid)
                                    }
                                PagedGridStateMachine.Update(
                                    items = filtered,
                                    nextKey = page + 1,
                                    endReached = !fetched.hasMore,
                                )
                            }
                        },
                    )

                val applied = result.appliedOrNull() ?: return@launch
                val fetched = latestFetched ?: return@launch
                total = fetched.total
                if (fetched.items.isEmpty()) {
                    if (applied.isRefresh) AppToast.show(this@FollowingListActivity, "暂无关注")
                    return@launch
                }
                applied.items.forEach { loadedMids.add(it.mid) }
                if (applied.isRefresh) adapter.submit(applied.items) else adapter.append(applied.items)
                binding.recycler.post { dpadGridController?.consumePendingFocusAfterLoadMore() }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("FollowingList", "load failed page=$startPage", t)
                AppToast.show(this@FollowingListActivity, "加载失败，可查看 Logcat(标签 BLBL)")
            } finally {
                if (paging.snapshot().generation == startGen) binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private suspend fun ensureUserMid(): Long? {
        if (vmid > 0) return vmid
        return runCatching {
            val nav = BiliApi.nav()
            val data = nav.optJSONObject("data")
            val isLogin = data?.optBoolean("isLogin") ?: false
            val mid = data?.optLong("mid") ?: 0L
            if (!isLogin || mid <= 0) {
                forceLoginUi = true
                refreshLoginUi()
                AppToast.show(this, "登录态失效，请重新登录")
                null
            } else {
                vmid = mid
                mid
            }
        }.getOrElse {
            AppLog.w("FollowingList", "nav failed", it)
            null
        }
    }

    private fun spanCountForWidth(): Int {
        return followingSpanCountForWidth(binding.root.context)
    }

    override fun onDestroy() {
        dpadGridController?.release()
        dpadGridController = null
        super.onDestroy()
    }

    private fun isNavKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            -> true

            else -> false
        }
    }

    private fun applyDisplayCutoutInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val cutoutInsets = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout())
            val btnSize = resources.getDimensionPixelSize(blbl.cat3399.R.dimen.sidebar_settings_size)
            val btnMarginStart = (12 * resources.displayMetrics.density).toInt()
            val btnMarginTop = (10 * resources.displayMetrics.density).toInt()
            val btnRadius = btnSize / 2f
            val btnCenterX = btnMarginStart + btnRadius
            val btnCenterY = btnMarginTop + btnRadius

            val leftPadding = cutoutInsets.left
                .coerceAtLeast(ScreenCornerInsets.safeLeftInsetForCircle(insets, btnRadius, btnCenterX, btnCenterY))
            val rightPadding = cutoutInsets.right
                .coerceAtLeast(ScreenCornerInsets.safeRightInset(insets))

            if (view.paddingLeft != leftPadding || view.paddingRight != rightPadding) {
                view.setPadding(leftPadding, 0, rightPadding, 0)
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }
}
