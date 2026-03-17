package blbl.cat3399.feature.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import android.util.LruCache
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.concurrent.ConcurrentHashMap

data class SegmentMark(
    val startFraction: Float,
    val endFraction: Float,
    val style: SegmentMarkStyle = SegmentMarkStyle.SKIP,
)

enum class SegmentMarkStyle {
    SKIP,
    POI,
}

internal data class SubtitleItem(
    val lan: String,
    val lanDoc: String,
    val url: String,
)

data class PlayerPlaylistItem(
    val bvid: String,
    val cid: Long? = null,
    val epId: Long? = null,
    val aid: Long? = null,
    val title: String? = null,
    val seasonId: Long? = null,
)

internal sealed interface Playable {
    data class Dash(
        val videoUrl: String,
        val audioUrl: String,
        val videoUrlCandidates: List<String>,
        val audioUrlCandidates: List<String>,
        val videoTrackInfo: DashTrackInfo,
        val audioTrackInfo: DashTrackInfo,
        val qn: Int,
        val codecid: Int,
        val audioId: Int,
        val audioKind: DashAudioKind,
        val isDolbyVision: Boolean,
    ) : Playable

    data class VideoOnly(
        val videoUrl: String,
        val videoUrlCandidates: List<String>,
        val qn: Int,
        val codecid: Int,
        val isDolbyVision: Boolean,
    ) : Playable

    data class Progressive(
        val url: String,
        val urlCandidates: List<String>,
    ) : Playable
}

internal enum class DashAudioKind { NORMAL, DOLBY, FLAC }

internal data class DashSegmentBase(
    val initialization: String,
    val indexRange: String,
)

internal data class DashTrackInfo(
    val mimeType: String?,
    val codecs: String?,
    val bandwidth: Long?,
    val width: Int?,
    val height: Int?,
    val frameRate: String?,
    val segmentBase: DashSegmentBase?,
)

internal data class PlaybackConstraints(
    val allowDolbyVision: Boolean = true,
    val allowDolbyAudio: Boolean = true,
    val allowFlacAudio: Boolean = true,
)

internal data class ResumeCandidate(
    val rawTime: Long,
    val rawTimeUnitHint: RawTimeUnitHint,
    val source: String,
)

internal enum class RawTimeUnitHint {
    UNKNOWN,
    SECONDS_LIKELY,
    MILLIS_LIKELY,
}

internal data class SkipSegment(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val category: String?,
    val source: String,
    val actionType: String? = null,
)

internal fun SkipSegment.isPoi(): Boolean {
    val action = actionType?.trim().orEmpty()
    if (action.equals("poi", ignoreCase = true)) return true
    return category?.trim().equals("poi_highlight", ignoreCase = true)
}

internal fun SkipSegment.isAutoSkippable(): Boolean = !isPoi() && endMs > startMs

internal data class PendingAutoSkip(
    val token: Int,
    val segment: SkipSegment,
    val dueAtElapsedMs: Long,
)

internal data class PlayerSessionSettings(
    val playbackSpeed: Float,
    val preferCodec: String,
    val preferAudioId: Int,
    val targetAudioId: Int = 0,
    val actualAudioId: Int = 0,
    val preferredQn: Int,
    val targetQn: Int,
    val actualQn: Int = 0,
    val playbackModeOverride: String?,
    val subtitleEnabled: Boolean,
    val subtitleLangOverride: String?,
    val subtitleTextSizeSp: Float,
    val subtitleBottomPaddingFraction: Float,
    val subtitleBackgroundOpacity: Float,
    val danmaku: DanmakuSessionSettings,
    val debugEnabled: Boolean,
)

