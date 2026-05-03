package blbl.cat3399.feature.player.engine

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.Player
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.feature.player.Playable
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

internal class IjkPlayerEngine(
    context: Context,
) : BlblPlayerEngine {
    private val appContext: Context = context.applicationContext
    private val listeners: MutableSet<BlblPlayerEngine.Listener> = CopyOnWriteArraySet()

    private var ijk: IjkMediaPlayer? = null
    private var prepared: Boolean = false
    private var buffering: Boolean = false
    private var preparing: Boolean = false
    private var prepareRequested: Boolean = false
    private var pendingSeekMs: Long? = null
    private var visibleSeekPositionMs: Long? = null
    private var visibleSeekStartedAtMs: Long = 0L
    private var visibleSeekClearsOnFirstFrame: Boolean = false

    private var playbackStateInternal: Int = Player.STATE_IDLE
    private var playWhenReadyInternal: Boolean = false
    private var repeatModeInternal: Int = Player.REPEAT_MODE_OFF
    private var playbackSpeedInternal: Float = 1.0f
    private var videoSurface: Surface? = null

    private var source: PlaybackSource? = null
    private var nativeHttpEventCount: Int = 0

    override val kind: PlayerEngineKind = PlayerEngineKind.IjkPlayer
    override val capabilities: EngineCapabilities = EngineCapabilities(subtitlesSupported = false)

    override val playbackState: Int
        get() = playbackStateInternal

    override val isPlaying: Boolean
        get() = playWhenReadyInternal && playbackStateInternal == Player.STATE_READY

    override var playWhenReady: Boolean
        get() = playWhenReadyInternal
        set(value) {
            playWhenReadyInternal = value
            val p = ijk ?: return
            syncPlayWhenReadyToNative(p)
        }

    override val duration: Long
        get() {
            val s = source
            if (s is PlaybackSource.Vod) {
                val d = s.durationMs?.takeIf { it > 0L }
                if (d != null) return d
            }
            return ijk?.duration ?: 0L
        }

    override val currentPosition: Long
        get() = visibleSeekPositionOrNull() ?: ijk?.currentPosition?.coerceAtLeast(0L) ?: 0L

    override val bufferedPosition: Long
        get() {
            val p = ijk ?: return 0L
            visibleSeekPositionOrNull()?.let { seekPosition ->
                return duration.takeIf { it > 0L }?.let { seekPosition.coerceIn(0L, it) } ?: seekPosition
            }
            val pos = p.currentPosition.coerceAtLeast(0L)
            val vCache = p.videoCachedDuration.coerceAtLeast(0L)
            val aCache = p.audioCachedDuration.coerceAtLeast(0L)
            // For DASH (separate A/V), the truly playable buffered window is usually bounded by the slower track.
            // Keep this estimate conservative so diagnostics reflect the real playable window instead of an
            // optimistic union of the two tracks.
            val cachedForwardMs =
                when {
                    vCache > 0L && aCache > 0L -> minOf(vCache, aCache)
                    else -> maxOf(vCache, aCache)
                }.coerceAtMost(MAX_BUFFERED_FORWARD_ESTIMATE_MS)
            val raw =
                if (Long.MAX_VALUE - pos < cachedForwardMs) {
                    Long.MAX_VALUE
                } else {
                    pos + cachedForwardMs
                }
            val dur = duration.takeIf { it > 0L }
            return dur?.let { raw.coerceIn(0L, it) } ?: raw
        }

    override fun setVideoSurface(surface: Surface?) {
        val hadSurface = videoSurface != null
        videoSurface = surface
        val p = ijk ?: return
        runCatching { p.setSurface(surface) }
        val hasSurface = surface != null
        if (hadSurface != hasSurface) {
            AppLog.d("IjkEngine", "surface changed: hasSurface=${if (hasSurface) 1 else 0} prepared=${if (prepared) 1 else 0} preparing=${if (preparing) 1 else 0}")
        }
        startPrepareIfPossible(reason = "surface_set")
    }

    override fun seekTo(positionMs: Long) {
        val p = ijk ?: return
        val pos = positionMs.coerceAtLeast(0L)
        val leavesEndedState = seekLeavesEndedState(pos)
        val vod = source as? PlaybackSource.Vod
        val dash = vod?.playable as? Playable.Dash
        if (!prepared) {
            // For DASH: prefer `seek-at-start` so the next prepare starts at the target position.
            // This works better than issuing `seekTo()` right after prepared for some dashdec paths.
            if (dash != null) {
                pendingSeekMs = null
                runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "seek-at-start", pos) }
                    .onSuccess { markVisibleSeekPosition(pos, clearsOnFirstFrame = true) }
            } else {
                pendingSeekMs = pos
                markVisibleSeekPosition(pos, clearsOnFirstFrame = false)
            }
            return
        }
        if (dash == null) {
            runCatching { p.seekTo(pos) }
                .onSuccess {
                    markVisibleSeekPosition(pos, clearsOnFirstFrame = false)
                    handleSeekLeavesEndedState(p, leavesEndedState)
                }
                .onFailure { clearVisibleSeekPosition() }
            return
        }

        if (BuildConfig.DEBUG) {
            val buf = bufferedPosition
            val vCache = p.videoCachedDuration.coerceAtLeast(0L)
            val aCache = p.audioCachedDuration.coerceAtLeast(0L)
            AppLog.i(
                "IjkEngine",
                "seek dash to=${pos}ms cur=${p.currentPosition.coerceAtLeast(0L)}ms buf=${buf}ms vCache=${vCache}ms aCache=${aCache}ms mode=native",
            )
        }
        runCatching { p.seekTo(pos) }
            .onSuccess {
                markVisibleSeekPosition(pos, clearsOnFirstFrame = false)
                handleSeekLeavesEndedState(p, leavesEndedState)
            }
            .onFailure { clearVisibleSeekPosition() }
    }

    override val playbackSpeed: Float
        get() = playbackSpeedInternal

    override fun setPlaybackSpeed(speed: Float) {
        val v = speed.takeIf { it.isFinite() }?.coerceIn(0.25f, 4.0f) ?: 1.0f
        playbackSpeedInternal = v
        val p = ijk ?: return
        runCatching { p.setSpeed(v) }
    }

    override var repeatMode: Int
        get() = repeatModeInternal
        set(value) {
            repeatModeInternal =
                when (value) {
                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            val p = ijk ?: return
            runCatching { p.setLooping(repeatModeInternal == Player.REPEAT_MODE_ONE) }
        }

    override fun setSource(source: PlaybackSource) {
        this.source = source
        nativeHttpEventCount = 0
        prepared = false
        buffering = false
        preparing = false
        prepareRequested = false
        pendingSeekMs = null
        clearVisibleSeekPosition()
        updateState(Player.STATE_IDLE)

        runCatching { ensurePlayer() }.onFailure { t ->
            listeners.forEach { it.onPlayerError(t) }
            return
        }

        val p = ijk ?: return
        val dataSource = source
        runCatching { p.reset() }.onFailure { AppLog.w("IjkEngine", "reset failed", it) }
        prepared = false
        buffering = false
        preparing = false
        clearVisibleSeekPosition()
        updateState(Player.STATE_IDLE)

        applyCommonOptions(p)
        runCatching { p.setSurface(videoSurface) }
        runCatching { p.setLooping(repeatModeInternal == Player.REPEAT_MODE_ONE) }
        runCatching { p.setSpeed(playbackSpeedInternal) }

        try {
            when (dataSource) {
                is PlaybackSource.Live -> {
                    val headers = buildHttpHeaders(urlForCookie = dataSource.url)
                    applyHttpOptions(p, headers)
                    p.setDataSource(dataSource.url)
                }

                is PlaybackSource.Vod -> {
                    when (val playable = dataSource.playable) {
                        is Playable.Dash -> {
                            val mpdFile =
                                writeDashMpd(
                                    playable,
                                    durationMs = dataSource.durationMs,
                                )
                            if (BuildConfig.DEBUG) {
                                val vLen = playable.videoUrl.length
                                val aLen = playable.audioUrl.length
                                AppLog.i(
                                    "IjkEngine",
                                    "dash source mode=direct mpd=${mpdFile.name} bytes=${mpdFile.length()} vUrlLen=$vLen aUrlLen=$aLen",
                                )
                                if (vLen > 1024 || aLen > 1024) {
                                    AppLog.w(
                                        "IjkEngine",
                                        "DASH segment url is very long (>1024). If playback fails, consider ijkffmpeg dashdec long-url support.",
                                    )
                                }
                            }
                            val headers = buildHttpHeaders(urlForCookie = playable.videoUrl)
                            applyHttpOptions(p, headers)
                            // Use a plain file path to avoid ContentResolver/fd:// schemes.
                            p.setDataSource(mpdFile.absolutePath)
                        }

                        is Playable.VideoOnly -> {
                            val headers = buildHttpHeaders(urlForCookie = playable.videoUrl)
                            applyHttpOptions(p, headers)
                            p.setDataSource(playable.videoUrl)
                        }

                        is Playable.Progressive -> {
                            val headers = buildHttpHeaders(urlForCookie = playable.url)
                            applyHttpOptions(p, headers)
                            p.setDataSource(playable.url)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            listeners.forEach { it.onPlayerError(t) }
        }
    }

    override fun prepare() {
        prepareRequested = true
        startPrepareIfPossible(reason = "explicit_prepare")
    }

    override fun play() {
        playWhenReady = true
    }

    override fun pause() {
        playWhenReady = false
    }

    override fun stop() {
        val p = ijk ?: return
        runCatching { p.stop() }
        prepared = false
        buffering = false
        preparing = false
        prepareRequested = false
        pendingSeekMs = null
        clearVisibleSeekPosition()
        updateState(Player.STATE_IDLE)
    }

    override fun release() {
        source = null
        playWhenReadyInternal = false
        prepared = false
        buffering = false
        preparing = false
        prepareRequested = false
        pendingSeekMs = null
        clearVisibleSeekPosition()
        updateState(Player.STATE_IDLE)

        val p = ijk
        ijk = null
        if (p != null) {
            runCatching { p.resetListeners() }
            runCatching { p.release() }
        }
    }

    override fun addListener(listener: BlblPlayerEngine.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: BlblPlayerEngine.Listener) {
        listeners.remove(listener)
    }

    private fun seekLeavesEndedState(positionMs: Long): Boolean {
        if (playbackStateInternal != Player.STATE_ENDED) return false
        val d = duration
        return d <= 0L || positionMs < d
    }

    private fun markVisibleSeekPosition(positionMs: Long, clearsOnFirstFrame: Boolean) {
        visibleSeekPositionMs = positionMs.coerceAtLeast(0L)
        visibleSeekStartedAtMs = SystemClock.elapsedRealtime()
        visibleSeekClearsOnFirstFrame = clearsOnFirstFrame
    }

    private fun clearVisibleSeekPosition() {
        visibleSeekPositionMs = null
        visibleSeekStartedAtMs = 0L
        visibleSeekClearsOnFirstFrame = false
    }

    private fun visibleSeekPositionOrNull(): Long? {
        val position = visibleSeekPositionMs ?: return null
        val elapsedMs = SystemClock.elapsedRealtime() - visibleSeekStartedAtMs
        if (!visibleSeekClearsOnFirstFrame && elapsedMs > VISIBLE_SEEK_POSITION_TIMEOUT_MS) {
            clearVisibleSeekPosition()
            return null
        }
        return position
    }

    private fun handleSeekLeavesEndedState(
        p: IjkMediaPlayer,
        leavesEndedState: Boolean,
    ) {
        if (!leavesEndedState) return
        updateState(Player.STATE_BUFFERING)
        syncPlayWhenReadyToNative(p)
    }

    private fun settleSeekIfReady(p: IjkMediaPlayer) {
        if (prepared && !buffering && playbackStateInternal == Player.STATE_BUFFERING) {
            updateState(Player.STATE_READY)
        }
        if (playbackStateInternal == Player.STATE_READY) {
            syncPlayWhenReadyToNative(p)
        } else {
            notifyIsPlayingIfChanged()
        }
    }

    private fun syncPlayWhenReadyToNative(p: IjkMediaPlayer) {
        if (prepared) {
            runCatching {
                if (playWhenReadyInternal) {
                    p.start()
                } else {
                    p.pause()
                }
            }.onFailure { AppLog.w("IjkEngine", "sync playWhenReady failed", it) }
        }
        notifyIsPlayingIfChanged()
    }

    private fun updateState(state: Int) {
        if (playbackStateInternal == state) {
            notifyIsPlayingIfChanged()
            return
        }
        playbackStateInternal = state
        listeners.forEach { it.onPlaybackStateChanged(state) }
        notifyIsPlayingIfChanged()
    }

    private var lastNotifiedIsPlaying: Boolean? = null

    private fun notifyIsPlayingIfChanged() {
        val now = isPlaying
        if (lastNotifiedIsPlaying == now) return
        lastNotifiedIsPlaying = now
        listeners.forEach { it.onIsPlayingChanged(now) }
    }

    private fun ensurePlayer() {
        if (ijk != null) return
        val libLoader = IjkPlayerPlugin.libLoaderOrNull(appContext) ?: throw IjkPluginNotInstalledException()
        runCatching { IjkMediaPlayer.loadLibrariesOnce(libLoader) }
            .onFailure { t ->
                AppLog.w("IjkEngine", "loadLibrariesOnce failed", t)
                throw t
            }
        runCatching { IjkMediaPlayer.native_profileBegin("libijkplayer.so") }
        ijk =
            runCatching { IjkMediaPlayer(libLoader) }
                .getOrElse { t ->
                    AppLog.w("IjkEngine", "create IjkMediaPlayer failed", t)
                    throw t
                }.also { p ->
            p.setOnPreparedListener(
                IMediaPlayer.OnPreparedListener {
                    prepared = true
                    buffering = false
                    preparing = false
                    val preparedSeekMs = pendingSeekMs?.coerceAtLeast(0L)
                    if (preparedSeekMs != null) {
                        markVisibleSeekPosition(preparedSeekMs, clearsOnFirstFrame = false)
                    }
                    updateState(Player.STATE_READY)
                    runCatching { p.setLooping(repeatModeInternal == Player.REPEAT_MODE_ONE) }
                    runCatching { p.setSpeed(playbackSpeedInternal) }
                    preparedSeekMs?.let { target ->
                        pendingSeekMs = null
                        runCatching { p.seekTo(target) }
                            .onFailure { clearVisibleSeekPosition() }
                    }
                    syncPlayWhenReadyToNative(p)
                },
            )
            p.setOnCompletionListener(
                IMediaPlayer.OnCompletionListener {
                    prepared = true
                    buffering = false
                    preparing = false
                    clearVisibleSeekPosition()
                    updateState(Player.STATE_ENDED)
                    notifyIsPlayingIfChanged()
                },
            )
            p.setOnErrorListener(
                IMediaPlayer.OnErrorListener { _, what, extra ->
                    val e = IjkPlayerErrorException(what = what, extra = extra)
                    prepared = false
                    buffering = false
                    preparing = false
                    clearVisibleSeekPosition()
                    updateState(Player.STATE_IDLE)
                    listeners.forEach { it.onPlayerError(e) }
                    true
                },
            )
            p.setOnInfoListener(
                IMediaPlayer.OnInfoListener { _, what, _ ->
                    when (what) {
                        IMediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            buffering = true
                            updateState(Player.STATE_BUFFERING)
                        }

                        IMediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                            buffering = false
                            updateState(if (prepared) Player.STATE_READY else Player.STATE_BUFFERING)
                            syncPlayWhenReadyToNative(p)
                        }

                        IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                            if (visibleSeekClearsOnFirstFrame) clearVisibleSeekPosition()
                            listeners.forEach { it.onRenderedFirstFrame() }
                        }

                        IMediaPlayer.MEDIA_INFO_MEDIA_ACCURATE_SEEK_COMPLETE,
                        IMediaPlayer.MEDIA_INFO_VIDEO_SEEK_RENDERING_START,
                        IMediaPlayer.MEDIA_INFO_AUDIO_SEEK_RENDERING_START,
                        -> {
                            clearVisibleSeekPosition()
                            listeners.forEach { it.onPositionDiscontinuity(currentPosition) }
                            settleSeekIfReady(p)
                        }
                    }
                    false
                },
            )
            p.setOnSeekCompleteListener(
                IMediaPlayer.OnSeekCompleteListener {
                    listeners.forEach { it.onPositionDiscontinuity(currentPosition) }
                    settleSeekIfReady(p)
                },
            )
            p.setOnVideoSizeChangedListener(
                IMediaPlayer.OnVideoSizeChangedListener { _, width, height, _, _ ->
                    val w = width.coerceAtLeast(0)
                    val h = height.coerceAtLeast(0)
                    listeners.forEach { it.onVideoSizeChanged(w, h) }
                },
            )
            p.setOnBufferingUpdateListener(
                IMediaPlayer.OnBufferingUpdateListener { _, _ ->
                    // We expose bufferedPosition via cached durations; no-op here.
                },
            )

            if (BuildConfig.DEBUG) {
                p.setOnNativeInvokeListener(
                    IjkMediaPlayer.OnNativeInvokeListener { what, args ->
                        logNativeHttpEvent(what, args)
                        false
                    },
                )
                // Keep DASH/native playback diagnostics visible while validating custom ijk builds.
                runCatching { IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_DEBUG) }
            }
        }
    }

    private fun applyCommonOptions(p: IjkMediaPlayer) {
        // Playback flags.
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-avc", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "seek-at-start", 0L) }
        // Network flags.
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 20_000_000L) } // us
        // Allow local MPD (`file://`) to reference remote segments (`http(s)://`).
        runCatching {
            p.setOption(
                IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                "protocol_whitelist",
                // Keep consistent with IjkMediaPlayer#setDataSource(url, headers) defaults.
                // Some ijk builds may rewrite URLs to these schemes internally (e.g. long-url / url-hook).
                "async,cache,crypto,file,http,https,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data",
            )
        }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "allowed_extensions", "ALL") }
    }

    internal data class IjkDebugSnapshot(
        val playbackState: Int,
        val isPlaying: Boolean,
        val playWhenReady: Boolean,
        val playbackSpeed: Float,
        val durationMs: Long,
        val positionMs: Long,
        val bufferedPositionMs: Long,
        val videoWidth: Int?,
        val videoHeight: Int?,
        val videoDecoder: Int?,
        val fpsDecode: Float?,
        val fpsOutput: Float?,
        val bitRate: Long,
        val tcpSpeed: Long,
        val videoCachedDurationMs: Long,
        val audioCachedDurationMs: Long,
    )

    internal fun debugSnapshot(): IjkDebugSnapshot {
        val p = ijk
        val videoW = p?.videoWidth?.takeIf { it > 0 }
        val videoH = p?.videoHeight?.takeIf { it > 0 }
        val decoder = p?.videoDecoder
        val fpsOut = p?.videoOutputFramesPerSecond?.takeIf { it > 0f }
        val fpsDec = p?.videoDecodeFramesPerSecond?.takeIf { it > 0f }
        val bitRate = p?.bitRate?.coerceAtLeast(0L) ?: 0L
        val tcpSpeed = p?.tcpSpeed?.coerceAtLeast(0L) ?: 0L
        val vCache = p?.videoCachedDuration?.coerceAtLeast(0L) ?: 0L
        val aCache = p?.audioCachedDuration?.coerceAtLeast(0L) ?: 0L

        return IjkDebugSnapshot(
            playbackState = playbackStateInternal,
            isPlaying = isPlaying,
            playWhenReady = playWhenReadyInternal,
            playbackSpeed = playbackSpeedInternal,
            durationMs = duration.coerceAtLeast(0L),
            positionMs = currentPosition.coerceAtLeast(0L),
            bufferedPositionMs = bufferedPosition.coerceAtLeast(0L),
            videoWidth = videoW,
            videoHeight = videoH,
            videoDecoder = decoder,
            fpsDecode = fpsDec,
            fpsOutput = fpsOut,
            bitRate = bitRate,
            tcpSpeed = tcpSpeed,
            videoCachedDurationMs = vCache,
            audioCachedDurationMs = aCache,
        )
    }

    private data class BuiltHttpHeaders(
        val userAgent: String,
        val referer: String,
        val cookie: String?,
        val headersString: String,
    )

    private fun buildHttpHeaders(urlForCookie: String): BuiltHttpHeaders {
        val userAgent = BiliClient.prefs.userAgent.trim().ifBlank { blbl.cat3399.core.prefs.AppPrefs.DEFAULT_UA }
        val referer = "https://www.bilibili.com/"
        val cookie = BiliClient.cookies.cookieHeaderFor(urlForCookie)?.trim().takeIf { !it.isNullOrBlank() }

        // NOTE:
        // - Keep User-Agent in `user_agent` option (not in custom headers) to avoid duplicates.
        // - Keep Referer in custom headers for old cores, and also set `referer` explicitly for patched dashdec.
        val headersString =
            buildString {
                append("Referer: ").append(referer).append("\r\n")
                append("Accept-Encoding: identity\r\n")
            }
        return BuiltHttpHeaders(
            userAgent = userAgent,
            referer = referer,
            cookie = cookie,
            headersString = headersString,
        )
    }

    private fun applyHttpOptions(p: IjkMediaPlayer, headers: BuiltHttpHeaders) {
        // Prefer option-based UA so DASH sub-requests (init/m4s) can inherit it.
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", headers.userAgent) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user-agent", headers.userAgent) }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "referer", headers.referer) }
        headers.cookie?.let { cookie ->
            runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cookies", cookie) }
        }
        runCatching { p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", headers.headersString) }

        if (BuildConfig.DEBUG) {
            AppLog.i(
                "IjkEngine",
                "http opts: uaLen=${headers.userAgent.length} referer=${headers.referer} cookie=${if (headers.cookie != null) 1 else 0}",
            )
        }
    }

    private fun logNativeHttpEvent(what: Int, args: Bundle?) {
        val bundle = args ?: return
        runCatching {
            val url = bundle.getString(IjkMediaPlayer.OnNativeInvokeListener.ARG_URL).orEmpty()
            val httpCode = bundle.getInt(IjkMediaPlayer.OnNativeInvokeListener.ARG_HTTP_CODE, -1)
            val error = bundle.getInt(IjkMediaPlayer.OnNativeInvokeListener.ARG_ERROR, 0)
            val offset = bundle.getLong(IjkMediaPlayer.OnNativeInvokeListener.ARG_OFFSET, -1L)

            when (what) {
                IjkMediaPlayer.OnNativeInvokeListener.EVENT_WILL_HTTP_OPEN,
                IjkMediaPlayer.OnNativeInvokeListener.CTRL_WILL_HTTP_OPEN,
                -> {
                    if (nativeHttpEventCount < 12) {
                        nativeHttpEventCount++
                        AppLog.d(
                            "IjkHttp",
                            "event=$what willOpen urlLen=${url.length}",
                        )
                    }
                }

                IjkMediaPlayer.OnNativeInvokeListener.EVENT_DID_HTTP_OPEN,
                IjkMediaPlayer.OnNativeInvokeListener.EVENT_DID_HTTP_SEEK,
                -> {
                    if (error != 0 || httpCode >= 400) {
                        AppLog.w(
                            "IjkHttp",
                            "event=$what http=$httpCode err=$error off=$offset url=${url.take(220)}",
                        )
                    }
                }
            }
        }
            .onFailure { t ->
                AppLog.w("IjkHttp", "native http event parse failed", t)
            }
    }

    private fun startPrepareIfPossible(reason: String) {
        val p = ijk ?: return
        if (prepared) return
        if (preparing) return
        if (!prepareRequested) return
        if (source == null) return
        if (videoSurface == null) {
            // MediaCodec path requires a valid Surface; defer prepare until surface is attached.
            if (playbackStateInternal != Player.STATE_BUFFERING) {
                AppLog.d("IjkEngine", "prepare deferred (no surface), reason=$reason")
            }
            updateState(Player.STATE_BUFFERING)
            return
        }

        preparing = true
        updateState(Player.STATE_BUFFERING)
        runCatching { p.prepareAsync() }
            .onFailure { t ->
                preparing = false
                clearVisibleSeekPosition()
                updateState(Player.STATE_IDLE)
                listeners.forEach { it.onPlayerError(t) }
            }
    }

    @Throws(IOException::class)
    private fun writeDashMpd(
        playable: Playable.Dash,
        durationMs: Long?,
        videoBaseUrl: String = playable.videoUrl,
        audioBaseUrl: String = playable.audioUrl,
    ): File {
        val mpd =
            DashMpdGenerator.buildOnDemandMpd(
                dash = playable,
                durationMs = durationMs,
                videoBaseUrl = videoBaseUrl,
                audioBaseUrl = audioBaseUrl,
            )
        val videoSeg = playable.videoTrackInfo.segmentBase ?: error("video segment_base missing")
        val audioSeg = playable.audioTrackInfo.segmentBase ?: error("audio segment_base missing")
        val key =
            buildString {
                append(playable.videoUrl)
                append('|')
                append(playable.audioUrl)
                append('|')
                append(videoSeg.initialization)
                append('|')
                append(videoSeg.indexRange)
                append('|')
                append(audioSeg.initialization)
                append('|')
                append(audioSeg.indexRange)
                append('|')
                append(playable.qn)
                append('|')
                append(playable.codecid)
                append('|')
                append(playable.audioId)
                append('|')
                append(durationMs ?: -1L)
            }
        val name = "blbl_dash_${md5Hex(key)}.mpd"
        val file = File(appContext.cacheDir, name)
        file.writeText(mpd, Charsets.UTF_8)
        return file
    }

    private fun md5Hex(text: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(md5.size * 2) {
            md5.forEach { b -> append(String.format(Locale.US, "%02x", b)) }
        }
    }

    internal class IjkPluginNotInstalledException : RuntimeException("IjkPlayer 插件未安装，请先下载插件或切回 ExoPlayer")

    private class IjkPlayerErrorException(
        val what: Int,
        val extra: Int,
    ) : RuntimeException("IjkMediaPlayer error what=$what extra=$extra")

    private companion object {
        private const val MAX_BUFFERED_FORWARD_ESTIMATE_MS: Long = 5 * 60_000L
        private const val VISIBLE_SEEK_POSITION_TIMEOUT_MS: Long = 10_000L
    }
}
