package et.alpha.nchat.mesh.router

import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Multi-hop routing, verified entirely in memory.
 *
 * Every topology here is a [MeshTestbed] graph of [FakeTransport]s, and rule 5's
 * 50–500ms jitter runs on the test scheduler's virtual clock — so these tests
 * need no radio, no emulator, and no wall-clock waiting. Physical devices remain
 * necessary to prove `NearbyTransport` itself works; they are not necessary to
 * prove the routing rules do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshRouterTest {

    @Test
    fun `message crosses a four-hop chain`() = runTest {
        meshTest {
            val (a, b, c, d, e) = chain("A", "B", "C", "D", "E")
            settle()

            val packet = testPacket(from = a.id, to = e.id, ttl = 6)
            a.router.originate(packet)
            settle()

            assertEquals(1, e.delivered.size, "E is the recipient and should receive it once")
            assertEquals(packet.messageId.toList(), e.delivered.single().messageId.toList())

            // Relays carry traffic without consuming it.
            for (relay in listOf(b, c, d)) {
                assertTrue(relay.delivered.isEmpty(), "${relay.name} is a relay, not the recipient")
            }
            assertTrue(a.delivered.isEmpty(), "the originator should not deliver to itself")

            // Exactly four hops, in order, no strays.
            assertEquals(listOf("A" to "B", "B" to "C", "C" to "D", "D" to "E"), hops())

            // One hop is one decrement. A does not decrement on origination, so the
            // packet leaves A at 6, and E — four links later — sees 6 - 3 = 3.
            assertEquals(3, e.delivered.single().ttl)
        }
    }

    @Test
    fun `a triangle does not rebroadcast forever`() = runTest {
        meshTest {
            val (a, b, c) = fullyConnect("A", "B", "C")
            settle()

            a.router.originate(testPacket(from = a.id, to = Packet.BROADCAST_ID, ttl = 6))
            settle()

            // A floods both neighbours; B and C each relay once to the third node,
            // and both of those arrive already-seen and die there. Four transmissions,
            // and no fifth however long the scheduler runs.
            assertEquals(
                setOf("A" to "B", "A" to "C", "B" to "C", "C" to "B"),
                hops().toSet(),
            )
            assertEquals(4, transmissions.size)

            val quiescent = transmissions.size
            settle()
            assertEquals(quiescent, transmissions.size, "nothing should still be in flight")

            // The broadcast reaches every other node exactly once, despite arriving
            // at B and C twice each.
            assertEquals(1, b.delivered.size)
            assertEquals(1, c.delivered.size)
            assertEquals(0, a.delivered.size, "the originator marked its own id seen")
        }
    }

    @Test
    fun `ttl expiry stops propagation at the right hop`() = runTest {
        meshTest {
            val (a, b, c, d, e) = chain("A", "B", "C", "D", "E", "F")
            val f = node("F")
            settle()

            // ttl 3 leaves A at 3; B receives 3 and relays 2; C receives 2 and relays 1;
            // D receives 1 and has no hop left to spend.
            a.router.originate(testPacket(from = a.id, to = f.id, ttl = 3))
            settle()

            assertEquals(listOf("A" to "B", "B" to "C", "C" to "D"), hops())
            assertEquals(1, transmissions.last().packet.ttl, "D receives the last hop at ttl 1")

            for (unreached in listOf(e, f)) {
                assertTrue(
                    transmissions.none { it.to == unreached.name },
                    "${unreached.name} is past the hop budget and should see nothing",
                )
            }
            assertTrue(listOf(b, c, d, e, f).all { it.delivered.isEmpty() }, "F never got it")
        }
    }

    @Test
    fun `a ttl of one is never forwarded`() = runTest {
        meshTest {
            val (a, _, c) = chain("A", "B", "C")
            settle()

            a.router.originate(testPacket(from = a.id, to = c.id, ttl = 1))
            settle()

            assertEquals(listOf("A" to "B"), hops(), "B has no hop left to spend")
            assertTrue(c.delivered.isEmpty())
        }
    }

    @Test
    fun `a node never echoes a packet back to its source`() = runTest {
        meshTest {
            val (a, b, c) = fullyConnect("A", "B", "C")
            settle()

            a.router.originate(testPacket(from = a.id, to = Packet.BROADCAST_ID, ttl = 6))
            settle()

            // Asserted on the transmission log, not on delivery counts: an echo back
            // to A would be harmlessly deduped there, so counting deliveries would
            // pass even with the bug present. The packet must never go on the wire.
            for (relay in listOf(b, c)) {
                assertFalse(
                    transmissions.any { it.from == relay.name && it.to == a.name },
                    "${relay.name} received from A and must not send back to A",
                )
            }

            // The relays did forward — this is not passing because nothing moved.
            assertEquals(listOf("C"), hopsFrom("B"))
            assertEquals(listOf("B"), hopsFrom("C"))
        }
    }

    @Test
    fun `a relayed packet keeps a verifiable signature`() = runTest {
        meshTest {
            val (a, _, _, _, e) = chain("A", "B", "C", "D", "E")
            settle()

            val original = testPacket(from = a.id, to = e.id, ttl = 6)
            a.router.originate(original)
            settle()

            // Four hops and four decrements later, the bytes an Ed25519 verifier
            // would check are still bit-identical to what A signed. Without ttl
            // excluded from the signed region this fails at the first relay.
            assertArrayEquals(
                PacketCodec.signedBytes(original),
                PacketCodec.signedBytes(e.delivered.single()),
            )
        }
    }

    @Test
    fun `messages delivered before a consumer attaches are not lost`() = runTest {
        meshTest {
            val (a, b) = chain("A", "B")
            settle()

            // b's collector is attached by the testbed, so drain it and route a
            // packet into a router nobody is listening to yet.
            val late = MeshRouter(
                transport = network.register("Z"),
                localId = peerId("Z"),
                scope = detachedScope,
            )
            network.link("B", "Z")
            late.start()
            settle()

            a.router.originate(testPacket(from = a.id, to = peerId("Z"), ttl = 6))
            settle()

            // Nothing has ever collected `late.delivered`. The packet must still be
            // waiting rather than discarded, which is why delivery is a channel and
            // not a subscriber-less SharedFlow.
            val received = mutableListOf<Packet>()
            detachedScope.launch { late.delivered.collect { received.add(it) } }
            settle()

            assertEquals(1, received.size, "a packet routed before anyone subscribed")
            assertTrue(b.delivered.isEmpty(), "B is only a relay here")
        }
    }

    @Test
    fun `starting twice is refused`() = runTest {
        meshTest {
            val a = node("A")
            settle()

            // A service restarted in place must not attach a second set of
            // collectors to the same transport.
            assertThrows<IllegalStateException> { a.router.start() }
        }
    }

    @Test
    fun `a forward to a departed peer is counted`() = runTest {
        meshTest {
            val (a, b, c) = chain("A", "B", "C")
            settle()

            // C drops off the mesh, but B has not heard about it yet: the link is
            // gone while B still believes C is a peer.
            network.severSilently("B", "C")

            a.router.originate(testPacket(from = a.id, to = c.id, ttl = 6))
            settle()

            assertEquals(1L, b.router.failedForwards)
            assertTrue(c.delivered.isEmpty())
        }
    }

    @Test
    fun `a malformed packet is dropped without killing the router`() = runTest {
        meshTest {
            val (a, b) = chain("A", "B")
            settle()

            b.transport.deliver(from = "A", bytes = byteArrayOf(1, 2, 3))
            settle()
            assertEquals(1L, b.router.droppedMalformed)

            // The collector survived: a good packet still routes afterwards.
            a.router.originate(testPacket(from = a.id, to = b.id, ttl = 6))
            settle()
            assertEquals(1, b.delivered.size)
        }
    }
}
