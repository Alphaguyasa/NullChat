package et.alpha.nchat.mesh

import android.util.Log
import et.alpha.nchat.data.db.BROADCAST_CONVERSATION_ID
import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.PeerEntity
import et.alpha.nchat.data.db.directConversationId
import et.alpha.nchat.data.db.hexIdToBytes
import et.alpha.nchat.data.db.newMessageId
import et.alpha.nchat.data.db.peerIdOfDirect
import et.alpha.nchat.data.db.toHexId
import et.alpha.nchat.data.media.AttachmentStore
import et.alpha.nchat.data.media.ImagePolicy
import et.alpha.nchat.data.repo.DeletedMessageStore
import et.alpha.nchat.data.repo.MessageRepository
import et.alpha.nchat.data.repo.PeerRepository
import et.alpha.nchat.data.settings.DisplayNameStore
import et.alpha.nchat.data.settings.PeerTimerStore
import et.alpha.nchat.di.MeshScope
import et.alpha.nchat.mesh.crypto.LocalIdentity
import et.alpha.nchat.mesh.crypto.PublicKeyDirectory
import et.alpha.nchat.mesh.crypto.SessionCipher
import et.alpha.nchat.mesh.protocol.Attachment
import et.alpha.nchat.mesh.protocol.Fragmenter
import et.alpha.nchat.mesh.protocol.MessagePacket
import et.alpha.nchat.mesh.protocol.announcementPacket
import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.Profile
import et.alpha.nchat.mesh.protocol.TimerSetting
import et.alpha.nchat.mesh.protocol.profilePacket
import et.alpha.nchat.mesh.protocol.timerPacket
import et.alpha.nchat.mesh.router.ForwardQueue
import et.alpha.nchat.mesh.router.LinkBindings
import et.alpha.nchat.mesh.router.MeshRouter
import et.alpha.nchat.mesh.router.RelayEvent
import et.alpha.nchat.mesh.transport.NearbyTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What happened to a message the user tried to send. */
sealed interface SendResult {

    /** Stored and handed to at least one peer. */
    data object Sent : SendResult

