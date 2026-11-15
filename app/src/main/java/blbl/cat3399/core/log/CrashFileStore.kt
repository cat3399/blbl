package blbl.cat3399.core.log

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Synchronous crash persistence, independent of the asynchronous diagnostic log queue. */
internal class CrashFileStore(
    private val directory: File,
    private val versionName: String,
    private val versionCode: Int,
    private val buildType: String,
    private val onFailure: (String, Throwable) -> Unit,
    private val maxFiles: Int = 10,
    private val maxTotalBytes: Long = 2L * 1024 * 1024,
    private val maxFileBytes: Int = 256 * 1024,
) {
    data class Record(
        val id: String?,
        val crashAtMs: Long,
        val threadName: String,
        val stacktrace: String,
        val versionName: String?,
        val versionCode: Int?,
        val buildType: String?,
        val truncated: Boolean,
    )

    data class Exported(val file: File, val record: Record?)

    init {
        require(maxFiles > 0 && maxFileBytes >= 1024 && maxTotalBytes >= maxFileBytes)
    }

    @Synchronized
    fun record(threadName: String, throwable: Throwable, crashAtMs: Long = System.currentTimeMillis()): Record {
        ensureDirectory()
        val id = UUID.randomUUID().toString()
        val originalStack = throwable.stackTraceToString().toByteArray(Charsets.UTF_8)
        val stackLimit = maxFileBytes - 768
        val truncated = originalStack.size > stackLimit
        val stack = if (truncated) {
            originalStack.copyOf(stackLimit - 64).toString(Charsets.UTF_8) + "\n[crash stack truncated]"
        } else originalStack.toString(Charsets.UTF_8)
        val record = Record(id, crashAtMs, threadName, stack, versionName, versionCode, buildType, truncated)
        val body = buildString {
            append("crashAtMs=").append(crashAtMs).append('\n')
            append("thread=").append(threadName.replace('\n', ' ').take(128)).append('\n')
            append("throwable=").append(throwable.javaClass.name).append('\n')
            append("eventId=").append(id).append('\n')
            append("versionName=").append(versionName.replace('\n', ' ').take(128)).append('\n')
            append("versionCode=").append(versionCode).append('\n')
            append("buildType=").append(buildType).append('\n')
            append("truncated=").append(truncated).append("\n\n")
            append(stack).append('\n')
        }
        val file = File(directory, "crash_${crashAtMs}_$id.txt")
        val pending = File(directory, ".${file.name}.pending")
        try {
            FileOutputStream(pending).use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!pending.renameTo(file)) throw IOException("cannot finish crash record ${file.name}")
        } finally {
            if (pending.exists() && !pending.delete()) onFailure("remove unfinished crash record failed", IOException(pending.name))
        }
        cleanOldFiles(keep = file)
        return record
    }

    @Synchronized
    fun latest(): Record? = files().mapNotNull { read(it) }.maxByOrNull { it.crashAtMs }

    @Synchronized
    fun copyTo(targetDirectory: File): List<Exported> {
        ensureDirectory()
        cleanOldFiles(keep = null)
        return files().map { file ->
            val copy = file.copyTo(File(targetDirectory, file.name))
            Exported(copy, read(copy))
        }
    }

    @Synchronized
    fun wasPrompted(crashAtMs: Long): Boolean {
        val marker = File(directory, PROMPT_MARKER)
        if (!marker.exists()) return false
        return try {
            marker.readText().substringAfter("crashAtMs=", "").trim().toLongOrNull() == crashAtMs
        } catch (e: Exception) {
            onFailure("read crash prompt marker failed", e)
            false
        }
    }

    @Synchronized
    fun markPrompted(crashAtMs: Long) {
        try {
            ensureDirectory()
            File(directory, PROMPT_MARKER).writeText("crashAtMs=$crashAtMs\n")
        } catch (e: Exception) {
            onFailure("save crash prompt marker failed", e)
        }
    }

    private fun read(file: File): Record? = try {
        parse(file.readText()) ?: run {
            onFailure("invalid crash record", IOException(file.name))
            null
        }
    } catch (e: Exception) {
        onFailure("read crash record failed: ${file.name}", e)
        null
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create crash directory")
    }

    private fun files(): List<File> {
        if (!directory.exists()) return emptyList()
        val entries = directory.listFiles() ?: throw IOException("cannot list crash directory")
        return entries.filter { it.isFile && (it.name == LEGACY_FILE || (it.name.startsWith("crash_") && it.name.endsWith(".txt"))) }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
    }

    private fun cleanOldFiles(keep: File?) {
        val files = files().toMutableList()
        var bytes = files.sumOf { it.length() }
        while (files.size > maxFiles || bytes > maxTotalBytes) {
            val victim = files.firstOrNull { it != keep } ?: break
            val length = victim.length()
            if (!victim.delete()) {
                onFailure("remove old crash record failed", IOException(victim.name))
                break
            }
            files.remove(victim)
            bytes -= length
        }
    }

    companion object {
        private const val LEGACY_FILE = "last_crash.txt"
        private const val PROMPT_MARKER = "last_crash_prompted.txt"

        internal fun parse(raw: String): Record? {
            val separator = raw.indexOf("\n\n")
            if (separator < 0) return null
            val fields = raw.take(separator).lineSequence().mapNotNull { line ->
                val equal = line.indexOf('=')
                if (equal < 0) null else line.take(equal) to line.substring(equal + 1)
            }.toMap()
            val at = fields["crashAtMs"]?.toLongOrNull() ?: return null
            return Record(
                id = fields["eventId"]?.takeIf { it.isNotBlank() },
                crashAtMs = at,
                threadName = fields["thread"]?.ifBlank { "unknown" } ?: "unknown",
                stacktrace = raw.substring(separator + 2).trimEnd(),
                versionName = fields["versionName"],
                versionCode = fields["versionCode"]?.toIntOrNull(),
                buildType = fields["buildType"],
                truncated = fields["truncated"] == "true",
            )
        }
    }
}
