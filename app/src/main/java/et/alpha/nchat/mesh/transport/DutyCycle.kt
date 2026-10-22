package et.alpha.nchat.mesh.transport

/**
 * How long the radio scans and how long it rests between scans.
 *
 * Continuous discovery is the single most expensive thing this app can do to a
 * battery, and it buys almost nothing: peers are either nearby for minutes at a
 * time or not there at all, so sampling finds them nearly as fast for a fraction
 * of the power. The cycle widens further once it becomes clear nobody is around.
 *
 * Pure Kotlin with an injected clock, so the schedule is unit-testable on the
 * JVM without waiting out a single real second.
 */
class DutyCycle(
    /** How long each scan lasts. */
    val scanMillis: Long = DEFAULT_SCAN_MILLIS,
    /** Rest between scans while peers are still turning up. */
    private val activeIdleMillis: Long = DEFAULT_ACTIVE_IDLE_MILLIS,
    /** Total cycle length once backed off; the rest period is this minus the scan. */
    private val backoffPeriodMillis: Long = DEFAULT_BACKOFF_PERIOD_MILLIS,
    /** How long with nothing found before widening the cycle. */
    private val backoffAfterMillis: Long = DEFAULT_BACKOFF_AFTER_MILLIS,
    /** Total cycle length in saver mode while peers are still turning up. */
    private val saverPeriodMillis: Long = DEFAULT_SAVER_PERIOD_MILLIS,
    /** Total cycle length in saver mode once backed off. */
    private val saverBackoffPeriodMillis: Long = DEFAULT_SAVER_BACKOFF_PERIOD_MILLIS,
    private val timeSource: () -> Long = System::currentTimeMillis,
) {

    init {
        require(scanMillis > 0) { "scan must be positive, was $scanMillis" }
        require(activeIdleMillis > 0) { "idle must be positive, was $activeIdleMillis" }
        require(backoffPeriodMillis > scanMillis) {
            "backoff period ($backoffPeriodMillis) must leave room for a ${scanMillis}ms scan"
        }
        require(saverPeriodMillis > scanMillis && saverBackoffPeriodMillis > scanMillis) {
            "saver periods must leave room for a ${scanMillis}ms scan"
        }
    }

    /**
     * True for the slow schedule (see [BatteryMode]).
     *
     * Only the rest between scans changes, never the scan itself: a shorter scan
     * would save little and miss phones that need the whole window to be found,
     * so saver mode finds people as reliably, just less often. It takes effect
     * from the next rest, so a switch never cuts a scan short.
     */
    @Volatile
    var isSaving: Boolean = false

    @Volatile
    private var lastFoundAt: Long = timeSource()

    /**
     * Records that a peer turned up, returning the search to the fast cycle.
     *
     * Deliberately driven by *discovery*, not by the current connection count: a
     * device sitting in a stable two-peer mesh with nothing new appearing for
     * five minutes should still back off. It has already found everyone there is
     * to find, and scanning harder will not conjure a third phone.
     */
    fun onPeerFound() {
        lastFoundAt = timeSource()
    }

    /** True once nothing has been found for long enough to widen the cycle. */
    fun isBackedOff(): Boolean = timeSource() - lastFoundAt >= backoffAfterMillis

    /**
     * How long to rest before the next scan.
     *
     * The backed-off value is derived by subtracting the scan from the total
     * period, so "back off to 60 second intervals" means a scan starts every 60
     * seconds — not that the radio rests for 60 seconds on top of a 5 second scan.
     */
    fun idleMillis(): Long = when {
        isSaving && isBackedOff() -> saverBackoffPeriodMillis - scanMillis
        isSaving -> saverPeriodMillis - scanMillis
        isBackedOff() -> backoffPeriodMillis - scanMillis
        else -> activeIdleMillis
    }

    /** Full cycle length, scan included. Useful for logging and tests. */
    fun periodMillis(): Long = scanMillis + idleMillis()

    companion object {
        const val DEFAULT_SCAN_MILLIS: Long = 5_000
        const val DEFAULT_ACTIVE_IDLE_MILLIS: Long = 25_000
        const val DEFAULT_BACKOFF_PERIOD_MILLIS: Long = 60_000
        const val DEFAULT_BACKOFF_AFTER_MILLIS: Long = 5 * 60_000

        /**
         * Saver mode scans every minute, then every three once nobody new turns
         * up: roughly half and a third of the normal radio time. A message to
         * someone who walks into range waits up to a minute longer to be found,
         * which is the trade the user asked for.
         */
        const val DEFAULT_SAVER_PERIOD_MILLIS: Long = 60_000
        const val DEFAULT_SAVER_BACKOFF_PERIOD_MILLIS: Long = 3 * 60_000
    }
}
