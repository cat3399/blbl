package blbl.cat3399.core.api.video

import blbl.cat3399.core.api.ApiSourceSelector
import blbl.cat3399.core.api.BiliApiCapability
import blbl.cat3399.core.api.BiliApiSource
import blbl.cat3399.core.api.BiliApiSourceProvider
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.model.VideoTag
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.api.video.app.AppVideoApi
import blbl.cat3399.core.api.video.web.WebVideoApi

data class VideoRecommendRequest(
    val freshIdx: Int = 1,
    val ps: Int = 20,
    val fetchRow: Int = 1,
)

data class VideoRecommendPage(
    val source: BiliApiSource,
    val request: VideoRecommendRequest,
    val items: List<VideoCard>,
)

data class VideoPopularRequest(
    val pn: Int = 1,
    val ps: Int = 20,
)

data class VideoRegionLatestRequest(
    val rid: Int,
    val pn: Int = 1,
    val ps: Int = 20,
)

data class VideoDynamicTagRequest(
    val rid: Int,
    val tagId: Long,
    val pn: Int = 1,
    val ps: Int = 20,
)

data class VideoCardPage<RequestT>(
    val source: BiliApiSource,
    val request: RequestT,
    val items: List<VideoCard>,
    val page: Int,
    val hasMore: Boolean,
    val total: Int,
)

data class ArchiveRelatedRequest(
    val bvid: String,
    val aid: Long? = null,
)

data class ArchiveRelatedPage(
    val source: BiliApiSource,
    val request: ArchiveRelatedRequest,
    val items: List<VideoCard>,
)

data class VideoTagsRequest(
    val bvid: String? = null,
    val aid: Long? = null,
    val cid: Long? = null,
)

data class VideoTagsPage(
    val source: BiliApiSource,
    val request: VideoTagsRequest,
    val tags: List<VideoTag>,
)

enum class VideoPlayKind {
    UGC,
    PGC,
}

data class VideoPlayRequest(
    val kind: VideoPlayKind,
    val bvid: String? = null,
    val aid: Long? = null,
    val cid: Long? = null,
    val epId: Long? = null,
    val qn: Int = 80,
    val fnval: Int = 16,
    val tryLook: Boolean = false,
    val preferCodec: String? = null,
)

data class VideoPlayerInfoRequest(
    val bvid: String,
    val cid: Long,
)

data class VideoPlayerInfo(
    val source: BiliApiSource,
    val request: VideoPlayerInfoRequest,
    val subtitles: List<VideoSubtitle>,
    val needLoginSubtitle: Boolean,
    val resume: VideoPlayResume?,
)

data class VideoOnlineStatusRequest(
    val bvid: String,
    val cid: Long,
)

data class VideoOnlineStatus(
    val source: BiliApiSource,
    val request: VideoOnlineStatusRequest,
    val totalText: String?,
    val countText: String?,
    val totalEnabled: Boolean,
    val countEnabled: Boolean,
) {
    fun displayCountText(): String =
        when {
            totalEnabled && !totalText.isNullOrBlank() -> totalText
            countEnabled && !countText.isNullOrBlank() -> countText
            else -> "-"
        }
}

data class VideoShotRequest(
    val aid: Long? = null,
    val bvid: String? = null,
    val cid: Long? = null,
    val needJsonArrayIndex: Boolean = false,
)

data class VideoShotInfo(
    val source: BiliApiSource,
    val request: VideoShotRequest,
    val pvData: String?,
    val imgXLen: Int,
    val imgYLen: Int,
    val imgXSize: Int,
    val imgYSize: Int,
    val image: List<String>,
    val index: List<Int>?,
)

data class UgcSeasonArchivesRequest(
    val mid: Long,
    val seasonId: Long,
    val pageNum: Int = 1,
    val pageSize: Int = 200,
    val sortReverse: Boolean = false,
)

data class UgcSeasonArchivesPage(
    val source: BiliApiSource,
    val request: UgcSeasonArchivesRequest,
    val items: List<VideoCard>,
    val totalCount: Int?,
)

