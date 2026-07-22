package et.alpha.nchat.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
