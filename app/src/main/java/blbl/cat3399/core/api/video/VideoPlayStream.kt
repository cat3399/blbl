package blbl.cat3399.core.api.video

import blbl.cat3399.core.api.BiliApiSource

data class VideoPlayStream(
    val source: BiliApiSource,
    val request: VideoPlayRequest,
    val durationMs: Long?,
    val dash: VideoDashStream?,
    val progressive: List<VideoProgressiveStream>,
    val supportFormats: List<VideoSupportFormat>,
    val clipSegments: List<VideoPlayClipSegment>,
    val resume: VideoPlayResume?,
    val vVoucher: String?,
    val riskControl: VideoPlayRiskControl? = null,
) {
    fun hasPlayableStream(): Boolean {
        if (dash?.videos?.any { it.urls.isNotEmpty() } == true) return true
        if (dash?.audios?.any { it.urls.isNotEmpty() } == true) return true
        return progressive.any { it.urls.isNotEmpty() }
    }

    fun availableVideoQns(): List<Int> {
        val out = LinkedHashSet<Int>()
        dash?.videos.orEmpty().forEach { track ->
            if (track.qn > 0 && track.urls.isNotEmpty()) out.add(track.qn)
        }
        supportFormats.forEach { format ->
            if (format.quality > 0) out.add(format.quality)
        }
        return out.toList()
    }

    fun availableAudioIds(): List<Int> =
        dash
            ?.audios
            .orEmpty()
            .filter { it.urls.isNotEmpty() && it.id > 0 }
            .map { it.id }
            .distinct()

    fun withRiskControl(
        code: Int,
        message: String,
    ): VideoPlayStream =
        copy(
            riskControl =
                VideoPlayRiskControl(
                    bypassed = true,
                    code = code,
                    message = message,
                ),
        )
}

data class VideoDashStream(
    val durationMs: Long?,
    val videos: List<VideoTrack>,
    val audios: List<AudioTrack>,
)

enum class VideoMediaRequestProfile {
    WEB,
    APP,
}

data class VideoTrack(
    val qn: Int,
    val codecid: Int,
    val urls: List<String>,
    val info: VideoTrackInfo,
    val isDolbyVision: Boolean,
    val mediaRequestProfile: VideoMediaRequestProfile = VideoMediaRequestProfile.WEB,
)

data class AudioTrack(
    val id: Int,
    val kind: VideoAudioKind,
    val urls: List<String>,
    val info: VideoTrackInfo,
    val mediaRequestProfile: VideoMediaRequestProfile = VideoMediaRequestProfile.WEB,
)

enum class VideoAudioKind {
    NORMAL,
    DOLBY,
    FLAC,
}

data class VideoProgressiveStream(
    val urls: List<String>,
    val lengthMs: Long?,
    val mediaRequestProfile: VideoMediaRequestProfile = VideoMediaRequestProfile.WEB,
)

data class VideoTrackInfo(
    val mimeType: String?,
    val codecs: String?,
    val bandwidth: Long?,
    val width: Int?,
    val height: Int?,
    val frameRate: String?,
    val segmentBase: VideoSegmentBase?,
)

data class VideoSegmentBase(
    val initialization: String,
    val indexRange: String,
)

data class VideoSupportFormat(
    val quality: Int,
    val label: String?,
)

data class VideoPlayClipSegment(
    val category: String,
    val startMs: Long,
    val endMs: Long,
)

data class VideoPlayResume(
    val rawTime: Long,
    val lastCid: Long?,
)

data class VideoPlayRiskControl(
    val bypassed: Boolean,
    val code: Int,
    val message: String,
)
