package et.alpha.nchat.mesh

import et.alpha.nchat.data.db.BROADCAST_CONVERSATION_ID
import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.PeerEntity
import et.alpha.nchat.data.db.directConversationId
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `delete chat keeps only our queued messages`() {
        val queued = message(DeliveryState.PENDING).copy(id = "01".repeat(16))
        val sent = message(DeliveryState.SENT).copy(id = "02".repeat(16))
        val received = message(DeliveryState.DELIVERED, isOutgoing = false).copy(id = "03".repeat(16))

        val deleted = chatDeletion(listOf(queued, sent, received)).map { it.id }

        assertEquals(listOf(sent.id, received.id), deleted)
    }

    @Test
    fun `a stored peer can be forgotten, but not while blocked or when unknown`() {
        val peer = PeerEntity(id = "a1b2c3d4e5f60718", firstSeenAt = 0L, lastSeenAt = 0L)
        assertTrue(canForget(peer))
        // Forgetting drops the row the block lives on, so it would lift the block.
        assertFalse(canForget(peer.copy(isBlocked = true)))
        assertFalse(canForget(null))
    }

    @Test
    fun `deleting received messages takes down each thread's notification once`() {
        val a = message(DeliveryState.DELIVERED, isOutgoing = false)
        val b = a.copy(id = "02".repeat(16), conversationId = directConversationId("0102030405060708"))
        val again = a.copy(id = "03".repeat(16))

        assertEquals(listOf(a.conversationId, b.conversationId), withdrawnConversations(listOf(a, b, again)))
    }

    @Test
    fun `our own messages and the broadcast thread have no notification to take down`() {
        val ours = message(DeliveryState.SENT)
        val broadcast = message(DeliveryState.DELIVERED, isOutgoing = false)
            .copy(conversationId = BROADCAST_CONVERSATION_ID)

        assertTrue(withdrawnConversations(listOf(ours, broadcast)).isEmpty())
    }

    @Test
    fun `only received messages get a tombstone, oldest received first`() {
        val ours = message(DeliveryState.SENT).copy(id = "01".repeat(16), receivedAt = 1L)
        val newer = message(DeliveryState.DELIVERED, isOutgoing = false).copy(id = "02".repeat(16), receivedAt = 30L)
        val older = message(DeliveryState.DELIVERED, isOutgoing = false).copy(id = "03".repeat(16), receivedAt = 20L)

        // Ours would be dropped by sender id anyway; oldest first so the cap evicts those.
        assertEquals(listOf(older.id, newer.id), tombstoneIds(listOf(ours, newer, older)))
    }
}
