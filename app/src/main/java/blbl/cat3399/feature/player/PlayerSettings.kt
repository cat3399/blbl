@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player

import android.content.Intent
import android.util.TypedValue
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayerPlaybackModes
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.ExoPlayerEngine
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import java.util.Locale

internal object PlayerSettingKeys {
    const val PLAYER_ENGINE = "player_engine"
    const val RESOLUTION = "resolution"
    const val AUDIO_TRACK = "audio_track"
    const val CODEC = "codec"
    const val PLAYBACK_SPEED = "playback_speed"
    const val AUDIO_BALANCE = "audio_balance"
    const val PLAYBACK_MODE = "playback_mode"
    const val SUBTITLE_MENU = "subtitle_menu"
    const val SUBTITLE_ENABLED = "subtitle_enabled"
    const val SUBTITLE_LANG = "subtitle_lang"
    const val SUBTITLE_TEXT_SIZE = "subtitle_text_size"
    const val SUBTITLE_BOTTOM_PADDING = "subtitle_bottom_padding"
    const val SUBTITLE_BACKGROUND_OPACITY = "subtitle_background_opacity"
    const val DANMAKU_MENU = "danmaku_menu"
    const val DANMAKU_ENABLED = "danmaku_enabled"
    const val DANMAKU_SPEED = "danmaku_speed"
    const val DANMAKU_OPACITY = "danmaku_opacity"
    const val DANMAKU_TEXT_SIZE = "danmaku_text_size"
    const val DANMAKU_AREA = "danmaku_area"
    const val DANMAKU_STROKE_WIDTH = "danmaku_stroke_width"
    const val DANMAKU_FONT_WEIGHT = "danmaku_font_weight"
    const val DANMAKU_LANE_DENSITY = "danmaku_lane_density"
    const val DANMAKU_FOLLOW_BILI_SHIELD = "danmaku_follow_bili_shield"
    const val DANMAKU_SHOW_HIGH_LIKE_ICON = "danmaku_show_high_like_icon"
    const val DANMAKU_AI_SHIELD_ENABLED = "danmaku_ai_shield_enabled"
    const val DANMAKU_AI_SHIELD_LEVEL = "danmaku_ai_shield_level"
    const val DANMAKU_ALLOW_SCROLL = "danmaku_allow_scroll"
    const val DANMAKU_ALLOW_TOP = "danmaku_allow_top"
    const val DANMAKU_ALLOW_BOTTOM = "danmaku_allow_bottom"
    const val DANMAKU_ALLOW_COLOR = "danmaku_allow_color"
    const val DANMAKU_ALLOW_SPECIAL = "danmaku_allow_special"
    const val DEBUG_INFO = "debug_info"
    const val PERSISTENT_BOTTOM_PROGRESS = "persistent_bottom_progress"
    const val PERSISTENT_CLOCK = "persistent_clock"
}

internal enum class PlayerSettingsMenu {
    ROOT,
    SUBTITLE,
    DANMAKU,
}

private fun settingItem(
    key: String,
    title: String,
    subtitle: String? = null,
): PlayerSettingsAdapter.SettingItem = PlayerSettingsAdapter.SettingItem(key = key, title = title, subtitle = subtitle)

private fun Boolean.switchText(): String = if (this) "开" else "关"

private fun PlayerActivity.shouldPersistPlayerSettingsToGlobal(): Boolean = BiliClient.prefs.playerSettingsApplyToGlobal

internal fun <T> PlayerActivity.applySessionSettingValue(
    value: T,
    updateSession: PlayerSessionSettings.(T) -> PlayerSessionSettings,
    syncToGlobal: AppPrefs.(T) -> Unit = {},
    afterApplied: PlayerActivity.(T) -> Unit = {},
    refreshSettingsPanel: Boolean = true,
) {
    session = session.updateSession(value)
    if (shouldPersistPlayerSettingsToGlobal()) {
        BiliClient.prefs.syncToGlobal(value)
    }
    afterApplied(value)
    if (refreshSettingsPanel) {
        refreshSettingsPanel()
    }
}

internal fun <T> PlayerActivity.applyDanmakuSettingValue(
    value: T,
    updateDanmaku: DanmakuSessionSettings.(T) -> DanmakuSessionSettings,
    syncToGlobal: AppPrefs.(T) -> Unit = {},
    afterApplied: PlayerActivity.(T) -> Unit = {},
    refreshSettingsPanel: Boolean = true,
) {
    applySessionSettingValue(
        value = value,
        updateSession = { nextValue -> copy(danmaku = danmaku.updateDanmaku(nextValue)) },
        syncToGlobal = syncToGlobal,
        afterApplied = afterApplied,
        refreshSettingsPanel = refreshSettingsPanel,
    )
}

internal fun PlayerActivity.toggleSessionSettingFlag(
    current: Boolean,
    updateSession: PlayerSessionSettings.(Boolean) -> PlayerSessionSettings,
    syncToGlobal: AppPrefs.(Boolean) -> Unit = {},
    afterApplied: PlayerActivity.(Boolean) -> Unit = {},
    refreshSettingsPanel: Boolean = true,
) {
    applySessionSettingValue(
        value = !current,
        updateSession = updateSession,
        syncToGlobal = syncToGlobal,
        afterApplied = afterApplied,
        refreshSettingsPanel = refreshSettingsPanel,
    )
}

internal fun PlayerActivity.toggleDanmakuReloadSettingFlag(
    current: Boolean,
    updateDanmaku: DanmakuSessionSettings.(Boolean) -> DanmakuSessionSettings,
    syncToGlobal: AppPrefs.(Boolean) -> Unit = {},
) {
    applyDanmakuSettingValue(
        value = !current,
        updateDanmaku = updateDanmaku,
        syncToGlobal = syncToGlobal,
        afterApplied = { reloadDanmakuForCurrentSession() },
    )
}

private fun PlayerActivity.persistResolutionPreference(prefs: AppPrefs, qn: Int) {
    if (isPgcLikePlayback()) {
        prefs.playerPreferredQnPgc = qn
        return
    }
    when (currentVideoIsPortrait) {
        true -> prefs.playerPreferredQnPortrait = qn
        false -> prefs.playerPreferredQn = qn
        null -> Unit
    }
}

internal fun PlayerActivity.defaultSubtitleLangCode(): String {
    return BiliClient.prefs.subtitlePreferredLang
        .trim()
        .ifBlank { "auto" }
}

internal fun PlayerActivity.selectedResolutionQn(): Int {
    return session.actualQn.takeIf { it > 0 }
        ?: session.targetQn.takeIf { it > 0 }
        ?: session.preferredQn
}

