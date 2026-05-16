package blbl.cat3399.feature.category

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Zone
import blbl.cat3399.feature.video.VideoGridFragment
import android.os.SystemClock

class CategoryPagerAdapter(
    fragment: Fragment,
    private val zones: List<Zone>,
) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = zones.size

    override fun createFragment(position: Int): Fragment {
        val zone = zones[position]
        AppLog.d(
            "Category",
            "createFragment pos=$position title=${zone.title} tid=${zone.tid} t=${SystemClock.uptimeMillis()}",
        )
        return if (zone.tid == null) {
            VideoGridFragment.newPopular()
        } else {
            VideoGridFragment.newRegion(zone.tid)
        }
    }

    override fun getItemId(position: Int): Long = CategoryZones.stableKeyFor(zones[position]).hashCode().toLong()

    override fun containsItem(itemId: Long): Boolean = zones.any { CategoryZones.stableKeyFor(it).hashCode().toLong() == itemId }
}
