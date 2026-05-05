@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package androidx.media3.datasource.okhttp

import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import blbl.cat3399.feature.player.engine.OkHttp3DataSource
import okhttp3.Call

// Wrapper for kitkat flavor: OkHttpDataSource -> OkHttp3DataSource
class OkHttpDataSource private constructor() {
    class Factory(
        private val callFactory: Call.Factory,
    ) : HttpDataSource.Factory {
        private val delegate = OkHttp3DataSource.Factory(callFactory)

        fun setUserAgent(userAgent: String): Factory = apply {
            delegate.setUserAgent(userAgent)
        }

        override fun setDefaultRequestProperties(properties: Map<String, String>): HttpDataSource.Factory = apply {
            delegate.setDefaultRequestProperties(properties)
        }

        fun setTransferListener(listener: TransferListener): Factory = apply {
            delegate.setTransferListener(listener)
        }

        override fun createDataSource(): HttpDataSource {
            return delegate.createDataSource()
        }
    }
}
