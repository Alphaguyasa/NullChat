package et.alpha.nchat.mesh

import android.util.Log
import et.alpha.nchat.data.db.BROADCAST_CONVERSATION_ID
import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.directConversationId
import et.alpha.nchat.data.db.hexIdToBytes
import et.alpha.nchat.data.db.newMessageId
import et.alpha.nchat.data.db.toHexId
import et.alpha.nchat.data.media.AttachmentStore
import et.alpha.nchat.data.media.ImagePolicy
import et.alpha.nchat.data.repo.MessageRepository
import et.alpha.nchat.data.repo.PeerRepository
import et.alpha.nchat.di.MeshScope
import et.alpha.nchat.mesh.crypto.LocalIdentity
import et.alpha.nchat.mesh.crypto.PublicKeyDirectory
import et.alpha.nchat.mesh.crypto.SessionCipher
import et.alpha.nchat.mesh.protocol.Acknowledgement
import et.alpha.nchat.mesh.protocol.Announcement
import et.alpha.nchat.mesh.protocol.Attachment
import et.alpha.nchat.mesh.protocol.ContentType
import et.alpha.nchat.mesh.protocol.FragmentHeader
import et.alpha.nchat.mesh.protocol.FragmentReassembler
import et.alpha.nchat.mesh.protocol.Fragmenter
import et.alpha.nchat.mesh.protocol.MessagePacket
import et.alpha.nchat.mesh.protocol.ReassembledMessage
import et.alpha.nchat.mesh.protocol.acknowledgementPacket
import et.alpha.nchat.mesh.protocol.announcementPacket
import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketType
import et.alpha.nchat.mesh.router.MeshRouter
import et.alpha.nchat.mesh.router.RelayEvent
import et.alpha.nchat.mesh.transport.NearbyTransport
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    /**
     * Stored, but the transfer could not finish; the row is FAILED.
     *
     * Only fragmented messages end up here. They are not store-and-forwarded, so
     * an empty mesh is a real failure the user needs to hear about rather than
     * the quiet "will go out later" of [Pending].
     */
    data class Failed(val reason: String) : SendResult
}

/**
 * A message that has just been stored for the first time.
 *
 * Emitted once per message, never for the duplicates flooding delivers by
 * other routes, so a listener can treat each one as news. [preview] is message
 * text: it may be shown to the user but must never be logged.
 */
data class Arrival(
    val conversationId: String,
    val senderId: String,
    val isBroadcast: Boolean,
    val preview: String,
    val isImage: Boolean,
)

/**
 * Whether a verified ACK from [ackSenderHex] may mark [message] delivered.
 *
 * Only our own direct message, and only by the peer it was addressed to. A relay
 * that carried the message holds its id and could otherwise "acknowledge" it, and
 * broadcasts have no single recipient whose receipt would mean anything.
 */
internal fun isReceiptFor(message: MessageEntity, ackSenderHex: String): Boolean =
    message.isOutgoing && message.conversationId == directConversationId(ackSenderHex)

/**
 * The hop count to store for a delivered message, from what its ACK reported.
 *
 * Zero is the recipient saying it does not know. Anything above
 * [Packet.DEFAULT_TTL] cannot have come from a real trip — no packet starts with
 * a bigger budget — and the byte is unsigned on the wire, so a buggy or hostile
 * peer could claim 200. Both become null: the bubble then reads plain
 * `delivered`, which is true, instead of a distance nobody measured.
 */
internal fun storedHops(ack: Acknowledgement): Int? =
    ack.hops.takeIf { it in 1..Packet.DEFAULT_TTL }

/**
 * The packet to put on the air for [packet], or null to hold it.
 *
 * Broadcasts go out as they are. A direct message goes out only sealed: when
 * [seal] has no agreement key for the recipient it returns null, and so does
 * this. The old fallback sent such a message in the clear, readable by every
 * relay in the path, after a restart or before the peer had announced. Holding
 * it costs a delay until the key arrives; sending it costs the content.
 */
internal fun sealOrHold(packet: Packet, seal: (Packet) -> ByteArray?): Packet? {
    if (packet.isBroadcast) return packet
    val ciphertext = seal(packet) ?: return null
    return packet.copy(payload = ciphertext)
}

