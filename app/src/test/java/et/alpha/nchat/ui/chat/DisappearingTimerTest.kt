package et.alpha.nchat.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DisappearingTimerTest {

    private fun label(millis: Long?) = DisappearingTimer.label(millis, EnglishTimerWords)

    @Test
    fun `off comes first and is null`() {
        assertNull(DisappearingTimer.OPTIONS.first())
        assertEquals("Off", label(null))
    }

    @Test
    fun `every offered timer has its own label`() {
        assertEquals(listOf("Off", "1 hour", "1 day", "1 week"), DisappearingTimer.OPTIONS.map(::label))
    }

    @Test
    fun `a timer this build does not offer still reads as on`() {
        assertEquals("2 days", label(2 * DAY))
        assertEquals("3 hours", label(3 * HOUR))
        assertEquals("90 minutes", label(90 * MINUTE))
    }

    @Test
    fun `whole weeks read as weeks, anything else falls to the next unit down`() {
        assertEquals("2 weeks", label(14 * DAY))
        assertEquals("8 days", label(8 * DAY))
        assertEquals("25 hours", label(25 * HOUR))
    }

    @Test
    fun `one of a unit is singular`() {
        assertEquals("1 minute", label(MINUTE))
    }

    private companion object {
        const val MINUTE = 60 * 1000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