    /**
     * Stored, but nobody was in range to take it.
     *
     * Not an error: the row stays PENDING and goes out when a peer appears —
     * for as long as the forward queue holds it. After that the row is FAILED
     * and the user can resend it; see [pendingExpiryCutoff].
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
 * Where a resend of [message] goes, or null when it is not ours to resend.
 *
 * Only our own FAILED rows. A PENDING one is still on its way (queued, or held
 * for a key) and a resend would put two copies on the air; a SENT or DELIVERED
 * one has already left. The inner null is the broadcast thread, which is why the
 * answer is wrapped: "to everyone" and "not at all" must not look alike.
 */
internal fun resendTarget(message: MessageEntity): ResendTarget? {
    if (!message.isOutgoing || message.deliveryState != DeliveryState.FAILED) return null
    if (message.conversationId == BROADCAST_CONVERSATION_ID) return ResendTarget(null)
    return peerIdOfDirect(message.conversationId)?.let(::ResendTarget)
}

/** @property recipientHexId the peer to resend to, or null for everyone. */
internal data class ResendTarget(val recipientHexId: String?)

/**
 * Whether [message] may be deleted from this phone.
 *
 * Everything but our own PENDING rows. A queued message is already signed and
 * sitting in the forward queue, or held until its recipient's key arrives, and
 * would still go out after its row was gone, so deleting it would hide a send
 * rather than stop it.
 */
internal fun canDeleteLocally(message: MessageEntity): Boolean =
    !(message.isOutgoing && message.deliveryState == DeliveryState.PENDING)

/**
 * The rows "Delete chat" removes: every one a single delete could, so our own
 * queued messages stay in the thread until they go out, for the same reason
 * [canDeleteLocally] keeps them.
 */
internal fun chatDeletion(rows: List<MessageEntity>): List<MessageEntity> = rows.filter(::canDeleteLocally)

/**
 * Whether "Forget contact" is offered for [peer].
 *
 * Not while blocked: the block lives on the peer's row, so forgetting would
 * quietly lift it, and the blocked sender's next message would arrive as a
 * stranger's. Delete chat clears a blocked thread without that.
 */
internal fun canForget(peer: PeerEntity?): Boolean = peer != null && !peer.isBlocked

/**
 * The oldest write time a PENDING row may have at [now] and still be queued.
 *
 * Matches the forward queue's own lifetime, because that is the promise
 * `queued` makes: past it the queue has dropped the packet and the row is
 * waiting for nothing. A row held for want of a key ages out the same way. A
 * peer that has not announced in a day is not one to keep a message sitting
 * under `queued` for, and the user can resend it once they are back.
 */
internal fun pendingExpiryCutoff(now: Long): Long = now - ForwardQueue.DEFAULT_EXPIRY_MILLIS

/**
 * [InboundStore] over the repositories, so the inbound path writes where the UI reads.
 */
private class RepositoryInboundStore(
    private val messages: MessageRepository,
    private val peers: PeerRepository,
    private val deletedMessages: DeletedMessageStore,
    private val attachments: AttachmentStore,
    private val peerTimers: PeerTimerStore,
) : InboundStore {
    override fun isDeleted(messageId: String): Boolean = deletedMessages.isDeleted(messageId)

    override suspend fun isBlocked(peerHex: String): Boolean = peers.isBlocked(peerHex)

    override suspend fun recordSeen(
        peerHex: String,
        displayName: String?,
        publicKey: ByteArray?,
        agreementKey: ByteArray?,
    ) {
        peers.recordSeen(
            peerHex,
            displayName = displayName,
            publicKey = publicKey,
            agreementKey = agreementKey,
        )
    }

    override suspend fun findMessage(messageId: String): MessageEntity? = messages.findMessage(messageId)

    override suspend fun recordIncoming(
        messageId: String,
        conversationId: String,
        senderId: String,
        body: String,
        timestamp: Long,
        attachmentFile: String?,
    ): Boolean = messages.recordIncoming(
        messageId = messageId,
        conversationId = conversationId,
        senderId = senderId,
        body = body,
        timestamp = timestamp,
        peerId = senderId,
        attachmentFile = attachmentFile,
    )

    override suspend fun markDelivered(messageId: String, hops: Int?) {
        messages.markDelivered(messageId, hops)
    }

    override suspend fun writeImage(messageId: String, jpeg: ByteArray): String? =
        attachments.write(messageId, jpeg)

    override suspend fun peerTimer(peerHex: String): PeerTimerStamp? = peerTimers.get(peerHex)

    override suspend fun applyPeerTimer(peerHex: String, millis: Long?, stamp: PeerTimerStamp) {
        messages.setDisappearAfter(directConversationId(peerHex), peerHex, millis)
        peerTimers.put(peerHex, stamp)
    }
}

/** The inbound path's diagnostics, under the coordinator's tag as before the split. */
private object AndroidInboundLog : InboundLog {
    private const val TAG = "MeshCoordinator"

    override fun debug(message: String) {
        Log.d(TAG, message)
    }

    override fun info(message: String) {
        Log.i(TAG, message)
    }

    override fun warn(message: String) {
        Log.w(TAG, message)
    }
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
    private val attachments: AttachmentStore,
    private val transfers: TransferTracker,
    private val displayNames: DisplayNameStore,
    private val deletedMessages: DeletedMessageStore,
    private val peerTimers: PeerTimerStore,
    @MeshScope private val scope: CoroutineScope,
) {

    private val started = AtomicBoolean(false)

    /** Ids of failed rows a [resend] is working on right now. */
    private val resending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Which connected endpoints have proved the peer id they advertise. */
    private val links = LinkBindings()

    /**
     * The part of this class that faces the mesh: packets the router delivered
     * become rows, receipts, keys and transfer progress there.
     */
    private val inbound = InboundHandler(
        identity = identity,
        store = RepositoryInboundStore(messages, peers, deletedMessages, attachments, peerTimers),
        keyDirectory = keyDirectory,
        decrypt = cipher::decrypt,
        transfers = transfers,
        originate = { packet -> router.originate(packet) },
        log = AndroidInboundLog,
    )

    /** New messages as they are stored, for notifications; see [InboundHandler.arrivals]. */
    val arrivals: SharedFlow<Arrival> = inbound.arrivals

    /** This device's own id, hex-encoded — the `senderId` on everything it sends. */
    val selfId: String by lazy { identity.peerId.toHexId() }

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
    val observedHops: StateFlow<Map<String, Int>> = inbound.hopObservations
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
        collectLinkProofs()
        expireStalePending()
        pullBackFutureThreads()
        sweepDisappearing()
        collectNameChanges()
    }

