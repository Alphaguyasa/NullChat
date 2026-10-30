package et.alpha.nchat.mesh

/**
 * The last disappearing-timer change accepted from one peer.
 *
 * [sentAt] is the sender's own clock, from the TIMER packet; [appliedAt] is this
 * phone's, from when it took effect. Both are needed: the first orders the
 * peer's changes among themselves, the second says how long ago that ordering
 * was last worth anything.
 */
data class PeerTimerStamp(val sentAt: Long, val appliedAt: Long)

/**
 * Whether a TIMER the peer stamped [sentAt] may replace the one in [previous],
 * given that the user last changed this chat's timer on this phone at
 * [localChangedAt] (null if never).
 *
 * Flooding and forward queues deliver a peer's changes in any order, and a relay
 * can replay one days later, after the seen-cache has forgotten it. Someone who
 * set an hour and then turned it off must not find it back on because the older
 * packet took the longer road. So a change only wins against an older stamp
 * from the same sender's clock.
 *
 * It must also lose to a newer change made here. The contact sets an hour, a
 * relay carries that TIMER around for an afternoon, and meanwhile the user turns
 * the timer off: the late packet must not switch it back on. That needs the two
 * phones' clocks compared, which nothing else here does, so it is done loosely:
 * the peer's change loses only when it is stamped more than
 * [PEER_TIMER_CLOCK_TOLERANCE_MILLIS] before the local one. Ordinary drift
 * between phones stays inside that, and the race it targets is hours long. A
 * phone whose clock is further behind than that has its changes refused while
 * the local one is recent; the two chats then show different timers, which is
 * what an older build gets anyway, and either user's next change settles it.
 *
 * Both refusals end after [PEER_TIMER_REPLAY_WINDOW_MILLIS]. A sender whose clock
 * was set back (a phone with no network fixing a wrong date by hand) would
 * otherwise have every later change refused until its clock caught up again.
 * Past the window nothing older can still be in flight, by the same arithmetic as
 * `Tombstones.TTL_MILLIS`, so the next change is taken as it comes.
 */
internal fun acceptsPeerTimer(
    previous: PeerTimerStamp?,
    sentAt: Long,
    now: Long,
    localChangedAt: Long? = null,
): Boolean {
    val newerThanPeer = previous == null ||
        sentAt > previous.sentAt ||
        now - previous.appliedAt > PEER_TIMER_REPLAY_WINDOW_MILLIS
    val newerThanLocal = localChangedAt == null ||
        sentAt >= localChangedAt - PEER_TIMER_CLOCK_TOLERANCE_MILLIS ||
        now - localChangedAt > PEER_TIMER_REPLAY_WINDOW_MILLIS
    return newerThanPeer && newerThanLocal
}

/** One week: past every forward queue a stale TIMER could still be waiting in. */
internal const val PEER_TIMER_REPLAY_WINDOW_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

/** How far apart two phones' clocks may be before a local change is preferred. */
internal const val PEER_TIMER_CLOCK_TOLERANCE_MILLIS: Long = 15L * 60 * 1000
