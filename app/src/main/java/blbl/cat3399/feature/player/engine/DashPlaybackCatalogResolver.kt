package blbl.cat3399.feature.player.engine

import blbl.cat3399.core.api.video.VideoMediaRequestProfile
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.feature.player.DashAudioKind
import blbl.cat3399.feature.player.DashSegmentBase
import blbl.cat3399.feature.player.DashTrackInfo
import blbl.cat3399.feature.player.Playable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream

/** Normalizes Web/App DASH metadata before it enters the player engine. */
internal object DashPlaybackCatalogResolver {
    suspend fun resolve(playable: Playable.Dash): Playable.Dash {
        val normalizedPlayable =
            playable.copy(audioTrackInfo = normalizeAudioTrackInfo(playable.audioTrackInfo, playable.audioKind))
        val missingVideoCount = normalizedPlayable.videoRepresentations.count { it.videoTrackInfo.segmentBase == null }
        val missingAudio = normalizedPlayable.audioTrackInfo.segmentBase == null
        if (missingVideoCount == 0 && !missingAudio) return normalizedPlayable

        val startedAtMs = android.os.SystemClock.elapsedRealtime()
        val semaphore = Semaphore(MAX_CONCURRENT_PROBES)
        return coroutineScope {
            val videoDeferred =
                normalizedPlayable.videoRepresentations.map { representation ->
                    async(Dispatchers.IO) {
                        if (representation.videoTrackInfo.segmentBase != null) return@async representation
                        val segmentBase =
                            semaphore.withPermit {
                                probeCandidates(
                                    candidates = representation.videoUrlCandidates,
                                    profile = representation.videoMediaRequestProfile,
                                )
                            }
                        if (segmentBase == null) {
                            AppLog.w(
                                "QualitySwitch",
                                "segment probe failed kind=video qn=${representation.qn} codecid=${representation.codecid}",
                            )
                            representation
                        } else {
                            representation.copy(videoTrackInfo = representation.videoTrackInfo.copy(segmentBase = segmentBase))
                        }
                    }
                }

            val audioDeferred =
                async(Dispatchers.IO) {
                    if (!missingAudio) {
                        return@async normalizedPlayable.audioTrackInfo.segmentBase
                    }
                    semaphore.withPermit {
                        probeCandidates(
                            candidates = normalizedPlayable.audioUrlCandidates,
                            profile = normalizedPlayable.audioMediaRequestProfile,
                        )
                    }
                }
            val videos = videoDeferred.awaitAll()
            val audioSegmentBase = audioDeferred.await()

            val selectedVideo =
                videos.firstOrNull { representation ->
                    representation.qn == normalizedPlayable.qn &&
                        representation.codecid == normalizedPlayable.codecid &&
                        representation.videoUrl == normalizedPlayable.videoUrl
                }
                    ?: videos.firstOrNull { it.qn == normalizedPlayable.qn && it.codecid == normalizedPlayable.codecid }
            val resolved =
                normalizedPlayable.copy(
                    videoTrackInfo = selectedVideo?.videoTrackInfo ?: normalizedPlayable.videoTrackInfo,
                    audioTrackInfo = normalizedPlayable.audioTrackInfo.copy(segmentBase = audioSegmentBase),
                    videoRepresentations = videos,
                )
            AppLog.i(
                "QualitySwitch",
                "catalog normalized missingVideo=$missingVideoCount resolvedVideo=${videos.count { it.videoTrackInfo.segmentBase != null }} " +
                    "missingAudio=$missingAudio resolvedAudio=${audioSegmentBase != null} " +
                    "costMs=${android.os.SystemClock.elapsedRealtime() - startedAtMs}",
            )
            resolved
        }
    }

    private fun normalizeAudioTrackInfo(trackInfo: DashTrackInfo, audioKind: DashAudioKind): DashTrackInfo {
        val normalizedMimeType = trackInfo.mimeType?.trim()?.takeIf(String::isNotEmpty) ?: "audio/mp4"
        val normalizedCodecs =
            trackInfo.codecs?.trim()?.takeIf(String::isNotEmpty)
                ?: when (audioKind) {
                    DashAudioKind.NORMAL -> "mp4a.40.2"
                    DashAudioKind.DOLBY -> "ec-3"
                    DashAudioKind.FLAC -> "fLaC"
                }
        return trackInfo.copy(mimeType = normalizedMimeType, codecs = normalizedCodecs)
    }

    private fun probeCandidates(
        candidates: List<String>,
        profile: VideoMediaRequestProfile,
    ): DashSegmentBase? {
        val client =
            when (profile) {
                VideoMediaRequestProfile.WEB -> BiliClient.cdnOkHttp
                VideoMediaRequestProfile.APP -> BiliClient.appCdnOkHttp
            }
        candidates.asSequence().map(String::trim).filter(String::isNotBlank).forEach { url ->
            PROBE_BYTE_LIMITS.forEach { byteLimit ->
                probeUrl(client = client, url = url, byteLimit = byteLimit)?.let { return it }
            }
        }
        return null
    }

    private fun probeUrl(client: OkHttpClient, url: String, byteLimit: Int): DashSegmentBase? {
        val request =
            Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${byteLimit - 1}")
                .header("Accept-Encoding", "identity")
                .get()
                .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                parseSegmentBase(readAtMost(body.byteStream(), byteLimit))
            }
        }.getOrNull()
    }

    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val output = ByteArray(limit)
        var offset = 0
        while (offset < limit) {
            val read = input.read(output, offset, limit - offset)
            if (read <= 0) break
            offset += read
        }
        return output.copyOf(offset)
    }

    internal fun parseSegmentBase(bytes: ByteArray): DashSegmentBase? {
        var offset = 0L
        while (offset + 8L <= bytes.size.toLong()) {
            val base = offset.toInt()
            val size32 = readUInt32(bytes, base)
            val type = String(bytes, base + 4, 4, Charsets.US_ASCII)
            val headerSize: Long
            val boxSize: Long
            when (size32) {
                0L -> return null
                1L -> {
                    if (offset + 16L > bytes.size.toLong()) return null
                    headerSize = 16L
                    boxSize = readUInt64(bytes, base + 8)
                }
                else -> {
                    headerSize = 8L
                    boxSize = size32
                }
            }
            if (boxSize < headerSize) return null
            val boxEnd = offset + boxSize
            if (type == "sidx") {
                if (offset <= 0L || boxEnd > bytes.size.toLong()) return null
                return DashSegmentBase(
                    initialization = "0-${offset - 1L}",
                    indexRange = "$offset-${boxEnd - 1L}",
                )
            }
            if (boxEnd <= offset) return null
            offset = boxEnd
        }
        return null
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) return -1L
        return ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
    }

    private fun readUInt64(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 8 > bytes.size) return -1L
        var value = 0L
        repeat(8) { index ->
            val byte = bytes[offset + index].toLong() and 0xFFL
            if (value > (Long.MAX_VALUE ushr 8)) return -1L
            value = (value shl 8) or byte
        }
        return value
    }

    private val PROBE_BYTE_LIMITS = intArrayOf(64 * 1024, 256 * 1024)
    private const val MAX_CONCURRENT_PROBES = 4
}
