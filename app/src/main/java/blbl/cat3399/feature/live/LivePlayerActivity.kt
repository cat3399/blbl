@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.live

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.ui.AspectRatioFrameLayout
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayerCustomShortcutAction
import blbl.cat3399.core.prefs.PlayerCustomShortcutsStore
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DoubleBackToExitHandler
import blbl.cat3399.core.ui.FocusReturn
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupAction
import blbl.cat3399.core.ui.popup.PopupActionRole
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.databinding.DialogLiveChatBinding
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.PlayerBufferingOverlayController
import blbl.cat3399.feature.player.PlayerCustomShortcutInputPolicy
import blbl.cat3399.feature.player.PlayerDebugMetrics
import blbl.cat3399.feature.player.PlayerOsdSizing
import blbl.cat3399.feature.player.PlayerSettingsAdapter
import blbl.cat3399.feature.player.PlayerTouchController
import blbl.cat3399.feature.player.PlayerTouchGestureHost
import blbl.cat3399.feature.player.PlayerUpQuickCardController
import blbl.cat3399.feature.player.PlayerUiMode
import blbl.cat3399.feature.player.areaText
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.ExoPlayerEngine
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.player.engine.IjkPlayerEngine
import blbl.cat3399.feature.player.engine.LiveHlsDebugInfo
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import blbl.cat3399.feature.player.engine.PlaybackSource
import blbl.cat3399.feature.player.requirePlayerTouchOverlayBinding
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private object LivePlayerSettingKeys {
    const val QUALITY = "quality"
    const val LINE = "line"
    const val HIGH_BITRATE = "high_bitrate"
    const val AUDIO_BALANCE = "audio_balance"
    const val PLAYER_ENGINE = "player_engine"
    const val DEBUG_INFO = "debug_info"
}

class LivePlayerActivity : BaseActivity() {
    override fun shouldRecreateOnUiScaleChange(): Boolean = true

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var upQuickCard: PlayerUpQuickCardController

    private var player: BlblPlayerEngine? = null
    private var ijkRenderView: View? = null
    private var ijkTextureSurface: Surface? = null
    private val settingsPanelReturnFocus = FocusReturn()
    private var autoHideJob: Job? = null
    private var seekHintJob: Job? = null
    private var touchController: PlayerTouchController? = null
    private val shortcutPrevDanmakuOpacityByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuTextSizeByKey = HashMap<Int, Float>()
    private val shortcutPrevDanmakuSpeedLevelByKey = HashMap<Int, Int>()
    private val shortcutPrevDanmakuAreaByKey = HashMap<Int, Float>()
    private var debugJob: Job? = null
    private var autoFailoverJob: Job? = null
    private var liveEntryReportedRoomId: Long = 0L
    private var finishOnBackKeyUp: Boolean = false
    private var controlsVisible: Boolean = false
    private var lastInteractionAtMs: Long = 0L
    private var autoFailoverWindowStartAtMs: Long = 0L
    private var autoFailoverSwitchCount: Int = 0
    private var autoFailoverLastSwitchAtMs: Long = 0L
    private var autoFailoverInFlight: Boolean = false
    private var behindLiveWindowWindowStartAtMs: Long = 0L
    private var behindLiveWindowRecoverCount: Int = 0
    private var behindLiveWindowLastRecoverAtMs: Long = 0L
    private var exitRequested: Boolean = false
    private val bufferingOverlayController: PlayerBufferingOverlayController by lazy {
        PlayerBufferingOverlayController(
            context = this,
            bindingProvider = { if (::binding.isInitialized) binding else null },
            scope = lifecycleScope,
            playbackStateProvider = { player?.playbackState },
        )
    }

    private val doubleBackToExit by lazy {
        DoubleBackToExitHandler(context = this, windowMs = BACK_DOUBLE_PRESS_WINDOW_MS) {
            if (controlsVisible) setControlsVisible(false)
        }
    }

    private var roomId: Long = 0L
    private var realRoomId: Long = 0L
    private var roomUid: Long = 0L
    private var roomTitle: String = ""
    private var roomUname: String = ""
    private var roomFace: String? = null

    private var session: LiveSession = LiveSession()
    private val debug = PlayerDebugMetrics()
    @Volatile private var liveHlsDebugInfo: LiveHlsDebugInfo? = null

    private var lastPlay: BiliApi.LivePlayUrl? = null
    private var lastLiveStatus: Int = 0
    private var transientPlaybackResumeRequested: Boolean? = null

    private val chatItems = ArrayDeque<LiveChatAdapter.Item>()
    private val chatMax = 200

