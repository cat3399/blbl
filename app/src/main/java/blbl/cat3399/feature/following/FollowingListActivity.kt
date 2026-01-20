package blbl.cat3399.feature.following

import android.content.Intent
import android.os.Bundle
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.tv.RemoteKeys
import blbl.cat3399.core.tv.TvMode
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.databinding.ActivityFollowingListBinding
import blbl.cat3399.feature.login.QrLoginActivity
import kotlinx.coroutines.launch

class FollowingListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFollowingListBinding
    private lateinit var adapter: FollowingGridAdapter

    private var vmid: Long = 0L
    private var forceLoginUi: Boolean = false
    private var page: Int = 1
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var total: Int = 0
    private val loadedMids = HashSet<Long>()

    private var pendingFocusNextAfterLoadMoreFromDpad: Boolean = false
    private var pendingFocusNextAfterLoadMoreFromPos: Int = RecyclerView.NO_POSITION

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFollowingListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

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
        adapter.setTvMode(TvMode.isEnabled(this))

        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(this, spanCountForWidth())
        binding.recycler.adapter = adapter
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        binding.recycler.clearOnScrollListeners()
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (isLoadingMore || endReached) return
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val totalCount = adapter.itemCount
                    if (totalCount <= 0) return
                    if (totalCount - lastVisible - 1 <= 12) loadNextPage()
                }
            },
        )
        binding.recycler.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (!binding.recycler.canScrollVertically(-1)) {
                                    val lm = binding.recycler.layoutManager as? GridLayoutManager ?: return@setOnKeyListener false
                                    val holder = binding.recycler.findContainingViewHolder(v) ?: return@setOnKeyListener false
                                    val pos = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnKeyListener false
                                    if (pos < lm.spanCount) {
                                        binding.btnBack.requestFocus()
                                        return@setOnKeyListener true
                                    }
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val itemView = binding.recycler.findContainingItemView(v) ?: return@setOnKeyListener false
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_DOWN)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    if (binding.recycler.canScrollVertically(1)) {
                                        val dy = (itemView.height * 0.8f).toInt().coerceAtLeast(1)
                                        binding.recycler.scrollBy(0, dy)
                                        binding.recycler.post {
                                            if (!isFinishing && !isDestroyed) tryFocusNextDownFromCurrent()
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
                                            pendingFocusNextAfterLoadMoreFromDpad = true
                                            pendingFocusNextAfterLoadMoreFromPos = pos
                                        }
                                        loadNextPage()
                                        return@setOnKeyListener true
                                    }
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
        adapter.setTvMode(TvMode.isEnabled(this))
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
                Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show()
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

    private fun tryFocusNextDownFromCurrent() {
        val focused = currentFocus ?: return
        val itemView = binding.recycler.findContainingItemView(focused) ?: return
        val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_DOWN)
        if (next != null && isDescendantOf(next, binding.recycler)) next.requestFocus()
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current == ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun resetAndLoad() {
        loadedMids.clear()
        page = 1
        total = 0
        endReached = false
        isLoadingMore = false
        pendingFocusNextAfterLoadMoreFromDpad = false
        pendingFocusNextAfterLoadMoreFromPos = RecyclerView.NO_POSITION
        adapter.submit(emptyList())
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || endReached) return
        val currentPage = page
        isLoadingMore = true
        lifecycleScope.launch {
            try {
                val mid = ensureUserMid() ?: return@launch
                val res = BiliApi.followingsPage(vmid = mid, pn = currentPage, ps = 50)
                total = res.total
                if (res.items.isEmpty()) {
                    endReached = true
                    if (isRefresh) Toast.makeText(this@FollowingListActivity, "暂无关注", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val filtered = res.items.filter { loadedMids.add(it.mid) }
                if (currentPage == 1) adapter.submit(filtered) else adapter.append(filtered)
                page = currentPage + 1
                endReached = !res.hasMore
                binding.recycler.post { maybeConsumePendingFocusNextAfterLoadMoreFromDpad() }
            } catch (t: Throwable) {
                AppLog.e("FollowingList", "load failed page=$currentPage", t)
                Toast.makeText(this@FollowingListActivity, "加载失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show()
            } finally {
                binding.swipeRefresh.isRefreshing = false
                isLoadingMore = false
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
                Toast.makeText(this, "登录态失效，请重新登录", Toast.LENGTH_SHORT).show()
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

    private fun maybeConsumePendingFocusNextAfterLoadMoreFromDpad() {
        if (!pendingFocusNextAfterLoadMoreFromDpad) return
        val pos = pendingFocusNextAfterLoadMoreFromPos
        pendingFocusNextAfterLoadMoreFromDpad = false
        pendingFocusNextAfterLoadMoreFromPos = RecyclerView.NO_POSITION

        val lm = binding.recycler.layoutManager as? GridLayoutManager ?: return
        if (pos == RecyclerView.NO_POSITION) return
        val nextPos = pos + lm.spanCount
        if (nextPos < 0 || nextPos >= adapter.itemCount) return
        focusGridAt(nextPos)
    }

    private fun spanCountForWidth(): Int {
        return followingSpanCountForWidth(resources, tvMode = TvMode.isEnabled(this))
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
}
