package blbl.cat3399.feature.player.engine

import blbl.cat3399.core.api.video.VideoMediaRequestProfile
import blbl.cat3399.feature.player.DashAudioKind
import blbl.cat3399.feature.player.DashSegmentBase
import blbl.cat3399.feature.player.DashTrackInfo
import blbl.cat3399.feature.player.Playable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashMpdGeneratorTest {
    @Test
    fun buildOnDemandMpd_shouldUseBaseUrlOnlyWhenSegmentBaseIsMissing() {
        val mpd =
            DashMpdGenerator.buildOnDemandMpd(
                dash = dash(videoSegmentBase = null, audioSegmentBase = null),
                durationMs = 12_345L,
            )

        assertTrue(mpd.contains("<BaseURL>https://upos.example.com/video.m4s</BaseURL>"))
        assertTrue(mpd.contains("<BaseURL>https://upos.example.com/audio.m4s</BaseURL>"))
        assertFalse(mpd.contains("<SegmentBase"))
        assertFalse(mpd.contains("<Initialization"))
    }

    @Test
    fun buildOnDemandMpd_shouldKeepSegmentBaseWhenProvided() {
        val mpd =
            DashMpdGenerator.buildOnDemandMpd(
                dash =
                    dash(
                        videoSegmentBase = DashSegmentBase(initialization = "0-999", indexRange = "1000-1200"),
                        audioSegmentBase = DashSegmentBase(initialization = "0-800", indexRange = "801-940"),
                    ),
                durationMs = 12_345L,
            )

        assertTrue(mpd.contains("<SegmentBase indexRange=\"1000-1200\">"))
        assertTrue(mpd.contains("<Initialization range=\"0-999\" />"))
        assertTrue(mpd.contains("<SegmentBase indexRange=\"801-940\">"))
        assertTrue(mpd.contains("<Initialization range=\"0-800\" />"))
    }

    @Test
    fun buildAdaptiveOnDemandMpd_shouldExposeAllVideoRepresentationsAndStableIds() {
        val segmentBase = DashSegmentBase(initialization = "0-999", indexRange = "1000-1200")
        val first = dash(videoSegmentBase = segmentBase, audioSegmentBase = segmentBase)
        val adaptive =
            first.copy(
                videoRepresentations =
                    listOf(
                        Playable.DashVideoRepresentation(
                            videoUrl = "https://upos.example.com/720p.m4s",
                            videoUrlCandidates = listOf("https://upos.example.com/720p.m4s"),
                            videoMediaRequestProfile = VideoMediaRequestProfile.WEB,
                            videoTrackInfo = first.videoTrackInfo.copy(width = 1280, height = 720, bandwidth = 800_000L),
                            qn = 64,
                            codecid = 7,
                            isDolbyVision = false,
                        ),
                        Playable.DashVideoRepresentation(
                            videoUrl = first.videoUrl,
                            videoUrlCandidates = first.videoUrlCandidates,
                            videoMediaRequestProfile = first.videoMediaRequestProfile,
                            videoTrackInfo = first.videoTrackInfo,
                            qn = first.qn,
                            codecid = first.codecid,
                            isDolbyVision = false,
                        ),
                    ),
            )

        val mpd = DashMpdGenerator.buildAdaptiveOnDemandMpd(adaptive, durationMs = 12_345L)

        assertTrue(mpd.contains("video_qn_64_codec_7_index_0"))
        assertTrue(mpd.contains("video_qn_80_codec_7_index_1"))
        assertTrue(mpd.contains("<AdaptationSet contentType=\"video\" segmentAlignment=\"true\" startWithSAP=\"1\" id=\"7\">"))
        assertFalse(mpd.contains("id=\"video_codec_7\""))
        assertTrue(mpd.contains("https://upos.example.com/720p.m4s"))
        assertEquals(80 to 7, DashMpdGenerator.parseVideoRepresentationId("video_qn_80_codec_7_index_1"))
        assertEquals(80 to 7, DashMpdGenerator.parseVideoRepresentationId("0:video_qn_80_codec_7_index_1:main"))
    }

    private fun dash(
        videoSegmentBase: DashSegmentBase?,
        audioSegmentBase: DashSegmentBase?,
    ): Playable.Dash =
        Playable.Dash(
            videoUrl = "https://upos.example.com/video.m4s",
            audioUrl = "https://upos.example.com/audio.m4s",
            videoUrlCandidates = listOf("https://upos.example.com/video.m4s"),
            audioUrlCandidates = listOf("https://upos.example.com/audio.m4s"),
            videoMediaRequestProfile = VideoMediaRequestProfile.APP,
            audioMediaRequestProfile = VideoMediaRequestProfile.APP,
            videoTrackInfo =
                DashTrackInfo(
                    mimeType = "video/mp4",
                    codecs = "avc1",
                    bandwidth = 1_000_000L,
                    width = 1920,
                    height = 1080,
                    frameRate = "30",
                    segmentBase = videoSegmentBase,
                ),
            audioTrackInfo =
                DashTrackInfo(
                    mimeType = "audio/mp4",
                    codecs = null,
                    bandwidth = 128_000L,
                    width = null,
                    height = null,
                    frameRate = null,
                    segmentBase = audioSegmentBase,
                ),
            qn = 80,
            codecid = 7,
            audioId = 30280,
            audioKind = DashAudioKind.NORMAL,
            isDolbyVision = false,
        )
}
