package blbl.cat3399.feature.live

/**
 * Implemented by fragments that host a TabLayout + ViewPager2 whose pages are [LiveGridFragment].
 * Used when switching tabs from inside the content area (e.g. DPAD_LEFT/RIGHT at list edges) to
 * move focus into the newly selected page content.
 */
interface LiveGridTabSwitchFocusHost {
    fun requestFocusCurrentPageFirstCardFromContentSwitch(): Boolean
}

