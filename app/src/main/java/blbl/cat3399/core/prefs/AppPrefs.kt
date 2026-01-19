package blbl.cat3399.core.prefs

import android.content.Context
import org.json.JSONArray

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("blbl_prefs", Context.MODE_PRIVATE)

    var disclaimerAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, value).apply()

    var webRefreshToken: String?
        get() = prefs.getString(KEY_WEB_REFRESH_TOKEN, null)?.trim()?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_WEB_REFRESH_TOKEN, value?.trim()).apply()

    var webCookieRefreshCheckedEpochDay: Long
        get() = prefs.getLong(KEY_WEB_COOKIE_REFRESH_CHECKED_EPOCH_DAY, -1L)
        set(value) = prefs.edit().putLong(KEY_WEB_COOKIE_REFRESH_CHECKED_EPOCH_DAY, value).apply()

    var biliTicketCheckedEpochDay: Long
        get() = prefs.getLong(KEY_BILI_TICKET_CHECKED_EPOCH_DAY, -1L)
        set(value) = prefs.edit().putLong(KEY_BILI_TICKET_CHECKED_EPOCH_DAY, value).apply()

    var uiMode: String
        get() = prefs.getString(KEY_UI_MODE, UI_MODE_AUTO) ?: UI_MODE_AUTO
        set(value) = prefs.edit().putString(KEY_UI_MODE, value).apply()

    var sidebarSize: String
        get() = prefs.getString(KEY_SIDEBAR_SIZE, SIDEBAR_SIZE_MEDIUM) ?: SIDEBAR_SIZE_MEDIUM
        set(value) = prefs.edit().putString(KEY_SIDEBAR_SIZE, value).apply()

    var userAgent: String
        get() = prefs.getString(KEY_UA, DEFAULT_UA) ?: DEFAULT_UA
        set(value) = prefs.edit().putString(KEY_UA, value).apply()

    var deviceBuvid: String
        get() = prefs.getString(KEY_DEVICE_BUVID, null) ?: generateBuvid().also { prefs.edit().putString(KEY_DEVICE_BUVID, it).apply() }
        set(value) = prefs.edit().putString(KEY_DEVICE_BUVID, value.trim()).apply()

    var buvidActivatedMid: Long
        get() = prefs.getLong(KEY_BUVID_ACTIVATED_MID, 0L)
        set(value) = prefs.edit().putLong(KEY_BUVID_ACTIVATED_MID, value).apply()

    var buvidActivatedEpochDay: Long
        get() = prefs.getLong(KEY_BUVID_ACTIVATED_EPOCH_DAY, -1L)
        set(value) = prefs.edit().putLong(KEY_BUVID_ACTIVATED_EPOCH_DAY, value).apply()

    var imageQuality: String
        get() = prefs.getString(KEY_IMAGE_QUALITY, "medium") ?: "medium"
        set(value) = prefs.edit().putString(KEY_IMAGE_QUALITY, value).apply()

    var danmakuEnabled: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_ENABLED, value).apply()

    var danmakuAllowTop: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_ALLOW_TOP, true)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_ALLOW_TOP, value).apply()

    var danmakuAllowBottom: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_ALLOW_BOTTOM, true)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_ALLOW_BOTTOM, value).apply()

    var danmakuAllowScroll: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_ALLOW_SCROLL, true)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_ALLOW_SCROLL, value).apply()

    var danmakuAllowColor: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_ALLOW_COLOR, true)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_ALLOW_COLOR, value).apply()

    var danmakuAllowSpecial: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_ALLOW_SPECIAL, true)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_ALLOW_SPECIAL, value).apply()

    var danmakuAiShieldEnabled: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_AI_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_AI_ENABLED, value).apply()

    var danmakuAiShieldLevel: Int
        get() = prefs.getInt(KEY_DANMAKU_AI_LEVEL, 0)
        set(value) = prefs.edit().putInt(KEY_DANMAKU_AI_LEVEL, value.coerceIn(0, 10)).apply()

    var danmakuFollowBiliShield: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_FOLLOW_BILI_SHIELD, true)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_FOLLOW_BILI_SHIELD, value).apply()

    var danmakuOpacity: Float
        get() = prefs.getFloat(KEY_DANMAKU_OPACITY, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_DANMAKU_OPACITY, value).apply()

    var danmakuTextSizeSp: Float
        get() = prefs.getFloat(KEY_DANMAKU_TEXT_SIZE_SP, 18f)
        set(value) = prefs.edit().putFloat(KEY_DANMAKU_TEXT_SIZE_SP, value).apply()

    var danmakuSpeed: Int
        get() = prefs.getInt(KEY_DANMAKU_SPEED, 4)
        set(value) = prefs.edit().putInt(KEY_DANMAKU_SPEED, value).apply()

    var danmakuArea: Float
        get() = prefs.getFloat(KEY_DANMAKU_AREA, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_DANMAKU_AREA, value).apply()

    var playerPreferredQn: Int
        get() = prefs.getInt(KEY_PLAYER_PREFERRED_QN, 80)
        set(value) = prefs.edit().putInt(KEY_PLAYER_PREFERRED_QN, value).apply()

    var playerPreferredCodec: String
        get() = prefs.getString(KEY_PLAYER_CODEC, "AVC") ?: "AVC"
        set(value) = prefs.edit().putString(KEY_PLAYER_CODEC, value).apply()

    var playerPreferredAudioId: Int
        get() = prefs.getInt(KEY_PLAYER_AUDIO_ID, 30280)
        set(value) = prefs.edit().putInt(KEY_PLAYER_AUDIO_ID, value).apply()

    var playerCdnPreference: String
        get() = prefs.getString(KEY_PLAYER_CDN_PREFERENCE, PLAYER_CDN_BILIVIDEO) ?: PLAYER_CDN_BILIVIDEO
        set(value) = prefs.edit().putString(KEY_PLAYER_CDN_PREFERENCE, value).apply()

    var subtitlePreferredLang: String
        get() = prefs.getString(KEY_SUBTITLE_LANG, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_SUBTITLE_LANG, value).apply()

    var subtitleEnabledDefault: Boolean
        get() = prefs.getBoolean(KEY_SUBTITLE_ENABLED_DEFAULT, false)
        set(value) = prefs.edit().putBoolean(KEY_SUBTITLE_ENABLED_DEFAULT, value).apply()

    var playerSpeed: Float
        get() = prefs.getFloat(KEY_PLAYER_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PLAYER_SPEED, value).apply()

    var playerAutoResumeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_AUTO_RESUME_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_AUTO_RESUME_ENABLED, value).apply()

    var playerAutoSkipSegmentsEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_AUTO_SKIP_SEGMENTS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_AUTO_SKIP_SEGMENTS_ENABLED, value).apply()

    var fullscreenEnabled: Boolean
        get() = prefs.getBoolean(KEY_FULLSCREEN, true)
        set(value) = prefs.edit().putBoolean(KEY_FULLSCREEN, value).apply()

    var playerDebugEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_DEBUG, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_DEBUG, value).apply()

    var playerDoubleBackOnEnded: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_DOUBLE_BACK_ON_ENDED, true)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_DOUBLE_BACK_ON_ENDED, value).apply()

    var playerPersistentBottomProgressEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_PERSISTENT_BOTTOM_PROGRESS, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_PERSISTENT_BOTTOM_PROGRESS, value).apply()

    var playerPlaybackMode: String
        get() = prefs.getString(KEY_PLAYER_PLAYBACK_MODE, PLAYER_PLAYBACK_MODE_NONE) ?: PLAYER_PLAYBACK_MODE_NONE
        set(value) = prefs.edit().putString(KEY_PLAYER_PLAYBACK_MODE, value).apply()

    var gridSpanCount: Int
        get() = prefs.getInt(KEY_GRID_SPAN, 0) // 0 => auto
        set(value) = prefs.edit().putInt(KEY_GRID_SPAN, value).apply()

    var dynamicGridSpanCount: Int
        get() = prefs.getInt(KEY_DYNAMIC_GRID_SPAN, 3)
        set(value) = prefs.edit().putInt(KEY_DYNAMIC_GRID_SPAN, value).apply()

    var searchHistory: List<String>
        get() = loadStringList(KEY_SEARCH_HISTORY)
        set(value) = saveStringList(KEY_SEARCH_HISTORY, value)

    var gaiaVgateVVoucher: String?
        get() = prefs.getString(KEY_GAIA_VGATE_V_VOUCHER, null)?.trim()?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_GAIA_VGATE_V_VOUCHER, value?.trim()).apply()

    var gaiaVgateVVoucherSavedAtMs: Long
        get() = prefs.getLong(KEY_GAIA_VGATE_V_VOUCHER_SAVED_AT_MS, -1L)
        set(value) = prefs.edit().putLong(KEY_GAIA_VGATE_V_VOUCHER_SAVED_AT_MS, value).apply()

    fun addSearchHistory(keyword: String, maxSize: Int = 20) {
        val k = keyword.trim()
        if (k.isBlank()) return
        val old = searchHistory
        val out = ArrayList<String>(old.size + 1)
        out.add(k)
        for (item in old) {
            if (item.equals(k, ignoreCase = true)) continue
            out.add(item)
            if (out.size >= maxSize) break
        }
        searchHistory = out
    }

    fun clearSearchHistory() {
        prefs.edit().remove(KEY_SEARCH_HISTORY).apply()
    }

    private fun loadStringList(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotBlank()) out.add(s)
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun saveStringList(key: String, value: List<String>) {
        val arr = JSONArray()
        for (s in value) {
            val v = s.trim()
            if (v.isNotBlank()) arr.put(v)
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    companion object {
        const val UI_MODE_AUTO = "auto"
        const val UI_MODE_TV = "tv"
        const val UI_MODE_NORMAL = "normal"

        const val SIDEBAR_SIZE_SMALL = "small"
        const val SIDEBAR_SIZE_MEDIUM = "medium"
        const val SIDEBAR_SIZE_LARGE = "large"

        private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
        private const val KEY_WEB_REFRESH_TOKEN = "web_refresh_token"
        private const val KEY_WEB_COOKIE_REFRESH_CHECKED_EPOCH_DAY = "web_cookie_refresh_checked_epoch_day"
        private const val KEY_BILI_TICKET_CHECKED_EPOCH_DAY = "bili_ticket_checked_epoch_day"

        private const val KEY_UA = "ua"
        private const val KEY_DEVICE_BUVID = "device_buvid"
        private const val KEY_BUVID_ACTIVATED_MID = "buvid_activated_mid"
        private const val KEY_BUVID_ACTIVATED_EPOCH_DAY = "buvid_activated_epoch_day"
        private const val KEY_UI_MODE = "ui_mode"
        private const val KEY_SIDEBAR_SIZE = "sidebar_size"
        private const val KEY_IMAGE_QUALITY = "image_quality"
        private const val KEY_DANMAKU_ENABLED = "danmaku_enabled"
        private const val KEY_DANMAKU_ALLOW_TOP = "danmaku_allow_top"
        private const val KEY_DANMAKU_ALLOW_BOTTOM = "danmaku_allow_bottom"
        private const val KEY_DANMAKU_ALLOW_SCROLL = "danmaku_allow_scroll"
        private const val KEY_DANMAKU_ALLOW_COLOR = "danmaku_allow_color"
        private const val KEY_DANMAKU_ALLOW_SPECIAL = "danmaku_allow_special"
        private const val KEY_DANMAKU_AI_ENABLED = "danmaku_ai_enabled"
        private const val KEY_DANMAKU_AI_LEVEL = "danmaku_ai_level"
        private const val KEY_DANMAKU_FOLLOW_BILI_SHIELD = "danmaku_follow_bili_shield"
        private const val KEY_DANMAKU_OPACITY = "danmaku_opacity"
        private const val KEY_DANMAKU_TEXT_SIZE_SP = "danmaku_text_size_sp"
        private const val KEY_DANMAKU_SPEED = "danmaku_speed"
        private const val KEY_DANMAKU_AREA = "danmaku_area"
        private const val KEY_PLAYER_PREFERRED_QN = "player_preferred_qn"
        private const val KEY_PLAYER_CODEC = "player_codec"
        private const val KEY_PLAYER_AUDIO_ID = "player_audio_id"
        private const val KEY_PLAYER_CDN_PREFERENCE = "player_cdn_preference"
        private const val KEY_SUBTITLE_LANG = "subtitle_lang"
        private const val KEY_SUBTITLE_ENABLED_DEFAULT = "subtitle_enabled_default"
        private const val KEY_PLAYER_SPEED = "player_speed"
        private const val KEY_PLAYER_AUTO_RESUME_ENABLED = "player_auto_resume_enabled"
        private const val KEY_PLAYER_AUTO_SKIP_SEGMENTS_ENABLED = "player_auto_skip_segments_enabled"
        private const val KEY_FULLSCREEN = "fullscreen_enabled"
        private const val KEY_PLAYER_DEBUG = "player_debug_enabled"
        private const val KEY_PLAYER_DOUBLE_BACK_ON_ENDED = "player_double_back_on_ended"
        private const val KEY_PLAYER_PERSISTENT_BOTTOM_PROGRESS = "player_persistent_bottom_progress"
        private const val KEY_PLAYER_PLAYBACK_MODE = "player_playback_mode"
        private const val KEY_GRID_SPAN = "grid_span"
        private const val KEY_DYNAMIC_GRID_SPAN = "dynamic_grid_span"
        private const val KEY_SEARCH_HISTORY = "search_history"
        private const val KEY_GAIA_VGATE_V_VOUCHER = "gaia_vgate_v_voucher"
        private const val KEY_GAIA_VGATE_V_VOUCHER_SAVED_AT_MS = "gaia_vgate_v_voucher_saved_at_ms"

        // PC browser UA is used to reduce CDN 403 for media resources.
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

        const val PLAYER_CDN_BILIVIDEO = "bilivideo"
        const val PLAYER_CDN_MCDN = "mcdn"

        const val PLAYER_PLAYBACK_MODE_LOOP_ONE = "loop_one"
        const val PLAYER_PLAYBACK_MODE_NEXT = "next"
        const val PLAYER_PLAYBACK_MODE_NONE = "none"
        const val PLAYER_PLAYBACK_MODE_EXIT = "exit"

        private fun generateBuvid(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            val md5 = java.security.MessageDigest.getInstance("MD5").digest(bytes)
            val hex = buildString(md5.size * 2) { md5.forEach { append(String.format(java.util.Locale.US, "%02x", it)) } }
            return "XY${hex[2]}${hex[12]}${hex[22]}$hex"
        }
    }
}
