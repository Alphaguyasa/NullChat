package et.alpha.nchat.mesh

import et.alpha.nchat.data.db.BROADCAST_CONVERSATION_ID
import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.directConversationId
import et.alpha.nchat.mesh.router.ForwardQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ResendRuleTest {

    private val maya = "a1b2c3d4e5f60718"

    private fun message(
        conversationId: String = directConversationId(maya),
        state: DeliveryState = DeliveryState.FAILED,
        isOutgoing: Boolean = true,
    ) = MessageEntity(
        id = "00".repeat(16),
        conversationId = conversationId,
        senderId = "ffffffffffffffff",
        body = "hello",
        timestamp = 0L,
        deliveryState = state,
        isOutgoing = isOutgoing,
    )

    @Test
    fun `a failed direct message of ours goes back to its recipient`() {
        assertEquals(ResendTarget(maya), resendTarget(message()))
    }

    @Test
    fun `a failed broadcast goes back to everyone`() {
        // Wrapped, so "to everyone" is not mistaken for "not resendable".
        assertEquals(ResendTarget(null), resendTarget(message(BROADCAST_CONVERSATION_ID)))
    }

    @Test
    fun `a queued message is still on its way and is not resent`() {
        assertNull(resendTarget(message(state = DeliveryState.PENDING)))
    }

    @Test
    fun `a message that already left is not resent`() {
        assertNull(resendTarget(message(state = DeliveryState.SENT)))
        assertNull(resendTarget(message(state = DeliveryState.DELIVERED)))
    }

    @Test
    fun `a message we received is not ours to resend`() {
        assertNull(resendTarget(message(isOutgoing = false)))
    }

    @Test
    fun `a conversation id that names nobody is not resent`() {
        assertNull(resendTarget(message(conversationId = "direct:")))
        assertNull(resendTarget(message(conversationId = "group:xyz")))
    }

    @Test
    fun `queued rows expire on the forward queue's own lifetime`() {
        val now = 10 * ForwardQueue.DEFAULT_EXPIRY_MILLIS

        assertEquals(now - ForwardQueue.DEFAULT_EXPIRY_MILLIS, pendingExpiryCutoff(now))
    }
}
