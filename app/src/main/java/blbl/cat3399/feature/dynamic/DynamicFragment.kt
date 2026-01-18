package blbl.cat3399.feature.dynamic

import android.content.Intent
import android.os.Bundle
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.tv.TvMode
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.databinding.FragmentDynamicBinding
import blbl.cat3399.databinding.FragmentDynamicLoginBinding
import blbl.cat3399.feature.login.QrLoginActivity
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import blbl.cat3399.feature.video.VideoCardAdapter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class DynamicFragment : Fragment() {
    private var _bindingLogin: FragmentDynamicLoginBinding? = null
    private var _binding: FragmentDynamicBinding? = null

    private lateinit var followAdapter: FollowingAdapter
    private lateinit var videoAdapter: VideoCardAdapter

    private val loggedIn: Boolean
        get() = BiliClient.cookies.hasSessData()

    private val loadedBvids = HashSet<String>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var nextOffset: String? = null
    private var requestToken: Int = 0
    private var pendingFocusNextCardAfterLoadMoreFromDpad: Boolean = false
    private var pendingFocusNextCardAfterLoadMoreFromPos: Int = RecyclerView.NO_POSITION

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (!loggedIn) {
            _bindingLogin = FragmentDynamicLoginBinding.inflate(inflater, container, false)
            return _bindingLogin!!.root
        }
        _binding = FragmentDynamicBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!loggedIn) {
            _bindingLogin?.btnLogin?.setOnClickListener {
                startActivity(Intent(requireContext(), QrLoginActivity::class.java))
            }
            return
        }

        val binding = _binding ?: return

        followAdapter = FollowingAdapter(::onFollowingClicked)
        binding.recyclerFollowing.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerFollowing.adapter = followAdapter
        followAdapter.setTvMode(TvMode.isEnabled(requireContext()))
        applyUiMode()

        videoAdapter = VideoCardAdapter { card, pos ->
            val playlistItems =
                videoAdapter.snapshot().map {
                    PlayerPlaylistItem(
                        bvid = it.bvid,
                        cid = it.cid,
                        title = it.title,
                    )
                }
            val token = PlayerPlaylistStore.put(items = playlistItems, index = pos, source = "Dynamic")
            startActivity(
                Intent(requireContext(), PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_BVID, card.bvid)
                    .putExtra(PlayerActivity.EXTRA_CID, card.cid ?: -1L)
                    .putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, token)
                    .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, pos),
            )
        }
        videoAdapter.setTvMode(TvMode.isEnabled(requireContext()))
        binding.recyclerDynamic.setHasFixedSize(true)
        binding.recyclerDynamic.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth())
        binding.recyclerDynamic.adapter = videoAdapter
        (binding.recyclerDynamic.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recyclerDynamic.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val itemView = binding.recyclerDynamic.findContainingItemView(v) ?: return@setOnKeyListener false
                                val next = FocusFinder.getInstance().findNextFocus(binding.recyclerDynamic, itemView, View.FOCUS_DOWN)
                                if (next == null || !isDescendantOf(next, binding.recyclerDynamic)) {
                                    if (binding.recyclerDynamic.canScrollVertically(1)) {
                                        // Focus-search failed but the list can still scroll; scroll a bit to let
                                        // RecyclerView lay out the next row, and keep focus inside the list.
                                        val dy = (itemView.height * 0.8f).toInt().coerceAtLeast(1)
                                        binding.recyclerDynamic.scrollBy(0, dy)
                                        binding.recyclerDynamic.post {
                                            if (_binding == null) return@post
                                            tryFocusNextDownFromCurrent()
                                        }
                                        return@setOnKeyListener true
                                    }
                                    val holder = binding.recyclerDynamic.findContainingViewHolder(v)
                                    val pos =
                                        holder?.bindingAdapterPosition
                                            ?.takeIf { it != RecyclerView.NO_POSITION }
                                            ?: RecyclerView.NO_POSITION
                                    if (pos != RecyclerView.NO_POSITION) {
                                        pendingFocusNextCardAfterLoadMoreFromDpad = true
                                        pendingFocusNextCardAfterLoadMoreFromPos = pos
                                    }
                                    loadMoreFeed()
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
        binding.recyclerDynamic.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (isLoadingMore || endReached) return
                    if (binding.swipeRefresh.isRefreshing) return

                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = videoAdapter.itemCount
                    if (total <= 0) return

                    if (total - lastVisible - 1 <= 8) {
                        loadMoreFeed()
                    }
                }
            },
        )

        binding.swipeRefresh.setOnRefreshListener { loadAll(resetFeed = true) }
        binding.swipeRefresh.isRefreshing = true
        loadAll(resetFeed = true)
    }

    private var followItems: List<FollowingAdapter.FollowingUi> = emptyList()
    private var selectedMid: Long = FollowingAdapter.MID_ALL

    private fun onFollowingClicked(following: FollowingAdapter.FollowingUi) {
        selectedMid = following.mid
        followAdapter.submit(followItems, selected = selectedMid)
        resetAndLoadFeed()
    }

    private fun loadAll(resetFeed: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val nav = BiliApi.nav()
                val data = nav.optJSONObject("data")
                val mid = data?.optLong("mid") ?: 0L
                val isLogin = data?.optBoolean("isLogin") ?: false
                AppLog.i("Dynamic", "nav isLogin=$isLogin mid=$mid")
                if (!isLogin || mid <= 0) {
                    Toast.makeText(requireContext(), "登录态失效，请重新登录", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val followings = BiliApi.followings(vmid = mid, pn = 1, ps = 30)
                followItems = buildList {
                    add(FollowingAdapter.FollowingUi(FollowingAdapter.MID_ALL, "所有", null, isAll = true))
                    followings.forEach { f -> add(FollowingAdapter.FollowingUi(f.mid, f.name, f.avatarUrl)) }
                }
                if (selectedMid == 0L) selectedMid = FollowingAdapter.MID_ALL
                followAdapter.submit(followItems, selected = selectedMid)
                if (resetFeed) resetAndLoadFeed() else loadMoreFeed()
            } catch (t: Throwable) {
                AppLog.e("Dynamic", "load failed", t)
                _binding?.swipeRefresh?.isRefreshing = false
                Toast.makeText(requireContext(), "加载失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show()
            } finally {
                if (!resetFeed) _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    private fun resetAndLoadFeed() {
        clearPendingFocusNextCardAfterLoadMoreFromDpad()
        loadedBvids.clear()
        nextOffset = null
        endReached = false
        isLoadingMore = false
        requestToken++
        videoAdapter.submit(emptyList())
        _binding?.swipeRefresh?.isRefreshing = true
        loadMoreFeed()
    }

    private fun loadMoreFeed() {
        if (isLoadingMore || endReached) return
        val token = requestToken
        isLoadingMore = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = if (selectedMid == FollowingAdapter.MID_ALL) {
                    BiliApi.dynamicAllVideo(offset = nextOffset)
                } else {
                    BiliApi.dynamicSpaceVideo(hostMid = selectedMid, offset = nextOffset)
                }
                if (token != requestToken) return@launch

                nextOffset = page.nextOffset
                if (page.items.isEmpty() || nextOffset == null) endReached = true

                val filtered = page.items.filter { loadedBvids.add(it.bvid) }
                videoAdapter.append(filtered)
                _binding?.recyclerDynamic?.post { maybeConsumePendingFocusNextCardAfterLoadMoreFromDpad() }
            } catch (t: Throwable) {
                AppLog.e("Dynamic", "load feed failed mid=$selectedMid", t)
                Toast.makeText(requireContext(), "加载失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show()
            } finally {
                if (token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private fun spanCountForWidth(): Int {
        val prefs = blbl.cat3399.core.net.BiliClient.prefs
        val dyn = prefs.dynamicGridSpanCount
        if (dyn > 0) return dyn.coerceIn(1, 6)
        val override = prefs.gridSpanCount
        if (override > 0) return override.coerceIn(1, 6)
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return when {
            widthDp >= 1100 -> 4
            widthDp >= 800 -> 3
            else -> 2
        }
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
        val binding = _binding
        if (binding == null || !isResumed || !this::videoAdapter.isInitialized) {
            clearPendingFocusNextCardAfterLoadMoreFromDpad()
            return false
        }

        val recycler = binding.recyclerDynamic
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
        val itemCount = videoAdapter.itemCount
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
        val binding = _binding ?: return
        if (!isResumed) return
        val recycler = binding.recyclerDynamic
        val focused = activity?.currentFocus ?: return
        if (!isDescendantOf(focused, recycler)) return
        val itemView = recycler.findContainingItemView(focused) ?: return
        val next = FocusFinder.getInstance().findNextFocus(recycler, itemView, View.FOCUS_DOWN)
        if (next != null && isDescendantOf(next, recycler)) {
            next.requestFocus()
        }
    }

    override fun onDestroyView() {
        _bindingLogin = null
        clearPendingFocusNextCardAfterLoadMoreFromDpad()
        _binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        applyUiMode()
        if (this::videoAdapter.isInitialized) videoAdapter.setTvMode(TvMode.isEnabled(requireContext()))
        if (this::followAdapter.isInitialized) followAdapter.setTvMode(TvMode.isEnabled(requireContext()))
        (_binding?.recyclerDynamic?.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth()
    }

    private fun applyUiMode() {
        val binding = _binding ?: return
        val tvMode = TvMode.isEnabled(requireContext())
        val uiScale = UiScale.factor(requireContext(), tvMode)

        fun px(id: Int): Int = resources.getDimensionPixelSize(id)
        fun scaledPx(id: Int): Int = (px(id) * uiScale).roundToInt().coerceAtLeast(0)

        val width =
            scaledPx(
                if (tvMode) blbl.cat3399.R.dimen.dynamic_following_panel_width_tv else blbl.cat3399.R.dimen.dynamic_following_panel_width,
            )
        val margin =
            scaledPx(
                if (tvMode) blbl.cat3399.R.dimen.dynamic_following_panel_margin_tv else blbl.cat3399.R.dimen.dynamic_following_panel_margin,
            )
        val padding =
            scaledPx(
                if (tvMode) blbl.cat3399.R.dimen.dynamic_following_list_padding_tv else blbl.cat3399.R.dimen.dynamic_following_list_padding,
            )

        val cardLp = binding.cardFollowing.layoutParams
        var changed = false
        if (cardLp.width != width.coerceAtLeast(1)) {
            cardLp.width = width.coerceAtLeast(1)
            changed = true
        }
        val mlp = cardLp as? ViewGroup.MarginLayoutParams
        if (mlp != null && (mlp.leftMargin != margin || mlp.topMargin != margin || mlp.rightMargin != margin || mlp.bottomMargin != margin)) {
            mlp.setMargins(margin, margin, margin, margin)
            changed = true
        }
        if (changed) binding.cardFollowing.layoutParams = cardLp

        if (binding.recyclerFollowing.paddingLeft != padding || binding.recyclerFollowing.paddingTop != padding || binding.recyclerFollowing.paddingRight != padding || binding.recyclerFollowing.paddingBottom != padding) {
            binding.recyclerFollowing.setPadding(padding, padding, padding, padding)
        }
    }

    companion object {
        fun newInstance() = DynamicFragment()
    }
}
