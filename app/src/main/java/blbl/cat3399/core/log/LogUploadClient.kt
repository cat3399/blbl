package blbl.cat3399.core.log

import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.await
import blbl.cat3399.core.net.bufferCompat
import blbl.cat3399.core.net.ipv4OnlyDns
import blbl.cat3399.core.net.statusCode
import blbl.cat3399.core.net.statusMessage
import blbl.cat3399.core.net.bodyOrNull
import blbl.cat3399.core.net.toMediaTypeCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object LogUploadClient {
    private const val TAG = "LogUploadClient"

    private const val UPLOAD_URL = "https://upload.cat3399.top/logs"
    private const val AUTH_TOKEN = "cat3399-blbl"

    // Keep aligned with Worker-side limits.
    private const val MAX_UPLOAD_BYTES: Long = 20L * 1024 * 1024

    data class UploadResult(
        val key: String,
        val id: String?,
        val sizeBytes: Long?,
    )

    private val okHttpLazy: Lazy<OkHttpClient> =
        lazy {
            OkHttpClient.Builder()
                .dns(ipv4OnlyDns { BiliClient.prefs.ipv4OnlyEnabled })
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

    private val okHttp: OkHttpClient
        get() = okHttpLazy.value

    suspend fun uploadZip(
        file: File,
        fileName: String = file.name,
        onProgress: ((sentBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): UploadResult {
        val f = file.takeIf { it.exists() && it.isFile } ?: throw IOException("日志文件不存在")
        val len = runCatching { f.length() }.getOrDefault(0L).coerceAtLeast(0L)
        if (len <= 0L) throw IOException("日志文件为空")
        if (len > MAX_UPLOAD_BYTES) throw IOException("日志文件过大：${len}B（上限 ${MAX_UPLOAD_BYTES}B）")

        val safeName = sanitizeFileName(fileName).ifBlank { f.name }
        val rawBody = RequestBody.create("application/zip".toMediaTypeCompat(), f)
        val body =
            if (onProgress == null) {
                rawBody
            } else {
                CountingRequestBody(
                    delegate = rawBody,
                    totalBytes = len,
                    onProgress = onProgress,
                )
            }
        val req =
            Request.Builder()
                .url(UPLOAD_URL)
                .post(body)
                .header("Authorization", "Bearer $AUTH_TOKEN")
                .header("Content-Type", "application/zip")
                .header("Content-Disposition", "attachment; filename=\"$safeName\"")
                .build()

        val res = okHttp.newCall(req).await()
        res.use { r ->
            val raw = withContext(Dispatchers.IO) { r.bodyOrNull()?.string().orEmpty() }
            val jsonMessage =
                runCatching { JSONObject(raw).optString("message", "").trim() }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
            if (!r.isSuccessful) {
                AppLog.w(TAG, "upload failed http=${r.statusCode()} msg=${r.statusMessage()} body=${raw.take(200)}")
                val hint = jsonMessage?.let { "（$it）" }.orEmpty()
                throw IOException("HTTP ${r.statusCode()} ${r.statusMessage()}$hint")
            }

            val json = runCatching { JSONObject(raw) }.getOrNull() ?: throw IOException("响应解析失败")
            val ok = json.optBoolean("ok", false)
            if (!ok) {
                val msg = jsonMessage ?: "上传失败"
                throw IOException(msg)
            }
            val key = json.optString("key", "").trim()
            if (key.isBlank()) throw IOException("响应缺少key")
            val id = json.optString("id", "").trim().takeIf { it.isNotBlank() }
            val sizeBytes = json.optLong("size", -1L).takeIf { it >= 0L }
            return UploadResult(key = key, id = id, sizeBytes = sizeBytes)
        }
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return ""
        return trimmed.replace(Regex("[\\\\/\\r\\n\\t\\u0000]"), "_").take(96)
    }

    private class CountingRequestBody(
        private val delegate: RequestBody,
        private val totalBytes: Long,
        private val onProgress: (sentBytes: Long, totalBytes: Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = delegate.contentType()

        override fun contentLength(): Long = totalBytes.takeIf { it > 0L } ?: delegate.contentLength()

        override fun writeTo(sink: BufferedSink) {
            onProgress(0L, totalBytes)

            var sent = 0L
            val forwardingSink =
                object : ForwardingSink(sink) {
                    override fun write(source: Buffer, byteCount: Long) {
                        super.write(source, byteCount)
                        sent += byteCount
                        onProgress(sent.coerceAtMost(totalBytes), totalBytes)
                    }
                }
            val buffered = forwardingSink.bufferCompat()
            delegate.writeTo(buffered)
            buffered.flush()
        }
    }
}
