package blbl.cat3399.feature.live

import blbl.cat3399.core.model.LiveSuperChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSuperChatQueueTest {
    private var nowSeconds = 1_000L
    private val queue = LiveSuperChatQueue { nowSeconds }

    @Test
    fun queue_should_display_items_fifo_and_dedupe_positive_ids() {
        val first = item(id = 1L)
        val second = item(id = 2L)

        assertTrue(queue.offer(first))
        assertTrue(queue.offer(second))
        assertFalse(queue.offer(first.copy(message = "重复")))
        assertSame(first, queue.startNext())
        assertTrue(queue.finishCurrent(first))
        assertSame(second, queue.startNext())
    }

    @Test
    fun queue_should_skip_expired_items() {
        assertFalse(queue.offer(item(id = 1L, endTimeSeconds = nowSeconds)))
        assertNull(queue.startNext())

        val future = item(id = 2L, endTimeSeconds = nowSeconds + 1L)
        assertTrue(queue.offer(future))
        nowSeconds += 2L
        assertNull(queue.startNext())
    }

    @Test
    fun delete_should_remove_current_and_pending_items() {
        val first = item(id = 1L)
        val second = item(id = 2L)
        val third = item(id = 3L)
        queue.offer(first)
        queue.offer(second)
        queue.offer(third)
        assertSame(first, queue.startNext())

        assertTrue(queue.delete(setOf(1L, 2L)))
        assertSame(third, queue.startNext())
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun displayDuration_should_respect_message_duration_expiry_and_ten_second_cap() {
        assertEquals(
            5_000L,
            item(id = 1L, endTimeSeconds = nowSeconds + 60L, durationSeconds = 5L)
                .displayDurationMs(nowSeconds),
        )
        assertEquals(
            3_000L,
            item(id = 2L, endTimeSeconds = nowSeconds + 3L, durationSeconds = 60L)
                .displayDurationMs(nowSeconds),
        )
        assertEquals(
            10_000L,
            item(id = 3L, endTimeSeconds = nowSeconds + 60L, durationSeconds = 60L)
                .displayDurationMs(nowSeconds),
        )
        assertNull(
            item(id = 4L, endTimeSeconds = nowSeconds, durationSeconds = 60L)
                .displayDurationMs(nowSeconds),
        )
    }

    private fun item(
        id: Long,
        endTimeSeconds: Long? = nowSeconds + 60L,
        durationSeconds: Long? = 60L,
    ) = LiveSuperChat(
        id = id,
        uid = 1L,
        userName = "用户",
        userFaceUrl = null,
        userNameColor = "#666666",
        price = 30L,
        message = "正文",
        backgroundImageUrl = null,
        backgroundColor = "#EDF5FF",
        backgroundBottomColor = "#2A60B2",
        backgroundPriceColor = "#7497CD",
        messageFontColor = "#FFFFFF",
        startTimeSeconds = nowSeconds,
        endTimeSeconds = endTimeSeconds,
        durationSeconds = durationSeconds,
    )
}
