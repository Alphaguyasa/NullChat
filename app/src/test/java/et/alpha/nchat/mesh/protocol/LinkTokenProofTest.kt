package et.alpha.nchat.mesh.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkTokenProofTest {

    private val signingKey = ByteArray(32) { it.toByte() }

    @Test
    fun `a link proof round-trips through a whole packet`() {
        val digest = linkTokenDigest(byteArrayOf(9, 8, 7))
        val recipient = ByteArray(Packet.PEER_ID_SIZE) { 3 }
        val packet = linkProofPacket(
            messageId = ByteArray(Packet.MESSAGE_ID_SIZE) { 7 },
            senderId = peerIdFor(signingKey),
            recipientId = recipient,
            proof = LinkTokenProof(signingKey, digest),
            timestamp = 1_700_000_000_000L,
        )

        val decoded = PacketCodec.decode(PacketCodec.encode(packet))

        assertEquals(PacketType.LINK_PROOF, decoded.type)
        assertEquals(1, decoded.ttl, "a link proof must never be relayed")
        assertArrayEquals(recipient, decoded.recipientId)
        val proof = LinkTokenProof.parse(decoded.payload)!!
        assertArrayEquals(signingKey, proof.signingKey)
        assertArrayEquals(digest, proof.tokenDigest)
        assertArrayEquals(peerIdFor(signingKey), proof.peerId)
    }

    @Test
    fun `the payload fits a single packet`() {
        assertTrue(LinkTokenProof.PAYLOAD_SIZE <= Packet.MAX_PAYLOAD_SIZE)
    }

    @Test
    fun `a payload of the wrong size is not a proof`() {
        assertNull(LinkTokenProof.parse(ByteArray(63)))
        assertNull(LinkTokenProof.parse(ByteArray(65)))
    }

    @Test
    fun `the digest is fixed size and differs per token`() {
        val a = linkTokenDigest(ByteArray(4) { 1 })
        val b = linkTokenDigest(ByteArray(4) { 2 })

        assertEquals(LinkTokenProof.DIGEST_SIZE, a.size)
        assertEquals(LinkTokenProof.DIGEST_SIZE, linkTokenDigest(ByteArray(100)).size)
        assertFalse(a.contentEquals(b))
        assertArrayEquals(a, linkTokenDigest(ByteArray(4) { 1 }))
    }

    @Test
    fun `the wire value is new`() {
        assertEquals(PacketType.LINK_PROOF, PacketType.fromWire(8))
        assertEquals(PacketType.entries.size, PacketType.entries.map { it.wire }.toSet().size)
    }
}
