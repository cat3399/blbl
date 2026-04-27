package blbl.cat3399.feature.home

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import blbl.cat3399.R
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.feature.video.VideoGridFragment

data class HomeTabSpec(
    val key: String,
    @StringRes val titleRes: Int,
    val createFragment: () -> Fragment,
)

object HomeTabs {
    const val KEY_RECOMMEND = "recommend"
    const val KEY_POPULAR = "popular"
    const val KEY_BANGUMI = "bangumi"
    const val KEY_CINEMA = "cinema"

    val all: List<HomeTabSpec> =
        listOf(
            HomeTabSpec(KEY_RECOMMEND, R.string.tab_recommend) { VideoGridFragment.newRecommend() },
            HomeTabSpec(KEY_POPULAR, R.string.tab_popular) { VideoGridFragment.newPopular() },
            HomeTabSpec(KEY_BANGUMI, R.string.tab_bangumi) { PgcRecommendGridFragment.newBangumi() },
            HomeTabSpec(KEY_CINEMA, R.string.tab_cinema) { PgcRecommendGridFragment.newCinema() },
        )

    fun visibleTabs(prefs: AppPrefs): List<HomeTabSpec> = filterVisible(all, prefs.mainHomeVisibleTabs)

    private fun filterVisible(allTabs: List<HomeTabSpec>, selectedKeys: List<String>): List<HomeTabSpec> {
        if (selectedKeys.isEmpty()) return allTabs
        val selected = selectedKeys.toSet()
        return allTabs.filter { it.key in selected }.ifEmpty { allTabs }
    }
}

class HomePagerAdapter(
    fragment: Fragment,
    private val tabs: List<HomeTabSpec>,
) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = tabs.size

    override fun createFragment(position: Int): Fragment {
        return tabs[position].createFragment()
    }

    override fun getItemId(position: Int): Long = tabs[position].key.hashCode().toLong()

    override fun containsItem(itemId: Long): Boolean = tabs.any { it.key.hashCode().toLong() == itemId }
}
