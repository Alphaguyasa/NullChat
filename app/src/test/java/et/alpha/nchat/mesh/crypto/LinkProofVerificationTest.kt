package et.alpha.nchat.mesh.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import et.alpha.nchat.mesh.protocol.LinkTokenProof
import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketSigner
import et.alpha.nchat.mesh.protocol.Verification
import et.alpha.nchat.mesh.protocol.linkProofPacket
import et.alpha.nchat.mesh.protocol.linkTokenDigest
import et.alpha.nchat.mesh.protocol.peerIdFor
import et.alpha.nchat.mesh.protocol.signing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A LINK_PROOF carries its own key, like an ANNOUNCE, so it verifies even from
 * a peer the directory has not filed yet, and only for the id that key hashes to.
 */
class LinkProofVerificationTest {

    private val verifier = DirectoryPacketVerifier(PublicKeyDirectory())
    private val alice = Ed25519Sign.KeyPair.newKeyPair()
    private val mallory = Ed25519Sign.KeyPair.newKeyPair()
    private val digest = linkTokenDigest(byteArrayOf(1, 2, 3))

    private fun proofPacket(
        key: Ed25519Sign.KeyPair,
        claimedId: ByteArray = peerIdFor(key.publicKey),
        signWith: Ed25519Sign.KeyPair = key,
    ): Packet {
        val packet = linkProofPacket(
            messageId = ByteArray(Packet.MESSAGE_ID_SIZE) { 4 },
            senderId = claimedId,
            recipientId = ByteArray(Packet.PEER_ID_SIZE) { 5 },
            proof = LinkTokenProof(key.publicKey, digest),
            timestamp = 1_700_000_000_000L,
        )
        val signer = PacketSigner { Ed25519Sign(signWith.privateKey).sign(it) }
        return signer.signing(packet)
    }

    @Test
    fun `a proof signed by its own key verifies without a directory entry`() {
        assertEquals(Verification.VALID, verifier.verify(proofPacket(alice)))
    }

    @Test
    fun `a proof claiming someone else's id is rejected`() {
        val forged = proofPacket(mallory, claimedId = peerIdFor(alice.publicKey))

        assertEquals(Verification.INVALID, verifier.verify(forged))
    }

    @Test
    fun `a proof signed by another key is rejected`() {
        assertEquals(Verification.INVALID, verifier.verify(proofPacket(alice, signWith = mallory)))
    }

    @Test
    fun `a malformed proof is rejected`() {
        val packet = proofPacket(alice).copy(payload = ByteArray(10))

        assertEquals(Verification.INVALID, verifier.verify(packet))
    }
}
