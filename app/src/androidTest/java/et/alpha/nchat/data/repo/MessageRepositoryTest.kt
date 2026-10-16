package et.alpha.nchat.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import et.alpha.nchat.data.db.DatabaseTestFixtures
import et.alpha.nchat.data.db.DatabaseTestFixtures.PEER_A
import et.alpha.nchat.data.db.DatabaseTestFixtures.SELF
import et.alpha.nchat.data.db.DatabaseTestFixtures.T0
import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.NChatDatabase
import et.alpha.nchat.data.db.directConversationId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageRepositoryTest {

    private lateinit var db: NChatDatabase
    private lateinit var repo: MessageRepository

    private val convoId = directConversationId(PEER_A)

    @Before
    fun setUp() {
        db = DatabaseTestFixtures.createDatabase()
        repo = MessageRepository(db, db.messageDao(), db.conversationDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun recordOutgoingCreatesTheThreadOnFirstMessage() = runTest {
        // Nothing pre-creates conversations; the first message has to, or the
        // foreign key rejects it.
        val message = repo.recordOutgoing(
            conversationId = convoId,
            selfId = SELF,
            body = "on my way",
            peerId = PEER_A,
            now = T0,
        )

        assertEquals(DeliveryState.PENDING, message.deliveryState)
        assertTrue(message.isOutgoing)
        assertNotNull(repo.findMessage(message.id))
        assertEquals(1, repo.observeConversations().first().size)
    }

    @Test
    fun recordOutgoingMintsDistinctMessageIds() = runTest {
        val a = repo.recordOutgoing(convoId, SELF, "one", PEER_A, now = T0)
        val b = repo.recordOutgoing(convoId, SELF, "two", PEER_A, now = T0)

        assertEquals(32, a.id.length)
        assertTrue("ids must be unique", a.id != b.id)
    }

    @Test
    fun recordIncomingReportsNewTrafficOnce() = runTest {
        val isNew = repo.recordIncoming(
            messageId = "m1",
            conversationId = convoId,
            senderId = PEER_A,
            body = "hello",
            timestamp = T0,
        )

        assertTrue(isNew)
    }

    @Test
    fun recordIncomingReportsAReplayAsNotNew() = runTest {
        // Flooding delivers the same message by several routes. The second copy
        // must be reported as a duplicate so it does not raise a notification.
        repo.recordIncoming("m1", convoId, PEER_A, "hello", T0)

        val isNew = repo.recordIncoming("m1", convoId, PEER_A, "hello", T0)

        assertFalse(isNew)
        assertEquals(1, repo.observeMessages(convoId).first().size)
    }

    @Test
    fun aReplayDoesNotBumpThreadActivity() = runTest {
        repo.recordIncoming("m1", convoId, PEER_A, "hello", timestamp = T0)
        val activityAfterFirst =
            repo.observeConversations().first().single().conversation.lastActivityAt

        repo.recordIncoming("m1", convoId, PEER_A, "hello", timestamp = T0 + 10_000)

        assertEquals(
            activityAfterFirst,
            repo.observeConversations().first().single().conversation.lastActivityAt,
        )
    }

    @Test
    fun markSentAdvancesAPendingMessage() = runTest {
        val message = repo.recordOutgoing(convoId, SELF, "hi", PEER_A, now = T0)

        assertTrue(repo.markSent(message.id))
        assertEquals(DeliveryState.SENT, repo.findMessage(message.id)?.deliveryState)
    }

    @Test
    fun markSentWillNotOverwriteAnAckThatAlreadyArrived() = runTest {
        // ACKs routinely beat the transport's own send callback in a mesh.
        val message = repo.recordOutgoing(convoId, SELF, "hi", PEER_A, now = T0)
        repo.markDelivered(message.id)

        assertFalse(repo.markSent(message.id))
        assertEquals(DeliveryState.DELIVERED, repo.findMessage(message.id)?.deliveryState)
    }

    @Test
    fun listsOnlyPendingOutgoingMessages() = runTest {
        val pending = repo.recordOutgoing(convoId, SELF, "waiting", PEER_A, now = T0)
        val sent = repo.recordOutgoing(convoId, SELF, "gone", PEER_A, now = T0 + 1)
        repo.markSent(sent.id)
        repo.recordIncoming("in", convoId, PEER_A, "inbound", T0 + 2)

        assertEquals(listOf(pending.id), repo.pendingOutgoing().map { it.id })
    }

    @Test
    fun markFailedRecordsGivingUp() = runTest {
        val message = repo.recordOutgoing(convoId, SELF, "hi", PEER_A, now = T0)

        assertTrue(repo.markFailed(message.id))
        assertEquals(DeliveryState.FAILED, repo.findMessage(message.id)?.deliveryState)
    }

    @Test
    fun markReadClearsTheUnreadCount() = runTest {
        repo.recordIncoming("m1", convoId, PEER_A, "hello", timestamp = T0, now = T0)
        assertEquals(1, repo.observeConversations().first().single().unreadCount)

        repo.markRead(convoId, at = T0 + 1)

        assertEquals(0, repo.observeConversations().first().single().unreadCount)
    }

    @Test
    fun aMessageFromASenderWhoseClockIsBehindStillCountsAsUnread() = runTest {
        // Read at T0 by our clock; the sender's clock says the new message was
        // written an hour earlier. It arrived after the read, so it is unread.
        repo.recordIncoming("m0", convoId, PEER_A, "earlier", timestamp = T0 - 10, now = T0 - 10)
        repo.markRead(convoId, at = T0)

        repo.recordIncoming("m1", convoId, PEER_A, "new", timestamp = T0 - HOUR, now = T0 + 1)

        assertEquals(1, repo.observeConversations().first().single().unreadCount)
    }

    @Test
    fun aMessageFromASenderWhoseClockIsAheadIsClearedByReading() = runTest {
        repo.recordIncoming("m1", convoId, PEER_A, "from the future", timestamp = T0 + HOUR, now = T0)

        repo.markRead(convoId, at = T0 + 1)

        assertEquals(0, repo.observeConversations().first().single().unreadCount)
    }

    @Test
    fun deletingAThreadRemovesItsMessages() = runTest {
        repo.recordIncoming("m1", convoId, PEER_A, "hello", T0)

        assertTrue(repo.deleteConversation(convoId))

        assertTrue(repo.observeMessages(convoId).first().isEmpty())
        assertTrue(repo.observeConversations().first().isEmpty())
    }

    @Test
    fun prunesOldHistory() = runTest {
        repo.recordIncoming("old", convoId, PEER_A, "ancient", timestamp = T0 - 10_000)
        repo.recordIncoming("new", convoId, PEER_A, "recent", timestamp = T0 + 10_000)

        assertEquals(1, repo.pruneOlderThan(cutoff = T0))
        assertEquals(listOf("new"), repo.observeMessages(convoId).first().map { it.id })
    }

    @Test
    fun observeMessagesReflectsBothDirectionsOfTheConversation() = runTest {
        repo.recordIncoming("in", convoId, PEER_A, "hello", timestamp = T0)
        repo.recordOutgoing(convoId, SELF, "hi back", PEER_A, messageId = "out", now = T0 + 1)

        val thread = repo.observeMessages(convoId).first()

        assertEquals(listOf("in", "out"), thread.map { it.id })
        assertEquals(listOf(false, true), thread.map { it.isOutgoing })
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
