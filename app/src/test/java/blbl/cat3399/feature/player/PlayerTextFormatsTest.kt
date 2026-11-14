package blbl.cat3399.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTextFormatsTest {
    @Test
    fun opacityLabels_render_percentages_and_keep_subtitle_default() {
        assertEquals("85%", opacityText(0.85f))
        val subtitleDefault = 34f / 255f
        assertEquals("13%", subtitleBackgroundOpacityText(subtitleDefault))
        assertEquals("0%", opacityText(0f))
        assertEquals("100%", opacityText(1f))
        assertEquals("13%", subtitleBackgroundOpacityText(Float.NaN))
    }

    @Test
    fun formatTransferBytes_should_use_kb_for_small_values() {
        assertEquals("1.5KB", formatTransferBytes(1536))
    }

    @Test
    fun formatTransferBytes_should_use_mb_for_larger_values() {
        assertEquals("1.5MB", formatTransferBytes(1572864))
    }

    @Test
    fun pickAudioIdByPreference_should_keep_exact_match_when_available() {
        val picked = pickAudioIdByPreference(availableAudioIds = listOf(30250, 30280, 30232), desiredAudioId = 30250)
        assertEquals(30250, picked)
    }

    @Test
    fun pickAudioIdByPreference_should_fallback_hires_to_192k_before_dolby() {
        val picked = pickAudioIdByPreference(availableAudioIds = listOf(30250, 30280, 30232), desiredAudioId = 30251)
        assertEquals(30280, picked)
    }

    @Test
    fun pickAudioIdByPreference_should_fallback_normal_to_best_not_above_desired() {
        val picked = pickAudioIdByPreference(availableAudioIds = listOf(30280, 30216), desiredAudioId = 30232)
        assertEquals(30216, picked)
    }

    @Test
    fun pickAudioIdByPreference_should_pick_lowest_normal_above_desired_when_needed() {
        val picked = pickAudioIdByPreference(availableAudioIds = listOf(30280), desiredAudioId = 30232)
        assertEquals(30280, picked)
    }

    @Test
    fun pickQnByQualityOrder_should_pick_exact_match_when_available() {
        val picked = pickQnByQualityOrder(availableQns = listOf(64, 80, 112, 120), desiredQn = 112)
        assertEquals(112, picked)
    }

    @Test
    fun pickQnByQualityOrder_should_fallback_to_best_not_above_desired() {
        val picked = pickQnByQualityOrder(availableQns = listOf(80, 120), desiredQn = 112)
        assertEquals(80, picked)
    }

    @Test
    fun pickQnByQualityOrder_should_pick_lowest_when_all_available_are_above_desired() {
        val picked = pickQnByQualityOrder(availableQns = listOf(120, 127), desiredQn = 80)
        assertEquals(120, picked)
    }
}
