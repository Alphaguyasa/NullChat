package et.alpha.nchat.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DisappearingTimerTest {

    @Test
    fun `off comes first and is null`() {
        assertNull(DisappearingTimer.OPTIONS.first())
        assertEquals("Off", DisappearingTimer.label(null))
    }

    @Test
    fun `every offered timer has its own label`() {
        val labels = DisappearingTimer.OPTIONS.map(DisappearingTimer::label)
        assertEquals(listOf("Off", "1 hour", "1 day", "1 week"), labels)
    }

    @Test
    fun `a timer this build does not offer still reads as on`() {
        assertEquals("2 days", DisappearingTimer.label(2 * 24 * 60 * 60 * 1000L))
        assertEquals("3 hours", DisappearingTimer.label(3 * 60 * 60 * 1000L))
    }
}
