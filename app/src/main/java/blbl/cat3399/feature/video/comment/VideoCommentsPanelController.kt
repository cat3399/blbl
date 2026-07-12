package blbl.cat3399.feature.video.comment

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.note.NoteImageRepository
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.postIfAlive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class VideoCommentsPanelViews(
    val sortRow: View,
    val sortHot: TextView,
    val sortNew: TextView,
    val comments: RecyclerView,
    val thread: RecyclerView,
    val hint: TextView,
)

internal class VideoCommentsPanelController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val views: VideoCommentsPanelViews,
    private val oidProvider: () -> Long?,
    private val upMidProvider: () -> Long,
    private val imageViewer: VideoCommentImageViewerController?,
    private val isActive: () -> Boolean,
) {
    private val dataSource = VideoCommentDataSource(upMidProvider)
    private var commentSort: Int = VIDEO_COMMENT_SORT_HOT
    private var commentsFetchJob: Job? = null
    private var commentsFetchToken: Int = 0
    private val commentsState = VideoCommentRootPagingState()
    private val expandedRpids = HashSet<Long>()

    private var threadFetchJob: Job? = null
    private var threadFetchToken: Int = 0
    private val threadState = VideoCommentThreadPagingState()

    private var commentsDpadController: DpadGridController? = null
    private var threadDpadController: DpadGridController? = null

    init {
        installViews()
    }

    fun release() {
        cancelJobs()
        commentsDpadController?.release()
        commentsDpadController = null
        threadDpadController?.release()
        threadDpadController = null
        imageViewer?.close(restoreFocus = false)
    }

    fun resetForMedia() {
        cancelJobs()
        commentsDpadController?.clearPendingFocusAfterLoadMore()
        threadDpadController?.clearPendingFocusAfterLoadMore()
        commentsState.reset()
        (views.comments.adapter as? VideoCommentsAdapter)?.setItems(emptyList())
        threadState.reset()
        (views.thread.adapter as? VideoCommentsAdapter)?.setItems(emptyList())
        expandedRpids.clear()
        imageViewer?.close(restoreFocus = false)
        showRoot()
        views.hint.visibility = View.GONE
    }

    fun clearTransientUi() {
        imageViewer?.close(restoreFocus = false)
        expandedRpids.clear()
        (views.comments.adapter as? VideoCommentsAdapter)?.invalidateSizing()
        (views.thread.adapter as? VideoCommentsAdapter)?.invalidateSizing()
    }

    fun showRoot() {
        views.comments.visibility = View.VISIBLE
        views.thread.visibility = View.GONE
        views.sortRow.visibility = View.VISIBLE
        threadState.clearRoot()
        (views.comments.adapter as? VideoCommentsAdapter)?.invalidateSizing()
    }

    fun ensureLoaded() {
        val oid = oidProvider()?.takeIf { it > 0L }
        if (oid == null) {
            views.hint.text = context.getString(R.string.player_comment_no_aid)
            views.hint.visibility = View.VISIBLE
            return
        }
        if (commentsState.items.isNotEmpty()) return
        if (commentsState.totalCount == 0) {
            views.hint.text = context.getString(R.string.player_comment_empty)
            views.hint.visibility = View.VISIBLE
            return
        }
        reloadComments()
    }

    fun focusRoot(targetRpid: Long? = null) {
        val safeRpid = targetRpid?.takeIf { it > 0L }
        views.comments.post {
            if (!isActive()) return@post
            if (safeRpid != null && focusCommentInRootList(rpid = safeRpid)) {
                return@post
            }

            val child = views.comments.getChildAt(0)
            if (child != null) {
                child.requestFocus()
                return@post
            }
            if (commentsState.items.isEmpty()) {
                focusSelectedSortChip()
            } else {
                views.comments.requestFocus()
            }
        }
    }

    fun focusThread() {
        views.thread.post {
            if (!isActive()) return@post
            val child = views.thread.getChildAt(0)
            (child ?: views.thread).requestFocus()
        }
    }

    fun isThreadVisible(): Boolean = views.thread.visibility == View.VISIBLE

    fun isImageViewerVisible(): Boolean = imageViewer?.isVisible() == true

    fun dispatchImageViewerKeyEvent(event: KeyEvent): Boolean =
        imageViewer?.dispatchKeyEvent(event) == true

    fun handleBack(): Boolean {
        if (imageViewer?.isVisible() == true) {
            imageViewer.close()
            return true
        }
        if (isThreadVisible()) {
            showRoot()
            focusRoot(targetRpid = threadState.consumeReturnFocusRpid())
            return true
        }
        return false
    }

    private fun installViews() {
        views.sortHot.setOnClickListener { applySort(VIDEO_COMMENT_SORT_HOT) }
        views.sortNew.setOnClickListener { applySort(VIDEO_COMMENT_SORT_NEW) }

        fun switchSortOnFocus(sort: Int) {
            if (!isActive()) return
            if (isThreadVisible()) return
            if (!BiliClient.prefs.tabSwitchFollowsFocus) return
            applySort(sort)
        }
        views.sortHot.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) switchSortOnFocus(VIDEO_COMMENT_SORT_HOT)
        }
        views.sortNew.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) switchSortOnFocus(VIDEO_COMMENT_SORT_NEW)
        }
        updateSortUi()

        val pool = RecyclerView.RecycledViewPool()
        views.comments.setRecycledViewPool(pool)
        views.thread.setRecycledViewPool(pool)
        views.comments.setHasFixedSize(true)
        views.thread.setHasFixedSize(true)
        views.comments.itemAnimator = null
        views.thread.itemAnimator = null

        val commentsAdapter =
            VideoCommentsAdapter(
                expandedRpids = expandedRpids,
                onClick = { item -> onRootCommentClick(item) },
                onTouchClick = { item -> onRootCommentTouchClick(item) },
                onPictureClick = { item, index -> openItemPictures(item, index, requireThreadVisible = false) },
                onLongClick = { item -> onRootCommentLongClick(item) },
            )
        views.comments.adapter = commentsAdapter
        views.comments.layoutManager = LinearLayoutManager(context)
        views.comments.clearOnScrollListeners()
        views.comments.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (!isActive() || isThreadVisible()) return
                    if (commentsFetchJob?.isActive == true || commentsState.endReached) return
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = commentsAdapter.itemCount
                    if (total <= 0) return
                    if (total - lastVisible - 1 <= 6) {
                        loadMoreComments()
                    }
                }
            },
        )

        val threadAdapter =
            VideoCommentsAdapter(
                expandedRpids = expandedRpids,
                onClick = { item -> onThreadCommentClick(item) },
                onPictureClick = { item, index -> openItemPictures(item, index, requireThreadVisible = true) },
            )
        views.thread.adapter = threadAdapter
        views.thread.layoutManager = LinearLayoutManager(context)
        views.thread.clearOnScrollListeners()
        views.thread.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (!isActive() || !isThreadVisible()) return
                    if (threadFetchJob?.isActive == true || threadState.endReached) return
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = threadAdapter.itemCount
                    if (total <= 0) return
                    if (total - lastVisible - 1 <= 6) {
                        loadMoreThread()
                    }
                }
            },
        )

        commentsDpadController =
            DpadGridController(
                recyclerView = views.comments,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            focusSelectedSortChip()
                            return true
                        }

                        override fun onLeftEdge(): Boolean = false

                        override fun onRightEdge() {}

                        override fun canLoadMore(): Boolean = !commentsState.endReached

                        override fun loadMore() {
                            loadMoreComments()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { isActive() && !isThreadVisible() },
                        enableCenterLongPressToLongClick = true,
                    ),
            ).also { it.install() }

        threadDpadController =
            DpadGridController(
                recyclerView = views.thread,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean = false

                        override fun onLeftEdge(): Boolean = false

                        override fun onRightEdge() {}

                        override fun canLoadMore(): Boolean = !threadState.endReached

                        override fun loadMore() {
                            loadMoreThread()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { isActive() && isThreadVisible() },
                        enableCenterLongPressToLongClick = true,
                    ),
            ).also { it.install() }
    }

    private fun cancelJobs() {
        commentsFetchJob?.cancel()
        commentsFetchJob = null
        commentsFetchToken++
        threadFetchJob?.cancel()
        threadFetchJob = null
        threadFetchToken++
    }

    private fun onRootCommentClick(item: VideoCommentItem) {
        if (!isActive() || isThreadVisible()) return

        val hasPictures = item.pictures.isNotEmpty() || item.noteCvid > 0L
        if (hasPictures) {
            openItemPictures(item, startIndex = 0, requireThreadVisible = false)
            return
        }

        if (item.replyCount <= 0) {
            AppToast.show(context, context.getString(R.string.player_comment_thread_empty))
            return
        }
        openThread(rootRpid = item.rpid)
    }

    private fun onRootCommentTouchClick(item: VideoCommentItem) {
        if (!isActive() || isThreadVisible()) return
        if (item.replyCount <= 0) {
            AppToast.show(context, context.getString(R.string.player_comment_thread_empty))
            return
        }
        openThread(rootRpid = item.rpid)
    }

    private fun onRootCommentLongClick(item: VideoCommentItem): Boolean {
        if (!isActive() || isThreadVisible()) return false

        val hasPictures = item.pictures.isNotEmpty() || item.noteCvid > 0L
        if (!hasPictures) return false

        if (item.replyCount <= 0) {
            AppToast.show(context, context.getString(R.string.player_comment_thread_empty))
            return true
        }
        openThread(rootRpid = item.rpid)
        return true
    }

    private fun onThreadCommentClick(item: VideoCommentItem) {
        if (!isActive() || !isThreadVisible()) return
        val hasPictures = item.pictures.isNotEmpty() || item.noteCvid > 0L
        if (!hasPictures) return
        openItemPictures(item, startIndex = 0, requireThreadVisible = true)
    }

    private fun openItemPictures(
        item: VideoCommentItem,
        startIndex: Int,
        requireThreadVisible: Boolean,
    ) {
        val viewer = imageViewer ?: return
        if (item.pictures.isNotEmpty()) {
            viewer.open(urls = item.pictures, startIndex = startIndex)
            return
        }
        AppToast.show(context, "加载图片中…")
        NoteImageRepository.load(item.noteCvid) { urls ->
            if (!isActive()) return@load
            if (requireThreadVisible != isThreadVisible()) return@load
            if (urls.isEmpty()) return@load
            viewer.open(urls = urls, startIndex = startIndex)
        }
    }

    private fun openThread(rootRpid: Long) {
        val safeRoot = rootRpid.takeIf { it > 0L } ?: return
        threadState.open(safeRoot)
        views.comments.visibility = View.GONE
        views.thread.visibility = View.VISIBLE
        views.sortRow.visibility = View.GONE
        (views.thread.adapter as? VideoCommentsAdapter)?.invalidateSizing()
        reloadThread()
        focusThread()
    }

    private fun focusSelectedSortChip() {
        val target =
            when (commentSort) {
                VIDEO_COMMENT_SORT_NEW -> views.sortNew
                else -> views.sortHot
            }
        target.requestFocus()
    }

    private fun focusCommentInRootList(rpid: Long): Boolean {
        val targetPos = commentsState.items.indexOfFirst { it.rpid == rpid }
        if (targetPos !in commentsState.items.indices) return false

        val direct = views.comments.findViewHolderForAdapterPosition(targetPos)?.itemView
        if (direct != null) {
            direct.requestFocus()
            return true
        }

        views.comments.scrollToPosition(targetPos)
        views.comments.post {
            views.comments.findViewHolderForAdapterPosition(targetPos)?.itemView?.requestFocus()
        }
        return true
    }

    private fun applySort(sort: Int) {
        if (commentSort == sort) return
        commentSort = sort
        updateSortUi()
        if (isActive() && !isThreadVisible()) {
            reloadComments()
        }
    }

    private fun updateSortUi() {
        val selected = ContextCompat.getColor(context, R.color.blbl_text)
        val unselected = ContextCompat.getColor(context, R.color.blbl_text_secondary)

        val hotSelected = commentSort == VIDEO_COMMENT_SORT_HOT
        val newSelected = commentSort == VIDEO_COMMENT_SORT_NEW

        views.sortHot.isSelected = hotSelected
        views.sortNew.isSelected = newSelected
        views.sortHot.setTextColor(if (hotSelected) selected else unselected)
        views.sortNew.setTextColor(if (newSelected) selected else unselected)
    }

    private fun reloadComments() {
        val oid = oidProvider()?.takeIf { it > 0L }
        if (oid == null) {
            AppToast.show(context, context.getString(R.string.player_comment_no_aid))
            return
        }

        commentsDpadController?.clearPendingFocusAfterLoadMore()
        commentsFetchJob?.cancel()
        commentsFetchJob = null
        val token = ++commentsFetchToken
        commentsState.beginReload()
        (views.comments.adapter as? VideoCommentsAdapter)?.setItems(emptyList())

        views.hint.text = context.getString(R.string.player_comment_loading)
        views.hint.visibility = View.VISIBLE

        commentsFetchJob =
            scope.launch {
                try {
                    val pageData = dataSource.loadRootPage(oid = oid, sort = commentSort, page = 1)
                    if (token != commentsFetchToken) return@launch
                    if (oidProvider()?.takeIf { it > 0L } != oid) return@launch

                    commentsState.replace(pageData)

                    (views.comments.adapter as? VideoCommentsAdapter)?.setItems(commentsState.items)
                    if (commentsState.items.isEmpty()) {
                        views.hint.text = context.getString(R.string.player_comment_empty)
                        views.hint.visibility = View.VISIBLE
                    } else {
                        views.hint.visibility = View.GONE
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    AppLog.w(LOG_TAG, "reloadComments failed oid=$oid sort=$commentSort", t)
                    AppToast.show(context, commentLoadErrorMessage(t))
                    if (commentsState.items.isEmpty()) {
                        views.hint.text = context.getString(R.string.player_comment_load_failed)
                        views.hint.visibility = View.VISIBLE
                    }
                } finally {
                    if (token == commentsFetchToken) commentsFetchJob = null
                }
            }
    }

    private fun loadMoreComments() {
        val oid = oidProvider()?.takeIf { it > 0L } ?: return
        if (commentsFetchJob?.isActive == true || commentsState.endReached) return
        val nextPage = commentsState.page + 1
        val token = ++commentsFetchToken

        commentsFetchJob =
            scope.launch {
                try {
                    val pageData =
                        dataSource.loadRootPage(
                            oid = oid,
                            sort = commentSort,
                            page = nextPage,
                            fallbackTotalCount = commentsState.totalCount,
                        )
                    if (token != commentsFetchToken) return@launch
                    if (oidProvider()?.takeIf { it > 0L } != oid) return@launch

                    if (!commentsState.append(nextPage = nextPage, pageData = pageData)) {
                        commentsDpadController?.clearPendingFocusAfterLoadMore()
                        return@launch
                    }
                    (views.comments.adapter as? VideoCommentsAdapter)?.appendItems(pageData.items)
                    views.comments.postIfAlive(isAlive = { isActive() }) {
                        commentsDpadController?.consumePendingFocusAfterLoadMore()
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    AppLog.w(LOG_TAG, "loadMoreComments failed oid=$oid sort=$commentSort page=$nextPage", t)
                    AppToast.show(context, commentLoadErrorMessage(t))
                } finally {
                    if (token == commentsFetchToken) commentsFetchJob = null
                }
            }
    }

    private fun reloadThread() {
        val oid = oidProvider()?.takeIf { it > 0L } ?: return
        val root = threadState.rootRpid.takeIf { it > 0L } ?: return

        threadDpadController?.clearPendingFocusAfterLoadMore()
        threadFetchJob?.cancel()
        threadFetchJob = null
        val token = ++threadFetchToken
        threadState.beginReload()

        views.hint.text = context.getString(R.string.player_comment_loading)
        views.hint.visibility = View.VISIBLE
        (views.thread.adapter as? VideoCommentsAdapter)?.setItems(emptyList())

        threadFetchJob =
            scope.launch {
                try {
                    val pageData = dataSource.loadThreadPage(oid = oid, rootRpid = root, page = 1)
                    if (token != threadFetchToken) return@launch
                    if (oidProvider()?.takeIf { it > 0L } != oid) return@launch
                    if (threadState.rootRpid != root) return@launch

                    threadState.replace(pageData)

                    (views.thread.adapter as? VideoCommentsAdapter)?.setItems(threadState.items)
                    views.thread.postIfAlive(isAlive = { isActive() }) {
                        threadDpadController?.consumePendingFocusAfterLoadMore()
                    }

                    if (threadState.items.isEmpty()) {
                        views.hint.text = context.getString(R.string.player_comment_thread_empty)
                        views.hint.visibility = View.VISIBLE
                    } else {
                        views.hint.visibility = View.GONE
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    AppToast.show(context, commentLoadErrorMessage(t))
                    if (threadState.items.isEmpty()) {
                        views.hint.text = context.getString(R.string.player_comment_load_failed)
                        views.hint.visibility = View.VISIBLE
                    }
                } finally {
                    if (token == threadFetchToken) threadFetchJob = null
                }
            }
    }

    private fun loadMoreThread() {
        val oid = oidProvider()?.takeIf { it > 0L } ?: return
        val root = threadState.rootRpid.takeIf { it > 0L } ?: return
        if (threadFetchJob?.isActive == true || threadState.endReached) return

        val nextPage = threadState.page + 1
        val token = ++threadFetchToken

        threadFetchJob =
            scope.launch {
                try {
                    val pageData =
                        dataSource.loadThreadPage(
                            oid = oid,
                            rootRpid = root,
                            page = nextPage,
                            fallbackTotalCount = threadState.totalCount,
                        )
                    if (token != threadFetchToken) return@launch
                    if (oidProvider()?.takeIf { it > 0L } != oid) return@launch
                    if (threadState.rootRpid != root) return@launch

                    if (!threadState.append(nextPage = nextPage, replies = pageData.replies)) {
                        threadDpadController?.clearPendingFocusAfterLoadMore()
                        return@launch
                    }

                    (views.thread.adapter as? VideoCommentsAdapter)?.appendItems(pageData.replies)
                    views.thread.postIfAlive(isAlive = { isActive() }) {
                        threadDpadController?.consumePendingFocusAfterLoadMore()
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    AppToast.show(context, commentLoadErrorMessage(t))
                } finally {
                    if (token == threadFetchToken) threadFetchJob = null
                }
            }
    }

    private fun commentLoadErrorMessage(t: Throwable): String {
        val api = t as? BiliApiException
        return api?.apiMessage?.takeIf { it.isNotBlank() }
            ?: (t.message ?: context.getString(R.string.player_comment_load_failed))
    }

    private companion object {
        private const val LOG_TAG = "VideoCommentsPanel"
    }
}