enum class VideoCollectionKind {
    SEASON,
    SERIES,
}

data class VideoCollectionSection(
    val kind: VideoCollectionKind,
    val id: Long,
    val title: String,
    val totalCount: Int?,
    val items: List<VideoCard>,
)

data class VideoCollectionSectionsRequest(
    val mid: Long,
    val pageNum: Int = 1,
    val pageSize: Int = 20,
)

data class VideoCollectionSectionsPage(
    val source: BiliApiSource,
    val request: VideoCollectionSectionsRequest,
    val totalPages: Int,
    val sections: List<VideoCollectionSection>,
)

data class VideoSeriesArchivesRequest(
    val mid: Long,
    val seriesId: Long,
    val pageNum: Int = 1,
    val pageSize: Int = 20,
    val sort: String = "desc",
    val onlyNormal: Boolean = true,
)

internal interface VideoSourceApi : BiliApiSourceProvider {
    suspend fun detail(request: VideoDetailRequest): VideoDetail

    suspend fun tags(request: VideoTagsRequest): VideoTagsPage

    suspend fun recommend(request: VideoRecommendRequest): VideoRecommendPage

    suspend fun popular(request: VideoPopularRequest): VideoCardPage<VideoPopularRequest>

    suspend fun regionLatest(request: VideoRegionLatestRequest): VideoCardPage<VideoRegionLatestRequest>

    suspend fun dynamicTag(request: VideoDynamicTagRequest): VideoCardPage<VideoDynamicTagRequest>

    suspend fun archiveRelated(request: ArchiveRelatedRequest): ArchiveRelatedPage

    suspend fun playUrl(request: VideoPlayRequest): VideoPlayStream

    suspend fun playerInfo(request: VideoPlayerInfoRequest): VideoPlayerInfo

    suspend fun onlineStatus(request: VideoOnlineStatusRequest): VideoOnlineStatus

    suspend fun videoShot(request: VideoShotRequest): VideoShotInfo

    suspend fun ugcSeasonArchives(request: UgcSeasonArchivesRequest): UgcSeasonArchivesPage

    suspend fun collectionSections(request: VideoCollectionSectionsRequest): VideoCollectionSectionsPage

    suspend fun seriesArchives(request: VideoSeriesArchivesRequest): VideoCardPage<VideoSeriesArchivesRequest>
}

