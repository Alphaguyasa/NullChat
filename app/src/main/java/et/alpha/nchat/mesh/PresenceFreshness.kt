package et.alpha.nchat.mesh

import et.alpha.nchat.mesh.protocol.PacketType

/**
 * Tells a live ANNOUNCE or PROFILE from an old signed copy played back later.
 *
 * Both are signed by their sender, so a relay cannot forge one, but it can keep
 * a copy and send it again once the router's seen-cache (10 minutes) has
 * forgotten its id. Taken at face value, a replay makes a contact who left hours
 * ago look "heard now", resets the stranger prune that keys off the same
 * timestamp, and puts back a display name the peer has since changed.
 *
 * The rule compares the sender's own clock with itself: per sender and type,
 * only a packet newer than the newest one already accepted counts. That needs no
 * agreement between two phones' clocks, which on a mesh with no network time
 * and no SIM cannot be assumed. The one exception is a packet that is current by
 * *our* clock (within [FRESH_MILLIS] either way): a replay that recent is already
 * inside the seen-cache's reach, and it lets a peer whose clock was set back
 * (someone correcting a wrong date) keep being heard instead of going silent
 * until the clock catches up with its old future-dated packets.
 *
 * In memory only, bounded, least recently heard dropped first: after a restart,
 * or for a sender pushed out by [maxSenders] others, the first copy is believed,
 * which is the behaviour before this class existed. The next live packet from
 * that sender then sets things right, because it is newer.
 *
 * Not thread-safe; the one inbound collector is its only caller.
 */
internal class PresenceFreshness(private val maxSenders: Int = DEFAULT_MAX_SENDERS) {

    private val newest = object : LinkedHashMap<Key, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Long>): Boolean =
            size > maxSenders
    }

    /**
     * True when a packet of [type] from [senderHex], stamped [timestamp] by its
     * sender, may update what we know about that sender. Records it when so.
     */
    fun accept(senderHex: String, type: PacketType, timestamp: Long, now: Long): Boolean {
        val key = Key(senderHex, type)
        val previous = newest[key]
        val current = kotlin.math.abs(now - timestamp) <= FRESH_MILLIS
        if (previous != null && timestamp <= previous && !current) return false
        newest[key] = if (previous == null) timestamp else maxOf(previous, timestamp)
        return true
    }

    private data class Key(val senderHex: String, val type: PacketType)

    companion object {
        /** Matches the router's seen-cache window; see the class comment. */
        const val FRESH_MILLIS: Long = 10 * 60 * 1000L

        /** Two entries per peer (ANNOUNCE and PROFILE), so about 512 peers. */
        const val DEFAULT_MAX_SENDERS = 1024
    }
}
