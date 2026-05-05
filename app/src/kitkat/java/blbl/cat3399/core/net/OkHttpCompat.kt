package blbl.cat3399.core.net

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.buffer
import okio.Sink

// OkHttp 3.x (Java-style API) compat shim for kitkat flavor

fun Response.statusCode(): Int = code()
fun Response.statusMessage(): String = message()
fun Response.bodyOrNull(): ResponseBody? = body()

fun Request.requestMethod(): String = method()
fun Request.requestUrl(): HttpUrl = url()

fun HttpUrl.urlHost(): String = host()
fun HttpUrl.urlEncodedPath(): String = encodedPath()
fun HttpUrl.urlQuery(): String? = query()
fun HttpUrl.urlFragment(): String? = fragment()

fun String.parseHttpUrl(): HttpUrl? = HttpUrl.parse(this)
fun String.toMediaTypeCompat(): MediaType = MediaType.parse(this)!!
fun String.toRequestBodyCompat(contentType: MediaType): RequestBody = RequestBody.create(contentType, this)

fun OkHttpClient.evictConnectionPool() = connectionPool().evictAll()

fun Cookie.cookieName(): String = name()
fun Cookie.cookieValue(): String = value()
fun Cookie.cookieDomain(): String = domain()
fun Cookie.cookiePath(): String = path()
fun Cookie.cookieExpiresAt(): Long = expiresAt()
fun Cookie.cookieSecure(): Boolean = secure()
fun Cookie.cookieHttpOnly(): Boolean = httpOnly()
fun Cookie.cookieHostOnly(): Boolean = hostOnly()
fun Cookie.cookiePersistent(): Boolean = persistent()

fun Sink.bufferCompat(): BufferedSink = buffer()

// OkHttp 3.x compat: Request.url and Response.code are methods, not properties
val Request.url get() = url()
val Request.method get() = method()
val Response.code get() = code()
val Response.message get() = message()

// OkHttp 3.x compat: HttpUrl properties are methods
val HttpUrl.host get() = host()
val HttpUrl.port get() = port()
val HttpUrl.encodedPath get() = encodedPath()


