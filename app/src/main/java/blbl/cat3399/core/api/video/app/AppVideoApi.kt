package blbl.cat3399.core.api.video.app

import bilibili.app.playerunite.v1.PlayViewUniteReq
import bilibili.pgc.gateway.player.v2.CodeType as PgcCodeType
import bilibili.pgc.gateway.player.v2.PlayViewReq
import bilibili.playershared.CodeType
import bilibili.playershared.VideoVod
import blbl.cat3399.core.api.BiliApiCapability
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.api.BiliApiSource
import blbl.cat3399.core.api.video.ArchiveRelatedPage
import blbl.cat3399.core.api.video.ArchiveRelatedRequest
import blbl.cat3399.core.api.video.UgcSeasonArchivesPage
import blbl.cat3399.core.api.video.UgcSeasonArchivesRequest
import blbl.cat3399.core.api.video.VideoCardPage
import blbl.cat3399.core.api.video.VideoCollectionSectionsPage
import blbl.cat3399.core.api.video.VideoCollectionSectionsRequest
import blbl.cat3399.core.api.video.VideoDetail
import blbl.cat3399.core.api.video.VideoDetailRequest
import blbl.cat3399.core.api.video.VideoDynamicTagRequest
import blbl.cat3399.core.api.video.VideoOnlineStatus
import blbl.cat3399.core.api.video.VideoOnlineStatusRequest
import blbl.cat3399.core.api.video.VideoPlayerInfo
import blbl.cat3399.core.api.video.VideoPlayerInfoRequest
import blbl.cat3399.core.api.video.VideoPlayKind
import blbl.cat3399.core.api.video.VideoPlayRequest
import blbl.cat3399.core.api.video.VideoPlayStream
import blbl.cat3399.core.api.video.VideoPopularRequest
import blbl.cat3399.core.api.video.VideoRecommendPage
import blbl.cat3399.core.api.video.VideoRecommendRequest
import blbl.cat3399.core.api.video.VideoRegionLatestRequest
import blbl.cat3399.core.api.video.VideoSeriesArchivesRequest
import blbl.cat3399.core.api.video.VideoShotInfo
import blbl.cat3399.core.api.video.VideoShotRequest
import blbl.cat3399.core.api.video.VideoSourceApi
import blbl.cat3399.core.api.video.VideoTagsPage
import blbl.cat3399.core.api.video.VideoTagsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal class AppVideoApi(
    private val httpTransport: AppVideoHttpTransport = BiliClientAppVideoHttpTransport,
    private val grpcTransport: AppVideoGrpcTransport = BiliClientAppVideoGrpcTransport,
) : VideoSourceApi {
    override val source: BiliApiSource = BiliApiSource.APP
    override val capabilities: Set<BiliApiCapability> =
        setOf(
            BiliApiCapability.VIDEO_RECOMMEND,
            BiliApiCapability.VIDEO_PLAY_URL_UGC,
            BiliApiCapability.VIDEO_PLAY_URL_PGC,
        )
    private val mapper = AppVideoMapper(source)

    override suspend fun recommend(request: VideoRecommendRequest): VideoRecommendPage {
        val idx = (request.fetchRow - 1).coerceAtLeast(0)
        val json = httpTransport.recommend(idx = idx)
        checkApiCode(json)
        val itemsJson = json.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
        val items = withContext(Dispatchers.Default) { mapper.parseRecommendItems(itemsJson) }
        return VideoRecommendPage(source = source, request = request, items = items)
    }

    override suspend fun playUrl(request: VideoPlayRequest): VideoPlayStream {
        return when (request.kind) {
            VideoPlayKind.UGC -> requestUgcPlayStream(request)
            VideoPlayKind.PGC -> requestPgcPlayStream(request)
        }
    }

    private suspend fun requestUgcPlayStream(request: VideoPlayRequest): VideoPlayStream {
        val aid = request.aid?.takeIf { it > 0L } ?: error("aid required")
        val cid = request.cid?.takeIf { it > 0L } ?: error("cid required")
        val req =
            PlayViewUniteReq
                .newBuilder()
                .setVod(
                    VideoVod
                        .newBuilder()
                        .setAid(aid)
                        .setCid(cid)
                        .setQn(GRPC_QN_ALL)
                        .setFnver(0)
                        .setFnval(request.fnval)
                        .setDownload(0)
                        .setFourk(true)
                        .setForceHost(FORCE_HOST_HTTPS)
                        .setPreferCodecType(ugcPreferredCodecType(request.preferCodec))
                        .setVoiceBalance(1)
                        .build(),
                )
                .apply { request.bvid?.trim()?.takeIf { it.isNotBlank() }?.let(::setBvid) }
                .build()
        return mapper.parseUgcPlayStream(reply = grpcTransport.playUgc(req), request = request)
    }

    private suspend fun requestPgcPlayStream(request: VideoPlayRequest): VideoPlayStream {
        val epId = request.epId?.takeIf { it > 0L } ?: error("epId required")
        val codecTypes = listOf(PgcCodeType.CODE264, PgcCodeType.CODE265, PgcCodeType.NOCODE)
        val streams =
            requestAllCodecs(codecTypes) { codecType ->
                val req =
                    PlayViewReq
                        .newBuilder()
                        .setEpid(epId)
                        .setQn(request.qn.toLong())
                        .setFnver(0)
                        .setFnval(request.fnval)
                        .setFourk(true)
                        .setForceHost(FORCE_HOST_DEFAULT)
                        .setDownload(0)
                        .setPreferCodecType(codecType)
                        .apply { request.cid?.takeIf { it > 0L }?.let(::setCid) }
                        .build()
                mapper.parsePgcPlayStream(reply = grpcTransport.playPgc(req), request = request)
            }
        return mapper.mergeStreams(streams)
    }

    private suspend fun <CodecT> requestAllCodecs(
        codecTypes: List<CodecT>,
        block: suspend (CodecT) -> VideoPlayStream,
    ): List<VideoPlayStream> =
        coroutineScope {
            val results =
                codecTypes
                    .map { codecType ->
                        async {
                            runCatching { block(codecType) }
                        }
                    }
                    .awaitAll()
            val streams = results.mapNotNull { it.getOrNull() }
            if (streams.isNotEmpty()) return@coroutineScope streams
            val failure = results.firstOrNull()?.exceptionOrNull()
            if (failure is CancellationException) throw failure
            throw failure ?: IllegalStateException("app_play_url_empty")
        }

    override suspend fun detail(request: VideoDetailRequest): VideoDetail = notImplemented()

    override suspend fun tags(request: VideoTagsRequest): VideoTagsPage = notImplemented()

    override suspend fun popular(request: VideoPopularRequest): VideoCardPage<VideoPopularRequest> = notImplemented()

    override suspend fun regionLatest(request: VideoRegionLatestRequest): VideoCardPage<VideoRegionLatestRequest> = notImplemented()

    override suspend fun dynamicTag(request: VideoDynamicTagRequest): VideoCardPage<VideoDynamicTagRequest> = notImplemented()

    override suspend fun archiveRelated(request: ArchiveRelatedRequest): ArchiveRelatedPage = notImplemented()

    override suspend fun playerInfo(request: VideoPlayerInfoRequest): VideoPlayerInfo = notImplemented()

    override suspend fun onlineStatus(request: VideoOnlineStatusRequest): VideoOnlineStatus = notImplemented()

    override suspend fun videoShot(request: VideoShotRequest): VideoShotInfo = notImplemented()

    override suspend fun ugcSeasonArchives(request: UgcSeasonArchivesRequest): UgcSeasonArchivesPage = notImplemented()

    override suspend fun collectionSections(request: VideoCollectionSectionsRequest): VideoCollectionSectionsPage = notImplemented()

    override suspend fun seriesArchives(request: VideoSeriesArchivesRequest): VideoCardPage<VideoSeriesArchivesRequest> = notImplemented()

    private fun checkApiCode(json: JSONObject) {
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    private fun notImplemented(): Nothing = error("app_video_capability_not_implemented")

    private fun ugcPreferredCodecType(preferCodec: String?): CodeType =
        when (preferCodec?.trim()?.uppercase(Locale.US)) {
            "HEVC" -> CodeType.CODE265
            "AV1" -> CodeType.CODEAV1
            else -> CodeType.CODE264
        }

    private companion object {
        private const val GRPC_QN_ALL = 127L
        private const val FORCE_HOST_DEFAULT = 0
        private const val FORCE_HOST_HTTPS = 2
    }
}
