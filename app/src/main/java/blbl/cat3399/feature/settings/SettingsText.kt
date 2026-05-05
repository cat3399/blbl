package blbl.cat3399.feature.settings

import android.app.ActivityManager
import android.content.Context
import android.content.res.Resources
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.CustomPageConfig
import blbl.cat3399.core.prefs.PlayerPlaybackModes
import blbl.cat3399.feature.category.CategoryZones
import blbl.cat3399.feature.custom.CustomPageTabRegistry
import blbl.cat3399.feature.home.HomeTabs
import blbl.cat3399.feature.live.LiveFragment
import blbl.cat3399.feature.my.MyTabs
import blbl.cat3399.ui.MainRootNavRegistry
import java.util.Locale
import kotlin.math.roundToInt

object SettingsText {
    fun audioText(id: Int): String =
        when (id) {
            30251 -> "Hi-Res 无损"
            30250 -> "杜比全景声"
            30280 -> "192K"
            30232 -> "132K"
            30216 -> "64K"
            else -> id.toString()
        }

    fun subtitleLangText(code: String): String =
        when (code) {
            "auto" -> "自动"
            "zh-Hans" -> "中文(简体)"
            "zh-Hant" -> "中文(繁体)"
            "en" -> "English"
            "ja" -> "日本語"
            "ko" -> "한국어"
            else -> code
        }

    fun subtitleBottomPaddingText(fraction: Float): String {
        val v = fraction.takeIf { it.isFinite() } ?: 0.16f
        val pct = (v.coerceIn(0f, 0.30f) * 100f).roundToInt()
        return "${pct}%"
    }

    fun subtitleBackgroundOpacityText(opacity: Float): String {
        val v = opacity.takeIf { it.isFinite() } ?: (34f / 255f)
        return String.format(Locale.US, "%.2f", v.coerceIn(0f, 1.0f))
    }

    fun areaText(area: Float): String = "${(AppPrefs.normalizeDanmakuArea(area) * 100f).roundToInt()}%"

    fun danmakuLaneDensityText(prefValue: String): String =
        when (prefValue.trim()) {
            blbl.cat3399.core.prefs.AppPrefs.DANMAKU_LANE_DENSITY_SPARSE -> "稀疏"
            blbl.cat3399.core.prefs.AppPrefs.DANMAKU_LANE_DENSITY_DENSE -> "密集"
            else -> "标准"
        }

    fun danmakuFontWeightText(prefValue: String): String =
        when (prefValue.trim()) {
            blbl.cat3399.core.prefs.AppPrefs.DANMAKU_FONT_WEIGHT_NORMAL -> "常规"
            else -> "加粗"
        }

    fun aiLevelText(level: Int): String =
        if (level <= 0) {
            "3"
        } else {
            level.coerceIn(1, 10).toString()
        }

    fun gridSpanText(span: Int): String = if (span <= 0) "自动" else span.toString()

    fun followingListOrderText(prefValue: String): String =
        when (prefValue) {
            blbl.cat3399.core.prefs.AppPrefs.FOLLOWING_LIST_ORDER_RECENT_VISIT -> "最近访问"
            else -> "关注时间"
        }

    fun startupPageText(context: Context, prefValue: String): String = MainRootNavRegistry.startupTitle(context, prefValue)

    fun customPageContentText(config: CustomPageConfig): String {
        if (config.tabs.isEmpty()) return "未配置"
        val labels = config.tabs.map { CustomPageTabRegistry.settingsLabelForConfig(it) }
        if (labels.size <= 2) return labels.joinToString(separator = " / ")
        return labels.take(2).joinToString(separator = " / ") + " 等${labels.size}项"
    }

    fun mainHomeVisibleTabsText(context: Context, selectedKeys: List<String>): String {
        return visibleTabsText(
            options = HomeTabs.all.map { it.key to context.getString(it.titleRes) },
            selectedKeys = selectedKeys,
        )
    }

    fun mainCategoryVisibleTabsText(selectedKeys: List<String>): String {
        return visibleTabsText(
            options = CategoryZones.defaultZones.map { CategoryZones.stableKeyFor(it) to it.title },
            selectedKeys = selectedKeys,
        )
    }

    fun mainLiveVisibleTabsText(selectedKeys: List<String>): String {
        return visibleTabsText(
            options = LiveFragment.LiveTabs.all.map { it.key to it.title },
            selectedKeys = selectedKeys,
        )
    }

    fun mainMyVisibleTabsText(context: Context, selectedKeys: List<String>): String {
        return visibleTabsText(
            options = MyTabs.all.map { it.key to context.getString(it.titleRes) },
            selectedKeys = selectedKeys,
        )
    }

