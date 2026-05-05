package blbl.cat3399.core.api.video.web

import blbl.cat3399.core.api.BiliApiCapability
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.api.BiliApiSource
import blbl.cat3399.core.api.video.ArchiveRelatedPage
import blbl.cat3399.core.api.video.ArchiveRelatedRequest
import blbl.cat3399.core.api.video.VideoCardPage
import blbl.cat3399.core.api.video.VideoCollectionSectionsPage
import blbl.cat3399.core.api.video.VideoCollectionSectionsRequest
import blbl.cat3399.core.api.video.VideoDetail
import blbl.cat3399.core.api.video.VideoDetailRequest
import blbl.cat3399.core.api.video.VideoDynamicTagRequest
import blbl.cat3399.core.api.video.VideoPlayKind
import blbl.cat3399.core.api.video.VideoPlayRequest
import blbl.cat3399.core.api.video.VideoPlayStream
import blbl.cat3399.core.api.video.VideoPopularRequest
import blbl.cat3399.core.api.video.VideoRecommendPage
import blbl.cat3399.core.api.video.VideoRecommendRequest
import blbl.cat3399.core.api.video.VideoRegionLatestRequest
import blbl.cat3399.core.api.video.VideoSeriesArchivesRequest
import blbl.cat3399.core.api.video.VideoSourceApi
import blbl.cat3399.core.api.video.VideoOnlineStatus
import blbl.cat3399.core.api.video.VideoOnlineStatusRequest
import blbl.cat3399.core.api.video.VideoPlayerInfo
import blbl.cat3399.core.api.video.VideoPlayerInfoRequest
import blbl.cat3399.core.api.video.VideoShotInfo
import blbl.cat3399.core.api.video.VideoShotRequest
import blbl.cat3399.core.api.video.VideoTagsPage
import blbl.cat3399.core.api.video.VideoTagsRequest
import blbl.cat3399.core.api.video.UgcSeasonArchivesPage
import blbl.cat3399.core.api.video.UgcSeasonArchivesRequest
import blbl.cat3399.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class WebVideoApi(
    private val transport: WebVideoApiTransport = BiliClientWebVideoApiTransport,
) : VideoSourceApi {
    override val source: BiliApiSource = BiliApiSource.WEB
    override val capabilities: Set<BiliApiCapability> =
        setOf(
            BiliApiCapability.VIDEO_DETAIL,
            BiliApiCapability.VIDEO_RECOMMEND,
            BiliApiCapability.VIDEO_POPULAR,
            BiliApiCapability.VIDEO_REGION_LATEST,
            BiliApiCapability.VIDEO_DYNAMIC_TAG,
            BiliApiCapability.VIDEO_ARCHIVE_RELATED,
            BiliApiCapability.VIDEO_PLAY_URL_UGC,
            BiliApiCapability.VIDEO_PLAY_URL_PGC,
            BiliApiCapability.VIDEO_PLAYER_INFO,
            BiliApiCapability.VIDEO_TAGS,
            BiliApiCapability.VIDEO_ONLINE_STATUS,
            BiliApiCapability.VIDEO_SHOT,
            BiliApiCapability.VIDEO_UGC_SEASON_ARCHIVES,
            BiliApiCapability.VIDEO_COLLECTION_SECTIONS,
            BiliApiCapability.VIDEO_SERIES_ARCHIVES,
        )
    private val mapper = WebVideoMapper(source)

    override suspend fun detail(request: VideoDetailRequest): VideoDetail {
        val safeBvid = request.bvid?.trim().orEmpty()
        val safeAid = request.aid?.takeIf { it > 0 }
        if (safeBvid.isBlank() && safeAid == null) error("bvid or aid required")

        val params =
            buildMap<String, String> {
                if (safeBvid.isNotBlank()) put("bvid", safeBvid)
                if (safeAid != null && safeBvid.isBlank()) put("aid", safeAid.toString())
            }
        val url = transport.withQuery("https://api.bilibili.com/x/web-interface/view", params)
        val json = transport.getJson(url)
        checkApiCode(json)
        val data = json.optJSONObject("data") ?: JSONObject()
        return withContext(Dispatchers.Default) { mapper.parseDetail(data = data, request = request) }
    }

    override suspend fun tags(request: VideoTagsRequest): VideoTagsPage {
        val safeBvid = request.bvid?.trim().orEmpty().takeIf { it.isNotBlank() }
        val safeAid = request.aid?.takeIf { it > 0L }
        val safeCid = request.cid?.takeIf { it > 0L }
        if (safeBvid == null && safeAid == null) error("view_tags_missing_bvid_aid")

        return requestTagsNew(request = request, bvid = safeBvid, aid = safeAid, cid = safeCid)
    }

    override suspend fun recommend(request: VideoRecommendRequest): VideoRecommendPage {
        val keys = transport.ensureWbiKeys()
        val url =
            transport.signedWbiUrl(
                path = "/x/web-interface/wbi/index/top/feed/rcmd",
                params =
                    mapOf(
                        "ps" to request.ps.toString(),
                        "fresh_idx" to request.freshIdx.toString(),
                        "fresh_idx_1h" to request.freshIdx.toString(),
                        "fetch_row" to request.fetchRow.toString(),
                        "feed_version" to "V8",
                    ),
                keys = keys,
            )
        val json = transport.getJson(url)
        checkApiCode(json)
        val itemsJson = json.optJSONObject("data")?.optJSONArray("item") ?: JSONArray()
        val items = withContext(Dispatchers.Default) { mapper.parseVideoCards(itemsJson) }
        AppLog.d(TAG, "recommend source=${source.prefValue} items=${items.size}")
        return VideoRecommendPage(source = source, request = request, items = items)
    }

    override suspend fun popular(request: VideoPopularRequest): VideoCardPage<VideoPopularRequest> {
        val safePn = request.pn.coerceAtLeast(1)
        val safePs = request.ps.coerceIn(1, 50)
        val safeRequest = request.copy(pn = safePn, ps = safePs)
        val url =
            transport.withQuery(
                "https://api.bilibili.com/x/web-interface/popular",
                mapOf("pn" to safePn.toString(), "ps" to safePs.toString()),
            )
        val json = transport.getJson(url)
        checkApiCode(json)
        val data = json.optJSONObject("data") ?: JSONObject()
        return withContext(Dispatchers.Default) { mapper.parsePopularPage(data = data, request = safeRequest) }
    }

    override suspend fun regionLatest(request: VideoRegionLatestRequest): VideoCardPage<VideoRegionLatestRequest> {
        val safeRid = request.rid.takeIf { it > 0 } ?: error("region_latest_invalid_rid")
        val safePn = request.pn.coerceAtLeast(1)
        val safePs = request.ps.coerceIn(1, 50)
        val safeRequest = request.copy(rid = safeRid, pn = safePn, ps = safePs)
        val url =
            transport.withQuery(
                "https://api.bilibili.com/x/web-interface/dynamic/region",
                mapOf("rid" to safeRid.toString(), "pn" to safePn.toString(), "ps" to safePs.toString()),
            )
        val json = transport.getJson(url)
        checkApiCode(json)
        val data = json.optJSONObject("data") ?: JSONObject()
        return withContext(Dispatchers.Default) { mapper.parseRegionLatestPage(data = data, request = safeRequest) }
    }

    override suspend fun dynamicTag(request: VideoDynamicTagRequest): VideoCardPage<VideoDynamicTagRequest> {
        val safeRid = request.rid.takeIf { it > 0 } ?: error("dynamic_tag_invalid_rid")
        val safeTagId = request.tagId.takeIf { it > 0L } ?: error("dynamic_tag_invalid_tag_id")
        val safePn = request.pn.coerceAtLeast(1)
        val safePs = request.ps.coerceIn(1, 50)
        val safeRequest = request.copy(rid = safeRid, tagId = safeTagId, pn = safePn, ps = safePs)
        val url =
            transport.withQuery(
                "https://api.bilibili.com/x/web-interface/dynamic/tag",
                mapOf(
                    "rid" to safeRid.toString(),
                    "tag_id" to safeTagId.toString(),
                    "pn" to safePn.toString(),
                    "ps" to safePs.toString(),
                ),
            )
        val json = transport.getJson(url)
        checkApiCode(json)
        val data = json.optJSONObject("data") ?: JSONObject()
        return withContext(Dispatchers.Default) { mapper.parseDynamicTagPage(data = data, request = safeRequest) }
    }

    override suspend fun archiveRelated(request: ArchiveRelatedRequest): ArchiveRelatedPage {
        val safeBvid = request.bvid.trim()
        val safeAid = request.aid?.takeIf { it > 0 }
        if (safeBvid.isBlank() && safeAid == null) error("bvid or aid required")

        val params =
            buildMap<String, String> {
                if (safeAid != null) put("aid", safeAid.toString())
                if (safeBvid.isNotBlank()) put("bvid", safeBvid)
            }
        val url = transport.withQuery("https://api.bilibili.com/x/web-interface/archive/related", params)
        val json = transport.getJson(url)
        checkApiCode(json)
        val list = json.optJSONArray("data") ?: JSONArray()
        val items = withContext(Dispatchers.Default) { mapper.parseVideoCards(list) }
        AppLog.d(TAG, "archiveRelated source=${source.prefValue} items=${items.size}")
        return ArchiveRelatedPage(source = source, request = request, items = items)
    }

    override suspend fun playUrl(request: VideoPlayRequest): VideoPlayStream {
        val json =
            when (request.kind) {
                VideoPlayKind.UGC -> requestUgcPlayJson(request)
                VideoPlayKind.PGC -> requestPgcPlayJson(request)
        }
        return withContext(Dispatchers.Default) {
            mapper.parsePlayStream(json = json, request = request)
        }
    }

    override suspend fun playerInfo(request: VideoPlayerInfoRequest): VideoPlayerInfo {
        transport.ensurePgcPlayCookieMaintenance()
        val params =
            mutableMapOf(
                "bvid" to request.bvid.trim(),
                "cid" to request.cid.toString(),
            )
        val keys = transport.ensureWbiKeys()
        val url = transport.signedWbiUrl(path = "/x/player/wbi/v2", params = params, keys = keys)
        val json =
            try {
                transport.getJson(
                    url = url,
                    headers = transport.webHeaders(targetUrl = url, includeCookie = true),
                    noCookies = true,
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                params["try_look"] = "1"
                val fallbackUrl = transport.signedWbiUrl(path = "/x/player/wbi/v2", params = params, keys = keys)
                transport.getJson(
                    url = fallbackUrl,
                    headers = transport.webHeaders(targetUrl = fallbackUrl, includeCookie = false),
                    noCookies = true,
                )
            }
        checkApiCode(json)
        val data = json.optJSONObject("data") ?: JSONObject()
        return withContext(Dispatchers.Default) { mapper.parsePlayerInfo(data = data, request = request) }
    }

    override suspend fun onlineStatus(request: VideoOnlineStatusRequest): VideoOnlineStatus {
        val url =
            transport.withQuery(
                "https://api.bilibili.com/x/player/online/total",
                mapOf("bvid" to request.bvid.trim(), "cid" to request.cid.toString()),
            )
        val json =
            transport.getJson(
                url = url,
                headers = transport.webHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        checkApiCode(json)
        val data = json.optJSONObject("data") ?: JSONObject()
        return withContext(Dispatchers.Default) { mapper.parseOnlineStatus(data = data, request = request) }
    }

    override suspend fun videoShot(request: VideoShotRequest): VideoShotInfo {
        if (request.aid?.takeIf { it > 0L } == null && request.bvid?.trim().isNullOrBlank()) {
            error("missing aid/bvid")
        }
        val params =
            buildMap {
                request.aid?.takeIf { it > 0L }?.let { put("aid", it.toString()) }
                request.bvid?.trim()?.takeIf { it.isNotBlank() }?.let { put("bvid", it) }
                request.cid?.takeIf { it > 0L }?.let { put("cid", it.toString()) }
                put("index", if (request.needJsonArrayIndex) "1" else "0")
            }
        val url = transport.withQuery("https://api.bilibili.com/x/player/videoshot", params)
        val json = transport.getJson(url)
        checkApiCode(json)
        val data = json.optJSONObject("data") ?: JSONObject()
        return withContext(Dispatchers.Default) { mapper.parseVideoShot(data = data, request = request) }
    }

    override suspend fun ugcSeasonArchives(request: UgcSeasonArchivesRequest): UgcSeasonArchivesPage {
        val safeMid = request.mid.takeIf { it > 0 } ?: error("mid required")
        val safeSeasonId = request.seasonId.takeIf { it > 0 } ?: error("seasonId required")
        val url =
            transport.withQuery(
                "https://api.bilibili.com/x/polymer/web-space/seasons_archives_list",
                mapOf(
                    "mid" to safeMid.toString(),
                    "season_id" to safeSeasonId.toString(),
                    "sort_reverse" to request.sortReverse.toString(),
                    "page_num" to request.pageNum.coerceAtLeast(1).toString(),
                    "page_size" to request.pageSize.coerceIn(1, 200).toString(),
                    "web_location" to "333.999",
                ),
            )
        val json =
            transport.getJson(
                url = url,
                headers = transport.webHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        checkApiCode(json)
        val data = json.optJSONObject("data") ?: JSONObject()
        return withContext(Dispatchers.Default) { mapper.parseUgcSeasonArchives(data = data, request = request) }
    }

    override suspend fun collectionSections(request: VideoCollectionSectionsRequest): VideoCollectionSectionsPage {
        val safeMid = request.mid.takeIf { it > 0 } ?: error("mid required")
        val safePageNum = request.pageNum.coerceAtLeast(1)
        val safePageSize = request.pageSize.coerceIn(1, 50)
        val safeRequest = request.copy(mid = safeMid, pageNum = safePageNum, pageSize = safePageSize)
        val keys = transport.ensureWbiKeys()
        val url =
            transport.signedWbiUrl(
                path = "/x/polymer/web-space/seasons_series_list",
                params =
                    mapOf(
                        "mid" to safeMid.toString(),
                        "page_num" to safePageNum.toString(),
                        "page_size" to safePageSize.toString(),
                        "web_location" to "333.999",
                    ),
                keys = keys,
            )
        val json =
            transport.getJson(
                url = url,
                headers = transport.webHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        checkApiCode(json)
        val data = json.optJSONObject("data") ?: JSONObject()
        return withContext(Dispatchers.Default) { mapper.parseCollectionSections(data = data, request = safeRequest) }
    }

    override suspend fun seriesArchives(request: VideoSeriesArchivesRequest): VideoCardPage<VideoSeriesArchivesRequest> {
        val safeMid = request.mid.takeIf { it > 0 } ?: error("mid required")
        val safeSeriesId = request.seriesId.takeIf { it > 0 } ?: error("seriesId required")
        val safePageNum = request.pageNum.coerceAtLeast(1)
        val safePageSize = request.pageSize.coerceIn(1, 50)
        val safeSort =
            when (request.sort.trim().lowercase()) {
                "asc" -> "asc"
                else -> "desc"
            }
        val safeRequest =
            request.copy(
                mid = safeMid,
                seriesId = safeSeriesId,
                pageNum = safePageNum,
                pageSize = safePageSize,
                sort = safeSort,
            )
        val params =
            buildMap<String, String> {
                this["mid"] = safeMid.toString()
                this["series_id"] = safeSeriesId.toString()
                this["pn"] = safePageNum.toString()
                this["ps"] = safePageSize.toString()
                this["sort"] = safeSort
                this["only_normal"] = request.onlyNormal.toString()
                transport.cookieValue("DedeUserID")?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let {
                    this["current_mid"] = it.toString()
                }
            }
        val url = transport.withQuery("https://api.bilibili.com/x/series/archives", params)
        val json =
            transport.getJson(
                url = url,
                headers = transport.webHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        checkApiCode(json)
        val data = json.optJSONObject("data") ?: JSONObject()
        return withContext(Dispatchers.Default) { mapper.parseSeriesArchives(data = data, request = safeRequest) }
    }

    private suspend fun requestTagsNew(
        request: VideoTagsRequest,
        bvid: String?,
        aid: Long?,
        cid: Long?,
    ): VideoTagsPage {
        val params =
            buildMap<String, String> {
                if (bvid != null) {
                    this["bvid"] = bvid
                } else if (aid != null) {
                    this["aid"] = aid.toString()
                }
                cid?.let { this["cid"] = it.toString() }
            }
        val url = transport.withQuery("https://api.bilibili.com/x/web-interface/view/detail/tag", params)
        val json = transport.getJson(url)
        checkApiCode(json)
        val data = json.optJSONArray("data") ?: JSONArray()
        return withContext(Dispatchers.Default) {
            mapper.parseTags(data = data, request = request)
        }
    }

    private suspend fun requestUgcPlayJson(request: VideoPlayRequest): JSONObject {
        transport.ensureUgcPlayCookieMaintenance()
        val bvid = request.bvid?.trim().orEmpty()
        val cid = request.cid?.takeIf { it > 0 } ?: error("cid required")
        if (bvid.isBlank()) error("bvid required")

        val params =
            mutableMapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                "qn" to request.qn.toString(),
                "fnver" to "0",
                "fnval" to request.fnval.toString(),
                "fourk" to "1",
                "voice_balance" to "1",
                "web_location" to "1315873",
                "gaia_source" to "pre-load",
                "isGaiaAvoided" to "true",
            )
        if (request.tryLook || !transport.hasSessData()) {
            params["try_look"] = "1"
        }
        if (!request.tryLook) {
            transport.cookieValue("x-bili-gaia-vtoken")?.trim()?.takeIf { it.isNotBlank() }?.let {
                params["gaia_vtoken"] = it
            }
        }
        return requestWbiPlayUrl(params = params, includeCookie = !request.tryLook)
    }

    private suspend fun requestPgcPlayJson(request: VideoPlayRequest): JSONObject {
        transport.ensurePgcPlayCookieMaintenance()
        val safeBvid = request.bvid?.trim().orEmpty()
        val safeAid = request.aid?.takeIf { it > 0 }
        if (safeBvid.isBlank() && safeAid == null) error("bvid or aid required")

        val params =
            mutableMapOf(
                "qn" to request.qn.toString(),
                "fnver" to "0",
                "fnval" to request.fnval.toString(),
                "fourk" to "1",
                "from_client" to "BROWSER",
                "drm_tech_type" to "2",
            )
        if (request.tryLook) {
            params["try_look"] = "1"
        } else {
            transport.cookieValue("x-bili-gaia-vtoken")?.trim()?.takeIf { it.isNotBlank() }?.let {
                params["gaia_vtoken"] = it
            }
        }
        if (safeBvid.isNotBlank()) params["bvid"] = safeBvid
        safeAid?.let { params["avid"] = it.toString() }
        request.cid?.takeIf { it > 0 }?.let { params["cid"] = it.toString() }
        request.epId?.takeIf { it > 0 }?.let { params["ep_id"] = it.toString() }

        val url = transport.withQuery("https://api.bilibili.com/pgc/player/web/playurl", params)
        val json =
            transport.getJson(
                url = url,
                headers = transport.webHeaders(targetUrl = url, includeCookie = !request.tryLook),
                noCookies = true,
            )
        checkApiCode(json)
        val result = json.optJSONObject("result") ?: JSONObject()
        if (json.optJSONObject("data") == null) json.put("data", result)
        return json
    }

    private suspend fun requestWbiPlayUrl(
        params: Map<String, String>,
        includeCookie: Boolean,
    ): JSONObject {
        val keys = transport.ensureWbiKeys()
        val url = transport.signedWbiUrl(path = "/x/player/wbi/playurl", params = params, keys = keys)
        val json =
            transport.getJson(
                url = url,
                headers = transport.webHeaders(targetUrl = url, includeCookie = includeCookie),
                noCookies = true,
            )
        checkApiCode(json)
        return json
    }

    private fun checkApiCode(json: JSONObject) {
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    companion object {
        private const val TAG = "WebVideoApi"
    }
}
