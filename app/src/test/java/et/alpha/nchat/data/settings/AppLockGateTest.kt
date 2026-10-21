package et.alpha.nchat.data.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppLockGateTest {

    private val grace = AppLockGate.GRACE_MILLIS

    @Test
    fun `a cold start with the lock on starts locked`() {
        assertTrue(AppLockGate(initiallyLocked = true).locked.value)
    }

    @Test
    fun `a cold start with the lock off starts open`() {
        assertFalse(AppLockGate(initiallyLocked = false).locked.value)
    }

    @Test
    fun `a short trip out, like picking a photo, does not lock`() {
        val gate = AppLockGate(initiallyLocked = false)
        gate.onBackground(now = 1_000, enabled = true)
        gate.onForeground(now = 1_000 + grace - 1, enabled = true)
        assertFalse(gate.locked.value)
    }

    @Test
    fun `coming back after the grace period locks`() {
        val gate = AppLockGate(initiallyLocked = false)
        gate.onBackground(now = 1_000, enabled = true)
        gate.onForeground(now = 1_000 + grace, enabled = true)
        assertTrue(gate.locked.value)
    }

    @Test
    fun `nothing locks while the lock is off`() {
        val gate = AppLockGate(initiallyLocked = false)
        gate.onBackground(now = 1_000, enabled = false)
        gate.onForeground(now = 1_000 + 10 * grace, enabled = false)
        assertFalse(gate.locked.value)
    }

    @Test
    fun `turning the lock on while away does not lock on return`() {
        // No time was recorded when leaving, so there is nothing to measure.
        val gate = AppLockGate(initiallyLocked = false)
        gate.onBackground(now = 1_000, enabled = false)
        gate.onForeground(now = 1_000 + 10 * grace, enabled = true)
        assertFalse(gate.locked.value)
    }

    @Test
    fun `turning the lock off elsewhere opens a locked app`() {
        val gate = AppLockGate(initiallyLocked = true)
        gate.onForeground(now = 1_000, enabled = false)
        assertFalse(gate.locked.value)
    }

    @Test
    fun `a locked app stays locked through background and foreground`() {
        val gate = AppLockGate(initiallyLocked = true)
        gate.onBackground(now = 1_000, enabled = true)
        gate.onForeground(now = 1_001, enabled = true)
        assertTrue(gate.locked.value)
    }

    @Test
    fun `the system credential screen does not re-lock after a slow unlock`() {
        // On older Android the PIN screen is its own activity: ours stops, the
        // unlock callback can land before onStart, and onStart must not undo it.
        val gate = AppLockGate(initiallyLocked = true)
        gate.onBackground(now = 1_000, enabled = true)
        gate.unlock()
        gate.onForeground(now = 1_000 + 2 * grace, enabled = true)
        assertFalse(gate.locked.value)
    }

    @Test
    fun `each trip out is timed from its own start`() {
        val gate = AppLockGate(initiallyLocked = false)
        gate.onBackground(now = 0, enabled = true)
        gate.onForeground(now = grace - 1, enabled = true)
        gate.onBackground(now = 10 * grace, enabled = true)
        gate.onForeground(now = 11 * grace - 1, enabled = true)
        assertFalse(gate.locked.value)
    }
}