    private fun visibleTabsText(options: List<Pair<String, String>>, selectedKeys: List<String>): String {
        val selected = selectedKeys.takeIf { it.isNotEmpty() }?.toSet()
        val labels =
            if (selected == null) {
                options.map { it.second }
            } else {
                options.filter { it.first in selected }.map { it.second }
            }
        if (labels.isEmpty()) return "全部"
        if (labels.size <= 4) return labels.joinToString(separator = " / ")
        return labels.take(3).joinToString(separator = " / ") + " 等${labels.size}项"
    }

    fun mainBackFocusSchemeText(prefValue: String): String =
        when (prefValue) {
            blbl.cat3399.core.prefs.AppPrefs.MAIN_BACK_FOCUS_SCHEME_B -> "回到Tab0内容区"
            blbl.cat3399.core.prefs.AppPrefs.MAIN_BACK_FOCUS_SCHEME_C -> "回到侧边栏"
            else -> "回到当前所属Tab"
        }

    fun videoCardLongPressActionText(prefValue: String): String =
        when (prefValue) {
            blbl.cat3399.core.prefs.AppPrefs.VIDEO_CARD_LONG_PRESS_ACTION_WATCH_LATER -> "添加到稍后再看"
            blbl.cat3399.core.prefs.AppPrefs.VIDEO_CARD_LONG_PRESS_ACTION_OPEN_DETAIL -> "进入详情页"
            blbl.cat3399.core.prefs.AppPrefs.VIDEO_CARD_LONG_PRESS_ACTION_OPEN_UP -> "进入UP主页"
            blbl.cat3399.core.prefs.AppPrefs.VIDEO_CARD_LONG_PRESS_ACTION_DISMISS -> "不感兴趣"
            else -> "手动选择"
        }

    fun themePresetText(prefValue: String): String =
        when (prefValue) {
            blbl.cat3399.core.prefs.AppPrefs.THEME_PRESET_TV_PINK -> "小电视粉"
            blbl.cat3399.core.prefs.AppPrefs.THEME_PRESET_TV_PINK_ILLUSTRATION -> "经典"
            else -> "默认"
        }

    fun apiSourceText(prefValue: String): String =
        when (prefValue) {
            blbl.cat3399.core.prefs.AppPrefs.API_SOURCE_APP -> "App"
            else -> "Web"
        }

    fun uiScaleFactorText(factor: Float): String {
        val v = factor.takeIf { it.isFinite() } ?: 1.0f
        return String.format(Locale.US, "%.2fx", v)
    }

    fun cdnText(code: String): String =
        when (code) {
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_MCDN -> "mcdn"
            else -> "bilivideo"
        }

    fun seekStepSecondsText(seconds: Int): String = "${seconds.coerceAtLeast(0)}秒"

    fun holdSeekModeText(code: String): String =
        when (code) {
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME -> "固定时间拖动进度条"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB -> "拖动进度条"
            else -> "倍率加速"
        }

    fun videoShotPreviewSizeText(code: String): String =
        when (code) {
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_OFF -> "不显示"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_SMALL -> "小"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_LARGE -> "大"
            else -> "中"
        }

    fun playerStyleTitle(): String = "播放器样式"

    fun playerStyleText(code: String): String =
        when (code) {
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_STYLE_HD -> "HD"
            else -> "全屏"
        }

    fun renderViewText(code: String): String =
        when (code) {
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW -> "TextureView"
            else -> "SurfaceView"
        }

    fun playerEngineText(code: String): String =
        when (code) {
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_ENGINE_IJK -> "IjkPlayer"
            else -> "ExoPlayer"
        }

    fun downKeyOsdFocusTargetText(code: String): String =
        when (code) {
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_PREV -> "上一个"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_NEXT -> "下一个"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_SUBTITLE -> "字幕"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_DANMAKU -> "弹幕"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_COMMENTS -> "评论"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_DETAIL -> "视频详情页"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_UP -> "UP主"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_LIKE -> "点赞"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_COIN -> "投币"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_FAV -> "收藏"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_LIST_PANEL -> "列表面板"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_SPONSOR_SUBMIT -> "上传广告片段"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_ADVANCED -> "更多设置"
            else -> "播放/暂停"
        }

