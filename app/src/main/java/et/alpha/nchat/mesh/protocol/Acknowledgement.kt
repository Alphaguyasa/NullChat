package et.alpha.nchat.mesh.protocol

/**
 * What an ACK packet carries: proof that a direct message reached its recipient.
 *
 * ```
 * ackedMessageId  16B  the messageId of the MESSAGE (or fragmented message) received
 * hops            u8   how many links the message crossed to get here, 0 if unknown
 * ```
 *
 * 17 bytes, well inside the 71-byte payload budget.
 *
 * ## Why this is trustworthy
 *
 * The ACK is an ordinary signed packet, so it verifies against the recipient's own
 * key. A relay can drop an ACK but cannot forge one, and the sender only accepts an
 * ACK whose `senderId` is the peer the original message was addressed to — so a
 * third device cannot mark somebody else's message delivered either.
 *
 * ## Why it is not encrypted
 *
 * It reveals that *some* message from A reached B, which anyone in range already
 * learns from watching the traffic pattern. Sealing 17 bytes would add a 16-byte
 * tag for no real privacy gain.
 *
 * ## Why hops travels here
 *
 * The sender cannot know how far its own message went: the remaining ttl is only
 * visible to whoever receives it. The recipient reports it back so the bubble can
 * read `delivered · 2 hops`. Like every hop count it is derived from the unsigned
 * ttl byte and is a hint, not a measurement.
 */
class Acknowledgement(
    val ackedMessageId: ByteArray,
    val hops: Int,
) {

    init {
        require(ackedMessageId.size == Packet.MESSAGE_ID_SIZE) {
            "acked message id must be ${Packet.MESSAGE_ID_SIZE} bytes, was ${ackedMessageId.size}"
        }
        require(hops in 0..MAX_HOPS) { "hops must be in 0..$MAX_HOPS, was $hops" }
    }

    fun encode(): ByteArray = ackedMessageId + hops.toByte()

    companion object {
        const val PAYLOAD_SIZE: Int = Packet.MESSAGE_ID_SIZE + 1

        /** Fits an unsigned byte; anything real is at most `Packet.DEFAULT_TTL`. */
        const val MAX_HOPS: Int = 0xFF

        /**
         * Parses an ACK payload, or null if it is not one.
         *
         * Null rather than an exception: the bytes come off the radio, so a
         * malformed payload is routine hostile input, not a programming error.
         */
        fun parse(payload: ByteArray): Acknowledgement? {
            if (payload.size != PAYLOAD_SIZE) return null
            return Acknowledgement(
                ackedMessageId = payload.copyOfRange(0, Packet.MESSAGE_ID_SIZE),
                hops = payload[Packet.MESSAGE_ID_SIZE].toInt() and 0xFF,
            )
        }
    }
}

/**
 * Builds the ACK packet answering a message from [recipientId].
 *
 * Unicast back to the original sender, with a fresh [messageId] of its own: reusing
 * the acked id would make every relay that already carried the message drop the ACK
 * as a duplicate.
 */
fun acknowledgementPacket(
    messageId: ByteArray,
    senderId: ByteArray,
    recipientId: ByteArray,
    acknowledgement: Acknowledgement,
    timestamp: Long,
    ttl: Int = Packet.DEFAULT_TTL,
): Packet = Packet(
    type = PacketType.ACK,
    messageId = messageId,
    senderId = senderId,
    recipientId = recipientId,
    ttl = ttl,
    timestamp = timestamp,
    payload = acknowledgement.encode(),
    signature = ByteArray(Packet.SIGNATURE_SIZE),
)
