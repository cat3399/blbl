package blbl.cat3399.core.api.video.app

import bilibili.app.playerunite.v1.PlayViewUniteReply
import bilibili.pgc.gateway.player.v2.PlayViewReply
import bilibili.playershared.DashItem as UgcDashItem
import bilibili.playershared.DashVideo as UgcDashVideo
import bilibili.playershared.ResponseUrl as UgcResponseUrl
import bilibili.playershared.Stream as UgcStream
import bilibili.playershared.StreamInfo as UgcStreamInfo
import bilibili.playershared.VodInfo
import blbl.cat3399.core.api.BiliApiSource
import blbl.cat3399.core.api.video.AudioTrack
import blbl.cat3399.core.api.video.VideoAudioKind
import blbl.cat3399.core.api.video.VideoDashStream
import blbl.cat3399.core.api.video.VideoMediaRequestProfile
import blbl.cat3399.core.api.video.VideoPlayRequest
import blbl.cat3399.core.api.video.VideoPlayResume
import blbl.cat3399.core.api.video.VideoPlayStream
import blbl.cat3399.core.api.video.VideoProgressiveStream
import blbl.cat3399.core.api.video.VideoSegmentBase
import blbl.cat3399.core.api.video.VideoSupportFormat
import blbl.cat3399.core.api.video.VideoTrack
import blbl.cat3399.core.api.video.VideoTrackInfo
import blbl.cat3399.core.model.VideoCard
import org.json.JSONArray
import org.json.JSONObject
import bilibili.pgc.gateway.player.v2.DashItem as PgcDashItem
import bilibili.pgc.gateway.player.v2.DashVideo as PgcDashVideo
import bilibili.pgc.gateway.player.v2.ResponseUrl as PgcResponseUrl
import bilibili.pgc.gateway.player.v2.Stream as PgcStream
import bilibili.pgc.gateway.player.v2.StreamInfo as PgcStreamInfo
import bilibili.pgc.gateway.player.v2.VideoInfo as PgcVideoInfo