internal fun PlayerSessionSettings.toEngineSwitchJsonString(): String {
    val obj =
        JSONObject().apply {
            put("v", 1)
            put("playbackSpeed", playbackSpeed.toDouble())
            put("preferCodec", preferCodec)
            put("preferAudioId", preferAudioId)
            put("targetAudioId", targetAudioId)
            put("preferredQn", preferredQn)
            put("targetQn", targetQn)
            put("playbackModeOverride", playbackModeOverride ?: JSONObject.NULL)
            put("subtitleEnabled", subtitleEnabled)
            put("subtitleLangOverride", subtitleLangOverride ?: JSONObject.NULL)
            put("subtitleTextSizeSp", subtitleTextSizeSp.toDouble())
            put("subtitleBottomPaddingFraction", subtitleBottomPaddingFraction.toDouble())
            put("subtitleBackgroundOpacity", subtitleBackgroundOpacity.toDouble())
            put("danmakuEnabled", danmaku.enabled)
            put("danmakuOpacity", danmaku.opacity.toDouble())
            put("danmakuTextSizeSp", danmaku.textSizeSp.toDouble())
            put("danmakuSpeedLevel", danmaku.speedLevel)
            put("danmakuArea", danmaku.area.toDouble())
            put("debugEnabled", debugEnabled)
        }
    return obj.toString()
}

internal fun PlayerSessionSettings.restoreFromEngineSwitchJsonString(raw: String): PlayerSessionSettings {
    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return this

    fun optFloat(key: String, fallback: Float): Float {
        val v = obj.optDouble(key, fallback.toDouble()).toFloat()
        if (!v.isFinite()) return fallback
        return v
    }

    fun optInt(key: String, fallback: Int): Int {
        return obj.optInt(key, fallback)
    }

    fun optStringOrNull(key: String): String? {
        if (obj.isNull(key)) return null
        val v = obj.optString(key, "").trim()
        return v.takeIf { it.isNotBlank() }
    }

    val speed = optFloat("playbackSpeed", playbackSpeed).coerceIn(0.25f, 4.0f)
    val codec = obj.optString("preferCodec", preferCodec).trim().ifBlank { preferCodec }
    val preferAudio = optInt("preferAudioId", preferAudioId).takeIf { it > 0 } ?: preferAudioId
    val tAudio = optInt("targetAudioId", targetAudioId).takeIf { it >= 0 } ?: targetAudioId
    val pQn = optInt("preferredQn", preferredQn).takeIf { it > 0 } ?: preferredQn
    val tQn = optInt("targetQn", targetQn).takeIf { it >= 0 } ?: targetQn
    val modeOverride = optStringOrNull("playbackModeOverride")
    val subEnabled = obj.optBoolean("subtitleEnabled", subtitleEnabled)
    val subLangOverride = optStringOrNull("subtitleLangOverride")
    val subTextSize = optFloat("subtitleTextSizeSp", subtitleTextSizeSp).coerceIn(10f, 60f)
    val subBottomPaddingFraction =
        optFloat("subtitleBottomPaddingFraction", subtitleBottomPaddingFraction)
            .coerceIn(0f, 0.30f)
    val subBackgroundOpacity =
        optFloat("subtitleBackgroundOpacity", subtitleBackgroundOpacity)
            .coerceIn(0f, 1.0f)
    val danEnabled = obj.optBoolean("danmakuEnabled", danmaku.enabled)
    val danOpacity = optFloat("danmakuOpacity", danmaku.opacity).coerceIn(0.05f, 1.0f)
    val danText = optFloat("danmakuTextSizeSp", danmaku.textSizeSp).coerceIn(10f, 60f)
    val danSpeed = optInt("danmakuSpeedLevel", danmaku.speedLevel).coerceIn(1, 10)
    val danArea = optFloat("danmakuArea", danmaku.area).coerceIn(0.05f, 1.0f)
    val dbg = obj.optBoolean("debugEnabled", debugEnabled)

    return copy(
        playbackSpeed = speed,
        preferCodec = codec,
        preferAudioId = preferAudio,
        targetAudioId = tAudio,
        preferredQn = pQn,
        targetQn = tQn,
        playbackModeOverride = modeOverride,
        subtitleEnabled = subEnabled,
        subtitleLangOverride = subLangOverride,
        subtitleTextSizeSp = subTextSize,
        subtitleBottomPaddingFraction = subBottomPaddingFraction,
        subtitleBackgroundOpacity = subBackgroundOpacity,
        danmaku =
            danmaku.copy(
                enabled = danEnabled,
                opacity = danOpacity,
                textSizeSp = danText,
                speedLevel = danSpeed,
                area = danArea,
            ),
        debugEnabled = dbg,
    )
}

