package blbl.cat3399.feature.settings

import android.os.Build
import android.view.KeyEvent
import android.view.View
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.databinding.ActivitySettingsBinding
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin

class SettingsRenderer(
    private val activity: SettingsActivity,
    private val binding: ActivitySettingsBinding,
    private val state: SettingsState,
    private val sections: List<String>,
    private val leftAdapter: SettingsLeftAdapter,
    private val rightAdapter: SettingsEntryAdapter,
    private val onSectionShown: (String) -> Unit,
) {
    private var focusListener: android.view.ViewTreeObserver.OnGlobalFocusChangeListener? = null
    private val deviceCodecSupportValue: String by lazy { SettingsText.hardDecoderSupportText() }

    fun installFocusListener() {
        if (focusListener != null) return
        focusListener =
            android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus == null) return@OnGlobalFocusChangeListener
                when {
                    newFocus == binding.btnBack -> {
                        state.pendingRestoreBack = false
                    }

                    FocusTreeUtils.isDescendantOf(newFocus, binding.recyclerLeft) -> {
                        val holder = binding.recyclerLeft.findContainingViewHolder(newFocus) ?: return@OnGlobalFocusChangeListener
                        val pos =
                            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                                ?: return@OnGlobalFocusChangeListener
                        state.lastFocusedLeftIndex = pos
                        if (state.pendingRestoreLeftIndex == pos) state.pendingRestoreLeftIndex = null
                    }

                    FocusTreeUtils.isDescendantOf(newFocus, binding.recyclerRight) -> {
                        val itemView = binding.recyclerRight.findContainingItemView(newFocus) ?: newFocus
                        val id = itemView.tag as? SettingId
                        if (id != null) state.lastFocusedRightId = id
                        if (state.pendingRestoreRightId == id) state.pendingRestoreRightId = null
                    }
                }
            }.also { binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(it) }
    }

    fun uninstallFocusListener() {
        focusListener?.let { binding.root.viewTreeObserver.removeOnGlobalFocusChangeListener(it) }
        focusListener = null
    }

    fun showSection(index: Int, keepScroll: Boolean = index == state.currentSectionIndex, focusId: SettingId? = null) {
        val lm = binding.recyclerRight.layoutManager as? LinearLayoutManager
        val scrollAnchor =
            if (keepScroll && lm != null) {
                val firstVisible = lm.findFirstVisibleItemPosition()
                if (firstVisible != RecyclerView.NO_POSITION) {
                    val anchorView = lm.findViewByPosition(firstVisible) ?: binding.recyclerRight.getChildAt(0)
                    if (anchorView != null) {
                        val anchorPos =
                            binding.recyclerRight.getChildAdapterPosition(anchorView).takeIf { it != RecyclerView.NO_POSITION }
                                ?: firstVisible
                        val anchorOffset = lm.getDecoratedTop(anchorView) - binding.recyclerRight.paddingTop
                        anchorPos to anchorOffset
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }

        state.currentSectionIndex = index
        if (index in 0 until leftAdapter.itemCount) {
            state.lastFocusedLeftIndex = index
            leftAdapter.setSelected(index)
        }
        val sectionName = sections.getOrNull(index)
        val entries = buildEntriesForSection(sectionName)
        rightAdapter.submit(entries)
        onSectionShown(sectionName.orEmpty())

        state.pendingRestoreRightId = focusId
        val token = ++state.focusRequestToken
        binding.recyclerRight.doOnPreDraw {
            if (token != state.focusRequestToken) return@doOnPreDraw
            if (keepScroll && lm != null) {
                scrollAnchor?.let { (position, offset) ->
                    lm.scrollToPositionWithOffset(position, offset)
                }
            }
            restorePendingFocus()
        }
    }

    fun refreshSection(focusId: SettingId? = null) {
        showSection(state.currentSectionIndex, focusId = focusId)
    }

    fun refreshAboutSectionKeepPosition() {
        if (sections.getOrNull(state.currentSectionIndex) != "关于应用") return
        showSection(state.currentSectionIndex, keepScroll = true, focusId = state.lastFocusedRightId)
    }

    fun ensureInitialFocus() {
        if (activity.currentFocus != null) return
        if (restorePendingFocus()) return
        focusLeftAt(state.lastFocusedLeftIndex.coerceAtLeast(0))
    }

    fun restorePendingFocus(): Boolean {
        if (state.pendingRestoreBack) {
            state.pendingRestoreBack = false
            binding.btnBack.requestFocus()
            return true
        }

        state.pendingRestoreRightId?.let { pendingRightId ->
            if (focusRightById(pendingRightId)) return true
            state.pendingRestoreRightId = null
        }

        val currentFocus = activity.currentFocus
        if (currentFocus?.isAttachedToWindow == true) {
            when {
                currentFocus == binding.btnBack -> return true
                FocusTreeUtils.isDescendantOf(currentFocus, binding.recyclerLeft) -> return true
                FocusTreeUtils.isDescendantOf(currentFocus, binding.recyclerRight) -> return true
            }
        }

        val rightId = state.lastFocusedRightId
        if (rightId != null) {
            if (focusRightById(rightId)) return true
        }

        val leftIndex = state.pendingRestoreLeftIndex ?: state.lastFocusedLeftIndex
        if (focusLeftAt(leftIndex)) {
            return true
        }

        binding.btnBack.requestFocus()
        return true
    }

    fun focusSectionTab(index: Int): Boolean {
        val count = leftAdapter.itemCount
        if (count <= 0) return false
        val safeIndex =
            index.takeIf { it in 0 until count }
                ?: state.lastFocusedLeftIndex.takeIf { it in 0 until count }
                ?: 0
        return focusLeftAt(safeIndex)
    }

    fun isNavKey(keyCode: Int): Boolean {
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

    private fun buildEntriesForSection(sectionName: String?): List<SettingEntry> {
        val prefs = BiliClient.prefs
        return when (sectionName) {
            "通用设置" ->
                listOf(
                    SettingEntry(SettingId.ImageQuality, "图片质量", prefs.imageQuality, null),
                    SettingEntry(SettingId.ThemePreset, "主题", SettingsText.themePresetText(prefs.themePreset), null),
                    SettingEntry(SettingId.UserAgent, "User-Agent", prefs.userAgent.take(60), null),
                    SettingEntry(SettingId.Ipv4OnlyEnabled, "是否只允许使用IPV4", if (prefs.ipv4OnlyEnabled) "开" else "关", null),
                    SettingEntry(SettingId.GaiaVgate, "风控验证", gaiaVgateStatusText(), "播放被拦截后可在此手动完成人机验证"),
                    SettingEntry(SettingId.ClearCache, "清理缓存", cacheSizeText(), null),
                    SettingEntry(SettingId.ConfigTransfer, "导出/入配置", "打开", null),
                    SettingEntry(SettingId.ClearLogin, "清除登录", if (BiliClient.cookies.hasSessData()) "已登录" else "未登录", null),
                )

            "页面设置" ->
                listOf(
                    SettingEntry(SettingId.StartupPage, "启动默认页", SettingsText.startupPageText(activity, prefs.startupPage), null),
                    SettingEntry(SettingId.GridSpanCount, "每行卡片数量", SettingsText.gridSpanText(prefs.gridSpanCount), null),
                    SettingEntry(
                        SettingId.DynamicGridSpanCount,
                        "动态页每行卡片数量",
                        SettingsText.gridSpanText(prefs.dynamicGridSpanCount),
                        null,
                    ),
                    SettingEntry(SettingId.PgcGridSpanCount, "番剧/电视剧每行卡片数量", SettingsText.gridSpanText(prefs.pgcGridSpanCount), null),
                    SettingEntry(SettingId.UiScaleFactor, "界面大小", SettingsText.uiScaleFactorText(prefs.uiScaleFactor), null),
                    SettingEntry(SettingId.FullscreenEnabled, "以全屏模式运行", if (prefs.fullscreenEnabled) "开" else "关", null),
                    SettingEntry(SettingId.TabSwitchFollowsFocus, "tab跟随焦点切换", if (prefs.tabSwitchFollowsFocus) "开" else "关", null),
                    SettingEntry(
                        SettingId.MainAutoHideSidebarOnEnterContent,
                        "进入内容区后关闭侧边栏",
                        if (prefs.mainAutoHideSidebarOnEnterContent) "开" else "关",
                        null,
                    ),
                    SettingEntry(
                        SettingId.MainBackFocusScheme,
                        "返回键焦点策略",
                        SettingsText.mainBackFocusSchemeText(prefs.mainBackFocusScheme),
                        null,
                    ),
                    SettingEntry(
                        SettingId.VideoCardLongPressAction,
                        "长按视频卡片",
                        SettingsText.videoCardLongPressActionText(prefs.videoCardLongPressAction),
                        null,
                    ),
                    SettingEntry(SettingId.CustomPageEnabled, "自定义页", if (prefs.customPageConfig.enabled) "开" else "关", null),
                    SettingEntry(
                        SettingId.CustomPageContent,
                        "自定义页内容",
                        SettingsText.customPageContentText(prefs.customPageConfig),
                        null,
                    ),
                    SettingEntry(
                        SettingId.DynamicFollowingRecentUpdateDotEnabled,
                        "动态页小红点",
                        if (prefs.dynamicFollowingRecentUpdateDotEnabled) "开" else "关",
                        null,
                    ),
                    SettingEntry(
                        SettingId.FollowingListOrder,
                        "关注列表排序",
                        SettingsText.followingListOrderText(prefs.followingListOrder),
                        null,
                    ),
                    SettingEntry(
                        SettingId.MainHomeVisibleTabs,
                        "主页显示页面",
                        SettingsText.mainHomeVisibleTabsText(activity, prefs.mainHomeVisibleTabs),
                        null,
                    ),
                    SettingEntry(
                        SettingId.MainCategoryVisibleTabs,
                        "分类页显示页面",
                        SettingsText.mainCategoryVisibleTabsText(prefs.mainCategoryVisibleTabs),
                        null,
                    ),
                    SettingEntry(
                        SettingId.MainLiveVisibleTabs,
                        "直播页显示页面",
                        SettingsText.mainLiveVisibleTabsText(prefs.mainLiveVisibleTabs),
                        null,
                    ),
                    SettingEntry(
                        SettingId.MainMyVisibleTabs,
                        "我的页显示页面",
                        SettingsText.mainMyVisibleTabsText(activity, prefs.mainMyVisibleTabs),
                        null,
                    ),
                )

            "播放设置" ->
                listOf(
                    SettingEntry(SettingId.PlayerPreferredQn, "默认画质", SettingsText.qnText(prefs.playerPreferredQn), null),
                    SettingEntry(
                        SettingId.PlayerPreferredQnPortrait,
                        "默认画质（竖屏）",
                        SettingsText.qnText(prefs.playerPreferredQnPortrait),
                        null,
                    ),
                    SettingEntry(SettingId.PlayerPreferredAudioId, "默认音轨", SettingsText.audioText(prefs.playerPreferredAudioId), null),
                    SettingEntry(SettingId.PlayerSpeed, "默认播放速度", String.format(java.util.Locale.US, "%.2fx", prefs.playerSpeed), null),
                    SettingEntry(
                        SettingId.PlayerShortSeekStepSeconds,
                        "点按快进秒数",
                        SettingsText.seekStepSecondsText(prefs.playerShortSeekStepSeconds),
                        null,
                    ),
                    SettingEntry(
                        SettingId.PlayerHoldSeekSpeed,
                        "长按快进倍率",
                        String.format(java.util.Locale.US, "%.2fx", prefs.playerHoldSeekSpeed),
                        null,
                    ),
                    SettingEntry(SettingId.PlayerHoldSeekMode, "长按快进模式", SettingsText.holdSeekModeText(prefs.playerHoldSeekMode), null),
                    SettingEntry(
                        SettingId.PlayerHoldScrubTraverseSeconds,
                        "拖完整个视频所需时间",
                        SettingsText.seekStepSecondsText(prefs.playerHoldScrubTraverseSeconds),
                        null,
                    ),
                    SettingEntry(
                        SettingId.PlayerHoldScrubFixedStepSeconds,
                        "固定时间拖动进度条间隔",
                        SettingsText.seekStepSecondsText(prefs.playerHoldScrubFixedStepSeconds),
                        null,
                    ),
                    SettingEntry(SettingId.PlayerAutoResumeEnabled, "自动跳到上次播放位置", if (prefs.playerAutoResumeEnabled) "开" else "关", null),
                    SettingEntry(
                        SettingId.PlayerAutoSkipSegmentsEnabled,
                        "自动跳过片段（空降助手）",
                        if (prefs.playerAutoSkipSegmentsEnabled) "开" else "关",
                        null,
                    ),
                    SettingEntry(
                        SettingId.PlayerAutoSkipServerBaseUrl,
                        "空降助手服务器地址",
                        prefs.playerAutoSkipServerBaseUrl,
                        null,
                    ),
                    SettingEntry(SettingId.PlayerOpenDetailBeforePlay, "播放前打开详情页", if (prefs.playerOpenDetailBeforePlay) "开" else "关", null),
                    SettingEntry(SettingId.PlayerPlaybackMode, "播放模式", SettingsText.playbackModeText(prefs.playerPlaybackMode), null),
                    SettingEntry(
                        SettingId.PlayerSettingsApplyToGlobal,
                        "播放器设置应用到全局",
                        if (prefs.playerSettingsApplyToGlobal) "开" else "关",
                        null,
                    ),
                    SettingEntry(SettingId.SubtitlePreferredLang, "字幕语言", SettingsText.subtitleLangText(prefs.subtitlePreferredLang), null),
                    SettingEntry(SettingId.SubtitleTextSizeSp, "字幕字体大小", prefs.subtitleTextSizeSp.toInt().toString(), null),
                    SettingEntry(
                        SettingId.SubtitleBottomPaddingFraction,
                        "字幕底部间距",
                        SettingsText.subtitleBottomPaddingText(prefs.subtitleBottomPaddingFraction),
                        null,
                    ),
                    SettingEntry(
                        SettingId.SubtitleBackgroundOpacity,
                        "字幕背景透明度",
                        SettingsText.subtitleBackgroundOpacityText(prefs.subtitleBackgroundOpacity),
                        null,
                    ),
                    SettingEntry(SettingId.SubtitleEnabledDefault, "默认开启字幕", if (prefs.subtitleEnabledDefault) "开" else "关", null),
                    SettingEntry(SettingId.PlayerPreferredCodec, "视频编码", prefs.playerPreferredCodec, null),
                    SettingEntry(SettingId.PlayerOsdButtons, "OSD按钮显示", SettingsText.playerOsdButtonsText(prefs.playerOsdButtons), null),
                    SettingEntry(SettingId.PlayerUpQuickCardEnabled, "UP关注卡片", if (prefs.playerUpQuickCardEnabled) "开" else "关", null),
                    SettingEntry(SettingId.PlayerDoubleBackToExit, "按两次退出键才退出播放器", if (prefs.playerDoubleBackToExit) "开" else "关", null),
                    SettingEntry(
                        SettingId.PlayerDownKeyOsdFocusTarget,
                        "下键呼出OSD后焦点",
                        SettingsText.downKeyOsdFocusTargetText(prefs.playerDownKeyOsdFocusTarget),
                        null,
                    ),
                    SettingEntry(
                        SettingId.PlayerTogglePlayStateShowOsd,
                        "全屏下切换播放状态是否弹出OSD",
                        if (prefs.playerTogglePlayStateShowOsd) "开" else "关",
                        null,
                    ),
                    SettingEntry(
                        SettingId.PlayerPersistentBottomProgressEnabled,
                        "底部常驻进度条",
                        if (prefs.playerPersistentBottomProgressEnabled) "开" else "关",
                        null,
                    ),
                    SettingEntry(
                        SettingId.PlayerPersistentClockEnabled,
                        "常驻时间显示",
                        if (prefs.playerPersistentClockEnabled) "开" else "关",
                        null,
                    ),
                    SettingEntry(
                        SettingId.PlayerTouchGesturesEnabled,
                        "触摸手势",
                        if (prefs.playerTouchGesturesEnabled) "开" else "关",
                        null,
                    ),
                    SettingEntry(
                        SettingId.PlayerVideoShotPreviewSize,
                        "缩略图显示",
                        SettingsText.videoShotPreviewSizeText(prefs.playerVideoShotPreviewSize),
                        null,
                    ),
                )

            "其他设置" ->
                listOf(
                    SettingEntry(SettingId.PlayerRenderView, "渲染视图", SettingsText.renderViewText(prefs.playerRenderViewType), null),
                    SettingEntry(SettingId.PlayerEngineKind, "播放器内核", SettingsText.playerEngineText(prefs.playerEngineKind), null),
                    SettingEntry(
                        SettingId.PlayerCustomShortcuts,
                        "自定义播放快捷键",
                        prefs.playerCustomShortcuts.let { if (it.isEmpty()) "未设置" else "已设置 ${it.size} 个" },
                        "播放时按指定按键执行动作或切换播放设置（再按一次切回上次值）",
                    ),
                    SettingEntry(
                        SettingId.PlayerAudioBalance,
                        "音频平衡",
                        AudioBalanceLevel.fromPrefValue(prefs.playerAudioBalanceLevel).label,
                        null,
                    ),
                    SettingEntry(SettingId.PlayerCdnPreference, "CDN线路", SettingsText.cdnText(prefs.playerCdnPreference), null),
                    SettingEntry(
                        SettingId.LiveHighBitrateEnabled,
                        "提高直播码率",
                        if (prefs.liveHighBitrateEnabled) "开" else "关",
                        "如果直播遇到问题,请关闭此功能",
                    ),
                    SettingEntry(SettingId.PlayerDebugEnabled, "显示视频调试信息", if (prefs.playerDebugEnabled) "开" else "关", null),
                )

            "弹幕设置" ->
                listOf(
                    SettingEntry(SettingId.DanmakuEnabled, "弹幕开关", if (prefs.danmakuEnabled) "开" else "关", null),
                    SettingEntry(
                        SettingId.DanmakuOpacity,
                        "弹幕透明度",
                        String.format(java.util.Locale.US, "%.2f", prefs.danmakuOpacity),
                        null,
                    ),
                    SettingEntry(SettingId.DanmakuTextSizeSp, "弹幕字体大小", prefs.danmakuTextSizeSp.toInt().toString(), null),
                    SettingEntry(SettingId.DanmakuFontWeight, "字体粗细", SettingsText.danmakuFontWeightText(prefs.danmakuFontWeight), null),
                    SettingEntry(SettingId.DanmakuStrokeWidthPx, "弹幕文字描边粗细", prefs.danmakuStrokeWidthPx.toString(), null),
                    SettingEntry(SettingId.DanmakuArea, "弹幕占屏比", SettingsText.areaText(prefs.danmakuArea), null),
                    SettingEntry(SettingId.DanmakuLaneDensity, "轨道密度", SettingsText.danmakuLaneDensityText(prefs.danmakuLaneDensity), null),
                    SettingEntry(SettingId.DanmakuSpeed, "弹幕速度", prefs.danmakuSpeed.toString(), null),
                    SettingEntry(SettingId.DanmakuFollowBiliShield, "跟随B站弹幕屏蔽", if (prefs.danmakuFollowBiliShield) "开" else "关", null),
                    SettingEntry(SettingId.DanmakuShowHighLikeIcon, "显示高赞弹幕图标", if (prefs.danmakuShowHighLikeIcon) "开" else "关", null),
                    SettingEntry(SettingId.DanmakuAiShieldEnabled, "智能云屏蔽", if (prefs.danmakuAiShieldEnabled) "开" else "关", null),
                    SettingEntry(SettingId.DanmakuAiShieldLevel, "智能云屏蔽等级", SettingsText.aiLevelText(prefs.danmakuAiShieldLevel), null),
                    SettingEntry(SettingId.DanmakuAllowScroll, "允许滚动弹幕", if (prefs.danmakuAllowScroll) "开" else "关", null),
                    SettingEntry(SettingId.DanmakuAllowTop, "允许顶部悬停弹幕", if (prefs.danmakuAllowTop) "开" else "关", null),
                    SettingEntry(SettingId.DanmakuAllowBottom, "允许底部悬停弹幕", if (prefs.danmakuAllowBottom) "开" else "关", null),
                    SettingEntry(SettingId.DanmakuAllowColor, "允许彩色弹幕", if (prefs.danmakuAllowColor) "开" else "关", null),
                    SettingEntry(SettingId.DanmakuAllowSpecial, "允许特殊弹幕", if (prefs.danmakuAllowSpecial) "开" else "关", null),
                )

            "关于应用" ->
                listOf(
                    SettingEntry(SettingId.AppVersion, "版本", BuildConfig.VERSION_NAME, null),
                    SettingEntry(SettingId.ProjectUrl, "项目地址", SettingsConstants.PROJECT_URL, null),
                    SettingEntry(SettingId.QqGroup, "QQ交流群", SettingsConstants.QQ_GROUP, null),
                    SettingEntry(SettingId.LogTag, "日志标签", "BLBL", "用于 Logcat 过滤"),
                    SettingEntry(SettingId.ExportLogs, "导出日志", "保存文件", null),
                    SettingEntry(SettingId.UploadLogs, "上传日志", "点击上传", "打包并上传日志zip到开发者（含设备/版本/非登录配置元数据）"),
                    playerKernelEntry(),
                    SettingEntry(
                        SettingId.AutoUpdateCheckEnabled,
                        "自动检查更新",
                        if (prefs.autoUpdateCheckEnabled) "开" else "关",
                        "启动时后台检查，有新版本才提示",
                    ),
                    aboutUpdateEntry(),
                )

            "设备信息" ->
                listOf(
                    SettingEntry(SettingId.DeviceCpu, "CPU", Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), null),
                    SettingEntry(SettingId.DeviceModel, "设备", "${Build.MANUFACTURER} ${Build.MODEL}", null),
                    SettingEntry(SettingId.DeviceSystem, "系统", "Android ${Build.VERSION.RELEASE} API${Build.VERSION.SDK_INT}", null),
                    SettingEntry(SettingId.DeviceScreen, "屏幕", SettingsText.screenText(activity.resources), null),
                    SettingEntry(SettingId.DeviceRam, "RAM", SettingsText.ramText(activity), null),
                    SettingEntry(SettingId.DeviceDecoder, "硬件解码器", deviceCodecSupportValue, null),
                )

            else -> emptyList()
        }
    }

    private fun playerKernelEntry(): SettingEntry {
        return when (IjkPlayerPlugin.status(activity)) {
            IjkPlayerPlugin.InstallStatus.Unsupported ->
                SettingEntry(SettingId.PlayerKernelCheck, "播放器内核检测", "不支持", null)

            IjkPlayerPlugin.InstallStatus.NotInstalled ->
                SettingEntry(SettingId.PlayerKernelCheck, "播放器内核检测", "未安装", null)

            IjkPlayerPlugin.InstallStatus.NeedsUpdate ->
                SettingEntry(SettingId.PlayerKernelCheck, "播放器内核检测", "需要更新", null)

            IjkPlayerPlugin.InstallStatus.Installed ->
                SettingEntry(SettingId.PlayerKernelCheck, "播放器内核检测", "已就绪", null)
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

    private fun cacheSizeText(): String {
        val size = state.cacheSizeBytes ?: return "-"
        return SettingsText.formatBytes(size)
    }

    private fun aboutUpdateEntry(): SettingEntry {
        val currentVersion = BuildConfig.VERSION_NAME
        val title = "检查更新"
        val defaultDesc = "检查新版本并下载安装"
        return when (val checkState = state.testUpdateCheckState) {
            TestUpdateCheckState.Idle -> SettingEntry(SettingId.CheckUpdate, title, "点击检查", defaultDesc)
            TestUpdateCheckState.Checking -> SettingEntry(SettingId.CheckUpdate, title, "检查中…", "正在获取更新日志…")

            is TestUpdateCheckState.Latest ->
                SettingEntry(
                    SettingId.CheckUpdate,
                    title,
                    "已是最新版",
                    "当前：$currentVersion / 最新：${checkState.latestVersion}",
                )

            is TestUpdateCheckState.UpdateAvailable ->
                SettingEntry(SettingId.CheckUpdate, title, "新版本 ${checkState.latestVersion}", "当前：$currentVersion，点击更新")

            is TestUpdateCheckState.Error -> {
                val msg = checkState.message.trim().take(80)
                val desc = if (msg.isBlank()) "检查失败，点击重试" else "检查失败，点击重试（$msg）"
                SettingEntry(SettingId.CheckUpdate, title, "检查失败", desc)
            }
        }
    }

    private fun focusRightById(id: SettingId): Boolean {
        val pos = rightAdapter.indexOfId(id)
        if (pos == RecyclerView.NO_POSITION) return false
        val holder = binding.recyclerRight.findViewHolderForAdapterPosition(pos)
        if (holder?.itemView?.requestFocus() == true) return true
        return focusRightAt(pos)
    }

    private fun focusRightAt(position: Int): Boolean {
        if (position < 0 || position >= rightAdapter.itemCount) return false
        val layoutManager = binding.recyclerRight.layoutManager as? LinearLayoutManager
        return focusRecyclerItemAt(
            recyclerView = binding.recyclerRight,
            position = position,
            shouldScroll = { isPositionOutsideVisibleRange(layoutManager, position) },
            scroll = { layoutManager?.scrollToPositionWithOffset(position, 0) },
        )
    }

    private fun focusLeftAt(position: Int): Boolean {
        if (position < 0 || position >= leftAdapter.itemCount) return false
        val layoutManager = binding.recyclerLeft.layoutManager as? LinearLayoutManager
        return focusRecyclerItemAt(
            recyclerView = binding.recyclerLeft,
            position = position,
            shouldScroll = { isPositionOutsideVisibleRange(layoutManager, position) },
            scroll = { binding.recyclerLeft.scrollToPosition(position) },
        )
    }

    private fun focusRecyclerItemAt(
        recyclerView: RecyclerView,
        position: Int,
        shouldScroll: () -> Boolean,
        scroll: () -> Unit,
    ): Boolean {
        val token = ++state.focusRequestToken
        val holder = recyclerView.findViewHolderForAdapterPosition(position)
        if (holder?.itemView?.requestFocus() == true) return true
        if (shouldScroll()) {
            scroll()
        }
        recyclerView.doOnPreDraw {
            if (token != state.focusRequestToken) return@doOnPreDraw
            recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
        return true
    }

    private fun isPositionOutsideVisibleRange(layoutManager: LinearLayoutManager?, position: Int): Boolean {
        if (layoutManager == null) return true
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return true
        return position < first || position > last
    }
}
