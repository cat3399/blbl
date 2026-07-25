package blbl.cat3399.feature.live

import blbl.cat3399.core.model.LiveSuperChat
import java.util.ArrayDeque
import java.util.LinkedHashSet

internal class LiveSuperChatQueue(
    private val nowEpochSeconds: () -> Long,
) {
    private val pending = ArrayDeque<LiveSuperChat>()
    private val seenIds = LinkedHashSet<Long>()

    var current: LiveSuperChat? = null
        private set

    val pendingCount: Int
        get() = pending.size

    fun offer(item: LiveSuperChat): Boolean {
        if (item.isExpired(nowEpochSeconds())) return false
        if (item.id > 0L && !seenIds.add(item.id)) return false
        trimSeenIds()
        if (pending.size >= MAX_PENDING_ITEMS) pending.removeFirst()
        pending.addLast(item)
        return true
    }

    fun startNext(): LiveSuperChat? {
        current?.let { return it }
        pruneExpiredPending()
        return pending.pollFirst()?.also { current = it }
    }

    fun finishCurrent(expected: LiveSuperChat): Boolean {
        if (current !== expected) return false
        current = null
        return true
    }

    fun delete(ids: Set<Long>): Boolean {
        if (ids.isEmpty()) return false
        val removedCurrent = current?.id?.let(ids::contains) == true
        if (removedCurrent) current = null

        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().id in ids) iterator.remove()
        }
        return removedCurrent
    }

    fun clear() {
        current = null
        pending.clear()
        seenIds.clear()
    }

    private fun pruneExpiredPending() {
        val now = nowEpochSeconds()
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().isExpired(now)) iterator.remove()
        }
    }

    private fun trimSeenIds() {
        while (seenIds.size > MAX_SEEN_IDS) {
            val iterator = seenIds.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private fun LiveSuperChat.isExpired(nowSeconds: Long): Boolean =
        endTimeSeconds?.let { it <= nowSeconds } == true

    private companion object {
        const val MAX_PENDING_ITEMS = 50
        const val MAX_SEEN_IDS = 512
    }
}

internal fun LiveSuperChat.displayDurationMs(nowEpochSeconds: Long): Long? {
    val remainingMs =
        endTimeSeconds?.let { endTime ->
            val seconds = endTime - nowEpochSeconds
            if (seconds <= 0L) return null
            seconds.coerceAtMost(MAX_SUPER_CHAT_DISPLAY_SECONDS) * 1_000L
        }
    val requestedMs =
        durationSeconds
            ?.takeIf { it > 0L }
            ?.coerceAtMost(MAX_SUPER_CHAT_DISPLAY_SECONDS)
            ?.times(1_000L)
    return minOf(
        MAX_SUPER_CHAT_DISPLAY_MS,
        remainingMs ?: MAX_SUPER_CHAT_DISPLAY_MS,
        requestedMs ?: MAX_SUPER_CHAT_DISPLAY_MS,
    ).takeIf { it > 0L }
}

private const val MAX_SUPER_CHAT_DISPLAY_SECONDS = 10L
private const val MAX_SUPER_CHAT_DISPLAY_MS = MAX_SUPER_CHAT_DISPLAY_SECONDS * 1_000L
