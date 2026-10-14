package et.alpha.nchat.mesh.router

import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketCodec
import et.alpha.nchat.mesh.protocol.PacketDecodeException
import et.alpha.nchat.mesh.protocol.PacketSigner
import et.alpha.nchat.mesh.protocol.PacketType
import et.alpha.nchat.mesh.protocol.PacketVerifier
import et.alpha.nchat.mesh.protocol.Verification
import et.alpha.nchat.mesh.protocol.signing
import et.alpha.nchat.mesh.transport.ConnectionEvent
import et.alpha.nchat.mesh.transport.InboundPayload
import et.alpha.nchat.mesh.transport.Transport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Flooding with deduplication — no routing tables, no topology knowledge.
 *
 * The whole of the routing policy is the five rules in [onInbound], in order.
 * This class talks to the link layer only through the [Transport] interface and
 * touches no Android framework types, so the entire mesh can be exercised on the
 * JVM against a fake multi-node topology.
 *
 * @param localId this device's 8-byte id (first 8 bytes of SHA-256 of its public key).
 * @param jitter how long to wait before relaying, per routing rule 5. Injected so
 *   tests can pin it; production randomises across [MIN_JITTER_MILLIS]..[MAX_JITTER_MILLIS].
 */
class MeshRouter(
    private val transport: Transport,
    private val localId: ByteArray,
    private val scope: CoroutineScope,
    private val seenCache: SeenCache = SeenCache(),
    private val forwardQueue: ForwardQueue? = null,
    private val signer: PacketSigner? = null,
    private val verifier: PacketVerifier = PacketVerifier.AcceptAll,
    private val jitter: () -> Long = {
        Random.nextLong(MIN_JITTER_MILLIS, MAX_JITTER_MILLIS + 1)
    },
) {

    init {
        require(localId.size == Packet.PEER_ID_SIZE) {
            "localId must be ${Packet.PEER_ID_SIZE} bytes, was ${localId.size}"
        }
    }

    /**
     * Buffered rather than broadcast, because delivery must not be lossy: a
     * `MutableSharedFlow` with no subscriber attached discards what it is handed,
     * and a discarded packet here is a chat message the user never receives.
     * An unbounded channel holds messages that arrive before the consumer is
     * ready, which is exactly what happens during service startup.
     *
     * The corollary is that this has a *single* consumer — the repository that
     * persists messages. The UI observes Room, not the router.
     */
    private val deliveries = Channel<Packet>(Channel.UNLIMITED)

    /**
     * Packets addressed to this device (or broadcast to everyone), in arrival order.
     *
     * Payloads are still ciphertext. Decryption belongs to `mesh/crypto`'s
     * SessionCipher, which does not exist yet; the router deliberately stops at
     * the routing boundary rather than growing a crypto stub.
     */
    val delivered: Flow<Packet> = deliveries.receiveAsFlow()

    /**
     * Deliberately the opposite contract to [deliveries], field for field.
     *
     * Delivery is a buffered channel because losing one costs the user a chat
     * message. Losing one of these costs a frame of a pulse animation, so it
     * replays nothing, buffers almost nothing, and drops the oldest event rather
     * than slowing a relay down to keep it. Making this lossless would put an
     * unbounded queue behind a UI that is usually not even on screen.
     */
    private val _relayActivity = MutableSharedFlow<RelayEvent>(
        replay = 0,
        extraBufferCapacity = RELAY_ACTIVITY_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Traffic crossing this device's own links. Lossy on purpose — see the backing field. */
    val relayActivity: SharedFlow<RelayEvent> = _relayActivity.asSharedFlow()

    /** Directly reachable peers, as reported by the transport. */
    private val peers = linkedSetOf<String>()

    private val _droppedMalformed = AtomicLong()
    private val _droppedUnverified = AtomicLong()
    private val _failedForwards = AtomicLong()

    /** Packets discarded because they did not parse. Useful as a health signal. */
    val droppedMalformed: Long get() = _droppedMalformed.get()

    /**
     * Packets discarded because they were signed by the wrong key.
     *
     * Counts only outright failures — a packet from a sender whose key we do not
     * hold is not counted here, because nothing was disproved about it.
     * A number above zero means someone is forging sender ids.
     */
    val droppedUnverified: Long get() = _droppedUnverified.get()

    /**
     * Relay attempts that failed because the peer had gone by the time we sent.
     *
     * Still counted now that [forwardQueue] recovers them, because a rising
     * count is a useful health signal even when nothing is lost: it means the
     * transport's view of who is reachable is running ahead of reality.
     */
    val failedForwards: Long get() = _failedForwards.get()

    private val started = AtomicBoolean(false)

    /**
     * Begins routing. Returns the supervising job so a foreground service can
     * cancel the whole router in one call.
     *
     * Guarded against a second call: a service restarted in place would otherwise
     * attach a duplicate set of collectors to the same transport.
     *
     * @throws IllegalStateException if called twice.
     */
    fun start(): Job {
        check(started.compareAndSet(false, true)) { "MeshRouter.start() called twice" }
        return scope.launch {
            launch { transport.connectionEvents.collect(::onConnectionEvent) }
            launch { transport.incoming.collect(::onInbound) }
        }
    }

    /**
     * Injects a locally-composed packet into the mesh.
     *
     * The id is marked seen first, so that when the packet floods back from a
     * neighbour it is dropped as a duplicate rather than delivered to our own UI
     * or relayed a second time.
     *
     * @return true if at least one peer took the packet. False means the mesh is
     *   currently empty — the packet is not lost, it simply never left, and the
     *   caller decides whether to retry.
     */
    suspend fun originate(packet: Packet): Boolean {
        // Signed here rather than where the packet is built, because this is the
        // one point every outgoing packet passes through. A packet the queue
        // replays is already signed and is re-sent verbatim, so nothing is
        // signed twice and a replay stays byte-identical to the original.
        val signed = signer?.signing(packet) ?: packet

        seenCache.markSeen(signed.messageId)
        if (transport.broadcast(PacketCodec.encode(signed)) > 0) return true

        // Nobody was in range. Hold it rather than lose it — the queue is what
        // makes composing a message with the mesh empty a delay instead of a
        // silent failure.
        enqueueForLater(signed)
        return false
    }

    /**
     * Hands a peer everything the queue was holding for it, plus anything queued
     * for broadcast.
     *
     * Called when a peer connects — routing rule "on each new peer connection,
     * offer queued messages addressed to that peer". The two ids are different
     * namespaces and both are needed: [recipientId] is the 8-byte protocol id
     * the queue is keyed by, [endpointId] is the link-layer address to send to.
     * Resolving one to the other is `NearbyTransport`'s job, not the router's,
     * which is why the caller passes both.
     *
     * @return the packets the peer actually took. The caller needs the identity
     *   of each one, not just a count, to advance the matching database rows.
     */
    suspend fun offerQueued(recipientId: ByteArray, endpointId: String): List<Packet> {
        val queue = forwardQueue ?: return emptyList()

        val entries = queue.takeFor(recipientId)
        if (entries.isEmpty()) return emptyList()

        val delivered = ArrayList<Packet>()
        val undelivered = ArrayList<QueuedPacket>()
        for (entry in entries) {
            // Sent as stored, with whatever ttl it was queued at. Re-decrementing
            // here would charge the packet a hop it never travelled.
            if (transport.send(endpointId, PacketCodec.encode(entry.packet))) {
                delivered.add(entry.packet)
            } else {
                undelivered.add(entry)
            }
        }

        queue.restore(undelivered)
        return delivered
    }

    /**
     * Queues a packet that found no route.
     *
     * Only MESSAGE and ACK packets are worth holding. An ANNOUNCE or a
     * KEY_EXCHANGE is only meaningful in the moment it is sent, and replaying a
     * stale one hours later says something untrue about the mesh's current shape.
     *
     * An ACK is held for the same reason its message was: the sender it answers
     * may have walked out of range, and a receipt that arrives hours late still
     * turns "sent" into the truth, "delivered".
     */
    private suspend fun enqueueForLater(packet: Packet) {
        if (packet.type !in STORE_AND_FORWARD_TYPES) return
        forwardQueue?.enqueue(packet)
    }

    private fun onConnectionEvent(event: ConnectionEvent) {
        synchronized(peers) {
            when (event) {
                is ConnectionEvent.Connected -> peers.add(event.peerId)
                is ConnectionEvent.Disconnected -> peers.remove(event.peerId)
            }
        }
    }

    /** The five routing rules from CLAUDE.md, in order. */
    private fun onInbound(inbound: InboundPayload) {
        // A peer that sends us garbage must not take the router down with it, so
        // a parse failure is counted and dropped rather than thrown at the collector.
        val packet = try {
            PacketCodec.decode(inbound.bytes)
        } catch (e: PacketDecodeException) {
            _droppedMalformed.incrementAndGet()
            return
        }

        // Ahead of both dedup and verification, because the claim being made is
        // only that bytes crossed this link — which a duplicate or a forgery does
        // as much as a message the user ends up reading. Waiting until a packet
        // has earned delivery would leave the busiest links, the ones carrying
        // other people's traffic, looking idle.
        _relayActivity.tryEmit(RelayEvent(inbound.fromPeer, RelayEvent.Direction.IN))

        // Verified before the dedup cache is touched, deliberately. markSeen is
        // what claims a slot in a bounded LRU, so verifying afterwards would let
        // an attacker evict genuine entries with forged ids and get real
        // messages re-relayed or dropped.
        val verification = verifier.verify(packet)
        if (verification == Verification.INVALID) {
            _droppedUnverified.incrementAndGet()
            return
        }

        // Rules 1 and 2: drop if already seen, otherwise remember it. One atomic
        // step — see SeenCache.markSeen.
        if (!seenCache.markSeen(packet.messageId)) return

        // Rule 3: deliver anything addressed to us or to everyone — but only if
        // we could actually check who sent it.
        //
        // An UNKNOWN_SENDER packet still relays. A relay has usually never met
        // the device that originated what it is carrying, so refusing to forward
        // what it cannot verify would stop multi-hop delivery working at all.
        // The cost is that an unknown sender can still occupy dedup slots; that
        // is inherent to relaying for strangers and bounded by the LRU.
        val isForUs = packet.recipientId.contentEquals(localId)
        if (verification == Verification.VALID && (isForUs || packet.isBroadcast)) {
            deliveries.trySend(packet)
            // A unicast that has arrived is finished travelling. A broadcast is
            // for us *and* for everyone past us, so it falls through to relaying.
            if (isForUs) return
        } else if (isForUs) {
            // Addressed to us but unverifiable: nothing to relay onward, and
            // nothing we are willing to show the user.
            return
        }

        // Rule 4: spend a hop. Null means the budget ran out and this is the last stop.
        val relayed = packet.decrementTtl() ?: return

        // Rule 5: jitter before relaying, so neighbours who received the same
        // packet in the same instant do not all transmit on top of each other.
        // Each relay gets its own coroutine; otherwise one packet's jitter would
        // stall every packet queued behind it in the collector.
        scope.launch {
            delay(jitter())
            forward(relayed, arrivedFrom = inbound.fromPeer)
        }
    }

    /**
     * Sends [packet] to every connected peer except the one it came from.
     *
     * This walks the peer set rather than calling [Transport.broadcast] because
     * broadcast has no way to express the exclusion, and echoing a packet back
     * down the link it arrived on is the cheapest loop in the mesh.
     */
    private suspend fun forward(packet: Packet, arrivedFrom: String) {
        val targets = synchronized(peers) { peers.filterTo(ArrayList()) { it != arrivedFrom } }
        if (targets.isEmpty()) {
            // A dead end: we are the only device this packet reached. Holding it
            // is what turns a chain of phones that are never all in range at once
            // into a delivery path.
            enqueueForLater(packet)
            return
        }

        // Re-encoding with a decremented ttl leaves the signature valid: the
        // signed region excludes the ttl byte precisely so a relay can spend a hop
        // without invalidating end-to-end authentication. See PacketCodec.signedBytes.
        val bytes = PacketCodec.encode(packet)
        var delivered = 0
        for (peer in targets) {
            if (transport.send(peer, bytes)) {
                delivered++
                // Only on a send the transport accepted. A failed forward means
                // the peer was already gone, and nothing crossed that link.
                _relayActivity.tryEmit(RelayEvent(peer, RelayEvent.Direction.OUT))
            } else {
                _failedForwards.incrementAndGet()
            }
        }

        // Queued only when every target failed. One peer taking it is enough for
        // flooding to carry it onward, and queuing then would duplicate a packet
        // that is already travelling.
        if (delivered == 0) enqueueForLater(packet)
    }

    companion object {
        /** Packet types the forward queue holds when nobody is in range. */
        private val STORE_AND_FORWARD_TYPES = setOf(PacketType.MESSAGE, PacketType.ACK)

        /** Rule 5's jitter window, in milliseconds. */
        const val MIN_JITTER_MILLIS: Long = 50
        const val MAX_JITTER_MILLIS: Long = 500

        /**
         * Room for a short burst — one packet arriving and being fanned out to a
         * handful of neighbours — and no more. A consumer that cannot keep up
         * with a pulse animation is one that should be dropping frames.
         */
        private const val RELAY_ACTIVITY_BUFFER: Int = 16
    }
}

/**
 * A packet crossing one of this device's links, in one direction.
 *
 * [endpointId] is a *link-layer* id, the same namespace as
 * [InboundPayload.fromPeer] and emphatically not the packet's 8-byte protocol
 * `senderId` — resolving one to the other belongs to the transport. Nothing here
 * says anything about traffic elsewhere in the mesh: a relay two hops away
 * carrying a conversation neither party can hear is invisible from this device,
 * and this flow must never be dressed up as a view of the whole network.
 */
data class RelayEvent(val endpointId: String, val direction: Direction) {

    /** Which way the bytes went across the link. */
    enum class Direction {

        /** A peer handed us a packet. */
        IN,

        /** We handed a peer a packet to carry onward. */
        OUT,
    }
}
