package blbl.cat3399.core.ui

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.ceil

/**
 * Detects when a grid RecyclerView does not have enough items to fill every row that is visible in
 * the current viewport. The page still owns pagination state and loading; this class only decides
 * when the existing load-more path should be asked for more data.
 */
internal class GridViewportFillMonitor(
    private val recyclerView: RecyclerView,
    private val callbacks: Callbacks,
    private val config: Config = Config(),
) {
    interface Callbacks {
        fun canLoadMore(): Boolean
        fun loadMore()
    }

    data class Config(
        val isEnabled: () -> Boolean = { true },
        val maxVisibleRows: Int = 8,
    )

    private val itemBounds = Rect()
    private var installed: Boolean = false
    private var checkPosted: Boolean = false
    private var registeredAdapter: RecyclerView.Adapter<*>? = null

    private val layoutChangeListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleCheck()
        }

    private val childAttachListener =
        object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                scheduleCheck()
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        }

    private val adapterObserver =
        object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = scheduleCheck()

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = scheduleCheck()

            override fun onItemRangeChanged(
                positionStart: Int,
                itemCount: Int,
                payload: Any?,
            ) = scheduleCheck()

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = scheduleCheck()

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = scheduleCheck()

            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = scheduleCheck()
        }

    fun install() {
        if (installed) return
        installed = true
        recyclerView.addOnLayoutChangeListener(layoutChangeListener)
        recyclerView.addOnChildAttachStateChangeListener(childAttachListener)
        registerCurrentAdapterIfNeeded()
        scheduleCheck()
    }

    fun release() {
        if (!installed) return
        installed = false
        checkPosted = false
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener)
        recyclerView.removeOnChildAttachStateChangeListener(childAttachListener)
        registeredAdapter?.unregisterAdapterDataObserver(adapterObserver)
        registeredAdapter = null
    }

    fun scheduleCheck() {
        if (!installed || checkPosted) return
        checkPosted = true
        recyclerView.post {
            checkPosted = false
            if (!installed || !recyclerView.isAttachedToWindow) return@post
            maybeLoadMore()
        }
    }

    private fun maybeLoadMore() {
        registerCurrentAdapterIfNeeded()
        if (!config.isEnabled()) return
        if (!callbacks.canLoadMore()) return
        val requiredItemCount = requiredItemCountForViewport() ?: return
        val itemCount = recyclerView.adapter?.itemCount ?: return
        if (itemCount in 1 until requiredItemCount) {
            callbacks.loadMore()
        }
    }

    private fun registerCurrentAdapterIfNeeded() {
        val current = recyclerView.adapter
        if (registeredAdapter === current) return
        registeredAdapter?.unregisterAdapterDataObserver(adapterObserver)
        registeredAdapter = current
        current?.registerAdapterDataObserver(adapterObserver)
    }

    private fun requiredItemCountForViewport(): Int? {
        val adapter = recyclerView.adapter ?: return null
        val itemCount = adapter.itemCount
        if (itemCount <= 0) return null

        val lm = recyclerView.layoutManager as? GridLayoutManager ?: return null
        if (lm.orientation != RecyclerView.VERTICAL) return null
        val spanCount = lm.spanCount.coerceAtLeast(1)
        val firstVisible = lm.findFirstVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION } ?: return null
        val firstVisibleView = lm.findViewByPosition(firstVisible) ?: return null

        recyclerView.getDecoratedBoundsWithMargins(firstVisibleView, itemBounds)
        val rowHeight = itemBounds.height().takeIf { it > 0 } ?: return null
        val viewportBottom = recyclerView.height - recyclerView.paddingBottom
        val heightFromFirstRow = viewportBottom - itemBounds.top
        if (heightFromFirstRow <= 0) return null

        val visibleRows =
            ceil(heightFromFirstRow.toDouble() / rowHeight.toDouble())
                .toInt()
                .coerceAtLeast(1)
                .coerceAtMost(config.maxVisibleRows.coerceAtLeast(1))
        val rowStart = firstVisible - lm.spanSizeLookup.getSpanIndex(firstVisible, spanCount)
        return rowStart.coerceAtLeast(0) + visibleRows * spanCount
    }
}

internal fun RecyclerView.installGridViewportFillMonitor(
    isEnabled: () -> Boolean = { true },
    canLoadMore: () -> Boolean,
    loadMore: () -> Unit,
): GridViewportFillMonitor {
    val canLoadMoreCallback = canLoadMore
    val loadMoreCallback = loadMore
    return GridViewportFillMonitor(
        recyclerView = this,
        callbacks =
            object : GridViewportFillMonitor.Callbacks {
                override fun canLoadMore(): Boolean = canLoadMoreCallback()

                override fun loadMore() = loadMoreCallback()
            },
        config = GridViewportFillMonitor.Config(isEnabled = isEnabled),
    ).also { it.install() }
}
