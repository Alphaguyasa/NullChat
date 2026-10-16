package et.alpha.nchat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /**
     * Inserts a message, ignoring it if [MessageEntity.id] is already stored.
     *
     * @return the new rowid, or -1 when the message was a duplicate. Callers use
     *   that to distinguish "new traffic" from a mesh replay arriving by a second
     *   path, which matters because only genuinely new messages should bump
     *   conversation activity or raise a notification.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<MessageEntity>): List<Long>

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun findById(id: String): MessageEntity?

    /**
     * Messages in a thread, oldest first.
     *
     * Ties break on [MessageEntity.id] because mesh timestamps come from other
     * devices and collide often enough to make ordering non-deterministic
     * otherwise — which shows up as messages visibly reshuffling on redraw.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
        ORDER BY timestamp ASC, id ASC
        """,
    )
    fun observeConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
        ORDER BY timestamp DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun findLatestIn(conversationId: String): MessageEntity?

    /** Outbound messages still awaiting a transport, oldest first. */
    @Query(
        """
        SELECT * FROM messages
        WHERE deliveryState = :state AND isOutgoing = 1
        ORDER BY timestamp ASC
        LIMIT :limit
        """,
    )
    suspend fun findOutgoingByState(state: DeliveryState, limit: Int = 100): List<MessageEntity>

    /** @return rows affected: 0 when no such message is stored. */
    @Query("UPDATE messages SET deliveryState = :state WHERE id = :id")
    suspend fun updateDeliveryState(id: String, state: DeliveryState): Int

    /**
     * Sets [state] (always DELIVERED from the repository) and files [hops].
     *
     * A known count is never overwritten by an unknown one, and two known
     * counts keep the smaller. The recipient only acknowledges a message the
     * first time it stores it, so a second receipt should not happen; but if a
     * future build ever re-acknowledges, the shortest route is still what
     * `delivered · N hops` means, and a later receipt must not erase it.
     *
     * @return rows affected: 0 when no such message is stored.
     */
    @Query(
        """
        UPDATE messages SET deliveryState = :state,
            hops = CASE
                WHEN :hops IS NULL THEN hops
                WHEN hops IS NULL THEN :hops
                ELSE MIN(hops, :hops)
            END
        WHERE id = :id
        """,
    )
    suspend fun markDelivered(id: String, state: DeliveryState, hops: Int?): Int

    /**
     * Advances a message's state only when it is currently [from].
     *
     * Guards against a late SENT overwriting a DELIVERED that already arrived —
     * ACKs routinely beat the transport's own send callback in a mesh.
     */
    @Query("UPDATE messages SET deliveryState = :to WHERE id = :id AND deliveryState = :from")
    suspend fun advanceDeliveryState(id: String, from: DeliveryState, to: DeliveryState): Int

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversationId = :conversationId AND isOutgoing = 0 AND timestamp > :since
        """,
    )
    fun observeUnreadCount(conversationId: String, since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countIn(conversationId: String): Int

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /** Prunes history older than [cutoff]. @return rows deleted. */
    @Query("DELETE FROM messages WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