    /**
     * Tells the mesh when the user changes their name.
     *
     * Only changes: the name at start goes out with every announcement anyway,
     * so re-sending it here would just put a second copy on the air. Clearing
     * the name sends nothing — there is no "forget my name" packet, and old
     * builds would not understand one. Peers keep the last name they heard until
     * a new one replaces it.
     */
    private fun collectNameChanges() = scope.launch {
        displayNames.displayName.drop(1).collect { name ->
            if (name != null) broadcastProfile(name)
        }
    }

    /** Broadcasts this device's display name, if it has one. */
    private suspend fun broadcastProfile(name: String? = displayNames.displayName.value) {
        val profile = name?.let(Profile::fromInput) ?: return
        val packet = profilePacket(
            messageId = newMessageId().hexIdToBytes(),
            senderId = identity.peerId,
            profile = profile,
            timestamp = System.currentTimeMillis(),
        )
        // Not queued when nobody is in range: PROFILE is not a store-and-forward
        // type, and the next connection sends a fresh one after the announcement.
        runCatching { router.originate(packet) }
            .onFailure { Log.w(TAG, "failed to send profile: ${it.message}") }
    }

    /**
     * Fails PENDING rows the forward queue has given up on, at start and then
     * every [EXPIRY_SWEEP_MILLIS].
     *
     * Periodic rather than only at start, because the service runs for days: a
     * message composed on Monday would otherwise stay `queued` until whenever
     * the process next happened to restart.
     */
    /**
     * Once per start: new activity is stamped by this phone's clock, so only
     * rows written before that, or before this clock was set back, need it.
     */
    private fun pullBackFutureThreads() = scope.launch {
        val repaired = messages.pullBackFutureActivity()
        if (repaired > 0) Log.i(TAG, "$repaired thread(s) had activity in the future")
    }

    private fun expireStalePending() = scope.launch {
        while (true) {
            val expired = messages.expirePendingOutgoing(pendingExpiryCutoff(System.currentTimeMillis()))
            if (expired > 0) Log.i(TAG, "$expired queued message(s) expired undelivered")
            delay(EXPIRY_SWEEP_MILLIS)
        }
    }

