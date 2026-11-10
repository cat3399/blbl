package blbl.cat3399.feature.player.recovery

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import kotlin.math.min

/** Main-thread owned, application-wide slot. An unfinished copy also occupies the slot. */
internal object PlaybackFrameCache {
    private var key: String? = null
    private var generation = 0L
    private var frame: Bitmap? = null
    private var pending: Capture? = null
    private var observerOwner: Any? = null
    private var observer: ((Bitmap?) -> Unit)? = null

    internal data class Capture(val key: String, val generation: Long, val bitmap: Bitmap)

    fun observe(contentKey: String, owner: Any, onChanged: (Bitmap?) -> Unit) {
        if (key != contentKey) {
            clear()
            key = contentKey
        }
        if (observerOwner !== owner) observer?.invoke(null)
        observerOwner = owner
        observer = onChanged
    }

    fun detach(owner: Any) {
        if (observerOwner === owner) {
            observerOwner = null
            observer = null
        }
    }

    fun get(contentKey: String): Bitmap? = frame?.takeIf { key == contentKey && !it.isRecycled }

    fun clear(owner: Any? = null) {
        if (owner != null && observerOwner !== owner) return
        generation++
        observer?.invoke(null)
        frame?.recycle()
        frame = null
        key = null
        observerOwner = null
        observer = null
        // PixelCopy still owns its destination until its callback; never recycle it early.
    }

    fun capture(contentKey: String, view: View?, hasRenderedFrame: Boolean) {
        if (!hasRenderedFrame || Build.VERSION.SDK_INT < 24 || view == null) return
        val ownedSurface: Surface?
        val surface = when (view) {
            is SurfaceView -> {
                ownedSurface = null
                view.holder.surface.takeIf { it.isValid }
            }
            is TextureView -> {
                ownedSurface = view.surfaceTexture?.takeIf { view.isAvailable }?.let { Surface(it) }
                ownedSurface?.takeIf { it.isValid }
            }
            else -> return
        }
        if (surface == null) {
            ownedSurface?.release()
            return
        }
        val capture = beginCapture(contentKey, view.width, view.height)
        if (capture == null) {
            ownedSurface?.release()
            return
        }
        try {
            PixelCopy.request(surface, capture.bitmap, { result ->
                ownedSurface?.release()
                completeCapture(capture, result == PixelCopy.SUCCESS)
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            ownedSurface?.release()
            completeCapture(capture, false)
        }
    }

    internal fun beginCapture(contentKey: String, width: Int, height: Int): Capture? {
        if (key != contentKey || pending != null) return null
        val size = boundedFrameSize(width, height) ?: return null
        observer?.invoke(null)
        frame?.recycle()
        frame = null
        val bitmap = runCatching { Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        return Capture(contentKey, generation, bitmap).also { pending = it }
    }

    internal fun completeCapture(capture: Capture, success: Boolean) {
        if (pending !== capture) return
        pending = null
        if (success && capture.key == key && capture.generation == generation) {
            frame = capture.bitmap
            observer?.invoke(frame)
        } else {
            capture.bitmap.recycle()
        }
    }
}

internal fun boundedFrameSize(width: Int, height: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    val scale = min(1.0, min(960.0 / width, 540.0 / height))
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}
