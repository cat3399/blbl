package blbl.cat3399.feature.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.tv.TvMode
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.SingleChoiceDialog
import blbl.cat3399.core.update.TestApkUpdater
import blbl.cat3399.databinding.ActivitySettingsBinding
import blbl.cat3399.feature.risk.GaiaVgateActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var currentSectionIndex: Int = -1
    private lateinit var leftAdapter: SettingsLeftAdapter
    private lateinit var rightAdapter: SettingsEntryAdapter
    private var testUpdateJob: Job? = null
    private var clearCacheJob: Job? = null
    private var cacheSizeJob: Job? = null
    private var cacheSizeBytes: Long? = null
    private var focusListener: android.view.ViewTreeObserver.OnGlobalFocusChangeListener? = null

    private var lastFocusedLeftIndex: Int = 0
    private var lastFocusedRightTitle: String? = null
    private var pendingRestoreRightTitle: String? = null
    private var pendingRestoreLeftIndex: Int? = null
    private var pendingRestoreBack: Boolean = false
    private var focusRequestToken: Int = 0

    private val gaiaVgateLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val token = result.data?.getStringExtra(GaiaVgateActivity.EXTRA_GAIA_VTOKEN)?.trim()?.takeIf { it.isNotBlank() } ?: return@registerForActivityResult
            upsertGaiaVtokenCookie(token)
            val prefs = BiliClient.prefs
            prefs.gaiaVgateVVoucher = null
            prefs.gaiaVgateVVoucherSavedAtMs = -1L
            Toast.makeText(this, "验证成功，已写入风控票据", Toast.LENGTH_SHORT).show()
            refreshSection("风控验证")
        }

    private val sections = listOf(
        "通用设置",
        "页面设置",
        "播放设置",
        "弹幕设置",
        "关于应用",
        "设备信息",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        applyUiMode()

        binding.btnBack.setOnClickListener { finish() }

        leftAdapter = SettingsLeftAdapter { index -> showSection(index, keepScroll = false) }
        binding.recyclerLeft.layoutManager = LinearLayoutManager(this)
        binding.recyclerLeft.adapter = leftAdapter
        leftAdapter.submit(sections, selected = 0)

        binding.recyclerRight.layoutManager = LinearLayoutManager(this)
        binding.recyclerRight.itemAnimator = null
        rightAdapter = SettingsEntryAdapter { entry -> onEntryClicked(entry) }
        binding.recyclerRight.adapter = rightAdapter
        showSection(0)

        focusListener =
            android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus == null) return@OnGlobalFocusChangeListener
                when {
                    newFocus == binding.btnBack -> {
                        pendingRestoreBack = false
                    }

                    isDescendantOf(newFocus, binding.recyclerLeft) -> {
                        val holder = binding.recyclerLeft.findContainingViewHolder(newFocus) ?: return@OnGlobalFocusChangeListener
                        val pos = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@OnGlobalFocusChangeListener
                        lastFocusedLeftIndex = pos
                        if (pendingRestoreLeftIndex == pos) pendingRestoreLeftIndex = null
                    }

                    isDescendantOf(newFocus, binding.recyclerRight) -> {
                        val itemView = binding.recyclerRight.findContainingItemView(newFocus) ?: newFocus
                        val title = (itemView.tag as? String)?.takeIf { it.isNotBlank() }
                        if (title != null) lastFocusedRightTitle = title
                        if (pendingRestoreRightTitle == title) pendingRestoreRightTitle = null
                    }
                }
            }.also { binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(it) }
    }

    override fun onResume() {
        super.onResume()
        applyUiMode()
        ensureInitialFocus()
    }

    override fun onDestroy() {
        focusListener?.let { binding.root.viewTreeObserver.removeOnGlobalFocusChangeListener(it) }
        focusListener = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        if (hasFocus) restorePendingFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null && isNavKey(event.keyCode)) {
            ensureInitialFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyUiMode() {
        val tvMode = TvMode.isEnabled(this)
        val widthDp = if (tvMode) 320f else 220f
        val widthPx = dp(widthDp)
        val lp = binding.recyclerLeft.layoutParams
        if (lp.width != widthPx) {
            lp.width = widthPx
            binding.recyclerLeft.layoutParams = lp
        }
    }

    private fun ensureInitialFocus() {
        if (currentFocus != null) return
        if (restorePendingFocus()) return
        focusLeftAt(lastFocusedLeftIndex.coerceAtLeast(0))
    }

    private fun dp(valueDp: Float): Int {
        val dm = resources.displayMetrics
        return (valueDp * dm.density).toInt()
    }

    private fun isNavKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            -> true

            else -> false
        }
    }

    private fun showSection(index: Int, keepScroll: Boolean = index == currentSectionIndex, focusTitle: String? = null) {
        val lm = binding.recyclerRight.layoutManager as? LinearLayoutManager
        val firstVisible = if (keepScroll) lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION else RecyclerView.NO_POSITION
        val topOffset =
            if (keepScroll && firstVisible != RecyclerView.NO_POSITION) {
                (binding.recyclerRight.getChildAt(0)?.top ?: 0) - binding.recyclerRight.paddingTop
            } else {
                0
            }
        currentSectionIndex = index

        val prefs = BiliClient.prefs
        val entries = when (sections.getOrNull(index)) {
            "通用设置" -> listOf(
                SettingEntry("图片质量", prefs.imageQuality, "影响封面/头像 URL 追加参数"),
                SettingEntry("User-Agent", prefs.userAgent.take(60), "点击可编辑/重置（影响 CDN/接口风控）"),
                SettingEntry("风控验证", gaiaVgateStatusText(), "播放被拦截后可在此手动完成人机验证"),
                SettingEntry("清理缓存", cacheSizeText(), null),
                SettingEntry("清除登录", if (BiliClient.cookies.hasSessData()) "已登录" else "未登录", "清除 Cookie（SESSDATA 等）"),
            )

            "页面设置" -> listOf(
                SettingEntry("每行卡片数量", gridSpanText(prefs.gridSpanCount), "影响推荐/热门/分类（动态单独设置）"),
                SettingEntry("动态页每行卡片数量", gridSpanText(prefs.dynamicGridSpanCount), "默认 3"),
                SettingEntry("界面大小", sidebarSizeText(prefs.sidebarSize), "统一调整视频卡片/侧边栏/播放器（TV 以 1080P x1.50 为基准）"),
                SettingEntry("TV 模式", tvModeText(prefs.uiMode), "优化遥控器/键盘导航与焦点样式"),
                SettingEntry("以全屏模式运行", if (prefs.fullscreenEnabled) "开" else "关", "隐藏状态栏/导航栏"),
            )

            "播放设置" -> listOf(
                SettingEntry("默认画质", qnText(prefs.playerPreferredQn), "按 B 站 qn 清晰度选择（DASH 走轨道 id）"),
                SettingEntry("默认音轨", audioText(prefs.playerPreferredAudioId), "30280/30232/30216/30250/30251"),
                SettingEntry("CDN线路", cdnText(prefs.playerCdnPreference), "优先选择匹配域名的播放 URL（匹配失败回退）"),
                SettingEntry("默认播放速度", String.format(Locale.US, "%.2fx", prefs.playerSpeed), null),
                SettingEntry("自动跳到上次播放位置", if (prefs.playerAutoResumeEnabled) "开" else "关", "进入播放页后自动跳转，按返回取消"),
                SettingEntry("自动跳过片段（空降助手）", if (prefs.playerAutoSkipSegmentsEnabled) "开" else "关", "SponsorBlock/片头片尾片段，按返回取消本次片段"),
                SettingEntry("播放模式", playbackModeText(prefs.playerPlaybackMode), "播放结束后的动作（循环/下一条/退出）"),
                SettingEntry("字幕语言", subtitleLangText(prefs.subtitlePreferredLang), "自动/优先匹配"),
                SettingEntry("默认开启字幕", if (prefs.subtitleEnabledDefault) "开" else "关", "进入播放页时默认状态"),
                SettingEntry("视频编码", prefs.playerPreferredCodec, "AVC/HEVC/AV1"),
                SettingEntry("显示视频调试信息", if (prefs.playerDebugEnabled) "开" else "关", "播放器左上角调试框"),
                SettingEntry("播放结束双击返回", if (prefs.playerDoubleBackOnEnded) "开" else "关", "关=播放结束时按一次返回直接退出"),
                SettingEntry("底部常驻进度条", if (prefs.playerPersistentBottomProgressEnabled) "开" else "关", "控制栏隐藏时在底部显示进度"),
            )

            "弹幕设置" -> listOf(
                SettingEntry("弹幕开关", if (prefs.danmakuEnabled) "开" else "关", null),
                SettingEntry("弹幕透明度", String.format("%.2f", prefs.danmakuOpacity), null),
                SettingEntry("弹幕字体大小", prefs.danmakuTextSizeSp.toInt().toString(), "sp"),
                SettingEntry("弹幕占屏比", areaText(prefs.danmakuArea), "1/4、1/2、3/4、不限"),
                SettingEntry("弹幕速度", prefs.danmakuSpeed.toString(), "1~10"),
                SettingEntry("跟随B站弹幕屏蔽", if (prefs.danmakuFollowBiliShield) "开" else "关", "需要登录且 dm/web/view 可用"),
                SettingEntry("智能云屏蔽", if (prefs.danmakuAiShieldEnabled) "开" else "关", "按弹幕 weight 过滤"),
                SettingEntry("智能云屏蔽等级", aiLevelText(prefs.danmakuAiShieldLevel), "0=默认(3)，1~10 越大越严格"),
                SettingEntry("允许滚动弹幕", if (prefs.danmakuAllowScroll) "开" else "关", null),
                SettingEntry("允许顶部悬停弹幕", if (prefs.danmakuAllowTop) "开" else "关", null),
                SettingEntry("允许底部悬停弹幕", if (prefs.danmakuAllowBottom) "开" else "关", null),
                SettingEntry("允许彩色弹幕", if (prefs.danmakuAllowColor) "开" else "关", "关=仅白色"),
                SettingEntry("允许特殊弹幕", if (prefs.danmakuAllowSpecial) "开" else "关", "高级/代码等（当前仅做过滤）"),
            )

            "关于应用" -> listOf(
                SettingEntry("版本", BuildConfig.VERSION_NAME, null),
                SettingEntry("日志标签", "BLBL", "用于 Logcat 过滤"),
                SettingEntry("更新测试版本", "点击更新", "从内置直链下载 APK 并安装（限速）"),
            )

            "设备信息" -> listOf(
                SettingEntry("CPU", Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), null),
                SettingEntry("设备", "${Build.MANUFACTURER} ${Build.MODEL}", null),
                SettingEntry("系统", "Android ${Build.VERSION.RELEASE} API${Build.VERSION.SDK_INT}", null),
                SettingEntry("屏幕", screenText(), null),
                SettingEntry("RAM", ramText(), null),
            )

            else -> emptyList()
        }
        rightAdapter.submit(entries)
        if (sections.getOrNull(index) == "通用设置") updateCacheSize(force = false)
        pendingRestoreRightTitle = focusTitle
        val token = ++focusRequestToken
        binding.recyclerRight.doOnPreDraw {
            if (token != focusRequestToken) return@doOnPreDraw
            if (keepScroll && lm != null && firstVisible != RecyclerView.NO_POSITION) {
                lm.scrollToPositionWithOffset(firstVisible, topOffset)
            }
            restorePendingFocus()
        }
    }

    private fun refreshSection(focusTitle: String? = null) {
        showSection(currentSectionIndex, focusTitle = focusTitle)
    }

    private fun onEntryClicked(entry: SettingEntry) {
        val prefs = BiliClient.prefs
        pendingRestoreRightTitle = entry.title
        when (entry.title) {
            "图片质量" -> {
                val next = when (prefs.imageQuality) {
                    "small" -> "medium"
                    "medium" -> "large"
                    else -> "small"
                }
                prefs.imageQuality = next
                Toast.makeText(this, "图片质量：$next", Toast.LENGTH_SHORT).show()
                refreshSection(entry.title)
            }

            "User-Agent" -> showUserAgentDialog(currentSectionIndex, entry.title)
            "风控验证" -> showGaiaVgateDialog(currentSectionIndex, entry.title)
            "清理缓存" -> showClearCacheDialog(currentSectionIndex, entry.title)
            "清除登录" -> showClearLoginDialog(currentSectionIndex, entry.title)

            "以全屏模式运行" -> {
                prefs.fullscreenEnabled = !prefs.fullscreenEnabled
                Immersive.apply(this, prefs.fullscreenEnabled)
                Toast.makeText(this, "全屏：${if (prefs.fullscreenEnabled) "开" else "关"}", Toast.LENGTH_SHORT).show()
                refreshSection(entry.title)
            }

            "TV 模式" -> {
                val options = listOf("自动", "开", "关")
                showChoiceDialog(
                    title = "TV 模式",
                    items = options,
                    current = tvModeText(prefs.uiMode),
                ) { selected ->
                    prefs.uiMode =
                        when (selected) {
                            "开" -> blbl.cat3399.core.prefs.AppPrefs.UI_MODE_TV
                            "关" -> blbl.cat3399.core.prefs.AppPrefs.UI_MODE_NORMAL
                            else -> blbl.cat3399.core.prefs.AppPrefs.UI_MODE_AUTO
                        }
                    Toast.makeText(this, "TV 模式：$selected", Toast.LENGTH_SHORT).show()
                    recreate()
                }
            }

            "界面大小" -> {
                val options = listOf("小", "中", "大")
                showChoiceDialog(
                    title = "界面大小",
                    items = options,
                    current = sidebarSizeText(prefs.sidebarSize),
                ) { selected ->
                    prefs.sidebarSize =
                        when (selected) {
                            "小" -> blbl.cat3399.core.prefs.AppPrefs.SIDEBAR_SIZE_SMALL
                            "大" -> blbl.cat3399.core.prefs.AppPrefs.SIDEBAR_SIZE_LARGE
                            else -> blbl.cat3399.core.prefs.AppPrefs.SIDEBAR_SIZE_MEDIUM
                        }
                    Toast.makeText(this, "界面大小：$selected", Toast.LENGTH_SHORT).show()
                    refreshSection(entry.title)
                }
            }

            "每行卡片数量" -> {
                val options = listOf("自动", "1", "2", "3", "4", "5", "6")
                showChoiceDialog(
                    title = "每行卡片数量",
                    items = options,
                    current = gridSpanText(prefs.gridSpanCount),
                ) { selected ->
                    prefs.gridSpanCount = if (selected == "自动") 0 else (selected.toIntOrNull() ?: 0)
                    Toast.makeText(this, "每行卡片：${gridSpanText(prefs.gridSpanCount)}", Toast.LENGTH_SHORT).show()
                    refreshSection(entry.title)
                }
            }

            "动态页每行卡片数量" -> {
                val options = listOf("1", "2", "3", "4", "5", "6")
                showChoiceDialog(
                    title = "动态页每行卡片数量",
                    items = options,
                    current = gridSpanText(prefs.dynamicGridSpanCount),
                ) { selected ->
                    prefs.dynamicGridSpanCount = (selected.toIntOrNull() ?: 3).coerceIn(1, 6)
                    Toast.makeText(this, "动态每行：${gridSpanText(prefs.dynamicGridSpanCount)}", Toast.LENGTH_SHORT).show()
                    refreshSection(entry.title)
                }
            }

            "弹幕开关" -> {
                prefs.danmakuEnabled = !prefs.danmakuEnabled
                Toast.makeText(this, "弹幕：${if (prefs.danmakuEnabled) "开" else "关"}", Toast.LENGTH_SHORT).show()
                refreshSection(entry.title)
            }

            "默认开启字幕" -> {
                prefs.subtitleEnabledDefault = !prefs.subtitleEnabledDefault
                Toast.makeText(this, "默认字幕：${if (prefs.subtitleEnabledDefault) "开" else "关"}", Toast.LENGTH_SHORT).show()
                refreshSection(entry.title)
            }

            "弹幕透明度" -> {
                val options = listOf(1.0f, 0.8f, 0.6f, 0.4f, 0.2f)
                showChoiceDialog(
                    title = "弹幕透明度",
                    items = options.map { String.format(Locale.US, "%.2f", it) },
                    current = String.format(Locale.US, "%.2f", prefs.danmakuOpacity),
                ) { selected ->
                    prefs.danmakuOpacity = selected.toFloatOrNull()?.coerceIn(0.05f, 1.0f) ?: prefs.danmakuOpacity
                    refreshSection(entry.title)
                }
            }

            "弹幕字体大小" -> {
                val options = listOf(14, 16, 18, 20, 22, 24, 28, 32, 36, 40)
                showChoiceDialog(
                    title = "弹幕字体大小(sp)",
                    items = options.map { it.toString() },
                    current = prefs.danmakuTextSizeSp.toInt().toString(),
                ) { selected ->
                    prefs.danmakuTextSizeSp = (selected.toIntOrNull() ?: 18).toFloat().coerceIn(10f, 60f)
                    refreshSection(entry.title)
                }
            }

            "弹幕占屏比" -> {
                val options = listOf(
                    0.25f to "1/4",
                    0.50f to "1/2",
                    0.75f to "3/4",
                    1.00f to "不限",
                )
                showChoiceDialog(
                    title = "弹幕占屏比",
                    items = options.map { it.second },
                    current = areaText(prefs.danmakuArea),
                ) { selected ->
                    val value = options.firstOrNull { it.second == selected }?.first ?: 1.0f
                    prefs.danmakuArea = value
                    refreshSection(entry.title)
                }
            }

            "弹幕速度" -> {
                val options = (1..10).map { it.toString() }
                showChoiceDialog(
                    title = "弹幕速度(1~10)",
                    items = options,
                    current = prefs.danmakuSpeed.toString(),
                ) { selected ->
                    prefs.danmakuSpeed = (selected.toIntOrNull() ?: 4).coerceIn(1, 10)
                    refreshSection(entry.title)
                }
            }

            "跟随B站弹幕屏蔽" -> {
                prefs.danmakuFollowBiliShield = !prefs.danmakuFollowBiliShield
                refreshSection(entry.title)
            }

            "智能云屏蔽" -> {
                prefs.danmakuAiShieldEnabled = !prefs.danmakuAiShieldEnabled
                refreshSection(entry.title)
            }

            "智能云屏蔽等级" -> {
                val options = listOf("默认(3)") + (1..10).map { it.toString() }
                showChoiceDialog(
                    title = "智能云屏蔽等级",
                    items = options,
                    current = aiLevelText(prefs.danmakuAiShieldLevel),
                ) { selected ->
                    prefs.danmakuAiShieldLevel = if (selected.startsWith("默认")) 0 else (selected.toIntOrNull() ?: 0)
                    refreshSection(entry.title)
                }
            }

            "允许滚动弹幕" -> {
                prefs.danmakuAllowScroll = !prefs.danmakuAllowScroll
                refreshSection(entry.title)
            }

            "允许顶部悬停弹幕" -> {
                prefs.danmakuAllowTop = !prefs.danmakuAllowTop
                refreshSection(entry.title)
            }

            "允许底部悬停弹幕" -> {
                prefs.danmakuAllowBottom = !prefs.danmakuAllowBottom
                refreshSection(entry.title)
            }

            "允许彩色弹幕" -> {
                prefs.danmakuAllowColor = !prefs.danmakuAllowColor
                refreshSection(entry.title)
            }

            "允许特殊弹幕" -> {
                prefs.danmakuAllowSpecial = !prefs.danmakuAllowSpecial
                refreshSection(entry.title)
            }

            "默认画质" -> {
                val options =
                    listOf(16, 32, 64, 74, 80, 100, 112, 116, 120, 125, 126, 127, 129).map { it to qnText(it) }
                showChoiceDialog(
                    title = "默认画质",
                    items = options.map { it.second },
                    current = qnText(prefs.playerPreferredQn),
                ) { selected ->
                    val qn = options.firstOrNull { it.second == selected }?.first
                    if (qn != null) prefs.playerPreferredQn = qn
                    refreshSection(entry.title)
                }
            }

            "默认音轨" -> {
                val options = listOf(30251, 30250, 30280, 30232, 30216)
                val optionLabels = options.map { audioText(it) }
                showChoiceDialog(
                    title = "默认音轨",
                    items = optionLabels,
                    current = audioText(prefs.playerPreferredAudioId),
                ) { selected ->
                    val id = options.getOrNull(optionLabels.indexOfFirst { it == selected }.takeIf { it >= 0 } ?: -1)
                    if (id != null) prefs.playerPreferredAudioId = id
                    refreshSection(entry.title)
                }
            }

            "CDN线路" -> {
                val options = listOf(
                    blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_BILIVIDEO to "bilivideo（默认）",
                    blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_MCDN to "mcdn（部分网络更快/更慢）",
                )
                showChoiceDialog(
                    title = "CDN线路",
                    items = options.map { it.second },
                    current = cdnText(prefs.playerCdnPreference),
                ) { selected ->
                    val value = options.firstOrNull { it.second == selected }?.first
                        ?: blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_BILIVIDEO
                    prefs.playerCdnPreference = value
                    refreshSection(entry.title)
                }
            }

            "默认播放速度" -> {
                val options = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                showChoiceDialog(
                    title = "默认播放速度",
                    items = options.map { String.format(Locale.US, "%.2fx", it) },
                    current = String.format(Locale.US, "%.2fx", prefs.playerSpeed),
                ) { selected ->
                    val v = selected.removeSuffix("x").toFloatOrNull()
                    if (v != null) prefs.playerSpeed = v.coerceIn(0.25f, 3.0f)
                    refreshSection(entry.title)
                }
            }

            "自动跳到上次播放位置" -> {
                prefs.playerAutoResumeEnabled = !prefs.playerAutoResumeEnabled
                refreshSection(entry.title)
            }

            "自动跳过片段（空降助手）" -> {
                prefs.playerAutoSkipSegmentsEnabled = !prefs.playerAutoSkipSegmentsEnabled
                refreshSection(entry.title)
            }

            "播放模式" -> {
                val options = listOf("循环当前", "播放下一个", "什么都不做", "退出播放器")
                showChoiceDialog(
                    title = "播放模式（全局默认）",
                    items = options,
                    current = playbackModeText(prefs.playerPlaybackMode),
                ) { selected ->
                    prefs.playerPlaybackMode =
                        when (selected) {
                            "循环当前" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE
                            "播放下一个" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_NEXT
                            "退出播放器" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_EXIT
                            else -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_NONE
                        }
                    refreshSection(entry.title)
                }
            }

            "字幕语言" -> {
                val options = listOf(
                    "auto" to "自动",
                    "zh-Hans" to "中文(简体)",
                    "zh-Hant" to "中文(繁体)",
                    "en" to "English",
                    "ja" to "日本語",
                    "ko" to "한국어",
                )
                showChoiceDialog(
                    title = "字幕语言",
                    items = options.map { it.second },
                    current = subtitleLangText(prefs.subtitlePreferredLang),
                ) { selected ->
                    val code = options.firstOrNull { it.second == selected }?.first ?: "auto"
                    prefs.subtitlePreferredLang = code
                    refreshSection(entry.title)
                }
            }

            "视频编码" -> {
                val options = listOf("AVC", "HEVC", "AV1")
                showChoiceDialog(
                    title = "视频编码(偏好)",
                    items = options,
                    current = prefs.playerPreferredCodec,
                ) { selected ->
                    prefs.playerPreferredCodec = selected
                    refreshSection(entry.title)
                }
            }

            "显示视频调试信息" -> {
                prefs.playerDebugEnabled = !prefs.playerDebugEnabled
                refreshSection(entry.title)
            }

            "播放结束双击返回" -> {
                prefs.playerDoubleBackOnEnded = !prefs.playerDoubleBackOnEnded
                refreshSection(entry.title)
            }

            "底部常驻进度条" -> {
                prefs.playerPersistentBottomProgressEnabled = !prefs.playerPersistentBottomProgressEnabled
                refreshSection(entry.title)
            }

            "更新测试版本" -> showTestUpdateDialog()

            else -> AppLog.i("Settings", "click ${entry.title}")
        }
    }

    private fun gaiaVgateStatusText(): String {
        val now = System.currentTimeMillis()
        val tokenCookie = BiliClient.cookies.getCookie("x-bili-gaia-vtoken")
        val tokenOk = tokenCookie != null && tokenCookie.expiresAt > now
        val voucherOk = !BiliClient.prefs.gaiaVgateVVoucher.isNullOrBlank()
        return when {
            tokenOk -> "已通过"
            voucherOk -> "待验证"
            else -> "无"
        }
    }

    private fun showGaiaVgateDialog(sectionIndex: Int, focusTitle: String) {
        val prefs = BiliClient.prefs
        val now = System.currentTimeMillis()
        val tokenCookie = BiliClient.cookies.getCookie("x-bili-gaia-vtoken")
        val tokenOk = tokenCookie != null && tokenCookie.expiresAt > now
        val expiresAt = tokenCookie?.expiresAt ?: -1L

        val vVoucher = prefs.gaiaVgateVVoucher.orEmpty().trim()
        val hasVoucher = vVoucher.isNotBlank()
        val savedAt = prefs.gaiaVgateVVoucherSavedAtMs

        val msg =
            buildString {
                append("用于处理播放接口返回 v_voucher 的人机验证（极验）。")
                append("\n\n")
                append("当前票据：")
                append(if (tokenOk) "有效" else "无/已过期")
                if (tokenOk && expiresAt > 0L) {
                    append("\n")
                    append("过期时间：").append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", expiresAt))
                }
                append("\n\n")
                append("v_voucher：")
                append(if (hasVoucher) "已记录" else "暂无")
                if (hasVoucher && savedAt > 0L) {
                    append("\n")
                    append("记录时间：").append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", savedAt))
                }
            }

        MaterialAlertDialogBuilder(this)
            .setTitle("风控验证")
            .setMessage(msg)
            .setPositiveButton(if (hasVoucher) "开始验证" else "粘贴 v_voucher") { _, _ ->
                if (hasVoucher) {
                    gaiaVgateLauncher.launch(
                        Intent(this, GaiaVgateActivity::class.java)
                            .putExtra(GaiaVgateActivity.EXTRA_V_VOUCHER, vVoucher),
                    )
                } else {
                    showGaiaVgateVoucherDialog(sectionIndex, focusTitle)
                }
            }
            .setNeutralButton("编辑 v_voucher") { _, _ ->
                showGaiaVgateVoucherDialog(sectionIndex, focusTitle)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showGaiaVgateVoucherDialog(sectionIndex: Int, focusTitle: String) {
        val prefs = BiliClient.prefs
        val input = EditText(this).apply {
            setText(prefs.gaiaVgateVVoucher.orEmpty())
            setSelection(text.length)
            hint = "粘贴 v_voucher"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("编辑 v_voucher")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text?.toString().orEmpty().trim()
                prefs.gaiaVgateVVoucher = v.takeIf { it.isNotBlank() }
                prefs.gaiaVgateVVoucherSavedAtMs = if (v.isNotBlank()) System.currentTimeMillis() else -1L
                Toast.makeText(this, if (v.isNotBlank()) "已保存 v_voucher" else "已清除 v_voucher", Toast.LENGTH_SHORT).show()
                showSection(sectionIndex, focusTitle = focusTitle)
            }
            .setNeutralButton("清除") { _, _ ->
                prefs.gaiaVgateVVoucher = null
                prefs.gaiaVgateVVoucherSavedAtMs = -1L
                Toast.makeText(this, "已清除 v_voucher", Toast.LENGTH_SHORT).show()
                showSection(sectionIndex, focusTitle = focusTitle)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun upsertGaiaVtokenCookie(token: String) {
        val expiresAt = System.currentTimeMillis() + 12 * 60 * 60 * 1000L
        val cookie =
            Cookie.Builder()
                .name("x-bili-gaia-vtoken")
                .value(token)
                .domain("bilibili.com")
                .path("/")
                .expiresAt(expiresAt)
                .secure()
                .build()
        BiliClient.cookies.upsert(cookie)
    }

    private fun restorePendingFocus(): Boolean {
        if (pendingRestoreBack) {
            pendingRestoreBack = false
            binding.btnBack.requestFocus()
            return true
        }

        val title = pendingRestoreRightTitle ?: lastFocusedRightTitle
        if (!title.isNullOrBlank()) {
            if (focusRightByTitle(title)) return true
        }

        val leftIndex = pendingRestoreLeftIndex ?: lastFocusedLeftIndex
        if (focusLeftAt(leftIndex)) {
            return true
        }

        binding.btnBack.requestFocus()
        return true
    }

    private fun focusRightByTitle(title: String): Boolean {
        val pos = rightAdapter.indexOfTitle(title)
        if (pos == RecyclerView.NO_POSITION) return false
        val holder = binding.recyclerRight.findViewHolderForAdapterPosition(pos)
        if (holder?.itemView?.requestFocus() == true) return true
        return focusRightAt(pos)
    }

    private fun focusRightAt(position: Int): Boolean {
        if (position < 0 || position >= rightAdapter.itemCount) return false
        val token = ++focusRequestToken
        val layoutManager = binding.recyclerRight.layoutManager as? LinearLayoutManager
        if (layoutManager != null) {
            val first = layoutManager.findFirstVisibleItemPosition()
            val last = layoutManager.findLastVisibleItemPosition()
            if (position < first || position > last) {
                layoutManager.scrollToPositionWithOffset(position, 0)
            }
        }
        binding.recyclerRight.doOnPreDraw {
            if (token != focusRequestToken) return@doOnPreDraw
            binding.recyclerRight.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
        return true
    }

    private fun focusLeftAt(position: Int): Boolean {
        if (position < 0 || position >= leftAdapter.itemCount) return false
        val token = ++focusRequestToken
        val holder = binding.recyclerLeft.findViewHolderForAdapterPosition(position)
        if (holder?.itemView?.requestFocus() == true) return true
        binding.recyclerLeft.scrollToPosition(position)
        binding.recyclerLeft.doOnPreDraw {
            if (token != focusRequestToken) return@doOnPreDraw
            binding.recyclerLeft.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
        return true
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current == ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun showTestUpdateDialog() {
        if (testUpdateJob?.isActive == true) {
            Toast.makeText(this, "正在下载更新…", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("需要授权安装")
                .setMessage("更新需要允许“安装未知应用”。现在去设置开启吗？")
                .setPositiveButton("去设置") { _, _ ->
                    runCatching {
                        val intent =
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(Uri.parse("package:$packageName"))
                        startActivity(intent)
                    }.onFailure {
                        Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("更新测试版本")
            .setMessage(
                buildString {
                    append("将从内置直链下载 APK 并调用系统安装器覆盖安装。\n\n")
                    append("注意：需要允许“安装未知应用”。")
                },
            )
            .setPositiveButton("开始下载") { _, _ -> startTestUpdateDownload() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startTestUpdateDownload() {
        val now = System.currentTimeMillis()
        val cooldownLeftMs = TestApkUpdater.cooldownLeftMs(now)
        if (cooldownLeftMs > 0) {
            Toast.makeText(this, "操作太频繁，请稍后再试（${(cooldownLeftMs / 1000).coerceAtLeast(1)}s）", Toast.LENGTH_SHORT).show()
            return
        }

        val view = layoutInflater.inflate(blbl.cat3399.R.layout.dialog_test_update_progress, null, false)
        val tvStatus = view.findViewById<TextView>(blbl.cat3399.R.id.tv_status)
        val progress = view.findViewById<LinearProgressIndicator>(blbl.cat3399.R.id.progress)
        progress.isIndeterminate = true
        progress.max = 100
        tvStatus.text = "准备下载…"

        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle("下载更新")
                .setView(view)
                .setNegativeButton("取消") { _, _ -> testUpdateJob?.cancel() }
                .setCancelable(false)
                .show()

        TestApkUpdater.markStarted(now)
        testUpdateJob =
            lifecycleScope.launch {
                try {
                    val apkFile =
                        TestApkUpdater.downloadApkToCache(
                            context = this@SettingsActivity,
                            url = TestApkUpdater.TEST_APK_URL,
                        ) { state ->
                            runOnUiThread {
                                when (state) {
                                    is TestApkUpdater.Progress.Connecting -> {
                                        tvStatus.text = "连接中…"
                                        progress.isIndeterminate = true
                                    }

                                    is TestApkUpdater.Progress.Downloading -> {
                                        val percent = state.percent
                                        progress.isIndeterminate = percent == null
                                        if (percent != null) progress.progress = percent
                                        tvStatus.text = state.hint
                                    }
                                }
                            }
                        }

                    runOnUiThread {
                        dialog.dismiss()
                        Toast.makeText(this@SettingsActivity, "下载完成，准备安装…", Toast.LENGTH_SHORT).show()
                    }

                    TestApkUpdater.installApk(this@SettingsActivity, apkFile)
                } catch (_: CancellationException) {
                    runOnUiThread {
                        dialog.dismiss()
                        Toast.makeText(this@SettingsActivity, "已取消更新", Toast.LENGTH_SHORT).show()
                    }
                } catch (t: Throwable) {
                    AppLog.w("TestUpdate", "update failed: ${t.message}")
                    runOnUiThread {
                        dialog.dismiss()
                        Toast.makeText(this@SettingsActivity, "更新失败：${t.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
    }

    private fun showChoiceDialog(title: String, items: List<String>, current: String, onPicked: (String) -> Unit) {
        val checked = items.indexOf(current).coerceAtLeast(0)
        SingleChoiceDialog.show(
            context = this,
            title = title,
            items = items,
            checkedIndex = checked,
            negativeText = "取消",
        ) { _, label ->
            onPicked(label)
        }
    }

    private fun showUserAgentDialog(sectionIndex: Int, focusTitle: String) {
        val prefs = BiliClient.prefs
        val input = EditText(this).apply {
            setText(prefs.userAgent)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_NORMAL
            minLines = 3
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("User-Agent")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val ua = input.text?.toString().orEmpty().trim()
                if (ua.isBlank()) {
                    Toast.makeText(this, "User-Agent 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.userAgent = ua
                Toast.makeText(this, "已更新 User-Agent", Toast.LENGTH_SHORT).show()
                showSection(sectionIndex, focusTitle = focusTitle)
            }
            .setNeutralButton("重置默认") { _, _ ->
                prefs.userAgent = blbl.cat3399.core.prefs.AppPrefs.DEFAULT_UA
                Toast.makeText(this, "已重置 User-Agent", Toast.LENGTH_SHORT).show()
                showSection(sectionIndex, focusTitle = focusTitle)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearLoginDialog(sectionIndex: Int, focusTitle: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("清除登录")
            .setMessage("将清除 Cookie（SESSDATA 等），需要重新登录。确定继续吗？")
            .setPositiveButton("确定清除") { _, _ ->
                BiliClient.cookies.clearAll()
                BiliClient.prefs.webRefreshToken = null
                BiliClient.prefs.webCookieRefreshCheckedEpochDay = -1L
                BiliClient.prefs.biliTicketCheckedEpochDay = -1L
                BiliClient.prefs.gaiaVgateVVoucher = null
                BiliClient.prefs.gaiaVgateVVoucherSavedAtMs = -1L
                Toast.makeText(this, "已清除 Cookie", Toast.LENGTH_SHORT).show()
                showSection(sectionIndex, focusTitle = focusTitle)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearCacheDialog(sectionIndex: Int, focusTitle: String) {
        if (clearCacheJob?.isActive == true) {
            Toast.makeText(this, "清理中…", Toast.LENGTH_SHORT).show()
            return
        }
        if (testUpdateJob?.isActive == true) {
            Toast.makeText(this, "下载中，稍后再试", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("清理缓存")
            .setMessage("确定清理缓存？")
            .setPositiveButton("清理") { _, _ -> startClearCache(sectionIndex, focusTitle) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startClearCache(sectionIndex: Int, focusTitle: String) {
        cacheSizeJob?.cancel()
        val view = layoutInflater.inflate(blbl.cat3399.R.layout.dialog_test_update_progress, null, false)
        val tvStatus = view.findViewById<TextView>(blbl.cat3399.R.id.tv_status)
        val progress = view.findViewById<LinearProgressIndicator>(blbl.cat3399.R.id.progress)
        progress.isIndeterminate = true
        progress.max = 100
        tvStatus.text = "清理中…"

        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle("清理中")
                .setView(view)
                .setNegativeButton("取消") { _, _ -> clearCacheJob?.cancel() }
                .setCancelable(false)
                .show()

        clearCacheJob =
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val dirs = listOfNotNull(cacheDir, externalCacheDir)
                        for (dir in dirs) {
                            for (child in (dir.listFiles() ?: emptyArray())) {
                                currentCoroutineContext().ensureActive()
                                runCatching { child.deleteRecursively() }
                            }
                        }
                    }

                    dialog.dismiss()
                    Toast.makeText(this@SettingsActivity, "已清理缓存", Toast.LENGTH_SHORT).show()
                    cacheSizeBytes = 0L
                    showSection(sectionIndex, focusTitle = focusTitle)
                    updateCacheSize(force = true)
                } catch (_: CancellationException) {
                    dialog.dismiss()
                    Toast.makeText(this@SettingsActivity, "已取消", Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    AppLog.w("Settings", "clear cache failed: ${t.message}", t)
                    dialog.dismiss()
                    Toast.makeText(this@SettingsActivity, "清理失败", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun cacheSizeText(): String {
        val size = cacheSizeBytes ?: return "-"
        return formatBytes(size)
    }

    private fun updateCacheSize(force: Boolean) {
        if (!force && cacheSizeBytes != null) return
        if (cacheSizeJob?.isActive == true) return
        cacheSizeJob =
            lifecycleScope.launch {
                val size =
                    withContext(Dispatchers.IO) {
                        val dirs = listOfNotNull(cacheDir, externalCacheDir)
                        dirs.sumOf { dirChildrenSizeBytes(it) }.coerceAtLeast(0L)
                    }
                val old = cacheSizeBytes
                cacheSizeBytes = size
                if (sections.getOrNull(currentSectionIndex) == "通用设置" && old != size) {
                    showSection(currentSectionIndex, keepScroll = true)
                }
            }
    }

    private fun dirChildrenSizeBytes(dir: File): Long {
        val children = dir.listFiles() ?: return 0L
        var total = 0L
        val stack = ArrayDeque<File>(children.size)
        for (child in children) stack.add(child)
        while (stack.isNotEmpty()) {
            val file = stack.removeLast()
            if (!file.exists()) continue
            if (file.isFile) {
                total += file.length().coerceAtLeast(0L)
            } else {
                val nested = file.listFiles() ?: continue
                for (n in nested) stack.add(n)
            }
        }
        return total.coerceAtLeast(0L)
    }

    private fun audioText(id: Int): String = when (id) {
        30251 -> "Hi-Res 无损"
        30250 -> "杜比全景声"
        30280 -> "192K"
        30232 -> "132K"
        30216 -> "64K"
        else -> id.toString()
    }

    private fun subtitleLangText(code: String): String = when (code) {
        "auto" -> "自动"
        "zh-Hans" -> "中文(简体)"
        "zh-Hant" -> "中文(繁体)"
        "en" -> "English"
        "ja" -> "日本語"
        "ko" -> "한국어"
        else -> code
    }

    private fun areaText(area: Float): String = when {
        area >= 0.99f -> "不限"
        area >= 0.74f -> "3/4"
        area >= 0.49f -> "1/2"
        else -> "1/4"
    }

    private fun aiLevelText(level: Int): String = if (level == 0) "默认(3)" else level.coerceIn(1, 10).toString()

    private fun gridSpanText(span: Int): String = if (span <= 0) "自动" else span.toString()

    private fun tvModeText(mode: String): String = when (mode) {
        blbl.cat3399.core.prefs.AppPrefs.UI_MODE_TV -> "开"
        blbl.cat3399.core.prefs.AppPrefs.UI_MODE_NORMAL -> "关"
        else -> "自动"
    }

    private fun sidebarSizeText(prefValue: String): String = when (prefValue) {
        blbl.cat3399.core.prefs.AppPrefs.SIDEBAR_SIZE_SMALL -> "小"
        blbl.cat3399.core.prefs.AppPrefs.SIDEBAR_SIZE_LARGE -> "大"
        else -> "中"
    }

    private fun cdnText(code: String): String = when (code) {
        blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_MCDN -> "mcdn"
        else -> "bilivideo"
    }

    private fun screenText(): String {
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val scale = dm.density
        val refreshHz =
            runCatching {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.refreshRate
            }.getOrNull() ?: 0f

        val hzText = if (refreshHz > 0f) String.format(Locale.US, "%.0fHz", refreshHz) else "-"
        val scaleText = String.format(Locale.US, "x%.2f", scale)
        return "${width}×${height} ${hzText} ${scaleText}"
    }

    private fun ramText(): String {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return "-"
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = mi.totalMem.takeIf { it > 0 } ?: return "-"
        val avail = mi.availMem.coerceAtLeast(0)
        return "总${formatBytes(total)} 可用${formatBytes(avail)}"
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        if (b < 1024) return "${b}B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2fGB", gb)
    }

    private fun playbackModeText(code: String): String = when (code) {
        blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> "循环当前"
        blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_NEXT -> "播放下一个"
        blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_EXIT -> "退出播放器"
        else -> "什么都不做"
    }

    private fun qnText(qn: Int): String = when (qn) {
        16 -> "360P 流畅"
        32 -> "480P 清晰"
        64 -> "720P 高清"
        74 -> "720P60 高帧率"
        80 -> "1080P 高清"
        112 -> "1080P+ 高码率"
        116 -> "1080P60 高帧率"
        120 -> "4K 超清"
        127 -> "8K 超高清"
        125 -> "HDR 真彩色"
        126 -> "杜比视界"
        129 -> "HDR Vivid"
        6 -> "240P 极速"
        100 -> "智能修复"
        else -> qn.toString()
    }
}
