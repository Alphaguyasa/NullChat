package et.alpha.nchat.mesh.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A link layer the mesh can speak over: Nearby Connections today, BLE later.
 *
 * Everything above this interface — [et.alpha.nchat.mesh.router.MeshRouter] in
 * particular — works only in terms of opaque peer ids and byte arrays, so no
 * transport-specific type ever leaks into the routing layer.
 *
 * Implementations are responsible for connection management; the router only
 * observes the results through [connectionEvents].
 */
interface Transport {

    /** Peers appearing and disappearing. Hot; missed events are missed. */
    val connectionEvents: Flow<ConnectionEvent>

    /** Payloads handed to us by a connected peer, in arrival order. */
    val incoming: Flow<InboundPayload>

    /**
     * Currently reachable peers, as state rather than events.
     *
     * [connectionEvents] is a single-consumer stream the router owns; anything
     * that needs to render or count who is reachable — the notification, the peer
     * list — observes this instead.
     */
    val connectedPeers: StateFlow<Set<String>>

    /** Makes this device discoverable to peers looking for the mesh. */
    suspend fun startAdvertising()

    /** Starts looking for peers advertising the mesh. */
    suspend fun startDiscovery()

    /**
     * Stops looking for peers, leaving advertising and existing connections up.
     *
     * Separate from [stop] because duty cycling pauses scanning — by far the most
     * expensive part of the radio's work — while staying discoverable so peers
     * scanning on their own schedule can still find us.
     */
    suspend fun stopDiscovery()

    /**
     * Sends [bytes] to a single connected peer.
     *
     * @return false if the peer is not currently reachable. Callers use this to
     *   decide whether the packet belongs in the forward queue instead.
     */
    suspend fun send(peerId: String, bytes: ByteArray): Boolean

    /**
     * Sends [bytes] to every currently connected peer.
     *
     * @return how many peers the payload was actually handed to. Zero means the
     *   mesh is empty right now, which is not an error — it is the caller's cue
     *   to leave the message pending and try again when someone appears.
     */
    suspend fun broadcast(bytes: ByteArray): Int

    /**
     * Stops advertising and discovery and drops all connections.
     *
     * Must be idempotent: the foreground service calls it on teardown regardless
     * of how far startup actually got.
     */
    suspend fun stop()
}

/**
 * A payload as it arrived, tagged with the peer that handed it over.
 *
 * [fromPeer] is a *link-layer* id (a Nearby endpoint id, say) and is emphatically
 * not the packet's 8-byte protocol `senderId`: on any hop past the first, the
 * originating sender is several nodes away from whoever passed us the bytes.
 * Routing rule 4 — rebroadcast to everyone except the peer it arrived from —
 * depends on this distinction.
 */
class InboundPayload(val fromPeer: String, val bytes: ByteArray) {

    override fun toString(): String = "InboundPayload(from=$fromPeer, ${bytes.size}B)"

    // Content equality, so tests and buffers behave. ByteArray defaults to
    // identity comparison, which silently makes every payload distinct.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InboundPayload) return false
        return fromPeer == other.fromPeer && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * fromPeer.hashCode() + bytes.contentHashCode()
}

/** Changes to the set of peers this device can currently reach directly. */
sealed interface ConnectionEvent {

    val peerId: String

    /** A peer is connected and can be sent to. */
    data class Connected(override val peerId: String) : ConnectionEvent

    /** A peer went away; sends to it will fail until it returns. */
    data class Disconnected(override val peerId: String) : ConnectionEvent
}
