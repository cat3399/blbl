package blbl.cat3399.feature.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.io.CreateDocumentRequest
import blbl.cat3399.core.io.DocumentExporter
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.log.LogExporter
import blbl.cat3399.core.log.LogUploadClient
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppConfigBackup
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.CustomPageConfig
import blbl.cat3399.core.prefs.CustomPageTabConfig
import blbl.cat3399.core.prefs.PlayerCustomShortcut
import blbl.cat3399.core.prefs.PlayerCustomShortcutAction
import blbl.cat3399.core.prefs.PlayerPlaybackModes
import blbl.cat3399.core.prefs.PlayerCustomShortcutsStore
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupAction
import blbl.cat3399.core.ui.popup.PopupActionRole
import blbl.cat3399.core.update.ApkUpdater
import blbl.cat3399.feature.player.engine.IjkPlayerPlugin
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.PlaybackSettingChoices
import blbl.cat3399.feature.player.PlayerCustomShortcutCatalog
import blbl.cat3399.feature.risk.GaiaVgateActivity
import blbl.cat3399.feature.category.CategoryZones
import blbl.cat3399.feature.custom.CustomPageTabRegistry
import blbl.cat3399.feature.home.HomeTabs
import blbl.cat3399.feature.live.LiveFragment
import blbl.cat3399.feature.my.MyTabs
import blbl.cat3399.ui.MainRootNavRegistry
import blbl.cat3399.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class SettingsInteractionHandler(
    private val activity: SettingsActivity,
    private val state: SettingsState,
    private val gaiaVgateLauncher: ActivityResultLauncher<Intent>,
    private val exportDocumentLauncher: ActivityResultLauncher<CreateDocumentRequest>,
    private val importConfigLauncher: ActivityResultLauncher<Array<String>>,
) {
    lateinit var renderer: SettingsRenderer

    private data class PreparedLogsExport(
        val fileName: String,
        val nowMs: Long,
        val extras: List<LogExporter.ZipExtra>,
    )

    private var testUpdateJob: Job? = null
    private var testUpdateCheckJob: Job? = null
    private var exportLogsJob: Job? = null
    private var uploadLogsJob: Job? = null
    private var clearCacheJob: Job? = null
    private var cacheSizeJob: Job? = null
    private var configTransferJob: Job? = null
    private var pendingDocumentExport: ((Uri) -> Unit)? = null

    fun onSectionShown(sectionName: String) {
        when (sectionName) {
            "通用设置" -> updateCacheSize(force = false)
            "关于应用" -> ensureTestUpdateChecked(force = false, refreshUi = false)
        }
    }

    fun onGaiaVgateResult(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) return
        val token =
            result.data?.getStringExtra(GaiaVgateActivity.EXTRA_GAIA_VTOKEN)?.trim()?.takeIf { it.isNotBlank() }
                ?: return
        upsertGaiaVtokenCookie(token)
        val prefs = BiliClient.prefs
        prefs.gaiaVgateVVoucher = null
        prefs.gaiaVgateVVoucherSavedAtMs = -1L
        AppToast.show(activity, "验证成功，已写入风控票据")
        renderer.refreshSection(SettingId.GaiaVgate)
    }

    fun onExportDocumentSelected(uri: Uri?) {
        val export = pendingDocumentExport
        pendingDocumentExport = null
        if (uri == null || export == null) return
        export(uri)
    }

    fun onImportConfigSelected(uri: Uri?) {
        if (uri == null) return
        importConfigFromUri(uri)
    }

    private fun exportLogsToUri(
        uri: Uri,
        prepared: PreparedLogsExport,
    ) {
        exportLogsJob?.cancel()
        exportLogsJob =
            launchIoJob(
                startToast = "正在导出日志…",
                failureLogMessage = "export logs failed",
                failureToastPrefix = "导出失败",
                work = {
                    LogExporter.exportToUri(
                        context = activity,
                        uri = uri,
                        nowMs = prepared.nowMs,
                        fileNameOverride = prepared.fileName,
                        extras = prepared.extras,
                    )
                },
            ) { result ->
                AppToast.showLong(activity, "已导出：${result.fileName}（${result.includedFiles}个文件）")
            }
    }

    private fun exportLogsToLocalFile(prepared: PreparedLogsExport) {
        exportLogsJob?.cancel()
        exportLogsJob =
            launchIoJob(
                startToast = "正在导出日志到本地…",
                failureLogMessage = "export logs (local) failed",
                failureToastPrefix = "导出失败",
                work = {
                    LogExporter.exportToLocalFile(
                        context = activity,
                        nowMs = prepared.nowMs,
                        fileNameOverride = prepared.fileName,
                        extras = prepared.extras,
                    )
                },
            ) { result ->
                val path = result.file.absolutePath
                AppToast.showLong(activity, "无法打开保存文件，已导出到本地：${result.fileName}（${result.includedFiles}个文件）\n路径：$path")
            }
    }

    private fun showConfigTransferDialog() {
        AppPopup.custom(
            context = activity,
            title = "导出/入配置",
            cancelable = true,
            actions =
                listOf(
                    PopupAction(
                        role = PopupActionRole.NEUTRAL,
                        text = "导出配置",
                    ) {
                        startConfigExport(AppConfigBackup.ExportMode.CONFIG_ONLY)
                    },
                    PopupAction(
                        role = PopupActionRole.NEUTRAL,
                        text = "导出配置与登录状态",
                    ) {
                        startConfigExport(AppConfigBackup.ExportMode.CONFIG_WITH_CREDENTIALS)
                    },
                    PopupAction(role = PopupActionRole.NEGATIVE, text = "关闭"),
                    PopupAction(
                        role = PopupActionRole.POSITIVE,
                        text = "导入配置",
                    ) {
                        startImportConfig()
                    },
                ),
            preferredActionRole = PopupActionRole.POSITIVE,
            autoFocus = true,
        ) { dialogContext ->
            val tv =
                LayoutInflater.from(dialogContext)
                    .inflate(R.layout.view_popup_message, null, false) as TextView
            tv.text = "可导出当前配置，也可选包含当前登录状态；导入时会按文件内容整包覆盖。"
            tv
        }
    }

    private fun startConfigExport(mode: AppConfigBackup.ExportMode) {
        if (!ensureConfigTransferIdle()) return
        val prepared = prepareConfigExport(mode)
        launchDocumentExport(
            request = CreateDocumentRequest(mimeType = AppConfigBackup.JSON_MIME, fileName = prepared.fileName),
            onUriSelected = { uri -> exportConfigToUri(uri = uri, prepared = prepared) },
            onFallbackToLocal = { exportConfigToLocalFile(prepared) },
            logTag = "config",
        )
    }

    private fun exportConfigToUri(
        uri: Uri,
        prepared: AppConfigBackup.PreparedExport,
    ) {
        configTransferJob =
            launchIoJob(
                startToast = exportStartToast(prepared.mode),
                failureLogMessage = "export config failed",
                failureToastPrefix = "导出失败",
                work = {
                    DocumentExporter.exportToUri(
                        context = activity,
                        uri = uri,
                        fileName = prepared.fileName,
                    ) { out ->
                        out.write(prepared.jsonText.toByteArray(Charsets.UTF_8))
                    }
                },
            ) { result ->
                AppToast.showLong(activity, "已导出：${result.fileName}")
            }
    }

    private fun exportConfigToLocalFile(prepared: AppConfigBackup.PreparedExport) {
        configTransferJob =
            launchIoJob(
                startToast = "${exportStartToast(prepared.mode)}到本地…",
                failureLogMessage = "export config (local) failed",
                failureToastPrefix = "导出失败",
                work = {
                    DocumentExporter.exportToLocalFile(
                        context = activity,
                        fileName = prepared.fileName,
                        subDir = "exports",
                    ) { out ->
                        out.write(prepared.jsonText.toByteArray(Charsets.UTF_8))
                    }
                },
            ) { result ->
                AppToast.showLong(activity, "无法打开保存文件，已导出到本地：${result.fileName}\n路径：${result.file.absolutePath}")
            }
    }

    private fun startImportConfig() {
        if (!ensureConfigTransferIdle()) return
        try {
            importConfigLauncher.launch(arrayOf(AppConfigBackup.JSON_MIME, "text/plain", "application/octet-stream"))
        } catch (e: ActivityNotFoundException) {
            AppLog.w("Settings", "OpenDocument not supported", e)
            AppToast.showLong(activity, "当前设备不支持导入配置")
        } catch (t: Throwable) {
            AppLog.w("Settings", "open import config picker failed", t)
            AppToast.showLong(activity, "打开导入文件失败")
        }
    }

    private fun importConfigFromUri(uri: Uri) {
        if (!ensureConfigTransferIdle()) return
        configTransferJob =
            launchIoJob(
                startToast = "正在读取配置…",
                failureLogMessage = "import config read failed",
                failureToastPrefix = "读取配置失败",
                work = {
                    val text =
                        activity.contentResolver.openInputStream(uri)?.use { input ->
                            input.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")
                        } ?: error("无法读取配置文件")
                    AppConfigBackup.parse(text)
                },
            ) { parsed ->
                showImportConfigConfirmDialog(parsed)
            }
    }

    private fun showImportConfigConfirmDialog(parsed: AppConfigBackup.ParsedBackup) {
        val message =
            if (parsed.includesCredentials) {
                "该文件包含登录状态部分。\n导入后将覆盖当前配置和登录状态，并重启应用。"
            } else {
                "该文件仅包含配置部分。\n导入后只覆盖当前配置，保留当前登录状态，并重启应用。"
            }
        AppPopup.confirm(
            context = activity,
            title = "导入配置",
            message = message,
            positiveText = "开始导入",
            negativeText = "取消",
            cancelable = true,
            onPositive = {
                applyImportedConfig(parsed)
            },
        )
    }

    private fun applyImportedConfig(parsed: AppConfigBackup.ParsedBackup) {
        if (!ensureConfigTransferIdle()) return
        configTransferJob =
            launchIoJob(
                startToast = if (parsed.includesCredentials) "正在导入配置与登录状态…" else "正在导入配置…",
                failureLogMessage = "apply config failed",
                failureToastPrefix = "导入失败",
                work = {
                    AppConfigBackup.apply(parsed, prefs = BiliClient.prefs, cookies = BiliClient.cookies)
                },
            ) {
                evictNetworkConnections()
                AppToast.showLong(activity, if (parsed.includesCredentials) "已导入配置与登录状态，正在重启…" else "已导入配置，正在重启…")
                restartToMain()
            }
    }

    private fun prepareConfigExport(mode: AppConfigBackup.ExportMode): AppConfigBackup.PreparedExport {
        return AppConfigBackup.prepareExport(
            prefs = BiliClient.prefs,
            cookies = BiliClient.cookies,
            mode = mode,
        )
    }

    private fun exportStartToast(mode: AppConfigBackup.ExportMode): String {
        return if (mode == AppConfigBackup.ExportMode.CONFIG_WITH_CREDENTIALS) {
            "正在导出配置与登录状态…"
        } else {
            "正在导出配置…"
        }
    }

    private fun showUploadLogsDialog() {
        if (uploadLogsJob?.isActive == true) {
            AppToast.show(activity, "正在上传…")
            return
        }

        AppPopup.confirm(
            context = activity,
            title = "上传日志",
            message =
                "将日志上传给开发者便于排查问题。\n\n" +
                    "会随日志附带设备、版本、屏幕和非登录配置元数据，不包含登录 Cookie。\n\n" +
                    "反馈问题时请带上上传成功后显示的文件名。",
            positiveText = "上传",
            negativeText = "取消",
            cancelable = true,
            onPositive = { startUploadLogs() },
        )
    }

    private fun startUploadLogs() {
        uploadLogsJob?.cancel()
        val popup =
            AppPopup.progress(
                context = activity,
                title = "上传日志",
                status = "准备中…",
                negativeText = "取消",
                cancelable = false,
                onNegative = { uploadLogsJob?.cancel() },
            )

        uploadLogsJob =
            activity.lifecycleScope.launch {
                var exportedFile: File? = null
                try {
                    val nowMs = System.currentTimeMillis()
                    val deviceUuid = BiliClient.prefs.deviceUuid
                    val epochSeconds = (nowMs / 1000L).coerceAtLeast(0L)
                    val deviceId8 = deviceUuid.replace("-", "").take(8).ifBlank { "unknown00" }
                    val fileName = "${epochSeconds}-${deviceId8}.zip"
                    val metaJson = buildUploadMetaJson(nowMs = nowMs, deviceUuid = deviceUuid)

                    popup?.updateProgress(null)
                    popup?.updateStatus("打包中…")
                    val export =
                        withContext(Dispatchers.IO) {
                            LogExporter.exportToLocalFile(
                                context = activity,
                                nowMs = nowMs,
                                fileNameOverride = fileName,
                                extras =
                                    listOf(
                                        LogExporter.ZipExtra(
                                            path = "meta.json",
                                            bytes = metaJson.toByteArray(Charsets.UTF_8),
                                        ),
                                    ),
                            )
                        }
                    exportedFile = export.file

                    currentCoroutineContext().ensureActive()
                    popup?.updateProgress(0)
                    popup?.updateStatus("上传中… 0%")
                    var lastPct = -1
                    var lastUpdateAtMs = 0L
                    withContext(Dispatchers.IO) {
                        LogUploadClient.uploadZip(
                            file = export.file,
                            fileName = export.fileName,
                            onProgress = { sentBytes, totalBytes ->
                                if (totalBytes <= 0L) return@uploadZip
                                val pct = ((sentBytes.coerceAtLeast(0L) * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                val now = System.currentTimeMillis()
                                if (pct == lastPct && now - lastUpdateAtMs < 80L) return@uploadZip
                                lastPct = pct
                                lastUpdateAtMs = now
                                val hint = "${SettingsText.formatBytes(sentBytes)}/${SettingsText.formatBytes(totalBytes)}"
                                popup?.updateProgress(pct)
                                popup?.updateStatus("上传中… ${pct}% $hint")
                            },
                        )
                    }

                    popup?.dismiss()
                    showUploadLogsSuccessPopup(
                        fileName = export.fileName,
                    )
                } catch (_: CancellationException) {
                    popup?.dismiss()
                } catch (t: Throwable) {
                    popup?.dismiss()
                    AppLog.w("Settings", "upload logs failed", t)
                    val msg = t.message?.takeIf { it.isNotBlank() } ?: "未知错误"
                    AppToast.showLong(activity, "上传失败：$msg")
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        exportedFile?.let { runCatching { it.delete() } }
                    }
                }
            }
    }

    private fun showUploadLogsSuccessPopup(
        fileName: String,
    ) {
        val body = "文件：$fileName"

        AppPopup.custom(
            context = activity,
            title = "上传成功",
            cancelable = true,
            actions =
                listOf(
                    PopupAction(role = PopupActionRole.NEGATIVE, text = "关闭"),
                    PopupAction(role = PopupActionRole.NEUTRAL, text = "复制文件名") {
                        copyToClipboard(label = "日志文件名", text = fileName, toastText = "已复制文件名")
                    },
                ),
            preferredActionRole = PopupActionRole.NEUTRAL,
            content = { dialogContext ->
                val tv =
                    android.view.LayoutInflater.from(dialogContext)
                        .inflate(blbl.cat3399.R.layout.view_popup_message, null, false) as TextView
                tv.text = body
                tv
            },
        )
    }

    private fun formatUploadTimestamp(nowMs: Long): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        return runCatching { sdf.format(Date(nowMs)) }.getOrNull()?.takeIf { it.isNotBlank() } ?: nowMs.toString()
    }

    private fun buildUploadMetaJson(
        nowMs: Long,
        deviceUuid: String,
    ): String {
        val tzId = runCatching { java.util.TimeZone.getDefault().id }.getOrNull().orEmpty()
        val locale = runCatching { Locale.getDefault() }.getOrNull()
        val localeTag = runCatching { locale?.toLanguageTag() }.getOrNull().orEmpty()
        val prefs = BiliClient.prefs

        val json =
            JSONObject()
                .put("schema", 1)
                .put("device_uuid", deviceUuid)
                .put("export_at_ms", nowMs)
                .put("export_at", formatUploadTimestamp(nowMs))
                .put("time_zone", tzId)
                .put("locale", localeTag)
                .put(
                    "app",
                    JSONObject()
                        .put("package", BuildConfig.APPLICATION_ID)
                        .put("version_name", BuildConfig.VERSION_NAME)
                        .put("version_code", BuildConfig.VERSION_CODE)
                        .put("build_type", BuildConfig.BUILD_TYPE)
                        .put("debug", BuildConfig.DEBUG),
                )
                .put(
                    "device",
                    JSONObject()
                        .put("manufacturer", Build.MANUFACTURER)
                        .put("model", Build.MODEL)
                        .put("sdk_int", Build.VERSION.SDK_INT)
                        .put("release", Build.VERSION.RELEASE)
                        .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                        .put("ram", SettingsText.ramText(activity))
                        .put("hardware_decoder", SettingsText.hardDecoderSupportText()),
                )
                .put(
                    "account",
                    JSONObject()
                        .put("is_logged_in", BiliClient.cookies.hasSessData()),
                )
                .put("screen", buildUploadScreenJson())
                .put("prefs_snapshot", prefs.exportDiagnosticsSnapshotJson())

        return json.toString(2)
    }

    private fun buildUploadScreenJson(): JSONObject {
        val res = activity.resources
        val dm = res.displayMetrics
        val cfg = res.configuration

        val orientation =
            when (cfg.orientation) {
                Configuration.ORIENTATION_PORTRAIT -> "portrait"
                Configuration.ORIENTATION_LANDSCAPE -> "landscape"
                else -> "undefined"
            }
        val nightMode =
            when (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES -> "yes"
                Configuration.UI_MODE_NIGHT_NO -> "no"
                else -> "undefined"
            }

        val sysDm = android.content.res.Resources.getSystem().displayMetrics
        val scaledDensity = dm.density * cfg.fontScale
        val systemScaledDensity = sysDm.density * cfg.fontScale

        return JSONObject()
            .put("width_px", dm.widthPixels)
            .put("height_px", dm.heightPixels)
            .put("density", dm.density)
            .put("scaled_density", scaledDensity)
            .put("density_dpi", dm.densityDpi)
            .put("xdpi", dm.xdpi)
            .put("ydpi", dm.ydpi)
            .put("font_scale", cfg.fontScale)
            .put("screen_width_dp", cfg.screenWidthDp)
            .put("screen_height_dp", cfg.screenHeightDp)
            .put("smallest_screen_width_dp", cfg.smallestScreenWidthDp)
            .put("orientation", orientation)
            .put("night_mode", nightMode)
            .put(
                "system_display_scale",
                JSONObject()
                    .put("density", sysDm.density)
                    .put("scaled_density", systemScaledDensity)
                    .put("density_dpi", sysDm.densityDpi),
            )
    }

    private fun prepareLogsExport(nowMs: Long = System.currentTimeMillis()): PreparedLogsExport {
        val deviceUuid = BiliClient.prefs.deviceUuid
        val metaJson = buildUploadMetaJson(nowMs = nowMs, deviceUuid = deviceUuid)
        return PreparedLogsExport(
            fileName = LogExporter.suggestExportFileName(nowMs = nowMs),
            nowMs = nowMs,
            extras = buildLogExportExtras(metaJson),
        )
    }

    private fun launchDocumentExport(
        request: CreateDocumentRequest,
        onUriSelected: (Uri) -> Unit,
        onFallbackToLocal: () -> Unit,
        logTag: String,
    ) {
        pendingDocumentExport = onUriSelected
        try {
            exportDocumentLauncher.launch(request)
        } catch (e: ActivityNotFoundException) {
            pendingDocumentExport = null
            AppLog.w("Settings", "CreateDocument not supported; fallback to local $logTag export", e)
            onFallbackToLocal()
        } catch (t: Throwable) {
            pendingDocumentExport = null
            AppLog.w("Settings", "open $logTag export picker failed; fallback to local export", t)
            onFallbackToLocal()
        }
    }

    private fun ensureConfigTransferIdle(): Boolean {
        if (configTransferJob?.isActive == true) {
            AppToast.show(activity, "正在处理配置…")
            return false
        }
        return true
    }

    private fun buildLogExportExtras(metaJson: String): List<LogExporter.ZipExtra> {
        return listOf(
            LogExporter.ZipExtra(
                path = "meta.json",
                bytes = metaJson.toByteArray(Charsets.UTF_8),
            ),
        )
    }

    private fun <T> launchIoJob(
        startToast: String,
        failureLogMessage: String,
        failureToastPrefix: String,
        work: suspend () -> T,
        onSuccess: (T) -> Unit,
    ): Job {
        return activity.lifecycleScope.launch {
            AppToast.show(activity, startToast)
            runCatching {
                withContext(Dispatchers.IO) { work() }
            }.onSuccess(onSuccess)
                .onFailure { t ->
                    AppLog.w("Settings", failureLogMessage, t)
                    AppToast.showLong(activity, "$failureToastPrefix：${throwableMessage(t)}")
                }
        }
    }

    private fun throwableMessage(t: Throwable): String {
        return t.message?.takeIf { it.isNotBlank() } ?: "未知错误"
    }

    fun onEntryClicked(entry: SettingEntry) {
        val prefs = BiliClient.prefs
        state.pendingRestoreRightId = entry.id
        when (entry.id) {
            SettingId.ImageQuality -> {
                val next =
                    when (prefs.imageQuality) {
                        "small" -> "medium"
                        "medium" -> "large"
                        else -> "small"
                    }
                prefs.imageQuality = next
                AppToast.show(activity, "图片质量：$next")
                renderer.refreshSection(entry.id)
            }

            SettingId.ThemePreset -> {
                val options =
                    listOf(
                        blbl.cat3399.core.prefs.AppPrefs.THEME_PRESET_DEFAULT to "默认",
                        blbl.cat3399.core.prefs.AppPrefs.THEME_PRESET_TV_PINK to "小电视粉",
                        blbl.cat3399.core.prefs.AppPrefs.THEME_PRESET_TV_PINK_ILLUSTRATION to "经典",
                    )
                showChoiceDialog(
                    title = "主题",
                    items = options.map { it.second },
                    current = SettingsText.themePresetText(prefs.themePreset),
                ) { selected ->
                    val key =
                        options.firstOrNull { it.second == selected }?.first
                            ?: blbl.cat3399.core.prefs.AppPrefs.THEME_PRESET_DEFAULT

                    if (prefs.themePreset == key) {
                        AppToast.show(activity, "主题：$selected")
                        return@showChoiceDialog
                    }

                    prefs.themePreset = key
                    AppToast.show(activity, "主题：$selected（已应用）")
                    restartToMain()
                }
            }

            SettingId.UserAgent -> showUserAgentDialog(state.currentSectionIndex, entry.id)
            SettingId.Ipv4OnlyEnabled -> {
                prefs.ipv4OnlyEnabled = !prefs.ipv4OnlyEnabled
                AppToast.show(activity, "是否只允许使用IPV4：${if (prefs.ipv4OnlyEnabled) "开" else "关"}")
                evictNetworkConnections()
                renderer.refreshSection(entry.id)
            }
            SettingId.GaiaVgate -> showGaiaVgateDialog(state.currentSectionIndex, entry.id)
            SettingId.ClearCache -> showClearCacheDialog(state.currentSectionIndex, entry.id)
            SettingId.ConfigTransfer -> showConfigTransferDialog()
            SettingId.ClearLogin -> showClearLoginDialog(state.currentSectionIndex, entry.id)
            SettingId.ExportLogs -> {
                val prepared = prepareLogsExport()
                launchDocumentExport(
                    request = CreateDocumentRequest(mimeType = LogExporter.ZIP_MIME, fileName = prepared.fileName),
                    onUriSelected = { uri -> exportLogsToUri(uri = uri, prepared = prepared) },
                    onFallbackToLocal = { exportLogsToLocalFile(prepared) },
                    logTag = "logs",
                )
            }

            SettingId.UploadLogs -> {
                showUploadLogsDialog()
            }

            SettingId.FullscreenEnabled -> {
                prefs.fullscreenEnabled = !prefs.fullscreenEnabled
                Immersive.apply(activity, prefs.fullscreenEnabled)
                AppToast.show(activity, "全屏：${if (prefs.fullscreenEnabled) "开" else "关"}")
                renderer.refreshSection(entry.id)
            }

            SettingId.TabSwitchFollowsFocus -> {
                prefs.tabSwitchFollowsFocus = !prefs.tabSwitchFollowsFocus
                AppToast.show(activity, "tab跟随焦点切换：${if (prefs.tabSwitchFollowsFocus) "开" else "关"}")
                renderer.refreshSection(entry.id)
            }

            SettingId.MainAutoHideSidebarOnEnterContent -> {
                prefs.mainAutoHideSidebarOnEnterContent = !prefs.mainAutoHideSidebarOnEnterContent
                AppToast.show(activity, "进入内容区后关闭侧边栏：${if (prefs.mainAutoHideSidebarOnEnterContent) "开" else "关"}")
                renderer.refreshSection(entry.id)
            }

            SettingId.MainBackFocusScheme -> {
                val options =
                    listOf(
                        blbl.cat3399.core.prefs.AppPrefs.MAIN_BACK_FOCUS_SCHEME_A to "回到当前所属Tab",
                        blbl.cat3399.core.prefs.AppPrefs.MAIN_BACK_FOCUS_SCHEME_B to "回到Tab0内容区",
                        blbl.cat3399.core.prefs.AppPrefs.MAIN_BACK_FOCUS_SCHEME_C to "回到侧边栏",
                    )
                showChoiceDialog(
                    title = "返回键焦点策略",
                    items = options.map { it.second },
                    current = SettingsText.mainBackFocusSchemeText(prefs.mainBackFocusScheme),
                ) { selected ->
                    val key = options.firstOrNull { it.second == selected }?.first
                        ?: blbl.cat3399.core.prefs.AppPrefs.MAIN_BACK_FOCUS_SCHEME_A
                    prefs.mainBackFocusScheme = key
                    AppToast.show(activity, "返回键焦点策略：$selected")
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.VideoCardLongPressAction -> {
                val options =
                    listOf(
                        AppPrefs.VIDEO_CARD_LONG_PRESS_ACTION_MANUAL to "手动选择",
                        AppPrefs.VIDEO_CARD_LONG_PRESS_ACTION_WATCH_LATER to "添加到稍后再看",
                        AppPrefs.VIDEO_CARD_LONG_PRESS_ACTION_OPEN_DETAIL to "进入详情页",
                        AppPrefs.VIDEO_CARD_LONG_PRESS_ACTION_OPEN_UP to "进入UP主页",
                        AppPrefs.VIDEO_CARD_LONG_PRESS_ACTION_DISMISS to "不感兴趣",
                    )
                showChoiceDialog(
                    title = "长按视频卡片",
                    items = options.map { it.second },
                    current = SettingsText.videoCardLongPressActionText(prefs.videoCardLongPressAction),
                ) { selected ->
                    val value = options.firstOrNull { it.second == selected }?.first ?: AppPrefs.VIDEO_CARD_LONG_PRESS_ACTION_MANUAL
                    prefs.videoCardLongPressAction = value
                    AppToast.show(activity, "长按视频卡片：$selected")
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.StartupPage -> {
                val options =
                    MainRootNavRegistry.startupSpecs().map { spec ->
                        (spec.startupPageKey ?: AppPrefs.STARTUP_PAGE_HOME) to activity.getString(spec.titleRes)
                    }
                showChoiceDialog(
                    title = "启动默认页",
                    items = options.map { it.second },
                    current = SettingsText.startupPageText(activity, prefs.startupPage),
                ) { selected ->
                    val key =
                        options.firstOrNull { it.second == selected }?.first
                            ?: blbl.cat3399.core.prefs.AppPrefs.STARTUP_PAGE_HOME
                    prefs.startupPage = key
                    AppToast.show(activity, "启动默认页：$selected（下次启动生效）")
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.CustomPageEnabled -> {
                val config = prefs.customPageConfig
                prefs.customPageConfig = config.copy(enabled = !config.enabled)
                AppToast.show(activity, "自定义页：${if (prefs.customPageConfig.enabled) "开" else "关"}")
                renderer.refreshSection(entry.id)
            }

            SettingId.CustomPageContent -> showCustomPageContentDialog(sectionIndex = state.currentSectionIndex, focusId = entry.id)

            SettingId.FollowingListOrder -> {
                val options =
                    listOf(
                        AppPrefs.FOLLOWING_LIST_ORDER_FOLLOW_TIME to "关注时间",
                        AppPrefs.FOLLOWING_LIST_ORDER_RECENT_VISIT to "最近访问",
                    )
                showChoiceDialog(
                    title = "关注列表排序",
                    items = options.map { it.second },
                    current = SettingsText.followingListOrderText(prefs.followingListOrder),
                ) { selected ->
                    val value = options.firstOrNull { it.second == selected }?.first ?: AppPrefs.FOLLOWING_LIST_ORDER_FOLLOW_TIME
                    prefs.followingListOrder = value
                    AppToast.show(activity, "关注列表排序：$selected")
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.MainHomeVisibleTabs -> {
                showVisibleTabsDialog(
                    sectionIndex = state.currentSectionIndex,
                    focusId = entry.id,
                    title = "主页显示页面",
                    options = HomeTabs.all.map { it.key to activity.getString(it.titleRes) },
                    selectedKeys = prefs.mainHomeVisibleTabs,
                ) { prefs.mainHomeVisibleTabs = it }
            }

            SettingId.MainCategoryVisibleTabs -> {
                showVisibleTabsDialog(
                    sectionIndex = state.currentSectionIndex,
                    focusId = entry.id,
                    title = "分类页显示页面",
                    options = CategoryZones.defaultZones.map { CategoryZones.stableKeyFor(it) to it.title },
                    selectedKeys = prefs.mainCategoryVisibleTabs,
                ) { prefs.mainCategoryVisibleTabs = it }
            }

            SettingId.MainLiveVisibleTabs -> {
                showVisibleTabsDialog(
                    sectionIndex = state.currentSectionIndex,
                    focusId = entry.id,
                    title = "直播页显示页面",
                    options = LiveFragment.LiveTabs.all.map { it.key to it.title },
                    selectedKeys = prefs.mainLiveVisibleTabs,
                ) { prefs.mainLiveVisibleTabs = it }
            }

            SettingId.MainMyVisibleTabs -> {
                showVisibleTabsDialog(
                    sectionIndex = state.currentSectionIndex,
                    focusId = entry.id,
                    title = "我的页显示页面",
                    options = MyTabs.all.map { it.key to activity.getString(it.titleRes) },
                    selectedKeys = prefs.mainMyVisibleTabs,
                ) { prefs.mainMyVisibleTabs = it }
            }

            SettingId.UiScaleFactor -> {
                val factors = (70..140 step 5).map { it / 100f }
                val items = factors.map { SettingsText.uiScaleFactorText(it) }
                showChoiceDialog(
                    title = "界面大小",
                    items = items,
                    current = SettingsText.uiScaleFactorText(prefs.uiScaleFactor),
                ) { selected ->
                    val factor = factors.getOrNull(items.indexOf(selected)) ?: prefs.uiScaleFactor
                    prefs.uiScaleFactor = factor
                    AppToast.show(activity, "界面大小：$selected")
                    // Accept "recreate to apply" to keep UI scale management centralized and reduce per-module sizing code.
                    activity.recreate()
                }
            }

            SettingId.GridSpanCount -> {
                val options = listOf("1", "2", "3", "4", "5", "6")
                showChoiceDialog(
                    title = "每行卡片数量",
                    items = options,
                    current = SettingsText.gridSpanText(prefs.gridSpanCount),
                ) { selected ->
                    prefs.gridSpanCount = (selected.toIntOrNull() ?: 4).coerceIn(1, 6)
                    AppToast.show(activity, "每行卡片：${SettingsText.gridSpanText(prefs.gridSpanCount)}")
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DynamicGridSpanCount -> {
                val options = listOf("1", "2", "3", "4", "5", "6")
                showChoiceDialog(
                    title = "动态页每行卡片数量",
                    items = options,
                    current = SettingsText.gridSpanText(prefs.dynamicGridSpanCount),
                ) { selected ->
                    prefs.dynamicGridSpanCount = (selected.toIntOrNull() ?: 3).coerceIn(1, 6)
                    AppToast.show(activity, "动态每行：${SettingsText.gridSpanText(prefs.dynamicGridSpanCount)}")
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PgcGridSpanCount -> {
                val options = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
                showChoiceDialog(
                    title = "番剧/电视剧每行卡片数量",
                    items = options,
                    current = SettingsText.gridSpanText(prefs.pgcGridSpanCount),
                ) { selected ->
                    prefs.pgcGridSpanCount = (selected.toIntOrNull() ?: 6).coerceIn(1, 6)
                    AppToast.show(activity, "番剧每行：${SettingsText.gridSpanText(prefs.pgcGridSpanCount)}")
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuEnabled -> {
                prefs.danmakuEnabled = !prefs.danmakuEnabled
                AppToast.show(activity, "弹幕：${if (prefs.danmakuEnabled) "开" else "关"}")
                renderer.refreshSection(entry.id)
            }

            SettingId.SubtitleEnabledDefault -> {
                prefs.subtitleEnabledDefault = !prefs.subtitleEnabledDefault
                AppToast.show(activity, "默认字幕：${if (prefs.subtitleEnabledDefault) "开" else "关"}")
                renderer.refreshSection(entry.id)
            }

            SettingId.SubtitleTextSizeSp -> {
                val options = PlaybackSettingChoices.subtitleTextSizes
                showChoiceDialog(
                    title = "字幕字体大小(sp)",
                    items = options.map { it.toString() },
                    current = prefs.subtitleTextSizeSp.toInt().toString(),
                ) { selected ->
                    prefs.subtitleTextSizeSp = (selected.toIntOrNull() ?: 26).toFloat().coerceIn(10f, 60f)
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.SubtitleBottomPaddingFraction -> {
                val options = PlaybackSettingChoices.subtitleBottomPaddingPercents
                val items = options.map { "${it}%" }
                val checked =
                    options.indices.minByOrNull { kotlin.math.abs(options[it] / 100f - prefs.subtitleBottomPaddingFraction) }
                        ?: 0
                showChoiceDialog(
                    title = "字幕底部间距(占屏比%)",
                    items = items,
                    checkedIndex = checked,
                ) { selected ->
                    val percent = selected.removeSuffix("%").toIntOrNull() ?: options.getOrNull(checked) ?: 16
                    prefs.subtitleBottomPaddingFraction = percent / 100f
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.SubtitleBackgroundOpacity -> {
                val ordered = PlaybackSettingChoices.subtitleBackgroundOpacities
                val items = ordered.map { String.format(Locale.US, "%.2f", it) }
                val checked = ordered.indices.minByOrNull { kotlin.math.abs(ordered[it] - prefs.subtitleBackgroundOpacity) } ?: 0
                showChoiceDialog(
                    title = "字幕背景透明度",
                    items = items,
                    checkedIndex = checked,
                ) { selected ->
                    val value = selected.toFloatOrNull() ?: ordered.getOrNull(checked) ?: prefs.subtitleBackgroundOpacity
                    prefs.subtitleBackgroundOpacity = value.coerceIn(0f, 1.0f)
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuOpacity -> {
                val options = PlaybackSettingChoices.danmakuOpacities
                showChoiceDialog(
                    title = "弹幕透明度",
                    items = options.map { String.format(Locale.US, "%.2f", it) },
                    current = String.format(Locale.US, "%.2f", prefs.danmakuOpacity),
                ) { selected ->
                    prefs.danmakuOpacity = selected.toFloatOrNull()?.coerceIn(0.05f, 1.0f) ?: prefs.danmakuOpacity
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuTextSizeSp -> {
                val options = PlaybackSettingChoices.danmakuTextSizes
                showChoiceDialog(
                    title = "弹幕字体大小(sp)",
                    items = options.map { it.toString() },
                    current = prefs.danmakuTextSizeSp.toInt().toString(),
                ) { selected ->
                    prefs.danmakuTextSizeSp = (selected.toIntOrNull() ?: 18).toFloat().coerceIn(10f, 60f)
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuFontWeight -> {
                val options = PlaybackSettingChoices.danmakuFontWeights
                showChoiceDialog(
                    title = "字体粗细",
                    items = options.map { SettingsText.danmakuFontWeightText(it.prefValue) },
                    current = SettingsText.danmakuFontWeightText(prefs.danmakuFontWeight),
                ) { selected ->
                    val value =
                        options
                            .firstOrNull { SettingsText.danmakuFontWeightText(it.prefValue) == selected }
                            ?.prefValue
                            ?: AppPrefs.DANMAKU_FONT_WEIGHT_BOLD
                    prefs.danmakuFontWeight = value
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuStrokeWidthPx -> {
                val options = PlaybackSettingChoices.danmakuStrokeWidths
                showChoiceDialog(
                    title = "弹幕文字描边粗细",
                    items = options.map { it.toString() },
                    current = prefs.danmakuStrokeWidthPx.toString(),
                ) { selected ->
                    prefs.danmakuStrokeWidthPx = selected.toIntOrNull() ?: prefs.danmakuStrokeWidthPx
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuArea -> {
                val options = PlaybackSettingChoices.danmakuAreas
                showChoiceDialog(
                    title = "弹幕占屏比",
                    items = options.map { it.second },
                    current = SettingsText.areaText(prefs.danmakuArea),
                ) { selected ->
                    val value = options.firstOrNull { it.second == selected }?.first ?: 1.0f
                    prefs.danmakuArea = value
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuLaneDensity -> {
                val options = PlaybackSettingChoices.danmakuLaneDensities
                showChoiceDialog(
                    title = "轨道密度",
                    items = options.map { SettingsText.danmakuLaneDensityText(it.prefValue) },
                    current = SettingsText.danmakuLaneDensityText(prefs.danmakuLaneDensity),
                ) { selected ->
                    val value =
                        options
                            .firstOrNull { SettingsText.danmakuLaneDensityText(it.prefValue) == selected }
                            ?.prefValue
                            ?: AppPrefs.DANMAKU_LANE_DENSITY_STANDARD
                    prefs.danmakuLaneDensity = value
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuSpeed -> {
                val options = PlaybackSettingChoices.danmakuSpeeds.map(Int::toString)
                showChoiceDialog(
                    title = "弹幕速度(1~10)",
                    items = options,
                    current = prefs.danmakuSpeed.toString(),
                ) { selected ->
                    prefs.danmakuSpeed = (selected.toIntOrNull() ?: 4).coerceIn(1, 10)
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuFollowBiliShield -> {
                prefs.danmakuFollowBiliShield = !prefs.danmakuFollowBiliShield
                renderer.refreshSection(entry.id)
            }

            SettingId.DanmakuShowHighLikeIcon -> {
                prefs.danmakuShowHighLikeIcon = !prefs.danmakuShowHighLikeIcon
                renderer.refreshSection(entry.id)
            }

            SettingId.DanmakuAiShieldEnabled -> {
                prefs.danmakuAiShieldEnabled = !prefs.danmakuAiShieldEnabled
                renderer.refreshSection(entry.id)
            }

            SettingId.DanmakuAiShieldLevel -> {
                val options = PlaybackSettingChoices.aiShieldLevels.map(Int::toString)
                showChoiceDialog(
                    title = "智能云屏蔽等级",
                    items = options,
                    current = SettingsText.aiLevelText(prefs.danmakuAiShieldLevel),
                ) { selected ->
                    prefs.danmakuAiShieldLevel = (selected.toIntOrNull() ?: 3).coerceIn(1, 10)
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuAllowScroll -> {
                prefs.danmakuAllowScroll = !prefs.danmakuAllowScroll
                renderer.refreshSection(entry.id)
            }

            SettingId.DanmakuAllowTop -> {
                prefs.danmakuAllowTop = !prefs.danmakuAllowTop
                renderer.refreshSection(entry.id)
            }

            SettingId.DanmakuAllowBottom -> {
                prefs.danmakuAllowBottom = !prefs.danmakuAllowBottom
                renderer.refreshSection(entry.id)
            }

            SettingId.DanmakuAllowColor -> {
                prefs.danmakuAllowColor = !prefs.danmakuAllowColor
                renderer.refreshSection(entry.id)
            }

            SettingId.DanmakuAllowSpecial -> {
                prefs.danmakuAllowSpecial = !prefs.danmakuAllowSpecial
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerPreferredQn -> {
                val options =
                    PlaybackSettingChoices.resolutionQns.map { it to SettingsText.qnText(it) }
                showChoiceDialog(
                    title = "默认画质",
                    items = options.map { it.second },
                    current = SettingsText.qnText(prefs.playerPreferredQn),
                ) { selected ->
                    val qn = options.firstOrNull { it.second == selected }?.first
                    if (qn != null) prefs.playerPreferredQn = qn
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerPreferredQnPortrait -> {
                val options =
                    PlaybackSettingChoices.resolutionQns.map { it to SettingsText.qnText(it) }
                showChoiceDialog(
                    title = "默认画质（竖屏）",
                    items = options.map { it.second },
                    current = SettingsText.qnText(prefs.playerPreferredQnPortrait),
                ) { selected ->
                    val qn = options.firstOrNull { it.second == selected }?.first
                    if (qn != null) prefs.playerPreferredQnPortrait = qn
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerPreferredAudioId -> {
                val options = PlaybackSettingChoices.audioTrackIds
                val optionLabels = options.map { SettingsText.audioText(it) }
                showChoiceDialog(
                    title = "默认音轨",
                    items = optionLabels,
                    current = SettingsText.audioText(prefs.playerPreferredAudioId),
                ) { selected ->
                    val id = options.getOrNull(optionLabels.indexOfFirst { it == selected }.takeIf { it >= 0 } ?: -1)
                    if (id != null) prefs.playerPreferredAudioId = id
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerCdnPreference -> {
                val options =
                    listOf(
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_BILIVIDEO to "bilivideo（默认）",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_MCDN to "mcdn（部分网络更快/更慢）",
                    )
                val checked = options.indexOfFirst { it.first == prefs.playerCdnPreference }.coerceAtLeast(0)
                showChoiceDialog(
                    title = "CDN线路",
                    items = options.map { it.second },
                    checkedIndex = checked,
                ) { selected ->
                    val value = options.firstOrNull { it.second == selected }?.first
                        ?: blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_BILIVIDEO
                    prefs.playerCdnPreference = value
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerSpeed -> {
                val options = PlaybackSettingChoices.playbackSpeeds
                showChoiceDialog(
                    title = "默认播放速度",
                    items = options.map { String.format(Locale.US, "%.2fx", it) },
                    current = String.format(Locale.US, "%.2fx", prefs.playerSpeed),
                ) { selected ->
                    val v = selected.removeSuffix("x").toFloatOrNull()
                    if (v != null) prefs.playerSpeed = v.coerceIn(0.25f, 3.0f)
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerShortSeekStepSeconds -> {
                showPlayerShortSeekStepDialog(sectionIndex = state.currentSectionIndex, focusId = entry.id)
            }

            SettingId.PlayerHoldSeekSpeed -> {
                val options = listOf(1.5f, 2.0f, 3.0f, 4.0f)
                showChoiceDialog(
                    title = "长按快进倍率",
                    items = options.map { String.format(Locale.US, "%.2fx", it) },
                    current = String.format(Locale.US, "%.2fx", prefs.playerHoldSeekSpeed),
                ) { selected ->
                    val v = selected.removeSuffix("x").toFloatOrNull()
                    if (v != null) prefs.playerHoldSeekSpeed = v.coerceIn(1.5f, 4.0f)
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerHoldSeekMode -> {
                val options =
                    listOf(
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_HOLD_SEEK_MODE_SPEED to "倍率加速",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB to "拖动进度条",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME to "固定时间拖动进度条",
                    )
                val labels = options.map { it.second }
                val checked = options.indexOfFirst { it.first == prefs.playerHoldSeekMode }.coerceAtLeast(0)
                AppPopup.singleChoice(
                    context = activity,
                    title = "长按快进模式",
                    items = labels,
                    checkedIndex = checked,
                ) { which, _ ->
                    val value =
                        options.getOrNull(which)?.first
                            ?: blbl.cat3399.core.prefs.AppPrefs.PLAYER_HOLD_SEEK_MODE_SPEED
                    prefs.playerHoldSeekMode = value
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerHoldScrubTraverseSeconds -> {
                showPlayerHoldScrubTraverseSecondsDialog(sectionIndex = state.currentSectionIndex, focusId = entry.id)
            }

            SettingId.PlayerHoldScrubFixedStepSeconds -> {
                showPlayerHoldScrubFixedStepSecondsDialog(sectionIndex = state.currentSectionIndex, focusId = entry.id)
            }

            SettingId.PlayerAutoResumeEnabled -> {
                prefs.playerAutoResumeEnabled = !prefs.playerAutoResumeEnabled
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerAutoSkipSegmentsEnabled -> {
                prefs.playerAutoSkipSegmentsEnabled = !prefs.playerAutoSkipSegmentsEnabled
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerAutoSkipServerBaseUrl -> {
                showPlayerAutoSkipServerBaseUrlDialog(state.currentSectionIndex, entry.id)
            }

            SettingId.PlayerOpenDetailBeforePlay -> {
                prefs.playerOpenDetailBeforePlay = !prefs.playerOpenDetailBeforePlay
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerPlaybackMode -> {
                val modeCodes = PlayerPlaybackModes.ordered
                val options = modeCodes.map(PlayerPlaybackModes::label)
                showChoiceDialog(
                    title = "播放模式（全局默认）",
                    items = options,
                    current = PlayerPlaybackModes.label(prefs.playerPlaybackMode),
                ) { selected ->
                    val selectedIndex = options.indexOf(selected).takeIf { it >= 0 } ?: 0
                    prefs.playerPlaybackMode = modeCodes.getOrElse(selectedIndex) { AppPrefs.PLAYER_PLAYBACK_MODE_NONE }
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerSettingsApplyToGlobal -> {
                prefs.playerSettingsApplyToGlobal = !prefs.playerSettingsApplyToGlobal
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerUpQuickCardEnabled -> {
                prefs.playerUpQuickCardEnabled = !prefs.playerUpQuickCardEnabled
                renderer.refreshSection(entry.id)
            }

            SettingId.SubtitlePreferredLang -> {
                val options =
                    listOf(
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
                    current = SettingsText.subtitleLangText(prefs.subtitlePreferredLang),
                ) { selected ->
                    val code = options.firstOrNull { it.second == selected }?.first ?: "auto"
                    prefs.subtitlePreferredLang = code
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerPreferredCodec -> {
                val options = listOf("AVC", "HEVC", "AV1")
                showChoiceDialog(
                    title = "视频编码(偏好)",
                    items = options,
                    current = prefs.playerPreferredCodec,
                ) { selected ->
                    prefs.playerPreferredCodec = selected
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerRenderView -> {
                val options = listOf("SurfaceView", "TextureView")
                showChoiceDialog(
                    title = "渲染视图",
                    items = options,
                    current = SettingsText.renderViewText(prefs.playerRenderViewType),
                ) { selected ->
                    prefs.playerRenderViewType =
                        when (selected) {
                            "TextureView" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW
                            else -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_RENDER_VIEW_SURFACE_VIEW
                        }
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerEngineKind -> {
                val options = listOf("ExoPlayer", "IjkPlayer")
                showChoiceDialog(
                    title = "播放器内核",
                    items = options,
                    current = SettingsText.playerEngineText(prefs.playerEngineKind),
                ) { selected ->
                    val value =
                        when (selected) {
                            "IjkPlayer" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_ENGINE_IJK
                            else -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_ENGINE_EXO
                        }
                    if (value == blbl.cat3399.core.prefs.AppPrefs.PLAYER_ENGINE_IJK) {
                        IjkPlayerPluginUi.ensureInstalled(activity) {
                            prefs.playerEngineKind = value
                            AppToast.show(activity, "播放器内核：$selected（下次播放生效）")
                            renderer.refreshSection(entry.id)
                        }
                        return@showChoiceDialog
                    }

                    if (prefs.playerEngineKind == value) {
                        AppToast.show(activity, "播放器内核：$selected")
                        return@showChoiceDialog
                    }

                    prefs.playerEngineKind = value
                    AppToast.show(activity, "播放器内核：$selected（下次播放生效）")
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerAudioBalance -> {
                val options = AudioBalanceLevel.ordered
                val current = AudioBalanceLevel.fromPrefValue(prefs.playerAudioBalanceLevel)
                val checked = options.indexOf(current).takeIf { it >= 0 } ?: 0
                showChoiceDialog(
                    title = "音频平衡",
                    items = options.map { it.label },
                    checkedIndex = checked,
                ) { selected ->
                    val picked = options.firstOrNull { it.label == selected } ?: AudioBalanceLevel.Off
                    prefs.playerAudioBalanceLevel = picked.prefValue
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerOsdButtons -> showPlayerOsdButtonsDialog(sectionIndex = state.currentSectionIndex, focusId = entry.id)

            SettingId.PlayerCustomShortcuts -> showPlayerCustomShortcutsDialog(sectionIndex = state.currentSectionIndex, focusId = entry.id)

            SettingId.LiveHighBitrateEnabled -> {
                prefs.liveHighBitrateEnabled = !prefs.liveHighBitrateEnabled
                AppToast.show(activity, "提高直播码率：${if (prefs.liveHighBitrateEnabled) "开" else "关"}")
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerDebugEnabled -> {
                prefs.playerDebugEnabled = !prefs.playerDebugEnabled
                renderer.refreshSection(entry.id)
            }

            SettingId.DynamicFollowingRecentUpdateDotEnabled -> {
                prefs.dynamicFollowingRecentUpdateDotEnabled = !prefs.dynamicFollowingRecentUpdateDotEnabled
                AppToast.show(activity, "动态页小红点：${if (prefs.dynamicFollowingRecentUpdateDotEnabled) "开" else "关"}")
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerDoubleBackToExit -> {
                prefs.playerDoubleBackToExit = !prefs.playerDoubleBackToExit
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerDownKeyOsdFocusTarget -> {
                val options =
                    listOf(
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE to "播放/暂停",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_PREV to "上一个",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_NEXT to "下一个",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_SUBTITLE to "字幕",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_DANMAKU to "弹幕",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_COMMENTS to "评论",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_DETAIL to "视频详情页",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_UP to "UP主",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_LIKE to "点赞",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_COIN to "投币",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_FAV to "收藏",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_LIST_PANEL to "列表面板",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_ADVANCED to "更多设置",
                    )
                showChoiceDialog(
                    title = "下键呼出OSD后焦点",
                    items = options.map { it.second },
                    current = SettingsText.downKeyOsdFocusTargetText(prefs.playerDownKeyOsdFocusTarget),
                ) { selected ->
                    val value =
                        options.firstOrNull { it.second == selected }?.first
                            ?: blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE
                    prefs.playerDownKeyOsdFocusTarget = value
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerTogglePlayStateShowOsd -> {
                prefs.playerTogglePlayStateShowOsd = !prefs.playerTogglePlayStateShowOsd
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerPersistentBottomProgressEnabled -> {
                prefs.playerPersistentBottomProgressEnabled = !prefs.playerPersistentBottomProgressEnabled
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerPersistentClockEnabled -> {
                prefs.playerPersistentClockEnabled = !prefs.playerPersistentClockEnabled
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerTouchGesturesEnabled -> {
                prefs.playerTouchGesturesEnabled = !prefs.playerTouchGesturesEnabled
                AppToast.show(activity, "触摸手势：${if (prefs.playerTouchGesturesEnabled) "开" else "关"}")
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerVideoShotPreviewSize -> {
                val options =
                    listOf(
                        AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_OFF to "不显示",
                        AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_SMALL to "小",
                        AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM to "中",
                        AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_LARGE to "大",
                    )
                val checked = options.indexOfFirst { it.first == prefs.playerVideoShotPreviewSize }.coerceAtLeast(0)
                showChoiceDialog(
                    title = "缩略图显示",
                    items = options.map { it.second },
                    checkedIndex = checked,
                ) { selected ->
                    val value = options.firstOrNull { it.second == selected }?.first ?: AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM
                    prefs.playerVideoShotPreviewSize = value
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerStyle -> {
                val options =
                    listOf(
                        AppPrefs.PLAYER_STYLE_FULLSCREEN to "全屏",
                        AppPrefs.PLAYER_STYLE_HD to "HD",
                    )
                val checked = options.indexOfFirst { it.first == prefs.playerStyle }.coerceAtLeast(0)
                showChoiceDialog(
                    title = SettingsText.playerStyleTitle(),
                    items = options.map { it.second },
                    checkedIndex = checked,
                ) { selected ->
                    val value = options.firstOrNull { it.second == selected }?.first ?: AppPrefs.PLAYER_STYLE_FULLSCREEN
                    prefs.playerStyle = value
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.ProjectUrl -> showProjectDialog()

            SettingId.QqGroup -> {
                copyToClipboard(label = "QQ交流群", text = SettingsConstants.QQ_GROUP, toastText = "已复制群号：${SettingsConstants.QQ_GROUP}")
            }

            SettingId.PlayerKernelCheck -> handlePlayerKernelCheck()

            SettingId.CheckUpdate -> {
                when (val checkState = state.testUpdateCheckState) {
                    TestUpdateCheckState.Checking -> {
                        AppToast.show(activity, "正在检查更新…")
                    }

                    is TestUpdateCheckState.UpdateAvailable -> {
                        startTestUpdateDownload(latestVersionHint = checkState.latestVersion)
                    }

                    else -> ensureTestUpdateChecked(force = true, refreshUi = true)
                }
            }

            else -> AppLog.i("Settings", "click id=${entry.id.key} title=${entry.title}")
        }
    }

    private fun handlePlayerKernelCheck() {
        when (IjkPlayerPlugin.status(activity)) {
            IjkPlayerPlugin.InstallStatus.Unsupported -> {
                AppToast.showLong(activity, "当前设备不支持 IjkPlayer（ABI=${Build.SUPPORTED_ABIS.joinToString()}）")
            }

            IjkPlayerPlugin.InstallStatus.Installed -> {
                AppToast.show(activity, "播放器内核已是最新")
            }

            IjkPlayerPlugin.InstallStatus.NotInstalled,
            IjkPlayerPlugin.InstallStatus.NeedsUpdate,
            -> {
                IjkPlayerPluginUi.ensureInstalled(activity) {
                    renderer.refreshAboutSectionKeepPosition()
                }
            }
        }
    }

    private fun restartToMain() {
        val intent =
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
    }

    private fun evictNetworkConnections() {
        runCatching { BiliClient.apiOkHttp.connectionPool.evictAll() }
        runCatching { BiliClient.cdnOkHttp.connectionPool.evictAll() }
        runCatching { ApkUpdater.evictConnections() }
    }

    private fun showChoiceDialog(title: String, items: List<String>, current: String, onPicked: (String) -> Unit) {
        val checked = items.indexOf(current).takeIf { it >= 0 } ?: 0
        showChoiceDialog(
            title = title,
            items = items,
            checkedIndex = checked,
            onPicked = onPicked,
        )
    }

    private fun showChoiceDialog(title: String, items: List<String>, checkedIndex: Int, onPicked: (String) -> Unit) {
        val checked = checkedIndex.takeIf { it in items.indices } ?: 0
        AppPopup.singleChoice(
            context = activity,
            title = title,
            items = items,
            checkedIndex = checked,
        ) { _, label ->
            onPicked(label)
        }
    }

    private fun showVisibleTabsDialog(
        sectionIndex: Int,
        focusId: SettingId,
        title: String,
        options: List<Pair<String, String>>,
        selectedKeys: List<String>,
        save: (List<String>) -> Unit,
    ) {
        val keys = options.map { it.first }
        val labels = options.map { it.second }
        val selected =
            selectedKeys
                .takeIf { it.isNotEmpty() }
                ?.toSet()
                ?: keys.toSet()
        val checked = BooleanArray(keys.size) { idx -> keys.getOrNull(idx) in selected }
        if (checked.none { it } && checked.isNotEmpty()) {
            for (idx in checked.indices) checked[idx] = true
        }

        AppPopup.multiChoice(
            context = activity,
            title = title,
            items = labels,
            checked = checked,
            minCheckedCount = 1,
            onChanged = { finalChecked ->
                save(
                    keys.filterIndexed { idx, _ ->
                        idx in finalChecked.indices && finalChecked[idx]
                    },
                )
            },
            onDismiss = {
                renderer.showSection(sectionIndex, focusId = focusId)
            },
        )
    }

    private fun showPlayerOsdButtonsDialog(sectionIndex: Int, focusId: SettingId) {
        val prefs = BiliClient.prefs
        val options =
            listOf(
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_PREV to "上一个",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_PLAY_PAUSE to "播放/暂停",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_NEXT to "下一个",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_SUBTITLE to "字幕",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_DANMAKU to "弹幕",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_COMMENTS to "评论",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_DETAIL to "视频详情页",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_UP to "UP主",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_LIKE to "点赞",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_COIN to "投币",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_FAV to "收藏",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_LIST_PANEL to "列表",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_ADVANCED to "更多设置",
            )
        val keys = options.map { it.first }
        val labels = options.map { it.second }.toTypedArray()

        val selected = prefs.playerOsdButtons.toSet()
        val checked = BooleanArray(keys.size) { idx -> selected.contains(keys[idx]) }
        AppPopup.multiChoice(
            context = activity,
            title = "OSD按钮显示",
            items = labels.toList(),
            checked = checked,
            onChanged = { finalChecked ->
                prefs.playerOsdButtons =
                    keys.filterIndexed { idx, _ ->
                        idx in finalChecked.indices && finalChecked[idx]
                    }
            },
            onDismiss = {
                renderer.showSection(sectionIndex, focusId = focusId)
            },
        )
    }

    private fun showPlayerCustomShortcutsDialog(sectionIndex: Int, focusId: SettingId) {
        fun keyLabel(keyCode: Int): String {
            val raw = runCatching { KeyEvent.keyCodeToString(keyCode) }.getOrNull()?.trim().orEmpty()
            if (raw.isBlank()) return keyCode.toString()
            val text = raw.removePrefix("KEYCODE_")
            return when {
                text.startsWith("NUMPAD_") && text.length == "NUMPAD_0".length -> "小键盘${text.last()}"
                text.length == 1 && text[0] in '0'..'9' -> text
                else -> text
            }
        }

        fun actionLabel(action: PlayerCustomShortcutAction): String = PlayerCustomShortcutCatalog.actionLabel(action)

        fun bindingLabel(binding: PlayerCustomShortcut): String =
            "${keyLabel(binding.keyCode)} → ${actionLabel(binding.action)}"

        fun loadShortcuts(): List<PlayerCustomShortcut> = BiliClient.prefs.playerCustomShortcuts

        fun upsert(binding: PlayerCustomShortcut) {
            val prefs = BiliClient.prefs
            prefs.playerCustomShortcuts = PlayerCustomShortcutsStore.upsert(prefs.playerCustomShortcuts, binding)
            renderer.refreshSection(SettingId.PlayerCustomShortcuts)
        }

        fun removeBinding(keyCode: Int) {
            val prefs = BiliClient.prefs
            prefs.playerCustomShortcuts = PlayerCustomShortcutsStore.remove(prefs.playerCustomShortcuts, keyCode)
            renderer.refreshSection(SettingId.PlayerCustomShortcuts)
        }

        fun clearAll() {
            BiliClient.prefs.playerCustomShortcuts = PlayerCustomShortcutsStore.clear()
            renderer.refreshSection(SettingId.PlayerCustomShortcuts)
        }

        class ShortcutItemVh(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvLabel: TextView = itemView.findViewById(blbl.cat3399.R.id.tv_label)
            private val tvCheck: TextView = itemView.findViewById(blbl.cat3399.R.id.tv_check)

            fun bind(label: String, onClick: () -> Unit) {
                tvLabel.text = label
                tvCheck.visibility = View.GONE
                itemView.setOnClickListener { onClick() }
                itemView.setOnKeyListener { _, keyCode, event ->
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        ->
                            if (event.action == KeyEvent.ACTION_UP) {
                                onClick()
                                true
                            } else {
                                false
                            }

                        else -> false
                    }
                }
            }
        }

        class ShortcutListAdapter(
            private val list: List<PlayerCustomShortcut>,
            private val onItemClick: (PlayerCustomShortcut) -> Unit,
        ) : RecyclerView.Adapter<ShortcutItemVh>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortcutItemVh {
                val view = LayoutInflater.from(parent.context).inflate(blbl.cat3399.R.layout.item_popup_choice, parent, false)
                return ShortcutItemVh(view)
            }

            override fun onBindViewHolder(holder: ShortcutItemVh, position: Int) {
                val item = list.getOrNull(position) ?: return
                holder.bind(
                    label = bindingLabel(item),
                    onClick = { onItemClick(item) },
                )
            }

            override fun getItemCount(): Int = list.size
        }

        class Controller {
            fun showManager(focusKeyCode: Int? = null) {
                var replacing = false
                val items = loadShortcuts()
                var recyclerForLayout: RecyclerView? = null
                val focusIndex =
                    if (items.isNotEmpty()) {
                        focusKeyCode?.let { key ->
                            items.indexOfFirst { it.keyCode == key }.takeIf { it >= 0 }
                        } ?: 0
                    } else {
                        0
                    }

                AppPopup.custom(
                    context = activity,
                    title = "自定义播放快捷键",
                    cancelable = true,
                    actions =
                        listOf(
                            PopupAction(
                                role = PopupActionRole.NEUTRAL,
                                text = "清空",
                                dismissOnClick = false,
                            ) {
                                if (items.isEmpty()) {
                                    AppToast.show(activity, "暂无快捷键")
                                    return@PopupAction
                                }
                                replacing = true
                                showClearConfirm(focusKeyCode = focusKeyCode)
                            },
                            PopupAction(
                                role = PopupActionRole.NEUTRAL,
                                text = "删除",
                                dismissOnClick = false,
                            ) {
                                if (items.isEmpty()) {
                                    AppToast.show(activity, "暂无快捷键")
                                    return@PopupAction
                                }
                                replacing = true
                                showDeletePicker(focusKeyCode = focusKeyCode)
                            },
                            PopupAction(
                                role = PopupActionRole.NEGATIVE,
                                text = "关闭",
                            ),
                            PopupAction(
                                role = PopupActionRole.POSITIVE,
                                text = "新增",
                                dismissOnClick = false,
                            ) {
                                replacing = true
                                showKeyCapture()
                            },
                        ),
                    preferredActionRole = PopupActionRole.POSITIVE,
                    autoFocus = true,
                    onModalAttached = { modalRoot ->
                        recyclerForLayout?.let { recycler ->
                            AppPopup.applyManagedListLayout(
                                modalRoot = modalRoot,
                                recycler = recycler,
                                itemCount = items.size,
                                focusIndex = focusIndex,
                            )
                        }
                    },
                    onDismiss = {
                        if (!replacing) renderer.showSection(sectionIndex, focusId = focusId)
                    },
                ) { dialogContext ->
                    val recycler =
                        (LayoutInflater.from(dialogContext).inflate(blbl.cat3399.R.layout.view_popup_choice_list, null, false) as RecyclerView).apply {
                            layoutManager = LinearLayoutManager(dialogContext)
                            itemAnimator = null
                        }
                    recyclerForLayout = recycler

                    recycler.adapter =
                        ShortcutListAdapter(items) { picked ->
                            replacing = true
                            showActionPicker(keyCode = picked.keyCode, currentAction = picked.action)
                        }

                    if (items.isNotEmpty()) {
                        recycler.scrollToPosition(focusIndex)
                        recycler.post {
                            val holder = recycler.findViewHolderForAdapterPosition(focusIndex)
                            (holder?.itemView ?: recycler.getChildAt(0))?.requestFocus()
                        }
                    }

                    recycler
                }
            }

            private fun showKeyCapture() {
                var forward = false
                var captureView: TextView? = null
                AppPopup.custom(
                    context = activity,
                    title = "请按下要绑定的按键",
                    cancelable = true,
                    actions = emptyList(),
                    preferredActionRole = null,
                    autoFocus = false,
                    onModalAttached = {
                        captureView?.post { captureView?.requestFocus() }
                    },
                    onDismiss = {
                        if (!forward) showManager()
                    },
                ) { dialogContext ->
                    val tv =
                        LayoutInflater.from(dialogContext)
                            .inflate(blbl.cat3399.R.layout.view_player_custom_shortcut_key_capture, null, false) as TextView
                    captureView = tv
                    tv.text = "请按下要绑定的按键\n（返回键取消）"
                    tv.setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        if (event.repeatCount > 0) return@setOnKeyListener true

                        // Let the popup host handle these as "cancel/back".
                        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                            return@setOnKeyListener false
                        }

                        if (keyCode == KeyEvent.KEYCODE_UNKNOWN || keyCode <= 0) return@setOnKeyListener true
                        if (PlayerCustomShortcutsStore.isForbiddenKeyCode(keyCode)) {
                            val msg =
                                when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                                    -> "确认键不允许绑定，请换用其他按键"
                                    else -> "该按键不允许绑定"
                                }
                            AppToast.show(activity, msg)
                            return@setOnKeyListener true
                        }

                        val existing = loadShortcuts().firstOrNull { it.keyCode == keyCode }?.action
                        forward = true
                        showActionPicker(keyCode = keyCode, currentAction = existing)
                        true
                    }
                    tv
                }
            }

            private fun showActionPicker(keyCode: Int, currentAction: PlayerCustomShortcutAction?) {
                var forward = false
                val options = PlayerCustomShortcutCatalog.actionOptions()

                val checked =
                    options.indexOfFirst { it.type == currentAction?.type }
                        .takeIf { it >= 0 } ?: 0

                AppPopup.singleChoice(
                    context = activity,
                    title = "选择动作（${keyLabel(keyCode)}）",
                    items = options.map { it.label },
                    checkedIndex = checked,
                    onDismiss = {
                        if (!forward) showManager(focusKeyCode = keyCode)
                    },
                ) { which, _ ->
                    val picked = options.getOrNull(which) ?: return@singleChoice
                    if (picked.requiresValue) {
                        forward = true
                        showValuePicker(keyCode = keyCode, actionType = picked.type, currentAction = currentAction)
                        return@singleChoice
                    }

                    val action = PlayerCustomShortcutCatalog.createAction(picked.type) ?: return@singleChoice

                    forward = true
                    upsert(PlayerCustomShortcut(keyCode = keyCode, action = action))
                    showManager(focusKeyCode = keyCode)
                }
            }

            private fun showValuePicker(keyCode: Int, actionType: String, currentAction: PlayerCustomShortcutAction?) {
                var forward = false
                val title = "${keyLabel(keyCode)} → ${PlayerCustomShortcutCatalog.actionTitle(actionType)}"

                fun cancelBackToActionPicker() {
                    if (!forward) showActionPicker(keyCode = keyCode, currentAction = currentAction)
                }

                val config =
                    PlayerCustomShortcutCatalog.valuePickerConfig(
                        type = actionType,
                        currentAction = currentAction,
                    ) ?: run {
                        AppToast.show(activity, "未知动作：$actionType")
                        showActionPicker(keyCode = keyCode, currentAction = currentAction)
                        return
                    }

                AppPopup.singleChoice(
                    context = activity,
                    title = title,
                    items = config.choices.map { it.label },
                    checkedIndex = config.checkedIndex,
                    onDismiss = { cancelBackToActionPicker() },
                ) { which, _ ->
                    val action = config.choices.getOrNull(which)?.action ?: return@singleChoice
                    forward = true
                    upsert(PlayerCustomShortcut(keyCode = keyCode, action = action))
                    showManager(focusKeyCode = keyCode)
                }
            }

            private fun showDeletePicker(focusKeyCode: Int?) {
                var forward = false
                val items = loadShortcuts()
                val labels = items.map { bindingLabel(it) }
                val checked = focusKeyCode?.let { k -> items.indexOfFirst { it.keyCode == k }.takeIf { it >= 0 } } ?: 0
                AppPopup.singleChoice(
                    context = activity,
                    title = "删除快捷键",
                    items = labels.ifEmpty { listOf("暂无快捷键") },
                    checkedIndex = checked,
                    onDismiss = {
                        if (!forward) showManager(focusKeyCode = focusKeyCode)
                    },
                ) { which, _ ->
                    val picked = items.getOrNull(which) ?: return@singleChoice
                    forward = true
                    removeBinding(picked.keyCode)
                    showManager()
                }
            }

            private fun showClearConfirm(focusKeyCode: Int?) {
                var forward = false
                AppPopup.confirm(
                    context = activity,
                    title = "清空快捷键",
                    message = "确定清空所有自定义播放快捷键？",
                    positiveText = "清空",
                    negativeText = "取消",
                    cancelable = true,
                    onPositive = {
                        forward = true
                        clearAll()
                        showManager()
                    },
                    onNegative = {
                        forward = true
                        showManager(focusKeyCode = focusKeyCode)
                    },
                    onDismiss = {
                        if (!forward) showManager(focusKeyCode = focusKeyCode)
                    },
                )
            }
        }

        Controller().showManager()
    }

    private fun showCustomPageContentDialog(sectionIndex: Int, focusId: SettingId) {
        fun loadConfig(): CustomPageConfig = BiliClient.prefs.customPageConfig

        fun saveConfig(config: CustomPageConfig) {
            BiliClient.prefs.customPageConfig = config
            renderer.refreshSection(focusId)
        }

        fun moveTab(
            tabs: List<CustomPageTabConfig>,
            fromIndex: Int,
            toIndex: Int,
        ): List<CustomPageTabConfig> {
            if (fromIndex !in tabs.indices || toIndex !in tabs.indices) return tabs
            val out = tabs.toMutableList()
            val item = out.removeAt(fromIndex)
            out.add(toIndex, item)
            return out
        }

        class TabItemVh(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvLabel: TextView = itemView.findViewById(blbl.cat3399.R.id.tv_label)
            private val tvCheck: TextView = itemView.findViewById(blbl.cat3399.R.id.tv_check)

            fun bind(label: String, onClick: () -> Unit) {
                tvLabel.text = label
                tvCheck.visibility = View.GONE
                itemView.setOnClickListener { onClick() }
                itemView.setOnKeyListener { _, keyCode, event ->
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        ->
                            if (event.action == KeyEvent.ACTION_UP) {
                                onClick()
                                true
                            } else {
                                false
                            }

                        else -> false
                    }
                }
            }
        }

        class TabListAdapter(
            private val list: List<CustomPageTabConfig>,
            private val onItemClick: (CustomPageTabConfig) -> Unit,
        ) : RecyclerView.Adapter<TabItemVh>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabItemVh {
                val view = LayoutInflater.from(parent.context).inflate(blbl.cat3399.R.layout.item_popup_choice, parent, false)
                return TabItemVh(view)
            }

            override fun onBindViewHolder(holder: TabItemVh, position: Int) {
                val item = list.getOrNull(position) ?: return
                holder.bind(
                    label = CustomPageTabRegistry.settingsLabelForConfig(item),
                    onClick = { onItemClick(item) },
                )
            }

            override fun getItemCount(): Int = list.size
        }

        class Controller {
            fun showManager(focusStableKey: String? = null) {
                var replacing = false
                val config = loadConfig()
                val tabs = config.tabs
                var recyclerForLayout: RecyclerView? = null
                val focusIndex =
                    if (tabs.isNotEmpty()) {
                        focusStableKey?.let { key ->
                            tabs.indexOfFirst { it.stableKey() == key }.takeIf { it >= 0 }
                        } ?: 0
                    } else {
                        0
                    }

                AppPopup.custom(
                    context = activity,
                    title = "自定义页内容",
                    cancelable = true,
                    actions =
                        listOf(
                            PopupAction(
                                role = PopupActionRole.NEUTRAL,
                                text = "清空",
                                dismissOnClick = false,
                            ) {
                                if (tabs.isEmpty()) {
                                    AppToast.show(activity, "暂无内容")
                                    return@PopupAction
                                }
                                replacing = true
                                showClearConfirm(focusStableKey = focusStableKey)
                            },
                            PopupAction(role = PopupActionRole.NEGATIVE, text = "关闭"),
                            PopupAction(
                                role = PopupActionRole.POSITIVE,
                                text = "新增",
                                dismissOnClick = false,
                            ) {
                                replacing = true
                                showAddPicker()
                            },
                        ),
                    preferredActionRole = PopupActionRole.POSITIVE,
                    autoFocus = true,
                    onModalAttached = { modalRoot ->
                        recyclerForLayout?.let { recycler ->
                            AppPopup.applyManagedListLayout(
                                modalRoot = modalRoot,
                                recycler = recycler,
                                itemCount = tabs.size,
                                focusIndex = focusIndex,
                            )
                        }
                    },
                    onDismiss = {
                        if (!replacing) renderer.showSection(sectionIndex, focusId = focusId)
                    },
                ) { dialogContext ->
                    if (tabs.isEmpty()) {
                        return@custom (LayoutInflater.from(dialogContext)
                            .inflate(blbl.cat3399.R.layout.view_popup_message, null, false) as TextView).apply {
                            text = "暂无内容，按“新增”添加来源。"
                        }
                    }

                    val recycler =
                        (LayoutInflater.from(dialogContext).inflate(blbl.cat3399.R.layout.view_popup_choice_list, null, false) as RecyclerView).apply {
                            layoutManager = LinearLayoutManager(dialogContext)
                            itemAnimator = null
                        }
                    recyclerForLayout = recycler
                    recycler.adapter =
                        TabListAdapter(tabs) { picked ->
                            replacing = true
                            showItemActions(picked.stableKey())
                        }

                    recycler.scrollToPosition(focusIndex)
                    recycler.post {
                        val holder = recycler.findViewHolderForAdapterPosition(focusIndex)
                        (holder?.itemView ?: recycler.getChildAt(0))?.requestFocus()
                    }
                    recycler
                }
            }

            private fun showAddPicker() {
                var forward = false
                val config = loadConfig()
                val groups = CustomPageTabRegistry.availableAddGroups(config)
                if (groups.isEmpty()) {
                    AppToast.show(activity, "可添加的来源已经用完")
                    showManager()
                    return
                }

                AppPopup.singleChoice(
                    context = activity,
                    title = "添加来源",
                    items = groups.map { it.label },
                    checkedIndex = 0,
                    onDismiss = {
                        if (!forward) showManager()
                    },
                ) { which, _ ->
                    val picked = groups.getOrNull(which) ?: return@singleChoice
                    forward = true
                    val directOption = picked.directOption
                    if (directOption != null) {
                        val current = loadConfig()
                        saveConfig(current.copy(tabs = current.tabs + directOption.config))
                        showManager(focusStableKey = directOption.config.stableKey())
                    } else {
                        showAddLeafPicker(group = picked)
                    }
                }
            }

            private fun showAddLeafPicker(group: blbl.cat3399.feature.custom.CustomPageAddGroup) {
                var forward = false
                val config = loadConfig()
                val options = CustomPageTabRegistry.availableAddOptionsForGroup(group.key, config)
                if (options.isEmpty()) {
                    AppToast.show(activity, "该分类下暂无可添加页面")
                    showAddPicker()
                    return
                }

                AppPopup.singleChoice(
                    context = activity,
                    title = "添加${group.label}页面",
                    items = options.map { it.label },
                    checkedIndex = 0,
                    onDismiss = {
                        if (!forward) showAddPicker()
                    },
                ) { which, _ ->
                    val picked = options.getOrNull(which) ?: return@singleChoice
                    forward = true
                    val current = loadConfig()
                    saveConfig(current.copy(tabs = current.tabs + picked.config))
                    showManager(focusStableKey = picked.config.stableKey())
                }
            }

            private fun showItemActions(focusStableKey: String) {
                var forward = false
                val config = loadConfig()
                val index = config.tabs.indexOfFirst { it.stableKey() == focusStableKey }
                if (index < 0) {
                    showManager()
                    return
                }
                val tab = config.tabs[index]

                data class Action(
                    val label: String,
                    val nextConfig: CustomPageConfig,
                    val nextFocusStableKey: String?,
                )

                val actions =
                    buildList {
                        if (index > 0) {
                            val nextTabs = moveTab(config.tabs, fromIndex = index, toIndex = index - 1)
                            add(
                                Action(
                                    label = "上移",
                                    nextConfig = config.copy(tabs = nextTabs),
                                    nextFocusStableKey = nextTabs.getOrNull(index - 1)?.stableKey(),
                                ),
                            )
                        }
                        if (index < config.tabs.lastIndex) {
                            val nextTabs = moveTab(config.tabs, fromIndex = index, toIndex = index + 1)
                            add(
                                Action(
                                    label = "下移",
                                    nextConfig = config.copy(tabs = nextTabs),
                                    nextFocusStableKey = nextTabs.getOrNull(index + 1)?.stableKey(),
                                ),
                            )
                        }
                        val nextTabs = config.tabs.filterIndexed { pos, _ -> pos != index }
                        add(
                            Action(
                                label = "删除",
                                nextConfig = config.copy(tabs = nextTabs),
                                nextFocusStableKey = nextTabs.getOrNull(index)?.stableKey() ?: nextTabs.lastOrNull()?.stableKey(),
                            ),
                        )
                    }

                AppPopup.singleChoice(
                    context = activity,
                    title = CustomPageTabRegistry.settingsLabelForConfig(tab),
                    items = actions.map { it.label },
                    checkedIndex = 0,
                    onDismiss = {
                        if (!forward) showManager(focusStableKey = focusStableKey)
                    },
                ) { which, _ ->
                    val picked = actions.getOrNull(which) ?: return@singleChoice
                    forward = true
                    saveConfig(picked.nextConfig)
                    showManager(focusStableKey = picked.nextFocusStableKey)
                }
            }

            private fun showClearConfirm(focusStableKey: String?) {
                var forward = false
                AppPopup.confirm(
                    context = activity,
                    title = "清空自定义页",
                    message = "确定清空所有自定义页内容？",
                    positiveText = "清空",
                    negativeText = "取消",
                    cancelable = true,
                    onPositive = {
                        forward = true
                        val current = loadConfig()
                        saveConfig(current.copy(tabs = emptyList()))
                        showManager()
                    },
                    onNegative = {
                        forward = true
                        showManager(focusStableKey = focusStableKey)
                    },
                    onDismiss = {
                        if (!forward) showManager(focusStableKey = focusStableKey)
                    },
                )
            }
        }

        Controller().showManager()
    }

    private fun showUserAgentDialog(sectionIndex: Int, focusId: SettingId) {
        val prefs = BiliClient.prefs
        AppPopup.input(
            context = activity,
            title = "User-Agent",
            initial = prefs.userAgent,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_NORMAL,
            minLines = 3,
            positiveText = "保存",
            negativeText = "取消",
            neutralText = "重置默认",
            validate = { text ->
                val ua = text.trim()
                if (ua.isBlank()) {
                    AppToast.show(activity, "User-Agent 不能为空")
                    false
                } else {
                    true
                }
            },
            onPositive = { text ->
                val ua = text.trim()
                prefs.userAgent = ua
                AppToast.show(activity, "已更新 User-Agent")
                renderer.showSection(sectionIndex, focusId = focusId)
            },
            onNeutral = {
                prefs.userAgent = blbl.cat3399.core.prefs.AppPrefs.DEFAULT_UA
                AppToast.show(activity, "已重置 User-Agent")
                renderer.showSection(sectionIndex, focusId = focusId)
            },
        )
    }

    private fun showPlayerAutoSkipServerBaseUrlDialog(sectionIndex: Int, focusId: SettingId) {
        val prefs = BiliClient.prefs
        AppPopup.input(
            context = activity,
            title = "空降助手服务器地址",
            initial = prefs.playerAutoSkipServerBaseUrl,
            hint = "例如 https://bsbsb.top",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            minLines = 1,
            positiveText = "保存",
            negativeText = "取消",
            neutralText = "重置默认",
            validate = { text ->
                val normalized = AppPrefs.normalizePlayerAutoSkipServerBaseUrl(text)
                if (normalized == null) {
                    AppToast.show(activity, "请输入有效的 http:// 或 https:// 地址")
                    false
                } else {
                    true
                }
            },
            onPositive = { text ->
                val url = AppPrefs.normalizePlayerAutoSkipServerBaseUrl(text) ?: return@input
                prefs.playerAutoSkipServerBaseUrl = url
                evictNetworkConnections()
                AppToast.show(activity, "已更新空降助手服务器地址")
                renderer.showSection(sectionIndex, focusId = focusId)
            },
            onNeutral = {
                prefs.playerAutoSkipServerBaseUrl = AppPrefs.DEFAULT_PLAYER_AUTO_SKIP_SERVER_BASE_URL
                evictNetworkConnections()
                AppToast.show(activity, "已重置空降助手服务器地址")
                renderer.showSection(sectionIndex, focusId = focusId)
            },
        )
    }

    private fun showPlayerShortSeekStepDialog(sectionIndex: Int, focusId: SettingId) {
        val prefs = BiliClient.prefs
        val options = AppPrefs.PLAYER_SHORT_SEEK_STEP_SECONDS_OPTIONS.toList()
        showChoiceDialog(
            title = "点按快进秒数",
            items = options.map(SettingsText::seekStepSecondsText),
            current = SettingsText.seekStepSecondsText(prefs.playerShortSeekStepSeconds),
        ) { selected ->
            val value =
                options.firstOrNull { SettingsText.seekStepSecondsText(it) == selected }
                    ?: AppPrefs.PLAYER_SHORT_SEEK_STEP_SECONDS_DEFAULT
            prefs.playerShortSeekStepSeconds = value
            renderer.showSection(sectionIndex, focusId = focusId)
        }
    }

    private fun showPlayerHoldScrubTraverseSecondsDialog(sectionIndex: Int, focusId: SettingId) {
        val prefs = BiliClient.prefs
        showPlayerHoldScrubSecondsDialog(
            title = "拖完整个视频所需时间",
            currentSeconds = prefs.playerHoldScrubTraverseSeconds,
            sectionIndex = sectionIndex,
            focusId = focusId,
        ) { value ->
            prefs.playerHoldScrubTraverseSeconds = value
        }
    }

    private fun showPlayerHoldScrubFixedStepSecondsDialog(sectionIndex: Int, focusId: SettingId) {
        val prefs = BiliClient.prefs
        showPlayerHoldScrubSecondsDialog(
            title = "固定时间拖动进度条间隔",
            currentSeconds = prefs.playerHoldScrubFixedStepSeconds,
            sectionIndex = sectionIndex,
            focusId = focusId,
        ) { value ->
            prefs.playerHoldScrubFixedStepSeconds = value
        }
    }

    private fun showPlayerHoldScrubSecondsDialog(
        title: String,
        currentSeconds: Int,
        sectionIndex: Int,
        focusId: SettingId,
        onSelected: (Int) -> Unit,
    ) {
        val options = AppPrefs.PLAYER_HOLD_SCRUB_SECONDS_OPTIONS.toList()
        showChoiceDialog(
            title = title,
            items = options.map(SettingsText::seekStepSecondsText),
            current = SettingsText.seekStepSecondsText(currentSeconds),
        ) { selected ->
            val value =
                options.firstOrNull { SettingsText.seekStepSecondsText(it) == selected }
                    ?: AppPrefs.PLAYER_HOLD_SCRUB_SECONDS_DEFAULT
            onSelected(value)
            renderer.showSection(sectionIndex, focusId = focusId)
        }
    }

    private fun showClearLoginDialog(sectionIndex: Int, focusId: SettingId) {
        AppPopup.confirm(
            context = activity,
            title = "清除登录",
            message = "将清除 Cookie（SESSDATA 等），需要重新登录。确定继续吗？",
            positiveText = "确定清除",
            negativeText = "取消",
            cancelable = true,
            onPositive = {
                BiliClient.clearLoginSession()
                AppToast.show(activity, "已清除 Cookie")
                renderer.showSection(sectionIndex, focusId = focusId)
            },
        )
    }

    private fun showClearCacheDialog(sectionIndex: Int, focusId: SettingId) {
        if (clearCacheJob?.isActive == true) {
            AppToast.show(activity, "清理中…")
            return
        }
        if (testUpdateJob?.isActive == true) {
            AppToast.show(activity, "下载中，稍后再试")
            return
        }

        AppPopup.confirm(
            context = activity,
            title = "清理缓存",
            message = "确定清理缓存？",
            positiveText = "清理",
            negativeText = "取消",
            cancelable = true,
            onPositive = { startClearCache(sectionIndex, focusId) },
        )
    }

    private fun startClearCache(sectionIndex: Int, focusId: SettingId) {
        cacheSizeJob?.cancel()
        val popup =
            AppPopup.progress(
                context = activity,
                title = "清理中",
                status = "清理中…",
                negativeText = "取消",
                cancelable = false,
                onNegative = { clearCacheJob?.cancel() },
            )

        clearCacheJob =
            activity.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val dirs = listOfNotNull(activity.cacheDir, activity.externalCacheDir)
                        for (dir in dirs) {
                            for (child in (dir.listFiles() ?: emptyArray())) {
                                currentCoroutineContext().ensureActive()
                                runCatching { child.deleteRecursively() }
                            }
                        }
                    }

                    popup?.dismiss()
                    AppToast.show(activity, "已清理缓存")
                    state.cacheSizeBytes = 0L
                    renderer.showSection(sectionIndex, focusId = focusId)
                    updateCacheSize(force = true)
                } catch (_: CancellationException) {
                    popup?.dismiss()
                    AppToast.show(activity, "已取消")
                } catch (t: Throwable) {
                    AppLog.w("Settings", "clear cache failed: ${t.message}", t)
                    popup?.dismiss()
                    AppToast.showLong(activity, "清理失败")
                }
            }
    }

    private fun updateCacheSize(force: Boolean) {
        if (!force && state.cacheSizeBytes != null) return
        if (cacheSizeJob?.isActive == true) return
        cacheSizeJob =
            activity.lifecycleScope.launch {
                val size =
                    withContext(Dispatchers.IO) {
                        val dirs = listOfNotNull(activity.cacheDir, activity.externalCacheDir)
                        dirs.sumOf { dirChildrenSizeBytes(it) }.coerceAtLeast(0L)
                    }
                val old = state.cacheSizeBytes
                state.cacheSizeBytes = size
                if (old != size) {
                    renderer.showSection(state.currentSectionIndex, keepScroll = true)
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

    private fun ensureTestUpdateChecked(force: Boolean, refreshUi: Boolean = true) {
        if (testUpdateJob?.isActive == true) return
        if (testUpdateCheckJob?.isActive == true) return
        if (state.testUpdateCheckState is TestUpdateCheckState.Checking) return

        val now = System.currentTimeMillis()
        val last = state.testUpdateCheckedAtMs
        val hasFreshResult =
            !force &&
                last > 0 &&
                now - last < SettingsConstants.UPDATE_CHECK_TTL_MS &&
                state.testUpdateCheckState !is TestUpdateCheckState.Idle &&
                state.testUpdateCheckState !is TestUpdateCheckState.Checking
        if (hasFreshResult) return

        state.testUpdateCheckState = TestUpdateCheckState.Checking
        if (refreshUi) renderer.refreshAboutSectionKeepPosition()

        testUpdateCheckJob =
            activity.lifecycleScope.launch {
                try {
                    val latest = ApkUpdater.fetchLatestVersionName()
                    val current = BuildConfig.VERSION_NAME
                    state.testUpdateCheckState =
                        if (ApkUpdater.isRemoteNewer(latest, current)) {
                            TestUpdateCheckState.UpdateAvailable(latest)
                        } else {
                            TestUpdateCheckState.Latest(latest)
                        }
                    state.testUpdateCheckedAtMs = System.currentTimeMillis()
                } catch (_: CancellationException) {
                    return@launch
                } catch (t: Throwable) {
                    state.testUpdateCheckState = TestUpdateCheckState.Error(t.message ?: "检查失败")
                    state.testUpdateCheckedAtMs = System.currentTimeMillis()
                }
                renderer.refreshAboutSectionKeepPosition()
            }
    }

    private fun startTestUpdateDownload(latestVersionHint: String? = null) {
        if (testUpdateJob?.isActive == true) {
            AppToast.show(activity, "正在下载更新…")
            return
        }

        val now = System.currentTimeMillis()
        val cooldownLeftMs = ApkUpdater.cooldownLeftMs(now)
        if (cooldownLeftMs > 0) {
            AppToast.show(activity, "操作太频繁，请稍后再试（${(cooldownLeftMs / 1000).coerceAtLeast(1)}s）")
            return
        }

        val popup =
            AppPopup.progress(
                context = activity,
                title = "下载更新",
                status = "检查更新…",
                negativeText = "取消",
                cancelable = false,
                onNegative = { testUpdateJob?.cancel() },
            )

        testUpdateJob =
            activity.lifecycleScope.launch {
                try {
                    val currentVersion = BuildConfig.VERSION_NAME
                    val latestVersion = latestVersionHint ?: ApkUpdater.fetchLatestVersionName()
                    if (!ApkUpdater.isRemoteNewer(latestVersion, currentVersion)) {
                        state.testUpdateCheckState = TestUpdateCheckState.Latest(latestVersion)
                        state.testUpdateCheckedAtMs = System.currentTimeMillis()
                        renderer.refreshAboutSectionKeepPosition()
                        popup?.dismiss()
                        AppToast.show(activity, "已是最新版（当前：$currentVersion）")
                        return@launch
                    }

                    state.testUpdateCheckState = TestUpdateCheckState.UpdateAvailable(latestVersion)
                    state.testUpdateCheckedAtMs = System.currentTimeMillis()
                    renderer.refreshAboutSectionKeepPosition()

                    popup?.updateStatus("准备下载…（最新：$latestVersion）")
                    popup?.updateProgress(null)

                    ApkUpdater.markStarted(now)
                    val apkFile =
                        ApkUpdater.downloadApkToCache(
                            context = activity,
                            url = ApkUpdater.TEST_APK_URL,
                        ) { dlState ->
                            when (dlState) {
                                ApkUpdater.Progress.Connecting -> {
                                    popup?.updateProgress(null)
                                    popup?.updateStatus("连接中…")
                                }

                                is ApkUpdater.Progress.Downloading -> {
                                    val pct = dlState.percent
                                    if (pct != null) {
                                        popup?.updateProgress(pct.coerceIn(0, 100))
                                        popup?.updateStatus("下载中… ${pct.coerceIn(0, 100)}% ${dlState.hint}")
                                    } else {
                                        popup?.updateProgress(null)
                                        popup?.updateStatus("下载中… ${dlState.hint}")
                                    }
                                }
                            }
                        }

                    popup?.updateStatus("准备安装…")
                    popup?.updateProgress(null)
                    popup?.dismiss()
                    ApkUpdater.installApk(activity, apkFile)
                } catch (_: CancellationException) {
                    popup?.dismiss()
                    AppToast.show(activity, "已取消更新")
                } catch (t: Throwable) {
                    AppLog.w("TestUpdate", "update failed: ${t.message}")
                    popup?.dismiss()
                    AppToast.showLong(activity, "更新失败：${t.message ?: "未知错误"}")
                }
            }
    }

    private fun showProjectDialog() {
        AppPopup.custom(
            context = activity,
            title = "项目地址",
            cancelable = true,
            actions =
                listOf(
                    PopupAction(role = PopupActionRole.NEGATIVE, text = "关闭"),
                    PopupAction(role = PopupActionRole.NEUTRAL, text = "复制") {
                        copyToClipboard(label = "项目地址", text = SettingsConstants.PROJECT_URL, toastText = "已复制项目地址")
                    },
                    PopupAction(role = PopupActionRole.POSITIVE, text = "打开") { openUrl(SettingsConstants.PROJECT_URL) },
                ),
            preferredActionRole = PopupActionRole.POSITIVE,
            content = { dialogContext ->
                val tv =
                    android.view.LayoutInflater.from(dialogContext)
                        .inflate(blbl.cat3399.R.layout.view_popup_message, null, false) as TextView
                tv.text = SettingsConstants.PROJECT_URL
                tv
            },
        )
    }

    private fun openUrl(url: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW).setData(Uri.parse(url)))
        }.onFailure {
            AppToast.show(activity, "无法打开链接")
        }
    }

    private fun copyToClipboard(label: String, text: String, toastText: String? = null) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            AppToast.show(activity, "无法访问剪贴板")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        AppToast.show(activity, toastText ?: "已复制：$text")
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

    private fun showGaiaVgateDialog(sectionIndex: Int, focusId: SettingId) {
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

        AppPopup.custom(
            context = activity,
            title = "风控验证",
            cancelable = true,
            actions =
                listOf(
                    PopupAction(role = PopupActionRole.NEGATIVE, text = "关闭"),
                    PopupAction(role = PopupActionRole.NEUTRAL, text = "编辑 v_voucher") {
                        showGaiaVgateVoucherDialog(sectionIndex, focusId)
                    },
                    PopupAction(role = PopupActionRole.POSITIVE, text = if (hasVoucher) "开始验证" else "粘贴 v_voucher") {
                        if (hasVoucher) {
                            gaiaVgateLauncher.launch(
                                Intent(activity, GaiaVgateActivity::class.java)
                                    .putExtra(GaiaVgateActivity.EXTRA_V_VOUCHER, vVoucher),
                            )
                        } else {
                            showGaiaVgateVoucherDialog(sectionIndex, focusId)
                        }
                    },
                ),
            preferredActionRole = PopupActionRole.POSITIVE,
            content = { dialogContext ->
                val tv =
                    android.view.LayoutInflater.from(dialogContext)
                        .inflate(blbl.cat3399.R.layout.view_popup_message, null, false) as TextView
                tv.text = msg
                tv
            },
        )
    }

    private fun showGaiaVgateVoucherDialog(sectionIndex: Int, focusId: SettingId) {
        val prefs = BiliClient.prefs
        AppPopup.input(
            context = activity,
            title = "编辑 v_voucher",
            initial = prefs.gaiaVgateVVoucher.orEmpty(),
            hint = "粘贴 v_voucher",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
            minLines = 1,
            positiveText = "保存",
            negativeText = "取消",
            neutralText = "清除",
            onPositive = { text ->
                val v = text.trim()
                prefs.gaiaVgateVVoucher = v.takeIf { it.isNotBlank() }
                prefs.gaiaVgateVVoucherSavedAtMs = if (v.isNotBlank()) System.currentTimeMillis() else -1L
                AppToast.show(activity, if (v.isNotBlank()) "已保存 v_voucher" else "已清除 v_voucher")
                renderer.showSection(sectionIndex, focusId = focusId)
            },
            onNeutral = {
                prefs.gaiaVgateVVoucher = null
                prefs.gaiaVgateVVoucherSavedAtMs = -1L
                AppToast.show(activity, "已清除 v_voucher")
                renderer.showSection(sectionIndex, focusId = focusId)
            },
        )
    }
}
