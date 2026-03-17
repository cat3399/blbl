package blbl.cat3399.core.api

import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiEpisode
import blbl.cat3399.core.model.BangumiEpisodeSection
import blbl.cat3399.core.model.BangumiSeason
import blbl.cat3399.core.model.BangumiSeasonDetail
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.model.DanmakuUserFilter
import blbl.cat3399.core.model.FavFolder
import blbl.cat3399.core.model.Following
import blbl.cat3399.core.model.LiveAreaParent
import blbl.cat3399.core.model.LiveRoomCard
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.model.VideoTag
import android.util.Base64
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.WebCookieMaintainer
import blbl.cat3399.core.util.Format
import blbl.cat3399.core.util.parseBangumiRedirectUrl
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

    // Used by APP-side PGC aggregation endpoints like /pgc/page/bangumi and /pgc/page/cinema/tab.
    // The response schema/modules may vary by build; keep this as a reasonably new default.
    private const val PGC_PAGE_BUILD_DEFAULT = 8130300
    private const val PGC_PAGE_MOBI_APP_DEFAULT = "android"

    // https://www.bilibili.com/read/cv11815732
    private const val BV_XOR = 177451812L
    private const val BV_ADD = 8728348608L
    private val BV_TABLE = "fZodR9XQDSUm21yCkr6zBqiveYah8bt4xsWpHnJE7jL5VG3guMTKNPAwcF"
    private val BV_POS = intArrayOf(11, 10, 3, 8, 4, 6)
    private val BV_REGEX = Regex("BV[0-9A-Za-z]{10}")

    private fun extractVVoucher(json: JSONObject): String? {
        val data = json.optJSONObject("data") ?: json.optJSONObject("result") ?: return null
        return data.optString("v_voucher", "").trim().takeIf { it.isNotBlank() }
    }

    internal fun piliWebHeaders(targetUrl: String, includeCookie: Boolean = true): Map<String, String> {
        val out = piliHeaders(includeMid = includeCookie).toMutableMap()
        out["Referer"] = PILI_REFERER
        // Tell OkHttp interceptor to not add Origin (PiliPlus does not send it for this flow).
        out["X-Blbl-Skip-Origin"] = "1"
        if (includeCookie) {
            val cookie = BiliClient.cookies.cookieHeaderFor(targetUrl)
            if (!cookie.isNullOrBlank()) out["Cookie"] = cookie
        }
        return out
    }

    private fun piliHeaders(includeMid: Boolean = true): Map<String, String> {
        val headers =
            mutableMapOf(
                "env" to "prod",
                "app-key" to "android64",
                "x-bili-aurora-zone" to "sh001",
            )
        if (!includeMid) return headers
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

    data class GaiaVgateRegister(
        val gt: String,
        val challenge: String,
        val token: String,
    )

    suspend fun gaiaVgateRegister(vVoucher: String): GaiaVgateRegister {
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty()
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/gaia-vgate/v1/register",
                if (csrf.isNotBlank()) mapOf("csrf" to csrf) else emptyMap(),
            )
        val json =
            BiliClient.postFormJson(
                url,
                form = mapOf("v_voucher" to vVoucher),
                headers = piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val token = data.optString("token", "").trim()
        val geetest = data.optJSONObject("geetest") ?: JSONObject()
        val gt = geetest.optString("gt", "").trim()
        val challenge = geetest.optString("challenge", "").trim()
        if (token.isBlank() || gt.isBlank() || challenge.isBlank()) error("gaia_vgate_register_invalid_data")
        return GaiaVgateRegister(gt = gt, challenge = challenge, token = token)
    }

    suspend fun gaiaVgateValidate(
        token: String,
        geetestChallenge: String,
        validate: String,
        seccode: String,
    ): String {
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty()
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/gaia-vgate/v1/validate",
                if (csrf.isNotBlank()) mapOf("csrf" to csrf) else emptyMap(),
            )
        val json =
            BiliClient.postFormJson(
                url,
                form =
                    mapOf(
                        "token" to token,
                        "challenge" to geetestChallenge,
                        "validate" to validate,
                        "seccode" to seccode,
                    ),
                headers = piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val isValid = data.optInt("is_valid", 0)
        if (isValid != 1) error("gaia_vgate_validate_invalid")
        return data.optString("grisk_id", "").trim().takeIf { it.isNotBlank() } ?: error("gaia_vgate_validate_missing_grisk_id")
    }

    data class PagedResult<T>(
        val items: List<T>,
        val page: Int,
        val pages: Int,
        val total: Int,
    )

    data class CursorPage<T>(
        val items: List<T>,
        val hasNext: Boolean,
        val nextCursor: String?,
    )

    data class RelationStat(
        val following: Long,
        val follower: Long,
    )

    data class SpaceUpStat(
        val archiveView: Long,
        val articleView: Long?,
        val likes: Long,
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

    data class SpaceAccInfo(
        val mid: Long,
        val name: String,
        val faceUrl: String?,
        val sign: String?,
        val isFollowed: Boolean,
    )

    data class HasMorePage<T>(
        val items: List<T>,
        val page: Int,
        val hasMore: Boolean,
        val total: Int,
    )

    data class PgcFollowActionResult(
        val status: Int?,
        val toast: String?,
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

    data class VideoShotInfo(
        val pvData: String?,
        val imgXLen: Int,
        val imgYLen: Int,
        val imgXSize: Int,
        val imgYSize: Int,
        val image: List<String>,
        val index: List<UShort>?,
    )

    suspend fun nav(): JSONObject {
        return BiliClient.getJson("https://api.bilibili.com/x/web-interface/nav")
    }

    suspend fun searchDefaultText(): String? = SearchApi.searchDefaultText()

    suspend fun searchHot(limit: Int = 10): List<String> = SearchApi.searchHot(limit = limit)

    suspend fun searchSuggest(term: String): List<String> = SearchApi.searchSuggest(term = term)

    suspend fun searchVideo(
        keyword: String,
        page: Int = 1,
        order: String = "totalrank",
    ): PagedResult<VideoCard> {
        return SearchApi.searchVideo(keyword = keyword, page = page, order = order)
    }

    suspend fun searchMediaFt(
        keyword: String,
        page: Int = 1,
        order: String = "totalrank",
    ): PagedResult<BangumiSeason> {
        return SearchApi.searchMediaFt(keyword = keyword, page = page, order = order)
    }

    suspend fun searchMediaBangumi(
        keyword: String,
        page: Int = 1,
        order: String = "totalrank",
    ): PagedResult<BangumiSeason> {
        return SearchApi.searchMediaBangumi(keyword = keyword, page = page, order = order)
    }

    suspend fun searchLiveRoom(
        keyword: String,
        page: Int = 1,
        order: String = "online",
    ): PagedResult<LiveRoomCard> {
        return SearchApi.searchLiveRoom(keyword = keyword, page = page, order = order)
    }

    suspend fun searchUser(
        keyword: String,
        page: Int = 1,
        order: String = "0",
        orderSort: Int = 0,
        userType: Int = 0,
    ): PagedResult<Following> {
        return SearchApi.searchUser(
            keyword = keyword,
            page = page,
            order = order,
            orderSort = orderSort,
            userType = userType,
        )
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

    suspend fun spaceUpStat(mid: Long): SpaceUpStat {
        val safeMid = mid.takeIf { it > 0 } ?: error("mid required")
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/space/upstat",
                mapOf("mid" to safeMid.toString()),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        if (data.length() <= 0) error("space_upstat_empty")

        val archiveView =
            data.optJSONObject("archive")
                ?.optLong("view")
                ?.takeIf { it >= 0 }
                ?: 0L
        val articleView =
            data.optJSONObject("article")
                ?.optLong("view")
                ?.takeIf { it >= 0 }
        val likesAny = data.opt("likes")
        val likes =
            when (likesAny) {
                is Number -> likesAny.toLong().coerceAtLeast(0)
                is String -> likesAny.toLongOrNull()?.coerceAtLeast(0) ?: 0L
                else -> data.optLong("likes").coerceAtLeast(0)
            }

        return SpaceUpStat(
            archiveView = archiveView,
            articleView = articleView,
            likes = likes,
        )
    }

    suspend fun liveAreas(force: Boolean = false): List<LiveAreaParent> = LiveApi.liveAreas(force = force)

    suspend fun liveRecommend(page: Int = 1): List<LiveRoomCard> = LiveApi.liveRecommend(page = page)

    suspend fun liveAreaRooms(
        parentAreaId: Int,
        areaId: Int,
        page: Int = 1,
        pageSize: Int = 30,
        sortType: String = "online",
    ): List<LiveRoomCard> {
        return LiveApi.liveAreaRooms(
            parentAreaId = parentAreaId,
            areaId = areaId,
            page = page,
            pageSize = pageSize,
            sortType = sortType,
        )
    }

    suspend fun liveFollowing(page: Int = 1, pageSize: Int = 10): HasMorePage<LiveRoomCard> {
        return LiveApi.liveFollowing(page = page, pageSize = pageSize)
    }

    suspend fun liveRoomInfo(roomId: Long): LiveRoomInfo = LiveApi.liveRoomInfo(roomId = roomId)

    suspend fun livePlayUrl(roomId: Long, qn: Int, highBitrateEnabled: Boolean): LivePlayUrl =
        LiveApi.livePlayUrl(roomId = roomId, qn = qn, highBitrateEnabled = highBitrateEnabled)

    suspend fun liveDanmuInfo(roomId: Long): LiveDanmuInfo = LiveApi.liveDanmuInfo(roomId = roomId)

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
                    if (businessType != "archive" && businessType != "pgc") continue

                    val bvid = history.optString("bvid", "").trim()
                    val aid = history.optLong("oid").takeIf { v -> v > 0 }
                    val cid = history.optLong("cid").takeIf { v -> v > 0 }
                    val epId = history.optLong("epid").takeIf { v -> v > 0 }
                    val seasonId =
                        if (businessType == "pgc") {
                            it.optLong("kid").takeIf { v -> v > 0 }
                                ?: parseBangumiRedirectUrl(it.optString("uri", ""))?.seasonId
                        } else {
                            null
                        }

                    // For archive items bvid should exist; for PGC items bvid may be absent so we fall back to aid/epId.
                    if (businessType == "archive" && bvid.isBlank()) continue
                    if (businessType == "pgc" && (epId == null || cid == null) && (bvid.isBlank() && aid == null)) continue

                    val covers = it.optJSONArray("covers")
                    val coverUrl =
                        it.optString("cover", "").takeIf { s -> s.isNotBlank() }
                            ?: covers?.optString(0)?.takeIf { s -> s.isNotBlank() }
                            ?: ""

                    val viewAtSec = it.optLong("view_at").takeIf { v -> v > 0 }
                    val progressSec = it.optLong("progress").takeIf { v -> v > 0 }
                    val showTitle = it.optString("show_title", "").trim().takeIf { s -> s.isNotBlank() }
                    val badge = it.optString("badge", "").trim().takeIf { s -> s.isNotBlank() }
                    val subtitleParts = buildList {
                        viewAtSec?.let { add(Format.timeText(it)) }
                        badge?.let { add(it) }
                        showTitle?.let { add(it) }
                    }
                    val subtitle = subtitleParts.joinToString(" · ").takeIf { s -> s.isNotBlank() }
                    out.add(
                        VideoCard(
                            bvid = bvid,
                            cid = cid,
                            aid = aid,
                            epId = epId,
                            seasonId = seasonId,
                            business = businessType.takeIf { s -> s.isNotBlank() },
                            title = it.optString("title", ""),
                            coverUrl = coverUrl,
                            durationSec = it.optInt("duration", 0),
                            ownerName = it.optString("author_name", ""),
                            ownerFace = it.optString("author_face").takeIf { s -> s.isNotBlank() },
                            ownerMid =
                                it.optLong("author_mid").takeIf { v -> v > 0 }
                                    ?: it.optLong("mid").takeIf { v -> v > 0 },
                            view = null,
                            danmaku = null,
                            pubDate = null,
                            pubDateText = subtitle,
                            progressSec = progressSec,
                        ),
                    )
                }
                out
            }
        return HistoryPage(items = cards, cursor = cursor)
    }

    suspend fun toViewList(): List<VideoCard> = VideoApi.toViewList()

    suspend fun spaceLikeVideoList(vmid: Long): List<VideoCard> = VideoApi.spaceLikeVideoList(vmid = vmid)

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

    data class FavFolderWithState(
        val mediaId: Long,
        val title: String,
        val mediaCount: Int,
        val favState: Boolean,
    )

    suspend fun favFoldersWithState(upMid: Long, rid: Long): List<FavFolderWithState> {
        if (upMid <= 0L) error("fav_folders_with_state_invalid_up_mid")
        if (rid <= 0L) error("fav_folders_with_state_invalid_rid")
        val url = BiliClient.withQuery(
            "https://api.bilibili.com/x/v3/fav/folder/created/list-all",
            mapOf(
                "up_mid" to upMid.toString(),
                "type" to "2",
                "rid" to rid.toString(),
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
        return withContext(Dispatchers.Default) {
            val out = ArrayList<FavFolderWithState>(list.length())
            for (i in 0 until list.length()) {
                val obj = list.optJSONObject(i) ?: continue
                val mediaId = obj.optLong("id").takeIf { it > 0 } ?: continue
                val favState = obj.optInt("fav_state", 0) == 1
                out.add(
                    FavFolderWithState(
                        mediaId = mediaId,
                        title = obj.optString("title", ""),
                        mediaCount = obj.optInt("media_count", 0),
                        favState = favState,
                    ),
                )
            }
            out
        }
    }

    suspend fun favResourceDeal(
        rid: Long,
        addMediaIds: List<Long>,
        delMediaIds: List<Long>,
    ) = VideoApi.favResourceDeal(rid = rid, addMediaIds = addMediaIds, delMediaIds = delMediaIds)

    suspend fun archiveHasLike(
        bvid: String? = null,
        aid: Long? = null,
    ) = VideoApi.archiveHasLike(bvid = bvid, aid = aid)

    suspend fun archiveCoins(
        bvid: String? = null,
        aid: Long? = null,
    ) = VideoApi.archiveCoins(bvid = bvid, aid = aid)

    suspend fun archiveFavoured(
        bvid: String? = null,
        aid: Long? = null,
    ) = VideoApi.archiveFavoured(bvid = bvid, aid = aid)

    suspend fun archiveLike(
        bvid: String? = null,
        aid: Long? = null,
        like: Boolean,
    ) = VideoApi.archiveLike(bvid = bvid, aid = aid, like = like)

    suspend fun coinAdd(
        bvid: String? = null,
        aid: Long? = null,
        multiply: Int = 1,
        selectLike: Boolean = false,
    ) = VideoApi.coinAdd(bvid = bvid, aid = aid, multiply = multiply, selectLike = selectLike)

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
                            ownerMid = upper.optLong("mid").takeIf { it > 0 },
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
                            badge =
                                normalizeBadgeText(obj.optString("badge"))
                                    ?: normalizeBadgeText(obj.optJSONObject("badge_info")?.optString("text"))
                                    ?: normalizeBadgeText(obj.optJSONObject("badgeInfo")?.optString("text")),
                            badgeEp =
                                normalizeBadgeText(obj.optString("badge_ep"))
                                    ?: normalizeBadgeText(obj.optString("badgeEp"))
                                    ?: normalizeBadgeText(obj.optJSONObject("badge_ep_info")?.optString("text"))
                                    ?: normalizeBadgeText(obj.optJSONObject("badgeEpInfo")?.optString("text"))
                                    ?: obj.optJSONObject("new_ep")?.let { newEp ->
                                        normalizeBadgeText(newEp.optString("badge"))
                                            ?: normalizeBadgeText(newEp.optJSONObject("badge_info")?.optString("text"))
                                            ?: normalizeBadgeText(newEp.optJSONObject("badgeInfo")?.optString("text"))
                                    },
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
        val safeSeasonId = seasonId.takeIf { it > 0L } ?: error("seasonId required")
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/pgc/view/web/season",
                mapOf("season_id" to safeSeasonId.toString()),
            )
        return bangumiSeasonDetailInner(url = url, seasonId = safeSeasonId, epId = null)
    }

    suspend fun bangumiSeasonDetailByEpId(epId: Long): BangumiSeasonDetail {
        val safeEpId = epId.takeIf { it > 0L } ?: error("epId required")
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/pgc/view/web/season",
                mapOf("ep_id" to safeEpId.toString()),
            )
        return bangumiSeasonDetailInner(url = url, seasonId = null, epId = safeEpId)
    }

    suspend fun pgcFollowAdd(seasonId: Long): PgcFollowActionResult {
        return pgcFollowAction(
            url = "https://api.bilibili.com/pgc/web/follow/add",
            seasonId = seasonId,
        )
    }

    suspend fun pgcFollowDel(seasonId: Long): PgcFollowActionResult {
        return pgcFollowAction(
            url = "https://api.bilibili.com/pgc/web/follow/del",
            seasonId = seasonId,
        )
    }

    private suspend fun pgcFollowAction(
        url: String,
        seasonId: Long,
    ): PgcFollowActionResult {
        val safeSeasonId = seasonId.takeIf { it > 0L } ?: error("seasonId required")

        WebCookieMaintainer.ensureWebFingerprintCookies()
        WebCookieMaintainer.ensureBuvidActiveOncePerDay()
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")

        val form =
            mapOf(
                "season_id" to safeSeasonId.toString(),
                "csrf" to csrf,
            )
        val json =
            BiliClient.postFormJson(
                url,
                form = form,
                headers = piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }

        val result = json.optJSONObject("result") ?: JSONObject()
        val status = result.optInt("status", -1).takeIf { it >= 0 }
        val toast = result.optString("toast", "").trim().takeIf { it.isNotBlank() }
        return PgcFollowActionResult(status = status, toast = toast)
    }

    private suspend fun bangumiSeasonDetailInner(
        url: String,
        seasonId: Long?,
        epId: Long?,
    ): BangumiSeasonDetail {
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
        val sections = result.optJSONArray("section") ?: JSONArray()
        val parsed =
            withContext(Dispatchers.Default) {
                val seen = HashSet<Long>(episodes.length() * 3 + 64)

                fun parseEpisode(ep: JSONObject): BangumiEpisode? {
                    val parsedEpId = ep.optLong("id").takeIf { it > 0 } ?: ep.optLong("ep_id").takeIf { it > 0 } ?: return null
                    val badge =
                        normalizeBadgeText(ep.optString("badge"))
                            ?: normalizeBadgeText(ep.optJSONObject("badge_info")?.optString("text"))
                            ?: normalizeBadgeText(ep.optJSONObject("badgeInfo")?.optString("text"))
                    return BangumiEpisode(
                        epId = parsedEpId,
                        aid = ep.optLong("aid").takeIf { it > 0 } ?: ep.optLong("avid").takeIf { it > 0 },
                        cid = ep.optLong("cid").takeIf { it > 0 },
                        bvid = ep.optString("bvid").takeIf { it.isNotBlank() },
                        title = ep.optString("title", ""),
                        longTitle = ep.optString("long_title", ""),
                        coverUrl = ep.optString("cover").takeIf { it.isNotBlank() },
                        badge = badge,
                    )
                }

                val mainEpisodes = ArrayList<BangumiEpisode>(episodes.length())
                val extraEpisodesFromRoot = ArrayList<BangumiEpisode>(32)
                for (i in 0 until episodes.length()) {
                    val ep = episodes.optJSONObject(i) ?: continue
                    val parsedEpisode = parseEpisode(ep) ?: continue
                    if (isPgcMainEpisodeFromSeasonEpisodes(ep = ep, normalizedBadge = parsedEpisode.badge)) {
                        if (!seen.add(parsedEpisode.epId)) continue
                        mainEpisodes.add(parsedEpisode)
                    } else {
                        extraEpisodesFromRoot.add(parsedEpisode)
                    }
                }

                val extraSections = ArrayList<BangumiEpisodeSection>(sections.length() + 1)
                for (i in 0 until sections.length()) {
                    val section = sections.optJSONObject(i) ?: continue
                    val sectionTitle = section.optString("title", "").trim()
                    val sectionEpisodes = section.optJSONArray("episodes") ?: continue
                    val parsedEpisodes = ArrayList<BangumiEpisode>(sectionEpisodes.length())
                    for (j in 0 until sectionEpisodes.length()) {
                        val ep = sectionEpisodes.optJSONObject(j) ?: continue
                        val parsedEpisode = parseEpisode(ep) ?: continue
                        if (!seen.add(parsedEpisode.epId)) continue
                        parsedEpisodes.add(parsedEpisode)
                    }
                    if (parsedEpisodes.isEmpty()) {
                        continue
                    }
                    extraSections.add(
                        BangumiEpisodeSection(
                            title = sectionTitle,
                            episodes = parsedEpisodes,
                        ),
                    )
                }

                val rootOnlyExtras = ArrayList<BangumiEpisode>(extraEpisodesFromRoot.size)
                for (episode in extraEpisodesFromRoot) {
                    if (!seen.add(episode.epId)) continue
                    rootOnlyExtras.add(episode)
                }
                if (rootOnlyExtras.isNotEmpty()) {
                    extraSections.add(
                        BangumiEpisodeSection(
                            title = "其他内容",
                            episodes = rootOnlyExtras,
                        ),
                    )
                }

                mainEpisodes to extraSections
            }

        val userProgress = result.optJSONObject("user_status")?.optJSONObject("progress") ?: JSONObject()
        val rawUserLastEpId = userProgress.optLong("last_ep_id", -1L).takeIf { it > 0 }
        val rawUserLastEpid = userProgress.optLong("last_epid", -1L).takeIf { it > 0 }
        val rawUserLastTime = userProgress.optLong("last_time", -1L).takeIf { it > 0 }
        val rawUserLastEpIndex =
            userProgress.optInt("last_ep_index", -1).takeIf { it > 0 }
                ?: userProgress.optString("last_ep_index", "").trim().toIntOrNull()?.takeIf { it > 0 }
        val rawProgressLastEpId = result.optJSONObject("progress")?.optLong("last_ep_id", -1L)?.takeIf { it > 0 }
        val rawResultLastEpId = result.optLong("last_ep_id", -1L).takeIf { it > 0 }
        AppLog.i(
            TAG,
            "CONTINUE_DEBUG seasonDetail seasonId=${seasonId ?: -1L} epId=${epId ?: -1L} sess=${BiliClient.cookies.hasSessData()} " +
                "user_progress.last_ep_id=${rawUserLastEpId ?: -1L} user_progress.last_epid=${rawUserLastEpid ?: -1L} " +
                "user_progress.last_ep_index=${rawUserLastEpIndex ?: -1} user_progress.last_time=${rawUserLastTime ?: -1L} " +
                "result.progress.last_ep_id=${rawProgressLastEpId ?: -1L} result.last_ep_id=${rawResultLastEpId ?: -1L} " +
                "parsed=$progressLastEpId episodes=${parsed.first.size} extra=${parsed.second.sumOf { it.episodes.size }}",
        )
        val resolvedSeasonId = result.optLong("season_id").takeIf { it > 0 } ?: seasonId ?: 0L
        val userStatus = result.optJSONObject("user_status") ?: JSONObject()
        val follow = userStatus.optInt("follow", -1).takeIf { it >= 0 }
        val followStatus = userStatus.optInt("follow_status", -1).takeIf { it >= 0 }
        val isFollowed =
            when {
                follow == 1 -> true
                follow == 0 -> false
                followStatus != null -> followStatus > 0
                else -> null
            }
        return BangumiSeasonDetail(
            seasonId = resolvedSeasonId,
            title = result.optString("title", result.optString("season_title", "")),
            coverUrl = result.optString("cover").takeIf { it.isNotBlank() },
            subtitle = result.optString("subtitle").takeIf { it.isNotBlank() },
            evaluate = result.optString("evaluate").takeIf { it.isNotBlank() },
            ratingScore = ratingScore,
            views = views,
            danmaku = danmaku,
            episodes = parsed.first,
            extraSections = parsed.second,
            progressLastEpId = progressLastEpId,
            progressLastTimeSec = rawUserLastTime,
            isFollowed = isFollowed,
        )
    }

    private fun pgcSeasonTypeName(type: Int): String? {
        return when (type) {
            1 -> "番剧"
            2 -> "电影"
            3 -> "纪录片"
            4 -> "国创"
            5 -> "电视剧"
            7 -> "综艺"
            else -> null
        }
    }

    private fun normalizeBadgeText(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return null
        if (s == "0") return null
        if (s.equals("null", ignoreCase = true)) return null
        return s
    }

    internal fun isPgcMainEpisodeFromSeasonEpisodes(
        ep: JSONObject,
        normalizedBadge: String? = null,
    ): Boolean {
        val sectionType = parseOptionalInt(ep.opt("section_type"))
        if (sectionType != null && sectionType != 0) return false

        val texts =
            buildList {
                add(normalizeBadgeText(normalizedBadge))
                add(normalizeBadgeText(ep.optJSONObject("badge_info")?.optString("text")))
                add(normalizeBadgeText(ep.optJSONObject("badgeInfo")?.optString("text")))
                add(ep.optString("show_title"))
                add(ep.optString("share_copy"))
            }
        return texts.none(::looksLikePgcExtraByText)
    }

    private fun parseOptionalInt(raw: Any?): Int? {
        return when (raw) {
            null,
            JSONObject.NULL,
            -> null
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    private fun looksLikePgcExtraByText(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return false
        if (text.contains("预告")) return true
        if (text.contains("花絮")) return true
        if (text.contains("番外")) return true
        if (text.contains("幕后")) return true
        if (text.uppercase(Locale.ROOT).contains("PV")) return true
        return false
    }

    private fun parsePgcScoreText(any: Any?): String? {
        return when (any) {
            is Number -> any.toDouble().takeIf { it > 0 }?.let { String.format(Locale.getDefault(), "%.1f", it) }
            is String ->
                any
                    .trim()
                    .trimEnd('分')
                    .takeIf { it.isNotBlank() }
                    ?.toDoubleOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { String.format(Locale.getDefault(), "%.1f", it) }
            else -> null
        }
    }

    private fun parsePgcPageToBangumiSeasons(json: JSONObject): CursorPage<BangumiSeason> {
        val result = json.optJSONObject("result") ?: JSONObject()
        val hasNext = result.optInt("has_next", 0) == 1
        val nextCursor = result.optString("next_cursor").trim().takeIf { it.isNotBlank() }
        val modules = result.optJSONArray("modules") ?: JSONArray()

        val items =
            run {
                val out = ArrayList<BangumiSeason>(128)
                val seen = HashSet<Long>(256)
                for (i in 0 until modules.length()) {
                    val module = modules.optJSONObject(i) ?: continue
                    val list = module.optJSONArray("items") ?: continue
                    for (j in 0 until list.length()) {
                        val obj = list.optJSONObject(j) ?: continue
                        val seasonId = obj.optLong("season_id").takeIf { it > 0 } ?: continue
                        if (!seen.add(seasonId)) continue

                        val title = obj.optString("title", "").trim()
                        val cover = obj.optString("cover").trim().takeIf { it.isNotBlank() }
                        val seasonType = obj.optInt("season_type", 0).takeIf { it > 0 }
                        val typeName = seasonType?.let(::pgcSeasonTypeName)
                        val badge =
                            normalizeBadgeText(obj.optString("badge"))
                                ?: normalizeBadgeText(obj.optJSONObject("badge_info")?.optString("text"))
                                ?: normalizeBadgeText(obj.optJSONObject("badgeInfo")?.optString("text"))

                        val scoreText = parsePgcScoreText(obj.opt("score"))
                        val newEpIndexShow =
                            obj.optJSONObject("new_ep")
                                ?.optString("index_show")
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                        val desc = obj.optString("desc").trim().takeIf { it.isNotBlank() }
                        val styles = obj.optString("season_styles").trim().takeIf { it.isNotBlank() }

                        val progressText =
                            buildList {
                                scoreText?.let { add("${it}分") }
                                newEpIndexShow?.let { add(it) }
                                if (newEpIndexShow == null) {
                                    desc?.let { add(it) }
                                }
                                if (newEpIndexShow == null && desc == null) {
                                    styles?.let { add(it) }
                                }
                            }.joinToString(" · ").takeIf { it.isNotBlank() }

                        out.add(
                            BangumiSeason(
                                seasonId = seasonId,
                                seasonTypeName = typeName,
                                title = title,
                                coverUrl = cover,
                                badge = badge,
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
                }
                out
            }

        return CursorPage(items = items, hasNext = hasNext, nextCursor = nextCursor)
    }

    private fun parsePgcPcBangumiTabToBangumiSeasons(data: JSONObject): CursorPage<BangumiSeason> {
        val hasNext = data.optInt("has_next", 0) == 1
        val nextCursor = data.optString("next_cursor", "").trim().takeIf { it.isNotBlank() && hasNext }
        val modules = data.optJSONArray("modules") ?: JSONArray()

        val items =
            run {
                val out = ArrayList<BangumiSeason>(128)
                val seen = HashSet<Long>(256)
                for (i in 0 until modules.length()) {
                    val module = modules.optJSONObject(i) ?: continue
                    val moduleTitle = module.optString("title", "").trim()
                    if (!moduleTitle.contains("猜你喜欢")) continue
                    val list = module.optJSONArray("items") ?: continue
                    for (j in 0 until list.length()) {
                        val obj = list.optJSONObject(j) ?: continue
                        val seasonId = obj.optLong("season_id").takeIf { it > 0 } ?: continue
                        if (!seen.add(seasonId)) continue
                        val title = obj.optString("title", "").trim()
                        if (title.isBlank()) continue

                        val cover = obj.optString("cover", "").trim().takeIf { it.isNotBlank() }
                        val seasonType = obj.optInt("season_type", 0).takeIf { it > 0 }
                        val typeName = seasonType?.let(::pgcSeasonTypeName)
                        val badge = normalizeBadgeText(obj.optJSONObject("badge_info")?.optString("text"))
                        val badgeEp = normalizeBadgeText(obj.optJSONObject("bottom_right_badge")?.optString("text"))
                        val newEpIndexShow = obj.optJSONObject("new_ep")?.optString("index_show", "")?.trim().takeIf { !it.isNullOrBlank() }
                        val followView = obj.optJSONObject("stat")?.optString("follow_view", "")?.trim().takeIf { !it.isNullOrBlank() }
                        val desc = obj.optString("desc", "").trim().takeIf { it.isNotBlank() }
                        val subTitle = obj.optString("sub_title", "").trim().takeIf { it.isNotBlank() }
                        val progressText = badgeEp ?: newEpIndexShow ?: followView ?: desc ?: subTitle

                        out.add(
                            BangumiSeason(
                                seasonId = seasonId,
                                seasonTypeName = typeName,
                                title = title,
                                coverUrl = cover,
                                badge = badge,
                                badgeEp = badgeEp,
                                progressText = progressText,
                                totalCount = null,
                                lastEpIndex = null,
                                lastEpId = null,
                                newestEpIndex = null,
                                isFinish = null,
                            ),
                        )
                    }
                }
                out
            }

        return CursorPage(items = items, hasNext = hasNext, nextCursor = nextCursor)
    }

    suspend fun pgcBangumiPage(cursor: String? = null, build: Int = PGC_PAGE_BUILD_DEFAULT): CursorPage<BangumiSeason> {
        val safeCursor = cursor?.trim()?.takeIf { it.isNotBlank() } ?: "0"
        val params = LinkedHashMap<String, String>(4)
        params["mobi_app"] = PGC_PAGE_MOBI_APP_DEFAULT
        params["build"] = build.toString()
        params["is_refresh"] = "0"
        params["cursor"] = safeCursor
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/pgc/page/pc/bangumi/tab",
                params,
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        return withContext(Dispatchers.Default) { parsePgcPcBangumiTabToBangumiSeasons(data) }
    }

    suspend fun pgcCinemaTabPage(cursor: String? = null, build: Int = PGC_PAGE_BUILD_DEFAULT): CursorPage<BangumiSeason> {
        val params = LinkedHashMap<String, String>(4)
        params["mobi_app"] = PGC_PAGE_MOBI_APP_DEFAULT
        params["build"] = build.toString()
        cursor?.trim()?.takeIf { it.isNotBlank() }?.let { params["cursor"] = it }
        val url = BiliClient.withQuery("https://api.bilibili.com/pgc/page/cinema/tab", params)
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        return withContext(Dispatchers.Default) { parsePgcPageToBangumiSeasons(json) }
    }

    suspend fun recommend(
        freshIdx: Int = 1,
        ps: Int = 20,
        fetchRow: Int = 1,
    ): List<VideoCard> = VideoApi.recommend(freshIdx = freshIdx, ps = ps, fetchRow = fetchRow)

    suspend fun popular(pn: Int = 1, ps: Int = 20): List<VideoCard> = VideoApi.popular(pn = pn, ps = ps)

    suspend fun popularPage(
        pn: Int = 1,
        ps: Int = 20,
    ): HasMorePage<VideoCard> = VideoApi.popularPage(pn = pn, ps = ps)

    suspend fun regionLatest(rid: Int, pn: Int = 1, ps: Int = 20): List<VideoCard> = VideoApi.regionLatest(rid = rid, pn = pn, ps = ps)

    suspend fun regionLatestPage(
        rid: Int,
        pn: Int = 1,
        ps: Int = 20,
    ): HasMorePage<VideoCard> = VideoApi.regionLatestPage(rid = rid, pn = pn, ps = ps)

    suspend fun dynamicTag(
        rid: Int,
        tagId: Long,
        pn: Int = 1,
        ps: Int = 20,
    ): HasMorePage<VideoCard> = VideoApi.dynamicTag(rid = rid, tagId = tagId, pn = pn, ps = ps)

    suspend fun view(bvid: String): JSONObject = VideoApi.view(bvid = bvid)

    suspend fun view(aid: Long): JSONObject = VideoApi.view(aid = aid)

    suspend fun viewTags(
        bvid: String? = null,
        aid: Long? = null,
        cid: Long? = null,
    ): List<VideoTag> = VideoApi.viewTags(bvid = bvid, aid = aid, cid = cid)

    suspend fun commentPage(
        type: Int,
        oid: Long,
        sort: Int = 1,
        pn: Int = 1,
        ps: Int = 20,
        noHot: Int = 1,
    ): JSONObject = VideoApi.commentPage(type = type, oid = oid, sort = sort, pn = pn, ps = ps, noHot = noHot)

    suspend fun commentRepliesPage(
        type: Int,
        oid: Long,
        rootRpid: Long,
        pn: Int = 1,
        ps: Int = 20,
    ): JSONObject = VideoApi.commentRepliesPage(type = type, oid = oid, rootRpid = rootRpid, pn = pn, ps = ps)

    suspend fun archiveRelated(bvid: String, aid: Long? = null): List<VideoCard> = VideoApi.archiveRelated(bvid = bvid, aid = aid)

    suspend fun seasonsArchivesList(
        mid: Long,
        seasonId: Long,
        pageNum: Int = 1,
        pageSize: Int = 200,
        sortReverse: Boolean = false,
    ): JSONObject =
        VideoApi.seasonsArchivesList(
            mid = mid,
            seasonId = seasonId,
            pageNum = pageNum,
            pageSize = pageSize,
            sortReverse = sortReverse,
        )

    suspend fun seasonsSeriesList(
        mid: Long,
        pageNum: Int = 1,
        pageSize: Int = 20,
    ): JSONObject =
        VideoApi.seasonsSeriesList(
            mid = mid,
            pageNum = pageNum,
            pageSize = pageSize,
        )

    suspend fun seriesArchives(
        mid: Long,
        seriesId: Long,
        pageNum: Int = 1,
        pageSize: Int = 20,
        sort: String = "desc",
        onlyNormal: Boolean = true,
    ): JSONObject =
        VideoApi.seriesArchives(
            mid = mid,
            seriesId = seriesId,
            pageNum = pageNum,
            pageSize = pageSize,
            sort = sort,
            onlyNormal = onlyNormal,
        )

    suspend fun onlineTotal(bvid: String, cid: Long): JSONObject = VideoApi.onlineTotal(bvid = bvid, cid = cid)

    suspend fun playUrlDash(bvid: String, cid: Long, qn: Int = 80, fnval: Int = 16): JSONObject =
        VideoApi.playUrlDash(bvid = bvid, cid = cid, qn = qn, fnval = fnval)

    suspend fun playUrlDashTryLook(bvid: String, cid: Long, qn: Int = 80, fnval: Int = 16): JSONObject =
        VideoApi.playUrlDashTryLook(bvid = bvid, cid = cid, qn = qn, fnval = fnval)

    suspend fun pgcPlayUrl(
        bvid: String? = null,
        aid: Long? = null,
        cid: Long? = null,
        epId: Long? = null,
        qn: Int = 80,
        fnval: Int = 16,
    ): JSONObject =
        VideoApi.pgcPlayUrl(
            bvid = bvid,
            aid = aid,
            cid = cid,
            epId = epId,
            qn = qn,
            fnval = fnval,
        )

    suspend fun pgcPlayUrlTryLook(
        bvid: String? = null,
        aid: Long? = null,
        cid: Long? = null,
        epId: Long? = null,
        qn: Int = 80,
        fnval: Int = 16,
    ): JSONObject =
        VideoApi.pgcPlayUrlTryLook(
            bvid = bvid,
            aid = aid,
            cid = cid,
            epId = epId,
            qn = qn,
            fnval = fnval,
        )

    suspend fun playerWbiV2(bvid: String, cid: Long): JSONObject = VideoApi.playerWbiV2(bvid = bvid, cid = cid)

    suspend fun historyReport(aid: Long, cid: Long, progressSec: Long, platform: String = "android") =
        VideoApi.historyReport(aid = aid, cid = cid, progressSec = progressSec, platform = platform)

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
    ) = VideoApi.webHeartbeat(
        aid = aid,
        bvid = bvid,
        cid = cid,
        epId = epId,
        seasonId = seasonId,
        playedTimeSec = playedTimeSec,
        type = type,
        subType = subType,
        playType = playType,
    )

    suspend fun dmSeg(cid: Long, segmentIndex: Int): List<Danmaku> = VideoApi.dmSeg(cid = cid, segmentIndex = segmentIndex)

    suspend fun dmWebView(cid: Long, aid: Long? = null): DanmakuWebView = VideoApi.dmWebView(cid = cid, aid = aid)

    suspend fun dmFilterUser(forceRefresh: Boolean = false): DanmakuUserFilter = VideoApi.dmFilterUser(forceRefresh = forceRefresh)

    internal fun isChargingArc(
        isChargingArc: Boolean,
        elecArcType: Int,
        badgeText: String?,
    ): Boolean {
        if (isChargingArc) return true
        if (elecArcType > 0) return true
        val text = badgeText?.trim().orEmpty()
        if (text.isBlank()) return false
        return text.contains("充电")
    }

    private fun parseSpaceArcSearchVideoCards(arr: JSONArray): List<VideoCard> {
        val out = ArrayList<VideoCard>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val bvid = obj.optString("bvid", "").trim()
            if (bvid.isBlank()) continue

            val aid = obj.optLong("aid").takeIf { it > 0 }
            val cid = obj.optLong("cid").takeIf { it > 0 }
            val title = obj.optString("title", "")
            val cover = obj.optString("pic", obj.optString("cover", "")).trim()
            val durationText = obj.optString("length", obj.optString("duration", "0:00"))
            val isChargingArc =
                isChargingArc(
                    isChargingArc = obj.optBoolean("is_charging_arc", false),
                    elecArcType = obj.optInt("elec_arc_type", 0),
                    badgeText = null,
                )
            val play =
                obj.optLong("play").takeIf { it > 0 }
                    ?: obj.optString("play").trim().toLongOrNull()?.takeIf { it > 0 }
            val danmaku =
                obj.optLong("video_review").takeIf { it > 0 }
                    ?: obj.optString("video_review").trim().toLongOrNull()?.takeIf { it > 0 }
            val created =
                obj.optLong("created").takeIf { it > 0 }
                    ?: obj.optLong("pubdate").takeIf { it > 0 }

            out.add(
                VideoCard(
                    bvid = bvid,
                    cid = cid,
                    aid = aid,
                    title = title,
                    coverUrl = cover,
                    durationSec = parseDuration(durationText),
                    ownerName = obj.optString("author", ""),
                    ownerFace = null,
                    ownerMid = obj.optLong("mid").takeIf { it > 0 },
                    view = play,
                    danmaku = danmaku,
                    pubDate = created,
                    pubDateText = null,
                    isChargingArc = isChargingArc,
                ),
            )
        }
        return out
    }

    internal fun parseDuration(durationText: String): Int {
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

    suspend fun followings(vmid: Long, pn: Int = 1, ps: Int = 20): List<Following> {
        return followingsPage(vmid = vmid, pn = pn, ps = ps).items
    }

    suspend fun followingsPage(vmid: Long, pn: Int = 1, ps: Int = 50): HasMorePage<Following> {
        val url = BiliClient.withQuery(
            "https://api.bilibili.com/x/relation/followings",
            mapOf(
                "vmid" to vmid.toString(),
                "pn" to pn.coerceAtLeast(1).toString(),
                "ps" to ps.coerceIn(1, 50).toString(),
            ),
        )
        val json = BiliClient.getJson(
            url,
            headers = mapOf(
                "Referer" to "https://www.bilibili.com/",
            ),
        )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }

        val data = json.optJSONObject("data") ?: JSONObject()
        val list = data.optJSONArray("list") ?: JSONArray()
        val total = data.optInt("total", 0)
        val page = pn.coerceAtLeast(1)
        val pageSize = ps.coerceIn(1, 50)
        return withContext(Dispatchers.Default) {
            val out = ArrayList<Following>(list.length())
            for (i in 0 until list.length()) {
                val obj = list.optJSONObject(i) ?: continue
                out.add(
                    Following(
                        mid = obj.optLong("mid"),
                        name = obj.optString("uname", ""),
                        avatarUrl = obj.optString("face").takeIf { it.isNotBlank() },
                        sign = obj.optString("sign").trim().takeIf { it.isNotBlank() },
                    ),
                )
            }
            AppLog.d(TAG, "followings vmid=$vmid page=$page size=${out.size} total=$total")
            HasMorePage(
                items = out,
                page = page,
                hasMore = out.isNotEmpty() && page * pageSize < total,
                total = total,
            )
        }
    }

    data class DynamicPage(
        val items: List<VideoCard>,
        val nextOffset: String?,
    )

    private data class DynamicFeedPage(
        val cards: List<VideoCard>,
        val nextOffset: String?,
        val hasMore: Boolean,
        val rawItemCount: Int,
    )

    private fun parseBvidFromJumpUrl(jumpUrl: String?): String? {
        if (jumpUrl.isNullOrBlank()) return null
        return BV_REGEX.find(jumpUrl)?.value
    }

    private fun avToBv(aid: Long): String? {
        if (aid <= 0) return null
        val x = (aid xor BV_XOR) + BV_ADD
        val out = "BV1  4 1 7  ".toCharArray()
        var pow = 1L
        for (i in 0 until 6) {
            val idx = ((x / pow) % 58L).toInt()
            out[BV_POS[i]] = BV_TABLE[idx]
            pow *= 58L
        }
        return String(out)
    }

    private fun parseDynamicVideoCards(items: JSONArray): List<VideoCard> {
        val cards = ArrayList<VideoCard>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val modules = it.optJSONObject("modules") ?: continue
            val moduleDynamic = modules.optJSONObject("module_dynamic") ?: continue
            val major = moduleDynamic.optJSONObject("major") ?: continue

            val author = modules.optJSONObject("module_author")
            val ownerName = author?.optString("name", "") ?: ""
            val ownerFace = author?.optString("face")?.takeIf { it.isNotBlank() }
            val ownerMid = author?.optLong("mid")?.takeIf { v -> v > 0 }
            val authorPubTs = author?.optLong("pub_ts")?.takeIf { v -> v > 0 }

            val archive = major.optJSONObject("archive")
            if (archive != null) {
                val bvid = archive.optString("bvid", "")
                if (bvid.isBlank()) continue
                val stat = archive.optJSONObject("stat") ?: JSONObject()
                val isChargingArc =
                    isChargingArc(
                        isChargingArc = archive.optBoolean("is_charging_arc", false),
                        elecArcType = archive.optInt("elec_arc_type", 0),
                        badgeText = archive.optJSONObject("badge")?.optString("text", ""),
                    )
                val pubDate = archive.optLong("pubdate").takeIf { v -> v > 0 } ?: authorPubTs
                cards.add(
                    VideoCard(
                        bvid = bvid,
                        cid = null,
                        title = archive.optString("title", ""),
                        coverUrl = archive.optString("cover", ""),
                        durationSec = parseDuration(archive.optString("duration_text", "0:00")),
                        ownerName = ownerName,
                        ownerFace = ownerFace,
                        ownerMid = ownerMid,
                        view = parseCountText(stat.optString("play", "")),
                        danmaku = parseCountText(stat.optString("danmaku", "")),
                        pubDate = pubDate,
                        pubDateText = null,
                        isChargingArc = isChargingArc,
                    ),
                )
                continue
            }

            val ugcSeason = major.optJSONObject("ugc_season")
            if (ugcSeason != null) {
                val jumpUrl = ugcSeason.optString("jump_url", "").takeIf { u -> u.isNotBlank() }
                val aid = ugcSeason.optLong("aid").takeIf { v -> v > 0 }
                val bvid =
                    parseBvidFromJumpUrl(jumpUrl)
                        ?: (aid?.let { avToBv(it) })
                        ?: ""
                if (bvid.isBlank()) continue
                val stat = ugcSeason.optJSONObject("stat") ?: JSONObject()
                val isChargingArc =
                    isChargingArc(
                        isChargingArc = ugcSeason.optBoolean("is_charging_arc", false),
                        elecArcType = ugcSeason.optInt("elec_arc_type", 0),
                        badgeText = ugcSeason.optJSONObject("badge")?.optString("text", ""),
                    )
                cards.add(
                    VideoCard(
                        bvid = bvid,
                        cid = null,
                        title = ugcSeason.optString("title", ""),
                        coverUrl = ugcSeason.optString("cover", ""),
                        durationSec = parseDuration(ugcSeason.optString("duration_text", "0:00")),
                        ownerName = ownerName,
                        ownerFace = ownerFace,
                        ownerMid = ownerMid,
                        view = parseCountText(stat.optString("play", "")),
                        danmaku = parseCountText(stat.optString("danmaku", "")),
                        pubDate = authorPubTs,
                        pubDateText = null,
                        isChargingArc = isChargingArc,
                    ),
                )
                continue
            }

            val additional = moduleDynamic.optJSONObject("additional")
            val additionalUgc = additional?.optJSONObject("ugc")
            if (additionalUgc != null) {
                val jumpUrl = additionalUgc.optString("jump_url", "").takeIf { u -> u.isNotBlank() }
                val aid = additionalUgc.optString("id_str", "").toLongOrNull()?.takeIf { v -> v > 0 }
                val bvid =
                    parseBvidFromJumpUrl(jumpUrl)
                        ?: (aid?.let { avToBv(it) })
                        ?: ""
                if (bvid.isBlank()) continue
                val isChargingArc =
                    isChargingArc(
                        isChargingArc = additionalUgc.optBoolean("is_charging_arc", false),
                        elecArcType = additionalUgc.optInt("elec_arc_type", 0),
                        badgeText = additionalUgc.optString("head_text", ""),
                    )
                cards.add(
                    VideoCard(
                        bvid = bvid,
                        cid = null,
                        title = additionalUgc.optString("title", ""),
                        coverUrl = additionalUgc.optString("cover", ""),
                        durationSec = parseDuration(additionalUgc.optString("duration", "0:00")),
                        ownerName = ownerName,
                        ownerFace = ownerFace,
                        ownerMid = ownerMid,
                        view = null,
                        danmaku = null,
                        pubDate = authorPubTs,
                        pubDateText = null,
                        isChargingArc = isChargingArc,
                    ),
                )
            }
        }
        return cards
    }

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
        val hasMore = data.optBoolean("has_more", false)
        val items = data.optJSONArray("items") ?: JSONArray()
        return withContext(Dispatchers.Default) {
            val cards = parseDynamicVideoCards(items)
            val next = data.optString("offset", "").takeIf { it.isNotBlank() }
            val nextOffset = if (hasMore) next else null
            AppLog.d(TAG, "dynamicAllVideo items=${items.length()} cards=${cards.size} hasMore=$hasMore nextOffset=${nextOffset?.take(8)}")
            DynamicPage(cards, nextOffset)
        }
    }

    private suspend fun dynamicSpaceVideoPage(hostMid: Long, offset: String?): DynamicFeedPage {
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
        val hasMore = data.optBoolean("has_more", false)
        return withContext(Dispatchers.Default) {
            val cards = parseDynamicVideoCards(items)
            val next = data.optString("offset", "").takeIf { it.isNotBlank() }
            val nextOffset = if (hasMore) next else null
            DynamicFeedPage(
                cards = cards,
                nextOffset = nextOffset,
                hasMore = hasMore,
                rawItemCount = items.length(),
            )
        }
    }

    suspend fun dynamicSpaceVideo(
        hostMid: Long,
        offset: String? = null,
        minCardCount: Int = 0,
        maxPages: Int = 3,
    ): DynamicPage {
        var currentOffset = offset
        val cards = ArrayList<VideoCard>()
        var pages = 0
        var rawItems = 0

        val firstPage = dynamicSpaceVideoPage(hostMid = hostMid, offset = currentOffset)
        pages++
        rawItems += firstPage.rawItemCount
        cards.addAll(firstPage.cards)
        var nextOffset = firstPage.nextOffset
        var hasMore = firstPage.hasMore && nextOffset != null
        currentOffset = nextOffset

        while (true) {
            val reachedTarget = minCardCount <= 0 || cards.size >= minCardCount
            val reachedPageLimit = pages >= maxPages.coerceAtLeast(1)
            if (reachedTarget || !hasMore || reachedPageLimit) break

            val page = dynamicSpaceVideoPage(hostMid = hostMid, offset = currentOffset)
            pages++
            rawItems += page.rawItemCount
            cards.addAll(page.cards)
            nextOffset = page.nextOffset
            hasMore = page.hasMore && nextOffset != null
            currentOffset = nextOffset
        }

        AppLog.d(
            TAG,
            "dynamicSpaceVideo hostMid=$hostMid pages=$pages rawItems=$rawItems cards=${cards.size} min=$minCardCount hasMore=$hasMore nextOffset=${nextOffset?.take(8)}",
        )
        return DynamicPage(cards, nextOffset)
    }

    suspend fun spaceArcSearchPage(
        mid: Long,
        pn: Int = 1,
        ps: Int = 30,
        order: String = "pubdate",
        tid: Int = 0,
        keyword: String? = null,
    ): HasMorePage<VideoCard> {
        val safeMid = mid.takeIf { it > 0 } ?: error("mid required")
        val page = pn.coerceAtLeast(1)
        val pageSize = ps.coerceIn(1, 50)
        val safeOrder = order.trim().takeIf { it.isNotBlank() } ?: "pubdate"
        val safeTid = tid.coerceAtLeast(0)
        val safeKeyword = keyword?.trim().orEmpty()

        val params =
            buildMap {
                put("mid", safeMid.toString())
                put("pn", page.toString())
                put("ps", pageSize.toString())
                put("order", safeOrder)
                if (safeTid > 0) put("tid", safeTid.toString())
                if (safeKeyword.isNotBlank()) put("keyword", safeKeyword)
            }
        val keys = BiliClient.ensureWbiKeys()
        val url = BiliClient.signedWbiUrl(path = "/x/space/wbi/arc/search", params = params, keys = keys)
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }

        val data = json.optJSONObject("data") ?: JSONObject()
        val list = data.optJSONObject("list") ?: JSONObject()
        val vlist = list.optJSONArray("vlist") ?: JSONArray()
        val total = data.optJSONObject("page")?.optInt("count", 0) ?: 0
        return withContext(Dispatchers.Default) {
            val out = parseSpaceArcSearchVideoCards(vlist)
            val hasMore = out.isNotEmpty() && page * pageSize < total
            AppLog.d(TAG, "spaceArcSearch mid=$safeMid page=$page size=${out.size} total=$total hasMore=$hasMore")
            HasMorePage(items = out, page = page, hasMore = hasMore, total = total)
        }
    }

    suspend fun spaceAccInfo(mid: Long): SpaceAccInfo {
        val keys = BiliClient.ensureWbiKeys()
        val url = BiliClient.signedWbiUrl(
            path = "/x/space/wbi/acc/info",
            params = mapOf("mid" to mid.toString()),
            keys = keys,
        )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        return SpaceAccInfo(
            mid = data.optLong("mid").takeIf { it > 0 } ?: mid,
            name = data.optString("name", ""),
            faceUrl = data.optString("face").trim().takeIf { it.isNotBlank() },
            sign = data.optString("sign").trim().takeIf { it.isNotBlank() },
            isFollowed = data.optBoolean("is_followed", false),
        )
    }

    suspend fun modifyRelation(
        fid: Long,
        act: Int,
        reSrc: Int = 11,
    ) {
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")
        val url = "https://api.bilibili.com/x/relation/modify"
        val form =
            buildMap {
                put("fid", fid.toString())
                put("act", act.toString())
                if (reSrc > 0) put("re_src", reSrc.toString())
                put("csrf", csrf)
            }
        val json = BiliClient.postFormJson(url, form)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    private fun genPlayUrlSession(nowMs: Long = System.currentTimeMillis()): String? {
        val buvid3 = BiliClient.cookies.getCookieValue("buvid3")?.takeIf { it.isNotBlank() } ?: return null
        return md5Hex(buvid3 + nowMs.toString())
    }

    private fun md5Hex(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format(Locale.US, "%02x", b))
        return sb.toString()
    }

    suspend fun getWebVideoShot(
        aid: Long? = null,
        bvid: String? = null,
        cid: Long? = null,
        needJsonArrayIndex: Boolean = false
    ): VideoShotInfo {

        require(aid != null || bvid != null)

        val params = buildMap<String, String> {
            aid?.let { put("aid", it.toString()) }
            bvid?.let { put("bvid", it) }
            cid?.let { put("cid", it.toString()) }
            put("index", if (needJsonArrayIndex) "1" else "0")
        }

        val url = BiliClient.withQuery(
            "https://api.bilibili.com/x/player/videoshot",
            params
        )

        val json = BiliClient.getJson(url)

        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(code, msg)
        }

        val data = json.optJSONObject("data") ?: JSONObject()

        val images = buildList {
            val arr = data.optJSONArray("image")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    add(arr.optString(i))
                }
            }
        }

        val indexList = data.optJSONArray("index")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.optInt(i).toUShort())
                }
            }
        }

        return VideoShotInfo(
            pvData = data.optString("pvdata").takeIf { it.isNotBlank() },
            imgXLen = data.optInt("img_x_len", 10),
            imgYLen = data.optInt("img_y_len", 10),
            imgXSize = data.optInt("img_x_size", 0),
            imgYSize = data.optInt("img_y_size", 0),
            image = images,
            index = indexList
        )
    }
}
