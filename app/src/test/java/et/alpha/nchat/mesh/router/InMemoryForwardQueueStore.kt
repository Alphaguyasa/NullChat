package et.alpha.nchat.mesh.router

/**
 * A settable clock, so expiry is tested without a single real millisecond.
 *
 * Implements `() -> Long` directly so it can be passed as `timeSource = clock`
 * with no lambda wrapper, the same shape `SeenCacheTest` uses.
 */
class FakeClock(var now: Long = 0L) : () -> Long {
    override fun invoke(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

/**
 * A [ForwardQueueStore] held in a map, standing in for the Room-backed one.
 *
 * Ordering here deliberately mirrors `ForwardQueueDao`'s SQL — `queuedAt` then
 * `messageId`, ascending for reads and descending for eviction — because the
 * cap and expiry tests assert *which* entry was dropped. A fake that ordered
 * differently would pass while the real store evicted the wrong packet.
 *
 * The DAO's behaviour against real SQLite is covered separately by
 * `ForwardQueueDaoTest`, which is an instrumented test.
 */
class InMemoryForwardQueueStore : ForwardQueueStore {

    private val entries = LinkedHashMap<String, QueuedPacket>()

    override suspend fun add(entry: QueuedPacket): Boolean {
        val key = entry.packet.messageId.hex()
        // Mirrors OnConflictStrategy.IGNORE: a repeat offer is a no-op, not an
        // overwrite, so a re-queued packet keeps its original queuedAt.
        if (entries.containsKey(key)) return false
        entries[key] = entry
        return true
    }

    override suspend fun forRecipients(recipientIds: List<ByteArray>): List<QueuedPacket> {
        val wanted = recipientIds.map { it.hex() }.toSet()
        return entries.values
            .filter { it.packet.recipientId.hex() in wanted }
            .sortedWith(compareBy({ it.queuedAt }, { it.packet.messageId.hex() }))
    }

    override suspend fun newest(limit: Int): List<QueuedPacket> =
        entries.values
            .sortedWith(compareByDescending<QueuedPacket> { it.queuedAt }
                .thenByDescending { it.packet.messageId.hex() })
            .take(limit)

    override suspend fun remove(messageIds: List<ByteArray>): Int {
        val keys = messageIds.map { it.hex() }
        return keys.count { entries.remove(it) != null }
    }

    override suspend fun removeQueuedAtOrBefore(cutoff: Long): Int {
        val doomed = entries.filterValues { it.queuedAt <= cutoff }.keys.toList()
        doomed.forEach { entries.remove(it) }
        return doomed.size
    }

    override suspend fun trimToNewest(capacity: Int): Int {
        if (entries.size <= capacity) return 0
        val doomed = entries.values
            .sortedWith(compareByDescending<QueuedPacket> { it.queuedAt }
                .thenByDescending { it.packet.messageId.hex() })
            .drop(capacity)
            .map { it.packet.messageId.hex() }
        doomed.forEach { entries.remove(it) }
        return doomed.size
    }

    override suspend fun count(): Int = entries.size

    override suspend fun clear() = entries.clear()

    /** Whether a packet with this id is currently held. */
    fun holds(messageId: ByteArray): Boolean = entries.containsKey(messageId.hex())

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
