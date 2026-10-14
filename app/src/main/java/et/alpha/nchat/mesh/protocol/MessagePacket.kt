package et.alpha.nchat.mesh.protocol

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

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
     * Overhead an encrypted payload pays: the AES-GCM authentication tag.
     *
     * Only the tag, because the nonce is taken from the packet's existing
     * `messageId` rather than shipped alongside the ciphertext — see
     * `SessionCipher`. Declared here rather than there so the pure protocol
     * layer can size a message without importing Tink or Android.
     */
    const val CIPHER_OVERHEAD_BYTES: Int = 16

    /**
     * The most chat text one packet can carry, in **bytes, not characters**.
     *
     * 55 bytes: the 180-byte packet budget less a 45-byte header, a 64-byte
     * signature, and the 16-byte AES-GCM tag. Text longer than this is still
     * sendable, up to [MAX_TEXT_BYTES], but travels as FRAGMENT packets instead.
     *
     * The limit is the encrypted one even for broadcasts, which are sent in the
     * clear and would technically fit 71 bytes. Two different limits would mean
     * a message the user could send to everyone but not to one person, with the
     * counter changing as they switched threads.
     */
    const val MAX_BODY_BYTES: Int = Packet.MAX_PAYLOAD_SIZE - CIPHER_OVERHEAD_BYTES

    /**
     * How many bytes [body] will occupy on the wire.
     *
     * UTF-8, so this is not `body.length`: an emoji is four bytes and most
     * non-Latin scripts are two or three. Counting characters here would let a
     * message through the UI that the codec then rejects.
     */
    fun bodyByteCount(body: String): Int = body.toByteArray(Charsets.UTF_8).size

    /**
     * The longest chat text the app will send at all, in bytes.
     *
     * Anything over [MAX_BODY_BYTES] is split by `Fragmenter` into 51-byte
     * chunks, so 1000 bytes is about 20 FRAGMENT packets: under half a second
     * on the air and a small fraction of the receiver's 60 second reassembly
     * window. Fragments are not store-and-forwarded, so every extra packet is
     * another chance for a partial delivery; this ceiling keeps that risk close
     * to a single message's while still fitting a long paragraph.
     */
    const val MAX_TEXT_BYTES: Int = 1000

    /** Whether [body] fits in a single packet. */
    fun fits(body: String): Boolean = bodyByteCount(body) <= MAX_BODY_BYTES

    /** Whether [body] can be sent at all, in one packet or as fragments. */
    fun isSendable(body: String): Boolean = bodyByteCount(body) <= MAX_TEXT_BYTES

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
            // Plaintext at this point. `MeshCoordinator` seals it with
            // `SessionCipher` before it goes anywhere, which cannot happen here
            // without dragging Tink into the pure protocol layer. A broadcast
            // stays in the clear — there is no single recipient to agree a key
            // with.
            payload = payload,
            // Zero for now; `MeshRouter.originate` signs every packet on its way
            // out, so that the queue stores and replays an already-signed packet
            // rather than re-signing it.
            signature = ByteArray(Packet.SIGNATURE_SIZE),
        )
    }

    /** Reads the chat text back out of a delivered packet. */
    fun bodyOf(packet: Packet): String = packet.payload.toString(Charsets.UTF_8)

    /**
     * What the thread shows in place of a direct message that would not decrypt.
     *
     * Stored as the row's body rather than flagged in a column, so it needs no
     * migration and reads correctly in every place a body is shown.
     */
    const val UNDECRYPTABLE_BODY: String = "This message could not be decrypted."

    /**
     * Reads [bytes] as UTF-8 only if they genuinely are UTF-8, or null.
     *
     * The lenient `toString(Charsets.UTF_8)` turns any byte soup into text with
     * replacement characters, which is how an AES-GCM ciphertext that failed to
     * open used to end up on screen as garbage. A strict decoder refuses
     * malformed input instead. Real ciphertext is at least 17 random bytes, and
     * the chance of that happening to be well-formed UTF-8 is negligible.
     */
    fun strictUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    /**
     * The text of a direct message whose decryption failed, or the placeholder.
     *
     * Decryption fails for two different reasons. The sender may not have held
     * our agreement key yet and sent in the clear, which reads as valid UTF-8 and
     * is shown. Or it really is ciphertext we cannot open (a key mismatch,
     * corruption), which is never shown as text.
     */
    fun undecryptedBody(payload: ByteArray): String = strictUtf8(payload) ?: UNDECRYPTABLE_BODY
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
