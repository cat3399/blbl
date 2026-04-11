package blbl.cat3399.core.ui

import android.animation.AnimatorInflater
import android.os.Build
import android.view.ViewGroup
import blbl.cat3399.R
import com.google.android.material.tabs.TabLayout

fun TabLayout.enableDpadTabFocus(
    selectOnFocus: Boolean = true,
    onTabFocused: ((position: Int) -> Unit)? = null,
) {
    enableDpadTabFocus(selectOnFocusProvider = { selectOnFocus }, onTabFocused = onTabFocused)
}

fun TabLayout.enableDpadTabFocus(
    selectOnFocusProvider: () -> Boolean,
    onTabFocused: ((position: Int) -> Unit)? = null,
) {
    val tabStrip = getChildAt(0) as? ViewGroup ?: return
    for (i in 0 until tabStrip.childCount) {
        val index = i
        val tabView = tabStrip.getChildAt(i)
        tabView.isFocusable = true
        tabView.isClickable = true
        if (Build.VERSION.SDK_INT >= 21) {
            tabView.stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.blbl_focus_scale)
        }
        tabView.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            if (selectOnFocusProvider() && selectedTabPosition != index) {
                getTabAt(index)?.select()
            }
            onTabFocused?.invoke(index)
        }
    }
}
