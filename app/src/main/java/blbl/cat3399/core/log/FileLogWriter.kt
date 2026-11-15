package blbl.cat3399.core.log

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns file IO and snapshot ordering. Producers only take a short queue lock, never perform IO. */
internal class FileLogWriter(
    private val directory: File,
    private val sessionName: String,
    scope: CoroutineScope,
    private val onFailure: (String, Throwable) -> Unit,
    private val maxFileBytes: Long = 1024L * 1024,
    private val maxFiles: Int = 10,
    private val maxTotalBytes: Long = 10L * 1024 * 1024,
    private val queueCapacity: Int = 256,
    private val openOutput: (File) -> OutputStream = { BufferedOutputStream(FileOutputStream(it), 32 * 1024) },
) {
    data class Health(
        val active: Boolean,
        val droppedLines: Long,
        val failedLines: Long,
        val truncatedLines: Long,
        val lastError: String?,
    )

    class Snapshot(
        val directory: File,
        val files: List<File>,
        val health: Health,
        private val onFailure: (String, Throwable) -> Unit,
    ) : Closeable {
        override fun close() {
            if (directory.exists() && !directory.deleteRecursively()) {
                onFailure("remove log snapshot failed", IOException(directory.name))
            }
        }
    }

    private sealed interface Event {
        data class Line(val atMs: Long, val level: Char, val tag: String, val thread: String, val message: String) : Event
        data class Capture(val parent: File, val reply: CancellableContinuation<Snapshot>) : Event
        data class Stop(val reply: CancellableContinuation<Unit>) : Event
    }

    private val queueLock = Any()
    private val queue = ArrayDeque<Event>()
    private var queuedLines = 0
    private var accepting = true
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val dropped = AtomicLong()
    private var failedLines = 0L
    private var truncatedLines = 0L
    private var lastError: String? = null
    private var writeDisabled = false
    private var output: OutputStream? = null
    private var currentFile: File? = null
    private var currentBytes = 0L
    private var closedFileBytes = 0L
    private var volume = 0
    private var dirty = false
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    init {
        require(maxFileBytes >= 256 && maxTotalBytes >= maxFileBytes && maxFiles > 0 && queueCapacity > 0)
        scope.launch {
            try {
                try {
                    openNextFile()
                    cleanOldFiles()
                } catch (e: Exception) {
                    disableWrites("initialize file log failed", e)
                }
                while (isActive) {
                    withTimeoutOrNull(1_000L) { wake.receive() }
                    while (true) {
                        val event = synchronized(queueLock) {
                            queue.pollFirst()?.also { if (it is Event.Line) queuedLines-- }
                        } ?: break
                        when (event) {
                            is Event.Line -> writeLine(event)
                            is Event.Capture -> capture(event)
                            is Event.Stop -> {
                                flush()
                                closeOutput()
                                event.reply.resume(Unit)
                                return@launch
                            }
                        }
                    }
                    flush()
                }
            } finally {
                synchronized(queueLock) {
                    accepting = false
                    val error = IOException("file log writer stopped")
                    for (event in queue) {
                        when (event) {
                            is Event.Line -> dropped.incrementAndGet()
                            is Event.Capture -> event.reply.resumeWithException(error)
                            is Event.Stop -> event.reply.resumeWithException(error)
                        }
                    }
                    queue.clear()
                    queuedLines = 0
                }
                closeOutput()
                wake.close()
            }
        }
    }

    fun append(level: Char, tag: String, message: String, thread: String = Thread.currentThread().name, atMs: Long = System.currentTimeMillis()) {
        synchronized(queueLock) {
            if (!accepting) {
                dropped.incrementAndGet()
                return
            }
            if (queuedLines >= queueCapacity) {
                // Only log lines may be dropped. Export barriers and shutdown requests must survive.
                val iterator = queue.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next() is Event.Line) {
                        iterator.remove()
                        queuedLines--
                        dropped.incrementAndGet()
                        break
                    }
                }
            }
            queue.addLast(Event.Line(atMs, level, tag, thread, message))
            queuedLines++
        }
        wake.trySend(Unit)
    }

    suspend fun snapshot(parent: File, timeoutMs: Long = 10_000L): Snapshot = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { reply -> enqueueControl(Event.Capture(parent, reply)) }
    }

    suspend fun close() = withTimeout(10_000L) {
        suspendCancellableCoroutine<Unit> { reply ->
            synchronized(queueLock) {
                if (!accepting) {
                    reply.resume(Unit)
                    return@suspendCancellableCoroutine
                }
                accepting = false
                queue.addLast(Event.Stop(reply))
            }
            wake.trySend(Unit)
        }
    }

    private fun enqueueControl(event: Event.Capture) {
        synchronized(queueLock) {
            if (!accepting) {
                event.reply.resumeWithException(IOException("file log writer stopped"))
                return
            }
            queue.addLast(event)
        }
        wake.trySend(Unit)
    }

    private fun writeLine(event: Event.Line) {
        if (writeDisabled) {
            failedLines++
            return
        }
        try {
            val raw = "${timestamp.format(Date(event.atMs))} ${event.level} [${event.thread}] ${event.tag}: ${event.message}\n".toByteArray(Charsets.UTF_8)
            val bytes = if (raw.size <= maxFileBytes) raw else {
                truncatedLines++
                val marker = "\n[log line truncated; original_bytes=${raw.size}]\n".toByteArray(Charsets.UTF_8)
                // Leave room for a UTF-8 replacement character if the byte boundary splits a code point.
                raw.copyOf((maxFileBytes - marker.size - 4).toInt()).toString(Charsets.UTF_8).toByteArray(Charsets.UTF_8) + marker
            }
            if (currentBytes + bytes.size > maxFileBytes) {
                output?.flush()
                output?.close()
                output = null
                openNextFile()
                cleanOldFiles()
            }
            output?.write(bytes) ?: throw IOException("file log output unavailable")
            currentBytes += bytes.size
            dirty = true
            // File length can lag buffered writes, so retention accounts for currentBytes separately.
            if (closedFileBytes + currentBytes > maxTotalBytes) cleanOldFiles()
            if (event.level in "WEA") flush()
        } catch (e: Exception) {
            failedLines++
            disableWrites("write file log failed", e)
        }
    }

    private fun flush() {
        if (!dirty || writeDisabled) return
        try {
            output?.flush()
            dirty = false
        } catch (e: Exception) {
            disableWrites("flush file log failed", e)
        }
    }

    private fun openNextFile() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create log directory")
        var file: File
        do {
            file = File(directory, "${sessionName}_${(volume++).toString().padStart(3, '0')}.log")
        } while (!file.createNewFile())
        currentFile = file
        currentBytes = 0
        output = openOutput(file)
        dirty = false
    }

    private fun logFiles(): List<File> = directory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".log") }
        ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        ?: if (directory.isDirectory) throw IOException("cannot list log directory") else emptyList()

    private fun cleanOldFiles() {
        val files = logFiles().toMutableList()
        var bytes = files.sumOf { if (it == currentFile) currentBytes else it.length() }
        while (files.size > maxFiles || bytes > maxTotalBytes) {
            val file = files.firstOrNull { it != currentFile } ?: break
            val length = file.length()
            if (!file.delete()) throw IOException("cannot remove old log ${file.name}")
            files.remove(file)
            bytes -= length
        }
        closedFileBytes = files.filter { it != currentFile }.sumOf { it.length() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun capture(event: Event.Capture) {
        if (!event.reply.isActive) return
        var snapshotDirectory: File? = null
        try {
            flush()
            val target = File(event.parent, "log_snapshot_${UUID.randomUUID()}")
            snapshotDirectory = target
            if (!target.mkdirs()) throw IOException("cannot create log snapshot directory")
            val copies = logFiles().map { file -> file.copyTo(File(target, file.name)) }
            val snapshot = Snapshot(target, copies, Health(!writeDisabled, dropped.get(), failedLines, truncatedLines, lastError), onFailure)
            // Ownership stays with the writer if the caller cancels before receiving the result.
            event.reply.resume(snapshot, onCancellation = { snapshot.close() })
        } catch (e: Exception) {
            snapshotDirectory?.let { if (it.exists() && !it.deleteRecursively()) onFailure("remove incomplete snapshot failed", e) }
            onFailure("capture file logs failed", e)
            event.reply.resumeWithException(e)
        }
    }

    private fun disableWrites(message: String, error: Exception) {
        writeDisabled = true
        lastError = "$message: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        onFailure(message, error)
        closeOutput()
    }

    private fun closeOutput() {
        val out = output ?: return
        output = null
        try {
            out.close()
        } catch (e: Exception) {
            writeDisabled = true
            lastError = "close file log failed: ${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            onFailure("close file log failed", e)
        }
    }
}
