package blbl.cat3399.core.prefs

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

internal data class PlayerCustomShortcut(
    val keyCode: Int,
    val trigger: PlayerCustomShortcutTrigger = PlayerCustomShortcutTrigger.SHORT_PRESS,
    val action: PlayerCustomShortcutAction,
)

internal enum class PlayerCustomShortcutTrigger(
    val value: String,
) {
    SHORT_PRESS("short"),
    LONG_PRESS("long"),
    ;

    companion object {
        fun fromValueOrNull(raw: String?): PlayerCustomShortcutTrigger? =
            entries.firstOrNull { it.value == raw?.trim()?.lowercase() }
    }
}

internal enum class PlayerCustomShortcutOpenVideoListTarget(
    val value: String,
) {
    AUTO("auto"),
    PAGE("page"),
    PARTS("parts"),
    RECOMMEND("recommend"),
    ;

    companion object {
        fun fromValueOrNull(raw: String?): PlayerCustomShortcutOpenVideoListTarget? {
            return entries.firstOrNull { it.value == raw?.trim()?.lowercase() }
        }
    }
}

internal sealed class PlayerCustomShortcutAction(
    val type: String,
) {
    data class OpenVideoList(
        val target: PlayerCustomShortcutOpenVideoListTarget,
    ) : PlayerCustomShortcutAction(TYPE_OPEN_VIDEO_LIST)

    object OpenComments : PlayerCustomShortcutAction(TYPE_OPEN_COMMENTS)

    object OpenSettings : PlayerCustomShortcutAction(TYPE_OPEN_SETTINGS)

    object ShowOsd : PlayerCustomShortcutAction(TYPE_SHOW_OSD)

    object TogglePlayPause : PlayerCustomShortcutAction(TYPE_TOGGLE_PLAY_PAUSE)

    object PlayPrevious : PlayerCustomShortcutAction(TYPE_PLAY_PREVIOUS)

    object PlayNext : PlayerCustomShortcutAction(TYPE_PLAY_NEXT)

    object OpenVideoDetail : PlayerCustomShortcutAction(TYPE_OPEN_VIDEO_DETAIL)

    object OpenUpDetail : PlayerCustomShortcutAction(TYPE_OPEN_UP_DETAIL)

    object Like : PlayerCustomShortcutAction(TYPE_LIKE)

    object Coin : PlayerCustomShortcutAction(TYPE_COIN)

    object Fav : PlayerCustomShortcutAction(TYPE_FAV)

    object ToggleSubtitles : PlayerCustomShortcutAction(TYPE_TOGGLE_SUBTITLES)

    object ToggleDanmaku : PlayerCustomShortcutAction(TYPE_TOGGLE_DANMAKU)

    data class SetPlaybackSpeed(
        val speed: Float,
    ) : PlayerCustomShortcutAction(TYPE_SET_PLAYBACK_SPEED)

    data class SetResolutionQn(
        val qn: Int,
    ) : PlayerCustomShortcutAction(TYPE_SET_RESOLUTION_QN)

    data class SetAudioId(
        val audioId: Int,
    ) : PlayerCustomShortcutAction(TYPE_SET_AUDIO_ID)

    data class SetCodec(
        val codec: String,
    ) : PlayerCustomShortcutAction(TYPE_SET_CODEC)

    data class SetPlaybackMode(
        val mode: String,
    ) : PlayerCustomShortcutAction(TYPE_SET_PLAYBACK_MODE)

    /**
     * @param lang "default" means clear session override and follow global pref.
     */
    data class SetSubtitleLang(
        val lang: String,
    ) : PlayerCustomShortcutAction(TYPE_SET_SUBTITLE_LANG)

    data class SetSubtitleTextSize(
        val textSizeSp: Float,
    ) : PlayerCustomShortcutAction(TYPE_SET_SUBTITLE_TEXT_SIZE)

    data class SetDanmakuOpacity(
        val opacity: Float,
    ) : PlayerCustomShortcutAction(TYPE_SET_DANMAKU_OPACITY)

    data class SetDanmakuTextSize(
        val textSizeSp: Float,
    ) : PlayerCustomShortcutAction(TYPE_SET_DANMAKU_TEXT_SIZE)

    data class SetDanmakuSpeed(
        val speedLevel: Int,
    ) : PlayerCustomShortcutAction(TYPE_SET_DANMAKU_SPEED)

    data class SetDanmakuArea(
        val area: Float,
    ) : PlayerCustomShortcutAction(TYPE_SET_DANMAKU_AREA)

    object ToggleDebugOverlay : PlayerCustomShortcutAction(TYPE_TOGGLE_DEBUG_OVERLAY)

    object TogglePersistentBottomProgress : PlayerCustomShortcutAction(TYPE_TOGGLE_PERSISTENT_BOTTOM_PROGRESS)

    companion object {
        const val TYPE_OPEN_VIDEO_LIST = "open_video_list"
        const val TYPE_OPEN_COMMENTS = "open_comments"
        const val TYPE_OPEN_SETTINGS = "open_settings"
        const val TYPE_SHOW_OSD = "show_osd"
        const val TYPE_TOGGLE_PLAY_PAUSE = "toggle_play_pause"
        const val TYPE_PLAY_PREVIOUS = "play_previous"
        const val TYPE_PLAY_NEXT = "play_next"
        const val TYPE_OPEN_VIDEO_DETAIL = "open_video_detail"
        const val TYPE_OPEN_UP_DETAIL = "open_up_detail"
        const val TYPE_LIKE = "like"
        const val TYPE_COIN = "coin"
        const val TYPE_FAV = "fav"
        const val TYPE_TOGGLE_SUBTITLES = "toggle_subtitles"
        const val TYPE_TOGGLE_DANMAKU = "toggle_danmaku"
        const val TYPE_SET_PLAYBACK_SPEED = "set_playback_speed"
        const val TYPE_SET_RESOLUTION_QN = "set_resolution_qn"
        const val TYPE_SET_AUDIO_ID = "set_audio_id"
        const val TYPE_SET_CODEC = "set_codec"
        const val TYPE_SET_PLAYBACK_MODE = "set_playback_mode"
        const val TYPE_SET_SUBTITLE_LANG = "set_subtitle_lang"
        const val TYPE_SET_SUBTITLE_TEXT_SIZE = "set_subtitle_text_size"
        const val TYPE_SET_DANMAKU_OPACITY = "set_danmaku_opacity"
        const val TYPE_SET_DANMAKU_TEXT_SIZE = "set_danmaku_text_size"
        const val TYPE_SET_DANMAKU_SPEED = "set_danmaku_speed"
        const val TYPE_SET_DANMAKU_AREA = "set_danmaku_area"
        const val TYPE_TOGGLE_DEBUG_OVERLAY = "toggle_debug_overlay"
        const val TYPE_TOGGLE_PERSISTENT_BOTTOM_PROGRESS = "toggle_persistent_bottom_progress"

        const val SUBTITLE_LANG_DEFAULT = "default"
    }
}

