package et.alpha.nchat.mesh

import et.alpha.nchat.data.db.BROADCAST_CONVERSATION_ID
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.directConversationId
import et.alpha.nchat.data.db.hexIdToBytes
import et.alpha.nchat.data.db.newMessageId
import et.alpha.nchat.data.db.toHexId
import et.alpha.nchat.data.media.ImagePolicy
import et.alpha.nchat.mesh.crypto.LocalIdentity
import et.alpha.nchat.mesh.crypto.PublicKeyDirectory
import et.alpha.nchat.mesh.protocol.Acknowledgement
import et.alpha.nchat.mesh.protocol.Announcement
import et.alpha.nchat.mesh.protocol.Attachment
import et.alpha.nchat.mesh.protocol.ContentType
import et.alpha.nchat.mesh.protocol.FragmentHeader
import et.alpha.nchat.mesh.protocol.FragmentReassembler
import et.alpha.nchat.mesh.protocol.MessagePacket
import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketType
import et.alpha.nchat.mesh.protocol.Profile
import et.alpha.nchat.mesh.protocol.ReassembledMessage
import et.alpha.nchat.mesh.protocol.acknowledgementPacket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
 * Whether a reassembled attachment may be stored.
 *
 * The reassembler accepts up to 64 KB, the most any message may claim, but our
 * own build never sends an image over [ImagePolicy]'s limits. Anything larger
 * came from a modified client, and storing it would hand the chat screen a file
 * it has no reason to trust. Text is bounded by the reassembler alone.
 */
internal fun acceptsInbound(attachment: Attachment): Boolean =
    attachment.contentType != ContentType.IMAGE_JPEG || ImagePolicy.fits(attachment.data.size)

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
internal fun hopsFrom(ttl: Int): Int? =
    if (ttl in 1..Packet.DEFAULT_TTL) Packet.DEFAULT_TTL - ttl + 1 else null

/**
 * One reading of a peer's distance, and when it was taken.
 *
 * The timestamp is the whole point: a hop count with no age attached cannot be
 * told apart from a stale one, and the minimum-wins rule needs to know which of
 * two disagreeing paths is still describing the mesh as it is now.
 */
internal data class HopObservation(val hops: Int, val observedAt: Long)

/**
 * What the inbound path reads and writes outside the mesh.
 *
 * An interface rather than the repositories themselves so the whole path can run
 * on the JVM against a map: the repositories need Room and a `Context`, and the
 * rules this path enforces (blocked, deleted, our own echo, receipts only from
 * the addressee) are exactly the ones a regression would break silently.
 */
internal interface InboundStore {
    fun isDeleted(messageId: String): Boolean
    suspend fun isBlocked(peerHex: String): Boolean
    suspend fun recordSeen(
        peerHex: String,
        displayName: String? = null,
        publicKey: ByteArray? = null,
        agreementKey: ByteArray? = null,
    )
    suspend fun findMessage(messageId: String): MessageEntity?

    /** True when the row is new, false for a duplicate already stored. */
    suspend fun recordIncoming(
        messageId: String,
        conversationId: String,
        senderId: String,
        body: String,
        timestamp: Long,
        attachmentFile: String? = null,
    ): Boolean
    suspend fun markDelivered(messageId: String, hops: Int?)

    /** The stored file's name, or null when it could not be written. */
    suspend fun writeImage(messageId: String, jpeg: ByteArray): String?
}

/**
 * Where the inbound path's diagnostics go.
 *
 * Injected because `android.util.Log` throws on the JVM, and this class exists
 * to be tested there. Never handed message text, keys or full peer ids.
 */
internal interface InboundLog {
    fun debug(message: String) = Unit
    fun info(message: String) = Unit
    fun warn(message: String) = Unit

    object None : InboundLog
}

/**
 * Turns packets the router delivered into rows, receipts, keys and progress.
 *
 * Split out of `MeshCoordinator`, which owns the seam to the database, so the
 * part of it that faces the mesh can be driven packet by packet in a JVM test.
 * The router has already verified, deduplicated and addressed every packet that
 * reaches [handle]; what is decided here is what the app does with it.
 */
