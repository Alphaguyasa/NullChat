package et.alpha.nchat.mesh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SendLifetimeTest {

    @Test
    fun `a transfer keeps going after the screen that started it goes away`() = runTest {
        val mesh = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val sentFragments = mutableListOf<Int>()

        // The screen: a job that is cancelled partway, like viewModelScope on leaving the chat.
        val screen = launch {
            mesh.outliveCaller {
                repeat(5) { index ->
                    sentFragments += index
                    delay(100)
                }
            }
        }
        runCurrent()
        assertEquals(listOf(0), sentFragments)

        screen.cancelAndJoin()
        advanceUntilIdle()

        // The bug: the transfer died with the screen after the first fragment.
        assertEquals(listOf(0, 1, 2, 3, 4), sentFragments)
    }

    @Test
    fun `the caller still gets the result while it waits`() = runTest {
        val mesh = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

        assertEquals(SendResult.Sent, mesh.outliveCaller { SendResult.Sent })
    }

    @Test
    fun `a failing send reaches the caller and leaves the mesh running`() = runTest {
        val meshJob = SupervisorJob()
        val mesh = CoroutineScope(meshJob + StandardTestDispatcher(testScheduler))

        val failure = runCatching { mesh.outliveCaller { error("disk full") } }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(meshJob.isActive)
    }

    @Test
    fun `a cancelled transfer runs the cancel handler, even when it suspends`() = runTest {
        var markedFailed = false

        val job = launch {
            onCancellation(
                onCancel = {
                    // A database write: suspends, and would throw at once outside NonCancellable.
                    yield()
                    markedFailed = true
                },
            ) {
                delay(10_000)
            }
        }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(markedFailed)
    }

    @Test
    fun `the cancellation is not swallowed`() = runTest {
        val job = launch {
            onCancellation(onCancel = {}) { delay(10_000) }
            error("kept running after being cancelled")
        }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    @Test
    fun `a finished or failed transfer does not run the cancel handler`() = runTest {
        var handled = 0

        assertEquals(1, onCancellation(onCancel = { handled++ }) { 1 })
        val failure = runCatching { onCancellation(onCancel = { handled++ }) { error("boom") } }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)

        assertEquals(0, handled)
    }

    @Test
    fun `a plain CancellationException thrown by the block counts as cancellation`() = runTest {
        var handled = false

        val failure = runCatching {
            onCancellation(onCancel = { handled = true }) { throw CancellationException("stop") }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(handled)
    }
}
