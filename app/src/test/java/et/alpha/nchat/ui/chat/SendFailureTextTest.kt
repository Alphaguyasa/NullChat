package et.alpha.nchat.ui.chat

import et.alpha.nchat.mesh.SendFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SendFailureTextTest {

    @Test
    fun `every failure has a sentence of its own`() {
        val texts = SendFailure.entries.map(::sendFailureText)

        assertEquals(SendFailure.entries.size, texts.toSet().size)
    }
}
