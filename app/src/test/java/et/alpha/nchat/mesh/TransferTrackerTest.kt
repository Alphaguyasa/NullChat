package et.alpha.nchat.mesh

import et.alpha.nchat.mesh.protocol.FragmentReassembler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [TransferTracker] holds only what the chat screen can show. Before roadmap #48
 * nothing ever left it, and every progress tick copied the whole map.
 */
class TransferTrackerTest {

    private var now = 1_000L
    private val tracker = TransferTracker { now }

    @Test
    fun `a completed transfer leaves the map but is remembered for stragglers`() {
        tracker.start("a", total = 10, isOutgoing = false)

        tracker.complete("a")

        assertFalse("a" in tracker.transfers.value)
        assertTrue(tracker.isRecentlyCompleted("a"))
    }

    @Test
    fun `remembered completions are bounded, oldest forgotten first`() {
        repeat(TransferTracker.MAX_COMPLETED + 1) { tracker.complete("m$it") }

        assertFalse(tracker.isRecentlyCompleted("m0"))
        assertTrue(tracker.isRecentlyCompleted("m${TransferTracker.MAX_COMPLETED}"))
    }

    @Test
    fun `failures stay to explain their bubble, newest kept`() {
        val count = TransferTracker.MAX_FAILED + 5
        repeat(count) {
            tracker.start("f$it", total = 4, isOutgoing = true)
            tracker.fail("f$it")
        }

        val failed = tracker.transfers.value
        assertEquals(TransferTracker.MAX_FAILED, failed.size)
        assertFalse("f0" in failed)
        assertTrue("f${count - 1}" in failed)
        assertTrue(failed.values.all { it.state == TransferState.FAILED })
    }

    @Test
    fun `the failure cap never drops a running transfer`() {
        tracker.start("running", total = 4, isOutgoing = true)
        repeat(TransferTracker.MAX_FAILED + 1) {
            tracker.start("f$it", total = 4, isOutgoing = true)
            tracker.fail("f$it")
        }

        assertEquals(TransferState.RUNNING, tracker.transfers.value.getValue("running").state)
    }

    @Test
    fun `an incoming transfer that stalls is dropped after the reassembly window`() {
        tracker.start("stalled", total = 10, isOutgoing = false)
        tracker.advance("stalled", 3)

        now += FragmentReassembler.DEFAULT_TIMEOUT_MILLIS + 1
        tracker.start("next", total = 2, isOutgoing = false)

        assertFalse("stalled" in tracker.transfers.value)
        assertTrue("next" in tracker.transfers.value)
    }

    @Test
    fun `an incoming transfer inside its window is kept`() {
        tracker.start("live", total = 10, isOutgoing = false)

        now += FragmentReassembler.DEFAULT_TIMEOUT_MILLIS
        tracker.advance("live", 9)

        assertEquals(9, tracker.transfers.value.getValue("live").completed)
    }

    @Test
    fun `a long outgoing send is never swept, the send loop ends it`() {
        tracker.start("out", total = 300, isOutgoing = true)

        now += 10 * FragmentReassembler.DEFAULT_TIMEOUT_MILLIS
        tracker.advance("out", 250)

        assertEquals(250, tracker.transfers.value.getValue("out").completed)
    }
}
