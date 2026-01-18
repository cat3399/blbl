package blbl.cat3399.feature.live

/**
 * Implemented by live page fragments that want to restore focus after returning from
 * [LiveAreaDetailFragment] (e.g. go back to the previously selected card instead of
 * always focusing the first card).
 */
interface LivePageReturnFocusTarget {
    fun restoreFocusAfterReturnFromDetail(): Boolean
}

