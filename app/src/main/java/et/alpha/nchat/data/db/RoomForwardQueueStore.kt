package et.alpha.nchat.data.db

import et.alpha.nchat.mesh.protocol.PacketCodec
import et.alpha.nchat.mesh.protocol.PacketDecodeException
import et.alpha.nchat.mesh.router.ForwardQueueStore
import et.alpha.nchat.mesh.router.QueuedPacket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Room half of `ForwardQueue`.
 *
 * Exists so the queue's policy can stay pure Kotlin in `mesh/router`, which may
 * not import Room — see `ArchitectureRulesTest`. This is also the hex boundary:
 * the queue speaks raw byte ids, the database stores text, and the conversion
 * happens here and nowhere else.
 */
@Singleton
class RoomForwardQueueStore @Inject constructor(
    private val dao: ForwardQueueDao,
) : ForwardQueueStore {

    override suspend fun add(entry: QueuedPacket): Boolean {
        val packet = entry.packet
        val row = ForwardQueueEntity(
            messageId = packet.messageId.toHexId(),
            recipientId = packet.recipientId.toHexId(),
            queuedAt = entry.queuedAt,
            packet = PacketCodec.encode(packet),
        )
        return dao.insert(row) != DUPLICATE_ROW_ID
    }

    override suspend fun forRecipients(recipientIds: List<ByteArray>): List<QueuedPacket> =
        dao.findFor(recipientIds.map { it.toHexId() }).mapNotNull(::toQueuedPacket)

    override suspend fun remove(messageIds: List<ByteArray>): Int =
        dao.deleteByIds(messageIds.map { it.toHexId() })

    override suspend fun removeQueuedAtOrBefore(cutoff: Long): Int =
        dao.deleteQueuedAtOrBefore(cutoff)

    override suspend fun trimToNewest(capacity: Int): Int = dao.trimToNewest(capacity)

    override suspend fun count(): Int = dao.count()

    override suspend fun clear() {
        dao.deleteAll()
    }

    /**
     * Decodes a stored row, discarding it if the bytes no longer parse.
     *
     * A blob written by a build with a different wire format would otherwise
     * throw on every flush and wedge the queue permanently. Dropping the one bad
     * row loses a message; throwing loses the whole queue and takes the flush
     * with it.
     */
    private fun toQueuedPacket(row: ForwardQueueEntity): QueuedPacket? =
        try {
            QueuedPacket(PacketCodec.decode(row.packet), row.queuedAt)
        } catch (e: PacketDecodeException) {
            null
        }

    private companion object {
        /** What `OnConflictStrategy.IGNORE` returns when the row already exists. */
        const val DUPLICATE_ROW_ID = -1L
    }
}