internal data class VideoShot(
    val times: List<UShort>,
    val images: List<ByteArray?>,
    val imageCountX: Int,
    val imageCountY: Int,
    val imageWidth: Int,
    val imageHeight: Int
){
    companion object {
        suspend fun fromVideoShot(videoShot: BiliApi.VideoShotInfo): VideoShot? =
            withContext(Dispatchers.IO) {
                Log.d("videoShot", videoShot.toString())
                val images = videoShot.image.map { imageUrl ->
                    async {
                        runCatching {
                            BiliClient.getBytes("https:"+imageUrl)
                        }.onFailure {
                            Log.e("videoShot", "download video shot image failed: ${it.stackTraceToString()}")
                        }.getOrNull()
                    }
                }.awaitAll()

                if (images.any { it == null }) {
                    println("download video shot images failed")
                    return@withContext null
                }

                val timeBinary = runCatching {
                    BiliClient.getBytes(
                        "https:"+videoShot.pvData ?: throw IllegalStateException("pvData is null")
                    )
                }.onFailure {
                    println("download video shot times binary failed: ${it.stackTraceToString()}")
                    return@withContext null
                }.getOrNull()

                val times = mutableListOf<UShort>()

                runCatching {
                    DataInputStream(ByteArrayInputStream(timeBinary)).use {
                        while (it.available() > 0) {
                            times.add(it.readUnsignedShort().toUShort())
                        }
                    }
                }.onFailure {
                    println("parse video shot times binary failed: ${it.stackTraceToString()}")
                    return@withContext null
                }

                return@withContext VideoShot(
                    times = times.drop(1),
                    images = images.filterNotNull(),
                    imageCountX = videoShot.imgXLen,
                    imageCountY = videoShot.imgYLen,
                    imageWidth = videoShot.imgXSize,
                    imageHeight = videoShot.imgYSize
                )
            }
    }

    suspend fun getSpriteFrame(time: Int, cache: VideoShotImageCache): SpriteFrame {
        val index = findClosestValueIndex(times, time.toUShort())
        val singleImgCount = imageCountX * imageCountY
        val imagesIndex = index / singleImgCount
        val imageIndex = index % singleImgCount

        val spriteSheet = cache.getOrDecodeImage(
            imagesIndex,
            images[imagesIndex]!!
        )

        val cellWidth = spriteSheet.width / imageCountX
        val cellHeight = spriteSheet.height / imageCountY

        val left = (imageIndex % imageCountX) * cellWidth
        val top = (imageIndex / imageCountX) * cellHeight

        return SpriteFrame(
            spriteSheet = spriteSheet,
            srcRect = Rect(left, top, left + cellWidth, top + cellHeight)
        )
    }

    private fun findClosestValueIndex(array: List<UShort>, target: UShort): Int {
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

class VideoShotImageCache {
    private val memoryCache = LruCache<Int, Bitmap>(3) // 缓存3张大图
    private val activeTasks = ConcurrentHashMap<Int, Deferred<Bitmap>>()

    companion object {
        val bitmapOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inScaled = false
        }
    }

    suspend fun getOrDecodeImage(imagesIndex: Int, imageData: ByteArray): Bitmap = coroutineScope {
        memoryCache.get(imagesIndex)?.let { return@coroutineScope it }

        val task = activeTasks.getOrPut(imagesIndex) {
            async(Dispatchers.IO) {
                val decoded = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, bitmapOptions)
                memoryCache.put(imagesIndex, decoded)
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
        memoryCache.evictAll()
        activeTasks.clear()
    }
}

data class SpriteFrame(
    val spriteSheet: Bitmap,
    val srcRect: Rect
)