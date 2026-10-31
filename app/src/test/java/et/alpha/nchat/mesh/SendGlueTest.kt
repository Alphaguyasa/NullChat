package et.alpha.nchat.mesh

import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.directConversationId
import et.alpha.nchat.mesh.protocol.MessagePacket
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The send side's glue between the radio and the rows: what moves a message
 * the user is watching from `queued` to `sent`. See [sendHeld], [markFlushed].
 */
class SendGlueTest {

    private val maya = "a1b2c3d4e5f60718"

    private fun row(id: String) = MessageEntity(
        id = id,
        conversationId = directConversationId(maya),
        senderId = "ffffffffffffffff",
        body = "hi",
        timestamp = 0L,
        deliveryState = DeliveryState.PENDING,
        isOutgoing = true,
    )

    private fun packet(messageIdHex: String) = MessagePacket.build(
        messageId = messageIdHex,
        senderId = ByteArray(8) { 1 },
        recipientHexId = maya,
        body = "hi",
        timestamp = 0L,
    )

    @Test
    fun `a held row is marked sent only once it went out`() = runTest {
        val marked = mutableListOf<String>()

        val sent = sendHeld(
            held = listOf(row("a"), row("b"), row("c")),
            transmit = { it.id != "b" },
            markSent = { marked += it; true },
        )

        // b stays PENDING for the next release; c is not stranded behind it.
        assertEquals(listOf("a", "c"), marked)
        assertEquals(2, sent)
    }

    @Test
    fun `held rows go out in the order they were written`() = runTest {
        val order = mutableListOf<String>()

        sendHeld(listOf(row("1"), row("2"), row("3")), transmit = { order += it.id; true }, markSent = { true })

        assertEquals(listOf("1", "2", "3"), order)
    }

    @Test
    fun `a row already advanced elsewhere is not counted twice`() = runTest {
        // markSent returns false when an ACK got there first (DELIVERED).
        val sent = sendHeld(listOf(row("a")), transmit = { true }, markSent = { false })

        assertEquals(0, sent)
    }

    @Test
    fun `a flush marks exactly the packets that went out`() = runTest {
        val ours = "aa".repeat(16)
        val relayed = "bb".repeat(16)
        val marked = mutableListOf<String>()

        val count = markFlushed(listOf(packet(ours), packet(relayed))) { id ->
            marked += id
            // Only our message has a row; the relayed one finds nothing.
            id == ours
        }

        assertEquals(listOf(ours, relayed), marked)
        assertEquals(1, count)
    }

    @Test
    fun `an empty flush touches no row`() = runTest {
        var calls = 0

        assertEquals(0, markFlushed(emptyList()) { calls++; true })
        assertEquals(0, calls)
    }
}
