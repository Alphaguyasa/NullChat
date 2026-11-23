package et.alpha.nchat.ui.chat

/**
 * The English strings.xml wording, for JVM tests that cannot load resources.
 *
 * Kept word for word with values/strings.xml; `TimerWordsResourceTest` on the
 * device checks the real resources say the same.
 */
internal object EnglishTimerWords : TimerWords {
    private fun plural(n: Int, unit: String) = if (n == 1) "$n $unit" else "$n ${unit}s"

    override fun off() = "Off"
    override fun minutes(n: Int) = plural(n, "minute")
    override fun hours(n: Int) = plural(n, "hour")
    override fun days(n: Int) = plural(n, "day")
    override fun weeks(n: Int) = plural(n, "week")

    override fun unnamedContact() = "Your contact"
    override fun youTurnedOff() = "You turned off disappearing messages"
    override fun peerTurnedOff(name: String) = "$name turned off disappearing messages"
    override fun youSet(duration: String) = "You set messages to disappear after $duration"
    override fun peerSet(name: String, duration: String) = "$name set messages to disappear after $duration"
}
