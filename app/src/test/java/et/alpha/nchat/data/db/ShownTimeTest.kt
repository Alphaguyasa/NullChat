package et.alpha.nchat.data.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShownTimeTest {

    private fun message(timestamp: Long, receivedAt: Long, isOutgoing: Boolean = false) = MessageEntity(
        id = "m1",
        conversationId = "direct:aabbccddeeff0011",
        senderId = "aabbccddeeff0011",
        body = "hello",
        timestamp = timestamp,
        deliveryState = DeliveryState.DELIVERED,
        isOutgoing = isOutgoing,
        receivedAt = receivedAt,
    )

    @Test
    fun `a sender's fast clock is clamped to when the message arrived`() {
        // The bug: the bubble showed the sender's clock, a year ahead.
        assertEquals(T0, message(timestamp = T0 + YEAR, receivedAt = T0).shownAt)
    }

    @Test
    fun `a store-and-forwarded message keeps the time it was written`() {
        assertEquals(T0, message(timestamp = T0, receivedAt = T0 + 3 * HOUR).shownAt)
    }

    @Test
    fun `a row from before arrival times were stored keeps its timestamp`() {
        assertEquals(T0 + YEAR, message(timestamp = T0 + YEAR, receivedAt = 0L).shownAt)
    }

    @Test
    fun `our own messages show our own clock`() {
        assertEquals(T0 + HOUR, message(timestamp = T0 + HOUR, receivedAt = T0, isOutgoing = true).shownAt)
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val HOUR = 60 * 60 * 1000L
        const val YEAR = 365L * 24 * HOUR
    }
}
