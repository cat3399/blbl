package blbl.cat3399.feature.player

import blbl.cat3399.core.api.video.VideoPlayResume
import blbl.cat3399.core.api.video.VideoResumeTimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerInitialResumeTest {
    @Test
    fun secondsHistoryResolvesToInitialPlaybackPosition() {
        val candidate =
            resumeCandidateOf(
                resume =
                    VideoPlayResume(
                        rawTime = 42L,
                        timeUnit = VideoResumeTimeUnit.SECONDS,
                        lastCid = 100L,
                    ),
                source = "playurl",
            )

        assertEquals(
            InitialResumePosition(positionMs = 42_000L, source = "playurl"),
            resolveInitialResumePosition(candidate = requireNotNull(candidate), durationMs = 120_000L),
        )
    }

    @Test
    fun insignificantOrNearlyFinishedHistoryDoesNotResume() {
        assertNull(
            resolveInitialResumePosition(
                candidate = ResumeCandidate(4_999L, RawTimeUnitHint.MILLIS_LIKELY, "test"),
                durationMs = 120_000L,
            ),
        )
        assertNull(
            resolveInitialResumePosition(
                candidate = ResumeCandidate(111_000L, RawTimeUnitHint.MILLIS_LIKELY, "test"),
                durationMs = 120_000L,
            ),
        )
    }

    @Test
    fun intentResumeMustMatchExpectedCidAndEpisode() {
        assertTrue(
            resumeIntentMatchesCurrentMedia(
                currentCid = 100L,
                currentEpId = 200L,
                expectedCid = 100L,
                expectedEpId = 200L,
            ),
        )
        assertFalse(
            resumeIntentMatchesCurrentMedia(
                currentCid = 100L,
                currentEpId = 200L,
                expectedCid = 101L,
                expectedEpId = 200L,
            ),
        )
        assertFalse(
            resumeIntentMatchesCurrentMedia(
                currentCid = 100L,
                currentEpId = 200L,
                expectedCid = 100L,
                expectedEpId = 201L,
            ),
        )
    }

    @Test
    fun multiPageHistoryRequiresMatchingLastCid() {
        assertTrue(resumeHistoryMatchesCurrentCid(currentCid = 100L, lastCid = 100L, strictCidMatch = true))
        assertFalse(resumeHistoryMatchesCurrentCid(currentCid = 100L, lastCid = 101L, strictCidMatch = true))
        assertFalse(resumeHistoryMatchesCurrentCid(currentCid = 100L, lastCid = null, strictCidMatch = true))
        assertTrue(resumeHistoryMatchesCurrentCid(currentCid = 100L, lastCid = null, strictCidMatch = false))
    }
}
