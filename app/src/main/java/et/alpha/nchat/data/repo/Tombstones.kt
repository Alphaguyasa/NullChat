package et.alpha.nchat.data.repo

/**
 * Ids of messages the user deleted, remembered long enough to refuse replays.
 *
 * Deleting a row is not enough on a flooding mesh. Relays hold a message in
 * their forward queue for 24 hours and offer it to every phone they meet, and
 * the router's seen-cache forgets an id after 10 minutes, so a deleted message
 * would come straight back the next time a relay walks into range. A tombstone
 * is what makes "delete" stick.
 *
 * Kept for [TTL_MILLIS], well past one forward-queue lifetime because a relay
 * that queues the message late in its own 24 hours restarts the clock for the
 * next hop. Capped at [maxEntries], oldest dropped first, so a user deleting
 * a huge history cannot grow this without bound; past that a very old
 * deletion may come back, which is the cheaper failure.
 *
 * Pure Kotlin and not thread-safe; [DeletedMessageStore] owns the locking and
 * the persistence.
 */
class Tombstones private constructor(
    // Insertion order is deletion order, which is what the cap evicts by.
    private val entries: LinkedHashMap<String, Long>,
    private val maxEntries: Int,
) {

    constructor(maxEntries: Int = MAX_ENTRIES) : this(LinkedHashMap(), maxEntries)

    init {
        require(maxEntries > 0) { "cap must be positive, was $maxEntries" }
    }

    val size: Int get() = entries.size

    fun add(messageId: String, now: Long) {
        // Re-inserted rather than updated so a second delete moves it to the back.
        entries.remove(messageId)
        entries[messageId] = now
        prune(now)
    }

    /**
     * Whether [messageId] was deleted and is still within its lifetime.
     *
     * A deletion stamped after [now] counts as live: the device clock moved
     * back, and the safe reading is that the user deleted it recently.
     */
    fun contains(messageId: String, now: Long): Boolean {
        val deletedAt = entries[messageId] ?: return false
        return now - deletedAt < TTL_MILLIS
    }

    fun prune(now: Long) {
        entries.values.removeAll { deletedAt -> now - deletedAt >= TTL_MILLIS }
        trimToCap()
    }

    private fun trimToCap() {
        val excess = entries.size - maxEntries
        if (excess > 0) {
            val oldest = entries.keys.take(excess)
            oldest.forEach(entries::remove)
        }
    }

    /** `id:deletedAt` pairs separated by `;`, oldest first. */
    fun encode(): String = entries.entries.joinToString(SEPARATOR) { (id, at) -> "$id$FIELD$at" }

    companion object {
        const val TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
        /**
         * Enough for deleting a chat of 5000 received messages at once (our own
         * need no tombstone, see `tombstoneIds`). About 47 bytes an entry, so a
         * full set is roughly 235 KB of preferences, rewritten on each delete off
         * the main thread. Was 1000, which let the older part of a long chat
         * come back from relays' queues within a day.
         */
        const val MAX_ENTRIES: Int = 5000

        private const val SEPARATOR = ";"
        private const val FIELD = ":"

        /**
         * Reads what [encode] wrote. Malformed entries are skipped rather than
         * failing the whole set: losing one tombstone lets one message back,
         * losing all of them lets every one back.
         */
        fun decode(encoded: String?, maxEntries: Int = MAX_ENTRIES): Tombstones {
            val entries = LinkedHashMap<String, Long>()
            encoded?.split(SEPARATOR)?.forEach { entry ->
                val id = entry.substringBefore(FIELD, missingDelimiterValue = "")
                val at = entry.substringAfter(FIELD, missingDelimiterValue = "").toLongOrNull()
                if (id.isNotEmpty() && at != null) entries[id] = at
            }
            // Pruned to the cap at once, so a set written under a larger cap
            // never sits over this one until the next deletion.
            return Tombstones(entries, maxEntries).also { it.trimToCap() }
        }
    }
}