internal object PlayerCustomShortcutsStore {
    private const val JSON_VERSION = 2
    private const val JSON_VERSION_LEGACY_SHORT_PRESS = 1
    private const val KEY_VERSION = "v"
    private const val KEY_ITEMS = "items"

    private const val KEY_KEYCODE = "k"
    private const val KEY_TRIGGER = "t"
    private const val KEY_ACTION = "a"
    private const val KEY_PARAMS = "p"

    fun isForbiddenKeyCode(keyCode: Int): Boolean {
        // Reserve "system / navigation / confirm" keys so custom shortcuts never break basic navigation.
        return keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_ESCAPE ||
            keyCode == KeyEvent.KEYCODE_BUTTON_B ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
    }

    fun parse(raw: String?): List<PlayerCustomShortcut> {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return emptyList()

        val root = runCatching { JSONObject(text) }.getOrNull()
        val version = root?.optInt(KEY_VERSION, JSON_VERSION_LEGACY_SHORT_PRESS)
        val arr =
            root
                ?.takeIf { version == JSON_VERSION || version == JSON_VERSION_LEGACY_SHORT_PRESS }
                ?.optJSONArray(KEY_ITEMS)
                ?: runCatching { JSONArray(text) }.getOrNull()
                ?: return emptyList()

        val out = ArrayList<PlayerCustomShortcut>(arr.length().coerceAtLeast(0))
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val keyCode = obj.optInt(KEY_KEYCODE, 0)
            if (keyCode <= 0) continue
            if (isForbiddenKeyCode(keyCode)) continue
            val trigger =
                if (version == JSON_VERSION) {
                    PlayerCustomShortcutTrigger.fromValueOrNull(obj.optString(KEY_TRIGGER)) ?: continue
                } else {
                    PlayerCustomShortcutTrigger.SHORT_PRESS
                }

            val actionType = obj.optString(KEY_ACTION, "").trim()
            if (actionType.isBlank()) continue
            val params = obj.optJSONObject(KEY_PARAMS)
            val action = parseAction(type = actionType, params = params) ?: continue
            out.add(PlayerCustomShortcut(keyCode = keyCode, trigger = trigger, action = action))
        }

