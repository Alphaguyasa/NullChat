package et.alpha.nchat.mesh.router

import et.alpha.nchat.mesh.protocol.Packet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a relay can honestly say it saw.
 *
 * These assert on link-layer endpoint names — "A", "C" — rather than on protocol
 * peer ids, because that is the namespace the events are in and conflating the
 * two is the mistake this flow is easiest to misuse for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayActivityTest {

    @Test
    fun `a relayed packet pulses the link it arrived on and the link it left by`() = runTest {
        meshTest {
            val (a, b, c) = chain("A", "B", "C")
            settle()

            val events = collectRelayActivity(b)

            a.router.originate(testPacket(from = a.id, to = c.id, ttl = 6))
            settle()

            assertEquals(
                listOf(
                    RelayEvent("A", RelayEvent.Direction.IN),
                    RelayEvent("C", RelayEvent.Direction.OUT),
                ),
                events,
                "B carried the packet across both of its links and neither is B's own id",
            )
        }
    }

    @Test
    fun `a duplicate still pulses the link it crossed`() = runTest {
        meshTest {
            val (a, b, c) = fullyConnect("A", "B", "C")
            settle()

            val events = collectRelayActivity(b)

            a.router.originate(testPacket(from = a.id, to = Packet.BROADCAST_ID, ttl = 6))
            settle()

            // B hears the same broadcast from A and again from C, which relayed
            // it. The second copy is dropped as a duplicate — but bytes crossed
            // that link, and a radar that ignored them would draw a busy relay as
            // an idle one.
            assertEquals(1, b.delivered.size, "the duplicate was deduped, as it should be")
            assertEquals(
                setOf("A", "C"),
                events.filter { it.direction == RelayEvent.Direction.IN }
                    .mapTo(mutableSetOf()) { it.endpointId },
            )
        }
    }

    @Test
    fun `a forward to a departed peer does not pulse`() = runTest {
        meshTest {
            val (a, b, c) = chain("A", "B", "C")
            settle()

            val events = collectRelayActivity(b)

            // C is gone while B still believes it is a peer, so B's send fails and
            // nothing reaches the link at all.
            network.severSilently("B", "C")
            a.router.originate(testPacket(from = a.id, to = c.id, ttl = 6))
            settle()

            assertEquals(1L, b.router.failedForwards)
            assertEquals(listOf(RelayEvent("A", RelayEvent.Direction.IN)), events)
        }
    }

    @Test
    fun `activity emitted before anyone subscribes is dropped rather than replayed`() = runTest {
        meshTest {
            val (a, b, c) = chain("A", "B", "C")
            settle()

            a.router.originate(testPacket(from = a.id, to = c.id, ttl = 6))
            settle()

            // The deliberate opposite of `delivered`, which buffers a packet
            // routed before its consumer attached. A pulse for a packet that has
            // already been and gone would animate a link that is now quiet.
            val events = collectRelayActivity(b)
            settle()

            assertTrue(events.isEmpty(), "replay is 0 on purpose")
            assertEquals(1, c.delivered.size, "and routing itself never waited for a subscriber")
        }
    }

    /** Attaches a collector and pumps the scheduler so it is live before any traffic. */
    private fun MeshTestbed.collectRelayActivity(node: TestNode): List<RelayEvent> {
        val events = mutableListOf<RelayEvent>()
        detachedScope.launch { node.router.relayActivity.collect { events.add(it) } }
        settle()
        return events
    }
}
