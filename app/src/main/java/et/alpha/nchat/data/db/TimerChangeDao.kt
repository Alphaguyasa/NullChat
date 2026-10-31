package et.alpha.nchat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TimerChangeDao {

    @Insert
    suspend fun insert(change: TimerChangeEntity): Long

    /** A thread's timer lines, oldest first, in the order the thread shows them. */
    @Query(
        """
        SELECT * FROM timer_changes WHERE conversationId = :conversationId
        ORDER BY changedAt ASC, id ASC
        """,
    )
    fun observeConversation(conversationId: String): Flow<List<TimerChangeEntity>>

    /**
     * Deletes all but the newest [keep] lines of a thread.
     *
     * Every change the contact makes writes a line here, so without a cap a
     * contact toggling the timer in a loop could grow this table without bound.
     */
    @Query(
        """
        DELETE FROM timer_changes WHERE conversationId = :conversationId AND id NOT IN (
            SELECT id FROM timer_changes WHERE conversationId = :conversationId
            ORDER BY changedAt DESC, id DESC LIMIT :keep
        )
        """,
    )
    suspend fun trim(conversationId: String, keep: Int): Int
}
