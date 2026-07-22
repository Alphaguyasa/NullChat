package et.alpha.nchat.mesh.router

import et.alpha.nchat.mesh.protocol.Packet
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ForwardQueueTest {

    private val alice = peerId("alice")
    private val bob = peerId("bob")

    private fun queue(
        store: InMemoryForwardQueueStore = InMemoryForwardQueueStore(),
        clock: FakeClock = FakeClock(),
        capacity: Int = ForwardQueue.DEFAULT_CAPACITY,
    ) = ForwardQueue(store = store, capacity = capacity, timeSource = clock)

    @Test
    fun `a queued packet comes back for its recipient`() = runTest {
        val queue = queue()
        val packet = testPacket(from = alice, to = bob)

        assertTrue(queue.enqueue(packet), "first enqueue should store the packet")
        assertEquals(1, queue.size())

        val taken = queue.takeFor(bob)
        assertEquals(1, taken.size)
        assertEquals(packet.messageId.toList(), taken.single().packet.messageId.toList())
        assertEquals(0, queue.size(), "taking should remove what it returns")
    }

    @Test
    fun `packets for other recipients are left alone`() = runTest {
        val queue = queue()
        val forBob = testPacket(from = alice, to = bob)
        val forCarol = testPacket(from = alice, to = peerId("carol"))
        queue.enqueue(forBob)
        queue.enqueue(forCarol)

        val taken = queue.takeFor(bob)

        assertEquals(1, taken.size, "only bob's packet should come back")
        assertEquals(forBob.messageId.toList(), taken.single().packet.messageId.toList())
        assertEquals(1, queue.size(), "carol's packet stays queued")
    }

    @Test
    fun `broadcasts go out to whoever turns up first`() = runTest {
        val queue = queue()
        // A broadcast has no specific recipient to wait for, so it rides along
        // with the first flush rather than waiting for a peer that never comes.
        val broadcast = testPacket(from = alice, to = Packet.BROADCAST_ID)
        queue.enqueue(broadcast)

        val taken = queue.takeFor(bob)

        assertEquals(1, taken.size, "a broadcast should flush to any peer")
        assertEquals(0, queue.size())
    }

    @Test
    fun `the same packet is not queued twice`() = runTest {
        val queue = queue()
        val packet = testPacket(from = alice, to = bob)

        assertTrue(queue.enqueue(packet))
        assertFalse(queue.enqueue(packet), "flooding re-offers the same packet; it must not stack up")
        assertEquals(1, queue.size())
    }

    @Test
    fun `entries expire after twenty four hours`() = runTest {
        val clock = FakeClock()
        val queue = queue(clock = clock)
        queue.enqueue(testPacket(from = alice, to = bob))

        clock.advance(ForwardQueue.DEFAULT_EXPIRY_MILLIS - 1)
        assertEquals(1, queue.size(), "still queued one millisecond short of expiry")

        clock.advance(1)
        assertEquals(0, queue.size(), "should be dropped once the window passes")
        assertTrue(queue.takeFor(bob).isEmpty(), "an expired packet must not be delivered")
    }

    @Test
    fun `expiry does not drop entries still inside the window`() = runTest {
        val clock = FakeClock()
        val queue = queue(clock = clock)

        val old = testPacket(from = alice, to = bob)
        queue.enqueue(old)
        clock.advance(ForwardQueue.DEFAULT_EXPIRY_MILLIS / 2)
        val young = testPacket(from = alice, to = bob)
        queue.enqueue(young)
        clock.advance(ForwardQueue.DEFAULT_EXPIRY_MILLIS / 2)

        // `old` is exactly at the window; `young` is only halfway through it.
        val taken = queue.takeFor(bob)
        assertEquals(1, taken.size)
        assertEquals(young.messageId.toList(), taken.single().packet.messageId.toList())
    }

    @Test
    fun `the cap evicts the oldest first`() = runTest {
        val clock = FakeClock()
        val store = InMemoryForwardQueueStore()
        val capacity = 10
        val queue = queue(store = store, clock = clock, capacity = capacity)

        // One millisecond apart so "oldest" is unambiguous rather than resting
        // on the id tiebreak.
        val packets = (0..capacity).map { // one more than the cap
            testPacket(from = alice, to = bob).also {
                queue.enqueue(it)
                clock.advance(1)
            }
        }

        assertEquals(capacity, queue.size(), "the queue must not exceed its cap")
        assertFalse(store.holds(packets.first().messageId), "the oldest packet should be evicted")
        assertTrue(store.holds(packets.last().messageId), "the newest packet should survive")
    }

    @Test
    fun `restoring an undelivered packet keeps its original age`() = runTest {
        val clock = FakeClock()
        val queue = queue(clock = clock)
        queue.enqueue(testPacket(from = alice, to = bob))

        // A flush that failed to send: taken out, then put back.
        clock.advance(ForwardQueue.DEFAULT_EXPIRY_MILLIS / 2)
        queue.restore(queue.takeFor(bob))
        assertEquals(1, queue.size(), "the packet should still be held")

        // Half a window later it is a full window old. If restore had re-stamped
        // it with the current time, an undeliverable packet would live forever.
        clock.advance(ForwardQueue.DEFAULT_EXPIRY_MILLIS / 2)
        assertEquals(0, queue.size(), "restore must not renew the expiry window")
    }

    @Test
    fun `restore refuses a packet that expired in flight`() = runTest {
        val clock = FakeClock()
        val queue = queue(clock = clock)
        queue.enqueue(testPacket(from = alice, to = bob))

        val taken = queue.takeFor(bob)
        clock.advance(ForwardQueue.DEFAULT_EXPIRY_MILLIS)
        queue.restore(taken)

        assertEquals(0, queue.size(), "an entry that aged out during the flush is not resurrected")
    }

    @Test
    fun `a nonsense capacity is refused`() = runTest {
        assertThrows<IllegalArgumentException> {
            ForwardQueue(store = InMemoryForwardQueueStore(), capacity = 0)
        }
        assertThrows<IllegalArgumentException> {
            ForwardQueue(store = InMemoryForwardQueueStore(), expiryMillis = 0)
        }
    }
}
