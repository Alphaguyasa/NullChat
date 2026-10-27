package et.alpha.nchat.mesh.protocol

/**
 * A single mesh packet, in its decoded form.
 *
 * Pure Kotlin by design: the protocol layer is unit-testable on the JVM and must
 * not reach for Android framework types. Encoding and decoding live in
 * [PacketCodec] — nothing else in the app may serialize a packet by hand.
 *
 * Wire layout (big-endian) is documented on [PacketCodec].
 */
data class Packet(
    val version: Int = PROTOCOL_VERSION,
    val type: PacketType,
    /** UUID identifying this message; the router dedups on it. */
    val messageId: ByteArray,
    /** First 8 bytes of SHA-256 of the sender's public key. */
    val senderId: ByteArray,
    /** Destination peer, or all zeroes for a broadcast. */
    val recipientId: ByteArray,
    /** Hop budget; decremented on each forward. */
    val ttl: Int,
    /** Epoch millis, carried as an unsigned 64-bit value. */
    val timestamp: Long,
    /** Ciphertext. Never log this. */
    val payload: ByteArray,
    /**
     * Ed25519 signature over every preceding byte **except [ttl]**.
     *
     * The hop counter is the one field every relay is required to modify, so
     * signing it would invalidate the signature on the first forward and make
     * multi-hop delivery unverifiable by construction. See [PacketCodec.signedBytes].
     */
    val signature: ByteArray,
) {
    init {
        require(version in 0..0xFF) { "version must fit in a byte, was $version" }
        require(messageId.size == MESSAGE_ID_SIZE) {
            "messageId must be $MESSAGE_ID_SIZE bytes, was ${messageId.size}"
        }
        require(senderId.size == PEER_ID_SIZE) {
            "senderId must be $PEER_ID_SIZE bytes, was ${senderId.size}"
        }
        require(recipientId.size == PEER_ID_SIZE) {
            "recipientId must be $PEER_ID_SIZE bytes, was ${recipientId.size}"
        }
        require(ttl in 0..0xFF) { "ttl must fit in a byte, was $ttl" }
        require(payload.size <= MAX_PAYLOAD_SIZE) {
            "payload exceeds $MAX_PAYLOAD_SIZE bytes (was ${payload.size}); fragment it first"
        }
        require(signature.size == SIGNATURE_SIZE) {
            "signature must be $SIGNATURE_SIZE bytes, was ${signature.size}"
        }
    }

    /** True when this packet is addressed to every peer rather than one. */
    val isBroadcast: Boolean get() = recipientId.all { it == 0.toByte() }

    /** Total encoded size in bytes, without actually encoding. */
    val encodedSize: Int get() = HEADER_SIZE + payload.size + SIGNATURE_SIZE

    /** Size of the region the signature covers: the header without [ttl], plus payload. */
    val signedSize: Int get() = HEADER_SIZE - TTL_SIZE + payload.size

    /**
     * A copy with [ttl] reduced by one, or null when the packet has run out of
     * hops and must not be forwarded.
     */
    fun decrementTtl(): Packet? = if (ttl <= 1) null else copy(ttl = ttl - 1)

    /** Short, non-identifying rendering of a peer id for logs. */
    fun senderTag(): String = tag(senderId)

    override fun toString(): String =
        "Packet(v=$version, type=$type, from=${tag(senderId)}, to=${tag(recipientId)}, " +
            "ttl=$ttl, payloadLen=${payload.size})"

    // ByteArray fields compare by identity by default, which would silently break
    // the router's dedup and test assertions. Compare contents instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Packet) return false
        return version == other.version &&
            type == other.type &&
            ttl == other.ttl &&
            timestamp == other.timestamp &&
            messageId.contentEquals(other.messageId) &&
            senderId.contentEquals(other.senderId) &&
            recipientId.contentEquals(other.recipientId) &&
            payload.contentEquals(other.payload) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + type.hashCode()
        result = 31 * result + ttl
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + messageId.contentHashCode()
        result = 31 * result + senderId.contentHashCode()
        result = 31 * result + recipientId.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }

    companion object {
        /** Wire-format version this build speaks. */
        const val PROTOCOL_VERSION: Int = 1

        const val MESSAGE_ID_SIZE: Int = 16
        const val PEER_ID_SIZE: Int = 8
        const val SIGNATURE_SIZE: Int = 64

        /** Width of the hop counter, which the signature deliberately skips. */
        const val TTL_SIZE: Int = 1

        /** version + type + messageId + senderId + recipientId + ttl + timestamp + payloadLen. */
        const val HEADER_SIZE: Int =
            1 + 1 + MESSAGE_ID_SIZE + PEER_ID_SIZE + PEER_ID_SIZE + 1 + 8 + 2

        /** Budget a whole packet must fit inside before fragmentation kicks in. */
        const val MAX_PACKET_SIZE: Int = 180

        /** What is left for ciphertext once header and signature are paid for. */
        const val MAX_PAYLOAD_SIZE: Int = MAX_PACKET_SIZE - HEADER_SIZE - SIGNATURE_SIZE

        /** Default hop budget for a freshly-created packet. */
        const val DEFAULT_TTL: Int = 6

        /** Recipient value meaning "everyone". */
        val BROADCAST_ID: ByteArray get() = ByteArray(PEER_ID_SIZE)

        /** Truncates a peer id to 4 hex chars, the only form allowed in logs. */
        fun tag(id: ByteArray): String =
            id.take(2).joinToString("") { "%02x".format(it) }
    }
}

/** Packet kinds, as carried in the `packetType` byte. */
enum class PacketType(val wire: Int) {
    ANNOUNCE(1),
    MESSAGE(2),
    ACK(3),
    KEY_EXCHANGE(4),
    FRAGMENT(5),

    /** The sender's chosen display name. Builds before it drop the packet unread. */
    PROFILE(6),

    /** A direct chat's disappearing timer, sealed. Builds before it drop the packet unread. */
    TIMER(7),
    ;

    companion object {
        private val BY_WIRE: Map<Int, PacketType> = entries.associateBy { it.wire }

        /** Returns null for a wire value this build does not understand. */
        fun fromWire(value: Int): PacketType? = BY_WIRE[value]
    }
}
