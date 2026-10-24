package et.alpha.nchat.mesh.router

import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketVerifier
import et.alpha.nchat.mesh.protocol.Verification
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Roadmap #32: what an inbound packet costs. Duplicates are dropped before the
 * signature check, and a forged ttl cannot push a packet past the mesh radius.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboundCostTest {

    @Test
    fun `a duplicate is dropped without verifying its signature again`() = runTest {
        meshTest {
            val checks = AtomicInteger()
            verifier = PacketVerifier { checks.incrementAndGet(); Verification.VALID }
            val (a, b, c) = fullyConnect("A", "B", "C")
            settle()

            a.router.originate(testPacket(from = a.id))
            settle()

            // B and C each verify A's copy once. The relayed copies B and C
            // swap afterwards are duplicates and must cost no Ed25519 check.
            assertEquals(2, checks.get())
            assertEquals(1, b.delivered.size)
            assertEquals(1, c.delivered.size)
        }
    }

    @Test
    fun `a forged high ttl is clamped to the default before relaying`() = runTest {
        meshTest {
            val (a, _, _) = chain("A", "B", "C")
            settle()

            // A stands in for a hostile relay that rewrote the unsigned ttl.
            a.router.originate(testPacket(from = a.id, ttl = 255))
            settle()

            val relayed = transmissions.single { it.from == "B" && it.to == "C" }.packet
            assertEquals(Packet.DEFAULT_TTL - 1, relayed.ttl)
        }
    }

    @Test
    fun `a forged high ttl does not carry a packet past the default radius`() = runTest {
        meshTest {
            val names = (0..Packet.DEFAULT_TTL + 2).map { "N$it" }
            chain(*names.toTypedArray())
            settle()

            node("N0").router.originate(testPacket(from = node("N0").id, ttl = 255))
            settle()

            // Clamped to DEFAULT_TTL on the first hop, so it is delivered at most
            // DEFAULT_TTL hops past that node.
            val reached = names.filter { node(it).delivered.isNotEmpty() }
            assertTrue(reached.size <= Packet.DEFAULT_TTL, "reached $reached")
        }
    }
}