internal class AppVideoMapper(
    private val source: BiliApiSource,
) {
    fun parseRecommendItems(itemsJson: JSONArray): List<VideoCard> {
        val out = ArrayList<VideoCard>(itemsJson.length())
        for (i in 0 until itemsJson.length()) {
            val obj = itemsJson.optJSONObject(i) ?: continue
            parseRecommendItem(obj)?.let { out += it }
        }
        return out
    }

    fun parseUgcPlayStream(
        reply: PlayViewUniteReply,
        request: VideoPlayRequest,
    ): VideoPlayStream {
        val vod = reply.vodInfo
        val dashVideos =
            vod.streamListList
                .filter { it.hasDashVideo() }
                .mapNotNull { stream -> ugcVideoTrack(stream) }
        val fallbackProgressive =
            if (dashVideos.isEmpty()) {
                vod.streamListList.flatMap { stream -> ugcProgressive(stream) }
            } else {
                emptyList()
            }
        val audios =
            buildList {
                addAll(vod.dashAudioList.mapNotNull { ugcAudioTrack(it, VideoAudioKind.NORMAL) })
                val dolby = vod.takeIf { it.hasDolby() }?.dolby
                addAll(dolby?.audioList.orEmpty().mapNotNull { ugcAudioTrack(it, VideoAudioKind.DOLBY) })
                val lossless = vod.takeIf { it.hasLossLessItem() }?.lossLessItem
                lossless?.takeIf { it.hasAudio() && it.audio.id > 0 }?.audio?.let {
                    ugcAudioTrack(it, VideoAudioKind.FLAC)?.let(::add)
                }
            }
        return VideoPlayStream(
            source = source,
            request = request,
            durationMs = vod.timelength.takeIf { it > 0L },
            dash = VideoDashStream(durationMs = vod.timelength.takeIf { it > 0L }, videos = dashVideos, audios = audios),
            progressive = fallbackProgressive,
            supportFormats = ugcSupportFormats(vod),
            clipSegments = emptyList(),
            resume = reply.takeIf { it.hasHistory() }?.history?.currentVideo?.progress
                ?.takeIf { it > 0L }
                ?.let { progress ->
                    VideoPlayResume(
                        rawTime = progress,
                        lastCid = reply.history.currentVideo.lastPlayCid.takeIf { it > 0L },
                    )
                },
            vVoucher = null,
        )
    }

    fun parsePgcPlayStream(
        reply: PlayViewReply,
        request: VideoPlayRequest,
    ): VideoPlayStream {
        val video = reply.videoInfo
        val dashVideos =
            video.streamListList
                .filter { it.hasDashVideo() }
                .mapNotNull { stream -> pgcVideoTrack(stream) }
        val fallbackProgressive =
            if (dashVideos.isEmpty()) {
                video.streamListList.flatMap { stream -> pgcProgressive(stream) }
            } else {
                emptyList()
            }
        val audios =
            buildList {
                addAll(video.dashAudioList.mapNotNull { pgcAudioTrack(it, VideoAudioKind.NORMAL) })
                video.takeIf { it.hasDolby() }?.dolby?.takeIf { it.hasAudio() }?.audio?.let {
                    pgcAudioTrack(it, VideoAudioKind.DOLBY)?.let(::add)
                }
            }
        val resume =
            reply.business.userStatus.watchProgress.progress
                .takeIf { it > 0L }
                ?.let { VideoPlayResume(rawTime = it, lastCid = null) }
        return VideoPlayStream(
            source = source,
            request = request,
            durationMs = video.timelength.takeIf { it > 0L },
            dash = VideoDashStream(durationMs = video.timelength.takeIf { it > 0L }, videos = dashVideos, audios = audios),
            progressive = fallbackProgressive,
            supportFormats = pgcSupportFormats(video),
            clipSegments = emptyList(),
            resume = resume,
            vVoucher = null,
        )
    }

    fun mergeStreams(streams: List<VideoPlayStream>): VideoPlayStream {
        val first = streams.first()
        val dashVideos =
            streams
                .flatMap { it.dash?.videos.orEmpty() }
                .filter { it.urls.isNotEmpty() }
                .distinctBy { "${it.qn}:${it.codecid}:${it.urls.firstOrNull().orEmpty()}" }
                .sortedWith(compareByDescending<VideoTrack> { it.qn }.thenBy { it.codecid })
        val audios =
            streams
                .flatMap { it.dash?.audios.orEmpty() }
                .filter { it.urls.isNotEmpty() }
                .distinctBy { "${it.kind}:${it.id}:${it.urls.firstOrNull().orEmpty()}" }
                .sortedWith(compareByDescending<AudioTrack> { it.id }.thenBy { it.kind.ordinal })
        val progressive =
            streams
                .flatMap { it.progressive }
                .filter { it.urls.isNotEmpty() }
                .distinctBy { it.urls.firstOrNull().orEmpty() }
        val supportFormats =
            streams
                .flatMap { it.supportFormats }
                .distinctBy { it.quality }
                .sortedByDescending { it.quality }
        return first.copy(
            durationMs = streams.firstNotNullOfOrNull { it.durationMs },
            dash = VideoDashStream(durationMs = streams.firstNotNullOfOrNull { it.dash?.durationMs }, videos = dashVideos, audios = audios),
            progressive = progressive,
            supportFormats = supportFormats,
            resume = streams.firstNotNullOfOrNull { it.resume },
            vVoucher = streams.firstNotNullOfOrNull { it.vVoucher },
        )
    }

    private fun parseRecommendItem(obj: JSONObject): VideoCard? {
        if (obj.optString("card_goto", "").trim() != "av") return null
        val args = obj.optJSONObject("args") ?: JSONObject()
        val playerArgs = obj.optJSONObject("player_args") ?: JSONObject()
        val aid =
            firstPositiveLong(
                playerArgs.optLong("aid", 0L),
                args.optLong("aid", 0L),
                obj.optString("param", "").trim().toLongOrNull(),
            ) ?: return null
        val cid = playerArgs.optLong("cid", 0L).takeIf { it > 0L } ?: return null
        val title = obj.optString("title", "").trim().takeIf { it.isNotBlank() } ?: return null
        val cover = obj.optString("cover", "").trim().takeIf { it.isNotBlank() } ?: return null
        val durationSec =
            playerArgs.optInt("duration", 0).takeIf { it > 0 }
                ?: parseDurationText(obj.optString("cover_right_text", ""))
        return VideoCard(
            bvid = "",
            cid = cid,
            aid = aid,
            title = title,
            coverUrl = cover,
            durationSec = durationSec,
            ownerName = args.optString("up_name", "").trim(),
            ownerFace = null,
            ownerMid = args.optLong("up_id", 0L).takeIf { it > 0L },
            view = parseCountText(obj.optString("cover_left_text_1", "")),
            danmaku = parseCountText(obj.optString("cover_left_text_2", "")),
            pubDate = null,
            pubDateText = null,
            trackId = obj.optString("track_id", obj.optString("trackid", "")).trim().takeIf { it.isNotBlank() }
                ?: obj.optInt("idx", -1).takeIf { it >= 0 }?.let { "app-idx:$it" },
        )
    }

    private fun ugcVideoTrack(stream: UgcStream): VideoTrack? {
        val info = stream.streamInfo
        val video = stream.dashVideo
        val qn = info.quality.takeIf { it > 0 }?.toInt() ?: return null
        return VideoTrack(
            qn = qn,
            codecid = video.codecid.toInt(),
            urls = urlsOf(video.baseUrl, video.backupUrlList),
            info = ugcTrackInfo(video),
            isDolbyVision = video.codecid.toInt() == CODEC_DOLBY_VISION,
            mediaRequestProfile = VideoMediaRequestProfile.APP,
        )
    }

    private fun pgcVideoTrack(stream: PgcStream): VideoTrack? {
        val info = stream.info
        val video = stream.dashVideo
        val qn = info.quality.takeIf { it > 0 } ?: return null
        return VideoTrack(
            qn = qn,
            codecid = video.codecid.toInt(),
            urls = urlsOf(video.baseUrl, video.backupUrlList),
            info = pgcTrackInfo(video),
            isDolbyVision = video.codecid.toInt() == CODEC_DOLBY_VISION,
            mediaRequestProfile = VideoMediaRequestProfile.APP,
        )
    }

    private fun ugcAudioTrack(
        item: UgcDashItem,
        kind: VideoAudioKind,
    ): AudioTrack? =
        AudioTrack(
            id = item.id.toInt(),
            kind = kind,
            urls = urlsOf(item.baseUrl, item.backupUrlList),
            info =
                VideoTrackInfo(
                    mimeType = "audio/mp4",
                    codecs = null,
                    bandwidth = item.bandwidth.toLong().takeIf { it > 0L },
                    width = null,
                    height = null,
                    frameRate = null,
                    segmentBase = null,
                ),
            mediaRequestProfile = VideoMediaRequestProfile.APP,
        ).takeIf { it.urls.isNotEmpty() }

    private fun pgcAudioTrack(
        item: PgcDashItem,
        kind: VideoAudioKind,
    ): AudioTrack? =
        AudioTrack(
            id = item.id.toInt(),
            kind = kind,
            urls = urlsOf(item.baseUrl, item.backupUrlList),
            info =
                VideoTrackInfo(
                    mimeType = "audio/mp4",
                    codecs = null,
                    bandwidth = item.bandwidth.toLong().takeIf { it > 0L },
                    width = null,
                    height = null,
                    frameRate = null,
                    segmentBase = null,
                ),
            mediaRequestProfile = VideoMediaRequestProfile.APP,
        ).takeIf { it.urls.isNotEmpty() }

    private fun ugcProgressive(stream: UgcStream): List<VideoProgressiveStream> {
        if (!stream.hasSegmentVideo()) return emptyList()
        return stream.segmentVideo.segmentList.mapNotNull { segment -> ugcProgressiveSegment(segment) }
    }

    private fun pgcProgressive(stream: PgcStream): List<VideoProgressiveStream> {
        if (!stream.hasSegmentVideo()) return emptyList()
        return stream.segmentVideo.segmentList.mapNotNull { segment -> pgcProgressiveSegment(segment) }
    }

    private fun ugcProgressiveSegment(segment: UgcResponseUrl): VideoProgressiveStream? =
        VideoProgressiveStream(
            urls = urlsOf(segment.url, segment.backupUrlList),
            lengthMs = segment.length.takeIf { it > 0L },
            mediaRequestProfile = VideoMediaRequestProfile.APP,
        ).takeIf { it.urls.isNotEmpty() }

    private fun pgcProgressiveSegment(segment: PgcResponseUrl): VideoProgressiveStream? =
        VideoProgressiveStream(
            urls = urlsOf(segment.url, segment.backupUrlList),
            lengthMs = segment.length.takeIf { it > 0L },
            mediaRequestProfile = VideoMediaRequestProfile.APP,
        ).takeIf { it.urls.isNotEmpty() }

    private fun ugcSupportFormats(vod: VodInfo): List<VideoSupportFormat> =
        vod.streamListList
            .mapNotNull { stream -> ugcSupportFormat(stream.streamInfo) }
            .distinctBy { it.quality }
            .sortedByDescending { it.quality }

    private fun pgcSupportFormats(video: PgcVideoInfo): List<VideoSupportFormat> =
        video.streamListList
            .mapNotNull { stream -> pgcSupportFormat(stream.info) }
            .distinctBy { it.quality }
            .sortedByDescending { it.quality }

    private fun ugcSupportFormat(info: UgcStreamInfo): VideoSupportFormat? {
        val quality = info.quality.takeIf { it > 0 }?.toInt() ?: return null
        val label = firstNonBlank(info.newDescription, info.displayDesc, info.description)
        return VideoSupportFormat(quality = quality, label = label)
    }

    private fun pgcSupportFormat(info: PgcStreamInfo): VideoSupportFormat? {
        val quality = info.quality.takeIf { it > 0 } ?: return null
        val label = firstNonBlank(info.newDescription, info.displayDesc, info.description)
        return VideoSupportFormat(quality = quality, label = label)
    }

    private fun ugcTrackInfo(video: UgcDashVideo): VideoTrackInfo =
        VideoTrackInfo(
            mimeType = "video/mp4",
            codecs = codecString(video.codecid.toInt()),
            bandwidth = video.bandwidth.toLong().takeIf { it > 0L },
            width = video.width.takeIf { it > 0 },
            height = video.height.takeIf { it > 0 },
            frameRate = video.frameRate.trim().takeIf { it.isNotBlank() },
            segmentBase = null,
        )

    private fun pgcTrackInfo(video: PgcDashVideo): VideoTrackInfo =
        VideoTrackInfo(
            mimeType = "video/mp4",
            codecs = codecString(video.codecid.toInt()),
            bandwidth = video.bandwidth.toLong().takeIf { it > 0L },
            width = video.width.takeIf { it > 0 },
            height = video.height.takeIf { it > 0 },
            frameRate = video.frameRate.trim().takeIf { it.isNotBlank() },
            segmentBase = null,
        )

    private fun urlsOf(
        baseUrl: String,
        backupUrls: List<String>,
    ): List<String> =
        buildList {
            baseUrl.trim().takeIf { it.isNotBlank() }?.let(::add)
            backupUrls.mapNotNullTo(this) { it.trim().takeIf { url -> url.isNotBlank() } }
        }.distinct()

    private fun parseDurationText(text: String): Int {
        val parts = text.trim().split(":").filter { it.isNotBlank() }
        if (parts.isEmpty()) return 0
        return runCatching {
            when (parts.size) {
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                2 -> parts[0].toInt() * 60 + parts[1].toInt()
                else -> parts[0].toInt()
            }
        }.getOrDefault(0)
    }

    private fun parseCountText(text: String): Long? {
        val raw = text.trim().removeSuffix("观看").removeSuffix("弹幕").trim()
        if (raw.isBlank()) return null
        return runCatching {
            when {
                raw.endsWith("万") -> (raw.removeSuffix("万").toDouble() * 10_000).toLong()
                raw.endsWith("亿") -> (raw.removeSuffix("亿").toDouble() * 100_000_000).toLong()
                else -> raw.toLong()
            }
        }.getOrNull()?.takeIf { it > 0L }
    }

    private fun codecString(codecid: Int): String? =
        when (codecid) {
            CODEC_AVC -> "avc1"
            CODEC_HEVC -> "hev1"
            CODEC_AV1 -> "av01"
            else -> null
        }

    private fun firstPositiveLong(vararg values: Long?): Long? = values.firstOrNull { it != null && it > 0L }

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private companion object {
        private const val CODEC_AVC = 7
        private const val CODEC_HEVC = 12
        private const val CODEC_AV1 = 13
        private const val CODEC_DOLBY_VISION = 20
    }
}
