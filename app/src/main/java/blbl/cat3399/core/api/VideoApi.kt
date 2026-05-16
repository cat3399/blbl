package blbl.cat3399.core.api

import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.model.DanmakuUserFilter
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.WebCookieMaintainer
import blbl.cat3399.proto.dm.DmSegMobileReply
import blbl.cat3399.proto.dmview.DmWebViewReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.zip.CRC32

internal object VideoApi {
    private const val TAG = "BiliApi"
    private const val FEEDBACK_DISLIKE_URL = "https://api.bilibili.com/x/web-interface/feedback/dislike"
    private const val FEEDBACK_DISLIKE_APP_ID = "100"
    private const val FEEDBACK_DISLIKE_PLATFORM = "5"
    private const val FEEDBACK_DISLIKE_SPMID = "333.1007.0.0"
    private const val FEEDBACK_DISLIKE_GOTO = "av"
    private const val FEEDBACK_DISLIKE_PAGE = "1"
    private const val FEEDBACK_DISLIKE_DEFAULT_REASON_ID = 1

    private const val DM_FILTER_USER_CACHE_TTL_MS: Long = 10L * 60 * 1000 // 10min
    private val MID_HASH_REGEX = Regex("^[0-9a-fA-F]{1,8}$")
    private val MID_REGEX = Regex("^\\d{1,20}$")

    private data class DmFilterUserCache(
        val mid: Long,
        val fetchedAtMs: Long,
        val filter: DanmakuUserFilter,
    )

    private data class FeedbackDislikePayload(
        val aid: Long,
        val ownerMid: Long,
        val trackId: String?,
        val reasonId: Int,
    )

    @Volatile
    private var dmFilterUserCache: DmFilterUserCache? = null

    internal interface JsonObj {
        fun optString(name: String, fallback: String): String

        fun optLong(name: String): Long

        fun optLong(name: String, fallback: Long): Long

        fun optInt(name: String, fallback: Int): Int

        fun optBoolean(name: String, fallback: Boolean): Boolean

        fun optJSONObject(name: String): JsonObj?
    }

    private class OrgJsonObj(
        private val obj: JSONObject,
    ) : JsonObj {
        override fun optString(name: String, fallback: String): String = obj.optString(name, fallback)

        override fun optLong(name: String): Long = obj.optLong(name)

        override fun optLong(name: String, fallback: Long): Long = obj.optLong(name, fallback)

        override fun optInt(name: String, fallback: Int): Int = obj.optInt(name, fallback)

        override fun optBoolean(name: String, fallback: Boolean): Boolean = obj.optBoolean(name, fallback)

        override fun optJSONObject(name: String): JsonObj? {
            val nested = obj.optJSONObject(name) ?: return null
            return OrgJsonObj(nested)
        }
    }

    suspend fun toViewList(): List<VideoCard> {
        val url = "https://api.bilibili.com/x/v2/history/toview"
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        return withContext(Dispatchers.Default) { parseVideoCards(list) }
    }

