package blbl.cat3399.core.log

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val PREFIX = "BLBL"
    private const val LOG_DIR_NAME = "logs"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var writer: FileLogWriter? = null

    fun v(tag: String, msg: String, tr: Throwable? = null) = log(Log.VERBOSE, tag, msg, tr)
    fun d(tag: String, msg: String, tr: Throwable? = null) = log(Log.DEBUG, tag, msg, tr)
    fun i(tag: String, msg: String, tr: Throwable? = null) = log(Log.INFO, tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log(Log.WARN, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(Log.ERROR, tag, msg, tr)

    @Synchronized
    fun init(context: Context) {
        if (writer != null) return
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        writer = FileLogWriter(
            directory = logDir(context),
            sessionName = "blbl_$timestamp",
            scope = scope,
            onFailure = { message, error -> reportFileFailure("AppLog", message, error) },
        )
    }

    fun logDir(context: Context): File = File(context.applicationContext.filesDir, LOG_DIR_NAME)

    internal suspend fun snapshot(context: Context): FileLogWriter.Snapshot {
        val activeWriter = writer ?: throw IOException("日志记录尚未初始化")
        return activeWriter.snapshot(context.applicationContext.cacheDir)
    }

    private fun log(priority: Int, tag: String, msg: String, tr: Throwable?) {
        val rendered = if (tr == null) msg else "$msg\n${tr.stackTraceToString()}"
        writeLogcat(priority, tag, rendered)
        val level = when (priority) {
            Log.VERBOSE -> 'V'
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            else -> 'A'
        }
        writer?.append(level = level, tag = tag, message = rendered)
    }

    internal fun reportFileFailure(tag: String, message: String, error: Throwable) {
        writeLogcat(Log.WARN, tag, "$message\n${error.stackTraceToString()}")
    }

    private fun writeLogcat(priority: Int, tag: String, message: String) {
        try {
            Log.println(priority, "$PREFIX/$tag", message)
        } catch (error: Exception) {
            System.err.println("$PREFIX/$tag: $message\nLogcat unavailable: $error")
        }
    }
}
