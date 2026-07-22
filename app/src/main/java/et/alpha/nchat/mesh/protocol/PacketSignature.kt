package et.alpha.nchat.mesh.protocol

import java.security.MessageDigest

/**
 * Derives a peer id from an Ed25519 public key: the first
 * [Packet.PEER_ID_SIZE] bytes of its SHA-256.
 *
 * This is what makes an id self-authenticating. An id is not a name a device
 * picks, it is a commitment to a key, so a forger cannot claim someone else's id
 * without their private key — a packet signed by the wrong key simply fails
 * verification against the id it claims.
 *
 * Truncating to 8 bytes costs collision resistance: finding two keys sharing an
 * id is a ~2^32 birthday search, which is not out of reach. The wire format
 * fixes the width at 8 bytes, so the mitigation is out-of-band verification (QR
 * pairing) rather than a wider id.
 */
fun peerIdFor(publicKey: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256")
        .digest(publicKey)
        .copyOf(Packet.PEER_ID_SIZE)

/** The outcome of checking a packet's signature. */
enum class Verification {

    /** Signed by the key the sender id commits to. */
    VALID,

    /** We hold a key for this sender and the signature does not match it. */
    INVALID,

    /**
     * No key is known for this sender, so nothing can be concluded either way.
     *
     * Deliberately not folded into [INVALID]: the two lead to opposite routing
     * decisions. A relay has usually never met the device that originated a
     * packet, so treating "cannot check" as "failed" would drop most relayed
     * traffic and multi-hop delivery would stop working.
     */
    UNKNOWN_SENDER,
}

/**
 * Produces the 64-byte Ed25519 signature over a packet's signed region.
 *
 * Pure interface so `mesh/protocol` and `mesh/router` stay free of Android and
 * of Tink — see `ArchitectureRulesTest`. The implementation lives in
 * `mesh/crypto`.
 */
fun interface PacketSigner {

    /** @param signedBytes exactly what [PacketCodec.signedBytes] returned. */
    fun sign(signedBytes: ByteArray): ByteArray
}

/**
 * Checks a packet's signature against the key its sender id commits to.
 *
 * Takes the whole packet rather than just the signed bytes because an ANNOUNCE
 * is verified differently from everything else: it carries the very key it is
 * signed with, so the verifier needs to see the payload and the type.
 */
fun interface PacketVerifier {

    fun verify(packet: Packet): Verification

    companion object {

        /**
         * Verifies nothing and approves everything.
         *
         * The default for [et.alpha.nchat.mesh.router.MeshRouter] so tests that
         * are not about signatures can keep building packets with placeholder
         * ones. Production always injects the real verifier; if this is ever
         * reached on a device, nothing is authenticated.
         */
        val AcceptAll: PacketVerifier = PacketVerifier { Verification.VALID }
    }
}

/**
 * Returns [packet] with a real signature over its signed region.
 *
 * Routed through [PacketCodec.signedBytes] rather than re-deriving the region,
 * so the bytes signed here and the bytes verified on the far side can never
 * drift apart.
 */
fun PacketSigner.signing(packet: Packet): Packet =
    packet.copy(signature = sign(PacketCodec.signedBytes(packet)))
