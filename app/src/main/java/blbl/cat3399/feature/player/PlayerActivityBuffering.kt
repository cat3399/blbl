package blbl.cat3399.feature.player

import android.os.SystemClock

private const val BUFFERING_SPEED_WINDOW_MS = 800L
private const val BUFFERING_SPEED_STALE_MS = 2_000L

internal class BufferingSpeedMeter {
    @Volatile private var lastBytesPerSecond: Long? = null
    @Volatile private var lastUpdatedAtMs: Long = 0L

    private var sampleBytes: Long = 0L
    private var sampleStartAtMs: Long = 0L

    @Synchronized
    fun reset() {
        sampleBytes = 0L
        sampleStartAtMs = 0L
        lastBytesPerSecond = null
        lastUpdatedAtMs = 0L
    }

    @Synchronized
    fun addBytes(bytes: Long, nowMs: Long = SystemClock.elapsedRealtime()) {
        if (bytes <= 0L) return
        if (sampleStartAtMs <= 0L) {
            sampleStartAtMs = nowMs
        }
        sampleBytes += bytes
        val elapsedMs = nowMs - sampleStartAtMs
        if (elapsedMs < BUFFERING_SPEED_WINDOW_MS) return
        lastBytesPerSecond = (sampleBytes * 1000L / elapsedMs.coerceAtLeast(1L)).coerceAtLeast(0L)
        lastUpdatedAtMs = nowMs
        sampleBytes = 0L
        sampleStartAtMs = nowMs
    }

    fun currentBytesPerSecond(nowMs: Long = SystemClock.elapsedRealtime()): Long? {
        val speed = lastBytesPerSecond ?: return null
        return speed.takeIf { nowMs - lastUpdatedAtMs <= BUFFERING_SPEED_STALE_MS }
    }
}

internal fun PlayerActivity.recordBufferingTransferBytes(bytes: Long) {
    bufferingOverlayController.recordTransferBytes(bytes)
}

internal fun PlayerActivity.resetBufferingOverlayState() {
    bufferingOverlayController.reset()
}

internal fun PlayerActivity.suppressBufferingOverlayDuringKeySeek(commitDelayMs: Long) {
    bufferingOverlayController.suppressFor(
        durationMs = commitDelayMs,
        graceMs = PlayerActivity.KEY_SEEK_BUFFERING_POST_COMMIT_GRACE_MS,
    )
}

internal fun PlayerActivity.clearKeySeekBufferingOverlaySuppression() {
    bufferingOverlayController.clearSuppression()
}

internal fun PlayerActivity.updateBufferingOverlay() {
    bufferingOverlayController.update()
}