    /**
     * Deletes messages whose disappearing timer has run out, at start and then
     * every [DISAPPEAR_SWEEP_MILLIS].
     *
     * A loop in the mesh's own scope rather than a WorkManager job. This scope
     * lives as long as the foreground service, which runs whenever the mesh is
     * on, so a job would only add a dependency to cover the time the app is
     * fully stopped; then the sweep at the next start catches up, and nobody
     * can open the app to read the rows in between.
     *
     * Each one goes the way [deleteLocally] goes: tombstone first, so a relay
     * replaying it from a forward queue cannot bring it back, then the row,
     * then its image.
     */
    private fun sweepDisappearing() = scope.launch {
        while (true) {
            // A failed pass (Room refusing a write on a full disk) is retried on
            // the next tick rather than ending the sweep for the rest of the
            // process: a message that should have gone must not stay for good.
            try {
                val expired = messages.findExpired()
                if (expired.isNotEmpty()) {
                    val ids = expired.map { it.id }
                    deletedMessages.rememberAll(ids)
                    messages.deleteMessages(ids)
                    expired.forEach { row -> row.attachmentFile?.let { attachments.delete(it) } }
                    Log.i(TAG, "${expired.size} disappearing message(s) deleted")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "disappearing sweep failed: ${e::class.simpleName}")
            }
            delay(DISAPPEAR_SWEEP_MILLIS)
        }
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
        keyDirectory.agreementKeyLearned.collect { peerHex ->
            // Caught per key, as in `InboundHandler.handle`: one failure must not
            // end the collector and strand every later peer's held messages.
            try {
                releaseHeld(peerHex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "releasing held messages failed: ${e::class.simpleName}")
            }
        }
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
     * Sets a thread's disappearing timer (null = off) and, in a direct chat,
     * tells the other phone.
     *
     * The local change comes first and stands on its own: the TIMER is a
     * request, honoured by a phone on this build or later and ignored by an
     * older one. It is sealed, so it is not sent at all while we lack the
     * peer's agreement key; the other phone then keeps its own timer, and the
     * next change made after the keys arrive reaches it. Queued like a message
     * when the peer is out of range. The broadcast thread has nobody to tell.
     */
    suspend fun setDisappearing(recipientHexId: String?, millis: Long?) = scope.outliveCaller {
        messages.setDisappearAfter(conversationIdFor(recipientHexId), recipientHexId, millis)
        if (recipientHexId != null) tellTimer(recipientHexId, millis)
    }

    private suspend fun tellTimer(peerHex: String, millis: Long?) {
        val setting = TimerSetting.of(millis) ?: return
        val packet = timerPacket(
            messageId = newMessageId().hexIdToBytes(),
            senderId = identity.peerId,
            recipientId = peerHex.hexIdToBytes(),
            setting = setting,
            timestamp = System.currentTimeMillis(),
        )
        val sealed = sealOrHold(packet) { cipher.encrypt(it.recipientId, it.messageId, it.payload) }
        if (sealed == null) {
            Log.i(TAG, "no key for ${Packet.tag(packet.recipientId)} yet, timer kept on this phone only")
            return
        }
        runCatching { router.originate(sealed) }
            .onFailure { Log.w(TAG, "failed to send timer: ${it.message}") }
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
     *
     * This and the other send entry points run in the mesh scope, not the
     * caller's: see [outliveCaller].
     */
    suspend fun send(body: String, recipientHexId: String?): SendResult =
        scope.outliveCaller { sendText(body, recipientHexId, replaces = null) }

    /**
     * Sends one of our FAILED messages again, as a new message in its place.
     *
     * A fresh id and timestamp rather than a replay of the old ones. Fragment
     * packet ids derive from the message id, so a replayed image would meet
     * every seen-cache in the mesh that took part in the first attempt and be
     * dropped as a duplicate for the next ten minutes — the retry would fail for
     * exactly the devices that heard part of it. The new row takes the old one's
     * place in a single transaction, so the thread shows one message, at the
     * bottom, in its new state.
     *
     * @return [SendResult.Rejected] when [messageId] is not a failed message of
     *   ours, or is already being resent — a double tap would otherwise read the
     *   row as FAILED twice and put two copies in the thread.
     */
    suspend fun resend(messageId: String): SendResult = scope.outliveCaller { resendNow(messageId) }

    private suspend fun resendNow(messageId: String): SendResult {
        if (!resending.add(messageId)) return SendResult.Rejected("That message is already being sent again.")
        try {
            val row = messages.findMessage(messageId)
                ?: return SendResult.Rejected("That message is no longer here.")
            val target = resendTarget(row)
                ?: return SendResult.Rejected("Only a message that failed can be sent again.")

            val attachment = row.attachmentFile
                ?: return sendText(row.body, target.recipientHexId, replaces = row.id)

            val jpeg = attachments.read(attachment)
                ?: return SendResult.Rejected("The image for that message is no longer stored.")
            val result = sendImageNow(jpeg, target.recipientHexId, replaces = row.id)
            // Keyed on the old row being gone rather than on the result: the new
            // row stores its own copy under its own id, and some failures land
            // after the swap has already happened.
            if (messages.findMessage(row.id) == null) attachments.delete(attachment)
            return result
        } finally {
            resending.remove(messageId)
        }
    }

    /**
     * Deletes [messageId] from this phone only; anyone who received it keeps it.
     *
     * The tombstone is written before the row goes, so a relay replaying the
     * message between the two writes still finds it refused.
     *
     * @return false when the message is gone already or [canDeleteLocally]
     *   refuses it.
     */
    suspend fun deleteLocally(messageId: String): Boolean {
        val row = messages.findMessage(messageId) ?: return false
        if (!canDeleteLocally(row)) return false
        deletedMessages.remember(messageId)
        val removed = messages.deleteMessage(messageId) ?: return false
        removed.attachmentFile?.let { attachments.delete(it) }
        return true
    }

    /**
     * Empties a thread on this phone only; see [chatDeletion] for what stays.
     *
     * The thread row itself is kept, so its disappearing timer survives: someone
     * who set one and then cleared the chat expects new messages to keep
     * disappearing. Tombstones first, as in [deleteLocally], so relays cannot
     * replay the history back in.
     *
     * @return how many messages were deleted.
     */
    suspend fun deleteChat(recipientHexId: String?): Int {
        val doomed = chatDeletion(messages.findIn(conversationIdFor(recipientHexId)))
        if (doomed.isEmpty()) return 0
        val ids = doomed.map { it.id }
        deletedMessages.rememberAll(ids)
        messages.deleteMessages(ids)
        doomed.forEach { row -> row.attachmentFile?.let { attachments.delete(it) } }
        Log.i(TAG, "${doomed.size} message(s) deleted with their chat")
        return doomed.size
    }

    /**
     * Removes [peerHex] from this phone: its thread with every message in it,
     * the thread's timer, the name, nickname and verification on its row, and
     * our messages to it still waiting to go, which are withdrawn from the
     * forward queue rather than left to send after their rows are gone.
     *
     * The key directory keeps the peer's keys, on purpose. A peer id is the hash
     * of its signing key and an ANNOUNCE is signed by the key it carries, so no
     * one but the peer can file different keys under that id; forgetting them
     * would protect nothing, and would make the peer's packets that this phone
     * relays unverifiable until it announced again. A peer still in range
     * announces within seconds and comes back as a new, unverified contact with
     * an empty thread, which is the honest outcome.
     *
     * Runs in the mesh scope, so leaving the chat straight after does not
     * strand it halfway.
     *
     * @return false when the peer is unknown or [canForget] refuses it.
     */
    suspend fun forgetPeer(peerHex: String): Boolean = scope.outliveCaller {
        if (!canForget(peers.findPeer(peerHex))) return@outliveCaller false
        val conversationId = conversationIdFor(peerHex)
        val rows = messages.findIn(conversationId)
        val ids = rows.map { it.id }
        // Tombstones first, as in deleteChat, so relays cannot replay the history back in.
        deletedMessages.rememberAll(ids)
        router.cancelQueued(
            rows.filter { it.isOutgoing && it.deliveryState == DeliveryState.PENDING }
                .map { it.id.hexIdToBytes() },
        )
        messages.deleteConversation(conversationId)
        rows.forEach { row -> row.attachmentFile?.let { attachments.delete(it) } }
        peers.forget(peerHex)
        Log.i(TAG, "Forgot a peer and ${rows.size} message(s)")
        true
    }

    private suspend fun sendText(body: String, recipientHexId: String?, replaces: String?): SendResult {
        if (body.isBlank()) return SendResult.Rejected("empty message")
        if (!MessagePacket.isSendable(body)) {
            return SendResult.Rejected(
                "message is ${MessagePacket.bodyByteCount(body)} bytes, " +
                    "over the ${MessagePacket.MAX_TEXT_BYTES} byte limit",
            )
        }
        if (!MessagePacket.fits(body)) return sendLongText(body, recipientHexId, replaces)

        val conversationId = conversationIdFor(recipientHexId)
        val row = messages.recordOutgoing(
            conversationId = conversationId,
            selfId = selfId,
            body = body,
            peerId = recipientHexId,
            replaces = replaces,
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
    suspend fun sendImage(jpeg: ByteArray, recipientHexId: String?): SendResult =
        scope.outliveCaller { sendImageNow(jpeg, recipientHexId, replaces = null) }

    private suspend fun sendImageNow(
        jpeg: ByteArray,
        recipientHexId: String?,
        replaces: String?,
    ): SendResult {
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
            replaces = replaces,
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
    private suspend fun sendLongText(
        body: String,
        recipientHexId: String?,
        replaces: String?,
    ): SendResult {
        val row = messages.recordOutgoing(
            conversationId = conversationIdFor(recipientHexId),
            selfId = selfId,
            body = body,
            peerId = recipientHexId,
            replaces = replaces,
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
     *
     * Cancelled partway (the mesh scope itself is going away), the row is
     * marked FAILED: fragments are never queued, so it would otherwise sit as
     * PENDING, shown as `queued`, for a message that can no longer go out.
     */
    private suspend fun transmitFragments(messageId: String, fragments: List<Packet>): SendResult {
        transfers.start(messageId, fragments.size, isOutgoing = true)
        Log.i(TAG, "sending ${fragments.size} fragments")

        return onCancellation(
            onCancel = {
                transfers.fail(messageId)
                messages.markFailed(messageId)
            },
        ) {
            fragments.forEachIndexed { index, fragment ->
                if (!router.originate(fragment)) {
                    // Nobody in range. Fragments are not queued, so the rest of this
                    // message would be shouting into an empty room.
                    Log.w(TAG, "transfer stalled at fragment $index/${fragments.size}, no peers")
                    transfers.fail(messageId)
                    messages.markFailed(messageId)
                    return@onCancellation SendResult.Failed("Nobody was in range for long enough to receive it.")
                }
                transfers.advance(messageId, index + 1)
                if (index < fragments.lastIndex) delay(FRAGMENT_SPACING_MILLIS)
            }

            transfers.complete(messageId)
            messages.markSent(messageId)
            SendResult.Sent
        }
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

    /** Delivered packets go to the inbound path; see [InboundHandler]. */
    private fun collectIncoming() = scope.launch {
        router.delivered.collect { packet -> inbound.handle(packet) }
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
     * Each newly connected endpoint is greeted, and offered what the forward
     * queue holds for it once it has proved who it is.
     *
     * Per-peer rather than the old edge-triggered "mesh became non-empty" flush:
     * the queue is keyed by recipient, so the useful moment is when *that*
     * recipient appears, not when any peer at all does.
     *
     * The endpoint's name is only a claim, so connecting no longer makes a peer
     * "online" or drains its queue (anyone can advertise a contact's id). Its
     * signed ANNOUNCE arriving on the same link does both: the announcement
     * marks it seen in [recordAnnouncement], and [collectLinkProofs] offers the
     * queue. See [LinkBindings].
     */
    private fun collectPeers() = scope.launch {
        val greeted = mutableSetOf<String>()
        transport.connectedPeers.collect { endpoints ->
            // First, so an endpoint that left and came back must prove itself
            // again on its new link.
            links.retain(endpoints)
            endpoints.forEach { endpointId ->
                val peerId = transport.peerIdFor(endpointId) ?: return@forEach

                // Only on the transition into being connected. Without this a
                // peer count going 1 -> 2 would re-greet the peer that was
                // already there.
                if (greeted.add(endpointId)) {
                    // Introduce ourselves first, so the peer can verify what
                    // follows rather than filing it as from an unknown sender.
                    announceSelf()
                    // After the announcement, so the peer holds our key by the
                    // time the signed name arrives and can verify it.
                    broadcastProfile()
                    if (links.claim(endpointId, peerId)) offerQueued(peerId, endpointId)
                }
            }

            // Endpoints that went away become eligible again, so a peer that
            // reconnects later gets a fresh greeting.
            greeted.retainAll(endpoints)
        }
    }

    /** A link that proves the id it claimed gets that id's queued packets. */
    private fun collectLinkProofs() = scope.launch {
        router.linkProofs.collect { proof ->
            if (links.prove(proof)) offerQueued(proof.peerId, proof.endpointId)
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

        /**
         * Why a long message or image to a not-yet-introduced peer failed.
         * Fragments cannot be held for later the way single packets are.
         */
        const val NO_KEY_FOR_FRAGMENTS =
            "No encryption key for this person yet. Try again once they have been " +
                "in range, or pair by QR code."

        /**
         * How often queued rows are checked against the queue's lifetime.
         *
         * A message can read `queued` for up to this long after the queue has
         * dropped it. Fifteen minutes on a 24 hour lifetime is a rounding error
         * for the user and a negligible wake-up for the battery.
         */
        const val EXPIRY_SWEEP_MILLIS: Long = 15 * 60 * 1000L

        /**
         * How often disappearing messages are looked for. A message can outlive
         * its timer by up to this long; a minute is invisible on the shortest
         * timer offered (an hour) and the query is one indexed lookup.
         */
        const val DISAPPEAR_SWEEP_MILLIS: Long = 60 * 1000L

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
