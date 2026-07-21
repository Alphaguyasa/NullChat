package et.alpha.nchat.mesh.router

import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketCodec
import et.alpha.nchat.mesh.protocol.PacketType
import et.alpha.nchat.mesh.transport.ConnectionEvent
import et.alpha.nchat.mesh.transport.InboundPayload
import et.alpha.nchat.mesh.transport.Transport
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle

/**
 * An in-memory mesh: a set of named nodes, an arbitrary adjacency graph between
 * them, and a full log of everything that crossed a link.
 *
 * Nothing here touches Bluetooth or Wi-Fi, so multi-hop routing is verified on
 * the JVM with zero hardware. Physical devices are still required to prove the
 * real transport works — this proves the *routing* works.
 */
class MeshNetwork {

    private val transports = linkedMapOf<String, FakeTransport>()
    private val adjacency = linkedMapOf<String, MutableSet<String>>()

    private val _transmissions = mutableListOf<Transmission>()

    /** Every packet handed from one node to another, in order. */
    val transmissions: List<Transmission> get() = synchronized(_transmissions) { _transmissions.toList() }

    fun register(name: String): FakeTransport = transports.getOrPut(name) {
        adjacency[name] = linkedSetOf()
        FakeTransport(name, this)
    }

    /** Connects two nodes, announcing the link to both, as a real transport would. */
    fun link(a: String, b: String) {
        require(a != b) { "cannot link $a to itself" }
        register(a)
        register(b)
        adjacency.getValue(a).add(b)
        adjacency.getValue(b).add(a)
        transports.getValue(a).announce(ConnectionEvent.Connected(b))
        transports.getValue(b).announce(ConnectionEvent.Connected(a))
    }

    /** Severs a link, for partition tests. */
    fun unlink(a: String, b: String) {
        adjacency.getValue(a).remove(b)
        adjacency.getValue(b).remove(a)
        transports.getValue(a).announce(ConnectionEvent.Disconnected(b))
        transports.getValue(b).announce(ConnectionEvent.Disconnected(a))
    }

    fun neighbours(name: String): Set<String> = adjacency[name].orEmpty()

    /** Transmissions grouped as `from -> to` pairs, which is what most assertions want. */
    fun hops(): List<Pair<String, String>> = transmissions.map { it.from to it.to }

    /**
     * Moves bytes across one link.
     *
     * A send to a non-neighbour returns false rather than quietly evaporating: a
     * miswired topology should fail the test loudly, not look like packet loss.
     */
    internal fun transmit(from: String, to: String, bytes: ByteArray): Boolean {
        if (to !in neighbours(from)) return false
        // Decoded eagerly so assertions can read ttl and ids straight off the log.
        val packet = PacketCodec.decode(bytes)
        synchronized(_transmissions) { _transmissions.add(Transmission(from, to, packet)) }
        transports.getValue(to).deliver(from, bytes)
        return true
    }
}

/** One packet crossing one link. */
data class Transmission(val from: String, val to: String, val packet: Packet)

/**
 * A [Transport] backed by a [MeshNetwork] rather than a radio.
 *
 * Inbound payloads and connection events land in unbounded channels, so a value
 * emitted before the router has attached its collector is buffered rather than
 * dropped — the trap a bare SharedFlow would set here.
 */
class FakeTransport(
    val name: String,
    private val network: MeshNetwork,
) : Transport {

    private val inbox = Channel<InboundPayload>(Channel.UNLIMITED)
    private val events = Channel<ConnectionEvent>(Channel.UNLIMITED)

    override val incoming: Flow<InboundPayload> = inbox.receiveAsFlow()
    override val connectionEvents: Flow<ConnectionEvent> = events.receiveAsFlow()

    var advertising: Boolean = false
        private set
    var discovering: Boolean = false
        private set

    override suspend fun startAdvertising() {
        advertising = true
    }

    override suspend fun startDiscovery() {
        discovering = true
    }

    override suspend fun send(peerId: String, bytes: ByteArray): Boolean =
        network.transmit(from = name, to = peerId, bytes = bytes)

    override suspend fun broadcast(bytes: ByteArray) {
        for (peer in network.neighbours(name)) {
            network.transmit(from = name, to = peer, bytes = bytes)
        }
    }

    internal fun deliver(from: String, bytes: ByteArray) {
        inbox.trySend(InboundPayload(from, bytes))
    }

    internal fun announce(event: ConnectionEvent) {
        events.trySend(event)
    }
}

/**
 * A node under test: a [FakeTransport], the [MeshRouter] driving it, and a
 * running record of what that router delivered upward.
 */
