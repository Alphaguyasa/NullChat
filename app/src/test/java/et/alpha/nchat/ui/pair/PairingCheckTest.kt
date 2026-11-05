package et.alpha.nchat.ui.pair

import et.alpha.nchat.mesh.crypto.PublicKeyDirectory
import et.alpha.nchat.mesh.protocol.Announcement
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PairingCheckTest {

    private val signing = ByteArray(32) { it.toByte() }
    private val realAgreement = ByteArray(32) { (it + 100).toByte() }
    private val attackerAgreement = ByteArray(32) { (it + 200).toByte() }

    @Test
    fun `a code whose agreement key the peer signed matches`() {
        val directory = PublicKeyDirectory()
        directory.record(Announcement(signing, realAgreement))

        assertEquals(
            PairingCheck.MATCHES,
            checkPairing(Announcement(signing, realAgreement), directory),
        )
    }

    @Test
    fun `a code with a swapped agreement key does not match`() {
        val directory = PublicKeyDirectory()
        directory.record(Announcement(signing, realAgreement))

        // The bug this guards: the fingerprint covers the signing key only, so
        // this code showed the same six words and its agreement key was stored.
        assertEquals(
            PairingCheck.MISMATCH,
            checkPairing(Announcement(signing, attackerAgreement), directory),
        )
    }

    @Test
    fun `a peer not yet heard on the air waits for its announce`() {
        val directory = PublicKeyDirectory()

        assertEquals(
            PairingCheck.AWAITING_ANNOUNCE,
            checkPairing(Announcement(signing, attackerAgreement), directory),
        )
    }

    @Test
    fun `pairing takes the agreement key from the signed announce, not the code`() {
        val directory = PublicKeyDirectory()
        val scanned = Announcement(signing, attackerAgreement)

        // What the view model does for a peer not heard yet.
        directory.pinSigningKey(scanned.signingKey)
        assertNull(directory.agreementKeyFor(scanned.peerId))

        directory.record(Announcement(signing, realAgreement))

        assertArrayEquals(realAgreement, directory.agreementKeyFor(scanned.peerId))
    }
}
