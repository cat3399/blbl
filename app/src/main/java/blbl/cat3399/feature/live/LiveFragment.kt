package blbl.cat3399.feature.live

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.LiveAreaParent
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.databinding.FragmentLiveBinding
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LiveFragment : Fragment(), LiveGridTabSwitchFocusHost {
    private var _binding: FragmentLiveBinding? = null
    private val binding get() = _binding!!

    private var mediator: TabLayoutMediator? = null
    private var pageCallback: androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback? = null
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false

    private var loadAreasJob: Job? = null

    private var tabs: List<LiveTab> =
        buildList {
            add(LiveTab.Recommend)
            if (BiliClient.cookies.hasSessData()) add(LiveTab.Following)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setTabs(tabs)
        loadAreasJob?.cancel()
        loadAreasJob =
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { BiliApi.liveAreas() }
                    .onSuccess { parents -> applyAreas(parents) }
                    .onFailure { AppLog.w("Live", "load areas failed", it) }
            }
    }

    private fun applyAreas(parents: List<LiveAreaParent>) {
        // Keep UI manageable: recommend + following + top parents.
        val picked =
            parents
                .filter { it.id > 0 && it.name.isNotBlank() }
                .sortedByDescending { it.children.count { c -> c.hot } }
                .take(12)
                .map { LiveTab.Area(parentId = it.id, title = it.name) }
        val next = buildList {
            add(LiveTab.Recommend)
            if (BiliClient.cookies.hasSessData()) add(LiveTab.Following)
            addAll(picked)
        }
        if (next == tabs) return
        tabs = next
        setTabs(tabs)
    }

    private fun setTabs(list: List<LiveTab>) {
        mediator?.detach()
        mediator = null

        binding.viewPager.adapter = LivePagerAdapter(this, list)
        mediator =
            TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
                tab.text = list.getOrNull(position)?.title ?: ""
            }.also { it.attach() }

        binding.tabLayout.post {
            binding.tabLayout.enableDpadTabFocus { position ->
                val title = list.getOrNull(position)?.title
                AppLog.d("Live", "tab focus pos=$position title=$title t=${SystemClock.uptimeMillis()}")
            }
            val tabStrip = binding.tabLayout.getChildAt(0) as? ViewGroup ?: return@post
            for (i in 0 until tabStrip.childCount) {
                tabStrip.getChildAt(i).setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        focusCurrentPageFirstCardFromTab()
                        return@setOnKeyListener true
                    }
                    false
                }
            }
        }

        pageCallback?.let { binding.viewPager.unregisterOnPageChangeCallback(it) }
        pageCallback =
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val tab = list.getOrNull(position)
                    AppLog.d("Live", "page selected pos=$position title=${tab?.title} t=${SystemClock.uptimeMillis()}")
                    if (pendingFocusFirstCardFromContentSwitch) {
                        if (focusCurrentPageFirstCardFromContentSwitch()) {
                            pendingFocusFirstCardFromContentSwitch = false
                        }
                    }
                }
            }.also { binding.viewPager.registerOnPageChangeCallback(it) }
    }

    private fun focusCurrentPageFirstCardFromTab(): Boolean {
        val pagerAdapter = binding.viewPager.adapter as? FragmentStateAdapter ?: return false
        val position = binding.viewPager.currentItem
        val itemId = pagerAdapter.getItemId(position)
        val byTag = childFragmentManager.findFragmentByTag("f$itemId")
        val pageFragment =
            when {
                byTag is LiveGridFragment -> byTag
                else -> childFragmentManager.fragments.firstOrNull { it.isVisible && it is LiveGridFragment } as? LiveGridFragment
            } ?: return false
        return pageFragment.requestFocusFirstCardFromTab()
    }

    private fun focusCurrentPageFirstCardFromContentSwitch(): Boolean {
        val pagerAdapter = binding.viewPager.adapter as? FragmentStateAdapter ?: return false
        val position = binding.viewPager.currentItem
        val itemId = pagerAdapter.getItemId(position)
        val byTag = childFragmentManager.findFragmentByTag("f$itemId")
        val pageFragment =
            when {
                byTag is LiveGridFragment -> byTag
                else -> childFragmentManager.fragments.firstOrNull { it.isVisible && it is LiveGridFragment } as? LiveGridFragment
            } ?: return false
        return pageFragment.requestFocusFirstCardFromContentSwitch()
    }

    override fun requestFocusCurrentPageFirstCardFromContentSwitch(): Boolean {
        pendingFocusFirstCardFromContentSwitch = true
        if (focusCurrentPageFirstCardFromContentSwitch()) {
            pendingFocusFirstCardFromContentSwitch = false
        }
        return true
    }

    override fun onDestroyView() {
        loadAreasJob?.cancel()
        loadAreasJob = null

        mediator?.detach()
        mediator = null

        pageCallback?.let { binding.viewPager.unregisterOnPageChangeCallback(it) }
        pageCallback = null

        _binding = null
        super.onDestroyView()
    }

    data class LiveTab(
        val title: String,
        val kind: Kind,
        val parentId: Int?,
    ) {
        enum class Kind { RECOMMEND, FOLLOWING, AREA }

        companion object {
            val Recommend = LiveTab(title = "推荐", kind = Kind.RECOMMEND, parentId = null)
            val Following = LiveTab(title = "关注", kind = Kind.FOLLOWING, parentId = null)
            fun Area(parentId: Int, title: String) = LiveTab(title = title, kind = Kind.AREA, parentId = parentId)
        }
    }

    private class LivePagerAdapter(
        fragment: Fragment,
        private val tabs: List<LiveTab>,
    ) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = tabs.size

        override fun createFragment(position: Int): Fragment {
            val tab = tabs[position]
            AppLog.d("Live", "createFragment pos=$position title=${tab.title} kind=${tab.kind} pid=${tab.parentId} t=${SystemClock.uptimeMillis()}")
            return when (tab.kind) {
                LiveTab.Kind.RECOMMEND -> LiveGridFragment.newRecommend()
                LiveTab.Kind.FOLLOWING -> LiveGridFragment.newFollowing()
                LiveTab.Kind.AREA -> LiveGridFragment.newRecommendFiltered(parentAreaId = tab.parentId ?: 0, title = tab.title)
            }
        }
    }

    companion object {
        fun newInstance() = LiveFragment()
    }
}
