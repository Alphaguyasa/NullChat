package et.alpha.nchat.mesh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Runs [block] in this scope and waits for its result, without tying the work
 * to the caller's lifetime.
 *
 * A send is started from a screen, but an image is ~280 fragments paced over
 * several seconds. Run in the screen's `viewModelScope`, leaving the chat
 * cancelled it between two fragments and stranded the row as `queued` until the
 * 24 h expiry. Here the caller may stop waiting, but the transfer finishes (or
 * fails) on its own and the row ends in a true state either way.
 *
 * The receiver must be a supervisor scope: a failure in [block] then reaches
 * the caller through `await` instead of cancelling the whole mesh.
 */
internal suspend fun <T> CoroutineScope.outliveCaller(block: suspend () -> T): T =
    async { block() }.await()

/**
 * Runs [block]; if it is cancelled, runs [onCancel] before the cancellation
 * carries on.
 *
 * [onCancel] runs [NonCancellable] because the coroutine is already cancelled:
 * without it the first suspending database write inside would throw at once and
 * the row would keep its half-finished state.
 */
internal suspend fun <T> onCancellation(onCancel: suspend () -> Unit, block: suspend () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        withContext(NonCancellable) { onCancel() }
        throw e
    }
