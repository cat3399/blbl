package blbl.cat3399.core.api.video.app

import bilibili.community.service.dm.v1.DMGrpc
import bilibili.community.service.dm.v1.DmViewReq
import blbl.cat3399.core.api.video.VideoSubtitle
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.withContext

internal object DmViewGrpcApi {
    @Volatile
    private var cachedChannel: ManagedChannel? = null

    suspend fun subtitles(aid: Long, cid: Long): List<VideoSubtitle> =
        withContext(Dispatchers.IO) {
            val reply =
                DMGrpc
                    .newBlockingStub(channel())
                    .withDeadlineAfter(GRPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .dmView(
                        DmViewReq
                            .newBuilder()
                            .setPid(aid)
                            .setOid(cid)
                            .setType(1)
                            .build(),
                    )
            if (!reply.hasSubtitle()) return@withContext emptyList()
            reply.subtitle.subtitlesList.mapNotNull { item ->
                val url = item.subtitleUrl.trim()
                val lan = item.lan.trim()
                if (url.isBlank() || lan.isBlank()) return@mapNotNull null
                VideoSubtitle(
                    url = url,
                    language = lan,
                    languageDoc = item.lanDoc.trim().ifBlank { lan },
                )
            }
        }

    @Synchronized
    private fun channel(): ManagedChannel {
        cachedChannel?.takeIf { !it.isShutdown && !it.isTerminated }?.let { return it }
        return ManagedChannelBuilder
            .forAddress(GRPC_HOST, GRPC_PORT)
            .useTransportSecurity()
            .executor(Dispatchers.IO.asExecutor())
            .build()
            .also { cachedChannel = it }
    }

    private const val GRPC_HOST = "grpc.biliapi.net"
    private const val GRPC_PORT = 443
    private const val GRPC_DEADLINE_SECONDS = 20L
}
