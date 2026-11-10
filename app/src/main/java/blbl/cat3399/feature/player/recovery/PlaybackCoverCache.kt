package blbl.cat3399.feature.player.recovery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import blbl.cat3399.core.net.BiliClient
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface RecoveryCoverSource {
    suspend fun load(key: String): Bitmap?
    suspend fun prefetch(key: String, url: String): Bitmap?
}

internal class PlaybackCoverCache(context: Context) : RecoveryCoverSource {
    private val directory = File(context.applicationContext.filesDir, "playback_recovery_covers")

    override suspend fun load(key: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { decode(AtomicFile(fileFor(key)).readFully()) }.getOrNull()
    }

    override suspend fun prefetch(key: String, url: String): Bitmap? = withContext(Dispatchers.IO) {
        val normalized = if (url.startsWith("//")) "https:$url" else url
        if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) return@withContext null
        val bitmap = decode(BiliClient.getBytes(normalized)) ?: return@withContext null
        directory.mkdirs()
        val target = fileFor(key)
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream))
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            atomic.failWrite(stream)
            throw t
        }
        // A few recent covers also support returning to a player lower in the activity stack.
        directory.listFiles()?.filter { it.extension == "jpg" && it != target }
            ?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        bitmap
    }

    internal fun fileFor(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.jpg")
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = 1
        while (options.outWidth / options.inSampleSize > 960 || options.outHeight / options.inSampleSize > 540) {
            options.inSampleSize *= 2
        }
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
