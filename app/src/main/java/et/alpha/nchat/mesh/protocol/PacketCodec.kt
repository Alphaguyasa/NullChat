package et.alpha.nchat.mesh.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The single encode/decode path for mesh packets. No other component may
 * serialize a packet.
 *
 * Wire format, big-endian:
 * ```
 * version      u8
 * packetType   u8    // 1=ANNOUNCE 2=MESSAGE 3=ACK 4=KEY_EXCHANGE 5=FRAGMENT
 * messageId    16B
 * senderId     8B
 * recipientId  8B    // all zero = broadcast
 * ttl          u8    // NOT signed: every relay decrements it
 * timestamp    u64
 * payloadLen   u16
 * payload      bytes // encrypted
 * signature    64B   // Ed25519 over all preceding bytes except ttl
 * ```
 *
 * [decode] treats its input as hostile: every length is bounds-checked against
 * the packet budget before a single byte is allocated on its behalf.
 */
object PacketCodec {

    /** Serializes [packet] into its complete wire form, signature included. */
    fun encode(packet: Packet): ByteArray {
        val buffer = ByteBuffer.allocate(packet.encodedSize).order(ByteOrder.BIG_ENDIAN)
        writeFields(buffer, packet, includeTtl = true)
        buffer.put(packet.signature)
        return buffer.array()
    }

    /**
     * The exact bytes an Ed25519 signature must cover: everything from the
     * version byte through the end of the payload, **skipping the ttl**.
     *
     * The ttl is excluded because routing rule 4 requires every relay to
     * decrement it. Were it signed, a packet's signature would fail verification
     * at the second hop and nothing beyond a direct neighbour could ever be
     * authenticated — the hop counter is mutable in transit by design, so it
     * cannot also be covered by an end-to-end signature.
     *
     * The consequence is that ttl is unauthenticated: a hostile relay can lower
     * it to truncate delivery, or raise it to make a packet circulate longer than
     * the sender intended. Both are bounded by the dedup cache and are the
     * accepted trade for verifiable multi-hop delivery.
     *
     * The crypto layer signs and verifies through this so the signed region can
     * never drift out of step with [encode].
     */
    fun signedBytes(packet: Packet): ByteArray {
        val buffer = ByteBuffer.allocate(packet.signedSize).order(ByteOrder.BIG_ENDIAN)
        writeFields(buffer, packet, includeTtl = false)
        return buffer.array()
    }

    /**
     * Parses a complete packet from [bytes].
     *
     * This verifies structure only — it does not check the signature. Callers
     * must verify against [signedBytes] before trusting anything decoded here.
     *
     * @throws PacketDecodeException if the input is not a well-formed packet.
     */
    fun decode(bytes: ByteArray): Packet {
        val minimum = Packet.HEADER_SIZE + Packet.SIGNATURE_SIZE
        if (bytes.size < minimum) {
            throw PacketDecodeException.Truncated(expected = minimum, actual = bytes.size)
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        val version = buffer.readU8()
        if (version != Packet.PROTOCOL_VERSION) {
            throw PacketDecodeException.UnsupportedVersion(version)
        }

        val typeByte = buffer.readU8()
        val type = PacketType.fromWire(typeByte)
            ?: throw PacketDecodeException.UnknownPacketType(typeByte)

        val messageId = buffer.readBytes(Packet.MESSAGE_ID_SIZE)
        val senderId = buffer.readBytes(Packet.PEER_ID_SIZE)
        val recipientId = buffer.readBytes(Packet.PEER_ID_SIZE)
        val ttl = buffer.readU8()
        val timestamp = buffer.long

        // Check the declared length against the budget *before* trusting it, so a
        // hostile peer cannot make us size a buffer from an attacker-chosen number.
        val payloadLen = buffer.readU16()
        if (payloadLen > Packet.MAX_PAYLOAD_SIZE) {
            throw PacketDecodeException.OversizedPayload(payloadLen)
        }

        val expectedSize = Packet.HEADER_SIZE + payloadLen + Packet.SIGNATURE_SIZE
        if (bytes.size < expectedSize) {
            throw PacketDecodeException.Truncated(expected = expectedSize, actual = bytes.size)
        }
        if (bytes.size > expectedSize) {
            throw PacketDecodeException.TrailingBytes(extra = bytes.size - expectedSize)
        }

        val payload = buffer.readBytes(payloadLen)
        val signature = buffer.readBytes(Packet.SIGNATURE_SIZE)

        return Packet(
            version = version,
            type = type,
            messageId = messageId,
            senderId = senderId,
            recipientId = recipientId,
            ttl = ttl,
            timestamp = timestamp,
            payload = payload,
            signature = signature,
        )
    }

    /**
     * Writes header and payload in wire order.
     *
     * [includeTtl] is the single difference between the encoded form and the
     * signed form; both are written from this one function so the two can never
     * disagree about field order.
     */
    private fun writeFields(buffer: ByteBuffer, packet: Packet, includeTtl: Boolean) {
        buffer.put(packet.version.toByte())
        buffer.put(packet.type.wire.toByte())
        buffer.put(packet.messageId)
        buffer.put(packet.senderId)
        buffer.put(packet.recipientId)
        if (includeTtl) buffer.put(packet.ttl.toByte())
        buffer.putLong(packet.timestamp)
        buffer.putShort(packet.payload.size.toShort())
        buffer.put(packet.payload)
    }

    private fun ByteBuffer.readU8(): Int = get().toInt() and 0xFF

    private fun ByteBuffer.readU16(): Int = short.toInt() and 0xFFFF

    private fun ByteBuffer.readBytes(count: Int): ByteArray =
        ByteArray(count).also { get(it) }
}

/** Structural failures encountered while parsing bytes off the wire. */
sealed class PacketDecodeException(message: String) : Exception(message) {

    /** Fewer bytes arrived than the format requires. */
    class Truncated(val expected: Int, val actual: Int) :
        PacketDecodeException("truncated packet: expected $expected bytes, got $actual")

    /** More bytes arrived than the declared lengths account for. */
    class TrailingBytes(val extra: Int) :
        PacketDecodeException("packet has $extra unexpected trailing byte(s)")

    /** A protocol version this build does not speak. */
    class UnsupportedVersion(val version: Int) :
        PacketDecodeException("unsupported protocol version $version")

    /** A packet type byte outside the known set. */
    class UnknownPacketType(val value: Int) :
        PacketDecodeException("unknown packet type $value")

    /** Declared payload length exceeds the per-packet budget. */
    class OversizedPayload(val declared: Int) :
        PacketDecodeException(
            "declared payload of $declared bytes exceeds max ${Packet.MAX_PAYLOAD_SIZE}",
        )
}
