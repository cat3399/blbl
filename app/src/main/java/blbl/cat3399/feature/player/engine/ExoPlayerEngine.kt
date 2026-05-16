@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player.engine

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.ParsingLoadable
import blbl.cat3399.core.api.video.VideoMediaRequestProfile
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.CdnFailoverDataSourceFactory
import blbl.cat3399.feature.player.CdnFailoverState
import blbl.cat3399.feature.player.DebugStreamKind
import blbl.cat3399.feature.player.Playable
import okhttp3.OkHttpClient
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToLong

internal data class LiveHlsDebugInfo(
    val mediaSequence: Long?,
    val targetDurationSec: Double?,
    val mapUri: String?,
    val segmentCount: Int,
    val recentSegmentCount: Int,
    val recentBitrateBps: Long?,
    val lastSegmentUri: String?,
    val lastSegmentDurationSec: Double?,
    val lastSegmentBytes: Long?,
    val lastSegmentSequence: Long?,
    val lastAuxRaw: String?,
)

internal class ExoPlayerEngine(
    context: Context,
    private val onTransferHost: ((kind: DebugStreamKind, host: String) -> Unit)? = null,
    private val onBytesTransferred: ((kind: DebugStreamKind, bytesTransferred: Long) -> Unit)? = null,
    private val onLiveHlsDebugInfo: ((LiveHlsDebugInfo) -> Unit)? = null,
    audioBalanceLevel: AudioBalanceLevel = AudioBalanceLevel.Off,
) : BlblPlayerEngine {
    private val appContext: Context = context.applicationContext

    private val volumeBalanceProcessor = VolumeBalanceAudioProcessor(level = audioBalanceLevel)
    private val loadControl: DefaultLoadControl =
        DefaultLoadControl.Builder()
            // Keep roughly one forward buffer window behind the playhead so in-buffer seek
            // does not immediately discard media that was already fetched.
            .setBackBuffer(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, true)
            .build()
    private val liveHlsPlaylistParserFactory: HlsPlaylistParserFactory =
        ExtXStartStrippingHlsPlaylistParserFactory(onPlaylistParsed = onLiveHlsDebugInfo)

    val exoPlayer: ExoPlayer =
        ExoPlayer.Builder(context, BlblRenderersFactory(context.applicationContext, volumeBalanceProcessor))
            .setLoadControl(loadControl)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .build()

    private val listeners: MutableSet<BlblPlayerEngine.Listener> = CopyOnWriteArraySet()

    override val kind: PlayerEngineKind = PlayerEngineKind.ExoPlayer
    override val capabilities: EngineCapabilities = EngineCapabilities(subtitlesSupported = true)

    override val playbackState: Int
        get() = exoPlayer.playbackState

    override val isPlaying: Boolean
        get() = exoPlayer.isPlaying

    override var playWhenReady: Boolean
        get() = exoPlayer.playWhenReady
        set(value) {
            exoPlayer.playWhenReady = value
        }

    override val duration: Long
        get() = exoPlayer.duration

    override val currentPosition: Long
        get() = exoPlayer.currentPosition

    override val bufferedPosition: Long
        get() = exoPlayer.bufferedPosition

    override fun setVideoSurface(surface: Surface?) {
        // No-op: ExoPlayer renders through PlayerView.
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    override val playbackSpeed: Float
        get() = exoPlayer.playbackParameters.speed

    override fun setPlaybackSpeed(speed: Float) {
        exoPlayer.setPlaybackSpeed(speed)
    }

    override var repeatMode: Int
        get() = exoPlayer.repeatMode
        set(value) {
            exoPlayer.repeatMode = value
        }

    init {
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    listeners.forEach { it.onPlayerError(error) }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    listeners.forEach { it.onIsPlayingChanged(isPlaying) }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    listeners.forEach { it.onPlaybackStateChanged(playbackState) }
                }

                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    listeners.forEach { it.onPositionDiscontinuity(newPosition.positionMs) }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    listeners.forEach { it.onVideoSizeChanged(videoSize.width, videoSize.height) }
                }
            },
        )
        exoPlayer.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                    listeners.forEach { it.onRenderedFirstFrame() }
                }
            },
        )
    }

    override fun setSource(source: PlaybackSource) {
        when (source) {
            is PlaybackSource.Vod -> {
                setVodPlayable(source.playable, subtitle = source.subtitle)
            }

            is PlaybackSource.Live -> {
                val url = source.url.trim()
                val uri = Uri.parse(url)
                val factory = createCdnFactory(DebugStreamKind.MAIN, urlCandidates = listOf(url))

                val isM3u8 = url.substringBefore('?').trim().lowercase(Locale.US).endsWith(".m3u8")
                if (isM3u8) {
                    val hlsSource =
                        HlsMediaSource.Factory(factory)
                            .setPlaylistParserFactory(liveHlsPlaylistParserFactory)
                            .createMediaSource(MediaItem.fromUri(uri))
                    exoPlayer.setMediaSource(hlsSource)
                } else {
                    val mediaSourceFactory = DefaultMediaSourceFactory(factory)
                    val mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(uri))
                    exoPlayer.setMediaSource(mediaSource)
                }
            }
        }
    }

    override fun prepare() {
        exoPlayer.prepare()
    }

    override fun play() {
        exoPlayer.play()
    }

    override fun pause() {
        exoPlayer.pause()
    }

    override fun stop() {
        exoPlayer.stop()
    }

    override fun release() {
        exoPlayer.release()
    }

    override fun addListener(listener: BlblPlayerEngine.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: BlblPlayerEngine.Listener) {
        listeners.remove(listener)
    }

    fun setAudioBalanceLevel(level: AudioBalanceLevel) {
        volumeBalanceProcessor.setLevel(level)
    }

    private fun createCdnFactory(
        kind: DebugStreamKind,
        urlCandidates: List<String>? = null,
        mediaRequestProfile: VideoMediaRequestProfile = VideoMediaRequestProfile.WEB,
    ): DataSource.Factory {
        val listener =
            object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                    val host = dataSpec.uri.host?.trim().orEmpty()
                    if (host.isBlank()) return
                    onTransferHost?.invoke(kind, host.lowercase(Locale.US))
                }

                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                    if (!isNetwork || bytesTransferred <= 0) return
                    onBytesTransferred?.invoke(kind, bytesTransferred.toLong())
                }

                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            }

        val client =
            when (mediaRequestProfile) {
                VideoMediaRequestProfile.WEB -> BiliClient.cdnOkHttp
                VideoMediaRequestProfile.APP -> BiliClient.appCdnOkHttp
            }
        val upstream = OkHttpDataSource.Factory(client).setTransferListener(listener)
        val uris =
            urlCandidates
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .map { Uri.parse(it) }
        if (uris.size <= 1) return upstream
        return CdnFailoverDataSourceFactory(upstreamFactory = upstream, state = CdnFailoverState(kind = kind, candidates = uris))
    }

    private fun buildMerged(
        videoFactory: DataSource.Factory,
        audioFactory: DataSource.Factory,
        videoUrl: String,
        audioUrl: String,
        subtitle: MediaItem.SubtitleConfiguration?,
    ): MediaSource {
        val subs = listOfNotNull(subtitle)
        val videoSource =
            DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, videoFactory))
                .createMediaSource(
                    MediaItem.Builder().setUri(Uri.parse(videoUrl)).setSubtitleConfigurations(subs).build(),
                )
        val audioSource =
            DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, audioFactory))
                .createMediaSource(
                    MediaItem.Builder().setUri(Uri.parse(audioUrl)).build(),
                )
        return MergingMediaSource(true, true, videoSource, audioSource)
    }

    private fun buildProgressive(factory: DataSource.Factory, url: String, subtitle: MediaItem.SubtitleConfiguration?): MediaSource {
        val subs = listOfNotNull(subtitle)
        val item =
            MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setSubtitleConfigurations(subs)
                .build()
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, factory)).createMediaSource(item)
    }

    private fun setVodPlayable(playable: Playable, subtitle: MediaItem.SubtitleConfiguration?) {
        when (playable) {
            is Playable.Dash -> {
                val videoFactory =
                    createCdnFactory(
                        DebugStreamKind.VIDEO,
                        urlCandidates = playable.videoUrlCandidates,
                        mediaRequestProfile = playable.videoMediaRequestProfile,
                    )
                val audioFactory =
                    createCdnFactory(
                        DebugStreamKind.AUDIO,
                        urlCandidates = playable.audioUrlCandidates,
                        mediaRequestProfile = playable.audioMediaRequestProfile,
                    )
                exoPlayer.setMediaSource(buildMerged(videoFactory, audioFactory, playable.videoUrl, playable.audioUrl, subtitle))
            }

            is Playable.VideoOnly -> {
                val mainFactory =
                    createCdnFactory(
                        DebugStreamKind.MAIN,
                        urlCandidates = playable.videoUrlCandidates,
                        mediaRequestProfile = playable.videoMediaRequestProfile,
                    )
                exoPlayer.setMediaSource(buildProgressive(mainFactory, playable.videoUrl, subtitle))
            }

            is Playable.Progressive -> {
                val mainFactory =
                    createCdnFactory(
                        DebugStreamKind.MAIN,
                        urlCandidates = playable.urlCandidates,
                        mediaRequestProfile = playable.mediaRequestProfile,
                    )
                exoPlayer.setMediaSource(buildProgressive(mainFactory, playable.url, subtitle))
            }
        }
    }
}