internal fun PlayerActivity.selectedAudioTrackId(): Int {
    return session.actualAudioId.takeIf { it > 0 }
        ?: session.targetAudioId.takeIf { it > 0 }
        ?: session.preferAudioId
}

internal fun PlayerActivity.resolvedSubtitleLangCode(): String {
    return (session.subtitleLangOverride ?: defaultSubtitleLangCode())
        .trim()
        .ifBlank { "auto" }
}

internal fun PlayerActivity.applyResolutionSetting(qn: Int) {
    session =
        if (shouldPersistPlayerSettingsToGlobal() && currentVideoIsPortrait != null) {
            session.copy(preferredQn = qn, targetQn = 0)
        } else if (qn == session.preferredQn) {
            session.copy(targetQn = 0)
        } else {
            session.copy(targetQn = qn)
        }
    if (shouldPersistPlayerSettingsToGlobal()) {
        persistResolutionPreference(BiliClient.prefs, qn)
    }
    val engine = player
    pendingResolutionSuccessHintQn = qn.takeIf { it != session.actualQn }
    AppLog.i(
        "QualitySwitch",
        "request qn=$qn actual=${session.actualQn} target=${session.targetQn} preferred=${session.preferredQn} " +
            "engine=${engine?.kind?.prefValue ?: "null"} seamlessSource=${engine?.isSeamlessQualitySource == true} " +
            "prefEnabled=${session.seamlessQualitySwitchEnabled} runtimeDisabled=$seamlessQualitySwitchDisabledForPlayback " +
            "pos=${engine?.currentPosition ?: -1L} buffered=${engine?.bufferedPosition ?: -1L}",
    )
    val accepted = engine?.selectVideoQuality(qn) == true
    AppLog.i("QualitySwitch", "selection result qn=$qn accepted=$accepted seamlessSource=${engine?.isSeamlessQualitySource == true}")
    if (accepted) {
        refreshSettingsPanel()
        return
    }
    if (engine?.isSeamlessQualitySource == true) {
        pendingResolutionSuccessHintQn = null
        AppLog.w("QualitySwitch", "selection failed qn=$qn; keep current seamless track")
        showSeekHint("切换失败", hold = false)
        refreshSettingsPanel()
        return
    }
    reloadStream(keepPosition = true, reason = "quality_select_rejected_qn_$qn")
    refreshSettingsPanel()
}

internal fun PlayerActivity.applyAudioTrackSetting(id: Int) {
    session =
        if (shouldPersistPlayerSettingsToGlobal()) {
            session.copy(preferAudioId = id, targetAudioId = 0)
        } else if (id == session.preferAudioId) {
            session.copy(targetAudioId = 0)
        } else {
            session.copy(targetAudioId = id)
        }
    if (shouldPersistPlayerSettingsToGlobal()) {
        BiliClient.prefs.playerPreferredAudioId = id
    }
    reloadStream(keepPosition = true)
    refreshSettingsPanel()
}

internal fun PlayerActivity.applyPlaybackModeSetting(
    pickedCode: String,
    engine: BlblPlayerEngine,
) {
    val normalized = PlayerPlaybackModes.normalize(pickedCode)
    val defaultCode =
        if (shouldPersistPlayerSettingsToGlobal() && !isPgcLikePlayback()) {
            normalized
        } else {
            defaultPlaybackModeCode()
        }
    session =
        if (normalized == defaultCode) {
            session.copy(playbackModeOverride = null)
        } else {
            session.copy(playbackModeOverride = normalized)
        }
    if (shouldPersistPlayerSettingsToGlobal()) {
        BiliClient.prefs.playerPlaybackMode = normalized
    }
    applyPlaybackMode(engine)
    updatePlaylistControls()
    refreshSettingsPanel()
}

private fun PlayerActivity.applyPlayerEngineSetting(picked: PlayerEngineKind) {
    applySessionSettingValue(
        value = picked,
        updateSession = { copy(engineKind = it) },
        syncToGlobal = { playerEngineKind = it.prefValue },
        afterApplied = { restartForEngineSwitch(it) },
        refreshSettingsPanel = false,
    )
}

internal fun PlayerActivity.applySubtitleEnabledSetting(
    enabled: Boolean,
    exo: ExoPlayer,
) {
    applySessionSettingValue(
        value = enabled,
        updateSession = { copy(subtitleEnabled = it) },
        syncToGlobal = { subtitleEnabledDefault = it },
        afterApplied = {
            applySubtitleEnabled(exo)
            updateSubtitleButton()
        },
    )
}

internal fun PlayerActivity.applySubtitleLanguageSetting(
    pickedCode: String,
    exo: ExoPlayer,
) {
    val normalized = pickedCode.trim().ifBlank { "auto" }
    val defaultCode = defaultSubtitleLangCode()
    session =
        if (shouldPersistPlayerSettingsToGlobal() || normalized.equals(defaultCode, ignoreCase = true)) {
            session.copy(subtitleLangOverride = null)
        } else {
            session.copy(subtitleLangOverride = normalized)
        }
    if (shouldPersistPlayerSettingsToGlobal()) {
        BiliClient.prefs.subtitlePreferredLang = normalized
    }
    refreshSettingsPanel()
    lifecycleScope.launch {
        subtitleConfig = buildSubtitleConfigFromCurrentSelection(bvid = currentBvid, cid = currentCid)
        subtitleAvailabilityKnown = true
        subtitleAvailable = subtitleConfig != null
        applySubtitleEnabled(exo)
        updateSubtitleButton()
        refreshSettingsPanel()
        reloadStream(keepPosition = true)
    }
}

internal fun PlayerActivity.applyDanmakuEnabledSetting(enabled: Boolean) {
    applyDanmakuSettingValue(
        value = enabled,
        updateDanmaku = { copy(enabled = it) },
        syncToGlobal = { danmakuEnabled = it },
        afterApplied = { nextEnabled ->
            binding.danmakuView.invalidate()
            updateDanmakuButton()
            if (nextEnabled) {
                val positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
                requestDanmakuSegmentsForPosition(positionMs, immediate = true)
            }
        },
    )
}

