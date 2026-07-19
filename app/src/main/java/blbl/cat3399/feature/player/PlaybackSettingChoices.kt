package blbl.cat3399.feature.player

import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import kotlin.math.abs
import kotlin.math.roundToInt

internal object PlaybackSettingChoices {
    val resolutionQns: List<Int> = listOf(16, 32, 64, 74, 80, 100, 112, 116, 120, 125, 126, 127, 129)
    val audioTrackIds: List<Int> = listOf(30251, 30250, 30280, 30232, 30216)
    val playbackSpeeds: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f, 2.75f)
    val extendedPlaybackSpeeds: List<Float> = playbackSpeeds + listOf(3.0f, 4.0f)
    val subtitleTextSizes: List<Int> = (10..60 step 2).toList()
    val subtitleBottomPaddingPercents: List<Int> = (0..30 step 2).toList()

    val subtitleBackgroundOpacities: List<Float> by lazy {
        val options = (20 downTo 0).map { it / 20f }.toMutableList()
        val defaultOpacity = 34f / 255f
        if (options.none { abs(it - defaultOpacity) < 0.005f }) options.add(defaultOpacity)
        options.distinct().sortedDescending()
    }

    val danmakuOpacities: List<Float> = (20 downTo 1).map { it / 20f }
    val danmakuTextSizes: List<Int> = subtitleTextSizes
    val danmakuAreas: List<Pair<Float, String>> = AppPrefs.DANMAKU_AREA_OPTIONS.map { it to "${(it * 100f).roundToInt()}%" }
    val danmakuStrokeWidths: List<Int> = listOf(0, 2, 4, 6)
    val danmakuFontWeights: List<DanmakuFontWeight> = listOf(DanmakuFontWeight.Normal, DanmakuFontWeight.Bold)
    val danmakuLaneDensities: List<DanmakuLaneDensity> =
        listOf(DanmakuLaneDensity.Sparse, DanmakuLaneDensity.Standard, DanmakuLaneDensity.Dense)
    val danmakuSpeeds: List<Int> = (1..10).toList()
    val aiShieldLevels: List<Int> = (1..10).toList()
}
