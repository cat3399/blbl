package blbl.cat3399.feature.player

import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayerCustomShortcutAction
import blbl.cat3399.core.prefs.PlayerCustomShortcutOpenVideoListTarget
import blbl.cat3399.core.prefs.PlayerPlaybackModes
import blbl.cat3399.feature.settings.SettingsText
import java.util.Locale
import kotlin.math.abs

internal data class PlayerCustomShortcutActionOption(
    val type: String,
    val label: String,
    val requiresValue: Boolean,
)

internal data class PlayerCustomShortcutValueChoice(
    val label: String,
    val action: PlayerCustomShortcutAction,
)

internal data class PlayerCustomShortcutValuePickerConfig(
    val choices: List<PlayerCustomShortcutValueChoice>,
    val checkedIndex: Int,
)

internal object PlayerCustomShortcutCatalog {
    private val actionOptionsInternal =
        listOf(
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_OPEN_VIDEO_LIST, "打开视频列表", requiresValue = true),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_OPEN_COMMENTS, "打开评论", requiresValue = false),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_OPEN_SETTINGS, "打开设置", requiresValue = false),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_SHOW_OSD, "呼出 OSD", requiresValue = false),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_TOGGLE_PLAY_PAUSE, "播放/暂停", requiresValue = false),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_PLAY_PREVIOUS, "上一个", requiresValue = false),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_PLAY_NEXT, "下一个", requiresValue = false),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_OPEN_VIDEO_DETAIL, "打开视频详情", requiresValue = false),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_OPEN_UP_DETAIL, "打开 UP 主页", requiresValue = false),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_LIKE, "点赞", requiresValue = false),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_COIN, "投币", requiresValue = false),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_FAV, "收藏", requiresValue = false),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_TOGGLE_SUBTITLES, "字幕 开/关", requiresValue = false),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_TOGGLE_DANMAKU, "弹幕 开/关", requiresValue = false),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_TOGGLE_DEBUG_OVERLAY, "调试信息 开/关", requiresValue = false),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_TOGGLE_PERSISTENT_BOTTOM_PROGRESS, "底部常驻进度条 开/关", requiresValue = false),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_SET_PLAYBACK_SPEED, "播放速度", requiresValue = true),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_SET_RESOLUTION_QN, "画质", requiresValue = true),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_SET_AUDIO_ID, "音质", requiresValue = true),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_SET_CODEC, "视频编码", requiresValue = true),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_SET_PLAYBACK_MODE, "播放模式", requiresValue = true),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_SET_SUBTITLE_LANG, "字幕语言", requiresValue = true),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_SET_SUBTITLE_TEXT_SIZE, "字幕字体大小", requiresValue = true),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_SET_DANMAKU_OPACITY, "弹幕不透明度", requiresValue = true),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_SET_DANMAKU_TEXT_SIZE, "弹幕字体大小", requiresValue = true),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_SET_DANMAKU_SPEED, "弹幕速度", requiresValue = true),
            PlayerCustomShortcutActionOption(PlayerCustomShortcutAction.TYPE_SET_DANMAKU_AREA, "弹幕区域", requiresValue = true),
        )

    private val actionOptionsByType = actionOptionsInternal.associateBy { it.type }

    fun actionOptions(): List<PlayerCustomShortcutActionOption> = actionOptionsInternal

    fun actionTitle(type: String): String = actionOptionsByType[type]?.label ?: type

    fun createAction(type: String): PlayerCustomShortcutAction? {
        return when (type) {
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
            else -> null
        }
    }

    fun actionLabel(action: PlayerCustomShortcutAction): String {
        return when (action) {
            is PlayerCustomShortcutAction.OpenVideoList -> "打开视频列表：${openVideoListTargetText(action.target)}"
            PlayerCustomShortcutAction.OpenComments -> "打开评论"
            PlayerCustomShortcutAction.OpenSettings -> "打开设置"
            PlayerCustomShortcutAction.ShowOsd -> "呼出 OSD"
            PlayerCustomShortcutAction.TogglePlayPause -> "播放/暂停"
            PlayerCustomShortcutAction.PlayPrevious -> "上一个"
            PlayerCustomShortcutAction.PlayNext -> "下一个"
            PlayerCustomShortcutAction.OpenVideoDetail -> "打开视频详情"
            PlayerCustomShortcutAction.OpenUpDetail -> "打开 UP 主页"
            PlayerCustomShortcutAction.Like -> "点赞"
            PlayerCustomShortcutAction.Coin -> "投币"
            PlayerCustomShortcutAction.Fav -> "收藏"
            PlayerCustomShortcutAction.ToggleSubtitles -> "字幕：开/关"
            PlayerCustomShortcutAction.ToggleDanmaku -> "弹幕：开/关"
            PlayerCustomShortcutAction.ToggleDebugOverlay -> "调试信息：开/关"
            PlayerCustomShortcutAction.TogglePersistentBottomProgress -> "底部常驻进度条：开/关"
            is PlayerCustomShortcutAction.SetPlaybackSpeed -> "播放速度：${String.format(Locale.US, "%.2fx", action.speed)}"
            is PlayerCustomShortcutAction.SetResolutionQn -> "画质：${qnLabel(action.qn)}"
            is PlayerCustomShortcutAction.SetAudioId -> "音质：${audioLabel(action.audioId)}"
            is PlayerCustomShortcutAction.SetCodec -> "视频编码：${action.codec}"
            is PlayerCustomShortcutAction.SetPlaybackMode -> "播放模式：${playbackModeText(action.mode)}"
            is PlayerCustomShortcutAction.SetSubtitleLang -> {
                val lang = action.lang.trim()
                if (lang.equals(PlayerCustomShortcutAction.SUBTITLE_LANG_DEFAULT, ignoreCase = true)) {
                    "字幕语言：跟随全局"
                } else {
                    "字幕语言：${SettingsText.subtitleLangText(lang)}"
                }
            }
            is PlayerCustomShortcutAction.SetSubtitleTextSize -> "字幕大小：${action.textSizeSp.toInt()}"
            is PlayerCustomShortcutAction.SetDanmakuOpacity -> "弹幕不透明度：${SettingsText.opacityText(action.opacity)}"
            is PlayerCustomShortcutAction.SetDanmakuTextSize -> "弹幕大小：${action.textSizeSp.toInt()}"
            is PlayerCustomShortcutAction.SetDanmakuSpeed -> "弹幕速度：${action.speedLevel}"
            is PlayerCustomShortcutAction.SetDanmakuArea -> "弹幕区域：${areaText(action.area)}"
        }
    }

    fun valuePickerConfig(
        type: String,
        currentAction: PlayerCustomShortcutAction?,
    ): PlayerCustomShortcutValuePickerConfig? {
        return when (type) {
            PlayerCustomShortcutAction.TYPE_OPEN_VIDEO_LIST -> {
                val options =
                    listOf(
                        PlayerCustomShortcutOpenVideoListTarget.AUTO,
                        PlayerCustomShortcutOpenVideoListTarget.PAGE,
                        PlayerCustomShortcutOpenVideoListTarget.PARTS,
                        PlayerCustomShortcutOpenVideoListTarget.RECOMMEND,
                    )
                val current = (currentAction as? PlayerCustomShortcutAction.OpenVideoList)?.target
                val checked = options.indexOf(current).takeIf { it >= 0 } ?: 0
                PlayerCustomShortcutValuePickerConfig(
                    choices =
                        options.map { target ->
                            PlayerCustomShortcutValueChoice(
                                label = openVideoListTargetText(target),
                                action = PlayerCustomShortcutAction.OpenVideoList(target = target),
                            )
                        },
                    checkedIndex = checked,
                )
            }

            PlayerCustomShortcutAction.TYPE_SET_PLAYBACK_SPEED -> {
                val options = PlaybackSettingChoices.extendedPlaybackSpeeds
                val current = (currentAction as? PlayerCustomShortcutAction.SetPlaybackSpeed)?.speed
                val checked = options.indices.minByOrNull { idx -> abs(options[idx] - (current ?: 1.0f)) } ?: 2
                PlayerCustomShortcutValuePickerConfig(
                    choices = options.map { PlayerCustomShortcutValueChoice(String.format(Locale.US, "%.2fx", it), PlayerCustomShortcutAction.SetPlaybackSpeed(speed = it)) },
                    checkedIndex = checked,
                )
            }

            PlayerCustomShortcutAction.TYPE_SET_RESOLUTION_QN -> {
                val options = PlaybackSettingChoices.resolutionQns
                val current = (currentAction as? PlayerCustomShortcutAction.SetResolutionQn)?.qn
                val checked = options.indexOf(current).takeIf { it >= 0 } ?: 0
                PlayerCustomShortcutValuePickerConfig(
                    choices = options.map { PlayerCustomShortcutValueChoice(qnLabel(it), PlayerCustomShortcutAction.SetResolutionQn(qn = it)) },
                    checkedIndex = checked,
                )
            }

            PlayerCustomShortcutAction.TYPE_SET_AUDIO_ID -> {
                val options = PlaybackSettingChoices.audioTrackIds
                val current = (currentAction as? PlayerCustomShortcutAction.SetAudioId)?.audioId
                val checked = options.indexOf(current).takeIf { it >= 0 } ?: 0
                PlayerCustomShortcutValuePickerConfig(
                    choices = options.map { PlayerCustomShortcutValueChoice(audioLabel(it), PlayerCustomShortcutAction.SetAudioId(audioId = it)) },
                    checkedIndex = checked,
                )
            }

            PlayerCustomShortcutAction.TYPE_SET_CODEC -> {
                val options = listOf("AVC", "HEVC", "AV1")
                val current = (currentAction as? PlayerCustomShortcutAction.SetCodec)?.codec
                val checked = options.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 0
                PlayerCustomShortcutValuePickerConfig(
                    choices = options.map { PlayerCustomShortcutValueChoice(it, PlayerCustomShortcutAction.SetCodec(codec = it)) },
                    checkedIndex = checked,
                )
            }

            PlayerCustomShortcutAction.TYPE_SET_PLAYBACK_MODE -> {
                val options = PlayerPlaybackModes.ordered
                val current = (currentAction as? PlayerCustomShortcutAction.SetPlaybackMode)?.mode
                val checked = options.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 0
                PlayerCustomShortcutValuePickerConfig(
                    choices = options.map { PlayerCustomShortcutValueChoice(playbackModeText(it), PlayerCustomShortcutAction.SetPlaybackMode(mode = it)) },
                    checkedIndex = checked,
                )
            }

            PlayerCustomShortcutAction.TYPE_SET_SUBTITLE_LANG -> {
                val options =
                    listOf(
                        PlayerCustomShortcutAction.SUBTITLE_LANG_DEFAULT,
                        "auto",
                        "zh-Hans",
                        "zh-Hant",
                        "en",
                        "ja",
                        "ko",
                    )
                val current = (currentAction as? PlayerCustomShortcutAction.SetSubtitleLang)?.lang
                val checked = options.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 0
                PlayerCustomShortcutValuePickerConfig(
                    choices =
                        options.map { lang ->
                            val label =
                                if (lang == PlayerCustomShortcutAction.SUBTITLE_LANG_DEFAULT) {
                                    "跟随全局"
                                } else {
                                    SettingsText.subtitleLangText(lang)
                                }
                            PlayerCustomShortcutValueChoice(label, PlayerCustomShortcutAction.SetSubtitleLang(lang = lang))
                        },
                    checkedIndex = checked,
                )
            }

            PlayerCustomShortcutAction.TYPE_SET_SUBTITLE_TEXT_SIZE -> {
                val options = (10..60 step 2).toList()
                val current = (currentAction as? PlayerCustomShortcutAction.SetSubtitleTextSize)?.textSizeSp
                val checked = options.indices.minByOrNull { idx -> abs(options[idx].toFloat() - (current ?: 26f)) } ?: 0
                PlayerCustomShortcutValuePickerConfig(
                    choices = options.map { PlayerCustomShortcutValueChoice(it.toString(), PlayerCustomShortcutAction.SetSubtitleTextSize(textSizeSp = it.toFloat())) },
                    checkedIndex = checked,
                )
            }

            PlayerCustomShortcutAction.TYPE_SET_DANMAKU_OPACITY -> {
                val options = (20 downTo 1).map { it / 20f }
                val current = (currentAction as? PlayerCustomShortcutAction.SetDanmakuOpacity)?.opacity
                val checked = options.indices.minByOrNull { idx -> abs(options[idx] - (current ?: 1f)) } ?: 0
                PlayerCustomShortcutValuePickerConfig(
                    choices = options.map { PlayerCustomShortcutValueChoice(SettingsText.opacityText(it), PlayerCustomShortcutAction.SetDanmakuOpacity(opacity = it)) },
                    checkedIndex = checked,
                )
            }

            PlayerCustomShortcutAction.TYPE_SET_DANMAKU_TEXT_SIZE -> {
                val options = (10..60 step 2).toList()
                val current = (currentAction as? PlayerCustomShortcutAction.SetDanmakuTextSize)?.textSizeSp
                val checked = options.indices.minByOrNull { idx -> abs(options[idx].toFloat() - (current ?: 18f)) } ?: 0
                PlayerCustomShortcutValuePickerConfig(
                    choices = options.map { PlayerCustomShortcutValueChoice(it.toString(), PlayerCustomShortcutAction.SetDanmakuTextSize(textSizeSp = it.toFloat())) },
                    checkedIndex = checked,
                )
            }

            PlayerCustomShortcutAction.TYPE_SET_DANMAKU_SPEED -> {
                val options = (1..10).toList()
                val current = (currentAction as? PlayerCustomShortcutAction.SetDanmakuSpeed)?.speedLevel
                val checked = options.indexOf(current).takeIf { it >= 0 } ?: 0
                PlayerCustomShortcutValuePickerConfig(
                    choices = options.map { PlayerCustomShortcutValueChoice(it.toString(), PlayerCustomShortcutAction.SetDanmakuSpeed(speedLevel = it)) },
                    checkedIndex = checked,
                )
            }

            PlayerCustomShortcutAction.TYPE_SET_DANMAKU_AREA -> {
                val options = PlaybackSettingChoices.danmakuAreas
                val current = (currentAction as? PlayerCustomShortcutAction.SetDanmakuArea)?.area
                val checked = options.indices.minByOrNull { idx -> abs(options[idx].first - (current ?: AppPrefs.DANMAKU_AREA_DEFAULT)) } ?: options.lastIndex
                PlayerCustomShortcutValuePickerConfig(
                    choices = options.map { PlayerCustomShortcutValueChoice(it.second, PlayerCustomShortcutAction.SetDanmakuArea(area = it.first)) },
                    checkedIndex = checked,
                )
            }

            else -> null
    }
}

private fun openVideoListTargetText(target: PlayerCustomShortcutOpenVideoListTarget): String {
    return when (target) {
        PlayerCustomShortcutOpenVideoListTarget.AUTO -> "自动"
        PlayerCustomShortcutOpenVideoListTarget.PAGE -> "视频列表"
        PlayerCustomShortcutOpenVideoListTarget.PARTS -> "合集/分P"
        PlayerCustomShortcutOpenVideoListTarget.RECOMMEND -> "推荐"
    }
}

    private fun playbackModeText(code: String): String = SettingsText.playbackModeText(code)
}