internal fun PlayerActivity.handleSettingsItemClick(item: PlayerSettingsAdapter.SettingItem) {
    when (item.key) {
        PlayerSettingKeys.PLAYER_ENGINE -> showPlayerEngineDialog()
        PlayerSettingKeys.RESOLUTION -> showResolutionDialog()
        PlayerSettingKeys.AUDIO_TRACK -> showAudioDialog()
        PlayerSettingKeys.CODEC -> showCodecDialog()
        PlayerSettingKeys.PLAYBACK_SPEED -> showSpeedDialog()
        PlayerSettingKeys.AUDIO_BALANCE -> showAudioBalanceDialog()
        PlayerSettingKeys.PLAYBACK_MODE -> showPlaybackModeDialog()
        PlayerSettingKeys.SUBTITLE_MENU -> showSubtitleSettingsMenu()
        PlayerSettingKeys.SUBTITLE_ENABLED -> {
            val exo = (player as? ExoPlayerEngine)?.exoPlayer
            if (exo == null) {
                AppToast.show(this, "当前播放器内核不支持字幕")
                return
            }
            toggleSubtitles(exo)
        }

        PlayerSettingKeys.SUBTITLE_LANG -> showSubtitleLangDialog()
        PlayerSettingKeys.SUBTITLE_TEXT_SIZE -> showSubtitleTextSizeDialog()
        PlayerSettingKeys.SUBTITLE_BOTTOM_PADDING -> showSubtitleBottomPaddingDialog()
        PlayerSettingKeys.SUBTITLE_BACKGROUND_OPACITY -> showSubtitleBackgroundOpacityDialog()
        PlayerSettingKeys.DANMAKU_MENU -> showDanmakuSettingsMenu()
        PlayerSettingKeys.DANMAKU_ENABLED -> setDanmakuEnabled(!session.danmaku.enabled)

        PlayerSettingKeys.DANMAKU_SPEED -> showDanmakuSpeedDialog()
        PlayerSettingKeys.DANMAKU_OPACITY -> showDanmakuOpacityDialog()
        PlayerSettingKeys.DANMAKU_TEXT_SIZE -> showDanmakuTextSizeDialog()
        PlayerSettingKeys.DANMAKU_AREA -> showDanmakuAreaDialog()
        PlayerSettingKeys.DANMAKU_STROKE_WIDTH -> showDanmakuStrokeWidthDialog()
        PlayerSettingKeys.DANMAKU_FONT_WEIGHT -> showDanmakuFontWeightDialog()
        PlayerSettingKeys.DANMAKU_LANE_DENSITY -> showDanmakuLaneDensityDialog()
        PlayerSettingKeys.DANMAKU_FOLLOW_BILI_SHIELD ->
            toggleDanmakuReloadSettingFlag(
                current = session.danmaku.followBiliShield,
                updateDanmaku = { copy(followBiliShield = it) },
                syncToGlobal = { danmakuFollowBiliShield = it },
            )
        PlayerSettingKeys.DANMAKU_SHOW_HIGH_LIKE_ICON ->
            applyDanmakuSettingValue(
                value = !session.danmaku.showHighLikeIcon,
                updateDanmaku = { copy(showHighLikeIcon = it) },
                syncToGlobal = { danmakuShowHighLikeIcon = it },
                afterApplied = { binding.danmakuView.invalidate() },
            )
        PlayerSettingKeys.DANMAKU_AI_SHIELD_ENABLED ->
            toggleDanmakuReloadSettingFlag(
                current = session.danmaku.aiShieldEnabled,
                updateDanmaku = { copy(aiShieldEnabled = it) },
                syncToGlobal = { danmakuAiShieldEnabled = it },
            )
        PlayerSettingKeys.DANMAKU_AI_SHIELD_LEVEL -> showDanmakuAiShieldLevelDialog()
        PlayerSettingKeys.DANMAKU_ALLOW_SCROLL ->
            toggleDanmakuReloadSettingFlag(
                current = session.danmaku.allowScroll,
                updateDanmaku = { copy(allowScroll = it) },
                syncToGlobal = { danmakuAllowScroll = it },
            )
        PlayerSettingKeys.DANMAKU_ALLOW_TOP ->
            toggleDanmakuReloadSettingFlag(
                current = session.danmaku.allowTop,
                updateDanmaku = { copy(allowTop = it) },
                syncToGlobal = { danmakuAllowTop = it },
            )
        PlayerSettingKeys.DANMAKU_ALLOW_BOTTOM ->
            toggleDanmakuReloadSettingFlag(
                current = session.danmaku.allowBottom,
                updateDanmaku = { copy(allowBottom = it) },
                syncToGlobal = { danmakuAllowBottom = it },
            )
        PlayerSettingKeys.DANMAKU_ALLOW_COLOR ->
            toggleDanmakuReloadSettingFlag(
                current = session.danmaku.allowColor,
                updateDanmaku = { copy(allowColor = it) },
                syncToGlobal = { danmakuAllowColor = it },
            )
        PlayerSettingKeys.DANMAKU_ALLOW_SPECIAL ->
            toggleDanmakuReloadSettingFlag(
                current = session.danmaku.allowSpecial,
                updateDanmaku = { copy(allowSpecial = it) },
                syncToGlobal = { danmakuAllowSpecial = it },
            )
        PlayerSettingKeys.DEBUG_INFO ->
            toggleSessionSettingFlag(
                current = session.debugEnabled,
                updateSession = { copy(debugEnabled = it) },
                syncToGlobal = { playerDebugEnabled = it },
                afterApplied = { updateDebugOverlay() },
            )
        PlayerSettingKeys.PERSISTENT_BOTTOM_PROGRESS ->
            toggleSessionSettingFlag(
                current = session.persistentBottomProgressEnabled,
                updateSession = { copy(persistentBottomProgressEnabled = it) },
                syncToGlobal = { playerPersistentBottomProgressEnabled = it },
                afterApplied = { updatePersistentBottomProgressBarVisibility() },
            )

        PlayerSettingKeys.PERSISTENT_CLOCK -> {
            val appPrefs = BiliClient.prefs
            appPrefs.playerPersistentClockEnabled = !appPrefs.playerPersistentClockEnabled
            updateClockVisibility()
            refreshSettingsPanel()
        }

        else -> AppToast.show(this, "暂未实现：${item.title}")
    }
}

internal fun PlayerActivity.showSettingsRoot(focusKey: String? = null) {
    settingsPanelMenu = PlayerSettingsMenu.ROOT
    (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it, focusKey) }
}

