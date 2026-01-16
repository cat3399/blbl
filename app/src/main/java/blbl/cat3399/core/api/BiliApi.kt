package blbl.cat3399.core.api

import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiEpisode
import blbl.cat3399.core.model.BangumiSeason
import blbl.cat3399.core.model.BangumiSeasonDetail
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.model.FavFolder
import blbl.cat3399.core.model.Following
import blbl.cat3399.core.model.LiveAreaParent
import blbl.cat3399.core.model.LiveRoomCard
import blbl.cat3399.core.model.VideoCard
import android.util.Base64
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.WebCookieMaintainer
import blbl.cat3399.core.util.Format
import blbl.cat3399.proto.dm.DmSegMobileReply
import blbl.cat3399.proto.dmview.DmWebViewReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToLong

object BiliApi {
    private const val TAG = "BiliApi"
    private const val PILI_REFERER = "https://www.bilibili.com"

    private fun piliCookieHeader(): String? {
        // Match PiliPlus' common cookie set for web APIs.
        val names =
            listOf(
                "SESSDATA",
                "bili_jct",
                "DedeUserID",
                "DedeUserID__ckMd5",
                "sid",
                "buvid3",
            )
        val parts = ArrayList<String>(names.size)
        for (name in names) {
            val v = BiliClient.cookies.getCookieValue(name)?.takeIf { it.isNotBlank() } ?: continue
            parts.add("$name=$v")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun piliWebHeaders(includeCookie: Boolean = true): Map<String, String> {
        val out = piliHeaders().toMutableMap()
        out["Referer"] = PILI_REFERER
        // Tell OkHttp interceptor to not add Origin (PiliPlus does not send it for this flow).
        out["X-Blbl-Skip-Origin"] = "1"
        if (includeCookie) {
            val cookie = piliCookieHeader()
            if (!cookie.isNullOrBlank()) out["Cookie"] = cookie
        }
        return out
    }

    private fun piliHeaders(): Map<String, String> {
        val headers =
            mutableMapOf(
                "env" to "prod",
                "app-key" to "android64",
                "x-bili-aurora-zone" to "sh001",
            )
        val midStr = BiliClient.cookies.getCookieValue("DedeUserID")?.trim().orEmpty()
        val mid = midStr.toLongOrNull()?.takeIf { it > 0 } ?: return headers
        headers["x-bili-mid"] = mid.toString()
        genAuroraEid(mid)?.let { headers["x-bili-aurora-eid"] = it }
        return headers
    }

    private fun genAuroraEid(mid: Long): String? {
        if (mid <= 0) return null
        val key = "ad1va46a7lza".toByteArray()
        val input = mid.toString().toByteArray()
        val out = ByteArray(input.size)
        for (i in input.indices) out[i] = (input[i].toInt() xor key[i % key.size].toInt()).toByte()
        return Base64.encodeToString(out, Base64.NO_PADDING or Base64.NO_WRAP)
    }

    data class PagedResult<T>(
        val items: List<T>,
        val page: Int,
        val pages: Int,
        val total: Int,
    )

    data class RelationStat(
        val following: Long,
        val follower: Long,
    )

    data class DanmakuWebSetting(
        val dmSwitch: Boolean,
        val allowScroll: Boolean,
        val allowTop: Boolean,
        val allowBottom: Boolean,
        val allowColor: Boolean,
        val allowSpecial: Boolean,
        val aiEnabled: Boolean,
        val aiLevel: Int,
    )

    data class DanmakuWebView(
        val segmentTotal: Int,
        val segmentPageSizeMs: Long,
        val count: Long,
        val setting: DanmakuWebSetting?,
    )

    data class HistoryCursor(
        val max: Long,
        val business: String?,
        val viewAt: Long,
    )

    data class HistoryPage(
        val items: List<VideoCard>,
        val cursor: HistoryCursor?,
    )

    data class HasMorePage<T>(
        val items: List<T>,
        val page: Int,
        val hasMore: Boolean,
        val total: Int,
    )

    data class LiveRoomInfo(
        val roomId: Long,
        val uid: Long,
        val title: String,
        val liveStatus: Int,
        val areaName: String?,
        val parentAreaName: String?,
    )

    data class LivePlayUrl(
        val currentQn: Int,
        val acceptQn: List<Int>,
        val qnDesc: Map<Int, String>,
        val lines: List<LivePlayLine>,
    )

    data class LivePlayLine(
        val order: Int,
        val url: String,
    )

    data class LiveDanmuInfo(
        val token: String,
        val hosts: List<LiveDanmuHost>,
    )

    data class LiveDanmuHost(
        val host: String,
        val wssPort: Int,
        val wsPort: Int,
    )

    suspend fun nav(): JSONObject {
        return BiliClient.getJson("https://api.bilibili.com/x/web-interface/nav")
    }

    suspend fun searchDefaultText(): String? {
        val keys = BiliClient.ensureWbiKeys()
        val url = BiliClient.signedWbiUrl(
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
        val url = BiliClient.signedWbiUrl(
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
        val url = BiliClient.withQuery(
            "https://s.search.bilibili.com/main/suggest",
            mapOf("term" to t, "main_ver" to "v1", "func" to "suggest", "suggest_type" to "accurate", "sub_type" to "tag"),
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
    ): PagedResult<VideoCard> {
        return searchVideoInner(keyword = keyword, page = page, order = order, allowRetry = true)
    }

    private suspend fun searchVideoInner(
        keyword: String,
        page: Int,
        order: String,
        allowRetry: Boolean,
    ): PagedResult<VideoCard> {
        ensureSearchCookies()
        val keys = BiliClient.ensureWbiKeys()
        val params = mapOf(
            "search_type" to "video",
            "keyword" to keyword,
            "order" to order,
            "page" to page.coerceAtLeast(1).toString(),
        )
        val url = BiliClient.signedWbiUrl(
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
        return PagedResult(items = cards, page = p, pages = pages, total = total)
    }

    suspend fun relationStat(vmid: Long): RelationStat {
        val url = BiliClient.withQuery(
            "https://api.bilibili.com/x/relation/stat",
            mapOf("vmid" to vmid.toString()),
        )
        val json = BiliClient.getJson(url)
        val data = json.optJSONObject("data") ?: JSONObject()
        return RelationStat(
            following = data.optLong("following"),
            follower = data.optLong("follower"),
        )
    }

    suspend fun liveAreas(): List<LiveAreaParent> {
        val json = BiliClient.getJson("https://api.live.bilibili.com/room/v1/Area/getList")
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONArray("data") ?: JSONArray()
        return withContext(Dispatchers.Default) { parseLiveAreas(data) }
    }

    suspend fun liveRecommend(page: Int = 1): List<LiveRoomCard> {
        // This endpoint is public; keep params minimal to avoid extra risk controls.
        val url =
            BiliClient.withQuery(
                "https://api.live.bilibili.com/xlive/web-interface/v1/webMain/getMoreRecList",
                buildMap {
                    put("platform", "web")
                    put("page", page.coerceAtLeast(1).toString())
                },
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val list = json.optJSONObject("data")?.optJSONArray("recommend_room_list") ?: JSONArray()
        return withContext(Dispatchers.Default) { parseLiveRecommendRooms(list) }
    }

    suspend fun liveFollowing(page: Int = 1, pageSize: Int = 10): HasMorePage<LiveRoomCard> {
        if (!BiliClient.cookies.hasSessData()) {
            return HasMorePage(items = emptyList(), page = page.coerceAtLeast(1), hasMore = false, total = 0)
        }
        WebCookieMaintainer.ensureHealthyForPlay()
        val url =
            BiliClient.withQuery(
                "https://api.live.bilibili.com/xlive/web-ucenter/user/following",
                mapOf(
                    "page" to page.coerceAtLeast(1).toString(),
                    "page_size" to pageSize.coerceIn(1, 10).toString(),
                    "ignoreRecord" to "1",
                    "hit_ab" to "true",
                ),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val totalPage = data.optInt("totalPage", 1).coerceAtLeast(1)
        val list = data.optJSONArray("list") ?: JSONArray()
        val items = withContext(Dispatchers.Default) { parseLiveFollowingRooms(list) }
        val p = page.coerceAtLeast(1)
        return HasMorePage(items = items, page = p, hasMore = p < totalPage, total = data.optInt("count", 0))
    }

    suspend fun liveRoomInfo(roomId: Long): LiveRoomInfo {
        val url =
            BiliClient.withQuery(
                "https://api.live.bilibili.com/room/v1/Room/get_info",
                mapOf("room_id" to roomId.toString()),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        return LiveRoomInfo(
            roomId = data.optLong("room_id").takeIf { it > 0 } ?: roomId,
            uid = data.optLong("uid").takeIf { it > 0 } ?: 0L,
            title = data.optString("title", ""),
            liveStatus = data.optInt("live_status", 0),
            areaName = data.optString("area_name", "").trim().takeIf { it.isNotBlank() },
            parentAreaName = data.optString("parent_area_name", "").trim().takeIf { it.isNotBlank() },
        )
    }

    suspend fun livePlayUrl(roomId: Long, qn: Int): LivePlayUrl {
        val url =
            BiliClient.withQuery(
                "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo",
                mapOf(
                    "room_id" to roomId.toString(),
                    "protocol" to "0", // http_stream
                    "format" to "0", // flv
                    "codec" to "0", // avc
                    "qn" to qn.coerceAtLeast(1).toString(),
                ),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val playurl = data.optJSONObject("playurl_info")?.optJSONObject("playurl") ?: JSONObject()

        val qnDesc = HashMap<Int, String>()
        val descArr = playurl.optJSONArray("g_qn_desc") ?: JSONArray()
        for (i in 0 until descArr.length()) {
            val obj = descArr.optJSONObject(i) ?: continue
            val q = obj.optInt("qn", 0).takeIf { it > 0 } ?: continue
            val d = obj.optString("desc", "").trim()
            if (d.isNotBlank()) qnDesc[q] = d
        }

        val streamArr = playurl.optJSONArray("stream") ?: JSONArray()
        var pickedCodec: JSONObject? = null
        loop@ for (i in 0 until streamArr.length()) {
            val stream = streamArr.optJSONObject(i) ?: continue
            val protocolName = stream.optString("protocol_name", "").lowercase()
            if (!protocolName.contains("http_stream")) continue
            val formats = stream.optJSONArray("format") ?: continue
            for (j in 0 until formats.length()) {
                val fmt = formats.optJSONObject(j) ?: continue
                val formatName = fmt.optString("format_name", "").lowercase()
                if (formatName != "flv") continue
                val codecs = fmt.optJSONArray("codec") ?: continue
                for (k in 0 until codecs.length()) {
                    val c = codecs.optJSONObject(k) ?: continue
                    val codecName = c.optString("codec_name", "").lowercase()
                    if (codecName != "avc") continue
                    pickedCodec = c
                    break@loop
                }
            }
        }
        val codec = pickedCodec ?: JSONObject()
        val currentQn = codec.optInt("current_qn", 0).takeIf { it > 0 } ?: qn
        val accept = codec.optJSONArray("accept_qn") ?: JSONArray()
        val acceptQn =
            buildList {
                for (i in 0 until accept.length()) {
                    val v = accept.optInt(i, 0).takeIf { it > 0 } ?: continue
                    add(v)
                }
            }.distinct()
        val baseUrl = codec.optString("base_url", "").trim()
        val urlInfo = codec.optJSONArray("url_info") ?: JSONArray()
        val lines =
            buildList {
                for (i in 0 until urlInfo.length()) {
                    val obj = urlInfo.optJSONObject(i) ?: continue
                    val host = obj.optString("host", "").trim()
                    val extra = obj.optString("extra", "").trim()
                    if (host.isBlank() || baseUrl.isBlank()) continue
                    val full = host + baseUrl + extra
                    add(LivePlayLine(order = i + 1, url = full))
                }
            }

        return LivePlayUrl(currentQn = currentQn, acceptQn = acceptQn, qnDesc = qnDesc, lines = lines)
    }

    suspend fun liveDanmuInfo(roomId: Long): LiveDanmuInfo {
        WebCookieMaintainer.ensureWebFingerprintCookies()
        val keys = BiliClient.ensureWbiKeys()
        val url =
            BiliClient.signedWbiUrlAbsolute(
                "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo",
                params =
                    mapOf(
                        "id" to roomId.toString(),
                        "type" to "0",
                        "web_location" to "444.8",
                    ),
                keys = keys,
            )
        val json =
            BiliClient.getJson(
                url,
                headers =
                    mapOf(
                        "Referer" to "https://live.bilibili.com/",
                        "Origin" to "https://live.bilibili.com",
                    ),
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val token = data.optString("token", "").trim()
        val hostList = data.optJSONArray("host_list") ?: JSONArray()
        val hosts =
            buildList {
                for (i in 0 until hostList.length()) {
                    val obj = hostList.optJSONObject(i) ?: continue
                    val host = obj.optString("host", "").trim()
                    val wssPort = obj.optInt("wss_port", 0)
                    val wsPort = obj.optInt("ws_port", 0)
                    if (host.isBlank() || (wssPort <= 0 && wsPort <= 0)) continue
                    add(LiveDanmuHost(host = host, wssPort = wssPort, wsPort = wsPort))
                }
            }.distinctBy { "${it.host}:${it.wssPort}:${it.wsPort}" }
        return LiveDanmuInfo(token = token, hosts = hosts)
    }

    private fun parseLiveAreas(arr: JSONArray): List<LiveAreaParent> {
        val out = ArrayList<LiveAreaParent>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val id = p.optInt("id", 0)
            val name = p.optString("name", "").trim()
            if (id <= 0 || name.isBlank()) continue
            val children = ArrayList<LiveAreaParent.Child>()
            val list = p.optJSONArray("list") ?: JSONArray()
            for (j in 0 until list.length()) {
                val c = list.optJSONObject(j) ?: continue
                val cid = c.optString("id", "").trim().toIntOrNull() ?: continue
                val parentId = c.optString("parent_id", "").trim().toIntOrNull() ?: id
                val cname = c.optString("name", "").trim()
                if (cname.isBlank()) continue
                children.add(
                    LiveAreaParent.Child(
                        id = cid,
                        parentId = parentId,
                        name = cname,
                        hot = c.optInt("hot_status", 0) == 1,
                        coverUrl = c.optString("pic", "").trim().takeIf { it.isNotBlank() },
                    ),
                )
            }
            out.add(LiveAreaParent(id = id, name = name, children = children))
        }
        return out
    }

    private fun parseLiveRecommendRooms(arr: JSONArray): List<LiveRoomCard> {
        val out = ArrayList<LiveRoomCard>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val roomId = obj.optLong("roomid").takeIf { it > 0 } ?: continue
            val online = obj.optLong("online").takeIf { it > 0 } ?: 0L
            out.add(
                LiveRoomCard(
                    roomId = roomId,
                    uid = obj.optLong("uid").takeIf { it > 0 } ?: 0L,
                    title = obj.optString("title", ""),
                    uname = obj.optString("uname", ""),
                    coverUrl = obj.optString("cover", "").trim(),
                    faceUrl = obj.optString("face", "").trim().takeIf { it.isNotBlank() },
                    online = online,
                    isLive = true,
                    parentAreaId = obj.optInt("area_v2_parent_id").takeIf { it > 0 },
                    parentAreaName = obj.optString("area_v2_parent_name", "").trim().takeIf { it.isNotBlank() },
                    areaId = obj.optInt("area_v2_id").takeIf { it > 0 },
                    areaName = obj.optString("area_v2_name", "").trim().takeIf { it.isNotBlank() },
                    keyframe = obj.optString("keyframe", "").trim().takeIf { it.isNotBlank() },
                ),
            )
        }
        return out
    }

    private fun parseLiveFollowingRooms(arr: JSONArray): List<LiveRoomCard> {
        val out = ArrayList<LiveRoomCard>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val roomId = obj.optLong("roomid").takeIf { it > 0 } ?: continue
            val online = parseCnCount(obj.optString("text_small", "0").trim())
            val liveStatus = obj.optInt("live_status", 0)
            out.add(
                LiveRoomCard(
                    roomId = roomId,
                    uid = obj.optLong("uid").takeIf { it > 0 } ?: 0L,
                    title = obj.optString("title", ""),
                    uname = obj.optString("uname", ""),
                    coverUrl = obj.optString("room_cover", obj.optString("cover_from_user", "")).trim(),
                    faceUrl = obj.optString("face", "").trim().takeIf { it.isNotBlank() },
                    online = online,
                    isLive = liveStatus == 1,
                    parentAreaId = obj.optInt("parent_area_id").takeIf { it > 0 },
                    parentAreaName = obj.optString("area_v2_parent_name", "").trim().takeIf { it.isNotBlank() },
                    areaId = obj.optInt("area_id").takeIf { it > 0 },
                    areaName = obj.optString("area_name_v2", obj.optString("area_name", "")).trim().takeIf { it.isNotBlank() },
                    keyframe = null,
                ),
            )
        }
        return out
    }

    private fun parseCnCount(text: String): Long {
        val t = text.trim()
        if (t.isBlank()) return 0L
        val m = Regex("^([0-9]+(?:\\.[0-9]+)?)([万亿]?)$").find(t) ?: return t.toLongOrNull() ?: 0L
        val num = m.groupValues[1].toDoubleOrNull() ?: return 0L
        val unit = m.groupValues[2]
        val mul =
            when (unit) {
                "万" -> 10_000.0
                "亿" -> 100_000_000.0
                else -> 1.0
            }
        return (num * mul).toLong()
    }

    suspend fun historyCursor(
        max: Long = 0,
        business: String? = null,
        viewAt: Long = 0,
        ps: Int = 24,
    ): HistoryPage {
        val params = mutableMapOf(
            "max" to max.coerceAtLeast(0).toString(),
            "view_at" to viewAt.coerceAtLeast(0).toString(),
            "ps" to ps.coerceIn(1, 30).toString(),
        )
        if (!business.isNullOrBlank()) params["business"] = business
        val url = BiliClient.withQuery("https://api.bilibili.com/x/web-interface/history/cursor", params)
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val cursorObj = data.optJSONObject("cursor")
        val cursor =
            cursorObj?.let {
                HistoryCursor(
                    max = it.optLong("max"),
                    business = it.optString("business", "").takeIf { s -> s.isNotBlank() },
                    viewAt = it.optLong("view_at"),
                )
            }
        val list = data.optJSONArray("list") ?: JSONArray()
        val cards =
            withContext(Dispatchers.Default) {
                val out = ArrayList<VideoCard>(list.length())
                for (i in 0 until list.length()) {
                    val it = list.optJSONObject(i) ?: continue
                    val history = it.optJSONObject("history") ?: JSONObject()
                    val businessType = history.optString("business", "")
                    if (businessType != "archive") continue
                    val bvid = history.optString("bvid", "").trim()
                    if (bvid.isBlank()) continue

                    val covers = it.optJSONArray("covers")
                    val coverUrl =
                        it.optString("cover", "").takeIf { s -> s.isNotBlank() }
                            ?: covers?.optString(0)?.takeIf { s -> s.isNotBlank() }
                            ?: ""

                    val viewAtSec = it.optLong("view_at").takeIf { v -> v > 0 }
                    out.add(
                        VideoCard(
                            bvid = bvid,
                            cid = history.optLong("cid").takeIf { v -> v > 0 },
                            title = it.optString("title", ""),
                            coverUrl = coverUrl,
                            durationSec = it.optInt("duration", 0),
                            ownerName = it.optString("author_name", ""),
                            ownerFace = it.optString("author_face").takeIf { s -> s.isNotBlank() },
                            view = null,
                            danmaku = null,
                            pubDate = null,
                            pubDateText = viewAtSec?.let { v -> Format.timeText(v) },
                        ),
                    )
                }
                out
            }
        return HistoryPage(items = cards, cursor = cursor)
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

    private suspend fun favFolderInfo(mediaId: Long): FavFolder? {
        val url = BiliClient.withQuery(
            "https://api.bilibili.com/x/v3/fav/folder/info",
            mapOf("media_id" to mediaId.toString()),
        )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) return null
        val data = json.optJSONObject("data") ?: return null
        return FavFolder(
            mediaId = data.optLong("id"),
            title = data.optString("title", ""),
            coverUrl = data.optString("cover").takeIf { it.isNotBlank() },
            mediaCount = data.optInt("media_count", 0),
        )
    }

    suspend fun favFolders(upMid: Long): List<FavFolder> {
        val url = BiliClient.withQuery(
            "https://api.bilibili.com/x/v3/fav/folder/created/list-all",
            mapOf(
                "up_mid" to upMid.toString(),
                "type" to "2",
                "web_location" to "333.1387",
            ),
        )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val folders = withContext(Dispatchers.Default) {
            val out = ArrayList<FavFolder>(list.length())
            for (i in 0 until list.length()) {
                val obj = list.optJSONObject(i) ?: continue
                val mediaId = obj.optLong("id").takeIf { it > 0 } ?: continue
                out.add(
                    FavFolder(
                        mediaId = mediaId,
                        title = obj.optString("title", ""),
                        coverUrl = obj.optString("cover").takeIf { it.isNotBlank() },
                        mediaCount = obj.optInt("media_count", 0),
                    ),
                )
            }
            out
        }
        val missingIndices = folders.withIndex().filter { it.value.coverUrl.isNullOrBlank() }.map { it.index }
        if (missingIndices.isEmpty()) return folders

        val enriched = folders.toMutableList()
        for (idx in missingIndices) {
            val f = folders[idx]
            val info = runCatching { favFolderInfo(f.mediaId) }.getOrNull()
            if (info != null && !info.coverUrl.isNullOrBlank()) {
                enriched[idx] = f.copy(coverUrl = info.coverUrl)
            }
        }
        return enriched
    }

    suspend fun favFolderResources(
        mediaId: Long,
        pn: Int = 1,
        ps: Int = 20,
    ): HasMorePage<VideoCard> {
        val url = BiliClient.withQuery(
            "https://api.bilibili.com/x/v3/fav/resource/list",
            mapOf(
                "media_id" to mediaId.toString(),
                "pn" to pn.coerceAtLeast(1).toString(),
                "ps" to ps.coerceIn(1, 20).toString(),
                "platform" to "web",
            ),
        )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val medias = data.optJSONArray("medias") ?: JSONArray()
        val hasMore = data.optBoolean("has_more", false)
        val total = data.optJSONObject("info")?.optInt("media_count", 0) ?: 0
        val cards =
            withContext(Dispatchers.Default) {
                val out = ArrayList<VideoCard>(medias.length())
                for (i in 0 until medias.length()) {
                    val obj = medias.optJSONObject(i) ?: continue
                    val bvid = obj.optString("bvid", "").trim()
                    if (bvid.isBlank()) continue
                    val upper = obj.optJSONObject("upper") ?: JSONObject()
                    val cnt = obj.optJSONObject("cnt_info") ?: JSONObject()
                    val favTime = obj.optLong("fav_time").takeIf { it > 0 }
                    out.add(
                        VideoCard(
                            bvid = bvid,
                            cid = obj.optLong("cid").takeIf { it > 0 },
                            title = obj.optString("title", ""),
                            coverUrl = obj.optString("cover", ""),
                            durationSec = obj.optInt("duration", 0),
                            ownerName = upper.optString("name", ""),
                            ownerFace = upper.optString("face").takeIf { it.isNotBlank() },
                            view = cnt.optLong("play").takeIf { it > 0 },
                            danmaku = cnt.optLong("danmaku").takeIf { it > 0 },
                            pubDate = obj.optLong("pubdate").takeIf { it > 0 },
                            pubDateText = favTime?.let { "收藏于：${Format.timeText(it)}" },
                        ),
                    )
                }
                out
            }
        return HasMorePage(items = cards, page = pn.coerceAtLeast(1), hasMore = hasMore, total = total)
    }

    suspend fun bangumiFollowList(
        vmid: Long,
        type: Int,
        pn: Int = 1,
        ps: Int = 15,
    ): PagedResult<BangumiSeason> {
        if (type != 1 && type != 2) error("invalid bangumi follow type=$type")
        val url = BiliClient.withQuery(
            "https://api.bilibili.com/x/space/bangumi/follow/list",
            mapOf(
                "vmid" to vmid.toString(),
                "type" to type.toString(),
                "pn" to pn.coerceAtLeast(1).toString(),
                "ps" to ps.coerceIn(1, 30).toString(),
            ),
        )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val list = data.optJSONArray("list") ?: JSONArray()
        val total = data.optInt("total", 0)
        val page = data.optInt("pn", pn)
        val pageSize = data.optInt("ps", ps)
        val pages = if (pageSize <= 0) 0 else ((total + pageSize - 1) / pageSize)
        val items =
            withContext(Dispatchers.Default) {
                val out = ArrayList<BangumiSeason>(list.length())
                for (i in 0 until list.length()) {
                    val obj = list.optJSONObject(i) ?: continue
                    val seasonId = obj.optLong("season_id").takeIf { it > 0 } ?: continue
                    val progressAny = obj.opt("progress")
                    val progressObj = progressAny as? JSONObject
                    val progressText =
                        when (progressAny) {
                            is JSONObject -> {
                                progressAny.optString("index_show").takeIf { it.isNotBlank() }
                                    ?: progressAny.optInt("last_ep_index").takeIf { it > 0 }?.let { "看到第${it}话" }
                            }
                            is String -> progressAny.takeIf { it.isNotBlank() }
                            else -> null
                        }
                    val progressLastEpId =
                        progressObj?.optLong("last_ep_id")?.takeIf { it > 0 }
                            ?: progressObj?.optLong("last_epid")?.takeIf { it > 0 }
                            ?: obj.optLong("last_ep_id").takeIf { it > 0 }
                    out.add(
                        BangumiSeason(
                            seasonId = seasonId,
                            seasonTypeName = obj.optString("season_type_name").takeIf { it.isNotBlank() },
                            title = obj.optString("title", ""),
                            coverUrl = obj.optString("cover").takeIf { it.isNotBlank() },
                            badge = obj.optString("badge").takeIf { it.isNotBlank() },
                            badgeEp =
                                obj.optString("badge_ep").takeIf { it.isNotBlank() }
                                    ?: obj.optString("badgeEp").takeIf { it.isNotBlank() },
                            progressText = progressText,
                            totalCount = obj.optInt("total_count").takeIf { it > 0 },
                            isFinish = obj.optInt("is_finish", -1).takeIf { it >= 0 }?.let { it == 1 },
                            newestEpIndex = obj.optInt("newest_ep_index").takeIf { it > 0 },
                            lastEpIndex = obj.optInt("last_ep_index").takeIf { it > 0 },
                            lastEpId = progressLastEpId,
                        ),
                    )
                }
                out
            }
        return PagedResult(items = items, page = page, pages = pages, total = total)
    }

    suspend fun bangumiSeasonDetail(seasonId: Long): BangumiSeasonDetail {
        val url = BiliClient.withQuery(
            "https://api.bilibili.com/pgc/view/web/season",
            mapOf("season_id" to seasonId.toString()),
        )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val result = json.optJSONObject("result") ?: JSONObject()
        val progressLastEpId =
            result.optJSONObject("user_status")?.optJSONObject("progress")?.optLong("last_ep_id")?.takeIf { it > 0 }
                ?: result.optJSONObject("user_status")?.optLong("progress")?.takeIf { it > 0 }
                ?: result.optJSONObject("progress")?.optLong("last_ep_id")?.takeIf { it > 0 }
                ?: result.optLong("last_ep_id").takeIf { it > 0 }
        val ratingScore = result.optJSONObject("rating")?.optDouble("score")?.takeIf { it > 0 }
        val stat = result.optJSONObject("stat") ?: JSONObject()
        val views = stat.optLong("views").takeIf { it > 0 } ?: stat.optLong("view").takeIf { it > 0 }
        val danmaku = stat.optLong("danmakus").takeIf { it > 0 } ?: stat.optLong("danmaku").takeIf { it > 0 }
        val episodes = result.optJSONArray("episodes") ?: JSONArray()
        val epList =
            withContext(Dispatchers.Default) {
                val out = ArrayList<BangumiEpisode>(episodes.length())
                for (i in 0 until episodes.length()) {
                    val ep = episodes.optJSONObject(i) ?: continue
                    val epId = ep.optLong("id").takeIf { it > 0 } ?: ep.optLong("ep_id").takeIf { it > 0 } ?: continue
                    out.add(
                        BangumiEpisode(
                            epId = epId,
                            aid = ep.optLong("aid").takeIf { it > 0 } ?: ep.optLong("avid").takeIf { it > 0 },
                            cid = ep.optLong("cid").takeIf { it > 0 },
                            bvid = ep.optString("bvid").takeIf { it.isNotBlank() },
                            title = ep.optString("title", ""),
                            longTitle = ep.optString("long_title", ""),
                            coverUrl = ep.optString("cover").takeIf { it.isNotBlank() },
                            badge = ep.optString("badge").takeIf { it.isNotBlank() },
                        ),
                    )
                }
                out
            }
        return BangumiSeasonDetail(
            seasonId = result.optLong("season_id").takeIf { it > 0 } ?: seasonId,
            title = result.optString("title", result.optString("season_title", "")),
            coverUrl = result.optString("cover").takeIf { it.isNotBlank() },
            subtitle = result.optString("subtitle").takeIf { it.isNotBlank() },
            evaluate = result.optString("evaluate").takeIf { it.isNotBlank() },
            ratingScore = ratingScore,
            views = views,
            danmaku = danmaku,
            episodes = epList,
            progressLastEpId = progressLastEpId,
        )
    }

    suspend fun recommend(
        freshIdx: Int = 1,
        ps: Int = 20,
        fetchRow: Int = 1,
    ): List<VideoCard> {
        val keys = BiliClient.ensureWbiKeys()
        val url = BiliClient.signedWbiUrl(
            path = "/x/web-interface/wbi/index/top/feed/rcmd",
            params = mapOf(
                "ps" to ps.toString(),
                "fresh_idx" to freshIdx.toString(),
                "fresh_idx_1h" to freshIdx.toString(),
                "fetch_row" to fetchRow.toString(),
                "feed_version" to "V8",
            ),
            keys = keys,
        )
        val json = BiliClient.getJson(url)
        val items = json.optJSONObject("data")?.optJSONArray("item") ?: JSONArray()
        AppLog.d(TAG, "recommend items=${items.length()}")
        return withContext(Dispatchers.Default) { parseVideoCards(items) }
    }

    suspend fun popular(pn: Int = 1, ps: Int = 20): List<VideoCard> {
        val url = BiliClient.withQuery(
            "https://api.bilibili.com/x/web-interface/popular",
            mapOf("pn" to pn.toString(), "ps" to ps.toString()),
        )
        val json = BiliClient.getJson(url)
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        AppLog.d(TAG, "popular list=${list.length()}")
        return withContext(Dispatchers.Default) { parseVideoCards(list) }
    }

    suspend fun regionLatest(rid: Int, pn: Int = 1, ps: Int = 20): List<VideoCard> {
        val url = BiliClient.withQuery(
            "https://api.bilibili.com/x/web-interface/dynamic/region",
            mapOf("rid" to rid.toString(), "pn" to pn.toString(), "ps" to ps.toString()),
        )
        val json = BiliClient.getJson(url)
        val archives = json.optJSONObject("data")?.optJSONArray("archives") ?: JSONArray()
        AppLog.d(TAG, "region rid=$rid archives=${archives.length()}")
        return withContext(Dispatchers.Default) { parseVideoCards(archives) }
    }

    suspend fun view(bvid: String): JSONObject {
        val url = BiliClient.withQuery(
            "https://api.bilibili.com/x/web-interface/view",
            mapOf("bvid" to bvid),
        )
        return BiliClient.getJson(url)
    }

    suspend fun onlineTotal(bvid: String, cid: Long): JSONObject {
        val url = BiliClient.withQuery(
            "https://api.bilibili.com/x/player/online/total",
            mapOf("bvid" to bvid, "cid" to cid.toString()),
        )
        return BiliClient.getJson(url, headers = piliWebHeaders(includeCookie = true), noCookies = true)
    }

    suspend fun playUrlDash(bvid: String, cid: Long, qn: Int = 80, fnval: Int = 16): JSONObject {
        val keys = BiliClient.ensureWbiKeys()
        val hasSessData = BiliClient.cookies.hasSessData()
        @Suppress("UNUSED_VARIABLE")
        val requestedFnval = fnval
        val params =
            mutableMapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                "qn" to qn.toString(),
                "fnver" to "0",
                "fnval" to "4048",
                "fourk" to "1",
                "voice_balance" to "1",
                "web_location" to "1315873",
                "gaia_source" to "pre-load",
                "isGaiaAvoided" to "true",
            )
        if (!hasSessData) {
            params["try_look"] = "1"
        }
        return requestPlayUrl(
            path = "/x/player/wbi/playurl",
            params = params,
            keys = keys,
            headers = piliWebHeaders(includeCookie = true),
            noCookies = true,
        )
    }

    suspend fun pgcPlayUrl(
        bvid: String,
        cid: Long? = null,
        epId: Long? = null,
        qn: Int = 80,
        fnval: Int = 16,
    ): JSONObject {
        WebCookieMaintainer.ensureWebFingerprintCookies()
        WebCookieMaintainer.ensureDailyMaintenance()
        val params =
            mutableMapOf(
                "bvid" to bvid,
                "qn" to qn.toString(),
                "fnver" to "0",
                "fnval" to fnval.toString(),
                "fourk" to "1",
                "from_client" to "BROWSER",
                "drm_tech_type" to "2",
            )
        cid?.takeIf { it > 0 }?.let { params["cid"] = it.toString() }
        epId?.takeIf { it > 0 }?.let { params["ep_id"] = it.toString() }

        fun hasVVoucher(json: JSONObject): Boolean {
            val data = json.optJSONObject("data") ?: json.optJSONObject("result") ?: JSONObject()
            return data.optString("v_voucher", "").trim().isNotBlank()
        }

        suspend fun request(params: Map<String, String>, includeCookie: Boolean): JSONObject {
            val url = BiliClient.withQuery("https://api.bilibili.com/pgc/player/web/playurl", params)
            val json = BiliClient.getJson(url, headers = piliWebHeaders(includeCookie = includeCookie), noCookies = true)
            val code = json.optInt("code", 0)
            if (code != 0) {
                val msg = json.optString("message", json.optString("msg", ""))
                throw BiliApiException(apiCode = code, apiMessage = msg)
            }
            val result = json.optJSONObject("result") ?: JSONObject()
            if (json.optJSONObject("data") == null) json.put("data", result)
            if (hasVVoucher(json)) error("risk_control_voucher")
            return json
        }

        return try {
            request(params = params, includeCookie = true)
        } catch (t: Throwable) {
            // Final fallback: noCookies + try_look=1 (attempt preview without login cookies).
            val fallback = params.toMutableMap()
            fallback["try_look"] = "1"
            request(params = fallback, includeCookie = false)
        }
    }

    suspend fun playerWbiV2(bvid: String, cid: Long): JSONObject {
        WebCookieMaintainer.ensureWebFingerprintCookies()
        val keys = BiliClient.ensureWbiKeys()
        val params =
            mutableMapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
            )
        val url = BiliClient.signedWbiUrl(path = "/x/player/wbi/v2", params = params, keys = keys)
        return try {
            BiliClient.getJson(url, headers = piliWebHeaders(includeCookie = true), noCookies = true)
        } catch (t: Throwable) {
            // Final fallback: noCookies + try_look=1.
            params["try_look"] = "1"
            val fallbackUrl = BiliClient.signedWbiUrl(path = "/x/player/wbi/v2", params = params, keys = keys)
            BiliClient.getJson(fallbackUrl, headers = piliWebHeaders(includeCookie = false), noCookies = true)
        }
    }

    suspend fun dmSeg(cid: Long, segmentIndex: Int): List<Danmaku> {
        val url = BiliClient.withQuery(
            "https://api.bilibili.com/x/v2/dm/web/seg.so",
            mapOf(
                "type" to "1",
                "oid" to cid.toString(),
                "segment_index" to segmentIndex.toString(),
            ),
        )
        val bytes = BiliClient.getBytes(url, headers = piliWebHeaders(includeCookie = true), noCookies = true)
        val reply = DmSegMobileReply.parseFrom(bytes)
        val list = reply.elemsList.mapNotNull { e ->
            val text = e.content ?: return@mapNotNull null
            Danmaku(
                timeMs = e.progress,
                mode = e.mode,
                text = text,
                color = e.color.toInt(),
                fontSize = e.fontsize,
                weight = e.weight,
            )
        }
        AppLog.d(TAG, "dmSeg cid=$cid seg=$segmentIndex size=${list.size} state=${reply.state}")
        return list
    }

    suspend fun dmWebView(cid: Long, aid: Long? = null): DanmakuWebView {
        val params = mutableMapOf(
            "type" to "1",
            "oid" to cid.toString(),
        )
        if (aid != null && aid > 0) params["pid"] = aid.toString()
        val url = BiliClient.withQuery("https://api.bilibili.com/x/v2/dm/web/view", params)
        val bytes = BiliClient.getBytes(url, headers = piliWebHeaders(includeCookie = true), noCookies = true)
        val reply = DmWebViewReply.parseFrom(bytes)

        val seg = reply.dmSge
        val segTotal = seg.total.coerceAtLeast(0).toInt()
        val pageSizeMs = seg.pageSize.coerceAtLeast(0)

        val setting = if (reply.hasDmSetting()) {
            val s = reply.dmSetting
            val aiLevel = when (s.aiLevel) {
                0 -> 3 // 0 表示默认等级（通常为 3）
                else -> s.aiLevel.coerceIn(0, 10)
            }
            DanmakuWebSetting(
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
        AppLog.d(TAG, "dmWebView cid=$cid segTotal=$segTotal pageSizeMs=$pageSizeMs hasSetting=${setting != null}")
        return DanmakuWebView(
            segmentTotal = segTotal,
            segmentPageSizeMs = pageSizeMs,
            count = reply.count,
            setting = setting,
        )
    }

    private fun parseVideoCards(arr: JSONArray): List<VideoCard> {
        val out = ArrayList<VideoCard>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val bvid = obj.optString("bvid", "")
            if (bvid.isBlank()) continue
            val owner = obj.optJSONObject("owner") ?: JSONObject()
            val stat = obj.optJSONObject("stat") ?: JSONObject()
            out.add(
                VideoCard(
                    bvid = bvid,
                    cid = obj.optLong("cid").takeIf { it > 0 },
                    title = obj.optString("title", ""),
                    coverUrl = obj.optString("pic", obj.optString("cover", "")),
                    durationSec = obj.optInt("duration", parseDuration(obj.optString("duration_text", "0:00"))),
                    ownerName = owner.optString("name", ""),
                    ownerFace = owner.optString("face").takeIf { it.isNotBlank() },
                    view = stat.optLong("view").takeIf { it > 0 } ?: stat.optLong("play").takeIf { it > 0 },
                    danmaku = stat.optLong("danmaku").takeIf { it > 0 } ?: stat.optLong("dm").takeIf { it > 0 },
                    pubDate = obj.optLong("pubdate").takeIf { it > 0 },
                    pubDateText = null,
                ),
            )
        }
        return out
    }

    private fun parseSearchVideoCards(arr: JSONArray): List<VideoCard> {
        val out = ArrayList<VideoCard>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val bvid = obj.optString("bvid", "")
            if (bvid.isBlank()) continue
            val title = stripHtmlTags(obj.optString("title", ""))
            out.add(
                VideoCard(
                    bvid = bvid,
                    cid = null,
                    title = title,
                    coverUrl = obj.optString("pic", ""),
                    durationSec = parseDuration(obj.optString("duration", "0:00")),
                    ownerName = obj.optString("author", ""),
                    ownerFace = null,
                    view = obj.optLong("play").takeIf { it > 0 },
                    danmaku = obj.optLong("video_review").takeIf { it > 0 },
                    pubDate = obj.optLong("pubdate").takeIf { it > 0 },
                    pubDateText = null,
                ),
            )
        }
        return out
    }

    private fun stripHtmlTags(s: String): String {
        if (s.indexOf('<') < 0) return s
        return s.replace(Regex("<[^>]*>"), "")
    }

    private fun parseDuration(durationText: String): Int {
        val parts = durationText.split(":")
        if (parts.isEmpty()) return 0
        return try {
            when (parts.size) {
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                2 -> parts[0].toInt() * 60 + parts[1].toInt()
                else -> parts[0].toInt()
            }
        } catch (_: Throwable) {
            0
        }
    }

    private fun parseCountText(text: String): Long? {
        val s = text.trim()
        if (s.isBlank()) return null
        val multiplier = when {
            s.contains("亿") -> 100_000_000L
            s.contains("万") -> 10_000L
            else -> 1L
        }
        val numText = s.replace(Regex("[^0-9.]"), "")
        if (numText.isBlank()) return null
        val value = numText.toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        return (value * multiplier).roundToLong()
    }

    private suspend fun ensureSearchCookies(force: Boolean = false) {
        if (!force && !BiliClient.cookies.getCookieValue("buvid3").isNullOrBlank()) return
        runCatching { BiliClient.getBytes("https://www.bilibili.com/") }
    }

    suspend fun followings(vmid: Long, pn: Int = 1, ps: Int = 20): List<Following> {
        val url = BiliClient.withQuery(
            "https://api.bilibili.com/x/relation/followings",
            mapOf("vmid" to vmid.toString(), "pn" to pn.toString(), "ps" to ps.toString()),
        )
        val json = BiliClient.getJson(
            url,
            headers = mapOf(
                "Referer" to "https://www.bilibili.com/",
            ),
        )
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        return withContext(Dispatchers.Default) {
            val out = ArrayList<Following>(list.length())
            for (i in 0 until list.length()) {
                val obj = list.optJSONObject(i) ?: continue
                out.add(
                    Following(
                        mid = obj.optLong("mid"),
                        name = obj.optString("uname", ""),
                        avatarUrl = obj.optString("face").takeIf { it.isNotBlank() },
                    ),
                )
            }
            AppLog.d(TAG, "followings vmid=$vmid size=${out.size}")
            out
        }
    }

    data class DynamicPage(
        val items: List<VideoCard>,
        val nextOffset: String?,
    )

    suspend fun dynamicAllVideo(offset: String? = null): DynamicPage {
        val params = mutableMapOf(
            "type" to "video",
            "platform" to "web",
            "features" to "itemOpusStyle,listOnlyfans,opusBigCover",
        )
        if (!offset.isNullOrBlank()) params["offset"] = offset
        val url = BiliClient.withQuery("https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all", params)
        val json = BiliClient.getJson(url)
        val data = json.optJSONObject("data") ?: JSONObject()
        val items = data.optJSONArray("items") ?: JSONArray()
        return withContext(Dispatchers.Default) {
            val cards = ArrayList<VideoCard>()
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val modules = it.optJSONObject("modules") ?: continue
                val moduleDynamic = modules.optJSONObject("module_dynamic") ?: continue
                val major = moduleDynamic.optJSONObject("major") ?: continue
                val archive = major.optJSONObject("archive") ?: continue
                val bvid = archive.optString("bvid", "")
                if (bvid.isBlank()) continue

                val author = modules.optJSONObject("module_author")
                val ownerName = author?.optString("name", "") ?: ""
                val ownerFace = author?.optString("face")?.takeIf { it.isNotBlank() }
                val stat = archive.optJSONObject("stat") ?: JSONObject()
                val pubDate = archive.optLong("pubdate").takeIf { it > 0 } ?: author?.optLong("pub_ts")?.takeIf { it > 0 }
                cards.add(
                    VideoCard(
                        bvid = bvid,
                        cid = null,
                        title = archive.optString("title", ""),
                        coverUrl = archive.optString("cover", ""),
                        durationSec = parseDuration(archive.optString("duration_text", "0:00")),
                        ownerName = ownerName,
                        ownerFace = ownerFace,
                        view = parseCountText(stat.optString("play", "")),
                        danmaku = parseCountText(stat.optString("danmaku", "")),
                        pubDate = pubDate,
                        pubDateText = null,
                    ),
                )
            }
            val next = data.optString("offset", "").takeIf { it.isNotBlank() }
            AppLog.d(TAG, "dynamicAllVideo size=${cards.size} nextOffset=${next?.take(8)}")
            DynamicPage(cards, next)
        }
    }

    suspend fun dynamicSpaceVideo(hostMid: Long, offset: String? = null): DynamicPage {
        val params = mutableMapOf(
            "host_mid" to hostMid.toString(),
            "platform" to "web",
            "features" to "itemOpusStyle,listOnlyfans,opusBigCover",
        )
        if (!offset.isNullOrBlank()) params["offset"] = offset
        val url = BiliClient.withQuery("https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space", params)
        val json = BiliClient.getJson(url)
        val data = json.optJSONObject("data") ?: JSONObject()
        val items = data.optJSONArray("items") ?: JSONArray()
        return withContext(Dispatchers.Default) {
            val cards = ArrayList<VideoCard>()
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val modules = it.optJSONObject("modules") ?: continue
                val moduleDynamic = modules.optJSONObject("module_dynamic") ?: continue
                val major = moduleDynamic.optJSONObject("major") ?: continue
                val archive = major.optJSONObject("archive") ?: continue
                val bvid = archive.optString("bvid", "")
                if (bvid.isBlank()) continue

                val author = modules.optJSONObject("module_author")
                val ownerName = author?.optString("name", "") ?: ""
                val ownerFace = author?.optString("face")?.takeIf { it.isNotBlank() }
                val stat = archive.optJSONObject("stat") ?: JSONObject()
                val pubDate = archive.optLong("pubdate").takeIf { it > 0 } ?: author?.optLong("pub_ts")?.takeIf { it > 0 }
                cards.add(
                    VideoCard(
                        bvid = bvid,
                        cid = null,
                        title = archive.optString("title", ""),
                        coverUrl = archive.optString("cover", ""),
                        durationSec = parseDuration(archive.optString("duration_text", "0:00")),
                        ownerName = ownerName,
                        ownerFace = ownerFace,
                        view = parseCountText(stat.optString("play", "")),
                        danmaku = parseCountText(stat.optString("danmaku", "")),
                        pubDate = pubDate,
                        pubDateText = null,
                    ),
                )
            }
            val next = data.optString("offset", "").takeIf { it.isNotBlank() }
            AppLog.d(TAG, "dynamicSpaceVideo hostMid=$hostMid size=${cards.size} nextOffset=${next?.take(8)}")
            DynamicPage(cards, next)
        }
    }

    private fun genPlayUrlSession(nowMs: Long = System.currentTimeMillis()): String? {
        val buvid3 = BiliClient.cookies.getCookieValue("buvid3")?.takeIf { it.isNotBlank() } ?: return null
        return md5Hex(buvid3 + nowMs.toString())
    }

    private suspend fun requestPlayUrl(
        path: String,
        params: Map<String, String>,
        keys: blbl.cat3399.core.net.WbiSigner.Keys,
        headers: Map<String, String> = emptyMap(),
        noCookies: Boolean = false,
    ): JSONObject {
        val url = BiliClient.signedWbiUrl(path = path, params = params, keys = keys)
        val json = BiliClient.getJson(url, headers = headers, noCookies = noCookies)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        return json
    }

    private fun md5Hex(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format(Locale.US, "%02x", b))
        return sb.toString()
    }
}
