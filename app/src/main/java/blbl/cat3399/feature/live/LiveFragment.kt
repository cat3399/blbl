package blbl.cat3399.feature.live

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.ui.TabContentFocusTarget
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.core.ui.findCurrentViewPagerChildFragment
import blbl.cat3399.core.ui.findCurrentViewPagerChildFragmentAs
import blbl.cat3399.core.ui.postDelayedIfAlive
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.requestFocusSelectedTab
import blbl.cat3399.databinding.FragmentLiveBinding
import blbl.cat3399.ui.BackPressHandler
import blbl.cat3399.ui.RefreshKeyHandler
import blbl.cat3399.ui.SidebarFocusHost
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class LiveFragment : Fragment(), LiveGridTabSwitchFocusHost, BackPressHandler, LiveNavigator {
    private var _binding: FragmentLiveBinding? = null
    private val binding get() = _binding!!

    private var mediator: TabLayoutMediator? = null
    private var pageCallback: androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback? = null
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false
    private var pendingFocusFirstCardFromBackToTab0: Boolean = false
    private var pendingBackToTab0RequestToken: Int = 0
    private var pendingBackToTab0AttemptsLeft: Int = 0
    private var pendingRestoreFocusAfterDetailReturn: Boolean = false

    private var backStackListener: FragmentManager.OnBackStackChangedListener? = null

    private val tabReselectRefreshListener: TabLayout.OnTabSelectedListener =
        object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = Unit

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
                refreshCurrentPageFromTabReselect()
            }
        }

    private var tabs: List<LiveTab> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setTabs(LiveTabs.visibleTabs(BiliClient.prefs, isLoggedIn = BiliClient.cookies.hasSessData()), force = true)
        backStackListener =
            FragmentManager.OnBackStackChangedListener {
                val showDetail = updateDetailVisibility()
                if (!showDetail) maybeRestoreFocusAfterDetailReturn()
            }.also { childFragmentManager.addOnBackStackChangedListener(it) }
        updateDetailVisibility()
    }

    override fun onResume() {
        super.onResume()
        setTabs(LiveTabs.visibleTabs(BiliClient.prefs, isLoggedIn = BiliClient.cookies.hasSessData()))
    }

    override fun openAreaDetail(parentAreaId: Int, parentTitle: String, areaId: Int, areaTitle: String): Boolean {
        if (_binding == null || childFragmentManager.isStateSaved) return false
        pendingRestoreFocusAfterDetailReturn = true
        childFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(
                binding.detailContainer.id,
                LiveAreaDetailFragment.newInstance(
                    parentAreaId = parentAreaId,
                    parentTitle = parentTitle,
                    areaId = areaId,
                    areaTitle = areaTitle,
                ),
            )
            .addToBackStack(null)
            .commit()
        updateDetailVisibility()
        return true
    }

    private fun updateDetailVisibility(): Boolean {
        val b = _binding ?: return false
        val showDetail = childFragmentManager.backStackEntryCount > 0
        b.detailContainer.visibility = if (showDetail) View.VISIBLE else View.GONE
        b.tabLayout.visibility = if (showDetail) View.GONE else View.VISIBLE
        b.viewPager.visibility = if (showDetail) View.GONE else View.VISIBLE
        return showDetail
    }

    private fun maybeRestoreFocusAfterDetailReturn() {
        if (!pendingRestoreFocusAfterDetailReturn) return
        val b = _binding ?: return
        pendingRestoreFocusAfterDetailReturn = false
        val isUiAlive = { _binding === b && isResumed }
        b.viewPager.postIfAlive(isAlive = isUiAlive) {
            val page = currentPageFragment()
            val restored = (page as? LivePageReturnFocusTarget)?.restoreFocusAfterReturnFromDetail() == true
            if (!restored) {
                focusCurrentPageFirstCardFromContentSwitch()
            }
        }
    }

    private fun currentPageFragment(): Fragment? {
        return findCurrentViewPagerChildFragment(binding.viewPager)
    }

    private fun refreshCurrentPageFromTabReselect(): Boolean {
        val page = currentPageFragment() ?: return false
        val handler = page as? RefreshKeyHandler ?: return false
        return handler.handleRefreshKey()
    }

    private fun setTabs(list: List<LiveTab>, force: Boolean = false) {
        if (_binding == null) return
        val next = list.ifEmpty { LiveTabs.defaultVisibleTabs(isLoggedIn = BiliClient.cookies.hasSessData()) }
        if (!force && tabs.map { it.key } == next.map { it.key }) return

        val currentKey = tabs.getOrNull(binding.viewPager.currentItem)?.key
        mediator?.detach()
        mediator = null
        tabs = next

        val b = binding
        b.viewPager.adapter = LivePagerAdapter(this, tabs)
        mediator =
            TabLayoutMediator(b.tabLayout, b.viewPager) { tab, position ->
                tab.text = tabs.getOrNull(position)?.title ?: ""
            }.also { it.attach() }

        b.tabLayout.removeOnTabSelectedListener(tabReselectRefreshListener)
        b.tabLayout.addOnTabSelectedListener(tabReselectRefreshListener)

        val targetIndex = currentKey?.let { key -> tabs.indexOfFirst { it.key == key }.takeIf { it >= 0 } } ?: 0
        if (targetIndex != b.viewPager.currentItem && targetIndex in tabs.indices) {
            b.viewPager.setCurrentItem(targetIndex, false)
        }

        val tabLayout = b.tabLayout
        tabLayout.postIfAlive(isAlive = { _binding === b }) {
            tabLayout.enableDpadTabFocus(selectOnFocusProvider = { BiliClient.prefs.tabSwitchFollowsFocus }) { position ->
                val tab = tabs.getOrNull(position)
                AppLog.d("Live", "tab focus pos=$position key=${tab?.key} title=${tab?.title} t=${SystemClock.uptimeMillis()}")
            }
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return@postIfAlive
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
                    val tab = tabs.getOrNull(position)
                    AppLog.d("Live", "page selected pos=$position key=${tab?.key} title=${tab?.title} t=${SystemClock.uptimeMillis()}")
                    if (pendingFocusFirstCardFromBackToTab0) {
                        maybeRequestTab0FocusFromBackToTab0()
                    } else if (pendingFocusFirstCardFromContentSwitch) {
                        if (focusCurrentPageFirstCardFromContentSwitch()) {
                            pendingFocusFirstCardFromContentSwitch = false
                        }
                    }
                }
            }.also { binding.viewPager.registerOnPageChangeCallback(it) }
    }

    private fun focusCurrentPageFirstCardFromTab(): Boolean {
        val pageFragment = findCurrentViewPagerChildFragmentAs<TabContentFocusTarget>(binding.viewPager) ?: return false
        return pageFragment.requestFocusPrimaryItemFromTab()
    }

    private fun focusCurrentPageFirstCardFromContentSwitch(): Boolean {
        val pageFragment = findCurrentViewPagerChildFragmentAs<TabContentFocusTarget>(binding.viewPager) ?: return false
        return pageFragment.requestFocusPrimaryItemFromContentSwitch()
    }

    private fun maybeRequestTab0FocusFromBackToTab0(): Boolean {
        val b = _binding ?: return false
        if (!pendingFocusFirstCardFromBackToTab0) return false
        // "Back -> tab0 content" is only meaningful when tab0 is selected.
        if (b.viewPager.currentItem != 0) return false

        val tab0 = findCurrentViewPagerChildFragmentAs<TabContentFocusTarget>(b.viewPager)
        if (tab0 != null) {
            tab0.requestFocusPrimaryItemFromBackToTab0()
            pendingFocusFirstCardFromBackToTab0 = false
            pendingBackToTab0AttemptsLeft = 0
            return true
        }

        // Page fragment not ready yet: retry for a few frames.
        if (pendingBackToTab0AttemptsLeft <= 0) return false
        val token = pendingBackToTab0RequestToken
        pendingBackToTab0AttemptsLeft--
        b.viewPager.postDelayedIfAlive(
            delayMillis = 16L,
            isAlive = { _binding === b && pendingFocusFirstCardFromBackToTab0 && pendingBackToTab0RequestToken == token },
        ) {
            maybeRequestTab0FocusFromBackToTab0()
        }
        return true
    }

    private fun focusSelectedTab(): Boolean {
        val b = _binding ?: return false
        return b.tabLayout.requestFocusSelectedTab(fallbackPosition = b.viewPager.currentItem) { _binding != null }
    }

    override fun requestFocusCurrentPageFirstCardFromContentSwitch(): Boolean {
        pendingFocusFirstCardFromContentSwitch = true
        if (focusCurrentPageFirstCardFromContentSwitch()) {
            pendingFocusFirstCardFromContentSwitch = false
        }
        return true
    }

    override fun handleBackPressed(): Boolean {
        if (childFragmentManager.popBackStackImmediate()) {
            updateDetailVisibility()
            maybeRestoreFocusAfterDetailReturn()
            return true
        }
        val b = _binding ?: return false
        val scheme = BiliClient.prefs.mainBackFocusScheme

        // Tab strip is a navigation layer: Back should always return to the left sidebar.
        if (b.tabLayout.hasFocus()) {
            return (activity as? SidebarFocusHost)?.requestFocusSidebarSelectedNav() == true
        }

        // Only handle the Back key when focus is inside the page content area.
        val inContent = b.viewPager.hasFocus() && !b.tabLayout.hasFocus()
        if (!inContent) return false

        return when (scheme) {
            AppPrefs.MAIN_BACK_FOCUS_SCHEME_A -> focusSelectedTab()
            AppPrefs.MAIN_BACK_FOCUS_SCHEME_B -> {
                if (b.viewPager.currentItem != 0) {
                    pendingFocusFirstCardFromBackToTab0 = true
                    pendingFocusFirstCardFromContentSwitch = false
                    pendingBackToTab0RequestToken++
                    pendingBackToTab0AttemptsLeft = 30
                    // Use non-smooth switch: smooth scrolling may trigger intermediate onPageSelected callbacks
                    // and consume the pending focus restore on the wrong page.
                    b.viewPager.setCurrentItem(0, false)
                    true
                } else {
                    (activity as? SidebarFocusHost)?.requestFocusSidebarSelectedNav() == true
                }
            }
            AppPrefs.MAIN_BACK_FOCUS_SCHEME_C -> {
                (activity as? SidebarFocusHost)?.requestFocusSidebarSelectedNav() == true
            }
            else -> focusSelectedTab()
        }
    }

    override fun onDestroyView() {
        backStackListener?.let { childFragmentManager.removeOnBackStackChangedListener(it) }
        backStackListener = null

        mediator?.detach()
        mediator = null

        pageCallback?.let { binding.viewPager.unregisterOnPageChangeCallback(it) }
        pageCallback = null

        _binding = null
        super.onDestroyView()
    }

    data class LiveTab(
        val key: String,
        val title: String,
        val kind: Kind,
        val parentId: Int?,
    ) {
        enum class Kind { RECOMMEND, FOLLOWING, AREA }
    }

    object LiveTabs {
        const val KEY_RECOMMEND = "recommend"
        const val KEY_FOLLOWING = "following"
        private const val KEY_AREA_PREFIX = "area:"

        val all: List<LiveTab> =
            buildList {
                add(LiveTab(key = KEY_RECOMMEND, title = "推荐", kind = LiveTab.Kind.RECOMMEND, parentId = null))
                add(LiveTab(key = KEY_FOLLOWING, title = "关注", kind = LiveTab.Kind.FOLLOWING, parentId = null))
                LiveAreas.defaultParents
                    .filter { it.id > 0 && it.name.isNotBlank() }
                    .forEach {
                        add(LiveTab(key = areaKey(it.id), title = it.name, kind = LiveTab.Kind.AREA, parentId = it.id))
                    }
            }

        fun visibleTabs(prefs: AppPrefs, isLoggedIn: Boolean): List<LiveTab> {
            val available = availableTabs(isLoggedIn = isLoggedIn)
            val selectedKeys = prefs.mainLiveVisibleTabs
            if (selectedKeys.isEmpty()) return available
            val selected = selectedKeys.toSet()
            return available.filter { it.key in selected }.ifEmpty { defaultVisibleTabs(isLoggedIn = isLoggedIn) }
        }

        fun defaultVisibleTabs(isLoggedIn: Boolean): List<LiveTab> = availableTabs(isLoggedIn = isLoggedIn).take(1)

        private fun availableTabs(isLoggedIn: Boolean): List<LiveTab> {
            return all.filter { it.kind != LiveTab.Kind.FOLLOWING || isLoggedIn }
        }

        private fun areaKey(parentId: Int): String = KEY_AREA_PREFIX + parentId
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
                LiveTab.Kind.AREA -> LiveAreaIndexFragment.newInstance(parentAreaId = tab.parentId ?: 0, parentTitle = tab.title)
            }
        }

        override fun getItemId(position: Int): Long = tabs[position].key.hashCode().toLong()

        override fun containsItem(itemId: Long): Boolean = tabs.any { it.key.hashCode().toLong() == itemId }
    }

    companion object {
        fun newInstance() = LiveFragment()
    }
}
