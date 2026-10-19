package et.alpha.nchat.mesh

import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.directConversationId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeleteRuleTest {

    private fun message(state: DeliveryState, isOutgoing: Boolean = true) = MessageEntity(
        id = "00".repeat(16),
        conversationId = directConversationId("a1b2c3d4e5f60718"),
        senderId = "ffffffffffffffff",
        body = "hello",
        timestamp = 0L,
        deliveryState = state,
        isOutgoing = isOutgoing,
    )

    @Test
    fun `a queued message of ours is not deleted, because it would still go out`() {
        assertFalse(canDeleteLocally(message(DeliveryState.PENDING)))
    }

    @Test
    fun `our messages that left or failed can be deleted`() {
        assertTrue(canDeleteLocally(message(DeliveryState.SENT)))
        assertTrue(canDeleteLocally(message(DeliveryState.DELIVERED)))
        assertTrue(canDeleteLocally(message(DeliveryState.FAILED)))
    }

    @Test
    fun `a message we received can always be deleted`() {
        DeliveryState.entries.forEach { state ->
            assertTrue(canDeleteLocally(message(state, isOutgoing = false)), "$state")
        }
    }
}
