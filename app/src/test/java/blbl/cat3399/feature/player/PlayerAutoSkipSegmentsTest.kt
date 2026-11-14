package blbl.cat3399.feature.player

import blbl.cat3399.core.api.SponsorBlockCategories
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerAutoSkipSegmentsTest {
    @Test
    fun filterAutoSkipSegments_should_keep_only_selected_auto_skip_categories() {
        val segments =
            listOf(
                segment(id = "sponsor", category = "sponsor"),
                segment(id = "intro", category = "intro"),
                segment(id = "legacy_filler", category = "padding"),
            )

        val filtered =
            filterAutoSkipSegments(
                segments,
                listOf(SponsorBlockCategories.INTRO, SponsorBlockCategories.FILLER),
            )

        assertEquals(listOf("intro", "legacy_filler"), filtered.map { it.id })
    }

    @Test
    fun filterAutoSkipSegments_should_keep_poi_markers_even_when_not_selectable() {
        val poi = segment(id = "poi", category = "poi_highlight", actionType = "poi")

        val filtered = filterAutoSkipSegments(listOf(poi), listOf(SponsorBlockCategories.SPONSOR))

        assertEquals(listOf(poi), filtered)
    }

    private fun segment(
        id: String,
        category: String,
        actionType: String = "skip",
    ) =
        SkipSegment(
            id = id,
            startMs = 1_000L,
            endMs = 2_000L,
            category = category,
            source = "test",
            actionType = actionType,
        )
}
