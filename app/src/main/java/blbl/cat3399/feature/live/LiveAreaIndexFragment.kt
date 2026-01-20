package blbl.cat3399.feature.live

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
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.LiveAreaParent
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.databinding.FragmentLiveGridBinding
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.launch

class LiveAreaIndexFragment : Fragment(), LivePageFocusTarget, LivePageReturnFocusTarget, RefreshKeyHandler {
    private var _binding: FragmentLiveGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: LiveAreaAdapter

    private val parentAreaId: Int by lazy { requireArguments().getInt(ARG_PARENT_AREA_ID, 0) }
    private val parentTitle: String by lazy { requireArguments().getString(ARG_PARENT_TITLE).orEmpty() }

    private var initialLoadTriggered: Boolean = false
    private var requestToken: Int = 0

    private var pendingFocusFirstCardFromTab: Boolean = false
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false
    private var pendingRestorePosition: Int? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter =
                LiveAreaAdapter { position, area ->
                    pendingRestorePosition = position
                    val nav = parentFragment as? LiveNavigator
                    if (nav == null) {
                        Toast.makeText(requireContext(), "无法打开分区：找不到导航宿主", Toast.LENGTH_SHORT).show()
                        return@LiveAreaAdapter
                    }
                    nav.openAreaDetail(
                        parentAreaId = parentAreaId,
                        parentTitle = parentTitle,
                        areaId = area.id,
                        areaTitle = area.name,
                    )
                }
        }

        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth())
        (binding.recycler.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.clearOnScrollListeners()
        binding.recycler.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val itemView = v
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (!binding.recycler.canScrollVertically(-1)) {
                                    val lm = binding.recycler.layoutManager as? GridLayoutManager ?: return@setOnKeyListener false
                                    val holder = binding.recycler.findContainingViewHolder(itemView) ?: return@setOnKeyListener false
                                    val pos = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnKeyListener false
                                    val firstVisible = lm.findFirstVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION } ?: 0
                                    if (pos <= firstVisible + lm.spanCount - 1) {
                                        focusSelectedTabIfAvailable()
                                        return@setOnKeyListener true
                                    }
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_LEFT)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    val switched = switchToPrevTabFromContentEdge()
                                    return@setOnKeyListener switched
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val next = FocusFinder.getInstance().findNextFocus(binding.recycler, itemView, View.FOCUS_RIGHT)
                                if (next == null || !isDescendantOf(next, binding.recycler)) {
                                    if (switchToNextTabFromContentEdge()) return@setOnKeyListener true
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
        binding.swipeRefresh.setOnRefreshListener { reload(force = true) }
    }

    override fun onResume() {
        super.onResume()
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth()
        maybeTriggerInitialLoad()
        restoreFocusIfNeeded()
        maybeConsumePendingFocusFirstCard()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        reload(force = true)
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
        reload(force = false)
        initialLoadTriggered = true
    }

    private fun reload(force: Boolean) {
        val token = ++requestToken
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val parents = BiliApi.liveAreas(force = force)
                if (token != requestToken) return@launch
                val pickedParent = parents.firstOrNull { it.id == parentAreaId }
                val children =
                    pickedParent
                        ?.children
                        ?.filter { it.id > 0 && it.name.isNotBlank() }
                        .orEmpty()
                adapter.submit(children)
                _binding?.recycler?.post {
                    restoreFocusIfNeeded()
                    maybeConsumePendingFocusFirstCard()
                }
            } catch (t: Throwable) {
                AppLog.e("LiveAreaIndex", "load failed pid=$parentAreaId title=$parentTitle", t)
                context?.let { Toast.makeText(it, "加载失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show() }
            } finally {
                if (token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    private fun spanCountForWidth(): Int {
        val override = BiliClient.prefs.gridSpanCount
        if (override > 0) return override.coerceIn(1, 6)
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return when {
            widthDp >= 1100 -> 4
            widthDp >= 800 -> 3
            else -> 2
        }
    }

    override fun requestFocusFirstCardFromTab(): Boolean {
        pendingFocusFirstCardFromTab = true
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    override fun requestFocusFirstCardFromContentSwitch(): Boolean {
        pendingFocusFirstCardFromContentSwitch = true
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    override fun restoreFocusAfterReturnFromDetail(): Boolean {
        if (!isResumed) return true
        return restoreFocusIfNeeded()
    }

    private fun restoreFocusIfNeeded(): Boolean {
        val pos = pendingRestorePosition ?: return false
        if (!isAdded || _binding == null) return false
        if (!isResumed) return false
        if (!this::adapter.isInitialized) return false
        if (pos < 0 || pos >= adapter.itemCount) {
            pendingRestorePosition = null
            return false
        }

        val recycler = binding.recycler
        recycler.post outerPost@{
            if (_binding == null) return@outerPost
            recycler.scrollToPosition(pos)
            recycler.post innerPost@{
                if (_binding == null) return@innerPost
                recycler.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                    ?: recycler.requestFocus()
                pendingRestorePosition = null
            }
        }
        return true
    }

    private fun maybeConsumePendingFocusFirstCard(): Boolean {
        if (pendingRestorePosition != null) return false
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
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
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
            (parentFragment as? LiveGridTabSwitchFocusHost)?.requestFocusCurrentPageFirstCardFromContentSwitch()
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
            (parentFragment as? LiveGridTabSwitchFocusHost)?.requestFocusCurrentPageFirstCardFromContentSwitch()
                ?: tabStrip.getChildAt(prev)?.requestFocus()
        }
        return true
    }

    override fun onDestroyView() {
        initialLoadTriggered = false
        pendingRestorePosition = null
        _binding = null
        super.onDestroyView()
    }

    private fun isDescendantOf(child: View, parent: View): Boolean {
        var cur: View? = child
        while (cur != null) {
            if (cur === parent) return true
            cur = (cur.parent as? View)
        }
        return false
    }

    companion object {
        private const val ARG_PARENT_AREA_ID = "parent_area_id"
        private const val ARG_PARENT_TITLE = "parent_title"

        fun newInstance(parentAreaId: Int, parentTitle: String): LiveAreaIndexFragment =
            LiveAreaIndexFragment().apply {
                arguments =
                    Bundle().apply {
                        putInt(ARG_PARENT_AREA_ID, parentAreaId)
                        putString(ARG_PARENT_TITLE, parentTitle)
                    }
            }
    }
}
