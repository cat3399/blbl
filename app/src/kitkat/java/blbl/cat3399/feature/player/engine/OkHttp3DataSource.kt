@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player.engine

import android.net.Uri
import androidx.media3.common.util.Assertions
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InputStream
import androidx.core.net.toUri

/**
 * OkHttp 3.x based HttpDataSource for API 19 (kitkat flavor).
 * Reuses connection pool and cookies from the provided OkHttpClient.
 */
internal class OkHttp3DataSource(
    private val callFactory: Call.Factory,
    private val userAgent: String?,
    private val defaultRequestProperties: Map<String, String>?,
    transferListener: TransferListener?,
) : BaseDataSource(/* isNetwork = */ true), HttpDataSource {

    private val requestProperties = HashMap<String, String>()
    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var responseBody: ResponseBody? = null
    private var inputStream: InputStream? = null
    private var opened = false
    private var bytesToRead = 0L
    private var bytesRead = 0L

    init {
        if (transferListener != null) {
            addTransferListener(transferListener)
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRead = 0
        bytesToRead = 0
        transferInitializing(dataSpec)

        val request = makeRequest(dataSpec)
        val response: Response
        val responseBody: ResponseBody

        try {
            val call = callFactory.newCall(request)
            response = call.execute()
            this.response = response
            responseBody = response.body() ?: throw HttpDataSource.HttpDataSourceException(
                "Response body is null",
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
            this.responseBody = responseBody
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }

        val responseCode = response.code()
        val responseMessage = response.message()

        // Check for successful response (2xx)
        if (responseCode < 200 || responseCode > 299) {
            val errorStream = try {
                responseBody.bytes()
            } catch (e: IOException) {
                ByteArray(0)
            }
            closeConnectionQuietly()
            throw HttpDataSource.InvalidResponseCodeException(
                responseCode,
                responseMessage,
                /* cause = */ null,
                response.headers().toMultimap(),
                dataSpec,
                errorStream,
            )
        }

        // Determine content length
        val contentLength = if (responseCode == 200 && dataSpec.position != 0L) {
            // Server doesn't support range requests, but we requested a range
            val contentLengthHeader = responseBody.contentLength()
            if (contentLengthHeader != -1L) {
                contentLengthHeader - dataSpec.position
            } else {
                -1L
            }
        } else {
            responseBody.contentLength()
        }

        bytesToRead = if (dataSpec.length != -1L) {
            dataSpec.length
        } else if (contentLength != -1L) {
            contentLength
        } else {
            -1L
        }

        opened = true
        transferStarted(dataSpec)

        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        try {
            return readInternal(buffer, offset, length)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec!!,
                HttpDataSource.HttpDataSourceException.TYPE_READ,
            )
        }
    }

    private fun readInternal(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }

        if (bytesToRead != -1L && bytesRead >= bytesToRead) {
            return -1
        }

        val stream = inputStream ?: run {
            val body = responseBody ?: throw IOException("Response body is null")
            val newStream = body.byteStream()
            inputStream = newStream
            newStream
        }

        val bytesToReadNow = if (bytesToRead == -1L) {
            length
        } else {
            minOf(length.toLong(), bytesToRead - bytesRead).toInt()
        }

        val read = stream.read(buffer, offset, bytesToReadNow)
        if (read == -1) {
            return -1
        }

        bytesRead += read
        bytesTransferred(read)
        return read
    }

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
            closeConnectionQuietly()
        }
    }

    override fun getUri(): Uri? {
        return response?.request()?.url()?.toString()?.toUri()
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return response?.headers()?.toMultimap() ?: emptyMap()
    }

    override fun setRequestProperty(name: String, value: String) {
        Assertions.checkNotNull(name)
        Assertions.checkNotNull(value)
        requestProperties[name] = value
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.remove(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
    }

    override fun getResponseCode(): Int {
        return response?.code() ?: -1
    }

    private fun makeRequest(dataSpec: DataSpec): Request {
        val url = dataSpec.uri.toString()
        val httpUrl = HttpUrl.parse(url) ?: throw IllegalArgumentException("Invalid URL: $url")

        val builder = Request.Builder().url(httpUrl)

        // Add default request properties
        defaultRequestProperties?.forEach { (name, value) ->
            builder.header(name, value)
        }

        // Add user agent
        if (userAgent != null) {
            builder.header("User-Agent", userAgent)
        }

        // Add custom request properties
        requestProperties.forEach { (name, value) ->
            builder.header(name, value)
        }

        // Add range header if needed
        val position = dataSpec.position
        val length = dataSpec.length
        if (position != 0L || length != -1L) {
            val rangeValue = buildRangeHeader(position, length)
            builder.header("Range", rangeValue)
        }

        // Disable caching
        builder.cacheControl(CacheControl.FORCE_NETWORK)

        return builder.build()
    }

    private fun buildRangeHeader(position: Long, length: Long): String {
        return if (length != -1L) {
            "bytes=$position-${position + length - 1}"
        } else {
            "bytes=$position-"
        }
    }

    private fun closeConnectionQuietly() {
        runCatching { inputStream?.close() }
        inputStream = null
        runCatching { responseBody?.close() }
        responseBody = null
        response = null
    }

    class Factory(
        private val callFactory: Call.Factory,
    ) : HttpDataSource.Factory {
        private var userAgent: String? = null
        private var defaultRequestProperties: Map<String, String>? = null
        private var transferListener: TransferListener? = null

        fun setUserAgent(userAgent: String): Factory = apply {
            this.userAgent = userAgent
        }

        override fun setDefaultRequestProperties(properties: Map<String, String>): HttpDataSource.Factory = apply {
            this.defaultRequestProperties = properties
        }

        fun setTransferListener(listener: TransferListener): Factory = apply {
            this.transferListener = listener
        }

        override fun createDataSource(): HttpDataSource {
            return OkHttp3DataSource(
                callFactory,
                userAgent,
                defaultRequestProperties,
                transferListener,
            )
        }
    }
}
