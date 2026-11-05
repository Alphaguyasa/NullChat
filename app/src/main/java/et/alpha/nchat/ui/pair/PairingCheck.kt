package et.alpha.nchat.ui.pair

import et.alpha.nchat.mesh.crypto.PublicKeyDirectory
import et.alpha.nchat.mesh.protocol.Announcement

/** How a scanned code's agreement key compares with the one the peer signed. */
internal enum class PairingCheck {

    /** The peer's signed ANNOUNCE carried the same agreement key. */
    MATCHES,

    /**
     * No signed agreement key yet. The signing key is still verified, and
     * messages to the peer wait until its next ANNOUNCE brings one.
     */
    AWAITING_ANNOUNCE,

    /** The QR's agreement key is not the one the peer signed. */
    MISMATCH,
}

/**
 * Checks the agreement key in a scanned QR against the one [directory] holds.
 *
 * The fingerprint words cover the signing key only, so a QR built from a real
 * signing key and an attacker's agreement key shows the same six words. Had it
 * been trusted, every direct message would be sealed to the attacker. The QR's
 * agreement key is therefore never stored; it is only compared. Whatever the
 * directory holds came from an ANNOUNCE signed by that signing key, so a
 * mismatch means the code was altered.
 */
internal fun checkPairing(scanned: Announcement, directory: PublicKeyDirectory): PairingCheck {
    val signed = directory.agreementKeyFor(scanned.peerId) ?: return PairingCheck.AWAITING_ANNOUNCE
    return if (signed.contentEquals(scanned.agreementKey)) PairingCheck.MATCHES else PairingCheck.MISMATCH
}
