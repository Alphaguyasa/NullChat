package et.alpha.nchat.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the upgrade path described in [NChatDatabase]'s migration policy.
 *
 * The version-1 test below has no migration to run yet, but it is not
 * decorative: it creates a database from the *committed schema JSON* and then
 * opens it with the compiled app code. If an entity is edited without bumping
 * the version, the identity hashes diverge and this fails — which is exactly the
 * mistake that would otherwise ship and crash on a user's existing database.
 *
 * When adding version 2, add a test that creates v1, writes rows, runs the
 * migration, and asserts the rows survived. Asserting only that the migration
 * executes proves nothing about the data.
 */
@RunWith(AndroidJUnit4::class)
class NChatDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NChatDatabase::class.java,
    )

    @Test
    fun version1SchemaMatchesTheCompiledEntities() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, peerId, title, createdAt, lastActivityAt, lastReadAt)
                VALUES ('direct:peer', 'peer', NULL, 1000, 1000, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO messages
                    (id, conversationId, senderId, body, timestamp, deliveryState, isOutgoing)
                VALUES ('m1', 'direct:peer', 'peer', 'survived', 1000, 'DELIVERED', 0)
                """.trimIndent(),
            )
        }

        // Opening with the real database class validates the identity hash.
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NChatDatabase::class.java,
            TEST_DB,
        ).addMigrations(*NChatDatabase.MIGRATIONS).build()

        try {
            val stored = runBlocking { db.messageDao().findById("m1") }
            assertEquals("survived", stored?.body)
            assertEquals(DeliveryState.DELIVERED, stored?.deliveryState)
        } finally {
            db.close()
        }
    }

    /**
     * The v1 → v2 upgrade that adds `forward_queue`.
     *
     * The migration is purely additive, so the point of the test is the part
     * that is easy to get wrong: that existing chat history is still there
     * afterwards. A migration that runs is not a migration that preserves data.
     */
    @Test
    fun migratingToVersion2AddsTheQueueAndKeepsHistory() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, peerId, title, createdAt, lastActivityAt, lastReadAt)
                VALUES ('direct:peer', 'peer', NULL, 1000, 1000, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO messages
                    (id, conversationId, senderId, body, timestamp, deliveryState, isOutgoing)
                VALUES ('m1', 'direct:peer', 'peer', 'survived', 1000, 'DELIVERED', 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO peers
                    (id, displayName, publicKey, isVerified, firstSeenAt, lastSeenAt)
                VALUES ('peer', NULL, NULL, 1, 500, 1000)
                """.trimIndent(),
            )
        }

        // Runs MIGRATION_1_2 and validates the result against schemas/2.json.
        helper.runMigrationsAndValidate(TEST_DB, 2, true, *NChatDatabase.MIGRATIONS).close()

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NChatDatabase::class.java,
            TEST_DB,
        ).addMigrations(*NChatDatabase.MIGRATIONS).build()

        try {
            runBlocking {
                val stored = db.messageDao().findById("m1")
                assertEquals("survived", stored?.body)
                assertEquals(DeliveryState.DELIVERED, stored?.deliveryState)

                // The verified flag is a security property; losing it in a
                // migration would silently downgrade a channel the user
                // confirmed by QR.
                assertEquals(true, db.peerDao().findById("peer")?.isVerified)

                // The new table exists and is usable, not merely declared.
                assertEquals(0, db.forwardQueueDao().count())
                db.forwardQueueDao().insert(
                    DatabaseTestFixtures.forwardQueueEntry(messageId = "q1"),
                )
                assertEquals(1, db.forwardQueueDao().count())
            }
        } finally {
            db.close()
        }
    }

    /**
     * The v2 → v3 upgrade that adds `messages.attachmentFile`.
     *
     * Two things are worth proving beyond "the column exists". First that chat
     * history survives — the same point as every migration test here. Second
     * that pre-existing rows read back with a *null* attachment rather than
     * failing to map: the column is added without a default, and a message
     * written before images existed genuinely has no image.
     */
    @Test
    fun migratingToVersion3AddsAttachmentsAndKeepsHistory() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, peerId, title, createdAt, lastActivityAt, lastReadAt)
                VALUES ('direct:peer', 'peer', NULL, 1000, 1000, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO messages
                    (id, conversationId, senderId, body, timestamp, deliveryState, isOutgoing)
                VALUES ('m1', 'direct:peer', 'peer', 'survived', 1000, 'DELIVERED', 0)
                """.trimIndent(),
            )
        }

        // Runs MIGRATION_2_3 and validates the result against schemas/3.json.
        helper.runMigrationsAndValidate(TEST_DB, 3, true, *NChatDatabase.MIGRATIONS).close()

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NChatDatabase::class.java,
            TEST_DB,
        ).addMigrations(*NChatDatabase.MIGRATIONS).build()

        try {
            runBlocking {
                val stored = db.messageDao().findById("m1")
                assertEquals("survived", stored?.body)
                assertEquals(DeliveryState.DELIVERED, stored?.deliveryState)
                // A message from before images existed has no image.
                assertEquals(null, stored?.attachmentFile)
                assertEquals(false, stored?.hasAttachment)

                // And the new column round-trips a value.
                db.messageDao().insert(
                    MessageEntity(
                        id = "m2",
                        conversationId = "direct:peer",
                        senderId = "peer",
                        body = "",
                        timestamp = 2000,
                        deliveryState = DeliveryState.DELIVERED,
                        isOutgoing = false,
                        attachmentFile = "m2.jpg",
                    ),
                )
                assertEquals("m2.jpg", db.messageDao().findById("m2")?.attachmentFile)
            }
        } finally {
            db.close()
        }
    }

    /**
     * The v3 → v4 upgrade that adds `peers.agreementKey`.
     *
     * Beyond history surviving, the peer row has to keep its signing key and
     * verified flag: losing either would silently downgrade a channel the user
     * confirmed by QR. The new column reads back null for an existing peer —
     * its agreement key was never stored — and round-trips once written.
     */
    @Test
    fun migratingToVersion4AddsAgreementKeysAndKeepsPeers() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, peerId, title, createdAt, lastActivityAt, lastReadAt)
                VALUES ('direct:peer', 'peer', NULL, 1000, 1000, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO messages
                    (id, conversationId, senderId, body, timestamp, deliveryState,
                     isOutgoing, attachmentFile)
                VALUES ('m1', 'direct:peer', 'peer', 'survived', 1000, 'DELIVERED', 0, NULL)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO peers
                    (id, displayName, publicKey, isVerified, firstSeenAt, lastSeenAt)
                VALUES ('peer', 'sam', X'0707070707070707070707070707070707070707070707070707070707070707',
                        1, 500, 1000)
                """.trimIndent(),
            )
        }

        // Runs MIGRATION_3_4 and validates the result against schemas/4.json.
        helper.runMigrationsAndValidate(TEST_DB, 4, true, *NChatDatabase.MIGRATIONS).close()

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NChatDatabase::class.java,
            TEST_DB,
        ).addMigrations(*NChatDatabase.MIGRATIONS).build()

        try {
            runBlocking {
                assertEquals("survived", db.messageDao().findById("m1")?.body)

                val peer = db.peerDao().findById("peer")
                assertEquals("sam", peer?.displayName)
                assertEquals(true, peer?.isVerified)
                assertArrayEquals(ByteArray(32) { 7 }, peer?.publicKey)
                assertNull(peer?.agreementKey)

                val key = ByteArray(32) { 8 }
                db.peerDao().recordSeen(id = "peer", agreementKey = key, now = 2000)
                assertArrayEquals(key, db.peerDao().findById("peer")?.agreementKey)
            }
        } finally {
            db.close()
        }
    }

    /**
     * The v4 → v5 upgrade that adds `messages.hops`.
     *
     * An outgoing message delivered before this version must keep its state
     * and read back with no hop count — its receipt's count was never kept, so
     * the bubble shows plain `delivered` rather than an invented distance.
     */
    @Test
    fun migratingToVersion5AddsHopCountsAndKeepsHistory() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, peerId, title, createdAt, lastActivityAt, lastReadAt)
                VALUES ('direct:peer', 'peer', NULL, 1000, 1000, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO messages
                    (id, conversationId, senderId, body, timestamp, deliveryState,
                     isOutgoing, attachmentFile)
                VALUES ('m1', 'direct:peer', 'self', 'survived', 1000, 'DELIVERED', 1, NULL),
                       ('m2', 'direct:peer', 'self', 'still going', 2000, 'SENT', 1, NULL)
                """.trimIndent(),
            )
        }

        // Runs MIGRATION_4_5 and validates the result against schemas/5.json.
        helper.runMigrationsAndValidate(TEST_DB, 5, true, *NChatDatabase.MIGRATIONS).close()

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NChatDatabase::class.java,
            TEST_DB,
        ).addMigrations(*NChatDatabase.MIGRATIONS).build()

        try {
            runBlocking {
                val old = db.messageDao().findById("m1")
                assertEquals("survived", old?.body)
                assertEquals(DeliveryState.DELIVERED, old?.deliveryState)
                assertNull(old?.hops)

                db.messageDao().markDelivered("m2", DeliveryState.DELIVERED, hops = 3)
                val delivered = db.messageDao().findById("m2")
                assertEquals(DeliveryState.DELIVERED, delivered?.deliveryState)
                assertEquals(3, delivered?.hops)
            }
        } finally {
            db.close()
        }
    }

    /**
     * The v5 → v6 upgrade that adds `messages.receivedAt`.
     *
     * Existing rows take their sender timestamp, which is what unread
     * counting compared until now — so the badge the user sees is the same
     * before and after the upgrade.
     */
    @Test
    fun migratingToVersion6KeepsHistoryAndTheUnreadBadge() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, peerId, title, createdAt, lastActivityAt, lastReadAt)
                VALUES ('direct:peer', 'peer', NULL, 1000, 3000, 1500)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO messages
                    (id, conversationId, senderId, body, timestamp, deliveryState,
                     isOutgoing, attachmentFile, hops)
                VALUES ('read', 'direct:peer', 'peer', 'seen', 1000, 'DELIVERED', 0, NULL, NULL),
                       ('unread', 'direct:peer', 'peer', 'not yet', 2000, 'DELIVERED', 0, NULL, NULL),
                       ('mine', 'direct:peer', 'self', 'sent', 3000, 'DELIVERED', 1, NULL, 2)
                """.trimIndent(),
            )
        }

        // Runs MIGRATION_5_6 and validates the result against schemas/6.json.
        helper.runMigrationsAndValidate(TEST_DB, 6, true, *NChatDatabase.MIGRATIONS).close()

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NChatDatabase::class.java,
            TEST_DB,
        ).addMigrations(*NChatDatabase.MIGRATIONS).build()

        try {
            runBlocking {
                val unread = db.messageDao().findById("unread")
                assertEquals("not yet", unread?.body)
                assertEquals(2000L, unread?.receivedAt)
                assertEquals(2, db.messageDao().findById("mine")?.hops)

                val summary = db.conversationDao().observeSummaries().first().single()
                assertEquals(3, summary.messageCount)
                assertEquals(1, summary.unreadCount)
            }
        } finally {
            db.close()
        }
    }

    /**
     * The v6 → v7 upgrade that adds `peers.nickname`.
     *
     * Every existing peer keeps its name, key and verified flag and reads back
     * with no nickname, and a nickname set afterwards survives the next
     * announcement.
     */
    @Test
    fun migratingToVersion7AddsNicknamesAndKeepsPeers() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                """
                INSERT INTO peers
                    (id, displayName, publicKey, isVerified, firstSeenAt, lastSeenAt, agreementKey)
                VALUES ('peer', 'sam', X'0707070707070707070707070707070707070707070707070707070707070707',
                        1, 500, 1000, NULL)
                """.trimIndent(),
            )
        }

        // Runs MIGRATION_6_7 and validates the result against schemas/7.json.
        helper.runMigrationsAndValidate(TEST_DB, 7, true, *NChatDatabase.MIGRATIONS).close()

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NChatDatabase::class.java,
            TEST_DB,
        ).addMigrations(*NChatDatabase.MIGRATIONS).build()

        try {
            runBlocking {
                val peer = db.peerDao().findById("peer")
                assertEquals("sam", peer?.displayName)
                assertEquals(true, peer?.isVerified)
                assertArrayEquals(ByteArray(32) { 7 }, peer?.publicKey)
                assertNull(peer?.nickname)

                assertEquals(1, db.peerDao().setNickname("peer", "Samuel"))
                db.peerDao().recordSeen(id = "peer", displayName = "sam2", now = 2000)
                assertEquals("Samuel", db.peerDao().findById("peer")?.nickname)
            }
        } finally {
            db.close()
        }
    }

    /**
     * The v7 → v8 upgrade that adds the disappearing-message timer.
     *
     * Every existing thread starts with the timer off and every existing
     * message with no expiry, so nothing stored before the upgrade can be
     * swept because of it.
     */
    @Test
    fun migratingToVersion8AddsTimersWithoutExpiringHistory() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, peerId, title, createdAt, lastActivityAt, lastReadAt)
                VALUES ('direct:peer', 'peer', NULL, 1000, 1000, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO messages
                    (id, conversationId, senderId, body, timestamp, deliveryState,
                     isOutgoing, attachmentFile, hops, receivedAt)
                VALUES ('m1', 'direct:peer', 'peer', 'survived', 1000, 'DELIVERED', 0, NULL, NULL, 1000)
                """.trimIndent(),
            )
        }

        // Runs MIGRATION_7_8 and validates the result against schemas/8.json.
        helper.runMigrationsAndValidate(TEST_DB, 8, true, *NChatDatabase.MIGRATIONS).close()

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NChatDatabase::class.java,
            TEST_DB,
        ).addMigrations(*NChatDatabase.MIGRATIONS).build()

        try {
            runBlocking {
                val old = db.messageDao().findById("m1")
                assertEquals("survived", old?.body)
                assertNull(old?.expiresAt)
                assertNull(db.conversationDao().findById("direct:peer")?.disappearAfterMillis)
                assertEquals(emptyList<MessageEntity>(), db.messageDao().findExpired(now = Long.MAX_VALUE))
            }
        } finally {
            db.close()
        }
    }

    /**
     * The v8 → v9 upgrade that adds blocking.
     *
     * Every existing peer keeps its name, key, nickname and verified flag and
     * reads back unblocked, and a block set afterwards survives the next
     * announcement.
     */
    @Test
    fun migratingToVersion9AddsBlockingAndKeepsPeers() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                """
                INSERT INTO peers
                    (id, displayName, publicKey, isVerified, firstSeenAt, lastSeenAt, agreementKey, nickname)
                VALUES ('peer', 'sam', X'0707070707070707070707070707070707070707070707070707070707070707',
                        1, 500, 1000, NULL, 'Samuel')
                """.trimIndent(),
            )
        }

        // Runs MIGRATION_8_9 and validates the result against schemas/9.json.
        helper.runMigrationsAndValidate(TEST_DB, 9, true, *NChatDatabase.MIGRATIONS).close()

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NChatDatabase::class.java,
            TEST_DB,
        ).addMigrations(*NChatDatabase.MIGRATIONS).build()

        try {
            runBlocking {
                val peer = db.peerDao().findById("peer")
                assertEquals("sam", peer?.displayName)
                assertEquals("Samuel", peer?.nickname)
                assertEquals(true, peer?.isVerified)
                assertArrayEquals(ByteArray(32) { 7 }, peer?.publicKey)
                assertEquals(false, peer?.isBlocked)

                assertEquals(1, db.peerDao().setBlocked("peer", true))
                db.peerDao().recordSeen(id = "peer", displayName = "sam2", now = 2000)
                assertEquals(true, db.peerDao().findById("peer")?.isBlocked)
            }
        } finally {
            db.close()
        }
    }

    /**
     * The v9 → v10 upgrade that adds muting.
     *
     * Every existing thread keeps its timer and messages and reads back
     * unmuted, and a mute set afterwards reads back.
     */
    @Test
    fun migratingToVersion10AddsMutingAndKeepsThreads() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations
                    (id, peerId, title, createdAt, lastActivityAt, lastReadAt, disappearAfterMillis)
                VALUES ('direct:peer', 'peer', NULL, 1000, 1000, 0, 3600000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO messages
                    (id, conversationId, senderId, body, timestamp, deliveryState,
                     isOutgoing, attachmentFile, hops, receivedAt, expiresAt)
                VALUES ('m1', 'direct:peer', 'peer', 'survived', 1000, 'DELIVERED', 0, NULL, NULL, 1000, NULL)
                """.trimIndent(),
            )
        }

        // Runs MIGRATION_9_10 and validates the result against schemas/10.json.
        helper.runMigrationsAndValidate(TEST_DB, 10, true, *NChatDatabase.MIGRATIONS).close()

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NChatDatabase::class.java,
            TEST_DB,
        ).addMigrations(*NChatDatabase.MIGRATIONS).build()

        try {
            runBlocking {
                val thread = db.conversationDao().findById("direct:peer")
                assertEquals(3_600_000L, thread?.disappearAfterMillis)
                assertEquals(false, thread?.isMuted)
                assertEquals("survived", db.messageDao().findById("m1")?.body)

                assertEquals(1, db.conversationDao().setMuted("direct:peer", true))
                assertEquals(true, db.conversationDao().isMuted("direct:peer"))
                assertNull(db.conversationDao().isMuted("direct:nobody"))
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
