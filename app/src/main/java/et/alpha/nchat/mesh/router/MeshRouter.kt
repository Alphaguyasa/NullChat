package et.alpha.nchat.mesh.router

import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketCodec
import et.alpha.nchat.mesh.protocol.PacketDecodeException
import et.alpha.nchat.mesh.transport.ConnectionEvent
import et.alpha.nchat.mesh.transport.InboundPayload
import et.alpha.nchat.mesh.transport.Transport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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

    /** Directly reachable peers, as reported by the transport. */
    private val peers = linkedSetOf<String>()

    private val _droppedMalformed = AtomicLong()
    private val _failedForwards = AtomicLong()

    /** Packets discarded because they did not parse. Useful as a health signal. */
    val droppedMalformed: Long get() = _droppedMalformed.get()

    /**
     * Relay attempts that failed because the peer had gone by the time we sent.
     *
     * Nothing recovers these yet — that is `ForwardQueue`'s job, and it is not
     * built. Counting them keeps the gap visible instead of silent.
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
     */
    suspend fun originate(packet: Packet) {
        seenCache.markSeen(packet.messageId)
        transport.broadcast(PacketCodec.encode(packet))
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

        // NOTE: the signature is not verified here. Verification needs the sender's
        // public key from mesh/crypto's KeyManager, which is not built yet. It
        // belongs at exactly this point, before the packet is trusted enough to
        // occupy a dedup slot.

        // Rules 1 and 2: drop if already seen, otherwise remember it. One atomic
        // step — see SeenCache.markSeen.
        if (!seenCache.markSeen(packet.messageId)) return

        // Rule 3: deliver anything addressed to us or to everyone.
        val isForUs = packet.recipientId.contentEquals(localId)
        if (isForUs || packet.isBroadcast) {
            deliveries.trySend(packet)
            // A unicast that has arrived is finished travelling. A broadcast is
            // for us *and* for everyone past us, so it falls through to relaying.
            if (isForUs) return
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
        if (targets.isEmpty()) return

        // Re-encoding with a decremented ttl leaves the signature valid: the
        // signed region excludes the ttl byte precisely so a relay can spend a hop
        // without invalidating end-to-end authentication. See PacketCodec.signedBytes.
        val bytes = PacketCodec.encode(packet)
        for (peer in targets) {
            if (!transport.send(peer, bytes)) _failedForwards.incrementAndGet()
        }
    }

    companion object {
        /** Rule 5's jitter window, in milliseconds. */
        const val MIN_JITTER_MILLIS: Long = 50
        const val MAX_JITTER_MILLIS: Long = 500
    }
}
