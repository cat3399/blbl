package blbl.cat3399.feature.my

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.core.ui.findCurrentViewPagerChildFragment
import blbl.cat3399.core.ui.findCurrentViewPagerChildFragmentAs
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.requestFocusSelectedTab
import blbl.cat3399.databinding.FragmentMyTabsBinding
import blbl.cat3399.ui.BackPressHandler
import com.google.android.material.tabs.TabLayoutMediator
import blbl.cat3399.ui.RefreshKeyHandler
import blbl.cat3399.ui.SidebarFocusHost
import com.google.android.material.tabs.TabLayout

class MyTabsFragment : Fragment(), MyTabContentSwitchFocusHost, BackPressHandler {
    private var _binding: FragmentMyTabsBinding? = null
    private val binding get() = _binding!!

    private var mediator: TabLayoutMediator? = null
    private var pageCallback: androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback? = null
    private var tabs: List<MyTabSpec> = emptyList()
    private var pendingFocusFirstItemFromContentSwitch: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyTabsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setTabs(MyTabs.visibleTabs(BiliClient.prefs), force = true)
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
                    AppLog.d("My", "page selected pos=$position key=${tab?.key} t=${SystemClock.uptimeMillis()}")
                    if (pendingFocusFirstItemFromContentSwitch) {
                        if (focusCurrentPageFirstItemFromContentSwitch()) {
                            pendingFocusFirstItemFromContentSwitch = false
                        }
                    }
                }
            }
        binding.viewPager.registerOnPageChangeCallback(pageCallback!!)
    }

    override fun onResume() {
        super.onResume()
        setTabs(MyTabs.visibleTabs(BiliClient.prefs))
    }

    private fun setTabs(nextTabs: List<MyTabSpec>, force: Boolean = false) {
        val b = _binding ?: return
        val next = nextTabs.ifEmpty { MyTabs.all }
        if (!force && tabs.map { it.key } == next.map { it.key }) return

        val currentKey = tabs.getOrNull(b.viewPager.currentItem)?.key
        mediator?.detach()
        mediator = null
        tabs = next

        b.viewPager.adapter = MyPagerAdapter(this, tabs)
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

    private fun installTabFocusHandlers(b: FragmentMyTabsBinding) {
        val tabLayout = b.tabLayout
        tabLayout.postIfAlive(isAlive = { _binding === b }) {
            tabLayout.enableDpadTabFocus(selectOnFocusProvider = { BiliClient.prefs.tabSwitchFollowsFocus }) { position ->
                val tab = tabs.getOrNull(position)
                AppLog.d("My", "tab focus pos=$position key=${tab?.key} t=${SystemClock.uptimeMillis()}")
            }
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return@postIfAlive
            for (i in 0 until tabStrip.childCount) {
                tabStrip.getChildAt(i).setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        return@setOnKeyListener focusCurrentPageFirstItem()
                    }
                    false
                }
            }
        }
    }

    private fun refreshCurrentPageFromTabReselect(): Boolean {
        val target = findCurrentViewPagerChildFragmentAs<RefreshKeyHandler>(binding.viewPager) ?: return false
        return target.handleRefreshKey()
    }

    private fun focusCurrentPageFirstItem(): Boolean {
        val byTag = findCurrentViewPagerChildFragment(binding.viewPager)
        val target = byTag as? MyTabSwitchFocusTarget
        if (target != null) return target.requestFocusFirstItemFromTabSwitch()

        val pageFragment =
            if (byTag?.view?.findViewById<RecyclerView?>(R.id.recycler) != null) {
                byTag
            } else {
                childFragmentManager.fragments.firstOrNull { it.isVisible && it.view?.findViewById<RecyclerView?>(R.id.recycler) != null }
            } ?: return false
        val recycler = pageFragment.view?.findViewById<RecyclerView?>(R.id.recycler) ?: return false

        recycler.postIfAlive(isAlive = { _binding != null }) {
            val vh = recycler.findViewHolderForAdapterPosition(0)
            when {
                vh != null -> {
                    vh.itemView.requestFocus()
                }

                recycler.adapter?.itemCount == 0 -> {
                    recycler.requestFocus()
                }

                else -> {
                    recycler.scrollToPosition(0)
                    recycler.postIfAlive(isAlive = { _binding != null }) {
                        recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                            ?: recycler.requestFocus()
                    }
                }
            }
        }
        return true
    }

    private fun focusCurrentPageFirstItemFromContentSwitch(): Boolean {
        val target = findCurrentViewPagerChildFragmentAs<MyTabSwitchFocusTarget>(binding.viewPager)
            ?: return false
        return target.requestFocusFirstItemFromTabSwitch()
    }

    private fun focusSelectedTab(): Boolean {
        val b = _binding ?: return false
        return b.tabLayout.requestFocusSelectedTab(fallbackPosition = b.viewPager.currentItem) { _binding != null }
    }

    override fun requestFocusCurrentPageFirstItemFromContentSwitch(): Boolean {
        pendingFocusFirstItemFromContentSwitch = true
        if (focusCurrentPageFirstItemFromContentSwitch()) {
            pendingFocusFirstItemFromContentSwitch = false
        }
        return true
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
                    pendingFocusFirstItemFromContentSwitch = true
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
        pageCallback?.let { binding.viewPager.unregisterOnPageChangeCallback(it) }
        pageCallback = null
        mediator?.detach()
        mediator = null
        tabs = emptyList()
        _binding = null
        super.onDestroyView()
    }
}
