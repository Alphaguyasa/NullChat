package et.alpha.nchat.mesh.transport

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScanLoopTest {

    // 5 s scan + 25 s idle = one window every 30 s; the clock never reaches backoff.
    private val dutyCycle = DutyCycle(timeSource = { 0L })

    @Test
    fun `a failed advertising start is retried at the next window`() = runTest {
        val transport = mockk<Transport>(relaxed = true)
        var calls = 0
        coEvery { transport.startAdvertising() } answers {
            calls++
            if (calls == 1) throw TransportException("startAdvertising failed: radio busy")
        }
        val job = launch { ScanLoop(transport, dutyCycle).runWhileReady { true } }

        runCurrent()
        coVerify(exactly = 1) { transport.startAdvertising() }

        advanceTimeBy(WINDOW_MS)
        runCurrent()
        coVerify(exactly = 2) { transport.startAdvertising() }
        job.cancel()
    }

    @Test
    fun `advertising that started is not restarted every window`() = runTest {
        val transport = mockk<Transport>(relaxed = true)
        val job = launch { ScanLoop(transport, dutyCycle).runWhileReady { true } }

        runCurrent()
        advanceTimeBy(3 * WINDOW_MS)
        runCurrent()

        coVerify(exactly = 1) { transport.startAdvertising() }
        coVerify(exactly = 4) { transport.startDiscovery() }
        job.cancel()
    }

    @Test
    fun `the loop ends when the radio stops being ready`() = runTest {
        val transport = mockk<Transport>(relaxed = true)
        var ready = true
        val job = launch { ScanLoop(transport, dutyCycle).runWhileReady { ready } }

        runCurrent()
        ready = false
        advanceTimeBy(WINDOW_MS)
        runCurrent()

        coVerify(exactly = 1) { transport.startDiscovery() }
        assertTrue(job.isCompleted)
    }

    private companion object {
        const val WINDOW_MS = 30_000L
    }
}
