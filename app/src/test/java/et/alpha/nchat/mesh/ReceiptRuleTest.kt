package et.alpha.nchat.mesh

import et.alpha.nchat.data.db.BROADCAST_CONVERSATION_ID
import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.directConversationId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReceiptRuleTest {

    private val maya = "a1b2c3d4e5f60718"
    private val relay = "0011223344556677"

    private fun message(conversationId: String, isOutgoing: Boolean = true) = MessageEntity(
        id = "00".repeat(16),
        conversationId = conversationId,
        senderId = "ffffffffffffffff",
        body = "hello",
        timestamp = 0L,
        deliveryState = DeliveryState.SENT,
        isOutgoing = isOutgoing,
    )

    @Test
    fun `the recipient of our direct message may acknowledge it`() {
        assertTrue(isReceiptFor(message(directConversationId(maya)), maya))
    }

    @Test
    fun `a relay that carried the message may not`() {
        assertFalse(isReceiptFor(message(directConversationId(maya)), relay))
    }

    @Test
    fun `a broadcast is never acknowledged`() {
        assertFalse(isReceiptFor(message(BROADCAST_CONVERSATION_ID), maya))
    }

    @Test
    fun `a message we received is not ours to have delivered`() {
        assertFalse(isReceiptFor(message(directConversationId(maya), isOutgoing = false), maya))
    }
}
