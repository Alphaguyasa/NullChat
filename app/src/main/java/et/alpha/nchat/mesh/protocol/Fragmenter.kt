package et.alpha.nchat.mesh.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Splits an oversized payload across FRAGMENT packets, and puts it back together
 * on the far side.
 *
 * ## Why this takes loose parts rather than a [Packet]
 *
 * [Packet] refuses a payload over [Packet.MAX_PAYLOAD_SIZE] in its init block, so
 * an oversized message can never exist as a `Packet` in the first place — there
 * is nothing to hand in. [fragment] therefore takes the fields a message is made
 * of, and [FragmentReassembler] hands back a [ReassembledMessage] rather than a
 * `Packet` for the same reason: the reassembled payload is by definition larger
 * than a packet may carry.
 *
 * ## Trust
 *
 * Each FRAGMENT is an ordinary packet that the router signs on the way out and
 * verifies on the way in, so a piece that reaches the reassembler has already
 * been authenticated on its own. There is deliberately no signature over the
 * *reassembled* whole: signing it would mean the receiver could not check
 * anything until the last piece landed, and a single dropped fragment would turn
 * the entire message unverifiable rather than merely incomplete.
 *
 * The gap that leaves is that a sender can abandon a message half-sent, which is
 * indistinguishable from fragments that simply never arrived. Both end the same
 * way: the buffer expires after [FragmentReassembler.DEFAULT_TIMEOUT_MILLIS] and
 * the partial message is discarded, never surfaced.
 */
object Fragmenter {

    /**
     * A FRAGMENT payload's own header, ahead of the chunk:
     * ```
     * originalId  16B  // messageId of the message being reassembled
     * index       u16
     * total       u16
     * chunk       bytes
     * ```
     */
    const val FRAGMENT_HEADER_SIZE: Int = Packet.MESSAGE_ID_SIZE + 2 + 2

    /** Payload bytes one FRAGMENT carries at the default packet budget: 51. */
    const val DEFAULT_CHUNK_SIZE: Int = Packet.MAX_PAYLOAD_SIZE - FRAGMENT_HEADER_SIZE

    /** `total` is a u16, so this is a hard ceiling from the wire format. */
    const val MAX_FRAGMENTS: Int = 0xFFFF

    /** How much payload fits per fragment at a given packet payload budget. */
    fun chunkSize(maxPayloadSize: Int = Packet.MAX_PAYLOAD_SIZE): Int {
        val capacity = maxPayloadSize - FRAGMENT_HEADER_SIZE
        require(capacity > 0) {
            "a $maxPayloadSize byte payload budget leaves no room for a chunk after " +
                "the $FRAGMENT_HEADER_SIZE byte fragment header"
        }
        return capacity
    }

    /** Whether [payload] fits in one packet and needs no fragmenting at all. */
    fun fits(payload: ByteArray, maxPayloadSize: Int = Packet.MAX_PAYLOAD_SIZE): Boolean =
        payload.size <= maxPayloadSize

    /** How many FRAGMENT packets [size] bytes will occupy. */
    fun fragmentCount(size: Int, maxPayloadSize: Int = Packet.MAX_PAYLOAD_SIZE): Int {
        val capacity = chunkSize(maxPayloadSize)
        // Ceiling division: a trailing partial chunk still costs a whole packet.
        return (size + capacity - 1) / capacity
    }

    /**
     * Splits [payload] into FRAGMENT packets.
     *
     * Every fragment repeats the original's [senderId], [recipientId] and
     * [timestamp] so each one routes and dedups on its own — the mesh has no
     * concept of a message, only of packets, and a relay that carries piece 4
     * without ever seeing piece 3 is the normal case rather than an error.
     *
     * @param messageId the id the reassembled message will be known by. Each
     *   fragment additionally gets its own distinct packet id — see [fragmentId].
     * @throws IllegalArgumentException if [payload] is empty, or needs more than
     *   [MAX_FRAGMENTS] pieces.
     */
    fun fragment(
        messageId: ByteArray,
        senderId: ByteArray,
        recipientId: ByteArray,
        payload: ByteArray,
        timestamp: Long,
        ttl: Int = Packet.DEFAULT_TTL,
        maxPayloadSize: Int = Packet.MAX_PAYLOAD_SIZE,
    ): List<Packet> {
        require(messageId.size == Packet.MESSAGE_ID_SIZE) {
            "messageId must be ${Packet.MESSAGE_ID_SIZE} bytes, was ${messageId.size}"
        }
        require(payload.isNotEmpty()) { "cannot fragment an empty payload" }

        val capacity = chunkSize(maxPayloadSize)
        val total = fragmentCount(payload.size, maxPayloadSize)
        require(total <= MAX_FRAGMENTS) {
            "payload of ${payload.size} bytes needs $total fragments, over the " +
                "$MAX_FRAGMENTS the wire format can number"
        }

        return (0 until total).map { index ->
            val start = index * capacity
            val chunk = payload.copyOfRange(start, minOf(start + capacity, payload.size))

            Packet(
                type = PacketType.FRAGMENT,
                // Distinct per fragment: the router dedups on messageId, so
                // reusing the original id would make every piece after the first
                // look like a duplicate of the first and be dropped.
                messageId = fragmentId(messageId, index),
                senderId = senderId,
                recipientId = recipientId,
                ttl = ttl,
                timestamp = timestamp,
                payload = encodeFragmentPayload(messageId, index, total, chunk),
                // Zero here, as everywhere else a packet is built: MeshRouter
                // signs on originate so the queue stores an already-signed packet.
                signature = ByteArray(Packet.SIGNATURE_SIZE),
            )
        }
    }

