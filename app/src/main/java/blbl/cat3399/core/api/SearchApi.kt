package blbl.cat3399.core.api

import blbl.cat3399.core.model.BangumiSeason
import blbl.cat3399.core.model.Following
import blbl.cat3399.core.model.LiveRoomCard
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object SearchApi {
    internal interface JsonObj {
        fun optString(name: String, fallback: String): String

        fun optLong(name: String): Long

        fun optLong(name: String, fallback: Long): Long

        fun optInt(name: String, fallback: Int): Int

        fun optBoolean(name: String, fallback: Boolean): Boolean
    }

    private class OrgJsonObj(
        private val obj: JSONObject,
    ) : JsonObj {
        override fun optString(name: String, fallback: String): String = obj.optString(name, fallback)

        override fun optLong(name: String): Long = obj.optLong(name)

        override fun optLong(name: String, fallback: Long): Long = obj.optLong(name, fallback)

        override fun optInt(name: String, fallback: Int): Int = obj.optInt(name, fallback)

        override fun optBoolean(name: String, fallback: Boolean): Boolean = obj.optBoolean(name, fallback)
    }

    suspend fun searchDefaultText(): String? {
        val keys = BiliClient.ensureWbiKeys()
        val url =
            BiliClient.signedWbiUrl(
                path = "/x/web-interface/wbi/search/default",
                params = emptyMap(),
                keys = keys,
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        return json.optJSONObject("data")?.optString("show_name")?.takeIf { it.isNotBlank() }
    }

    suspend fun searchHot(limit: Int = 10): List<String> {
        val keys = BiliClient.ensureWbiKeys()
        val url =
            BiliClient.signedWbiUrl(
                path = "/x/web-interface/wbi/search/square",
                params = mapOf("limit" to limit.coerceIn(1, 50).toString()),
                keys = keys,
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val list = json.optJSONObject("data")?.optJSONObject("trending")?.optJSONArray("list") ?: JSONArray()
        return withContext(Dispatchers.Default) {
            val out = ArrayList<String>(list.length())
            for (i in 0 until list.length()) {
                val obj = list.optJSONObject(i) ?: continue
                val name = obj.optString("show_name", obj.optString("keyword", "")).trim()
                if (name.isNotBlank()) out.add(name)
            }
            out
        }
    }

    suspend fun searchSuggest(term: String): List<String> {
        val t = term.trim()
        if (t.isBlank()) return emptyList()
        val url =
            BiliClient.withQuery(
                "https://s.search.bilibili.com/main/suggest",
                mapOf(
                    "term" to t,
                    "main_ver" to "v1",
                    "func" to "suggest",
                    "suggest_type" to "accurate",
                    "sub_type" to "tag",
                ),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) return emptyList()
        val tags = json.optJSONObject("result")?.optJSONArray("tag") ?: JSONArray()
        return withContext(Dispatchers.Default) {
            val out = ArrayList<String>(tags.length())
            for (i in 0 until tags.length()) {
                val obj = tags.optJSONObject(i) ?: continue
                val value = obj.optString("value", "").trim()
                if (value.isNotBlank()) out.add(value)
            }
            out
        }
    }

    suspend fun searchVideo(
        keyword: String,
        page: Int = 1,
        order: String = "totalrank",
    ): BiliApi.PagedResult<VideoCard> {
        return searchVideoInner(keyword = keyword, page = page, order = order, allowRetry = true)
    }

    suspend fun searchMediaFt(
        keyword: String,
        page: Int = 1,
        order: String = "totalrank",
    ): BiliApi.PagedResult<BangumiSeason> {
        return searchMediaInner(
            keyword = keyword,
            page = page,
            order = order,
            searchType = "media_ft",
            allowRetry = true,
        )
    }

    suspend fun searchMediaBangumi(
        keyword: String,
        page: Int = 1,
        order: String = "totalrank",
    ): BiliApi.PagedResult<BangumiSeason> {
        return searchMediaInner(
            keyword = keyword,
            page = page,
            order = order,
            searchType = "media_bangumi",
            allowRetry = true,
        )
    }

    private suspend fun searchMediaInner(
        keyword: String,
        page: Int,
        order: String,
        searchType: String,
        allowRetry: Boolean,
    ): BiliApi.PagedResult<BangumiSeason> {
        ensureSearchCookies()
        val keys = BiliClient.ensureWbiKeys()
        val params =
            mapOf(
                "search_type" to searchType,
                "keyword" to keyword,
                "order" to order,
                "page" to page.coerceAtLeast(1).toString(),
            )
        val url =
            BiliClient.signedWbiUrl(
                path = "/x/web-interface/wbi/search/type",
                params = params,
                keys = keys,
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            if (code == -412 && allowRetry) {
                ensureSearchCookies(force = true)
                return searchMediaInner(
                    keyword = keyword,
                    page = page,
                    order = order,
                    searchType = searchType,
                    allowRetry = false,
                )
            }
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val result = data.optJSONArray("result") ?: JSONArray()
        val p = data.optInt("page", page)
        val pages = data.optInt("numPages", 0)
        val total = data.optInt("numResults", 0)
        val seasons = withContext(Dispatchers.Default) { parseSearchMediaCards(result) }
        return BiliApi.PagedResult(items = seasons, page = p, pages = pages, total = total)
    }

    suspend fun searchLiveRoom(
        keyword: String,
        page: Int = 1,
        order: String = "online",
    ): BiliApi.PagedResult<LiveRoomCard> {
        return searchLiveRoomInner(keyword = keyword, page = page, order = order, allowRetry = true)
    }

    private suspend fun searchLiveRoomInner(
        keyword: String,
        page: Int,
        order: String,
        allowRetry: Boolean,
    ): BiliApi.PagedResult<LiveRoomCard> {
        ensureSearchCookies()
        val keys = BiliClient.ensureWbiKeys()
        val params =
            mapOf(
                "search_type" to "live_room",
                "keyword" to keyword,
                "order" to order,
                "page" to page.coerceAtLeast(1).toString(),
            )
        val url =
            BiliClient.signedWbiUrl(
                path = "/x/web-interface/wbi/search/type",
                params = params,
                keys = keys,
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            if (code == -412 && allowRetry) {
                ensureSearchCookies(force = true)
                return searchLiveRoomInner(
                    keyword = keyword,
                    page = page,
                    order = order,
                    allowRetry = false,
                )
            }
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val result = data.optJSONArray("result") ?: JSONArray()
        val p = data.optInt("page", page)
        val pages = data.optInt("numPages", 0)
        val total = data.optInt("numResults", 0)
        val rooms = withContext(Dispatchers.Default) { parseSearchLiveRoomCards(result) }
        return BiliApi.PagedResult(items = rooms, page = p, pages = pages, total = total)
    }

    suspend fun searchUser(
        keyword: String,
        page: Int = 1,
        order: String = "0",
        orderSort: Int = 0,
        userType: Int = 0,
    ): BiliApi.PagedResult<Following> {
        return searchUserInner(
            keyword = keyword,
            page = page,
            order = order,
            orderSort = orderSort,
            userType = userType,
            allowRetry = true,
        )
    }

    private suspend fun searchUserInner(
        keyword: String,
        page: Int,
        order: String,
        orderSort: Int,
        userType: Int,
        allowRetry: Boolean,
    ): BiliApi.PagedResult<Following> {
        ensureSearchCookies()
        val keys = BiliClient.ensureWbiKeys()
        val params =
            buildMap {
                put("search_type", "bili_user")
                put("keyword", keyword)
                put("order", order)
                put("order_sort", orderSort.coerceIn(0, 1).toString())
                put("user_type", userType.coerceIn(0, 3).toString())
                put("page", page.coerceAtLeast(1).toString())
            }
        val url =
            BiliClient.signedWbiUrl(
                path = "/x/web-interface/wbi/search/type",
                params = params,
                keys = keys,
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            if (code == -412 && allowRetry) {
                ensureSearchCookies(force = true)
                return searchUserInner(
                    keyword = keyword,
                    page = page,
                    order = order,
                    orderSort = orderSort,
                    userType = userType,
                    allowRetry = false,
                )
            }
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val result = data.optJSONArray("result") ?: JSONArray()
        val p = data.optInt("page", page)
        val pages = data.optInt("numPages", 0)
        val total = data.optInt("numResults", 0)
        val users = withContext(Dispatchers.Default) { parseSearchUsers(result) }
        return BiliApi.PagedResult(items = users, page = p, pages = pages, total = total)
    }

    private suspend fun searchVideoInner(
        keyword: String,
        page: Int,
        order: String,
        allowRetry: Boolean,
    ): BiliApi.PagedResult<VideoCard> {
        ensureSearchCookies()
        val keys = BiliClient.ensureWbiKeys()
        val params =
            mapOf(
                "search_type" to "video",
                "keyword" to keyword,
                "order" to order,
                "page" to page.coerceAtLeast(1).toString(),
            )
        val url =
            BiliClient.signedWbiUrl(
                path = "/x/web-interface/wbi/search/type",
                params = params,
                keys = keys,
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            if (code == -412 && allowRetry) {
                ensureSearchCookies(force = true)
                return searchVideoInner(keyword = keyword, page = page, order = order, allowRetry = false)
            }
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val result = data.optJSONArray("result") ?: JSONArray()
        val p = data.optInt("page", page)
        val pages = data.optInt("numPages", 0)
        val total = data.optInt("numResults", 0)
        val cards = withContext(Dispatchers.Default) { parseSearchVideoCards(result) }
        return BiliApi.PagedResult(items = cards, page = p, pages = pages, total = total)
    }

    internal fun parseSearchMediaCards(arr: JSONArray): List<BangumiSeason> {
        val out = ArrayList<BangumiSeason>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val seasonId =
                obj.optLong("season_id").takeIf { it > 0 }
                    ?: obj.optLong("pgc_season_id").takeIf { it > 0 }
                    ?: continue
            val title = stripHtmlTags(obj.optString("title", "")).trim()
            val seasonType = obj.optString("season_type_name").takeIf { it.isNotBlank() }
            val badgeText =
                obj.optString("angle_title").takeIf { it.isNotBlank() }
                    ?: (obj.optJSONArray("display_info")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() })
            val area = obj.optString("areas").takeIf { it.isNotBlank() }
            val styles = obj.optString("styles").takeIf { it.isNotBlank() }
            val progressText =
                buildList {
                    area?.let { add(it) }
                    styles?.let { add(it) }
                }.joinToString(" · ").takeIf { it.isNotBlank() }

            out.add(
                BangumiSeason(
                    seasonId = seasonId,
                    seasonTypeName = seasonType,
                    title = title,
                    coverUrl = obj.optString("cover").takeIf { it.isNotBlank() },
                    badge = badgeText,
                    badgeEp = null,
                    progressText = progressText,
                    totalCount = null,
                    lastEpIndex = null,
                    lastEpId = null,
                    newestEpIndex = null,
                    isFinish = null,
                ),
            )
        }
        return out
    }

    internal fun parseSearchLiveRoomCards(arr: JSONArray): List<LiveRoomCard> {
        val out = ArrayList<LiveRoomCard>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val roomId = obj.optLong("roomid").takeIf { it > 0 } ?: continue
            val uid = obj.optLong("uid").takeIf { it > 0 } ?: obj.optLong("mid").takeIf { it > 0 } ?: 0L
            val title = stripHtmlTags(obj.optString("title", "")).trim()
            val uname = stripHtmlTags(obj.optString("uname", "")).trim()
            val coverUrl = obj.optString("user_cover").ifBlank { obj.optString("cover") }
            val faceUrl = obj.optString("uface").takeIf { it.isNotBlank() }
            val online = obj.optLong("online").takeIf { it > 0 } ?: 0L
            val isLive = obj.optInt("live_status").let { it == 1 } || obj.optBoolean("is_live", false)
            val areaName = stripHtmlTags(obj.optString("cate_name")).trim().takeIf { it.isNotBlank() }
            out.add(
                LiveRoomCard(
                    roomId = roomId,
                    uid = uid,
                    title = title,
                    uname = uname,
                    coverUrl = coverUrl,
                    faceUrl = faceUrl,
                    online = online,
                    isLive = isLive,
                    parentAreaId = null,
                    parentAreaName = null,
                    areaId = null,
                    areaName = areaName,
                    keyframe = obj.optString("cover").takeIf { it.isNotBlank() },
                ),
            )
        }
        return out
    }

    internal fun parseSearchUser(obj: JsonObj): Following? {
        val mid = obj.optLong("mid").takeIf { it > 0 } ?: return null
        val name = stripHtmlTags(obj.optString("uname", "")).trim()
        if (name.isBlank()) return null
        val avatar = obj.optString("upic", "").takeIf { it.isNotBlank() }
        val sign = obj.optString("usign", "").takeIf { it.isNotBlank() }
        val liveRoomId = obj.optLong("room_id").takeIf { it > 0 } ?: 0L
        val isLive =
            obj.optInt("is_live", 0) == 1 ||
                obj.optInt("live_status", 0) == 1 ||
                obj.optBoolean("is_live", false)
        return Following(
            mid = mid,
            name = name,
            avatarUrl = avatar,
            sign = sign,
            isLive = isLive && liveRoomId > 0L,
            liveRoomId = liveRoomId,
        )
    }

    internal fun parseSearchUsers(arr: JSONArray): List<Following> {
        val out = ArrayList<Following>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parseSearchUser(OrgJsonObj(obj))?.let { out.add(it) }
        }
        return out
    }

    internal fun parseSearchVideoCard(obj: JsonObj): VideoCard? {
        val bvid = obj.optString("bvid", "").trim()
        if (bvid.isBlank()) return null
        return VideoCard(
            bvid = bvid,
            cid = null,
            aid = obj.optLong("aid").takeIf { it > 0 },
            title = stripHtmlTags(obj.optString("title", "")),
            coverUrl = obj.optString("pic", ""),
            durationSec = BiliApi.parseDuration(obj.optString("duration", "0:00")),
            ownerName = obj.optString("author", ""),
            ownerFace = null,
            ownerMid =
                obj.optLong("mid").takeIf { it > 0 }
                    ?: obj.optLong("up_mid").takeIf { it > 0 }
                    ?: obj.optLong("author_mid").takeIf { it > 0 },
            view = obj.optLong("play").takeIf { it > 0 },
            danmaku = obj.optLong("video_review").takeIf { it > 0 },
            pubDate = obj.optLong("pubdate").takeIf { it > 0 },
            pubDateText = null,
            trackId =
                obj.optString("trackid", obj.optString("track_id", ""))
                    .trim()
                    .takeIf { it.isNotBlank() },
        )
    }

    internal fun parseSearchVideoCards(arr: JSONArray): List<VideoCard> {
        val out = ArrayList<VideoCard>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parseSearchVideoCard(OrgJsonObj(obj))?.let { out.add(it) }
        }
        return out
    }

    private fun stripHtmlTags(s: String): String {
        if (s.indexOf('<') < 0) return s
        return s.replace(Regex("<[^>]*>"), "")
    }

    private suspend fun ensureSearchCookies(force: Boolean = false) {
        if (!force && !BiliClient.cookies.getCookieValue("buvid3").isNullOrBlank()) return
        runCatching { BiliClient.getBytes("https://www.bilibili.com/") }
    }
}
