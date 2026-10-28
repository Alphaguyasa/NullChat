package et.alpha.nchat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

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

    /**
     * The [limit] newest entries, newest first, with the same `messageId`
     * tiebreak as [trimToNewest] so both agree on what "newest" means.
     */
    @Query("SELECT * FROM forward_queue ORDER BY queuedAt DESC, messageId DESC LIMIT :limit")
    suspend fun findNewest(limit: Int): List<ForwardQueueEntity>

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
     * The ids [trimToNewest] drops: everything past the [capacity] newest
     * entries.
     *
     * `LIMIT -1 OFFSET :capacity` is SQLite for "everything past the first
     * [capacity]". The query orders newest first, so the offset skips the
     * survivors and what is left is to go. The `messageId` tiebreak keeps the
     * choice deterministic when several packets share a timestamp, which is
     * routine — a flush queues a burst within the same millisecond.
     */
    @Query(
        """
        SELECT messageId FROM forward_queue
        ORDER BY queuedAt DESC, messageId DESC
        LIMIT -1 OFFSET :capacity
        """,
    )
    suspend fun findBeyondNewest(capacity: Int): List<String>

    /**
     * Keeps the [capacity] newest entries and drops the rest — oldest-first
     * eviction, per the CLAUDE.md store-and-forward rules.
     *
     * One transaction, so the ids returned are exactly the rows deleted. A
     * flush taking an entry between the read and the delete would otherwise be
     * reported as evicted, and its message failed while it was going out.
     *
     * @return the messageIds deleted.
     */
    @Transaction
    suspend fun trimToNewest(capacity: Int): List<String> {
        val doomed = findBeyondNewest(capacity)
        if (doomed.isNotEmpty()) deleteByIds(doomed)
        return doomed
    }

    @Query("SELECT COUNT(*) FROM forward_queue")
    suspend fun count(): Int

    @Query("DELETE FROM forward_queue")
    suspend fun deleteAll(): Int
}
