package et.alpha.nchat.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RangeAlertsTest {

    private val maya = "a1b2c3d4e5f60718"
    private val sam = "0011223344556677"
    private val stranger = "ffeeddccbbaa9988"
    private val gap = 1_000L

    @Test
    fun `a watched contact joining the bound set alerts once`() {
        val alerts = RangeAlerts(gap)

        assertEquals(listOf(maya), alerts.onBoundPeers(setOf(maya), setOf(maya), now = 0))
        // Still in range: no second alert.
        assertEquals(emptyList<String>(), alerts.onBoundPeers(setOf(maya, sam), setOf(maya), now = 10))
    }

    @Test
    fun `unwatched peers and strangers never alert`() {
        val alerts = RangeAlerts(gap)

        assertEquals(emptyList<String>(), alerts.onBoundPeers(setOf(sam, stranger), setOf(maya), now = 0))
    }

    @Test
    fun `a flapping link alerts again only after the gap`() {
        val alerts = RangeAlerts(gap)
        val watched = setOf(maya)

        assertEquals(listOf(maya), alerts.onBoundPeers(setOf(maya), watched, now = 0))
        alerts.onBoundPeers(emptySet(), watched, now = 100)
        assertEquals(emptyList<String>(), alerts.onBoundPeers(setOf(maya), watched, now = 200))
        alerts.onBoundPeers(emptySet(), watched, now = 900)
        assertEquals(listOf(maya), alerts.onBoundPeers(setOf(maya), watched, now = 1_200))
    }

    @Test
    fun `the gap is per peer`() {
        val alerts = RangeAlerts(gap)
        val watched = setOf(maya, sam)

        alerts.onBoundPeers(setOf(maya), watched, now = 0)
        assertEquals(listOf(sam), alerts.onBoundPeers(setOf(maya, sam), watched, now = 10))
    }

    @Test
    fun `turning a watch off and on does not keep the old rate limit`() {
        val alerts = RangeAlerts(gap)

        alerts.onBoundPeers(setOf(maya), setOf(maya), now = 0)
        alerts.onBoundPeers(emptySet(), emptySet(), now = 10)
        assertEquals(listOf(maya), alerts.onBoundPeers(setOf(maya), setOf(maya), now = 20))
    }

    @Test
    fun `peers already in range when watching starts alert on their next arrival`() {
        val alerts = RangeAlerts(gap)

        // In range before the watch was switched on: the set did not change,
        // so nothing joins and nothing alerts.
        alerts.onBoundPeers(setOf(maya), emptySet(), now = 0)
        assertEquals(emptyList<String>(), alerts.onBoundPeers(setOf(maya), setOf(maya), now = 10))
        alerts.onBoundPeers(emptySet(), setOf(maya), now = 20)
        assertEquals(listOf(maya), alerts.onBoundPeers(setOf(maya), setOf(maya), now = 30))
    }
}
