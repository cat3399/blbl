package blbl.cat3399.core.api.video.web

import blbl.cat3399.core.api.BiliApiSource
import blbl.cat3399.core.api.video.AudioTrack
import blbl.cat3399.core.api.video.VideoAudioKind
import blbl.cat3399.core.api.video.VideoCardPage
import blbl.cat3399.core.api.video.VideoCollectionKind
import blbl.cat3399.core.api.video.VideoCollectionSection
import blbl.cat3399.core.api.video.VideoCollectionSectionsPage
import blbl.cat3399.core.api.video.VideoCollectionSectionsRequest
import blbl.cat3399.core.api.video.VideoDashStream
import blbl.cat3399.core.api.video.VideoDetail
import blbl.cat3399.core.api.video.VideoDetailPage
import blbl.cat3399.core.api.video.VideoDetailRequest
import blbl.cat3399.core.api.video.VideoDetailStat
import blbl.cat3399.core.api.video.VideoDimension
import blbl.cat3399.core.api.video.VideoDynamicTagRequest
import blbl.cat3399.core.api.video.VideoOwner
import blbl.cat3399.core.api.video.VideoPlayClipSegment
import blbl.cat3399.core.api.video.VideoPlayRequest
import blbl.cat3399.core.api.video.VideoPlayResume
import blbl.cat3399.core.api.video.VideoPlayStream
import blbl.cat3399.core.api.video.VideoPopularRequest
import blbl.cat3399.core.api.video.VideoProgressiveStream
import blbl.cat3399.core.api.video.VideoRegionLatestRequest
import blbl.cat3399.core.api.video.VideoSegmentBase
import blbl.cat3399.core.api.video.VideoSeriesArchivesRequest
import blbl.cat3399.core.api.video.VideoSubtitle
import blbl.cat3399.core.api.video.VideoSupportFormat
import blbl.cat3399.core.api.video.VideoTrack
import blbl.cat3399.core.api.video.VideoTrackInfo
import blbl.cat3399.core.api.video.VideoUgcSeason
import blbl.cat3399.core.api.video.VideoUgcSeasonEpisode
import blbl.cat3399.core.api.video.VideoUgcSeasonSection
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
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.model.VideoTag
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal class WebVideoMapper(
    private val source: BiliApiSource,
) {
    fun parsePlayStream(
        json: JSONObject,
        request: VideoPlayRequest,
    ): VideoPlayStream {
        val data = json.optJSONObject("data") ?: json.optJSONObject("result") ?: JSONObject()
        val dash = parseDash(data.optJSONObject("dash"))
        val progressive = parseProgressive(data.optJSONArray("durl"))
        return VideoPlayStream(
            source = source,
            request = request,
            durationMs = resolveDurationMs(dash = dash, timeLengthMs = data.optLong("timelength", -1L), progressive = progressive),
            dash = dash,
            progressive = progressive,
            supportFormats = parseSupportFormats(data.optJSONArray("support_formats")),
            clipSegments = parseClipSegments(data.optJSONArray("clip_info_list") ?: data.optJSONArray("clipInfoList")),
            resume = parseResume(data),
            vVoucher = data.optString("v_voucher", "").trim().takeIf { it.isNotBlank() },
        )
    }

    fun parseDetail(
        data: JSONObject,
        request: VideoDetailRequest,
    ): VideoDetail {
        val owner = parseOwner(data.optJSONObject("owner") ?: data.optJSONObject("up_info"))
        val stat = parseStat(data.optJSONObject("stat"))
        val pages = parseDetailPages(data.optJSONArray("pages"))
        return VideoDetail(
            source = source,
            request = request,
            aid = data.optLong("aid").takeIf { it > 0L },
            bvid = data.optString("bvid", "").trim(),
            cid = data.optLong("cid").takeIf { it > 0L },
            title = data.optString("title", "").trim().takeIf { it.isNotBlank() },
            description = data.optString("desc", "").trim().takeIf { it.isNotBlank() },
            coverUrl = data.optString("pic", data.optString("cover", "")).trim().takeIf { it.isNotBlank() },
            tabId = data.optInt("tid").takeIf { it > 0 },
            tabName = data.optString("tname", "").trim().takeIf { it.isNotBlank() },
            redirectUrl = data.optString("redirect_url", "").trim().takeIf { it.isNotBlank() },
            owner = owner,
            stat = stat,
            pubDateSec = data.optLong("pubdate").takeIf { it > 0L },
            durationSec = data.optLong("duration", -1L).takeIf { it > 0L },
            dimension = parseDimension(data.optJSONObject("dimension")),
            pages = pages,
            ugcSeason = parseUgcSeason(data.optJSONObject("ugc_season"), fallbackOwnerMid = owner?.mid),
            subtitles = parseSubtitles(data.optJSONObject("subtitle")?.optJSONArray("list")),
            upFollowed = parseUpFollowed(data),
        )
    }

    fun parseTags(
        data: JSONArray,
        request: VideoTagsRequest,
    ): VideoTagsPage {
        val out = ArrayList<VideoTag>(data.length())
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val tagType = obj.optString("tag_type", "").trim()
            if (tagType != "old_channel") continue
            val tagId = obj.optLong("tag_id").takeIf { it > 0L } ?: continue
            val tagName = obj.optString("tag_name", "").trim()
            if (tagName.isBlank()) continue
            out.add(
                VideoTag(
                    tagId = tagId,
                    tagName = tagName,
                    tagType = tagType,
                    jumpUrl = obj.optString("jump_url", "").trim().takeIf { it.isNotBlank() },
                    musicId = obj.optString("music_id", "").trim().takeIf { it.isNotBlank() },
                ),
            )
        }
        return VideoTagsPage(source = source, request = request, tags = out)
    }

    fun parsePopularPage(
        data: JSONObject,
        request: VideoPopularRequest,
    ): VideoCardPage<VideoPopularRequest> {
        val list = data.optJSONArray("list") ?: JSONArray()
        val items = parseVideoCards(list)
        val noMore = data.optBoolean("no_more", false)
        return VideoCardPage(
            source = source,
            request = request,
            items = items,
            page = request.pn,
            hasMore = items.isNotEmpty() && !noMore,
            total = if (noMore) request.pn * request.ps else 0,
        )
    }

    fun parseRegionLatestPage(
        data: JSONObject,
        request: VideoRegionLatestRequest,
    ): VideoCardPage<VideoRegionLatestRequest> {
        return parseArchiveListPage(
            data = data,
            request = request,
            fallbackPage = request.pn,
            fallbackPageSize = request.ps,
            useLengthFallback = true,
        )
    }

    fun parseDynamicTagPage(
        data: JSONObject,
        request: VideoDynamicTagRequest,
    ): VideoCardPage<VideoDynamicTagRequest> {
        return parseArchiveListPage(
            data = data,
            request = request,
            fallbackPage = request.pn,
            fallbackPageSize = request.ps,
            useLengthFallback = false,
        )
    }

    fun parseOnlineStatus(
        data: JSONObject,
        request: VideoOnlineStatusRequest,
    ): VideoOnlineStatus {
        val showSwitch = data.optJSONObject("show_switch") ?: JSONObject()
        return VideoOnlineStatus(
            source = source,
            request = request,
            totalText = data.optString("total", "").trim().takeIf { it.isNotBlank() },
            countText = data.optString("count", "").trim().takeIf { it.isNotBlank() },
            totalEnabled = showSwitch.optBoolean("total", true),
            countEnabled = showSwitch.optBoolean("count", true),
        )
    }

    fun parsePlayerInfo(
        data: JSONObject,
        request: VideoPlayerInfoRequest,
    ): VideoPlayerInfo {
        val subtitles = parseSubtitles(data.optJSONObject("subtitle")?.optJSONArray("subtitles"))
        return VideoPlayerInfo(
            source = source,
            request = request,
            subtitles = subtitles,
            needLoginSubtitle = data.optBoolean("need_login_subtitle", false),
            resume = parseResume(data),
        )
    }

    fun parseVideoShot(
        data: JSONObject,
        request: VideoShotRequest,
    ): VideoShotInfo {
        val images = buildList {
            val arr = data.optJSONArray("image")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
        val indexList =
            data.optJSONArray("index")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) add(arr.optInt(i))
                }
            }
        return VideoShotInfo(
            source = source,
            request = request,
            pvData = data.optString("pvdata").takeIf { it.isNotBlank() },
            imgXLen = data.optInt("img_x_len", 10),
            imgYLen = data.optInt("img_y_len", 10),
            imgXSize = data.optInt("img_x_size", 0),
            imgYSize = data.optInt("img_y_size", 0),
            image = images,
            index = indexList,
        )
    }

    fun parseUgcSeasonArchives(
        data: JSONObject,
        request: UgcSeasonArchivesRequest,
    ): UgcSeasonArchivesPage {
        val total = data.optJSONObject("page")?.optInt("total", 0)?.takeIf { it > 0 }
        val archives = data.optJSONArray("archives") ?: JSONArray()
        return UgcSeasonArchivesPage(
            source = source,
            request = request,
            items = parseVideoCards(archives),
            totalCount = total,
        )
    }

    fun parseCollectionSections(
        data: JSONObject,
        request: VideoCollectionSectionsRequest,
    ): VideoCollectionSectionsPage {
        val itemsLists = data.optJSONObject("items_lists") ?: JSONObject()
        val pageObj = itemsLists.optJSONObject("page") ?: JSONObject()
        val totalPages = pageObj.optInt("total", 0).coerceAtLeast(0)
        val seasons = itemsLists.optJSONArray("seasons_list") ?: JSONArray()
        val series = itemsLists.optJSONArray("series_list") ?: JSONArray()
        return VideoCollectionSectionsPage(
            source = source,
            request = request,
            totalPages = totalPages,
            sections =
                buildList {
                    addAll(parseCollectionSectionArray(seasons, kind = VideoCollectionKind.SEASON))
                    addAll(parseCollectionSectionArray(series, kind = VideoCollectionKind.SERIES))
                },
        )
    }

    fun parseSeriesArchives(
        data: JSONObject,
        request: VideoSeriesArchivesRequest,
    ): VideoCardPage<VideoSeriesArchivesRequest> {
        return parseArchiveCardsPage(
            data = data,
            request = request,
            fallbackPage = request.pageNum,
            fallbackPageSize = request.pageSize,
        )
    }

    private fun parseCollectionSectionArray(
        arr: JSONArray,
        kind: VideoCollectionKind,
    ): List<VideoCollectionSection> {
        val out = ArrayList<VideoCollectionSection>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val meta = obj.optJSONObject("meta") ?: JSONObject()
            val id =
                when (kind) {
                    VideoCollectionKind.SEASON -> meta.optLong("season_id").takeIf { it > 0 }
                    VideoCollectionKind.SERIES -> meta.optLong("series_id").takeIf { it > 0 }
                } ?: continue
            val title = meta.optString("name", "").trim().takeIf { it.isNotBlank() } ?: continue
            val total = meta.optInt("total", 0).takeIf { it > 0 }
            val archives = obj.optJSONArray("archives") ?: JSONArray()
            val videos = parseVideoCards(archives)
            if (videos.isEmpty()) continue
            out.add(
                VideoCollectionSection(
                    kind = kind,
                    id = id,
                    title = title,
                    totalCount = total,
                    items = videos,
                ),
            )
        }
        return out
    }

    private fun <RequestT> parseArchiveCardsPage(
        data: JSONObject,
        request: RequestT,
        fallbackPage: Int,
        fallbackPageSize: Int,
    ): VideoCardPage<RequestT> {
        val page = data.optJSONObject("page") ?: JSONObject()
        val total = page.optInt("total", 0).coerceAtLeast(0)
        val pageNum = page.optInt("num", fallbackPage).coerceAtLeast(1)
        val pageSize = page.optInt("size", fallbackPageSize).coerceAtLeast(1)
        val archives = data.optJSONArray("archives") ?: JSONArray()
        val items = parseVideoCards(archives)
        val hasMore =
            items.isNotEmpty() &&
                (
                    if (total > 0) {
                        pageNum * pageSize < total
                    } else {
                        archives.length() >= pageSize
                    }
                )
        return VideoCardPage(
            source = source,
            request = request,
            items = items,
            page = pageNum,
            hasMore = hasMore,
            total = total,
        )
    }

    private fun <RequestT> parseArchiveListPage(
        data: JSONObject,
        request: RequestT,
        fallbackPage: Int,
        fallbackPageSize: Int,
        useLengthFallback: Boolean,
    ): VideoCardPage<RequestT> {
        val page = data.optJSONObject("page") ?: JSONObject()
        val total = page.optInt("count", 0).coerceAtLeast(0)
        val pageNum = page.optInt("num", fallbackPage).coerceAtLeast(1)
        val pageSize = page.optInt("size", fallbackPageSize).coerceAtLeast(1)
        val archives = data.optJSONArray("archives") ?: JSONArray()
        val items = parseVideoCards(archives)
        val hasMore =
            items.isNotEmpty() &&
                (
                    if (total > 0) {
                        pageNum * pageSize < total
                    } else if (useLengthFallback) {
                        archives.length() >= pageSize
                    } else {
                        false
                    }
                )
        return VideoCardPage(
            source = source,
            request = request,
            items = items,
            page = pageNum,
            hasMore = hasMore,
            total = total,
        )
    }

    private fun parseDetailPages(arr: JSONArray?): List<VideoDetailPage> {
        if (arr == null) return emptyList()
        val out = ArrayList<VideoDetailPage>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val cid = obj.optLong("cid").takeIf { it > 0L } ?: continue
            out +=
                VideoDetailPage(
                    cid = cid,
                    page = obj.optInt("page").takeIf { it > 0 } ?: (i + 1),
                    title = obj.optString("part", "").trim().takeIf { it.isNotBlank() },
                    durationSec = parseDurationSec(obj),
                    dimension = parseDimension(obj.optJSONObject("dimension")),
                )
        }
        return out
    }

    private fun parseUgcSeason(
        obj: JSONObject?,
        fallbackOwnerMid: Long?,
    ): VideoUgcSeason? {
        obj ?: return null
        val sections = obj.optJSONArray("sections") ?: JSONArray()
        val parsedSections = ArrayList<VideoUgcSeasonSection>(sections.length())
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val eps = section.optJSONArray("episodes") ?: continue
            val episodes = ArrayList<VideoUgcSeasonEpisode>(eps.length())
            for (j in 0 until eps.length()) {
                parseUgcSeasonEpisode(eps.optJSONObject(j))?.let { episodes += it }
            }
            parsedSections += VideoUgcSeasonSection(episodes = episodes)
        }

        return VideoUgcSeason(
            id = obj.optLong("id").takeIf { it > 0L },
            title = obj.optString("title", "").trim().takeIf { it.isNotBlank() },
            epCount = obj.optInt("ep_count").takeIf { it > 0 },
            ownerMid = obj.optLong("mid").takeIf { it > 0L } ?: fallbackOwnerMid,
            sections = parsedSections,
        )
    }

    private fun parseUgcSeasonEpisode(obj: JSONObject?): VideoUgcSeasonEpisode? {
        obj ?: return null
        val arc = obj.optJSONObject("arc") ?: JSONObject()
        val bvid = obj.optString("bvid", "").trim().ifBlank { arc.optString("bvid", "").trim() }
        if (bvid.isBlank()) return null
        val title =
            obj.optString("title", "").trim().takeIf { it.isNotBlank() }
                ?: arc.optString("title", "").trim().takeIf { it.isNotBlank() }
        val cover =
            arc.optString("pic", arc.optString("cover", "")).trim().ifBlank {
                obj.optString("cover", obj.optString("pic", "")).trim()
            }.takeIf { it.isNotBlank() }
        val owner = parseOwner(arc.optJSONObject("owner") ?: obj.optJSONObject("owner"))
        return VideoUgcSeasonEpisode(
            bvid = bvid,
            cid = obj.optLong("cid").takeIf { it > 0L } ?: arc.optLong("cid").takeIf { it > 0L },
            aid = obj.optLong("aid").takeIf { it > 0L } ?: arc.optLong("aid").takeIf { it > 0L },
            title = title,
            coverUrl = cover,
            durationSec = parseDurationSec(arc) ?: parseDurationSec(obj),
            owner = owner,
            stat = parseStat(arc.optJSONObject("stat")),
            pubDateSec = arc.optLong("pubdate").takeIf { it > 0L } ?: obj.optLong("pubdate").takeIf { it > 0L },
        )
    }

    private fun parseOwner(obj: JSONObject?): VideoOwner? {
        obj ?: return null
        val mid = obj.optLong("mid").takeIf { it > 0L } ?: return null
        return VideoOwner(
            mid = mid,
            name = obj.optString("name", "").trim().takeIf { it.isNotBlank() },
            avatarUrl = obj.optString("face", "").trim().takeIf { it.isNotBlank() },
        )
    }

    private fun parseStat(obj: JSONObject?): VideoDetailStat {
        fun count(key: String): Long? = obj?.optLong(key)?.takeIf { it > 0L }
        return VideoDetailStat(
            view = count("view") ?: count("play"),
            danmaku = count("danmaku") ?: count("dm"),
            reply = count("reply"),
            like = count("like"),
            coin = count("coin"),
            favorite = count("favorite"),
        )
    }

    private fun parseDimension(obj: JSONObject?): VideoDimension? {
        obj ?: return null
        val width = obj.optInt("width", 0)
        val height = obj.optInt("height", 0)
        if (width <= 0 || height <= 0) return null
        return VideoDimension(
            width = width,
            height = height,
            rotate = obj.optInt("rotate", 0),
        )
    }

    private fun parseSubtitles(arr: JSONArray?): List<VideoSubtitle> {
        if (arr == null) return emptyList()
        val out = ArrayList<VideoSubtitle>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val url = obj.optString("subtitle_url", obj.optString("subtitleUrl", "")).trim()
            if (url.isBlank()) continue
            val lan = obj.optString("lan", "").trim().ifBlank { "unknown" }
            val doc = obj.optString("lan_doc", obj.optString("language", lan)).trim().ifBlank { lan }
            out += VideoSubtitle(url = url, language = lan, languageDoc = doc)
        }
        return out
    }

    fun parseVideoCards(arr: JSONArray): List<VideoCard> {
        val out = ArrayList<VideoCard>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parseVideoCard(obj, index = i)?.let { out += it }
        }
        return out
    }

    private fun parseVideoCard(
        obj: JSONObject,
        index: Int,
    ): VideoCard? {
        val bvid = obj.optString("bvid", "").trim()
        if (bvid.isBlank()) return null

        val ownerObj = obj.optJSONObject("owner") ?: JSONObject()
        val statObj = obj.optJSONObject("stat") ?: JSONObject()
        val durationSec = parseDurationSec(obj) ?: 0
        val rawProgressSec = obj.optLong("progress", 0L)
        val progressFinished = rawProgressSec < 0L || (durationSec > 0 && rawProgressSec >= durationSec.toLong())
        return VideoCard(
            bvid = bvid,
            cid = obj.optLong("cid").takeIf { it > 0 },
            aid = obj.optLong("aid").takeIf { it > 0 },
            title = obj.optString("title", "").trim().ifBlank { "视频 ${index + 1}" },
            coverUrl = obj.optString("pic", obj.optString("cover", "")).trim(),
            durationSec = durationSec,
            ownerName =
                ownerObj.optString("name", "").trim().ifBlank {
                    obj.optString("author", "").trim()
                },
            ownerFace = ownerObj.optString("face", "").trim().takeIf { it.isNotBlank() },
            ownerMid =
                ownerObj.optLong("mid").takeIf { it > 0 }
                    ?: obj.optLong("mid").takeIf { it > 0 }
                    ?: obj.optLong("upMid").takeIf { it > 0 }
                    ?: obj.optLong("owner_mid").takeIf { it > 0 },
            view =
                statObj.optLong("view").takeIf { it > 0 }
                    ?: statObj.optLong("play").takeIf { it > 0 }
                    ?: obj.optLong("play").takeIf { it > 0 },
            danmaku =
                statObj.optLong("danmaku").takeIf { it > 0 }
                    ?: statObj.optLong("dm").takeIf { it > 0 }
                    ?: obj.optLong("video_review").takeIf { it > 0 },
            pubDate = obj.optLong("pubdate").takeIf { it > 0 },
            pubDateText = null,
            progressSec = rawProgressSec.takeIf { it > 0 && !progressFinished },
            progressFinished = progressFinished,
            trackId = obj.optString("track_id", obj.optString("trackid", "")).trim().takeIf { it.isNotBlank() },
        )
    }

    private fun parseUpFollowed(data: JSONObject): Boolean? {
        val owner = data.optJSONObject("owner")
        val reqUser = data.optJSONObject("req_user")
        val ownerAttention = owner?.optInt("attention", -1) ?: -1
        if (ownerAttention >= 0) return ownerAttention == 1
        val reqAttention = reqUser?.optInt("attention", -1) ?: -1
        if (reqAttention >= 0) return reqAttention == 1
        val reqFollow = reqUser?.optInt("follow", -1) ?: -1
        if (reqFollow >= 0) return reqFollow == 1
        val reqFollowStatus = reqUser?.optInt("follow_status", -1) ?: -1
        if (reqFollowStatus >= 0) return reqFollowStatus > 0
        val relationStatus = owner?.optJSONObject("relation")?.optInt("status", -1) ?: -1
        if (relationStatus >= 0) return relationStatus > 0
        return null
    }

    private fun parseDurationSec(obj: JSONObject): Int? {
        obj.optInt("duration", 0).takeIf { it > 0 }?.let { return it }
        val text = obj.optString("duration_text", obj.optString("duration", "0:00"))
        return parseDurationText(text).takeIf { it > 0 }
    }

    private fun parseDurationText(durationText: String): Int {
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

    private fun parseDash(dash: JSONObject?): VideoDashStream? {
        if (dash == null) return null
        val durationMs = dash.optLong("duration", -1L).takeIf { it > 0L }?.times(1000L)
        val videos = parseVideoTracks(dash.optJSONArray("video"))
        val audios =
            buildList {
                addAll(parseAudioTracks(dash.optJSONArray("audio"), kind = VideoAudioKind.NORMAL))
                addAll(parseAudioTracks(dash.optJSONObject("dolby")?.optJSONArray("audio"), kind = VideoAudioKind.DOLBY))
                parseAudioTrack(dash.optJSONObject("flac")?.optJSONObject("audio"), kind = VideoAudioKind.FLAC)?.let { add(it) }
            }
        return VideoDashStream(durationMs = durationMs, videos = videos, audios = audios)
    }

    private fun parseVideoTracks(arr: JSONArray?): List<VideoTrack> {
        if (arr == null) return emptyList()
        val out = ArrayList<VideoTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val qn = dashQnOf(obj)
            if (qn <= 0) continue
            out +=
                VideoTrack(
                    qn = qn,
                    codecid = obj.optInt("codecid", 0),
                    urls = urlsOf(obj),
                    info = trackInfo(obj),
                    isDolbyVision = isDolbyVisionTrack(obj),
                )
        }
        return out
    }

    private fun parseAudioTracks(
        arr: JSONArray?,
        kind: VideoAudioKind,
    ): List<AudioTrack> {
        if (arr == null) return emptyList()
        val out = ArrayList<AudioTrack>(arr.length())
        for (i in 0 until arr.length()) {
            parseAudioTrack(arr.optJSONObject(i), kind = kind)?.let { out += it }
        }
        return out
    }

    private fun parseAudioTrack(
        obj: JSONObject?,
        kind: VideoAudioKind,
    ): AudioTrack? {
        obj ?: return null
        return AudioTrack(
            id = obj.optInt("id", 0),
            kind = kind,
            urls = urlsOf(obj),
            info = trackInfo(obj),
        )
    }

    private fun parseProgressive(arr: JSONArray?): List<VideoProgressiveStream> {
        if (arr == null) return emptyList()
        val out = ArrayList<VideoProgressiveStream>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out +=
                VideoProgressiveStream(
                    urls = urlsOf(obj),
                    lengthMs = obj.optLong("length", -1L).takeIf { it > 0L },
                )
        }
        return out
    }

    private fun parseSupportFormats(arr: JSONArray?): List<VideoSupportFormat> {
        if (arr == null) return emptyList()
        val out = ArrayList<VideoSupportFormat>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val quality = obj.optInt("quality", 0).takeIf { it > 0 } ?: continue
            val label =
                obj.optString("new_description", obj.optString("display_desc", obj.optString("description", "")))
                    .trim()
                    .takeIf { it.isNotBlank() }
            out += VideoSupportFormat(quality = quality, label = label)
        }
        return out
    }

    private fun parseClipSegments(arr: JSONArray?): List<VideoPlayClipSegment> {
        if (arr == null) return emptyList()
        val out = ArrayList<VideoPlayClipSegment>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val clipType = obj.optString("clipType", obj.optString("clip_type", "")).trim()
            val category =
                when (clipType) {
                    "CLIP_TYPE_OP" -> "intro"
                    "CLIP_TYPE_ED" -> "outro"
                    else -> "clip"
                }
            val startRaw = obj.optDouble("start", Double.NaN)
            val endRaw = obj.optDouble("end", Double.NaN)
            if (!startRaw.isFinite() || !endRaw.isFinite()) continue
            val startMs = normalizeClipTimeToMs(startRaw).coerceAtLeast(0L)
            val endMs = normalizeClipTimeToMs(endRaw).coerceAtLeast(0L)
            if (endMs <= startMs) continue
            out += VideoPlayClipSegment(category = category, startMs = startMs, endMs = endMs)
        }
        return out
    }

    private fun parseResume(data: JSONObject): VideoPlayResume? {
        val time = data.optLong("last_play_time", -1L).takeIf { it > 0L } ?: return null
        val lastCid = data.optLong("last_play_cid", -1L).takeIf { it > 0L }
        return VideoPlayResume(rawTime = time, lastCid = lastCid)
    }

    private fun resolveDurationMs(
        dash: VideoDashStream?,
        timeLengthMs: Long,
        progressive: List<VideoProgressiveStream>,
    ): Long? {
        dash?.durationMs?.takeIf { it > 0L }?.let { return it }
        timeLengthMs.takeIf { it > 0L }?.let { return it }
        val progressiveTotal = progressive.mapNotNull { it.lengthMs?.takeIf { length -> length > 0L } }.sum()
        return progressiveTotal.takeIf { it > 0L }
    }

    private fun urlsOf(obj: JSONObject): List<String> {
        val out = ArrayList<String>(4)
        obj.optString("baseUrl", obj.optString("base_url", obj.optString("url", "")))
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { out += it }
        val backup = obj.optJSONArray("backupUrl") ?: obj.optJSONArray("backup_url") ?: JSONArray()
        for (i in 0 until backup.length()) {
            backup.optString(i, "").trim().takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out.distinct()
    }

    private fun trackInfo(obj: JSONObject): VideoTrackInfo {
        val segment = obj.optJSONObject("segment_base") ?: obj.optJSONObject("segmentBase")
        val segmentBase =
            if (segment != null) {
                val initialization = segment.optString("initialization", segment.optString("Initialization", "")).trim()
                val indexRange = segment.optString("index_range", segment.optString("indexRange", "")).trim()
                if (initialization.isNotBlank() && indexRange.isNotBlank()) {
                    VideoSegmentBase(initialization = initialization, indexRange = indexRange)
                } else {
                    null
                }
            } else {
                null
            }
        return VideoTrackInfo(
            mimeType = obj.optString("mimeType", obj.optString("mime_type", "")).trim().takeIf { it.isNotBlank() },
            codecs = obj.optString("codecs", "").trim().takeIf { it.isNotBlank() },
            bandwidth = obj.optLong("bandwidth", 0L).takeIf { it > 0L },
            width = obj.optInt("width", 0).takeIf { it > 0 },
            height = obj.optInt("height", 0).takeIf { it > 0 },
            frameRate = obj.optString("frameRate", obj.optString("frame_rate", "")).trim().takeIf { it.isNotBlank() },
            segmentBase = segmentBase,
        )
    }

    private fun dashQnOf(obj: JSONObject): Int {
        val id = obj.optInt("id", 0)
        if (id > 0) return id
        return obj.optInt("quality", 0).takeIf { it > 0 } ?: 0
    }

    private fun isDolbyVisionTrack(obj: JSONObject): Boolean {
        if (dashQnOf(obj) == 126) return true
        val mime = obj.optString("mimeType", obj.optString("mime_type", "")).lowercase(Locale.US)
        if (mime.contains("dolby-vision")) return true
        val codecs = obj.optString("codecs", "").lowercase(Locale.US)
        return codecs.startsWith("dvhe") || codecs.startsWith("dvh1") || codecs.contains("dovi")
    }

    private fun normalizeClipTimeToMs(value: Double): Long {
        return if (value >= 10_000.0) value.toLong() else (value * 1000.0).toLong()
    }
}