internal fun PlayerActivity.showSubtitleSettingsMenu() {
    settingsPanelMenu = PlayerSettingsMenu.SUBTITLE
    (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let {
        refreshSettings(it, PlayerSettingKeys.SUBTITLE_ENABLED)
    }
}

internal fun PlayerActivity.showDanmakuSettingsMenu() {
    settingsPanelMenu = PlayerSettingsMenu.DANMAKU
    (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let {
        refreshSettings(it, PlayerSettingKeys.DANMAKU_ENABLED)
    }
}

internal fun PlayerActivity.backFromSettingsSubmenu(): Boolean {
    return when (settingsPanelMenu) {
        PlayerSettingsMenu.SUBTITLE -> {
            showSettingsRoot(PlayerSettingKeys.SUBTITLE_MENU)
            true
        }

        PlayerSettingsMenu.DANMAKU -> {
            showSettingsRoot(PlayerSettingKeys.DANMAKU_MENU)
            true
        }

        PlayerSettingsMenu.ROOT -> false
    }
}

internal fun PlayerActivity.refreshSettings(
    adapter: PlayerSettingsAdapter,
    preferredFocusKey: String? = null,
) {
    val subtitleSupported = player?.capabilities?.subtitlesSupported == true
    val menu =
        when (settingsPanelMenu) {
            PlayerSettingsMenu.SUBTITLE -> {
                if (subtitleSupported) {
                    PlayerSettingsMenu.SUBTITLE
                } else {
                    settingsPanelMenu = PlayerSettingsMenu.ROOT
                    PlayerSettingsMenu.ROOT
                }
            }

            PlayerSettingsMenu.DANMAKU -> PlayerSettingsMenu.DANMAKU
            PlayerSettingsMenu.ROOT -> PlayerSettingsMenu.ROOT
        }
    val restoreFocusKey = preferredFocusKey ?: currentSettingsFocusKey()
    val items =
        when (menu) {
            PlayerSettingsMenu.ROOT -> buildRootSettingsItems(subtitleSupported = subtitleSupported)
            PlayerSettingsMenu.SUBTITLE -> buildSubtitleSettingsItems()
            PlayerSettingsMenu.DANMAKU -> buildDanmakuSettingsItems()
        }
    adapter.submit(
        items,
        onCommitted = {
            if (restoreFocusKey != null && restoreSettingsPanelFocusByKey(restoreFocusKey)) {
                return@submit
            }
            if (restoreFocusKey != null && isSettingsPanelVisible()) {
                focusSettingsPanel()
            }
        },
    )
}

private fun PlayerActivity.buildRootSettingsItems(
    subtitleSupported: Boolean,
): List<PlayerSettingsAdapter.SettingItem> {
    return listOfNotNull(
        settingItem(PlayerSettingKeys.RESOLUTION, "分辨率", resolutionSubtitle()),
        settingItem(PlayerSettingKeys.AUDIO_TRACK, "音轨", audioSubtitle()),
        settingItem(PlayerSettingKeys.CODEC, "视频编码", session.preferCodec),
        settingItem(PlayerSettingKeys.PLAYBACK_SPEED, "播放速度", String.format(Locale.US, "%.2fx", session.playbackSpeed)),
        settingItem(PlayerSettingKeys.PLAYBACK_MODE, "播放模式", playbackModeSubtitle()),
        subtitleSupported.takeIf { it }?.let { settingItem(PlayerSettingKeys.SUBTITLE_MENU, "字幕设置", ">") },
        settingItem(PlayerSettingKeys.DANMAKU_MENU, "弹幕设置", ">"),
        settingItem(PlayerSettingKeys.AUDIO_BALANCE, "音频平衡", session.audioBalanceLevel.label),
        settingItem(
            PlayerSettingKeys.PERSISTENT_BOTTOM_PROGRESS,
            "底部常驻进度条",
            session.persistentBottomProgressEnabled.switchText(),
        ),
        settingItem(
            PlayerSettingKeys.PERSISTENT_CLOCK,
            "常驻时间显示",
            BiliClient.prefs.playerPersistentClockEnabled.switchText(),
        ),
        settingItem(PlayerSettingKeys.PLAYER_ENGINE, "播放器内核", playerEngineSubtitle()),
        settingItem(PlayerSettingKeys.DEBUG_INFO, "调试信息", session.debugEnabled.switchText()),
    )
}

private fun PlayerActivity.buildSubtitleSettingsItems(): List<PlayerSettingsAdapter.SettingItem> {
    return listOf(
        settingItem(PlayerSettingKeys.SUBTITLE_ENABLED, "字幕开关", session.subtitleEnabled.switchText()),
        settingItem(PlayerSettingKeys.SUBTITLE_LANG, "字幕语言", subtitleLangSubtitle()),
        settingItem(PlayerSettingKeys.SUBTITLE_TEXT_SIZE, "字幕字体大小", session.subtitleTextSizeSp.toInt().toString()),
        settingItem(
            PlayerSettingKeys.SUBTITLE_BOTTOM_PADDING,
            "字幕底部间距",
            subtitleBottomPaddingText(session.subtitleBottomPaddingFraction),
        ),
        settingItem(
            PlayerSettingKeys.SUBTITLE_BACKGROUND_OPACITY,
            "字幕背景透明度",
            subtitleBackgroundOpacityText(session.subtitleBackgroundOpacity),
        ),
    )
}

private fun PlayerActivity.buildDanmakuSettingsItems(): List<PlayerSettingsAdapter.SettingItem> {
    return listOf(
        settingItem(PlayerSettingKeys.DANMAKU_ENABLED, "弹幕开关", session.danmaku.enabled.switchText()),
        settingItem(PlayerSettingKeys.DANMAKU_SPEED, "弹幕速度", session.danmaku.speedLevel.toString()),
        settingItem(PlayerSettingKeys.DANMAKU_OPACITY, "弹幕透明度", String.format(Locale.US, "%.2f", session.danmaku.opacity)),
        settingItem(PlayerSettingKeys.DANMAKU_TEXT_SIZE, "弹幕字体大小", session.danmaku.textSizeSp.toInt().toString()),
        settingItem(PlayerSettingKeys.DANMAKU_AREA, "弹幕占屏比", areaText(session.danmaku.area)),
        settingItem(PlayerSettingKeys.DANMAKU_STROKE_WIDTH, "弹幕文字描边粗细", session.danmaku.strokeWidthPx.toString()),
        settingItem(PlayerSettingKeys.DANMAKU_FONT_WEIGHT, "字体粗细", danmakuFontWeightText(session.danmaku.fontWeight)),
        settingItem(PlayerSettingKeys.DANMAKU_LANE_DENSITY, "轨道密度", danmakuLaneDensityText(session.danmaku.laneDensity)),
        settingItem(PlayerSettingKeys.DANMAKU_FOLLOW_BILI_SHIELD, "跟随B站弹幕屏蔽", session.danmaku.followBiliShield.switchText()),
        settingItem(PlayerSettingKeys.DANMAKU_SHOW_HIGH_LIKE_ICON, "显示高赞弹幕图标", session.danmaku.showHighLikeIcon.switchText()),
        settingItem(PlayerSettingKeys.DANMAKU_AI_SHIELD_ENABLED, "智能云屏蔽", session.danmaku.aiShieldEnabled.switchText()),
        settingItem(PlayerSettingKeys.DANMAKU_AI_SHIELD_LEVEL, "智能云屏蔽等级", aiLevelText(session.danmaku.aiShieldLevel)),
        settingItem(PlayerSettingKeys.DANMAKU_ALLOW_SCROLL, "允许滚动弹幕", session.danmaku.allowScroll.switchText()),
        settingItem(PlayerSettingKeys.DANMAKU_ALLOW_TOP, "允许顶部悬停弹幕", session.danmaku.allowTop.switchText()),
        settingItem(PlayerSettingKeys.DANMAKU_ALLOW_BOTTOM, "允许底部悬停弹幕", session.danmaku.allowBottom.switchText()),
        settingItem(PlayerSettingKeys.DANMAKU_ALLOW_COLOR, "允许彩色弹幕", session.danmaku.allowColor.switchText()),
        settingItem(PlayerSettingKeys.DANMAKU_ALLOW_SPECIAL, "允许特殊弹幕", session.danmaku.allowSpecial.switchText()),
    )
}

private fun PlayerActivity.playerEngineSubtitle(): String {
    val kind = player?.kind ?: session.engineKind
    return when (kind) {
        PlayerEngineKind.IjkPlayer -> "IjkPlayer"
        PlayerEngineKind.ExoPlayer -> "ExoPlayer"
    }
}

private fun PlayerActivity.restartForEngineSwitch(picked: PlayerEngineKind) {
    val engine = player ?: return

    val resumePosMs = engine.currentPosition.coerceAtLeast(0L)
    val resumePlayWhenReady = engine.playWhenReady
    val sessionJson = session.copy(engineKind = picked).toEngineSwitchJsonString()

    val restart =
        Intent(this, PlayerActivity::class.java).apply {
            val bvid = currentBvid.takeIf { it.isNotBlank() } ?: intent.getStringExtra(PlayerActivity.EXTRA_BVID).orEmpty()
            if (bvid.isNotBlank()) putExtra(PlayerActivity.EXTRA_BVID, bvid)
            val cid = currentCid.takeIf { it > 0L } ?: intent.getLongExtra(PlayerActivity.EXTRA_CID, -1L).takeIf { it > 0L }
            if (cid != null) putExtra(PlayerActivity.EXTRA_CID, cid)
            currentEpId?.takeIf { it > 0L }?.let { putExtra(PlayerActivity.EXTRA_EP_ID, it) }
            currentAid?.takeIf { it > 0L }?.let { putExtra(PlayerActivity.EXTRA_AID, it) }
            pageListToken?.takeIf { it.isNotBlank() }?.let { putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, it) }
            pageListIndex.takeIf { it >= 0 }?.let { putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, it) }
            putExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_POSITION_MS, resumePosMs)
            putExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_PLAY_WHEN_READY, resumePlayWhenReady)
            putExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_SESSION_JSON, sessionJson)
        }

    startActivity(restart)
    finish()
}

