package blbl.cat3399.feature.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
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

        val frameBitmap =
            cache.getOrDecodeFrame(
                imagesIndex,
                images[imagesIndex],
                imageIndex,
                imageCountX,
                imageCountY,
            )

        return SpriteFrame(
            bitmap = frameBitmap,
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
    private val memoryCache = LruCache<Long, Bitmap>(48)
    private val activeTasks = ConcurrentHashMap<Long, Deferred<Bitmap>>()

    companion object {
        private const val TAG = "VideoShotCache"

        private fun bitmapOptions(): BitmapFactory.Options =
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inScaled = false
            }

        private fun frameCacheKey(imagesIndex: Int, imageIndex: Int): Long =
            (imagesIndex.toLong() shl 32) xor (imageIndex.toLong() and 0xffffffffL)
    }

    suspend fun getOrDecodeFrame(
        imagesIndex: Int,
        imageData: ByteArray,
        imageIndex: Int,
        imageCountX: Int,
        imageCountY: Int,
    ): Bitmap =
        coroutineScope {
            if (imageData.isEmpty()) throw IllegalStateException("empty videoshot image")
            val key = frameCacheKey(imagesIndex, imageIndex)
            synchronized(cacheLock) {
                memoryCache.get(key)
            }?.let { return@coroutineScope it }

            val task =
                activeTasks.getOrPut(key) {
                    async(Dispatchers.IO) {
                        val decoded = decodeFrameBitmap(imageData, imageIndex, imageCountX, imageCountY)
                        synchronized(cacheLock) {
                            memoryCache.put(key, decoded)
                        }
                        decoded
                    }
                }
            try {
                return@coroutineScope task.await()
            } finally {
                activeTasks.remove(key, task)
            }
        }

    @Suppress("DEPRECATION")
    private fun decodeFrameBitmap(
        imageData: ByteArray,
        imageIndex: Int,
        imageCountX: Int,
        imageCountY: Int,
    ): Bitmap {
        val xCount = imageCountX.coerceAtLeast(1)
        val yCount = imageCountY.coerceAtLeast(1)
        val decoder = BitmapRegionDecoder.newInstance(imageData, 0, imageData.size, false)
        try {
            val cellWidth = decoder.width / xCount
            val cellHeight = decoder.height / yCount
            if (cellWidth <= 0 || cellHeight <= 0) {
                throw IllegalStateException(
                    "invalid videoshot grid: image=${decoder.width}x${decoder.height} grid=${xCount}x$yCount",
                )
            }

            val safeImageIndex = imageIndex.coerceIn(0, xCount * yCount - 1)
            val left = (safeImageIndex % xCount) * cellWidth
            val top = (safeImageIndex / xCount) * cellHeight
            val rect =
                Rect(
                    left,
                    top,
                    (left + cellWidth).coerceAtMost(decoder.width),
                    (top + cellHeight).coerceAtMost(decoder.height),
                )
            return decoder.decodeRegion(rect, bitmapOptions())
                ?: throw IllegalStateException("decode videoshot frame failed: index=$safeImageIndex rect=$rect")
        } catch (t: Throwable) {
            AppLog.w(TAG, "decode videoshot frame failed: index=$imageIndex size=${imageData.size}", t)
            throw t
        } finally {
            decoder.recycle()
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
    val bitmap: Bitmap,
)
