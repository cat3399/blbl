package blbl.cat3399.feature.player.engine

import blbl.cat3399.BuildConfig
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.bodyOrNull
import blbl.cat3399.core.net.statusCode
import blbl.cat3399.core.net.statusMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A tiny local HTTP reverse proxy for DASH range requests.
 *
 * Why:
 * - Some Ijk/FFmpeg DASH sub-requests may miss required headers (e.g. Referer/UA) and get 403.
 * - Exo uses OkHttp with app-configured headers; we reuse the same OkHttp client here.
 *
 * Design:
 * - Ijk requests `http://127.0.0.1:<port>/{v|a}/{key}.m4s` with Range.
 * - We forward to the real CDN URL via OkHttp and stream the response back.
 */
internal class DashLocalHttpProxy(
    private val okHttpClient: OkHttpClient,
) : Closeable {
    private val upstreamByKey: ConcurrentHashMap<String, String> = ConcurrentHashMap()
    private val debugLogCount: AtomicInteger = AtomicInteger(0)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var executor: ExecutorService? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    fun register(kind: String, upstreamUrl: String): String {
        ensureStarted()
        val k = kind.trim().lowercase(Locale.US).ifBlank { "v" }
        val url = upstreamUrl.trim()
        val key = md5Hex("$k|$url")
        upstreamByKey[key] = url
        return "http://127.0.0.1:${port}/${k}/${key}.m4s"
    }

    fun resetRegistrations() {
        upstreamByKey.clear()
    }

    private fun ensureStarted() {
        if (serverSocket != null) return
        synchronized(this) {
            if (serverSocket != null) return
            val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            executor =
                Executors.newFixedThreadPool(4) { r ->
                    Thread(r, "blbl-ijk-dash-proxy-worker").apply { isDaemon = true }
                }
            acceptThread =
                thread(name = "blbl-ijk-dash-proxy-accept", isDaemon = true) {
                    AppLog.i("DashProxy", "started port=${socket.localPort}")
                    while (!socket.isClosed) {
                        val client =
                            runCatching { socket.accept() }
                                .getOrNull()
                                ?: break
                        executor?.execute {
                            runCatching { handleClient(client) }
                                .onFailure { t ->
                                    AppLog.w("DashProxy", "handle client failed", t)
                                    runCatching { client.close() }
                                }
                        } ?: runCatching { client.close() }
                    }
                    AppLog.i("DashProxy", "accept loop ended")
                }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 15_000
        socket.use { s ->
            val input = s.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
            val requestLine = input.readLine()?.trim().orEmpty()
            if (requestLine.isBlank()) return
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 2) return
            val method = parts[0].trim().uppercase(Locale.US)
            val target = parts[1].trim()

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim().lowercase(Locale.US)
                val value = line.substring(idx + 1).trim()
                if (name.isNotBlank()) headers[name] = value
            }

            if (method != "GET" && method != "HEAD") {
                respondPlain(s, code = 405, message = "Method Not Allowed", body = "method=$method")
                return
            }

            val path = target.substringBefore('?')
            val segs = path.trim().split('/').filter { it.isNotBlank() }
            if (segs.size < 2) {
                respondPlain(s, code = 404, message = "Not Found", body = "path=$path")
                return
            }
            val key = segs[1].substringBefore('.').trim()
            val upstreamUrl = upstreamByKey[key]
            if (upstreamUrl.isNullOrBlank()) {
                respondPlain(s, code = 404, message = "Not Found", body = "missing key=$key")
                return
            }

            val range = headers["range"]?.trim()?.takeIf { it.isNotBlank() }
            val debugIndex =
                if (BuildConfig.DEBUG && range != null) {
                    val n = debugLogCount.getAndIncrement()
                    if (n < 12) n else null
                } else {
                    null
                }
            debugIndex?.let {
                AppLog.i("DashProxy", "req method=$method key=$key range=$range")
            }
            runCatching { executeUpstream(upstreamUrl, range = range, method = method) }
                .onFailure { t ->
                    AppLog.w("DashProxy", "upstream request failed", t)
                    respondPlain(s, code = 502, message = "Bad Gateway", body = "upstream failed")
                }
                .onSuccess { upstreamRes ->
                    upstreamRes.use { res ->
                        if (BuildConfig.DEBUG && range != null && res.statusCode() != 206) {
                            AppLog.w("DashProxy", "range request got http=${res.statusCode()} key=$key range=$range")
                        }
                        val out = BufferedOutputStream(s.getOutputStream())
                        writeStatusLine(out, res)
                        writeHeader(out, "Connection", "close")

                        val contentType = res.header("Content-Type")?.trim()?.takeIf { it.isNotBlank() }
                        val acceptRanges = res.header("Accept-Ranges")?.trim()?.takeIf { it.isNotBlank() }
                        val contentRange = res.header("Content-Range")?.trim()?.takeIf { it.isNotBlank() }
                        val upstreamTransferEncoding = res.header("Transfer-Encoding")?.trim()?.takeIf { it.isNotBlank() }
                        val upstreamContentLength =
                            res.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0L }

                        contentType?.let { writeHeader(out, "Content-Type", it) }
                        acceptRanges?.let { writeHeader(out, "Accept-Ranges", it) }
                        contentRange?.let { writeHeader(out, "Content-Range", it) }

                        val body = res.bodyOrNull()
                        val bodyLen = body?.contentLength() ?: -1L
                        val resolvedLen =
                            upstreamContentLength
                                ?: contentRange?.let { parseContentRangeLength(it) }
                                ?: bodyLen.takeIf { it >= 0L }

                        debugIndex?.let {
                            val crShort = contentRange?.take(96)?.replace("\r", "")?.replace("\n", "") ?: "null"
                            val teShort = upstreamTransferEncoding ?: "null"
                            AppLog.i(
                                "DashProxy",
                                "res http=${res.statusCode()} key=$key range=$range len=${resolvedLen ?: -1L} te=$teShort cr=$crShort",
                            )
                        }
                        if (method == "HEAD") {
                            if (resolvedLen != null) writeHeader(out, "Content-Length", resolvedLen.toString())
                            finishHeaders(out)
                            out.flush()
                            return
                        }

                        if (body == null) {
                            finishHeaders(out)
                            out.flush()
                            return
                        }

                        // For DASH seeking, the client expects a bounded 206 response. If upstream is chunked,
                        // OkHttp reports contentLength=-1; in that case we must derive Content-Length from
                        // Content-Range (end-start+1) and avoid Transfer-Encoding: chunked.
                        if (resolvedLen != null) {
                            writeHeader(out, "Content-Length", resolvedLen.toString())
                            finishHeaders(out)
                            try {
                                body.byteStream().use { copyToExactly(it, out, resolvedLen) }
                                out.flush()
                            } catch (e: java.net.SocketException) {
                                // Client may abort early (e.g. dashdec probing). This is normal.
                                if (BuildConfig.DEBUG) {
                                    AppLog.d("DashProxy", "client closed connection key=$key range=$range")
                                }
                            } catch (e: java.io.IOException) {
                                AppLog.w("DashProxy", "stream copy failed key=$key range=$range", e)
                            }
                            return
                        }

                        if (range != null || res.statusCode() == 206) {
                            // Range response without a reliable length is not seekable for some ijk/ffmpeg paths.
                            AppLog.w(
                                "DashProxy",
                                "range response missing Content-Length/Content-Range; http=${res.statusCode()} key=$key range=$range",
                            )
                            finishHeaders(out)
                            out.flush()
                            return
                        }

                        // Fallback for non-range responses.
                        val upstreamLenHeaderRaw = res.header("Content-Length")?.trim().orEmpty()
                        if (upstreamLenHeaderRaw.isNotBlank()) {
                            writeHeader(out, "Content-Length", upstreamLenHeaderRaw)
                            finishHeaders(out)
                            body.byteStream().use { it.copyTo(out) }
                            out.flush()
                            return
                        }

                        // Last resort: chunked (should not happen for DASH range requests).
                        writeHeader(out, "Transfer-Encoding", "chunked")
                        finishHeaders(out)
                        body.byteStream().use { streamChunked(it, out) }
                        out.flush()
                    }
                }
        }
    }

    private fun executeUpstream(upstreamUrl: String, range: String?, method: String): Response {
        val builder =
            Request.Builder()
                .url(upstreamUrl)
                // Keep the payload raw (avoid implicit gzip decompression breaking Content-Length).
                .header("Accept-Encoding", "identity")
        if (!range.isNullOrBlank()) builder.header("Range", range)
        when (method) {
            "HEAD" -> builder.head()
            else -> builder.get()
        }
        return okHttpClient.newCall(builder.build()).execute()
    }

    private fun respondPlain(socket: Socket, code: Int, message: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write("HTTP/1.1 $code $message\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("Connection: close\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("Content-Type: text/plain; charset=utf-8\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("Content-Length: ${bytes.size}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun writeStatusLine(out: BufferedOutputStream, res: Response) {
        val msg = res.statusMessage().takeIf { it.isNotBlank() } ?: "OK"
        out.write("HTTP/1.1 ${res.statusCode()} $msg\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun writeHeader(out: BufferedOutputStream, name: String, value: String) {
        out.write("$name: $value\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun finishHeaders(out: BufferedOutputStream) {
        out.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun streamChunked(input: java.io.InputStream, out: BufferedOutputStream) {
        val buf = ByteArray(DEFAULT_BUF_SIZE)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(Integer.toHexString(n).toByteArray(StandardCharsets.ISO_8859_1))
            out.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.write(buf, 0, n)
            out.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        out.write("0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    }

    override fun close() {
        stop()
    }

    fun stop() {
        upstreamByKey.clear()
        val socket = serverSocket ?: return
        serverSocket = null
        runCatching { socket.close() }

        val exec = executor
        executor = null
        exec?.shutdown()
        runCatching { exec?.awaitTermination(800, TimeUnit.MILLISECONDS) }
        runCatching { exec?.shutdownNow() }

        acceptThread = null
        AppLog.i("DashProxy", "stopped")
    }

    private fun md5Hex(text: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest(text.toByteArray(StandardCharsets.UTF_8))
        return buildString(md5.size * 2) {
            md5.forEach { b -> append(String.format(Locale.US, "%02x", b)) }
        }
    }

    private fun parseContentRangeLength(contentRange: String): Long? {
        // Examples:
        // - "bytes 0-1023/5000"
        // - "bytes 27399468-28500000/90000000"
        // - "bytes */90000000" (416)
        val v = contentRange.trim()
        if (!v.startsWith("bytes", ignoreCase = true)) return null
        val rest = v.substringAfter(' ', "").trim()
        if (rest.isBlank()) return null
        val rangePart = rest.substringBefore('/').trim()
        if (rangePart == "*" || rangePart.isBlank()) return null
        val startStr = rangePart.substringBefore('-').trim()
        val endStr = rangePart.substringAfter('-', "").trim()
        val start = startStr.toLongOrNull() ?: return null
        val end = endStr.toLongOrNull() ?: return null
        if (start < 0L || end < start) return null
        return (end - start + 1L).takeIf { it >= 0L }
    }

    private fun copyToExactly(input: InputStream, out: BufferedOutputStream, bytes: Long) {
        var remaining = bytes.coerceAtLeast(0L)
        val buf = ByteArray(DEFAULT_BUF_SIZE)
        while (remaining > 0L) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, toRead)
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n.toLong()
        }
    }

    private companion object {
        private const val DEFAULT_BUF_SIZE: Int = 32 * 1024
    }
}
