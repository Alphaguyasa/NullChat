package et.alpha.nchat.mesh.transport

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Task
import et.alpha.nchat.data.db.hexIdToBytes
import et.alpha.nchat.data.db.toHexId
import et.alpha.nchat.mesh.protocol.Packet
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The Nearby Connections implementation of [Transport], running `P2P_CLUSTER`.
 *
 * **This is the only file in the app permitted to import Nearby Connections.**
 * Everything above it — the router in particular — sees the [Transport]
 * interface and opaque `String` peer ids, so swapping in `BleTransport` later
 * touches nothing but dependency injection.
 *
 * `P2P_CLUSTER` is the right strategy here because it is the only one allowing
 * M-to-N connections: each device advertises and discovers simultaneously and
 * holds several links at once, which is what makes the mesh a mesh rather than a
 * set of star topologies. The cost is lower per-link bandwidth than `P2P_STAR`
 * or `P2P_POINT_TO_POINT`, which is irrelevant for ~180-byte packets.
 *
 * ### Two id spaces
 * Nearby hands out its own short, ephemeral **endpoint ids** — regenerated per
 * session and meaningless across restarts. The protocol has its own stable
 * 8-byte **peer ids** derived from a device's public key. The [Transport]
 * interface speaks endpoint ids; this class maintains the mapping between the
 * two (see [peerIdFor] / [endpointIdFor]) by advertising the local peer id as
 * the endpoint name.
 *
 * ### Threading
 * Nearby delivers callbacks on the main looper. Every one of them does nothing
 * but update a lock-guarded map and `trySend` into an unbounded channel, so no
 * callback can block the main thread or lose an event.
 *
 * Requires the Bluetooth, location (SDK < 31), and `NEARBY_WIFI_DEVICES`
 * (SDK 33+) permissions to already be granted; Nearby fails the start Task if
 * they are not. Cannot be exercised on an emulator — see CLAUDE.md.
 *
 * @param localPeerId this device's 8-byte protocol id, advertised to peers.
 */