private class ExtXStartStrippingHlsPlaylistParserFactory(
    private val delegate: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
    private val onPlaylistParsed: ((LiveHlsDebugInfo) -> Unit)? = null,
) : HlsPlaylistParserFactory {
    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
        return ExtXStartStrippingParser(delegate.createPlaylistParser(), onPlaylistParsed = onPlaylistParsed)
    }

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> {
        return ExtXStartStrippingParser(
            delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist),
            onPlaylistParsed = onPlaylistParsed,
        )
    }
}

private class ExtXStartStrippingParser(
    private val delegate: ParsingLoadable.Parser<HlsPlaylist>,
    private val onPlaylistParsed: ((LiveHlsDebugInfo) -> Unit)? = null,
) : ParsingLoadable.Parser<HlsPlaylist> {
    override fun parse(uri: Uri, inputStream: InputStream): HlsPlaylist {
        val bytes = inputStream.readBytes()
        val text = String(bytes, Charsets.UTF_8)
        parseLiveHlsDebugInfo(text = text)?.let { info -> onPlaylistParsed?.invoke(info) }
        if (!text.contains("#EXT-X-START", ignoreCase = true)) {
            return delegate.parse(uri, ByteArrayInputStream(bytes))
        }

        val filtered =
            text
                .lineSequence()
                .filterNot { it.trimStart().startsWith("#EXT-X-START", ignoreCase = true) }
                .joinToString("\n")
        return delegate.parse(uri, ByteArrayInputStream(filtered.toByteArray(Charsets.UTF_8)))
    }
}

