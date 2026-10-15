package et.alpha.nchat.mesh.crypto

import et.alpha.nchat.mesh.protocol.Announcement
import et.alpha.nchat.mesh.protocol.peerIdFor
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicKeyDirectoryTest {

    private val signing = ByteArray(32) { it.toByte() }
    private val agreement = ByteArray(32) { (it + 100).toByte() }
    private val peerId = peerIdFor(signing)

    @Test
    fun `a peer restored with both keys can be encrypted to straight away`() {
        val directory = PublicKeyDirectory()

        directory.restore(signing, agreement)

        // The bug this guards: after a restart only signing keys were restored,
        // so nothing could be sealed to a known peer until it announced again.
        assertArrayEquals(signing, directory.signingKeyFor(peerId))
        assertArrayEquals(agreement, directory.agreementKeyFor(peerId))
    }

    @Test
    fun `a peer stored without an agreement key stays verify-only`() {
        val directory = PublicKeyDirectory()

        directory.restore(signing, null)

        assertTrue(directory.knows(peerId))
        assertNull(directory.agreementKeyFor(peerId))
    }

    @Test
    fun `a corrupt stored agreement key is dropped, not thrown`() {
        val directory = PublicKeyDirectory()

        directory.restore(signing, ByteArray(7))

        assertTrue(directory.knows(peerId))
        assertNull(directory.agreementKeyFor(peerId))
    }

    @Test
    fun `an announce after a verify-only restore fills in the agreement key`() {
        val directory = PublicKeyDirectory()
        directory.restore(signing, null)

        directory.record(Announcement(signing, agreement))

        assertArrayEquals(agreement, directory.agreementKeyFor(peerId))
    }

    @Test
    fun `a restored agreement key is not replaced by a later one`() {
        val directory = PublicKeyDirectory()
        directory.restore(signing, agreement)

        val other = ByteArray(32) { 9 }
        directory.record(Announcement(signing, other))

        assertArrayEquals(agreement, directory.agreementKeyFor(peerId))
        assertEquals(1, directory.size)
    }
}