internal class VideoApiSources(
    providers: List<VideoSourceApi>,
    preferredSource: () -> BiliApiSource,
) {
    private val selector = ApiSourceSelector(providers = providers, preferredSource = preferredSource)

    suspend fun detail(request: VideoDetailRequest): VideoDetail =
        selector.providerFor(BiliApiCapability.VIDEO_DETAIL).detail(request)

    suspend fun tags(request: VideoTagsRequest): VideoTagsPage =
        selector.providerFor(BiliApiCapability.VIDEO_TAGS).tags(request)

    suspend fun recommend(request: VideoRecommendRequest): VideoRecommendPage =
        selector.providerFor(BiliApiCapability.VIDEO_RECOMMEND).recommend(request)

    suspend fun popular(request: VideoPopularRequest): VideoCardPage<VideoPopularRequest> =
        selector.providerFor(BiliApiCapability.VIDEO_POPULAR).popular(request)

    suspend fun regionLatest(request: VideoRegionLatestRequest): VideoCardPage<VideoRegionLatestRequest> =
        selector.providerFor(BiliApiCapability.VIDEO_REGION_LATEST).regionLatest(request)

    suspend fun dynamicTag(request: VideoDynamicTagRequest): VideoCardPage<VideoDynamicTagRequest> =
        selector.providerFor(BiliApiCapability.VIDEO_DYNAMIC_TAG).dynamicTag(request)

    suspend fun archiveRelated(request: ArchiveRelatedRequest): ArchiveRelatedPage =
        selector.providerFor(BiliApiCapability.VIDEO_ARCHIVE_RELATED).archiveRelated(request)

    suspend fun playUrl(request: VideoPlayRequest): VideoPlayStream =
        selector.providerFor(request.capability).playUrl(request)

    suspend fun playerInfo(request: VideoPlayerInfoRequest): VideoPlayerInfo =
        selector.providerFor(BiliApiCapability.VIDEO_PLAYER_INFO).playerInfo(request)

    suspend fun onlineStatus(request: VideoOnlineStatusRequest): VideoOnlineStatus =
        selector.providerFor(BiliApiCapability.VIDEO_ONLINE_STATUS).onlineStatus(request)

    suspend fun videoShot(request: VideoShotRequest): VideoShotInfo =
        selector.providerFor(BiliApiCapability.VIDEO_SHOT).videoShot(request)

    suspend fun ugcSeasonArchives(request: UgcSeasonArchivesRequest): UgcSeasonArchivesPage =
        selector.providerFor(BiliApiCapability.VIDEO_UGC_SEASON_ARCHIVES).ugcSeasonArchives(request)

    suspend fun collectionSections(request: VideoCollectionSectionsRequest): VideoCollectionSectionsPage =
        selector.providerFor(BiliApiCapability.VIDEO_COLLECTION_SECTIONS).collectionSections(request)

    suspend fun seriesArchives(request: VideoSeriesArchivesRequest): VideoCardPage<VideoSeriesArchivesRequest> =
        selector.providerFor(BiliApiCapability.VIDEO_SERIES_ARCHIVES).seriesArchives(request)

    private val VideoPlayRequest.capability: BiliApiCapability
        get() =
            when (kind) {
                VideoPlayKind.UGC -> BiliApiCapability.VIDEO_PLAY_URL_UGC
                VideoPlayKind.PGC -> BiliApiCapability.VIDEO_PLAY_URL_PGC
            }
}

internal object VideoApiGateway {
    private val sources =
        VideoApiSources(
            providers = listOf(WebVideoApi(), AppVideoApi()),
            preferredSource = {
                runCatching { BiliApiSource.fromPrefValue(BiliClient.prefs.apiSource) }.getOrDefault(BiliApiSource.WEB)
            },
    )

    suspend fun detail(request: VideoDetailRequest): VideoDetail = sources.detail(request)

    suspend fun tags(request: VideoTagsRequest): VideoTagsPage = sources.tags(request)

    suspend fun recommend(request: VideoRecommendRequest): VideoRecommendPage = sources.recommend(request)

    suspend fun popular(request: VideoPopularRequest): VideoCardPage<VideoPopularRequest> = sources.popular(request)

    suspend fun regionLatest(request: VideoRegionLatestRequest): VideoCardPage<VideoRegionLatestRequest> =
        sources.regionLatest(request)

    suspend fun dynamicTag(request: VideoDynamicTagRequest): VideoCardPage<VideoDynamicTagRequest> =
        sources.dynamicTag(request)

    suspend fun archiveRelated(request: ArchiveRelatedRequest): ArchiveRelatedPage = sources.archiveRelated(request)

    suspend fun playUrl(request: VideoPlayRequest): VideoPlayStream = sources.playUrl(request)

    suspend fun playerInfo(request: VideoPlayerInfoRequest): VideoPlayerInfo = sources.playerInfo(request)

    suspend fun onlineStatus(request: VideoOnlineStatusRequest): VideoOnlineStatus = sources.onlineStatus(request)

    suspend fun videoShot(request: VideoShotRequest): VideoShotInfo = sources.videoShot(request)

    suspend fun ugcSeasonArchives(request: UgcSeasonArchivesRequest): UgcSeasonArchivesPage =
        sources.ugcSeasonArchives(request)

    suspend fun collectionSections(request: VideoCollectionSectionsRequest): VideoCollectionSectionsPage =
        sources.collectionSections(request)

    suspend fun seriesArchives(request: VideoSeriesArchivesRequest): VideoCardPage<VideoSeriesArchivesRequest> =
        sources.seriesArchives(request)
}
