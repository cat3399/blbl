package blbl.cat3399.feature.player

import android.app.Activity
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.tv.TvMode
import blbl.cat3399.databinding.ActivityPlayerBinding
import kotlin.math.roundToInt

object PlayerOsdSizing {
    private enum class SizeTier {
        SMALL,
        MEDIUM,
        LARGE,
    }

    fun applyTheme(activity: Activity) {
        val tvMode = TvMode.isEnabled(activity)
        val tier = tierFromPref(BiliClient.prefs.sidebarSize)
        val overlay =
            if (tvMode) {
                when (tier) {
                    SizeTier.SMALL -> R.style.ThemeOverlay_Blbl_PlayerOsd_Tv_Small
                    SizeTier.MEDIUM -> R.style.ThemeOverlay_Blbl_PlayerOsd_Tv_Medium
                    SizeTier.LARGE -> R.style.ThemeOverlay_Blbl_PlayerOsd_Tv_Large
                }
            } else {
                when (tier) {
                    SizeTier.SMALL -> R.style.ThemeOverlay_Blbl_PlayerOsd_Normal_Small
                    SizeTier.MEDIUM -> R.style.ThemeOverlay_Blbl_PlayerOsd_Normal_Medium
                    SizeTier.LARGE -> R.style.ThemeOverlay_Blbl_PlayerOsd_Normal_Large
                }
            }
        activity.theme.applyStyle(overlay, true)
    }

    fun applyToViews(activity: Activity, binding: ActivityPlayerBinding, scale: Float = 1.0f) {
        val s = scale.takeIf { it.isFinite() && it > 0f } ?: 1.0f
        fun scaled(v: Int): Int = (v * s).roundToInt()

        val targetSize = scaled(activity.themeDimenPx(R.attr.playerOsdButtonTargetSize)).coerceAtLeast(1)
        val padTransport = scaled(activity.themeDimenPx(R.attr.playerOsdPadTransport)).coerceAtLeast(0)
        val padNormal = scaled(activity.themeDimenPx(R.attr.playerOsdPadNormal)).coerceAtLeast(0)
        val padSmall = scaled(activity.themeDimenPx(R.attr.playerOsdPadSmall)).coerceAtLeast(0)
        val gap = scaled(activity.themeDimenPx(R.attr.playerOsdGap)).coerceAtLeast(0)

        listOf(binding.btnPrev, binding.btnPlayPause, binding.btnNext).forEach { btn ->
            setSize(btn, targetSize, targetSize)
            btn.setPadding(padTransport, padTransport, padTransport, padTransport)
            setEndMargin(btn, gap)
        }
        listOf(binding.btnSubtitle, binding.btnDanmaku, binding.btnUp).forEach { btn ->
            setSize(btn, targetSize, targetSize)
            btn.setPadding(padNormal, padNormal, padNormal, padNormal)
            setEndMargin(btn, gap)
        }
        listOf(binding.btnLike, binding.btnCoin, binding.btnFav, binding.btnPlaylist).forEach { btn ->
            setSize(btn, targetSize, targetSize)
            btn.setPadding(padSmall, padSmall, padSmall, padSmall)
            setEndMargin(btn, gap)
        }
        run {
            val btn = binding.btnAdvanced
            setSize(btn, targetSize, targetSize)
            btn.setPadding(padSmall, padSmall, padSmall, padSmall)
            setEndMargin(btn, 0)
        }
    }

    private fun tierFromPref(prefValue: String): SizeTier {
        return when (prefValue) {
            AppPrefs.SIDEBAR_SIZE_SMALL -> SizeTier.SMALL
            AppPrefs.SIDEBAR_SIZE_LARGE -> SizeTier.LARGE
            else -> SizeTier.MEDIUM
        }
    }

    private fun Activity.themeDimenPx(attr: Int): Int {
        val out = TypedValue()
        if (!theme.resolveAttribute(attr, out, true)) return 0
        return if (out.resourceId != 0) resources.getDimensionPixelSize(out.resourceId)
        else TypedValue.complexToDimensionPixelSize(out.data, resources.displayMetrics)
    }

    private fun setSize(view: View, widthPx: Int, heightPx: Int) {
        val lp = view.layoutParams ?: return
        if (lp.width == widthPx && lp.height == heightPx) return
        lp.width = widthPx
        lp.height = heightPx
        view.layoutParams = lp
    }

    private fun setEndMargin(view: View, marginEndPx: Int) {
        val lp = view.layoutParams as? MarginLayoutParams ?: return
        if (lp.marginEnd == marginEndPx) return
        lp.marginEnd = marginEndPx
        view.layoutParams = lp
    }
}
