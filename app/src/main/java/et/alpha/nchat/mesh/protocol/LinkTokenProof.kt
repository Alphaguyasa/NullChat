package et.alpha.nchat.mesh.protocol

import java.security.MessageDigest

/**
 * What a LINK_PROOF packet carries: the sender's signing key and a digest of
 * the link's authentication token.
 *
 * ```
 * signingKey   32B  Ed25519, as in an ANNOUNCE. peerId is its SHA-256 prefix.
 * tokenDigest  32B  linkTokenDigest(the token both ends of this link see)
 * ```
 *
 * A signed ANNOUNCE proves the key holder spoke, not that it spoke *on this
 * link*: a stranger in range of a contact can pass the contact's live
 * announcement down its own link and be taken for the contact. Nearby gives
 * both ends of one connection the same raw authentication token, derived from
 * that connection's key exchange, and a relay sits on two connections with two
 * different tokens. Signing the token's digest therefore pins the proof to one
 * link: the contact signs the token of its link to the relay, which is not the
 * one this phone checks against.
 *
 * Self-authenticating like an ANNOUNCE (the key is in the payload), so it can
 * be checked even if it overtakes the announcement that would file the key.
 *
 * The digest, not the token itself, goes on the air. Both ends already know the
 * token, so nothing is hidden from them, but a fixed 32 bytes keeps the payload
 * inside one packet whatever length Nearby's token has.
 */
class LinkTokenProof(
    val signingKey: ByteArray,
    val tokenDigest: ByteArray,
) {

    init {
        require(signingKey.size == Announcement.KEY_SIZE) {
            "signing key must be ${Announcement.KEY_SIZE} bytes, was ${signingKey.size}"
        }
        require(tokenDigest.size == DIGEST_SIZE) {
            "token digest must be $DIGEST_SIZE bytes, was ${tokenDigest.size}"
        }
    }

    /** The id this proof commits to. */
    val peerId: ByteArray get() = peerIdFor(signingKey)

    fun encode(): ByteArray = signingKey + tokenDigest

    companion object {
        const val DIGEST_SIZE: Int = 32
        const val PAYLOAD_SIZE: Int = Announcement.KEY_SIZE + DIGEST_SIZE

        /** Parses a LINK_PROOF payload, or null if it is not one. Hostile input, so no throwing. */
        fun parse(payload: ByteArray): LinkTokenProof? {
            if (payload.size != PAYLOAD_SIZE) return null
            return LinkTokenProof(
                signingKey = payload.copyOfRange(0, Announcement.KEY_SIZE),
                tokenDigest = payload.copyOfRange(Announcement.KEY_SIZE, PAYLOAD_SIZE),
            )
        }
    }
}

/**
 * SHA-256 of a domain tag and the link's raw authentication token.
 *
 * The tag keeps the signature from meaning anything outside this protocol: a
 * signed digest of a bare token could otherwise be replayed as a proof in some
 * other place that also signs 32-byte hashes.
 */
fun linkTokenDigest(token: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").run {
        update(LINK_TOKEN_DOMAIN)
        update(token)
        digest()
    }

private val LINK_TOKEN_DOMAIN: ByteArray = "nchat-link-token-v1".toByteArray(Charsets.US_ASCII)

/**
 * Builds the LINK_PROOF this device sends down one freshly connected link.
 *
 * ttl 1: the proof means something only to the phone at the other end of the
 * link, so it must never travel further, and a router that sees it spends its
 * last hop. Addressed to that phone for the same reason.
 */
fun linkProofPacket(
    messageId: ByteArray,
    senderId: ByteArray,
    recipientId: ByteArray,
    proof: LinkTokenProof,
    timestamp: Long,
): Packet = Packet(
    type = PacketType.LINK_PROOF,
    messageId = messageId,
    senderId = senderId,
    recipientId = recipientId,
    ttl = 1,
    timestamp = timestamp,
    payload = proof.encode(),
    signature = ByteArray(Packet.SIGNATURE_SIZE),
)
