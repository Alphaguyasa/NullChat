package et.alpha.nchat.ui.settings

import et.alpha.nchat.data.db.PeerEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchedContactsTest {

    private fun peer(id: String, displayName: String? = null, nickname: String? = null) = PeerEntity(
        id = id,
        displayName = displayName,
        nickname = nickname,
        firstSeenAt = 0L,
        lastSeenAt = 0L,
    )

    private val maya = "aaaaaaaa11111111"
    private val dawit = "bbbbbbbb22222222"
    private val stranger = "cccccccc33333333"

    @Test
    fun `nothing watched lists nothing`() {
        assertTrue(watchedContacts(emptySet(), listOf(peer(maya, "Maya"))).isEmpty())
    }

    @Test
    fun `only watched contacts are listed, labelled like the peer list and sorted`() {
        val peers = listOf(peer(maya, "maya"), peer(dawit, displayName = "Someone", nickname = "Dawit"), peer(stranger))

        val listed = watchedContacts(setOf(maya, dawit), peers)

        assertEquals(listOf(dawit, maya), listed.map { it.peerId })
        assertEquals(listOf("Dawit · bbbbbbbb", "maya · aaaaaaaa"), listed.map { it.label })
    }

    @Test
    fun `a watched id with no peer row still gets a line by its short id`() {
        val listed = watchedContacts(setOf(stranger), emptyList())

        assertEquals(listOf(WatchedContact(stranger, "cccccccc")), listed)
    }
}
