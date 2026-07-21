package et.alpha.nchat.mesh.router

/**
 * The dedup memory that keeps flooding from turning into a broadcast storm:
 * an LRU of message ids, capped at [DEFAULT_CAPACITY] entries with a
 * [DEFAULT_EXPIRY_MILLIS] lifetime.
 *
 * Pure Kotlin and clock-injected, so expiry is testable without sleeping.
 */
class SeenCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val expiryMillis: Long = DEFAULT_EXPIRY_MILLIS,
    private val timeSource: () -> Long = System::currentTimeMillis,
) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
        require(expiryMillis > 0) { "expiry must be positive, was $expiryMillis" }
    }

    /**
     * Insertion-ordered, not access-ordered, on purpose. An id is only ever
     * written once — a repeat sighting is a dedup hit that leaves the entry
     * untouched — so insertion order *is* recency order here, and it keeps the
     * map's iteration order aligned with the timestamps. That alignment is what
     * lets [sweepExpired] stop at the first live entry instead of scanning all
     * thousand on every packet.
     */
    private val entries = object : LinkedHashMap<MessageId, Long>(INITIAL_BUCKETS, LOAD_FACTOR) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MessageId, Long>): Boolean =
            size > capacity
    }

    /**
     * Records [messageId] and reports whether it is new.
     *
     * Check-and-insert is one atomic step by design: a separate `contains` then
     * `add` is a race the moment two transports deliver the same flooded packet
     * concurrently, and losing that race means forwarding a duplicate.
     *
     * @return true if this is the first sighting (caller should process it),
     *   false if it has been seen and must be dropped.
     */
    @Synchronized
    fun markSeen(messageId: ByteArray): Boolean {
        val now = timeSource()
        sweepExpired(now)
        val key = MessageId(messageId)
        if (entries.containsKey(key)) return false
        entries[key] = now
        return true
    }

    /** Whether [messageId] is currently remembered. Does not record anything. */
    @Synchronized
    fun contains(messageId: ByteArray): Boolean {
        sweepExpired(timeSource())
        return entries.containsKey(MessageId(messageId))
    }

    /** Number of live entries, after expiring anything stale. */
    @get:Synchronized
    val size: Int
        get() {
            sweepExpired(timeSource())
            return entries.size
        }

    @Synchronized
    fun clear() = entries.clear()

    /**
     * Drops entries older than the expiry window. Lazy — there is no background
     * timer — and cheap, because insertion order means the first entry still
     * inside the window ends the scan.
     */
    private fun sweepExpired(now: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value < expiryMillis) return
            iterator.remove()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 1000
        const val DEFAULT_EXPIRY_MILLIS: Long = 10 * 60 * 1000L

        private const val INITIAL_BUCKETS = 16
        private const val LOAD_FACTOR = 0.75f
    }
}

/**
 * A message id usable as a map key.
 *
 * `ByteArray` hashes and compares by identity, so keying the cache on the raw
 * array would mean every decoded packet looked brand new and the mesh would
 * flood forever. The bytes are copied because the caller's array is not ours to
 * trust for the lifetime of the entry.
 */
private class MessageId(bytes: ByteArray) {
    private val bytes: ByteArray = bytes.copyOf()
    private val hash: Int = this.bytes.contentHashCode()

    override fun equals(other: Any?): Boolean =
        this === other || (other is MessageId && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = hash
}
