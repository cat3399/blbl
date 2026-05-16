package blbl.cat3399.core.api.video

import blbl.cat3399.core.api.BiliApiSource

data class VideoDetailRequest(
    val bvid: String? = null,
    val aid: Long? = null,
)

data class VideoDetail(
    val source: BiliApiSource,
    val request: VideoDetailRequest,
    val aid: Long?,
    val bvid: String,
    val cid: Long?,
    val title: String?,
    val description: String?,
    val coverUrl: String?,
    val tabId: Int?,
    val tabName: String?,
    val redirectUrl: String?,
    val owner: VideoOwner?,
    val stat: VideoDetailStat,
    val pubDateSec: Long?,
    val durationSec: Long?,
    val dimension: VideoDimension?,
    val pages: List<VideoDetailPage>,
    val ugcSeason: VideoUgcSeason?,
    val subtitles: List<VideoSubtitle>,
    val upFollowed: Boolean?,
)

data class VideoOwner(
    val mid: Long,
    val name: String?,
    val avatarUrl: String?,
)

data class VideoDetailStat(
    val view: Long?,
    val danmaku: Long?,
    val reply: Long?,
    val like: Long?,
    val coin: Long?,
    val favorite: Long?,
)

data class VideoDimension(
    val width: Int,
    val height: Int,
    val rotate: Int,
)

data class VideoDetailPage(
    val cid: Long,
    val page: Int,
    val title: String?,
    val durationSec: Int?,
    val dimension: VideoDimension?,
)

data class VideoUgcSeason(
    val id: Long?,
    val title: String?,
    val epCount: Int?,
    val ownerMid: Long?,
    val sections: List<VideoUgcSeasonSection>,
)

data class VideoUgcSeasonSection(
    val episodes: List<VideoUgcSeasonEpisode>,
)

data class VideoUgcSeasonEpisode(
    val bvid: String,
    val cid: Long?,
    val aid: Long?,
    val title: String?,
    val coverUrl: String?,
    val durationSec: Int?,
    val owner: VideoOwner?,
    val stat: VideoDetailStat,
    val pubDateSec: Long?,
)

data class VideoSubtitle(
    val url: String,
    val language: String,
    val languageDoc: String,
)
