package et.alpha.nchat.ui.chat

/**
 * The disappearing-message timers a thread can be set to, and how each reads.
 *
 * A short fixed list rather than a free choice: four options fit one dialog at
 * 200% font scale, and an hour is the shortest that the one-minute sweep can
 * honour without anyone noticing the lag.
 */
internal object DisappearingTimer {

    private const val MINUTE: Long = 60 * 1000L
    private const val HOUR: Long = 60 * MINUTE
    private const val DAY: Long = 24 * HOUR
    private const val WEEK: Long = 7 * DAY

    /** Null is "off": messages stay until the user deletes them. */
    val OPTIONS: List<Long?> = listOf(null, HOUR, DAY, WEEK)

    /**
     * How [millis] reads, in the largest whole unit that fits.
     *
     * The unit choice lives here so it can be tested on the JVM; the words
     * come from [words], which is strings.xml in the app.
     */
    fun label(millis: Long?, words: TimerWords): String = when {
        millis == null -> words.off()
        // Not all of these are offered, but a value this build did not write
        // should still read as something true rather than as "Off".
        millis % WEEK == 0L -> words.weeks(count(millis / WEEK))
        millis % DAY == 0L -> words.days(count(millis / DAY))
        millis % HOUR == 0L -> words.hours(count(millis / HOUR))
        else -> words.minutes(count(millis / MINUTE))
    }

    /** Plural lookups take an Int; a timer that large is not a real setting. */
    private fun count(n: Long): Int = n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/**
 * The words the timer label and the timer lines are made of.
 *
 * An interface so the choice of unit and of sentence stays pure Kotlin and
 * testable on the JVM, while the app reads every word from strings.xml
 * through [ResourceTimerWords]. Each sentence is whole rather than glued
 * from "You" plus a verb, because other languages agree the verb with the
 * subject.
 */
internal interface TimerWords {
    fun off(): String
    fun minutes(n: Int): String
    fun hours(n: Int): String
    fun days(n: Int): String
    fun weeks(n: Int): String

    /** The peer's name for a timer line when they have none. */
    fun unnamedContact(): String
    fun youTurnedOff(): String
    fun peerTurnedOff(name: String): String
    fun youSet(duration: String): String
    fun peerSet(name: String, duration: String): String
}
