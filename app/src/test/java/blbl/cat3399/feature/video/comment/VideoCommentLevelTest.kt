package blbl.cat3399.feature.video.comment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCommentLevelTest {
    @Test
    fun level_should_accept_official_range_from_number_or_string() {
        assertEquals(0, parseVideoCommentLevel(0))
        assertEquals(4, parseVideoCommentLevel("4"))
        assertEquals(6, parseVideoCommentLevel(6L))
    }

    @Test
    fun level_should_hide_missing_or_unsupported_values() {
        assertNull(parseVideoCommentLevel(null))
        assertNull(parseVideoCommentLevel(""))
        assertNull(parseVideoCommentLevel(-1))
        assertNull(parseVideoCommentLevel(7))
    }

    @Test
    fun senior_member_should_accept_api_boolean_number_and_string_forms() {
        assertTrue(parseVideoCommentSeniorMember(true))
        assertTrue(parseVideoCommentSeniorMember(1))
        assertTrue(parseVideoCommentSeniorMember("true"))
        assertFalse(parseVideoCommentSeniorMember(false))
        assertFalse(parseVideoCommentSeniorMember(0))
        assertFalse(parseVideoCommentSeniorMember(null))
    }

    @Test
    fun level_colors_should_match_official_palette() {
        assertEquals(0xFFC0C0C0.toInt(), videoCommentLevelColor(0))
        assertEquals(0xFFC0C0C0.toInt(), videoCommentLevelColor(1))
        assertEquals(0xFF8BD29B.toInt(), videoCommentLevelColor(2))
        assertEquals(0xFF7BCDEF.toInt(), videoCommentLevelColor(3))
        assertEquals(0xFFFEBB8B.toInt(), videoCommentLevelColor(4))
        assertEquals(0xFFEE672A.toInt(), videoCommentLevelColor(5))
        assertEquals(0xFFF04C49.toInt(), videoCommentLevelColor(6))
    }
}
