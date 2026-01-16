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
import blbl.cat3399.databinding.FragmentDynamicBinding
import blbl.cat3399.databinding.FragmentDynamicLoginBinding
import blbl.cat3399.feature.login.QrLoginActivity
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.video.VideoCardAdapter
import kotlinx.coroutines.launch

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

        videoAdapter = VideoCardAdapter { card ->
            startActivity(
                Intent(requireContext(), PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_BVID, card.bvid)
                    .putExtra(PlayerActivity.EXTRA_CID, -1L),
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

    override fun onDestroyView() {
        _bindingLogin = null
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
        val width =
            resources.getDimensionPixelSize(
                if (tvMode) blbl.cat3399.R.dimen.dynamic_following_panel_width_tv else blbl.cat3399.R.dimen.dynamic_following_panel_width,
            )
        val margin =
            resources.getDimensionPixelSize(
                if (tvMode) blbl.cat3399.R.dimen.dynamic_following_panel_margin_tv else blbl.cat3399.R.dimen.dynamic_following_panel_margin,
            )
        val padding =
            resources.getDimensionPixelSize(
                if (tvMode) blbl.cat3399.R.dimen.dynamic_following_list_padding_tv else blbl.cat3399.R.dimen.dynamic_following_list_padding,
            )

        val cardLp = binding.cardFollowing.layoutParams
        var changed = false
        if (cardLp.width != width) {
            cardLp.width = width
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
