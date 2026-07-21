package et.alpha.nchat.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Delivery progress of an outgoing message.
 *
 * Stored by [DeliveryState.name], not by ordinal, so reordering or inserting a
 * constant cannot silently reinterpret existing rows.
 */
enum class DeliveryState {
    /** Queued locally; not yet handed to any transport. */
    PENDING,

    /** Handed to a transport and broadcast into the mesh. */
    SENT,

    /** An ACK packet came back from the recipient. */
    DELIVERED,

    /** Gave up — expired from the forward queue or exceeded retries. */
    FAILED,
}

/**
 * A remote device we have heard from.
 *
 * [id] is the peer id from the wire format — the first 8 bytes of SHA-256 of the
 * peer's public key — hex-encoded to 16 characters. See [toHexId].
 */
@Entity(
    tableName = "peers",
    indices = [Index("lastSeenAt")],
)
data class PeerEntity(
    @PrimaryKey val id: String,
    val displayName: String? = null,
    /** Ed25519 public key, 32 bytes. Null until the peer announces one. */
    val publicKey: ByteArray? = null,
    /** True once the key was confirmed out-of-band by QR scan. */
    val isVerified: Boolean = false,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
) {
    // ByteArray compares by identity by default, which would make two peers with
    // the same key look different.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerEntity) return false
        return id == other.id &&
            displayName == other.displayName &&
            isVerified == other.isVerified &&
            firstSeenAt == other.firstSeenAt &&
            lastSeenAt == other.lastSeenAt &&
            publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (displayName?.hashCode() ?: 0)
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + isVerified.hashCode()
        result = 31 * result + firstSeenAt.hashCode()
        result = 31 * result + lastSeenAt.hashCode()
        return result
    }
}

/**
 * A chat thread: either one-to-one with [peerId], or the broadcast thread when
 * [peerId] is null.
 *
 * There is deliberately no foreign key to [PeerEntity]. A conversation can be
 * created from an inbound packet before we have ever stored the sender as a peer.
 */
@Entity(
    tableName = "conversations",
    indices = [Index("peerId"), Index("lastActivityAt")],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    /** Remote peer id, or null for the mesh-wide broadcast thread. */
    val peerId: String? = null,
    val title: String? = null,
    val createdAt: Long,
    /** Timestamp of the most recent activity; drives thread-list ordering. */
    val lastActivityAt: Long,
    /** Everything received at or before this instant counts as read. */
    val lastReadAt: Long = 0L,
)

/**
 * One chat message.
 *
 * [id] is the 16-byte `messageId` from the wire format, hex-encoded to 32
 * characters. Reusing the wire id as the primary key is what makes inserts
 * idempotent: mesh flooding delivers the same message by several paths, and a
 * duplicate insert is rejected by the primary key rather than creating a second
 * row. See [MessageDao.insert].
 *
 * [senderId] has no foreign key for the same reason [ConversationEntity.peerId]
 * does not: a message routinely arrives from a peer we have not stored yet, and a
 * constraint here would reject perfectly valid traffic.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversationId", "timestamp"),
        Index("senderId"),
        Index("deliveryState"),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    /** Plaintext, decrypted before storage. Never log this. */
    val body: String,
    /** Epoch millis taken from the packet header. */
    val timestamp: Long,
    val deliveryState: DeliveryState,
    val isOutgoing: Boolean,
)
