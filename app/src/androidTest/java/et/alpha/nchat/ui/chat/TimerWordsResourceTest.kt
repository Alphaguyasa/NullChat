package et.alpha.nchat.ui.chat

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the real strings.xml says what the JVM tests' `EnglishTimerWords`
 * stand-in (in the unit-test source set) assumes, so the unit tests test the
 * shipped wording.
 */
@RunWith(AndroidJUnit4::class)
class TimerWordsResourceTest {

    private val words: TimerWords = run {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val english = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        ResourceTimerWords(context.createConfigurationContext(english).resources)
    }

    @Test
    fun unitsAreSingularForOneAndPluralOtherwise() {
        assertEquals("Off", DisappearingTimer.label(null, words))
        assertEquals(
            listOf("Off", "1 hour", "1 day", "1 week"),
            DisappearingTimer.OPTIONS.map { DisappearingTimer.label(it, words) },
        )
        assertEquals("2 weeks", DisappearingTimer.label(14 * DAY, words))
        assertEquals("3 hours", DisappearingTimer.label(3 * HOUR, words))
        assertEquals("1 minute", DisappearingTimer.label(MINUTE, words))
        assertEquals("90 minutes", DisappearingTimer.label(90 * MINUTE, words))
    }

    @Test
    fun timerLinesMatchTheJvmStandIn() {
        val samples = listOf(
            words.youTurnedOff() to EnglishTimerWordsCopy.youTurnedOff(),
            words.peerTurnedOff("Maya") to EnglishTimerWordsCopy.peerTurnedOff("Maya"),
            words.youSet("1 week") to EnglishTimerWordsCopy.youSet("1 week"),
            words.peerSet("Maya", "1 hour") to EnglishTimerWordsCopy.peerSet("Maya", "1 hour"),
            words.unnamedContact() to EnglishTimerWordsCopy.unnamedContact(),
        )
        samples.forEach { (real, expected) -> assertEquals(expected, real) }
    }

    /** The JVM stand-in lives in the unit-test source set, so its sentences are repeated here. */
    private object EnglishTimerWordsCopy {
        fun unnamedContact() = "Your contact"
        fun youTurnedOff() = "You turned off disappearing messages"
        fun peerTurnedOff(name: String) = "$name turned off disappearing messages"
        fun youSet(duration: String) = "You set messages to disappear after $duration"
        fun peerSet(name: String, duration: String) = "$name set messages to disappear after $duration"
    }

    private companion object {
        const val MINUTE = 60 * 1000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
