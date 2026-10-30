package et.alpha.nchat.ui.peers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PeerRowOrderTest {

    private fun row(label: String, isOnline: Boolean = false, lastSeenAt: Long = 0L) =
        PeerRow(
            peerId = label,
            label = label,
            isOnline = isOnline,
            lastMessage = null,
            unreadCount = 0,
            lastSeenAt = lastSeenAt,
        )

    @Test
    fun `offline peers sort by most recently heard, not by name`() {
        // The bug this guards: the comment promised "most recently heard from"
        // while the code sorted by label, so "Abebe" from last month sat above
        // "Tsion" from five minutes ago.
        val rows = listOf(row("abebe", lastSeenAt = 1L), row("tsion", lastSeenAt = 3L), row("maya", lastSeenAt = 2L))

        assertEquals(listOf("tsion", "maya", "abebe"), rows.sortedWith(peerRowOrder).map { it.label })
    }

    @Test
    fun `reachable peers come first, in a stable order by name`() {
        val rows = listOf(
            row("old", lastSeenAt = 99L),
            row("zed", isOnline = true, lastSeenAt = 50L),
            row("amy", isOnline = true, lastSeenAt = 10L),
        )

        assertEquals(listOf("amy", "zed", "old"), rows.sortedWith(peerRowOrder).map { it.label })
    }

    @Test
    fun `a tie in last seen falls back to the name`() {
        val rows = listOf(row("b", lastSeenAt = 5L), row("a", lastSeenAt = 5L))

        assertEquals(listOf("a", "b"), rows.sortedWith(peerRowOrder).map { it.label })
    }
}
