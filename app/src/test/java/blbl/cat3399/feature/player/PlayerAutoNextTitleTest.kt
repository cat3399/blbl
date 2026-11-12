package blbl.cat3399.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerAutoNextTitleTest {
    @Test
    fun keepsShortTitlesAfterWhitespaceNormalization() {
        assertEquals("第12话", formatAutoNextHintTitle("  第12话  ", fallbackTitle = "推荐视频"))
    }

    @Test
    fun truncatesTitlesToEighteenCharactersBeforeEllipsis() {
        assertEquals("123456789012345678...", formatAutoNextHintTitle("12345678901234567890", fallbackTitle = "推荐视频"))
    }

    @Test
    fun fallsBackWhenTitleIsBlank() {
        assertEquals("推荐视频", formatAutoNextHintTitle("  \n\t  ", fallbackTitle = "推荐视频"))
    }
}
