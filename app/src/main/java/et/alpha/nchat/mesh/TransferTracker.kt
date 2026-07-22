package et.alpha.nchat.mesh

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
 */
@Singleton
class TransferTracker @Inject constructor() {

    private val _transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())

    /** Every transfer currently running, plus recently finished ones until cleared. */
    val transfers: StateFlow<Map<String, Transfer>> = _transfers.asStateFlow()

    fun start(messageId: String, total: Int, isOutgoing: Boolean) {
        _transfers.update {
            it + (messageId to Transfer(messageId, 0, total, isOutgoing))
        }
    }

    /** Records absolute progress. Idempotent, so a replayed fragment cannot inflate it. */
    fun advance(messageId: String, completed: Int) {
        _transfers.update { current ->
            val existing = current[messageId] ?: return@update current
            current + (messageId to existing.copy(completed = completed))
        }
    }

    fun complete(messageId: String) {
        _transfers.update { current ->
            val existing = current[messageId] ?: return@update current
            current + (
                messageId to existing.copy(
                    completed = existing.total,
                    state = TransferState.COMPLETE,
                )
                )
        }
    }

    fun fail(messageId: String) {
        _transfers.update { current ->
            val existing = current[messageId] ?: return@update current
            current + (messageId to existing.copy(state = TransferState.FAILED))
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
}
