package et.alpha.nchat.mesh.router

/**
 * A token bucket per link, so one neighbour cannot make this phone decode,
 * verify, cache and relay without limit.
 *
 * Each link starts full with [capacity] packets and earns [refillPerSecond] more
 * each second, up to [capacity]. The capacity is sized for the largest burst a
 * well-behaved neighbour sends: its whole forward queue (500) plus the packets
 * it carries for others, all handed over the moment it connects. The refill is
 * well above anything people typing produce, and an image's ~300 fragments fit
 * in the burst; it is a ceiling for a modified phone, not a pacing rule.
 *
 * Keyed by link-layer endpoint id, not by the packet's sender: a flood names any
 * sender it likes, but it can only arrive on the links it actually has.
 *
 * Pure Kotlin and clock-injected (milliseconds, monotonic in production) so
 * refill is testable without sleeping.
 */
class LinkRateLimiter(
    private val capacity: Double = DEFAULT_CAPACITY,
    private val refillPerSecond: Double = DEFAULT_REFILL_PER_SECOND,
    private val timeSource: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    init {
        require(capacity >= 1) { "capacity must be at least 1, was $capacity" }
        require(refillPerSecond > 0) { "refill must be positive, was $refillPerSecond" }
    }

    private class Bucket(var tokens: Double, var updatedAt: Long)

    private val buckets = HashMap<String, Bucket>()

    /** Takes one packet's worth from [endpointId]'s budget. False means drop it. */
    @Synchronized
    fun tryAcquire(endpointId: String): Boolean {
        val now = timeSource()
        val bucket = buckets.getOrPut(endpointId) { Bucket(capacity, now) }
        val elapsed = (now - bucket.updatedAt).coerceAtLeast(0)
        bucket.tokens = minOf(capacity, bucket.tokens + elapsed * refillPerSecond / 1000.0)
        bucket.updatedAt = now
        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        return true
    }

    /**
     * Forgets [endpointId]'s bucket when its link closes, so the map holds only
     * live links. A reconnect starts full again, which costs the attacker a
     * whole Nearby handshake per burst.
     */
    @Synchronized
    fun forget(endpointId: String) {
        buckets.remove(endpointId)
    }

    companion object {
        const val DEFAULT_CAPACITY: Double = 1000.0
        const val DEFAULT_REFILL_PER_SECOND: Double = 50.0
    }
}
