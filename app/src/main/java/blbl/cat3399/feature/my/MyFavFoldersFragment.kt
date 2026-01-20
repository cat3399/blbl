package blbl.cat3399.feature.my

import android.os.Bundle
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.launch

class MyFavFoldersFragment : Fragment(), MyTabSwitchFocusTarget, RefreshKeyHandler {
    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: FavFolderAdapter
    private var initialLoadTriggered: Boolean = false
    private var requestToken: Int = 0
    private var pendingRestorePosition: Int? = null
    private var pendingFocusFirstItemFromTabSwitch: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter = FavFolderAdapter { position, folder ->
                pendingRestorePosition = position
                val nav = parentFragment?.parentFragment as? MyNavigator
                nav?.openFavFolder(folder.mediaId, folder.title)
            }
        }

        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = StaggeredGridLayoutManager(spanCountForWidth(resources), StaggeredGridLayoutManager.VERTICAL).apply {
            gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        }
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (!binding.recycler.canScrollVertically(-1)) {
                                    val lm = binding.recycler.layoutManager as? StaggeredGridLayoutManager ?: return@setOnKeyListener false
                                    val holder = binding.recycler.findContainingViewHolder(v) ?: return@setOnKeyListener false
                                    val pos = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnKeyListener false
                                    val first = IntArray(lm.spanCount)
                                    lm.findFirstVisibleItemPositions(first)
                                    if (first.any { it == pos }) {
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
                                        return@setOnKeyListener true
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
        binding.swipeRefresh.setOnRefreshListener { reload() }
    }

    override fun onResume() {
        super.onResume()
        (binding.recycler.layoutManager as? StaggeredGridLayoutManager)?.spanCount = spanCountForWidth(resources)
        maybeTriggerInitialLoad()
        restoreFocusIfNeeded()
        maybeConsumePendingFocusFirstItemFromTabSwitch()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        reload()
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
        if (!this::adapter.isInitialized) return
        if (adapter.itemCount != 0) {
            initialLoadTriggered = true
            return
        }
        if (binding.swipeRefresh.isRefreshing) return
        binding.swipeRefresh.isRefreshing = true
        reload()
        initialLoadTriggered = true
    }

    private fun reload() {
        val token = ++requestToken
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val nav = BiliApi.nav()
                val mid = nav.optJSONObject("data")?.optLong("mid") ?: 0L
                if (mid <= 0) error("invalid mid")
                val folders = BiliApi.favFolders(upMid = mid)
                if (token != requestToken) return@launch
                adapter.submit(folders)
                _binding?.recycler?.post { maybeConsumePendingFocusFirstItemFromTabSwitch() }
                restoreFocusIfNeeded()
            } catch (t: Throwable) {
                AppLog.e("MyFav", "load failed", t)
                context?.let { Toast.makeText(it, "加载失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show() }
            } finally {
                if (token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    private fun restoreFocusIfNeeded() {
        val pos = pendingRestorePosition ?: return
        if (_binding == null) return
        if (pos < 0 || pos >= adapter.itemCount) return
        val recycler = binding.recycler
        recycler.post outerPost@{
            if (_binding == null) return@outerPost
            recycler.scrollToPosition(pos)
            recycler.post innerPost@{
                if (_binding == null) return@innerPost
                recycler.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                pendingRestorePosition = null
            }
        }
    }

    override fun onDestroyView() {
        initialLoadTriggered = false
        _binding = null
        super.onDestroyView()
    }
}
