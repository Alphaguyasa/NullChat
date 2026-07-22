package et.alpha.nchat.mesh

import android.util.Log
import et.alpha.nchat.data.db.BROADCAST_CONVERSATION_ID
import et.alpha.nchat.data.db.directConversationId
import et.alpha.nchat.data.db.hexIdToBytes
import et.alpha.nchat.data.db.newMessageId
import et.alpha.nchat.data.db.toHexId
import et.alpha.nchat.data.repo.MessageRepository
import et.alpha.nchat.data.repo.PeerRepository
import et.alpha.nchat.di.MeshScope
import et.alpha.nchat.mesh.crypto.LocalIdentity
import et.alpha.nchat.mesh.crypto.PublicKeyDirectory
import et.alpha.nchat.mesh.crypto.SessionCipher
import et.alpha.nchat.mesh.protocol.Announcement
import et.alpha.nchat.mesh.protocol.MessagePacket
import et.alpha.nchat.mesh.protocol.announcementPacket
import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketType
import et.alpha.nchat.mesh.router.MeshRouter
import et.alpha.nchat.mesh.transport.NearbyTransport
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
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
    private val keyDirectory: PublicKeyDirectory,
    private val cipher: SessionCipher,
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

        warmKeyDirectory()
        router.start()
        collectIncoming()
        collectPeers()
    }

    /**
     * Loads known public keys into the directory the verifier reads.
     *
     * Room is the durable copy; the directory is the synchronous cache the
     * router can consult from a non-suspend collector. Our own key goes in too,
     * so a broadcast of ours flooding back verifies rather than looking like it
     * came from a stranger.
     *
     * Until ANNOUNCE distribution lands, `PeerEntity.publicKey` is null for
     * every peer, so in practice this loads exactly one key — ours. Everything
     * arriving from another device verifies as UNKNOWN_SENDER and is relayed
     * but not delivered.
     */
    private fun warmKeyDirectory() = scope.launch {
        keyDirectory.record(identity.announcement)
        // Stored peers carry only a signing key — the agreement key is not
        // persisted, so a peer loaded from the database can be verified but not
        // encrypted to until it announces again on the next connection.
        peers.observePeers().first().forEach { peer ->
            peer.publicKey?.let { keyDirectory.recordSigningKey(it) }
        }
        Log.i(TAG, "key directory warmed with ${keyDirectory.size} key(s)")
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
        val packet = MessagePacket.build(
            messageId = messageId,
            senderId = identity.peerId,
            recipientHexId = recipientHexId,
            body = body,
            timestamp = timestamp,
        )
        router.originate(sealed(packet))
    }.onFailure {
        Log.w(TAG, "failed to originate message: ${it.message}")
    }.getOrDefault(false)

    /**
     * Encrypts a direct message's payload, if we can.
     *
     * Three outcomes, and the second is the uncomfortable one:
     * - **Broadcast** goes out in the clear. There is no single recipient to
     *   agree a key with, so the mesh-wide thread is readable by anyone in
     *   range.
     * - **No agreement key yet** — the recipient has not announced itself — also
     *   goes out in the clear rather than not at all. Refusing to send would
     *   make the app look broken to two users who have only just met, and the
     *   plaintext fallback is what `TESTING.md` warns about. This is the weakest
     *   point in the design and the first thing a real release should tighten.
     * - Otherwise the payload is sealed with AES-GCM.
     */
    private fun sealed(packet: Packet): Packet {
        if (packet.isBroadcast) return packet

        val ciphertext = cipher.encrypt(
            peerId = packet.recipientId,
            messageId = packet.messageId,
            plaintext = packet.payload,
        )
        if (ciphertext == null) {
            Log.w(TAG, "no key for ${Packet.tag(packet.recipientId)}, sending in the clear")
            return packet
        }
        return packet.copy(payload = ciphertext)
    }

    /**
     * Recovers a delivered message's text.
     *
     * A direct message is tried through the cipher first and falls back to
     * reading the payload as text, because a peer that does not yet hold our
     * agreement key sends in the clear. The fallback is why a garbled body is
     * possible rather than an error: an undecryptable payload read as UTF-8
     * produces replacement characters instead of throwing.
     */
    private fun openedBody(packet: Packet): String {
        if (packet.isBroadcast) return MessagePacket.bodyOf(packet)

        val plaintext = cipher.decrypt(
            peerId = packet.senderId,
            messageId = packet.messageId,
            ciphertext = packet.payload,
        )
        return plaintext?.toString(Charsets.UTF_8) ?: MessagePacket.bodyOf(packet)
    }

    /** Delivered packets become rows. */
    private fun collectIncoming() = scope.launch {
        router.delivered.collect { packet ->
            if (packet.type == PacketType.ANNOUNCE) {
                recordAnnouncement(packet)
                return@collect
            }
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
                body = openedBody(packet),
                timestamp = packet.timestamp,
                peerId = senderHex,
            )
            // Flooding delivers the same message by several routes; a duplicate
            // here is routine, not a fault.
            Log.d(TAG, "message from ${Packet.tag(packet.senderId)} new=$isNew")
        }
    }

    /**
     * Files a peer's keys from an ANNOUNCE the router has already verified.
     *
     * By the time this runs the packet is VALID, which for an ANNOUNCE means
     * its signature checked out against the key in its own payload *and* the
     * sender id equals the hash of that key. So the keys genuinely belong to
     * the id claiming them — what is still unknown is whether the human behind
     * that id is who the user thinks, which is what QR pairing settles.
     */
    private suspend fun recordAnnouncement(packet: Packet) {
        val announced = Announcement.parse(packet.payload) ?: return
        val senderHex = packet.senderId.toHexId()
        if (senderHex == selfId) return

        val known = keyDirectory.knows(packet.senderId)
        keyDirectory.record(announced)
        peers.recordSeen(senderHex, publicKey = announced.signingKey)

        if (!known) Log.i(TAG, "learned keys for ${Packet.tag(packet.senderId)}")
    }

    /**
     * Introduces this device to the mesh.
     *
     * Broadcast rather than sent to one peer: an introduction is for whoever can
     * hear it, and it has to reach devices that by definition do not know us
     * yet. Flooding carries it past direct neighbours, so a peer two hops away
     * can verify us without ever being in range.
     */
    private suspend fun announceSelf() {
        val packet = announcementPacket(
            messageId = newMessageId().hexIdToBytes(),
            announcement = identity.announcement,
            timestamp = System.currentTimeMillis(),
        )
        runCatching { router.originate(packet) }
            .onFailure { Log.w(TAG, "failed to announce: ${it.message}") }
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
                if (flushed.add(endpointId)) {
                    // Introduce ourselves before handing over anything queued,
                    // so the peer can verify what follows rather than filing it
                    // all as coming from an unknown sender.
                    announceSelf()
                    offerQueued(peerId, endpointId)
                }
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
