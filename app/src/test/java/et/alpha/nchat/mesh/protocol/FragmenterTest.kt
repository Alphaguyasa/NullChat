package et.alpha.nchat.mesh.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * [Fragmenter] and [FragmentReassembler].
 *
 * The three cases that matter on a real mesh each get their own region:
 * fragments arrive **out of order** because flooding races them down different
 * paths, **duplicates** arrive because several paths succeed, and fragments go
 * **missing** because a phone walked away mid-message. The first two must
 * reassemble correctly; the third must expire rather than hang or half-deliver.
 */
class FragmenterTest {

    // region helpers

    private val messageId = ByteArray(Packet.MESSAGE_ID_SIZE) { (0x40 + it).toByte() }
    private val senderId = ByteArray(Packet.PEER_ID_SIZE) { (0x11 + it).toByte() }
    private val recipientId = ByteArray(Packet.PEER_ID_SIZE) { (0x21 + it).toByte() }
    private val timestamp = 1_700_000_000_000L

    /** A payload long enough to need [Fragmenter.DEFAULT_CHUNK_SIZE]-sized pieces. */
    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun fragment(
        payload: ByteArray,
        messageId: ByteArray = this.messageId,
        senderId: ByteArray = this.senderId,
    ) = Fragmenter.fragment(
        messageId = messageId,
        senderId = senderId,
        recipientId = recipientId,
        payload = payload,
        timestamp = timestamp,
    )