internal class InboundHandler(
    private val identity: LocalIdentity,
    private val store: InboundStore,
    private val keyDirectory: PublicKeyDirectory,
    private val decrypt: (peerId: ByteArray, messageId: ByteArray, ciphertext: ByteArray) -> ByteArray?,
    private val transfers: TransferTracker,
    private val originate: suspend (Packet) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val reassembler: FragmentReassembler = FragmentReassembler(),
    private val log: InboundLog = InboundLog.None,
) {

    // Lazy for the same reason as `MeshCoordinator.selfId`: reading the id
    // opens the keystore, which construction must not do.
    private val selfId: String by lazy { identity.peerId.toHexId() }

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

    private val _hopObservations = MutableStateFlow<Map<String, HopObservation>>(emptyMap())

    /** Latest distance reading per hex peer id; see `MeshCoordinator.observedHops`. */
    val hopObservations: StateFlow<Map<String, HopObservation>> = _hopObservations.asStateFlow()

    /** One packet off `MeshRouter.delivered`. */
    suspend fun handle(packet: Packet) {
        // Before the type switch, so ANNOUNCE, FRAGMENT and MESSAGE all
        // contribute: this is the one point every live packet with an intact
        // ttl passes through, and an announcement is often the only thing we
        // ever hear from a distant peer.
        recordHops(packet)

        when (packet.type) {
            PacketType.ANNOUNCE -> recordAnnouncement(packet)
            PacketType.FRAGMENT -> recordFragment(packet)
            PacketType.ACK -> recordAcknowledgement(packet)
            PacketType.PROFILE -> recordProfile(packet)
            PacketType.MESSAGE -> recordMessage(packet)
            else -> Unit
        }
    }

    private suspend fun recordMessage(packet: Packet) {
        val senderHex = packet.senderId.toHexId()
        // Our own broadcast flooding back to us. The router's seen-cache
        // catches this within a session; this catches it across a restart,
        // when the cache is empty but the database is not.
        if (senderHex == selfId) return

        // Deleted here earlier and replayed by a relay's forward queue.
        // Not acknowledged again: the sender had its receipt the first time.
        if (store.isDeleted(packet.messageId.toHexId())) return

        // Blocked: not stored, so nothing to show or notify, and not
        // acknowledged, so the sender sees `sent` rather than `delivered`
        // and learns nothing it could not see before. Not tombstoned either:
        // a spammer's flood would push the user's real deletions out of the
        // bounded tombstone set. So a message still in a relay's queue can
        // arrive after an unblock, which is the user asking for it.
        if (store.isBlocked(senderHex)) return

        val conversationId =
            if (packet.isBroadcast) BROADCAST_CONVERSATION_ID else directConversationId(senderHex)

        store.recordSeen(senderHex)
        val body = openedBody(packet)
        val isNew = store.recordIncoming(
            messageId = packet.messageId.toHexId(),
            conversationId = conversationId,
            senderId = senderHex,
            body = body,
            timestamp = packet.timestamp,
        )
        // Flooding delivers the same message by several routes; a duplicate
        // here is routine, not a fault.
        log.debug("message from ${Packet.tag(packet.senderId)} new=$isNew")

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

        val plaintext = decrypt(packet.senderId, packet.messageId, packet.payload)
        return plaintext?.toString(Charsets.UTF_8) ?: MessagePacket.undecryptedBody(packet.payload)
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
            timestamp = clock(),
        )
        runCatching { originate(packet) }
            .onFailure { log.warn("failed to acknowledge: ${it.message}") }
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

        val message = store.findMessage(messageId) ?: return
        if (!isReceiptFor(message, senderHex)) {
            log.warn("ignoring receipt from ${Packet.tag(packet.senderId)} for a message not sent to it")
            return
        }

        store.recordSeen(senderHex)
        store.markDelivered(messageId, storedHops(ack))
        log.debug("message delivered to ${Packet.tag(packet.senderId)} in ${ack.hops} hop(s)")
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
    private fun recordHops(packet: Packet) {
        val senderHex = packet.senderId.toHexId()
        if (senderHex == selfId) return
        val hops = hopsFrom(packet.ttl) ?: return
        val now = clock()

        _hopObservations.update { observations ->
            val previous = observations[senderHex]
            val keepPrevious = previous != null &&
                previous.hops <= hops &&
                now - previous.observedAt <= HOP_FRESHNESS_MILLIS
            if (keepPrevious) observations else observations + (senderHex to HopObservation(hops, now))
        }
    }

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
        // is empty but the history is not. A message the user deleted is refused
        // here too, before a progress bar or an image file exists for it.
        if (transfers.transfers.value[messageId]?.state == TransferState.COMPLETE) return
        if (store.findMessage(messageId) != null) return
        if (store.isDeleted(messageId)) return
        // Checked on every piece, not only the first, so a block set partway
        // through a transfer stops it before the image is ever written.
        if (store.isBlocked(senderHex)) return

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
            store.recordSeen(senderHex)
            transfers.start(messageId, progress.total, isOutgoing = false)
        }
        transfers.advance(messageId, progress.received)
    }

    /**
     * Turns a fully reassembled message into a row.
     *
     * Decryption happens here rather than per fragment because the payload was
     * sealed once before splitting — see `MeshCoordinator.fragmentsFor`. The
     * plaintext fallback is the same one [openedBody] documents: a peer that does
     * not hold our agreement key sends in the clear, and refusing to render that
     * would make the app look broken to two users who have only just met.
     */
    private suspend fun deliverReassembled(message: ReassembledMessage, hops: Int?) {
        val senderHex = message.senderId.toHexId()

        val decrypted = if (message.isBroadcast) {
            message.payload
        } else {
            decrypt(message.senderId, message.messageId, message.payload)
        }
        val plaintext = decrypted ?: message.payload

        val attachment = Attachment.parse(plaintext)
            // A sealed message we could not open: say so in the thread rather
            // than drop it without a trace.
            ?: Attachment.text(MessagePacket.UNDECRYPTABLE_BODY).takeIf { decrypted == null }
        if (attachment == null) {
            log.warn("reassembled message from ${Packet.tag(message.senderId)} was not readable")
            return
        }
        if (!acceptsInbound(attachment)) {
            log.warn("dropped an oversized image from ${Packet.tag(message.senderId)}")
            return
        }

        val messageId = message.messageId.toHexId()
        val conversationId =
            if (message.isBroadcast) BROADCAST_CONVERSATION_ID else directConversationId(senderHex)

        store.recordSeen(senderHex)

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
                val fileName = store.writeImage(messageId, attachment.data)
                if (fileName == null) {
                    log.warn("could not store image from ${Packet.tag(message.senderId)}")
                    return
                }
                store.recordIncoming(
                    messageId = messageId,
                    conversationId = conversationId,
                    senderId = senderHex,
                    body = "",
                    timestamp = message.timestamp,
                    attachmentFile = fileName,
                )
            }

            ContentType.TEXT_UTF8 -> store.recordIncoming(
                messageId = messageId,
                conversationId = conversationId,
                senderId = senderHex,
                body = textBody,
                timestamp = message.timestamp,
            )
        }

        log.debug("reassembled ${attachment.contentType} from ${Packet.tag(message.senderId)} new=$isNew")

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
        store.recordSeen(
            senderHex,
            publicKey = announced.signingKey,
            agreementKey = announced.agreementKey,
        )

        if (!known) log.info("learned keys for ${Packet.tag(packet.senderId)}")
    }

    /**
     * Files the display name a peer chose, from a PROFILE the router verified.
     *
     * Verified means signed by the key we hold for that id, so the name really is
     * the one that peer picked — not that the peer is who the name says. The
     * peer list therefore keeps the short id beside it; see `peerLabel`.
     */
    private suspend fun recordProfile(packet: Packet) {
        val senderHex = packet.senderId.toHexId()
        if (senderHex == selfId) return
        val profile = Profile.parse(packet.payload) ?: return
        store.recordSeen(senderHex, displayName = profile.displayName)
        log.debug("profile from ${Packet.tag(packet.senderId)}")
    }

    private companion object {
        /** Arrivals held for a slow listener before the oldest are dropped. */
        const val ARRIVAL_BUFFER = 32

        /**
         * How long a hop observation stays worth comparing a new one against.
         *
         * Shorter than `PeerRepository.DEFAULT_PRESENCE_WINDOW_MS`: how far away
         * someone is changes as they walk, while whether they are here at all
         * changes only when they leave.
         */
        const val HOP_FRESHNESS_MILLIS: Long = 60 * 1000L
    }
}
