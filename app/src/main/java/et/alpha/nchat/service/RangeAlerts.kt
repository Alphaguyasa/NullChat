package et.alpha.nchat.service

/**
 * Decides when "this contact is in range" should notify (roadmap #77).
 *
 * Fed every change of the coordinator's bound peers, the only honest source of
 * "in range": a link that proved its peer id. A peer that merely shows up in
 * Nearby's endpoint list, or claims an id it cannot sign for, never alerts.
 *
 * An alert fires when a watched peer *joins* the bound set, not while it stays
 * in it, so being in range for an hour is one notification, not sixty. Links
 * drop and come back as people walk around a building, and each return is a
 * join; [minGapMillis] keeps that from turning into a notification every few
 * seconds. The gap is per peer, so one flapping contact cannot hold back
 * another's alert.
 *
 * Times are from a monotonic clock (`SystemClock.elapsedRealtime`) so a user
 * changing the wall clock can neither silence nor repeat alerts.
 *
 * Not thread-safe; the service calls it from one collector.
 */
class RangeAlerts(private val minGapMillis: Long = DEFAULT_MIN_GAP_MILLIS) {

    private var previous: Set<String> = emptySet()
    private val lastAlertAt = mutableMapOf<String, Long>()

    /**
     * Returns the peers to notify about now that [bound] is the set of bound
     * peers, given the [watched] contacts and the monotonic time [now].
     */
    fun onBoundPeers(bound: Set<String>, watched: Set<String>, now: Long): List<String> {
        val joined = bound - previous
        previous = bound
        // Forgotten or unwatched contacts drop out, so the map cannot grow past
        // the watch list however many strangers pass by.
        lastAlertAt.keys.retainAll(watched)
        return joined
            .filter { it in watched }
            .filter { peer -> lastAlertAt[peer]?.let { now - it >= minGapMillis } ?: true }
            .sorted()
            .onEach { lastAlertAt[it] = now }
    }

    companion object {
        /** Thirty minutes: long enough to ride out a walk past a wall, short enough for "back again". */
        const val DEFAULT_MIN_GAP_MILLIS = 30 * 60 * 1000L
    }
}
