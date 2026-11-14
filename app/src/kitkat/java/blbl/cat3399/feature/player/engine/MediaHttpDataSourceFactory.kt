package blbl.cat3399.feature.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import blbl.cat3399.core.net.BiliClient
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
internal fun createHttpDataSourceFactory(
    client: OkHttpClient = BiliClient.cdnOkHttp,
    transferListener: TransferListener? = null,
): HttpDataSource.Factory =
    OkHttp3DataSource.Factory(client)
        .setUserAgent(BiliClient.prefs.userAgent)
        .apply { if (transferListener != null) setTransferListener(transferListener) }
        .setDefaultRequestProperties(mapOf("Referer" to "https://www.bilibili.com/"))
