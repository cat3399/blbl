package blbl.cat3399.core.ui

import android.os.Build
import android.os.SystemClock
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import blbl.cat3399.R

internal fun interface DpadItemKeyHandler {
    fun onInterceptKey(
        itemView: View,
        keyCode: Int,
        event: KeyEvent,
    ): Boolean
}

internal fun View.setDpadItemKeyHandler(handler: DpadItemKeyHandler?) {
    setTag(R.id.tag_dpad_item_key_handler, handler)
}

internal fun View.dispatchToDpadItemKeyHandler(
    keyCode: Int,
    event: KeyEvent,
): Boolean {
    val handler = getTag(R.id.tag_dpad_item_key_handler) as? DpadItemKeyHandler ?: return false
    return handler.onInterceptKey(this, keyCode, event)
}

internal fun View.dispatchToAncestorDpadItemKeyHandler(
    keyCode: Int,
    event: KeyEvent,
): Boolean {
    var current: View? = this
    while (current != null) {
        if (current.dispatchToDpadItemKeyHandler(keyCode, event)) {
            return true
        }
        current = current.parent as? View
    }
    return false
}

/**
 * A reusable DPAD focus controller for grid-like RecyclerViews.
 *
 * It keeps focus inside the grid on RIGHT/DOWN edges (no outflow), optionally switches tabs on
 * LEFT/RIGHT edges, and supports "infinite scroll" UX: when DPAD_DOWN hits the bottom and triggers
 * loading more data, focus will move to the next row (same column) after new items are appended.
 */
