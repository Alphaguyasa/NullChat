package et.alpha.nchat.ui.peers

import et.alpha.nchat.data.db.PeerEntity
import et.alpha.nchat.data.db.preferredName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PeerLabelTest {

    private val peer = "a1b2c3d4e5f60718"

    @Test
    fun `a peer with no name is its short id`() {
        assertEquals("a1b2c3d4", peerLabel(null, peer))
    }

    @Test
    fun `a chosen name keeps the short id beside it`() {
        // Anyone can call themselves Maya; only the id comes from the key.
        assertEquals("Maya · a1b2c3d4", peerLabel("Maya", peer))
    }

    @Test
    fun `a blank name counts as none`() {
        assertEquals("a1b2c3d4", peerLabel("  ", peer))
    }

    private fun row(displayName: String?, nickname: String?) = PeerEntity(
        id = peer,
        displayName = displayName,
        firstSeenAt = 0,
        lastSeenAt = 0,
        nickname = nickname,
    )

    @Test
    fun `the user's nickname wins over the name the peer chose`() {
        assertEquals("Mom", row(displayName = "Maya", nickname = "Mom").preferredName)
    }

    @Test
    fun `without a nickname the peer's own name is used`() {
        assertEquals("Maya", row(displayName = "Maya", nickname = null).preferredName)
        assertEquals("Maya", row(displayName = "Maya", nickname = " ").preferredName)
    }

    @Test
    fun `a peer with neither name has none`() {
        assertNull(row(displayName = null, nickname = null).preferredName)
    }
}
