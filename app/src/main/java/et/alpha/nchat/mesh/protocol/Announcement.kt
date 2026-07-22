package et.alpha.nchat.mesh.protocol

import java.util.Base64

/**
 * What an ANNOUNCE packet carries: this device's two public keys.
 *
 * ```
 * signingKey    32B  Ed25519, the identity key. peerId is its SHA-256 prefix.
 * agreementKey  32B  X25519, used to derive message encryption keys.
 * ```
 *
 * 64 bytes, inside the 71-byte payload budget with 7 to spare. Both keys travel
 * together so that one exchange is enough to both verify a peer and talk to them
 * privately — splitting them would mean a device could be verifiable but
 * unreachable, or reachable but unauthenticated.
 *
 * ## Why this is safe to accept from a stranger
 *
 * An ANNOUNCE is self-authenticating. The signature is checked against the
 * signing key in the payload, and the sender id is checked to be
 * `peerIdFor(signingKey)`. A forger can generate a keypair and announce it, but
 * the id that comes out is a hash of *their* key, so they cannot announce
 * themselves as somebody else. The worst they achieve is introducing a device
 * nobody has heard of, which is what a new device does anyway.
 *
 * What that does **not** give you is any assurance the human behind the key is
 * who you think. That is what QR pairing and the fingerprint word list are for.
 */
class Announcement(
    val signingKey: ByteArray,
    val agreementKey: ByteArray,
) {

    init {
        require(signingKey.size == KEY_SIZE) {
            "signing key must be $KEY_SIZE bytes, was ${signingKey.size}"
        }
        require(agreementKey.size == KEY_SIZE) {
            "agreement key must be $KEY_SIZE bytes, was ${agreementKey.size}"
        }
    }

    /** The id this announcement commits to. */
    val peerId: ByteArray get() = peerIdFor(signingKey)

    fun encode(): ByteArray = signingKey + agreementKey

    companion object {
        const val KEY_SIZE: Int = 32
        const val PAYLOAD_SIZE: Int = KEY_SIZE * 2

        /**
         * Parses an ANNOUNCE payload, or null if it is not one.
         *
         * Null rather than an exception because this runs on bytes handed over
         * by an unauthenticated stranger; a malformed announcement is routine
         * hostile input, not a programming error.
         */
        fun parse(payload: ByteArray): Announcement? {
            if (payload.size != PAYLOAD_SIZE) return null
            return Announcement(
                signingKey = payload.copyOfRange(0, KEY_SIZE),
                agreementKey = payload.copyOfRange(KEY_SIZE, PAYLOAD_SIZE),
            )
        }
    }
}

/**
 * The text a pairing QR code carries.
 *
 * `nchat:1:<base64url of the 64-byte announcement>` — a version tag so a future
 * format change is detected rather than misparsed, and URL-safe base64 without
 * padding so the QR stays in alphanumeric-friendly territory and small enough to
 * scan quickly.
 *
 * The same two keys as an ANNOUNCE, carried out of band. The difference is not
 * the content but the channel: keys that arrive over the radio are
 * trust-on-first-use, whereas keys read off a screen the user is physically
 * looking at are what makes a contact *verified*.
 */
fun Announcement.toPairingUri(): String =
    "$PAIRING_SCHEME:$PAIRING_VERSION:" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(encode())

/** Parses a pairing QR's text, or null if it is not one of ours. */
fun parsePairingUri(text: String): Announcement? {
    val parts = text.trim().split(":")
    if (parts.size != 3) return null
    if (parts[0] != PAIRING_SCHEME || parts[1] != PAIRING_VERSION) return null
    val decoded = runCatching { Base64.getUrlDecoder().decode(parts[2]) }.getOrNull() ?: return null
    return Announcement.parse(decoded)
}

const val PAIRING_SCHEME: String = "nchat"
const val PAIRING_VERSION: String = "1"

/** Builds the ANNOUNCE packet this device broadcasts to introduce itself. */
fun announcementPacket(
    messageId: ByteArray,
    announcement: Announcement,
    timestamp: Long,
    ttl: Int = Packet.DEFAULT_TTL,
): Packet = Packet(
    type = PacketType.ANNOUNCE,
    messageId = messageId,
    senderId = announcement.peerId,
    // Broadcast: an introduction is for whoever can hear it, and it needs to
    // reach devices that by definition do not know us yet.
    recipientId = Packet.BROADCAST_ID,
    ttl = ttl,
    timestamp = timestamp,
    payload = announcement.encode(),
    signature = ByteArray(Packet.SIGNATURE_SIZE),
)
