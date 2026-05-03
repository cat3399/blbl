package blbl.cat3399.feature.player.engine

import android.content.Context
import android.os.Build
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.await
import blbl.cat3399.core.net.ipv4OnlyDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.danmaku.ijk.media.player.IjkLibLoader
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

internal object IjkPlayerPlugin {
    private const val BASE_URL = "https://cat3399.top/blbl/ijkplayer"
    private const val ZIP_FILE_NAME = "libijkplayer.zip"
    private const val SO_FILE_NAME = "libijkplayer.so"
    private const val INSTALL_STAMP_FILE_NAME = "install_stamp"
    private const val REQUIRED_INSTALL_STAMP = 3
    private const val MIN_SO_BYTES = 1_000_000L

    private val supportedAbis: Set<String> =
        linkedSetOf(
            "arm64-v8a",
            "armeabi-v7a",
            "x86_64",
            "x86",
        )

    sealed class Progress {
        data object Connecting : Progress()

        data class Downloading(
            val downloadedBytes: Long,
            val totalBytes: Long?,
            val bytesPerSecond: Long,
        ) : Progress() {
            val percent: Int? =
                totalBytes?.takeIf { it > 0 }?.let { total ->
                    ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                }

            val hint: String =
                buildString {
                    if (totalBytes != null && totalBytes > 0) {
                        append("${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}")
                    } else {
                        append(formatBytes(downloadedBytes))
                    }
                    if (bytesPerSecond > 0) append("（${formatBytes(bytesPerSecond)}/s）")
                }
        }

        data class Extracting(
            val extractedBytes: Long,
        ) : Progress() {
            val hint: String = "${formatBytes(extractedBytes)}"
        }
    }

    enum class InstallStatus {
        Unsupported,
        NotInstalled,
        NeedsUpdate,
        Installed,
    }

    fun deviceAbi(): String? {
        return Build.SUPPORTED_ABIS.firstOrNull { supportedAbis.contains(it) }
    }

    fun status(context: Context, abi: String = deviceAbi().orEmpty()): InstallStatus {
        if (abi.isBlank()) return InstallStatus.Unsupported
        val so = soFile(context, abi)
        if (!so.exists() || !so.isFile) return InstallStatus.NotInstalled
        if (so.length() < MIN_SO_BYTES) return InstallStatus.NotInstalled
        if (!looksLikeElf(so)) return InstallStatus.NotInstalled
        return if (readInstallStamp(context, abi) == REQUIRED_INSTALL_STAMP) {
            InstallStatus.Installed
        } else {
            InstallStatus.NeedsUpdate
        }
    }

    fun isInstalled(context: Context, abi: String = deviceAbi().orEmpty()): Boolean {
        return status(context, abi) == InstallStatus.Installed
    }

    fun soFile(context: Context, abi: String = deviceAbi().orEmpty()): File {
        val safeAbi = abi.trim()
        val dir = File(context.applicationContext.filesDir, "plugins/ijkplayer/$safeAbi").apply { mkdirs() }
        return File(dir, SO_FILE_NAME)
    }

    fun libLoaderOrNull(context: Context): IjkLibLoader? {
        val abi = deviceAbi() ?: return null
        val so = soFile(context, abi)
        if (!isInstalled(context, abi)) return null
        val soPath = so.absolutePath
        return IjkLibLoader { libName ->
            if (libName == "ijkplayer") {
                System.load(soPath)
            } else {
                System.loadLibrary(libName)
            }
        }
    }

    suspend fun installIfNeeded(
        context: Context,
        onProgress: (Progress) -> Unit,
    ): File {
        val appContext = context.applicationContext
        val abi = deviceAbi() ?: throw IOException("不支持的 ABI：${Build.SUPPORTED_ABIS.joinToString()}")
        if (isInstalled(appContext, abi)) return soFile(appContext, abi)

        return withContext(Dispatchers.IO) {
            if (isInstalled(appContext, abi)) return@withContext soFile(appContext, abi)

            onProgress(Progress.Connecting)

            val zip = downloadZipToCache(appContext, abi = abi, onProgress = onProgress)
            try {
                val so = extractSoFromZip(appContext, abi = abi, zipFile = zip, onProgress = onProgress)
                validateInstalledSo(so)
                writeInstallStamp(appContext, abi)
                return@withContext so
            } finally {
                runCatching { zip.delete() }
            }
        }
    }

