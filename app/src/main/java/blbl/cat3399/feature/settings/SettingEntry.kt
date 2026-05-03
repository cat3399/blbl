package blbl.cat3399.feature.settings

/**
 * Stable id for settings entries.
 *
 * Must remain stable across copy/text changes; do NOT use [SettingEntry.title] to drive behavior.
 */
enum class SettingId(
    val key: String,
) {
    // 通用设置
    ImageQuality("image_quality"),
    ThemePreset("theme_preset"),
    UserAgent("user_agent"),
    Ipv4OnlyEnabled("ipv4_only_enabled"),
    GaiaVgate("gaia_vgate"),
    ClearCache("clear_cache"),
    ConfigTransfer("config_transfer"),
    ClearLogin("clear_login"),

    // 页面设置
    StartupPage("startup_page"),
    CustomPageEnabled("custom_page_enabled"),
    CustomPageContent("custom_page_content"),
    GridSpanCount("grid_span_count"),
    DynamicGridSpanCount("dynamic_grid_span_count"),
    PgcGridSpanCount("pgc_grid_span_count"),
    UiScaleFactor("ui_scale_factor"),
    FullscreenEnabled("fullscreen_enabled"),
    TabSwitchFollowsFocus("tab_switch_follows_focus"),
    MainAutoHideSidebarOnEnterContent("main_auto_hide_sidebar_on_enter_content"),
    MainBackFocusScheme("main_back_focus_scheme"),
    VideoCardLongPressAction("video_card_long_press_action"),
    FollowingListOrder("following_list_order"),
    MainHomeVisibleTabs("main_home_visible_tabs"),
    MainCategoryVisibleTabs("main_category_visible_tabs"),
    MainLiveVisibleTabs("main_live_visible_tabs"),
    MainMyVisibleTabs("main_my_visible_tabs"),

    // 播放设置
    PlayerPreferredQn("player_preferred_qn"),
    PlayerPreferredQnPortrait("player_preferred_qn_portrait"),
    PlayerPreferredAudioId("player_preferred_audio_id"),
    PlayerCdnPreference("player_cdn_preference"),
    LiveHighBitrateEnabled("live_high_bitrate_enabled"),
    PlayerSpeed("player_speed"),
    PlayerShortSeekStepSeconds("player_short_seek_step_seconds"),
    PlayerHoldSeekSpeed("player_hold_seek_speed"),
    PlayerHoldSeekMode("player_hold_seek_mode"),
    PlayerHoldScrubTraverseSeconds("player_hold_scrub_traverse_seconds"),
    PlayerHoldScrubFixedStepSeconds("player_hold_scrub_fixed_step_seconds"),
    PlayerAutoResumeEnabled("player_auto_resume_enabled"),
    PlayerAutoSkipSegmentsEnabled("player_auto_skip_segments_enabled"),
    PlayerAutoSkipServerBaseUrl("player_auto_skip_server_base_url"),
    PlayerOpenDetailBeforePlay("player_open_detail_before_play"),
    PlayerPlaybackMode("player_playback_mode"),
    PlayerSettingsApplyToGlobal("player_settings_apply_to_global"),
    PlayerUpQuickCardEnabled("player_up_quick_card_enabled"),
    PlayerStyle("player_style"),
    SubtitlePreferredLang("subtitle_preferred_lang"),
    SubtitleTextSizeSp("subtitle_text_size_sp"),
    SubtitleBottomPaddingFraction("subtitle_bottom_padding_fraction"),
    SubtitleBackgroundOpacity("subtitle_background_opacity"),
    SubtitleEnabledDefault("subtitle_enabled_default"),
    PlayerPreferredCodec("player_preferred_codec"),
    PlayerRenderView("player_render_view"),
    PlayerEngineKind("player_engine_kind"),
    PlayerAudioBalance("player_audio_balance"),
    PlayerOsdButtons("player_osd_buttons"),
    PlayerCustomShortcuts("player_custom_shortcuts"),
    PlayerDebugEnabled("player_debug_enabled"),
    DynamicFollowingRecentUpdateDotEnabled("dynamic_following_recent_update_dot_enabled"),
    PlayerDoubleBackToExit("player_double_back_to_exit"),
    PlayerDownKeyOsdFocusTarget("player_down_key_osd_focus_target"),
    PlayerTogglePlayStateShowOsd("player_toggle_play_state_show_osd"),
    PlayerPersistentBottomProgressEnabled("player_persistent_bottom_progress_enabled"),
    PlayerPersistentClockEnabled("player_persistent_clock_enabled"),
    PlayerTouchGesturesEnabled("player_touch_gestures_enabled"),
    PlayerVideoShotPreviewSize("player_videoshot_preview_size"),

    // 弹幕设置
    DanmakuEnabled("danmaku_enabled"),
    DanmakuOpacity("danmaku_opacity"),
    DanmakuTextSizeSp("danmaku_text_size_sp"),
    DanmakuLaneDensity("danmaku_lane_density"),
    DanmakuStrokeWidthPx("danmaku_stroke_width_px"),
    DanmakuFontWeight("danmaku_font_weight"),
    DanmakuArea("danmaku_area"),
    DanmakuSpeed("danmaku_speed"),
    DanmakuFollowBiliShield("danmaku_follow_bili_shield"),
    DanmakuShowHighLikeIcon("danmaku_show_high_like_icon"),
    DanmakuAiShieldEnabled("danmaku_ai_shield_enabled"),
    DanmakuAiShieldLevel("danmaku_ai_shield_level"),
    DanmakuAllowScroll("danmaku_allow_scroll"),
    DanmakuAllowTop("danmaku_allow_top"),
    DanmakuAllowBottom("danmaku_allow_bottom"),
    DanmakuAllowColor("danmaku_allow_color"),
    DanmakuAllowSpecial("danmaku_allow_special"),

    // 关于应用
    PlayerKernelCheck("player_kernel_check"),
    AppVersion("app_version"),
    ProjectUrl("project_url"),
    QqGroup("qq_group"),
    LogTag("log_tag"),
    ExportLogs("export_logs"),
    UploadLogs("upload_logs"),
    AutoUpdateCheckEnabled("auto_update_check_enabled"),
    CheckUpdate("check_update"),

    // 设备信息
    DeviceCpu("device_cpu"),
    DeviceModel("device_model"),
    DeviceSystem("device_system"),
    DeviceDecoder("device_decoder"),
    DeviceScreen("device_screen"),
    DeviceRam("device_ram"),

}

data class SettingEntry(
    val id: SettingId,
    val title: String,
    val value: String,
    val desc: String?,
)
