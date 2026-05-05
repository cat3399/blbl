package blbl.cat3399.core.api.video.app

import android.os.Build
import bilibili.app.playerunite.v1.PlayViewUniteReply
import bilibili.app.playerunite.v1.PlayViewUniteReq
import bilibili.app.playerunite.v1.PlayerGrpc
import bilibili.metadata.Metadata as BiliMetadata
import bilibili.metadata.device.Device
import bilibili.metadata.locale.Locale
import bilibili.metadata.network.Network
import bilibili.metadata.network.NetworkType
import bilibili.pgc.gateway.player.v2.PlayURLGrpc
import bilibili.pgc.gateway.player.v2.PlayViewReply
import bilibili.pgc.gateway.player.v2.PlayViewReq
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.BiliAppAuthSession
import common.ErrorProto
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.withContext
import io.grpc.Metadata as GrpcMetadata

internal interface AppVideoGrpcTransport {
    suspend fun playUgc(request: PlayViewUniteReq): PlayViewUniteReply

    suspend fun playPgc(request: PlayViewReq): PlayViewReply
}

internal object BiliClientAppVideoGrpcTransport : AppVideoGrpcTransport {
    @Volatile
    private var cachedChannel: ManagedChannel? = null

    @Volatile
    private var cachedIdentity: String = ""

    override suspend fun playUgc(request: PlayViewUniteReq): PlayViewUniteReply =
        withContext(Dispatchers.IO) {
            runGrpcCall {
                PlayerGrpc
                    .newBlockingStub(channel())
                    .withDeadlineAfter(GRPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .playViewUnite(request)
            }
        }

    override suspend fun playPgc(request: PlayViewReq): PlayViewReply =
        withContext(Dispatchers.IO) {
            runGrpcCall {
                PlayURLGrpc
                    .newBlockingStub(channel())
                    .withDeadlineAfter(GRPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .playView(request)
            }
        }

    @Synchronized
    private fun channel(): ManagedChannel {
        val session = requireAppSession()
        val buvid = BiliClient.prefs.deviceBuvid.trim().takeIf { it.isNotBlank() } ?: error("app_buvid_missing")
        val identity = "${session.accessKey}:$buvid"
        cachedChannel?.takeIf { cachedIdentity == identity && !it.isShutdown && !it.isTerminated }?.let { return it }

        cachedChannel?.shutdownNow()
        cachedIdentity = identity
        return ManagedChannelBuilder
            .forAddress(GRPC_HOST, GRPC_PORT)
            .useTransportSecurity()
            .executor(Dispatchers.IO.asExecutor())
            .intercept(AppGrpcMetadataInterceptor(session = session, buvid = buvid))
            .build()
            .also { cachedChannel = it }
    }

    private fun requireAppSession(): BiliAppAuthSession =
        BiliClient.prefs.appAuthSession ?: error("app_auth_session_missing")

    private inline fun <T> runGrpcCall(block: () -> T): T {
        try {
            return block()
        } catch (t: Throwable) {
            throw normalizeGrpcException(t)
        }
    }

    private fun normalizeGrpcException(t: Throwable): Throwable {
        val trailers =
            when (t) {
                is StatusException -> t.trailers
                is StatusRuntimeException -> t.trailers
                else -> null
            } ?: return t
        val key = GrpcMetadata.Key.of("grpc-status-details-bin", GrpcMetadata.BINARY_BYTE_MARSHALLER)
        val details = trailers.get(key) ?: return t
        runCatching {
            val status = bilibili.rpc.Status.parseFrom(details)
            val message = status.message.trim().takeIf { it.isNotBlank() }
            if (message != null) return IllegalStateException(message, t)
        }
        runCatching {
            val status = ErrorProto.parseFrom(details)
            val message = status.message.trim().takeIf { it.isNotBlank() }
            if (message != null) return IllegalStateException(message, t)
        }
        return t
    }

    private const val GRPC_HOST = "grpc.biliapi.net"
    private const val GRPC_PORT = 443
    private const val GRPC_DEADLINE_SECONDS = 20L
}

private class AppGrpcMetadataInterceptor(
    private val session: BiliAppAuthSession,
    private val buvid: String,
) : ClientInterceptor {
    override fun <ReqT : Any?, RespT : Any?> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            override fun start(
                responseListener: Listener<RespT>,
                headers: GrpcMetadata,
            ) {
                headers.put(
                    GrpcMetadata.Key.of("authorization", GrpcMetadata.ASCII_STRING_MARSHALLER),
                    "identify_v1 ${session.accessKey}",
                )
                headers.put(
                    GrpcMetadata.Key.of("x-bili-metadata-bin", GrpcMetadata.BINARY_BYTE_MARSHALLER),
                    buildMetadata(session = session, buvid = buvid).toByteArray(),
                )
                headers.put(
                    GrpcMetadata.Key.of("x-bili-device-bin", GrpcMetadata.BINARY_BYTE_MARSHALLER),
                    buildDevice(buvid = buvid).toByteArray(),
                )
                headers.put(
                    GrpcMetadata.Key.of("x-bili-local-bin", GrpcMetadata.BINARY_BYTE_MARSHALLER),
                    Locale.newBuilder().setTimezone(APP_TIMEZONE).build().toByteArray(),
                )
                headers.put(
                    GrpcMetadata.Key.of("x-bili-network-bin", GrpcMetadata.BINARY_BYTE_MARSHALLER),
                    Network.newBuilder().setType(NetworkType.WIFI).build().toByteArray(),
                )
                super.start(responseListener, headers)
            }
        }
    }

    private fun buildMetadata(
        session: BiliAppAuthSession,
        buvid: String,
    ): BiliMetadata {
        val model = Build.MODEL?.trim().orEmpty().ifBlank { APP_DEVICE_FALLBACK }
        return BiliMetadata
            .newBuilder()
            .setAccessKey(session.accessKey)
            .setMobiApp(APP_MOBI_APP)
            .setDevice(model)
            .setBuild(APP_BUILD_CODE)
            .setChannel(APP_CHANNEL)
            .setBuvid(buvid)
            .setPlatform(APP_PLATFORM)
            .build()
    }

    private fun buildDevice(buvid: String): Device {
        val model = Build.MODEL?.trim().orEmpty().ifBlank { APP_DEVICE_FALLBACK }
        val osVer = Build.VERSION.RELEASE?.trim().orEmpty()
        return Device
            .newBuilder()
            .setAppId(APP_ID)
            .setMobiApp(APP_MOBI_APP)
            .setDevice(model)
            .setBuild(APP_BUILD_CODE)
            .setChannel(APP_CHANNEL)
            .setBuvid(buvid)
            .setPlatform(APP_PLATFORM)
            .setBrand(Build.BRAND?.trim().orEmpty())
            .setModel(model)
            .setOsver(osVer)
            .setVersionName(APP_VERSION_NAME)
            .build()
    }

    private companion object {
        private const val APP_ID = 1
        private const val APP_BUILD_CODE = 7380300
        private const val APP_VERSION_NAME = "7.38.0"
        private const val APP_CHANNEL = "master"
        private const val APP_MOBI_APP = "android_hd"
        private const val APP_DEVICE_FALLBACK = "Android"
        private const val APP_PLATFORM = "android"
        private const val APP_TIMEZONE = "Asia/Shanghai"
    }
}
