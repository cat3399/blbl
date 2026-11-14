package blbl.cat3399.core.log

import android.content.Context
import android.net.Uri
import blbl.cat3399.core.io.DocumentExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogExporter {
    const val ZIP_MIME = "application/zip"
    const val METADATA_SCHEMA = 2

    private data class ExportInput(
        val appContext: Context,
        val fileName: String,
        val snapshot: FileLogWriter.Snapshot,
        val crashes: List<CrashFileStore.Exported>,
    )

    data class ZipExtra(val path: String, val bytes: ByteArray)
    data class ExportResult(val fileName: String, val uri: Uri, val includedFiles: Int)
    data class LocalExportResult(val fileName: String, val file: File, val includedFiles: Int)

    suspend fun exportToUri(
        context: Context,
        uri: Uri,
        nowMs: Long = System.currentTimeMillis(),
        fileNameOverride: String? = null,
        extras: () -> List<ZipExtra> = { emptyList() },
    ): ExportResult = withContext(Dispatchers.IO) {
        val input = buildExportInput(context, nowMs, fileNameOverride)
        input.snapshot.use {
            val metadata = buildExtras(input, extras())
            currentCoroutineContext().ensureActive()
            val result = DocumentExporter.exportToUri(input.appContext, uri, input.fileName) { out ->
                ZipOutputStream(out).use { zip -> writeZip(zip, input, metadata) }
            }
            ExportResult(result.fileName, result.uri, result.value)
        }
    }

    suspend fun exportToLocalFile(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        fileNameOverride: String? = null,
        extras: () -> List<ZipExtra> = { emptyList() },
    ): LocalExportResult = withContext(Dispatchers.IO) {
        val input = buildExportInput(context, nowMs, fileNameOverride)
        input.snapshot.use {
            val metadata = buildExtras(input, extras())
            currentCoroutineContext().ensureActive()
            val result = DocumentExporter.exportToLocalFile(input.appContext, input.fileName, subDir = "exports") { out ->
                ZipOutputStream(out).use { zip -> writeZip(zip, input, metadata) }
            }
            LocalExportResult(result.fileName, result.file, result.value)
        }
    }

    private suspend fun buildExportInput(context: Context, nowMs: Long, fileNameOverride: String?): ExportInput {
        val appContext = context.applicationContext
        val snapshot = AppLog.snapshot(appContext)
        try {
            currentCoroutineContext().ensureActive()
            val crashes = CrashTracker.snapshotTo(appContext, snapshot.directory)
            if (snapshot.files.isEmpty() && crashes.isEmpty()) throw IOException("没有可导出的日志文件")
            return ExportInput(appContext, resolveExportFileName(nowMs, fileNameOverride), snapshot, crashes)
        } catch (error: Throwable) {
            snapshot.close()
            throw error
        }
    }

    private fun buildExtras(input: ExportInput, extras: List<ZipExtra>): List<ZipExtra> {
        val metadataExtras = extras.filter { it.path == "meta.json" }
        require(metadataExtras.size <= 1) { "重复的日志元数据" }
        val metadata = metadataExtras.firstOrNull()?.let { JSONObject(it.bytes.toString(Charsets.UTF_8)) } ?: JSONObject()
        val health = input.snapshot.health
        metadata.put("schema", METADATA_SCHEMA)
        metadata.put("log_health", JSONObject()
            .put("file_logging_active", health.active)
            .put("queue_dropped_lines", health.droppedLines)
            .put("write_failed_lines", health.failedLines)
            .put("truncated_lines", health.truncatedLines)
            .put("last_error", health.lastError ?: JSONObject.NULL))
        val crashes = JSONArray()
        input.crashes.forEach { entry ->
            val item = JSONObject().put("path", "logs/${entry.file.name}")
            val crash = entry.record
            if (crash == null) {
                item.put("parse_error", true)
            } else {
                item.put("event_id", crash.id ?: JSONObject.NULL)
                    .put("crash_at_ms", crash.crashAtMs)
                    .put("thread", crash.threadName)
                    .put("version_name", crash.versionName ?: JSONObject.NULL)
                    .put("version_code", crash.versionCode ?: JSONObject.NULL)
                    .put("build_type", crash.buildType ?: JSONObject.NULL)
                    .put("truncated", crash.truncated)
                    .put("legacy", crash.id == null)
            }
            crashes.put(item)
        }
        metadata.put("crashes", crashes)
        val result = extras.filter { it.path != "meta.json" } + ZipExtra("meta.json", metadata.toString(2).toByteArray(Charsets.UTF_8))
        result.forEach { require(sanitizeZipPath(it.path) != null) { "无效的日志附加文件路径" } }
        return result
    }

    private fun writeZip(zip: ZipOutputStream, input: ExportInput, extras: List<ZipExtra>): Int {
        var included = 0
        for (extra in extras) {
            zip.putNextEntry(ZipEntry(requireNotNull(sanitizeZipPath(extra.path))))
            zip.write(extra.bytes)
            zip.closeEntry()
            included++
        }
        for (file in input.snapshot.files + input.crashes.map { it.file }) {
            zip.putNextEntry(ZipEntry("logs/${file.name}"))
            FileInputStream(file).use { it.copyTo(zip, bufferSize = 32 * 1024) }
            zip.closeEntry()
            included++
        }
        return included
    }

    private fun buildExportFileName(nowMs: Long): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        return "blbl_logs_$timestamp.zip"
    }

    private fun resolveExportFileName(
        nowMs: Long,
        fileNameOverride: String?,
    ): String {
        val fallback = buildExportFileName(nowMs)
        val raw = fileNameOverride?.trim().takeIf { !it.isNullOrBlank() } ?: fallback
        val withExt = if (raw.endsWith(".zip", ignoreCase = true)) raw else "$raw.zip"
        val safe = sanitizeFileName(withExt)
        return safe.ifBlank { fallback }
    }

    fun suggestExportFileName(
        nowMs: Long = System.currentTimeMillis(),
        fileNameOverride: String? = null,
    ): String = resolveExportFileName(nowMs = nowMs, fileNameOverride = fileNameOverride)

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return ""
        val noSeparators = trimmed.replace(Regex("[\\\\/\\r\\n\\t]"), "_")
        // Keep it reasonably short; some providers reject very long file names.
        return noSeparators.take(96)
    }

    private fun sanitizeZipPath(path: String): String? {
        val p = path.trim().replace('\\', '/')
        if (p.isBlank()) return null
        if (p.startsWith('/')) return null
        if (p.startsWith("../")) return null
        if (p.contains("/../")) return null
        if (p.contains('\u0000')) return null
        return p.take(256)
    }
}
