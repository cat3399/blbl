package blbl.cat3399.feature.video.comment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCommentPictureTest {
    @Test
    fun dimensionRatio_should_preserve_source_dimensions() {
        val picture =
            VideoCommentPicture(
                url = "https://i.example.com/picture.webp",
                width = 1920,
                height = 1080,
            )

        assertEquals("1920:1080", picture.dimensionRatio)
    }

    @Test
    fun dimensionRatio_should_ignore_missing_or_invalid_dimensions() {
        assertNull(imageDimensionRatio(width = null, height = 1080))
        assertNull(imageDimensionRatio(width = 1920, height = null))
        assertNull(imageDimensionRatio(width = 0, height = 1080))
        assertNull(imageDimensionRatio(width = 1920, height = -1))
    }
}
