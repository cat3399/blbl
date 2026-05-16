package blbl.cat3399.feature.player.engine

import blbl.cat3399.feature.player.DashTrackInfo
import blbl.cat3399.feature.player.Playable

internal object DashMpdGenerator {
    fun buildOnDemandMpd(
        dash: Playable.Dash,
        durationMs: Long?,
        videoBaseUrl: String = dash.videoUrl,
        audioBaseUrl: String = dash.audioUrl,
    ): String {
        val videoMimeType = (dash.videoTrackInfo.mimeType ?: "video/mp4").trim().ifBlank { "video/mp4" }
        val audioMimeType = (dash.audioTrackInfo.mimeType ?: "audio/mp4").trim().ifBlank { "audio/mp4" }

        val videoRepresentationId = "video_${dash.qn}_${dash.codecid}"
        val audioRepresentationId = "audio_${dash.audioId}"
        val durationAttr = durationMs?.takeIf { it > 0 }?.let { formatMpdDuration(it) }

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ")
            append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" ")
            append("type=\"static\" minBufferTime=\"PT1.5S\"")
            if (durationAttr != null) append(" mediaPresentationDuration=\"").append(durationAttr).append("\"")
            append(">\n")
            append("  <Period start=\"PT0S\"")
            if (durationAttr != null) append(" duration=\"").append(durationAttr).append("\"")
            append(">\n")

            appendAdaptationSet(
                contentType = "video",
                mimeType = videoMimeType,
                track = dash.videoTrackInfo,
                representationId = videoRepresentationId,
                baseUrl = videoBaseUrl,
            )
            appendAdaptationSet(
                contentType = "audio",
                mimeType = audioMimeType,
                track = dash.audioTrackInfo,
                representationId = audioRepresentationId,
                baseUrl = audioBaseUrl,
            )

            append("  </Period>\n")
            append("</MPD>\n")
        }
    }

    private fun StringBuilder.appendAdaptationSet(
        contentType: String,
        mimeType: String,
        track: DashTrackInfo,
        representationId: String,
        baseUrl: String,
    ) {
        append("    <AdaptationSet contentType=\"").append(xmlEscapeAttr(contentType)).append("\"")
        append(" mimeType=\"").append(xmlEscapeAttr(mimeType)).append("\">\n")

        append("      <Representation id=\"").append(xmlEscapeAttr(representationId)).append("\"")
        track.bandwidth?.takeIf { it > 0L }?.let { bw ->
            append(" bandwidth=\"").append(bw).append("\"")
        }
        track.codecs?.trim()?.takeIf { it.isNotBlank() }?.let { codecs ->
            append(" codecs=\"").append(xmlEscapeAttr(codecs)).append("\"")
        }
        track.width?.takeIf { it > 0 }?.let { w ->
            append(" width=\"").append(w).append("\"")
        }
        track.height?.takeIf { it > 0 }?.let { h ->
            append(" height=\"").append(h).append("\"")
        }
        track.frameRate?.trim()?.takeIf { it.isNotBlank() }?.let { fr ->
            append(" frameRate=\"").append(xmlEscapeAttr(fr)).append("\"")
        }
        append(">\n")

        append("        <BaseURL>").append(xmlEscapeText(baseUrl)).append("</BaseURL>\n")
        track.segmentBase?.let { segmentBase ->
            append("        <SegmentBase indexRange=\"").append(xmlEscapeAttr(segmentBase.indexRange)).append("\">\n")
            append("          <Initialization range=\"").append(xmlEscapeAttr(segmentBase.initialization)).append("\" />\n")
            append("        </SegmentBase>\n")
        }
        append("      </Representation>\n")
        append("    </AdaptationSet>\n")
    }

    private fun xmlEscapeText(value: String): String {
        val v = value.trim()
        if (v.isEmpty()) return ""
        return buildString(v.length + 16) {
            for (ch in v) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    else -> append(ch)
                }
            }
        }
    }

    private fun xmlEscapeAttr(value: String): String {
        val v = value.trim()
        if (v.isEmpty()) return ""
        return buildString(v.length + 16) {
            for (ch in v) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }
    }

    private fun formatMpdDuration(durationMs: Long): String {
        val ms = durationMs.coerceAtLeast(0L)
        val sec = ms / 1000L
        val remMs = (ms % 1000L).toInt().coerceAtLeast(0)
        return if (remMs == 0) {
            "PT${sec}S"
        } else {
            val v = sec.toDouble() + (remMs.toDouble() / 1000.0)
            // Avoid scientific notation.
            val fixed = String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
            "PT${fixed}S"
        }
    }
}