/**
 * Our outgoing rows to [peerHex] that were held for want of its agreement key.
 *
 * Only single-packet text: fragmented messages (long text, images) are never
 * held — they fail at send time, because fragments are not store-and-forwarded
 * and the receiver's reassembly window would long have closed.
 */
internal fun heldFor(peerHex: String, pending: List<MessageEntity>): List<MessageEntity> =
    pending.filter { row ->
        row.isOutgoing &&
            row.deliveryState == DeliveryState.PENDING &&
            row.conversationId == directConversationId(peerHex) &&
            row.attachmentFile == null &&
            MessagePacket.fits(row.body)
    }

/**
 * One reading of a peer's distance, and when it was taken.
 *
 * The timestamp is the whole point: a hop count with no age attached cannot be
 * told apart from a stale one, and the minimum-wins rule needs to know which of
 * two disagreeing paths is still describing the mesh as it is now.
 */
private data class HopObservation(val hops: Int, val observedAt: Long)

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
    private val attachments: AttachmentStore,
    private val transfers: TransferTracker,
    @MeshScope private val scope: CoroutineScope,
) {

    private val started = AtomicBoolean(false)

    /**
     * Buffers for messages arriving in pieces.
     *
     * Owned here rather than by the router, because the router's job ends at
     * "this packet is for us" — reassembly is about message content, which is
     * this class's side of the boundary.
     */
    private val reassembler = FragmentReassembler()

    private val _arrivals = MutableSharedFlow<Arrival>(
        extraBufferCapacity = ARRIVAL_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * New messages as they are stored, for notifications.
     *
     * Hot and unbuffered for late subscribers: a notification for something
     * that arrived before anyone was listening would be stale news. Dropping
     * the oldest on overflow rather than suspending, because the inbound path
     * must never wait on a slow listener — that would stall relaying too.
     */
    val arrivals: SharedFlow<Arrival> = _arrivals.asSharedFlow()

    /** This device's own id, hex-encoded — the `senderId` on everything it sends. */
    val selfId: String by lazy { identity.peerId.toHexId() }

    private val hopObservations = MutableStateFlow<Map<String, HopObservation>>(emptyMap())

    /**
     * How far away each peer was the last time we heard it, keyed by hex peer id.
     *
     * ## Why this is not a column on `PeerEntity`
     *
     * `PeerRepository` already argues that presence is derived rather than
     * stored, because a mesh peer never announces that it left — it just stops
     * being heard from. Hop distance is the same kind of fact one step further
     * on. A distance reloaded from disk describes a topology that has since
     * walked out of the building, and the radar would draw it as though it were
     * current. So this lives for as long as the process does and no longer, and
     * there is no migration to write.
     *
     * ## Why the number is a hint and not a measurement
     *
     * It is derived from `ttl`, which `PacketCodec.signedBytes` deliberately
     * excludes from the signature so a relay can spend a hop without breaking
     * end-to-end verification. The cost of that trade is paid here: anything in
     * the path can rewrite the byte, and a forged one moves a node between rings
     * for free. Nothing downstream may present this to the user as a fact about
     * where somebody is standing.
     *
     * Only covers peers whose packets actually reach us — a device whose traffic
     * we merely relay is never delivered here and stays unknown.
     */
    val observedHops: StateFlow<Map<String, Int>> = hopObservations
        .map { observations -> observations.mapValues { (_, observation) -> observation.hops } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * Packets crossing this device's links, straight off the router.
     *
     * A passthrough rather than a second implementation, and routed through here
     * rather than injected into a ViewModel directly, because this class is the
     * seam: the UI reaching into `MeshRouter` for one flow is how a boundary
     * stops being one. What the events mean, and what they carefully do not mean,
     * is documented on [RelayEvent].
     */
    val relayActivity: SharedFlow<RelayEvent> get() = router.relayActivity

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

        collectLearnedKeys()
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
     * Agreement keys are restored too, so a direct message written right after
     * a restart can be sealed without waiting for the peer to announce again.
     * Peers stored before version 4 of the database have no agreement key yet
     * and stay verify-only until their next ANNOUNCE.
     */
    private fun warmKeyDirectory() = scope.launch {
        keyDirectory.record(identity.announcement)
        peers.observePeers().first().forEach { peer ->
            peer.publicKey?.let { keyDirectory.restore(it, peer.agreementKey) }
        }
        Log.i(TAG, "key directory warmed with ${keyDirectory.size} key(s)")
    }

    /**
     * Sends direct messages that were held until their recipient's agreement key
     * turned up.
     *
     * Subscribed undispatched, before [warmKeyDirectory] runs, so a key restored
     * from the database at start also releases what was held for that peer when
     * the app last closed.
     */
    private fun collectLearnedKeys() = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        keyDirectory.agreementKeyLearned.collect { peerHex -> releaseHeld(peerHex) }
    }

    private suspend fun releaseHeld(peerHex: String) {
        val held = heldFor(peerHex, messages.pendingOutgoing())
        if (held.isEmpty()) return
        Log.i(TAG, "key for ${Packet.tag(peerHex.hexIdToBytes())} arrived, sending ${held.size} held message(s)")
        for (row in held) {
            // Same id and timestamp as the first attempt, so a row that had in
            // fact been queued already is deduplicated by the queue and the
            // receiver rather than shown twice.
            if (transmit(row.id, row.body, peerHex, row.timestamp)) messages.markSent(row.id)
        }
    }

    /**
     * Sends [body] to [recipientHexId], or to everyone when it is null.
     *
     * The row is written **before** the packet goes anywhere. A crash between
     * the two leaves a PENDING message rather than one that vanished; the
     * reverse order would lose the message with no trace it ever existed.
     *
     * A direct message to a peer whose agreement key we do not hold yet stays
     * PENDING and is not transmitted at all; [releaseHeld] sends it once the key
     * arrives. See [sealOrHold].
     */
    suspend fun send(body: String, recipientHexId: String?): SendResult {
        if (body.isBlank()) return SendResult.Rejected("empty message")
        if (!MessagePacket.isSendable(body)) {
            return SendResult.Rejected(
                "message is ${MessagePacket.bodyByteCount(body)} bytes, " +
                    "over the ${MessagePacket.MAX_TEXT_BYTES} byte limit",
            )
        }
        if (!MessagePacket.fits(body)) return sendLongText(body, recipientHexId)

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
     * Sends an already-downscaled JPEG to [recipientHexId], or to everyone.
     *
     * ## Why images are best-effort
     *
     * A 14 KB image is ~280 FRAGMENT packets. `MeshRouter.enqueueForLater` holds
     * only MESSAGE packets, so fragments are **not** store-and-forwarded, and
     * that is deliberate: 280 entries through a 500-entry `ForwardQueue` would
     * evict most of the pending text messages, and the receiver's 60 second
     * reassembly window would have closed long before a queued fragment was
     * replayed anyway. A half-delivered image is not worth a wiped queue.
     *
     * So an image needs a peer in range for the whole transfer. If the mesh is
     * empty, or empties partway, the message is marked FAILED and the user is
     * told — rather than left with a row that looks pending forever.
     */
    suspend fun sendImage(jpeg: ByteArray, recipientHexId: String?): SendResult {
        if (!ImagePolicy.fits(jpeg.size)) {
            return SendResult.Rejected(
                "image is ${jpeg.size} bytes, over the " +
                    "${ImagePolicy.MAX_ENCODED_BYTES} byte limit",
            )
        }

        val messageId = newMessageId()
        // Stored before anything is transmitted, same ordering as text: a crash
        // mid-send leaves a visible row rather than a message that vanished.
        val fileName = attachments.write(messageId, jpeg)
            ?: return SendResult.Rejected("could not save the image")

        val conversationId = conversationIdFor(recipientHexId)
        val row = messages.recordOutgoing(
            conversationId = conversationId,
            selfId = selfId,
            body = "",
            peerId = recipientHexId,
            messageId = messageId,
            attachmentFile = fileName,
        )

        val fragments = runCatching {
            fragmentsFor(row.id, Attachment.jpeg(jpeg), recipientHexId, row.timestamp)
        }
            .onFailure { Log.w(TAG, "could not fragment image: ${it.message}") }
            .getOrElse {
                messages.markFailed(row.id)
                return SendResult.Rejected("could not prepare the image for sending")
            }
            ?: run {
                messages.markFailed(row.id)
                return SendResult.Failed(NO_KEY_FOR_FRAGMENTS)
            }

        return transmitFragments(row.id, fragments)
    }

    /**
     * Sends text too long for one packet as a fragmented TEXT_UTF8 attachment.
     *
     * The row stores the plain body like any other text message, so the thread
     * cannot tell the two apart. On the wire it travels the image path: one
     * encryption, then FRAGMENT packets the receiver reassembles. Like an image
     * it needs a peer in range for the whole transfer; see [sendImage].
     */
    private suspend fun sendLongText(body: String, recipientHexId: String?): SendResult {
        val row = messages.recordOutgoing(
            conversationId = conversationIdFor(recipientHexId),
            selfId = selfId,
            body = body,
            peerId = recipientHexId,
        )

        val fragments = runCatching {
            fragmentsFor(row.id, Attachment.text(body), recipientHexId, row.timestamp)
        }
            .onFailure { Log.w(TAG, "could not fragment long text: ${it.message}") }
            .getOrElse {
                messages.markFailed(row.id)
                return SendResult.Failed("Could not prepare the message for sending.")
            }
            ?: run {
                messages.markFailed(row.id)
                return SendResult.Failed(NO_KEY_FOR_FRAGMENTS)
            }

        return transmitFragments(row.id, fragments)
    }

    /**
     * Wraps, encrypts and splits an attachment into FRAGMENT packets.
     *
     * The whole payload is encrypted **once, before** splitting, rather than each
     * chunk separately. `SessionCipher` derives its nonce from the message id,
     * and every fragment has a different packet id, so per-fragment encryption
     * would need a different nonce per piece and 16 bytes of tag on each — a
     * third of the 51-byte chunk spent on overhead. Encrypting first also means
     * the content-type envelope is inside the sealed region rather than readable
     * by anyone in range.
     *
     * @return null for a direct message whose recipient's agreement key is not
     *   known yet.
     */
    private fun fragmentsFor(
        messageId: String,
        attachment: Attachment,
        recipientHexId: String?,
        timestamp: Long,
    ): List<Packet>? {
        val messageIdBytes = messageId.hexIdToBytes()
        val recipientId = recipientHexId?.hexIdToBytes() ?: Packet.BROADCAST_ID
        val envelope = attachment.encode()

        // A broadcast has no single recipient to agree a key with, so it goes out
        // in the clear — the same trade `sealOrHold` documents for text. A direct
        // one is never sent in the clear: no key means no fragments.
        val payload = if (recipientHexId == null) {
            envelope
        } else {
            cipher.encrypt(
                peerId = recipientId,
                messageId = messageIdBytes,
                plaintext = envelope,
            ) ?: return null
        }

        return Fragmenter.fragment(
            messageId = messageIdBytes,
            senderId = identity.peerId,
            recipientId = recipientId,
            payload = payload,
            timestamp = timestamp,
        )
    }

    /**
     * Pushes every fragment into the mesh, reporting progress as it goes.
     *
     * Paced by [FRAGMENT_SPACING_MILLIS]. Handing Nearby ~280 packets in a tight
     * loop fills its outbound queue faster than a Bluetooth link drains it, and
     * the packets that overflow are simply lost — which for a fragmented message
     * means the whole image fails. The spacing costs a few seconds and stays well
     * inside the receiver's 60 second window.
     */
    private suspend fun transmitFragments(messageId: String, fragments: List<Packet>): SendResult {
        transfers.start(messageId, fragments.size, isOutgoing = true)
        Log.i(TAG, "sending ${fragments.size} fragments")

        fragments.forEachIndexed { index, fragment ->
            if (!router.originate(fragment)) {
                // Nobody in range. Fragments are not queued, so the rest of this
                // message would be shouting into an empty room.
                Log.w(TAG, "transfer stalled at fragment $index/${fragments.size}, no peers")
                transfers.fail(messageId)
                messages.markFailed(messageId)
                return SendResult.Failed("Nobody was in range for long enough to receive it.")
            }
            transfers.advance(messageId, index + 1)
            if (index < fragments.lastIndex) delay(FRAGMENT_SPACING_MILLIS)
        }

        transfers.complete(messageId)
        messages.markSent(messageId)
        return SendResult.Sent
    }

    /**
     * Encodes and originates one message. Returns whether a peer took it.
     *
     * Shared by [send] and [releaseHeld] so a retried message is built exactly
     * the same way as a first attempt — same id, same timestamp, so the receiver
     * dedups it rather than showing it twice.
     *
     * Returns false without touching the radio when a direct message cannot be
     * sealed yet; the row stays PENDING.
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
        val outgoing = sealOrHold(packet) { cipher.encrypt(it.recipientId, it.messageId, it.payload) }
        if (outgoing == null) {
            Log.i(TAG, "no key for ${Packet.tag(packet.recipientId)} yet, holding the message")
            return@runCatching false
        }
        router.originate(outgoing)
    }.onFailure {
        Log.w(TAG, "failed to originate message: ${it.message}")
    }.getOrDefault(false)

    /**
     * Recovers a delivered message's text.
     *
     * A direct message is tried through the cipher first and falls back to
     * reading the payload as text, because builds before held direct messages
     * (see [sealOrHold]) sent in the clear when they lacked our agreement key.
     * The fallback only accepts well-formed UTF-8; real ciphertext that would
     * not open becomes a placeholder instead of being rendered as garbage. See [MessagePacket.undecryptedBody].
     */
    private fun openedBody(packet: Packet): String {
        if (packet.isBroadcast) return MessagePacket.bodyOf(packet)

        val plaintext = cipher.decrypt(
            peerId = packet.senderId,
            messageId = packet.messageId,
            ciphertext = packet.payload,
        )
        return plaintext?.toString(Charsets.UTF_8) ?: MessagePacket.undecryptedBody(packet.payload)
    }

    /** Delivered packets become rows. */
    private fun collectIncoming() = scope.launch {
        router.delivered.collect { packet ->
            // Before the type switch, so ANNOUNCE, FRAGMENT and MESSAGE all
            // contribute: this is the one point every live packet with an intact
            // ttl passes through, and an announcement is often the only thing we
            // ever hear from a distant peer.
            recordHops(packet)

            if (packet.type == PacketType.ANNOUNCE) {
                recordAnnouncement(packet)
                return@collect
            }
            if (packet.type == PacketType.FRAGMENT) {
                recordFragment(packet)
                return@collect
            }
            if (packet.type == PacketType.ACK) {
                recordAcknowledgement(packet)
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
            val body = openedBody(packet)
            val isNew = messages.recordIncoming(
                messageId = packet.messageId.toHexId(),
                conversationId = conversationId,
                senderId = senderHex,
                body = body,
                timestamp = packet.timestamp,
                peerId = senderHex,
            )
            // Flooding delivers the same message by several routes; a duplicate
            // here is routine, not a fault.
            Log.d(TAG, "message from ${Packet.tag(packet.senderId)} new=$isNew")

            if (isNew && !packet.isBroadcast) {
                acknowledge(packet.messageId, packet.senderId, hopsFrom(packet.ttl))
            }
            if (isNew) {
                _arrivals.tryEmit(
                    Arrival(
                        conversationId = conversationId,
                        senderId = senderHex,
                        isBroadcast = packet.isBroadcast,
                        preview = body,
                        isImage = false,
                    ),
                )
            }
        }
    }

    /**
     * Tells [senderId] that its direct message [messageId] arrived.
     *
     * Only for the first copy: flooding delivers duplicates by every route there
     * is, and answering each one would multiply receipts across the mesh for no
     * new information. A receipt that is lost on the way back is covered by the
     * forward queue, which holds ACKs as well as messages.
     *
     * Broadcasts are never acknowledged — a receipt from everyone in range would
     * be a storm, and "delivered" has no single meaning for a message to all.
     */
    private suspend fun acknowledge(messageId: ByteArray, senderId: ByteArray, hops: Int?) {
        val packet = acknowledgementPacket(
            messageId = newMessageId().hexIdToBytes(),
            senderId = identity.peerId,
            recipientId = senderId,
            acknowledgement = Acknowledgement(messageId, hops ?: 0),
            timestamp = System.currentTimeMillis(),
        )
        runCatching { router.originate(packet) }
            .onFailure { Log.w(TAG, "failed to acknowledge: ${it.message}") }
    }

    /**
     * Marks one of our messages delivered on a verified receipt.
     *
     * The receipt must come from the peer the message was addressed to. The
     * router has already checked the signature, so [Packet.senderId] is genuine;
     * this check is what stops a third device from acknowledging a message it was
     * merely relaying, or one meant for somebody else.
     */
    private suspend fun recordAcknowledgement(packet: Packet) {
        val ack = Acknowledgement.parse(packet.payload) ?: return
        val messageId = ack.ackedMessageId.toHexId()
        val senderHex = packet.senderId.toHexId()

        val message = messages.findMessage(messageId) ?: return
        if (!isReceiptFor(message, senderHex)) {
            Log.w(TAG, "ignoring receipt from ${Packet.tag(packet.senderId)} for a message not sent to it")
            return
        }

        peers.recordSeen(senderHex)
        messages.markDelivered(messageId, storedHops(ack))
        Log.d(TAG, "message delivered to ${Packet.tag(packet.senderId)} in ${ack.hops} hop(s)")
    }

    /**
     * Files how far away a packet's sender was, from the ttl it arrived with.
     *
     * Flooding delivers the same sender's traffic by every route there is, so two
     * paths routinely disagree about the distance. The shortest wins, because
     * that is what a ring on a radar is supposed to mean.
     *
     * But only against an observation of comparable age. One lucky short path —
     * a phone that walked past once — would otherwise pin a peer to an inner ring
     * for the rest of the session, and the peer would keep looking closer than it
     * is however long it spends far away. So an observation older than
     * [HOP_FRESHNESS_MILLIS] is replaced outright instead of compared against,
     * and a surviving minimum is never re-stamped: it has to re-earn its place by
     * being observed again, or it ages out.
     */
    private fun recordHops(packet: Packet, now: Long = System.currentTimeMillis()) {
        val senderHex = packet.senderId.toHexId()
        if (senderHex == selfId) return
        val hops = hopsFrom(packet.ttl) ?: return

        hopObservations.update { observations ->
            val previous = observations[senderHex]
            val keepPrevious = previous != null &&
                previous.hops <= hops &&
                now - previous.observedAt <= HOP_FRESHNESS_MILLIS
            if (keepPrevious) observations else observations + (senderHex to HopObservation(hops, now))
        }
    }

    /**
     * Links between here and a packet's originator, from the ttl it carries.
     *
     * Not `DEFAULT_TTL - ttl`, which counts *decrements* and lands one short: a
     * packet is originated at the full budget and only pays a hop when a relay
     * forwards it, so a direct neighbour's packet arrives untouched at
     * [Packet.DEFAULT_TTL]. Counting decrements would file every neighbour at
     * zero and contradict the peer list, which calls a connected peer one hop
     * away.
     *
     * Null when the arithmetic cannot be trusted at all: a ttl above the default
     * is either a build speaking a different budget or an outright forgery, and
     * either way it would place the sender closer than anything real can be.
     */
    private fun hopsFrom(ttl: Int): Int? =
        if (ttl in 1..Packet.DEFAULT_TTL) Packet.DEFAULT_TTL - ttl + 1 else null

    /**
     * Feeds one fragment to the reassembler and reports the transfer's progress.
     *
     * Every fragment was individually signed and verified by the router before it
     * got here, so a piece that arrives is known to come from the sender it
     * claims. What is *not* checked is that all the pieces came from the same
     * device — the reassembler keys buffers on (messageId, senderId) so that
     * mixing two senders' fragments into one image is impossible by construction.
     */
    private suspend fun recordFragment(packet: Packet) {
        val header = FragmentHeader.parse(packet.payload) ?: return
        val messageId = header.messageId.toHexId()
        val senderHex = packet.senderId.toHexId()
        if (senderHex == selfId) return

        // Flooding keeps delivering pieces of a message long after the last one
        // needed has arrived. Without this, a straggler would open a fresh
        // reassembly buffer and the UI would show "Receiving 1/282" underneath a
        // picture the user is already looking at. The tracker catches it within a
        // session; the message row catches it across a restart, when the tracker
        // is empty but the history is not.
        if (transfers.transfers.value[messageId]?.state == TransferState.COMPLETE) return
        if (messages.findMessage(messageId) != null) return

        val completed = reassembler.offer(packet)
        if (completed != null) {
            transfers.complete(messageId)
            // The completing fragment's ttl stands in for the whole message's
            // distance: every piece took the same flood from the same sender.
            deliverReassembled(completed, hopsFrom(packet.ttl))
            return
        }

        val progress = reassembler.progressFor(header.messageId, packet.senderId)
        if (progress == null) {
            // The buffer is gone: this message's 60 seconds ran out, or it was
            // evicted. Say so rather than leaving a bar frozen at 80%.
            transfers.fail(messageId)
            return
        }
        if (progress.received == 1) {
            peers.recordSeen(senderHex)
            transfers.start(messageId, progress.total, isOutgoing = false)
        }
        transfers.advance(messageId, progress.received)
    }

    /**
     * Turns a fully reassembled message into a row.
     *
     * Decryption happens here rather than per fragment because the payload was
     * sealed once before splitting — see [fragmentsFor]. The plaintext fallback
     * is the same one [openedBody] documents: a peer that does not hold our
     * agreement key sends in the clear, and refusing to render that would make
     * the app look broken to two users who have only just met.
     */
    private suspend fun deliverReassembled(message: ReassembledMessage, hops: Int?) {
        val senderHex = message.senderId.toHexId()

        val decrypted = if (message.isBroadcast) {
            message.payload
        } else {
            cipher.decrypt(
                peerId = message.senderId,
                messageId = message.messageId,
                ciphertext = message.payload,
            )
        }
        val plaintext = decrypted ?: message.payload

        val attachment = Attachment.parse(plaintext)
            // A sealed message we could not open: say so in the thread rather
            // than drop it without a trace.
            ?: Attachment.text(MessagePacket.UNDECRYPTABLE_BODY).takeIf { decrypted == null }
        if (attachment == null) {
            Log.w(TAG, "reassembled message from ${Packet.tag(message.senderId)} was not readable")
            return
        }

        val messageId = message.messageId.toHexId()
        val conversationId =
            if (message.isBroadcast) BROADCAST_CONVERSATION_ID else directConversationId(senderHex)

        peers.recordSeen(senderHex)

        val isImage = attachment.contentType == ContentType.IMAGE_JPEG
        // An undecrypted payload that happens to parse as an envelope is still
        // only shown if its text is genuinely text.
        val textBody = when {
            isImage -> ""
            decrypted != null -> attachment.asText()
            else -> MessagePacket.undecryptedBody(attachment.data)
        }

        val isNew = when (attachment.contentType) {
            ContentType.IMAGE_JPEG -> {
                val fileName = attachments.write(messageId, attachment.data)
                if (fileName == null) {
                    Log.w(TAG, "could not store image from ${Packet.tag(message.senderId)}")
                    return
                }
                messages.recordIncoming(
                    messageId = messageId,
                    conversationId = conversationId,
                    senderId = senderHex,
                    body = "",
                    timestamp = message.timestamp,
                    peerId = senderHex,
                    attachmentFile = fileName,
                )
            }

            ContentType.TEXT_UTF8 -> messages.recordIncoming(
                messageId = messageId,
                conversationId = conversationId,
                senderId = senderHex,
                body = textBody,
                timestamp = message.timestamp,
                peerId = senderHex,
            )
        }

        Log.d(
            TAG,
            "reassembled ${attachment.contentType} from ${Packet.tag(message.senderId)} new=$isNew",
        )

        if (isNew && !message.isBroadcast) acknowledge(message.messageId, message.senderId, hops)
        if (isNew) {
            _arrivals.tryEmit(
                Arrival(
                    conversationId = conversationId,
                    senderId = senderHex,
                    isBroadcast = message.isBroadcast,
                    preview = textBody,
                    isImage = isImage,
                ),
            )
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
        peers.recordSeen(
            senderHex,
            publicKey = announced.signingKey,
            agreementKey = announced.agreementKey,
        )

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

        /** Arrivals held for a slow listener before the oldest are dropped. */
        const val ARRIVAL_BUFFER = 32

        /**
         * Why a long message or image to a not-yet-introduced peer failed.
         * Fragments cannot be held for later the way single packets are.
         */
        const val NO_KEY_FOR_FRAGMENTS =
            "No encryption key for this person yet. Try again once they have been " +
                "in range, or pair by QR code."

        /**
         * How long a hop observation stays worth comparing a new one against.
         *
         * Shorter than `PeerRepository.DEFAULT_PRESENCE_WINDOW_MS`: how far away
         * someone is changes as they walk, while whether they are here at all
         * changes only when they leave.
         */
        const val HOP_FRESHNESS_MILLIS: Long = 60 * 1000L

        /**
         * Gap between consecutive fragments of one image.
         *
         * Slow enough that Nearby's outbound queue drains between packets, fast
         * enough that ~280 fragments still clear the receiver's 60 second
         * reassembly window with room to spare.
         */
        const val FRAGMENT_SPACING_MILLIS: Long = 20L
    }
}
