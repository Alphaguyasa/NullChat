package et.alpha.nchat.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A conversation plus the derived counters the thread list needs.
 *
 * The counters are computed per query rather than stored as columns. Storing them
 * would mean every insert, delete and read-marking path has to remember to keep
 * them in step, and any missed path shows up as a permanently wrong unread badge.
 */
data class ConversationSummary(
    @Embedded val conversation: ConversationEntity,
    val messageCount: Int,
    val unreadCount: Int,
    val lastMessageBody: String?,
    val lastMessageAt: Long?,
)

@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun findById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations ORDER BY lastActivityAt DESC, id ASC")
    fun observeAll(): Flow<List<ConversationEntity>>

    /**
     * Thread list, most recently active first.
     *
     * Ordered by the maintained `lastActivityAt` column rather than by a computed
     * max(timestamp) so the sort can use its index instead of scanning messages.
     *
     * Unread compares `receivedAt` with `lastReadAt`, both from this device's
     * clock; see `MessageEntity.receivedAt` for why not the sender's timestamp.
     */
    @Query(
        """
        SELECT c.*,
            (SELECT COUNT(*) FROM messages m
             WHERE m.conversationId = c.id) AS messageCount,
            (SELECT COUNT(*) FROM messages m
             WHERE m.conversationId = c.id
               AND m.isOutgoing = 0
               AND m.receivedAt > c.lastReadAt) AS unreadCount,
            (SELECT m.body FROM messages m
             WHERE m.conversationId = c.id
             ORDER BY m.receivedAt DESC, m.id DESC LIMIT 1) AS lastMessageBody,
            (SELECT MAX(m.receivedAt) FROM messages m
             WHERE m.conversationId = c.id) AS lastMessageAt
        FROM conversations c
        ORDER BY c.lastActivityAt DESC, c.id ASC
        """,
    )
    fun observeSummaries(): Flow<List<ConversationSummary>>

    @Query("UPDATE conversations SET lastActivityAt = :at WHERE id = :id AND lastActivityAt < :at")
    suspend fun bumpActivity(id: String, at: Long): Int

    /**
     * Pulls back every thread whose activity time is later than [now], to its
     * newest arrival (never past [now]).
     *
     * Builds before this one bumped activity with the sender's timestamp, and
     * [bumpActivity] only moves forward, so a thread from a sender whose clock
     * was a year fast stayed pinned at the top of the list for that year. New
     * bumps use this phone's clock; this repairs the rows already written. It
     * also covers this phone's own clock being set back.
     */
    @Query(
        """
        UPDATE conversations SET lastActivityAt = MIN(
            :now,
            COALESCE(
                (SELECT MAX(m.receivedAt) FROM messages m WHERE m.conversationId = conversations.id),
                :now
            )
        )
        WHERE lastActivityAt > :now
        """,
    )
    suspend fun pullBackFutureActivity(now: Long): Int

    @Query("UPDATE conversations SET lastReadAt = :at WHERE id = :id AND lastReadAt < :at")
    suspend fun markReadUpTo(id: String, at: Long): Int

    /** Deletes a thread. Its messages go with it via `ON DELETE CASCADE`. */
    @Query("UPDATE conversations SET disappearAfterMillis = :millis WHERE id = :id")
    suspend fun setDisappearAfter(id: String, millis: Long?): Int

    @Query("UPDATE conversations SET isMuted = :muted WHERE id = :id")
    suspend fun setMuted(id: String, muted: Boolean): Int

    /** Null for a thread with no row, which nobody can have muted. */
    @Query("SELECT isMuted FROM conversations WHERE id = :id")
    suspend fun isMuted(id: String): Boolean?

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
