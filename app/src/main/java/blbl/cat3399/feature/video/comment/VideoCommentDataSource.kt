package blbl.cat3399.feature.video.comment

import blbl.cat3399.core.api.BiliApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class VideoCommentRootPage(
    val totalCount: Int,
    val items: List<VideoCommentItem>,
)

internal data class VideoCommentThreadPage(
    val totalCount: Int,
    val rootItem: VideoCommentItem?,
    val replies: List<VideoCommentItem>,
)

internal class VideoCommentDataSource(
    private val upMidProvider: () -> Long,
) {
    suspend fun loadRootPage(
        oid: Long,
        sort: Int,
        page: Int,
        fallbackTotalCount: Int = -1,
    ): VideoCommentRootPage {
        val data =
            withContext(Dispatchers.IO) {
                BiliApi.commentPage(
                    type = VIDEO_COMMENT_TYPE_ARCHIVE,
                    oid = oid,
                    sort = sort,
                    pn = page,
                    ps = VIDEO_COMMENT_PAGE_SIZE,
                    noHot = 1,
                )
            }

        return withContext(Dispatchers.Default) {
            val pageObj = data.optJSONObject("page") ?: JSONObject()
            val totalCount = pageObj.optInt("count", fallbackTotalCount).takeIf { it >= 0 } ?: fallbackTotalCount
            val replies = data.optJSONArray("replies") ?: JSONArray()
            VideoCommentRootPage(
                totalCount = totalCount,
                items = parseVideoCommentReplyList(replies, oid = oid, canOpenThread = true, upMid = upMidProvider()),
            )
        }
    }

    suspend fun loadThreadPage(
        oid: Long,
        rootRpid: Long,
        page: Int,
        fallbackTotalCount: Int = -1,
    ): VideoCommentThreadPage {
        val data =
            withContext(Dispatchers.IO) {
                BiliApi.commentRepliesPage(
                    type = VIDEO_COMMENT_TYPE_ARCHIVE,
                    oid = oid,
                    rootRpid = rootRpid,
                    pn = page,
                    ps = VIDEO_COMMENT_PAGE_SIZE,
                )
            }

        return withContext(Dispatchers.Default) {
            val pageObj = data.optJSONObject("page") ?: JSONObject()
            val totalCount = pageObj.optInt("count", fallbackTotalCount).takeIf { it >= 0 } ?: fallbackTotalCount
            val rootItem =
                data.optJSONObject("root")
                    ?.let { parseVideoCommentReplyItem(it, oid = oid, contextTag = null, canOpenThread = false, upMid = upMidProvider()) }
                    ?.let { it.copy(key = "thread_root:${it.rpid}", isThreadRoot = true) }
            val replies = data.optJSONArray("replies") ?: JSONArray()
            val replyItems =
                parseVideoCommentReplyList(replies, oid = oid, canOpenThread = false, upMid = upMidProvider())
                    .map { it.copy(key = "thread:${it.rpid}") }

            VideoCommentThreadPage(
                totalCount = totalCount,
                rootItem = rootItem,
                replies = replyItems,
            )
        }
    }
}

private const val VIDEO_COMMENT_PAGE_SIZE = 20