    private var messageClient: LiveMessageClient? = null
    private var liveDanmakuBaseUptimeMs: Long = 0L
    private var liveDanmakuLastAppendMs: Int = Int.MIN_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerOsdSizing.applyTheme(this)
        val prefs = BiliClient.prefs
        val playerInflater = PlayerOsdSizing.cloneInflater(this, layoutInflater)
        val root =
            playerInflater.inflate(
                if (prefs.playerRenderViewType == AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW) blbl.cat3399.R.layout.activity_player_texture else blbl.cat3399.R.layout.activity_player,
                null,
            )
        binding = ActivityPlayerBinding.bind(root)
        upQuickCard =
            PlayerUpQuickCardController(
                activity = this,
                binding = binding,
                isCardVisible = { controlsVisible },
                keepControlsVisible = { setControlsVisible(true) },
                beforeOpenUpDetail = { prepareTransientPlaybackExit() },
            )
        setContentView(binding.root)
        Immersive.apply(this, prefs.fullscreenEnabled)
        PlayerUiMode.applyLive(this, binding)
        resetBufferingOverlayState()

        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0L)
        roomTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        roomUname = intent.getStringExtra(EXTRA_UNAME).orEmpty()
        if (roomId <= 0L) {
            AppToast.show(this, "缺少 room_id")
            finish()
            return
        }

        val sessionOverrideJson =
            intent.getStringExtra(EXTRA_ENGINE_SWITCH_SESSION_JSON)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        session =
            LiveSession(
                engineKind = PlayerEngineKind.fromPrefValue(prefs.playerEngineKind),
                highBitrateEnabled = prefs.liveHighBitrateEnabled,
                danmaku = DanmakuSessionSettings(
                    enabled = prefs.danmakuEnabled,
                    opacity = prefs.danmakuOpacity,
                    textSizeSp = prefs.danmakuTextSizeSp,
                    fontWeight = DanmakuFontWeight.fromPrefValue(prefs.danmakuFontWeight),
                    strokeWidthPx = prefs.danmakuStrokeWidthPx,
                    speedLevel = prefs.danmakuSpeed,
                    area = prefs.danmakuArea,
                    laneDensity = DanmakuLaneDensity.fromPrefValue(prefs.danmakuLaneDensity),
                ),
                debugEnabled = prefs.playerDebugEnabled,
            )
        if (sessionOverrideJson != null) {
            session = session.restoreFromEngineSwitchJsonString(sessionOverrideJson)
        }

        // Live: no seek bar.
        binding.seekProgress.visibility = View.GONE
        binding.tvTime.visibility = View.GONE
        binding.btnSubtitle.visibility = View.GONE
        binding.tvSeekHint.visibility = View.GONE
        binding.btnPrev.visibility = View.GONE
        binding.btnNext.visibility = View.GONE
        binding.tvOnline.visibility = View.GONE
        binding.llTitleMeta.visibility = View.GONE
        binding.btnUp.visibility = View.GONE
        binding.btnLike.visibility = View.GONE
        binding.btnCoin.visibility = View.GONE
        binding.btnFav.visibility = View.GONE
        binding.btnListPanel.visibility = View.GONE
        binding.btnComments.visibility = View.VISIBLE

        setupUpQuickCard()
        binding.btnBack.setOnClickListener { finish() }

        val desiredEngineKind = session.engineKind
        val engineKind =
            if (desiredEngineKind == PlayerEngineKind.IjkPlayer && !IjkPlayerPlugin.isInstalled(this)) {
                val status = IjkPlayerPlugin.status(this)
                val text =
                    if (status == IjkPlayerPlugin.InstallStatus.NeedsUpdate) {
                        "IjkPlayer 插件需要更新，已临时使用 ExoPlayer"
                    } else {
                        "IjkPlayer 插件未安装，已临时使用 ExoPlayer"
                    }
                AppToast.showLong(this, text)
                IjkPlayerPluginUi.ensureInstalled(this) {
                    if (!isFinishing && !isDestroyed) recreate()
                }
                PlayerEngineKind.ExoPlayer
            } else {
                desiredEngineKind
            }
        if (session.engineKind != engineKind) {
            session = session.copy(engineKind = engineKind)
        }
        val engine: BlblPlayerEngine =
            when (engineKind) {
                PlayerEngineKind.IjkPlayer -> {
                    IjkPlayerEngine(context = this)
                }
                PlayerEngineKind.ExoPlayer ->
                    ExoPlayerEngine(
                        context = this,
                        onTransferHost = { _, host ->
                            debug.videoTransferHost = host
                        },
                        onBytesTransferred = { _, bytes ->
                            recordBufferingTransferBytes(bytes)
                        },
                        onLiveHlsDebugInfo = { info ->
                            liveHlsDebugInfo = info
                        },
                        audioBalanceLevel = AudioBalanceLevel.fromPrefValue(prefs.playerAudioBalanceLevel),
                    )
            }
        player = engine
        applyRenderForEngine(engine, prefs)
        (engine as? ExoPlayerEngine)?.exoPlayer?.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    debug.videoDecoderName = decoderName
                }

                override fun onVideoInputFormatChanged(eventTime: EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                    debug.videoInputWidth = format.width.takeIf { it > 0 }
                    debug.videoInputHeight = format.height.takeIf { it > 0 }
                    debug.videoInputFps = format.frameRate.takeIf { it > 0f }
                }

                override fun onDroppedVideoFrames(eventTime: EventTime, droppedFrames: Int, elapsedMs: Long) {
                    debug.droppedFramesTotal += droppedFrames.toLong().coerceAtLeast(0L)
                }

                override fun onVideoFrameProcessingOffset(eventTime: EventTime, totalProcessingOffsetUs: Long, frameCount: Int) {
                    val now = eventTime.realtimeMs
                    val last = debug.renderFpsLastAtMs
                    debug.renderFpsLastAtMs = now
                    if (last == null) return
                    val deltaMs = now - last
                    if (deltaMs <= 0L || deltaMs > 60_000L) return
                    val frames = frameCount.coerceAtLeast(0)
                    if (frames == 0) return
                    debug.renderFps = (frames * 1000f) / deltaMs.toFloat()
                }
            },
        )
        liveDanmakuBaseUptimeMs = SystemClock.elapsedRealtime()
        liveDanmakuLastAppendMs = Int.MIN_VALUE
        binding.danmakuView.setPositionProvider { liveDanmakuPositionMs() }
        binding.danmakuView.setIsPlayingProvider { player?.isPlaying == true }
        binding.danmakuView.setPlaybackSpeedProvider { player?.playbackSpeed ?: 1.0f }
        binding.danmakuView.setConfigProvider { session.danmaku.toConfig() }

        engine.addListener(
            object : BlblPlayerEngine.Listener {
                override fun onPlayerError(error: Throwable) {
                    if (shouldSuppressPlayerError(error)) {
                        resetBufferingOverlayState()
                        return
                    }
                    AppLog.e("LivePlayer", "onPlayerError", error)
                    val playbackException = error as? PlaybackException
                    if (playbackException != null && tryRecoverBehindLiveWindow(playbackException)) {
                        return
                    }
                    if (tryAutoFailoverOnError(error)) {
                        return
                    }
                    resetBufferingOverlayState()
                    if (playbackException != null) {
                        AppToast.show(this@LivePlayerActivity, "播放失败：${playbackException.errorCodeName}")
                        return
                    }
                    AppToast.showLong(this@LivePlayerActivity, "播放失败：${error.message ?: "未知错误"}")
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseIcon(isPlaying)
                    noteUserInteraction()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_BUFFERING && debug.lastPlaybackState != Player.STATE_BUFFERING && engine.playWhenReady) {
                        debug.rebufferCount++
                    }
                    when (playbackState) {
                        Player.STATE_BUFFERING ->
                            showBufferingOverlay(
                                resetSpeedSample = debug.lastPlaybackState != Player.STATE_BUFFERING,
                            )
                        Player.STATE_READY,
                        Player.STATE_IDLE,
                        Player.STATE_ENDED -> resetBufferingOverlayState()
                    }
                    debug.lastPlaybackState = playbackState
                }

                override fun onVideoSizeChanged(width: Int, height: Int) {
                    if (engine.kind != PlayerEngineKind.IjkPlayer) return
                    if (width <= 0 || height <= 0) return
                    debug.videoInputWidth = width
                    debug.videoInputHeight = height
                    binding.ijkAspect.setAspectRatio(width.toFloat() / height.toFloat())
                }
            },
        )

        binding.btnPlayPause.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else p.play()
            setControlsVisible(true)
        }
        binding.btnAdvanced.setOnClickListener {
            val willShow = binding.settingsPanel.visibility != View.VISIBLE
            setControlsVisible(true)
            if (willShow) {
                settingsPanelReturnFocus.capture(currentFocus)
                binding.settingsPanel.visibility = View.VISIBLE
                onTouchOverlayStateChanged()
                focusSettingsPanel()
            } else {
                // Restore focus before hiding the panel to avoid a visible focus "double jump".
                settingsPanelReturnFocus.restoreAndClear(fallback = binding.btnAdvanced, postOnFail = false)
                binding.settingsPanel.visibility = View.GONE
                onTouchOverlayStateChanged()
            }
        }
        binding.btnDanmaku.setOnClickListener {
            session = session.copy(danmaku = session.danmaku.copy(enabled = !session.danmaku.enabled))
            binding.danmakuView.invalidate()
            updateDanmakuButton()
            setControlsVisible(true)
        }
        binding.btnComments.setOnClickListener {
            showChatDialog()
            setControlsVisible(true)
        }

        binding.playerView.setOnClickListener {
            if (binding.settingsPanel.visibility == View.VISIBLE) {
                setControlsVisible(true)
                settingsPanelReturnFocus.restoreAndClear(fallback = binding.btnAdvanced, postOnFail = false)
                binding.settingsPanel.visibility = View.GONE
                onTouchOverlayStateChanged()
                return@setOnClickListener
            }
            toggleControls()
        }
        initTouchGestures()

        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()

        setupSettingsPanel()
        // Live default: keep OSD hidden. User brings it up via D-pad / OK / Menu keys.
        binding.settingsPanel.visibility = View.GONE
        setControlsVisible(false)
        updateDanmakuButton()

        lifecycleScope.launch { loadAndPlay(initial = true) }
    }

    private fun prepareTransientPlaybackExit() {
        val engine = player ?: return
        val shouldResume = engine.isPlaying || engine.playWhenReady
        transientPlaybackResumeRequested = shouldResume
        engine.pause()
    }

    private fun consumeTransientPlaybackResumeIfNeeded() {
        val shouldResume = transientPlaybackResumeRequested ?: return
        transientPlaybackResumeRequested = null
        if (exitRequested || isFinishing || isDestroyed || isChangingConfigurations) return
        val engine = player ?: return
        if (shouldResume) {
            engine.playWhenReady = true
        }
    }

    override fun onResume() {
        super.onResume()
        PlayerOsdSizing.applyTheme(this)
        PlayerUiMode.applyLive(this, binding)
        initTouchGestures()
        consumeTransientPlaybackResumeIfNeeded()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
    }

    private fun setupUpQuickCard() {
        binding.btnUpQuickProfile.nextFocusDownId = R.id.btn_play_pause
        binding.btnUpQuickFollow.nextFocusDownId = R.id.btn_play_pause
        listOf(
            binding.btnPlayPause,
            binding.btnDanmaku,
            binding.btnComments,
            binding.btnAdvanced,
        ).forEach { it.nextFocusUpId = R.id.btn_up_quick_profile }
        upQuickCard.setupActions()
        upQuickCard.setOwner(mid = roomUid, name = roomUname, avatar = roomFace)
        upQuickCard.refreshFollowStateIfNeeded(force = false)
    }

    private fun updateLiveUpQuickCardOwner(info: BiliApi.LiveRoomInfo) {
        roomUid = info.uid.takeIf { it > 0L } ?: roomUid
        roomUname =
            info.uname
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: roomUname
        roomFace =
            info.faceUrl
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: roomFace
        upQuickCard.setOwner(mid = roomUid, name = roomUname, avatar = roomFace)
        upQuickCard.refreshFollowStateIfNeeded(force = false)
    }

    override fun onStop() {
        touchController?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        val t0 = SystemClock.elapsedRealtime()
        AppLog.i("LivePlayer", "activity:onDestroy:start")
        transientPlaybackResumeRequested = null
        messageClient?.close()
        messageClient = null
        releaseTouchGestures()
        seekHintJob?.cancel()
        debugJob?.cancel()
        autoFailoverJob?.cancel()
        autoFailoverInFlight = false
        autoHideJob?.cancel()
        upQuickCard.release()
        resetBufferingOverlayState()
        binding.playerView.player = null
        player?.setVideoSurface(null)
        ijkTextureSurface?.release()
        ijkTextureSurface = null
        val releaseStart = SystemClock.elapsedRealtime()
        player?.release()
        val releaseCostMs = SystemClock.elapsedRealtime() - releaseStart
        AppLog.i("LivePlayer", "player:release:done cost=${releaseCostMs}ms")
        player = null
        val totalCostMs = SystemClock.elapsedRealtime() - t0
        AppLog.i("LivePlayer", "activity:onDestroy:beforeSuper cost=${totalCostMs}ms")
        super.onDestroy()
    }

    override fun finish() {
        exitRequested = true
        transientPlaybackResumeRequested = null
        autoFailoverJob?.cancel()
        autoFailoverInFlight = false
        runCatching { player?.pause() }
        if (::binding.isInitialized) {
            binding.playerView.player = null
        }
        super.finish()
        applyCloseTransitionNoAnim()
    }

    private fun applyRenderForEngine(engine: BlblPlayerEngine, prefs: AppPrefs) {
        // Reset any previous IJK render state.
        binding.ijkAspect.visibility = View.GONE
        binding.ijkContainer.removeAllViews()
        ijkRenderView = null
        ijkTextureSurface?.release()
        ijkTextureSurface = null
        engine.setVideoSurface(null)

        val exo = (engine as? ExoPlayerEngine)?.exoPlayer
        if (engine.kind == PlayerEngineKind.ExoPlayer) {
            binding.playerView.player = exo
            return
        }

        // IJK mode: render via our own Surface/Texture.
        binding.playerView.player = null
        binding.ijkAspect.visibility = View.VISIBLE
        binding.ijkAspect.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        binding.ijkAspect.setAspectRatio(0f)

        val renderView: View =
            if (prefs.playerRenderViewType == AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW) {
                TextureView(this).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    isClickable = false
                    isLongClickable = false
                }
            } else {
                SurfaceView(this).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    isClickable = false
                    isLongClickable = false
                }
            }

        binding.ijkContainer.addView(
            renderView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        ijkRenderView = renderView

        when (renderView) {
            is SurfaceView -> {
                val callback =
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            engine.setVideoSurface(holder.surface)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            engine.setVideoSurface(holder.surface)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            engine.setVideoSurface(null)
                        }
                    }
                renderView.holder.addCallback(callback)
            }

            is TextureView -> {
                fun setSurface(surfaceTexture: SurfaceTexture?) {
                    ijkTextureSurface?.release()
                    ijkTextureSurface = null
                    if (surfaceTexture == null) {
                        engine.setVideoSurface(null)
                        return
                    }
                    val surface = Surface(surfaceTexture)
                    ijkTextureSurface = surface
                    engine.setVideoSurface(surface)
                }

                renderView.surfaceTextureListener =
                    object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            setSurface(surface)
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            setSurface(null)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                if (renderView.isAvailable) {
                    setSurface(renderView.surfaceTexture)
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // LivePlayerActivity uses global exit shortcuts (BACK/ESC/B).
        // When a modal popup is showing, bypass these shortcuts and let the popup consume keys first.
        val popupHost = PopupHost.peek(this)
        if (popupHost != null) {
            if (popupHost.consumeBackLikeKeyEventIfNeeded(event)) {
                finishOnBackKeyUp = false
                return true
            }
            if (popupHost.hasModalView()) {
                return super.dispatchKeyEvent(event)
            }
        }

        if (event.action == KeyEvent.ACTION_UP) {
            if (isExitKey(keyCode)) {
                if (finishOnBackKeyUp) {
                    finishOnBackKeyUp = false
                    finish()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (isInteractionKey(keyCode)) noteUserInteraction()

        if (dispatchLiveCustomShortcutIfNeeded(event)) return true

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
                // handled below (shared exit logic)
            }
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BUTTON_B,
            -> {
                // handled below (shared exit logic)
            }

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                setControlsVisible(true)
                if (!hasControlsFocus()) {
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

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                if (!controlsVisible) {
                    setControlsVisible(true)
                    // Align with UP/DOWN/CENTER behavior: show OSD and move focus to controls in one press.
                    focusFirstControl()
                    return true
                }
                if (!hasControlsFocus()) {
                    focusFirstControl()
                    return true
                }
            }
        }
        if (isExitKey(keyCode)) {
            finishOnBackKeyUp = false
            if (binding.settingsPanel.visibility == View.VISIBLE) {
                setControlsVisible(true)
                settingsPanelReturnFocus.restoreAndClear(fallback = binding.btnAdvanced, postOnFail = false)
                binding.settingsPanel.visibility = View.GONE
                onTouchOverlayStateChanged()
                return true
            }
            if (controlsVisible) {
                setControlsVisible(false)
                return true
            }
            finishOnBackKeyUp = doubleBackToExit.shouldExit(enabled = BiliClient.prefs.playerDoubleBackToExit)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dispatchLiveCustomShortcutIfNeeded(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount != 0) return false

        val keyCode = event.keyCode
        if (keyCode <= 0 || keyCode == KeyEvent.KEYCODE_UNKNOWN) return false
        if (PlayerCustomShortcutsStore.isForbiddenKeyCode(keyCode)) return false
        if (
            !PlayerCustomShortcutInputPolicy.canDispatchInLive(
                hasInteractiveOsd = controlsVisible,
                hasSettingsPanel = binding.settingsPanel.visibility == View.VISIBLE,
            )
        ) {
            return false
        }

        val binding = BiliClient.prefs.playerCustomShortcuts.firstOrNull { it.keyCode == keyCode } ?: return false

        when (val action = binding.action) {
            PlayerCustomShortcutAction.ShowOsd -> {
                noteUserInteraction()
                setControlsVisible(true)
                focusFirstControl()
                return true
            }

            PlayerCustomShortcutAction.OpenComments -> {
                noteUserInteraction()
                showChatDialog()
                return true
            }

            PlayerCustomShortcutAction.OpenSettings -> {
                noteUserInteraction()
                setControlsVisible(true)
                if (this.binding.settingsPanel.visibility != View.VISIBLE) {
                    settingsPanelReturnFocus.capture(currentFocus)
                    this.binding.settingsPanel.visibility = View.VISIBLE
                    onTouchOverlayStateChanged()
                }
                focusSettingsPanel()
                return true
            }

            PlayerCustomShortcutAction.TogglePlayPause -> {
                noteUserInteraction()
                val engine = player ?: return true
                val willPlay = !engine.isPlaying
                if (willPlay) engine.play() else engine.pause()
                showSeekHint(if (willPlay) "播放" else "暂停")
                return true
            }

            PlayerCustomShortcutAction.ToggleDanmaku -> {
                noteUserInteraction()
                session = session.copy(danmaku = session.danmaku.copy(enabled = !session.danmaku.enabled))
                this.binding.danmakuView.invalidate()
                updateDanmakuButton()
                val state = if (session.danmaku.enabled) "开" else "关"
                showShortcutHint("弹幕：$state")
                return true
            }

            is PlayerCustomShortcutAction.SetDanmakuOpacity -> {
                noteUserInteraction()
                val target = action.opacity.takeIf { it.isFinite() }?.coerceIn(0.05f, 1.0f) ?: session.danmaku.opacity
                val current = session.danmaku.opacity
                val next =
                    if (sameFloat(current, target)) {
                        shortcutPrevDanmakuOpacityByKey[keyCode] ?: target
                    } else {
                        shortcutPrevDanmakuOpacityByKey[keyCode] = current
                        target
                    }
                session = session.copy(danmaku = session.danmaku.copy(opacity = next))
                this.binding.danmakuView.invalidate()
                showShortcutHint("弹幕透明度：${String.format(Locale.US, "%.2f", next)}")
                return true
            }

            is PlayerCustomShortcutAction.SetDanmakuTextSize -> {
                noteUserInteraction()
                val target = action.textSizeSp.takeIf { it.isFinite() }?.coerceIn(10f, 60f) ?: session.danmaku.textSizeSp
                val current = session.danmaku.textSizeSp
                val next =
                    if (sameFloat(current, target)) {
                        shortcutPrevDanmakuTextSizeByKey[keyCode] ?: target
                    } else {
                        shortcutPrevDanmakuTextSizeByKey[keyCode] = current
                        target
                    }
                session = session.copy(danmaku = session.danmaku.copy(textSizeSp = next))
                this.binding.danmakuView.invalidate()
                showShortcutHint("弹幕大小：${next.toInt()}")
                return true
            }

            is PlayerCustomShortcutAction.SetDanmakuSpeed -> {
                noteUserInteraction()
                val target = action.speedLevel.coerceIn(1, 10)
                val current = session.danmaku.speedLevel
                val next =
                    if (current == target) {
                        shortcutPrevDanmakuSpeedLevelByKey[keyCode] ?: target
                    } else {
                        shortcutPrevDanmakuSpeedLevelByKey[keyCode] = current
                        target
                    }
                session = session.copy(danmaku = session.danmaku.copy(speedLevel = next))
                this.binding.danmakuView.invalidate()
                showShortcutHint("弹幕速度：$next")
                return true
            }

            is PlayerCustomShortcutAction.SetDanmakuArea -> {
                noteUserInteraction()
                val target = action.area.takeIf { it.isFinite() }?.let(AppPrefs::normalizeLegacyDanmakuAreaCompat) ?: session.danmaku.area
                val current = session.danmaku.area
                val next =
                    if (sameFloat(current, target)) {
                        shortcutPrevDanmakuAreaByKey[keyCode] ?: target
                    } else {
                        shortcutPrevDanmakuAreaByKey[keyCode] = current
                        target
                    }
                session = session.copy(danmaku = session.danmaku.copy(area = next))
                this.binding.danmakuView.invalidate()
                showShortcutHint("弹幕区域：${areaText(next)}")
                return true
            }

            PlayerCustomShortcutAction.ToggleDebugOverlay -> {
                noteUserInteraction()
                session = session.copy(debugEnabled = !session.debugEnabled)
                updateDebugOverlay()
                refreshSettings()
                val state = if (session.debugEnabled) "开" else "关"
                showShortcutHint("调试信息：$state")
                return true
            }

            else -> return false
        }
    }

    private fun showShortcutHint(text: String) {
        if (!controlsVisible) setControlsVisible(true)
        showSeekHint(text, hold = false)
    }

    private fun showSeekHint(text: String, hold: Boolean = false) {
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

    private fun sameFloat(a: Float, b: Float): Boolean {
        if (!a.isFinite() || !b.isFinite()) return false
        return kotlin.math.abs(a - b) < 0.0001f
    }

    private fun setupSettingsPanel() {
        val settingsAdapter =
            PlayerSettingsAdapter { item ->
                when (item.key) {
                    LivePlayerSettingKeys.QUALITY -> showQualityDialog()
                    LivePlayerSettingKeys.LINE -> showLineDialog()
                    LivePlayerSettingKeys.HIGH_BITRATE -> toggleLiveHighBitrate()
                    LivePlayerSettingKeys.AUDIO_BALANCE -> showAudioBalanceDialog()
                    LivePlayerSettingKeys.PLAYER_ENGINE -> showPlayerEngineDialog()
                    LivePlayerSettingKeys.DEBUG_INFO -> {
                        session = session.copy(debugEnabled = !session.debugEnabled)
                        updateDebugOverlay()
                        refreshSettings()
                    }

                    else -> AppToast.show(this, "暂未实现：${item.title}")
                }
            }
        binding.recyclerSettings.adapter = settingsAdapter
        binding.recyclerSettings.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerSettings.itemAnimator = null
        binding.recyclerSettings.addOnChildAttachStateChangeListener(
            object : androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val holder = binding.recyclerSettings.findContainingViewHolder(v) ?: return@setOnKeyListener false
                        val pos =
                            holder.bindingAdapterPosition.takeIf { it != androidx.recyclerview.widget.RecyclerView.NO_POSITION }
                                ?: return@setOnKeyListener false

                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (pos == 0 && !binding.recyclerSettings.canScrollVertically(-1)) return@setOnKeyListener true
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val last = (binding.recyclerSettings.adapter?.itemCount ?: 0) - 1
                                if (pos == last && !binding.recyclerSettings.canScrollVertically(1)) return@setOnKeyListener true
                                false
                            }

                            else -> false
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.setOnKeyListener(null)
                }
            },
        )
        refreshSettings()
        updateDebugOverlay()
    }

    private fun showAudioBalanceDialog() {
        val prefs = BiliClient.prefs
        val options = AudioBalanceLevel.ordered
        val labels = options.map { it.label }
        val current = AudioBalanceLevel.fromPrefValue(prefs.playerAudioBalanceLevel)
        val checked = options.indexOf(current).takeIf { it >= 0 } ?: 0

        AppPopup.singleChoice(
            context = this,
            title = "音频平衡",
            items = labels,
            checkedIndex = checked,
        ) { which, _ ->
            val picked = options.getOrNull(which) ?: AudioBalanceLevel.Off
            prefs.playerAudioBalanceLevel = picked.prefValue
            val engine = player
            if (engine is ExoPlayerEngine) {
                engine.setAudioBalanceLevel(picked)
                AppToast.show(this, "音频平衡：${picked.label}")
            } else {
                AppToast.show(this, "当前播放器内核不支持音频平衡")
            }
            refreshSettings()
        }
    }

    private fun refreshSettings() {
        val prefs = BiliClient.prefs
        val p = lastPlay
        val qn = session.targetQn.takeIf { it > 0 } ?: p?.currentQn ?: LIVE_QN_ORIGINAL
        val qLabel = liveQnLabel(qn, p)
        val lineLabel =
            p?.lines
                ?.getOrNull((session.lineOrder - 1).coerceAtLeast(0))
                ?.let { "线路 ${it.order}" }
                ?: "自动"
        val engineLabel =
            when (player?.kind ?: session.engineKind) {
                PlayerEngineKind.IjkPlayer -> "IjkPlayer"
                PlayerEngineKind.ExoPlayer -> "ExoPlayer"
            }
        val balanceLabel = AudioBalanceLevel.fromPrefValue(prefs.playerAudioBalanceLevel).label
        val list =
            listOf(
                PlayerSettingsAdapter.SettingItem(key = LivePlayerSettingKeys.QUALITY, title = "清晰度", subtitle = qLabel),
                PlayerSettingsAdapter.SettingItem(key = LivePlayerSettingKeys.LINE, title = "线路选择", subtitle = lineLabel),
                PlayerSettingsAdapter.SettingItem(
                    key = LivePlayerSettingKeys.HIGH_BITRATE,
                    title = "提高直播码率",
                    subtitle = if (session.highBitrateEnabled) "开" else "关",
                ),
                PlayerSettingsAdapter.SettingItem(key = LivePlayerSettingKeys.AUDIO_BALANCE, title = "音频平衡", subtitle = balanceLabel),
                PlayerSettingsAdapter.SettingItem(key = LivePlayerSettingKeys.PLAYER_ENGINE, title = "播放器内核", subtitle = engineLabel),
                PlayerSettingsAdapter.SettingItem(key = LivePlayerSettingKeys.DEBUG_INFO, title = "调试信息", subtitle = if (session.debugEnabled) "开" else "关"),
            )
        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.submit(list)
    }

    private fun toggleLiveHighBitrate() {
        val enabled = !session.highBitrateEnabled
        session = session.copy(highBitrateEnabled = enabled, lineOrder = 1, originFailCount = 0)
        resetPlaybackRecoveryState()
        refreshSettings()
        AppToast.show(this, "提高直播码率：${if (enabled) "开" else "关"}")
        lifecycleScope.launch { loadAndPlay(initial = false) }
    }

    private fun resetPlaybackRecoveryState() {
        autoFailoverJob?.cancel()
        autoFailoverJob = null
        autoFailoverWindowStartAtMs = 0L
        autoFailoverSwitchCount = 0
        autoFailoverLastSwitchAtMs = 0L
        autoFailoverInFlight = false
        behindLiveWindowWindowStartAtMs = 0L
        behindLiveWindowRecoverCount = 0
        behindLiveWindowLastRecoverAtMs = 0L
    }

    private fun showPlayerEngineDialog() {
        val currentKind = player?.kind ?: session.engineKind
        val items = listOf("ExoPlayer", "IjkPlayer")
        val checked = if (currentKind == PlayerEngineKind.IjkPlayer) 1 else 0
        AppPopup.singleChoice(
            context = this,
            title = "播放器内核",
            items = items,
            checkedIndex = checked,
        ) { which, _ ->
            val picked = if (which == 1) PlayerEngineKind.IjkPlayer else PlayerEngineKind.ExoPlayer
            if (picked == currentKind) return@singleChoice
            fun doSwitch() {
                val sessionJson = session.copy(engineKind = picked).toEngineSwitchJsonString()
                val restart =
                    Intent(this, LivePlayerActivity::class.java).apply {
                        putExtra(EXTRA_ROOM_ID, roomId)
                        putExtra(EXTRA_TITLE, roomTitle)
                        putExtra(EXTRA_UNAME, roomUname)
                        putExtra(EXTRA_ENGINE_SWITCH_SESSION_JSON, sessionJson)
                    }
                startActivity(restart)
                finish()
            }

            if (picked == PlayerEngineKind.IjkPlayer) {
                IjkPlayerPluginUi.ensureInstalled(this) {
                    doSwitch()
                }
            } else {
                doSwitch()
            }
        }
    }

    private fun toggleControls() {
        if (isTouchLocked()) return
        val willShow = !controlsVisible
        if (!willShow) binding.settingsPanel.visibility = View.GONE
        setControlsVisible(willShow)
    }

    private fun setControlsVisible(visible: Boolean) {
        val hadControlsFocus = hasControlsFocus()
        controlsVisible = visible && !isTouchLocked()
        val show = controlsVisible || binding.settingsPanel.visibility == View.VISIBLE
        binding.topBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (show) View.VISIBLE else View.GONE
        upQuickCard.updateUi()
        if (!show && hadControlsFocus) binding.root.requestFocus()

        onTouchOverlayStateChanged()
        restartAutoHideTimer()
    }

    private fun restartAutoHideTimer() {
        autoHideJob?.cancel()
        if (isTouchLocked()) return
        if (!controlsVisible) return
        if (binding.settingsPanel.visibility == View.VISIBLE) return
        val token = lastInteractionAtMs
        autoHideJob =
            lifecycleScope.launch {
                delay(AUTO_HIDE_MS)
                if (token != lastInteractionAtMs) return@launch
                if (binding.settingsPanel.visibility == View.VISIBLE) return@launch
                if (controlsVisible) setControlsVisible(false)
            }
    }

    private fun focusFirstControl(): Boolean {
        return requestFocusControlNow(binding.btnPlayPause) || requestFocusControlNow(binding.btnBack)
    }

    private fun requestFocusControlNow(view: View?): Boolean {
        val target = view ?: return false
        if (!target.isShown) return false
        if (!target.isEnabled) return false
        if (!target.isFocusable) return false
        return target.requestFocus()
    }

    private fun focusAdvancedControl(): Boolean {
        return binding.btnAdvanced.requestFocus()
    }

    private fun focusSettingsPanel() {
        binding.recyclerSettings.post {
            val child = binding.recyclerSettings.getChildAt(0)
            if (child != null) {
                child.requestFocus()
                return@post
            }

            binding.recyclerSettings.scrollToPosition(0)
            binding.recyclerSettings.post {
                val first = binding.recyclerSettings.getChildAt(0)
                (first ?: binding.recyclerSettings).requestFocus()
            }
        }
    }

    private fun hasControlsFocus(): Boolean {
        if (binding.settingsPanel.visibility == View.VISIBLE) return true
        return binding.topBar.hasFocus() ||
            binding.cardUpQuick.hasFocus() ||
            binding.bottomBar.hasFocus()
    }

    private fun initTouchGestures() {
        if (!BiliClient.prefs.playerTouchGesturesEnabled) {
            releaseTouchGestures()
            return
        }
        if (touchController != null) return
        touchController =
            PlayerTouchController(createLiveTouchHost(), requirePlayerTouchOverlayBinding(binding)).also { controller ->
                controller.install()
            }
    }

    private fun releaseTouchGestures() {
        touchController?.release()
        touchController = null
    }

    private fun isTouchLocked(): Boolean = touchController?.isTouchLocked() == true

    private fun onTouchOverlayStateChanged() {
        touchController?.onControlsStateChanged()
    }

    private fun createLiveTouchHost(): PlayerTouchGestureHost {
        return object : PlayerTouchGestureHost {
            override val activity: android.app.Activity
                get() = this@LivePlayerActivity
            override val binding: ActivityPlayerBinding
                get() = this@LivePlayerActivity.binding
            override val lifecycleScope: LifecycleCoroutineScope
                get() = this@LivePlayerActivity.lifecycleScope
            override val player: BlblPlayerEngine?
                get() = this@LivePlayerActivity.player
            override val controlsVisibleForTouch: Boolean
                get() = controlsVisible
            override val isSidePanelVisible: Boolean
                get() = this@LivePlayerActivity.binding.settingsPanel.visibility == View.VISIBLE
            override val isBottomCardPanelVisible: Boolean = false
            override val isCommentImageViewerVisible: Boolean = false

            override fun setControlsVisibleFromTouch(visible: Boolean) {
                setControlsVisible(visible)
            }

            override fun closeSidePanelFromTouch(): Boolean {
                if (this@LivePlayerActivity.binding.settingsPanel.visibility != View.VISIBLE) return false
                setControlsVisible(true)
                settingsPanelReturnFocus.restoreAndClear(fallback = this@LivePlayerActivity.binding.btnAdvanced, postOnFail = false)
                this@LivePlayerActivity.binding.settingsPanel.visibility = View.GONE
                onTouchOverlayStateChanged()
                return true
            }

            override fun togglePlayPauseFromTouch() {
                val engine = player ?: return
                val willPlay = !engine.isPlaying
                if (willPlay) engine.play() else engine.pause()
                showSeekHint(if (willPlay) "播放" else "暂停", hold = false)
            }

            override fun noteUserInteractionFromTouch() {
                noteUserInteraction()
            }

            override fun showTouchHint(text: String, hold: Boolean) {
                showSeekHint(text, hold)
            }

            override fun scheduleHideTouchHint() {
                scheduleHideSeekHint()
            }
        }
    }

    private fun noteUserInteraction() {
        lastInteractionAtMs = SystemClock.uptimeMillis()
        restartAutoHideTimer()
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
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            -> true
            else -> false
        }
    }

    private fun isExitKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE, // some TV remotes map “Exit” to ESC
            KeyEvent.KEYCODE_BUTTON_B, // gamepad B
            -> true
            else -> false
        }
    }

    private fun isPlayerTeardownInProgress(): Boolean {
        return exitRequested || isFinishing || isDestroyed
    }

    private fun shouldSuppressPlayerError(error: Throwable): Boolean {
        if (!isPlayerTeardownInProgress()) return false
        val playbackException = error as? PlaybackException
        val errorLabel = playbackException?.errorCodeName ?: (error.message ?: error::class.java.simpleName)
        AppLog.i(
            "LivePlayer",
            "ignorePlayerError teardown=1 finishing=${if (isFinishing) 1 else 0} destroyed=${if (isDestroyed) 1 else 0} " +
                "exitRequested=${if (exitRequested) 1 else 0} type=$errorLabel",
        )
        return true
    }

    private fun shouldAutoFailoverOnError(error: PlaybackException): Boolean {
        // Limit to errors where switching CDN host (or resetting live edge) is likely to help.
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> true
            else -> false
        }
    }

    private fun tryRecoverBehindLiveWindow(error: PlaybackException): Boolean {
        if (error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return false
        if (isFinishing || isDestroyed) return false
        if (autoFailoverInFlight) return false

        // BehindLiveWindowException is a timeline/window issue (not necessarily a bad url).
        // Prefer self-heal by jumping back to the default live position before we start switching lines.
        val engine = (player as? ExoPlayerEngine) ?: return false
        val exo = engine.exoPlayer

        val nowMs = SystemClock.elapsedRealtime()
        if (behindLiveWindowWindowStartAtMs <= 0L || nowMs - behindLiveWindowWindowStartAtMs > BEHIND_LIVE_WINDOW_RECOVER_WINDOW_MS) {
            behindLiveWindowWindowStartAtMs = nowMs
            behindLiveWindowRecoverCount = 0
        }
        if (nowMs - behindLiveWindowLastRecoverAtMs < BEHIND_LIVE_WINDOW_RECOVER_MIN_INTERVAL_MS) return false
        if (behindLiveWindowRecoverCount >= BEHIND_LIVE_WINDOW_MAX_RECOVERS) return false

        behindLiveWindowRecoverCount += 1
        behindLiveWindowLastRecoverAtMs = nowMs
        val recoverCount = behindLiveWindowRecoverCount
        AppLog.w("LivePlayer", "behindLiveWindow recover #$recoverCount")

        if (recoverCount <= 1) {
            runCatching { exo.seekToDefaultPosition() }
            exo.prepare()
            exo.playWhenReady = true
            return true
        }

        val currentIndex = (session.lineOrder - 1).coerceAtLeast(0)
        val url =
            lastPlay?.lines?.getOrNull(currentIndex)?.url
                ?: exo.currentMediaItem?.localConfiguration?.uri?.toString()
        if (url.isNullOrBlank()) return false

        runCatching { exo.stop() }
        runCatching { engine.setSource(PlaybackSource.Live(url = url)) }
        exo.prepare()
        exo.playWhenReady = true
        return true
    }

    private fun isSignedLiveUrl(url: String): Boolean {
        val u = url.trim()
        // Signed urls always carry query params like expires/sign/trid...
        // Origin urls are normalized to a clean path (no query).
        return u.contains('?')
    }

    private fun tryAutoFailoverOnError(error: Throwable): Boolean {
        if (isFinishing || isDestroyed) return false
        if (autoFailoverInFlight) return false

        val play = lastPlay ?: return false
        val lines = play.lines
        if (lines.size <= 1) return false

        val currentIndex = (session.lineOrder - 1).coerceIn(0, lines.lastIndex)

        val signedStartIndex =
            lines.indexOfFirst { isSignedLiveUrl(it.url) }.let { idx ->
                if (idx < 0) lines.size else idx.coerceIn(0, lines.size)
            }
        val originCount = signedStartIndex
        val signedCount = (lines.size - signedStartIndex).coerceAtLeast(0)
        val hasOrigin = originCount > 0
        val hasSigned = signedCount > 0

        // Decide whether we should auto failover for this error:
        // - For ExoPlayer errors, only on IO/network failures.
        // - For other engines (Ijk), we only auto-switch while trying origin urls.
        val shouldFailover =
            when (error) {
                is PlaybackException -> shouldAutoFailoverOnError(error)
                else ->
                    // Keep it conservative to avoid endless loops on non-network errors.
                    hasSigned && hasOrigin && currentIndex < signedStartIndex
            }
        if (!shouldFailover) return false

        val nowMs = SystemClock.elapsedRealtime()
        if (autoFailoverWindowStartAtMs <= 0L || nowMs - autoFailoverWindowStartAtMs > AUTO_FAILOVER_WINDOW_MS) {
            autoFailoverWindowStartAtMs = nowMs
            autoFailoverSwitchCount = 0
        }
        if (nowMs - autoFailoverLastSwitchAtMs < AUTO_FAILOVER_MIN_INTERVAL_MS) return false

        // Avoid endless loops if the room is not playable (region/permission/not live).
        val maxSwitches = (lines.size - 1).coerceIn(1, AUTO_FAILOVER_MAX_SWITCHES)
        if (autoFailoverSwitchCount >= maxSwitches) return false

        val inSigned = currentIndex >= signedStartIndex
        val preferSigned = session.originFailCount >= ORIGIN_FAIL_BEFORE_SIGNED
        val phaseSigned = inSigned || !hasOrigin || (preferSigned && hasSigned)

        val nextIndex =
            if (!phaseSigned) {
                // Origin phase: count failures; once we hit threshold (or exhaust origin), switch to signed.
                if (originCount <= 1) {
                    if (!hasSigned) return false
                    signedStartIndex
                } else {
                val nextOriginIndex = currentIndex + 1
                val reachEnd = nextOriginIndex >= originCount
                val switchToSigned =
                    hasSigned &&
                        (session.originFailCount + 1 >= ORIGIN_FAIL_BEFORE_SIGNED || reachEnd)
                when {
                    switchToSigned -> signedStartIndex
                    nextOriginIndex in 0 until originCount -> nextOriginIndex
                    else -> 0
                }
                }
            } else {
                // Signed phase: only rotate inside signed urls.
                if (!hasSigned) return false
                if (currentIndex < signedStartIndex) {
                    signedStartIndex
                } else {
                    if (signedCount <= 1) return false
                    val nextSignedIndex = currentIndex + 1
                    if (nextSignedIndex < lines.size) nextSignedIndex else signedStartIndex
                }
            }
        if (nextIndex == currentIndex) return false

        val fromOrder = lines[currentIndex].order
        val toOrder = lines[nextIndex].order
        val errorLabel =
            when (error) {
                is PlaybackException -> error.errorCodeName
                else -> error::class.java.simpleName
            }
        AppLog.w("LivePlayer", "autoFailover error=$errorLabel line=$fromOrder -> $toOrder")

        autoFailoverSwitchCount += 1
        autoFailoverLastSwitchAtMs = nowMs
        autoFailoverInFlight = true
        autoFailoverJob?.cancel()

        // Stop current load attempts to avoid duplicate error callbacks while we are switching.
        runCatching { player?.stop() }

        session =
            if (!phaseSigned) {
                val nextFail = (session.originFailCount + 1).coerceAtLeast(0)
                val switchedToSigned = nextIndex >= signedStartIndex && hasSigned
                session.copy(
                    lineOrder = nextIndex + 1,
                    originFailCount = if (switchedToSigned) ORIGIN_FAIL_BEFORE_SIGNED else nextFail,
                )
            } else {
                session.copy(lineOrder = nextIndex + 1)
            }
        refreshSettings()
        AppToast.show(this, "线路 $fromOrder 失败，尝试线路 $toOrder")

        autoFailoverJob =
            lifecycleScope.launch {
                try {
                    loadAndPlay(initial = false)
                } finally {
                    autoFailoverInFlight = false
                }
            }
        return true
    }

    private suspend fun loadAndPlay(initial: Boolean) {
        val engine = player ?: return
        try {
            val info = BiliApi.liveRoomInfo(roomId)
            realRoomId = info.roomId
            lastLiveStatus = info.liveStatus
            updateLiveUpQuickCardOwner(info)

            val title = info.title.ifBlank { roomTitle }
            binding.tvTitle.text =
                buildString {
                    append(title.ifBlank { "直播间 $realRoomId" })
                    if (roomUname.isNotBlank()) append(" · ").append(roomUname)
                }

            val qn = session.targetQn.takeIf { it > 0 } ?: 150
            val play = BiliApi.livePlayUrl(realRoomId, qn, highBitrateEnabled = session.highBitrateEnabled)
            lastPlay = play
            if (play.lines.isNotEmpty()) {
                // If saved lineOrder becomes out of range (API may return fewer lines), fall back to line 1.
                val safeLineOrder = session.lineOrder.takeIf { it in 1..play.lines.size } ?: 1
                if (safeLineOrder != session.lineOrder) session = session.copy(lineOrder = safeLineOrder)
                if (session.originFailCount >= ORIGIN_FAIL_BEFORE_SIGNED) {
                    val signedStartIndex =
                        play.lines.indexOfFirst { isSignedLiveUrl(it.url) }.let { idx ->
                            if (idx < 0) play.lines.size else idx.coerceIn(0, play.lines.size)
                        }
                    val signedStartOrder = signedStartIndex + 1
                    // Keep current signed selection when possible; otherwise jump to the first signed url.
                    if (signedStartIndex in play.lines.indices && session.lineOrder < signedStartOrder) {
                        session = session.copy(lineOrder = signedStartOrder)
                    }
                }
            }
            refreshSettings()

            val pickedLine =
                play.lines.getOrNull((session.lineOrder - 1).coerceAtLeast(0))
                    ?: play.lines.firstOrNull()
            if (pickedLine == null) error("No playable live url")

            debug.reset()
            debug.cdnHost = runCatching { Uri.parse(pickedLine.url).host?.lowercase(Locale.US) }.getOrNull()
            liveHlsDebugInfo = null
            engine.setSource(PlaybackSource.Live(url = pickedLine.url))
            engine.prepare()
            engine.playWhenReady = true
            reportLiveRoomEntry(realRoomId)

            if (initial) connectDanmaku()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            resetBufferingOverlayState()
            AppLog.e("LivePlayer", "loadAndPlay failed", t)
            val e = t as? BiliApiException
            val msg = e?.let { "B 站返回：${it.apiCode} / ${it.apiMessage}" } ?: (t.message ?: "未知错误")
            AppToast.showLong(this, msg)
        }
    }

    private fun recordBufferingTransferBytes(bytes: Long) {
        bufferingOverlayController.recordTransferBytes(bytes)
    }

    private fun resetBufferingOverlayState() {
        bufferingOverlayController.reset()
    }

    private fun showBufferingOverlay(resetSpeedSample: Boolean = true) {
        bufferingOverlayController.onBufferingStarted(resetSpeedSample = resetSpeedSample)
    }

    private fun connectDanmaku() {
        messageClient?.close()
        messageClient = null
        val rid = realRoomId.takeIf { it > 0 } ?: return
        AppLog.i("LiveDanmaku", "connect room=$rid")

        messageClient =
            LiveMessageClient(
                roomId = rid,
                onDanmaku = { ev ->
                    runOnUiThread(
                        Runnable {
                            appendLiveDanmakuEvent(ev)
                        },
                    )
                },
                onSuperChat = { ev ->
                    runOnUiThread(
                        Runnable {
                            val title = "SC ¥${ev.price} · ${ev.user.ifBlank { "匿名" }}"
                            pushChatItem(LiveChatAdapter.Item(title = title, body = ev.message))
                        },
                    )
                },
                onStatus = { msg ->
                    runOnUiThread(
                        Runnable {
                            AppLog.d("LiveWs", msg)
                            pushChatItem(LiveChatAdapter.Item(title = "系统", body = msg))
                        },
                    )
                },
            )

        lifecycleScope.launch {
            runCatching { messageClient?.connect() }
                .onFailure { AppLog.w("LiveWs", "connect failed", it) }
        }
    }

    private fun liveDanmakuPositionMs(): Long {
        val base = liveDanmakuBaseUptimeMs
        if (base <= 0L) return 0L
        val posMs = SystemClock.elapsedRealtime() - base
        return posMs.coerceIn(0L, Int.MAX_VALUE.toLong())
    }

    private fun liveDanmakuAppendTimeMs(): Int {
        val nowMs = liveDanmakuPositionMs().toInt()
        val lastMs = liveDanmakuLastAppendMs
        val safeMs = if (lastMs == Int.MIN_VALUE) nowMs else nowMs.coerceAtLeast(lastMs)
        liveDanmakuLastAppendMs = safeMs
        return safeMs
    }

    private fun appendLiveDanmakuEvent(ev: LiveMessageClient.LiveDanmakuEvent) {
        if (!session.danmaku.enabled) return
        if (player == null) return
        val timeMs = liveDanmakuAppendTimeMs()

        val d =
            Danmaku(
                timeMs = timeMs,
                mode = 1,
                text = ev.text,
                color = ev.color,
                fontSize = 25,
                weight = 0,
                attr = 0,
            )
        binding.danmakuView.appendDanmakus(listOf(d), maxItems = 2000, alreadySorted = true)
        pushChatItem(LiveChatAdapter.Item(title = "弹幕", body = ev.text))
    }

    private fun pushChatItem(item: LiveChatAdapter.Item) {
        chatItems.addLast(item)
        while (chatItems.size > chatMax) chatItems.removeFirst()
    }

    private fun showQualityDialog() {
        val play = lastPlay ?: run {
            AppToast.show(this, "暂无可用清晰度")
            return
        }
        val available = play.acceptQn.ifEmpty { play.qnDesc.keys.sortedDescending() }.distinct()
        if (available.isEmpty()) {
            AppToast.show(this, "暂无可用清晰度")
            return
        }
        val optionsAvailable = available.sortedWith(compareBy({ it != LIVE_QN_ORIGINAL }, { -it }))
        val options = optionsAvailable.map { q -> liveQnLabel(q, play) }
        val current = session.targetQn.takeIf { it > 0 } ?: play.currentQn
        val checked =
            optionsAvailable.indexOf(current).takeIf { it >= 0 }
                ?: optionsAvailable.indexOf(LIVE_QN_ORIGINAL).takeIf { it >= 0 }
                ?: 0
        AppPopup.singleChoice(
            context = this,
            title = "清晰度",
            items = options,
            checkedIndex = checked,
        ) { which, _ ->
            val picked = optionsAvailable.getOrNull(which) ?: return@singleChoice
            session = session.copy(targetQn = picked, lineOrder = 1, originFailCount = 0)
            resetPlaybackRecoveryState()
            refreshSettings()
            lifecycleScope.launch { loadAndPlay(initial = false) }
        }
    }

    private fun showLineDialog() {
        val play = lastPlay ?: run {
            AppToast.show(this, "暂无可用线路")
            return
        }
        val lines = play.lines
        if (lines.isEmpty()) {
            AppToast.show(this, "暂无可用线路")
            return
        }
        val options = lines.map { "线路 ${it.order}" }
        val checked = (session.lineOrder - 1).coerceIn(0, lines.size - 1)
        AppPopup.singleChoice(
            context = this,
            title = "线路",
            items = options,
            checkedIndex = checked,
        ) { which, _ ->
            val picked = lines.getOrNull(which) ?: return@singleChoice
            session =
                if (isSignedLiveUrl(picked.url)) {
                    session.copy(lineOrder = picked.order)
                } else {
                    // User explicitly chooses an origin line: allow retrying origin mode.
                    session.copy(lineOrder = picked.order, originFailCount = 0)
                }
            resetPlaybackRecoveryState()
            refreshSettings()
            lifecycleScope.launch { loadAndPlay(initial = false) }
        }
    }

    private fun showChatDialog() {
        AppPopup.custom(
            context = this,
            title = "弹幕 / SC",
            cancelable = true,
            actions = listOf(PopupAction(role = PopupActionRole.POSITIVE, text = "关闭")),
            preferredActionRole = PopupActionRole.POSITIVE,
            content = { dialogContext ->
                val dialogBinding = DialogLiveChatBinding.inflate(LayoutInflater.from(dialogContext))
                val adapter = LiveChatAdapter()
                dialogBinding.recycler.adapter = adapter
                dialogBinding.recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(dialogContext)
                adapter.submit(chatItems.toList().asReversed())

                val dm = dialogContext.resources.displayMetrics
                val maxHeightPx =
                    (dm.heightPixels * 0.60f)
                        .toInt()
                        .coerceAtLeast(dp(dialogContext, 220f))
                        .coerceAtMost(dp(dialogContext, 520f))
                dialogBinding.recycler.layoutParams =
                    dialogBinding.recycler.layoutParams
                        ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                dialogBinding.recycler.layoutParams.height = maxHeightPx

                dialogBinding.root
            },
        )
    }

    private fun dp(context: android.content.Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        val icon = if (isPlaying) blbl.cat3399.R.drawable.ic_player_pause else blbl.cat3399.R.drawable.ic_player_play
        binding.btnPlayPause.setImageResource(icon)
    }

    private fun updateDanmakuButton() {
        binding.btnDanmaku.imageTintList = null
        binding.btnDanmaku.isSelected = session.danmaku.enabled
    }

    private fun updateDebugOverlay() {
        val enabled = session.debugEnabled
        binding.tvDebug.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.danmakuView.setDebugEnabled(enabled)
        debugJob?.cancel()
        if (!enabled) return
        val engine = player ?: return
        debugJob =
            lifecycleScope.launch {
                while (isActive) {
                    binding.tvDebug.text =
                        when (engine) {
                            is ExoPlayerEngine -> buildExoDebugText(engine.exoPlayer)
                            is IjkPlayerEngine -> buildIjkDebugText(engine)
                            else -> "-"
                        }
                    delay(500)
                }
            }
    }

    private fun buildExoDebugText(exo: ExoPlayer): String {
        updateDebugVideoStatsFromCounters(exo)
        val trackFormat = pickSelectedVideoFormat(exo)
        val trackBitrate =
            trackFormat
                ?.let { format ->
                    format.averageBitrate.takeIf { it > 0 }
                        ?: format.bitrate.takeIf { it > 0 }
                        ?: format.peakBitrate.takeIf { it > 0 }
                }?.toLong()
        val brBps = liveHlsDebugInfo?.recentBitrateBps?.takeIf { it > 0L } ?: trackBitrate
        val state =
            when (exo.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> exo.playbackState.toString()
            }
        return buildString {
            appendLiveDebugHeader(this, state = state, isPlaying = exo.isPlaying, playWhenReady = exo.playWhenReady)
            append("pos=").append(exo.currentPosition).append("ms")
            append(" buf=").append(exo.bufferedPosition).append("ms")
            append(" spd=").append(String.format(Locale.US, "%.2f", exo.playbackParameters.speed))
            append('\n')

            append("res=").append(buildDebugResolutionText(exo, trackFormat))
            append(" fps=").append(formatDebugFps(debug.renderFps) ?: formatDebugFps(debug.videoInputFps ?: trackFormat?.frameRate) ?: "-")
            append(" br=").append(formatBitrateKbps(brBps))
            appendCdnHostLine(this)

            buildDebugDisplayText()?.let { disp ->
                append("disp=").append(disp)
                append('\n')
            }

            append("decoder=").append(shortenDebugValue(debug.videoDecoderName ?: "-", maxChars = 64))
            append(" drop=").append(debug.droppedFramesTotal)
            append(" rebuffer=").append(debug.rebufferCount)
            append('\n')

            appendLiveHlsDebug(this)
            appendDanmakuDebug(this)
        }
    }

    private fun buildIjkDebugText(engine: IjkPlayerEngine): String {
        val snap = engine.debugSnapshot()
        val state =
            when (snap.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> snap.playbackState.toString()
            }
        val resolution =
            if ((snap.videoWidth ?: 0) > 0 && (snap.videoHeight ?: 0) > 0) {
                "${snap.videoWidth}x${snap.videoHeight}"
            } else {
                "-"
            }
        val decoder =
            when (snap.videoDecoder) {
                tv.danmaku.ijk.media.player.IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC -> "MediaCodec"
                tv.danmaku.ijk.media.player.IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC -> "avcodec"
                else -> "-"
            }
        return buildString {
            appendLiveDebugHeader(this, state = state, isPlaying = snap.isPlaying, playWhenReady = snap.playWhenReady)
            append("pos=").append(snap.positionMs).append("ms")
            append(" buf=").append(snap.bufferedPositionMs).append("ms")
            append(" spd=").append(String.format(Locale.US, "%.2f", snap.playbackSpeed))
            append('\n')

            append("res=").append(resolution)
            append(" fps=").append(formatDebugFps(snap.fpsOutput ?: snap.fpsDecode) ?: "-")
            append(" br=").append(formatBitrateKbps(snap.bitRate.takeIf { it > 0L }))
            appendCdnHostLine(this)

            buildDebugDisplayText()?.let { disp ->
                append("disp=").append(disp)
                append('\n')
            }

            append("decoder=").append(decoder)
            if (snap.tcpSpeed > 0L) {
                append(" net=").append(String.format(Locale.US, "%.2f", snap.tcpSpeed * 8.0 / 1_000_000.0)).append("Mbps")
            }
            append(" vCache=").append(snap.videoCachedDurationMs.coerceAtLeast(0L)).append("ms")
            append(" aCache=").append(snap.audioCachedDurationMs.coerceAtLeast(0L)).append("ms")
            append('\n')

            appendDanmakuDebug(this)
        }
    }

    private fun appendLiveDebugHeader(sb: StringBuilder, state: String, isPlaying: Boolean, playWhenReady: Boolean) {
        val play = lastPlay
        sb.append("room=").append(realRoomId)
        sb.append(" qn=").append(play?.currentQn ?: 0)
        sb.append(" line=").append(session.lineOrder)
        sb.append(" state=").append(state)
        sb.append(" playing=").append(isPlaying)
        sb.append(" pwr=").append(playWhenReady)
        sb.append('\n')
    }

    private fun appendCdnHostLine(sb: StringBuilder) {
        val cdnHost = debug.videoTransferHost?.trim().takeIf { !it.isNullOrBlank() } ?: debug.cdnHost?.trim().takeIf { !it.isNullOrBlank() } ?: "-"
        if (cdnHost.length <= 42) {
            sb.append(" cdn=").append(cdnHost)
            sb.append('\n')
        } else {
            sb.append('\n')
            sb.append("cdn=").append(cdnHost)
            sb.append('\n')
        }
    }

    private fun appendLiveHlsDebug(sb: StringBuilder) {
        val info = liveHlsDebugInfo ?: return
        sb.append("hls=")
        var hasField = false
        info.lastSegmentSequence?.let {
            sb.append("seq=").append(it)
            hasField = true
        } ?: info.mediaSequence?.let {
            sb.append("seq=").append(it)
            hasField = true
        }
        info.targetDurationSec?.let {
            if (hasField) sb.append(' ')
            sb.append("td=").append(formatDebugSeconds(it)).append('s')
            hasField = true
        }
        info.lastSegmentUri?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let {
            if (hasField) sb.append(' ')
            sb.append("seg=").append(shortenDebugValue(it, maxChars = 24))
            hasField = true
        }
        if (info.segmentCount > 0) {
            if (hasField) sb.append(' ')
            sb.append("win=").append(info.segmentCount)
            hasField = true
        }
        if (!hasField) sb.append('-')
        sb.append('\n')

        if (!info.lastAuxRaw.isNullOrBlank()) {
            sb.append("aux=").append(shortenDebugValue(info.lastAuxRaw, maxChars = 48)).append('\n')
        }
        if (!info.mapUri.isNullOrBlank()) {
            sb.append("map=").append(shortenDebugValue(info.mapUri.substringAfterLast('/'), maxChars = 32)).append('\n')
        }
    }

    private fun appendDanmakuDebug(sb: StringBuilder) {
        runCatching { binding.danmakuView.getDebugStats() }.getOrNull()?.let { dm ->
            sb.append("dm=").append(if (dm.configEnabled) "on" else "off")
            sb.append(" fps=").append(String.format(Locale.US, "%.1f", dm.drawFps))
            sb.append(" act=").append(dm.lastFrameActive)
            sb.append(" pend=").append(dm.lastFramePending)
            sb.append(" hit=").append(dm.lastFrameCachedDrawn).append('/').append(dm.lastFrameActive)
            sb.append(" fb=").append(dm.lastFrameFallbackDrawn)
            sb.append(" q=").append(dm.queueDepth)
            if (dm.invalidateFull) {
                sb.append(" inv=full")
            } else {
                sb.append(" inv=").append(dm.invalidateTopPx).append('-').append(dm.invalidateBottomPx)
            }
        }
    }

    private fun reportLiveRoomEntry(roomId: Long) {
        if (roomId <= 0L || lastLiveStatus != 1) return
        if (liveEntryReportedRoomId == roomId) return
        liveEntryReportedRoomId = roomId
        lifecycleScope.launch {
            runCatching { BiliApi.liveRoomEntryAction(roomId) }
                .onSuccess { AppLog.d("LiveReport", "entry ok room=$roomId") }
                .onFailure { t ->
                    if (t is CancellationException) throw t
                    if (liveEntryReportedRoomId == roomId) liveEntryReportedRoomId = 0L
                    AppLog.w("LiveReport", "entry failed room=$roomId", t)
                }
        }
    }

    private fun updateDebugVideoStatsFromCounters(exo: ExoPlayer) {
        val nowMs = SystemClock.elapsedRealtime()
        val counters = exo.videoDecoderCounters ?: return
        counters.ensureUpdated()
        debug.droppedFramesTotal = maxOf(debug.droppedFramesTotal, counters.droppedBufferCount.toLong())

        val count = counters.renderedOutputBufferCount
        val lastCount = debug.renderedFramesLastCount
        val lastAt = debug.renderedFramesLastAtMs
        debug.renderedFramesLastCount = count
        debug.renderedFramesLastAtMs = nowMs

        if (lastCount == null || lastAt == null) return
        val deltaMs = nowMs - lastAt
        val deltaFrames = count - lastCount
        if (deltaMs <= 0L || deltaMs > 10_000L) return
        if (deltaFrames <= 0) return
        val instantFps = (deltaFrames * 1000f) / deltaMs.toFloat()
        debug.renderFps = debug.renderFps?.let { it * 0.7f + instantFps * 0.3f } ?: instantFps
    }

    private fun buildDebugResolutionText(exo: ExoPlayer, trackFormat: Format?): String {
        val videoSize = exo.videoSize
        val width = videoSize.width.takeIf { it > 0 } ?: debug.videoInputWidth ?: 0
        val height = videoSize.height.takeIf { it > 0 } ?: debug.videoInputHeight ?: 0
        if (width > 0 && height > 0) return "${width}x${height}"
        val trackWidth = trackFormat?.width?.takeIf { it > 0 } ?: 0
        val trackHeight = trackFormat?.height?.takeIf { it > 0 } ?: 0
        return if (trackWidth > 0 && trackHeight > 0) "${trackWidth}x${trackHeight}" else "-"
    }

    private fun pickSelectedVideoFormat(exo: ExoPlayer): Format? {
        val tracks = exo.currentTracks
        for (group in tracks.groups) {
            if (!group.isSelected) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val format = group.getTrackFormat(i)
                val mime = format.sampleMimeType ?: ""
                if (mime.startsWith("video/")) return format
            }
        }
        return null
    }

    private fun formatDebugFps(fps: Float?): String? {
        val value = fps?.takeIf { it > 0f } ?: return null
        val rounded = value.roundToInt().toFloat()
        return if (abs(value - rounded) < 0.05f) rounded.toInt().toString() else String.format(Locale.US, "%.1f", value)
    }

    private fun formatDebugSeconds(seconds: Double): String {
        val rounded = seconds.roundToInt().toDouble()
        return if (abs(seconds - rounded) < 0.01) rounded.toInt().toString() else String.format(Locale.US, "%.2f", seconds)
    }

    private fun formatBitrateKbps(bps: Long?): String {
        val value = bps?.takeIf { it > 0L } ?: return "-"
        return String.format(Locale.US, "%.1fkbps", value / 1000.0)
    }

    private fun shortenDebugValue(value: String, maxChars: Int): String {
        val text = value.trim()
        if (text.length <= maxChars) return text
        return text.take(maxChars - 1) + "…"
    }

    private fun buildDebugDisplayText(): String? {
        val display = binding.root.display ?: return null
        val hz = display.refreshRate.takeIf { it > 0f }
        if (Build.VERSION.SDK_INT < 23) {
            return hz?.let { String.format(Locale.US, "%.0fHz", it) } ?: "-"
        }
        val mode = display.mode
        val w = mode.physicalWidth.takeIf { it > 0 } ?: 0
        val h = mode.physicalHeight.takeIf { it > 0 } ?: 0
        val mhz = mode.refreshRate.takeIf { it > 0f } ?: hz
        val hzText = mhz?.let { String.format(Locale.US, "%.0fHz", it) } ?: "-"
        if (w <= 0 || h <= 0) return hzText
        return "${w}x${h}@${hzText}"
    }

    private fun liveQnLabel(qn: Int, play: BiliApi.LivePlayUrl?): String {
        val fromApi = play?.qnDesc?.get(qn)?.takeIf { it.isNotBlank() }
        if (fromApi != null) return fromApi
        return when (qn) {
            30000 -> "杜比"
            20000 -> "4K"
            10000 -> "原画"
            400 -> "蓝光"
            250 -> "超清"
            150 -> "高清"
            80 -> "流畅"
            else -> qn.toString()
        }
    }

    private fun LiveSession.toEngineSwitchJsonString(): String {
        return JSONObject().apply {
            put("v", 1)
            put("engineKind", engineKind.prefValue)
            put("targetQn", targetQn)
            put("lineOrder", lineOrder)
            put("originFailCount", originFailCount)
            put("highBitrateEnabled", highBitrateEnabled)
            put("danmakuEnabled", danmaku.enabled)
            put("danmakuOpacity", danmaku.opacity.toDouble())
            put("danmakuTextSizeSp", danmaku.textSizeSp.toDouble())
            put("danmakuFontWeight", danmaku.fontWeight.prefValue)
            put("danmakuStrokeWidthPx", danmaku.strokeWidthPx)
            put("danmakuSpeedLevel", danmaku.speedLevel)
            put("danmakuArea", danmaku.area.toDouble())
            put("danmakuLaneDensity", danmaku.laneDensity.prefValue)
            put("debugEnabled", debugEnabled)
        }.toString()
    }

    private fun LiveSession.restoreFromEngineSwitchJsonString(raw: String): LiveSession {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return this

        fun optFloat(key: String, fallback: Float): Float {
            val value = obj.optDouble(key, fallback.toDouble()).toFloat()
            if (!value.isFinite()) return fallback
            return value
        }

        fun optInt(key: String, fallback: Int): Int {
            return obj.optInt(key, fallback)
        }

        fun normalizeDanmakuStrokeWidthPx(value: Int): Int {
            return when {
                value <= 1 -> 0
                value <= 3 -> 2
                value <= 5 -> 4
                else -> 6
            }
        }

        val restoredEngineKind = PlayerEngineKind.fromPrefValue(obj.optString("engineKind", engineKind.prefValue))
        val restoredTargetQn = optInt("targetQn", targetQn).takeIf { it > 0 } ?: targetQn
        val restoredLineOrder = optInt("lineOrder", lineOrder).takeIf { it > 0 } ?: lineOrder
        val restoredOriginFailCount = optInt("originFailCount", originFailCount).coerceAtLeast(0)
        val restoredHighBitrateEnabled = obj.optBoolean("highBitrateEnabled", highBitrateEnabled)
        val restoredDanmakuEnabled = obj.optBoolean("danmakuEnabled", danmaku.enabled)
        val restoredDanmakuOpacity = optFloat("danmakuOpacity", danmaku.opacity).coerceIn(0.05f, 1.0f)
        val restoredDanmakuTextSizeSp = optFloat("danmakuTextSizeSp", danmaku.textSizeSp).coerceIn(10f, 60f)
        val restoredDanmakuFontWeight = DanmakuFontWeight.fromPrefValue(obj.optString("danmakuFontWeight", danmaku.fontWeight.prefValue))
        val restoredDanmakuStrokeWidthPx = normalizeDanmakuStrokeWidthPx(optInt("danmakuStrokeWidthPx", danmaku.strokeWidthPx))
        val restoredDanmakuSpeedLevel = optInt("danmakuSpeedLevel", danmaku.speedLevel).coerceIn(1, 10)
        val restoredDanmakuArea = AppPrefs.normalizeLegacyDanmakuAreaCompat(optFloat("danmakuArea", danmaku.area))
        val restoredDanmakuLaneDensity = DanmakuLaneDensity.fromPrefValue(obj.optString("danmakuLaneDensity", danmaku.laneDensity.prefValue))
        val restoredDebugEnabled = obj.optBoolean("debugEnabled", debugEnabled)

        return copy(
            engineKind = restoredEngineKind,
            targetQn = restoredTargetQn,
            lineOrder = restoredLineOrder,
            originFailCount = restoredOriginFailCount,
            highBitrateEnabled = restoredHighBitrateEnabled,
            danmaku =
                danmaku.copy(
                    enabled = restoredDanmakuEnabled,
                    opacity = restoredDanmakuOpacity,
                    textSizeSp = restoredDanmakuTextSizeSp,
                    fontWeight = restoredDanmakuFontWeight,
                    strokeWidthPx = restoredDanmakuStrokeWidthPx,
                    speedLevel = restoredDanmakuSpeedLevel,
                    area = restoredDanmakuArea,
                    laneDensity = restoredDanmakuLaneDensity,
                ),
            debugEnabled = restoredDebugEnabled,
        )
    }

    private data class LiveSession(
        val engineKind: PlayerEngineKind = PlayerEngineKind.fromPrefValue(BiliClient.prefs.playerEngineKind),
        val targetQn: Int = LIVE_QN_ORIGINAL,
        val lineOrder: Int = 1,
        val originFailCount: Int = 0,
        val highBitrateEnabled: Boolean = BiliClient.prefs.liveHighBitrateEnabled,
        val danmaku: DanmakuSessionSettings =
            DanmakuSessionSettings(
                enabled = BiliClient.prefs.danmakuEnabled,
                opacity = BiliClient.prefs.danmakuOpacity,
                textSizeSp = BiliClient.prefs.danmakuTextSizeSp,
                fontWeight = DanmakuFontWeight.fromPrefValue(BiliClient.prefs.danmakuFontWeight),
                strokeWidthPx = BiliClient.prefs.danmakuStrokeWidthPx,
                speedLevel = BiliClient.prefs.danmakuSpeed,
                area = BiliClient.prefs.danmakuArea,
                laneDensity = DanmakuLaneDensity.fromPrefValue(BiliClient.prefs.danmakuLaneDensity),
            ),
        val debugEnabled: Boolean = BiliClient.prefs.playerDebugEnabled,
    )

    companion object {
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_UNAME = "uname"

        private const val EXTRA_ENGINE_SWITCH_SESSION_JSON = "engine_switch_session_json"
        private const val LIVE_QN_ORIGINAL = 10_000

        private const val AUTO_HIDE_MS = 4_000L
        private const val SEEK_HINT_HIDE_DELAY_MS = 900L
        private const val BACK_DOUBLE_PRESS_WINDOW_MS = 2_500L
        private const val AUTO_FAILOVER_WINDOW_MS = 12_000L
        private const val AUTO_FAILOVER_MIN_INTERVAL_MS = 1_200L
        private const val AUTO_FAILOVER_MAX_SWITCHES = 6
        private const val ORIGIN_FAIL_BEFORE_SIGNED = 5
        private const val BEHIND_LIVE_WINDOW_RECOVER_WINDOW_MS = 15_000L
        private const val BEHIND_LIVE_WINDOW_RECOVER_MIN_INTERVAL_MS = 1_200L
        private const val BEHIND_LIVE_WINDOW_MAX_RECOVERS = 2
    }
}
