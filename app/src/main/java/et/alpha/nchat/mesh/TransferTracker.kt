package et.alpha.nchat.mesh

import et.alpha.nchat.mesh.protocol.FragmentReassembler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A multi-fragment transfer in flight, in either direction. */
data class Transfer(
    /** Hex id of the message being carried. */
    val messageId: String,
    val completed: Int,
    val total: Int,
    val isOutgoing: Boolean,
    val state: TransferState = TransferState.RUNNING,
    /** This phone's clock when the first piece went out or came in. */
    val startedAt: Long = 0L,
) {
    /** 0f..1f. */
    val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total.toFloat()
}

enum class TransferState {
    RUNNING,

    /** Every fragment sent, or every fragment received and reassembled. */
    COMPLETE,

    /**
     * Gave up. Outgoing: nobody was in range to take a fragment. Incoming: the
     * 60 second reassembly window closed with pieces still missing.
     */
    FAILED,
}

/**
 * Live progress for messages travelling in more than one packet.
 *
 * Deliberately in memory only and never persisted. A transfer cannot survive
 * process death anyway — the reassembly buffer is in memory and the send loop is
 * a coroutine — so writing progress to Room would only produce rows claiming a
 * transfer is at 60% when nothing is moving.
 *
 * Keyed by hex message id, which is the id of the *whole* message rather than of
 * any individual fragment, so both ends of a transfer agree on the key.
 *
 * ## Bounds
 *
 * Every progress tick copies the map into the chat screen, so it holds only what
 * the screen can use. A completed transfer leaves at once: the message row now
 * carries the outcome, and the bubble never drew a bar for it. A failed one stays
 * so the bubble can say why, but only the newest [MAX_FAILED] of them. An incoming
 * transfer whose sender walked away gets no further fragment and so no final
 * state; it is dropped once the reassembler's own window has closed on it.
 */
@Singleton
class TransferTracker(private val clock: () -> Long) {

    @Inject
    constructor() : this(System::currentTimeMillis)

    private val _transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())

    /** Transfers still running, plus the most recent failures. */
    val transfers: StateFlow<Map<String, Transfer>> = _transfers.asStateFlow()

    /**
     * Ids of transfers that completed recently, oldest first, so a straggling
     * fragment after the image is whole does not open a fresh progress bar.
     * Bounded; past it the message row itself answers the same question.
     */
    private val completed = LinkedHashSet<String>()

    fun start(messageId: String, total: Int, isOutgoing: Boolean) {
        val now = clock()
        _transfers.update {
            withoutStale(it, now) +
                (messageId to Transfer(messageId, 0, total, isOutgoing, startedAt = now))
        }
    }

    /** Records absolute progress. Idempotent, so a replayed fragment cannot inflate it. */
    fun advance(messageId: String, completed: Int) {
        val now = clock()
        _transfers.update { all ->
            val current = withoutStale(all, now)
            val existing = current[messageId] ?: return@update current
            current + (messageId to existing.copy(completed = completed))
        }
    }

    fun complete(messageId: String) {
        synchronized(completed) {
            completed.remove(messageId)
            completed.add(messageId)
            if (completed.size > MAX_COMPLETED) completed.remove(completed.first())
        }
        _transfers.update { it - messageId }
    }

    /** Whether [messageId] finished arriving or leaving within the recent past. */
    fun isRecentlyCompleted(messageId: String): Boolean =
        synchronized(completed) { messageId in completed }

    fun fail(messageId: String) {
        _transfers.update { current ->
            val existing = current[messageId] ?: return@update current
            val failed = current - messageId +
                (messageId to existing.copy(state = TransferState.FAILED))
            val excess = failed.values.count { it.state == TransferState.FAILED } - MAX_FAILED
            if (excess <= 0) {
                failed
            } else {
                // Insertion order: re-added above, so this one is never the oldest.
                val oldest = failed.values.filter { it.state == TransferState.FAILED }
                    .take(excess).map { it.messageId }.toSet()
                failed - oldest
            }
        }
    }

    /**
     * Forgets a transfer.
     *
     * Called once the message row itself carries the outcome, so the bar stops
     * competing with the delivery tick for the user's attention.
     */
    fun clear(messageId: String) {
        _transfers.update { it - messageId }
    }

    /**
     * Drops incoming transfers older than the reassembly window. The reassembler
     * has already thrown their pieces away, so the bar could never move again.
     * Outgoing ones are left alone: the send loop always ends them itself.
     */
    private fun withoutStale(current: Map<String, Transfer>, now: Long): Map<String, Transfer> {
        val stale = current.values.filter {
            !it.isOutgoing &&
                it.state == TransferState.RUNNING &&
                now - it.startedAt > FragmentReassembler.DEFAULT_TIMEOUT_MILLIS
        }
        return if (stale.isEmpty()) current else current - stale.map { it.messageId }.toSet()
    }

    companion object {
        /** Failures kept for their bubbles to explain. */
        const val MAX_FAILED: Int = 32

        /** Completed ids remembered for straggler checks. */
        const val MAX_COMPLETED: Int = 256
    }
}
