package blbl.cat3399.feature.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class DashPlaybackCatalogResolverTest {
    @Test
    fun parseSegmentBase_shouldDeriveInitializationAndSidxRanges() {
        val bytes = box("ftyp", 24) + box("moov", 32) + box("sidx", 44)

        val result = DashPlaybackCatalogResolver.parseSegmentBase(bytes)

        assertEquals("0-55", result?.initialization)
        assertEquals("56-99", result?.indexRange)
    }

    @Test
    fun parseSegmentBase_shouldRejectTruncatedSidx() {
        val complete = box("ftyp", 24) + box("sidx", 44)

        assertNull(DashPlaybackCatalogResolver.parseSegmentBase(complete.copyOf(40)))
    }

    private fun box(type: String, size: Int): ByteArray {
        require(type.length == 4)
        require(size >= 8)
        return ByteArray(size).also { bytes ->
            ByteBuffer.wrap(bytes).putInt(size).put(type.toByteArray(Charsets.US_ASCII))
        }
    }
}
