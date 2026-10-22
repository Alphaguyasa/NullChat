package et.alpha.nchat.ui.chat

/**
 * The disappearing-message timers a thread can be set to, and how each reads.
 *
 * A short fixed list rather than a free choice: four options fit one dialog at
 * 200% font scale, and an hour is the shortest that the one-minute sweep can
 * honour without anyone noticing the lag.
 */
internal object DisappearingTimer {

    private const val HOUR: Long = 60 * 60 * 1000L
    private const val DAY: Long = 24 * HOUR

    /** Null is "off": messages stay until the user deletes them. */
    val OPTIONS: List<Long?> = listOf(null, HOUR, DAY, 7 * DAY)

    fun label(millis: Long?): String = when (millis) {
        null -> "Off"
        HOUR -> "1 hour"
        DAY -> "1 day"
        7 * DAY -> "1 week"
        // Not offered, but a value this build did not write should still read
        // as something true rather than as "Off".
        else -> when {
            millis % DAY == 0L -> "${millis / DAY} days"
            millis % HOUR == 0L -> "${millis / HOUR} hours"
            else -> "${millis / 60_000L} minutes"
        }
    }
}