    /** A clock the test moves by hand, so the 60 second window needs no sleeping. */
    private class TestClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    /**
     * Asserts non-null *and* narrows the type.
     *
     * JUnit 5's own `assertNotNull` returns `Unit`, so it cannot be used inline
     * to reach into the value it just proved was there.
     */
    private fun <T : Any> assertPresent(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    // endregion

    // region splitting

    @Test
    fun `chunk size is the payload budget less the fragment header`() {
        assertEquals(16 + 2 + 2, Fragmenter.FRAGMENT_HEADER_SIZE)
        assertEquals(Packet.MAX_PAYLOAD_SIZE - 20, Fragmenter.DEFAULT_CHUNK_SIZE)
        assertEquals(51, Fragmenter.DEFAULT_CHUNK_SIZE)
    }

    @Test
    fun `every fragment is a legal packet within the size budget`() {
        val fragments = fragment(payload(500))

        assertTrue(fragments.isNotEmpty())
        fragments.forEach {
            assertEquals(PacketType.FRAGMENT, it.type)
            assertTrue(
                it.encodedSize <= Packet.MAX_PACKET_SIZE,
                "fragment of ${it.encodedSize} bytes exceeds the packet budget",
            )
        }
    }

    @Test
    fun `fragments carry distinct packet ids so the router does not dedup them away`() {
        // The whole reason fragments cannot reuse the original message id: the
        // seen-cache would treat pieces 2..n as duplicates of piece 1 and drop
        // every one of them.
        val fragments = fragment(payload(500))

        val ids = fragments.map { it.messageId.toList() }.toSet()
        assertEquals(fragments.size, ids.size)
        assertTrue(fragments.none { it.messageId.contentEquals(messageId) })
    }

    @Test
    fun `fragment ids are derived, so a retransmission is byte-identical`() {
        val first = fragment(payload(500))
        val second = fragment(payload(500))

        first.zip(second).forEach { (a, b) -> assertEquals(a, b) }
    }

    @Test
    fun `every fragment repeats the original addressing`() {
        // Each piece routes on its own; a relay may carry piece 4 having never
        // seen piece 3.
        fragment(payload(300)).forEach {
            assertArrayEquals(senderId, it.senderId)
            assertArrayEquals(recipientId, it.recipientId)
            assertEquals(timestamp, it.timestamp)
            assertEquals(Packet.DEFAULT_TTL, it.ttl)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 50, 51, 52, 101, 102, 103, 500, 5000])
    fun `fragment count matches what splitting actually produces`(size: Int) {
        val fragments = fragment(payload(size))

        assertEquals(Fragmenter.fragmentCount(size), fragments.size)
        assertEquals((size + 50) / 51, fragments.size)
    }

    @Test
    fun `refuses to fragment an empty payload`() {
        assertThrows<IllegalArgumentException> { fragment(ByteArray(0)) }
    }

    @Test
    fun `refuses a payload budget too small to hold a chunk`() {
        assertThrows<IllegalArgumentException> {
            Fragmenter.chunkSize(Fragmenter.FRAGMENT_HEADER_SIZE)
        }
    }

    // endregion

    // region fragment payload round-trip

    @Test
    fun `a fragment payload round-trips through parse`() {
        val chunk = payload(51)
        val packet = fragment(chunk).single()

        val parsed = assertPresent(FragmentHeader.parse(packet.payload))

        assertArrayEquals(messageId, parsed.messageId)
        assertEquals(0, parsed.index)
        assertEquals(1, parsed.total)
        assertArrayEquals(chunk, parsed.chunk)
    }

    @Test
    fun `a fragment survives the packet codec`() {
        // The hard rule: every packet type needs a round-trip encode/decode test.
        val original = fragment(payload(300))

        val decoded = original.map { PacketCodec.decode(PacketCodec.encode(it)) }

        assertEquals(original, decoded)
    }

    @Test
    fun `rejects a fragment payload with no room for a chunk`() {
        assertNull(FragmentHeader.parse(ByteArray(0)))
        assertNull(FragmentHeader.parse(ByteArray(Fragmenter.FRAGMENT_HEADER_SIZE)))
    }

    @Test
    fun `rejects a fragment numbered outside its own total`() {
        val packet = fragment(payload(300)).first()
        // index := total, which would otherwise write past the end of the buffer.
        val tampered = packet.payload.copyOf()
        tampered[Packet.MESSAGE_ID_SIZE] = 0
        tampered[Packet.MESSAGE_ID_SIZE + 1] = 6
        tampered[Packet.MESSAGE_ID_SIZE + 2] = 0
        tampered[Packet.MESSAGE_ID_SIZE + 3] = 6

        assertNull(FragmentHeader.parse(tampered))
    }

    @Test
    fun `rejects a fragment declaring a total of zero`() {
        val packet = fragment(payload(300)).first()
        val tampered = packet.payload.copyOf()
        tampered[Packet.MESSAGE_ID_SIZE + 2] = 0
        tampered[Packet.MESSAGE_ID_SIZE + 3] = 0

        assertNull(FragmentHeader.parse(tampered))
    }

    // endregion

    // region in-order reassembly

    @Test
    fun `reassembles a message delivered in order`() {
        val original = payload(500)
        val reassembler = FragmentReassembler()
        val fragments = fragment(original)

        val completed = fragments.map { reassembler.offer(it) }

        // Only the final fragment completes the message.
        assertTrue(completed.dropLast(1).all { it == null })
        val message = assertPresent(completed.last())
        assertArrayEquals(original, message.payload)
        assertArrayEquals(messageId, message.messageId)
        assertArrayEquals(senderId, message.senderId)
        assertArrayEquals(recipientId, message.recipientId)
        assertEquals(timestamp, message.timestamp)
        assertEquals(0, reassembler.pendingCount)
    }

    @Test
    fun `reassembles a payload whose last fragment is a partial chunk`() {
        // 500 is not a multiple of 51, so the tail chunk is short — the case that
        // breaks any reassembler that assumes a fixed stride.
        val original = payload(500)
        val reassembler = FragmentReassembler()

        val message = fragment(original).firstNotNullOf { reassembler.offer(it) }

        assertEquals(500, message.payload.size)
        assertArrayEquals(original, message.payload)
    }

    @Test
    fun `ignores packets that are not fragments`() {
        val reassembler = FragmentReassembler()
        val message = Packet(
            type = PacketType.MESSAGE,
            messageId = messageId,
            senderId = senderId,
            recipientId = recipientId,
            ttl = 6,
            timestamp = timestamp,
            payload = "hello".toByteArray(),
            signature = ByteArray(Packet.SIGNATURE_SIZE),
        )

        assertNull(reassembler.offer(message))
        assertEquals(0, reassembler.pendingCount)
        assertEquals(0L, reassembler.droppedFragments)
    }

    // endregion

    // region out-of-order arrival

    @Test
    fun `reassembles a message delivered in reverse order`() {
        val original = payload(500)
        val reassembler = FragmentReassembler()

        val completed = fragment(original).reversed().map { reassembler.offer(it) }

        assertTrue(completed.dropLast(1).all { it == null })
        assertArrayEquals(original, assertPresent(completed.last()).payload)
    }

    @Test
    fun `reassembles a message delivered in an arbitrary shuffle`() {
        val original = payload(1000)
        val fragments = fragment(original)
        // A fixed seed, so a failure is reproducible rather than a flake.
        val shuffled = fragments.shuffled(kotlin.random.Random(20260722))
        val reassembler = FragmentReassembler()

        val message = shuffled.firstNotNullOfOrNull { reassembler.offer(it) }

        assertArrayEquals(original, assertPresent(message).payload)
    }

    @Test
    fun `completes on whichever fragment happens to arrive last`() {
        val original = payload(300)
        val fragments = fragment(original)
        val reassembler = FragmentReassembler()

        // Deliver everything except the first, then the first.
        val early = fragments.drop(1).map { reassembler.offer(it) }
        assertTrue(early.all { it == null })

        val message = assertPresent(reassembler.offer(fragments.first()))
        assertArrayEquals(original, message.payload)
    }

    @Test
    fun `interleaves two messages arriving at once`() {
        // Two senders mid-transfer simultaneously is the normal state of a busy
        // mesh, not an edge case.
        val first = payload(300)
        val second = payload(200)
        val otherId = ByteArray(Packet.MESSAGE_ID_SIZE) { (0x90 + it).toByte() }
        val reassembler = FragmentReassembler()

        val a = fragment(first)
        val b = fragment(second, messageId = otherId)

        val results = a.zip(b) { x, y -> listOf(reassembler.offer(x), reassembler.offer(y)) }
            .flatten() + a.drop(b.size).map { reassembler.offer(it) }

        val completed = results.filterNotNull()
        assertEquals(2, completed.size)
        assertArrayEquals(second, completed.first { it.messageId.contentEquals(otherId) }.payload)
        assertArrayEquals(first, completed.first { it.messageId.contentEquals(messageId) }.payload)
    }

    @Test
    fun `keeps two senders using the same message id apart`() {
        // Buffers are keyed by (messageId, senderId) so one peer cannot corrupt
        // the message another peer is assembling by claiming the same id.
        val mine = payload(300)
        val theirs = payload(300)
        val otherSender = ByteArray(Packet.PEER_ID_SIZE) { (0x77 + it).toByte() }
        val reassembler = FragmentReassembler()

        val a = fragment(mine)
        val b = fragment(theirs, senderId = otherSender)

        a.dropLast(1).forEach { assertNull(reassembler.offer(it)) }
        b.dropLast(1).forEach { assertNull(reassembler.offer(it)) }
        assertEquals(2, reassembler.pendingCount)

        assertArrayEquals(mine, assertPresent(reassembler.offer(a.last())).payload)
        assertArrayEquals(theirs, assertPresent(reassembler.offer(b.last())).payload)
    }

    // endregion

    // region duplicate fragments

    @Test
    fun `ignores a duplicated fragment`() {
        val original = payload(300)
        val fragments = fragment(original)
        val reassembler = FragmentReassembler()

        fragments.dropLast(1).forEach { assertNull(reassembler.offer(it)) }
        // Every piece so far, offered a second time — routine under flooding.
        fragments.dropLast(1).forEach { assertNull(reassembler.offer(it)) }

        val message = assertPresent(reassembler.offer(fragments.last()))
        assertArrayEquals(original, message.payload)
        // Duplicates are expected traffic, not a fault worth counting.
        assertEquals(0L, reassembler.droppedFragments)
    }

    @Test
    fun `one fragment repeated never completes a message`() {
        // The failure this guards against: counting arrivals rather than filled
        // slots, so six copies of piece 1 would "complete" a six-piece message
        // and deliver a payload that is mostly zeroes.
        val fragments = fragment(payload(300))
        val reassembler = FragmentReassembler()

        repeat(fragments.size * 3) { assertNull(reassembler.offer(fragments.first())) }

        assertEquals(1, reassembler.pendingCount)
    }

    @Test
    fun `a duplicate arriving after completion starts a new buffer rather than re-delivering`() {
        val fragments = fragment(payload(102))
        val reassembler = FragmentReassembler()

        assertPresent(fragments.firstNotNullOfOrNull { reassembler.offer(it) })

        // The completed buffer is gone, so a late duplicate looks like the start
        // of a fresh message — and, being incomplete, delivers nothing.
        assertNull(reassembler.offer(fragments.first()))
        assertEquals(1, reassembler.pendingCount)
    }

    @Test
    fun `drops a fragment that contradicts the total already agreed`() {
        val fragments = fragment(payload(300))
        val reassembler = FragmentReassembler()
        assertNull(reassembler.offer(fragments.first()))

        // Same message id, different total: either a bug or an attempt to
        // derail a reassembly in progress.
        val forged = fragments[1].payload.copyOf()
        forged[Packet.MESSAGE_ID_SIZE + 2] = 0
        forged[Packet.MESSAGE_ID_SIZE + 3] = 2
        assertNull(reassembler.offer(fragments[1].copy(payload = forged)))

        assertEquals(1L, reassembler.droppedFragments)
        // The genuine reassembly is untouched and still completes.
        val message = fragments.drop(1).firstNotNullOfOrNull { reassembler.offer(it) }
        assertEquals(300, assertPresent(message).payload.size)
    }

    // endregion

    // region missing fragments

    @Test
    fun `a message missing a fragment never completes`() {
        val fragments = fragment(payload(500))
        val reassembler = FragmentReassembler()

        // Everything but the third piece.
        val completed = fragments.filterIndexed { i, _ -> i != 2 }.map { reassembler.offer(it) }

        assertTrue(completed.all { it == null })
        assertEquals(1, reassembler.pendingCount)
    }

    @Test
    fun `an incomplete message expires after the 60 second window`() {
        assertEquals(60_000L, FragmentReassembler.DEFAULT_TIMEOUT_MILLIS)

        val clock = TestClock()
        val reassembler = FragmentReassembler(timeSource = clock)
        val fragments = fragment(payload(500))

        fragments.dropLast(1).forEach { assertNull(reassembler.offer(it)) }
        assertEquals(1, reassembler.pendingCount)

        clock.now += FragmentReassembler.DEFAULT_TIMEOUT_MILLIS
        assertEquals(0, reassembler.pendingCount)
    }

    @Test
    fun `a message completing just inside the window still delivers`() {
        val clock = TestClock()
        val reassembler = FragmentReassembler(timeSource = clock)
        val original = payload(500)
        val fragments = fragment(original)

        fragments.dropLast(1).forEach { reassembler.offer(it) }
        clock.now += FragmentReassembler.DEFAULT_TIMEOUT_MILLIS - 1

        val message = assertPresent(reassembler.offer(fragments.last()))
        assertArrayEquals(original, message.payload)
    }

    @Test
    fun `the last fragment arriving after the window delivers nothing`() {
        val clock = TestClock()
        val reassembler = FragmentReassembler(timeSource = clock)
        val fragments = fragment(payload(500))

        fragments.dropLast(1).forEach { reassembler.offer(it) }
        clock.now += FragmentReassembler.DEFAULT_TIMEOUT_MILLIS

        // The buffer is gone, so the straggler starts a new one instead of
        // completing a stale message an hour late.
        assertNull(reassembler.offer(fragments.last()))
    }

    @Test
    fun `a late fragment does not extend the deadline`() {
        // Otherwise a sender dribbling one fragment every 59 seconds could hold
        // a buffer open indefinitely. The 60 seconds is a budget for the whole
        // message, not an idle timeout.
        val clock = TestClock()
        val reassembler = FragmentReassembler(timeSource = clock)
        val fragments = fragment(payload(500))

        reassembler.offer(fragments[0])
        clock.now += 40_000
        reassembler.offer(fragments[1])
        clock.now += 20_000

        assertEquals(0, reassembler.pendingCount)
    }

    @Test
    fun `expiring one message leaves a newer one alone`() {
        val clock = TestClock()
        val reassembler = FragmentReassembler(timeSource = clock)
        val otherId = ByteArray(Packet.MESSAGE_ID_SIZE) { (0x90 + it).toByte() }
        val original = payload(300)

        reassembler.offer(fragment(payload(300)).first())
        clock.now += 30_000
        val newer = fragment(original, messageId = otherId)
        newer.dropLast(1).forEach { reassembler.offer(it) }

        clock.now += 30_000
        assertEquals(1, reassembler.pendingCount)
        assertArrayEquals(original, assertPresent(reassembler.offer(newer.last())).payload)
    }

    // endregion

    // region bounds

    @Test
    fun `evicts the oldest partial message once capacity is reached`() {
        val reassembler = FragmentReassembler(capacity = 2)

        repeat(3) { i ->
            val id = ByteArray(Packet.MESSAGE_ID_SIZE) { (i + 1).toByte() }
            reassembler.offer(fragment(payload(300), messageId = id).first())
        }

        assertEquals(2, reassembler.pendingCount)
    }

    @Test
    fun `refuses a message declaring more bytes than the ceiling allows`() {
        val reassembler = FragmentReassembler(maxMessageBytes = 100)

        // 500 bytes across ten 51-byte fragments: rejected on the first piece,
        // from the declared total, before any buffer is allocated.
        assertNull(reassembler.offer(fragment(payload(500)).first()))

        assertEquals(0, reassembler.pendingCount)
        assertEquals(1L, reassembler.droppedFragments)
    }

    @Test
    fun `clear drops everything in flight`() {
        val reassembler = FragmentReassembler()
        fragment(payload(500)).dropLast(1).forEach { reassembler.offer(it) }

        reassembler.clear()

        assertEquals(0, reassembler.pendingCount)
    }

    // endregion

    // region value semantics

    @Test
    fun `reassembled messages compare by content`() {
        val a = ReassembledMessage(messageId, senderId, recipientId, timestamp, payload(80))
        val b = ReassembledMessage(
            messageId.copyOf(),
            senderId.copyOf(),
            recipientId.copyOf(),
            timestamp,
            payload(80),
        )

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, b.copy(payload = payload(81)))
    }

    @Test
    fun `a reassembled broadcast reports itself as one`() {
        val broadcast = ReassembledMessage(
            messageId,
            senderId,
            Packet.BROADCAST_ID,
            timestamp,
            payload(80),
        )

        assertTrue(broadcast.isBroadcast)
        assertTrue(
            !ReassembledMessage(messageId, senderId, recipientId, timestamp, payload(80))
                .isBroadcast,
        )
    }

    // endregion
}
