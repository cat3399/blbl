package blbl.cat3399.core.net

import android.content.Context
import android.os.Build
import blbl.cat3399.core.account.AccountSessionStore
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object BiliClient {
    private const val TAG = "BiliClient"
    private const val BASE = "https://api.bilibili.com"
    private const val HDR_SKIP_ORIGIN = "X-Blbl-Skip-Origin"
    private const val LOG_HTTP_REQUESTS = false
    internal const val APP_CDN_USER_AGENT = "Bilibili Freedoooooom/MarkII"

    private val NO_COOKIES = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<okhttp3.Cookie>) {}
        override fun loadForRequest(url: HttpUrl): List<okhttp3.Cookie> = emptyList()
    }

    lateinit var prefs: AppPrefs
        private set
    lateinit var accounts: AccountSessionStore
        private set
    lateinit var cookies: CookieStore
        private set
    lateinit var apiOkHttp: OkHttpClient
        private set

    private lateinit var apiOkHttpNoCookies: OkHttpClient

    lateinit var cdnOkHttp: OkHttpClient
        private set

    lateinit var appCdnOkHttp: OkHttpClient
        private set

    data class StringResponse(
        val code: Int,
        val message: String,
        val body: String,
    ) {
        val isSuccessful: Boolean
            get() = code in 200..299
    }

    @Volatile
    private var wbiKeys: WbiSigner.Keys? = null

    fun init(context: Context) {
        prefs = AppPrefs(context.applicationContext)
        accounts = AccountSessionStore(context.applicationContext)
        cookies = CookieStore(context.applicationContext)
        val dns = ipv4OnlyDns { prefs.ipv4OnlyEnabled }
        val baseBuilder = OkHttpClient.Builder()
            .cookieJar(cookies)
            .dns(dns)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
        if (Build.VERSION.SDK_INT < 21) {
            installConscryptTls(baseBuilder)
        }
        val baseClient = baseBuilder.build()

        apiOkHttp = baseClient.newBuilder()
            .addInterceptor { chain ->
                val ua = prefs.userAgent
                val original = chain.request()
                val builder = original.newBuilder()
                if (original.header("User-Agent").isNullOrBlank()) builder.header("User-Agent", ua)
                if (original.header("Referer").isNullOrBlank()) builder.header("Referer", "https://www.bilibili.com/")
                val skipOrigin = original.header(HDR_SKIP_ORIGIN) == "1"
                if (skipOrigin) builder.removeHeader(HDR_SKIP_ORIGIN)
                if (!skipOrigin && original.header("Origin").isNullOrBlank()) builder.header("Origin", "https://www.bilibili.com")
                val req = builder.build()
                val start = System.nanoTime()
                val res = chain.proceed(req)
                val costMs = (System.nanoTime() - start) / 1_000_000
                if (LOG_HTTP_REQUESTS) {
                    AppLog.d(TAG, "${req.requestMethod()} ${req.requestUrl().urlHost()}${req.requestUrl().urlEncodedPath()} -> ${res.statusCode()} (${costMs}ms)")
                }
                res
            }
            .build()

        apiOkHttpNoCookies = apiOkHttp.newBuilder().cookieJar(NO_COOKIES).build()

        cdnOkHttp = baseClient.newBuilder()
            .addInterceptor { chain ->
                val ua = prefs.userAgent
                val original = chain.request()
                val builder = original.newBuilder()
                if (original.header("User-Agent").isNullOrBlank()) builder.header("User-Agent", ua)
                if (original.header("Referer").isNullOrBlank()) builder.header("Referer", "https://www.bilibili.com/")
                // CDN/媒体请求通常不需要 Origin；某些 CDN 反而会因 Origin 触发 403。
                val req = builder.build()
                val start = System.nanoTime()
                val res = chain.proceed(req)
                val costMs = (System.nanoTime() - start) / 1_000_000
                if (LOG_HTTP_REQUESTS) {
                    AppLog.d(TAG, "CDN ${req.requestMethod()} ${req.requestUrl().urlHost()}${req.requestUrl().urlEncodedPath()} -> ${res.statusCode()} (${costMs}ms)")
                }
                res
            }
            .build()

        appCdnOkHttp = baseClient.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor { chain ->
                val original = chain.request()
                val defaultPort = if (original.url.isHttps) 443 else 80
                val hostHeader =
                    if (original.url.port == defaultPort) {
                        original.url.host
                    } else {
                        "${original.url.host}:${original.url.port}"
                    }
                val req =
                    original
                        .newBuilder()
                        .header("User-Agent", APP_CDN_USER_AGENT)
                        .header("Accept-Encoding", "identity")
                        .header("Host", hostHeader)
                        .header("Connection", "Keep-Alive")
                        .build()
                val start = System.nanoTime()
                val res = chain.proceed(req)
                val costMs = (System.nanoTime() - start) / 1_000_000
                if (LOG_HTTP_REQUESTS) {
                    AppLog.d(TAG, "APP CDN ${req.method} ${req.url.host}${req.url.encodedPath} -> ${res.code} (${costMs}ms)")
                }
                res
            }
            .build()

        AppLog.i(TAG, "init ua=${prefs.userAgent.take(48)} cookiesSess=${cookies.hasSessData()}")
    }

    fun clearLoginSession() {
        accounts.clearAllAccountsAndCurrentSession(appPrefs = prefs, cookies = cookies)
    }

    private fun clientFor(url: String, noCookies: Boolean = false): OkHttpClient {
        val host = runCatching { url.parseHttpUrl()?.urlHost() }.getOrNull().orEmpty()
        val isCdn = host.endsWith("hdslb.com") ||
            host.contains("bilivideo.com") ||
            host.contains("bilivideo.cn") ||
            host.contains("mcdn.bilivideo")
        return when {
            isCdn -> cdnOkHttp
            noCookies -> apiOkHttpNoCookies
            else -> apiOkHttp
        }
    }

    suspend fun requestString(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        noCookies: Boolean = false,
    ): String {
        val res = requestStringResponse(url = url, method = method, headers = headers, body = body, noCookies = noCookies)
        if (!res.isSuccessful) throw IOException("HTTP ${res.code} ${res.message} body=${res.body.take(200)}")
        return res.body
    }

    suspend fun requestStringResponse(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        noCookies: Boolean = false,
    ): StringResponse {
        val reqBuilder = Request.Builder().url(url)
        for ((k, v) in headers) reqBuilder.header(k, v)
        when (method.uppercase()) {
            "GET" -> reqBuilder.get()
            "POST" -> reqBuilder.post(body ?: RequestBody.create(null, ByteArray(0)))
            else -> error("Unsupported method: $method")
        }
        val res = clientFor(url, noCookies = noCookies).newCall(reqBuilder.build()).await()
        res.use { r ->
            val raw = withContext(Dispatchers.IO) { r.bodyOrNull()?.string() ?: "" }
            return StringResponse(code = r.statusCode(), message = r.statusMessage(), body = raw)
        }
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap(), noCookies: Boolean = false): JSONObject {
        val body = requestString(url, method = "GET", headers = headers, noCookies = noCookies)
        return withContext(Dispatchers.Default) { JSONObject(body) }
    }

    suspend fun postFormJson(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        noCookies: Boolean = false,
    ): JSONObject {
        val builder = FormBody.Builder()
        for ((k, v) in form) builder.add(k, v)
        val body = requestString(url, method = "POST", headers = headers, body = builder.build(), noCookies = noCookies)
        return withContext(Dispatchers.Default) { JSONObject(body) }
    }

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap(), noCookies: Boolean = false): ByteArray {
        val reqBuilder = Request.Builder().url(url)
        for ((k, v) in headers) reqBuilder.header(k, v)
        val res = clientFor(url, noCookies = noCookies).newCall(reqBuilder.build()).await()
        res.use { r ->
            val bytes = withContext(Dispatchers.IO) { r.bodyOrNull()?.bytes() ?: ByteArray(0) }
            if (!r.isSuccessful) {
                if (bytes.isNotEmpty()) return bytes
                throw IOException("HTTP ${r.statusCode()} ${r.statusMessage()}")
            }
            return bytes
        }
    }

    suspend fun ensureWbiKeys(): WbiSigner.Keys {
        val cached = wbiKeys
        val nowSec = System.currentTimeMillis() / 1000
        if (cached != null && nowSec - cached.fetchedAtEpochSec < 12 * 60 * 60) return cached

        val url = "$BASE/x/web-interface/nav"
        val json = getJson(url)
        val data = json.optJSONObject("data") ?: JSONObject()
        val wbi = data.optJSONObject("wbi_img") ?: JSONObject()
        val imgUrl = wbi.optString("img_url", "")
        val subUrl = wbi.optString("sub_url", "")
        val imgKey = imgUrl.substringAfterLast('/').substringBefore('.')
        val subKey = subUrl.substringAfterLast('/').substringBefore('.')
        val keys = WbiSigner.Keys(imgKey = imgKey, subKey = subKey, fetchedAtEpochSec = nowSec)
        wbiKeys = keys
        AppLog.i(TAG, "ensureWbiKeys imgKey=${imgKey.take(6)} subKey=${subKey.take(6)} isLogin=${data.optBoolean("isLogin")}")
        return keys
    }

    fun withQuery(url: String, params: Map<String, String>): String {
        val httpUrl = url.parseHttpUrl()?.newBuilder() ?: return url
        for ((k, v) in params) httpUrl.addQueryParameter(k, v)
        return httpUrl.build().toString()
    }
    fun signedWbiUrl(path: String, params: Map<String, String>, keys: WbiSigner.Keys, nowEpochSec: Long = System.currentTimeMillis() / 1000): String {
        val base = "$BASE$path"
        val signed = WbiSigner.signQuery(params, keys, nowEpochSec)
        return withQuery(base, signed)
    }

    fun signedWbiUrlAbsolute(url: String, params: Map<String, String>, keys: WbiSigner.Keys, nowEpochSec: Long = System.currentTimeMillis() / 1000): String {
        val signed = WbiSigner.signQuery(params, keys, nowEpochSec)
        return withQuery(url, signed)
    }

    // API 19–20 的系统 SSL 不支持 TLS 1.2/1.3，借助 Conscrypt 补齐。
    // BlblApp.onCreate() 已将 Conscrypt 注册为全局 Security provider，此处直接使用默认 provider。
    private fun installConscryptTls(builder: OkHttpClient.Builder) {
        try {
            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(null, null, null)
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val trustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            AppLog.i(TAG, "Conscrypt TLS socket factory installed for API ${Build.VERSION.SDK_INT}")
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to install Conscrypt TLS socket factory", e)
        }
    }
}
