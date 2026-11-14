package blbl.cat3399.core.api

import org.junit.Assert.assertEquals
import org.junit.Test

class SponsorBlockCategoriesTest {
    @Test
    fun normalizeAutoSkipCategory_should_map_legacy_and_unknown_categories() {
        assertEquals(SponsorBlockCategories.FILLER, SponsorBlockCategories.normalizeAutoSkipCategory("padding"))
        assertEquals(SponsorBlockCategories.OTHER, SponsorBlockCategories.normalizeAutoSkipCategory("future_category"))
        assertEquals(SponsorBlockCategories.OTHER, SponsorBlockCategories.normalizeAutoSkipCategory(null))
    }

    @Test
    fun normalizeSelectedAutoSkipCategories_should_filter_deduplicate_and_keep_order() {
        val normalized =
            SponsorBlockCategories.normalizeSelectedAutoSkipCategories(
                listOf("outro", "padding", "sponsor", "outro"),
            )

        assertEquals(
            listOf(
                SponsorBlockCategories.SPONSOR,
                SponsorBlockCategories.OUTRO,
                SponsorBlockCategories.FILLER,
            ),
            normalized,
        )
    }
}
