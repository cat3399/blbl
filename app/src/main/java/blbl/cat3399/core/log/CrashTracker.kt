package blbl.cat3399.core.log

import android.content.Context
import blbl.cat3399.BuildConfig
import java.io.File

object CrashTracker {
    data class CrashInfo(val crashAtMs: Long, val threadName: String, val stacktrace: String)

    @Volatile
    private var fileStore: CrashFileStore? = null

    fun install(context: Context) {
        val store = store(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                store.record(thread.name, throwable)
            } catch (error: Throwable) {
                AppLog.reportFileFailure("CrashTracker", "write crash file failed", error)
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    fun loadLastCrash(context: Context): CrashInfo? = try {
        store(context).latest()?.let { CrashInfo(it.crashAtMs, it.threadName, it.stacktrace) }
    } catch (error: Exception) {
        AppLog.reportFileFailure("CrashTracker", "load crash records failed", error)
        null
    }

    fun wasPrompted(context: Context, crashAtMs: Long): Boolean = store(context).wasPrompted(crashAtMs)

    fun markPrompted(context: Context, crashAtMs: Long) = store(context).markPrompted(crashAtMs)

    internal fun snapshotTo(context: Context, directory: File): List<CrashFileStore.Exported> = store(context).copyTo(directory)

    @Synchronized
    private fun store(context: Context): CrashFileStore {
        return fileStore ?: CrashFileStore(
            directory = AppLog.logDir(context),
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            buildType = BuildConfig.BUILD_TYPE,
            onFailure = { message, error -> AppLog.reportFileFailure("CrashTracker", message, error) },
        ).also { fileStore = it }
    }
}
