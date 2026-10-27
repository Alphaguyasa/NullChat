package et.alpha.nchat.mesh

import et.alpha.nchat.data.db.BROADCAST_CONVERSATION_ID
import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.directConversationId
import et.alpha.nchat.data.db.hexIdToBytes
import et.alpha.nchat.data.db.newMessageId
import et.alpha.nchat.data.db.toHexId
import et.alpha.nchat.data.media.ImagePolicy
import et.alpha.nchat.mesh.crypto.LocalIdentity
import et.alpha.nchat.mesh.crypto.PublicKeyDirectory
import et.alpha.nchat.mesh.protocol.Acknowledgement
import et.alpha.nchat.mesh.protocol.Announcement
import et.alpha.nchat.mesh.protocol.Attachment
import et.alpha.nchat.mesh.protocol.Fragmenter
import et.alpha.nchat.mesh.protocol.MessagePacket
import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketType
import et.alpha.nchat.mesh.protocol.Profile
import et.alpha.nchat.mesh.protocol.TimerSetting
import et.alpha.nchat.mesh.protocol.acknowledgementPacket
import et.alpha.nchat.mesh.protocol.announcementPacket
import et.alpha.nchat.mesh.protocol.profilePacket
import et.alpha.nchat.mesh.protocol.timerPacket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The inbound path, packet by packet, against an in-memory store.
 *
 * Packets here skip the router, so signatures are zero: by the time a packet
 * reaches [InboundHandler.handle] the router has already verified it, and these
 * tests are about what happens after that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboundHandlerTest {

    private val self = FakeIdentity(byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1))
    private val alice = byteArrayOf(2, 2, 2, 2, 2, 2, 2, 2)
    private val aliceHex = alice.toHexId()
    private val mallory = byteArrayOf(3, 3, 3, 3, 3, 3, 3, 3)

    private val store = FakeInboundStore()
    private val transfers = TransferTracker()
    private val directory = PublicKeyDirectory()
    private val originated = mutableListOf<Packet>()
    private var now = 1_000_000L

    /** Stands in for the cipher: "sealed" payloads are the plaintext with a marker byte in front. */
    private val decrypt: (ByteArray, ByteArray, ByteArray) -> ByteArray? = { _, _, ciphertext ->
        if (ciphertext.firstOrNull() == SEALED) ciphertext.copyOfRange(1, ciphertext.size) else null
    }

    private fun handler(originate: suspend (Packet) -> Unit = { originated += it }) = InboundHandler(
        identity = self,
        store = store,
        keyDirectory = directory,
        decrypt = decrypt,
        transfers = transfers,
        originate = originate,
        clock = { now },
    )

    private fun TestScope.arrivalsOf(handler: InboundHandler): List<Arrival> {
        val arrivals = mutableListOf<Arrival>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { handler.arrivals.toList(arrivals) }
        return arrivals
    }

    private fun direct(body: String, from: ByteArray = alice, ttl: Int = Packet.DEFAULT_TTL, id: String = newMessageId()) =
        MessagePacket.build(id, from, self.peerId.toHexId(), body, timestamp = 42L, ttl = ttl)
            .let { it.copy(payload = byteArrayOf(SEALED) + it.payload) }

    private fun broadcast(body: String, from: ByteArray = alice, id: String = newMessageId()) =
        MessagePacket.build(id, from, null, body, timestamp = 42L)

    @Test
    fun `a direct message is stored, announced and acknowledged with its hop count`() = runTest {
        val handler = handler()
        val arrivals = arrivalsOf(handler)
        val packet = direct("hello", ttl = 4)

        handler.handle(packet)

        val row = store.rows.getValue(packet.messageId.toHexId())
        assertEquals("hello", row.body)
        assertEquals(directConversationId(aliceHex), row.conversationId)
        assertEquals(42L, row.timestamp)
        assertEquals(listOf(aliceHex), arrivals.map { it.senderId })
        assertEquals("hello", arrivals.single().preview)

        val ack = originated.single()
        assertEquals(PacketType.ACK, ack.type)
        assertArrayEquals(alice, ack.recipientId)
        assertArrayEquals(self.peerId, ack.senderId)
        val receipt = Acknowledgement.parse(ack.payload)!!
        assertArrayEquals(packet.messageId, receipt.ackedMessageId)
        // ttl 4 after starting at 6: two relays, so three links.
        assertEquals(3, receipt.hops)
    }

    @Test
    fun `a duplicate copy is neither acknowledged nor announced again`() = runTest {
        val handler = handler()
        val arrivals = arrivalsOf(handler)
        val packet = direct("hello")

        handler.handle(packet)
        handler.handle(packet)

        assertEquals(1, originated.size)
        assertEquals(1, arrivals.size)
    }

    @Test
    fun `a broadcast goes to the broadcast thread and is never acknowledged`() = runTest {
        val handler = handler()
        val packet = broadcast("to all")

        handler.handle(packet)

        assertEquals(BROADCAST_CONVERSATION_ID, store.rows.getValue(packet.messageId.toHexId()).conversationId)
        assertTrue(originated.isEmpty())
    }

    @Test
    fun `our own broadcast flooding back is ignored`() = runTest {
        val handler = handler()

        handler.handle(broadcast("mine", from = self.peerId))

        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `a message deleted here is not stored again or acknowledged`() = runTest {
        val handler = handler()
        val packet = direct("gone")
        store.deleted += packet.messageId.toHexId()

        handler.handle(packet)

        assertTrue(store.rows.isEmpty())
        assertTrue(originated.isEmpty())
    }

    @Test
    fun `a blocked sender's message is dropped without a receipt`() = runTest {
        val handler = handler()
        val arrivals = arrivalsOf(handler)
        store.blocked += aliceHex

        handler.handle(direct("spam"))

        assertTrue(store.rows.isEmpty())
        assertTrue(originated.isEmpty())
        assertTrue(arrivals.isEmpty())
    }

    @Test
    fun `a direct message that will not open shows the placeholder`() = runTest {
        val handler = handler()
        // Not marked sealed, and not valid UTF-8 either: real ciphertext we hold no key for.
        val packet = direct("x").copy(payload = byteArrayOf(0xC3.toByte(), 0x28))

        handler.handle(packet)

        assertEquals(MessagePacket.UNDECRYPTABLE_BODY, store.rows.getValue(packet.messageId.toHexId()).body)
    }

    @Test
    fun `a receipt from the addressee marks our message delivered`() = runTest {
        val handler = handler()
        val ours = store.outgoing(to = aliceHex)

        handler.handle(receipt(ours, from = alice, hops = 2))

        assertEquals(DeliveryState.DELIVERED, store.rows.getValue(ours).deliveryState)
        assertEquals(2, store.rows.getValue(ours).hops)
    }

    @Test
    fun `a receipt from a relay is ignored`() = runTest {
        val handler = handler()
        val ours = store.outgoing(to = aliceHex)

        handler.handle(receipt(ours, from = mallory, hops = 1))

        assertEquals(DeliveryState.SENT, store.rows.getValue(ours).deliveryState)
    }

    @Test
    fun `an impossible hop count in a receipt is stored as unknown`() = runTest {
        val handler = handler()
        val ours = store.outgoing(to = aliceHex)

        handler.handle(receipt(ours, from = alice, hops = 200))

        assertEquals(DeliveryState.DELIVERED, store.rows.getValue(ours).deliveryState)
        assertNull(store.rows.getValue(ours).hops)
    }

    @Test
    fun `an announcement files both keys`() = runTest {
        val handler = handler()
        val announcement = Announcement(ByteArray(32) { 7 }, ByteArray(32) { 9 })

        handler.handle(announcementPacket(newMessageId().hexIdToBytes(), announcement, timestamp = 1L))

        assertArrayEquals(announcement.agreementKey, directory.agreementKeyFor(announcement.peerId))
        val seen = store.seen.getValue(announcement.peerId.toHexId())
        assertArrayEquals(announcement.signingKey, seen.publicKey)
        assertArrayEquals(announcement.agreementKey, seen.agreementKey)
    }

    @Test
    fun `a profile files the name the peer chose`() = runTest {
        val handler = handler()
        val profile = Profile.fromInput("Maya")!!

        handler.handle(profilePacket(newMessageId().hexIdToBytes(), alice, profile, timestamp = 1L))

        assertEquals("Maya", store.seen.getValue(aliceHex).displayName)
    }

    @Test
    fun `an image arriving in pieces is stored once and acknowledged`() = runTest {
        val handler = handler()
        val arrivals = arrivalsOf(handler)
        val messageId = newMessageId()
        val pieces = imageFragments(messageId, ByteArray(500) { it.toByte() })

        pieces.forEach { handler.handle(it) }
        // A straggler from a slower route, after the image is complete.
        handler.handle(pieces.first())

        val row = store.rows.getValue(messageId)
        assertEquals("$messageId.jpg", row.attachmentFile)
        assertEquals(500, store.images.getValue(messageId).size)
        assertEquals(TransferState.COMPLETE, transfers.transfers.value.getValue(messageId).state)
        assertEquals(1, originated.size)
        assertTrue(arrivals.single().isImage)
    }

    @Test
    fun `an oversized image is not stored`() = runTest {
        val handler = handler()
        val messageId = newMessageId()

        imageFragments(messageId, ByteArray(ImagePolicy.MAX_ENCODED_BYTES + 1)).forEach { handler.handle(it) }

        assertTrue(store.rows.isEmpty())
        assertTrue(store.images.isEmpty())
        assertTrue(originated.isEmpty())
    }

    @Test
    fun `blocking partway through a transfer stops it before the image is written`() = runTest {
        val handler = handler()
        val messageId = newMessageId()
        val pieces = imageFragments(messageId, ByteArray(500))

        handler.handle(pieces.first())
        store.blocked += aliceHex
        pieces.drop(1).forEach { handler.handle(it) }

        assertTrue(store.images.isEmpty())
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `the shortest fresh path wins and a stale one is replaced`() = runTest {
        val handler = handler()

        handler.handle(broadcast("a", id = newMessageId()).copy(ttl = 3))
        handler.handle(broadcast("b", id = newMessageId()).copy(ttl = Packet.DEFAULT_TTL))
        assertEquals(1, handler.hopObservations.value.getValue(aliceHex).hops)

        handler.handle(broadcast("c", id = newMessageId()).copy(ttl = 3))
        assertEquals(1, handler.hopObservations.value.getValue(aliceHex).hops, "a longer path does not win")

        now += 2 * 60 * 1000L
        handler.handle(broadcast("d", id = newMessageId()).copy(ttl = 3))
        assertEquals(4, handler.hopObservations.value.getValue(aliceHex).hops, "an old reading ages out")
    }

    @Test
    fun `a forged ttl above the default records no distance`() = runTest {
        val handler = handler()

        handler.handle(broadcast("x").copy(ttl = 200))

        assertTrue(handler.hopObservations.value.isEmpty())
    }

    @Test
    fun `a receipt that cannot be sent does not lose the message`() = runTest {
        val handler = handler(originate = { error("nobody in range") })
        val packet = direct("hello")

        handler.handle(packet)

        assertEquals("hello", store.rows.getValue(packet.messageId.toHexId()).body)
    }

    private fun receipt(messageId: String, from: ByteArray, hops: Int) = acknowledgementPacket(
        messageId = newMessageId().hexIdToBytes(),
        senderId = from,
        recipientId = self.peerId,
        acknowledgement = Acknowledgement(messageId.hexIdToBytes(), hops),
        timestamp = 1L,
    )

    private fun imageFragments(messageId: String, jpeg: ByteArray): List<Packet> = Fragmenter.fragment(
        messageId = messageId.hexIdToBytes(),
        senderId = alice,
        recipientId = self.peerId,
        payload = byteArrayOf(SEALED) + Attachment.jpeg(jpeg).encode(),
        timestamp = 42L,
    )

    private class FakeIdentity(override val peerId: ByteArray) : LocalIdentity {
        override val publicKey: ByteArray = ByteArray(32)
        override val announcement: Announcement = Announcement(publicKey, ByteArray(32))
    }

    private class Seen(val displayName: String?, val publicKey: ByteArray?, val agreementKey: ByteArray?)

    private class FakeInboundStore : InboundStore {
        val rows = linkedMapOf<String, MessageEntity>()
        val images = mutableMapOf<String, ByteArray>()
        val seen = mutableMapOf<String, Seen>()
        val deleted = mutableSetOf<String>()
        val blocked = mutableSetOf<String>()

        /** One of our own direct messages, already on the air. */
        fun outgoing(to: String): String {
            val id = newMessageId()
            rows[id] = MessageEntity(
                id = id,
                conversationId = directConversationId(to),
                senderId = "self",
                body = "hi",
                timestamp = 1L,
                deliveryState = DeliveryState.SENT,
                isOutgoing = true,
            )
            return id
        }

        override fun isDeleted(messageId: String): Boolean = messageId in deleted

        override suspend fun isBlocked(peerHex: String): Boolean = peerHex in blocked

        override suspend fun recordSeen(
            peerHex: String,
            displayName: String?,
            publicKey: ByteArray?,
            agreementKey: ByteArray?,
        ) {
            val previous = seen[peerHex]
            seen[peerHex] = Seen(
                displayName ?: previous?.displayName,
                publicKey ?: previous?.publicKey,
                agreementKey ?: previous?.agreementKey,
            )
        }

        override suspend fun findMessage(messageId: String): MessageEntity? = rows[messageId]

        override suspend fun recordIncoming(
            messageId: String,
            conversationId: String,
            senderId: String,
            body: String,
            timestamp: Long,
            attachmentFile: String?,
        ): Boolean {
            if (messageId in rows) return false
            rows[messageId] = MessageEntity(
                id = messageId,
                conversationId = conversationId,
                senderId = senderId,
                body = body,
                timestamp = timestamp,
                deliveryState = DeliveryState.DELIVERED,
                isOutgoing = false,
                attachmentFile = attachmentFile,
            )
            return true
        }

        override suspend fun markDelivered(messageId: String, hops: Int?) {
            rows[messageId]?.let { rows[messageId] = it.copy(deliveryState = DeliveryState.DELIVERED, hops = hops) }
        }

        override suspend fun writeImage(messageId: String, jpeg: ByteArray): String? {
            images[messageId] = jpeg
            return "$messageId.jpg"
        }

        val timers = mutableMapOf<String, Long?>()
        val timerStamps = mutableMapOf<String, PeerTimerStamp>()

        override suspend fun peerTimer(peerHex: String): PeerTimerStamp? = timerStamps[peerHex]

        override suspend fun applyPeerTimer(peerHex: String, millis: Long?, stamp: PeerTimerStamp) {
            timers[peerHex] = millis
            timerStamps[peerHex] = stamp
        }
    }

    private fun timer(
        millis: Long?,
        sentAt: Long,
        from: ByteArray = alice,
        to: ByteArray = self.peerId,
        sealed: Boolean = true,
    ): Packet = timerPacket(
        messageId = newMessageId().hexIdToBytes(),
        senderId = from,
        recipientId = to,
        setting = TimerSetting.of(millis)!!,
        timestamp = sentAt,
    ).let { if (sealed) it.copy(payload = byteArrayOf(SEALED) + it.payload) else it }

    @Test
    fun `a contact's timer is applied to our chat with them`() = runTest {
        handler().handle(timer(HOUR, sentAt = 10L))

        assertEquals(HOUR, store.timers.getValue(aliceHex))
        assertEquals(PeerTimerStamp(sentAt = 10L, appliedAt = now), store.timerStamps[aliceHex])
        // Nothing to acknowledge: a timer is not a message.
        assertTrue(originated.isEmpty())
    }

    @Test
    fun `turning the timer off reaches us too`() = runTest {
        val handler = handler()
        handler.handle(timer(HOUR, sentAt = 10L))
        handler.handle(timer(null, sentAt = 20L))

        assertTrue(store.timers.containsKey(aliceHex))
        assertNull(store.timers.getValue(aliceHex))
    }

    @Test
    fun `an older change arriving late does not undo a newer one`() = runTest {
        val handler = handler()
        handler.handle(timer(null, sentAt = 20L))
        handler.handle(timer(HOUR, sentAt = 10L))

        assertNull(store.timers.getValue(aliceHex))
    }

    @Test
    fun `an unsealed, broadcast or blocked timer is ignored`() = runTest {
        val handler = handler()
        handler.handle(timer(HOUR, sentAt = 10L, sealed = false))
        handler.handle(timer(HOUR, sentAt = 11L, to = Packet.BROADCAST_ID))
        store.blocked += mallory.toHexId()
        handler.handle(timer(HOUR, sentAt = 12L, from = mallory))
        handler.handle(timer(HOUR, sentAt = 13L, from = self.peerId))

        assertTrue(store.timers.isEmpty())
    }

    private companion object {
        const val HOUR: Long = 60 * 60 * 1000L
        const val SEALED: Byte = 0x7F
    }
}
