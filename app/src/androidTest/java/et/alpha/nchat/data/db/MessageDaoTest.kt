package et.alpha.nchat.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import et.alpha.nchat.data.db.DatabaseTestFixtures.PEER_A
import et.alpha.nchat.data.db.DatabaseTestFixtures.PEER_B
import et.alpha.nchat.data.db.DatabaseTestFixtures.SELF
import et.alpha.nchat.data.db.DatabaseTestFixtures.T0
import et.alpha.nchat.data.db.DatabaseTestFixtures.conversation
import et.alpha.nchat.data.db.DatabaseTestFixtures.message
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {

    private lateinit var db: NChatDatabase
    private lateinit var messages: MessageDao
    private lateinit var conversations: ConversationDao

    private val convoId = "direct:$PEER_A"

    @Before
    fun setUp() = runTest {
        db = DatabaseTestFixtures.createDatabase()
        messages = db.messageDao()
        conversations = db.conversationDao()
        conversations.insert(conversation(id = convoId))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insertsAndReadsBackAMessage() = runTest {
        val original = message(id = "m1", body = "meet at the bridge")

        val rowId = messages.insert(original)

        assertNotEquals(-1L, rowId)
        assertEquals(original, messages.findById("m1"))
    }

    @Test
    fun preservesDeliveryStateAcrossTheEnumConverter() = runTest {
        DeliveryState.entries.forEachIndexed { i, state ->
            messages.insert(message(id = "state$i", deliveryState = state))
            assertEquals(state, messages.findById("state$i")?.deliveryState)
        }
    }

    @Test
    fun ignoresADuplicateMessageIdAndReportsIt() = runTest {
        // The same message reaching us by a second mesh path must not create a
        // second row, and the caller must be able to tell that it was a replay.
        messages.insert(message(id = "m1", body = "original"))

        val replayRowId = messages.insert(message(id = "m1", body = "replayed"))

        assertEquals(-1L, replayRowId)
        assertEquals(1, messages.countIn(convoId))
        assertEquals("original", messages.findById("m1")?.body)
    }

    @Test
    fun ordersByArrivalAndBreaksTiesDeterministically() = runTest {
        // Arrival times collide within a millisecond; ordering must still be
        // stable or the thread visibly reshuffles between redraws.
        messages.insert(message(id = "c", timestamp = T0 + 5))
        messages.insert(message(id = "a", timestamp = T0 + 5))
        messages.insert(message(id = "b", timestamp = T0 + 1))

        val ordered = messages.observeConversation(convoId).first()

        assertEquals(listOf("b", "a", "c"), ordered.map { it.id })
    }

    @Test
    fun ordersByThisPhonesClockNotTheSenders() = runTest {
        // The bug: a sender a year fast stayed below every later reply.
        messages.insert(message(id = "fast", timestamp = T0 + YEAR, receivedAt = T0))
        messages.insert(message(id = "reply", timestamp = T0 + 1_000, receivedAt = T0 + 1_000))

        assertEquals(listOf("fast", "reply"), messages.observeConversation(convoId).first().map { it.id })
        assertEquals("reply", messages.findLatestIn(convoId)?.id)
    }

    @Test
    fun observeConversationEmitsAgainWhenAMessageArrives() = runTest {
        messages.observeConversation(convoId).test {
            assertEquals(emptyList<MessageEntity>(), awaitItem())

            messages.insert(message(id = "m1"))

            assertEquals(listOf("m1"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun scopesMessagesToTheirOwnConversation() = runTest {
        val other = "direct:other"
        conversations.insert(conversation(id = other, peerId = "other"))
        messages.insert(message(id = "mine", conversationId = convoId))
        messages.insert(message(id = "theirs", conversationId = other))

        assertEquals(listOf("mine"), messages.observeConversation(convoId).first().map { it.id })
        assertEquals(listOf("theirs"), messages.observeConversation(other).first().map { it.id })
    }

    @Test
    fun findInReturnsOnlyThatThreadsRows() = runTest {
        // Delete chat reads this list; a row from another thread here would be
        // deleted along with it.
        val other = "direct:other"
        conversations.insert(conversation(id = other, peerId = "other"))
        messages.insert(message(id = "mine1", conversationId = convoId))
        messages.insert(message(id = "mine2", conversationId = convoId))
        messages.insert(message(id = "theirs", conversationId = other))

        assertEquals(setOf("mine1", "mine2"), messages.findIn(convoId).map { it.id }.toSet())
    }

    @Test
    fun updatesDeliveryStateAndReportsWhetherARowMatched() = runTest {
        messages.insert(message(id = "m1", deliveryState = DeliveryState.PENDING))

        assertEquals(1, messages.updateDeliveryState("m1", DeliveryState.SENT))
        assertEquals(DeliveryState.SENT, messages.findById("m1")?.deliveryState)
        assertEquals(0, messages.updateDeliveryState("absent", DeliveryState.SENT))
    }

    @Test
    fun advanceDeliveryStateRefusesToWalkBackALaterState() = runTest {
        // An ACK can beat the transport's own send callback. When it does, the
        // late PENDING->SENT must not overwrite the DELIVERED already recorded.
        messages.insert(
            message(id = "m1", isOutgoing = true, deliveryState = DeliveryState.DELIVERED),
        )

        val updated = messages.advanceDeliveryState("m1", DeliveryState.PENDING, DeliveryState.SENT)

        assertEquals(0, updated)
        assertEquals(DeliveryState.DELIVERED, messages.findById("m1")?.deliveryState)
    }

    @Test
    fun advanceDeliveryStateAppliesWhenTheCurrentStateMatches() = runTest {
        messages.insert(
            message(id = "m1", isOutgoing = true, deliveryState = DeliveryState.PENDING),
        )

        val updated = messages.advanceDeliveryState("m1", DeliveryState.PENDING, DeliveryState.SENT)

        assertEquals(1, updated)
        assertEquals(DeliveryState.SENT, messages.findById("m1")?.deliveryState)
    }

    @Test
    fun markDeliveredStoresTheReportedHops() = runTest {
        messages.insert(message(id = "m1", isOutgoing = true, deliveryState = DeliveryState.SENT))

        assertEquals(1, messages.markDelivered("m1", DeliveryState.DELIVERED, hops = 2))

        val stored = messages.findById("m1")
        assertEquals(DeliveryState.DELIVERED, stored?.deliveryState)
        assertEquals(2, stored?.hops)
    }

    @Test
    fun markDeliveredKeepsAKnownCountOverAnUnknownOne() = runTest {
        messages.insert(message(id = "m1", isOutgoing = true, deliveryState = DeliveryState.SENT))
        messages.markDelivered("m1", DeliveryState.DELIVERED, hops = 2)

        messages.markDelivered("m1", DeliveryState.DELIVERED, hops = null)

        assertEquals(2, messages.findById("m1")?.hops)
    }

    @Test
    fun markDeliveredKeepsTheShortestRoute() = runTest {
        messages.insert(message(id = "m1", isOutgoing = true, deliveryState = DeliveryState.SENT))
        messages.markDelivered("m1", DeliveryState.DELIVERED, hops = 2)

        messages.markDelivered("m1", DeliveryState.DELIVERED, hops = 4)
        assertEquals(2, messages.findById("m1")?.hops)

        messages.markDelivered("m1", DeliveryState.DELIVERED, hops = 1)
        assertEquals(1, messages.findById("m1")?.hops)
    }

    @Test
    fun markDeliveredWithoutACountLeavesHopsUnknown() = runTest {
        messages.insert(message(id = "m1", isOutgoing = true, deliveryState = DeliveryState.SENT))

        messages.markDelivered("m1", DeliveryState.DELIVERED, hops = null)

        assertEquals(DeliveryState.DELIVERED, messages.findById("m1")?.deliveryState)
        assertNull(messages.findById("m1")?.hops)
    }

    @Test
    fun findsOutgoingMessagesAwaitingATransport() = runTest {
        messages.insert(
            message(id = "out1", isOutgoing = true, deliveryState = DeliveryState.PENDING, timestamp = T0 + 2),
        )
        messages.insert(
            message(id = "out0", isOutgoing = true, deliveryState = DeliveryState.PENDING, timestamp = T0 + 1),
        )
        messages.insert(
            message(id = "sent", isOutgoing = true, deliveryState = DeliveryState.SENT),
        )
        // An inbound message is never "pending" for us to send, whatever its state.
        messages.insert(
            message(id = "in", isOutgoing = false, deliveryState = DeliveryState.PENDING),
        )

        val pending = messages.findOutgoingByState(DeliveryState.PENDING, limit = 100)

        assertEquals(listOf("out0", "out1"), pending.map { it.id })
    }

    @Test
    fun expireOutgoingFailsOnlyOurStalePendingRows() = runTest {
        messages.insert(
            message(id = "stale", isOutgoing = true, deliveryState = DeliveryState.PENDING, timestamp = T0 - 1),
        )
        messages.insert(
            message(id = "edge", isOutgoing = true, deliveryState = DeliveryState.PENDING, timestamp = T0),
        )
        messages.insert(
            message(id = "fresh", isOutgoing = true, deliveryState = DeliveryState.PENDING, timestamp = T0 + 1),
        )
        // Already went out: an old SENT row is history, not a stuck one.
        messages.insert(
            message(id = "sent", isOutgoing = true, deliveryState = DeliveryState.SENT, timestamp = T0 - 1),
        )
        messages.insert(
            message(id = "in", isOutgoing = false, deliveryState = DeliveryState.PENDING, timestamp = T0 - 1),
        )

        val expired = messages.expireOutgoing(T0, DeliveryState.PENDING, DeliveryState.FAILED)

        assertEquals(2, expired)
        assertEquals(DeliveryState.FAILED, messages.findById("stale")?.deliveryState)
        assertEquals(DeliveryState.FAILED, messages.findById("edge")?.deliveryState)
        assertEquals(DeliveryState.PENDING, messages.findById("fresh")?.deliveryState)
        assertEquals(DeliveryState.SENT, messages.findById("sent")?.deliveryState)
        assertEquals(DeliveryState.PENDING, messages.findById("in")?.deliveryState)
    }

    @Test
    fun moveOutgoingFailsOnlyOurPendingRowsAmongTheIds() = runTest {
        messages.insert(message(id = "evicted", isOutgoing = true, deliveryState = DeliveryState.PENDING))
        // Reached a relay before its queued copy was dropped: it did go out.
        messages.insert(message(id = "relayed", isOutgoing = true, deliveryState = DeliveryState.SENT))
        messages.insert(message(id = "theirs", isOutgoing = false, deliveryState = DeliveryState.PENDING))
        messages.insert(message(id = "other", isOutgoing = true, deliveryState = DeliveryState.PENDING))

        val failed = messages.moveOutgoing(
            listOf("evicted", "relayed", "theirs", "carried-for-someone"),
            DeliveryState.PENDING,
            DeliveryState.FAILED,
        )

        assertEquals(1, failed)
        assertEquals(DeliveryState.FAILED, messages.findById("evicted")?.deliveryState)
        assertEquals(DeliveryState.SENT, messages.findById("relayed")?.deliveryState)
        assertEquals(DeliveryState.PENDING, messages.findById("theirs")?.deliveryState)
        assertEquals(DeliveryState.PENDING, messages.findById("other")?.deliveryState)
    }

    @Test
    fun pendingInOneThreadIsFoundBehindAHundredOlderElsewhere() = runTest {
        // The cross-thread lookup returns only the oldest 100, so a thread whose
        // held message is newer than all of these was never released.
        conversations.insert(conversation(id = "direct:$PEER_B", peerId = PEER_B))
        repeat(120) { i ->
            messages.insert(
                message(
                    id = "old$i",
                    conversationId = "direct:$PEER_B",
                    isOutgoing = true,
                    deliveryState = DeliveryState.PENDING,
                    timestamp = T0 + i,
                ),
            )
        }
        messages.insert(
            message(id = "held", isOutgoing = true, deliveryState = DeliveryState.PENDING, timestamp = T0 + 500),
        )
        messages.insert(message(id = "gone", isOutgoing = true, deliveryState = DeliveryState.SENT))

        val pending = messages.findOutgoingInConversation("direct:$PEER_A", DeliveryState.PENDING)

        assertEquals(listOf("held"), pending.map { it.id })
        assertEquals(
            setOf("direct:$PEER_A", "direct:$PEER_B"),
            messages.conversationsWithOutgoing(DeliveryState.PENDING).toSet(),
        )
    }

    @Test
    fun honoursTheLimitOnPendingLookups() = runTest {
        repeat(5) {
            messages.insert(
                message(
                    id = "out$it",
                    isOutgoing = true,
                    deliveryState = DeliveryState.PENDING,
                    timestamp = T0 + it,
                ),
            )
        }

        assertEquals(2, messages.findOutgoingByState(DeliveryState.PENDING, limit = 2).size)
    }

    @Test
    fun countsOnlyInboundMessagesAsUnread() = runTest {
        messages.insert(message(id = "old", timestamp = T0 - 10, isOutgoing = false))
        messages.insert(message(id = "new", timestamp = T0 + 10, isOutgoing = false))
        messages.insert(message(id = "mine", timestamp = T0 + 20, isOutgoing = true, senderId = SELF))

        assertEquals(1, messages.observeUnreadCount(convoId, since = T0).first())
    }

    @Test
    fun rejectsAMessageForAConversationThatDoesNotExist() = runTest {
        // The foreign key is what stops orphaned history accumulating from
        // packets for threads we never created.
        val error = runCatching {
            messages.insert(message(id = "orphan", conversationId = "no-such-thread"))
        }.exceptionOrNull()

        assertTrue(
            "expected a foreign-key violation, got $error",
            error is SQLiteConstraintException,
        )
    }

    @Test
    fun deletesMessagesWhenTheirConversationGoes() = runTest {
        messages.insert(message(id = "m1"))
        messages.insert(message(id = "m2"))

        conversations.deleteById(convoId)

        assertEquals(0, messages.countIn(convoId))
        assertNull(messages.findById("m1"))
    }

    @Test
    fun prunesHistoryOlderThanACutoff() = runTest {
        messages.insert(message(id = "ancient", timestamp = T0 - 1000))
        messages.insert(message(id = "recent", timestamp = T0 + 1000))

        val deleted = messages.deleteOlderThan(cutoff = T0)

        assertEquals(1, deleted)
        assertEquals(listOf("recent"), messages.observeConversation(convoId).first().map { it.id })
    }

    @Test
    fun findsTheLatestMessageInAConversation() = runTest {
        messages.insert(message(id = "a", timestamp = T0 + 1))
        messages.insert(message(id = "b", timestamp = T0 + 9, body = "newest"))
        messages.insert(message(id = "c", timestamp = T0 + 5))

        assertEquals("newest", messages.findLatestIn(convoId)?.body)
    }

    @Test
    fun returnsNullForAnUnknownMessage() = runTest {
        assertNull(messages.findById("nope"))
    }

    @Test
    fun deletesASingleMessageById() = runTest {
        messages.insert(message(id = "m1"))

        assertEquals(1, messages.deleteById("m1"))
        assertEquals(0, messages.deleteById("m1"))
        assertTrue(messages.observeConversation(convoId).first().isEmpty())
    }

    private companion object {
        const val YEAR = 365L * 24 * 60 * 60 * 1000
    }
}
