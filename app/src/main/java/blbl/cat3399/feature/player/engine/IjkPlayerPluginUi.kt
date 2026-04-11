package blbl.cat3399.feature.player.engine

import androidx.lifecycle.lifecycleScope
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.popup.AppPopup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import blbl.cat3399.core.util.DeviceAbi
import kotlinx.coroutines.launch

internal object IjkPlayerPluginUi {
    @Volatile
    private var installJob: Job? = null

    fun ensureInstalled(activity: BaseActivity, onInstalled: () -> Unit) {
        val status = IjkPlayerPlugin.status(activity)
        if (status == IjkPlayerPlugin.InstallStatus.Installed) {
            onInstalled()
            return
        }

        val abi = IjkPlayerPlugin.deviceAbi()
        if (abi.isNullOrBlank()) {
            AppToast.showLong(activity, "当前设备不支持 IjkPlayer（ABI=${DeviceAbi.getSupportedAbis().joinToString()}）")
            return
        }

        if (installJob?.isActive == true) {
            AppToast.show(activity, "IjkPlayer 插件下载中…")
            return
        }

        var job: Job? = null
        val isUpdate = status == IjkPlayerPlugin.InstallStatus.NeedsUpdate
        val popup =
            AppPopup.progress(
                context = activity,
                title = if (isUpdate) "更新 IjkPlayer 插件" else "下载 IjkPlayer 插件",
                status = "连接中…",
                negativeText = "取消",
                cancelable = false,
                onNegative = { job?.cancel() },
            )

        job =
            activity.lifecycleScope.launch {
                try {
                    IjkPlayerPlugin.installIfNeeded(activity) { state ->
                        when (state) {
                            IjkPlayerPlugin.Progress.Connecting -> {
                                popup?.updateProgress(null)
                                popup?.updateStatus("连接中…")
                            }

                            is IjkPlayerPlugin.Progress.Downloading -> {
                                val pct = state.percent
                                if (pct != null) {
                                    popup?.updateProgress(pct.coerceIn(0, 100))
                                    popup?.updateStatus("下载中… ${pct.coerceIn(0, 100)}% ${state.hint}")
                                } else {
                                    popup?.updateProgress(null)
                                    popup?.updateStatus("下载中… ${state.hint}")
                                }
                            }

                            is IjkPlayerPlugin.Progress.Extracting -> {
                                popup?.updateProgress(null)
                                popup?.updateStatus("解压中… ${state.hint}")
                            }
                        }
                    }

                    popup?.dismiss()
                    AppToast.show(activity, if (isUpdate) "IjkPlayer 插件已更新（$abi）" else "IjkPlayer 插件已就绪（$abi）")
                    onInstalled()
                } catch (_: CancellationException) {
                    popup?.dismiss()
                    AppToast.show(activity, if (isUpdate) "已取消更新" else "已取消下载")
                } catch (t: Throwable) {
                    AppLog.w("IjkPlugin", "install failed: ${t.message}", t)
                    popup?.dismiss()
                    AppToast.showLong(activity, "IjkPlayer 插件${if (isUpdate) "更新" else "下载"}失败：${t.message ?: "未知错误"}")
                } finally {
                    if (installJob === job) installJob = null
                }
            }

        installJob = job
    }
}
