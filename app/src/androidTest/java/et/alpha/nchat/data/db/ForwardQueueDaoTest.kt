package et.alpha.nchat.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import et.alpha.nchat.data.db.DatabaseTestFixtures.BROADCAST
import et.alpha.nchat.data.db.DatabaseTestFixtures.PEER_A
import et.alpha.nchat.data.db.DatabaseTestFixtures.PEER_B
import et.alpha.nchat.data.db.DatabaseTestFixtures.T0
import et.alpha.nchat.data.db.DatabaseTestFixtures.forwardQueueEntry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The forward queue's SQL against real SQLite.
 *
 * `ForwardQueueTest` on the JVM covers the same rules against an in-memory fake;
 * this is what proves the fake and the database actually agree, in particular on
 * eviction order and the expiry boundary.
 */
@RunWith(AndroidJUnit4::class)
class ForwardQueueDaoTest {

    private lateinit var db: NChatDatabase
    private lateinit var queue: ForwardQueueDao

    @Before
    fun setUp() {
        db = DatabaseTestFixtures.createDatabase()
        queue = db.forwardQueueDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insertsAndReadsBackAnEntry() = runTest {
        val entry = forwardQueueEntry(messageId = "m1", packet = byteArrayOf(9, 8, 7))
        assertTrue(queue.insert(entry) > 0)

        val stored = queue.findFor(listOf(PEER_A)).single()
        assertEquals("m1", stored.messageId)
        assertEquals(PEER_A, stored.recipientId)
        assertArrayEqualsMessage(byteArrayOf(9, 8, 7), stored.packet)
    }

    @Test
    fun insertIgnoresADuplicateMessageId() = runTest {
        queue.insert(forwardQueueEntry(messageId = "m1", queuedAt = T0))
        val second = queue.insert(forwardQueueEntry(messageId = "m1", queuedAt = T0 + 5_000))

        assertEquals("a duplicate insert should be ignored", -1L, second)
        assertEquals(1, queue.count())
        // The original queuedAt must survive, or a repeatedly re-offered packet
        // would keep renewing its own expiry window.
        assertEquals(T0, queue.findFor(listOf(PEER_A)).single().queuedAt)
    }

    @Test
    fun findForReturnsOldestFirstAndOnlyTheRequestedRecipients() = runTest {
        queue.insert(forwardQueueEntry(messageId = "young", recipientId = PEER_A, queuedAt = T0 + 100))
        queue.insert(forwardQueueEntry(messageId = "old", recipientId = PEER_A, queuedAt = T0))
        queue.insert(forwardQueueEntry(messageId = "other", recipientId = PEER_B, queuedAt = T0))

        val found = queue.findFor(listOf(PEER_A))

        assertEquals(listOf("old", "young"), found.map { it.messageId })
    }

    @Test
    fun findNewestReturnsNewestFirstAcrossRecipientsUpToTheLimit() = runTest {
        queue.insert(forwardQueueEntry(messageId = "old", recipientId = PEER_A, queuedAt = T0))
        queue.insert(forwardQueueEntry(messageId = "mid", recipientId = PEER_B, queuedAt = T0 + 1))
        queue.insert(forwardQueueEntry(messageId = "new", recipientId = BROADCAST, queuedAt = T0 + 2))

        assertEquals(listOf("new", "mid"), queue.findNewest(2).map { it.messageId })
    }

    @Test
    fun findForPicksUpBroadcastsAlongsideDirectEntries() = runTest {
        queue.insert(forwardQueueEntry(messageId = "direct", recipientId = PEER_A, queuedAt = T0))
        queue.insert(forwardQueueEntry(messageId = "everyone", recipientId = BROADCAST, queuedAt = T0 + 1))

        val found = queue.findFor(listOf(PEER_A, BROADCAST))

        assertEquals(listOf("direct", "everyone"), found.map { it.messageId })
    }

    @Test
    fun deleteQueuedAtOrBeforeIncludesTheBoundary() = runTest {
        queue.insert(forwardQueueEntry(messageId = "expired", queuedAt = T0))
        queue.insert(forwardQueueEntry(messageId = "live", queuedAt = T0 + 1))

        // At-or-before, matching SeenCache's `now - queuedAt >= expiry`.
        val deleted = queue.deleteQueuedAtOrBefore(T0)

        assertEquals(1, deleted)
        assertEquals(listOf("live"), queue.findAll().map { it.messageId })
    }

    @Test
    fun trimToNewestEvictsTheOldestFirst() = runTest {
        repeat(5) { i ->
            queue.insert(forwardQueueEntry(messageId = "m$i", queuedAt = T0 + i))
        }

        val evicted = queue.trimToNewest(3)

        assertEquals(2, evicted)
        assertEquals(listOf("m2", "m3", "m4"), queue.findAll().map { it.messageId })
    }

    @Test
    fun trimToNewestLeavesAQueueUnderCapAlone() = runTest {
        repeat(3) { i -> queue.insert(forwardQueueEntry(messageId = "m$i", queuedAt = T0 + i)) }

        assertEquals(0, queue.trimToNewest(500))
        assertEquals(3, queue.count())
    }

    @Test
    fun deleteByIdsRemovesOnlyWhatWasAsked() = runTest {
        repeat(3) { i -> queue.insert(forwardQueueEntry(messageId = "m$i", queuedAt = T0 + i)) }

        assertEquals(2, queue.deleteByIds(listOf("m0", "m2")))
        assertEquals(listOf("m1"), queue.findAll().map { it.messageId })
    }

    private fun assertArrayEqualsMessage(expected: ByteArray, actual: ByteArray) =
        assertEquals(expected.toList(), actual.toList())
}
