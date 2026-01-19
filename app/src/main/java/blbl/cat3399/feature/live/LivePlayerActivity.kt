package blbl.cat3399.feature.live

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.tv.TvMode
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.SingleChoiceDialog
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.databinding.DialogLiveChatBinding
import blbl.cat3399.feature.player.PlayerSettingsAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class LivePlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var autoHideJob: Job? = null
    private var debugJob: Job? = null
    private var finishOnBackKeyUp: Boolean = false
    private var controlsVisible: Boolean = false
    private var lastInteractionAtMs: Long = 0L
    private var lastBackAtMs: Long = 0L

    private var roomId: Long = 0L
    private var realRoomId: Long = 0L
    private var roomTitle: String = ""
    private var roomUname: String = ""

    private var session: LiveSession = LiveSession()

    private var lastPlay: BiliApi.LivePlayUrl? = null
    private var lastLiveStatus: Int = 0

    private val chatItems = ArrayDeque<LiveChatAdapter.Item>()
    private val chatMax = 200

    private var messageClient: LiveMessageClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        applyUiMode()

        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0L)
        roomTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        roomUname = intent.getStringExtra(EXTRA_UNAME).orEmpty()
        if (roomId <= 0L) {
            Toast.makeText(this, "缺少 room_id", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Live: no seek bar.
        binding.seekProgress.visibility = View.GONE
        binding.tvTime.visibility = View.GONE
        binding.btnSubtitle.visibility = View.GONE
        binding.tvSeekHint.visibility = View.GONE
        binding.btnPrev.visibility = View.GONE
        binding.btnNext.visibility = View.GONE
        binding.tvOnline.visibility = View.GONE
        binding.btnUp.visibility = View.GONE
        binding.btnLike.visibility = View.GONE
        binding.btnCoin.visibility = View.GONE
        binding.btnFav.visibility = View.GONE

        binding.btnBack.setOnClickListener { finish() }

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        binding.playerView.player = exo
        binding.danmakuView.setPositionProvider { exo.currentPosition }
        binding.danmakuView.setConfigProvider { session.danmaku.toConfig() }

        exo.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    AppLog.e("LivePlayer", "onPlayerError", error)
                    Toast.makeText(this@LivePlayerActivity, "播放失败：${error.errorCodeName}", Toast.LENGTH_SHORT).show()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseIcon(isPlaying)
                    noteUserInteraction()
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
            binding.settingsPanel.visibility = if (willShow) View.VISIBLE else View.GONE
            setControlsVisible(true)
            if (willShow) binding.recyclerSettings.post { binding.recyclerSettings.requestFocus() }
        }
        binding.btnDanmaku.setOnClickListener {
            session = session.copy(danmaku = session.danmaku.copy(enabled = !session.danmaku.enabled))
            binding.danmakuView.invalidate()
            updateDanmakuButton()
            setControlsVisible(true)
        }

        binding.playerView.setOnClickListener {
            if (binding.settingsPanel.visibility == View.VISIBLE) {
                binding.settingsPanel.visibility = View.GONE
                setControlsVisible(true)
                focusFirstControl()
                return@setOnClickListener
            }
            toggleControls()
        }

        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()

        setupSettingsPanel()
        setControlsVisible(true)
        updateDanmakuButton()

        lifecycleScope.launch { loadAndPlay(initial = true) }
    }

    override fun onResume() {
        super.onResume()
        applyUiMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
    }

    override fun onDestroy() {
        messageClient?.close()
        messageClient = null
        debugJob?.cancel()
        autoHideJob?.cancel()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun applyUiMode() {
        val tvMode = TvMode.isEnabled(this)
        val uiScale = UiScale.factor(this, tvMode, BiliClient.prefs.sidebarSize)

        fun px(id: Int): Int = resources.getDimensionPixelSize(id)
        fun pxF(id: Int): Float = resources.getDimension(id)
        fun scaledPx(id: Int): Int = (px(id) * uiScale).roundToInt().coerceAtLeast(0)
        fun scaledPxF(id: Int): Float = pxF(id) * uiScale

        val topPadH =
            scaledPx(if (tvMode) blbl.cat3399.R.dimen.player_top_bar_padding_h_tv else blbl.cat3399.R.dimen.player_top_bar_padding_h)
        val topPadV =
            scaledPx(if (tvMode) blbl.cat3399.R.dimen.player_top_bar_padding_v_tv else blbl.cat3399.R.dimen.player_top_bar_padding_v)
        if (
            binding.topBar.paddingLeft != topPadH ||
            binding.topBar.paddingRight != topPadH ||
            binding.topBar.paddingTop != topPadV ||
            binding.topBar.paddingBottom != topPadV
        ) {
            binding.topBar.setPadding(topPadH, topPadV, topPadH, topPadV)
        }

        val topBtnSize =
            scaledPx(if (tvMode) blbl.cat3399.R.dimen.player_top_button_size_tv else blbl.cat3399.R.dimen.player_top_button_size).coerceAtLeast(1)
        val topBtnPad =
            scaledPx(if (tvMode) blbl.cat3399.R.dimen.player_top_button_padding_tv else blbl.cat3399.R.dimen.player_top_button_padding)
        setSize(binding.btnBack, topBtnSize, topBtnSize)
        binding.btnBack.setPadding(topBtnPad, topBtnPad, topBtnPad, topBtnPad)
        setSize(binding.btnSettings, topBtnSize, topBtnSize)
        binding.btnSettings.setPadding(topBtnPad, topBtnPad, topBtnPad, topBtnPad)

        binding.tvTitle.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(if (tvMode) blbl.cat3399.R.dimen.player_title_text_size_tv else blbl.cat3399.R.dimen.player_title_text_size),
        )

        binding.tvOnline.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(if (tvMode) blbl.cat3399.R.dimen.player_online_text_size_tv else blbl.cat3399.R.dimen.player_online_text_size),
        )

        val bottomPadV =
            scaledPx(if (tvMode) blbl.cat3399.R.dimen.player_bottom_bar_padding_v_tv else blbl.cat3399.R.dimen.player_bottom_bar_padding_v)
        if (binding.bottomBar.paddingTop != bottomPadV || binding.bottomBar.paddingBottom != bottomPadV) {
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                bottomPadV,
                binding.bottomBar.paddingRight,
                bottomPadV,
            )
        }

        (binding.seekProgress.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height =
                scaledPx(if (tvMode) blbl.cat3399.R.dimen.player_seekbar_height_tv else blbl.cat3399.R.dimen.player_seekbar_height).coerceAtLeast(1)
            val mb =
                scaledPx(if (tvMode) blbl.cat3399.R.dimen.player_seekbar_margin_bottom_tv else blbl.cat3399.R.dimen.player_seekbar_margin_bottom)
            if (lp.height != height || lp.bottomMargin != mb) {
                lp.height = height
                lp.bottomMargin = mb
                binding.seekProgress.layoutParams = lp
            }
        }

        (binding.controlsRow.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height =
                scaledPx(if (tvMode) blbl.cat3399.R.dimen.player_controls_row_height_tv else blbl.cat3399.R.dimen.player_controls_row_height).coerceAtLeast(1)
            val ms =
                scaledPx(if (tvMode) blbl.cat3399.R.dimen.player_controls_row_margin_start_tv else blbl.cat3399.R.dimen.player_controls_row_margin_start)
            val me = scaledPx(if (tvMode) blbl.cat3399.R.dimen.player_controls_row_margin_end_tv else blbl.cat3399.R.dimen.player_controls_row_margin_end)
            if (lp.height != height || lp.marginStart != ms || lp.marginEnd != me) {
                lp.height = height
                lp.marginStart = ms
                lp.marginEnd = me
                binding.controlsRow.layoutParams = lp
            }
        }

        val controlSize =
            scaledPx(if (tvMode) blbl.cat3399.R.dimen.player_control_button_size_tv else blbl.cat3399.R.dimen.player_control_button_size).coerceAtLeast(1)
        val subtitleHeight =
            scaledPx(
                if (tvMode) blbl.cat3399.R.dimen.player_control_button_height_subtitle_tv else blbl.cat3399.R.dimen.player_control_button_height_subtitle,
            ).coerceAtLeast(1)
        val settingsSize =
            scaledPx(
                if (tvMode) blbl.cat3399.R.dimen.player_control_button_size_settings_tv else blbl.cat3399.R.dimen.player_control_button_size_settings,
            ).coerceAtLeast(1)
        val controlPad =
            scaledPx(if (tvMode) blbl.cat3399.R.dimen.player_control_button_padding_tv else blbl.cat3399.R.dimen.player_control_button_padding)
        listOf(binding.btnSubtitle, binding.btnDanmaku).forEach { btn ->
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

            val playSize = scaledPx(blbl.cat3399.R.dimen.player_control_button_size_main_play_tv).coerceAtLeast(1)
            val transportPad = scaledPx(blbl.cat3399.R.dimen.player_control_button_padding_main_tv)
            val gap = scaledPx(blbl.cat3399.R.dimen.player_control_button_gap_tv)
            setSize(binding.btnPlayPause, playSize, playSize)
            binding.btnPlayPause.setPadding(transportPad, transportPad, transportPad, transportPad)
            setEndMargin(binding.btnPlayPause, gap)
        }

        binding.tvTime.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(if (tvMode) blbl.cat3399.R.dimen.player_time_text_size_tv else blbl.cat3399.R.dimen.player_time_text_size),
        )

        binding.tvSeekHint.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(if (tvMode) blbl.cat3399.R.dimen.player_seek_hint_text_size_tv else blbl.cat3399.R.dimen.player_seek_hint_text_size),
        )
        val hintPadH =
            scaledPx(if (tvMode) blbl.cat3399.R.dimen.player_seek_hint_padding_h_tv else blbl.cat3399.R.dimen.player_seek_hint_padding_h)
        val hintPadV =
            scaledPx(if (tvMode) blbl.cat3399.R.dimen.player_seek_hint_padding_v_tv else blbl.cat3399.R.dimen.player_seek_hint_padding_v)
        if (
            binding.tvSeekHint.paddingLeft != hintPadH ||
            binding.tvSeekHint.paddingRight != hintPadH ||
            binding.tvSeekHint.paddingTop != hintPadV ||
            binding.tvSeekHint.paddingBottom != hintPadV
        ) {
            binding.tvSeekHint.setPadding(hintPadH, hintPadV, hintPadH, hintPadV)
        }
    }

    private fun setSize(view: View, widthPx: Int, heightPx: Int) {
        val lp = view.layoutParams ?: return
        if (lp.width == widthPx && lp.height == heightPx) return
        lp.width = widthPx
        lp.height = heightPx
        view.layoutParams = lp
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

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
        return super.dispatchKeyEvent(event)
    }

    private fun setupSettingsPanel() {
        val settingsAdapter =
            PlayerSettingsAdapter { item ->
                when (item.title) {
                    "清晰度" -> showQualityDialog()
                    "线路选择" -> showLineDialog()
                    else -> Toast.makeText(this, "暂未实现：${item.title}", Toast.LENGTH_SHORT).show()
                }
            }
        binding.recyclerSettings.adapter = settingsAdapter
        binding.recyclerSettings.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        refreshSettings()
        updateDebugOverlay()
    }

    private fun refreshSettings() {
        val p = lastPlay
        val qn = session.targetQn.takeIf { it > 0 } ?: p?.currentQn ?: LIVE_QN_ORIGINAL
        val qLabel = liveQnLabel(qn, p)
        val lineLabel =
            p?.lines
                ?.getOrNull((session.lineOrder - 1).coerceAtLeast(0))
                ?.let { "线路 ${it.order}" }
                ?: "自动"
        val list =
            listOf(
                PlayerSettingsAdapter.SettingItem("清晰度", qLabel),
                PlayerSettingsAdapter.SettingItem("线路选择", lineLabel),
            )
        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.submit(list)
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

        restartAutoHideTimer()
    }

    private fun restartAutoHideTimer() {
        autoHideJob?.cancel()
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
        if (binding.btnPlayPause.visibility == View.VISIBLE) return binding.btnPlayPause.requestFocus()
        return binding.btnBack.requestFocus()
    }

    private fun hasControlsFocus(): Boolean {
        if (binding.settingsPanel.visibility == View.VISIBLE) return true
        return binding.topBar.hasFocus() || binding.bottomBar.hasFocus()
    }

    private fun noteUserInteraction() {
        lastInteractionAtMs = SystemClock.uptimeMillis()
        restartAutoHideTimer()
    }

    private fun shouldFinishOnBackPress(): Boolean {
        val exo = player
        if (
            exo != null &&
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

    private suspend fun loadAndPlay(initial: Boolean) {
        val exo = player ?: return
        try {
            val info = BiliApi.liveRoomInfo(roomId)
            realRoomId = info.roomId
            lastLiveStatus = info.liveStatus

            val title = info.title.ifBlank { roomTitle }
            binding.tvTitle.text =
                buildString {
                    append(title.ifBlank { "直播间 $realRoomId" })
                    if (roomUname.isNotBlank()) append(" · ").append(roomUname)
                }

            val qn = session.targetQn.takeIf { it > 0 } ?: 150
            val play = BiliApi.livePlayUrl(realRoomId, qn)
            lastPlay = play
            refreshSettings()

            val pickedLine =
                play.lines.getOrNull((session.lineOrder - 1).coerceAtLeast(0))
                    ?: play.lines.firstOrNull()
            if (pickedLine == null) error("No playable live url")

            val factory = OkHttpDataSource.Factory(BiliClient.cdnOkHttp)
            val mediaSourceFactory = DefaultMediaSourceFactory(factory)
            val mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(Uri.parse(pickedLine.url)))
            exo.setMediaSource(mediaSource)
            exo.prepare()
            exo.playWhenReady = true

            if (initial) connectDanmaku()
        } catch (t: Throwable) {
            AppLog.e("LivePlayer", "loadAndPlay failed", t)
            val e = t as? BiliApiException
            val msg = e?.let { "B 站返回：${it.apiCode} / ${it.apiMessage}" } ?: (t.message ?: "未知错误")
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun connectDanmaku() {
        messageClient?.close()
        messageClient = null
        val rid = realRoomId.takeIf { it > 0 } ?: return

        messageClient =
            LiveMessageClient(
                roomId = rid,
                onDanmaku = { ev ->
                    runOnUiThread(
                        Runnable {
                            if (!session.danmaku.enabled) return@Runnable
                            val exo = player ?: return@Runnable
                        val d =
                            Danmaku(
                                timeMs = exo.currentPosition.toInt(),
                                mode = 1,
                                text = ev.text,
                                color = ev.color,
                                fontSize = 25,
                                weight = 0,
                            )
                        binding.danmakuView.appendDanmakus(listOf(d), maxItems = 2000)
                        pushChatItem(LiveChatAdapter.Item(title = "弹幕", body = ev.text))
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

    private fun pushChatItem(item: LiveChatAdapter.Item) {
        chatItems.addLast(item)
        while (chatItems.size > chatMax) chatItems.removeFirst()
    }

    private fun showQualityDialog() {
        val play = lastPlay ?: run {
            Toast.makeText(this, "暂无可用清晰度", Toast.LENGTH_SHORT).show()
            return
        }
        val available = play.acceptQn.ifEmpty { play.qnDesc.keys.sortedDescending() }.distinct()
        if (available.isEmpty()) {
            Toast.makeText(this, "暂无可用清晰度", Toast.LENGTH_SHORT).show()
            return
        }
        val optionsAvailable = available.sortedWith(compareBy({ it != LIVE_QN_ORIGINAL }, { -it }))
        val options = optionsAvailable.map { q -> liveQnLabel(q, play) }
        val current = session.targetQn.takeIf { it > 0 } ?: play.currentQn
        val checked =
            optionsAvailable.indexOf(current).takeIf { it >= 0 }
                ?: optionsAvailable.indexOf(LIVE_QN_ORIGINAL).takeIf { it >= 0 }
                ?: 0
        SingleChoiceDialog.show(
            context = this,
            title = "清晰度",
            items = options,
            checkedIndex = checked,
            negativeText = "取消",
        ) { which, _ ->
            val picked = optionsAvailable.getOrNull(which) ?: return@show
            session = session.copy(targetQn = picked)
            session = session.copy(lineOrder = 1) // reset line
            refreshSettings()
            lifecycleScope.launch { loadAndPlay(initial = false) }
        }
    }

    private fun showLineDialog() {
        val play = lastPlay ?: run {
            Toast.makeText(this, "暂无可用线路", Toast.LENGTH_SHORT).show()
            return
        }
        val lines = play.lines
        if (lines.isEmpty()) {
            Toast.makeText(this, "暂无可用线路", Toast.LENGTH_SHORT).show()
            return
        }
        val options = lines.map { "线路 ${it.order}" }
        val checked = (session.lineOrder - 1).coerceIn(0, lines.size - 1)
        SingleChoiceDialog.show(
            context = this,
            title = "线路",
            items = options,
            checkedIndex = checked,
            negativeText = "取消",
        ) { which, _ ->
            val picked = lines.getOrNull(which) ?: return@show
            session = session.copy(lineOrder = picked.order)
            refreshSettings()
            lifecycleScope.launch { loadAndPlay(initial = false) }
        }
    }

    private fun showChatDialog() {
        val dialogBinding = DialogLiveChatBinding.inflate(layoutInflater)
        val adapter = LiveChatAdapter()
        dialogBinding.recycler.adapter = adapter
        dialogBinding.recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        adapter.submit(chatItems.toList().asReversed())
        MaterialAlertDialogBuilder(this)
            .setTitle("弹幕 / SC")
            .setView(dialogBinding.root)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        val icon = if (isPlaying) blbl.cat3399.R.drawable.ic_player_pause else blbl.cat3399.R.drawable.ic_player_play
        binding.btnPlayPause.setImageResource(icon)
    }

    private fun updateDanmakuButton() {
        val colorRes = if (session.danmaku.enabled) blbl.cat3399.R.color.blbl_blue else blbl.cat3399.R.color.blbl_text_secondary
        binding.btnDanmaku.imageTintList = ContextCompat.getColorStateList(this, colorRes)
    }

    private fun updateDebugOverlay() {
        val enabled = session.debugEnabled
        binding.tvDebug.visibility = if (enabled) View.VISIBLE else View.GONE
        debugJob?.cancel()
        if (!enabled) return
        val exo = player ?: return
        debugJob =
            lifecycleScope.launch {
                while (isActive) {
                    val play = lastPlay
                    binding.tvDebug.text =
                        buildString {
                            append("room=").append(realRoomId)
                            append(" qn=").append(play?.currentQn ?: 0)
                            append(" line=").append(session.lineOrder)
                            append(" pos=").append(exo.currentPosition).append("ms")
                        }
                    delay(500)
                }
            }
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

    private data class LiveDanmakuSession(
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

    private data class LiveSession(
        val targetQn: Int = LIVE_QN_ORIGINAL,
        val lineOrder: Int = 1,
        val danmaku: LiveDanmakuSession =
            LiveDanmakuSession(
                enabled = BiliClient.prefs.danmakuEnabled,
                opacity = BiliClient.prefs.danmakuOpacity,
                textSizeSp = BiliClient.prefs.danmakuTextSizeSp,
                speedLevel = BiliClient.prefs.danmakuSpeed,
                area = BiliClient.prefs.danmakuArea,
            ),
        val debugEnabled: Boolean = false,
    )

    companion object {
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_UNAME = "uname"

        private const val LIVE_QN_ORIGINAL = 10_000
        private const val AUTO_HIDE_MS = 4_000L
        private const val BACK_DOUBLE_PRESS_WINDOW_MS = 1_500L
    }
}
