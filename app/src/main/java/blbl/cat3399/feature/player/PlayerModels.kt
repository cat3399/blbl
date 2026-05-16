package blbl.cat3399.feature.player

import blbl.cat3399.core.api.video.VideoMediaRequestProfile
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import org.json.JSONObject

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
        val videoMediaRequestProfile: VideoMediaRequestProfile,
        val audioMediaRequestProfile: VideoMediaRequestProfile,
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
        val videoMediaRequestProfile: VideoMediaRequestProfile,
        val qn: Int,
        val codecid: Int,
        val isDolbyVision: Boolean,
    ) : Playable

    data class Progressive(
        val url: String,
        val urlCandidates: List<String>,
        val mediaRequestProfile: VideoMediaRequestProfile,
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
    val audioBalanceLevel: AudioBalanceLevel,
    val persistentBottomProgressEnabled: Boolean,
    val danmaku: DanmakuSessionSettings,
    val debugEnabled: Boolean,
    val engineKind: PlayerEngineKind,
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
            put("audioBalanceLevel", audioBalanceLevel.prefValue)
            put("persistentBottomProgressEnabled", persistentBottomProgressEnabled)
            put("danmakuEnabled", danmaku.enabled)
            put("danmakuOpacity", danmaku.opacity.toDouble())
            put("danmakuTextSizeSp", danmaku.textSizeSp.toDouble())
            put("danmakuFontWeight", danmaku.fontWeight.prefValue)
            put("danmakuStrokeWidthPx", danmaku.strokeWidthPx)
            put("danmakuSpeedLevel", danmaku.speedLevel)
            put("danmakuArea", danmaku.area.toDouble())
            put("danmakuLaneDensity", danmaku.laneDensity.prefValue)
            put("danmakuShowHighLikeIcon", danmaku.showHighLikeIcon)
            put("debugEnabled", debugEnabled)
            put("engineKind", engineKind.prefValue)
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

    fun normalizeDanmakuStrokeWidthPx(value: Int): Int {
        return when {
            value <= 1 -> 0
            value <= 3 -> 2
            value <= 5 -> 4
            else -> 6
        }
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
    val audioBalanceLevel = AudioBalanceLevel.fromPrefValue(obj.optString("audioBalanceLevel", audioBalanceLevel.prefValue))
    val persistentBottomProgressEnabled =
        obj.optBoolean("persistentBottomProgressEnabled", persistentBottomProgressEnabled)
    val danEnabled = obj.optBoolean("danmakuEnabled", danmaku.enabled)
    val danOpacity = optFloat("danmakuOpacity", danmaku.opacity).coerceIn(0.05f, 1.0f)
    val danText = optFloat("danmakuTextSizeSp", danmaku.textSizeSp).coerceIn(10f, 60f)
    val danFontWeight = DanmakuFontWeight.fromPrefValue(obj.optString("danmakuFontWeight", danmaku.fontWeight.prefValue))
    val danStrokeWidthPx = normalizeDanmakuStrokeWidthPx(optInt("danmakuStrokeWidthPx", danmaku.strokeWidthPx))
    val danSpeed = optInt("danmakuSpeedLevel", danmaku.speedLevel).coerceIn(1, 10)
    val danArea = AppPrefs.normalizeLegacyDanmakuAreaCompat(optFloat("danmakuArea", danmaku.area))
    val danLaneDensity = DanmakuLaneDensity.fromPrefValue(obj.optString("danmakuLaneDensity", danmaku.laneDensity.prefValue))
    val danShowHighLikeIcon = obj.optBoolean("danmakuShowHighLikeIcon", danmaku.showHighLikeIcon)
    val dbg = obj.optBoolean("debugEnabled", debugEnabled)
    val restoredEngineKind = PlayerEngineKind.fromPrefValue(obj.optString("engineKind", engineKind.prefValue))

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
        audioBalanceLevel = audioBalanceLevel,
        persistentBottomProgressEnabled = persistentBottomProgressEnabled,
        danmaku =
            danmaku.copy(
                enabled = danEnabled,
                opacity = danOpacity,
                textSizeSp = danText,
                fontWeight = danFontWeight,
                strokeWidthPx = danStrokeWidthPx,
                speedLevel = danSpeed,
                area = danArea,
                laneDensity = danLaneDensity,
                showHighLikeIcon = danShowHighLikeIcon,
            ),
        debugEnabled = dbg,
        engineKind = restoredEngineKind,
    )
}
