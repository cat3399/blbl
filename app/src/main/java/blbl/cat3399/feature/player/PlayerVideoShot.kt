package blbl.cat3399.feature.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.LruCache
import blbl.cat3399.core.api.video.VideoShotInfo
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.concurrent.ConcurrentHashMap

internal data class VideoShot(
    val times: List<Int>,
    val images: List<ByteArray>,
    val imageCountX: Int,
    val imageCountY: Int,
    val fallbackAspectWidth: Int,
    val fallbackAspectHeight: Int,
) {
    companion object {
        private const val TAG = "VideoShot"

        private fun normalizeVideoShotUrl(url: String): String {
            val u = url.trim()
            return when {
                u.startsWith("//") -> "https:$u"
                u.startsWith("http://") -> "https://" + u.removePrefix("http://")
                u.startsWith("https://") -> u
                else -> "https://$u"
            }
        }

        suspend fun fromVideoShot(videoShot: VideoShotInfo): VideoShot? =
            coroutineScope {
                val ctx = coroutineContext
                val normalizedImageUrls =
                    videoShot.image
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .map(::normalizeVideoShotUrl)
                        .distinct()

                if (normalizedImageUrls.isEmpty()) {
                    AppLog.w(TAG, "missing image urls")
                    return@coroutineScope null
                }

                val images =
                    normalizedImageUrls
                        .map { imageUrl ->
                            async(Dispatchers.IO) {
                                runCatching { BiliClient.getBytes(imageUrl) }
                                    .onFailure { t -> AppLog.w(TAG, "download videoshot image failed: ${imageUrl.takeLast(32)}", t) }
                                    .getOrNull()
                                    ?.takeIf { it.isNotEmpty() }
                            }
                        }.awaitAll()

                if (images.any { it == null }) {
                    AppLog.w(TAG, "download videoshot images failed: total=${images.size}")
                    return@coroutineScope null
                }

                val times =
                    videoShot.index
                        ?.takeIf { it.size > 1 }
                        ?.drop(1)
                        ?.filter { it >= 0 }
                        ?.takeIf { it.isNotEmpty() }
                        ?: run {
                            val pvData =
                                videoShot.pvData
                                    ?.trim()
                                    ?.takeIf { it.isNotBlank() }
                                    ?: run {
                                        AppLog.w(TAG, "missing pvdata")
                                        return@coroutineScope null
                                    }
                            val pvUrl = normalizeVideoShotUrl(pvData)
                            val timeBinary =
                                runCatching { BiliClient.getBytes(pvUrl) }
                                    .onFailure { t -> AppLog.w(TAG, "download videoshot pvdata failed", t) }
                                    .getOrNull()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: run {
                                        AppLog.w(TAG, "download videoshot pvdata returned empty")
                                        return@coroutineScope null
                                    }

                            val out = mutableListOf<Int>()
                            runCatching {
                                DataInputStream(ByteArrayInputStream(timeBinary)).use { input ->
                                    while (true) {
                                        ctx.ensureActive()
                                        if (input.available() < 2) break
                                        out.add(input.readUnsignedShort())
                                    }
                                }
                            }.onFailure { t ->
                                AppLog.w(TAG, "parse videoshot pvdata failed", t)
                                return@coroutineScope null
                            }
                            out.drop(1).takeIf { it.isNotEmpty() } ?: emptyList()
                        }

                if (times.isEmpty()) {
                    AppLog.w(TAG, "missing times table")
                    return@coroutineScope null
                }

                val x = videoShot.imgXLen.takeIf { it > 0 } ?: 10
                val y = videoShot.imgYLen.takeIf { it > 0 } ?: 10
                val fallbackAspectWidth = videoShot.imgXSize.takeIf { it > 0 } ?: 160
                val fallbackAspectHeight = videoShot.imgYSize.takeIf { it > 0 } ?: 90
                return@coroutineScope VideoShot(
                    times = times,
                    images = images.filterNotNull(),
                    imageCountX = x,
                    imageCountY = y,
                    fallbackAspectWidth = fallbackAspectWidth,
                    fallbackAspectHeight = fallbackAspectHeight,
                )
            }
    }

    suspend fun getSpriteFrame(time: Int, cache: VideoShotImageCache): SpriteFrame {
        if (times.isEmpty() || images.isEmpty() || imageCountX <= 0 || imageCountY <= 0) {
            throw IllegalStateException("videoshot not ready")
        }

        val clampedTime = time.coerceAtLeast(0)
        val index = findClosestValueIndex(times, clampedTime)
        val singleImgCount = (imageCountX * imageCountY).coerceAtLeast(1)
        val imagesIndex = (index / singleImgCount).coerceIn(0, images.lastIndex)
        val imageIndex = (index % singleImgCount).coerceIn(0, singleImgCount - 1)

        val spriteSheet =
            cache.getOrDecodeImage(
                imagesIndex,
                images[imagesIndex],
            )

        val cellWidth = spriteSheet.width / imageCountX
        val cellHeight = spriteSheet.height / imageCountY
        val left = (imageIndex % imageCountX) * cellWidth
        val top = (imageIndex / imageCountX) * cellHeight

        return SpriteFrame(
            spriteSheet = spriteSheet,
            srcRect = Rect(left, top, left + cellWidth, top + cellHeight),
        )
    }

    private fun findClosestValueIndex(array: List<Int>, target: Int): Int {
        if (array.isEmpty()) return 0
        var left = 0
        var right = array.size - 1
        while (left < right) {
            val mid = left + (right - left) / 2
            if (array[mid] < target) {
                left = mid + 1
            } else {
                right = mid
            }
        }
        return left
    }
}

internal class VideoShotImageCache {
    private val cacheLock = Any()
    private val memoryCache = LruCache<Int, Bitmap>(3)
    private val activeTasks = ConcurrentHashMap<Int, Deferred<Bitmap>>()

    companion object {
        private const val TAG = "VideoShotCache"
        private val bitmapOptions =
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inScaled = false
            }

        private val placeholderBitmap: Bitmap by lazy {
            Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565).apply {
                eraseColor(0x00000000)
            }
        }
    }

    suspend fun getOrDecodeImage(imagesIndex: Int, imageData: ByteArray): Bitmap =
        coroutineScope {
            synchronized(cacheLock) {
                memoryCache.get(imagesIndex)
            }?.let { return@coroutineScope it }

            if (imageData.isEmpty()) return@coroutineScope placeholderBitmap

            val task =
                activeTasks.getOrPut(imagesIndex) {
                    async(Dispatchers.IO) {
                        val decoded =
                            BitmapFactory.decodeByteArray(imageData, 0, imageData.size, bitmapOptions)
                                ?: run {
                                    AppLog.w(TAG, "decode videoshot bitmap failed: index=$imagesIndex size=${imageData.size}")
                                    placeholderBitmap
                                }
                        synchronized(cacheLock) {
                            memoryCache.put(imagesIndex, decoded)
                        }
                        decoded
                    }
                }
            try {
                return@coroutineScope task.await()
            } finally {
                activeTasks.remove(imagesIndex)
            }
        }

    fun clear() {
        synchronized(cacheLock) {
            memoryCache.evictAll()
        }
        activeTasks.clear()
    }
}

internal data class SpriteFrame(
    val spriteSheet: Bitmap,
    val srcRect: Rect,
)
