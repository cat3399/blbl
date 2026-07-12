package blbl.cat3399.feature.player.danmaku

import kotlin.math.abs

/**
 * AkDanmaku-style timer:
 * - Uses System.nanoTime() for smooth advancement.
 * - Prioritizes a monotonic local clock while playing for stable motion.
 * - Re-anchors only on explicit seek/discontinuity events.
 * - Never moves backwards during ordinary playback state or speed changes.
 * - Keeps a coarse forward-only fallback sync for unreported discontinuities.
 */
internal class DanmakuTimer {
    @Volatile
    private var lastFrameNanos: Long = 0L

    @Volatile
    private var smoothPositionMs: Double = 0.0

    @Volatile
    private var lastSeekSerial: Int = 0

    @Volatile
    private var lastPlaying: Boolean = false

    @Volatile
    private var lastPlaybackSpeed: Double = 1.0

    fun reset(
        positionMs: Long,
        nowNanos: Long,
        seekSerial: Int,
        isPlaying: Boolean,
        playbackSpeed: Float,
    ) {
        lastFrameNanos = nowNanos
        smoothPositionMs = positionMs.coerceAtLeast(0L).toDouble()
        lastSeekSerial = seekSerial
        lastPlaying = isPlaying
        lastPlaybackSpeed = normalizeSpeed(playbackSpeed)
    }

    fun currentPositionMs(): Long = smoothPositionMs.toLong()

    fun step(
        nowNanos: Long,
        rawPositionMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Float,
        seekSerial: Int,
    ): Long {
        val raw = rawPositionMs.coerceAtLeast(0L).toDouble()
        val speed = normalizeSpeed(playbackSpeed)
        val lastNanos = lastFrameNanos

        if (lastNanos == 0L || seekSerial != lastSeekSerial) {
            reset(
                positionMs = rawPositionMs,
                nowNanos = nowNanos,
                seekSerial = seekSerial,
                isPlaying = isPlaying,
                playbackSpeed = playbackSpeed,
            )
            return smoothPositionMs.toLong()
        }

        val dtNanos = (nowNanos - lastNanos).coerceAtLeast(0L)
        lastFrameNanos = nowNanos
        lastSeekSerial = seekSerial

        if (!isPlaying) {
            lastPlaying = false
            lastPlaybackSpeed = speed
            return smoothPositionMs.toLong()
        }

        if (!lastPlaying) {
            lastPlaying = true
            lastPlaybackSpeed = speed
            return smoothPositionMs.toLong()
        }

        if (dtNanos > 0L) {
            val dtMs = dtNanos.toDouble() / 1_000_000.0
            // The elapsed interval belongs to the speed active since the previous frame.
            // Applying the newly reported speed here would create a one-frame time jump.
            smoothPositionMs += dtMs * lastPlaybackSpeed
        }

        // Clamp for safety.
        if (!smoothPositionMs.isFinite() || abs(smoothPositionMs) > 1e15) {
            smoothPositionMs = raw
        }
        if (smoothPositionMs < 0.0) smoothPositionMs = 0.0
        if (raw - smoothPositionMs >= EXTREME_FORWARD_DRIFT_REANCHOR_THRESHOLD_MS) {
            // Catch an unreported forward discontinuity, but never pull danmaku backwards.
            smoothPositionMs = raw
        }
        lastPlaying = true
        lastPlaybackSpeed = speed
        return smoothPositionMs.toLong()
    }

    private fun normalizeSpeed(playbackSpeed: Float): Double =
        playbackSpeed
            .takeIf { it.isFinite() && it > 0f }
            ?.toDouble()
            ?: 1.0

    private companion object {
        private const val EXTREME_FORWARD_DRIFT_REANCHOR_THRESHOLD_MS = 2_000.0
    }
}