internal fun PlayerActivity.showPlayerEngineDialog() {
    val currentKind = player?.kind ?: session.engineKind
    val items = listOf("ExoPlayer", "IjkPlayer")
    val checked = if (currentKind == PlayerEngineKind.IjkPlayer) 1 else 0
    showSettingsSingleChoiceDialog(
        title = "播放器内核",
        items = items,
        checkedIndex = checked,
    ) { which, _ ->
        val picked = if (which == 1) PlayerEngineKind.IjkPlayer else PlayerEngineKind.ExoPlayer
        if (picked == currentKind) return@showSettingsSingleChoiceDialog
        if (picked == PlayerEngineKind.IjkPlayer) {
            IjkPlayerPluginUi.ensureInstalled(this) {
                applyPlayerEngineSetting(picked)
            }
        } else {
            applyPlayerEngineSetting(picked)
        }
    }
}

private fun PlayerActivity.currentSettingsFocusKey(): String? {
    if (!isSettingsPanelVisible()) return null
    val focused = currentFocus ?: return null
    val holder = binding.recyclerSettings.findContainingViewHolder(focused) ?: return null
    val pos = holder.bindingAdapterPosition
    if (pos == RecyclerView.NO_POSITION) return null
    val adapter = binding.recyclerSettings.adapter as? PlayerSettingsAdapter ?: return null
    return adapter.currentList.getOrNull(pos)?.key
}

private fun PlayerActivity.restoreSettingsPanelFocusByKey(key: String): Boolean {
    if (!isSettingsPanelVisible()) return false
    val rv = binding.recyclerSettings
    val adapter = rv.adapter as? PlayerSettingsAdapter ?: return false
    val targetPos = adapter.currentList.indexOfFirst { it.key == key }
    if (targetPos !in 0 until adapter.itemCount) return false

    fun requestFocus(view: View?): Boolean {
        val v = view ?: return false
        if (!v.isAttachedToWindow || !v.isShown || !v.isEnabled || !v.isFocusable) return false
        return v.requestFocus()
    }

    val direct = rv.findViewHolderForAdapterPosition(targetPos)?.itemView
    if (requestFocus(direct)) return true

    rv.scrollToPosition(targetPos)
    rv.post {
        requestFocus(rv.findViewHolderForAdapterPosition(targetPos)?.itemView)
    }
    return true
}

private inline fun PlayerActivity.showSettingsSingleChoiceDialog(
    title: CharSequence,
    items: List<String>,
    checkedIndex: Int,
    crossinline onPicked: (index: Int, label: String) -> Unit,
) {
    val restoreFocusKey = currentSettingsFocusKey()
    AppPopup.singleChoice(
        context = this,
        title = title,
        items = items,
        checkedIndex = checkedIndex,
        onRestoreFocus = {
            val key = restoreFocusKey ?: return@singleChoice false
            restoreSettingsPanelFocusByKey(key)
        },
    ) { which, label ->
        onPicked(which, label)
    }
}

private inline fun <T> PlayerActivity.showSettingsChoiceDialog(
    title: CharSequence,
    options: List<T>,
    checkedIndex: Int,
    crossinline label: (T) -> String = { it.toString() },
    crossinline onPicked: (T) -> Unit,
) {
    showSettingsSingleChoiceDialog(
        title = title,
        items = options.map(label),
        checkedIndex = checkedIndex,
    ) { which, _ ->
        options.getOrNull(which)?.let(onPicked)
    }
}

