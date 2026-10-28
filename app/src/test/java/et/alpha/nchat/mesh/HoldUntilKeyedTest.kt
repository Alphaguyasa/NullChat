package et.alpha.nchat.mesh

import et.alpha.nchat.data.db.BROADCAST_CONVERSATION_ID
import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.directConversationId
import et.alpha.nchat.mesh.protocol.MessagePacket
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HoldUntilKeyedTest {

    private val self = ByteArray(8) { 1 }
    private val maya = "a1b2c3d4e5f60718"
    private val sam = "0011223344556677"

    private fun packet(recipient: String?) = MessagePacket.build(
        messageId = "ab".repeat(16),
        senderId = self,
        recipientHexId = recipient,
        body = "meet at the gate",
        timestamp = 1000L,
    )

    @Test
    fun `a direct message with no agreement key is held, never sent in the clear`() {
        // The bug: this used to return the plaintext packet.
        assertNull(sealOrHold(packet(maya)) { null })
    }

    @Test
    fun `a direct message with a key goes out sealed`() {
        val sealed = ByteArray(32) { 9 }

        val out = sealOrHold(packet(maya)) { sealed }

        assertArrayEquals(sealed, out?.payload)
    }

    @Test
    fun `a broadcast goes out as it is, without asking for a key`() {
        val broadcast = packet(null)

        assertSame(broadcast, sealOrHold(broadcast) { error("broadcasts are not sealed") })
    }

    private fun row(
        id: String,
        conversationId: String = directConversationId(maya),
        state: DeliveryState = DeliveryState.PENDING,
        isOutgoing: Boolean = true,
        body: String = "hi",
        attachmentFile: String? = null,
    ) = MessageEntity(
        id = id,
        conversationId = conversationId,
        senderId = "ffffffffffffffff",
        body = body,
        timestamp = 0L,
        deliveryState = state,
        isOutgoing = isOutgoing,
        attachmentFile = attachmentFile,
    )

    @Test
    fun `only our pending single-packet rows to that peer are released`() {
        val rows = listOf(
            row("held"),
            row("other-peer", conversationId = directConversationId(sam)),
            row("broadcast", conversationId = BROADCAST_CONVERSATION_ID),
            row("already-sent", state = DeliveryState.SENT),
            row("incoming", isOutgoing = false),
            row("image", attachmentFile = "x.jpg"),
            row("long", body = "x".repeat(MessagePacket.MAX_TEXT_BYTES)),
        )

        assertEquals(listOf("held"), heldFor(maya, rows).map { it.id })
    }

    @Test
    fun `the start-up pass releases every keyed direct thread, however many`() {
        // More than the 64 signals the key directory buffers, which is where
        // restored contacts past the 64th used to be stranded.
        val keyed = (0 until 100).map { "%016x".format(it) }
        val threads = keyed.map(::directConversationId) +
            directConversationId(sam) +
            BROADCAST_CONVERSATION_ID

        val ready = releasablePeers(threads) { it != sam }

        assertEquals(keyed, ready)
    }
}