    private fun zipUrl(abi: String): String {
        val safeAbi = abi.trim()
        return "$BASE_URL/$safeAbi/$ZIP_FILE_NAME?cb=${System.currentTimeMillis()}"
    }

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .dns(ipv4OnlyDns { BiliClient.prefs.ipv4OnlyEnabled })
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun downloadZipToCache(
        context: Context,
        abi: String,
        onProgress: (Progress) -> Unit,
    ): File {
        val url = zipUrl(abi)
        val dir = File(context.cacheDir, "player_plugins/ijkplayer").apply { mkdirs() }
        val part = File(dir, "libijkplayer-$abi.zip.part")
        val target = File(dir, "libijkplayer-$abi.zip")
        runCatching { part.delete() }
        runCatching { target.delete() }

        val req = Request.Builder().url(url).get().build()
        val call = okHttp.newCall(req)
        val res = call.await()

        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                FileOutputStream(part).use { output ->
                    copyStreamWithProgress(
                        input = input,
                        output = output,
                        totalBytes = total,
                        onProgress = onProgress,
                    )
                    output.fd.sync()
                }
            }
        }

        check(part.exists() && part.length() > 0) { "downloaded file is empty" }
        check(part.renameTo(target)) { "rename failed" }
        return target
    }

    private suspend fun extractSoFromZip(
        context: Context,
        abi: String,
        zipFile: File,
        onProgress: (Progress) -> Unit,
    ): File {
        val outSo = soFile(context, abi)
        val tmp = File(outSo.parentFile, "${outSo.name}.tmp")
        runCatching { tmp.delete() }
        outSo.parentFile?.mkdirs()

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (!name.endsWith(SO_FILE_NAME)) continue

                FileOutputStream(tmp).use { output ->
                    var extracted = 0L
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = zis.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        extracted += read
                        if (extracted % (512 * 1024) == 0L) {
                            onProgress(Progress.Extracting(extractedBytes = extracted))
                        }
                    }
                    output.fd.sync()
                }

                check(tmp.exists() && tmp.length() > 0) { "extract failed: empty so" }
                runCatching { outSo.delete() }
                check(tmp.renameTo(outSo)) { "extract failed: rename so" }
                return outSo
            }
        }

        throw IOException("zip 中未找到 $SO_FILE_NAME")
    }

    private suspend fun copyStreamWithProgress(
        input: java.io.InputStream,
        output: OutputStream,
        totalBytes: Long?,
        onProgress: (Progress) -> Unit,
    ) {
        val buf = ByteArray(32 * 1024)
        var downloaded = 0L

        var lastEmitAtMs = 0L
        var speedAtMs = System.currentTimeMillis()
        var speedBytes = 0L
        var bytesPerSecond = 0L

        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buf)
            if (read <= 0) break
            output.write(buf, 0, read)
            downloaded += read

            // Speed estimate (1s window)
            speedBytes += read
            val nowMs = System.currentTimeMillis()
            val speedElapsedMs = nowMs - speedAtMs
            if (speedElapsedMs >= 1_000) {
                bytesPerSecond = (speedBytes * 1_000L / speedElapsedMs.coerceAtLeast(1)).coerceAtLeast(0)
                speedBytes = 0L
                speedAtMs = nowMs
            }

            // UI progress: at most 5 updates per second.
            if (nowMs - lastEmitAtMs >= 200) {
                lastEmitAtMs = nowMs
                onProgress(
                    Progress.Downloading(
                        downloadedBytes = downloaded,
                        totalBytes = totalBytes,
                        bytesPerSecond = bytesPerSecond,
                    ),
                )
            }
        }

        onProgress(
            Progress.Downloading(
                downloadedBytes = downloaded,
                totalBytes = totalBytes,
                bytesPerSecond = bytesPerSecond,
            ),
        )
    }

    private fun validateInstalledSo(file: File) {
        check(file.exists() && file.isFile) { "so 不存在" }
        check(file.length() >= MIN_SO_BYTES) { "so 文件过小（${file.length()} bytes）" }
        check(looksLikeElf(file)) { "so 文件格式不正确" }
    }

    private fun installStampFile(context: Context, abi: String): File {
        return File(soFile(context, abi).parentFile, INSTALL_STAMP_FILE_NAME)
    }

    private fun readInstallStamp(context: Context, abi: String): Int? {
        return runCatching {
            installStampFile(context, abi)
                .takeIf { it.exists() && it.isFile }
                ?.readText(Charsets.UTF_8)
                ?.trim()
                ?.toIntOrNull()
        }.getOrNull()
    }

    private fun writeInstallStamp(context: Context, abi: String) {
        val file = installStampFile(context, abi)
        file.parentFile?.mkdirs()
        file.writeText(REQUIRED_INSTALL_STAMP.toString(), Charsets.UTF_8)
    }

    private fun looksLikeElf(file: File): Boolean {
        return runCatching {
            FileInputStream(file).use { input ->
                val head = ByteArray(4)
                val n = input.read(head)
                n == 4 && head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        if (b < 1024) return "${b}B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2fGB", gb)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
