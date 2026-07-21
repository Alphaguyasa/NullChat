package et.alpha.nchat.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationDaoTest {

    private lateinit var db: NChatDatabase
    private lateinit var conversations: ConversationDao
    private lateinit var messages: MessageDao

    private val convoId = "direct:$PEER_A"

    @Before
    fun setUp() {
        db = DatabaseTestFixtures.createDatabase()
        conversations = db.conversationDao()
        messages = db.messageDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insertsAndReadsBackAConversation() = runTest {
        conversations.insert(conversation(id = convoId, peerId = PEER_A))

        assertEquals(PEER_A, conversations.findById(convoId)?.peerId)
    }

    @Test
    fun storesTheBroadcastThreadWithNoPeer() = runTest {
        conversations.insert(
            conversation(id = BROADCAST_CONVERSATION_ID, peerId = null),
        )

        val stored = conversations.findById(BROADCAST_CONVERSATION_ID)
        assertNull(stored?.peerId)
    }

    @Test
    fun ignoresARepeatedInsertForTheSameThread() = runTest {
        conversations.insert(conversation(id = convoId, createdAt = T0))

        val rowId = conversations.insert(conversation(id = convoId, createdAt = T0 + 999))

        assertEquals(-1L, rowId)
        assertEquals(T0, conversations.findById(convoId)?.createdAt)
    }

    @Test
    fun summarisesCountsAndTheLastMessage() = runTest {
        conversations.insert(conversation(id = convoId, lastReadAt = T0))
        messages.insert(message(id = "a", timestamp = T0 - 10, isOutgoing = false))
        messages.insert(message(id = "b", timestamp = T0 + 10, isOutgoing = false, body = "unread one"))
        messages.insert(message(id = "c", timestamp = T0 + 20, isOutgoing = false, body = "unread two"))
        messages.insert(message(id = "d", timestamp = T0 + 30, isOutgoing = true, senderId = SELF, body = "mine"))

        val summary = conversations.observeSummaries().first().single()

        assertEquals(4, summary.messageCount)
        // Only inbound messages newer than lastReadAt count as unread.
        assertEquals(2, summary.unreadCount)
        assertEquals("mine", summary.lastMessageBody)
        assertEquals(T0 + 30, summary.lastMessageAt)
    }

    @Test
    fun summarisesAnEmptyThreadWithoutInventingAMessage() = runTest {
        conversations.insert(conversation(id = convoId))

        val summary = conversations.observeSummaries().first().single()

        assertEquals(0, summary.messageCount)
        assertEquals(0, summary.unreadCount)
        assertNull(summary.lastMessageBody)
        assertNull(summary.lastMessageAt)
    }

    @Test
    fun ordersThreadsByMostRecentActivity() = runTest {
        val a = "direct:$PEER_A"
        val b = "direct:$PEER_B"
        conversations.insert(conversation(id = a, peerId = PEER_A, lastActivityAt = T0))
        conversations.insert(conversation(id = b, peerId = PEER_B, lastActivityAt = T0 + 100))

        assertEquals(
            listOf(b, a),
            conversations.observeSummaries().first().map { it.conversation.id },
        )

        conversations.bumpActivity(a, T0 + 200)

        assertEquals(
            listOf(a, b),
            conversations.observeSummaries().first().map { it.conversation.id },
        )
    }

    @Test
    fun bumpActivityNeverMovesAThreadBackwards() = runTest {
        // Mesh messages arrive out of order; a late delivery of an old message
        // must not make a stale thread look freshly active.
        conversations.insert(conversation(id = convoId, lastActivityAt = T0 + 100))

        val updated = conversations.bumpActivity(convoId, T0)

        assertEquals(0, updated)
        assertEquals(T0 + 100, conversations.findById(convoId)?.lastActivityAt)
    }

    @Test
    fun markReadUpToClearsUnreadAndNeverRewinds() = runTest {
        conversations.insert(conversation(id = convoId, lastReadAt = 0))
        messages.insert(message(id = "a", timestamp = T0 + 10, isOutgoing = false))

        conversations.markReadUpTo(convoId, T0 + 50)
        assertEquals(0, conversations.observeSummaries().first().single().unreadCount)

        // A stale read-marker from an older UI state must not resurrect the badge.
        val rewound = conversations.markReadUpTo(convoId, T0)
        assertEquals(0, rewound)
        assertEquals(T0 + 50, conversations.findById(convoId)?.lastReadAt)
    }

    @Test
    fun deletesAThreadAndReportsWhetherOneMatched() = runTest {
        conversations.insert(conversation(id = convoId))

        assertEquals(1, conversations.deleteById(convoId))
        assertEquals(0, conversations.deleteById(convoId))
        assertNull(conversations.findById(convoId))
    }
}
