package et.alpha.nchat.ui.chat

import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageInfoTest {

    private val format: (Long) -> String = { "t$it" }

    private fun message(
        isOutgoing: Boolean,
        state: DeliveryState = DeliveryState.SENT,
        timestamp: Long = 1_000L,
        receivedAt: Long = 2_000L,
        hops: Int? = null,
        expiresAt: Long? = null,
    ) = MessageEntity(
        id = "m1",
        conversationId = "c1",
        senderId = "a4f29c11de0b7e35",
        body = "hello",
        timestamp = timestamp,
        deliveryState = state,
        isOutgoing = isOutgoing,
        hops = hops,
        receivedAt = receivedAt,
        expiresAt = expiresAt,
    )

    @Test
    fun `own message shows its state and when it was written, not a received time`() {
        val rows = messageInfoRows(message(isOutgoing = true, state = DeliveryState.DELIVERED, hops = 2), format)
        assertEquals(
            listOf(
                MessageInfoRow(MessageInfoField.STATE, "delivered · 2 hops"),
                MessageInfoRow(MessageInfoField.WRITTEN, "t1000"),
            ),
            rows,
        )
    }

    @Test
    fun `a queued message is never called sent and carries no hop count`() {
        val rows = messageInfoRows(message(isOutgoing = true, state = DeliveryState.PENDING, hops = 3), format)
        assertEquals(MessageInfoRow(MessageInfoField.STATE, "queued"), rows.first())
        assertEquals("failed", deliveryText(DeliveryState.FAILED, 3))
        assertEquals("sent · 1 hop", deliveryText(DeliveryState.SENT, 1))
        assertEquals("delivered", deliveryText(DeliveryState.DELIVERED, 0))
    }

    @Test
    fun `incoming message shows the full sender id and both clocks separately`() {
        val rows = messageInfoRows(message(isOutgoing = false, timestamp = 5_000L, receivedAt = 9_000L), format)
        assertEquals(
            listOf(
                MessageInfoRow(MessageInfoField.FROM, "a4f29c11de0b7e35"),
                MessageInfoRow(MessageInfoField.WRITTEN, "t5000"),
                MessageInfoRow(MessageInfoField.RECEIVED, "t9000"),
            ),
            rows,
        )
    }

    @Test
    fun `rows stored before arrival time existed leave the received row out`() {
        val rows = messageInfoRows(message(isOutgoing = false, receivedAt = 0L), format)
        assertTrue(rows.none { it.field == MessageInfoField.RECEIVED })
    }

    @Test
    fun `a disappearing message says when it goes`() {
        val rows = messageInfoRows(message(isOutgoing = false, expiresAt = 7_000L), format)
        assertEquals(MessageInfoRow(MessageInfoField.DISAPPEARS, "t7000"), rows.last())
    }

    @Test
    fun `a sender clock more than a minute fast is flagged, drift and own messages are not`() {
        assertTrue(senderClockAhead(message(isOutgoing = false, timestamp = 200_000L, receivedAt = 100_000L)))
        assertFalse(senderClockAhead(message(isOutgoing = false, timestamp = 130_000L, receivedAt = 100_000L)))
        assertFalse(senderClockAhead(message(isOutgoing = false, timestamp = 200_000L, receivedAt = 0L)))
        assertFalse(senderClockAhead(message(isOutgoing = true, timestamp = 200_000L, receivedAt = 100_000L)))
    }
}
