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
     * Connected endpoints become peer rows, and a newly non-empty mesh flushes
     * anything waiting.
     */
    private fun collectPeers() = scope.launch {
        var wasEmpty = true
        transport.connectedPeers.collect { endpoints ->
            endpoints.forEach { endpointId ->
                transport.peerIdFor(endpointId)?.let { peers.recordSeen(it.toHexId()) }
            }

            // Edge-triggered: only on the transition into having peers, so a
            // peer count going 1 -> 2 does not re-send everything again.
            if (wasEmpty && endpoints.isNotEmpty()) flushPending()
            wasEmpty = endpoints.isEmpty()
        }
    }

    /**
     * Re-sends messages that were composed with nobody in range.
     *
     * Deliberately minimal: no expiry, no cap, no eviction. That is
     * `ForwardQueue`'s job and it does not exist yet. Without even this much, a
     * message typed before two phones link would sit PENDING forever and the
     * mesh would look broken to anyone testing it.
     */
    private suspend fun flushPending() {
        val pending = messages.pendingOutgoing()
        if (pending.isEmpty()) return

        Log.i(TAG, "flushing ${pending.size} pending message(s)")
        for (row in pending) {
            val recipient = recipientOf(row.conversationId)
            if (transmit(row.id, row.body, recipient, row.timestamp)) {
                messages.markSent(row.id)
            }
        }
    }

    private fun conversationIdFor(recipientHexId: String?): String =
        recipientHexId?.let(::directConversationId) ?: BROADCAST_CONVERSATION_ID

    /** Inverse of [conversationIdFor]: null means the broadcast thread. */
    private fun recipientOf(conversationId: String): String? =
        conversationId.removePrefix(DIRECT_PREFIX).takeIf { conversationId.startsWith(DIRECT_PREFIX) }

    private companion object {
        const val TAG = "MeshCoordinator"

        /** Must match the shape `directConversationId` produces. */
        const val DIRECT_PREFIX = "direct:"
    }
}
