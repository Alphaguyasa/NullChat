package et.alpha.nchat.mesh.router

import et.alpha.nchat.mesh.protocol.PacketType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The forward queue as the router actually drives it, over a fake topology.
 *
 * [ForwardQueueTest] covers the queue's policy in isolation; these cover the
 * seam — that a packet with nowhere to go is held rather than dropped, and that
 * offering it to a peer really puts it on the wire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardQueueRoutingTest {

    @Test
    fun `a message for an absent peer is delivered when that peer appears`() = runTest {
        meshTest {
            val a = node("A")
            val c = node("C")
            settle()

            // Nobody is linked to A yet, so this cannot go anywhere.
            val packet = testPacket(from = a.id, to = c.id)
            assertFalse(a.router.originate(packet), "originate should report that nobody took it")
            settle()

            assertEquals(1, a.queue.size(), "the packet should be held, not dropped")
            assertTrue(c.delivered.isEmpty(), "C cannot have received anything yet")

            // C turns up.
            link("A", "C")
            settle()
            val flushed = flush(from = "A", to = "C")
            settle()

            assertEquals(1, flushed.size, "the queued packet should go out on connection")
            assertEquals(
                packet.messageId.toList(),
                c.delivered.single().messageId.toList(),
                "C should receive the message composed before it was in range",
            )
            assertEquals(0, a.queue.size(), "a delivered packet leaves the queue")
        }
    }

    @Test
    fun `an expired message is dropped rather than delivered late`() = runTest {
        meshTest {
            val a = node("A")
            val c = node("C")
            settle()

            a.router.originate(testPacket(from = a.id, to = c.id))
            settle()
            assertEquals(1, a.queue.size())

            // A day passes before C ever comes into range.
            clock.advance(ForwardQueue.DEFAULT_EXPIRY_MILLIS)

            link("A", "C")
            settle()
            val flushed = flush(from = "A", to = "C")
            settle()

            assertTrue(flushed.isEmpty(), "nothing should be sent")
            assertTrue(c.delivered.isEmpty(), "a day-old message must not arrive")
            assertEquals(0, a.queue.size(), "the expired entry should be swept")
        }
    }

    @Test
    fun `the queue cap is enforced under routing`() = runTest {
        meshTest {
            val a = node("A")
            val c = node("C")
            settle()

            // More packets than the cap, all with nowhere to go.
            val overflow = ForwardQueue.DEFAULT_CAPACITY + 20
            val packets = (0 until overflow).map { testPacket(from = a.id, to = c.id) }
            for (packet in packets) {
                a.router.originate(packet)
                clock.advance(1)
            }
            settle()

            assertEquals(
                ForwardQueue.DEFAULT_CAPACITY,
                a.queue.size(),
                "the queue must stop at its cap however much is offered",
            )
            assertFalse(a.store.holds(packets.first().messageId), "the oldest should be evicted")
            assertTrue(a.store.holds(packets.last().messageId), "the newest should survive")
        }
    }

    @Test
    fun `a relayed packet with no onward peer is held`() = runTest {
        meshTest {
            // A—B only. B is a dead end for anything addressed to C.
            val (a, b) = chain("A", "B")
            val c = node("C")
            settle()

            a.router.originate(testPacket(from = a.id, to = c.id))
            settle()

            // B took it from A, found no onward peer, and kept it. This is the
            // store-and-forward case the old pending-flush never covered: the
            // packet is not B's own message.
            assertEquals(1, b.queue.size(), "B should hold the packet it could not relay")

            link("B", "C")
            settle()
            flush(from = "B", to = "C")
            settle()

            assertEquals(1, c.delivered.size, "C should get the message B carried for it")
        }
    }

    @Test
    fun `a packet that fails to send stays queued`() = runTest {
        meshTest {
            val a = node("A")
            val c = node("C")
            settle()

            a.router.originate(testPacket(from = a.id, to = c.id))
            settle()
            assertEquals(1, a.queue.size())

            // Flushing to a peer that is not actually linked: send returns false.
            val flushed = flush(from = "A", to = "C")
            settle()

            assertTrue(flushed.isEmpty(), "nothing was delivered")
            assertEquals(1, a.queue.size(), "a failed flush must put the packet back")
        }
    }

    @Test
    fun `delivering to one peer does not queue a copy as well`() = runTest {
        meshTest {
            val (a, b) = chain("A", "B")
            settle()

            a.router.originate(testPacket(from = a.id, to = b.id))
            settle()

            assertEquals(1, b.delivered.size, "B should receive it directly")
            assertEquals(0, a.queue.size(), "a packet that went out is not also queued")
        }
    }

    @Test
    fun `an acknowledgement for an absent sender is held and delivered later`() = runTest {
        meshTest {
            val a = node("A")
            val c = node("C")
            settle()

            // C's receipt for a message A sent, with A already out of range.
            val ack = testPacket(from = c.id, to = a.id, type = PacketType.ACK)
            assertFalse(c.router.originate(ack))
            settle()
            assertEquals(1, c.queue.size(), "a receipt must survive the sender walking away")

            link("A", "C")
            settle()
            flush(from = "C", to = "A")
            settle()

            assertEquals(PacketType.ACK, a.delivered.single().type)
        }
    }

    @Test
    fun `an announcement with nobody in range is not held`() = runTest {
        meshTest {
            val a = node("A")
            settle()

            a.router.originate(testPacket(from = a.id, type = PacketType.ANNOUNCE))
            settle()

            assertEquals(0, a.queue.size(), "a stale introduction says something untrue about the mesh")
        }
    }
}
