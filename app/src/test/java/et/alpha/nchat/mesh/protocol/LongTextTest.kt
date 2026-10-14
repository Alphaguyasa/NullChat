package et.alpha.nchat.mesh.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Text longer than one packet, sent as a fragmented TEXT_UTF8 attachment. */
class LongTextTest {

    private val messageId = ByteArray(Packet.MESSAGE_ID_SIZE) { (it + 9).toByte() }
    private val sender = ByteArray(Packet.PEER_ID_SIZE) { 1 }
    private val recipient = ByteArray(Packet.PEER_ID_SIZE) { 2 }

    @Test
    fun `text between one packet and the ceiling is sendable but does not fit`() {
        val body = "a".repeat(MessagePacket.MAX_BODY_BYTES + 1)

        assertFalse(MessagePacket.fits(body))
        assertTrue(MessagePacket.isSendable(body))
    }

    @Test
    fun `the ceiling is counted in bytes, not characters`() {
        // 251 four-byte emoji is 1004 bytes but only 502 UTF-16 chars.
        val body = "😀".repeat(251)

        assertFalse(MessagePacket.isSendable(body))
        assertTrue(MessagePacket.isSendable("😀".repeat(250)))
    }

    @Test
    fun `the longest sendable text stays a small number of fragments`() {
        val envelope = Attachment.text("x".repeat(MessagePacket.MAX_TEXT_BYTES)).encode()
        // Encrypted size: the envelope plus the AES-GCM tag.
        val sealedSize = envelope.size + MessagePacket.CIPHER_OVERHEAD_BYTES

        assertTrue(
            Fragmenter.fragmentCount(sealedSize) <= 21,
            "a long message must stay well inside the 60 second reassembly window",
        )
    }

    @Test
    fun `a maximum-length text survives fragmenting and reassembly`() {
        val body = "Long message, line by line. ".repeat(40).take(MessagePacket.MAX_TEXT_BYTES)
        val fragments = Fragmenter.fragment(
            messageId = messageId,
            senderId = sender,
            recipientId = recipient,
            payload = Attachment.text(body).encode(),
            timestamp = 1_700_000_000_000L,
        )

        val reassembler = FragmentReassembler()
        // Reverse order: flooding makes no promise about arrival order.
        val completed = fragments.reversed()
            .map { PacketCodec.decode(PacketCodec.encode(it)) }
            .mapNotNull(reassembler::offer)
            .single()

        val attachment = Attachment.parse(completed.payload)
        assertNotNull(attachment)
        attachment!!
        assertEquals(ContentType.TEXT_UTF8, attachment.contentType)
        assertEquals(body, attachment.asText())
    }
}
