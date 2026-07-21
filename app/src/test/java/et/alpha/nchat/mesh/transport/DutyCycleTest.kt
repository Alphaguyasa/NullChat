package et.alpha.nchat.mesh.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The scan schedule, on a fake clock.
 *
 * The numbers here are the ones from CLAUDE.md — 5 on, 25 off, widening to 60
 * second intervals after 5 minutes — asserted as durations rather than as
 * elapsed real time, so the whole schedule is checked in microseconds.
 */
class DutyCycleTest {

    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(millis: Long) {
            now += millis
        }
    }

    @Test
    fun `scans five seconds on, twenty-five off`() {
        val cycle = DutyCycle(timeSource = FakeClock())

        assertEquals(5_000, cycle.scanMillis)
        assertEquals(25_000, cycle.idleMillis())
        assertEquals(30_000, cycle.periodMillis())
        assertFalse(cycle.isBackedOff())
    }

    @Test
    fun `widens to sixty second intervals after five idle minutes`() {
        val clock = FakeClock()
        val cycle = DutyCycle(timeSource = clock)

        clock.advance(5 * 60_000 - 1)
        assertFalse(cycle.isBackedOff(), "still inside the window")
        assertEquals(25_000, cycle.idleMillis())

        clock.advance(1)
        assertTrue(cycle.isBackedOff())

        // "60 second intervals" means a scan every 60 seconds, so the rest period
        // is the period minus the scan — not 60 seconds on top of it.
        assertEquals(60_000, cycle.periodMillis())
        assertEquals(55_000, cycle.idleMillis())
    }

    @Test
    fun `finding a peer returns it to the fast cycle`() {
        val clock = FakeClock()
        val cycle = DutyCycle(timeSource = clock)

        clock.advance(10 * 60_000)
        assertTrue(cycle.isBackedOff())

        cycle.onPeerFound()

        assertFalse(cycle.isBackedOff(), "a peer turning up should resume fast scanning")
        assertEquals(25_000, cycle.idleMillis())
    }

    @Test
    fun `backs off again after another quiet spell`() {
        val clock = FakeClock()
        val cycle = DutyCycle(timeSource = clock)

        clock.advance(10 * 60_000)
        cycle.onPeerFound()
        clock.advance(5 * 60_000)

        // The window restarts from the sighting, not from service start, so a
        // device that goes quiet again gets backed off again.
        assertTrue(cycle.isBackedOff())
    }

    @Test
    fun `rejects a backoff period too short to fit a scan`() {
        // Would otherwise yield a negative idle and a scan loop that never rests.
        assertThrows<IllegalArgumentException> {
            DutyCycle(scanMillis = 5_000, backoffPeriodMillis = 4_000)
        }
    }
}
