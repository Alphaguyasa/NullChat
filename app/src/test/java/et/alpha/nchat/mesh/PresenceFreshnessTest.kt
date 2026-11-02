package et.alpha.nchat.mesh

import et.alpha.nchat.mesh.protocol.PacketType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PresenceFreshnessTest {

    private val hour = 60 * 60 * 1000L
    private val now = 100 * hour

    @Test
    fun `the first copy from a sender is believed`() {
        val gate = PresenceFreshness()

        assertTrue(gate.accept("a", PacketType.ANNOUNCE, timestamp = now - 5 * hour, now = now))
    }

    @Test
    fun `an old copy replayed after a newer one is refused`() {
        val gate = PresenceFreshness()
        gate.accept("a", PacketType.PROFILE, timestamp = now - 3 * hour, now = now - 3 * hour)
        gate.accept("a", PacketType.PROFILE, timestamp = now - hour, now = now - hour)

        assertFalse(gate.accept("a", PacketType.PROFILE, timestamp = now - 3 * hour, now = now))
        // The very same copy again, once the seen-cache has forgotten its id.
        assertFalse(gate.accept("a", PacketType.PROFILE, timestamp = now - hour, now = now))
    }

    @Test
    fun `a newer packet by the sender's clock is taken whatever our clock says`() {
        val gate = PresenceFreshness()
        // A sender whose clock runs a day behind ours is still heard.
        gate.accept("a", PacketType.ANNOUNCE, timestamp = now - 24 * hour, now = now)

        assertTrue(gate.accept("a", PacketType.ANNOUNCE, timestamp = now - 24 * hour + 1, now = now))
    }

    @Test
    fun `a sender whose clock was set back is still heard while current by ours`() {
        val gate = PresenceFreshness()
        gate.accept("a", PacketType.ANNOUNCE, timestamp = now + 48 * hour, now = now)

        assertTrue(gate.accept("a", PacketType.ANNOUNCE, timestamp = now, now = now))
        // But the corrected clock does not reopen the door to replays of old copies.
        assertFalse(gate.accept("a", PacketType.ANNOUNCE, timestamp = now - 2 * hour, now = now))
    }

    @Test
    fun `senders and types are tracked apart`() {
        val gate = PresenceFreshness()
        gate.accept("a", PacketType.ANNOUNCE, timestamp = now, now = now + 2 * hour)

        assertTrue(gate.accept("a", PacketType.PROFILE, timestamp = now - hour, now = now + 2 * hour))
        assertTrue(gate.accept("b", PacketType.ANNOUNCE, timestamp = now - hour, now = now + 2 * hour))
    }

    @Test
    fun `memory is bounded, the least recently heard sender forgotten first`() {
        val gate = PresenceFreshness(maxSenders = 2)
        val later = now + 2 * hour
        gate.accept("a", PacketType.ANNOUNCE, timestamp = now, now = later)
        gate.accept("b", PacketType.ANNOUNCE, timestamp = now, now = later)
        gate.accept("c", PacketType.ANNOUNCE, timestamp = now, now = later)

        // "a" was pushed out, so its replay is believed once, as before this rule.
        assertTrue(gate.accept("a", PacketType.ANNOUNCE, timestamp = now, now = later))
        assertFalse(gate.accept("c", PacketType.ANNOUNCE, timestamp = now, now = later))
    }
}
