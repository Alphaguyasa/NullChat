package et.alpha.nchat.mesh.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The send path's only real logic: id conversion, the payload budget, and
 * broadcast versus direct addressing.
 *
 * Extracted from the coordinator precisely so it can be tested here rather than
 * only on two phones — everything else on that path is Room and radio.
 */
class MessagePacketTest {

    private val messageId = "0123456789abcdef0123456789abcdef" // 32 hex = 16 bytes
    private val senderId = ByteArray(Packet.PEER_ID_SIZE) { (it + 1).toByte() }
    private val recipientHex = "aabbccddeeff0011" // 16 hex = 8 bytes

    @Test
    fun `builds a direct message that survives a codec round trip`() {
        val packet = MessagePacket.build(
            messageId = messageId,
            senderId = senderId,
            recipientHexId = recipientHex,
            body = "meet me at the north gate",
            timestamp = 1_700_000_000_000,
        )

        val decoded = PacketCodec.decode(PacketCodec.encode(packet))

        assertEquals(PacketType.MESSAGE, decoded.type)
        assertEquals("meet me at the north gate", MessagePacket.bodyOf(decoded))
        assertArrayEquals(senderId, decoded.senderId)
        assertFalse(decoded.isBroadcast)
        assertEquals(Packet.DEFAULT_TTL, decoded.ttl)
    }

    @Test
    fun `a null recipient addresses the whole mesh`() {
        val packet = MessagePacket.build(
            messageId = messageId,
            senderId = senderId,
            recipientHexId = null,
            body = "anyone there",
            timestamp = 1L,
        )

        assertTrue(packet.isBroadcast)
        assertArrayEquals(Packet.BROADCAST_ID, packet.recipientId)
    }

    @Test
    fun `the message id becomes the wire id`() {
        // This is what lets a delivered packet be matched back to its database
        // row, and what makes the receiver's dedup collapse flood duplicates.
        val packet = MessagePacket.build(messageId, senderId, null, "hi", 1L)

        assertEquals(messageId, packet.messageId.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `a body of exactly the limit is allowed`() {
        val body = "x".repeat(MessagePacket.MAX_BODY_BYTES)

        assertTrue(MessagePacket.fits(body))
        val packet = MessagePacket.build(messageId, senderId, null, body, 1L)

        // Proves the budget arithmetic. The packet is deliberately *not* full at
        // the body limit any more: 16 bytes are held back for the AES-GCM tag
        // that SessionCipher adds on the way out, so a longest-possible message
        // still fits under the 180-byte ceiling once encrypted.
        assertEquals(
            Packet.MAX_PACKET_SIZE - MessagePacket.CIPHER_OVERHEAD_BYTES,
            packet.encodedSize,
        )
        assertEquals(
            Packet.MAX_PACKET_SIZE,
            packet.encodedSize + MessagePacket.CIPHER_OVERHEAD_BYTES,
            "an encrypted longest message must exactly fill the packet budget",
        )
        assertEquals(body, MessagePacket.bodyOf(PacketCodec.decode(PacketCodec.encode(packet))))
    }

    @Test
    fun `one byte over the limit is rejected`() {
        val body = "x".repeat(MessagePacket.MAX_BODY_BYTES + 1)

        assertFalse(MessagePacket.fits(body))
        assertThrows<IllegalArgumentException> {
            MessagePacket.build(messageId, senderId, null, body, 1L)
        }
    }

    @Test
    fun `the limit counts bytes, not characters`() {
        // An emoji is four UTF-8 bytes, so 20 of them are 80 bytes and must not
        // fit despite being only 20 characters. Counting characters in the UI
        // would let this through and fail at the codec.
        val emoji = "🚀".repeat(20)

        assertEquals(20 * 2, emoji.length)
        assertEquals(80, MessagePacket.bodyByteCount(emoji))
        assertFalse(MessagePacket.fits(emoji))
    }

    @Test
    fun `multi-byte text round trips intact`() {
        val body = "ሰላም ዓለም" // Amharic, three bytes per character

        assertTrue(MessagePacket.fits(body))
        val decoded = PacketCodec.decode(
            PacketCodec.encode(MessagePacket.build(messageId, senderId, null, body, 1L)),
        )

        assertEquals(body, MessagePacket.bodyOf(decoded))
    }

    @Test
    fun `a malformed id is rejected with a message naming the field`() {
        val toShort = assertThrows<IllegalArgumentException> {
            MessagePacket.build("abcd", senderId, null, "hi", 1L)
        }
        assertTrue(toShort.message!!.contains("messageId"), toShort.message)

        val badRecipient = assertThrows<IllegalArgumentException> {
            MessagePacket.build(messageId, senderId, "nothexnothexnoth", "hi", 1L)
        }
        assertTrue(badRecipient.message!!.contains("recipientId"), badRecipient.message)
    }
}