    /**
     * The packet id fragment [index] of [messageId] travels under.
     *
     * The original id's first 14 bytes with the index in the last two. Derived
     * rather than random so that a retransmitted fragment is byte-identical to
     * the first attempt and the receiver's seen-cache recognises it — a random id
     * per attempt would let the same chunk flood the mesh twice.
     */
    fun fragmentId(messageId: ByteArray, index: Int): ByteArray {
        val id = messageId.copyOf()
        id[id.size - 2] = ((index ushr 8) and 0xFF).toByte()
        id[id.size - 1] = (index and 0xFF).toByte()
        return id
    }

    private fun encodeFragmentPayload(
        messageId: ByteArray,
        index: Int,
        total: Int,
        chunk: ByteArray,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(FRAGMENT_HEADER_SIZE + chunk.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(messageId)
        buffer.putShort(index.toShort())
        buffer.putShort(total.toShort())
        buffer.put(chunk)
        return buffer.array()
    }
}

/**
 * One piece of a fragmented message, as parsed off the wire.
 *
 * [FragmentHeader.parse] returns null rather than throwing for the same reason
 * `Announcement.parse` does: this runs on bytes from an unauthenticated stranger,
 * so a malformed fragment is routine hostile input, not a programming error.
 */
data class FragmentHeader(
    val messageId: ByteArray,
    val index: Int,
    val total: Int,
    val chunk: ByteArray,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FragmentHeader) return false
        return index == other.index &&
            total == other.total &&
            messageId.contentEquals(other.messageId) &&
            chunk.contentEquals(other.chunk)
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + total
        result = 31 * result + messageId.contentHashCode()
        result = 31 * result + chunk.contentHashCode()
        return result
    }

    companion object {

        /** Reads a FRAGMENT packet's payload, or null if it is not a valid one. */
        fun parse(payload: ByteArray): FragmentHeader? {
            if (payload.size <= Fragmenter.FRAGMENT_HEADER_SIZE) return null

            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val messageId = ByteArray(Packet.MESSAGE_ID_SIZE).also { buffer.get(it) }
            val index = buffer.short.toInt() and 0xFFFF
            val total = buffer.short.toInt() and 0xFFFF

            // A fragment numbered outside its own total is either a bug or an
            // attempt to write past the end of a reassembly buffer.
            if (total == 0 || index >= total) return null

            val chunk = ByteArray(payload.size - Fragmenter.FRAGMENT_HEADER_SIZE)
                .also { buffer.get(it) }
            return FragmentHeader(messageId, index, total, chunk)
        }
    }
}

/** How much of a fragmented message has arrived so far. */
data class FragmentProgress(val received: Int, val total: Int) {

    /** 0f..1f, for a progress bar. */
    val fraction: Float get() = received.toFloat() / total.toFloat()
}

/** A message that arrived in pieces and is now whole again. */
data class ReassembledMessage(
    val messageId: ByteArray,
    val senderId: ByteArray,
    val recipientId: ByteArray,
    val timestamp: Long,
    val payload: ByteArray,
) {

    /** True when the completed message was addressed to every peer. */
    val isBroadcast: Boolean get() = recipientId.all { it == 0.toByte() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReassembledMessage) return false
        return timestamp == other.timestamp &&
            messageId.contentEquals(other.messageId) &&
            senderId.contentEquals(other.senderId) &&
            recipientId.contentEquals(other.recipientId) &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + messageId.contentHashCode()
        result = 31 * result + senderId.contentHashCode()
        result = 31 * result + recipientId.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "ReassembledMessage(from=${Packet.tag(senderId)}, to=${Packet.tag(recipientId)}, " +
            "bytes=${payload.size})"
}

