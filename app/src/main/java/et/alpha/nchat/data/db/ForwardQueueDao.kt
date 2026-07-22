package et.alpha.nchat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ForwardQueueDao {

    /**
     * Queues a packet, ignoring it if [ForwardQueueEntity.messageId] is already
     * held.
     *
     * @return the new rowid, or -1 when the packet was already queued.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ForwardQueueEntity): Long

    /** Entries for any of [recipientIds], oldest first. */
    @Query(
        """
        SELECT * FROM forward_queue
        WHERE recipientId IN (:recipientIds)
        ORDER BY queuedAt ASC, messageId ASC
        """,
    )
    suspend fun findFor(recipientIds: List<String>): List<ForwardQueueEntity>

    @Query("SELECT * FROM forward_queue ORDER BY queuedAt ASC, messageId ASC")
    suspend fun findAll(): List<ForwardQueueEntity>

    /** @return rows deleted. */
    @Query("DELETE FROM forward_queue WHERE messageId IN (:messageIds)")
    suspend fun deleteByIds(messageIds: List<String>): Int

    /**
     * Drops everything queued at or before [cutoff] — the expiry sweep.
     *
     * At-or-before, not strictly before, so an entry exactly one expiry window
     * old is dropped. That matches `SeenCache`'s boundary.
     *
     * @return rows deleted.
     */
    @Query("DELETE FROM forward_queue WHERE queuedAt <= :cutoff")
    suspend fun deleteQueuedAtOrBefore(cutoff: Long): Int

    /**
     * Keeps the [capacity] newest entries and drops the rest — oldest-first
     * eviction, per the CLAUDE.md store-and-forward rules.
     *
     * `LIMIT -1 OFFSET :capacity` is SQLite for "everything past the first
     * [capacity]". The subquery orders newest first, so the offset skips the
     * survivors and the delete takes what is left. The `messageId` tiebreak
     * keeps the choice deterministic when several packets share a timestamp,
     * which is routine — a flush queues a burst within the same millisecond.
     *
     * @return rows deleted.
     */
    @Query(
        """
        DELETE FROM forward_queue WHERE messageId IN (
            SELECT messageId FROM forward_queue
            ORDER BY queuedAt DESC, messageId DESC
            LIMIT -1 OFFSET :capacity
        )
        """,
    )
    suspend fun trimToNewest(capacity: Int): Int

    @Query("SELECT COUNT(*) FROM forward_queue")
    suspend fun count(): Int

    @Query("DELETE FROM forward_queue")
    suspend fun deleteAll(): Int
}
