package blbl.cat3399.core.net

import android.content.Context
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object BiliClient {
    private const val TAG = "BiliClient"
    private const val BASE = "https://api.bilibili.com"
    private const val HDR_SKIP_ORIGIN = "X-Blbl-Skip-Origin"

    lateinit var prefs: AppPrefs
        private set
    lateinit var cookies: CookieStore
        private set
    lateinit var apiOkHttp: OkHttpClient
        private set

    private lateinit var apiOkHttpNoCookies: OkHttpClient

    lateinit var cdnOkHttp: OkHttpClient
        private set

    @Volatile
    private var wbiKeys: WbiSigner.Keys? = null

    fun init(context: Context) {
        prefs = AppPrefs(context.applicationContext)
        cookies = CookieStore(context.applicationContext)
        val baseClient = OkHttpClient.Builder()
            .cookieJar(cookies)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

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
                AppLog.d(TAG, "${req.method} ${req.url.host}${req.url.encodedPath} -> ${res.code} (${costMs}ms)")
                res
            }
            .build()

        apiOkHttpNoCookies = apiOkHttp.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()

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
                AppLog.d(TAG, "CDN ${req.method} ${req.url.host}${req.url.encodedPath} -> ${res.code} (${costMs}ms)")
                res
            }
            .build()

        AppLog.i(TAG, "init ua=${prefs.userAgent.take(48)} cookiesSess=${cookies.hasSessData()}")
    }

    private fun clientFor(url: String, noCookies: Boolean = false): OkHttpClient {
        val host = runCatching { url.toHttpUrl().host }.getOrNull().orEmpty()
        return if (
            host.endsWith("hdslb.com") ||
            host.contains("bilivideo.com") ||
            host.contains("bilivideo.cn") ||
            host.contains("mcdn.bilivideo")
        ) cdnOkHttp else apiOkHttp
            .let { if (noCookies) apiOkHttpNoCookies else apiOkHttp }
    }

    suspend fun requestString(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        noCookies: Boolean = false,
    ): String {
        val reqBuilder = Request.Builder().url(url)
        for ((k, v) in headers) reqBuilder.header(k, v)
        when (method.uppercase()) {
            "GET" -> reqBuilder.get()
            "POST" -> reqBuilder.post(body ?: ByteArray(0).toRequestBody(null))
            else -> error("Unsupported method: $method")
        }
        val res = clientFor(url, noCookies = noCookies).newCall(reqBuilder.build()).await()
        res.use { r ->
            val raw = withContext(Dispatchers.IO) { r.body?.string() ?: "" }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} ${r.message} body=${raw.take(200)}")
            return raw
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
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} ${r.message}")
            return withContext(Dispatchers.IO) { r.body?.bytes() ?: ByteArray(0) }
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
        val httpUrl = url.toHttpUrl().newBuilder()
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
}