class NearbyTransport(
    context: Context,
    private val localPeerId: ByteArray,
    private val serviceId: String = SERVICE_ID,
) : Transport {

    init {
        require(localPeerId.size == Packet.PEER_ID_SIZE) {
            "localPeerId must be ${Packet.PEER_ID_SIZE} bytes, was ${localPeerId.size}"
        }
    }

    // Application context: this outlives any Activity, and holding one would leak it.
    private val client = Nearby.getConnectionsClient(context.applicationContext)

    /**
     * Unbounded and single-consumer, deliberately.
     *
     * The consumer is the router, and it attaches after the transport is
     * constructed — a `SharedFlow` would silently discard anything arriving in
     * that window, and a discarded packet here is a lost message.
     */
    private val inbound = Channel<InboundPayload>(Channel.UNLIMITED)
    override val incoming: Flow<InboundPayload> = inbound.receiveAsFlow()

    private val events = Channel<ConnectionEvent>(Channel.UNLIMITED)
    override val connectionEvents: Flow<ConnectionEvent> = events.receiveAsFlow()

    /** Guards [peerIdsByEndpoint], [endpointsByPeerId] and [connectedEndpoints]. */
    private val lock = Any()

    /** endpoint id -> that peer's 8-byte protocol id. */
    private val peerIdsByEndpoint = mutableMapOf<String, ByteArray>()

    /** hex protocol id -> endpoint id. Hex-keyed because ByteArray hashes by identity. */
    private val endpointsByPeerId = mutableMapOf<String, String>()

    private val connectedEndpoints = mutableSetOf<String>()

    /**
     * Each connection's raw authentication token, the same at both ends of one
     * link and different on every other; see `LinkTokenProof`.
     */
    private val tokensByEndpoint = mutableMapOf<String, ByteArray>()

    private val _connectedPeers = MutableStateFlow<Set<String>>(emptySet())

    override val connectedPeers: StateFlow<Set<String>> = _connectedPeers.asStateFlow()

    // region Transport

    override suspend fun startAdvertising() {
        Log.i(TAG, "advertising as ${tag(localPeerId)}")
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising(localPeerId.toHexId(), serviceId, lifecycleCallback, options)
            .awaitUnit("startAdvertising")
    }

    override suspend fun startDiscovery() {
        Log.i(TAG, "discovering")
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(serviceId, discoveryCallback, options)
            .awaitUnit("startDiscovery")
    }

    override suspend fun stopDiscovery() {
        // Advertising is deliberately left running: if both ends duty-cycled
        // discovery *and* advertising, two devices scanning on independent
        // schedules would rarely overlap and could take minutes to meet.
        Log.d(TAG, "pausing discovery")
        client.stopDiscovery()
    }

    override suspend fun send(peerId: String, bytes: ByteArray): Boolean {
        // Checked before the call so a departed peer costs nothing, but the send
        // is still raced against disconnection and may fail below.
        val connected = synchronized(lock) { peerId in connectedEndpoints }
        if (!connected) {
            Log.d(TAG, "send to $peerId skipped: not connected")
            return false
        }
        return runCatching { client.sendPayload(peerId, Payload.fromBytes(bytes)).awaitUnit("send") }
            .onFailure { Log.w(TAG, "send to $peerId failed (${bytes.size}B): ${it.message}") }
            .isSuccess
    }

    override suspend fun broadcast(bytes: ByteArray): Int {
        val targets = synchronized(lock) { connectedEndpoints.toList() }
        if (targets.isEmpty()) return 0
        // One call rather than a loop: Nearby fans out a single Payload itself.
        // All-or-nothing, so a failure means nobody got it, not "some did".
        return runCatching {
            client.sendPayload(targets, Payload.fromBytes(bytes)).awaitUnit("broadcast")
            targets.size
        }.onFailure {
            Log.w(TAG, "broadcast to ${targets.size} peer(s) failed: ${it.message}")
        }.getOrDefault(0)
    }

    override suspend fun stop() {
        Log.i(TAG, "stopping")
        // Idempotent by contract, and each of these is individually safe to call
        // when the corresponding operation was never started.
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()

        // stopAllEndpoints does not invoke onDisconnected, so the router would
        // otherwise keep forwarding to endpoints that no longer exist.
        val dropped = synchronized(lock) {
            val previous = connectedEndpoints.toList()
            connectedEndpoints.clear()
            peerIdsByEndpoint.clear()
            endpointsByPeerId.clear()
            tokensByEndpoint.clear()
            _connectedPeers.value = emptySet()
            previous
        }
        dropped.forEach { events.trySend(ConnectionEvent.Disconnected(it)) }
    }

    // endregion

    // region id mapping

    /** The 8-byte protocol id behind a Nearby endpoint, or null if not yet known. */
    fun peerIdFor(endpointId: String): ByteArray? =
        synchronized(lock) { peerIdsByEndpoint[endpointId]?.copyOf() }

    /** The connection's raw authentication token, or null if Nearby gave none. */
    fun linkTokenFor(endpointId: String): ByteArray? =
        synchronized(lock) { tokensByEndpoint[endpointId]?.copyOf() }

    /** The endpoint currently carrying a protocol peer, or null if unreachable. */
    fun endpointIdFor(peerId: ByteArray): String? =
        synchronized(lock) { endpointsByPeerId[peerId.toHexId()] }

    // endregion

    // region Nearby callbacks

    private val discoveryCallback = object : EndpointDiscoveryCallback() {

        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peerId = parseAdvertisedPeerId(info.endpointName)
            if (peerId == null) {
                // Endpoint names come off the air from an unauthenticated source.
                // Anything that is not one of our peer ids is not ours to talk to.
                Log.w(TAG, "endpoint $endpointId found with unusable name, ignoring")
                return
            }
            if (peerId.contentEquals(localPeerId)) {
                Log.d(TAG, "endpoint $endpointId is this device, ignoring")
                return
            }

            Log.i(TAG, "found $endpointId (peer ${tag(peerId)})")
            remember(endpointId, peerId)
            // Kept for the link proof; never logged, since it is half of what
            // authenticates this connection.
            info.rawAuthenticationToken?.let { token ->
                synchronized(lock) { tokensByEndpoint[endpointId] = token.copyOf() }
            }

            // Both sides discover and both request; Nearby resolves the collision
            // and surfaces one connection, so a rejected duplicate is expected.
            client.requestConnection(localPeerId.toHexId(), endpointId, lifecycleCallback)
                .addOnFailureListener {
                    Log.d(TAG, "requestConnection to $endpointId declined: ${it.message}")
                }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.i(TAG, "lost $endpointId")
            // Only forget an endpoint we never established; a connected endpoint
            // going out of discovery range is normal and onDisconnected owns it.
            synchronized(lock) {
                if (endpointId !in connectedEndpoints) forget(endpointId)
            }
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val peerId = parseAdvertisedPeerId(info.endpointName)
            if (peerId == null) {
                Log.w(TAG, "rejecting $endpointId: unusable endpoint name")
                client.rejectConnection(endpointId)
                return
            }

            // Incoming connections arrive without discovery, so this is the only
            // place the mapping gets recorded for a peer that found us first.
            remember(endpointId, peerId)
            Log.i(
                TAG,
                "connection initiated with $endpointId (peer ${tag(peerId)}), " +
                    "incoming=${info.isIncomingConnection}",
            )

            // Auto-accept: Nearby's authentication token confirms only that both
            // ends see the same link, which is not an identity claim. Trust is
            // established above this layer — out-of-band QR verification plus the
            // per-packet Ed25519 signature, and the LINK_PROOF that signs this
            // token — so there is nothing for a user prompt here to confirm.
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener {
                    Log.w(TAG, "acceptConnection $endpointId failed: ${it.message}")
                    forget(endpointId)
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            when (val code = resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val peerId = synchronized(lock) {
                        connectedEndpoints.add(endpointId)
                        publishConnected()
                        peerIdsByEndpoint[endpointId]
                    }
                    Log.i(TAG, "connected $endpointId (peer ${tag(peerId)})")
                    events.trySend(ConnectionEvent.Connected(endpointId))
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.i(TAG, "connection to $endpointId rejected")
                    forget(endpointId)
                }

                else -> {
                    Log.w(TAG, "connection to $endpointId failed with status $code")
                    forget(endpointId)
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            val peerId = peerIdFor(endpointId)
            Log.i(TAG, "disconnected $endpointId (peer ${tag(peerId)})")
            forget(endpointId)
            events.trySend(ConnectionEvent.Disconnected(endpointId))
        }
    }

    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) {
                // The mesh only ever sends BYTES. A FILE or STREAM payload is
                // either a bug or a peer probing us; either way, not ours to read.
                Log.w(TAG, "ignoring non-BYTES payload from $endpointId")
                return
            }
            val bytes = payload.asBytes()
            if (bytes == null) {
                Log.w(TAG, "empty BYTES payload from $endpointId")
                return
            }
            // Size only — never the contents.
            Log.d(TAG, "received ${bytes.size}B from $endpointId")
            inbound.trySend(InboundPayload(fromPeer = endpointId, bytes = bytes))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // BYTES payloads are delivered whole to onPayloadReceived, so success
            // updates carry no information. Failures are worth knowing about.
            if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                Log.w(TAG, "payload transfer to/from $endpointId failed")
            }
        }
    }

    // endregion

    // region internals

    private fun remember(endpointId: String, peerId: ByteArray) = synchronized(lock) {
        peerIdsByEndpoint[endpointId] = peerId
        endpointsByPeerId[peerId.toHexId()] = endpointId
    }

    private fun forget(endpointId: String) = synchronized(lock) {
        tokensByEndpoint.remove(endpointId)
        peerIdsByEndpoint.remove(endpointId)?.let { peerId ->
            // Only clear the reverse entry if it still points at this endpoint: a
            // peer that reconnected under a new endpoint id must not be unmapped
            // by the teardown of its old one.
            val hex = peerId.toHexId()
            if (endpointsByPeerId[hex] == endpointId) endpointsByPeerId.remove(hex)
        }
        if (connectedEndpoints.remove(endpointId)) publishConnected()
    }

    /** Must be called while holding [lock]. */
    private fun publishConnected() {
        _connectedPeers.value = connectedEndpoints.toSet()
    }

    /** Awaits a Nearby [Task], translating failure into [TransportException]. */
    private suspend fun Task<Void>.awaitUnit(operation: String): Unit =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
            addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        TransportException("$operation failed: ${error.message}", error),
                    )
                }
            }
        }

    /** Four hex chars, the only form of a peer id allowed in a log. */
    private fun tag(peerId: ByteArray?): String = peerId?.let { Packet.tag(it) } ?: "????"

    // endregion

    companion object {
        private const val TAG = "NearbyTransport"

        /** Only endpoints advertising this exact service id are mesh nodes. */
        const val SERVICE_ID: String = "et.alpha.nchat.mesh"

        /** M-to-N connections; see the class doc for why not STAR or POINT_TO_POINT. */
        private val STRATEGY: Strategy = Strategy.P2P_CLUSTER
    }
}

/**
 * Decodes the peer id a remote device advertises as its endpoint name, or null
 * if it is not a well-formed one.
 *
 * Endpoint names arrive over the air from an unauthenticated source before any
 * connection exists, so this treats its input as hostile: wrong length, odd
 * length and non-hex characters all yield null rather than an exception that
 * would propagate out of a Nearby callback and onto the main thread.
 *
 * Top-level and free of Android and Nearby types on purpose — it is the one
 * piece of real logic in this file, and this keeps it unit-testable on the JVM.
 */
internal fun parseAdvertisedPeerId(endpointName: String?): ByteArray? {
    if (endpointName == null) return null
    if (endpointName.length != Packet.PEER_ID_SIZE * 2) return null
    return try {
        endpointName.hexIdToBytes()
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** A transport-layer operation that failed, with no Nearby types in its surface. */
class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
