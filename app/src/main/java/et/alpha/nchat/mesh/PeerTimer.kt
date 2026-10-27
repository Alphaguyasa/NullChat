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
 * Whether a TIMER the peer stamped [sentAt] may replace the one in [previous].
 *
 * Flooding and forward queues deliver a peer's changes in any order, and a relay
 * can replay one days later, after the seen-cache has forgotten it. Someone who
 * set an hour and then turned it off must not find it back on because the older
 * packet took the longer road. So a change only wins against an older stamp
 * from the same sender's clock; this phone's clock is never compared with it.
 *
 * Except after [PEER_TIMER_REPLAY_WINDOW_MILLIS]. A sender whose clock was set
 * back (a phone with no network fixing a wrong date by hand) would otherwise
 * have every later change refused until its clock caught up again. Past the
 * window nothing older can still be in flight, by the same arithmetic as
 * `Tombstones.TTL_MILLIS`, so the next change is taken as it comes.
 */
internal fun acceptsPeerTimer(previous: PeerTimerStamp?, sentAt: Long, now: Long): Boolean =
    previous == null ||
        sentAt > previous.sentAt ||
        now - previous.appliedAt > PEER_TIMER_REPLAY_WINDOW_MILLIS

/** One week: past every forward queue a stale TIMER could still be waiting in. */
internal const val PEER_TIMER_REPLAY_WINDOW_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
