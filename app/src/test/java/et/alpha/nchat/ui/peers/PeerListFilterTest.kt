package et.alpha.nchat.ui.peers

import et.alpha.nchat.data.db.PeerEntity
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PeerListFilterTest {

    private val now = 10L * 24 * 60 * 60 * 1000
    private val stale = now - STRANGER_LISTED_MS - 1

    private fun peer(lastSeenAt: Long = stale) =
        PeerEntity(id = "a1b2c3d4e5f60718", firstSeenAt = 0L, lastSeenAt = lastSeenAt)

    @Test
    fun `a stranger unheard for over an hour is not listed`() {
        // The bug this guards: every ANNOUNCE in range added a row for good, so
        // a crowded afternoon buried the contacts under passers-by.
        assertFalse(isListed(peer(), hasThread = false, isOnline = false, now = now))
    }

    @Test
    fun `a stranger heard within the hour is listed`() {
        assertTrue(isListed(peer(lastSeenAt = now - 1_000), hasThread = false, isOnline = false, now = now))
    }

    @Test
    fun `a stranger on a proven link is listed however stale its row`() {
        assertTrue(isListed(peer(), hasThread = false, isOnline = true, now = now))
    }

    @Test
    fun `kept peers stay listed when stale`() {
        assertTrue(isListed(peer(), hasThread = true, isOnline = false, now = now))
        assertTrue(isListed(peer().copy(isVerified = true), hasThread = false, isOnline = false, now = now))
        assertTrue(isListed(peer().copy(nickname = "Maya"), hasThread = false, isOnline = false, now = now))
        // Blocked stays findable, or it could never be unblocked.
        assertTrue(isListed(peer().copy(isBlocked = true), hasThread = false, isOnline = false, now = now))
    }
}
