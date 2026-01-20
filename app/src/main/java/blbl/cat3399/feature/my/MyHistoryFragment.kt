package blbl.cat3399.feature.my

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.tv.TvMode
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import blbl.cat3399.feature.video.VideoCardAdapter
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.launch

class MyHistoryFragment : Fragment(), MyTabSwitchFocusTarget, RefreshKeyHandler {
    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoCardAdapter

    private val loadedBvids = HashSet<String>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var requestToken: Int = 0
    private var initialLoadTriggered: Boolean = false

    private var cursor: BiliApi.HistoryCursor? = null
    private var pendingFocusFirstItemFromTabSwitch: Boolean = false
    private var pendingFocusNextCardAfterLoadMoreFromDpad: Boolean = false
    private var pendingFocusNextCardAfterLoadMoreFromPos: Int = RecyclerView.NO_POSITION

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        AppLog.d("MyHistory", "onCreateView t=${SystemClock.uptimeMillis()}")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter =
                VideoCardAdapter { card, pos ->
                    val playlistItems =
                        adapter.snapshot().map {
                            PlayerPlaylistItem(
                                bvid = it.bvid,
                                cid = it.cid,
                                title = it.title,
                            )
                        }
                    val token = PlayerPlaylistStore.put(items = playlistItems, index = pos, source = "MyHistory")
                    startActivity(
                        Intent(requireContext(), PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_BVID, card.bvid)
                            .putExtra(PlayerActivity.EXTRA_CID, card.cid ?: -1L)
                            .putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, token)
                            .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, pos),
                    )
                }
        }
        adapter.setTvMode(TvMode.isEnabled(requireContext()))
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth(resources))
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
                                        focusSelectedMyTabIfAvailable()
                                        return@setOnKeyListener true
                                    }
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                val itemView = binding.recycler.findContainingItemView(v) ?: return@setOnKeyListener false
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_LEFT)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    val switched = switchToPrevMyTabFromContentEdge()
                                    return@setOnKeyListener switched
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val itemView = binding.recycler.findContainingItemView(v) ?: return@setOnKeyListener false
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_RIGHT)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    if (switchToNextMyTabFromContentEdge()) return@setOnKeyListener true
                                    return@setOnKeyListener true
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val itemView = binding.recycler.findContainingItemView(v) ?: return@setOnKeyListener false
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
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth(resources)
        maybeTriggerInitialLoad()
        maybeConsumePendingFocusFirstItemFromTabSwitch()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
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
        if (focused != null && focused != binding.recycler && isDescendantOf(focused, binding.recycler)) {
            pendingFocusFirstItemFromTabSwitch = false
            return false
        }

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
                pendingFocusFirstItemFromTabSwitch = false
                return@outerPost
            }
            recycler.scrollToPosition(0)
            recycler.post innerPost@{
                if (_binding == null) return@innerPost
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
        loadedBvids.clear()
        isLoadingMore = false
        endReached = false
        cursor = null
        requestToken++
        adapter.submit(emptyList())
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || endReached) return
        val token = requestToken
        isLoadingMore = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val c = cursor
                val page =
                    BiliApi.historyCursor(
                        max = c?.max ?: 0,
                        business = c?.business,
                        viewAt = c?.viewAt ?: 0,
                        ps = 24,
                    )
                if (token != requestToken) return@launch

                cursor = page.cursor
                if (page.items.isEmpty()) {
                    endReached = true
                    return@launch
                }

                val filtered = page.items.filter { loadedBvids.add(it.bvid) }
                if (isRefresh) adapter.submit(filtered) else adapter.append(filtered)
                _binding?.recycler?.post {
                    maybeConsumePendingFocusFirstItemFromTabSwitch()
                    maybeConsumePendingFocusNextCardAfterLoadMoreFromDpad()
                }

                if (filtered.isEmpty()) endReached = true
            } catch (t: Throwable) {
                AppLog.e("MyHistory", "load failed", t)
                context?.let { Toast.makeText(it, "加载失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show() }
            } finally {
                if (token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private fun clearPendingFocusNextCardAfterLoadMoreFromDpad() {
        pendingFocusNextCardAfterLoadMoreFromDpad = false
        pendingFocusNextCardAfterLoadMoreFromPos = RecyclerView.NO_POSITION
    }

    private fun maybeConsumePendingFocusNextCardAfterLoadMoreFromDpad(): Boolean {
        if (!pendingFocusNextCardAfterLoadMoreFromDpad) return false
        if (_binding == null || !isResumed || !this::adapter.isInitialized) {
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

    override fun onDestroyView() {
        initialLoadTriggered = false
        clearPendingFocusNextCardAfterLoadMoreFromDpad()
        _binding = null
        super.onDestroyView()
    }
}
