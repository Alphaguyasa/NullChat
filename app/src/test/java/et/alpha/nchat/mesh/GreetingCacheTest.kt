package et.alpha.nchat.mesh

import et.alpha.nchat.mesh.protocol.PacketType
import et.alpha.nchat.mesh.router.peerId
import et.alpha.nchat.mesh.router.testPacket
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GreetingCacheTest {

    private val announce = testPacket(from = peerId("A"), type = PacketType.ANNOUNCE)
    private val profile = testPacket(from = peerId("A"), type = PacketType.PROFILE)
    private val start = 1_000_000L

    @Test
    fun `nothing sent yet means a fresh broadcast`() {
        val cache = GreetingCache()
        assertNull(cache.announcementFor(peerProvesLinks = true, now = start))
        assertNull(cache.profileFor(peerProvesLinks = true, now = start))
    }

    @Test
    fun `a recent greeting is reused for a peer that proves its links`() {
        val cache = GreetingCache()
        cache.announced(announce, start)
        cache.profiled(profile, start)

        val later = start + GreetingCache.REUSE_MILLIS - 1
        assertSame(announce, cache.announcementFor(peerProvesLinks = true, now = later))
        assertSame(profile, cache.profileFor(peerProvesLinks = true, now = later))
    }

    @Test
    fun `an older build or a peer never met gets a fresh broadcast`() {
        // It binds the link only on an announcement arriving on it, and a copy
        // it already heard through a relay would be dropped as a duplicate.
        val cache = GreetingCache()
        cache.announced(announce, start)
        cache.profiled(profile, start)

        assertNull(cache.announcementFor(peerProvesLinks = false, now = start + 1))
        assertNull(cache.profileFor(peerProvesLinks = false, now = start + 1))
    }

    @Test
    fun `the copy expires well before the seen-cache forgets its id`() {
        val cache = GreetingCache()
        cache.announced(announce, start)

        assertNull(cache.announcementFor(peerProvesLinks = true, now = start + GreetingCache.REUSE_MILLIS))
        assertTrue(GreetingCache.REUSE_MILLIS < PresenceFreshness.FRESH_MILLIS)
    }

    @Test
    fun `a clock set back does not keep an old copy alive`() {
        val cache = GreetingCache()
        cache.announced(announce, start)

        assertNull(cache.announcementFor(peerProvesLinks = true, now = start - 1))
    }

    @Test
    fun `a newer greeting replaces the copy`() {
        val cache = GreetingCache()
        cache.announced(announce, start)
        val newer = testPacket(from = peerId("A"), type = PacketType.ANNOUNCE)
        cache.announced(newer, start + 10)

        assertSame(newer, cache.announcementFor(peerProvesLinks = true, now = start + 20))
    }

    @Test
    fun `a cleared name is not handed to new peers`() {
        val cache = GreetingCache()
        cache.profiled(profile, start)
        cache.forgetProfile()

        assertNull(cache.profileFor(peerProvesLinks = true, now = start + 1))
    }
}
