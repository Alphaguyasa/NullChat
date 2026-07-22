package et.alpha.nchat.mesh.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * [peerIdFor] is the binding between a key and an identity.
 *
 * If it ever changed, every device on the mesh would silently become a
 * different device: conversations would orphan and no signature would verify
 * against the id it claims. The golden vector below exists to make that change
 * impossible to make by accident.
 */
class PeerIdTest {

    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `a peer id is the leading eight bytes of the key's sha-256`() {
        // SHA-256 of the bytes 0x00..0x1f, first 8 bytes. Pinned: this value is
        // on the wire and in every stored conversation id.
        assertEquals("630dcd2966c43366", peerIdFor(key).toHex())
    }

    @Test
    fun `a peer id is exactly the wire format's id width`() {
        assertEquals(Packet.PEER_ID_SIZE, peerIdFor(key).size)
    }

    @Test
    fun `the same key always gives the same id`() {
        assertEquals(peerIdFor(key).toHex(), peerIdFor(key.copyOf()).toHex())
    }

    @Test
    fun `one flipped bit gives a different id`() {
        val flipped = key.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }

        assertNotEquals(
            peerIdFor(key).toHex(),
            peerIdFor(flipped).toHex(),
            "a near-identical key must not collide, or one device could impersonate another",
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
