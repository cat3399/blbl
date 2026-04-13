package blbl.cat3399.core.net

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.Sink
import okio.buffer

// OkHttp 4.x (Kotlin-style API) compat shim for lollipopplus flavor

fun Response.statusCode(): Int = code
fun Response.statusMessage(): String = message
fun Response.bodyOrNull(): ResponseBody? = body

fun Request.requestMethod(): String = method
fun Request.requestUrl(): HttpUrl = url

fun HttpUrl.urlHost(): String = host
fun HttpUrl.urlEncodedPath(): String = encodedPath
fun HttpUrl.urlQuery(): String? = query
fun HttpUrl.urlFragment(): String? = fragment

fun String.parseHttpUrl(): HttpUrl? = toHttpUrlOrNull()
fun String.toMediaTypeCompat(): MediaType = toMediaType()

fun OkHttpClient.evictConnectionPool() = connectionPool.evictAll()

fun Cookie.cookieName(): String = name
fun Cookie.cookieValue(): String = value
fun Cookie.cookieDomain(): String = domain
fun Cookie.cookiePath(): String = path
fun Cookie.cookieExpiresAt(): Long = expiresAt
fun Cookie.cookieSecure(): Boolean = secure
fun Cookie.cookieHttpOnly(): Boolean = httpOnly
fun Cookie.cookieHostOnly(): Boolean = hostOnly
fun Cookie.cookiePersistent(): Boolean = persistent

fun Sink.bufferCompat(): BufferedSink = buffer()