private data class ParsedLiveHlsSegment(
    val durationSec: Double,
    val uri: String,
    val bytes: Long?,
    val auxRaw: String?,
)

private fun parseLiveHlsDebugInfo(text: String): LiveHlsDebugInfo? {
    var mediaSequence: Long? = null
    var targetDurationSec: Double? = null
    var mapUri: String? = null
    var pendingDurationSec: Double? = null
    var pendingAuxRaw: String? = null
    val segments = ArrayList<ParsedLiveHlsSegment>()

    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isBlank()) continue
        when {
            line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                mediaSequence = line.substringAfter(':').trim().toLongOrNull()
            }

            line.startsWith("#EXT-X-TARGETDURATION:", ignoreCase = true) -> {
                targetDurationSec = line.substringAfter(':').trim().toDoubleOrNull()
            }

            line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                mapUri = parseHlsQuotedAttr(line = line, key = "URI")
            }

            line.startsWith("#EXT-BILI-AUX:", ignoreCase = true) -> {
                pendingAuxRaw = line.substringAfter(':').trim().ifBlank { null }
            }

            line.startsWith("#EXTINF:", ignoreCase = true) -> {
                pendingDurationSec = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
            }

            line.startsWith("#") -> Unit

            else -> {
                val durationSec = pendingDurationSec
                if (durationSec != null && durationSec > 0.0) {
                    segments +=
                        ParsedLiveHlsSegment(
                            durationSec = durationSec,
                            uri = line,
                            bytes = parseLiveHlsAuxBytes(pendingAuxRaw),
                            auxRaw = pendingAuxRaw,
                        )
                }
                pendingDurationSec = null
                pendingAuxRaw = null
            }
        }
    }

    val last = segments.lastOrNull()
    val recentSegments = segments.takeLast(LIVE_HLS_DEBUG_RECENT_SEGMENT_COUNT)
    val recentWithBytes = recentSegments.filter { it.bytes != null && it.durationSec > 0.0 }
    val recentDurationSec = recentWithBytes.sumOf { it.durationSec }
    val recentBytes = recentWithBytes.sumOf { it.bytes ?: 0L }
    val recentBitrateBps =
        if (recentBytes > 0L && recentDurationSec > 0.0) {
            ((recentBytes * 8.0) / recentDurationSec).roundToLong()
        } else {
            null
        }
    val lastSegmentSequence =
        if (mediaSequence != null && last != null) {
            mediaSequence + segments.lastIndex.toLong()
        } else {
            null
        }

    val info =
        LiveHlsDebugInfo(
            mediaSequence = mediaSequence,
            targetDurationSec = targetDurationSec,
            mapUri = mapUri,
            segmentCount = segments.size,
            recentSegmentCount = recentWithBytes.size,
            recentBitrateBps = recentBitrateBps,
            lastSegmentUri = last?.uri,
            lastSegmentDurationSec = last?.durationSec,
            lastSegmentBytes = last?.bytes,
            lastSegmentSequence = lastSegmentSequence,
            lastAuxRaw = last?.auxRaw,
        )
    return if (
        info.mediaSequence != null ||
        info.targetDurationSec != null ||
        !info.mapUri.isNullOrBlank() ||
        !info.lastSegmentUri.isNullOrBlank() ||
        !info.lastAuxRaw.isNullOrBlank()
    ) {
        info
    } else {
        null
    }
}

private fun parseHlsQuotedAttr(line: String, key: String): String? {
    val pattern = Regex("""(?:^|,)${Regex.escape(key)}=\"([^\"]+)\"""")
    return pattern.find(line)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
}

private fun parseLiveHlsAuxBytes(auxRaw: String?): Long? {
    val parts = auxRaw?.split('|') ?: return null
    val value = parts.getOrNull(2)?.trim().orEmpty()
    if (value.isBlank()) return null
    return value.toLongOrNull()
        ?: value.toLongOrNull(radix = 16)
}

private const val LIVE_HLS_DEBUG_RECENT_SEGMENT_COUNT = 3

private class BlblRenderersFactory(
    context: Context,
    private val volumeBalanceProcessor: VolumeBalanceAudioProcessor,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(volumeBalanceProcessor))
            .build()
    }
}
