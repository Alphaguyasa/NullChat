package et.alpha.nchat.mesh.router

import et.alpha.nchat.mesh.protocol.Packet

/**
 * A packet waiting for its recipient to come back into range, with the instant
 * it was queued.
 *
 * [queuedAt] is when the queue accepted it, deliberately not
 * [Packet.timestamp]: the packet's own timestamp comes from whichever device
 * composed it, and a peer with a wrong clock could otherwise queue something
 * that expires immediately or never.
 */
data class QueuedPacket(val packet: Packet, val queuedAt: Long)

/**
 * Persistence for [ForwardQueue], kept as an interface so the queue itself stays
 * pure Kotlin.
 *
 * The mesh must be exercisable on the JVM against a fake topology, which rules
 * out `mesh/router` importing Room — see `ArchitectureRulesTest`. The Room
 * implementation lives in `data/db` and converts to the hex ids the database
 * stores; nothing here handles hex.
 *
 * Implementations are the source of truth. The queue holds no in-memory copy, so
 * what survives a process restart is whatever the store persisted.
 */
interface ForwardQueueStore {

    /** @return true if stored, false if this messageId was already queued. */
    suspend fun add(entry: QueuedPacket): Boolean

    /** Entries addressed to any of [recipientIds], oldest first. */
    suspend fun forRecipients(recipientIds: List<ByteArray>): List<QueuedPacket>

    suspend fun remove(messageIds: List<ByteArray>): Int

    /** Drops entries queued at or before [cutoff]. @return rows deleted. */
    suspend fun removeQueuedAtOrBefore(cutoff: Long): Int

    /** Keeps the [capacity] newest entries, dropping the rest. @return rows deleted. */
    suspend fun trimToNewest(capacity: Int): Int

    suspend fun count(): Int

    suspend fun clear()
}

/**
 * Store-and-forward for packets that had nowhere to go: capped at
 * [DEFAULT_CAPACITY] entries with a [DEFAULT_EXPIRY_MILLIS] lifetime, evicting
 * oldest first.
 *
 * Clock-injected like [SeenCache], so expiry is testable without sleeping for a
 * day. Unlike [SeenCache] this is not a `LinkedHashMap` — the cap and the sweep
 * are the store's job, because entries have to outlive the process.
 *
 * All of it is lazy: expiry is swept when the queue is next touched rather than
 * by a timer, so there is no background coroutine to own or cancel.
 */
class ForwardQueue(
    private val store: ForwardQueueStore,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val expiryMillis: Long = DEFAULT_EXPIRY_MILLIS,
    private val timeSource: () -> Long = System::currentTimeMillis,
) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
        require(expiryMillis > 0) { "expiry must be positive, was $expiryMillis" }
    }

    /**
     * Queues [packet] for a later attempt.
     *
     * Eviction runs after the insert rather than before, so the queue is briefly
     * one over capacity instead of dropping the packet that prompted the write.
     * Oldest-first: the newest [capacity] entries survive.
     *
     * @return true if stored, false if this packet was already queued — flooding
     *   routinely offers the same packet by several paths.
     */
    suspend fun enqueue(packet: Packet): Boolean {
        val now = timeSource()
        sweepExpired(now)
        val stored = store.add(QueuedPacket(packet, now))
        if (stored) store.trimToNewest(capacity)
        return stored
    }

    /**
     * Removes and returns everything waiting for [recipientId], plus anything
     * queued for broadcast, oldest first.
     *
     * Broadcasts come along because they have no specific recipient to wait for:
     * a message composed to the mesh-wide thread with nobody in range would
     * otherwise sit here until it expired, which is exactly the regression that
     * replacing `MeshCoordinator.flushPending` could have introduced.
     *
     * Entries are removed before they are handed back, so a caller that dies
     * mid-flush drops them rather than leaving the queue to re-offer the same
     * packets forever. Anything that fails to send goes back through [restore].
     *
     * Returns [QueuedPacket] rather than bare packets so the original
     * [QueuedPacket.queuedAt] survives a failed flush — see [restore].
     */
    suspend fun takeFor(recipientId: ByteArray): List<QueuedPacket> {
        val now = timeSource()
        sweepExpired(now)

        val entries = store.forRecipients(listOf(recipientId, Packet.BROADCAST_ID))
        if (entries.isEmpty()) return emptyList()

        store.remove(entries.map { it.packet.messageId })
        return entries
    }

    /**
     * Puts back entries a flush could not deliver, keeping their original
     * [QueuedPacket.queuedAt].
     *
     * Preserving the timestamp is the whole point. Re-queuing through [enqueue]
     * would stamp them with the current time, so a packet nobody can ever
     * deliver would have its 24 hours renewed on every reconnect and outlive the
     * expiry rule indefinitely.
     */
    suspend fun restore(entries: List<QueuedPacket>) {
        if (entries.isEmpty()) return
        val now = timeSource()
        sweepExpired(now)
        // An entry that expired while the flush was in flight is not resurrected.
        entries.filter { now - it.queuedAt < expiryMillis }.forEach { store.add(it) }
        store.trimToNewest(capacity)
    }

    /**
     * Drops the entries for [messageIds], whoever they are addressed to.
     *
     * For messages the user withdrew before they went (forgetting a contact).
     * Without it the packet would still go out the next time the recipient
     * came into range, after its row, and every sign it was ever sent, had gone.
     *
     * @return how many entries were dropped.
     */
    suspend fun cancel(messageIds: List<ByteArray>): Int =
        if (messageIds.isEmpty()) 0 else store.remove(messageIds)

    /** Number of live entries, after expiring anything stale. */
    suspend fun size(): Int {
        sweepExpired(timeSource())
        return store.count()
    }

    suspend fun clear() = store.clear()

    /**
     * An entry queued exactly [expiryMillis] ago is expired, matching
     * [SeenCache.sweepExpired]'s `now - queuedAt >= expiryMillis`.
     */
    private suspend fun sweepExpired(now: Long) {
        store.removeQueuedAtOrBefore(now - expiryMillis)
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 500
        const val DEFAULT_EXPIRY_MILLIS: Long = 24 * 60 * 60 * 1000L
    }
}
