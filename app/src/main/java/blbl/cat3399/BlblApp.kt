package blbl.cat3399

import android.app.Application
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.WebCookieMaintainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BlblApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.i("BlblApp", "onCreate")
        BiliClient.init(this)
        appScope.launch {
            runCatching { WebCookieMaintainer.ensureDailyMaintenance() }
                .onFailure { AppLog.w("BlblApp", "daily maintenance failed", it) }
        }
    }

    companion object {
        @JvmStatic
        lateinit var instance: BlblApp
            private set

        fun launchIo(block: suspend CoroutineScope.() -> Unit) {
            instance.appScope.launch(block = block)
        }
    }
}
