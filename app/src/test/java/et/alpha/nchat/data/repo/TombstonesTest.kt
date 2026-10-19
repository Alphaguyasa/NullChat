package et.alpha.nchat.data.repo

import et.alpha.nchat.data.repo.Tombstones.Companion.MAX_ENTRIES
import et.alpha.nchat.data.repo.Tombstones.Companion.TTL_MILLIS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TombstonesTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `a deleted id is refused until its lifetime runs out`() {
        val tombstones = Tombstones()
        tombstones.add("m1", t0)

        assertTrue(tombstones.contains("m1", t0 + TTL_MILLIS - 1))
        assertFalse(tombstones.contains("m1", t0 + TTL_MILLIS))
        assertFalse(tombstones.contains("m2", t0))
    }

    @Test
    fun `it outlives a forward queue's 24 hours twice over`() {
        // A relay that queues the message late in its own day restarts the clock.
        assertTrue(TTL_MILLIS >= 2 * 24 * 60 * 60 * 1000L)
    }

    @Test
    fun `a clock that moved back still counts the deletion as recent`() {
        val tombstones = Tombstones()
        tombstones.add("m1", t0)

        assertTrue(tombstones.contains("m1", t0 - 60_000))
    }

    @Test
    fun `the oldest deletions go first past the cap`() {
        val tombstones = Tombstones()
        repeat(MAX_ENTRIES + 2) { i -> tombstones.add("m$i", t0 + i) }

        assertEquals(MAX_ENTRIES, tombstones.size)
        assertFalse(tombstones.contains("m0", t0 + MAX_ENTRIES + 2))
        assertFalse(tombstones.contains("m1", t0 + MAX_ENTRIES + 2))
        assertTrue(tombstones.contains("m2", t0 + MAX_ENTRIES + 2))
    }

    @Test
    fun `deleting again moves an id to the back of the cap`() {
        val tombstones = Tombstones()
        repeat(MAX_ENTRIES) { i -> tombstones.add("m$i", t0) }
        tombstones.add("m0", t0)
        tombstones.add("new", t0)

        assertTrue(tombstones.contains("m0", t0))
        assertFalse(tombstones.contains("m1", t0))
    }

    @Test
    fun `adding prunes expired entries`() {
        val tombstones = Tombstones()
        tombstones.add("old", t0)
        tombstones.add("new", t0 + TTL_MILLIS)

        assertEquals(1, tombstones.size)
    }

    @Test
    fun `encode and decode round-trip in order`() {
        val tombstones = Tombstones()
        tombstones.add("aa", t0)
        tombstones.add("bb", t0 + 5)

        val decoded = Tombstones.decode(tombstones.encode())

        assertEquals(tombstones.encode(), decoded.encode())
        assertTrue(decoded.contains("aa", t0))
        assertTrue(decoded.contains("bb", t0))
    }

    @Test
    fun `decode keeps the good entries of a damaged value`() {
        val decoded = Tombstones.decode("aa:$t0;garbage;:5;bb:notanumber;cc:${t0 + 1}")

        assertEquals(2, decoded.size)
        assertTrue(decoded.contains("aa", t0))
        assertTrue(decoded.contains("cc", t0))
    }

    @Test
    fun `nothing stored decodes as empty`() {
        assertEquals(0, Tombstones.decode(null).size)
        assertEquals(0, Tombstones.decode("").size)
    }
}
