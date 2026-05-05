package blbl.cat3399.feature.player

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.appcompat.view.ContextThemeWrapper
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.ui.BackButtonSizingHelper
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.uiScaler
import blbl.cat3399.core.ui.userScaledContext
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.feature.video.VideoCardAdapter
import kotlin.math.abs
import kotlin.math.roundToInt

object PlayerOsdSizing {
    fun applyTheme(activity: Activity) {
        activity.theme.applyStyle(R.style.ThemeOverlay_Blbl_PlayerOsd_Normal, true)
    }

    fun inflationContext(activity: Activity): Context {
        return ContextThemeWrapper(
            activity.userScaledContext(),
            R.style.ThemeOverlay_Blbl_PlayerOsd_Normal,
        )
    }

    fun cloneInflater(activity: Activity, baseInflater: LayoutInflater): LayoutInflater {
        return baseInflater.cloneInContext(inflationContext(activity))
    }
}

/**
 * Shared UI scaling logic for both video and live players.
 *
 * Scaling strategy (2026-02-22):
 * - Device normalization: handled by UiDensity (Activity context wrap).
 * - Player UI scale: user UI scale only (UiScale.factor), no auto-scale by content size.
 */
internal object PlayerUiMode {
    private const val LIST_PANEL_GUIDELINE_TOP_PERCENT_BASE = 0.47f

    private data class Insets(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private data class Margins(
        val start: Int,
        val top: Int,
        val end: Int,
        val bottom: Int,
    )

    private data class TextViewMetrics(
        val margins: Margins,
        val padding: Insets,
        val textSizePx: Float,
    )

    private data class ListPanelBaseMetrics(
        val panelMargins: Margins,
        val tabRowMargins: Margins,
        val tabRowPadding: Insets,
        val tabPage: TextViewMetrics,
        val tabParts: TextViewMetrics,
        val tabRecommend: TextViewMetrics,
        val bodyMargins: Margins,
        val bodyRadiusPx: Float,
        val bodyElevationPx: Float,
        val contentPadding: Insets,
        val recyclerPadding: Insets,
        val emptyView: TextViewMetrics,
    )

    private class ListPanelSizingState(
        val base: ListPanelBaseMetrics,
        var lastAppliedScale: Float? = null,
    )

    private data class SidePanelsBaseMetrics(
        val settingsPanelWidthPx: Int,
        val settingsPanelMargins: Margins,
        val settingsPanelCornerRadiusPx: Float,
        val settingsPanelElevationPx: Float,
        val settingsContainerPadding: Insets,
        val settingsRecyclerPadding: Insets,
        val commentsPanelWidthPx: Int,
        val commentsPanelMargins: Margins,
        val commentsPanelCornerRadiusPx: Float,
        val commentsPanelElevationPx: Float,
        val commentsContainerPadding: Insets,
        val commentsContentMargins: Margins,
        val commentSortLabel: TextViewMetrics,
        val commentSortHot: TextViewMetrics,
        val commentSortNew: TextViewMetrics,
        val commentsRecyclerPadding: Insets,
        val commentsThreadRecyclerPadding: Insets,
        val commentsHint: TextViewMetrics,
    )

    private class SidePanelsSizingState(
        val base: SidePanelsBaseMetrics,
        var lastAppliedScale: Float? = null,
    )

    fun applyVideo(activity: Activity, binding: ActivityPlayerBinding) {
        val uiScale = UiScale.factor(activity).takeIf { it.isFinite() && it > 0f } ?: 1.0f
        val scaler = binding.root.context.uiScaler(uiScale)
        val viewScale = scaler.scale
        fun scaledPx(id: Int): Int = scaler.scaledDimenPx(id)
        fun scaledPxF(id: Int): Float = scaler.scaledDimenPxF(id)

        applyBottomListPanelSizing(binding = binding, viewScale = viewScale, userScale = uiScale)
        applySidePanelsSizing(binding = binding, scale = viewScale)

        run {
            val prefSize = BiliClient.prefs.playerVideoShotPreviewSize
            val shortEdgeId =
                when (prefSize) {
                    AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_SMALL ->
                        R.dimen.player_videoshot_preview_short_edge_small
                    AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_LARGE ->
                        R.dimen.player_videoshot_preview_short_edge_large
                    else ->
                        R.dimen.player_videoshot_preview_short_edge_medium
                }
            binding.videoShotPreview.setShortEdge(scaledPx(shortEdgeId))

            if (android.os.Build.VERSION.SDK_INT >= 21) {
                val elevationPx = scaledPxF(R.dimen.player_videoshot_preview_elevation)
                if (binding.videoShotPreview.elevation != elevationPx) {
                    binding.videoShotPreview.elevation = elevationPx
                }
            }

            (binding.videoShotPreview.layoutParams as? MarginLayoutParams)?.let { lp ->
                val mb = scaledPx(R.dimen.player_videoshot_preview_margin_bottom)
                if (lp.bottomMargin != mb) {
                    lp.bottomMargin = mb
                    binding.videoShotPreview.layoutParams = lp
                }
            }

            if (prefSize == AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_OFF) {
                binding.videoShotPreview.visibility = View.GONE
            }
        }

        val topPadH = scaledPx(R.dimen.player_top_bar_padding_h)
        val topPadV = scaledPx(R.dimen.player_top_bar_padding_v)
        val topPadTopExtra =
            scaledPx(
                R.dimen.player_top_bar_padding_top_extra,
            )
        val topPadTop = topPadV + topPadTopExtra
        val topPadBottom = topPadV
        if (
            binding.topBar.paddingLeft != topPadH ||
            binding.topBar.paddingRight != topPadH ||
            binding.topBar.paddingTop != topPadTop ||
            binding.topBar.paddingBottom != topPadBottom
        ) {
            binding.topBar.setPadding(topPadH, topPadTop, topPadH, topPadBottom)
        }

        val topBtnSize = scaledPx(R.dimen.player_top_button_size).coerceAtLeast(1)
        val topBtnPad = scaledPx(R.dimen.player_top_button_padding)
        val backBtnSize = scaledPx(R.dimen.sidebar_settings_size).coerceAtLeast(1)
        val backBtnPad = scaledPx(R.dimen.sidebar_settings_padding)
        BackButtonSizingHelper.applySizeAndPadding(
            view = binding.btnBack,
            sizePx = backBtnSize,
            paddingPx = backBtnPad,
        )
        setSize(binding.btnSettings, topBtnSize, topBtnSize)
        binding.btnSettings.setPadding(topBtnPad, topBtnPad, topBtnPad, topBtnPad)

        binding.tvTitle.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(R.dimen.player_title_text_size),
        )
        (binding.tvTitle.layoutParams as? MarginLayoutParams)?.let { lp ->
            val ms = scaledPx(R.dimen.player_title_margin_start)
            val me = scaledPx(R.dimen.player_title_margin_end)
            if (lp.marginStart != ms || lp.marginEnd != me) {
                lp.marginStart = ms
                lp.marginEnd = me
                binding.tvTitle.layoutParams = lp
            }
        }

        binding.tvOnline.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(R.dimen.player_online_text_size),
        )

