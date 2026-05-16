package blbl.cat3399.feature.home

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayoutMediator
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.TabContentFocusTarget
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.core.ui.findCurrentViewPagerChildFragmentAs
import blbl.cat3399.core.ui.postDelayedIfAlive
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.requestFocusSelectedTab
import blbl.cat3399.databinding.FragmentHomeBinding
import blbl.cat3399.feature.video.VideoGridTabSwitchFocusHost
import blbl.cat3399.ui.BackPressHandler
import blbl.cat3399.ui.RefreshKeyHandler
import blbl.cat3399.ui.SidebarFocusHost
import com.google.android.material.tabs.TabLayout

class HomeFragment : Fragment(), VideoGridTabSwitchFocusHost, BackPressHandler {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var mediator: TabLayoutMediator? = null
    private var pageCallback: androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback? = null
    private var tabs: List<HomeTabSpec> = emptyList()
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false
    private var pendingFocusFirstCardFromBackToTab0: Boolean = false
    private var pendingBackToTab0RequestToken: Int = 0
    private var pendingBackToTab0AttemptsLeft: Int = 0

    private fun requestCurrentPageFocusFromContentSwitch(): Boolean {
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
            // Delegate to the page: it owns the actual "focus first card" flow.
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

    private fun refreshCurrentPageFromTabReselect(): Boolean {
        val page = findCurrentViewPagerChildFragmentAs<RefreshKeyHandler>(binding.viewPager) ?: return false
        return page.handleRefreshKey()
    }

    private fun focusSelectedTab(): Boolean {
        val b = _binding ?: return false
        return b.tabLayout.requestFocusSelectedTab(fallbackPosition = b.viewPager.currentItem) { _binding != null }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setTabs(HomeTabs.visibleTabs(BiliClient.prefs), force = true)
        binding.tabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) = Unit

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) {
                    refreshCurrentPageFromTabReselect()
                }
            },
        )
        pageCallback =
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val tab = tabs.getOrNull(position)
                    AppLog.d("Home", "page selected pos=$position key=${tab?.key} t=${SystemClock.uptimeMillis()}")
                    if (pendingFocusFirstCardFromBackToTab0) {
                        maybeRequestTab0FocusFromBackToTab0()
                    } else if (pendingFocusFirstCardFromContentSwitch) {
                        if (requestCurrentPageFocusFromContentSwitch()) {
                            pendingFocusFirstCardFromContentSwitch = false
                        }
                    }
                }
            }
        binding.viewPager.registerOnPageChangeCallback(pageCallback!!)
    }

    override fun onResume() {
        super.onResume()
        setTabs(HomeTabs.visibleTabs(BiliClient.prefs))
    }

    private fun setTabs(nextTabs: List<HomeTabSpec>, force: Boolean = false) {
        val b = _binding ?: return
        val next = nextTabs.ifEmpty { HomeTabs.all }
        if (!force && tabs.map { it.key } == next.map { it.key }) return

        val currentKey = tabs.getOrNull(b.viewPager.currentItem)?.key
        mediator?.detach()
        mediator = null
        tabs = next

        b.viewPager.adapter = HomePagerAdapter(this, tabs)
        mediator =
            TabLayoutMediator(b.tabLayout, b.viewPager) { tab, position ->
                tab.text = tabs.getOrNull(position)?.let { getString(it.titleRes) }.orEmpty()
            }.also { it.attach() }

        val targetIndex = currentKey?.let { key -> tabs.indexOfFirst { it.key == key }.takeIf { it >= 0 } } ?: 0
        if (targetIndex != b.viewPager.currentItem && targetIndex in tabs.indices) {
            b.viewPager.setCurrentItem(targetIndex, false)
        }

        installTabFocusHandlers(b)
    }

    private fun installTabFocusHandlers(b: FragmentHomeBinding) {
        val tabLayout = b.tabLayout
        tabLayout.postIfAlive(isAlive = { _binding === b }) {
            tabLayout.enableDpadTabFocus(selectOnFocusProvider = { BiliClient.prefs.tabSwitchFollowsFocus }) { position ->
                val tab = tabs.getOrNull(position)
                AppLog.d("Home", "tab focus pos=$position key=${tab?.key} t=${SystemClock.uptimeMillis()}")
            }
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return@postIfAlive
            for (i in 0 until tabStrip.childCount) {
                tabStrip.getChildAt(i).setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        val pageFragment = findCurrentViewPagerChildFragmentAs<TabContentFocusTarget>(binding.viewPager)
                            ?: return@setOnKeyListener false
                        return@setOnKeyListener pageFragment.requestFocusPrimaryItemFromTab()
                    }
                    false
                }
            }
        }
    }

    override fun handleBackPressed(): Boolean {
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

    override fun requestFocusCurrentPageFirstCardFromContentSwitch(): Boolean {
        pendingFocusFirstCardFromContentSwitch = true
        if (requestCurrentPageFocusFromContentSwitch()) {
            pendingFocusFirstCardFromContentSwitch = false
        }
        return true
    }

    override fun onDestroyView() {
        pageCallback?.let { binding.viewPager.unregisterOnPageChangeCallback(it) }
        pageCallback = null
        mediator?.detach()
        mediator = null
        tabs = emptyList()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = HomeFragment()
    }
}
