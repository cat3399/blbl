package blbl.cat3399.feature.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.ui.CaptionStyleCompat
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.DanmakuShield
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.tv.TvMode
import blbl.cat3399.core.ui.ActivityStackLimiter
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.SingleChoiceDialog
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.feature.following.UpDetailActivity
import blbl.cat3399.feature.settings.SettingsActivity
import blbl.cat3399.databinding.ActivityPlayerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var debugJob: kotlinx.coroutines.Job? = null
    private var progressJob: kotlinx.coroutines.Job? = null
    private var autoResumeJob: kotlinx.coroutines.Job? = null
    private var autoResumeHintTimeoutJob: kotlinx.coroutines.Job? = null
    private var autoResumeHintVisible: Boolean = false
    private var reportProgressJob: kotlinx.coroutines.Job? = null
    private var autoHideJob: kotlinx.coroutines.Job? = null
    private var holdSeekJob: kotlinx.coroutines.Job? = null
    private var seekHintJob: kotlinx.coroutines.Job? = null
    private var keyScrubEndJob: kotlinx.coroutines.Job? = null
    private var scrubbing: Boolean = false
    private var controlsVisible: Boolean = false
    private var lastInteractionAtMs: Long = 0L
    private var lastBackAtMs: Long = 0L
    private var finishOnBackKeyUp: Boolean = false
    private var holdPrevSpeed: Float = 1.0f
    private var holdPrevPlayWhenReady: Boolean = false
    private var loadJob: kotlinx.coroutines.Job? = null
    private var lastEndedActionAtMs: Long = 0L
    private var playbackUncaughtHandler: CoroutineExceptionHandler? = null

    private var smartSeekDirection: Int = 0
    private var smartSeekStreak: Int = 0
    private var smartSeekLastAtMs: Long = 0L
    private var smartSeekTotalMs: Long = 0L
    private var tapSeekActiveDirection: Int = 0
    private var tapSeekActiveUntilMs: Long = 0L
    private var riskControlBypassHintShown: Boolean = false

    private var currentBvid: String = ""
    private var currentCid: Long = -1L
    private var currentEpId: Long? = null
    private var currentAid: Long? = null
    private var currentUpMid: Long = 0L
    private var currentUpName: String? = null
    private var currentUpAvatar: String? = null

    private var playlistToken: String? = null
    private var playlistItems: List<PlayerPlaylistItem> = emptyList()
    private var playlistIndex: Int = -1
    private lateinit var session: PlayerSessionSettings
    private var subtitleAvailable: Boolean = false
    private var subtitleConfig: MediaItem.SubtitleConfiguration? = null
    private var subtitleItems: List<SubtitleItem> = emptyList()
    private var lastAvailableQns: List<Int> = emptyList()
    private var danmakuSegmentSizeMs: Int = DANMAKU_DEFAULT_SEGMENT_MS
    private var danmakuSegmentTotal: Int = 0
    private var danmakuShield: DanmakuShield? = null
    private val danmakuLoadedSegments = LinkedHashSet<Int>()
    private val danmakuLoadingSegments = HashSet<Int>()
    private val danmakuAll = ArrayList<blbl.cat3399.core.model.Danmaku>()
    private var lastDanmakuPrefetchAtMs: Long = 0L
    private var playbackConstraints: PlaybackConstraints = PlaybackConstraints()
    private var decodeFallbackAttempted: Boolean = false
    private var lastPickedDash: Playable.Dash? = null
    private var autoResumeToken: Int = 0
    private var autoResumeCancelledByUser: Boolean = false
    private var reportToken: Int = 0
    private var lastReportAtMs: Long = 0L
    private var lastReportedProgressSec: Long = -1L

    private class PlaybackTrace(private val id: String) {
        private val startMs = SystemClock.elapsedRealtime()
        private var lastMs = startMs

        fun log(stage: String, extra: String = "") {
            val now = SystemClock.elapsedRealtime()
            val total = now - startMs
            val delta = now - lastMs
            lastMs = now
            val suffix = if (extra.isBlank()) "" else " $extra"
            // Keep tag consistent with existing Player logs; AppLog already prefixes with "BLBL/".
            AppLog.i("Player", "traceId=$id +${total}ms (+${delta}ms) $stage$suffix")
        }
    }

    private var trace: PlaybackTrace? = null
    private var traceFirstFrameLogged: Boolean = false

    private data class ResumeCandidate(
        val rawTime: Long,
        val rawTimeUnitHint: RawTimeUnitHint,
        val source: String,
    )

    private enum class RawTimeUnitHint {
        UNKNOWN,
        SECONDS_LIKELY,
        MILLIS_LIKELY,
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityStackLimiter.register(group = ACTIVITY_STACK_GROUP, activity = this, maxDepth = ACTIVITY_STACK_MAX_DEPTH)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        applyUiMode()

        binding.topBar.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.tvSeekHint.visibility = View.GONE
        binding.btnBack.setOnClickListener { finish() }
        binding.tvOnline.text = "-人正在观看"

        val bvid = intent.getStringExtra(EXTRA_BVID).orEmpty()
        val cidExtra = intent.getLongExtra(EXTRA_CID, -1L).takeIf { it > 0 }
        val epIdExtra = intent.getLongExtra(EXTRA_EP_ID, -1L).takeIf { it > 0 }
        val aidExtra = intent.getLongExtra(EXTRA_AID, -1L).takeIf { it > 0 }
        playlistToken = intent.getStringExtra(EXTRA_PLAYLIST_TOKEN)?.trim()?.takeIf { it.isNotBlank() }
        playlistIndex = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, -1)
        playlistToken?.let { token ->
            val p = PlayerPlaylistStore.get(token)
            if (p != null && p.items.isNotEmpty()) {
                playlistItems = p.items
                val idx = playlistIndex.takeIf { it in playlistItems.indices } ?: p.index
                playlistIndex = idx.coerceIn(0, playlistItems.lastIndex)
                PlayerPlaylistStore.updateIndex(token, playlistIndex)
            }
        }
        trace =
            PlaybackTrace(
                buildString {
                    append(bvid.takeLast(8).ifBlank { "unknown" })
                    append('-')
                    append((System.currentTimeMillis() and 0xFFFF).toString(16))
                },
            ).also { it.log("activity:onCreate") }
        if (bvid.isBlank()) {
            Toast.makeText(this, "缺少 bvid", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentBvid = bvid
        currentEpId = epIdExtra
        currentAid = aidExtra

        val prefs = BiliClient.prefs
        session = PlayerSessionSettings(
            playbackSpeed = prefs.playerSpeed,
            preferCodec = prefs.playerPreferredCodec,
            preferAudioId = prefs.playerPreferredAudioId,
            preferredQn = prefs.playerPreferredQn,
            targetQn = 0,
            playbackModeOverride = null,
            subtitleEnabled = prefs.subtitleEnabledDefault,
            subtitleLangOverride = null,
            danmaku = DanmakuSessionSettings(
                enabled = prefs.danmakuEnabled,
                opacity = prefs.danmakuOpacity,
                textSizeSp = prefs.danmakuTextSizeSp,
                speedLevel = prefs.danmakuSpeed,
                area = prefs.danmakuArea,
            ),
            debugEnabled = prefs.playerDebugEnabled,
        )

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        binding.playerView.player = exo
        trace?.log("exo:created")
        binding.danmakuView.setPositionProvider { exo.currentPosition }
        binding.danmakuView.setConfigProvider { session.danmaku.toConfig() }
        configureSubtitleView()
        exo.setPlaybackSpeed(session.playbackSpeed)
        applyPlaybackMode(exo)
        // Subtitle enabled state follows session (default from global prefs).
        applySubtitleEnabled(exo)
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                AppLog.e("Player", "onPlayerError", error)
                trace?.log("exo:error", "type=${error.errorCodeName}")
                val picked = lastPickedDash
                if (
                    picked != null &&
                    !decodeFallbackAttempted &&
                    picked.shouldAttemptDolbyFallback() &&
                    isLikelyCodecUnsupported(error)
                ) {
                    val nextConstraints = nextPlaybackConstraintsForDolbyFallback(picked)
                    if (nextConstraints != null) {
                        decodeFallbackAttempted = true
                        playbackConstraints = nextConstraints
                        Toast.makeText(this@PlayerActivity, "杜比/无损解码失败，尝试回退到普通轨道…", Toast.LENGTH_SHORT).show()
                        reloadStream(keepPosition = true, resetConstraints = false)
                        return
                    }
                }
                Toast.makeText(this@PlayerActivity, "播放失败：${error.errorCodeName}", Toast.LENGTH_SHORT).show()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon(isPlaying)
                restartAutoHideTimer()
                // DanmakuView stops its own vsync loop when playback is paused; kick it on state changes.
                binding.danmakuView.invalidate()
                if (isPlaying) {
                    startReportProgressLoop()
                } else {
                    stopReportProgressLoop(flush = true, reason = "pause")
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateProgressUi()
                val state =
                    when (playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> playbackState.toString()
                    }
                trace?.log("exo:state", "state=$state pos=${exo.currentPosition}ms")
                if (playbackState == Player.STATE_ENDED) {
                    stopReportProgressLoop(flush = true, reason = "ended")
                    handlePlaybackEnded(exo)
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // Rely on ExoPlayer discontinuity callbacks to re-sync danmaku.
                // Avoid doing "big jump" heuristics inside DanmakuView (which can be triggered by UI jank).
                binding.danmakuView.notifySeek(newPosition.positionMs)
                requestDanmakuSegmentsForPosition(newPosition.positionMs, immediate = true)
            }

        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: EventTime, output: Any, renderTimeMs: Long) {
                if (traceFirstFrameLogged) return
                traceFirstFrameLogged = true
                trace?.log("exo:firstFrame", "pos=${exo.currentPosition}ms")
            }
        })

        val settingsAdapter = PlayerSettingsAdapter { item ->
            when (item.title) {
                "分辨率" -> showResolutionDialog()
                "视频编码" -> showCodecDialog()
                "播放速度" -> showSpeedDialog()
                "播放模式" -> showPlaybackModeDialog()
                "字幕语言" -> showSubtitleLangDialog()
                "弹幕透明度" -> showDanmakuOpacityDialog()
                "弹幕字号" -> showDanmakuTextSizeDialog()
                "弹幕速度" -> showDanmakuSpeedDialog()
                "弹幕区域" -> showDanmakuAreaDialog()
                "调试信息" -> {
                    session = session.copy(debugEnabled = !session.debugEnabled)
                    updateDebugOverlay()
                    (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
                }
                else -> Toast.makeText(this, "暂未实现：${item.title}", Toast.LENGTH_SHORT).show()
            }
        }
        binding.recyclerSettings.adapter = settingsAdapter
        binding.recyclerSettings.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        refreshSettings(settingsAdapter)
        updateDebugOverlay()

        initControls(exo)

        val uncaughtHandler =
            CoroutineExceptionHandler { _, t ->
                AppLog.e("Player", "uncaught", t)
                Toast.makeText(this@PlayerActivity, "播放失败：${t.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        playbackUncaughtHandler = uncaughtHandler
        startPlayback(
            bvid = bvid,
            cidExtra = cidExtra,
            epIdExtra = epIdExtra,
            aidExtra = aidExtra,
            initialTitle = playlistItems.getOrNull(playlistIndex)?.title,
        )
    }

    private data class PlayFetchResult(
        val json: JSONObject,
        val playable: Playable,
    )

    private fun updatePlaylistControls() {
        val enabled = playlistItems.size >= 2 && playlistIndex in playlistItems.indices
        val alpha = if (enabled) 1.0f else 0.35f
        binding.btnPrev.isEnabled = enabled
        binding.btnNext.isEnabled = enabled
        binding.btnPrev.alpha = alpha
        binding.btnNext.alpha = alpha
    }

    private fun updateUpButton() {
        val enabled = currentUpMid > 0L
        val alpha = if (enabled) 1.0f else 0.35f
        binding.btnUp.isEnabled = enabled
        binding.btnUp.alpha = alpha
    }

    private fun resolvedPlaybackMode(): String {
        val prefs = BiliClient.prefs
        return session.playbackModeOverride ?: prefs.playerPlaybackMode
    }

    private fun playbackModeLabel(code: String): String = when (code) {
        AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> "循环当前"
        AppPrefs.PLAYER_PLAYBACK_MODE_NEXT -> "播放下一个"
        AppPrefs.PLAYER_PLAYBACK_MODE_EXIT -> "退出播放器"
        else -> "什么都不做"
    }

    private fun playbackModeSubtitle(): String {
        val prefs = BiliClient.prefs
        val globalLabel = playbackModeLabel(prefs.playerPlaybackMode)
        val override = session.playbackModeOverride
        return if (override == null) {
            "全局：$globalLabel"
        } else {
            playbackModeLabel(override)
        }
    }

    private fun applyPlaybackMode(exo: ExoPlayer) {
        exo.repeatMode =
            when (resolvedPlaybackMode()) {
                AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
    }

    private fun showPlaybackModeDialog() {
        val exo = player ?: return
        val prefs = BiliClient.prefs
        val global = prefs.playerPlaybackMode
        val globalLabel = playbackModeLabel(global)
        val items = listOf(
            "跟随全局（$globalLabel）",
            "循环当前",
            "播放下一个",
            "什么都不做",
            "退出播放器",
        )
        val currentLabel =
            when (session.playbackModeOverride) {
                null -> "跟随全局（$globalLabel）"
                AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> "循环当前"
                AppPrefs.PLAYER_PLAYBACK_MODE_NEXT -> "播放下一个"
                AppPrefs.PLAYER_PLAYBACK_MODE_EXIT -> "退出播放器"
                else -> "什么都不做"
            }
        val checked = items.indexOf(currentLabel).coerceAtLeast(0)
        SingleChoiceDialog.show(
            context = this,
            title = "播放模式（本次播放）",
            items = items,
            checkedIndex = checked,
            negativeText = "取消",
        ) { which, _ ->
            val chosen = items.getOrNull(which).orEmpty()
            session =
                when {
                    chosen.startsWith("跟随全局") -> session.copy(playbackModeOverride = null)
                    chosen.startsWith("循环") -> session.copy(playbackModeOverride = AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE)
                    chosen.startsWith("播放下一个") -> session.copy(playbackModeOverride = AppPrefs.PLAYER_PLAYBACK_MODE_NEXT)
                    chosen.startsWith("退出") -> session.copy(playbackModeOverride = AppPrefs.PLAYER_PLAYBACK_MODE_EXIT)
                    else -> session.copy(playbackModeOverride = AppPrefs.PLAYER_PLAYBACK_MODE_NONE)
                }
            applyPlaybackMode(exo)
            refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
        }
    }

    private fun handlePlaybackEnded(exo: ExoPlayer) {
        val now = SystemClock.uptimeMillis()
        if (now - lastEndedActionAtMs < 350) return
        lastEndedActionAtMs = now

        when (resolvedPlaybackMode()) {
            AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> {
                exo.seekTo(0)
                exo.playWhenReady = true
                exo.play()
            }

            AppPrefs.PLAYER_PLAYBACK_MODE_NEXT -> {
                playNext(userInitiated = false)
            }

            AppPrefs.PLAYER_PLAYBACK_MODE_EXIT -> {
                finish()
            }

            else -> Unit
        }
    }

    private fun playNext(userInitiated: Boolean) {
        val list = playlistItems
        if (list.isEmpty() || playlistIndex !in list.indices) {
            if (userInitiated) Toast.makeText(this, "暂无下一个视频", Toast.LENGTH_SHORT).show()
            return
        }
        if (list.size == 1) {
            val exo = player ?: return
            exo.seekTo(0)
            exo.playWhenReady = true
            exo.play()
            return
        }
        val next = (playlistIndex + 1) % list.size
        playPlaylistIndex(next)
    }

    private fun playPrev(userInitiated: Boolean) {
        val list = playlistItems
        if (list.isEmpty() || playlistIndex !in list.indices) {
            if (userInitiated) Toast.makeText(this, "暂无上一个视频", Toast.LENGTH_SHORT).show()
            return
        }
        if (list.size == 1) {
            val exo = player ?: return
            exo.seekTo(0)
            exo.playWhenReady = true
            exo.play()
            return
        }
        val prev = (playlistIndex - 1 + list.size) % list.size
        playPlaylistIndex(prev)
    }

    private fun playPlaylistIndex(index: Int) {
        val list = playlistItems
        val item = list.getOrNull(index) ?: return
        if (item.bvid.isBlank()) return

        // Avoid pointless reload when list has only one item.
        if (index == playlistIndex) {
            val exo = player ?: return
            exo.seekTo(0)
            exo.playWhenReady = true
            exo.play()
            return
        }

        playlistIndex = index.coerceIn(0, list.lastIndex)
        playlistToken?.let { PlayerPlaylistStore.updateIndex(it, playlistIndex) }
        updatePlaylistControls()
        startPlayback(
            bvid = item.bvid,
            cidExtra = item.cid?.takeIf { it > 0 },
            epIdExtra = item.epId?.takeIf { it > 0 },
            aidExtra = item.aid?.takeIf { it > 0 },
            initialTitle = item.title,
        )
    }

    private fun resetPlaybackStateForNewMedia(exo: ExoPlayer) {
        traceFirstFrameLogged = false
        lastAvailableQns = emptyList()
        subtitleAvailable = false
        subtitleConfig = null
        subtitleItems = emptyList()
        currentUpMid = 0L
        currentUpName = null
        currentUpAvatar = null
        danmakuShield = null
        danmakuLoadedSegments.clear()
        danmakuLoadingSegments.clear()
        danmakuAll.clear()
        binding.danmakuView.setDanmakus(emptyList())
        binding.danmakuView.notifySeek(0L)
        playbackConstraints = PlaybackConstraints()
        decodeFallbackAttempted = false
        lastPickedDash = null
        exo.stop()
        applySubtitleEnabled(exo)
        applyPlaybackMode(exo)
        updateSubtitleButton()
        updateDanmakuButton()
        updateUpButton()
        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
    }

    private fun startPlayback(
        bvid: String,
        cidExtra: Long?,
        epIdExtra: Long?,
        aidExtra: Long?,
        initialTitle: String?,
    ) {
        val exo = player ?: return
        val safeBvid = bvid.trim()
        if (safeBvid.isBlank()) return

        cancelPendingAutoResume(reason = "new_media")
        autoResumeToken++
        autoResumeCancelledByUser = false
        stopReportProgressLoop(flush = false, reason = "new_media")
        reportToken++
        lastReportAtMs = 0L
        lastReportedProgressSec = -1L

        loadJob?.cancel()
        loadJob = null

        currentBvid = safeBvid
        currentEpId = epIdExtra
        currentAid = aidExtra
        currentCid = -1L

        trace =
            PlaybackTrace(
                buildString {
                    append(safeBvid.takeLast(8).ifBlank { "unknown" })
                    append('-')
                    append((System.currentTimeMillis() and 0xFFFF).toString(16))
                },
            )

        binding.tvTitle.text = initialTitle?.takeIf { it.isNotBlank() } ?: "-"
        binding.tvOnline.text = "-人正在观看"
        resetPlaybackStateForNewMedia(exo)

        updatePlaylistControls()

        val handler =
            playbackUncaughtHandler
                ?: CoroutineExceptionHandler { _, t ->
                    AppLog.e("Player", "uncaught", t)
                    Toast.makeText(this@PlayerActivity, "播放失败：${t.message}", Toast.LENGTH_LONG).show()
                    finish()
                }

        loadJob =
            lifecycleScope.launch(handler) {
                try {
                    trace?.log("view:start")
                    val viewJson = async(Dispatchers.IO) { runCatching { BiliApi.view(safeBvid) }.getOrNull() }
                    val viewData = viewJson.await()?.optJSONObject("data") ?: JSONObject()
                    trace?.log("view:done")
                    val title = viewData.optString("title", "")
                    if (title.isNotBlank()) binding.tvTitle.text = title
                    applyUpInfo(viewData)

                    val cid = cidExtra ?: viewData.optLong("cid").takeIf { it > 0 } ?: error("cid missing")
                    val aid = viewData.optLong("aid").takeIf { it > 0 }
                    currentAid = currentAid ?: aid
                    currentCid = cid
                    AppLog.i("Player", "start bvid=$safeBvid cid=$cid")
                    trace?.log("cid:resolved", "cid=$cid aid=${aid ?: -1}")

                    requestOnlineWatchingText(bvid = safeBvid, cid = cid)

                    val playJob =
                        async {
                            val (qn, fnval) = playUrlParamsForSession()
                            trace?.log("playurl:start", "qn=$qn fnval=$fnval")
                            playbackConstraints = PlaybackConstraints()
                            decodeFallbackAttempted = false
                            lastPickedDash = null
                            loadPlayableWithTryLookFallback(
                                bvid = safeBvid,
                                cid = cid,
                                epId = currentEpId,
                                qn = qn,
                                fnval = fnval,
                                constraints = playbackConstraints,
                            ).also { trace?.log("playurl:done") }
                        }
                    val dmJob =
                        async(Dispatchers.IO) {
                            trace?.log("danmakuMeta:start")
                            prepareDanmakuMeta(cid, currentAid ?: aid, trace)
                                .also { trace?.log("danmakuMeta:done", "segTotal=${it.segmentTotal} segMs=${it.segmentSizeMs}") }
                        }
                    val subJob =
                        async(Dispatchers.IO) {
                            trace?.log("subtitle:start")
                            prepareSubtitleConfig(viewData, safeBvid, cid, trace)
                                .also { trace?.log("subtitle:done", "ok=${it != null}") }
                        }

                    trace?.log("playurl:await")
                    val (playJson, playable) = playJob.await()
                    trace?.log("playurl:awaitDone")
                    showRiskControlBypassHintIfNeeded(playJson)
                    lastAvailableQns = parseDashVideoQnList(playJson)
                    trace?.log("subtitle:await")
                    subtitleConfig = subJob.await()
                    trace?.log("subtitle:awaitDone", "ok=${subtitleConfig != null}")
                    subtitleAvailable = subtitleConfig != null
                    (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
                    applySubtitleEnabled(exo)

                    val okHttpFactory = OkHttpDataSource.Factory(BiliClient.cdnOkHttp)
                    trace?.log("exo:setMediaSource:start")
                    when (playable) {
                        is Playable.Dash -> {
                            lastPickedDash = playable
                            AppLog.i(
                                "Player",
                                "picked DASH qn=${playable.qn} codecid=${playable.codecid} dv=${playable.isDolbyVision} a=${playable.audioKind}(${playable.audioId}) video=${playable.videoUrl.take(40)}",
                            )
                            exo.setMediaSource(buildMerged(okHttpFactory, playable.videoUrl, playable.audioUrl, subtitleConfig))
                            applyResolutionFallbackIfNeeded(requestedQn = session.targetQn, actualQn = playable.qn)
                        }

                        is Playable.Progressive -> {
                            lastPickedDash = null
                            AppLog.i("Player", "picked Progressive url=${playable.url.take(60)}")
                            exo.setMediaSource(buildProgressive(okHttpFactory, playable.url, subtitleConfig))
                        }
                    }
                    trace?.log("exo:setMediaSource:done")
                    trace?.log("exo:prepare")
                    exo.prepare()
                    trace?.log("exo:playWhenReady")
                    exo.playWhenReady = true
                    updateSubtitleButton()
                    maybeScheduleAutoResume(
                        playJson = playJson,
                        bvid = safeBvid,
                        cid = cid,
                        playbackToken = autoResumeToken,
                    )

                    trace?.log("danmakuMeta:await")
                    val dmMeta = dmJob.await()
                    trace?.log("danmakuMeta:awaitDone")
                    applyDanmakuMeta(dmMeta)
                    requestDanmakuSegmentsForPosition(exo.currentPosition.coerceAtLeast(0L), immediate = true)
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    AppLog.e("Player", "start failed", t)
                    if (!handlePlayUrlErrorIfNeeded(t)) {
                        Toast.makeText(this@PlayerActivity, "加载播放信息失败：${t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
    }

    private fun requestOnlineWatchingText(bvid: String, cid: Long) {
        // Must not crash the player: always swallow any network/parse errors.
        binding.tvOnline.text = "-人正在观看"
        lifecycleScope.launch {
            val countText =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val json = BiliApi.onlineTotal(bvid = bvid, cid = cid)
                        if (json.optInt("code", 0) != 0) return@runCatching "-"
                        val data = json.optJSONObject("data") ?: return@runCatching "-"
                        val showSwitch = data.optJSONObject("show_switch") ?: JSONObject()
                        val totalEnabled = showSwitch.optBoolean("total", true)
                        val total = data.optString("total", "")
                        val countEnabled = showSwitch.optBoolean("count", true)
                        val count = data.optString("count", "")
                        when {
                            totalEnabled && total.isNotBlank() -> total
                            countEnabled && count.isNotBlank() -> count
                            else -> "-"
                        }
                    }.getOrDefault("-")
                }
            binding.tvOnline.text = "${countText}人正在观看"
        }
    }

    private fun extractVVoucher(json: JSONObject): String? {
        val data = json.optJSONObject("data") ?: json.optJSONObject("result") ?: return null
        return data.optString("v_voucher", "").trim().takeIf { it.isNotBlank() }
    }

    private fun recordVVoucher(vVoucher: String) {
        val prefs = BiliClient.prefs
        prefs.gaiaVgateVVoucher = vVoucher
        prefs.gaiaVgateVVoucherSavedAtMs = System.currentTimeMillis()
    }

    private suspend fun requestPlayJson(
        bvid: String,
        cid: Long,
        epId: Long?,
        qn: Int,
        fnval: Int,
        tryLook: Boolean,
    ): JSONObject {
        return try {
            if (tryLook) {
                BiliApi.playUrlDashTryLook(bvid = bvid, cid = cid, qn = qn, fnval = fnval)
            } else {
                BiliApi.playUrlDash(bvid = bvid, cid = cid, qn = qn, fnval = fnval)
            }
        } catch (t: Throwable) {
            val e = t as? BiliApiException
            if (e != null && epId != null && epId > 0 && (e.apiCode == -404 || e.apiCode == -400)) {
                if (tryLook) {
                    BiliApi.pgcPlayUrlTryLook(bvid = bvid, cid = cid, epId = epId, qn = qn, fnval = fnval)
                } else {
                    BiliApi.pgcPlayUrl(bvid = bvid, cid = cid, epId = epId, qn = qn, fnval = fnval)
                }
            } else {
                throw t
            }
        }
    }

    private fun trackHasAnyUrl(obj: JSONObject): Boolean {
        val base =
            obj.optString("baseUrl", obj.optString("base_url", obj.optString("url", "")))
                .trim()
        if (base.isNotBlank()) return true
        val backup = obj.optJSONArray("backupUrl") ?: obj.optJSONArray("backup_url") ?: JSONArray()
        for (i in 0 until backup.length()) {
            val u = backup.optString(i, "").trim()
            if (u.isNotBlank()) return true
        }
        return false
    }

    private fun hasAnyPlayableUrl(json: JSONObject): Boolean {
        val data = json.optJSONObject("data") ?: json.optJSONObject("result") ?: return false
        val dash = data.optJSONObject("dash")
        if (dash != null) {
            val videos = dash.optJSONArray("video") ?: JSONArray()
            val audios = dash.optJSONArray("audio") ?: JSONArray()
            val dolbyAudios = dash.optJSONObject("dolby")?.optJSONArray("audio") ?: JSONArray()
            val flacAudio = dash.optJSONObject("flac")?.optJSONObject("audio")
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                if (trackHasAnyUrl(v)) return true
            }
            for (i in 0 until audios.length()) {
                val a = audios.optJSONObject(i) ?: continue
                if (trackHasAnyUrl(a)) return true
            }
            for (i in 0 until dolbyAudios.length()) {
                val a = dolbyAudios.optJSONObject(i) ?: continue
                if (trackHasAnyUrl(a)) return true
            }
            if (flacAudio != null && trackHasAnyUrl(flacAudio)) return true
        }

        val durl = data.optJSONArray("durl") ?: JSONArray()
        for (i in 0 until durl.length()) {
            val obj = durl.optJSONObject(i) ?: continue
            val url = obj.optString("url", "").trim()
            if (url.isNotBlank()) return true
        }
        return false
    }

    private fun shouldAttemptTryLookFallback(playJson: JSONObject): Boolean {
        // try_look is only a risk-control fallback: only use it when we truly cannot get any playable URL.
        return !hasAnyPlayableUrl(playJson)
    }

    private suspend fun loadPlayableWithTryLookFallback(
        bvid: String,
        cid: Long,
        epId: Long?,
        qn: Int,
        fnval: Int,
        constraints: PlaybackConstraints,
    ): PlayFetchResult {
        val primaryJson =
            try {
                requestPlayJson(
                    bvid = bvid,
                    cid = cid,
                    epId = epId,
                    qn = qn,
                    fnval = fnval,
                    tryLook = false,
                )
            } catch (t: Throwable) {
                val e = t as? BiliApiException
                if (e != null && isRiskControl(e)) {
                    val fallbackJson =
                        requestPlayJson(
                            bvid = bvid,
                            cid = cid,
                            epId = epId,
                            qn = qn,
                            fnval = fnval,
                            tryLook = true,
                        )
                    fallbackJson.put("__blbl_risk_control_bypassed", true)
                    fallbackJson.put("__blbl_risk_control_code", e.apiCode)
                    fallbackJson.put("__blbl_risk_control_message", e.apiMessage)
                    val playable = pickPlayable(fallbackJson, constraints)
                    return PlayFetchResult(json = fallbackJson, playable = playable)
                }
                throw t
            }

        return try {
            val playable = pickPlayable(primaryJson, constraints)
            PlayFetchResult(json = primaryJson, playable = playable)
        } catch (t: Throwable) {
            if (!shouldAttemptTryLookFallback(primaryJson)) throw t

            extractVVoucher(primaryJson)?.let { recordVVoucher(it) }

            val fallbackJson =
                requestPlayJson(
                    bvid = bvid,
                    cid = cid,
                    epId = epId,
                    qn = qn,
                    fnval = fnval,
                    tryLook = true,
                )
            fallbackJson.put("__blbl_risk_control_bypassed", true)
            fallbackJson.put("__blbl_risk_control_code", -352)
            fallbackJson.put("__blbl_risk_control_message", "fallback try_look after no playable stream")

            return try {
                val playable = pickPlayable(fallbackJson, constraints)
                PlayFetchResult(json = fallbackJson, playable = playable)
            } catch (t2: Throwable) {
                AppLog.w("Player", "try_look fallback failed", t2)
                throw BiliApiException(apiCode = -352, apiMessage = "风控拦截：未返回可播放地址（已尝试 try_look 兜底失败）")
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
    }

    override fun onResume() {
        super.onResume()
        applyUiMode()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        if (event.action == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (finishOnBackKeyUp) {
                    finishOnBackKeyUp = false
                    finish()
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (holdSeekJob != null) {
                    stopHoldSeek()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (isInteractionKey(keyCode)) noteUserInteraction()

        when (keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) return true
                setControlsVisible(true)
                focusFirstControl()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                cancelPendingAutoResume(reason = "back")
                stopReportProgressLoop(flush = true, reason = "back")
                finishOnBackKeyUp = false
                if (binding.settingsPanel.visibility == View.VISIBLE) {
                    binding.settingsPanel.visibility = View.GONE
                    setControlsVisible(true)
                    focusFirstControl()
                    return true
                }
                if (controlsVisible) {
                    setControlsVisible(false)
                    return true
                }
                finishOnBackKeyUp = shouldFinishOnBackPress()
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                setControlsVisible(true)
                if (!binding.seekProgress.isFocused) {
                    focusSeekBar()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                if (binding.seekProgress.isFocused) {
                    setControlsVisible(true)
                    focusFirstControl()
                    return true
                }
                if (!controlsVisible) {
                    setControlsVisible(true)
                    focusFirstControl()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> {
                if (binding.settingsPanel.visibility != View.VISIBLE && !hasControlsFocus()) {
                    setControlsVisible(true)
                    focusFirstControl()
                    return true
                }
                if (!controlsVisible && binding.settingsPanel.visibility != View.VISIBLE) {
                    setControlsVisible(true)
                    focusFirstControl()
                    return true
                }
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            -> {
                binding.btnPlayPause.performClick()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playNext(userInitiated = true)
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                playPrev(userInitiated = true)
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                if (binding.seekProgress.isFocused) return super.dispatchKeyEvent(event)
                if (binding.topBar.hasFocus() || binding.bottomBar.hasFocus()) return super.dispatchKeyEvent(event)

                if (event.repeatCount > 0) {
                    startHoldSeek(direction = -1, showControls = false)
                    return true
                }

                smartSeek(direction = -1, showControls = false, hintKind = SeekHintKind.Step)
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                if (binding.seekProgress.isFocused) return super.dispatchKeyEvent(event)
                if (binding.topBar.hasFocus() || binding.bottomBar.hasFocus()) return super.dispatchKeyEvent(event)

                if (event.repeatCount > 0) {
                    startHoldSeek(direction = +1, showControls = false)
                    return true
                }

                smartSeek(direction = +1, showControls = false, hintKind = SeekHintKind.Step)
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        stopReportProgressLoop(flush = true, reason = "stop")
    }

    override fun onDestroy() {
        debugJob?.cancel()
        progressJob?.cancel()
        autoResumeJob?.cancel()
        autoResumeHintTimeoutJob?.cancel()
        reportProgressJob?.cancel()
        autoHideJob?.cancel()
        holdSeekJob?.cancel()
        seekHintJob?.cancel()
        keyScrubEndJob?.cancel()
        loadJob?.cancel()
        loadJob = null
        dismissAutoResumeHint()
        stopReportProgressLoop(flush = true, reason = "destroy")
        player?.release()
        player = null
        playlistToken?.let(PlayerPlaylistStore::remove)
        ActivityStackLimiter.unregister(group = ACTIVITY_STACK_GROUP, activity = this)
        super.onDestroy()
    }

    private fun initControls(exo: ExoPlayer) {
        val detector =
            GestureDetector(
                this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        if (binding.settingsPanel.visibility == View.VISIBLE) return true
                        if (controlsVisible) {
                            setControlsVisible(false)
                            return true
                        }
                        val w = binding.playerView.width.toFloat()
                        if (w <= 0f) return true
                        val dir = edgeDirection(e.x, w)
                        if (dir == 0) return true

                        smartSeek(direction = dir, showControls = false, hintKind = SeekHintKind.Step)
                        tapSeekActiveDirection = dir
                        tapSeekActiveUntilMs = SystemClock.uptimeMillis() + TAP_SEEK_ACTIVE_MS
                        return true
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        if (binding.settingsPanel.visibility == View.VISIBLE) {
                            binding.settingsPanel.visibility = View.GONE
                            setControlsVisible(true)
                            focusFirstControl()
                            return true
                        }
                        if (controlsVisible) {
                            setControlsVisible(false)
                            return true
                        }

                        val now = SystemClock.uptimeMillis()
                        val w = binding.playerView.width.toFloat()
                        if (w > 0f && now <= tapSeekActiveUntilMs) {
                            val dir = edgeDirection(e.x, w)
                            if (dir != 0 && dir == tapSeekActiveDirection) {
                                smartSeek(direction = dir, showControls = false, hintKind = SeekHintKind.Step)
                                tapSeekActiveUntilMs = now + TAP_SEEK_ACTIVE_MS
                                return true
                            }
                        }

                        setControlsVisible(true)
                        return true
                    }
                },
            )
        binding.playerView.setOnTouchListener { v, event ->
            val handled = detector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && handled) v.performClick()
            handled
        }

        binding.btnAdvanced.setOnClickListener {
            val willShow = binding.settingsPanel.visibility != View.VISIBLE
            binding.settingsPanel.visibility = if (willShow) View.VISIBLE else View.GONE
            setControlsVisible(true)
        }

        binding.btnPlayPause.setOnClickListener {
            if (exo.isPlaying) exo.pause() else exo.play()
            setControlsVisible(true)
        }
        binding.btnPrev.setOnClickListener {
            playPrev(userInitiated = true)
            setControlsVisible(true)
        }
        binding.btnNext.setOnClickListener {
            playNext(userInitiated = true)
            setControlsVisible(true)
        }

        binding.btnDanmaku.setOnClickListener {
            session = session.copy(danmaku = session.danmaku.copy(enabled = !session.danmaku.enabled))
            binding.danmakuView.invalidate()
            updateDanmakuButton()
            setControlsVisible(true)
        }

        binding.btnSubtitle.setOnClickListener {
            toggleSubtitles(exo)
            setControlsVisible(true)
        }

        binding.btnUp.setOnClickListener {
            val mid = currentUpMid
            if (mid <= 0L) {
                Toast.makeText(this, "未获取到 UP 主信息", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(
                Intent(this, UpDetailActivity::class.java)
                    .putExtra(UpDetailActivity.EXTRA_MID, mid)
                    .apply {
                        currentUpName?.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_NAME, it) }
                        currentUpAvatar?.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_AVATAR, it) }
                    },
            )
            setControlsVisible(true)
        }

        binding.seekProgress.max = SEEK_MAX
        binding.seekProgress.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    cancelPendingAutoResume(reason = "user_seek")
                    scrubbing = true
                    noteUserInteraction()
                    if (seekBar?.isPressed != true) scheduleKeyScrubEnd()

                    val duration = exo.duration.takeIf { it > 0 }
                    if (duration != null) {
                        val previewPos = (duration * progress) / SEEK_MAX
                        binding.tvTime.text = "${formatHms(previewPos)} / ${formatHms(duration)}"
                    }

                    if (binding.seekProgress.isFocused && duration != null) {
                        val seekTo = duration * progress / SEEK_MAX
                        exo.seekTo(seekTo)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    cancelPendingAutoResume(reason = "user_seek")
                    scrubbing = true
                    keyScrubEndJob?.cancel()
                    setControlsVisible(true)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val duration = exo.duration.takeIf { it > 0 } ?: return
                    val progress = seekBar?.progress ?: return
                    val seekTo = duration * progress / SEEK_MAX
                    exo.seekTo(seekTo)
                    binding.tvTime.text = "${formatHms(seekTo)} / ${formatHms(duration)}"
                    requestDanmakuSegmentsForPosition(seekTo, immediate = true)
                    scrubbing = false
                    keyScrubEndJob?.cancel()
                    setControlsVisible(true)
                    lifecycleScope.launch { reportProgressOnce(force = true, reason = "user_seek_end") }
                }
            },
        )

        updatePlayPauseIcon(exo.isPlaying)
        updateSubtitleButton()
        updateDanmakuButton()
        updateUpButton()
        updatePlaylistControls()
        setControlsVisible(true)
        startProgressLoop()
    }

    private fun applyUpInfo(viewData: JSONObject) {
        val owner =
            viewData.optJSONObject("owner")
                ?: viewData.optJSONObject("up_info")
                ?: JSONObject()
        currentUpMid = owner.optLong("mid").takeIf { it > 0L } ?: 0L
        currentUpName = owner.optString("name", "").trim().takeIf { it.isNotBlank() }
        currentUpAvatar = owner.optString("face", "").trim().takeIf { it.isNotBlank() }
        updateUpButton()
    }

    private fun seekRelative(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val next = (exo.currentPosition + deltaMs).coerceIn(0L, duration)
        exo.seekTo(next)
        requestDanmakuSegmentsForPosition(next, immediate = true)
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                updateProgressUi()
                delay(250)
            }
        }
    }

    private fun toggleControls() {
        val willShow = !controlsVisible
        if (!willShow) binding.settingsPanel.visibility = View.GONE
        setControlsVisible(willShow)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val show = visible || binding.settingsPanel.visibility == View.VISIBLE
        binding.topBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (show) View.VISIBLE else View.GONE
        if (visible) noteUserInteraction() else autoHideJob?.cancel()
    }

    private fun restartAutoHideTimer() {
        autoHideJob?.cancel()
        val exo = player ?: return
        if (!controlsVisible) return
        if (binding.settingsPanel.visibility == View.VISIBLE) return
        if (scrubbing) return
        if (!exo.isPlaying) return
        val token = lastInteractionAtMs
        autoHideJob = lifecycleScope.launch {
            delay(AUTO_HIDE_MS)
            if (token != lastInteractionAtMs) return@launch
            setControlsVisible(false)
        }
    }

    private fun noteUserInteraction() {
        lastInteractionAtMs = SystemClock.uptimeMillis()
        restartAutoHideTimer()
    }

    private fun scheduleKeyScrubEnd() {
        keyScrubEndJob?.cancel()
        keyScrubEndJob =
            lifecycleScope.launch {
                delay(KEY_SCRUB_END_DELAY_MS)
                scrubbing = false
                restartAutoHideTimer()
            }
    }

    private fun hasControlsFocus(): Boolean =
        binding.topBar.hasFocus() || binding.bottomBar.hasFocus() || binding.settingsPanel.hasFocus()

    private fun focusFirstControl() {
        binding.btnPlayPause.post { binding.btnPlayPause.requestFocus() }
    }

    private fun focusSeekBar() {
        binding.seekProgress.post { binding.seekProgress.requestFocus() }
    }

    private fun shouldFinishOnBackPress(): Boolean {
        val exo = player
        if (exo != null &&
            exo.playbackState == Player.STATE_ENDED &&
            !BiliClient.prefs.playerDoubleBackOnEnded
        ) {
            return true
        }
        val now = SystemClock.uptimeMillis()
        val isSecond = now - lastBackAtMs <= BACK_DOUBLE_PRESS_WINDOW_MS
        if (isSecond) return true
        lastBackAtMs = now
        Toast.makeText(this, "再按一次退出播放器", Toast.LENGTH_SHORT).show()
        if (controlsVisible) setControlsVisible(false)
        return false
    }

    private fun smartSeek(direction: Int) {
        smartSeek(direction, showControls = true, hintKind = SeekHintKind.Step)
    }

    private enum class SeekHintKind {
        Step,
        Hold,
    }

    private fun smartSeek(direction: Int, showControls: Boolean, hintKind: SeekHintKind) {
        val now = SystemClock.uptimeMillis()
        val sameDir = direction == smartSeekDirection
        val within = now - smartSeekLastAtMs <= SMART_SEEK_WINDOW_MS
        val continued = sameDir && within
        smartSeekStreak = if (continued) (smartSeekStreak + 1) else 1
        smartSeekDirection = direction
        smartSeekLastAtMs = now

        if (showControls) {
            if (!controlsVisible && binding.settingsPanel.visibility != View.VISIBLE) setControlsVisible(true) else noteUserInteraction()
        } else {
            noteUserInteraction()
        }

        val step = smartSeekStepMs(smartSeekStreak)
        seekRelative(step * direction)
        smartSeekTotalMs = if (continued) (smartSeekTotalMs + step) else step
        if (hintKind == SeekHintKind.Step) showSeekStepHint(direction, smartSeekTotalMs)
    }

    private fun smartSeekStepMs(streak: Int): Long {
        val baseStepsSec = intArrayOf(2, 3, 5, 7, 10, 15, 25, 35, 45)
        val idx = (streak - 1).coerceAtLeast(0)
        val sec =
            if (idx < baseStepsSec.size) {
                baseStepsSec[idx]
            } else {
                val extra = idx - (baseStepsSec.size - 1)
                (baseStepsSec.last() + (10 * extra)).coerceAtMost(300)
            }
        return sec * 1000L
    }

    private fun startHoldSeek(direction: Int, showControls: Boolean) {
        if (holdSeekJob?.isActive == true) return
        val exo = player ?: return

        if (showControls) {
            if (!controlsVisible && binding.settingsPanel.visibility != View.VISIBLE) setControlsVisible(true) else noteUserInteraction()
        } else {
            noteUserInteraction()
        }

        holdPrevSpeed = exo.playbackParameters.speed
        holdPrevPlayWhenReady = exo.playWhenReady
        if (direction > 0) {
            showSeekHoldHint(direction)
            exo.setPlaybackSpeed(HOLD_SPEED)
            exo.playWhenReady = true
            holdSeekJob = lifecycleScope.launch { kotlinx.coroutines.awaitCancellation() }
            return
        }

        // Rewind: ExoPlayer has no negative playback speed; do stepped rewind while paused.
        showSeekHoldHint(direction)
        exo.pause()
        holdSeekJob =
            lifecycleScope.launch {
                while (isActive) {
                    seekRelative(-HOLD_REWIND_STEP_MS)
                    delay(HOLD_REWIND_TICK_MS)
                }
            }
    }

    private fun stopHoldSeek() {
        val exo = player
        holdSeekJob?.cancel()
        holdSeekJob = null
        if (exo != null) {
            exo.setPlaybackSpeed(holdPrevSpeed)
            exo.playWhenReady = holdPrevPlayWhenReady
        }
        scheduleHideSeekHint()
    }

    private fun showSeekStepHint(direction: Int, totalMs: Long) {
        val sec = (kotlin.math.abs(totalMs) / 1000L).coerceAtLeast(1L)
        val text = if (direction > 0) "快进 ${sec}s" else "后退 ${sec}s"
        showSeekHint(text, hold = false)
    }

    private fun showSeekHoldHint(direction: Int) {
        val text = if (direction > 0) "快进 x2" else "后退 x2"
        showSeekHint(text, hold = true)
    }

    private fun showSeekHint(text: String, hold: Boolean) {
        binding.tvSeekHint.text = text
        binding.tvSeekHint.visibility = View.VISIBLE
        seekHintJob?.cancel()
        if (!hold) scheduleHideSeekHint()
    }

    private fun scheduleHideSeekHint() {
        seekHintJob?.cancel()
        seekHintJob =
            lifecycleScope.launch {
                delay(SEEK_HINT_HIDE_DELAY_MS)
                binding.tvSeekHint.visibility = View.GONE
            }
    }

    private fun edgeDirection(x: Float, width: Float): Int {
        return when {
            x < width * EDGE_TAP_THRESHOLD -> -1
            x > width * (1f - EDGE_TAP_THRESHOLD) -> +1
            else -> 0
        }
    }

    private fun isInteractionKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            -> true

            else -> false
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (isPlaying) blbl.cat3399.R.drawable.ic_player_pause else blbl.cat3399.R.drawable.ic_player_play,
        )
    }

    private fun updateProgressUi() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: 0L
        val pos = exo.currentPosition.coerceAtLeast(0L)

        if (!scrubbing) {
            binding.tvTime.text = "${formatHms(pos)} / ${formatHms(duration)}"
        }

        val enabled = duration > 0
        binding.seekProgress.isEnabled = enabled
        if (enabled && !scrubbing) {
            val p = ((pos.toDouble() / duration.toDouble()) * SEEK_MAX).toInt().coerceIn(0, SEEK_MAX)
            binding.seekProgress.progress = p
        }
        requestDanmakuSegmentsForPosition(pos, immediate = false)
    }

    private fun cancelPendingAutoResume(reason: String) {
        if (reason == "back" || reason == "user_seek") autoResumeCancelledByUser = true
        dismissAutoResumeHint()
        autoResumeJob?.cancel()
        autoResumeJob = null
        trace?.log("resume:cancel", "reason=$reason")
    }

    private fun showAutoResumeHint(targetMs: Long, delayMs: Long) {
        dismissAutoResumeHint()
        val timeText = formatHms(targetMs.coerceAtLeast(0L))
        val sec = (delayMs / 1000L).coerceAtLeast(1L)
        val msg = "将要跳到上次播放位置（$timeText），按返回取消（${sec}s）"
        autoResumeHintVisible = true
        // Reuse the existing bottom "seek hint" component for consistent look & feel.
        showSeekHint(msg, hold = true)
        autoResumeHintTimeoutJob?.cancel()
        autoResumeHintTimeoutJob =
            lifecycleScope.launch {
                delay(3_000)
                dismissAutoResumeHint()
            }
    }

    private fun dismissAutoResumeHint() {
        if (!autoResumeHintVisible) return
        autoResumeHintVisible = false
        autoResumeHintTimeoutJob?.cancel()
        autoResumeHintTimeoutJob = null
        seekHintJob?.cancel()
        binding.tvSeekHint.visibility = View.GONE
    }

    private fun extractResumeCandidateFromPlayJson(playJson: JSONObject): ResumeCandidate? {
        val data = playJson.optJSONObject("data") ?: playJson.optJSONObject("result") ?: return null
        val time = data.optLong("last_play_time", -1L).takeIf { it > 0 } ?: return null
        val hint =
            when {
                time >= 10_000L -> RawTimeUnitHint.MILLIS_LIKELY
                else -> RawTimeUnitHint.UNKNOWN
            }
        return ResumeCandidate(rawTime = time, rawTimeUnitHint = hint, source = "playurl")
    }

    private fun extractResumeCandidateFromPlayerWbiV2(playerJson: JSONObject): ResumeCandidate? {
        val data = playerJson.optJSONObject("data") ?: return null
        val time = data.optLong("last_play_time", -1L).takeIf { it > 0 } ?: return null
        val hint =
            when {
                time >= 10_000L -> RawTimeUnitHint.MILLIS_LIKELY
                else -> RawTimeUnitHint.UNKNOWN
            }
        return ResumeCandidate(rawTime = time, rawTimeUnitHint = hint, source = "playerWbiV2")
    }

    private fun normalizeResumePositionMs(raw: Long, hint: RawTimeUnitHint, durationMs: Long?): Long? {
        if (raw <= 0) return null
        val dur = durationMs?.takeIf { it > 0 }
        if (dur != null) {
            return when {
                raw in 1..dur -> raw
                raw * 1000 in 1..dur -> raw * 1000
                else -> raw
            }
        }
        return when (hint) {
            RawTimeUnitHint.MILLIS_LIKELY -> raw
            RawTimeUnitHint.SECONDS_LIKELY -> raw * 1000
            RawTimeUnitHint.UNKNOWN -> if (raw >= 10_000L) raw else raw * 1000
        }
    }

    private fun shouldAutoResumeTo(positionMs: Long, durationMs: Long?): Boolean {
        if (positionMs < 5_000L) return false
        val dur = durationMs?.takeIf { it > 0 } ?: return true
        return positionMs < (dur - 10_000L).coerceAtLeast(0L)
    }

    private fun maybeScheduleAutoResume(
        playJson: JSONObject,
        bvid: String,
        cid: Long,
        playbackToken: Int,
    ) {
        if (autoResumeCancelledByUser) return
        if (playbackToken != autoResumeToken) return
        val exo = player ?: return

        extractResumeCandidateFromPlayJson(playJson)?.let { cand ->
            scheduleAutoResume(exo = exo, candidate = cand, playbackToken = playbackToken)
            return
        }

        autoResumeJob?.cancel()
        autoResumeJob =
            lifecycleScope.launch {
                val playerJson = runCatching { BiliApi.playerWbiV2(bvid = bvid, cid = cid) }.getOrNull() ?: return@launch
                if (!isActive) return@launch
                if (playbackToken != autoResumeToken) return@launch
                if (autoResumeCancelledByUser) return@launch
                val cand = extractResumeCandidateFromPlayerWbiV2(playerJson) ?: return@launch
                scheduleAutoResume(exo = exo, candidate = cand, playbackToken = playbackToken)
            }
    }

    private fun scheduleAutoResume(exo: ExoPlayer, candidate: ResumeCandidate, playbackToken: Int) {
        if (autoResumeCancelledByUser) return
        autoResumeJob?.cancel()
        dismissAutoResumeHint()
        trace?.log("resume:pending", "src=${candidate.source} raw=${candidate.rawTime}")

        val delayMs = 2_000L
        val previewTargetMs = normalizeResumePositionMs(candidate.rawTime, candidate.rawTimeUnitHint, durationMs = null) ?: return
        if (!shouldAutoResumeTo(previewTargetMs, durationMs = null)) return
        showAutoResumeHint(targetMs = previewTargetMs, delayMs = delayMs)

        autoResumeJob =
            lifecycleScope.launch {
                delay(delayMs)
                if (!isActive) return@launch
                if (autoResumeCancelledByUser) return@launch
                if (playbackToken != autoResumeToken) return@launch
                val p = player ?: return@launch
                if (p !== exo) return@launch

                val durationMs = p.duration.takeIf { it > 0 }
                val targetMs = normalizeResumePositionMs(candidate.rawTime, candidate.rawTimeUnitHint, durationMs) ?: return@launch
                if (!shouldAutoResumeTo(targetMs, durationMs)) return@launch
                val clamped = durationMs?.let { dur -> targetMs.coerceIn(0L, (dur - 500L).coerceAtLeast(0L)) } ?: targetMs
                trace?.log("resume:seek", "to=${clamped}ms src=${candidate.source}")
                dismissAutoResumeHint()
                p.seekTo(clamped)
            }
    }

    private fun shouldReportProgressNow(): Boolean {
        if (!BiliClient.cookies.hasSessData()) return false
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) return false
        val aid = currentAid ?: return false
        if (aid <= 0L) return false
        val cid = currentCid
        if (cid <= 0L) return false
        return true
    }

    private fun startReportProgressLoop() {
        if (reportProgressJob != null) return
        if (!shouldReportProgressNow()) return
        val token = reportToken
        reportProgressJob =
            lifecycleScope.launch {
                delay(2_000)
                while (isActive && token == reportToken) {
                    reportProgressOnce(force = false, reason = "loop")
                    delay(15_000)
                }
            }
    }

    private fun stopReportProgressLoop(flush: Boolean, reason: String) {
        reportProgressJob?.cancel()
        reportProgressJob = null
        if (flush) lifecycleScope.launch { reportProgressOnce(force = true, reason = reason) }
    }

    private suspend fun reportProgressOnce(force: Boolean, reason: String) {
        if (!shouldReportProgressNow()) return
        val token = reportToken
        val exo = player ?: return
        val aid = currentAid ?: return
        val cid = currentCid
        val progressSec = (exo.currentPosition.coerceAtLeast(0L) / 1000L)
        if (token != reportToken) return

        val now = SystemClock.elapsedRealtime()
        if (!force) {
            if (now - lastReportAtMs < 14_000L) return
            if (progressSec == lastReportedProgressSec) return
        }

        runCatching {
            BiliApi.historyReport(aid = aid, cid = cid, progressSec = progressSec, platform = "android")
        }.onSuccess {
            lastReportAtMs = now
            lastReportedProgressSec = progressSec
            trace?.log("report:history", "ok=1 sec=$progressSec reason=$reason")
        }.onFailure {
            trace?.log("report:history", "ok=0 sec=$progressSec reason=$reason")
        }
    }

    private fun updateDanmakuButton() {
        val colorRes = if (session.danmaku.enabled) blbl.cat3399.R.color.blbl_blue else blbl.cat3399.R.color.blbl_text_secondary
        binding.btnDanmaku.imageTintList = ContextCompat.getColorStateList(this, colorRes)
    }

    private fun updateSubtitleButton() {
        val colorRes =
            if (!subtitleAvailable) {
                blbl.cat3399.R.color.blbl_text_secondary
            } else {
                if (session.subtitleEnabled) blbl.cat3399.R.color.blbl_blue else blbl.cat3399.R.color.blbl_text_secondary
            }
        binding.btnSubtitle.imageTintList = ContextCompat.getColorStateList(this, colorRes)
    }

    private fun toggleSubtitles(exo: ExoPlayer) {
        if (!subtitleAvailable) {
            Toast.makeText(this, "该视频暂无字幕", Toast.LENGTH_SHORT).show()
            return
        }
        session = session.copy(subtitleEnabled = !session.subtitleEnabled)
        applySubtitleEnabled(exo)
        updateSubtitleButton()
    }

    private fun applySubtitleEnabled(exo: ExoPlayer) {
        val disable = (!subtitleAvailable) || (!session.subtitleEnabled)
        exo.trackSelectionParameters =
            exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disable)
                .build()
    }

    private sealed interface Playable {
        data class Dash(
            val videoUrl: String,
            val audioUrl: String,
            val qn: Int,
            val codecid: Int,
            val audioId: Int,
            val audioKind: DashAudioKind,
            val isDolbyVision: Boolean,
        ) : Playable

        data class Progressive(val url: String) : Playable
    }

    private enum class DashAudioKind { NORMAL, DOLBY, FLAC }

    private data class PlaybackConstraints(
        val allowDolbyVision: Boolean = true,
        val allowDolbyAudio: Boolean = true,
        val allowFlacAudio: Boolean = true,
    )

    private fun Playable.Dash.shouldAttemptDolbyFallback(): Boolean =
        isDolbyVision || audioKind == DashAudioKind.DOLBY || audioKind == DashAudioKind.FLAC

    private fun nextPlaybackConstraintsForDolbyFallback(picked: Playable.Dash): PlaybackConstraints? {
        if (picked.isDolbyVision && playbackConstraints.allowDolbyVision) return playbackConstraints.copy(allowDolbyVision = false)
        if (picked.audioKind == DashAudioKind.DOLBY && playbackConstraints.allowDolbyAudio) return playbackConstraints.copy(allowDolbyAudio = false)
        if (picked.audioKind == DashAudioKind.FLAC && playbackConstraints.allowFlacAudio) return playbackConstraints.copy(allowFlacAudio = false)
        return null
    }

    private fun isLikelyCodecUnsupported(error: PlaybackException): Boolean {
        val name = error.errorCodeName.uppercase(Locale.US)
        return name.contains("DECOD") || name.contains("DECODER") || name.contains("FORMAT")
    }

    private val deviceSupportsDolbyVision: Boolean by lazy {
        hasDecoder(MimeTypes.VIDEO_DOLBY_VISION)
    }

    private fun hasDecoder(mimeType: String): Boolean =
        try {
            MediaCodecUtil.getDecoderInfos(mimeType, /* secure= */ false, /* tunneling= */ false).isNotEmpty()
        } catch (_: Throwable) {
            false
        }

    private fun refreshSettings(adapter: PlayerSettingsAdapter) {
        adapter.submit(
            listOf(
                PlayerSettingsAdapter.SettingItem("分辨率", resolutionSubtitle()),
                PlayerSettingsAdapter.SettingItem("视频编码", session.preferCodec),
                PlayerSettingsAdapter.SettingItem("播放速度", String.format(Locale.US, "%.2fx", session.playbackSpeed)),
                PlayerSettingsAdapter.SettingItem("播放模式", playbackModeSubtitle()),
                PlayerSettingsAdapter.SettingItem("字幕语言", subtitleLangSubtitle()),
                PlayerSettingsAdapter.SettingItem("弹幕透明度", String.format(Locale.US, "%.2f", session.danmaku.opacity)),
                PlayerSettingsAdapter.SettingItem("弹幕字号", session.danmaku.textSizeSp.toInt().toString()),
                PlayerSettingsAdapter.SettingItem("弹幕速度", session.danmaku.speedLevel.toString()),
                PlayerSettingsAdapter.SettingItem("弹幕区域", areaText(session.danmaku.area)),
                PlayerSettingsAdapter.SettingItem("调试信息", if (session.debugEnabled) "开" else "关"),
            ),
        )
    }

    private fun selectCdnUrlFromTrack(obj: JSONObject, preference: String): String {
        val candidates = buildList {
            val base =
                obj.optString("baseUrl", obj.optString("base_url", obj.optString("url", "")))
                    .trim()
            if (base.isNotBlank()) add(base)
            val backup = obj.optJSONArray("backupUrl") ?: obj.optJSONArray("backup_url") ?: JSONArray()
            for (i in 0 until backup.length()) {
                val u = backup.optString(i, "").trim()
                if (u.isNotBlank()) add(u)
            }
        }.distinct()
        if (candidates.isEmpty()) return ""

        fun hostOf(url: String): String =
            runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("").lowercase(Locale.US)

        fun isMcdn(url: String): Boolean {
            val host = hostOf(url)
            return host.contains("mcdn") && host.contains("bilivideo")
        }

        fun isBilivideo(url: String): Boolean {
            val host = hostOf(url)
            return host.contains("bilivideo") && !isMcdn(url)
        }

        val picked =
            when (preference) {
                AppPrefs.PLAYER_CDN_MCDN -> candidates.firstOrNull(::isMcdn)
                AppPrefs.PLAYER_CDN_BILIVIDEO -> candidates.firstOrNull(::isBilivideo)
                else -> null
            }
        return picked ?: candidates.first()
    }

    private suspend fun pickPlayable(json: JSONObject, constraints: PlaybackConstraints): Playable {
        val data = json.optJSONObject("data") ?: json.optJSONObject("result") ?: JSONObject()
        val vVoucher = data.optString("v_voucher", "").trim()
        if (vVoucher.isNotBlank()) {
            recordVVoucher(vVoucher)
        }
        val dash = data.optJSONObject("dash")
        if (dash != null) {
            val videos = dash.optJSONArray("video") ?: JSONArray()
            val audios = dash.optJSONArray("audio") ?: JSONArray()
            val dolby = dash.optJSONObject("dolby")
            val flac = dash.optJSONObject("flac")

            fun baseUrl(obj: JSONObject): String =
                selectCdnUrlFromTrack(obj, preference = BiliClient.prefs.playerCdnPreference)

            val preferCodecid = when (session.preferCodec) {
                "HEVC" -> 12
                "AV1" -> 13
                else -> 7
            }

            fun qnOf(v: JSONObject): Int {
                val id = v.optInt("id", 0)
                if (id > 0) return id
                return v.optInt("quality", 0).takeIf { it > 0 } ?: 0
            }

            fun isDolbyVisionTrack(v: JSONObject): Boolean {
                if (qnOf(v) == 126) return true
                val mime = v.optString("mimeType", v.optString("mime_type", "")).lowercase(Locale.US)
                if (mime.contains("dolby-vision")) return true
                val codecs = v.optString("codecs", "").lowercase(Locale.US)
                return codecs.startsWith("dvhe") || codecs.startsWith("dvh1") || codecs.contains("dovi")
            }

            val rawVideoItems = buildList {
                for (i in 0 until videos.length()) {
                    val v = videos.optJSONObject(i) ?: continue
                    if (baseUrl(v).isBlank()) continue
                    val qn = qnOf(v)
                    if (qn <= 0) continue
                    add(v)
                }
            }

            val videoItems =
                rawVideoItems.filterNot { v ->
                    isDolbyVisionTrack(v) && (!constraints.allowDolbyVision || !deviceSupportsDolbyVision)
                }

            val availableQns = videoItems.map { qnOf(it) }.filter { it > 0 }.distinct()

            val desiredQn = session.targetQn.takeIf { it > 0 } ?: session.preferredQn
            val pickedQn =
                when {
                    availableQns.contains(desiredQn) -> desiredQn
                    availableQns.isNotEmpty() -> availableQns.maxBy { qnRank(it) }
                    else -> 0
                }

            val candidatesByQn = if (pickedQn > 0) videoItems.filter { qnOf(it) == pickedQn } else videoItems
            val candidates =
                when {
                    candidatesByQn.isNotEmpty() -> candidatesByQn
                    videoItems.isNotEmpty() -> {
                        if (pickedQn > 0) {
                            AppLog.w("Player", "wanted qn=$pickedQn but no DASH track matched; fallback to best available")
                        }
                        videoItems
                    }
                    else -> emptyList()
                }

            var bestVideo: JSONObject? = null
            var bestScore = -1L
            for (v in candidates) {
                val codecid = v.optInt("codecid", 0)
                val qn = qnOf(v)
                val bandwidth = v.optLong("bandwidth", 0L)
                val okCodec = (codecid == preferCodecid)
                val score =
                    (qnRank(qn).toLong() * 1_000_000_000_000L) +
                        bandwidth +
                        (if (okCodec) 10_000_000_000L else 0L)
                if (score > bestScore) {
                    bestScore = score
                    bestVideo = v
                }
            }
            val picked = bestVideo
            if (picked == null) {
                AppLog.w("Player", "no DASH video track picked; fallback to durl if possible")
            } else {
                val videoUrl = baseUrl(picked)
                val pickedQnFinal = qnOf(picked)
                val pickedCodecid = picked.optInt("codecid", 0)
                val pickedIsDolbyVision = isDolbyVisionTrack(picked)

                data class AudioCandidate(val obj: JSONObject, val kind: DashAudioKind, val id: Int, val bandwidth: Long)

                val allAudioCandidates = buildList<AudioCandidate> {
                    for (i in 0 until audios.length()) {
                        val a = audios.optJSONObject(i) ?: continue
                        if (baseUrl(a).isBlank()) continue
                        add(AudioCandidate(a, DashAudioKind.NORMAL, a.optInt("id", 0), a.optLong("bandwidth", 0L)))
                    }
                    val dolbyAudios = dolby?.optJSONArray("audio")
                    if (dolbyAudios != null && constraints.allowDolbyAudio) {
                        for (i in 0 until dolbyAudios.length()) {
                            val a = dolbyAudios.optJSONObject(i) ?: continue
                            if (baseUrl(a).isBlank()) continue
                            add(AudioCandidate(a, DashAudioKind.DOLBY, a.optInt("id", 0), a.optLong("bandwidth", 0L)))
                        }
                    }
                    val flacAudio = flac?.optJSONObject("audio")
                    if (flacAudio != null && constraints.allowFlacAudio && baseUrl(flacAudio).isNotBlank()) {
                        add(AudioCandidate(flacAudio, DashAudioKind.FLAC, flacAudio.optInt("id", 0), flacAudio.optLong("bandwidth", 0L)))
                    }
                }

                val audioPool =
                    when (session.preferAudioId) {
                        30250 -> allAudioCandidates.filter { it.kind == DashAudioKind.DOLBY }.ifEmpty { allAudioCandidates }
                        30251 -> allAudioCandidates.filter { it.kind == DashAudioKind.FLAC }.ifEmpty { allAudioCandidates }
                        else -> allAudioCandidates.filter { it.kind == DashAudioKind.NORMAL }.ifEmpty { allAudioCandidates }
                    }

                val audioPicked =
                    audioPool.maxWithOrNull(
                        compareBy<AudioCandidate> { it.bandwidth }.thenBy { if (it.id == session.preferAudioId) 1 else 0 },
                    )
                if (audioPicked == null) {
                    AppLog.w("Player", "no DASH audio track picked; fallback to durl if possible")
                } else {
                    val audioUrl = baseUrl(audioPicked.obj)
                    return Playable.Dash(
                        videoUrl = videoUrl,
                        audioUrl = audioUrl,
                        qn = pickedQnFinal,
                        codecid = pickedCodecid,
                        audioId = audioPicked.id,
                        audioKind = audioPicked.kind,
                        isDolbyVision = pickedIsDolbyVision,
                    )
                }
            }
        }

        // Fallback: try durl (progressive) if dash missing.
        val durlObj = data.optJSONArray("durl")?.optJSONObject(0)
        val url =
            if (durlObj != null) {
                selectCdnUrlFromTrack(durlObj, preference = BiliClient.prefs.playerCdnPreference)
            } else {
                ""
            }
        if (url.isNotBlank()) return Playable.Progressive(url)

        val cid = currentCid.takeIf { it > 0 }
            ?: intent.getLongExtra(EXTRA_CID, -1L).takeIf { it > 0 }
            ?: error("cid missing for fallback")
        val bvid = currentBvid.ifBlank { intent.getStringExtra(EXTRA_BVID).orEmpty() }
        // Extra fallback: request MP4 directly (avoid deprecated fnval=0).
        val fallbackJson =
            requestPlayJson(
                bvid = bvid,
                cid = cid,
                epId = currentEpId,
                qn = 127,
                fnval = 1,
                tryLook = false,
            )
        val fallbackData = fallbackJson.optJSONObject("data") ?: fallbackJson.optJSONObject("result") ?: JSONObject()
        val fallbackObj = fallbackData.optJSONArray("durl")?.optJSONObject(0)
        val fallbackUrl =
            if (fallbackObj != null) {
                selectCdnUrlFromTrack(fallbackObj, preference = BiliClient.prefs.playerCdnPreference)
            } else {
                ""
            }
        if (fallbackUrl.isNotBlank()) return Playable.Progressive(fallbackUrl)

        error("No playable url in playurl response")
    }

    private fun buildMerged(
        factory: OkHttpDataSource.Factory,
        videoUrl: String,
        audioUrl: String,
        subtitle: MediaItem.SubtitleConfiguration?,
    ): MediaSource {
        val subs = listOfNotNull(subtitle)
        val videoSource = ProgressiveMediaSource.Factory(factory).createMediaSource(
            MediaItem.Builder().setUri(Uri.parse(videoUrl)).setSubtitleConfigurations(subs).build(),
        )
        val audioSource = ProgressiveMediaSource.Factory(factory).createMediaSource(
            MediaItem.Builder().setUri(Uri.parse(audioUrl)).build(),
        )
        val subtitleSource = subtitle?.let { buildSubtitleSource(it) }
        return if (subtitleSource != null) MergingMediaSource(videoSource, audioSource, subtitleSource) else MergingMediaSource(videoSource, audioSource)
    }

    private fun buildProgressive(factory: OkHttpDataSource.Factory, url: String, subtitle: MediaItem.SubtitleConfiguration?): MediaSource {
        val subs = listOfNotNull(subtitle)
        val main = ProgressiveMediaSource.Factory(factory).createMediaSource(
            MediaItem.Builder().setUri(Uri.parse(url)).setSubtitleConfigurations(subs).build(),
        )
        val subtitleSource = subtitle?.let { buildSubtitleSource(it) }
        return if (subtitleSource != null) MergingMediaSource(main, subtitleSource) else main
    }

    private fun buildSubtitleSource(subtitle: MediaItem.SubtitleConfiguration): MediaSource {
        val ds = DefaultDataSource.Factory(this)
        return SingleSampleMediaSource.Factory(ds).createMediaSource(subtitle, C.TIME_UNSET)
    }

    private fun showCodecDialog() {
        val options = arrayOf("AVC", "HEVC", "AV1")
        val current = options.indexOf(session.preferCodec).coerceAtLeast(0)
        SingleChoiceDialog.show(
            context = this,
            title = "视频编码",
            items = options.toList(),
            checkedIndex = current,
            negativeText = "取消",
        ) { which, _ ->
            val selected = options.getOrNull(which) ?: "AVC"
            session = session.copy(preferCodec = selected)
            refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
            reloadStream(keepPosition = true)
        }
    }

    private fun showSpeedDialog() {
        val options = arrayOf("0.50x", "0.75x", "1.00x", "1.25x", "1.50x", "2.00x")
        val current = options.indexOf(String.format(Locale.US, "%.2fx", session.playbackSpeed)).let { if (it >= 0) it else 2 }
        SingleChoiceDialog.show(
            context = this,
            title = "播放速度",
            items = options.toList(),
            checkedIndex = current,
            negativeText = "取消",
        ) { which, _ ->
            val selected = options.getOrNull(which) ?: "1.00x"
            val v = selected.removeSuffix("x").toFloatOrNull() ?: 1.0f
            session = session.copy(playbackSpeed = v)
            player?.setPlaybackSpeed(v)
            refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
        }
    }

    private fun reloadStream(keepPosition: Boolean, resetConstraints: Boolean = true) {
        val exo = player ?: return
        val cid = currentCid
        val bvid = currentBvid
        if (cid <= 0 || bvid.isBlank()) return
        val pos = exo.currentPosition
        lifecycleScope.launch {
            try {
                val (qn, fnval) = playUrlParamsForSession()
                if (resetConstraints) {
                    playbackConstraints = PlaybackConstraints()
                    decodeFallbackAttempted = false
                    lastPickedDash = null
                }
                val (playJson, playable) =
                    loadPlayableWithTryLookFallback(
                        bvid = bvid,
                        cid = cid,
                        epId = currentEpId,
                        qn = qn,
                        fnval = fnval,
                        constraints = playbackConstraints,
                    )
                showRiskControlBypassHintIfNeeded(playJson)
                lastAvailableQns = parseDashVideoQnList(playJson)
                val okHttpFactory = OkHttpDataSource.Factory(BiliClient.cdnOkHttp)
                when (playable) {
                    is Playable.Dash -> {
                        lastPickedDash = playable
                        exo.setMediaSource(buildMerged(okHttpFactory, playable.videoUrl, playable.audioUrl, subtitleConfig))
                        applyResolutionFallbackIfNeeded(requestedQn = session.targetQn, actualQn = playable.qn)
                    }
                    is Playable.Progressive -> {
                        lastPickedDash = null
                        exo.setMediaSource(buildProgressive(okHttpFactory, playable.url, subtitleConfig))
                    }
                }
                exo.prepare()
                applySubtitleEnabled(exo)
                if (keepPosition) exo.seekTo(pos)
                exo.playWhenReady = true
            } catch (t: Throwable) {
                AppLog.e("Player", "reloadStream failed", t)
                if (!handlePlayUrlErrorIfNeeded(t)) {
                    Toast.makeText(this@PlayerActivity, "切换失败：${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handlePlayUrlErrorIfNeeded(t: Throwable): Boolean {
        val e = t as? BiliApiException ?: return false
        if (!isRiskControl(e)) return false

        val prefs = BiliClient.prefs
        val savedVoucher = prefs.gaiaVgateVVoucher
        val savedAt = prefs.gaiaVgateVVoucherSavedAtMs
        val hasSavedVoucher = !savedVoucher.isNullOrBlank()

        val msg =
            buildString {
                append("B 站返回：").append(e.apiCode).append(" / ").append(e.apiMessage)
                append("\n\n")
                if (e.apiCode == -352 && hasSavedVoucher) {
                    append("已记录 v_voucher，可到“设置 -> 风控验证”手动完成人机验证后重试播放。")
                    if (savedAt > 0L) {
                        append("\n")
                        append("记录时间：").append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", savedAt))
                    }
                } else {
                    append("你的账号或网络环境可能触发风控，建议重新登录或稍后重试。")
                }
                append("\n")
                append("如持续出现，请向作者反馈日志。")
            }

        if (e.apiCode == -352 && hasSavedVoucher) {
            MaterialAlertDialogBuilder(this)
                .setTitle("需要风控验证")
                .setMessage(msg)
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("关闭", null)
                .show()
        } else {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        return true
    }

    private fun showRiskControlBypassHintIfNeeded(playJson: JSONObject) {
        if (riskControlBypassHintShown) return
        if (!playJson.optBoolean("__blbl_risk_control_bypassed", false)) return
        riskControlBypassHintShown = true

        val code = playJson.optInt("__blbl_risk_control_code", 0)
        val message = playJson.optString("__blbl_risk_control_message", "")
        val msg =
            buildString {
                append("B 站返回：").append(code).append(" / ").append(message)
                append("\n\n")
                append("你的账号或网络环境可能触发风控，建议重新登录或稍后重试。")
                append("\n")
                append("如持续出现，请向作者反馈日志。")
            }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun isRiskControl(e: BiliApiException): Boolean {
        if (e.apiCode == -412 || e.apiCode == -352) return true
        val m = e.apiMessage
        return m.contains("风控") || m.contains("拦截") || m.contains("风险")
    }

    private fun updateDebugOverlay() {
        val enabled = session.debugEnabled
        binding.tvDebug.visibility = if (enabled) View.VISIBLE else View.GONE
        debugJob?.cancel()
        if (!enabled) return
        val exo = player ?: return
        debugJob = lifecycleScope.launch {
            while (isActive) {
                binding.tvDebug.text = buildDebugText(exo)
                delay(500)
            }
        }
    }

    private fun buildDebugText(exo: ExoPlayer): String {
        val sb = StringBuilder()
        sb.append(
            when (exo.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFER"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> exo.playbackState.toString()
            },
        )
        sb.append(" playing=").append(exo.isPlaying)
        sb.append('\n')
        sb.append("pos=").append(exo.currentPosition).append("ms")
        sb.append(" buf=").append(exo.bufferedPosition).append("ms")
        sb.append(" spd=").append(String.format(Locale.US, "%.2f", exo.playbackParameters.speed))
        sb.append('\n')
        val vs = exo.videoSize
        if (vs.width > 0 && vs.height > 0) {
            sb.append("size=").append(vs.width).append("x").append(vs.height)
            sb.append(" par=").append(String.format(Locale.US, "%.2f", vs.pixelWidthHeightRatio))
            sb.append('\n')
        }
        val tracks = exo.currentTracks
        var videoLine: String? = null
        var audioLine: String? = null
        for (g in tracks.groups) {
            if (!g.isSelected) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSelected(i)) continue
                val f = g.getTrackFormat(i)
                val mime = f.sampleMimeType ?: ""
                val codecs = f.codecs ?: ""
                if (mime.startsWith("video/") && videoLine == null) {
                    videoLine = "v=$mime ${f.width}x${f.height} br=${f.bitrate} $codecs"
                }
                if (mime.startsWith("audio/") && audioLine == null) {
                    audioLine = "a=$mime br=${f.bitrate} ch=${f.channelCount} $codecs"
                }
            }
        }
        if (videoLine != null) sb.append(videoLine).append('\n')
        if (audioLine != null) sb.append(audioLine).append('\n')
        sb.append("ua=").append(BiliClient.prefs.userAgent.take(28)).append("…")
        return sb.toString()
    }

    private fun showDanmakuOpacityDialog() {
        val options = listOf(1.0f, 0.8f, 0.6f, 0.4f, 0.2f)
        val items = options.map { String.format(Locale.US, "%.2f", it) }
        val current = options.indexOfFirst { kotlin.math.abs(it - session.danmaku.opacity) < 0.01f }.let { if (it >= 0) it else 0 }
        SingleChoiceDialog.show(
            context = this,
            title = "弹幕透明度",
            items = items,
            checkedIndex = current,
            negativeText = "取消",
        ) { which, _ ->
            val v = options.getOrNull(which) ?: session.danmaku.opacity
            session = session.copy(danmaku = session.danmaku.copy(opacity = v))
            binding.danmakuView.invalidate()
            refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
        }
    }

    private fun showDanmakuTextSizeDialog() {
        val options = listOf(14, 16, 18, 20, 22, 24, 28, 32, 36, 40)
        val items = options.map { it.toString() }.toTypedArray()
        val current = options.indexOf(session.danmaku.textSizeSp.toInt()).let { if (it >= 0) it else 2 }
        SingleChoiceDialog.show(
            context = this,
            title = "弹幕字号(sp)",
            items = items.toList(),
            checkedIndex = current,
            negativeText = "取消",
        ) { which, _ ->
            val v = (options.getOrNull(which) ?: session.danmaku.textSizeSp.toInt()).toFloat()
            session = session.copy(danmaku = session.danmaku.copy(textSizeSp = v))
            binding.danmakuView.invalidate()
            refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
        }
    }

    private fun showDanmakuSpeedDialog() {
        val options = (1..10).toList()
        val items = options.map { it.toString() }
        val current = options.indexOf(session.danmaku.speedLevel).let { if (it >= 0) it else 3 }
        SingleChoiceDialog.show(
            context = this,
            title = "弹幕速度(1~10)",
            items = items,
            checkedIndex = current,
            negativeText = "取消",
        ) { which, _ ->
            val v = options.getOrNull(which) ?: session.danmaku.speedLevel
            session = session.copy(danmaku = session.danmaku.copy(speedLevel = v))
            binding.danmakuView.invalidate()
            refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
        }
    }

    private fun showDanmakuAreaDialog() {
        val options = listOf(
            0.25f to "1/4",
            0.50f to "1/2",
            0.75f to "3/4",
            1.00f to "不限",
        )
        val items = options.map { it.second }
        val current = options.indexOfFirst { kotlin.math.abs(it.first - session.danmaku.area) < 0.01f }.let { if (it >= 0) it else 3 }
        SingleChoiceDialog.show(
            context = this,
            title = "弹幕区域",
            items = items,
            checkedIndex = current,
            negativeText = "取消",
        ) { which, _ ->
            val v = options.getOrNull(which)?.first ?: session.danmaku.area
            session = session.copy(danmaku = session.danmaku.copy(area = v))
            binding.danmakuView.invalidate()
            refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
        }
    }

    private suspend fun prepareSubtitleConfig(
        viewData: JSONObject,
        bvid: String,
        cid: Long,
        trace: PlaybackTrace?,
    ): MediaItem.SubtitleConfiguration? {
        trace?.log("subtitle:items:start")
        val items = fetchSubtitleItems(viewData, bvid, cid, trace)
        trace?.log("subtitle:items:done", "count=${items.size}")
        subtitleItems = items
        val chosen = pickSubtitleItem(items) ?: return null
        trace?.log("subtitle:download:start", "lan=${chosen.lan}")
        return buildSubtitleConfigFromItem(chosen, bvid, cid, trace)
    }

    private suspend fun buildSubtitleConfigFromItem(
        item: SubtitleItem,
        bvid: String,
        cid: Long,
        trace: PlaybackTrace? = null,
    ): MediaItem.SubtitleConfiguration? {
        val subtitleJson = runCatching { BiliClient.getJson(item.url) }.getOrNull() ?: return null
        val body = subtitleJson.optJSONArray("body") ?: subtitleJson.optJSONObject("data")?.optJSONArray("body") ?: return null

        val vtt = buildWebVtt(body)
        if (vtt.isBlank()) return null

        val safeLan = item.lan.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = File(cacheDir, "sub_${bvid}_${cid}_${safeLan}.vtt")
        runCatching { file.writeText(vtt, Charsets.UTF_8) }.getOrElse { return null }
        trace?.log("subtitle:download:done", "vttBytes=${vtt.toByteArray(Charsets.UTF_8).size}")

        return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(item.lan)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    private suspend fun fetchSubtitleItems(
        viewData: JSONObject,
        bvid: String,
        cid: Long,
        trace: PlaybackTrace?,
    ): List<SubtitleItem> {
        trace?.log("subtitle:playerWbiV2:start")
        val playerJson = runCatching { BiliApi.playerWbiV2(bvid = bvid, cid = cid) }.getOrNull()
        trace?.log("subtitle:playerWbiV2:done", "ok=${playerJson != null}")
        val data = playerJson?.optJSONObject("data")
        val needLogin = data?.optBoolean("need_login_subtitle") ?: false
        val list = data?.optJSONObject("subtitle")?.optJSONArray("subtitles") ?: JSONArray()
        if (list.length() == 0 && needLogin && !BiliClient.cookies.hasSessData()) {
            return emptyList()
        }
        if (list.length() == 0) {
            // Fallback: try older view payload (some responses may include it).
            val legacy = viewData.optJSONObject("subtitle")?.optJSONArray("list") ?: JSONArray()
            if (legacy.length() == 0) return emptyList()
            return buildList {
                for (i in 0 until legacy.length()) {
                    val it = legacy.optJSONObject(i) ?: continue
                    val url = it.optString("subtitle_url", it.optString("subtitleUrl", "")).trim()
                    if (url.isBlank()) continue
                    val lan = it.optString("lan", "")
                    val doc = it.optString("lan_doc", it.optString("language", lan))
                    add(SubtitleItem(lan = lan.ifBlank { "unknown" }, lanDoc = doc.ifBlank { lan }, url = normalizeUrl(url)))
                }
            }
        }
        return buildList {
            for (i in 0 until list.length()) {
                val it = list.optJSONObject(i) ?: continue
                val url = it.optString("subtitle_url", "").trim()
                val lan = it.optString("lan", "").trim()
                val doc = it.optString("lan_doc", "").trim()
                if (url.isBlank() || lan.isBlank()) continue
                add(SubtitleItem(lan = lan, lanDoc = doc.ifBlank { lan }, url = normalizeUrl(url)))
            }
        }
    }

    private fun pickSubtitleItem(items: List<SubtitleItem>): SubtitleItem? {
        if (items.isEmpty()) return null
        val prefs = BiliClient.prefs
        val preferred = session.subtitleLangOverride ?: prefs.subtitlePreferredLang
        if (preferred == "auto" || preferred.isBlank()) return items.first()
        return items.firstOrNull { it.lan.equals(preferred, ignoreCase = true) } ?: items.first()
    }

    private fun subtitleLangSubtitle(): String {
        if (subtitleItems.isEmpty()) return "无/未加载"
        val prefs = BiliClient.prefs
        val preferred = session.subtitleLangOverride ?: prefs.subtitlePreferredLang
        if (session.subtitleLangOverride == null) {
            val resolved = resolveSubtitleLang(preferred)
            return "全局：$resolved"
        }
        return resolveSubtitleLang(preferred)
    }

    private fun resolveSubtitleLang(code: String): String {
        if (subtitleItems.isEmpty()) return "无"
        if (code == "auto" || code.isBlank()) {
            val first = subtitleItems.first()
            return "自动：${first.lanDoc}"
        }
        val found = subtitleItems.firstOrNull { it.lan.equals(code, ignoreCase = true) } ?: subtitleItems.first()
        return "${found.lanDoc}"
    }

    private fun showSubtitleLangDialog() {
        val exo = player ?: return
        if (subtitleItems.isEmpty()) {
            Toast.makeText(this, "该视频暂无字幕", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = BiliClient.prefs
        val global = prefs.subtitlePreferredLang
        val items = buildList {
            add("跟随全局（${resolveSubtitleLang(global)}）")
            add("自动（取第一个）")
            subtitleItems.forEach { add(it.lanDoc) }
        }
        val currentLabel =
            when (val ov = session.subtitleLangOverride) {
                null -> "跟随全局（${resolveSubtitleLang(global)}）"
                "auto" -> "自动（取第一个）"
                else -> subtitleItems.firstOrNull { it.lan.equals(ov, ignoreCase = true) }?.lanDoc ?: subtitleItems.first().lanDoc
            }
        val checked = items.indexOf(currentLabel).coerceAtLeast(0)
        SingleChoiceDialog.show(
            context = this,
            title = "字幕语言（本次播放）",
            items = items,
            checkedIndex = checked,
            negativeText = "取消",
        ) { which, _ ->
            val chosen = items.getOrNull(which).orEmpty()
            session =
                when {
                    chosen.startsWith("跟随全局") -> session.copy(subtitleLangOverride = null)
                    chosen.startsWith("自动") -> session.copy(subtitleLangOverride = "auto")
                    else -> {
                        val code = subtitleItems.firstOrNull { it.lanDoc == chosen }?.lan ?: subtitleItems.first().lan
                        session.copy(subtitleLangOverride = code)
                    }
                }
            lifecycleScope.launch {
                subtitleConfig = buildSubtitleConfigFromCurrentSelection(bvid = currentBvid, cid = currentCid)
                subtitleAvailable = subtitleConfig != null
                applySubtitleEnabled(exo)
                refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
                updateSubtitleButton()
                reloadStream(keepPosition = true)
            }
        }
    }

    private suspend fun buildSubtitleConfigFromCurrentSelection(bvid: String, cid: Long): MediaItem.SubtitleConfiguration? {
        if (bvid.isBlank() || cid <= 0) return null
        val chosen = pickSubtitleItem(subtitleItems) ?: return null
        return buildSubtitleConfigFromItem(chosen, bvid, cid)
    }

    private fun normalizeUrl(url: String): String {
        val u = url.trim()
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://") || u.startsWith("https://") -> u
            else -> "https://$u"
        }
    }

    private fun buildWebVtt(body: JSONArray): String {
        val sb = StringBuilder()
        sb.append("WEBVTT\n\n")
        for (i in 0 until body.length()) {
            val line = body.optJSONObject(i) ?: continue
            val from = line.optDouble("from", -1.0)
            val to = line.optDouble("to", -1.0)
            val content = line.optString("content", "").trim()
            if (from < 0 || to <= 0 || content.isBlank()) continue
            sb.append(formatVttTime(from)).append(" --> ").append(formatVttTime(to)).append('\n')
            sb.append(content.replace('\n', ' ')).append("\n\n")
        }
        return sb.toString()
    }

    private fun formatVttTime(sec: Double): String {
        val ms = (sec * 1000.0).toLong().coerceAtLeast(0L)
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val milli = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, milli)
    }

    private fun formatHms(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    }

    private data class SubtitleItem(
        val lan: String,
        val lanDoc: String,
        val url: String,
    )

    private data class DanmakuMeta(
        val shield: DanmakuShield,
        val segmentTotal: Int,
        val segmentSizeMs: Int,
    )

    private suspend fun prepareDanmakuMeta(cid: Long, aid: Long?, trace: PlaybackTrace? = null): DanmakuMeta {
        trace?.log("danmakuMeta:prepare:start", "cid=$cid aid=${aid ?: -1}")
        return withContext(Dispatchers.IO) {
            val prefs = BiliClient.prefs
            val followBili = prefs.danmakuFollowBiliShield
            if (session.debugEnabled) {
                AppLog.d("Player", "danmakuMeta cid=$cid aid=${aid ?: -1} followBili=$followBili hasSess=${BiliClient.cookies.hasSessData()}")
            }
            val dmView = if (followBili && BiliClient.cookies.hasSessData()) {
                val t0 = SystemClock.elapsedRealtime()
                runCatching { BiliApi.dmWebView(cid, aid) }
                    .onFailure { AppLog.w("Player", "dmWebView failed", it) }
                    .getOrNull()
                    .also {
                        val cost = SystemClock.elapsedRealtime() - t0
                        trace?.log("danmakuMeta:dmWebView", "ok=${it != null} cost=${cost}ms")
                    }
            } else {
                null
            }
            val setting = dmView?.setting
            val shield = DanmakuShield(
                allowScroll = prefs.danmakuAllowScroll && (setting?.allowScroll ?: true),
                allowTop = prefs.danmakuAllowTop && (setting?.allowTop ?: true),
                allowBottom = prefs.danmakuAllowBottom && (setting?.allowBottom ?: true),
                allowColor = prefs.danmakuAllowColor && (setting?.allowColor ?: true),
                allowSpecial = prefs.danmakuAllowSpecial && (setting?.allowSpecial ?: true),
                aiEnabled = prefs.danmakuAiShieldEnabled || (setting?.aiEnabled ?: false),
                aiLevel = maxOf(prefs.danmakuAiShieldLevel, setting?.aiLevel ?: 0),
            )
            val segmentTotal = dmView?.segmentTotal?.takeIf { it > 0 } ?: 0
            val segmentSizeMs = dmView?.segmentPageSizeMs?.takeIf { it > 0 }?.toInt() ?: DANMAKU_DEFAULT_SEGMENT_MS
            AppLog.i(
                "Player",
                "danmaku cid=$cid segTotal=$segmentTotal segSizeMs=$segmentSizeMs followBili=$followBili hasDmSetting=${setting != null}",
            )
            DanmakuMeta(shield = shield, segmentTotal = segmentTotal, segmentSizeMs = segmentSizeMs).also {
                trace?.log("danmakuMeta:prepare:done")
            }
        }
    }

    private fun applyDanmakuMeta(meta: DanmakuMeta) {
        danmakuShield = meta.shield
        danmakuSegmentTotal = meta.segmentTotal
        danmakuSegmentSizeMs = meta.segmentSizeMs.coerceAtLeast(1)
        danmakuLoadedSegments.clear()
        danmakuLoadingSegments.clear()
        danmakuAll.clear()
    }

    private fun requestDanmakuSegmentsForPosition(positionMs: Long, immediate: Boolean) {
        val debug = session.debugEnabled
        if (danmakuShield == null) {
            if (debug) AppLog.d("Player", "danmaku prefetch skipped: shield=null")
            return
        }
        if (!session.danmaku.enabled) {
            if (debug) AppLog.d("Player", "danmaku prefetch skipped: disabled")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (!immediate && now - lastDanmakuPrefetchAtMs < DANMAKU_PREFETCH_INTERVAL_MS) {
            if (debug) AppLog.d("Player", "danmaku prefetch skipped: interval")
            return
        }
        lastDanmakuPrefetchAtMs = now

        val cid = currentCid.takeIf { it > 0 } ?: return
        val segSize = danmakuSegmentSizeMs.coerceAtLeast(1)
        val targetSeg = (positionMs / segSize).toInt() + 1
        if (targetSeg <= 0) return

        val toLoad = buildList {
            add(targetSeg)
            for (i in 1..DANMAKU_PREFETCH_SEGMENTS) add(targetSeg + i)
        }.filter { canLoadSegment(it) }

        if (toLoad.isEmpty()) return

        if (debug) {
            AppLog.d(
                "Player",
                "danmaku prefetch cid=$cid pos=${positionMs}ms segSizeMs=$segSize targetSeg=$targetSeg toLoad=${toLoad.joinToString()} segTotal=$danmakuSegmentTotal",
            )
        }

            lifecycleScope.launch(Dispatchers.IO) {
                val shield = danmakuShield
                val newItems = ArrayList<blbl.cat3399.core.model.Danmaku>()
                val loaded = ArrayList<Int>()
                for (seg in toLoad) {
                val t0 = SystemClock.elapsedRealtime()
                val list = runCatching { BiliApi.dmSeg(cid, seg) }.getOrNull()
                val cost = SystemClock.elapsedRealtime() - t0
                if (list == null) {
                    if (debug) AppLog.d("Player", "danmaku seg=$seg fetch failed cost=${cost}ms")
                    continue
                }
                val before = list.size
                val filtered = if (shield != null) list.filter(shield::allow) else list
                val after = filtered.size
                if (debug) {
                    AppLog.d("Player", "danmaku seg=$seg items=$before kept=$after cost=${cost}ms")
                }
                if (before > 0 && after == 0 && debug) {
                    AppLog.d("Player", "danmaku seg=$seg filteredAll (shield)")
                }
                if (filtered.isNotEmpty()) newItems.addAll(filtered)
                loaded.add(seg)
                }
                if (newItems.isNotEmpty()) {
                    newItems.sortBy { it.timeMs }
                }
                withContext(Dispatchers.Main) {
                    danmakuLoadingSegments.removeAll(toLoad)
                    danmakuLoadedSegments.addAll(loaded)
                    if (newItems.isNotEmpty()) {
                    // Keep cache roughly ordered; avoid sorting on Main (can jank badly on massive danmaku).
                    val last = danmakuAll.lastOrNull()?.timeMs
                    if (last == null || newItems.first().timeMs >= last) {
                        danmakuAll.addAll(newItems)
                    } else {
                        danmakuAll.addAll(newItems)
                        danmakuAll.sortBy { it.timeMs }
                    }
                    trimDanmakuCacheIfNeeded(positionMs)
                    // Incremental append to avoid clearing currently running danmaku (prevents "sudden disappear").
                    binding.danmakuView.appendDanmakus(newItems, alreadySorted = true)
                } else if (debug) {
                    AppLog.d("Player", "danmaku loadedSegs=${loaded.joinToString()} but no new items (after filter)")
                }
            }
        }
    }

    private fun canLoadSegment(segmentIndex: Int): Boolean {
        if (segmentIndex <= 0) return false
        if (danmakuSegmentTotal > 0 && segmentIndex > danmakuSegmentTotal) return false
        if (danmakuLoadedSegments.contains(segmentIndex)) return false
        if (danmakuLoadingSegments.contains(segmentIndex)) return false
        danmakuLoadingSegments.add(segmentIndex)
        return true
    }

    private fun trimDanmakuCacheIfNeeded(positionMs: Long) {
        if (danmakuLoadedSegments.size <= DANMAKU_CACHE_SEGMENTS) return
        val segSize = danmakuSegmentSizeMs.coerceAtLeast(1)
        val currentSeg = (positionMs / segSize).toInt() + 1
        val minSeg = (currentSeg - DANMAKU_CACHE_SEGMENTS / 2).coerceAtLeast(1)
        val maxSeg = minSeg + DANMAKU_CACHE_SEGMENTS - 1
        val keepSegs = danmakuLoadedSegments.filter { it in minSeg..maxSeg }.toSet()
        if (keepSegs.size == danmakuLoadedSegments.size) return
        danmakuLoadedSegments.retainAll(keepSegs)
        val filtered = danmakuAll.filter { ((it.timeMs / segSize) + 1) in keepSegs }
        danmakuAll.clear()
        danmakuAll.addAll(filtered)

        // Keep DanmakuView/Engine memory bounded as well, without clearing currently running items.
        val minTimeMs = (minSeg - 1L) * segSize.toLong()
        val maxTimeMs = maxSeg.toLong() * segSize.toLong()
        binding.danmakuView.trimToTimeRange(minTimeMs = minTimeMs, maxTimeMs = maxTimeMs)
    }

    private fun configureSubtitleView() {
        val subtitleView = binding.playerView.findViewById<SubtitleView>(androidx.media3.ui.R.id.exo_subtitles) ?: return
        // Move subtitles slightly up from the very bottom.
        subtitleView.setBottomPaddingFraction(0.16f)
        // Make background more transparent while keeping readability.
        subtitleView.setStyle(
            CaptionStyleCompat(
                /* foregroundColor= */ 0xFFFFFFFF.toInt(),
                /* backgroundColor= */ 0x22000000,
                /* windowColor= */ 0x00000000,
                /* edgeType= */ CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                /* edgeColor= */ 0xCC000000.toInt(),
                /* typeface= */ null,
            ),
        )
    }

    private fun areaText(area: Float): String = when {
        area >= 0.99f -> "不限"
        area >= 0.74f -> "3/4"
        area >= 0.49f -> "1/2"
        else -> "1/4"
    }

    private data class PlayerSessionSettings(
        val playbackSpeed: Float,
        val preferCodec: String,
        val preferAudioId: Int,
        val preferredQn: Int,
        val targetQn: Int,
        val playbackModeOverride: String?,
        val subtitleEnabled: Boolean,
        val subtitleLangOverride: String?,
        val danmaku: DanmakuSessionSettings,
        val debugEnabled: Boolean,
    )

    private fun resolutionSubtitle(): String {
        val forced = session.targetQn
        return if (forced > 0) {
            qnLabel(forced)
        } else {
            "自动(${qnLabel(session.preferredQn)})"
        }
    }

    private fun showResolutionDialog() {
        val options = buildResolutionOptions()
        val currentQn = session.targetQn
        val currentIndex =
            options.indexOfFirst { parseResolutionFromOption(it) == currentQn }
                .takeIf { it >= 0 } ?: 0
        SingleChoiceDialog.show(
            context = this,
            title = "分辨率",
            items = options,
            checkedIndex = currentIndex,
            negativeText = "取消",
        ) { which, _ ->
            val selected = options.getOrNull(which).orEmpty()
            val qn = parseResolutionFromOption(selected)
            session = session.copy(targetQn = qn)
            refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
            reloadStream(keepPosition = true)
        }
    }

    private fun buildResolutionOptions(): List<String> {
        // Follow docs: qn list for resolution/framerate.
        val docQns = listOf(16, 32, 64, 74, 80, 100, 112, 116, 120, 125, 126, 127, 129)
        val available = lastAvailableQns.toSet()
        return buildList {
            add("自动(${qnLabel(session.preferredQn)})")
            for (qn in docQns) {
                val label = qnLabel(qn)
                val prefix = qn.toString().padStart(3, ' ') + " "
                add(if (available.contains(qn)) "${prefix}${label}（可用）" else "${prefix}${label}")
            }
        }
    }

    private fun parseResolutionFromOption(option: String): Int {
        if (option.startsWith("自动")) return 0
        val raw = option.trimStart().takeWhile { it.isDigit() }
        return raw.toIntOrNull() ?: 0
    }

    private fun qnLabel(qn: Int): String = when (qn) {
        16 -> "360P 流畅"
        32 -> "480P 清晰"
        64 -> "720P 高清"
        74 -> "720P60 高帧率"
        80 -> "1080P 高清"
        100 -> "智能修复"
        112 -> "1080P+ 高码率"
        116 -> "1080P60 高帧率"
        120 -> "4K 超清"
        125 -> "HDR 真彩色"
        126 -> "杜比视界"
        127 -> "8K 超高清"
        129 -> "HDR Vivid"
        else -> "qn $qn"
    }

    private fun playUrlParamsForSession(): Pair<Int, Int> {
        // Always request the highest; B 站会根据登录/会员权限返回实际可用清晰度。
        val qn = 127
        var fnval = 4048 // all available DASH video
        fnval = fnval or 128 // 4K
        fnval = fnval or 1024 // 8K
        fnval = fnval or 64 // HDR (may be ignored if not allowed)
        fnval = fnval or 512 // Dolby Vision (may be ignored if not allowed)
        return qn to fnval
    }

    private fun applyResolutionFallbackIfNeeded(requestedQn: Int, actualQn: Int) {
        if (requestedQn <= 0) return
        if (actualQn <= 0 || requestedQn == actualQn) return
        val fallbackQn = lastAvailableQns.maxByOrNull { qnRank(it) } ?: actualQn
        session = session.copy(targetQn = fallbackQn)
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
    }

    private fun parseDashVideoQnList(playJson: JSONObject): List<Int> {
        val data = playJson.optJSONObject("data") ?: playJson.optJSONObject("result") ?: return emptyList()
        val dash = data.optJSONObject("dash") ?: return emptyList()
        val videos = dash.optJSONArray("video") ?: return emptyList()
        val list = ArrayList<Int>(videos.length())

        fun baseUrl(obj: JSONObject): String =
            obj.optString("baseUrl", obj.optString("base_url", obj.optString("url", "")))

        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            if (baseUrl(v).isBlank()) continue
            val qn = v.optInt("id", 0).takeIf { it > 0 } ?: v.optInt("quality", 0)
            if (qn > 0) list.add(qn)
        }
        return list.distinct().sortedBy { qnRank(it) }
    }

    private fun qnRank(qn: Int): Int {
        // Follow docs ordering (roughly increasing quality).
        val order = intArrayOf(6, 16, 32, 64, 74, 80, 100, 112, 116, 120, 125, 126, 127, 129)
        val idx = order.indexOf(qn)
        return if (idx >= 0) idx else (order.size + qn)
    }

    private data class DanmakuSessionSettings(
        val enabled: Boolean,
        val opacity: Float,
        val textSizeSp: Float,
        val speedLevel: Int,
        val area: Float,
    ) {
        fun toConfig(): blbl.cat3399.feature.player.danmaku.DanmakuConfig =
            blbl.cat3399.feature.player.danmaku.DanmakuConfig(
                enabled = enabled,
                opacity = opacity,
                textSizeSp = textSizeSp,
                speedLevel = speedLevel,
                area = area,
            )
    }

    private fun applyUiMode() {
        val tvMode = TvMode.isEnabled(this)
        val uiScale = UiScale.factor(this, tvMode, BiliClient.prefs.sidebarSize)

        fun px(id: Int): Int = resources.getDimensionPixelSize(id)
        fun pxF(id: Int): Float = resources.getDimension(id)
        fun scaledPx(id: Int): Int = (px(id) * uiScale).roundToInt().coerceAtLeast(0)
        fun scaledPxF(id: Int): Float = pxF(id) * uiScale

        val topPadH = scaledPx(if (tvMode) R.dimen.player_top_bar_padding_h_tv else R.dimen.player_top_bar_padding_h)
        val topPadV = scaledPx(if (tvMode) R.dimen.player_top_bar_padding_v_tv else R.dimen.player_top_bar_padding_v)
        if (
            binding.topBar.paddingLeft != topPadH ||
            binding.topBar.paddingRight != topPadH ||
            binding.topBar.paddingTop != topPadV ||
            binding.topBar.paddingBottom != topPadV
        ) {
            binding.topBar.setPadding(topPadH, topPadV, topPadH, topPadV)
        }

        val topBtnSize = scaledPx(if (tvMode) R.dimen.player_top_button_size_tv else R.dimen.player_top_button_size).coerceAtLeast(1)
        val topBtnPad = scaledPx(if (tvMode) R.dimen.player_top_button_padding_tv else R.dimen.player_top_button_padding)
        setSize(binding.btnBack, topBtnSize, topBtnSize)
        binding.btnBack.setPadding(topBtnPad, topBtnPad, topBtnPad, topBtnPad)
        setSize(binding.btnSettings, topBtnSize, topBtnSize)
        binding.btnSettings.setPadding(topBtnPad, topBtnPad, topBtnPad, topBtnPad)

        binding.tvTitle.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(if (tvMode) R.dimen.player_title_text_size_tv else R.dimen.player_title_text_size),
        )
        (binding.tvTitle.layoutParams as? MarginLayoutParams)?.let { lp ->
            val ms = scaledPx(if (tvMode) R.dimen.player_title_margin_start_tv else R.dimen.player_title_margin_start)
            val me = scaledPx(if (tvMode) R.dimen.player_title_margin_end_tv else R.dimen.player_title_margin_end)
            if (lp.marginStart != ms || lp.marginEnd != me) {
                lp.marginStart = ms
                lp.marginEnd = me
                binding.tvTitle.layoutParams = lp
            }
        }

        binding.tvOnline.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(if (tvMode) R.dimen.player_online_text_size_tv else R.dimen.player_online_text_size),
        )

        val bottomPadV = scaledPx(if (tvMode) R.dimen.player_bottom_bar_padding_v_tv else R.dimen.player_bottom_bar_padding_v)
        if (binding.bottomBar.paddingTop != bottomPadV || binding.bottomBar.paddingBottom != bottomPadV) {
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                bottomPadV,
                binding.bottomBar.paddingRight,
                bottomPadV,
            )
        }

        (binding.seekProgress.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height = scaledPx(if (tvMode) R.dimen.player_seekbar_height_tv else R.dimen.player_seekbar_height).coerceAtLeast(1)
            val mb = scaledPx(if (tvMode) R.dimen.player_seekbar_margin_bottom_tv else R.dimen.player_seekbar_margin_bottom)
            if (lp.height != height || lp.bottomMargin != mb) {
                lp.height = height
                lp.bottomMargin = mb
                binding.seekProgress.layoutParams = lp
            }
        }

        (binding.controlsRow.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height = scaledPx(if (tvMode) R.dimen.player_controls_row_height_tv else R.dimen.player_controls_row_height).coerceAtLeast(1)
            val ms = scaledPx(if (tvMode) R.dimen.player_controls_row_margin_start_tv else R.dimen.player_controls_row_margin_start)
            val me = scaledPx(if (tvMode) R.dimen.player_controls_row_margin_end_tv else R.dimen.player_controls_row_margin_end)
            if (lp.height != height || lp.marginStart != ms || lp.marginEnd != me) {
                lp.height = height
                lp.marginStart = ms
                lp.marginEnd = me
                binding.controlsRow.layoutParams = lp
            }
        }

        val controlSize = scaledPx(if (tvMode) R.dimen.player_control_button_size_tv else R.dimen.player_control_button_size).coerceAtLeast(1)
        val subtitleHeight =
            scaledPx(if (tvMode) R.dimen.player_control_button_height_subtitle_tv else R.dimen.player_control_button_height_subtitle).coerceAtLeast(1)
        val settingsSize =
            scaledPx(if (tvMode) R.dimen.player_control_button_size_settings_tv else R.dimen.player_control_button_size_settings).coerceAtLeast(1)
        val controlPad = scaledPx(if (tvMode) R.dimen.player_control_button_padding_tv else R.dimen.player_control_button_padding)
        listOf(binding.btnSubtitle, binding.btnDanmaku, binding.btnUp).forEach { btn ->
            setSize(btn, controlSize, subtitleHeight)
            btn.setPadding(controlPad, controlPad, controlPad, controlPad)
        }
        run {
            val btn = binding.btnAdvanced
            setSize(btn, settingsSize, settingsSize)
            btn.setPadding(controlPad, controlPad, controlPad, controlPad)
        }
        if (tvMode) {
            fun setEndMargin(view: View, marginEndPx: Int) {
                val lp = view.layoutParams as? MarginLayoutParams ?: return
                if (lp.marginEnd == marginEndPx) return
                lp.marginEnd = marginEndPx
                view.layoutParams = lp
            }

            val transportSize = scaledPx(R.dimen.player_control_button_size_main_tv).coerceAtLeast(1)
            val playSize = scaledPx(R.dimen.player_control_button_size_main_play_tv).coerceAtLeast(1)
            val transportPad = scaledPx(R.dimen.player_control_button_padding_main_tv)
            val gap = scaledPx(R.dimen.player_control_button_gap_tv)

            setSize(binding.btnPrev, transportSize, transportSize)
            binding.btnPrev.setPadding(transportPad, transportPad, transportPad, transportPad)
            setEndMargin(binding.btnPrev, gap)

            setSize(binding.btnPlayPause, playSize, playSize)
            binding.btnPlayPause.setPadding(transportPad, transportPad, transportPad, transportPad)
            setEndMargin(binding.btnPlayPause, gap)

            setSize(binding.btnNext, transportSize, transportSize)
            binding.btnNext.setPadding(transportPad, transportPad, transportPad, transportPad)
            setEndMargin(binding.btnNext, gap)
        }

        binding.tvTime.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(if (tvMode) R.dimen.player_time_text_size_tv else R.dimen.player_time_text_size),
        )
        (binding.tvTime.layoutParams as? MarginLayoutParams)?.let { lp ->
            val me = scaledPx(if (tvMode) R.dimen.player_time_margin_end_tv else R.dimen.player_time_margin_end)
            if (lp.marginEnd != me) {
                lp.marginEnd = me
                binding.tvTime.layoutParams = lp
            }
        }

        binding.tvSeekHint.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(if (tvMode) R.dimen.player_seek_hint_text_size_tv else R.dimen.player_seek_hint_text_size),
        )
        val hintPadH = scaledPx(if (tvMode) R.dimen.player_seek_hint_padding_h_tv else R.dimen.player_seek_hint_padding_h)
        val hintPadV = scaledPx(if (tvMode) R.dimen.player_seek_hint_padding_v_tv else R.dimen.player_seek_hint_padding_v)
        if (
            binding.tvSeekHint.paddingLeft != hintPadH ||
            binding.tvSeekHint.paddingRight != hintPadH ||
            binding.tvSeekHint.paddingTop != hintPadV ||
            binding.tvSeekHint.paddingBottom != hintPadV
        ) {
            binding.tvSeekHint.setPadding(hintPadH, hintPadV, hintPadH, hintPadV)
        }
        (binding.tvSeekHint.layoutParams as? MarginLayoutParams)?.let { lp ->
            val ms = scaledPx(if (tvMode) R.dimen.player_seek_hint_margin_start_tv else R.dimen.player_seek_hint_margin_start)
            val mb = scaledPx(if (tvMode) R.dimen.player_seek_hint_margin_bottom_tv else R.dimen.player_seek_hint_margin_bottom)
            if (lp.marginStart != ms || lp.bottomMargin != mb) {
                lp.marginStart = ms
                lp.bottomMargin = mb
                binding.tvSeekHint.layoutParams = lp
            }
        }
    }

    private fun setSize(view: View, widthPx: Int, heightPx: Int) {
        val lp = view.layoutParams ?: return
        if (lp.width == widthPx && lp.height == heightPx) return
        lp.width = widthPx
        lp.height = heightPx
        view.layoutParams = lp
    }

    companion object {
        const val EXTRA_BVID = "bvid"
        const val EXTRA_CID = "cid"
        const val EXTRA_EP_ID = "ep_id"
        const val EXTRA_AID = "aid"
        const val EXTRA_PLAYLIST_TOKEN = "playlist_token"
        const val EXTRA_PLAYLIST_INDEX = "playlist_index"
        private const val ACTIVITY_STACK_GROUP: String = "player_up_flow"
        private const val ACTIVITY_STACK_MAX_DEPTH: Int = 3
        private const val SEEK_MAX = 10_000
        private const val AUTO_HIDE_MS = 4_000L
        private const val EDGE_TAP_THRESHOLD = 0.28f
        private const val TAP_SEEK_ACTIVE_MS = 1_200L
        private const val SMART_SEEK_WINDOW_MS = 900L
        private const val HOLD_SPEED = 2.0f
        private const val HOLD_REWIND_TICK_MS = 260L
        private const val HOLD_REWIND_STEP_MS = 520L
        private const val BACK_DOUBLE_PRESS_WINDOW_MS = 1_500L
        private const val SEEK_HINT_HIDE_DELAY_MS = 900L
        private const val KEY_SCRUB_END_DELAY_MS = 800L
        private const val DANMAKU_DEFAULT_SEGMENT_MS = 6 * 60 * 1000
        private const val DANMAKU_PREFETCH_SEGMENTS = 2
        private const val DANMAKU_PREFETCH_INTERVAL_MS = 1_000L
        private const val DANMAKU_CACHE_SEGMENTS = 20
    }
}