internal class DpadGridController(
    private val recyclerView: RecyclerView,
    private val callbacks: Callbacks,
    private val config: Config = Config(),
) {
    data class Config(
        /**
         * Called on every key event. Return false to disable all handling.
         * Typical usage in Fragments: `{ _binding != null && isResumed }`.
         */
        val isEnabled: () -> Boolean = { true },
        /**
         * When enabled, long-pressing DPAD_CENTER/ENTER triggers performLongClick() (repeatCount>0).
         * This is useful on TV where "OK" long press is expected to open a context action.
         */
        val enableCenterLongPressToLongClick: Boolean = false,
        /**
         * When DPAD_DOWN reaches the bottom but RecyclerView can still scroll, scroll by this
         * factor of the focused item height and then attempt to keep focus inside the grid.
         */
        val scrollOnDownEdgeFactor: Float = 0.8f,
        /**
         * If true, DPAD_RIGHT on the right edge will always be consumed (no outflow).
         *
         * Some layouts (e.g. a left-side vertical list that should move focus into a right-side
         * content panel) may want to allow RIGHT to bubble to the system focus-search.
         */
        val consumeRightEdge: Boolean = true,
        /**
         * If true, DPAD_UP at the top edge will always be consumed (even if callbacks fail to
         * move focus elsewhere). This helps prevent focus from "escaping" unexpectedly.
         */
        val consumeUpAtTopEdge: Boolean = true,
    )

    interface Callbacks {
        /**
         * Called when DPAD_UP is pressed on the first row and the list can't scroll further up.
         * Return true if you handled focus (e.g. focused TabLayout/back button).
         */
        fun onTopEdge(): Boolean

        /**
         * Called when DPAD_LEFT is pressed and focus-search can't find another item in the grid.
         *
         * Return true to consume the event (e.g. switched to previous tab), or false to allow the
         * event to bubble (e.g. let Activity handle entering the sidebar on the first tab).
         */
        fun onLeftEdge(): Boolean

        /**
         * Called when DPAD_RIGHT is pressed and focus-search can't find another item in the grid.
         * The key event will always be consumed (no outflow).
         */
        fun onRightEdge()

        /**
         * Whether more data could be loaded when reaching the bottom edge.
         * Note: return true even while a request is in-flight if you want DPAD_DOWN to "queue"
         * a focus jump to the next row when the request finishes.
         */
        fun canLoadMore(): Boolean

        /**
         * Trigger loading the next page (if applicable). Implementations should be idempotent.
         */
        fun loadMore()
    }

    private var installed: Boolean = false
    private var pendingFocusAfterLoadMoreAnchorPos: Int = RecyclerView.NO_POSITION
    private var pendingFocusAfterLoadMoreTargetPos: Int = RecyclerView.NO_POSITION
    private val focusRetryDelayMillis: Long = 16L
    private val focusRetryMaxAttempts: Int = 30
    private var focusParkedDescendantFocusability: Int? = null
    private val focusProtectWindowMs: Long = 500L
    private var lastVerticalNavAtMs: Long = 0L
    private var lastVerticalNavDirection: Int = View.FOCUS_DOWN
    private var lastKnownFocusedAdapterPos: Int = RecyclerView.NO_POSITION
    private var lastKnownFocusedSpanIndex: Int? = null
    private var detachFocusRestoreToken: Int = 0
    private var originalOverScrollMode: Int? = null
    private var originalDefaultFocusHighlightEnabled: Boolean? = null
    private var didSuppressDefaultFocusHighlight: Boolean = false
    private var focusHighlightListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    /**
     * RecyclerView-level fallback for the brief moments when:
     * - The grid has focus but no child item currently holds focus (e.g. initial load, async updates),
     * - Or the focused item is being rebound and doesn't reliably provide adapter positions.
     *
     * Without this, DPAD_DOWN can bubble to the system and the framework may pick an "arbitrary"
     * fallback focus target outside the content area (often the left sidebar).
     */
    private val recyclerKeyListener =
        View.OnKeyListener { _, keyCode, event ->
            if (!installed) return@OnKeyListener false
            if (!config.isEnabled()) return@OnKeyListener false
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            if (!recyclerView.isFocused) return@OnKeyListener false

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    markVerticalNav(View.FOCUS_DOWN)
                    handleRecyclerFocusedDpadDown()
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    markVerticalNav(View.FOCUS_UP)
                    // If the user navigates away while a load-more focus jump is pending,
                    // cancel it and restore normal focus behavior.
                    if (pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION) {
                        clearPendingFocusAfterLoadMore()
                    }
                    // We intentionally don't add special handling here: let the system focus-search
                    // move focus out of the RecyclerView (e.g. to tabs/header) if applicable.
                    false
                }

                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> {
                    if (pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION) {
                        clearPendingFocusAfterLoadMore()
                    }
                    false
                }

                else -> false
            }
        }

    private val childListener =
        object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (config.enableCenterLongPressToLongClick) {
                view.setTag(R.id.tag_long_press_handled, false)
                }
                view.setOnKeyListener { v, keyCode, event ->
                    if (!installed) return@setOnKeyListener false
                    if (!config.isEnabled()) return@setOnKeyListener false

                    if (v.dispatchToDpadItemKeyHandler(keyCode, event)) {
                        return@setOnKeyListener true
                    }

                    if (config.enableCenterLongPressToLongClick && handleCenterLongPress(v, keyCode, event)) {
                        return@setOnKeyListener true
                    }

                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            markVerticalNav(View.FOCUS_UP)
                            val pos = resolveAdapterPosition(v)
                            if (pos != null) {
                                rememberLastKnownFocus(position = pos)
                                handleDpadUp(pos)
                            } else {
                                // During adapter updates, a focused view can transiently report NO_POSITION.
                                // Consume the event to prevent focus escaping to other containers.
                                config.consumeUpAtTopEdge
                            }
                        }

                        KeyEvent.KEYCODE_DPAD_LEFT -> handleDpadLeft(v)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> handleDpadRight(v)
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            markVerticalNav(View.FOCUS_DOWN)
                            val pos = resolveAdapterPosition(v)
                            if (pos != null) {
                                rememberLastKnownFocus(position = pos)
                                handleDpadDown(v, pos)
                            } else {
                                handleDpadDownFallback(v)
                            }
                        }

                        else -> false
                    }
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                maybeProtectFocusOnChildDetach(view)
                view.setOnKeyListener(null)
                if (config.enableCenterLongPressToLongClick) {
                    view.setTag(R.id.tag_long_press_handled, false)
                }
            }
        }

    fun install() {
        if (installed) return
        installed = true
        if (originalOverScrollMode == null) {
            originalOverScrollMode = recyclerView.overScrollMode
        }
        recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
        ensureDefaultFocusHighlightSuppressionHook()
        recyclerView.setOnKeyListener(recyclerKeyListener)
        recyclerView.addOnChildAttachStateChangeListener(childListener)
        // Ensure already-attached children are covered (listener only fires for future attaches).
        for (i in 0 until recyclerView.childCount) {
            childListener.onChildViewAttachedToWindow(recyclerView.getChildAt(i))
        }
    }

    fun release() {
        if (!installed) return
        installed = false
        unparkFocusInRecyclerViewIfNeeded()
        removeDefaultFocusHighlightSuppressionHook()
        originalOverScrollMode?.let { mode ->
            if (recyclerView.overScrollMode == View.OVER_SCROLL_NEVER) {
                recyclerView.overScrollMode = mode
            }
        }
        originalOverScrollMode = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            originalDefaultFocusHighlightEnabled?.let { enabled ->
                if (didSuppressDefaultFocusHighlight && !recyclerView.defaultFocusHighlightEnabled) {
                    recyclerView.defaultFocusHighlightEnabled = enabled
                }
            }
        }
        originalDefaultFocusHighlightEnabled = null
        didSuppressDefaultFocusHighlight = false
        detachFocusRestoreToken++
        clearPendingFocusAfterLoadMore()
        recyclerView.removeOnChildAttachStateChangeListener(childListener)
        recyclerView.setOnKeyListener(null)
        for (i in 0 until recyclerView.childCount) {
            childListener.onChildViewDetachedFromWindow(recyclerView.getChildAt(i))
        }
    }

    fun clearPendingFocusAfterLoadMore() {
        pendingFocusAfterLoadMoreAnchorPos = RecyclerView.NO_POSITION
        pendingFocusAfterLoadMoreTargetPos = RecyclerView.NO_POSITION
        unparkFocusInRecyclerViewIfNeeded()
    }

    /**
     * When a page is being refreshed, some implementations reset their adapter (e.g. submit(emptyList())
     * then submit(newItems)). If focus is currently on a child item, the framework can fall back to an
     * unrelated container (often the sidebar/avatar) during the brief detach/rebind window.
     *
     * This method proactively parks focus on this RecyclerView to keep focus inside the content area.
     *
     * Notes:
     * - This will NOT steal focus from other containers (only works when focus is already inside this
     *   RecyclerView, or focus is currently null).
     * - Descendant focusability is restored immediately after focus is captured (no long-lived override).
     */
    fun parkFocusForDataSetReset() {
        // Reuse the same implementation as load-more focus parking, but don't keep the descendant
        // focusability overridden: we only need to capture focus for one frame.
        parkFocusInRecyclerViewForLoadMore(keepDescendantFocusabilityOverridden = false)
    }

    /**
     * Restore descendant focusability if it was temporarily overridden by [parkFocusForDataSetReset].
     *
     * Typically not necessary because [parkFocusForDataSetReset] restores immediately once focus is
     * captured, but keeping this public makes call-sites resilient if the internal behavior changes.
     */
    fun unparkFocusAfterDataSetReset() {
        unparkFocusInRecyclerViewIfNeeded()
    }

    fun consumePendingFocusAfterLoadMore(): Boolean {
        val anchorPos = pendingFocusAfterLoadMoreAnchorPos
        if (anchorPos == RecyclerView.NO_POSITION) return false

        if (!config.isEnabled()) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val adapter = recyclerView.adapter
        if (adapter == null) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val spanCount = spanCountForLayoutManager()
        if (spanCount == null) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val focused = recyclerView.rootView?.findFocus()
        if (focused != null && !FocusTreeUtils.isDescendantOf(focused, recyclerView)) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val itemCount = adapter.itemCount
        if (itemCount <= 0) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val existingTarget = pendingFocusAfterLoadMoreTargetPos
        if (existingTarget != RecyclerView.NO_POSITION) {
            // A focus jump is already in progress; don't restart it, but still validate state.
            if (existingTarget !in 0 until itemCount) {
                clearPendingFocusAfterLoadMore()
                return false
            }
            return true
        }

        val candidatePos =
            when {
                anchorPos + spanCount in 0 until itemCount -> anchorPos + spanCount
                anchorPos + 1 in 0 until itemCount -> anchorPos + 1
                else -> null
            }
        if (candidatePos == null) {
            // No "next row" available (e.g. load-more returned empty / end reached). If focus was
            // parked on RecyclerView, restore it back to the last known anchor item so the user
            // doesn't get stuck on an unfocused container.
            val fallbackPos = anchorPos.coerceIn(0, itemCount - 1)
            clearPendingFocusAfterLoadMore()
            scrollAndFocusAdapterPosition(fallbackPos, smooth = false)
            return true
        }

        pendingFocusAfterLoadMoreTargetPos = candidatePos
        // Keep focus "frozen" on RecyclerView while new children are being attached/recycled.
        parkFocusInRecyclerViewForLoadMore(keepDescendantFocusabilityOverridden = true)

        scrollAndFocusAdapterPosition(
            candidatePos,
            smooth = true,
            playDownSound = true,
            onFocused = {
                // Only clear when the intended target focus jump succeeds.
                if (pendingFocusAfterLoadMoreTargetPos == candidatePos) {
                    clearPendingFocusAfterLoadMore()
                }
            },
        )
        return true
    }

    private fun handleCenterLongPress(v: View, keyCode: Int, event: KeyEvent): Boolean {
        if (
            keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
            keyCode != KeyEvent.KEYCODE_ENTER &&
            keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            return false
        }

        val handled = (v.getTag(R.id.tag_long_press_handled) as? Boolean) == true
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
            if (!handled) {
                v.setTag(R.id.tag_long_press_handled, true)
                v.performLongClick()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && handled) {
            v.setTag(R.id.tag_long_press_handled, false)
            return true
        }
        return false
    }

    private fun handleRecyclerFocusedDpadDown(): Boolean {
        // When a load-more focus jump is pending, keep focus "parked" on RecyclerView until the
        // new items are appended and consumePendingFocusAfterLoadMore() moves focus to the target.
        if (pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION) {
            // Make sure focus stays on RecyclerView itself during adapter updates.
            parkFocusInRecyclerViewForLoadMore(keepDescendantFocusabilityOverridden = true)
            return true
        }

        val itemCount = recyclerView.adapter?.itemCount ?: 0
        if (itemCount <= 0) {
            // Data not ready yet: keep focus "parked" in the content area.
            return true
        }

        val candidatePos = (firstVisibleAdapterPosition() ?: 0).coerceIn(0, itemCount - 1)
        scrollAndFocusAdapterPosition(candidatePos, smooth = false, playDownSound = true)
        return true
    }

    private fun markVerticalNav(direction: Int) {
        lastVerticalNavAtMs = SystemClock.uptimeMillis()
        lastVerticalNavDirection = direction
    }

    private fun rememberLastKnownFocus(position: Int) {
        lastKnownFocusedAdapterPos = position
        val spanCount = spanCountForLayoutManager()
        if (spanCount == null || spanCount <= 0) {
            lastKnownFocusedSpanIndex = null
            return
        }
        val lm = recyclerView.layoutManager
        lastKnownFocusedSpanIndex =
            when (lm) {
                is GridLayoutManager -> lm.spanSizeLookup.getSpanIndex(position, spanCount)
                else -> position % spanCount
            }
    }

    private fun maybeProtectFocusOnChildDetach(detachedChild: View) {
        if (!installed) return
        if (!recyclerView.isAttachedToWindow) return
        if (!config.isEnabled()) return

        val hasPendingLoadMore = pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION
        if (!hasPendingLoadMore) {
            // Only guard during active vertical navigation; otherwise background updates should not
            // steal focus or re-focus content unexpectedly.
            val now = SystemClock.uptimeMillis()
            if (now - lastVerticalNavAtMs > focusProtectWindowMs) return
        }

        val focused = recyclerView.rootView?.findFocus()
        val focusWasInThisRecycler = focused != null && FocusTreeUtils.isDescendantOf(focused, recyclerView)
        if (!focusWasInThisRecycler && focused != null) return

        val detachingContainedFocus =
            focused != null && FocusTreeUtils.isDescendantOf(focused, detachedChild)
        if (!detachingContainedFocus && focused != null) return

        // Park focus on RecyclerView so the framework won't fall back to an unrelated container
        // (e.g. the left sidebar) for a brief frame while children are being recycled.
        parkFocusInRecyclerViewForLoadMore(keepDescendantFocusabilityOverridden = hasPendingLoadMore)

        val token = ++detachFocusRestoreToken
        recyclerView.postIfAlive(
            isAlive = { installed && recyclerView.isAttachedToWindow && config.isEnabled() && detachFocusRestoreToken == token },
        ) {
            // If a load-more focus jump is queued, keep focus parked until new items are appended.
            if (pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION) return@postIfAlive
            restoreFocusAfterDetach()
        }
    }

    private fun restoreFocusAfterDetach(): Boolean {
        val adapter = recyclerView.adapter ?: return false
        val itemCount = adapter.itemCount
        if (itemCount <= 0) return false

        val focused = recyclerView.rootView?.findFocus()
        if (focused != null && focused !== recyclerView && FocusTreeUtils.isDescendantOf(focused, recyclerView)) {
            return true
        }

        val firstVisible = firstVisibleAdapterPosition() ?: return false
        val lastVisible = lastVisibleAdapterPosition() ?: firstVisible
        if (firstVisible !in 0 until itemCount) return false

        val anchor =
            pendingFocusAfterLoadMoreAnchorPos
                .takeIf { it != RecyclerView.NO_POSITION }
                ?: lastKnownFocusedAdapterPos
                    .takeIf { it != RecyclerView.NO_POSITION }
                ?: firstVisible
        val spanCount = spanCountForLayoutManager()?.coerceAtLeast(1) ?: 1
        val desired =
            when (lastVerticalNavDirection) {
                View.FOCUS_UP -> anchor - spanCount
                else -> anchor + spanCount
            }.coerceIn(0, itemCount - 1)

        val candidateInVisibleRange = desired.coerceIn(firstVisible, lastVisible)

        val candidate =
            when (val lm = recyclerView.layoutManager) {
                is GridLayoutManager -> {
                    val targetSpan = lastKnownFocusedSpanIndex
                    if (targetSpan == null) {
                        candidateInVisibleRange
                    } else {
                        findVisiblePositionWithSpanIndex(
                            lm = lm,
                            firstVisible = firstVisible,
                            lastVisible = lastVisible,
                            targetSpanIndex = targetSpan,
                            direction = lastVerticalNavDirection,
                        ) ?: candidateInVisibleRange
                    }
                }

                else -> candidateInVisibleRange
            }

        return scrollAndFocusAdapterPosition(candidate, smooth = false)
    }

    private fun lastVisibleAdapterPosition(): Int? {
        val lm = recyclerView.layoutManager ?: return null
        val pos =
            when (lm) {
                is GridLayoutManager -> lm.findLastVisibleItemPosition()
                is LinearLayoutManager -> lm.findLastVisibleItemPosition()
                is StaggeredGridLayoutManager -> {
                    val spanCount = lm.spanCount.coerceAtLeast(1)
                    val last = IntArray(spanCount)
                    lm.findLastVisibleItemPositions(last)
                    var max = Int.MIN_VALUE
                    for (p in last) {
                        if (p != RecyclerView.NO_POSITION && p > max) max = p
                    }
                    if (max == Int.MIN_VALUE) RecyclerView.NO_POSITION else max
                }

                else -> RecyclerView.NO_POSITION
            }
        return pos.takeIf { it != RecyclerView.NO_POSITION }
    }

    private fun findVisiblePositionWithSpanIndex(
        lm: GridLayoutManager,
        firstVisible: Int,
        lastVisible: Int,
        targetSpanIndex: Int,
        direction: Int,
    ): Int? {
        val spanCount = lm.spanCount.coerceAtLeast(1)
        val lookup = lm.spanSizeLookup
        return if (direction == View.FOCUS_UP) {
            for (pos in lastVisible downTo firstVisible) {
                if (lookup.getSpanIndex(pos, spanCount) == targetSpanIndex) return pos
            }
            null
        } else {
            for (pos in firstVisible..lastVisible) {
                if (lookup.getSpanIndex(pos, spanCount) == targetSpanIndex) return pos
            }
            null
        }
    }

    private fun resolveAdapterPosition(view: View): Int? {
        val rootItem = recyclerView.findContainingItemView(view) ?: view
        val holder = recyclerView.findContainingViewHolder(rootItem) ?: return null

        return (
            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                ?: holder.absoluteAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                ?: holder.layoutPosition.takeIf { it != RecyclerView.NO_POSITION }
                ?: recyclerView.getChildLayoutPosition(rootItem).takeIf { it != RecyclerView.NO_POSITION }
        )
    }

    private fun firstVisibleAdapterPosition(): Int? {
        val lm = recyclerView.layoutManager ?: return null
        val pos =
            when (lm) {
                is GridLayoutManager -> lm.findFirstVisibleItemPosition()
                is LinearLayoutManager -> lm.findFirstVisibleItemPosition()
                is StaggeredGridLayoutManager -> {
                    val spanCount = lm.spanCount.coerceAtLeast(1)
                    val first = IntArray(spanCount)
                    lm.findFirstVisibleItemPositions(first)
                    var min = Int.MAX_VALUE
                    for (p in first) {
                        if (p != RecyclerView.NO_POSITION && p < min) min = p
                    }
                    if (min == Int.MAX_VALUE) RecyclerView.NO_POSITION else min
                }

                else -> RecyclerView.NO_POSITION
            }
        return pos.takeIf { it != RecyclerView.NO_POSITION }
    }

    private fun handleDpadUp(position: Int): Boolean {
        if (!isInTopRowAtTop(position)) return false
        val handled = callbacks.onTopEdge()
        return if (config.consumeUpAtTopEdge) true else handled
    }

    private fun handleDpadLeft(itemView: View): Boolean {
        val rootItem = recyclerView.findContainingItemView(itemView) ?: itemView
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, rootItem, View.FOCUS_LEFT)
        if (next != null && FocusTreeUtils.isDescendantOf(next, recyclerView)) return false
        return callbacks.onLeftEdge()
    }

    private fun handleDpadRight(itemView: View): Boolean {
        val rootItem = recyclerView.findContainingItemView(itemView) ?: itemView
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, rootItem, View.FOCUS_RIGHT)
        if (next != null && FocusTreeUtils.isDescendantOf(next, recyclerView)) return false
        if (!config.consumeRightEdge) return false
        callbacks.onRightEdge()
        // No outflow on the right edge.
        return true
    }

    private fun handleDpadDown(itemView: View, position: Int): Boolean {
        val rootItem = recyclerView.findContainingItemView(itemView) ?: itemView
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, rootItem, View.FOCUS_DOWN)
        if (next != null && FocusTreeUtils.isDescendantOf(next, recyclerView)) {
            // Consume even when an in-grid next focus exists. Letting the system handle the event
            // can occasionally pick a "better" candidate outside the RecyclerView (e.g. sidebar),
            // especially during layout/adapter updates while the user is holding DPAD_DOWN.
            requestDownFocus(next)
            return true
        }

        if (recyclerView.canScrollVertically(1)) {
            val dy = (rootItem.height * config.scrollOnDownEdgeFactor).toInt().coerceAtLeast(1)
            recyclerView.scrollBy(0, dy)
            val adapter = recyclerView.adapter
            val itemCount = adapter?.itemCount ?: 0
            val spanCount = spanCountForLayoutManager()
            val candidatePos =
                if (itemCount <= 0 || spanCount == null) {
                    null
                } else {
                    when {
                        position + spanCount in 0 until itemCount -> position + spanCount
                        position + 1 in 0 until itemCount -> position + 1
                        else -> null
                    }
                }
            recyclerView.postIfAlive(
                isAlive = { installed && recyclerView.isAttachedToWindow && config.isEnabled() },
            ) {
                if (tryFocusNextDownFromCurrent()) return@postIfAlive
                if (candidatePos != null) focusAdapterPosition(candidatePos)
            }
            return true
        }

        if (callbacks.canLoadMore()) {
            pendingFocusAfterLoadMoreAnchorPos = position
            pendingFocusAfterLoadMoreTargetPos = RecyclerView.NO_POSITION
            callbacks.loadMore()
            // Freeze focus on RecyclerView until the new page is appended and we can move focus to
            // the intended "next row" item. This prevents framework fallback focus (e.g. sidebar)
            // during detach/attach caused by adapter updates.
            parkFocusInRecyclerViewForLoadMore(keepDescendantFocusabilityOverridden = true)
        }
        // Always consume at the bottom edge to avoid focus escaping to other containers.
        return true
    }

    private fun handleDpadDownFallback(itemView: View): Boolean {
        val rootItem = recyclerView.findContainingItemView(itemView) ?: itemView
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, rootItem, View.FOCUS_DOWN)
        if (next != null && FocusTreeUtils.isDescendantOf(next, recyclerView)) {
            requestDownFocus(next)
            return true
        }

        if (recyclerView.canScrollVertically(1)) {
            val base =
                rootItem.height
                    .takeIf { it > 0 }
                    ?: (recyclerView.height.takeIf { it > 0 }?.div(2) ?: 1)
            val dy = (base * config.scrollOnDownEdgeFactor).toInt().coerceAtLeast(1)
            recyclerView.scrollBy(0, dy)
            recyclerView.postIfAlive(
                isAlive = { installed && recyclerView.isAttachedToWindow && config.isEnabled() },
            ) {
                tryFocusNextDownFromCurrent()
            }
            return true
        }

        if (callbacks.canLoadMore()) {
            val adapter = recyclerView.adapter
            val itemCount = adapter?.itemCount ?: 0
            val fallbackAnchor =
                when {
                    lastKnownFocusedAdapterPos in 0 until itemCount -> lastKnownFocusedAdapterPos
                    (lastVisibleAdapterPosition() ?: RecyclerView.NO_POSITION) in 0 until itemCount -> lastVisibleAdapterPosition()!!
                    (firstVisibleAdapterPosition() ?: RecyclerView.NO_POSITION) in 0 until itemCount -> firstVisibleAdapterPosition()!!
                    itemCount > 0 -> itemCount - 1
                    else -> RecyclerView.NO_POSITION
                }
            if (fallbackAnchor != RecyclerView.NO_POSITION) {
                pendingFocusAfterLoadMoreAnchorPos = fallbackAnchor
                pendingFocusAfterLoadMoreTargetPos = RecyclerView.NO_POSITION
            }
            callbacks.loadMore()
            // The focused child can be transiently detached/rebound while data is being appended.
            // Parking focus on RecyclerView avoids a framework-level fallback focus target.
            parkFocusInRecyclerViewForLoadMore(keepDescendantFocusabilityOverridden = true)
        }
        // Always consume: the whole purpose of this fallback is preventing outflow to other containers.
        return true
    }

    private fun parkFocusInRecyclerViewForLoadMore(keepDescendantFocusabilityOverridden: Boolean = false) {
        if (!installed) return
        if (!recyclerView.isAttachedToWindow) return
        if (!config.isEnabled()) return

        // Only park focus when focus is already inside this RecyclerView (or null).
        // This controller should never steal focus from other containers (e.g. the sidebar).
        val focused = recyclerView.rootView?.findFocus()
        if (focused != null && focused !== recyclerView && !FocusTreeUtils.isDescendantOf(focused, recyclerView)) {
            return
        }

        suppressRecyclerDefaultFocusHighlight()

        if (focusParkedDescendantFocusability == null) {
            // Temporarily prefer focusing the RecyclerView itself over its descendants, so
            // requestFocus() doesn't immediately land back on a child that might be recycled.
            focusParkedDescendantFocusability = recyclerView.descendantFocusability
            recyclerView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }

        if (recyclerView.isFocused || recyclerView.requestFocus()) {
            if (!keepDescendantFocusabilityOverridden) {
                // Once focus is captured, immediately restore the original descendantFocusability.
                // Keeping it overridden can affect how focus enters this RecyclerView later (e.g. from tab → grid).
                unparkFocusInRecyclerViewIfNeeded()
            }
            return
        }

        // If requestFocus() fails (timing/layout), retry next frame while the override is active.
        recyclerView.postIfAlive(
            isAlive = { installed && recyclerView.isAttachedToWindow && config.isEnabled() },
        ) {
            // Don't steal focus back if the user already navigated elsewhere.
            val currentFocused = recyclerView.rootView?.findFocus()
            if (
                currentFocused != null &&
                currentFocused !== recyclerView &&
                !FocusTreeUtils.isDescendantOf(currentFocused, recyclerView)
            ) {
                restoreRecyclerDefaultFocusHighlightIfSuppressed()
                return@postIfAlive
            }

            suppressRecyclerDefaultFocusHighlight()
            if (!recyclerView.isFocused) {
                recyclerView.requestFocus()
            }
            if (!keepDescendantFocusabilityOverridden) {
                unparkFocusInRecyclerViewIfNeeded()
            }
        }
    }

    private fun unparkFocusInRecyclerViewIfNeeded() {
        val original = focusParkedDescendantFocusability ?: return
        focusParkedDescendantFocusability = null
        recyclerView.descendantFocusability = original
    }

    private fun suppressRecyclerDefaultFocusHighlight() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        if (originalDefaultFocusHighlightEnabled == null) {
            originalDefaultFocusHighlightEnabled = recyclerView.defaultFocusHighlightEnabled
        }

        if (recyclerView.defaultFocusHighlightEnabled) {
            recyclerView.defaultFocusHighlightEnabled = false
            didSuppressDefaultFocusHighlight = true
        }
    }

    private fun restoreRecyclerDefaultFocusHighlightIfSuppressed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!didSuppressDefaultFocusHighlight) return

        val original = originalDefaultFocusHighlightEnabled ?: return
        if (!recyclerView.defaultFocusHighlightEnabled) {
            recyclerView.defaultFocusHighlightEnabled = original
        }
        didSuppressDefaultFocusHighlight = false
    }

    private fun ensureDefaultFocusHighlightSuppressionHook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (focusHighlightListener != null) return

        if (originalDefaultFocusHighlightEnabled == null) {
            originalDefaultFocusHighlightEnabled = recyclerView.defaultFocusHighlightEnabled
        }

        val listener =
            ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
                if (!installed) return@OnGlobalFocusChangeListener
                if (newFocus === recyclerView) {
                    suppressRecyclerDefaultFocusHighlight()
                } else if (oldFocus === recyclerView) {
                    restoreRecyclerDefaultFocusHighlightIfSuppressed()
                }
            }
        focusHighlightListener = listener
        runCatching { recyclerView.viewTreeObserver.addOnGlobalFocusChangeListener(listener) }

        // If focus is already parked on RecyclerView when installing (edge case), suppress right away.
        if (recyclerView.isFocused) {
            suppressRecyclerDefaultFocusHighlight()
        }
    }

    private fun removeDefaultFocusHighlightSuppressionHook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val listener = focusHighlightListener
        focusHighlightListener = null
        if (listener != null) {
            runCatching { recyclerView.viewTreeObserver.removeOnGlobalFocusChangeListener(listener) }
        }
    }

    private fun tryFocusNextDownFromCurrent(): Boolean {
        if (!config.isEnabled()) return false
        val focused = recyclerView.findFocus() ?: return false
        if (!FocusTreeUtils.isDescendantOf(focused, recyclerView)) return false
        val itemView = recyclerView.findContainingItemView(focused) ?: return false
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, itemView, View.FOCUS_DOWN)
        if (next != null && FocusTreeUtils.isDescendantOf(next, recyclerView)) {
            requestDownFocus(next)
            return true
        }
        return false
    }

    private fun focusAdapterPosition(position: Int): Boolean {
        val adapter = recyclerView.adapter ?: return false
        val itemCount = adapter.itemCount
        if (position !in 0 until itemCount) return false

        return scrollAndFocusAdapterPosition(position, smooth = true, playDownSound = true)
    }

    private fun requestDownFocus(view: View): Boolean {
        val hadFocus = view.hasFocus()
        val focused = view.requestFocus(View.FOCUS_DOWN)
        if (focused && !hadFocus) {
            view.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN)
        }
        return focused
    }

    private fun scrollAndFocusAdapterPosition(
        position: Int,
        smooth: Boolean,
        playDownSound: Boolean = false,
        onFocused: (() -> Unit)? = null,
    ): Boolean {
        val adapter = recyclerView.adapter
        if (adapter == null) {
            maybeAbortPendingLoadMoreFocusRetry(position)
            return false
        }
        val itemCount = adapter.itemCount
        if (position !in 0 until itemCount) {
            maybeAbortPendingLoadMoreFocusRetry(position)
            return false
        }

        recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.let { itemView ->
            // Avoid requesting focus while the target view is completely off-screen.
            // RecyclerView will "jump" to make the focused view visible, which looks like a flash.
            if (isPartiallyVisibleInRecycler(itemView) && (if (playDownSound) requestDownFocus(itemView) else itemView.requestFocus())) {
                onFocused?.invoke()
                return true
            }
        }

        if (smooth) recyclerView.smoothScrollToPosition(position) else recyclerView.scrollToPosition(position)
        retryFocusAdapterPosition(position, attemptsLeft = focusRetryMaxAttempts, playDownSound = playDownSound, onFocused = onFocused)
        return true
    }

    private fun retryFocusAdapterPosition(position: Int, attemptsLeft: Int, playDownSound: Boolean, onFocused: (() -> Unit)? = null) {
        if (!isAliveForFocusJump()) {
            maybeAbortPendingLoadMoreFocusRetry(position)
            return
        }

        val adapter = recyclerView.adapter
        if (adapter == null) {
            maybeAbortPendingLoadMoreFocusRetry(position)
            return
        }
        val itemCount = adapter.itemCount
        if (position !in 0 until itemCount) {
            maybeAbortPendingLoadMoreFocusRetry(position)
            return
        }

        recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.let { itemView ->
            // Only request focus once the target is at least partially visible, otherwise RecyclerView
            // may perform an immediate scroll-to-visible (no smooth animation) which looks like a flash.
            if (isPartiallyVisibleInRecycler(itemView) && (if (playDownSound) requestDownFocus(itemView) else itemView.requestFocus())) {
                onFocused?.invoke()
                return
            }
        }

        if (attemptsLeft <= 0) {
            maybeAbortPendingLoadMoreFocusRetry(position)
            return
        }
        recyclerView.postDelayedIfAlive(
            delayMillis = focusRetryDelayMillis,
            isAlive = { isAliveForFocusJump() },
        ) {
            retryFocusAdapterPosition(position, attemptsLeft = attemptsLeft - 1, playDownSound = playDownSound, onFocused = onFocused)
        }
    }

    private fun maybeAbortPendingLoadMoreFocusRetry(position: Int) {
        if (pendingFocusAfterLoadMoreTargetPos != position) return
        if (pendingFocusAfterLoadMoreAnchorPos == RecyclerView.NO_POSITION) return
        // Give up the queued load-more focus jump: restore normal focusability so users can keep
        // navigating even if the target view never becomes focusable/visible.
        clearPendingFocusAfterLoadMore()
    }

    private fun isAliveForFocusJump(): Boolean {
        if (!installed) return false
        if (!recyclerView.isAttachedToWindow) return false
        if (!config.isEnabled()) return false
        val focused = recyclerView.rootView?.findFocus()
        if (focused != null && !FocusTreeUtils.isDescendantOf(focused, recyclerView)) return false
        return true
    }

    private fun isPartiallyVisibleInRecycler(itemView: View): Boolean {
        val parentH = recyclerView.height
        if (parentH <= 0) return false
        val parentTop = recyclerView.paddingTop
        val parentBottom = parentH - recyclerView.paddingBottom
        val childTop = itemView.top
        val childBottom = itemView.bottom
        return childBottom > parentTop && childTop < parentBottom
    }

    private fun spanCountForLayoutManager(): Int? {
        val lm = recyclerView.layoutManager
        return when (lm) {
            is GridLayoutManager -> lm.spanCount.coerceAtLeast(1)
            is LinearLayoutManager -> 1
            is StaggeredGridLayoutManager -> lm.spanCount.coerceAtLeast(1)
            else -> null
        }
    }

    private fun isInTopRowAtTop(position: Int): Boolean {
        // IMPORTANT: Don't rely solely on `RecyclerView.canScrollVertically(-1)` here.
        // On TV, focused items often scale up slightly; RecyclerView may scroll by a few pixels
        // to keep the scaled item fully visible, making `canScrollVertically(-1)` return true
        // even when the user is effectively on the first row. That causes DPAD_UP to escape to
        // other containers (e.g. sidebar) instead of focusing the header/tab.
        val lm = recyclerView.layoutManager ?: return false
        return when (lm) {
            is GridLayoutManager -> {
                val spanCount = lm.spanCount.coerceAtLeast(1)
                val groupIndex = lm.spanSizeLookup.getSpanGroupIndex(position, spanCount)
                if (groupIndex != 0) return false

                val firstVisible = lm.findFirstVisibleItemPosition()
                firstVisible == 0 || !recyclerView.canScrollVertically(-1)
            }

            is LinearLayoutManager -> {
                if (position != 0) return false
                val firstVisible = lm.findFirstVisibleItemPosition()
                firstVisible == 0 || !recyclerView.canScrollVertically(-1)
            }

            is StaggeredGridLayoutManager -> {
                val spanCount = lm.spanCount.coerceAtLeast(1)
                val first = IntArray(spanCount)
                lm.findFirstVisibleItemPositions(first)
                val nearTop = first.any { it == 0 } || !recyclerView.canScrollVertically(-1)
                nearTop && first.any { it == position }
            }

            else -> position == 0 && !recyclerView.canScrollVertically(-1)
        }
    }
}
