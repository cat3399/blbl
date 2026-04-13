package blbl.cat3399.core.emote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import blbl.cat3399.core.net.parseHttpUrl
import blbl.cat3399.core.net.urlHost

object EmoteBitmapLoader {
    private const val TAG = "EmoteBitmapLoader"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

    private val cache =
        object : LruCache<String, Bitmap>(maxCacheBytes()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

    private val inFlight: ConcurrentHashMap<String, Job> = ConcurrentHashMap()
    private val waiters: ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>> = ConcurrentHashMap()

    fun getCached(url: String): Bitmap? {
        val normalized = normalizeImageUrl(url) ?: return null
        if (normalized.isBlank()) return null
        synchronized(lock) {
            return cache.get(normalized)
        }
    }

    fun prefetch(url: String?) {
        val normalized = normalizeImageUrl(url) ?: return
        load(normalized) { /* no-op */ }
    }

    fun load(url: String, onResult: (Bitmap?) -> Unit) {
        val normalized = normalizeImageUrl(url)
        if (normalized == null || normalized.isBlank()) {
            onResult(null)
            return
        }

        synchronized(lock) {
            val cached = cache.get(normalized)
            if (cached != null) {
                onResult(cached)
                return
            }
        }

        // Deduplicate in-flight requests and fan-out results to all waiters.
        //
        // Avoid ConcurrentHashMap.compute(...) here: on Android 5.1, java.util.function.* is missing
        // and Kotlin's SAM adapter for BiFunction can crash with NoClassDefFoundError.
        synchronized(lock) {
            val list = waiters[normalized]
            if (list != null) {
                list.add(onResult)
            } else {
                waiters[normalized] = mutableListOf(onResult)
            }

            if (inFlight[normalized]?.isActive == true) return

            val job =
                scope.launch {
                    val bmp =
                        runCatching {
                            val bytes = withContext(Dispatchers.IO) { BiliClient.getBytes(normalized) }
                            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                        }.onFailure { t ->
                            AppLog.w(TAG, "load failed url=$normalized", t)
                        }.getOrNull()

                    if (bmp != null) {
                        synchronized(lock) {
                            cache.put(normalized, bmp)
                        }
                    }

                    val callbacks =
                        synchronized(lock) {
                            val out = waiters.remove(normalized).orEmpty().toList()
                            inFlight.remove(normalized)
                            out
                        }
                    callbacks.forEach { cb ->
                        runCatching { cb(bmp) }
                    }
                }

            inFlight[normalized] = job
        }
    }

    private fun normalizeImageUrl(url: String?): String? {
        val raw = url?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        if (raw.startsWith("//")) return "https:$raw"
        if (!raw.startsWith("http://")) return raw

        val host = raw.parseHttpUrl()?.urlHost()?.lowercase().orEmpty()
        val isBiliCdn =
            host == "hdslb.com" ||
                host.endsWith(".hdslb.com") ||
                host == "bilibili.com" ||
                host.endsWith(".bilibili.com") ||
                host == "bilivideo.com" ||
                host.endsWith(".bilivideo.com") ||
                host == "bilivideo.cn" ||
                host.endsWith(".bilivideo.cn")
        return if (isBiliCdn) raw.replaceFirst("http://", "https://") else raw
    }

    private fun maxCacheBytes(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory().toInt()
        // Keep this smaller than ImageLoader's cache; emotes are numerous.
        return (maxMemory / 32).coerceAtLeast(2 * 1024 * 1024)
    }
}