private fun PlayerActivity.refreshSettingsPanel() {
    (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
}

internal fun PlayerActivity.showResolutionDialog() {
    // Follow docs: qn list for resolution/framerate.
    // Keep the full list so user can force-pick even if the server later falls back.
    val docQns = PlaybackSettingChoices.resolutionQns
    val available = lastAvailableQns.toSet()
    val currentQn = selectedResolutionQn()
    val currentIndex = docQns.indexOfFirst { it == currentQn }.takeIf { it >= 0 } ?: 0
    showSettingsChoiceDialog(
        title = "分辨率",
        options = docQns,
        checkedIndex = currentIndex,
        label = { qn ->
            val text = qnLabel(qn)
            if (available.contains(qn)) "${text}（可用）" else text
        },
    ) { qn -> applyResolutionSetting(qn) }
}

internal fun PlayerActivity.showAudioDialog() {
    val docIds = PlaybackSettingChoices.audioTrackIds
    val available = lastAvailableAudioIds.toSet()
    val currentId = selectedAudioTrackId()
    val currentIndex = docIds.indexOfFirst { it == currentId }.takeIf { it >= 0 } ?: 0

    showSettingsChoiceDialog(
        title = "音轨",
        options = docIds,
        checkedIndex = currentIndex,
        label = { id ->
            val text = audioLabel(id)
            if (available.contains(id)) "${text}（可用）" else text
        },
    ) { id -> applyAudioTrackSetting(id) }
}

internal fun PlayerActivity.showCodecDialog() {
    val options = arrayOf("AVC", "HEVC", "AV1")
    val current = options.indexOf(session.preferCodec).coerceAtLeast(0)
    showSettingsChoiceDialog(
        title = "视频编码",
        options = options.toList(),
        checkedIndex = current,
    ) { selected ->
        applySessionSettingValue(
            value = selected,
            updateSession = { copy(preferCodec = it) },
            syncToGlobal = { playerPreferredCodec = it },
            afterApplied = { reloadStream(keepPosition = true) },
        )
    }
}

internal fun PlayerActivity.showSpeedDialog() {
    val options = PlaybackSettingChoices.playbackSpeeds
    val current = options.indexOf(session.playbackSpeed).takeIf { it >= 0 } ?: 2
    showSettingsChoiceDialog(
        title = "播放速度",
        options = options,
        checkedIndex = current,
        label = { String.format(Locale.US, "%.2fx", it) },
    ) { v ->
        applySessionSettingValue(
            value = v.coerceIn(0.25f, 4.0f),
            updateSession = { copy(playbackSpeed = it) },
            syncToGlobal = { playerSpeed = it },
            afterApplied = { player?.setPlaybackSpeed(it) },
        )
    }
}

internal fun PlayerActivity.showAudioBalanceDialog() {
    val options = AudioBalanceLevel.ordered
    val current = session.audioBalanceLevel
    val checked = options.indexOf(current).takeIf { it >= 0 } ?: 0

    showSettingsChoiceDialog(
        title = "音频平衡",
        options = options,
        checkedIndex = checked,
        label = AudioBalanceLevel::label,
    ) { picked ->
        applySessionSettingValue(
            value = picked,
            updateSession = { copy(audioBalanceLevel = it) },
            syncToGlobal = { playerAudioBalanceLevel = it.prefValue },
            afterApplied = { nextLevel ->
                val engine = player
                if (engine is ExoPlayerEngine) {
                    engine.setAudioBalanceLevel(nextLevel)
                    AppToast.show(this, "音频平衡：${nextLevel.label}")
                } else {
                    AppToast.show(this, "当前播放器内核不支持音频平衡")
                }
            },
        )
    }
}

internal fun PlayerActivity.isPgcLikePlayback(): Boolean {
    val epId = currentEpId
    if (epId != null && epId > 0L) return true
    val src = pageListSource?.trim().orEmpty()
    if (src.startsWith("Bangumi:")) return true
    return false
}

private fun PlayerActivity.defaultPlaybackModeCode(): String {
    val raw =
        if (isPgcLikePlayback()) {
            // PGC (番剧/影视) 默认按“播放合集/分P”处理，不受全局默认播放模式影响。
            AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST
        } else {
            BiliClient.prefs.playerPlaybackMode
        }
    return PlayerPlaybackModes.normalize(raw)
}

internal fun PlayerActivity.resolvedPlaybackMode(): String {
    val override = session.playbackModeOverride
    return PlayerPlaybackModes.normalize(override ?: defaultPlaybackModeCode())
}

internal fun PlayerActivity.playbackModeLabel(code: String): String = PlayerPlaybackModes.label(code)

internal fun PlayerActivity.playbackModeSubtitle(): String {
    return playbackModeLabel(resolvedPlaybackMode())
}

internal fun PlayerActivity.applyPlaybackMode(engine: BlblPlayerEngine) {
    engine.repeatMode =
        when (resolvedPlaybackMode()) {
            AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    clearAutoNextState(reason = "mode_change", resetUserCancellation = true)
    maybeWarmUpAutoNextTarget()
}

internal fun PlayerActivity.showPlaybackModeDialog() {
    val engine = player ?: return
    val modeCodes = PlayerPlaybackModes.ordered
    val checked = modeCodes.indexOf(resolvedPlaybackMode()).takeIf { it >= 0 } ?: 0
    showSettingsChoiceDialog(
        title = "播放模式",
        options = modeCodes,
        checkedIndex = checked,
        label = PlayerPlaybackModes::label,
    ) { pickedCode -> applyPlaybackModeSetting(pickedCode, engine) }
}

internal fun PlayerActivity.pickSubtitleItem(items: List<SubtitleItem>): SubtitleItem? {
    if (items.isEmpty()) return null
    val preferred = resolvedSubtitleLangCode()
    if (preferred == "auto" || preferred.isBlank()) return items.first()
    return items.firstOrNull { it.lan.equals(preferred, ignoreCase = true) } ?: items.first()
}

internal fun PlayerActivity.subtitleLangSubtitle(): String {
    if (subtitleItems.isEmpty()) return "无/未加载"
    return resolveSubtitleLang(resolvedSubtitleLangCode())
}

internal fun PlayerActivity.resolveSubtitleLang(code: String): String {
    if (subtitleItems.isEmpty()) return "无"
    if (code == "auto" || code.isBlank()) {
        val first = subtitleItems.first()
        return "自动：${first.lanDoc}"
    }
    val found = subtitleItems.firstOrNull { it.lan.equals(code, ignoreCase = true) } ?: subtitleItems.first()
    return "${found.lanDoc}"
}

internal fun PlayerActivity.showSubtitleLangDialog() {
    val exo = (player as? ExoPlayerEngine)?.exoPlayer
    if (exo == null) {
        AppToast.show(this, "当前播放器内核不支持字幕")
        return
    }
    if (subtitleItems.isEmpty()) {
        AppToast.show(this, "该视频暂无字幕")
        return
    }
    val autoLabel = "自动（取第一个）"
    val items =
        buildList {
            add(autoLabel)
            subtitleItems.forEach { add(it.lanDoc) }
        }
    val effective = resolvedSubtitleLangCode()
    val currentLabel =
        when {
            effective.equals("auto", ignoreCase = true) || effective.isBlank() -> autoLabel
            else -> subtitleItems.firstOrNull { it.lan.equals(effective, ignoreCase = true) }?.lanDoc ?: subtitleItems.first().lanDoc
        }
    val checked = items.indexOf(currentLabel).coerceAtLeast(0)
    showSettingsSingleChoiceDialog(
        title = "字幕语言",
        items = items,
        checkedIndex = checked,
    ) { which, _ ->
        val chosen = items.getOrNull(which).orEmpty()
        val pickedCode =
            when {
                chosen.startsWith("自动") -> "auto"
                else -> subtitleItems.firstOrNull { it.lanDoc == chosen }?.lan ?: subtitleItems.first().lan
            }
        applySubtitleLanguageSetting(pickedCode, exo)
    }
}

internal fun PlayerActivity.showSubtitleTextSizeDialog() {
    val options = PlaybackSettingChoices.subtitleTextSizes
    val current =
        options.indices.minByOrNull { abs(options[it].toFloat() - session.subtitleTextSizeSp) }
            ?: options.indexOf(26).takeIf { it >= 0 }
            ?: 0
    showSettingsChoiceDialog(
        title = "字幕字体大小",
        options = options,
        checkedIndex = current,
    ) { picked ->
        applySessionSettingValue(
            value = picked.toFloat().coerceIn(10f, 60f),
            updateSession = { copy(subtitleTextSizeSp = it) },
            syncToGlobal = { subtitleTextSizeSp = it },
            afterApplied = { applySubtitleTextSize() },
        )
    }
}

internal fun PlayerActivity.showSubtitleBottomPaddingDialog() {
    val options = PlaybackSettingChoices.subtitleBottomPaddingPercents
    val current =
        options.indices.minByOrNull { abs(options[it] / 100f - session.subtitleBottomPaddingFraction) }
            ?: options.indexOf(16).takeIf { it >= 0 }
            ?: 0
    showSettingsChoiceDialog(
        title = "字幕底部间距",
        options = options,
        checkedIndex = current,
        label = { "${it}%" },
    ) { percent ->
        applySessionSettingValue(
            value = (percent / 100f).coerceIn(0f, 0.30f),
            updateSession = { copy(subtitleBottomPaddingFraction = it) },
            syncToGlobal = { subtitleBottomPaddingFraction = it },
            afterApplied = { applySubtitleStyle() },
        )
    }
}

internal fun PlayerActivity.showSubtitleBackgroundOpacityDialog() {
    val ordered = PlaybackSettingChoices.subtitleBackgroundOpacities
    val current = ordered.indices.minByOrNull { abs(ordered[it] - session.subtitleBackgroundOpacity) } ?: 0
    showSettingsChoiceDialog(
        title = "字幕背景透明度",
        options = ordered,
        checkedIndex = current,
        label = { String.format(Locale.US, "%.2f", it) },
    ) { picked ->
        applySessionSettingValue(
            value = picked.coerceIn(0f, 1.0f),
            updateSession = { copy(subtitleBackgroundOpacity = it) },
            syncToGlobal = { subtitleBackgroundOpacity = it },
            afterApplied = { applySubtitleStyle() },
        )
    }
}

internal fun PlayerActivity.configureSubtitleView() {
    val subtitleView = binding.playerView.findViewById<SubtitleView>(androidx.media3.ui.R.id.exo_subtitles) ?: return
    applySubtitleStyle(subtitleView)
    applySubtitleTextSize()
}

internal fun PlayerActivity.applySubtitleStyle() {
    val subtitleView = binding.playerView.findViewById<SubtitleView>(androidx.media3.ui.R.id.exo_subtitles) ?: return
    applySubtitleStyle(subtitleView)
}

private fun PlayerActivity.applySubtitleStyle(subtitleView: SubtitleView) {
    val bottomPaddingFraction =
        session.subtitleBottomPaddingFraction
            .let { if (it.isFinite()) it else 0.16f }
            .coerceIn(0f, 0.30f)
    // Prefer padding-based positioning to work consistently across different cue defaults.
    // Keep SubtitleView's own bottomPaddingFraction at 0 to avoid double-applying spacing.
    subtitleView.setBottomPaddingFraction(0f)
    applySubtitlePaddingFraction(subtitleView, bottomPaddingFraction)

    val bgOpacity =
        session.subtitleBackgroundOpacity
            .let { if (it.isFinite()) it else (34f / 255f) }
            .coerceIn(0f, 1.0f)
    val alpha = (bgOpacity * 255f).roundToInt().coerceIn(0, 255)
    val backgroundColor = (alpha shl 24)

    // Make background more transparent while keeping readability.
    subtitleView.setStyle(
        CaptionStyleCompat(
            /* foregroundColor= */ 0xFFFFFFFF.toInt(),
            /* backgroundColor= */ backgroundColor,
            /* windowColor= */ 0x00000000,
            /* edgeType= */ CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            /* edgeColor= */ 0xCC000000.toInt(),
            /* typeface= */ null,
        ),
    )
}

private fun PlayerActivity.applySubtitlePaddingFraction(subtitleView: SubtitleView, fraction: Float) {
    val basePadding =
        (subtitleView.getTag(blbl.cat3399.R.id.tag_player_subtitle_base_padding) as? IntArray)
            ?.takeIf { it.size == 4 }
            ?: intArrayOf(
                subtitleView.paddingLeft,
                subtitleView.paddingTop,
                subtitleView.paddingRight,
                subtitleView.paddingBottom,
            ).also { subtitleView.setTag(blbl.cat3399.R.id.tag_player_subtitle_base_padding, it) }

    val h = binding.playerView.height.takeIf { it > 0 } ?: subtitleView.height
    if (h <= 0) {
        subtitleView.post { applySubtitlePaddingFraction(subtitleView, fraction) }
        return
    }
    val extraBottomPx = (h * fraction.coerceIn(0f, 0.30f)).roundToInt().coerceAtLeast(0)
    subtitleView.setPadding(
        /* left= */ basePadding[0],
        /* top= */ basePadding[1],
        /* right= */ basePadding[2],
        /* bottom= */ basePadding[3] + extraBottomPx,
    )
}

internal fun PlayerActivity.applySubtitleTextSize() {
    val subtitleView = binding.playerView.findViewById<SubtitleView>(androidx.media3.ui.R.id.exo_subtitles) ?: return
    val sizeSp =
        session.subtitleTextSizeSp
            .let { if (it.isFinite()) it else 26f }
            .coerceIn(10f, 60f)
    subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
}

internal fun PlayerActivity.showDanmakuOpacityDialog() {
    val options = PlaybackSettingChoices.danmakuOpacities
    val current = options.indices.minByOrNull { kotlin.math.abs(options[it] - session.danmaku.opacity) } ?: 0
    showSettingsChoiceDialog(
        title = "弹幕透明度",
        options = options,
        checkedIndex = current,
        label = { String.format(Locale.US, "%.2f", it) },
    ) { picked ->
        applyDanmakuSettingValue(
            value = picked.coerceIn(0.05f, 1.0f),
            updateDanmaku = { copy(opacity = it) },
            syncToGlobal = { danmakuOpacity = it },
            afterApplied = { binding.danmakuView.invalidate() },
        )
    }
}

internal fun PlayerActivity.showDanmakuTextSizeDialog() {
    val options = PlaybackSettingChoices.danmakuTextSizes
    val current =
        options.indices.minByOrNull { kotlin.math.abs(options[it].toFloat() - session.danmaku.textSizeSp) }
            ?: options.indexOf(18).takeIf { it >= 0 }
            ?: 0
    showSettingsChoiceDialog(
        title = "弹幕字体大小",
        options = options,
        checkedIndex = current,
    ) { picked ->
        applyDanmakuSettingValue(
            value = picked.toFloat().coerceIn(10f, 60f),
            updateDanmaku = { copy(textSizeSp = it) },
            syncToGlobal = { danmakuTextSizeSp = it },
            afterApplied = { binding.danmakuView.invalidate() },
        )
    }
}

internal fun PlayerActivity.showDanmakuSpeedDialog() {
    val options = PlaybackSettingChoices.danmakuSpeeds
    val current = options.indexOf(session.danmaku.speedLevel).takeIf { it >= 0 } ?: 3
    showSettingsChoiceDialog(
        title = "弹幕速度(1~10)",
        options = options,
        checkedIndex = current,
    ) { picked ->
        applyDanmakuSettingValue(
            value = picked.coerceIn(1, 10),
            updateDanmaku = { copy(speedLevel = it) },
            syncToGlobal = { danmakuSpeed = it },
            afterApplied = { binding.danmakuView.invalidate() },
        )
    }
}

internal fun PlayerActivity.showDanmakuAreaDialog() {
    val options = PlaybackSettingChoices.danmakuAreas
    val current =
        options.indices.minByOrNull { kotlin.math.abs(options[it].first - session.danmaku.area) }
            ?: options.lastIndex
    showSettingsChoiceDialog(
        title = "弹幕占屏比",
        options = options,
        checkedIndex = current,
        label = { it.second },
    ) { picked ->
        applyDanmakuSettingValue(
            value = AppPrefs.normalizeDanmakuArea(picked.first),
            updateDanmaku = { copy(area = it) },
            syncToGlobal = { danmakuArea = it },
            afterApplied = { binding.danmakuView.invalidate() },
        )
    }
}

internal fun PlayerActivity.showDanmakuStrokeWidthDialog() {
    val options = PlaybackSettingChoices.danmakuStrokeWidths
    val current = options.indexOf(session.danmaku.strokeWidthPx).takeIf { it >= 0 } ?: options.indexOf(4)
    showSettingsChoiceDialog(
        title = "弹幕文字描边粗细",
        options = options,
        checkedIndex = current.coerceAtLeast(0),
    ) { picked ->
        applyDanmakuSettingValue(
            value = picked,
            updateDanmaku = { copy(strokeWidthPx = it) },
            syncToGlobal = { danmakuStrokeWidthPx = it },
            afterApplied = { binding.danmakuView.invalidate() },
        )
    }
}

internal fun PlayerActivity.showDanmakuFontWeightDialog() {
    val options = PlaybackSettingChoices.danmakuFontWeights
    val current = options.indexOf(session.danmaku.fontWeight).takeIf { it >= 0 } ?: 1
    showSettingsChoiceDialog(
        title = "字体粗细",
        options = options,
        checkedIndex = current,
        label = ::danmakuFontWeightText,
    ) { picked ->
        applyDanmakuSettingValue(
            value = picked,
            updateDanmaku = { copy(fontWeight = it) },
            syncToGlobal = { danmakuFontWeight = it.prefValue },
            afterApplied = { binding.danmakuView.invalidate() },
        )
    }
}

internal fun PlayerActivity.showDanmakuLaneDensityDialog() {
    val options = PlaybackSettingChoices.danmakuLaneDensities
    val current = options.indexOf(session.danmaku.laneDensity).takeIf { it >= 0 } ?: 1
    showSettingsChoiceDialog(
        title = "轨道密度",
        options = options,
        checkedIndex = current,
        label = ::danmakuLaneDensityText,
    ) { picked ->
        applyDanmakuSettingValue(
            value = picked,
            updateDanmaku = { copy(laneDensity = it) },
            syncToGlobal = { danmakuLaneDensity = it.prefValue },
            afterApplied = { binding.danmakuView.invalidate() },
        )
    }
}

internal fun PlayerActivity.showDanmakuAiShieldLevelDialog() {
    val options = PlaybackSettingChoices.aiShieldLevels
    val current = options.indexOf(session.danmaku.aiShieldLevel.coerceIn(1, 10)).takeIf { it >= 0 } ?: 2
    showSettingsChoiceDialog(
        title = "智能云屏蔽等级",
        options = options,
        checkedIndex = current,
    ) { picked ->
        applyDanmakuSettingValue(
            value = picked.coerceIn(1, 10),
            updateDanmaku = { copy(aiShieldLevel = it) },
            syncToGlobal = { danmakuAiShieldLevel = it },
            afterApplied = { reloadDanmakuForCurrentSession() },
        )
    }
}