/**
 * Collects FRAGMENT packets until a message is whole.
 *
 * Clock-injected like `SeenCache` and `ForwardQueue`, so the timeout is testable
 * without sleeping for a minute. Expiry is lazy — swept when the reassembler is
 * next touched — so there is no background coroutine to own or cancel.
 *
 * Fragments arrive out of order as a matter of course: flooding sends each piece
 * down whatever path is free, and jitter deliberately scrambles their spacing.
 * Pieces are therefore held in numbered slots and concatenated only once every
 * slot is filled, which costs one array per message and makes arrival order
 * irrelevant.
 *
 * ## Bounds
 *
 * Every limit here exists because a stranger can address fragments at us:
 * [capacity] caps how many part-finished messages we hold at once,
 * [maxMessageBytes] caps how large any one of them may grow, and the timeout
 * caps how long a message nobody is going to finish occupies a slot.
 *
 * Bytes alone are not enough. The slot array is sized from the declared `total`,
 * so a first fragment claiming 65 535 pieces of one byte each passed a byte check
 * and cost half a megabyte of empty slots, times [capacity]. Every piece but the
 * last must therefore be exactly [chunkSize] bytes, as [Fragmenter.fragment]
 * makes them, and `total` may not exceed what [maxMessageBytes] needs at that size.
 */
