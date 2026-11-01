package et.alpha.nchat.mesh.router

import et.alpha.nchat.mesh.protocol.PacketVerifier
import et.alpha.nchat.mesh.protocol.Verification
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Roadmap #57: one link's flood is cut off before it costs a decode or an
 * Ed25519 check, and other links keep their own budget.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkRateLimiterTest {

    private var now = 0L
    private fun limiter(capacity: Double = 3.0, refill: Double = 1.0) =
        LinkRateLimiter(capacity, refill) { now }

    @Test
    fun `a link gets its burst and then nothing`() {
        val limiter = limiter()

        repeat(3) { assertTrue(limiter.tryAcquire("x")) }
        assertFalse(limiter.tryAcquire("x"))
    }

    @Test
    fun `budget refills with time, up to the capacity`() {
        val limiter = limiter()
        repeat(3) { limiter.tryAcquire("x") }

        now += 1000
        assertTrue(limiter.tryAcquire("x"))
        assertFalse(limiter.tryAcquire("x"))

        now += 60_000
        repeat(3) { assertTrue(limiter.tryAcquire("x")) }
        assertFalse(limiter.tryAcquire("x"))
    }

    @Test
    fun `links have separate budgets`() {
        val limiter = limiter()
        repeat(3) { limiter.tryAcquire("x") }

        assertFalse(limiter.tryAcquire("x"))
        assertTrue(limiter.tryAcquire("y"))
    }

    @Test
    fun `forgetting a link drops its bucket`() {
        val limiter = limiter()
        repeat(3) { limiter.tryAcquire("x") }

        limiter.forget("x")

        assertTrue(limiter.tryAcquire("x"))
    }

    @Test
    fun `the default burst covers a full forward queue plus carried packets`() {
        // A neighbour hands over its whole queue the moment it connects. Dropping
        // part of that would lose real mail, so the burst must hold it all.
        val limiter = LinkRateLimiter { 0L }
        val burst = ForwardQueue.DEFAULT_CAPACITY + MeshRouter.CARRY_LIMIT

        repeat(burst) { assertTrue(limiter.tryAcquire("x"), "packet $it") }
    }

    @Test
    fun `the router drops a flooding link's excess before verifying it`() = runTest {
        meshTest {
            var checks = 0
            verifier = PacketVerifier {
                checks++
                Verification.VALID
            }
            rateLimiter = { LinkRateLimiter(capacity = 2.0, refillPerSecond = 0.001) { 0L } }
            val (a, b) = chain("A", "B")
            settle()

            repeat(5) { a.router.originate(testPacket(from = a.id)) }
            settle()

            assertEquals(2, b.delivered.size)
            assertEquals(2, checks)
            assertEquals(3L, b.router.droppedRateLimited)
        }
    }
}
