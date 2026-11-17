package blbl.cat3399.core.io

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import blbl.cat3399.core.log.AppLog
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

object DocumentExporter {
    data class ExportResult<T>(
        val fileName: String,
        val uri: Uri,
        val value: T,
    )

    data class LocalExportResult<T>(
        val fileName: String,
        val file: File,
        val value: T,
    )

    fun <T> exportToUri(
        context: Context,
        uri: Uri,
        fileName: String,
        writeTo: (OutputStream) -> T,
    ): ExportResult<T> {
        val appContext = context.applicationContext
        val safeFileName = sanitizeFileName(fileName)
        if (safeFileName.isBlank()) throw IOException("导出文件名无效")

        val output = appContext.contentResolver.openOutputStream(uri, "w") ?: throw IOException("无法写入导出文件")
        val value = writeAndClose(output, writeTo)

        val resolvedName =
            resolveDisplayName(appContext, uri)
                ?.let(::sanitizeFileName)
                .takeUnless { it.isNullOrBlank() }
                ?: safeFileName
        return ExportResult(fileName = resolvedName, uri = uri, value = value)
    }

    fun <T> exportToLocalFile(
        context: Context,
        fileName: String,
        subDir: String = "exports",
        writeTo: (OutputStream) -> T,
    ): LocalExportResult<T> {
        val appContext = context.applicationContext
        val safeFileName = sanitizeFileName(fileName)
        if (safeFileName.isBlank()) throw IOException("导出文件名无效")

        val exportDir =
            appContext.getExternalFilesDir(subDir)
                ?: File(appContext.filesDir, subDir)
        if (!exportDir.isDirectory && !exportDir.mkdirs() && !exportDir.isDirectory) {
            throw IOException("无法创建导出目录：${exportDir.absolutePath}")
        }

        val outFile = createLocalFile(dir = exportDir, baseName = safeFileName)
        val value = writeAndClose(FileOutputStream(outFile), writeTo)

        return LocalExportResult(fileName = outFile.name, file = outFile, value = value)
    }

    /** Owns the destination. Writers may close wrappers such as ZipOutputStream, not this stream. */
    internal fun <T> writeAndClose(output: OutputStream, writeTo: (OutputStream) -> T): T {
        return BufferedOutputStream(output, 32 * 1024).use { buffered ->
            val borrowed = object : OutputStream() {
                override fun write(value: Int) = buffered.write(value)
                override fun write(bytes: ByteArray, offset: Int, length: Int) = buffered.write(bytes, offset, length)
                override fun flush() = buffered.flush()
                override fun close() = flush()
            }
            writeTo(borrowed)
        }
    }

    private fun createLocalFile(
        dir: File,
        baseName: String,
    ): File {
        var attempt = 0
        while (attempt <= 20) {
            val name = appendAttemptSuffix(baseName = baseName, attempt = attempt)
            val file = File(dir, name)
            try {
                if (file.createNewFile()) return file
            } catch (error: IOException) {
                // Only an existing name warrants a retry. Preserve disk/permission errors.
                throw IOException("无法创建导出文件：${file.absolutePath}", error)
            }
            attempt++
        }
        throw IOException("创建导出文件失败")
    }

    private fun appendAttemptSuffix(
        baseName: String,
        attempt: Int,
    ): String {
        if (attempt == 0) return baseName
        val dotIndex = baseName.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == baseName.lastIndex) {
            return "${baseName}_$attempt"
        }
        val prefix = baseName.substring(0, dotIndex)
        val suffix = baseName.substring(dotIndex)
        return "${prefix}_$attempt$suffix"
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return ""
        val noSeparators = trimmed.replace(Regex("[\\\\/\\r\\n\\t]"), "_")
        return noSeparators.take(96)
    }

    private fun resolveDisplayName(
        context: Context,
        uri: Uri,
    ): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getStringOrNull(OpenableColumns.DISPLAY_NAME)
            }
        } catch (error: Exception) {
            AppLog.w("DocumentExporter", "query exported name failed scheme=${uri.scheme} authority=${uri.authority}", error)
            null
        }
    }

    private fun Cursor.getStringOrNull(columnName: String): String? {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return null
        return getString(index)
    }
}
