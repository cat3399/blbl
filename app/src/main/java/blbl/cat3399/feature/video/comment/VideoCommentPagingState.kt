package blbl.cat3399.feature.video.comment

internal class VideoCommentRootPagingState {
    var page: Int = 1
        private set
    var totalCount: Int = -1
        private set
    var endReached: Boolean = false
        private set

    val items: ArrayList<VideoCommentItem> = ArrayList()

    fun reset() {
        page = 1
        totalCount = -1
        endReached = false
        items.clear()
    }

    fun beginReload() {
        reset()
    }

    fun replace(pageData: VideoCommentRootPage) {
        totalCount = pageData.totalCount
        items.clear()
        items.addAll(pageData.items)
        endReached =
            pageData.items.isEmpty() ||
                (totalCount >= 0 && items.size >= totalCount)
    }

    fun append(nextPage: Int, pageData: VideoCommentRootPage): Boolean {
        totalCount = pageData.totalCount
        if (pageData.items.isEmpty()) {
            endReached = true
            return false
        }

        page = nextPage
        items.addAll(pageData.items)
        endReached = totalCount >= 0 && items.size >= totalCount
        return true
    }
}

internal class VideoCommentThreadPagingState {
    var rootRpid: Long = 0L
        private set
    var returnFocusRpid: Long = 0L
        private set
    var page: Int = 1
        private set
    var totalCount: Int = -1
        private set
    var endReached: Boolean = false
        private set

    val items: ArrayList<VideoCommentItem> = ArrayList()

    fun reset() {
        rootRpid = 0L
        returnFocusRpid = 0L
        page = 1
        totalCount = -1
        endReached = false
        items.clear()
    }

    fun open(rootRpid: Long) {
        this.rootRpid = rootRpid
        returnFocusRpid = rootRpid
    }

    fun clearRoot() {
        rootRpid = 0L
    }

    fun consumeReturnFocusRpid(): Long {
        val result = returnFocusRpid
        returnFocusRpid = 0L
        return result
    }

    fun beginReload() {
        page = 1
        totalCount = -1
        endReached = false
        items.clear()
    }

    fun replace(pageData: VideoCommentThreadPage) {
        totalCount = pageData.totalCount
        items.clear()
        pageData.rootItem?.let { items.add(it) }
        items.addAll(pageData.replies)

        val loadedReplies = loadedReplyCount()
        endReached =
            pageData.replies.isEmpty() ||
                (totalCount >= 0 && loadedReplies >= totalCount)
    }

    fun append(nextPage: Int, replies: List<VideoCommentItem>): Boolean {
        if (replies.isEmpty()) {
            endReached = true
            return false
        }

        page = nextPage
        items.addAll(replies)
        endReached = totalCount >= 0 && loadedReplyCount() >= totalCount
        return true
    }

    private fun loadedReplyCount(): Int {
        val rootCount = if (items.firstOrNull()?.key?.startsWith("thread_root:") == true) 1 else 0
        return (items.size - rootCount).coerceAtLeast(0)
    }
}