    suspend fun toViewAdd(
        bvid: String? = null,
        aid: Long? = null,
    ) {
        val safeBvid = bvid?.trim().orEmpty()
        val safeAid = aid?.takeIf { it > 0L }
        if (safeBvid.isBlank() && safeAid == null) throw BiliApiException(apiCode = -400, apiMessage = "missing_video_id")

        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")

        val url = "https://api.bilibili.com/x/v2/history/toview/add"
        val form =
            buildMap {
                if (safeBvid.isNotBlank()) put("bvid", safeBvid) else put("aid", safeAid.toString())
                put("csrf", csrf)
            }
        val json =
            BiliClient.postFormJson(
                url,
                form = form,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    suspend fun toViewDelete(aid: Long) {
        val safeAid = aid.takeIf { it > 0L } ?: throw BiliApiException(apiCode = -400, apiMessage = "missing_video_id")
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")

        val url = "https://api.bilibili.com/x/v2/history/toview/del"
        val json =
            BiliClient.postFormJson(
                url,
                form =
                    mapOf(
                        "aid" to safeAid.toString(),
                        "csrf" to csrf,
                    ),
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    suspend fun historyDelete(kid: String) {
        val safeKid = kid.trim().takeIf { it.isNotBlank() } ?: throw BiliApiException(apiCode = -400, apiMessage = "missing_history_kid")
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")

        val url = "https://api.bilibili.com/x/v2/history/delete"
        val json =
            BiliClient.postFormJson(
                url,
                form =
                    mapOf(
                        "kid" to safeKid,
                        "csrf" to csrf,
                    ),
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    suspend fun feedbackDislike(card: VideoCard) {
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")

        val payload = resolveFeedbackDislikePayload(card)
        val keys = BiliClient.ensureWbiKeys()
        val url = BiliClient.signedWbiUrlAbsolute(FEEDBACK_DISLIKE_URL, emptyMap(), keys)
        val form =
            linkedMapOf(
                "app_id" to FEEDBACK_DISLIKE_APP_ID,
                "platform" to FEEDBACK_DISLIKE_PLATFORM,
                "from_spmid" to "",
                "spmid" to FEEDBACK_DISLIKE_SPMID,
                "goto" to FEEDBACK_DISLIKE_GOTO,
                "id" to payload.aid.toString(),
                "mid" to payload.ownerMid.toString(),
                "track_id" to payload.trackId.orEmpty(),
                "feedback_page" to FEEDBACK_DISLIKE_PAGE,
                "reason_id" to payload.reasonId.toString(),
                "csrf" to csrf,
            )
        val json =
            BiliClient.postFormJson(
                url,
                form = form,
                headers = webFeedbackHeaders(targetUrl = FEEDBACK_DISLIKE_URL),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    suspend fun spaceLikeVideoList(vmid: Long): List<VideoCard> {
        val mid = vmid.takeIf { it > 0 } ?: error("space_like_video_invalid_vmid")
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/space/like/video",
                mapOf("vmid" to mid.toString()),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val dataAny = json.opt("data")
        val list =
            when (dataAny) {
                is JSONObject -> dataAny.optJSONArray("list") ?: JSONArray()
                is JSONArray -> dataAny
                else -> JSONArray()
            }
        return withContext(Dispatchers.Default) { parseVideoCards(list) }
    }

    suspend fun commentPage(
        type: Int,
        oid: Long,
        sort: Int = 1,
        pn: Int = 1,
        ps: Int = 20,
        noHot: Int = 1,
    ): JSONObject {
        val safeType = type.takeIf { it > 0 } ?: error("type required")
        val safeOid = oid.takeIf { it > 0L } ?: error("oid required")
        val safePn = pn.coerceAtLeast(1)
        val safePs = ps.coerceIn(1, 20)
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/v2/reply",
                mapOf(
                    "type" to safeType.toString(),
                    "oid" to safeOid.toString(),
                    "sort" to sort.toString(),
                    "pn" to safePn.toString(),
                    "ps" to safePs.toString(),
                    "nohot" to noHot.toString(),
                ),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        return json.optJSONObject("data") ?: JSONObject()
    }

    suspend fun commentRepliesPage(
        type: Int,
        oid: Long,
        rootRpid: Long,
        pn: Int = 1,
        ps: Int = 20,
    ): JSONObject {
        val safeType = type.takeIf { it > 0 } ?: error("type required")
        val safeOid = oid.takeIf { it > 0L } ?: error("oid required")
        val safeRoot = rootRpid.takeIf { it > 0L } ?: error("root required")
        val safePn = pn.coerceAtLeast(1)
        val safePs = ps.coerceIn(1, 49)
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/v2/reply/reply",
                mapOf(
                    "type" to safeType.toString(),
                    "oid" to safeOid.toString(),
                    "root" to safeRoot.toString(),
                    "pn" to safePn.toString(),
                    "ps" to safePs.toString(),
                ),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        return json.optJSONObject("data") ?: JSONObject()
    }

    suspend fun favResourceDeal(
        rid: Long,
        addMediaIds: List<Long>,
        delMediaIds: List<Long>,
    ) {
        if (rid <= 0L) error("fav_resource_deal_invalid_rid")
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")
        if (addMediaIds.isEmpty() && delMediaIds.isEmpty()) return

        val url = "https://api.bilibili.com/x/v3/fav/resource/deal"
        val form =
            buildMap {
                put("rid", rid.toString())
                put("type", "2")
                put("csrf", csrf)
                put("platform", "web")
                put("add_media_ids", addMediaIds.distinct().joinToString(","))
                put("del_media_ids", delMediaIds.distinct().joinToString(","))
            }
        val json =
            BiliClient.postFormJson(
                url,
                form = form,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    suspend fun archiveLike(
        bvid: String? = null,
        aid: Long? = null,
        like: Boolean,
    ) {
        val safeBvid = bvid?.trim().orEmpty()
        val safeAid = aid?.takeIf { it > 0L }
        if (safeBvid.isBlank() && safeAid == null) throw BiliApiException(apiCode = -400, apiMessage = "missing_video_id")

        WebCookieMaintainer.ensureWebFingerprintCookies()
        WebCookieMaintainer.ensureBuvidActiveOncePerDay()
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")

        val url = "https://api.bilibili.com/x/web-interface/archive/like"
        val form =
            buildMap {
                if (safeBvid.isNotBlank()) put("bvid", safeBvid) else put("aid", safeAid.toString())
                put("like", if (like) "1" else "2")
                put("csrf", csrf)
            }
        val json =
            BiliClient.postFormJson(
                url,
                form = form,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    suspend fun archiveHasLike(
        bvid: String? = null,
        aid: Long? = null,
    ): Boolean {
        val safeBvid = bvid?.trim().orEmpty()
        val safeAid = aid?.takeIf { it > 0L }
        if (safeBvid.isBlank() && safeAid == null) throw BiliApiException(apiCode = -400, apiMessage = "missing_video_id")

        WebCookieMaintainer.ensureWebFingerprintCookies()

        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/web-interface/archive/has/like",
                buildMap {
                    if (safeBvid.isNotBlank()) put("bvid", safeBvid) else put("aid", safeAid.toString())
                },
            )
        val json =
            BiliClient.getJson(
                url,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }

        val any = json.opt("data")
        val value =
            when (any) {
                is Number -> any.toInt()
                is String -> any.trim().toIntOrNull() ?: 0
                else -> json.optInt("data", 0)
            }
        return value == 1
    }

    suspend fun archiveCoins(
        bvid: String? = null,
        aid: Long? = null,
    ): Int {
        val safeBvid = bvid?.trim().orEmpty()
        val safeAid = aid?.takeIf { it > 0L }
        if (safeBvid.isBlank() && safeAid == null) throw BiliApiException(apiCode = -400, apiMessage = "missing_video_id")

        WebCookieMaintainer.ensureWebFingerprintCookies()

        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/web-interface/archive/coins",
                buildMap {
                    if (safeBvid.isNotBlank()) put("bvid", safeBvid) else put("aid", safeAid.toString())
                },
            )
        val json =
            BiliClient.getJson(
                url,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }

        val data = json.optJSONObject("data") ?: JSONObject()
        return data.optInt("multiply", 0).coerceAtLeast(0)
    }

    suspend fun archiveFavoured(
        bvid: String? = null,
        aid: Long? = null,
    ): Boolean {
        val safeBvid = bvid?.trim().orEmpty()
        val safeAid = aid?.takeIf { it > 0L }
        val id =
            when {
                safeBvid.isNotBlank() -> safeBvid
                safeAid != null -> safeAid.toString()
                else -> throw BiliApiException(apiCode = -400, apiMessage = "missing_video_id")
            }

        WebCookieMaintainer.ensureWebFingerprintCookies()

        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/v2/fav/video/favoured",
                mapOf("aid" to id),
            )
        val json =
            BiliClient.getJson(
                url,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }

        val data = json.optJSONObject("data") ?: JSONObject()
        return data.optBoolean("favoured", false)
    }

    suspend fun coinAdd(
        bvid: String? = null,
        aid: Long? = null,
        multiply: Int = 1,
        selectLike: Boolean = false,
    ) {
        val safeBvid = bvid?.trim().orEmpty()
        val safeAid = aid?.takeIf { it > 0L }
        if (safeBvid.isBlank() && safeAid == null) throw BiliApiException(apiCode = -400, apiMessage = "missing_video_id")
        val mul = multiply.coerceIn(1, 2)

        WebCookieMaintainer.ensureWebFingerprintCookies()
        WebCookieMaintainer.ensureBuvidActiveOncePerDay()
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")

        val url = "https://api.bilibili.com/x/web-interface/coin/add"
        val form =
            buildMap {
                if (safeBvid.isNotBlank()) put("bvid", safeBvid) else put("aid", safeAid.toString())
                put("multiply", mul.toString())
                put("select_like", if (selectLike) "1" else "0")
                put("csrf", csrf)
            }
        val json =
            BiliClient.postFormJson(
                url,
                form = form,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    suspend fun historyReport(
        aid: Long,
        cid: Long,
        progressSec: Long,
        platform: String = "android",
    ) {
        if (aid <= 0L) error("history_report_invalid_aid")
        if (cid <= 0L) error("history_report_invalid_cid")
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")

        val url = "https://api.bilibili.com/x/v2/history/report"
        val form =
            buildMap {
                put("aid", aid.toString())
                put("cid", cid.toString())
                put("progress", progressSec.coerceAtLeast(0L).toString())
                put("platform", platform)
                put("csrf", csrf)
            }
        val json =
            BiliClient.postFormJson(
                url,
                form = form,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    suspend fun webHeartbeat(
        aid: Long? = null,
        bvid: String? = null,
        cid: Long? = null,
        epId: Long? = null,
        seasonId: Long? = null,
        playedTimeSec: Long,
        type: Int,
        subType: Int? = null,
        playType: Int = 0,
    ) {
        val safeAid = aid?.takeIf { it > 0 }
        val safeBvid = bvid?.trim()?.takeIf { it.isNotBlank() }
        val safeCid = cid?.takeIf { it > 0 }
        val safeEpId = epId?.takeIf { it > 0 }
        val safeSeasonId = seasonId?.takeIf { it > 0 }
        if (safeAid == null && safeBvid == null) error("heartbeat_missing_aid_bvid")
        if (safeCid == null) error("heartbeat_missing_cid")
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")

        val url = "https://api.bilibili.com/x/click-interface/web/heartbeat"
        val form =
            buildMap {
                safeAid?.let { put("aid", it.toString()) }
                safeBvid?.let { put("bvid", it) }
                put("cid", safeCid.toString())
                safeEpId?.let { put("epid", it.toString()) }
                safeSeasonId?.let { put("sid", it.toString()) }
                put("played_time", playedTimeSec.coerceAtLeast(0L).toString())
                put("type", type.toString())
                subType?.takeIf { it > 0 }?.let { put("sub_type", it.toString()) }
                put("dt", "2")
                put("play_type", playType.toString())
                put("csrf", csrf)
            }
        val json =
            BiliClient.postFormJson(
                url,
                form = form,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    suspend fun dmSeg(cid: Long, segmentIndex: Int): List<Danmaku> {
        try {
            return requestWithAnonymousFallback(
                requestName = "dmSeg",
                context = "cid=$cid seg=$segmentIndex",
                primary = { requestDmSeg(cid = cid, segmentIndex = segmentIndex, includeCookie = true) },
                fallback = { requestDmSeg(cid = cid, segmentIndex = segmentIndex, includeCookie = false) },
            )
        } catch (t: Throwable) {
            AppLog.w(TAG, "dmSeg failed cid=$cid seg=$segmentIndex", t)
            throw t
        }
    }

    suspend fun dmWebView(cid: Long, aid: Long? = null): BiliApi.DanmakuWebView {
        return requestWithAnonymousFallback(
            requestName = "dmWebView",
            context = "cid=$cid aid=${aid ?: -1}",
            primary = { requestDmWebView(cid = cid, aid = aid, includeCookie = true) },
            fallback = { requestDmWebView(cid = cid, aid = aid, includeCookie = false) },
        )
    }

    private suspend fun requestDmSeg(
        cid: Long,
        segmentIndex: Int,
        includeCookie: Boolean,
    ): List<Danmaku> {
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/v2/dm/web/seg.so",
                mapOf(
                    "type" to "1",
                    "oid" to cid.toString(),
                    "segment_index" to segmentIndex.toString(),
                ),
            )
        val bytes =
            BiliClient.getBytes(
                url,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = includeCookie),
                noCookies = true,
            )
        val reply = DmSegMobileReply.parseFrom(bytes)
        val list =
            reply.elemsList.mapNotNull { e ->
                val text = e.content ?: return@mapNotNull null
                Danmaku(
                    timeMs = e.progress,
                    mode = e.mode,
                    text = text,
                    color = e.color.toInt(),
                    fontSize = e.fontsize,
                    weight = e.weight,
                    midHash = e.midHash?.trim()?.takeIf { it.isNotBlank() },
                    dmid = e.id.takeIf { it > 0L },
                    attr = e.attr,
                )
            }
        AppLog.d(
            TAG,
            "dmSeg cid=$cid seg=$segmentIndex includeCookie=$includeCookie bytes=${bytes.size} size=${list.size} state=${reply.state}",
        )
        return list
    }

    private suspend fun requestDmWebView(
        cid: Long,
        aid: Long?,
        includeCookie: Boolean,
    ): BiliApi.DanmakuWebView {
        val params =
            mutableMapOf(
                "type" to "1",
                "oid" to cid.toString(),
            )
        if (aid != null && aid > 0) params["pid"] = aid.toString()
        val url = BiliClient.withQuery("https://api.bilibili.com/x/v2/dm/web/view", params)
        val bytes =
            BiliClient.getBytes(
                url,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = includeCookie),
                noCookies = true,
            )
        val reply = DmWebViewReply.parseFrom(bytes)

        val seg = reply.dmSge
        val segTotal = seg.total.coerceAtLeast(0).toInt()
        val pageSizeMs = seg.pageSize.coerceAtLeast(0)

        val setting =
            if (reply.hasDmSetting()) {
                val s = reply.dmSetting
                val aiLevel =
                    when (s.aiLevel) {
                        0 -> 3 // 0 表示默认等级（通常为 3）
                        else -> s.aiLevel.coerceIn(0, 10)
                    }
                BiliApi.DanmakuWebSetting(
                    dmSwitch = s.dmSwitch,
                    allowScroll = s.blockscroll,
                    allowTop = s.blocktop,
                    allowBottom = s.blockbottom,
                    allowColor = s.blockcolor,
                    allowSpecial = s.blockspecial,
                    aiEnabled = s.aiSwitch,
                    aiLevel = aiLevel,
                )
            } else {
                null
            }
        AppLog.d(
            TAG,
            "dmWebView cid=$cid aid=${aid ?: -1} includeCookie=$includeCookie bytes=${bytes.size} segTotal=$segTotal pageSizeMs=$pageSizeMs hasSetting=${setting != null}",
        )
        return BiliApi.DanmakuWebView(
            segmentTotal = segTotal,
            segmentPageSizeMs = pageSizeMs,
            count = reply.count,
            setting = setting,
        )
    }

    suspend fun dmFilterUser(forceRefresh: Boolean = false): DanmakuUserFilter {
        // Requires login (SESSDATA).
        val mid =
            BiliClient.cookies.getCookieValue("DedeUserID")
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: return DanmakuUserFilter.EMPTY

        if (!BiliClient.cookies.hasSessData()) return DanmakuUserFilter.EMPTY

        val now = System.currentTimeMillis()
        val cached = dmFilterUserCache
        if (!forceRefresh && cached != null && cached.mid == mid && now - cached.fetchedAtMs < DM_FILTER_USER_CACHE_TTL_MS) {
            return cached.filter
        }

        try {
            val fetched = fetchDmFilterUser()
            dmFilterUserCache = DmFilterUserCache(mid = mid, fetchedAtMs = now, filter = fetched)
            return fetched
        } catch (t: Throwable) {
            // -101: not logged in (cookie may be stale); do not apply stale filters.
            if ((t as? BiliApiException)?.apiCode == -101) {
                dmFilterUserCache = null
                return DanmakuUserFilter.EMPTY
            }
            AppLog.w(TAG, "dmFilterUser failed mid=$mid", t)
            return cached?.takeIf { it.mid == mid }?.filter ?: DanmakuUserFilter.EMPTY
        }
    }

    private suspend fun fetchDmFilterUser(): DanmakuUserFilter {
        val url = "https://api.bilibili.com/x/dm/filter/user"
        val json =
            BiliClient.getJson(
                url,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )

        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }

        val list = json.optJSONObject("data")?.optJSONArray("rule") ?: JSONArray()
        val keywords = ArrayList<String>(minOf(64, list.length()))
        val regexes = ArrayList<Regex>(minOf(32, list.length()))
        val blockedMidHashes = LinkedHashSet<String>()

        for (i in 0 until list.length()) {
            val obj = list.optJSONObject(i) ?: continue
            val type = obj.optInt("type", -1)
            val raw =
                obj.optString(
                    "filter",
                    obj.optString(
                        "filter_content",
                        obj.optString("content", ""),
                    ),
                ).trim()

            if (raw.isBlank()) continue

            when (type) {
                0 -> keywords.add(raw)

                1 -> {
                    val r = normalizeRegexRule(raw)
                    if (r == null) {
                        AppLog.w(TAG, "dmFilterUser bad regex: ${raw.take(120)}")
                        continue
                    }
                    regexes.add(r)
                }

                2 -> {
                    normalizeMidHashRule(raw)?.let { blockedMidHashes.add(it) }
                }
            }
        }

        return DanmakuUserFilter(
            keywords = keywords.distinct(),
            regexes = regexes.distinctBy { it.pattern },
            blockedUserMidHashes = blockedMidHashes,
        )
    }

    internal fun normalizeMidHashRule(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        if (MID_REGEX.matches(trimmed)) {
            val mid = trimmed.toLongOrNull()?.takeIf { it > 0L } ?: return null
            // For type=2 rules, Bilibili usually stores CRC32(mid) as a hex string.
            // Some values may lose leading zeros, resulting in < 8 chars (e.g. "dc0589").
            // If the server ever returns a numeric MID here, it should be much longer than 8 chars.
            return if (trimmed.length > 8) midHashOfMid(mid) else trimmed.lowercase(Locale.US).padStart(8, '0')
        }
        if (MID_HASH_REGEX.matches(trimmed)) return trimmed.lowercase(Locale.US).padStart(8, '0')
        return null
    }

    internal fun normalizeRegexRule(raw: String): Regex? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val literal = parseRegexLiteral(trimmed)
        val pattern = literal?.pattern ?: trimmed
        val options = literal?.options.orEmpty()

        return runCatching {
            if (options.isEmpty()) {
                Regex(pattern)
            } else {
                Regex(pattern, options)
            }
        }.getOrNull()
    }

    private data class RegexLiteral(
        val pattern: String,
        val options: Set<RegexOption>,
    )

    private fun parseRegexLiteral(raw: String): RegexLiteral? {
        // Bilibili web regex rules are usually in the form "/pattern/" (optionally with flags: "/pattern/im").
        if (!raw.startsWith("/")) return null
        val closingSlashIndex = findLastUnescapedSlash(raw) ?: return null
        if (closingSlashIndex <= 0) return null

        val pattern = raw.substring(1, closingSlashIndex)
        val flags = raw.substring(closingSlashIndex + 1)
        if (flags.isBlank()) return RegexLiteral(pattern = pattern, options = emptySet())

        val options = LinkedHashSet<RegexOption>()
        val ignored = StringBuilder()
        for (c in flags) {
            when (c) {
                'i' -> options.add(RegexOption.IGNORE_CASE)
                'm' -> options.add(RegexOption.MULTILINE)
                's' -> options.add(RegexOption.DOT_MATCHES_ALL)
                else -> if (!c.isWhitespace()) ignored.append(c)
            }
        }
        if (ignored.isNotEmpty()) {
            AppLog.w(TAG, "dmFilterUser ignored regex flags=${ignored} raw=${raw.take(120)}")
        }
        return RegexLiteral(pattern = pattern, options = options)
    }

    private fun findLastUnescapedSlash(raw: String): Int? {
        for (i in raw.length - 1 downTo 1) {
            if (raw[i] != '/') continue
            var backslashes = 0
            var j = i - 1
            while (j >= 0 && raw[j] == '\\') {
                backslashes++
                j--
            }
            if (backslashes % 2 == 0) return i
        }
        return null
    }

    private fun midHashOfMid(mid: Long): String {
        val crc = CRC32()
        crc.update(mid.toString().toByteArray(Charsets.UTF_8))
        return java.lang.Long.toHexString(crc.value).padStart(8, '0')
    }

    internal fun parseVideoCard(obj: JsonObj): VideoCard? {
        val bvid = obj.optString("bvid", "")
        if (bvid.isBlank()) return null
        val owner = obj.optJSONObject("owner")
        val stat = obj.optJSONObject("stat")
        val durationSec = obj.optInt("duration", BiliApi.parseDuration(obj.optString("duration_text", "0:00"))).coerceAtLeast(0)
        val rawProgressSec = obj.optLong("progress")
        val progressFinished = rawProgressSec < 0 || (durationSec > 0 && rawProgressSec >= durationSec.toLong())
        return VideoCard(
            bvid = bvid,
            cid = obj.optLong("cid").takeIf { it > 0 },
            aid = obj.optLong("aid").takeIf { it > 0 },
            title = obj.optString("title", ""),
            coverUrl = obj.optString("pic", obj.optString("cover", "")),
            durationSec = durationSec,
            ownerName = owner?.optString("name", "").orEmpty(),
            ownerFace = owner?.optString("face", "")?.takeIf { it.isNotBlank() },
            ownerMid = owner?.optLong("mid")?.takeIf { it > 0 },
            view =
                stat?.optLong("view")?.takeIf { it > 0 }
                    ?: stat?.optLong("play")?.takeIf { it > 0 },
            danmaku =
                stat?.optLong("danmaku")?.takeIf { it > 0 }
                    ?: stat?.optLong("dm")?.takeIf { it > 0 },
            pubDate = obj.optLong("pubdate").takeIf { it > 0 },
            pubDateText = null,
            progressSec = rawProgressSec.takeIf { it > 0 && !progressFinished },
            progressFinished = progressFinished,
            trackId = obj.optString("track_id", obj.optString("trackid", "")).trim().takeIf { it.isNotBlank() },
        )
    }

    internal fun parseVideoCards(arr: JSONArray): List<VideoCard> {
        val out = ArrayList<VideoCard>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parseVideoCard(OrgJsonObj(obj))?.let { out.add(it) }
        }
        return out
    }

    private fun webFeedbackHeaders(targetUrl: String): Map<String, String> {
        return buildMap {
            put("Referer", "https://www.bilibili.com/")
            val cookie = BiliClient.cookies.cookieHeaderFor(targetUrl).orEmpty().trim()
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
    }

    private suspend fun resolveFeedbackDislikePayload(card: VideoCard): FeedbackDislikePayload {
        var aid = card.aid?.takeIf { it > 0L }
        var ownerMid = card.ownerMid?.takeIf { it > 0L }
        if (aid != null && ownerMid != null) {
            return FeedbackDislikePayload(
                aid = aid,
                ownerMid = ownerMid,
                trackId = card.trackId,
                reasonId = FEEDBACK_DISLIKE_DEFAULT_REASON_ID,
            )
        }

        val bvid = card.bvid.trim().takeIf { it.isNotBlank() } ?: throw BiliApiException(apiCode = -400, apiMessage = "missing_video_id")
        val detail = BiliApi.videoDetail(bvid)
        if (aid == null) {
            aid = detail.aid
        }
        if (ownerMid == null) {
            ownerMid = detail.owner?.mid?.takeIf { it > 0L }
        }
        return FeedbackDislikePayload(
            aid = aid ?: throw BiliApiException(apiCode = -400, apiMessage = "missing_video_id"),
            ownerMid = ownerMid ?: throw BiliApiException(apiCode = -400, apiMessage = "missing_video_owner"),
            trackId = card.trackId,
            reasonId = FEEDBACK_DISLIKE_DEFAULT_REASON_ID,
        )
    }

    internal suspend fun <T> requestWithAnonymousFallback(
        requestName: String,
        context: String,
        primary: suspend () -> T,
        fallback: suspend () -> T,
    ): T {
        try {
            return primary()
        } catch (primaryError: Throwable) {
            if (primaryError is CancellationException) throw primaryError
            runCatching { AppLog.w(TAG, "$requestName primary failed, retry anonymous $context", primaryError) }
            try {
                return fallback().also {
                    runCatching { AppLog.i(TAG, "$requestName anonymous fallback succeeded $context") }
                }
            } catch (fallbackError: Throwable) {
                if (fallbackError is CancellationException) throw fallbackError
                if (fallbackError !== primaryError) {
                    primaryError.addSuppressed(fallbackError)
                }
                runCatching { AppLog.w(TAG, "$requestName anonymous fallback failed $context", fallbackError) }
                throw primaryError
            }
        }
    }
}
