package blbl.cat3399.feature.video.comment

import blbl.cat3399.core.note.NoteImage
import org.json.JSONArray
import org.json.JSONObject

internal const val VIDEO_COMMENT_TYPE_ARCHIVE = 1
internal const val VIDEO_COMMENT_SORT_NEW = 0
internal const val VIDEO_COMMENT_SORT_HOT = 1

internal fun parseVideoCommentReplyList(
    arr: JSONArray,
    oid: Long,
    canOpenThread: Boolean,
    upMid: Long,
): List<VideoCommentItem> {
    if (arr.length() <= 0) return emptyList()
    val out = ArrayList<VideoCommentItem>(arr.length())
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val item = parseVideoCommentReplyItem(obj, oid = oid, contextTag = null, canOpenThread = canOpenThread, upMid = upMid) ?: continue
        out.add(item)
    }
    return out
}

internal fun parseVideoCommentReplyItem(
    obj: JSONObject,
    oid: Long,
    contextTag: String?,
    canOpenThread: Boolean,
    upMid: Long,
): VideoCommentItem? {
    val rpid = obj.optLong("rpid", 0L).takeIf { it > 0L } ?: return null
    val member = obj.optJSONObject("member") ?: JSONObject()
    val mid =
        member.optString("mid", "").trim().toLongOrNull()
            ?: member.optLong("mid", 0L)
    val uname = member.optString("uname", "").trim()
    val avatar = member.optString("avatar", "").trim().takeIf { it.isNotBlank() }
    val content = obj.optJSONObject("content") ?: JSONObject()
    val message = content.optString("message", "").trim()
    val emotes = parseVideoCommentEmoteMap(content.optJSONObject("emote"))
    val pictures = parseVideoCommentPictures(content.optJSONArray("pictures"))
    val noteCvid =
        obj.optString("note_cvid_str", "").trim().toLongOrNull()
            ?: obj.optLong("note_cvid", 0L)
    val ctime = obj.optLong("ctime", 0L).takeIf { it > 0L } ?: 0L
    val like = obj.optLong("like", 0L).coerceAtLeast(0L)
    val replyCount = obj.optInt("count", 0).coerceAtLeast(0)
    val replyPreviews =
        if (canOpenThread && replyCount > 0) {
            val replies = obj.optJSONArray("replies") ?: JSONArray()
            parseVideoCommentReplyPreviewList(replies)
        } else {
            emptyList()
        }
    val isUp = upMid > 0L && mid == upMid

    return VideoCommentItem(
        key = rpid.toString(),
        rpid = rpid,
        oid = oid,
        type = VIDEO_COMMENT_TYPE_ARCHIVE,
        mid = mid,
        userName = uname,
        avatarUrl = avatar,
        message = message,
        emotes = emotes,
        pictures = pictures,
        noteCvid = noteCvid.takeIf { it > 0L } ?: 0L,
        ctimeSec = ctime,
        likeCount = like,
        replyCount = replyCount,
        replyPreviews = replyPreviews,
        contextTag = contextTag,
        canOpenThread = canOpenThread,
        isUp = isUp,
    )
}

internal data class VideoCommentReplyPreview(
    val userName: String,
    val message: String,
    val emotes: Map<String, String> = emptyMap(),
)

internal data class VideoCommentPicture(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
) {
    val dimensionRatio: String?
        get() = imageDimensionRatio(width = width, height = height)
}

internal fun imageDimensionRatio(width: Int?, height: Int?): String? {
    val safeWidth = width?.takeIf { it > 0 } ?: return null
    val safeHeight = height?.takeIf { it > 0 } ?: return null
    return "$safeWidth:$safeHeight"
}

internal fun NoteImage.toVideoCommentPicture(): VideoCommentPicture =
    VideoCommentPicture(
        url = url,
        width = width,
        height = height,
    )

internal data class VideoCommentItem(
    val key: String,
    val rpid: Long,
    val oid: Long,
    val type: Int,
    val mid: Long,
    val userName: String,
    val avatarUrl: String?,
    val message: String,
    val emotes: Map<String, String> = emptyMap(),
    val pictures: List<VideoCommentPicture> = emptyList(),
    val noteCvid: Long = 0L,
    val ctimeSec: Long,
    val likeCount: Long,
    val replyCount: Int,
    val replyPreviews: List<VideoCommentReplyPreview> = emptyList(),
    val contextTag: String? = null,
    val canOpenThread: Boolean = false,
    val isThreadRoot: Boolean = false,
    val isUp: Boolean = false,
)

private fun parseVideoCommentReplyPreviewList(arr: JSONArray, limit: Int = 2): List<VideoCommentReplyPreview> {
    if (arr.length() <= 0) return emptyList()
    val max = minOf(limit.coerceAtLeast(0), arr.length())
    if (max <= 0) return emptyList()
    val out = ArrayList<VideoCommentReplyPreview>(max)
    for (i in 0 until max) {
        val obj = arr.optJSONObject(i) ?: continue
        val member = obj.optJSONObject("member") ?: JSONObject()
        val uname = member.optString("uname", "").trim()
        val content = obj.optJSONObject("content") ?: JSONObject()
        val message = content.optString("message", "").trim()
        val emotes = parseVideoCommentEmoteMap(content.optJSONObject("emote"))
        if (uname.isBlank() && message.isBlank()) continue
        out.add(VideoCommentReplyPreview(userName = uname, message = message, emotes = emotes))
    }
    return out
}

private fun parseVideoCommentEmoteMap(obj: JSONObject?): Map<String, String> {
    if (obj == null || obj.length() <= 0) return emptyMap()
    val out = HashMap<String, String>(obj.length().coerceAtLeast(0))
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next().trim()
        if (key.isBlank()) continue
        val value = obj.optJSONObject(key) ?: continue
        val url = value.optString("url", "").trim()
        if (!url.startsWith("http")) continue
        out[key] = url
    }
    return out
}

private fun parseVideoCommentPictures(arr: JSONArray?): List<VideoCommentPicture> {
    if (arr == null || arr.length() <= 0) return emptyList()
    val out = ArrayList<VideoCommentPicture>(arr.length().coerceAtMost(6))
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val rawUrl = obj.optString("img_src", "").trim()
        val url =
            when {
                rawUrl.startsWith("http") -> rawUrl
                rawUrl.startsWith("//") -> "https:$rawUrl"
                else -> continue
            }
        out.add(
            VideoCommentPicture(
                url = url,
                width = obj.optInt("img_width", 0).takeIf { it > 0 },
                height = obj.optInt("img_height", 0).takeIf { it > 0 },
            ),
        )
        if (out.size >= 6) break
    }
    return out
}
