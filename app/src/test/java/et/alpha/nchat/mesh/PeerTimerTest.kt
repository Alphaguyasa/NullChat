package et.alpha.nchat.mesh

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PeerTimerTest {

    private val accepted = PeerTimerStamp(sentAt = 1_000L, appliedAt = 50_000L)

    @Test
    fun `the first change from a peer is taken`() {
        assertTrue(acceptsPeerTimer(null, sentAt = 1L, now = 2L))
    }

    @Test
    fun `a newer stamp from the same sender wins`() {
        assertTrue(acceptsPeerTimer(accepted, sentAt = 1_001L, now = 50_001L))
    }

    @Test
    fun `an older or replayed stamp loses inside the window`() {
        assertFalse(acceptsPeerTimer(accepted, sentAt = 999L, now = 50_001L))
        assertFalse(acceptsPeerTimer(accepted, sentAt = 1_000L, now = 50_001L))
        assertFalse(
            acceptsPeerTimer(accepted, sentAt = 999L, now = 50_000L + PEER_TIMER_REPLAY_WINDOW_MILLIS),
        )
    }

    @Test
    fun `a sender whose clock went back is heard again once the window is over`() {
        assertTrue(
            acceptsPeerTimer(accepted, sentAt = 999L, now = 50_001L + PEER_TIMER_REPLAY_WINDOW_MILLIS),
        )
    }
}
