package blbl.cat3399.feature.video

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.FocusFinder
import android.view.LayoutInflater
import android.view.KeyEvent
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
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.feature.player.PlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class VideoGridFragment : Fragment() {
    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoCardAdapter
    private var preDrawListener: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private var firstDrawLogged: Boolean = false
    private var initialLoadTriggered: Boolean = false

    private val source: String by lazy { requireArguments().getString(ARG_SOURCE) ?: SRC_POPULAR }
    private val rid: Int by lazy { requireArguments().getInt(ARG_RID, 0) }

    private val loadedBvids = HashSet<String>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false

    private var page: Int = 1
    private var recommendFetchRow: Int = 1

    private var requestToken: Int = 0

    private var pendingFocusFirstCardFromTab: Boolean = false
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        AppLog.d("VideoGrid", "onCreateView source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        AppLog.d("VideoGrid", "onViewCreated source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
        if (!::adapter.isInitialized) {
            adapter = VideoCardAdapter { card ->
                AppLog.i("VideoGrid", "click bvid=${card.bvid} cid=${card.cid}")
                startActivity(
                    Intent(requireContext(), PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_BVID, card.bvid)
                        .putExtra(PlayerActivity.EXTRA_CID, card.cid ?: -1L),
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

                    if (total - lastVisible - 1 <= 8) {
                        AppLog.d("VideoGrid", "near end source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
                        loadNextPage()
                    }
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
                                    val pos =
                                        holder.bindingAdapterPosition
                                            .takeIf { it != RecyclerView.NO_POSITION }
                                            ?: return@setOnKeyListener false
                                    if (pos < lm.spanCount) {
                                        focusSelectedTabIfAvailable()
                                        return@setOnKeyListener true
                                    }
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                val itemView = binding.recycler.findContainingItemView(v) ?: return@setOnKeyListener false
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_LEFT)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    val switched = switchToPrevTabFromContentEdge()
                                    return@setOnKeyListener switched
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val itemView = binding.recycler.findContainingItemView(v) ?: return@setOnKeyListener false
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_RIGHT)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    if (switchToNextTabFromContentEdge()) return@setOnKeyListener true
                                    return@setOnKeyListener true
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val itemView = binding.recycler.findContainingItemView(v) ?: return@setOnKeyListener false
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_DOWN)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
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

        binding.swipeRefresh.setOnRefreshListener { resetAndLoad() }

        if (preDrawListener == null) {
            preDrawListener =
                android.view.ViewTreeObserver.OnPreDrawListener {
                    if (!firstDrawLogged) {
                        firstDrawLogged = true
                        AppLog.d(
                            "VideoGrid",
                            "first preDraw source=$source rid=$rid t=${SystemClock.uptimeMillis()}",
                        )
                    }
                    true
                }
            binding.recycler.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.d("VideoGrid", "onResume source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
        if (this::adapter.isInitialized) adapter.setTvMode(TvMode.isEnabled(requireContext()))
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth()
        maybeTriggerInitialLoad()
        maybeConsumePendingFocusFirstCard()
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
        AppLog.d("VideoGrid", "resetAndLoad source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
        loadedBvids.clear()
        endReached = false
        isLoadingMore = false
        page = 1
        recommendFetchRow = 1
        requestToken++
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || endReached) return
        val token = requestToken
        isLoadingMore = true
        val startAt = SystemClock.uptimeMillis()
        AppLog.d(
            "VideoGrid",
            "loadNextPage start source=$source rid=$rid page=$page refresh=$isRefresh t=$startAt",
        )
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ps = 24
                val cards = when (source) {
                    SRC_RECOMMEND -> BiliApi.recommend(freshIdx = page, ps = ps, fetchRow = recommendFetchRow)
                    SRC_REGION -> BiliApi.regionLatest(rid = rid, pn = page, ps = ps)
                    else -> BiliApi.popular(pn = page, ps = ps)
                }

                if (token != requestToken) return@launch

                if (cards.isEmpty()) {
                    endReached = true
                    return@launch
                }

                val filtered = cards.filter { loadedBvids.add(it.bvid) }
                if (page == 1) {
                    adapter.submit(filtered)
                } else {
                    adapter.append(filtered)
                }
                _binding?.recycler?.post { maybeConsumePendingFocusFirstCard() }

                if (source == SRC_RECOMMEND) {
                    recommendFetchRow += cards.size
                }
                page++

                if (filtered.isEmpty()) {
                    endReached = true
                }

                AppLog.i(
                    "VideoGrid",
                    "load ok source=$source rid=$rid page=${page - 1} add=${filtered.size} total=${adapter.itemCount} cost=${SystemClock.uptimeMillis() - startAt}ms",
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("VideoGrid", "load failed source=$source rid=$rid page=$page", t)
                context?.let { Toast.makeText(it, "加载失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show() }
            } finally {
                if (isRefresh && token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
                isLoadingMore = false
                AppLog.d(
                    "VideoGrid",
                    "loadNextPage end source=$source rid=$rid page=$page refresh=$isRefresh t=${SystemClock.uptimeMillis()}",
                )
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

    fun requestFocusFirstCardFromTab(): Boolean {
        pendingFocusFirstCardFromTab = true
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    fun requestFocusFirstCardFromContentSwitch(): Boolean {
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
        recycler.post outerPost@{
            if (_binding == null) return@outerPost
            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                pendingFocusFirstCardFromTab = false
                pendingFocusFirstCardFromContentSwitch = false
                return@outerPost
            }
            recycler.scrollToPosition(0)
            recycler.post innerPost@{
                if (_binding == null) return@innerPost
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

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current == ancestor) return true
            current = current.parent as? View
        }
        return false
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
            (parentFragment as? VideoGridTabSwitchFocusHost)?.requestFocusCurrentPageFirstCardFromContentSwitch()
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
            (parentFragment as? VideoGridTabSwitchFocusHost)?.requestFocusCurrentPageFirstCardFromContentSwitch()
                ?: tabStrip.getChildAt(prev)?.requestFocus()
        }
        return true
    }

    override fun onDestroyView() {
        AppLog.d("VideoGrid", "onDestroyView source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
        preDrawListener?.let { listener ->
            if (binding.recycler.viewTreeObserver.isAlive) {
                binding.recycler.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        }
        preDrawListener = null
        firstDrawLogged = false
        initialLoadTriggered = false
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_SOURCE = "source"
        private const val ARG_RID = "rid"

        const val SRC_RECOMMEND = "recommend"
        const val SRC_POPULAR = "popular"
        const val SRC_REGION = "region"

        fun newRecommend() = VideoGridFragment().apply { arguments = Bundle().apply { putString(ARG_SOURCE, SRC_RECOMMEND) } }
        fun newPopular() = VideoGridFragment().apply { arguments = Bundle().apply { putString(ARG_SOURCE, SRC_POPULAR) } }
        fun newRegion(rid: Int) = VideoGridFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SOURCE, SRC_REGION)
                putInt(ARG_RID, rid)
            }
        }
    }
}
