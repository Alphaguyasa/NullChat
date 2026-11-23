package et.alpha.nchat.ui.chat

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import et.alpha.nchat.R

/** [TimerWords] read from strings.xml, so plurals follow the user's language. */
internal class ResourceTimerWords(private val resources: Resources) : TimerWords {
    override fun off(): String = resources.getString(R.string.timer_off)
    override fun minutes(n: Int): String = resources.getQuantityString(R.plurals.timer_minutes, n, n)
    override fun hours(n: Int): String = resources.getQuantityString(R.plurals.timer_hours, n, n)
    override fun days(n: Int): String = resources.getQuantityString(R.plurals.timer_days, n, n)
    override fun weeks(n: Int): String = resources.getQuantityString(R.plurals.timer_weeks, n, n)

    override fun unnamedContact(): String = resources.getString(R.string.timer_line_unnamed_contact)
    override fun youTurnedOff(): String = resources.getString(R.string.timer_line_you_off)
    override fun peerTurnedOff(name: String): String = resources.getString(R.string.timer_line_peer_off, name)
    override fun youSet(duration: String): String = resources.getString(R.string.timer_line_you_set, duration)
    override fun peerSet(name: String, duration: String): String =
        resources.getString(R.string.timer_line_peer_set, name, duration)
}

/**
 * The timer words for the current configuration.
 *
 * The words are looked up on each call, not cached, and [LocalResources]
 * recomposes its readers on a configuration change, so a language change
 * while the chat is open shows the new words.
 */
@Composable
internal fun rememberTimerWords(): TimerWords {
    val resources = LocalResources.current
    return remember(resources) { ResourceTimerWords(resources) }
}
