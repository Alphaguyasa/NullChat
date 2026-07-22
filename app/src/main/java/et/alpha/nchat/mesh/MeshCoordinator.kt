package et.alpha.nchat.mesh

import android.util.Log
import et.alpha.nchat.data.db.BROADCAST_CONVERSATION_ID
import et.alpha.nchat.data.db.directConversationId
import et.alpha.nchat.data.db.toHexId
import et.alpha.nchat.data.repo.MessageRepository
import et.alpha.nchat.data.repo.PeerRepository
import et.alpha.nchat.di.MeshScope
import et.alpha.nchat.mesh.crypto.LocalIdentity
import et.alpha.nchat.mesh.protocol.MessagePacket
import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketType
import et.alpha.nchat.mesh.router.MeshRouter
import et.alpha.nchat.mesh.transport.NearbyTransport
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What happened to a message the user tried to send. */
sealed interface SendResult {

    /** Stored and handed to at least one peer. */
    data object Sent : SendResult

    /**
     * Stored, but nobody was in range to take it.
     *
     * Not an error: the row stays PENDING and goes out when a peer appears.
     */
    data object Pending : SendResult

    /** Rejected before storage — currently only ever an over-length body. */
    data class Rejected(val reason: String) : SendResult
}

/**
 * The single seam between the mesh and the database.
 *
 * Everything crossing that boundary goes through here: outgoing text becomes a
 * packet, delivered packets become rows, and connected endpoints become peers.
 * Keeping it in one place is what stops the UI from reaching into the router and
 * the router from knowing that Room exists.
 */
@Singleton
class MeshCoordinator @Inject constructor(
    private val router: MeshRouter,
    private val transport: NearbyTransport,
    private val identity: LocalIdentity,
    private val messages: MessageRepository,
    private val peers: PeerRepository,
    @MeshScope private val scope: CoroutineScope,
) {

    private val started = AtomicBoolean(false)

    /** This device's own id, hex-encoded — the `senderId` on everything it sends. */
    val selfId: String by lazy { identity.peerId.toHexId() }

    /**
     * Starts routing and the collectors, at most once per process.
     *
     * Idempotent on purpose. The router itself throws if started twice, and
     * START_STICKY means the service can be recreated against these already-live
     * singletons — so the guard belongs here rather than at every call site.
     */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        Log.i(TAG, "starting mesh coordinator as ${Packet.tag(identity.peerId)}")

        router.start()
        collectIncoming()
        collectPeers()
    }

    /**
     * Sends [body] to [recipientHexId], or to everyone when it is null.
     *
     * The row is written **before** the packet goes anywhere. A crash between
     * the two leaves a PENDING message that the flush will retry; the reverse
     * order would lose the message with no trace it ever existed.
     */
    suspend fun send(body: String, recipientHexId: String?): SendResult {
        if (body.isBlank()) return SendResult.Rejected("empty message")
        if (!MessagePacket.fits(body)) {
            return SendResult.Rejected(
                "message is ${MessagePacket.bodyByteCount(body)} bytes, " +
                    "over the ${MessagePacket.MAX_BODY_BYTES} byte limit",
            )
        }

        val conversationId = conversationIdFor(recipientHexId)
        val row = messages.recordOutgoing(
            conversationId = conversationId,
            selfId = selfId,
            body = body,
            peerId = recipientHexId,
        )

        return if (transmit(row.id, row.body, recipientHexId, row.timestamp)) {
            messages.markSent(row.id)
            SendResult.Sent
        } else {
            SendResult.Pending
        }
    }

    /**
     * Encodes and originates one message. Returns whether a peer took it.
     *
     * Shared by [send] and [flushPending] so a retried message is built exactly
     * the same way as a first attempt — same id, same timestamp, so the receiver
     * dedups it rather than showing it twice.
     */
    private suspend fun transmit(
        messageId: String,
        body: String,
        recipientHexId: String?,
        timestamp: Long,
    ): Boolean = runCatching {
        router.originate(
            MessagePacket.build(
                messageId = messageId,
                senderId = identity.peerId,
                recipientHexId = recipientHexId,
                body = body,
                timestamp = timestamp,
            ),
        )
    }.onFailure {
        Log.w(TAG, "failed to originate message: ${it.message}")
    }.getOrDefault(false)

    /** Delivered packets become rows. */
    private fun collectIncoming() = scope.launch {
        router.delivered.collect { packet ->
            if (packet.type != PacketType.MESSAGE) return@collect

            val senderHex = packet.senderId.toHexId()
            // Our own broadcast flooding back to us. The router's seen-cache
            // catches this within a session; this catches it across a restart,
            // when the cache is empty but the database is not.
            if (senderHex == selfId) return@collect

            val conversationId =
                if (packet.isBroadcast) BROADCAST_CONVERSATION_ID else directConversationId(senderHex)

            peers.recordSeen(senderHex)
            val isNew = messages.recordIncoming(
                messageId = packet.messageId.toHexId(),
                conversationId = conversationId,
                senderId = senderHex,
                body = MessagePacket.bodyOf(packet),
                timestamp = packet.timestamp,
                peerId = senderHex,
            )
            // Flooding delivers the same message by several routes; a duplicate
            // here is routine, not a fault.
            Log.d(TAG, "message from ${Packet.tag(packet.senderId)} new=$isNew")
        }
    }

    /**
     * Connected endpoints become peer rows, and each newly connected peer is
     * offered whatever the forward queue was holding for it.
     *
     * Per-peer rather than the old edge-triggered "mesh became non-empty" flush:
     * the queue is keyed by recipient, so the useful moment is when *that*
     * recipient appears, not when any peer at all does.
     */
    private fun collectPeers() = scope.launch {
        val flushed = mutableSetOf<String>()
        transport.connectedPeers.collect { endpoints ->
            endpoints.forEach { endpointId ->
                val peerId = transport.peerIdFor(endpointId) ?: return@forEach
                peers.recordSeen(peerId.toHexId())

                // Only on the transition into being connected. Without this a
                // peer count going 1 -> 2 would re-offer to the peer that was
                // already there, and every emission would re-run the flush.
                if (flushed.add(endpointId)) offerQueued(peerId, endpointId)
            }

            // Endpoints that went away become eligible again, so a peer that
            // reconnects later gets a fresh offer.
            flushed.retainAll(endpoints)
        }
    }

    /**
     * Hands a freshly connected peer its queued packets and advances the rows
     * the user is watching.
     *
     * The queue holds packets; the `messages` table holds what the UI renders.
     * A packet leaving the queue only moves a row to SENT when it is one of
     * ours — relayed traffic has no local row, and `advanceDeliveryState` leaves
     * anything it cannot find alone.
     */
    private suspend fun offerQueued(peerId: ByteArray, endpointId: String) {
        val delivered = runCatching { router.offerQueued(peerId, endpointId) }
            .onFailure { Log.w(TAG, "flush to ${Packet.tag(peerId)} failed: ${it.message}") }
            .getOrDefault(emptyList())
        if (delivered.isEmpty()) return

        Log.i(TAG, "flushed ${delivered.size} queued packet(s) to ${Packet.tag(peerId)}")
        // Exactly the packets that went out, not every pending row: the queue
        // also carries traffic relayed for other devices, which has no row here.
        for (packet in delivered) {
            messages.markSent(packet.messageId.toHexId())
        }
    }

    private fun conversationIdFor(recipientHexId: String?): String =
        recipientHexId?.let(::directConversationId) ?: BROADCAST_CONVERSATION_ID

    private companion object {
        const val TAG = "MeshCoordinator"
    }
}