    fun playerOsdButtonsText(buttons: List<String>): String {
        val enabled = buttons.toSet()
        val labels =
            buildList {
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_PREV)) add("上一个")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_PLAY_PAUSE)) add("播放/暂停")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_NEXT)) add("下一个")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_SUBTITLE)) add("字幕")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_DANMAKU)) add("弹幕")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_COMMENTS)) add("评论")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_DETAIL)) add("视频详情页")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_UP)) add("UP主")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_LIKE)) add("点赞")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_COIN)) add("投币")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_FAV)) add("收藏")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_LIST_PANEL)) add("列表")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_SPONSOR_SUBMIT)) add("上传广告片段")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_ADVANCED)) add("更多设置")
            }
        if (labels.isEmpty()) return "无"
        if (labels.size <= 4) return labels.joinToString(separator = " / ")
        return labels.take(3).joinToString(separator = " / ") + " 等${labels.size}项"
    }

    fun screenText(resources: Resources): String {
        val uiDm = resources.displayMetrics
        val width = uiDm.widthPixels
        val height = uiDm.heightPixels

        val sysDm = Resources.getSystem().displayMetrics
        val sysScale = sysDm.density
        val scaleText =
            if (sysScale.isFinite() && sysScale > 0f) {
                val x100 = (sysScale * 100f).roundToInt().toFloat() / 100f
                val x10 = (x100 * 10f).roundToInt().toFloat() / 10f
                val show =
                    when {
                        kotlin.math.abs(x100 - x100.toInt()) < 0.001f -> x100.toInt().toString()
                        kotlin.math.abs(x100 - x10) < 0.001f -> String.format(Locale.US, "%.1f", x100)
                        else -> String.format(Locale.US, "%.2f", x100)
                    }
                "${show}x"
            } else {
                "-"
            }

        // Settings -> Device Info -> Screen: show only resolution + system display scaling.
        return "${width}x${height} $scaleText"
    }

    fun ramText(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return "-"
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = mi.totalMem.takeIf { it > 0 } ?: return "-"
        val avail = mi.availMem.coerceAtLeast(0)
        return "总${formatBytes(total)} 可用${formatBytes(avail)}"
    }

    fun hardDecoderSupportText(): String {
        val support = runCatching { queryHardDecoderSupport() }.getOrNull() ?: return "-"
        return "H264 ${markSupport(support.h264)} / H265 ${markSupport(support.h265)} / AV1 ${markSupport(support.av1)} / " +
            "DV ${markSupport(support.dolbyVision)} / Atmos ${markSupport(support.dolbyAtmos)}"
    }

    fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        if (b < 1024) return "${b}B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2fGB", gb)
    }

    private fun markSupport(supported: Boolean): String = if (supported) "✓" else "✗"

    private fun queryHardDecoderSupport(): HardDecoderSupport {
        var h264 = false
        var h265 = false
        var av1 = false
        var dolbyVision = false
        var dolbyAtmos = false
        for (codecInfo in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (codecInfo.isEncoder) continue
            if (!isHardwareDecoder(codecInfo)) continue
            for (mime in codecInfo.supportedTypes) {
                when (mime.lowercase(Locale.US)) {
                    "video/avc" -> h264 = true
                    "video/hevc" -> h265 = true
                    "video/av01", "video/av1" -> av1 = true
                    MIME_VIDEO_DOLBY_VISION -> dolbyVision = true
                    in DOLBY_ATMOS_MIME_TYPES -> dolbyAtmos = true
                }
            }
            if (h264 && h265 && av1 && dolbyVision && dolbyAtmos) break
        }
        return HardDecoderSupport(
            h264 = h264,
            h265 = h265,
            av1 = av1,
            dolbyVision = dolbyVision,
            dolbyAtmos = dolbyAtmos,
        )
    }

    private fun isHardwareDecoder(codecInfo: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (codecInfo.isAlias) return false
            return codecInfo.isHardwareAccelerated
        }
        val name = codecInfo.name.lowercase(Locale.US)
        if (name.startsWith("omx.google.")) return false
        if (name.startsWith("c2.android.")) return false
        if (name.startsWith("c2.google.")) return false
        if (name.contains(".sw.")) return false
        if (name.contains("software")) return false
        if (name.contains("ffmpeg")) return false
        return true
    }

    private data class HardDecoderSupport(
        val h264: Boolean,
        val h265: Boolean,
        val av1: Boolean,
        val dolbyVision: Boolean,
        val dolbyAtmos: Boolean,
    )

    private const val MIME_VIDEO_DOLBY_VISION = "video/dolby-vision"

    private val DOLBY_ATMOS_MIME_TYPES =
        setOf(
            "audio/eac3-joc",
            "audio/ac4",
            "audio/vnd.dolby.mlp",
            "audio/vnd.dolby.mat",
        )

    fun playbackModeText(code: String): String = PlayerPlaybackModes.label(code)

    fun qnText(qn: Int): String =
        when (qn) {
            16 -> "360P 流畅"
            32 -> "480P 清晰"
            64 -> "720P 高清"
            74 -> "720P60 高帧率"
            80 -> "1080P 高清"
            112 -> "1080P+ 高码率"
            116 -> "1080P60 高帧率"
            120 -> "4K 超清"
            127 -> "8K 超高清"
            125 -> "HDR 真彩色"
            126 -> "杜比视界"
            129 -> "HDR Vivid"
            6 -> "240P 极速"
            100 -> "智能修复"
            else -> qn.toString()
        }
}
