package et.alpha.nchat.data.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IdsTest {

    @Test
    fun `peerIdOfDirect inverts directConversationId`() {
        val peer = "a1b2c3d4e5f60718"

        assertEquals(peer, peerIdOfDirect(directConversationId(peer)))
    }

    @Test
    fun `the broadcast thread has no peer`() {
        assertNull(peerIdOfDirect(BROADCAST_CONVERSATION_ID))
    }

    @Test
    fun `a direct prefix with nothing after it names nobody`() {
        assertNull(peerIdOfDirect("direct:"))
    }
}
