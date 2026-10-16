package et.alpha.nchat.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ForegroundConversationTest {

    @Test
    fun `nothing is in front until a thread is entered`() {
        assertNull(ForegroundConversation().current.value)
    }

    @Test
    fun `leaving the thread in front clears it`() {
        val foreground = ForegroundConversation()
        foreground.enter("direct:a")

        foreground.leave("direct:a")

        assertNull(foreground.current.value)
    }

    @Test
    fun `a late leave from the previous thread does not clear the new one`() {
        val foreground = ForegroundConversation()
        foreground.enter("direct:a")
        foreground.enter("direct:b")

        foreground.leave("direct:a")

        assertEquals("direct:b", foreground.current.value)
    }
}
