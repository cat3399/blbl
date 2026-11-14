package blbl.cat3399.feature.player.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuTimerTest {
    @Test
    fun speedChangesKeepPositionContinuousWhenRawClockLags() {
        val timer = DanmakuTimer()

        assertEquals(10_000L, timer.step(ns(0), 10_000L, true, 1f, 0))
        assertEquals(10_100L, timer.step(ns(100), 10_080L, true, 3f, 0))
        assertEquals(10_400L, timer.step(ns(200), 10_320L, true, 3f, 0))

        val released = timer.step(ns(300), 10_550L, true, 1f, 0)
        assertEquals(10_700L, released)
        assertEquals(10_800L, timer.step(ns(400), 10_650L, true, 1f, 0))
    }

    @Test
    fun pauseAndResumeDoNotPullDanmakuBackwards() {
        val timer = DanmakuTimer()

        timer.step(ns(0), 20_000L, true, 2f, 0)
        assertEquals(20_200L, timer.step(ns(100), 20_150L, true, 2f, 0))
        assertEquals(20_200L, timer.step(ns(200), 20_170L, false, 2f, 0))
        assertEquals(20_200L, timer.step(ns(300), 20_180L, true, 2f, 0))
        assertEquals(20_400L, timer.step(ns(400), 20_350L, true, 2f, 0))
    }

    @Test
    fun explicitSeekCanMoveDanmakuBackwards() {
        val timer = DanmakuTimer()

        timer.step(ns(0), 30_000L, true, 1f, 0)
        timer.step(ns(100), 30_100L, true, 1f, 0)

        assertEquals(5_000L, timer.step(ns(200), 5_000L, true, 1f, 1))
    }

    @Test
    fun unreportedForwardJumpStillReanchors() {
        val timer = DanmakuTimer()

        timer.step(ns(0), 1_000L, true, 1f, 0)
        val position = timer.step(ns(100), 4_000L, true, 1f, 0)

        assertEquals(4_000L, position)
        assertTrue(position >= timer.currentPositionMs())
    }

    private fun ns(milliseconds: Long): Long = milliseconds * 1_000_000L + 1L
}
