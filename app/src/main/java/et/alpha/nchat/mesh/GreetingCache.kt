package et.alpha.nchat.mesh

import et.alpha.nchat.mesh.protocol.Packet

/**
 * Decides whether a newly connected link gets a fresh, mesh-wide greeting or a
 * copy of the last one, sent down that link only.
 *
 * Every new link used to start a ttl-6 ANNOUNCE and PROFILE with fresh ids.
 * Each phone in range relays each of them once, so in a crowd where links keep
 * dropping and coming back, every reconnect cost the whole mesh two floods,
 * about O(N²) packets of airtime for news nobody needed.
 *
 * The copy keeps its message id, so the router's seen-cache does the rest.
 * A peer that already heard it drops it without relaying, which costs one
 * packet. A peer that did not (it came from another part of the mesh, or
 * restarted) takes it like any live greeting, a hop count of 1 included, and
 * floods it on into the part of the mesh that has not heard it, and no further.
 * That is the cluster-merge case the flood exists for.
 *
 * [REUSE_MILLIS] stays under the 10-minute seen-cache window. A copy older than
 * that would be new again to every phone, and the flood would come back.
 * The same 10 minutes is `PresenceFreshness.FRESH_MILLIS`, so a receiver never
 * takes the copy for a replay.
 *
 * Only for peers that have sent us a link proof before (a build with
 * LINK_PROOF, which binds the link by that proof alone). An older build binds
 * a link only on an announcement arriving on it. If it already heard the copy
 * through a relay, it would drop it as a duplicate and never mark us online.
 * Those peers, and any peer we have not met yet, get the fresh broadcast as
 * before.
 *
 * Holds the packets unsigned. The router signs on the way out, and Ed25519
 * gives the same signature for the same bytes, so the copy matches the
 * original. Pure Kotlin and thread-safe: the greeting and the name-change
 * collector both write to it.
 */
internal class GreetingCache(private val reuseMillis: Long = REUSE_MILLIS) {

    private val lock = Any()
    private var announcement: Cached? = null
    private var profile: Cached? = null

    /**
     * The announcement to send on a new link with a peer that does
     * ([peerProvesLinks]) or does not prove its links, or null to broadcast a
     * fresh one.
     */
    fun announcementFor(peerProvesLinks: Boolean, now: Long): Packet? =
        synchronized(lock) { announcement.reusable(peerProvesLinks, now) }

    /** As [announcementFor], for the display name. */
    fun profileFor(peerProvesLinks: Boolean, now: Long): Packet? =
        synchronized(lock) { profile.reusable(peerProvesLinks, now) }

    /** A fresh announcement went out to at least one peer at [now]. */
    fun announced(packet: Packet, now: Long) = synchronized(lock) {
        announcement = Cached(packet, now)
    }

    /** A fresh profile went out to at least one peer at [now]. */
    fun profiled(packet: Packet, now: Long) = synchronized(lock) {
        profile = Cached(packet, now)
    }

    /**
     * The user cleared their name. Peers keep the old one (there is no packet
     * for "no name"), but this phone must stop handing it to new ones.
     */
    fun forgetProfile() = synchronized(lock) {
        profile = null
    }

    private fun Cached?.reusable(peerProvesLinks: Boolean, now: Long): Packet? {
        if (this == null || !peerProvesLinks) return null
        // Also refused when the clock went backwards, so a wrong date being
        // corrected cannot keep one old copy alive.
        val age = now - sentAt
        return if (age in 0 until reuseMillis) packet else null
    }

    private class Cached(val packet: Packet, val sentAt: Long)

    companion object {
        /** Half the seen-cache window; see the class comment. */
        const val REUSE_MILLIS: Long = 5 * 60 * 1000L
    }
}
