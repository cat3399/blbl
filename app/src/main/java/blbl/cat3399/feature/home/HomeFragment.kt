package blbl.cat3399.feature.home

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.databinding.FragmentHomeBinding
import blbl.cat3399.feature.video.VideoGridFragment
import blbl.cat3399.feature.video.VideoGridTabSwitchFocusHost
import blbl.cat3399.ui.BackPressHandler

class HomeFragment : Fragment(), VideoGridTabSwitchFocusHost, BackPressHandler {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var pageCallback: androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback? = null
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false

    private fun requestCurrentPageFocusFromContentSwitch(): Boolean {
        val adapter = binding.viewPager.adapter as? FragmentStateAdapter ?: return false
        val position = binding.viewPager.currentItem
        val itemId = adapter.getItemId(position)
        val byTag = childFragmentManager.findFragmentByTag("f$itemId")
        val pageFragment =
            when {
                byTag is VideoGridFragment -> byTag
                else -> childFragmentManager.fragments.firstOrNull { it.isVisible && it is VideoGridFragment } as? VideoGridFragment
            } ?: return false
        return pageFragment.requestFocusFirstCardFromContentSwitch()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.viewPager.adapter = HomePagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_recommend)
                else -> getString(R.string.tab_popular)
            }
        }.attach()
        val tabLayout = binding.tabLayout
        tabLayout.post {
            if (_binding == null) return@post
            tabLayout.enableDpadTabFocus { position ->
                AppLog.d("Home", "tab focus pos=$position t=${SystemClock.uptimeMillis()}")
            }
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return@post
            for (i in 0 until tabStrip.childCount) {
                tabStrip.getChildAt(i).setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        val adapter = binding.viewPager.adapter as? FragmentStateAdapter ?: return@setOnKeyListener false
                        val position = binding.viewPager.currentItem
                        val itemId = adapter.getItemId(position)
                        val byTag = childFragmentManager.findFragmentByTag("f$itemId")
                        val pageFragment =
                            when {
                                byTag is VideoGridFragment -> byTag
                                else -> childFragmentManager.fragments.firstOrNull { it.isVisible && it is VideoGridFragment } as? VideoGridFragment
                            } ?: return@setOnKeyListener false
                        return@setOnKeyListener pageFragment.requestFocusFirstCardFromTab()
                    }
                    false
                }
            }
        }
        pageCallback =
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    AppLog.d("Home", "page selected pos=$position t=${SystemClock.uptimeMillis()}")
                    if (pendingFocusFirstCardFromContentSwitch) {
                        if (requestCurrentPageFocusFromContentSwitch()) {
                            pendingFocusFirstCardFromContentSwitch = false
                        }
                    }
                }
            }
        binding.viewPager.registerOnPageChangeCallback(pageCallback!!)
    }

    override fun handleBackPressed(): Boolean {
        val b = _binding ?: return false
        if (b.viewPager.currentItem == 0) return false
        pendingFocusFirstCardFromContentSwitch = true
        b.viewPager.setCurrentItem(0, true)
        return true
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
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = HomeFragment()
    }
}