class FragmentReassembler(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,
    private val timeSource: () -> Long = System::currentTimeMillis,
    private val chunkSize: Int = Fragmenter.DEFAULT_CHUNK_SIZE,
) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
        require(timeoutMillis > 0) { "timeout must be positive, was $timeoutMillis" }
        require(maxMessageBytes > 0) {
            "maxMessageBytes must be positive, was $maxMessageBytes"
        }
        require(chunkSize > 0) { "chunkSize must be positive, was $chunkSize" }
    }

    /** The most pieces a message within [maxMessageBytes] can need at [chunkSize]. */
    private val maxFragments: Int = (maxMessageBytes + chunkSize - 1) / chunkSize

    /**
     * Insertion-ordered, and never re-ordered on access — the same trick
     * `SeenCache` uses. A partial's deadline is set when its first fragment lands
     * and is never extended, so insertion order *is* deadline order and
     * [sweepExpired] can stop at the first live entry instead of scanning
     * everything on every packet.
     *
     * Not extending the deadline on later fragments is deliberate: a sender who
     * kept dribbling one fragment every 59 seconds could otherwise hold a buffer
     * open indefinitely. The 60 seconds is a budget for the whole message.
     */
    private val partials = object : LinkedHashMap<FragmentKey, Partial>(
        INITIAL_BUCKETS,
        LOAD_FACTOR,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FragmentKey, Partial>) =
            size > capacity
    }

    private var _droppedFragments: Long = 0

    /**
     * Fragments discarded as malformed, contradictory, or over-large.
     *
     * A number climbing above zero means someone is sending fragments that do not
     * describe a message we could ever assemble. Duplicates are not counted —
     * flooding makes those routine.
     */
    @get:Synchronized
    val droppedFragments: Long get() = _droppedFragments

    /** Messages currently part-assembled, after expiring anything stale. */
    @get:Synchronized
    val pendingCount: Int
        get() {
            sweepExpired(timeSource())
            return partials.size
        }

    /**
     * Offers one packet to the reassembler.
     *
     * @return the completed message when [packet] was the last piece missing,
     *   otherwise null — which covers every ordinary case: a non-FRAGMENT packet,
     *   a piece of a message still missing others, a duplicate, or a malformed
     *   fragment.
     */
    @Synchronized
    fun offer(packet: Packet): ReassembledMessage? {
        if (packet.type != PacketType.FRAGMENT) return null

        val now = timeSource()
        sweepExpired(now)

        val fragment = FragmentHeader.parse(packet.payload) ?: run {
            _droppedFragments++
            return null
        }

        // Checked before the partial is looked up or created, so a hostile
        // `total` never sizes an array: the slot count is bounded by
        // [maxFragments] and each slot stands for a full chunk.
        val isLast = fragment.index == fragment.total - 1
        val chunkFits = if (isLast) {
            fragment.chunk.size <= chunkSize
        } else {
            fragment.chunk.size == chunkSize
        }
        if (fragment.total > maxFragments || !chunkFits) {
            _droppedFragments++
            return null
        }

        // Keyed by sender as well as message id, so one peer cannot occupy or
        // corrupt the buffer another peer's message is assembling into by
        // claiming the same id.
        val key = FragmentKey(fragment.messageId, packet.senderId)
        val existing = partials[key]

        val partial = if (existing == null) {
            val started = Partial(
                total = fragment.total,
                senderId = packet.senderId.copyOf(),
                recipientId = packet.recipientId.copyOf(),
                timestamp = packet.timestamp,
                deadline = now + timeoutMillis,
            )
            partials[key] = started
            started
        } else {
            // Every piece of a message must agree on how many pieces there are.
            // The offending fragment is dropped rather than the partial, so a
            // forged fragment cannot destroy a reassembly already in progress.
            if (existing.total != fragment.total) {
                _droppedFragments++
                return null
            }
            existing
        }

        // A duplicate is the normal cost of flooding — the same chunk reaches us
        // by several paths. Ignored silently and explicitly not counted as a
        // drop, and critically it does not advance the filled count, or a message
        // could "complete" from one chunk delivered `total` times.
        if (partial.chunks[fragment.index] != null) return null

        if (partial.bytes + fragment.chunk.size > maxMessageBytes) {
            _droppedFragments++
            return null
        }

        partial.chunks[fragment.index] = fragment.chunk
        partial.bytes += fragment.chunk.size
        partial.filled++

        if (partial.filled < partial.total) return null

        partials.remove(key)
        return ReassembledMessage(
            messageId = fragment.messageId,
            senderId = partial.senderId,
            recipientId = partial.recipientId,
            timestamp = partial.timestamp,
            payload = partial.join(),
        )
    }

    /**
     * How far along a part-assembled message is, or null if nothing is being
     * assembled under that (id, sender) pair.
     *
     * Exists so the UI can show a transfer bar for an image arriving in ~280
     * pieces. A message the user can see is coming but not how fast reads as a
     * hang, which is precisely what a slow mesh looks like without this.
     */
    @Synchronized
    fun progressFor(messageId: ByteArray, senderId: ByteArray): FragmentProgress? {
        val partial = partials[FragmentKey(messageId, senderId)] ?: return null
        return FragmentProgress(received = partial.filled, total = partial.total)
    }

    @Synchronized
    fun clear() {
        partials.clear()
    }

    /**
     * Drops messages past their deadline. Insertion order is deadline order, so
     * the first entry still inside its window ends the scan.
     */
    private fun sweepExpired(now: Long) {
        val iterator = partials.entries.iterator()
        while (iterator.hasNext()) {
            if (now < iterator.next().value.deadline) return
            iterator.remove()
        }
    }

    /** One message mid-assembly. */
    private class Partial(
        val total: Int,
        val senderId: ByteArray,
        val recipientId: ByteArray,
        val timestamp: Long,
        val deadline: Long,
    ) {
        val chunks = arrayOfNulls<ByteArray>(total)
        var filled: Int = 0
        var bytes: Int = 0

        fun join(): ByteArray {
            val out = ByteArray(bytes)
            var offset = 0
            for (chunk in chunks) {
                // Non-null by construction: only called once filled == total.
                chunk!!.copyInto(out, offset)
                offset += chunk.size
            }
            return out
        }
    }

    /**
     * A (messageId, senderId) pair usable as a map key.
     *
     * `ByteArray` hashes by identity, so keying on the raw arrays would make
     * every fragment look like the start of a new message and nothing would ever
     * reassemble. Copied because the caller's arrays are not ours to trust for
     * the lifetime of the entry.
     */
    private class FragmentKey(messageId: ByteArray, senderId: ByteArray) {
        private val messageId: ByteArray = messageId.copyOf()
        private val senderId: ByteArray = senderId.copyOf()
        private val hash: Int = 31 * this.messageId.contentHashCode() +
            this.senderId.contentHashCode()

        override fun equals(other: Any?): Boolean =
            this === other || (
                other is FragmentKey &&
                    messageId.contentEquals(other.messageId) &&
                    senderId.contentEquals(other.senderId)
                )

        override fun hashCode(): Int = hash
    }

    companion object {
        /** The 60 second reassembly window from CLAUDE.md. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 60_000L

        /** Concurrent part-finished messages held before the oldest is evicted. */
        const val DEFAULT_CAPACITY: Int = 32

        /**
         * Ceiling on one reassembled message.
         *
         * 64 KiB is far more than any text message needs; it exists so that a
         * peer declaring a 65535-fragment message cannot make us hold 3 MB for it,
         * and so [DEFAULT_CAPACITY] messages have a bounded worst case.
         */
        const val DEFAULT_MAX_MESSAGE_BYTES: Int = 64 * 1024

        private const val INITIAL_BUCKETS = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
