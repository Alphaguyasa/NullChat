package et.alpha.nchat.mesh.crypto

import app.cash.turbine.test
import et.alpha.nchat.mesh.protocol.Announcement
import et.alpha.nchat.mesh.protocol.peerIdFor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `learning an agreement key is announced once`() = runTest {
        val directory = PublicKeyDirectory()
        directory.restore(signing, null)

        directory.agreementKeyLearned.test {
            directory.record(Announcement(signing, agreement))
            assertEquals(peerIdHex(), awaitItem())

            // First key wins, so a repeat ANNOUNCE is not news.
            directory.record(Announcement(signing, agreement))
            expectNoEvents()
        }
    }

    @Test
    fun `strangers beyond the cap push out the least recently used`() {
        val directory = PublicKeyDirectory(maxTransient = 2)
        val (a, b, c) = (1..3).map { n -> ByteArray(32) { n.toByte() } }
        directory.recordSigningKey(a)
        directory.recordSigningKey(b)

        // Verifying a packet from a touches it, so b is now the stalest.
        directory.signingKeyFor(peerIdFor(a))
        directory.recordSigningKey(c)

        // The bug this guards: every self-signed ANNOUNCE stayed in memory until
        // restart, so a phone minting keys could grow the heap without limit.
        assertEquals(2, directory.size)
        assertTrue(directory.knows(peerIdFor(a)))
        assertFalse(directory.knows(peerIdFor(b)))
        assertTrue(directory.knows(peerIdFor(c)))
    }

    @Test
    fun `pinned keys survive any number of strangers`() {
        val directory = PublicKeyDirectory(maxTransient = 1)
        directory.restore(signing, agreement, pin = true)

        (1..5).forEach { n -> directory.recordSigningKey(ByteArray(32) { n.toByte() }) }

        assertArrayEquals(agreement, directory.agreementKeyFor(peerId))
        assertEquals(2, directory.size)
    }

    @Test
    fun `pinning a stranger already held keeps its agreement key`() {
        val directory = PublicKeyDirectory(maxTransient = 1)
        directory.record(Announcement(signing, agreement))

        // A QR scan of someone first heard on the air.
        directory.record(Announcement(signing, agreement), pin = true)
        directory.recordSigningKey(ByteArray(32) { 7 })

        assertArrayEquals(agreement, directory.agreementKeyFor(peerId))
        assertEquals(2, directory.size)
    }

    @Test
    fun `an evicted stranger is learned again from its next announce`() = runTest {
        val directory = PublicKeyDirectory(maxTransient = 1)
        directory.record(Announcement(signing, agreement))
        directory.recordSigningKey(ByteArray(32) { 7 })
        assertFalse(directory.knows(peerId))

        directory.agreementKeyLearned.test {
            directory.record(Announcement(signing, agreement))
            // Signalled again, so messages held for it while it was forgotten go out.
            assertEquals(peerIdHex(), awaitItem())
        }
        assertArrayEquals(agreement, directory.agreementKeyFor(peerId))
    }

    private fun peerIdHex() = peerId.joinToString("") { "%02x".format(it) }
}
