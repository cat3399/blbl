package blbl.cat3399.core.log

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object AppLog {
    private const val PREFIX = "BLBL"
    private const val LOG_DIR_NAME = "logs"

    private const val MAX_LOG_FILES = 10
    private const val MAX_TOTAL_BYTES: Long = 10L * 1024 * 1024

    // Never block callers. When the buffer is full, drop the oldest log events.
    private const val FILE_EVENT_BUFFER = 256

    fun v(tag: String, msg: String, tr: Throwable? = null) = log(Log.VERBOSE, tag, msg, tr)
    fun d(tag: String, msg: String, tr: Throwable? = null) = log(Log.DEBUG, tag, msg, tr)
    fun i(tag: String, msg: String, tr: Throwable? = null) = log(Log.INFO, tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log(Log.WARN, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(Log.ERROR, tag, msg, tr)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = AtomicBoolean(false)
    private val fileLoggingDisabled = AtomicBoolean(false)

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var sessionLogFile: File? = null

    private sealed interface FileEvent {
        data class Line(
            val atMs: Long,
            val priority: Int,
            val tag: String,
            val thread: String,
            val message: String,
        ) : FileEvent
    }

    private val fileEvents =
        Channel<FileEvent>(
            capacity = FILE_EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Initializes file-backed logging.
     *
     * - Each cold start creates a new session log file named by startup time.
     * - Keeps at most [MAX_LOG_FILES] session files and at most [MAX_TOTAL_BYTES] total size.
     * - Never blocks the caller thread; when busy, old events are dropped.
     */
    fun init(context: Context) {
        if (initialized.getAndSet(true)) return

        val appContext = context.applicationContext
        val dir = File(appContext.filesDir, LOG_DIR_NAME)
        runCatching { dir.mkdirs() }
        logDir = dir

        val startedAtMs = System.currentTimeMillis()
        val sessionName = buildSessionFileName(startedAtMs)
        val file = File(dir, sessionName)
        runCatching {
            if (!file.exists()) file.createNewFile()
        }
        sessionLogFile = file

        scope.launch {
            cleanupLogs(dir, keep = file)
        }
        scope.launch {
            runFileWriter(file)
        }
    }

    fun logDir(context: Context): File {
        return File(context.applicationContext.filesDir, LOG_DIR_NAME)
    }

    private fun log(priority: Int, tag: String, msg: String, tr: Throwable?) {
        val fullTag = "$PREFIX/$tag"
        val rendered =
            if (tr == null) {
                msg
            } else {
                "$msg\n${runCatching { Log.getStackTraceString(tr) }.getOrElse { tr.stackTraceToString() }}"
            }

        runCatching { Log.println(priority, fullTag, rendered) }

        sessionLogFile ?: return
        if (fileLoggingDisabled.get()) return
        // Avoid any expensive formatting on the caller thread.
        fileEvents.trySend(
            FileEvent.Line(
                atMs = System.currentTimeMillis(),
                priority = priority,
                tag = tag,
                thread = Thread.currentThread().name,
                message = rendered,
            ),
        )
    }

    private fun buildSessionFileName(startedAtMs: Long): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        val ts = runCatching { sdf.format(Date(startedAtMs)) }.getOrNull() ?: startedAtMs.toString()
        return "blbl_${ts}.log"
    }

    private fun priorityToLetter(priority: Int): Char {
        return when (priority) {
            Log.VERBOSE -> 'V'
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            Log.ASSERT -> 'A'
            else -> '?'
        }
    }

    private suspend fun runFileWriter(file: File) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        var bytesWritten = runCatching { file.length() }.getOrDefault(0L).coerceAtLeast(0L)
        var warnedSizeCap = false
        var lastCleanupAtMs = 0L
        var lastCleanupAtBytes = bytesWritten
        var lastFlushAtMs = 0L
        var bytesSinceFlush = 0L

        val out =
            runCatching {
                BufferedOutputStream(FileOutputStream(file, true), 32 * 1024)
            }.getOrNull()
                ?: run {
                    fileLoggingDisabled.set(true)
                    return
                }

        try {
            while (true) {
                val ev = fileEvents.receive()
                when (ev) {
                    is FileEvent.Line -> {
                        if (bytesWritten >= MAX_TOTAL_BYTES) {
                            if (!warnedSizeCap) {
                                warnedSizeCap = true
                                Log.w("$PREFIX/AppLog", "file log reached size cap; stop writing")
                            }
                            continue
                        }

                        val ts = runCatching { sdf.format(Date(ev.atMs)) }.getOrNull() ?: ev.atMs.toString()
                        val line =
                            buildString(ev.message.length + 64) {
                                append(ts)
                                append(' ')
                                append(priorityToLetter(ev.priority))
                                append(" [")
                                append(ev.thread)
                                append("] ")
                                append(ev.tag)
                                append(": ")
                                append(ev.message)
                                append('\n')
                            }
                        val bytes = line.toByteArray(Charsets.UTF_8)
                        if (bytesWritten + bytes.size > MAX_TOTAL_BYTES) {
                            if (!warnedSizeCap) {
                                warnedSizeCap = true
                                Log.w("$PREFIX/AppLog", "file log reached size cap; stop writing")
                            }
                            continue
                        }
                        out.write(bytes)
                        bytesWritten += bytes.size
                        bytesSinceFlush += bytes.size

                        val nowMs = System.currentTimeMillis()
                        val shouldFlush =
                            ev.priority >= Log.WARN ||
                                bytesSinceFlush >= 8 * 1024 ||
                                nowMs - lastFlushAtMs >= 1_000L
                        if (shouldFlush) {
                            lastFlushAtMs = nowMs
                            bytesSinceFlush = 0L
                            runCatching { out.flush() }
                        }

                        val shouldCleanup =
                            nowMs - lastCleanupAtMs >= 30_000L ||
                                bytesWritten - lastCleanupAtBytes >= 512 * 1024
                        if (shouldCleanup) {
                            lastCleanupAtMs = nowMs
                            lastCleanupAtBytes = bytesWritten
                            val dir = logDir
                            if (dir != null) cleanupLogs(dir, keep = file)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            fileLoggingDisabled.set(true)
        } finally {
            runCatching { out.flush() }
            runCatching { out.close() }
        }
    }

    private fun cleanupLogs(dir: File, keep: File?) {
        val keepPath = runCatching { keep?.canonicalPath }.getOrNull()
        val all =
            runCatching {
                dir.listFiles()?.asSequence()
                    ?.filter { it.isFile && it.name.endsWith(".log") }
                    ?.sortedBy { it.lastModified() }
                    ?.toMutableList()
                    ?: mutableListOf()
            }.getOrDefault(mutableListOf())

        fun isKeep(f: File): Boolean {
            if (keepPath.isNullOrBlank()) return false
            val p = runCatching { f.canonicalPath }.getOrNull() ?: f.absolutePath
            return p == keepPath
        }

        fun oldestDeletable(): File? = all.firstOrNull { !isKeep(it) }

        while (all.size > MAX_LOG_FILES) {
            val victim = oldestDeletable() ?: break
            val removed = runCatching { victim.delete() }.getOrDefault(false)
            if (!removed) break
            all.remove(victim)
        }

        fun totalBytes(): Long = all.sumOf { runCatching { it.length() }.getOrDefault(0L).coerceAtLeast(0L) }

        var total = totalBytes()
        while (total > MAX_TOTAL_BYTES) {
            val victim = oldestDeletable() ?: break
            val len = runCatching { victim.length() }.getOrDefault(0L).coerceAtLeast(0L)
            val removed = runCatching { victim.delete() }.getOrDefault(false)
            if (!removed) break
            all.remove(victim)
            total -= len
        }
    }
}
