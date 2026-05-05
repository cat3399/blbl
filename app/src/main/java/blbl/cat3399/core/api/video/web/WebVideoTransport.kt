package blbl.cat3399.core.api.video.web

import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.PiliWebHeaders
import blbl.cat3399.core.net.WebCookieMaintainer
import blbl.cat3399.core.net.WbiSigner
import org.json.JSONObject

internal interface WebVideoApiTransport {
    suspend fun ensureWbiKeys(): WbiSigner.Keys

    suspend fun ensureUgcPlayCookieMaintenance()

    suspend fun ensurePgcPlayCookieMaintenance()

    fun signedWbiUrl(
        path: String,
        params: Map<String, String>,
        keys: WbiSigner.Keys,
    ): String

    fun withQuery(
        url: String,
        params: Map<String, String>,
    ): String

    suspend fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        noCookies: Boolean = false,
    ): JSONObject

    fun webHeaders(
        targetUrl: String,
        includeCookie: Boolean,
    ): Map<String, String>

    fun cookieValue(name: String): String?

    fun hasSessData(): Boolean
}

internal object BiliClientWebVideoApiTransport : WebVideoApiTransport {
    override suspend fun ensureWbiKeys(): WbiSigner.Keys = BiliClient.ensureWbiKeys()

    override suspend fun ensureUgcPlayCookieMaintenance() {
        WebCookieMaintainer.ensureHealthyForPlay()
    }

    override suspend fun ensurePgcPlayCookieMaintenance() {
        WebCookieMaintainer.ensureWebFingerprintCookies()
        WebCookieMaintainer.ensureDailyMaintenance()
    }

    override fun signedWbiUrl(
        path: String,
        params: Map<String, String>,
        keys: WbiSigner.Keys,
    ): String = BiliClient.signedWbiUrl(path = path, params = params, keys = keys)

    override fun withQuery(
        url: String,
        params: Map<String, String>,
    ): String = BiliClient.withQuery(url, params)

    override suspend fun getJson(
        url: String,
        headers: Map<String, String>,
        noCookies: Boolean,
    ): JSONObject = BiliClient.getJson(url = url, headers = headers, noCookies = noCookies)

    override fun webHeaders(
        targetUrl: String,
        includeCookie: Boolean,
    ): Map<String, String> = PiliWebHeaders.forUrl(targetUrl = targetUrl, includeCookie = includeCookie)

    override fun cookieValue(name: String): String? = BiliClient.cookies.getCookieValue(name)

    override fun hasSessData(): Boolean = BiliClient.cookies.hasSessData()
}