        binding.tvClock.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(R.dimen.player_clock_text_size),
        )
        (binding.tvClock.layoutParams as? MarginLayoutParams)?.let { lp ->
            val me = scaledPx(R.dimen.player_clock_margin_end)
            if (lp.topMargin != 0 || lp.marginEnd != me) {
                lp.topMargin = 0
                lp.marginEnd = me
                binding.tvClock.layoutParams = lp
            }
        }
        (binding.titleRow.layoutParams as? MarginLayoutParams)?.let { lp ->
            val me = scaledPx(R.dimen.player_clock_margin_start)
            if (lp.marginEnd != me) {
                lp.marginEnd = me
                binding.titleRow.layoutParams = lp
            }
        }

        run {
            val ms = scaledPx(R.dimen.player_title_margin_start)
            val me = scaledPx(R.dimen.player_title_margin_end)
            val mt = scaledPx(R.dimen.player_title_meta_margin_top)
            (binding.llTitleMeta.layoutParams as? MarginLayoutParams)?.let { lp ->
                if (lp.marginStart != ms || lp.marginEnd != me || lp.topMargin != mt) {
                    lp.marginStart = ms
                    lp.marginEnd = me
                    lp.topMargin = mt
                    binding.llTitleMeta.layoutParams = lp
                }
            }
            val pb = scaledPx(R.dimen.player_title_meta_padding_bottom)
            if (binding.llTitleMeta.paddingBottom != pb) {
                binding.llTitleMeta.setPadding(
                    binding.llTitleMeta.paddingLeft,
                    binding.llTitleMeta.paddingTop,
                    binding.llTitleMeta.paddingRight,
                    pb,
                )
            }
            val metaTextSizePx = scaledPxF(R.dimen.player_online_text_size)
            binding.tvViewCount.setTextSize(TypedValue.COMPLEX_UNIT_PX, metaTextSizePx)
            binding.tvPubdate.setTextSize(TypedValue.COMPLEX_UNIT_PX, metaTextSizePx)
            val metaIconSize =
                scaledPx(R.dimen.video_card_stat_icon_size)
                    .coerceAtLeast(1)
            setSize(binding.ivOnlineIcon, metaIconSize, metaIconSize)
            setSize(binding.ivViewIcon, metaIconSize, metaIconSize)
        }

        val bottomPadV = scaledPx(R.dimen.player_bottom_bar_padding_v)
        if (binding.bottomBar.paddingTop != bottomPadV || binding.bottomBar.paddingBottom != bottomPadV) {
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                bottomPadV,
                binding.bottomBar.paddingRight,
                bottomPadV,
            )
        }
        if (binding.seekOsdContainer.paddingTop != bottomPadV || binding.seekOsdContainer.paddingBottom != bottomPadV) {
            binding.seekOsdContainer.setPadding(
                binding.seekOsdContainer.paddingLeft,
                bottomPadV,
                binding.seekOsdContainer.paddingRight,
                bottomPadV,
            )
        }

        (binding.seekProgress.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height = scaledPx(R.dimen.player_seekbar_touch_height).coerceAtLeast(1)
            val mb = scaledPx(R.dimen.player_seekbar_margin_bottom)
            if (lp.height != height || lp.bottomMargin != mb) {
                lp.height = height
                lp.bottomMargin = mb
                binding.seekProgress.layoutParams = lp
            }
        }
        run {
            binding.seekProgress.progressDrawable = ContextCompat.getDrawable(activity, R.drawable.seekbar_player_progress)
            val trackHeight =
                scaledPx(
                    R.dimen.player_seekbar_track_height,
                ).coerceAtLeast(1)
            binding.seekProgress.setTrackHeightPx(trackHeight)
        }

        (binding.progressPersistentBottom.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height =
                scaledPx(
                    R.dimen.player_persistent_progress_height,
                ).coerceAtLeast(1)
            if (lp.height != height) {
                lp.height = height
                binding.progressPersistentBottom.layoutParams = lp
            }
        }
        run {
            binding.progressPersistentBottom.progressDrawable =
                ContextCompat.getDrawable(activity, R.drawable.progress_player_persistent)
        }

        (binding.progressSeekOsd.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height = scaledPx(R.dimen.player_seekbar_touch_height).coerceAtLeast(1)
            val mh = scaledPx(R.dimen.player_seek_osd_margin_h)
            val mb = scaledPx(R.dimen.player_seek_osd_time_margin_bottom)
            if (lp.height != height || lp.marginStart != mh || lp.marginEnd != mh || lp.bottomMargin != mb) {
                lp.height = height
                lp.marginStart = mh
                lp.marginEnd = mh
                lp.bottomMargin = mb
                binding.progressSeekOsd.layoutParams = lp
            }
        }
        run {
            binding.progressSeekOsd.progressDrawable = ContextCompat.getDrawable(activity, R.drawable.progress_player_seek_osd)
            binding.progressSeekOsd.setThumbDrawable(cloneSeekThumbDrawable(binding))
            val trackHeight = scaledPx(R.dimen.player_seek_osd_progress_height).coerceAtLeast(1)
            binding.progressSeekOsd.setTrackHeightPx(trackHeight)
            binding.progressSeekOsd.setTrackVerticalOffsetPx(
                scaledPx(R.dimen.player_seek_osd_track_offset_y),
            )
        }

        (binding.controlsRow.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height = scaledPx(R.dimen.player_controls_row_height).coerceAtLeast(1)
            val ms = scaledPx(R.dimen.player_controls_row_margin_start)
            val me = scaledPx(R.dimen.player_controls_row_margin_end)
            if (lp.height != height || lp.marginStart != ms || lp.marginEnd != me) {
                lp.height = height
                lp.marginStart = ms
                lp.marginEnd = me
                binding.controlsRow.layoutParams = lp
            }
        }
        binding.tvTime.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(R.dimen.player_time_text_size),
        )
        (binding.tvTime.layoutParams as? MarginLayoutParams)?.let { lp ->
            val me = scaledPx(R.dimen.player_time_margin_end)
            if (lp.marginEnd != me) {
                lp.marginEnd = me
                binding.tvTime.layoutParams = lp
            }
        }

        binding.tvSeekOsdTime.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(R.dimen.player_time_text_size),
        )
        (binding.tvSeekOsdTime.layoutParams as? MarginLayoutParams)?.let { lp ->
            val me = scaledPx(R.dimen.player_seek_osd_margin_h)
            val mb = scaledPx(R.dimen.player_seek_osd_margin_bottom)
            if (lp.marginEnd != me || lp.bottomMargin != mb) {
                lp.marginEnd = me
                lp.bottomMargin = mb
                binding.tvSeekOsdTime.layoutParams = lp
            }
        }

        binding.tvSeekHint.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(R.dimen.player_seek_hint_text_size),
        )
        binding.tvSeekHint.maxWidth = scaledPx(R.dimen.player_seek_hint_max_width).coerceAtLeast(1)
        val hintPadH = scaledPx(R.dimen.player_seek_hint_padding_h)
        val hintPadV = scaledPx(R.dimen.player_seek_hint_padding_v)
        if (
            binding.tvSeekHint.paddingLeft != hintPadH ||
            binding.tvSeekHint.paddingRight != hintPadH ||
            binding.tvSeekHint.paddingTop != hintPadV ||
            binding.tvSeekHint.paddingBottom != hintPadV
        ) {
            binding.tvSeekHint.setPadding(hintPadH, hintPadV, hintPadH, hintPadV)
        }
        (binding.tvSeekHint.layoutParams as? MarginLayoutParams)?.let { lp ->
            val ms = scaledPx(R.dimen.player_seek_hint_margin_start)
            val mb = scaledPx(R.dimen.player_seek_hint_margin_bottom)
            if (lp.marginStart != ms || lp.bottomMargin != mb) {
                lp.marginStart = ms
                lp.bottomMargin = mb
                binding.tvSeekHint.layoutParams = lp
            }
        }
        applyBufferingOverlaySizing(binding = binding, scaledPx = ::scaledPx, scaledPxF = ::scaledPxF)
    }

    fun applyLive(activity: Activity, binding: ActivityPlayerBinding) {
        val uiScale = UiScale.factor(activity).takeIf { it.isFinite() && it > 0f } ?: 1.0f
        val scaler = binding.root.context.uiScaler(uiScale)
        val viewScale = scaler.scale
        fun scaledPx(id: Int): Int = scaler.scaledDimenPx(id)
        fun scaledPxF(id: Int): Float = scaler.scaledDimenPxF(id)

        applySidePanelsSizing(binding = binding, scale = viewScale)

        val topPadH = scaledPx(blbl.cat3399.R.dimen.player_top_bar_padding_h)
        val topPadV = scaledPx(blbl.cat3399.R.dimen.player_top_bar_padding_v)
        if (
            binding.topBar.paddingLeft != topPadH ||
            binding.topBar.paddingRight != topPadH ||
            binding.topBar.paddingTop != topPadV ||
            binding.topBar.paddingBottom != topPadV
        ) {
            binding.topBar.setPadding(topPadH, topPadV, topPadH, topPadV)
        }

        val topBtnSize =
            scaledPx(blbl.cat3399.R.dimen.player_top_button_size).coerceAtLeast(1)
        val topBtnPad = scaledPx(blbl.cat3399.R.dimen.player_top_button_padding)
        val backBtnSize = scaledPx(blbl.cat3399.R.dimen.sidebar_settings_size).coerceAtLeast(1)
        val backBtnPad = scaledPx(blbl.cat3399.R.dimen.sidebar_settings_padding)
        BackButtonSizingHelper.applySizeAndPadding(
            view = binding.btnBack,
            sizePx = backBtnSize,
            paddingPx = backBtnPad,
        )
        setSize(binding.btnSettings, topBtnSize, topBtnSize)
        binding.btnSettings.setPadding(topBtnPad, topBtnPad, topBtnPad, topBtnPad)

        binding.tvTitle.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(blbl.cat3399.R.dimen.player_title_text_size),
        )

        binding.tvOnline.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(blbl.cat3399.R.dimen.player_online_text_size),
        )

        binding.tvClock.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(blbl.cat3399.R.dimen.player_clock_text_size),
        )
        (binding.tvClock.layoutParams as? MarginLayoutParams)?.let { lp ->
            val me = scaledPx(blbl.cat3399.R.dimen.player_clock_margin_end)
            if (lp.topMargin != 0 || lp.marginEnd != me) {
                lp.topMargin = 0
                lp.marginEnd = me
                binding.tvClock.layoutParams = lp
            }
        }
        (binding.titleRow.layoutParams as? MarginLayoutParams)?.let { lp ->
            val me = scaledPx(blbl.cat3399.R.dimen.player_clock_margin_start)
            if (lp.marginEnd != me) {
                lp.marginEnd = me
                binding.titleRow.layoutParams = lp
            }
        }

        val bottomPadV = scaledPx(blbl.cat3399.R.dimen.player_bottom_bar_padding_v)
        if (binding.bottomBar.paddingTop != bottomPadV || binding.bottomBar.paddingBottom != bottomPadV) {
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                bottomPadV,
                binding.bottomBar.paddingRight,
                bottomPadV,
            )
        }

        (binding.seekProgress.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height =
                scaledPx(
                    blbl.cat3399.R.dimen.player_seekbar_touch_height,
                ).coerceAtLeast(1)
            val mb = scaledPx(blbl.cat3399.R.dimen.player_seekbar_margin_bottom)
            if (lp.height != height || lp.bottomMargin != mb) {
                lp.height = height
                lp.bottomMargin = mb
                binding.seekProgress.layoutParams = lp
            }
        }
        run {
            binding.seekProgress.progressDrawable =
                ContextCompat.getDrawable(activity, blbl.cat3399.R.drawable.seekbar_player_progress)
            val trackHeight =
                scaledPx(
                    blbl.cat3399.R.dimen.player_seekbar_track_height,
                ).coerceAtLeast(1)
            binding.seekProgress.setTrackHeightPx(trackHeight)
        }

        (binding.controlsRow.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height =
                scaledPx(blbl.cat3399.R.dimen.player_controls_row_height).coerceAtLeast(1)
            val ms = scaledPx(blbl.cat3399.R.dimen.player_controls_row_margin_start)
            val me = scaledPx(blbl.cat3399.R.dimen.player_controls_row_margin_end)
            if (lp.height != height || lp.marginStart != ms || lp.marginEnd != me) {
                lp.height = height
                lp.marginStart = ms
                lp.marginEnd = me
                binding.controlsRow.layoutParams = lp
            }
        }
        binding.tvTime.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(blbl.cat3399.R.dimen.player_time_text_size),
        )

        binding.tvSeekHint.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(blbl.cat3399.R.dimen.player_seek_hint_text_size),
        )
        binding.tvSeekHint.maxWidth =
            scaledPx(blbl.cat3399.R.dimen.player_seek_hint_max_width).coerceAtLeast(1)
        val hintPadH = scaledPx(blbl.cat3399.R.dimen.player_seek_hint_padding_h)
        val hintPadV = scaledPx(blbl.cat3399.R.dimen.player_seek_hint_padding_v)
        if (
            binding.tvSeekHint.paddingLeft != hintPadH ||
            binding.tvSeekHint.paddingRight != hintPadH ||
            binding.tvSeekHint.paddingTop != hintPadV ||
            binding.tvSeekHint.paddingBottom != hintPadV
        ) {
            binding.tvSeekHint.setPadding(hintPadH, hintPadV, hintPadH, hintPadV)
        }
        applyBufferingOverlaySizing(binding = binding, scaledPx = ::scaledPx, scaledPxF = ::scaledPxF)
    }

    private fun applyBufferingOverlaySizing(
        binding: ActivityPlayerBinding,
        scaledPx: (Int) -> Int,
        scaledPxF: (Int) -> Float,
    ) {
        val padH = scaledPx(R.dimen.player_buffering_padding_h)
        val padV = scaledPx(R.dimen.player_buffering_padding_v)
        if (
            binding.bufferingOverlay.paddingLeft != padH ||
            binding.bufferingOverlay.paddingRight != padH ||
            binding.bufferingOverlay.paddingTop != padV ||
            binding.bufferingOverlay.paddingBottom != padV
        ) {
            binding.bufferingOverlay.setPadding(padH, padV, padH, padV)
        }

        (binding.progressBuffering.layoutParams as? MarginLayoutParams)?.let { lp ->
            val size = scaledPx(R.dimen.player_buffering_indicator_size).coerceAtLeast(1)
            if (lp.width != size || lp.height != size) {
                lp.width = size
                lp.height = size
                binding.progressBuffering.layoutParams = lp
            }
        }

        binding.tvBuffering.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(R.dimen.player_buffering_text_size),
        )
        (binding.tvBuffering.layoutParams as? MarginLayoutParams)?.let { lp ->
            val mt = scaledPx(R.dimen.player_buffering_text_margin_top)
            if (lp.topMargin != mt) {
                lp.topMargin = mt
                binding.tvBuffering.layoutParams = lp
            }
        }
    }

    private fun applyBottomListPanelSizing(
        binding: ActivityPlayerBinding,
        viewScale: Float,
        userScale: Float,
    ) {
        val s = viewScale.takeIf { it.isFinite() && it > 0f } ?: 1.0f

        val state =
            (binding.recommendPanel.getTag(R.id.tag_player_list_panel_base_metrics) as? ListPanelSizingState)
                ?: ListPanelSizingState(base = captureBottomListPanelBaseMetrics(binding)).also {
                    binding.recommendPanel.setTag(R.id.tag_player_list_panel_base_metrics, it)
                }

        val last = state.lastAppliedScale
        if (last != null && abs(last - s) < 0.001f) return
        state.lastAppliedScale = s

        fun scaledPx(basePx: Int): Int = (basePx * s).roundToInt().coerceAtLeast(0)
        fun scaledPxF(basePx: Float): Float = (basePx * s).coerceAtLeast(0f)

        fun applyMargins(view: View, base: Margins) {
            val lp = view.layoutParams as? MarginLayoutParams ?: return
            val ms = scaledPx(base.start)
            val mt = scaledPx(base.top)
            val me = scaledPx(base.end)
            val mb = scaledPx(base.bottom)
            if (lp.marginStart != ms || lp.topMargin != mt || lp.marginEnd != me || lp.bottomMargin != mb) {
                lp.marginStart = ms
                lp.topMargin = mt
                lp.marginEnd = me
                lp.bottomMargin = mb
                view.layoutParams = lp
            }
        }

        fun applyPadding(view: View, base: Insets) {
            val pl = scaledPx(base.left)
            val pt = scaledPx(base.top)
            val pr = scaledPx(base.right)
            val pb = scaledPx(base.bottom)
            if (view.paddingLeft != pl || view.paddingTop != pt || view.paddingRight != pr || view.paddingBottom != pb) {
                view.setPadding(pl, pt, pr, pb)
            }
        }

        fun applyTextView(view: android.widget.TextView, base: TextViewMetrics) {
            applyMargins(view, base.margins)
            applyPadding(view, base.padding)
            val textSize = scaledPxF(base.textSizePx).coerceAtLeast(1f)
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        }

        val base = state.base
        run {
            // Global strategy: the bottom list panel uses a guideline to define its fixed-height region.
            // When user increases UI scale, give the panel more vertical space; otherwise cards become
            // height-constrained and can overlap.
            val basePercent = LIST_PANEL_GUIDELINE_TOP_PERCENT_BASE
            val newPercent = scaledListPanelGuidelinePercent(basePercent = basePercent, userScale = userScale)
            val lp = binding.guidelineListPanelTop.layoutParams as? ConstraintLayout.LayoutParams
            if (lp != null && lp.guidePercent != newPercent) {
                lp.guidePercent = newPercent
                binding.guidelineListPanelTop.layoutParams = lp
            }
        }
        applyMargins(binding.recommendPanel, base.panelMargins)
        applyMargins(binding.listPanelTabRow, base.tabRowMargins)
        applyPadding(binding.listPanelTabRow, base.tabRowPadding)

        applyTextView(binding.tabPageList, base.tabPage)
        applyTextView(binding.tabPartsList, base.tabParts)
        applyTextView(binding.tabRecommendList, base.tabRecommend)

        applyMargins(binding.listPanelBody, base.bodyMargins)
        val radius = scaledPxF(base.bodyRadiusPx)
        if (binding.listPanelBody.radius != radius) binding.listPanelBody.radius = radius
        val elevation = scaledPxF(base.bodyElevationPx)
        if (binding.listPanelBody.cardElevation != elevation) binding.listPanelBody.cardElevation = elevation

        applyPadding(binding.listPanelContent, base.contentPadding)
        applyPadding(binding.recyclerRecommend, base.recyclerPadding)
        applyTextView(binding.tvListPanelEmpty, base.emptyView)

        (binding.recyclerRecommend.adapter as? VideoCardAdapter)?.invalidateSizing()
    }

    private fun applySidePanelsSizing(binding: ActivityPlayerBinding, scale: Float) {
        val s = scale.takeIf { it.isFinite() && it > 0f } ?: 1.0f

        val state =
            (binding.settingsPanel.getTag(R.id.tag_player_side_panels_base_metrics) as? SidePanelsSizingState)
                ?: SidePanelsSizingState(base = captureSidePanelsBaseMetrics(binding)).also {
                    binding.settingsPanel.setTag(R.id.tag_player_side_panels_base_metrics, it)
                }

        val last = state.lastAppliedScale
        if (last != null && abs(last - s) < 0.001f) return
        state.lastAppliedScale = s

        fun scaledPx(basePx: Int): Int = (basePx * s).roundToInt().coerceAtLeast(0)
        fun scaledPxF(basePx: Float): Float = (basePx * s).coerceAtLeast(0f)

        fun applyMargins(view: View, base: Margins) {
            val lp = view.layoutParams as? MarginLayoutParams ?: return
            val ms = scaledPx(base.start)
            val mt = scaledPx(base.top)
            val me = scaledPx(base.end)
            val mb = scaledPx(base.bottom)
            if (lp.marginStart != ms || lp.topMargin != mt || lp.marginEnd != me || lp.bottomMargin != mb) {
                lp.marginStart = ms
                lp.topMargin = mt
                lp.marginEnd = me
                lp.bottomMargin = mb
                view.layoutParams = lp
            }
        }

        fun applyPadding(view: View, base: Insets) {
            val pl = scaledPx(base.left)
            val pt = scaledPx(base.top)
            val pr = scaledPx(base.right)
            val pb = scaledPx(base.bottom)
            if (view.paddingLeft != pl || view.paddingTop != pt || view.paddingRight != pr || view.paddingBottom != pb) {
                view.setPadding(pl, pt, pr, pb)
            }
        }

        fun applyTextView(view: android.widget.TextView, base: TextViewMetrics) {
            applyMargins(view, base.margins)
            applyPadding(view, base.padding)
            val textSize = scaledPxF(base.textSizePx).coerceAtLeast(1f)
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        }

        fun applyCardPanel(
            panel: com.google.android.material.card.MaterialCardView,
            baseWidthPx: Int,
            baseMargins: Margins,
            baseCornerRadiusPx: Float,
            baseElevationPx: Float,
        ) {
            applyMargins(panel, baseMargins)
            val lp = panel.layoutParams as? MarginLayoutParams ?: return
            val w = scaledPx(baseWidthPx).coerceAtLeast(1)
            if (lp.width != w) {
                lp.width = w
                panel.layoutParams = lp
            }
            val radius = scaledPxF(baseCornerRadiusPx)
            if (panel.radius != radius) panel.radius = radius
            val elevation = scaledPxF(baseElevationPx)
            if (panel.cardElevation != elevation) panel.cardElevation = elevation
        }

        val base = state.base

        applyCardPanel(
            panel = binding.settingsPanel,
            baseWidthPx = base.settingsPanelWidthPx,
            baseMargins = base.settingsPanelMargins,
            baseCornerRadiusPx = base.settingsPanelCornerRadiusPx,
            baseElevationPx = base.settingsPanelElevationPx,
        )
        (binding.recyclerSettings.parent as? View)?.let { applyPadding(it, base.settingsContainerPadding) }
        applyPadding(binding.recyclerSettings, base.settingsRecyclerPadding)

        applyCardPanel(
            panel = binding.commentsPanel,
            baseWidthPx = base.commentsPanelWidthPx,
            baseMargins = base.commentsPanelMargins,
            baseCornerRadiusPx = base.commentsPanelCornerRadiusPx,
            baseElevationPx = base.commentsPanelElevationPx,
        )
        (binding.rowCommentSort.parent as? View)?.let { applyPadding(it, base.commentsContainerPadding) }
        applyMargins(binding.commentsContent, base.commentsContentMargins)
        applyTextView(binding.tvCommentSortLabel, base.commentSortLabel)
        applyTextView(binding.chipCommentSortHot, base.commentSortHot)
        applyTextView(binding.chipCommentSortNew, base.commentSortNew)
        applyPadding(binding.recyclerComments, base.commentsRecyclerPadding)
        applyPadding(binding.recyclerCommentThread, base.commentsThreadRecyclerPadding)
        applyTextView(binding.tvCommentsHint, base.commentsHint)

        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.invalidateSizing()
        (binding.recyclerComments.adapter as? PlayerCommentsAdapter)?.invalidateSizing()
        (binding.recyclerCommentThread.adapter as? PlayerCommentsAdapter)?.invalidateSizing()
    }

    private fun captureBottomListPanelBaseMetrics(binding: ActivityPlayerBinding): ListPanelBaseMetrics {
        fun captureMargins(view: View): Margins {
            val lp = view.layoutParams as? MarginLayoutParams
            return Margins(
                start = lp?.marginStart ?: 0,
                top = lp?.topMargin ?: 0,
                end = lp?.marginEnd ?: 0,
                bottom = lp?.bottomMargin ?: 0,
            )
        }

        fun capturePadding(view: View): Insets {
            return Insets(
                left = view.paddingLeft,
                top = view.paddingTop,
                right = view.paddingRight,
                bottom = view.paddingBottom,
            )
        }

        fun captureTextViewMetrics(view: android.widget.TextView): TextViewMetrics {
            return TextViewMetrics(
                margins = captureMargins(view),
                padding = capturePadding(view),
                textSizePx = view.textSize,
            )
        }

        return ListPanelBaseMetrics(
            panelMargins = captureMargins(binding.recommendPanel),
            tabRowMargins = captureMargins(binding.listPanelTabRow),
            tabRowPadding = capturePadding(binding.listPanelTabRow),
            tabPage = captureTextViewMetrics(binding.tabPageList),
            tabParts = captureTextViewMetrics(binding.tabPartsList),
            tabRecommend = captureTextViewMetrics(binding.tabRecommendList),
            bodyMargins = captureMargins(binding.listPanelBody),
            bodyRadiusPx = binding.listPanelBody.radius,
            bodyElevationPx = binding.listPanelBody.cardElevation,
            contentPadding = capturePadding(binding.listPanelContent),
            recyclerPadding = capturePadding(binding.recyclerRecommend),
            emptyView = captureTextViewMetrics(binding.tvListPanelEmpty),
        )
    }

    private fun scaledListPanelGuidelinePercent(basePercent: Float, userScale: Float): Float {
        val s = userScale.takeIf { it.isFinite() && it > 0f } ?: 1.0f
        val base = basePercent.takeIf { it.isFinite() } ?: 0.47f

        // The list panel height is (1 - percent). We scale that height with user scale,
        // but dampen the effect so it doesn't cover too much of the video.
        val baseHeightFrac = (1f - base).coerceIn(0.05f, 0.95f)
        // Stronger than "linear with alpha=0.5": large UI scales (e.g. 1.40x) need substantially
        // more vertical space, otherwise VideoCard content enters height-constrained layout and
        // can clip/overlap (title vs UP line).
        val alpha = 0.90f
        val heightScale = (1f + alpha * (s - 1f)).coerceIn(0.70f, 1.40f)
        val heightFrac = (baseHeightFrac * heightScale).coerceIn(0.20f, 0.80f)
        val percent = 1f - heightFrac

        // Keep reasonable bounds across non-16:9 screens.
        return percent.coerceIn(0.30f, 0.60f)
    }

    private fun captureSidePanelsBaseMetrics(binding: ActivityPlayerBinding): SidePanelsBaseMetrics {
        fun captureMargins(view: View): Margins {
            val lp = view.layoutParams as? MarginLayoutParams
            return Margins(
                start = lp?.marginStart ?: 0,
                top = lp?.topMargin ?: 0,
                end = lp?.marginEnd ?: 0,
                bottom = lp?.bottomMargin ?: 0,
            )
        }

        fun capturePadding(view: View): Insets {
            return Insets(
                left = view.paddingLeft,
                top = view.paddingTop,
                right = view.paddingRight,
                bottom = view.paddingBottom,
            )
        }

        fun captureTextViewMetrics(view: android.widget.TextView): TextViewMetrics {
            return TextViewMetrics(
                margins = captureMargins(view),
                padding = capturePadding(view),
                textSizePx = view.textSize,
            )
        }

        val settingsContainer = (binding.recyclerSettings.parent as? View) ?: binding.recyclerSettings
        val commentsContainer = (binding.rowCommentSort.parent as? View) ?: binding.rowCommentSort

        return SidePanelsBaseMetrics(
            settingsPanelWidthPx = binding.settingsPanel.layoutParams?.width ?: 0,
            settingsPanelMargins = captureMargins(binding.settingsPanel),
            settingsPanelCornerRadiusPx = binding.settingsPanel.radius,
            settingsPanelElevationPx = binding.settingsPanel.cardElevation,
            settingsContainerPadding = capturePadding(settingsContainer),
            settingsRecyclerPadding = capturePadding(binding.recyclerSettings),
            commentsPanelWidthPx = binding.commentsPanel.layoutParams?.width ?: 0,
            commentsPanelMargins = captureMargins(binding.commentsPanel),
            commentsPanelCornerRadiusPx = binding.commentsPanel.radius,
            commentsPanelElevationPx = binding.commentsPanel.cardElevation,
            commentsContainerPadding = capturePadding(commentsContainer),
            commentsContentMargins = captureMargins(binding.commentsContent),
            commentSortLabel = captureTextViewMetrics(binding.tvCommentSortLabel),
            commentSortHot = captureTextViewMetrics(binding.chipCommentSortHot),
            commentSortNew = captureTextViewMetrics(binding.chipCommentSortNew),
            commentsRecyclerPadding = capturePadding(binding.recyclerComments),
            commentsThreadRecyclerPadding = capturePadding(binding.recyclerCommentThread),
            commentsHint = captureTextViewMetrics(binding.tvCommentsHint),
        )
    }

    private fun setSize(view: View, widthPx: Int, heightPx: Int) {
        val lp = view.layoutParams ?: return
        if (lp.width == widthPx && lp.height == heightPx) return
        lp.width = widthPx
        lp.height = heightPx
        view.layoutParams = lp
    }

    private fun cloneSeekThumbDrawable(binding: ActivityPlayerBinding): Drawable? {
        val context = binding.root.context
        val resources = context.resources
        binding.seekProgress.thumb?.constantState?.newDrawable(resources)?.mutate()?.let { return it }
        return ContextCompat.getDrawable(context, R.drawable.seekbar_player_thumb)
            ?.constantState
            ?.newDrawable(resources)
            ?.mutate()
    }
}
