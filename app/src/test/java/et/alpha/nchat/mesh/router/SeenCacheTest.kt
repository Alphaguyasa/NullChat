package et.alpha.nchat.mesh.router

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeenCacheTest {

    /** A settable clock, so expiry is tested without a single real millisecond. */
    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(millis: Long) {
            now += millis
        }
    }

    /**
     * A distinct 16-byte id per [n]. The counter goes in the first four bytes
     * rather than being smeared across all sixteen: `(n + i).toByte()` wraps, so
     * id(0) and id(256) would collide and the capacity tests would silently be
     * testing dedup instead.
     */
    private fun id(n: Int): ByteArray = ByteArray(16).also {
        it[0] = (n ushr 24).toByte()
        it[1] = (n ushr 16).toByte()
        it[2] = (n ushr 8).toByte()
        it[3] = n.toByte()
    }

    @Test
    fun `first sighting is new, second is a duplicate`() {
        val cache = SeenCache()

        assertTrue(cache.markSeen(id(1)), "first sighting should be new")
        assertFalse(cache.markSeen(id(1)), "second sighting should be a duplicate")
    }

    @Test
    fun `equal ids in different arrays are the same id`() {
        val cache = SeenCache()

        // The whole point of the MessageId key: these are distinct objects with
        // identical contents, and ByteArray compares by identity. If this fails,
        // nothing ever dedups and the mesh floods forever.
        assertTrue(cache.markSeen(id(7)))
        assertFalse(cache.markSeen(id(7).copyOf()))
    }

    @Test
    fun `capacity evicts the oldest entry`() {
        val cache = SeenCache(capacity = 3)

        cache.markSeen(id(1))
        cache.markSeen(id(2))
        cache.markSeen(id(3))
        cache.markSeen(id(4)) // evicts id(1)

        assertEquals(3, cache.size)
        assertFalse(cache.contains(id(1)), "oldest entry should have been evicted")
        assertTrue(cache.contains(id(2)))
        assertTrue(cache.contains(id(4)))
    }

    @Test
    fun `holds a full thousand before evicting`() {
        val cache = SeenCache()

        repeat(SeenCache.DEFAULT_CAPACITY) { assertTrue(cache.markSeen(id(it))) }
        assertEquals(SeenCache.DEFAULT_CAPACITY, cache.size)
        assertTrue(cache.contains(id(0)), "entry 1 should survive at exactly capacity")

        cache.markSeen(id(SeenCache.DEFAULT_CAPACITY))
        assertEquals(SeenCache.DEFAULT_CAPACITY, cache.size)
        assertFalse(cache.contains(id(0)), "entry 1001 should push entry 1 out")
    }

    @Test
    fun `entries expire after ten minutes`() {
        val clock = FakeClock()
        val cache = SeenCache(timeSource = clock)

        cache.markSeen(id(1))

        clock.advance(SeenCache.DEFAULT_EXPIRY_MILLIS - 1)
        assertFalse(cache.markSeen(id(1)), "still a duplicate one millisecond short of expiry")

        clock.advance(1)
        assertTrue(cache.markSeen(id(1)), "should be treated as new once the window passes")
    }

    @Test
    fun `expiry does not evict entries still inside the window`() {
        val clock = FakeClock()
        val cache = SeenCache(timeSource = clock)

        cache.markSeen(id(1))
        clock.advance(SeenCache.DEFAULT_EXPIRY_MILLIS / 2)
        cache.markSeen(id(2))
        clock.advance(SeenCache.DEFAULT_EXPIRY_MILLIS / 2)

        // id(1) is exactly at the window; id(2) is only halfway through it.
        assertFalse(cache.contains(id(1)))
        assertTrue(cache.contains(id(2)))
        assertEquals(1, cache.size)
    }
}
