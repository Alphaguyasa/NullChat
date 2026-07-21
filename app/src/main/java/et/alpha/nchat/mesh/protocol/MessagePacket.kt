package et.alpha.nchat.mesh.protocol

/**
 * Builds MESSAGE packets from chat text.
 *
 * Deliberately a pure function over plain types rather than a method on the
 * coordinator: this is the one part of the send path with real logic — id
 * conversion, the payload budget, broadcast versus direct addressing — and
 * keeping it free of Room and Android means it is covered by the JVM suite
 * instead of only by two phones on a desk.
 */
object MessagePacket {

    /**
     * The most chat text one packet can carry, in **bytes, not characters**.
     *
     * 71 bytes: the 180-byte packet budget less a 45-byte header and a 64-byte
     * signature. Until `Fragmenter` exists this is a hard ceiling on a message,
     * and the send box enforces it before the user can hit send.
     */
    const val MAX_BODY_BYTES: Int = Packet.MAX_PAYLOAD_SIZE

    /**
     * How many bytes [body] will occupy on the wire.
     *
     * UTF-8, so this is not `body.length`: an emoji is four bytes and most
     * non-Latin scripts are two or three. Counting characters here would let a
     * message through the UI that the codec then rejects.
     */
    fun bodyByteCount(body: String): Int = body.toByteArray(Charsets.UTF_8).size

    /** Whether [body] fits in a single packet. */
    fun fits(body: String): Boolean = bodyByteCount(body) <= MAX_BODY_BYTES

    /**
     * Assembles a MESSAGE packet.
     *
     * @param messageId the 32-char hex id of the already-persisted row. Reusing
     *   the database id as the wire id is what lets an acknowledgement — and the
     *   receiver's own dedup — match a packet back to a row.
     * @param recipientHexId the destination peer, or null to broadcast to everyone.
     *
     * @throws IllegalArgumentException if [body] exceeds [MAX_BODY_BYTES].
     */
    fun build(
        messageId: String,
        senderId: ByteArray,
        recipientHexId: String?,
        body: String,
        timestamp: Long,
        ttl: Int = Packet.DEFAULT_TTL,
    ): Packet {
        val payload = body.toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_BODY_BYTES) {
            "message is ${payload.size} bytes, over the $MAX_BODY_BYTES byte limit"
        }

        return Packet(
            type = PacketType.MESSAGE,
            messageId = messageId.hexToBytes(Packet.MESSAGE_ID_SIZE, "messageId"),
            senderId = senderId,
            recipientId = recipientHexId
                ?.hexToBytes(Packet.PEER_ID_SIZE, "recipientId")
                ?: Packet.BROADCAST_ID,
            ttl = ttl,
            timestamp = timestamp,
            // Plaintext. mesh/crypto's SessionCipher does not exist yet, so this
            // is where encryption goes and currently does not happen — every
            // message is readable by anyone in Bluetooth range.
            payload = payload,
            // Unsigned. KeyManager does not exist, the router does not verify,
            // and so any device can claim any sender id. Both gaps are tracked
            // as v1 ship blockers.
            signature = ByteArray(Packet.SIGNATURE_SIZE),
        )
    }

    /** Reads the chat text back out of a delivered packet. */
    fun bodyOf(packet: Packet): String = packet.payload.toString(Charsets.UTF_8)
}

/**
 * Decodes a hex id to raw bytes, checking the width the wire format expects.
 *
 * Separate from `hexIdToBytes` in the data layer because that one is a pure
 * codec with no opinion on length; a wrong-width id here would otherwise fail
 * later inside [Packet]'s init block with a message that does not say which
 * field was at fault.
 */
private fun String.hexToBytes(expectedSize: Int, field: String): ByteArray {
    require(length == expectedSize * 2) {
        "$field must be ${expectedSize * 2} hex chars, was $length"
    }
    return ByteArray(expectedSize) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "$field contains a non-hex character" }
        ((hi shl 4) or lo).toByte()
    }
}
