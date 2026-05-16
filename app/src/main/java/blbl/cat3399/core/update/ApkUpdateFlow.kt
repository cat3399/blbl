package blbl.cat3399.core.update

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.BuildConfig
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupAction
import blbl.cat3399.core.ui.popup.PopupActionRole
import blbl.cat3399.core.ui.popup.PopupHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object ApkUpdateFlow {
    private var activeJob: Job? = null

    fun showUpdatePrompt(
        activity: ComponentActivity,
        update: ApkUpdater.RemoteUpdate,
        onSkipVersion: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        onUpdate: (ApkUpdater.RemoteUpdate) -> Unit,
    ): PopupHandle? {
        val versions = update.versions.ifEmpty { listOf(update) }
        var selectedIndex = 0
        var selectedUpdate = versions[selectedIndex]
        var titleView: TextView? = null
        var changelogView: TextView? = null

        fun renderSelectedVersion() {
            selectedUpdate = versions[selectedIndex]
            val titlePrefix = if (selectedIndex == 0) "发现新版本" else "版本"
            titleView?.text = "$titlePrefix ${selectedUpdate.versionName}"
            changelogView?.text = selectedUpdate.displayChangelog
        }

        return AppPopup.custom(
            context = activity,
            title = "发现新版本 ${update.versionName}",
            cancelable = true,
            actions =
                listOf(
                    PopupAction(role = PopupActionRole.NEGATIVE, text = "本次关闭"),
                    PopupAction(role = PopupActionRole.NEUTRAL, text = "此版本不再提醒") {
                        BiliClient.prefs.autoUpdateIgnoredVersionName = update.versionName
                        onSkipVersion?.invoke()
                    },
                    PopupAction(role = PopupActionRole.NEUTRAL, text = "上一版", dismissOnClick = false) {
                        if (selectedIndex >= versions.lastIndex) {
                            AppToast.show(activity, "已经是最早版本")
                        } else {
                            selectedIndex++
                            renderSelectedVersion()
                        }
                    },
                    PopupAction(role = PopupActionRole.NEUTRAL, text = "下一版", dismissOnClick = false) {
                        if (selectedIndex <= 0) {
                            AppToast.show(activity, "已经是最新版本")
                        } else {
                            selectedIndex--
                            renderSelectedVersion()
                        }
                    },
                    PopupAction(role = PopupActionRole.POSITIVE, text = "立即更新") {
                        onUpdate(selectedUpdate)
                    },
                ),
            preferredActionRole = PopupActionRole.POSITIVE,
            onModalAttached = { modalRoot ->
                titleView = modalRoot.findViewById(R.id.tv_title)
                renderSelectedVersion()
            },
            onDismiss = onDismiss,
        ) { dialogContext ->
            val scroll =
                ScrollView(dialogContext).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                }
            val tv =
                LayoutInflater.from(dialogContext)
                    .inflate(R.layout.view_popup_message, scroll, false) as TextView
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            tv.setLineSpacing(2f, 1.05f)
            changelogView = tv
            tv.text = selectedUpdate.displayChangelog
            scroll.addView(tv)
            scroll
        }
    }

    fun startDownloadAndInstall(
        activity: ComponentActivity,
        latestVersionHint: String? = null,
        apkUrl: String? = null,
        onResolved: ((latestVersion: String, isNewer: Boolean) -> Unit)? = null,
    ): Job? {
        if (activeJob?.isActive == true) {
            AppToast.show(activity, "正在下载更新…")
            return null
        }

        val now = System.currentTimeMillis()
        val cooldownLeftMs = ApkUpdater.cooldownLeftMs(now)
        if (cooldownLeftMs > 0) {
            AppToast.show(activity, "操作太频繁，请稍后再试（${(cooldownLeftMs / 1000).coerceAtLeast(1)}s）")
            return null
        }

        val popup =
            AppPopup.progress(
                context = activity,
                title = "下载更新",
                status = "检查更新…",
                negativeText = "取消",
                cancelable = false,
                onNegative = { activeJob?.cancel() },
            )

        val job =
            activity.lifecycleScope.launch {
                try {
                    val currentVersion = BuildConfig.VERSION_NAME
                    val latestVersion = latestVersionHint ?: ApkUpdater.fetchLatestUpdate().versionName
                    val isNewer = ApkUpdater.isRemoteNewer(latestVersion, currentVersion)
                    onResolved?.invoke(latestVersion, isNewer)
                    if (!isNewer && apkUrl == null) {
                        popup?.dismiss()
                        AppToast.show(activity, "已是最新版（当前：$currentVersion）")
                        return@launch
                    }

                    popup?.updateStatus("准备下载…（版本：$latestVersion）")
                    popup?.updateProgress(null)

                    ApkUpdater.markStarted(now)
                    val apkFile =
                        ApkUpdater.downloadApkToCache(
                            context = activity,
                            url = apkUrl ?: latestVersionHint?.let(ApkUpdater::apkUrlFor) ?: ApkUpdater.TEST_APK_URL,
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
                    AppLog.w("Update", "update failed: ${t.message}", t)
                    popup?.dismiss()
                    AppToast.showLong(activity, "更新失败：${t.message ?: "未知错误"}")
                } finally {
                    if (activeJob === this.coroutineContext[Job]) {
                        activeJob = null
                    }
                }
            }
        activeJob = job
        return job
    }
}
