package blbl.cat3399.core.prefs

import android.content.Context
import android.provider.Settings
import blbl.cat3399.core.tv.isTvDevice
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

class AppPrefs(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("blbl_prefs", Context.MODE_PRIVATE)
    private val defaultPlayerTouchGesturesEnabled by lazy(LazyThreadSafetyMode.NONE) { !appContext.isTvDevice() }

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

    var sidebarSize: String
        get() = prefs.getString(KEY_SIDEBAR_SIZE, SIDEBAR_SIZE_MEDIUM) ?: SIDEBAR_SIZE_MEDIUM
        set(value) = prefs.edit().putString(KEY_SIDEBAR_SIZE, value).apply()

    var uiScaleFactor: Float
        get() {
            if (prefs.contains(KEY_UI_SCALE_FACTOR)) {
                return normalizeUiScaleFactor(prefs.getFloat(KEY_UI_SCALE_FACTOR, UI_SCALE_FACTOR_DEFAULT))
            }
            // Legacy fallback (kept for migration): sidebar_size small/medium/large -> 0.90/1.00/1.10.
            return when (sidebarSize) {
                SIDEBAR_SIZE_SMALL -> 0.90f
                SIDEBAR_SIZE_LARGE -> 1.10f
                else -> UI_SCALE_FACTOR_DEFAULT
            }
        }
        set(value) = prefs.edit().putFloat(KEY_UI_SCALE_FACTOR, normalizeUiScaleFactor(value)).apply()

    var themePreset: String
        get() {
            return normalizeThemePreset(prefs.getString(KEY_THEME_PRESET, THEME_PRESET_DEFAULT))
        }
        set(value) {
            prefs.edit().putString(KEY_THEME_PRESET, normalizeThemePreset(value)).apply()
        }

    var startupPage: String
        get() = prefs.getString(KEY_STARTUP_PAGE, STARTUP_PAGE_HOME)?.trim()?.takeIf { it.isNotBlank() } ?: STARTUP_PAGE_HOME
        set(value) {
            val v = value.trim().takeIf { it.isNotBlank() } ?: STARTUP_PAGE_HOME
            prefs.edit().putString(KEY_STARTUP_PAGE, v).apply()
        }

    var customPageConfig: CustomPageConfig
        get() = CustomPageConfigStore.parse(prefs.getString(KEY_CUSTOM_PAGE_CONFIG, null))
        set(value) {
            val normalized = CustomPageConfigStore.normalize(value)
            if (!normalized.enabled && normalized.tabs.isEmpty()) {
                prefs.edit().remove(KEY_CUSTOM_PAGE_CONFIG).apply()
            } else {
                prefs.edit().putString(KEY_CUSTOM_PAGE_CONFIG, CustomPageConfigStore.serialize(normalized)).apply()
            }
        }

    var mainHomeVisibleTabs: List<String>
        get() = loadStringList(KEY_MAIN_HOME_VISIBLE_TABS)
        set(value) = saveStringList(KEY_MAIN_HOME_VISIBLE_TABS, normalizeStringList(value))

    var mainCategoryVisibleTabs: List<String>
        get() = loadStringList(KEY_MAIN_CATEGORY_VISIBLE_TABS)
        set(value) = saveStringList(KEY_MAIN_CATEGORY_VISIBLE_TABS, normalizeStringList(value))

    var mainLiveVisibleTabs: List<String>
        get() = loadStringList(KEY_MAIN_LIVE_VISIBLE_TABS)
        set(value) = saveStringList(KEY_MAIN_LIVE_VISIBLE_TABS, normalizeStringList(value))

    var mainMyVisibleTabs: List<String>
        get() = loadStringList(KEY_MAIN_MY_VISIBLE_TABS)
        set(value) = saveStringList(KEY_MAIN_MY_VISIBLE_TABS, normalizeStringList(value))

    var followingListOrder: String
        get() {
            val raw = prefs.getString(KEY_FOLLOWING_LIST_ORDER, FOLLOWING_LIST_ORDER_FOLLOW_TIME) ?: FOLLOWING_LIST_ORDER_FOLLOW_TIME
            return when (raw.trim()) {
                FOLLOWING_LIST_ORDER_RECENT_VISIT -> FOLLOWING_LIST_ORDER_RECENT_VISIT
                else -> FOLLOWING_LIST_ORDER_FOLLOW_TIME
            }
        }
        set(value) {
            val normalized =
                when (value.trim()) {
                    FOLLOWING_LIST_ORDER_RECENT_VISIT -> FOLLOWING_LIST_ORDER_RECENT_VISIT
                    else -> FOLLOWING_LIST_ORDER_FOLLOW_TIME
                }
            prefs.edit().putString(KEY_FOLLOWING_LIST_ORDER, normalized).apply()
        }

    var dynamicFollowingRecentUpdateDotEnabled: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_FOLLOWING_RECENT_UPDATE_DOT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DYNAMIC_FOLLOWING_RECENT_UPDATE_DOT_ENABLED, value).apply()

    var autoUpdateCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE_CHECK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE_CHECK_ENABLED, value).apply()

    var autoUpdateIgnoredVersionName: String?
        get() = prefs.getString(KEY_AUTO_UPDATE_IGNORED_VERSION_NAME, null)?.trim()?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_AUTO_UPDATE_IGNORED_VERSION_NAME, value?.trim()).apply()

    var userAgent: String
        get() = prefs.getString(KEY_UA, DEFAULT_UA) ?: DEFAULT_UA
        set(value) = prefs.edit().putString(KEY_UA, value).apply()

    var ipv4OnlyEnabled: Boolean
        get() = prefs.getBoolean(KEY_IPV4_ONLY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IPV4_ONLY_ENABLED, value).apply()

    var deviceBuvid: String
        get() = prefs.getString(KEY_DEVICE_BUVID, null) ?: generateBuvid().also { prefs.edit().putString(KEY_DEVICE_BUVID, it).apply() }
        set(value) = prefs.edit().putString(KEY_DEVICE_BUVID, value.trim()).apply()

    /**
     * Stable per-device UUID for diagnostics (e.g. log uploads).
     *
     * - Pref-backed (memory): once created/derived, keep using it.
     * - Prefer deriving from ANDROID_ID (stable across reinstall on most devices).
     * - Fallback to random UUID when ANDROID_ID is unavailable/invalid.
     */
    var deviceUuid: String
        get() {
            val cached =
                prefs.getString(KEY_DEVICE_UUID, null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.takeIf { isValidUuid(it) }
            if (cached != null) return cached

            val derived = deriveDeviceUuid()
            prefs.edit().putString(KEY_DEVICE_UUID, derived).apply()
            return derived
        }
        set(value) = prefs.edit().putString(KEY_DEVICE_UUID, value.trim()).apply()

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

    var danmakuShowHighLikeIcon: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_SHOW_HIGH_LIKE_ICON, true)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_SHOW_HIGH_LIKE_ICON, value).apply()

    var danmakuOpacity: Float
        get() = prefs.getFloat(KEY_DANMAKU_OPACITY, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_DANMAKU_OPACITY, value).apply()

    var danmakuTextSizeSp: Float
        get() = prefs.getFloat(KEY_DANMAKU_TEXT_SIZE_SP, 18f)
        set(value) = prefs.edit().putFloat(KEY_DANMAKU_TEXT_SIZE_SP, value).apply()

    var danmakuLaneDensity: String
        get() {
            val raw = prefs.getString(KEY_DANMAKU_LANE_DENSITY, DANMAKU_LANE_DENSITY_STANDARD) ?: DANMAKU_LANE_DENSITY_STANDARD
            val v = raw.trim()
            return when (v) {
                DANMAKU_LANE_DENSITY_SPARSE,
                DANMAKU_LANE_DENSITY_STANDARD,
                DANMAKU_LANE_DENSITY_DENSE,
                -> v

                else -> DANMAKU_LANE_DENSITY_STANDARD
            }
        }
        set(value) {
            val v = value.trim()
            val normalized =
                when (v) {
                    DANMAKU_LANE_DENSITY_SPARSE -> DANMAKU_LANE_DENSITY_SPARSE
                    DANMAKU_LANE_DENSITY_DENSE -> DANMAKU_LANE_DENSITY_DENSE
                    else -> DANMAKU_LANE_DENSITY_STANDARD
                }
            prefs.edit().putString(KEY_DANMAKU_LANE_DENSITY, normalized).apply()
        }

    var danmakuStrokeWidthPx: Int
        get() {
            val v = prefs.getInt(KEY_DANMAKU_STROKE_WIDTH_PX, 4)
            return when (v) {
                0, 2, 4, 6 -> v
                else -> 4
            }
        }
        set(value) {
            val v =
                when (value) {
                    0, 2, 4, 6 -> value
                    else -> 4
                }
            prefs.edit().putInt(KEY_DANMAKU_STROKE_WIDTH_PX, v).apply()
        }

    var danmakuFontWeight: String
        get() {
            val raw = prefs.getString(KEY_DANMAKU_FONT_WEIGHT, DANMAKU_FONT_WEIGHT_BOLD) ?: DANMAKU_FONT_WEIGHT_BOLD
            val v = raw.trim()
            return when (v) {
                DANMAKU_FONT_WEIGHT_NORMAL,
                DANMAKU_FONT_WEIGHT_BOLD,
                -> v

                else -> DANMAKU_FONT_WEIGHT_BOLD
            }
        }
        set(value) {
            val v = value.trim()
            val normalized =
                when (v) {
                    DANMAKU_FONT_WEIGHT_NORMAL -> DANMAKU_FONT_WEIGHT_NORMAL
                    else -> DANMAKU_FONT_WEIGHT_BOLD
                }
            prefs.edit().putString(KEY_DANMAKU_FONT_WEIGHT, normalized).apply()
        }

    var danmakuSpeed: Int
        get() = prefs.getInt(KEY_DANMAKU_SPEED, 4)
        set(value) = prefs.edit().putInt(KEY_DANMAKU_SPEED, value).apply()

    var danmakuArea: Float
        get() {
            val raw = prefs.getFloat(KEY_DANMAKU_AREA, DANMAKU_AREA_DEFAULT)
            val normalized = normalizeLegacyDanmakuAreaCompat(raw)
            if (abs(raw - normalized) > DANMAKU_AREA_COMPAT_EPSILON) {
                prefs.edit().putFloat(KEY_DANMAKU_AREA, normalized).apply()
            }
            return normalized
        }
        set(value) = prefs.edit().putFloat(KEY_DANMAKU_AREA, normalizeDanmakuArea(value)).apply()

    var playerPreferredQn: Int
        get() = prefs.getInt(KEY_PLAYER_PREFERRED_QN, 80)
        set(value) = prefs.edit().putInt(KEY_PLAYER_PREFERRED_QN, value).apply()

    var playerPreferredQnPortrait: Int
        get() {
            if (!prefs.contains(KEY_PLAYER_PREFERRED_QN_PORTRAIT)) return playerPreferredQn
            return prefs.getInt(KEY_PLAYER_PREFERRED_QN_PORTRAIT, playerPreferredQn)
        }
        set(value) = prefs.edit().putInt(KEY_PLAYER_PREFERRED_QN_PORTRAIT, value).apply()

    var playerPreferredCodec: String
        get() = prefs.getString(KEY_PLAYER_CODEC, "AVC") ?: "AVC"
        set(value) = prefs.edit().putString(KEY_PLAYER_CODEC, value).apply()

    var playerRenderViewType: String
        get() {
            val raw = prefs.getString(KEY_PLAYER_RENDER_VIEW, PLAYER_RENDER_VIEW_SURFACE_VIEW) ?: PLAYER_RENDER_VIEW_SURFACE_VIEW
            val v = raw.trim()
            return when (v) {
                PLAYER_RENDER_VIEW_SURFACE_VIEW,
                PLAYER_RENDER_VIEW_TEXTURE_VIEW,
                -> v

                else -> PLAYER_RENDER_VIEW_SURFACE_VIEW
            }
        }
        set(value) {
            val v = value.trim()
            val normalized =
                when (v) {
                    PLAYER_RENDER_VIEW_SURFACE_VIEW,
                    PLAYER_RENDER_VIEW_TEXTURE_VIEW,
                    -> v

                    else -> PLAYER_RENDER_VIEW_SURFACE_VIEW
                }
            prefs.edit().putString(KEY_PLAYER_RENDER_VIEW, normalized).apply()
        }

    var playerEngineKind: String
        get() {
            val raw = prefs.getString(KEY_PLAYER_ENGINE_KIND, PLAYER_ENGINE_EXO) ?: PLAYER_ENGINE_EXO
            val v = raw.trim()
            return when (v) {
                PLAYER_ENGINE_EXO,
                PLAYER_ENGINE_IJK,
                -> v
                else -> PLAYER_ENGINE_EXO
            }
        }
        set(value) {
            val v = value.trim()
            val normalized =
                when (v) {
                    PLAYER_ENGINE_IJK -> PLAYER_ENGINE_IJK
                    else -> PLAYER_ENGINE_EXO
                }
            prefs.edit().putString(KEY_PLAYER_ENGINE_KIND, normalized).apply()
        }

    var playerStyle: String
        get() {
            val raw = prefs.getString(KEY_PLAYER_STYLE, PLAYER_STYLE_FULLSCREEN) ?: PLAYER_STYLE_FULLSCREEN
            val v = raw.trim()
            return when (v) {
                PLAYER_STYLE_FULLSCREEN,
                PLAYER_STYLE_HD,
                -> v

                else -> PLAYER_STYLE_FULLSCREEN
            }
        }
        set(value) {
            val v = value.trim()
            val normalized =
                when (v) {
                    PLAYER_STYLE_HD -> PLAYER_STYLE_HD
                    else -> PLAYER_STYLE_FULLSCREEN
                }
            prefs.edit().putString(KEY_PLAYER_STYLE, normalized).apply()
        }

    var playerPreferredAudioId: Int
        get() = prefs.getInt(KEY_PLAYER_AUDIO_ID, 30280)
        set(value) = prefs.edit().putInt(KEY_PLAYER_AUDIO_ID, value).apply()

    var playerCdnPreference: String
        get() = prefs.getString(KEY_PLAYER_CDN_PREFERENCE, PLAYER_CDN_BILIVIDEO) ?: PLAYER_CDN_BILIVIDEO
        set(value) = prefs.edit().putString(KEY_PLAYER_CDN_PREFERENCE, value).apply()

    /**
     * When enabled, try to rewrite live m3u8 urls to remove Bilibili's transcoding suffix
     * (e.g. `_2500`, `_bluray`) in order to fetch the origin stream and get higher bitrate.
     *
     * Note: Some rooms/CDNs may reject the rewritten url (403/404) or have unstable playlists.
     */
    var liveHighBitrateEnabled: Boolean
        get() = prefs.getBoolean(KEY_LIVE_HIGH_BITRATE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_HIGH_BITRATE_ENABLED, value).apply()

    var subtitlePreferredLang: String
        get() = prefs.getString(KEY_SUBTITLE_LANG, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_SUBTITLE_LANG, value).apply()

    var subtitleEnabledDefault: Boolean
        get() = prefs.getBoolean(KEY_SUBTITLE_ENABLED_DEFAULT, false)
        set(value) = prefs.edit().putBoolean(KEY_SUBTITLE_ENABLED_DEFAULT, value).apply()

    var subtitleTextSizeSp: Float
        get() {
            val v = prefs.getFloat(KEY_SUBTITLE_TEXT_SIZE_SP, 26f)
            if (!v.isFinite()) return 26f
            return v.coerceIn(10f, 60f)
        }
        set(value) {
            val v = if (value.isFinite()) value.coerceIn(10f, 60f) else 26f
            prefs.edit().putFloat(KEY_SUBTITLE_TEXT_SIZE_SP, v).apply()
        }

    var subtitleBottomPaddingFraction: Float
        get() {
            val v = prefs.getFloat(KEY_SUBTITLE_BOTTOM_PADDING_FRACTION, SUBTITLE_BOTTOM_PADDING_FRACTION_DEFAULT)
            if (!v.isFinite()) return SUBTITLE_BOTTOM_PADDING_FRACTION_DEFAULT
            return v.coerceIn(0f, 0.30f)
        }
        set(value) {
            val v = if (value.isFinite()) value.coerceIn(0f, 0.30f) else SUBTITLE_BOTTOM_PADDING_FRACTION_DEFAULT
            prefs.edit().putFloat(KEY_SUBTITLE_BOTTOM_PADDING_FRACTION, v).apply()
        }

    var subtitleBackgroundOpacity: Float
        get() {
            val v = prefs.getFloat(KEY_SUBTITLE_BACKGROUND_OPACITY, SUBTITLE_BACKGROUND_OPACITY_DEFAULT)
            if (!v.isFinite()) return SUBTITLE_BACKGROUND_OPACITY_DEFAULT
            return v.coerceIn(0f, 1.0f)
        }
        set(value) {
            val v = if (value.isFinite()) value.coerceIn(0f, 1.0f) else SUBTITLE_BACKGROUND_OPACITY_DEFAULT
            prefs.edit().putFloat(KEY_SUBTITLE_BACKGROUND_OPACITY, v).apply()
        }

    var playerSpeed: Float
        get() = prefs.getFloat(KEY_PLAYER_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PLAYER_SPEED, value).apply()

    var playerShortSeekStepSeconds: Int
        get() =
            normalizePlayerShortSeekStepSeconds(
                prefs.getInt(KEY_PLAYER_SHORT_SEEK_STEP_SECONDS, PLAYER_SHORT_SEEK_STEP_SECONDS_DEFAULT),
            )
        set(value) =
            prefs.edit()
                .putInt(
                    KEY_PLAYER_SHORT_SEEK_STEP_SECONDS,
                    normalizePlayerShortSeekStepSeconds(value),
                ).apply()

    var playerHoldSeekSpeed: Float
        get() {
            val v = prefs.getFloat(KEY_PLAYER_HOLD_SEEK_SPEED, PLAYER_HOLD_SEEK_SPEED_DEFAULT)
            if (!v.isFinite()) return PLAYER_HOLD_SEEK_SPEED_DEFAULT
            return v.coerceIn(1.5f, 4.0f)
        }
        set(value) = prefs.edit().putFloat(KEY_PLAYER_HOLD_SEEK_SPEED, value.coerceIn(1.5f, 4.0f)).apply()

    var playerHoldSeekMode: String
        get() {
            val raw = prefs.getString(KEY_PLAYER_HOLD_SEEK_MODE, PLAYER_HOLD_SEEK_MODE_SPEED) ?: PLAYER_HOLD_SEEK_MODE_SPEED
            val v = raw.trim()
            return when (v) {
                PLAYER_HOLD_SEEK_MODE_SPEED,
                PLAYER_HOLD_SEEK_MODE_SCRUB,
                PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME,
                -> v

                else -> PLAYER_HOLD_SEEK_MODE_SPEED
            }
        }
        set(value) {
            val v =
                when (value) {
                    PLAYER_HOLD_SEEK_MODE_SPEED,
                    PLAYER_HOLD_SEEK_MODE_SCRUB,
                    PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME,
                    -> value

                    else -> PLAYER_HOLD_SEEK_MODE_SPEED
                }
            prefs.edit().putString(KEY_PLAYER_HOLD_SEEK_MODE, v).apply()
        }

    var playerHoldScrubTraverseSeconds: Int
        get() =
            normalizePlayerHoldScrubSeconds(
                prefs.getInt(KEY_PLAYER_HOLD_SCRUB_TRAVERSE_SECONDS, PLAYER_HOLD_SCRUB_SECONDS_DEFAULT),
            )
        set(value) =
            prefs.edit()
                .putInt(
                    KEY_PLAYER_HOLD_SCRUB_TRAVERSE_SECONDS,
                    normalizePlayerHoldScrubSeconds(value),
                ).apply()

    var playerHoldScrubFixedStepSeconds: Int
        get() =
            normalizePlayerHoldScrubSeconds(
                prefs.getInt(KEY_PLAYER_HOLD_SCRUB_FIXED_STEP_SECONDS, PLAYER_HOLD_SCRUB_SECONDS_DEFAULT),
            )
        set(value) =
            prefs.edit()
                .putInt(
                    KEY_PLAYER_HOLD_SCRUB_FIXED_STEP_SECONDS,
                    normalizePlayerHoldScrubSeconds(value),
                ).apply()

    var playerAutoResumeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_AUTO_RESUME_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_AUTO_RESUME_ENABLED, value).apply()

    var playerAutoSkipSegmentsEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_AUTO_SKIP_SEGMENTS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_AUTO_SKIP_SEGMENTS_ENABLED, value).apply()

    var playerAutoSkipServerBaseUrl: String
        get() =
            normalizePlayerAutoSkipServerBaseUrl(prefs.getString(KEY_PLAYER_AUTO_SKIP_SERVER_BASE_URL, null))
                ?: DEFAULT_PLAYER_AUTO_SKIP_SERVER_BASE_URL
        set(value) {
            val normalized = normalizePlayerAutoSkipServerBaseUrl(value) ?: DEFAULT_PLAYER_AUTO_SKIP_SERVER_BASE_URL
            if (normalized == DEFAULT_PLAYER_AUTO_SKIP_SERVER_BASE_URL) {
                prefs.edit().remove(KEY_PLAYER_AUTO_SKIP_SERVER_BASE_URL).apply()
            } else {
                prefs.edit().putString(KEY_PLAYER_AUTO_SKIP_SERVER_BASE_URL, normalized).apply()
            }
        }

    var playerOpenDetailBeforePlay: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_OPEN_DETAIL_BEFORE_PLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_OPEN_DETAIL_BEFORE_PLAY, value).apply()

    var fullscreenEnabled: Boolean
        get() = prefs.getBoolean(KEY_FULLSCREEN, true)
        set(value) = prefs.edit().putBoolean(KEY_FULLSCREEN, value).apply()

    var tabSwitchFollowsFocus: Boolean
        get() = prefs.getBoolean(KEY_TAB_SWITCH_FOLLOWS_FOCUS, true)
        set(value) = prefs.edit().putBoolean(KEY_TAB_SWITCH_FOLLOWS_FOCUS, value).apply()

    var mainAutoHideSidebarOnEnterContent: Boolean
        get() = prefs.getBoolean(KEY_MAIN_AUTO_HIDE_SIDEBAR_ON_ENTER_CONTENT, false)
        set(value) = prefs.edit().putBoolean(KEY_MAIN_AUTO_HIDE_SIDEBAR_ON_ENTER_CONTENT, value).apply()

    /**
     * Main page (Home/Category/Live/My) "Back" key focus-return scheme.
     *
     * Applied when focus is inside a page content area:
     * - Tab pages: focus is inside the ViewPager content.
     * - Dynamic page: focus is inside the page root (no tabs).
     *
     * Schemes:
     * - [MAIN_BACK_FOCUS_SCHEME_A] (Default): content -> focus current tab; tab -> focus sidebar.
     * - [MAIN_BACK_FOCUS_SCHEME_B]: content -> go to tab0 content; when already at tab0 content -> focus sidebar.
     * - [MAIN_BACK_FOCUS_SCHEME_C]: content -> focus sidebar.
     *
     * Notes:
     * - Search has its own back behavior (input/results panels).
     * - App-level navigation (return to startup page / exit) is still handled by MainActivity when unconsumed.
     */
    var mainBackFocusScheme: String
        get() {
            val raw = prefs.getString(KEY_MAIN_BACK_FOCUS_SCHEME, MAIN_BACK_FOCUS_SCHEME_A) ?: MAIN_BACK_FOCUS_SCHEME_A
            val v = raw.trim()
            return when (v) {
                MAIN_BACK_FOCUS_SCHEME_A,
                MAIN_BACK_FOCUS_SCHEME_B,
                MAIN_BACK_FOCUS_SCHEME_C,
                -> v
                else -> MAIN_BACK_FOCUS_SCHEME_A
            }
        }
        set(value) {
            val v = value.trim()
            val normalized =
                when (v) {
                    MAIN_BACK_FOCUS_SCHEME_A,
                    MAIN_BACK_FOCUS_SCHEME_B,
                    MAIN_BACK_FOCUS_SCHEME_C,
                    -> v
                    else -> MAIN_BACK_FOCUS_SCHEME_A
            }
            prefs.edit().putString(KEY_MAIN_BACK_FOCUS_SCHEME, normalized).apply()
        }

    var videoCardLongPressAction: String
        get() = normalizeVideoCardLongPressAction(prefs.getString(KEY_VIDEO_CARD_LONG_PRESS_ACTION, VIDEO_CARD_LONG_PRESS_ACTION_MANUAL))
        set(value) = prefs.edit().putString(KEY_VIDEO_CARD_LONG_PRESS_ACTION, normalizeVideoCardLongPressAction(value)).apply()

    var playerDebugEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_DEBUG, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_DEBUG, value).apply()

    var playerDoubleBackToExit: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_DOUBLE_BACK_TO_EXIT, true)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_DOUBLE_BACK_TO_EXIT, value).apply()

    var playerDownKeyOsdFocusTarget: String
        get() {
            val raw =
                prefs.getString(KEY_PLAYER_DOWN_KEY_OSD_FOCUS_TARGET, PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE)
                    ?: PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE
            val value = raw.trim()
            val normalized =
                when (value) {
                    PLAYER_DOWN_KEY_OSD_FOCUS_RECOMMEND_LEGACY,
                    PLAYER_DOWN_KEY_OSD_FOCUS_PLAYLIST_LEGACY,
                    -> PLAYER_DOWN_KEY_OSD_FOCUS_LIST_PANEL

                    else -> value
                }
            return when (normalized) {
                PLAYER_DOWN_KEY_OSD_FOCUS_PREV,
                PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE,
                PLAYER_DOWN_KEY_OSD_FOCUS_NEXT,
                PLAYER_DOWN_KEY_OSD_FOCUS_SUBTITLE,
                PLAYER_DOWN_KEY_OSD_FOCUS_DANMAKU,
                PLAYER_DOWN_KEY_OSD_FOCUS_COMMENTS,
                PLAYER_DOWN_KEY_OSD_FOCUS_DETAIL,
                PLAYER_DOWN_KEY_OSD_FOCUS_UP,
                PLAYER_DOWN_KEY_OSD_FOCUS_LIKE,
                PLAYER_DOWN_KEY_OSD_FOCUS_COIN,
                PLAYER_DOWN_KEY_OSD_FOCUS_FAV,
                PLAYER_DOWN_KEY_OSD_FOCUS_LIST_PANEL,
                PLAYER_DOWN_KEY_OSD_FOCUS_ADVANCED,
                -> normalized

                else -> PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE
            }
        }
        set(value) {
            val next =
                when (value) {
                    PLAYER_DOWN_KEY_OSD_FOCUS_PREV,
                    PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE,
                    PLAYER_DOWN_KEY_OSD_FOCUS_NEXT,
                    PLAYER_DOWN_KEY_OSD_FOCUS_SUBTITLE,
                    PLAYER_DOWN_KEY_OSD_FOCUS_DANMAKU,
                    PLAYER_DOWN_KEY_OSD_FOCUS_COMMENTS,
                    PLAYER_DOWN_KEY_OSD_FOCUS_DETAIL,
                    PLAYER_DOWN_KEY_OSD_FOCUS_UP,
                    PLAYER_DOWN_KEY_OSD_FOCUS_LIKE,
                    PLAYER_DOWN_KEY_OSD_FOCUS_COIN,
                    PLAYER_DOWN_KEY_OSD_FOCUS_FAV,
                    PLAYER_DOWN_KEY_OSD_FOCUS_LIST_PANEL,
                    PLAYER_DOWN_KEY_OSD_FOCUS_ADVANCED,
                    -> value

                    else -> PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE
                }
            prefs.edit().putString(KEY_PLAYER_DOWN_KEY_OSD_FOCUS_TARGET, next).apply()
        }

    var playerTogglePlayStateShowOsd: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_TOGGLE_PLAY_STATE_SHOW_OSD, true)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_TOGGLE_PLAY_STATE_SHOW_OSD, value).apply()

    var playerPersistentBottomProgressEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_PERSISTENT_BOTTOM_PROGRESS, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_PERSISTENT_BOTTOM_PROGRESS, value).apply()

    var playerPersistentClockEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_PERSISTENT_CLOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_PERSISTENT_CLOCK, value).apply()

    var playerTouchGesturesEnabled: Boolean
        get() {
            if (!prefs.contains(KEY_PLAYER_TOUCH_GESTURES_ENABLED)) return defaultPlayerTouchGesturesEnabled
            return prefs.getBoolean(KEY_PLAYER_TOUCH_GESTURES_ENABLED, defaultPlayerTouchGesturesEnabled)
        }
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_TOUCH_GESTURES_ENABLED, value).apply()

    var playerVideoShotPreviewSize: String
        get() {
            val raw = prefs.getString(KEY_PLAYER_VIDEOSHOT_PREVIEW_SIZE, PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM)
                ?: PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM
            val v = raw.trim()
            return when (v) {
                PLAYER_VIDEOSHOT_PREVIEW_SIZE_OFF,
                PLAYER_VIDEOSHOT_PREVIEW_SIZE_SMALL,
                PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM,
                PLAYER_VIDEOSHOT_PREVIEW_SIZE_LARGE,
                -> v
                else -> PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM
            }
        }
        set(value) {
            val v = value.trim()
            val normalized =
                when (v) {
                    PLAYER_VIDEOSHOT_PREVIEW_SIZE_OFF,
                    PLAYER_VIDEOSHOT_PREVIEW_SIZE_SMALL,
                    PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM,
                    PLAYER_VIDEOSHOT_PREVIEW_SIZE_LARGE,
                    -> v
                    else -> PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM
                }
            prefs.edit().putString(KEY_PLAYER_VIDEOSHOT_PREVIEW_SIZE, normalized).apply()
        }

    var playerAudioBalanceLevel: String
        get() {
            val raw = prefs.getString(KEY_PLAYER_AUDIO_BALANCE_LEVEL, PLAYER_AUDIO_BALANCE_OFF) ?: PLAYER_AUDIO_BALANCE_OFF
            val v = raw.trim()
            return when (v) {
                PLAYER_AUDIO_BALANCE_OFF,
                PLAYER_AUDIO_BALANCE_LOW,
                PLAYER_AUDIO_BALANCE_MEDIUM,
                PLAYER_AUDIO_BALANCE_HIGH,
                -> v

                else -> PLAYER_AUDIO_BALANCE_OFF
            }
        }
        set(value) {
            val v = value.trim()
            val normalized =
                when (v) {
                    PLAYER_AUDIO_BALANCE_LOW -> PLAYER_AUDIO_BALANCE_LOW
                    PLAYER_AUDIO_BALANCE_MEDIUM -> PLAYER_AUDIO_BALANCE_MEDIUM
                    PLAYER_AUDIO_BALANCE_HIGH -> PLAYER_AUDIO_BALANCE_HIGH
                    else -> PLAYER_AUDIO_BALANCE_OFF
                }
            prefs.edit().putString(KEY_PLAYER_AUDIO_BALANCE_LEVEL, normalized).apply()
        }

    var playerPlaybackMode: String
        get() = PlayerPlaybackModes.normalize(prefs.getString(KEY_PLAYER_PLAYBACK_MODE, PLAYER_PLAYBACK_MODE_NONE))
        set(value) = prefs.edit().putString(KEY_PLAYER_PLAYBACK_MODE, PlayerPlaybackModes.normalize(value)).apply()

    var playerSettingsApplyToGlobal: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_SETTINGS_APPLY_TO_GLOBAL, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_SETTINGS_APPLY_TO_GLOBAL, value).apply()

    var playerUpQuickCardEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_UP_QUICK_CARD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_UP_QUICK_CARD_ENABLED, value).apply()

    var playerOsdButtons: List<String>
        get() {
            // IMPORTANT:
            // - If the key doesn't exist yet, user never configured OSD -> return our default set.
            // - If the key exists (even if empty), respect it and only normalize (e.g. keep Play/Pause).
            if (!prefs.contains(KEY_PLAYER_OSD_BUTTONS)) return DEFAULT_PLAYER_OSD_BUTTONS
            val stored = loadStringList(KEY_PLAYER_OSD_BUTTONS)
            val normalized = normalizePlayerOsdButtons(stored)
            return migratePlayerOsdDetailButtonIfNeeded(normalized)
        }
        set(value) {
            saveStringList(KEY_PLAYER_OSD_BUTTONS, normalizePlayerOsdButtons(value))
            // Once user manually configures OSD buttons, never force-enable new buttons again.
            prefs.edit().putBoolean(KEY_PLAYER_OSD_BUTTONS_DETAIL_MIGRATED, true).apply()
        }

    internal var playerCustomShortcuts: List<PlayerCustomShortcut>
        get() = PlayerCustomShortcutsStore.parse(prefs.getString(KEY_PLAYER_CUSTOM_SHORTCUTS, null))
        set(value) {
            if (value.isEmpty()) {
                prefs.edit().remove(KEY_PLAYER_CUSTOM_SHORTCUTS).apply()
            } else {
                prefs.edit().putString(KEY_PLAYER_CUSTOM_SHORTCUTS, PlayerCustomShortcutsStore.serialize(value)).apply()
            }
        }

    var gridSpanCount: Int
        get() {
            val stored = prefs.getInt(KEY_GRID_SPAN, 4)
            val span = if (stored <= 0) 4 else stored
            return span.coerceIn(1, 6)
        }
        set(value) {
            val span = if (value <= 0) 4 else value
            prefs.edit().putInt(KEY_GRID_SPAN, span.coerceIn(1, 6)).apply()
        }

    var dynamicGridSpanCount: Int
        get() = prefs.getInt(KEY_DYNAMIC_GRID_SPAN, 3)
        set(value) = prefs.edit().putInt(KEY_DYNAMIC_GRID_SPAN, value).apply()

    var pgcGridSpanCount: Int
        get() {
            val stored = prefs.getInt(KEY_PGC_GRID_SPAN, 6)
            val span = if (stored <= 0) 6 else stored
            return span.coerceIn(1, 6)
        }
        set(value) {
            val span = if (value <= 0) 6 else value
            prefs.edit().putInt(KEY_PGC_GRID_SPAN, span.coerceIn(1, 6)).apply()
        }

    var pgcEpisodeOrderReversed: Boolean
        get() = prefs.getBoolean(KEY_PGC_EPISODE_ORDER_REVERSED, false)
        set(value) = prefs.edit().putBoolean(KEY_PGC_EPISODE_ORDER_REVERSED, value).apply()

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

    fun exportConfigSnapshotJson(): JSONObject =
        SharedPreferencesSnapshot.encode(
            prefs = prefs,
            excludeKeys = CREDENTIAL_KEYS,
        )

    fun exportDiagnosticsSnapshotJson(): JSONObject =
        SharedPreferencesSnapshot.encode(
            prefs = prefs,
            excludeKeys = DIAGNOSTIC_EXCLUDED_KEYS,
        )

    fun exportCredentialsSnapshotJson(): JSONObject =
        SharedPreferencesSnapshot.encode(
            prefs = prefs,
            includeKeys = CREDENTIAL_KEYS,
        )

    fun replaceConfigFromSnapshotJson(root: JSONObject) =
        SharedPreferencesSnapshot.replaceAll(
            prefs = prefs,
            root = root,
            excludeKeys = CREDENTIAL_KEYS,
        )

    fun replaceCredentialsFromSnapshotJson(root: JSONObject) =
        SharedPreferencesSnapshot.replaceAll(
            prefs = prefs,
            root = root,
            includeKeys = CREDENTIAL_KEYS,
        )

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

    private fun normalizeStringList(value: List<String>): List<String> {
        if (value.isEmpty()) return emptyList()
        val out = ArrayList<String>(value.size)
        val seen = HashSet<String>(value.size * 2)
        for (raw in value) {
            val key = raw.trim()
            if (key.isBlank()) continue
            if (seen.add(key)) out.add(key)
        }
        return out
    }

    private fun migratePlayerOsdDetailButtonIfNeeded(normalized: List<String>): List<String> {
        if (prefs.getBoolean(KEY_PLAYER_OSD_BUTTONS_DETAIL_MIGRATED, false)) return normalized
        // Requirement: auto-enable the new "Detail" button even for users who previously customized OSD.
        // Do it only once so the user can later disable it in Settings.
        prefs.edit().putBoolean(KEY_PLAYER_OSD_BUTTONS_DETAIL_MIGRATED, true).apply()
        if (normalized.contains(PLAYER_OSD_BTN_DETAIL)) return normalized

        val migrated = normalized + PLAYER_OSD_BTN_DETAIL
        saveStringList(KEY_PLAYER_OSD_BUTTONS, migrated)
        return migrated
    }

    private fun normalizePlayerOsdButtons(value: List<String>): List<String> {
        val out = ArrayList<String>(value.size + 1)
        val seen = HashSet<String>(value.size + 1)
        for (raw in value) {
            val key = raw.trim()
            if (key.isBlank()) continue
            if (!PLAYER_OSD_BUTTON_KEYS.contains(key)) continue
            if (seen.add(key)) out.add(key)
        }
        if (!seen.contains(PLAYER_OSD_BTN_PLAY_PAUSE)) {
            out.add(0, PLAYER_OSD_BTN_PLAY_PAUSE)
        }
        return out
    }

    private fun normalizeUiScaleFactor(value: Float): Float {
        val v = if (value.isFinite()) value else UI_SCALE_FACTOR_DEFAULT
        val clamped = v.coerceIn(UI_SCALE_FACTOR_MIN, UI_SCALE_FACTOR_MAX)
        val scaled = (clamped * 100f).roundToInt()
        val step = (UI_SCALE_FACTOR_STEP * 100f).roundToInt().coerceAtLeast(1)
        val snapped = ((scaled + step / 2) / step) * step
        return (snapped / 100f).coerceIn(UI_SCALE_FACTOR_MIN, UI_SCALE_FACTOR_MAX)
    }

    private fun normalizePlayerShortSeekStepSeconds(value: Int): Int {
        return if (PLAYER_SHORT_SEEK_STEP_SECONDS_OPTIONS.contains(value)) value else PLAYER_SHORT_SEEK_STEP_SECONDS_DEFAULT
    }

    private fun normalizePlayerHoldScrubSeconds(value: Int): Int {
        return if (PLAYER_HOLD_SCRUB_SECONDS_OPTIONS.contains(value)) value else PLAYER_HOLD_SCRUB_SECONDS_DEFAULT
    }

    companion object {
        const val STARTUP_PAGE_HOME = "home"
        const val STARTUP_PAGE_CATEGORY = "category"
        const val STARTUP_PAGE_DYNAMIC = "dynamic"
        const val STARTUP_PAGE_LIVE = "live"
        const val STARTUP_PAGE_MY = "my"
        const val STARTUP_PAGE_CUSTOM = "custom"

        const val SIDEBAR_SIZE_SMALL = "small"
        const val SIDEBAR_SIZE_MEDIUM = "medium"
        const val SIDEBAR_SIZE_LARGE = "large"

        const val UI_SCALE_FACTOR_MIN = 0.70f
        const val UI_SCALE_FACTOR_MAX = 1.40f
        const val UI_SCALE_FACTOR_STEP = 0.05f
        const val UI_SCALE_FACTOR_DEFAULT = 1.00f

        const val THEME_PRESET_DEFAULT = "default"
        const val THEME_PRESET_TV_PINK = "tv_pink"
        const val THEME_PRESET_TV_PINK_ILLUSTRATION = "tv_pink_illustration"

        const val FOLLOWING_LIST_ORDER_FOLLOW_TIME = "follow_time"
        const val FOLLOWING_LIST_ORDER_RECENT_VISIT = "recent_visit"

        // Main page back focus schemes for "Settings -> Page Settings".
        const val MAIN_BACK_FOCUS_SCHEME_A = "A"
        const val MAIN_BACK_FOCUS_SCHEME_B = "B"
        const val MAIN_BACK_FOCUS_SCHEME_C = "C"

        const val VIDEO_CARD_LONG_PRESS_ACTION_MANUAL = "manual"
        const val VIDEO_CARD_LONG_PRESS_ACTION_WATCH_LATER = "watch_later"
        const val VIDEO_CARD_LONG_PRESS_ACTION_OPEN_DETAIL = "open_detail"
        const val VIDEO_CARD_LONG_PRESS_ACTION_OPEN_UP = "open_up"
        const val VIDEO_CARD_LONG_PRESS_ACTION_DISMISS = "dismiss"

        private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
        private const val KEY_WEB_REFRESH_TOKEN = "web_refresh_token"
        private const val KEY_WEB_COOKIE_REFRESH_CHECKED_EPOCH_DAY = "web_cookie_refresh_checked_epoch_day"
        private const val KEY_BILI_TICKET_CHECKED_EPOCH_DAY = "bili_ticket_checked_epoch_day"

        private const val KEY_UA = "ua"
        private const val KEY_IPV4_ONLY_ENABLED = "ipv4_only_enabled"
        private const val KEY_DEVICE_BUVID = "device_buvid"
        private const val KEY_DEVICE_UUID = "device_uuid"
        private const val KEY_BUVID_ACTIVATED_MID = "buvid_activated_mid"
        private const val KEY_BUVID_ACTIVATED_EPOCH_DAY = "buvid_activated_epoch_day"
        private const val KEY_SIDEBAR_SIZE = "sidebar_size"
        private const val KEY_UI_SCALE_FACTOR = "ui_scale_factor"
        private const val KEY_THEME_PRESET = "theme_preset"
        private const val KEY_STARTUP_PAGE = "startup_page"
        private const val KEY_CUSTOM_PAGE_CONFIG = "custom_page_config"
        private const val KEY_MAIN_HOME_VISIBLE_TABS = "main_home_visible_tabs"
        private const val KEY_MAIN_CATEGORY_VISIBLE_TABS = "main_category_visible_tabs"
        private const val KEY_MAIN_LIVE_VISIBLE_TABS = "main_live_visible_tabs"
        private const val KEY_MAIN_MY_VISIBLE_TABS = "main_my_visible_tabs"
        private const val KEY_FOLLOWING_LIST_ORDER = "following_list_order"
        private const val KEY_DYNAMIC_FOLLOWING_RECENT_UPDATE_DOT_ENABLED = "dynamic_following_recent_update_dot_enabled"
        private const val KEY_AUTO_UPDATE_CHECK_ENABLED = "auto_update_check_enabled"
        private const val KEY_AUTO_UPDATE_IGNORED_VERSION_NAME = "auto_update_ignored_version_name"
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
        private const val KEY_DANMAKU_SHOW_HIGH_LIKE_ICON = "danmaku_show_high_like_icon"
        private const val KEY_DANMAKU_OPACITY = "danmaku_opacity"
        private const val KEY_DANMAKU_TEXT_SIZE_SP = "danmaku_text_size_sp"
        private const val KEY_DANMAKU_LANE_DENSITY = "danmaku_lane_density"
        private const val KEY_DANMAKU_STROKE_WIDTH_PX = "danmaku_stroke_width_px"
        private const val KEY_DANMAKU_FONT_WEIGHT = "danmaku_font_weight"
        private const val KEY_DANMAKU_SPEED = "danmaku_speed"
        private const val KEY_DANMAKU_AREA = "danmaku_area"
        private const val KEY_PLAYER_PREFERRED_QN = "player_preferred_qn"
        private const val KEY_PLAYER_PREFERRED_QN_PORTRAIT = "player_preferred_qn_portrait"
        private const val KEY_PLAYER_CODEC = "player_codec"
        private const val KEY_PLAYER_RENDER_VIEW = "player_render_view"
        private const val KEY_PLAYER_ENGINE_KIND = "player_engine_kind"
        private const val KEY_PLAYER_STYLE = "player_style"
        private const val KEY_PLAYER_AUDIO_ID = "player_audio_id"
        private const val KEY_PLAYER_CDN_PREFERENCE = "player_cdn_preference"
        private const val KEY_LIVE_HIGH_BITRATE_ENABLED = "live_high_bitrate_enabled"
        private const val KEY_SUBTITLE_LANG = "subtitle_lang"
        private const val KEY_SUBTITLE_ENABLED_DEFAULT = "subtitle_enabled_default"
        private const val KEY_SUBTITLE_TEXT_SIZE_SP = "subtitle_text_size_sp"
        private const val KEY_SUBTITLE_BOTTOM_PADDING_FRACTION = "subtitle_bottom_padding_fraction"
        private const val KEY_SUBTITLE_BACKGROUND_OPACITY = "subtitle_background_opacity"
        private const val KEY_PLAYER_SPEED = "player_speed"
        private const val KEY_PLAYER_SHORT_SEEK_STEP_SECONDS = "player_short_seek_step_seconds"
        private const val KEY_PLAYER_HOLD_SEEK_SPEED = "player_hold_seek_speed"
        private const val KEY_PLAYER_HOLD_SEEK_MODE = "player_hold_seek_mode"
        private const val KEY_PLAYER_HOLD_SCRUB_TRAVERSE_SECONDS = "player_hold_scrub_traverse_seconds"
        private const val KEY_PLAYER_HOLD_SCRUB_FIXED_STEP_SECONDS = "player_hold_scrub_fixed_step_seconds"
        private const val KEY_PLAYER_AUTO_RESUME_ENABLED = "player_auto_resume_enabled"
        private const val KEY_PLAYER_AUTO_SKIP_SEGMENTS_ENABLED = "player_auto_skip_segments_enabled"
        private const val KEY_PLAYER_AUTO_SKIP_SERVER_BASE_URL = "player_auto_skip_server_base_url"
        private const val KEY_PLAYER_OPEN_DETAIL_BEFORE_PLAY = "player_open_detail_before_play"
        private const val KEY_FULLSCREEN = "fullscreen_enabled"
        private const val KEY_TAB_SWITCH_FOLLOWS_FOCUS = "tab_switch_follows_focus"
        private const val KEY_MAIN_AUTO_HIDE_SIDEBAR_ON_ENTER_CONTENT = "main_auto_hide_sidebar_on_enter_content"
        private const val KEY_MAIN_BACK_FOCUS_SCHEME = "main_back_focus_scheme"
        private const val KEY_VIDEO_CARD_LONG_PRESS_ACTION = "video_card_long_press_action"
        private const val KEY_PLAYER_DEBUG = "player_debug_enabled"
        private const val KEY_PLAYER_DOUBLE_BACK_TO_EXIT = "player_double_back_on_ended"
        private const val KEY_PLAYER_DOWN_KEY_OSD_FOCUS_TARGET = "player_down_key_osd_focus_target"
        private const val KEY_PLAYER_TOGGLE_PLAY_STATE_SHOW_OSD = "player_toggle_play_state_show_osd"
        private const val KEY_PLAYER_PERSISTENT_BOTTOM_PROGRESS = "player_persistent_bottom_progress"
        private const val KEY_PLAYER_PERSISTENT_CLOCK = "player_persistent_clock"
        private const val KEY_PLAYER_TOUCH_GESTURES_ENABLED = "player_touch_gestures_enabled"
        private const val KEY_PLAYER_VIDEOSHOT_PREVIEW_SIZE = "player_videoshot_preview_size"
        private const val KEY_PLAYER_AUDIO_BALANCE_LEVEL = "player_audio_balance_level"
        private const val KEY_PLAYER_PLAYBACK_MODE = "player_playback_mode"
        private const val KEY_PLAYER_SETTINGS_APPLY_TO_GLOBAL = "player_settings_apply_to_global"
        private const val KEY_PLAYER_UP_QUICK_CARD_ENABLED = "player_up_quick_card_enabled"
        private const val KEY_PLAYER_OSD_BUTTONS = "player_osd_buttons"
        private const val KEY_PLAYER_OSD_BUTTONS_DETAIL_MIGRATED = "player_osd_buttons_detail_migrated"
        private const val KEY_PLAYER_CUSTOM_SHORTCUTS = "player_custom_shortcuts"
        private const val KEY_GRID_SPAN = "grid_span"
        private const val KEY_DYNAMIC_GRID_SPAN = "dynamic_grid_span"
        private const val KEY_PGC_GRID_SPAN = "pgc_grid_span"
        private const val KEY_PGC_EPISODE_ORDER_REVERSED = "pgc_episode_order_reversed"
        private const val KEY_SEARCH_HISTORY = "search_history"
        private const val KEY_GAIA_VGATE_V_VOUCHER = "gaia_vgate_v_voucher"
        private const val KEY_GAIA_VGATE_V_VOUCHER_SAVED_AT_MS = "gaia_vgate_v_voucher_saved_at_ms"

        private val CREDENTIAL_KEYS: Set<String> =
            setOf(
                KEY_WEB_REFRESH_TOKEN,
                KEY_WEB_COOKIE_REFRESH_CHECKED_EPOCH_DAY,
                KEY_BILI_TICKET_CHECKED_EPOCH_DAY,
                KEY_GAIA_VGATE_V_VOUCHER,
                KEY_GAIA_VGATE_V_VOUCHER_SAVED_AT_MS,
            )

        private val DIAGNOSTIC_EXCLUDED_KEYS: Set<String> =
            CREDENTIAL_KEYS +
                setOf(
                    KEY_BUVID_ACTIVATED_MID,
                    KEY_SEARCH_HISTORY,
                )

        // PC browser UA is used to reduce CDN 403 for media resources.
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

        const val PLAYER_CDN_BILIVIDEO = "bilivideo"
        const val PLAYER_CDN_MCDN = "mcdn"

        const val DANMAKU_LANE_DENSITY_SPARSE = "sparse"
        const val DANMAKU_LANE_DENSITY_STANDARD = "standard"
        const val DANMAKU_LANE_DENSITY_DENSE = "dense"

        const val DANMAKU_AREA_MIN = 0.10f
        const val DANMAKU_AREA_MAX = 1.00f
        const val DANMAKU_AREA_STEP = 0.10f
        const val DANMAKU_AREA_DEFAULT = DANMAKU_AREA_MAX
        const val DANMAKU_AREA_COMPAT_EPSILON = 0.0001f

        val DANMAKU_AREA_OPTIONS: List<Float> = (1..10).map { it / 10f }

        private val LEGACY_DANMAKU_AREA_OPTIONS: List<Float> =
            listOf(
                1f / 6f,
                1f / 5f,
                0.25f,
                1f / 3f,
                2f / 5f,
                0.50f,
                3f / 5f,
                2f / 3f,
                0.75f,
                4f / 5f,
                1.00f,
            )

        fun normalizeDanmakuArea(value: Float): Float {
            val v = value.takeIf { it.isFinite() } ?: DANMAKU_AREA_DEFAULT
            val clamped = v.coerceIn(DANMAKU_AREA_MIN, DANMAKU_AREA_MAX)
            val scaled = (clamped * 100f).roundToInt()
            val step = (DANMAKU_AREA_STEP * 100f).roundToInt().coerceAtLeast(1)
            val snapped = ((scaled + step / 2) / step) * step
            return (snapped / 100f).coerceIn(DANMAKU_AREA_MIN, DANMAKU_AREA_MAX)
        }

        /**
         * 0.1.22 生效，3 个版本后移除兼容：
         * 兼容历史分数档位（1/6、1/5、1/4、1/3、2/5、1/2、3/5、2/3、3/4、4/5、1），
         * 统一按新的 10% 档位四舍五入吸收到规范值。
         */
        fun normalizeLegacyDanmakuAreaCompat(value: Float): Float {
            val sanitized = value.takeIf { it.isFinite() } ?: DANMAKU_AREA_DEFAULT
            val legacy =
                LEGACY_DANMAKU_AREA_OPTIONS.firstOrNull { legacyValue ->
                    abs(legacyValue - sanitized) < DANMAKU_AREA_COMPAT_EPSILON
                }
            return normalizeDanmakuArea(legacy ?: sanitized)
        }

        fun normalizeVideoCardLongPressAction(value: String?): String {
            return when (value?.trim()) {
                VIDEO_CARD_LONG_PRESS_ACTION_WATCH_LATER -> VIDEO_CARD_LONG_PRESS_ACTION_WATCH_LATER
                VIDEO_CARD_LONG_PRESS_ACTION_OPEN_DETAIL -> VIDEO_CARD_LONG_PRESS_ACTION_OPEN_DETAIL
                VIDEO_CARD_LONG_PRESS_ACTION_OPEN_UP -> VIDEO_CARD_LONG_PRESS_ACTION_OPEN_UP
                VIDEO_CARD_LONG_PRESS_ACTION_DISMISS -> VIDEO_CARD_LONG_PRESS_ACTION_DISMISS
                else -> VIDEO_CARD_LONG_PRESS_ACTION_MANUAL
            }
        }

        const val DANMAKU_FONT_WEIGHT_NORMAL = "normal"
        const val DANMAKU_FONT_WEIGHT_BOLD = "bold"

        const val PLAYER_RENDER_VIEW_SURFACE_VIEW = "surface_view"
        const val PLAYER_RENDER_VIEW_TEXTURE_VIEW = "texture_view"

        const val PLAYER_ENGINE_EXO = "exoplayer"
        const val PLAYER_ENGINE_IJK = "ijkplayer"

        const val PLAYER_STYLE_FULLSCREEN = "fullscreen"
        const val PLAYER_STYLE_HD = "hd"

        const val PLAYER_AUDIO_BALANCE_OFF = "off"
        const val PLAYER_AUDIO_BALANCE_LOW = "low"
        const val PLAYER_AUDIO_BALANCE_MEDIUM = "medium"
        const val PLAYER_AUDIO_BALANCE_HIGH = "high"

        const val PLAYER_PLAYBACK_MODE_NONE = "none"
        const val PLAYER_PLAYBACK_MODE_LOOP_ONE = "loop_one"
        const val PLAYER_PLAYBACK_MODE_EXIT = "exit"
        const val PLAYER_PLAYBACK_MODE_PAGE_LIST = "page_list"
        const val PLAYER_PLAYBACK_MODE_PARTS_LIST = "parts_list"
        const val PLAYER_PLAYBACK_MODE_PARTS_LIST_THEN_RECOMMEND = "parts_list_then_recommend"
        const val PLAYER_PLAYBACK_MODE_RECOMMEND = "recommend"

        val PLAYER_SHORT_SEEK_STEP_SECONDS_OPTIONS: Set<Int> = linkedSetOf(3, 5, 8, 10, 15, 20)
        const val PLAYER_SHORT_SEEK_STEP_SECONDS_DEFAULT = 10

        const val PLAYER_HOLD_SEEK_MODE_SPEED = "speed"
        const val PLAYER_HOLD_SEEK_MODE_SCRUB = "scrub"
        const val PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME = "scrub_fixed_time"
        const val PLAYER_HOLD_SEEK_SPEED_DEFAULT = 3.0f
        val PLAYER_HOLD_SCRUB_SECONDS_OPTIONS: Set<Int> = linkedSetOf(5, 8, 10, 12, 15, 17, 20, 22, 25, 27, 30)
        const val PLAYER_HOLD_SCRUB_SECONDS_DEFAULT = 10

        const val DEFAULT_PLAYER_AUTO_SKIP_SERVER_BASE_URL = "https://bsbsb.top"
        const val FALLBACK_PLAYER_AUTO_SKIP_SERVER_BASE_URL = "http://154.222.28.109"

        const val PLAYER_VIDEOSHOT_PREVIEW_SIZE_OFF = "off"
        const val PLAYER_VIDEOSHOT_PREVIEW_SIZE_SMALL = "small"
        const val PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM = "medium"
        const val PLAYER_VIDEOSHOT_PREVIEW_SIZE_LARGE = "large"

        private const val SUBTITLE_BOTTOM_PADDING_FRACTION_DEFAULT = 0.16f
        private const val SUBTITLE_BACKGROUND_OPACITY_DEFAULT = 34f / 255f

        const val PLAYER_OSD_BTN_PREV = "prev"
        const val PLAYER_OSD_BTN_PLAY_PAUSE = "play_pause"
        const val PLAYER_OSD_BTN_NEXT = "next"
        const val PLAYER_OSD_BTN_SUBTITLE = "subtitle"
        const val PLAYER_OSD_BTN_DANMAKU = "danmaku"
        const val PLAYER_OSD_BTN_COMMENTS = "comments"
        const val PLAYER_OSD_BTN_DETAIL = "detail"
        const val PLAYER_OSD_BTN_UP = "up"
        const val PLAYER_OSD_BTN_LIKE = "like"
        const val PLAYER_OSD_BTN_COIN = "coin"
        const val PLAYER_OSD_BTN_FAV = "fav"
        const val PLAYER_OSD_BTN_LIST_PANEL = "list_panel"
        const val PLAYER_OSD_BTN_ADVANCED = "advanced"

        val DEFAULT_PLAYER_OSD_BUTTONS: List<String> =
            listOf(
                PLAYER_OSD_BTN_PLAY_PAUSE,
                PLAYER_OSD_BTN_NEXT,
                PLAYER_OSD_BTN_SUBTITLE,
                PLAYER_OSD_BTN_DANMAKU,
                PLAYER_OSD_BTN_COMMENTS,
                PLAYER_OSD_BTN_DETAIL,
                PLAYER_OSD_BTN_UP,
                PLAYER_OSD_BTN_LIST_PANEL,
                PLAYER_OSD_BTN_ADVANCED,
            )

        private val PLAYER_OSD_BUTTON_KEYS: Set<String> =
            setOf(
                PLAYER_OSD_BTN_PREV,
                PLAYER_OSD_BTN_PLAY_PAUSE,
                PLAYER_OSD_BTN_NEXT,
                PLAYER_OSD_BTN_SUBTITLE,
                PLAYER_OSD_BTN_DANMAKU,
                PLAYER_OSD_BTN_COMMENTS,
                PLAYER_OSD_BTN_DETAIL,
                PLAYER_OSD_BTN_UP,
                PLAYER_OSD_BTN_LIKE,
                PLAYER_OSD_BTN_COIN,
                PLAYER_OSD_BTN_FAV,
                PLAYER_OSD_BTN_LIST_PANEL,
                PLAYER_OSD_BTN_ADVANCED,
            )

        const val PLAYER_DOWN_KEY_OSD_FOCUS_PREV = "prev"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE = "play_pause"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_NEXT = "next"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_SUBTITLE = "subtitle"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_DANMAKU = "danmaku"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_COMMENTS = "comments"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_DETAIL = "detail"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_UP = "up"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_LIKE = "like"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_COIN = "coin"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_FAV = "fav"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_LIST_PANEL = "list_panel"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_ADVANCED = "advanced"

        private const val PLAYER_DOWN_KEY_OSD_FOCUS_RECOMMEND_LEGACY = "recommend"
        private const val PLAYER_DOWN_KEY_OSD_FOCUS_PLAYLIST_LEGACY = "playlist"

        private fun generateBuvid(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            val md5 = java.security.MessageDigest.getInstance("MD5").digest(bytes)
            val hex = buildString(md5.size * 2) { md5.forEach { append(String.format(java.util.Locale.US, "%02x", it)) } }
            return "XY${hex[2]}${hex[12]}${hex[22]}$hex"
        }

        private fun isValidUuid(text: String): Boolean {
            return runCatching {
                UUID.fromString(text.trim())
                true
            }.getOrDefault(false)
        }

        fun normalizePlayerAutoSkipServerBaseUrl(raw: String?): String? {
            val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val url = value.toHttpUrlOrNull() ?: return null
            if (url.query != null || url.fragment != null) return null
            return url.toString().trimEnd('/')
        }

        fun normalizeThemePreset(value: String?): String {
            return when (value?.trim()) {
                THEME_PRESET_TV_PINK -> THEME_PRESET_TV_PINK
                THEME_PRESET_TV_PINK_ILLUSTRATION -> THEME_PRESET_TV_PINK_ILLUSTRATION
                else -> THEME_PRESET_DEFAULT
            }
        }
    }

    private fun deriveDeviceUuid(): String {
        val androidId =
            runCatching {
                Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        if (!androidId.isNullOrBlank()) {
            val name = "blbl:device_uuid:$androidId"
            return UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString()
        }

        return UUID.randomUUID().toString()
    }
}
