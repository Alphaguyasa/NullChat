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
     * Messages in a thread, in the order this phone stored them.
     *
     * By `receivedAt`, this phone's clock, not the sender's `timestamp`: phones
     * with no network never sync their clocks, and a sender a year fast would
     * otherwise stay at the bottom of the thread under every later reply.
     *
     * Ties break on [MessageEntity.id] so rows stored in the same millisecond
     * do not visibly reshuffle on redraw.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
        ORDER BY receivedAt ASC, id ASC
        """,
    )
    fun observeConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
        ORDER BY receivedAt DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun findLatestIn(conversationId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId")
    suspend fun findIn(conversationId: String): List<MessageEntity>

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

    /**
     * Our messages in one thread in [state], oldest first, with no limit.
     *
     * Per thread rather than [findOutgoingByState] filtered afterwards: that
     * scans only the oldest rows across every chat, so a peer whose held
     * messages sat behind a hundred others' would never have them released.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId AND deliveryState = :state AND isOutgoing = 1
        ORDER BY timestamp ASC
        """,
    )
    suspend fun findOutgoingInConversation(conversationId: String, state: DeliveryState): List<MessageEntity>

    /** Threads holding any of our messages in [state]. */
    @Query("SELECT DISTINCT conversationId FROM messages WHERE deliveryState = :state AND isOutgoing = 1")
    suspend fun conversationsWithOutgoing(state: DeliveryState): List<String>

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

    /**
     * Moves our own messages written at or before [cutoff] from [from] to [to].
     *
     * Always PENDING → FAILED from the repository. Guarded on the current state
     * for the same reason as [advanceDeliveryState]: a message that went out or
     * was acknowledged in the meantime must keep that state.
     *
     * @return rows affected.
     */
    @Query(
        """
        UPDATE messages SET deliveryState = :to
        WHERE isOutgoing = 1 AND deliveryState = :from AND timestamp <= :cutoff
        """,
    )
    suspend fun expireOutgoing(cutoff: Long, from: DeliveryState, to: DeliveryState): Int

    /**
     * Moves those of [ids] that are our own messages from [from] to [to].
     *
     * The forward queue reports every packet it evicts, most of them carried
     * for other people; the `isOutgoing` filter is what picks ours out. Guarded
     * on [from] like [expireOutgoing]: a message that reached a relay before
     * its queued copy was dropped stays `sent`.
     *
     * @return rows affected.
     */
    @Query(
        """
        UPDATE messages SET deliveryState = :to
        WHERE isOutgoing = 1 AND deliveryState = :from AND id IN (:ids)
        """,
    )
    suspend fun moveOutgoing(ids: List<String>, from: DeliveryState, to: DeliveryState): Int

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversationId = :conversationId AND isOutgoing = 0 AND receivedAt > :since
        """,
    )
    fun observeUnreadCount(conversationId: String, since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countIn(conversationId: String): Int

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /** Prunes history older than [cutoff]. @return rows deleted. */
    /**
     * Rows whose disappearing timer has run out at [now].
     *
     * Our own PENDING messages wait: the packet is still in the forward queue
     * and goes out regardless, and a row deleted before then would leave the
     * user with no sign that it is still being sent. They are swept once sent.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE expiresAt IS NOT NULL AND expiresAt <= :now AND deliveryState != :pending
        """,
    )
    suspend fun findExpired(now: Long, pending: DeliveryState = DeliveryState.PENDING): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("DELETE FROM messages WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