        return normalize(out)
    }

    fun serialize(items: List<PlayerCustomShortcut>): String {
        val normalized = normalize(items)
        val arr = JSONArray()
        for (item in normalized) {
            val obj = JSONObject()
            obj.put(KEY_KEYCODE, item.keyCode)
            obj.put(KEY_TRIGGER, item.trigger.value)
            obj.put(KEY_ACTION, item.action.type)
            val params = buildActionParams(item.action)
            if (params != null) obj.put(KEY_PARAMS, params)
            arr.put(obj)
        }
        return JSONObject()
            .put(KEY_VERSION, JSON_VERSION)
            .put(KEY_ITEMS, arr)
            .toString()
    }

    fun upsert(existing: List<PlayerCustomShortcut>, binding: PlayerCustomShortcut): List<PlayerCustomShortcut> {
        if (binding.keyCode <= 0 || isForbiddenKeyCode(binding.keyCode)) return normalize(existing)
        val out = existing.toMutableList()
        val idx = out.indexOfFirst { it.keyCode == binding.keyCode && it.trigger == binding.trigger }
        if (idx >= 0) {
            out[idx] = binding
        } else {
            out.add(binding)
        }
        return normalize(out)
    }

    fun remove(
        existing: List<PlayerCustomShortcut>,
        keyCode: Int,
        trigger: PlayerCustomShortcutTrigger,
    ): List<PlayerCustomShortcut> {
        if (keyCode <= 0) return normalize(existing)
        return normalize(existing.filterNot { it.keyCode == keyCode && it.trigger == trigger })
    }

    fun clear(): List<PlayerCustomShortcut> = emptyList()

    private fun normalize(items: List<PlayerCustomShortcut>): List<PlayerCustomShortcut> {
        if (items.isEmpty()) return emptyList()
        val seen = HashSet<Pair<Int, PlayerCustomShortcutTrigger>>(items.size * 2)
        val outReversed = ArrayList<PlayerCustomShortcut>(items.size)
        for (i in items.lastIndex downTo 0) {
            val it = items[i]
            if (it.keyCode <= 0) continue
            if (isForbiddenKeyCode(it.keyCode)) continue
            if (!seen.add(it.keyCode to it.trigger)) continue
            val action = parseAction(type = it.action.type, params = buildActionParams(it.action)) ?: continue
            outReversed.add(PlayerCustomShortcut(keyCode = it.keyCode, trigger = it.trigger, action = action))
        }
        outReversed.reverse()
        return outReversed
    }

    private fun parseAction(type: String, params: JSONObject?): PlayerCustomShortcutAction? {
        return when (type) {
            PlayerCustomShortcutAction.TYPE_OPEN_VIDEO_LIST -> {
                val target = PlayerCustomShortcutOpenVideoListTarget.fromValueOrNull(params?.optString("target"))
                    ?: return null
                PlayerCustomShortcutAction.OpenVideoList(target = target)
            }
            PlayerCustomShortcutAction.TYPE_OPEN_COMMENTS -> PlayerCustomShortcutAction.OpenComments
            PlayerCustomShortcutAction.TYPE_OPEN_SETTINGS -> PlayerCustomShortcutAction.OpenSettings
            PlayerCustomShortcutAction.TYPE_SHOW_OSD -> PlayerCustomShortcutAction.ShowOsd
            PlayerCustomShortcutAction.TYPE_TOGGLE_PLAY_PAUSE -> PlayerCustomShortcutAction.TogglePlayPause
            PlayerCustomShortcutAction.TYPE_PLAY_PREVIOUS -> PlayerCustomShortcutAction.PlayPrevious
            PlayerCustomShortcutAction.TYPE_PLAY_NEXT -> PlayerCustomShortcutAction.PlayNext
            PlayerCustomShortcutAction.TYPE_OPEN_VIDEO_DETAIL -> PlayerCustomShortcutAction.OpenVideoDetail
            PlayerCustomShortcutAction.TYPE_OPEN_UP_DETAIL -> PlayerCustomShortcutAction.OpenUpDetail
            PlayerCustomShortcutAction.TYPE_LIKE -> PlayerCustomShortcutAction.Like
            PlayerCustomShortcutAction.TYPE_COIN -> PlayerCustomShortcutAction.Coin
            PlayerCustomShortcutAction.TYPE_FAV -> PlayerCustomShortcutAction.Fav
            PlayerCustomShortcutAction.TYPE_TOGGLE_SUBTITLES -> PlayerCustomShortcutAction.ToggleSubtitles
            PlayerCustomShortcutAction.TYPE_TOGGLE_DANMAKU -> PlayerCustomShortcutAction.ToggleDanmaku
            PlayerCustomShortcutAction.TYPE_TOGGLE_DEBUG_OVERLAY -> PlayerCustomShortcutAction.ToggleDebugOverlay
            PlayerCustomShortcutAction.TYPE_TOGGLE_PERSISTENT_BOTTOM_PROGRESS -> PlayerCustomShortcutAction.TogglePersistentBottomProgress

            PlayerCustomShortcutAction.TYPE_SET_PLAYBACK_SPEED -> {
                val v = params?.optDouble("speed", Double.NaN)?.toFloat() ?: return null
                if (!v.isFinite()) return null
                val speed = v.coerceIn(0.25f, 4.0f)
                PlayerCustomShortcutAction.SetPlaybackSpeed(speed = speed)
            }

            PlayerCustomShortcutAction.TYPE_SET_RESOLUTION_QN -> {
                val qn = params?.optInt("qn", 0) ?: return null
                if (qn <= 0) return null
                PlayerCustomShortcutAction.SetResolutionQn(qn = qn)
            }

            PlayerCustomShortcutAction.TYPE_SET_AUDIO_ID -> {
                val id = params?.optInt("audioId", 0) ?: return null
                if (id <= 0) return null
                PlayerCustomShortcutAction.SetAudioId(audioId = id)
            }

            PlayerCustomShortcutAction.TYPE_SET_CODEC -> {
                val codec = params?.optString("codec", "")?.trim().orEmpty()
                if (codec.isBlank()) return null
                val normalized =
                    when (codec.uppercase()) {
                        "AVC" -> "AVC"
                        "HEVC" -> "HEVC"
                        "AV1" -> "AV1"
                        else -> return null
                    }
                PlayerCustomShortcutAction.SetCodec(codec = normalized)
            }

            PlayerCustomShortcutAction.TYPE_SET_PLAYBACK_MODE -> {
                val mode = params?.optString("mode", "")?.trim().orEmpty()
                val normalized = PlayerPlaybackModes.normalize(mode)
                if (normalized != mode) return null
                PlayerCustomShortcutAction.SetPlaybackMode(mode = normalized)
            }

            PlayerCustomShortcutAction.TYPE_SET_SUBTITLE_LANG -> {
                val raw = params?.optString("lang", "")?.trim().orEmpty()
                val lang =
                    when {
                        raw.equals(PlayerCustomShortcutAction.SUBTITLE_LANG_DEFAULT, ignoreCase = true) -> PlayerCustomShortcutAction.SUBTITLE_LANG_DEFAULT
                        raw.isBlank() -> PlayerCustomShortcutAction.SUBTITLE_LANG_DEFAULT
                        raw.length > 32 -> raw.take(32)
                        else -> raw
                    }
                PlayerCustomShortcutAction.SetSubtitleLang(lang = lang)
            }

            PlayerCustomShortcutAction.TYPE_SET_SUBTITLE_TEXT_SIZE -> {
                val v = params?.optDouble("textSizeSp", Double.NaN)?.toFloat() ?: return null
                if (!v.isFinite()) return null
                PlayerCustomShortcutAction.SetSubtitleTextSize(textSizeSp = v.coerceIn(10f, 60f))
            }

            PlayerCustomShortcutAction.TYPE_SET_DANMAKU_OPACITY -> {
                val v = params?.optDouble("opacity", Double.NaN)?.toFloat() ?: return null
                if (!v.isFinite()) return null
                PlayerCustomShortcutAction.SetDanmakuOpacity(opacity = v.coerceIn(0.05f, 1.0f))
            }

            PlayerCustomShortcutAction.TYPE_SET_DANMAKU_TEXT_SIZE -> {
                val v = params?.optDouble("textSizeSp", Double.NaN)?.toFloat() ?: return null
                if (!v.isFinite()) return null
                PlayerCustomShortcutAction.SetDanmakuTextSize(textSizeSp = v.coerceIn(10f, 60f))
            }

            PlayerCustomShortcutAction.TYPE_SET_DANMAKU_SPEED -> {
                val v = params?.optInt("speedLevel", 0) ?: return null
                if (v <= 0) return null
                PlayerCustomShortcutAction.SetDanmakuSpeed(speedLevel = v.coerceIn(1, 10))
            }

            PlayerCustomShortcutAction.TYPE_SET_DANMAKU_AREA -> {
                val v = params?.optDouble("area", Double.NaN)?.toFloat() ?: return null
                if (!v.isFinite()) return null
                PlayerCustomShortcutAction.SetDanmakuArea(area = AppPrefs.normalizeLegacyDanmakuAreaCompat(v))
            }

            else -> null
        }
    }

    private fun buildActionParams(action: PlayerCustomShortcutAction): JSONObject? {
        return when (action) {
            is PlayerCustomShortcutAction.OpenVideoList ->
                JSONObject().put("target", action.target.value)

            PlayerCustomShortcutAction.OpenComments,
            PlayerCustomShortcutAction.OpenSettings,
            PlayerCustomShortcutAction.ShowOsd,
            PlayerCustomShortcutAction.TogglePlayPause,
            PlayerCustomShortcutAction.PlayPrevious,
            PlayerCustomShortcutAction.PlayNext,
            PlayerCustomShortcutAction.OpenVideoDetail,
            PlayerCustomShortcutAction.OpenUpDetail,
            PlayerCustomShortcutAction.Like,
            PlayerCustomShortcutAction.Coin,
            PlayerCustomShortcutAction.Fav,
            PlayerCustomShortcutAction.ToggleSubtitles,
            PlayerCustomShortcutAction.ToggleDanmaku,
            PlayerCustomShortcutAction.ToggleDebugOverlay,
            PlayerCustomShortcutAction.TogglePersistentBottomProgress,
            -> null

            is PlayerCustomShortcutAction.SetPlaybackSpeed ->
                JSONObject().put("speed", action.speed.toDouble())

            is PlayerCustomShortcutAction.SetResolutionQn ->
                JSONObject().put("qn", action.qn)

            is PlayerCustomShortcutAction.SetAudioId ->
                JSONObject().put("audioId", action.audioId)

            is PlayerCustomShortcutAction.SetCodec ->
                JSONObject().put("codec", action.codec)

            is PlayerCustomShortcutAction.SetPlaybackMode ->
                JSONObject().put("mode", action.mode)

            is PlayerCustomShortcutAction.SetSubtitleLang ->
                JSONObject().put("lang", action.lang)

            is PlayerCustomShortcutAction.SetSubtitleTextSize ->
                JSONObject().put("textSizeSp", action.textSizeSp.toDouble())

            is PlayerCustomShortcutAction.SetDanmakuOpacity ->
                JSONObject().put("opacity", action.opacity.toDouble())

            is PlayerCustomShortcutAction.SetDanmakuTextSize ->
                JSONObject().put("textSizeSp", action.textSizeSp.toDouble())

            is PlayerCustomShortcutAction.SetDanmakuSpeed ->
                JSONObject().put("speedLevel", action.speedLevel)

            is PlayerCustomShortcutAction.SetDanmakuArea ->
                JSONObject().put("area", action.area.toDouble())
        }
    }
}
