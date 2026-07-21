package et.alpha.nchat.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

/**
 * The app's Room database.
 *
 * ## Migration policy
 *
 * Chat history is the product. A user's messages exist on their device and
 * nowhere else — there is no server to re-sync from, by design. Losing the
 * database is unrecoverable data loss, so:
 *
 * - **Never** call `fallbackToDestructiveMigration()`. It silently drops every
 *   table on a version mismatch. On a normal app that costs a re-sync; here it
 *   destroys the only copy.
 * - Every schema change bumps [VERSION] **and** adds a [Migration] to
 *   [MIGRATIONS].
 * - `exportSchema` is on and `schemas/` is committed, so a schema change shows up
 *   as a reviewable diff and `MigrationTestHelper` can replay real upgrades
 *   against real old databases.
 * - Every migration gets a test in `NChatDatabaseMigrationTest` that opens the
 *   previous version, writes rows, migrates, and asserts the rows survived.
 *   A migration that compiles is not a migration that preserves data.
 *
 * Adding a nullable column or a new table is expressive enough for most changes
 * and needs no data rewrite. Prefer that over recreating a table.
 */
@Database(
    entities = [
        MessageEntity::class,
        PeerEntity::class,
        ConversationEntity::class,
    ],
    version = NChatDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NChatDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    abstract fun peerDao(): PeerDao

    abstract fun conversationDao(): ConversationDao

    companion object {
        const val VERSION: Int = 1

        const val NAME: String = "nchat.db"

        /**
         * Ordered upgrade path. Empty at version 1 — there is nothing to upgrade
         * from yet.
         *
         * When adding one, append it here and leave the existing entries alone;
         * Room chains them to upgrade a database that skipped several releases.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
