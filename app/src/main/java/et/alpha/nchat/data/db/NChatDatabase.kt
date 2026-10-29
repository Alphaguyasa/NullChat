package et.alpha.nchat.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

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
        ForwardQueueEntity::class,
    ],
    version = NChatDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NChatDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    abstract fun peerDao(): PeerDao

    abstract fun conversationDao(): ConversationDao

    abstract fun forwardQueueDao(): ForwardQueueDao

    companion object {
        const val VERSION: Int = 10

        const val NAME: String = "nchat.db"

        /**
         * Adds the `forward_queue` table backing store-and-forward.
         *
         * Purely additive — a new table, no change to any existing one — so
         * there is no data to rewrite and nothing in `messages`, `peers` or
         * `conversations` is touched.
         *
         * The SQL is copied verbatim from the `createSql` Room generated into
         * `schemas/2.json` rather than hand-written. Room compares the migrated
         * schema against that file by identity hash, and a difference as small
         * as a missing backtick fails the migration test.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `forward_queue` (" +
                        "`messageId` TEXT NOT NULL, " +
                        "`recipientId` TEXT NOT NULL, " +
                        "`queuedAt` INTEGER NOT NULL, " +
                        "`packet` BLOB NOT NULL, " +
                        "PRIMARY KEY(`messageId`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_forward_queue_recipientId_queuedAt` " +
                        "ON `forward_queue` (`recipientId`, `queuedAt`)",
                )
            }
        }

        /**
         * Adds `messages.attachmentFile`, the pointer to an image on disk.
         *
         * A single nullable column, which is the cheapest schema change there
         * is: SQLite rewrites no rows, and existing messages read back as null,
         * which is exactly what they are — text with no attachment. No default
         * is declared for the same reason; null already means "no image".
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `attachmentFile` TEXT")
            }
        }

        /**
         * Adds `peers.agreementKey`, the peer's X25519 public key.
         *
         * Nullable and without a default, like `attachmentFile` in 2 → 3: no
         * row is rewritten, and an existing peer reads back with no agreement
         * key, which is the truth — it was never stored. That peer becomes
         * encryptable again on its next ANNOUNCE, exactly as before.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `agreementKey` BLOB")
            }
        }

        /**
         * Adds `messages.hops`, the hop count a delivery receipt reported.
         *
         * Nullable and without a default, like the two columns before it. No
         * row is rewritten; messages delivered before this version read back
         * with no count and show plain `delivered`, which is honest — the
         * receipt's count was never kept.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `hops` INTEGER")
            }
        }

        /**
         * Adds `messages.receivedAt`, the local time a row was stored.
         *
         * NOT NULL with a default so the entity can keep a plain `Long`. The
         * best value existing rows can have is their sender timestamp — the
         * same value unread counting used until now — so the badge a user sees
         * is unchanged by the upgrade, and only new messages get a local time.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `receivedAt` INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL("UPDATE `messages` SET `receivedAt` = `timestamp`")
            }
        }

        /**
         * Adds `peers.nickname`, the name the user gave a peer.
         *
         * Nullable and without a default, like the other added columns: no row
         * is rewritten, and every existing peer reads back with no nickname,
         * which is true — the user has not named anyone yet.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `peers` ADD COLUMN `nickname` TEXT")
            }
        }

        /**
         * Adds the disappearing-message timer: `conversations.disappearAfterMillis`
         * and `messages.expiresAt`, with an index for the sweep.
         *
         * Both nullable, and null means "keep", so every existing thread starts
         * with the timer off and every existing message stays. Nothing that was
         * stored before the upgrade can disappear because of it.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `conversations` ADD COLUMN `disappearAfterMillis` INTEGER")
                connection.execSQL("ALTER TABLE `messages` ADD COLUMN `expiresAt` INTEGER")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_expiresAt` ON `messages` (`expiresAt`)",
                )
            }
        }

        /**
         * Adds `peers.isBlocked`.
         *
         * NOT NULL with a default of 0, like `receivedAt` in 5 → 6, so the
         * entity keeps a plain `Boolean`. No row is rewritten and every existing
         * peer reads back unblocked, which is true: nobody could block anyone
         * before this version.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `peers` ADD COLUMN `isBlocked` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Adds `conversations.isMuted`.
         *
         * NOT NULL with a default of 0, as in 8 → 9. No row is rewritten and
         * every existing thread reads back unmuted, which is how it behaved
         * before this version: nothing could be muted.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `conversations` ADD COLUMN `isMuted` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Ordered upgrade path.
         *
         * When adding one, append it here and leave the existing entries alone;
         * Room chains them to upgrade a database that skipped several releases.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
        )
    }
}
