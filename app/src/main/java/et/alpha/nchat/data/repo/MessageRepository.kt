package et.alpha.nchat.data.repo

import androidx.room.withTransaction
import et.alpha.nchat.data.db.ConversationDao
import et.alpha.nchat.data.db.ConversationEntity
import et.alpha.nchat.data.db.ConversationSummary
import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageDao
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.NChatDatabase
import et.alpha.nchat.data.db.newMessageId
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read and write path for chat history.
 *
 * Every write that touches more than one table runs in a transaction, so a
 * process death mid-write cannot leave a message pointing at a conversation row
 * that was never committed.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val database: NChatDatabase,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
) {

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.observeConversation(conversationId)

    fun observeConversations(): Flow<List<ConversationSummary>> =
        conversationDao.observeSummaries()

    fun observeUnreadCount(conversationId: String, since: Long): Flow<Int> =
        messageDao.observeUnreadCount(conversationId, since)

    suspend fun findMessage(id: String): MessageEntity? = messageDao.findById(id)

    /**
     * Stores a message this device is sending, in [DeliveryState.PENDING].
     *
     * The returned row carries the id the mesh layer must put in the packet's
     * `messageId` field, so acknowledgements can be matched back to this row.
     */
    suspend fun recordOutgoing(
        conversationId: String,
        selfId: String,
        body: String,
        peerId: String? = null,
        messageId: String = newMessageId(),
        now: Long = System.currentTimeMillis(),
        attachmentFile: String? = null,
    ): MessageEntity {
        val message = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = selfId,
            body = body,
            timestamp = now,
            deliveryState = DeliveryState.PENDING,
            isOutgoing = true,
            attachmentFile = attachmentFile,
        )
        database.withTransaction {
            ensureConversation(conversationId, peerId, now)
            messageDao.insert(message)
            conversationDao.bumpActivity(conversationId, now)
        }
        return message
    }

    /**
     * Stores a message received from the mesh.
     *
     * @return true if this was new, false if the same [messageId] is already
     *   stored. Flooding delivers the same message by several routes, so a false
     *   here is routine and means "drop it, do not notify".
     */
    suspend fun recordIncoming(
        messageId: String,
        conversationId: String,
        senderId: String,
        body: String,
        timestamp: Long,
        peerId: String? = senderId,
        now: Long = System.currentTimeMillis(),
        attachmentFile: String? = null,
    ): Boolean = database.withTransaction {
        ensureConversation(conversationId, peerId, now)
        val rowId = messageDao.insert(
            MessageEntity(
                id = messageId,
                conversationId = conversationId,
                senderId = senderId,
                body = body,
                timestamp = timestamp,
                deliveryState = DeliveryState.DELIVERED,
                isOutgoing = false,
                attachmentFile = attachmentFile,
            ),
        )
        val isNew = rowId != -1L
        if (isNew) conversationDao.bumpActivity(conversationId, timestamp)
        isNew
    }

    /** Outgoing messages that no transport has taken yet. */
    suspend fun pendingOutgoing(limit: Int = 100): List<MessageEntity> =
        messageDao.findOutgoingByState(DeliveryState.PENDING, limit)

    /**
     * Marks a message as handed to a transport.
     *
     * Only advances from PENDING: an ACK can arrive before the transport reports
     * its own send completion, and that DELIVERED must not be walked back.
     */
    suspend fun markSent(messageId: String): Boolean =
        messageDao.advanceDeliveryState(messageId, DeliveryState.PENDING, DeliveryState.SENT) > 0

    /**
     * Marks a message delivered, with the hops its receipt reported if known.
     *
     * @param hops links the message crossed, or null when the receipt did not say.
     */
    suspend fun markDelivered(messageId: String, hops: Int? = null): Boolean =
        messageDao.markDelivered(messageId, DeliveryState.DELIVERED, hops) > 0

    suspend fun markFailed(messageId: String): Boolean =
        messageDao.updateDeliveryState(messageId, DeliveryState.FAILED) > 0

    /** Marks everything received up to [at] as read. */
    suspend fun markRead(conversationId: String, at: Long = System.currentTimeMillis()) {
        conversationDao.markReadUpTo(conversationId, at)
    }

    /** Deletes a thread and, by cascade, its messages. */
    suspend fun deleteConversation(conversationId: String): Boolean =
        conversationDao.deleteById(conversationId) > 0

    suspend fun pruneOlderThan(cutoff: Long): Int = messageDao.deleteOlderThan(cutoff)

    /**
     * Creates the conversation row if absent.
     *
     * Callers already hold a transaction; the insert ignores conflicts so two
     * concurrent inbound messages for a new peer cannot race into a constraint
     * failure.
     */
    private suspend fun ensureConversation(
        conversationId: String,
        peerId: String?,
        now: Long,
    ) {
        conversationDao.insert(
            ConversationEntity(
                id = conversationId,
                peerId = peerId,
                createdAt = now,
                lastActivityAt = now,
            ),
        )
    }
}