class TestNode(
    val name: String,
    val id: ByteArray,
    val transport: FakeTransport,
    val router: MeshRouter,
    val delivered: List<Packet>,
)

/**
 * Builds node graphs for router tests.
 *
 * Call [settle] after wiring a topology and after each send: routers attach
 * their collectors, learn their peers, and serve rule 5's jitter delay on the
 * scheduler's virtual clock, so nothing moves until the scheduler is pumped.
 */
class MeshTestbed(
    private val testScope: TestScope,
    private val jitterMillis: Long = 100L,
) {

    /**
     * Routers run in a scope detached from the test's job — its own [Job], on the
     * test scheduler — rather than in `backgroundScope`.
     *
     * As of coroutines 1.9.0, `advanceUntilIdle()` does not run work launched in
     * `backgroundScope`: a router parked there never collects a single packet and
     * every multi-hop assertion fails at hop one. A detached scope is ordinary
     * scheduler work, so it advances normally, and because nothing awaits its job
     * `runTest` still returns instead of hanging on the infinite collectors.
     */
    private val scope = CoroutineScope(StandardTestDispatcher(testScope.testScheduler) + Job())

    val network = MeshNetwork()
    private val nodes = linkedMapOf<String, TestNode>()

    /** Runs the scheduler until the mesh is quiet. */
    fun settle() {
        testScope.testScheduler.advanceUntilIdle()
    }

    /** Stops every router. Called for you by [meshTest]. */
    fun shutdown() {
        scope.cancel()
    }

    val transmissions: List<Transmission> get() = network.transmissions

    fun node(name: String): TestNode = nodes.getOrPut(name) {
        val transport = network.register(name)
        val id = peerId(name)
        val router = MeshRouter(
            transport = transport,
            localId = id,
            scope = scope,
            jitter = { jitterMillis },
        )
        val delivered = mutableListOf<Packet>()
        router.start()
        scope.launch { router.delivered.collect { delivered.add(it) } }
        TestNode(name, id, transport, router, delivered)
    }

    fun link(a: String, b: String) {
        node(a)
        node(b)
        network.link(a, b)
    }

    /** Wires the named nodes into a line: A—B—C—… */
    fun chain(vararg names: String): List<TestNode> {
        names.toList().zipWithNext { a, b -> link(a, b) }
        return names.map { node(it) }
    }

    /** Wires the named nodes into a full mesh, every node to every other. */
    fun fullyConnect(vararg names: String): List<TestNode> {
        for (i in names.indices) {
            for (j in i + 1 until names.size) link(names[i], names[j])
        }
        return names.map { node(it) }
    }

    /** Packets this node sent to that node, whatever their contents. */
    fun hopsFrom(name: String): List<String> =
        transmissions.filter { it.from == name }.map { it.to }

    /** Every hop that crossed the mesh, as `from to to` pairs, in order. */
    fun hops(): List<Pair<String, String>> = network.hops()
}

/** Runs [body] against a fresh mesh, shutting its routers down afterwards. */
suspend fun TestScope.meshTest(jitterMillis: Long = 100L, body: suspend MeshTestbed.() -> Unit) {
    val testbed = MeshTestbed(this, jitterMillis)
    try {
        testbed.body()
    } finally {
        testbed.shutdown()
    }
}

/** A deterministic 8-byte peer id derived from a node's test name. */
fun peerId(name: String): ByteArray =
    ByteArray(Packet.PEER_ID_SIZE) { name[0].code.toByte() }

private val nextMessage = AtomicInteger()

/** A MESSAGE packet with a unique id and a placeholder signature. */
fun testPacket(
    from: ByteArray,
    to: ByteArray = Packet.BROADCAST_ID,
    ttl: Int = Packet.DEFAULT_TTL,
    payload: ByteArray = "ping".toByteArray(),
): Packet {
    val seq = nextMessage.getAndIncrement()
    // Counter in the leading bytes, not smeared across all sixteen: a byte-wrapping
    // id generator would hand out duplicates and quietly turn dedup on.
    val messageId = ByteArray(Packet.MESSAGE_ID_SIZE).also {
        it[0] = (seq ushr 24).toByte()
        it[1] = (seq ushr 16).toByte()
        it[2] = (seq ushr 8).toByte()
        it[3] = seq.toByte()
    }
    return Packet(
        type = PacketType.MESSAGE,
        messageId = messageId,
        senderId = from,
        recipientId = to,
        ttl = ttl,
        timestamp = 1_700_000_000_000L,
        payload = payload,
        // The router does not verify signatures — that lands with mesh/crypto —
        // so a well-formed placeholder is all these tests need.
        signature = ByteArray(Packet.SIGNATURE_SIZE) { 0x5A },
    )
}
