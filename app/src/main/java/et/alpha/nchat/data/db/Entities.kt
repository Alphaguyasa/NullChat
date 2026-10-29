package et.alpha.nchat.data.db

import androidx.room.ColumnInfo
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
    /**
     * X25519 public key, 32 bytes, from the peer's ANNOUNCE or QR code. Null
     * until one arrives. Persisted so a restart does not leave us unable to
     * encrypt to a peer until it happens to announce again.
     */
    val agreementKey: ByteArray? = null,
    /**
     * The name this user gave the peer, or null. Local only: never sent, and
     * never overwritten by anything the peer announces.
     */
    val nickname: String? = null,
    /**
     * True when the user blocked this peer: its messages are not stored,
     * acknowledged or notified. Local only, like [nickname]. Relaying is
     * untouched, because a relay cannot tell a blocked sender's packet from
     * anyone else's without reading it, and the mesh needs every phone to carry
     * what it can.
     *
     * The default is declared in SQL as well, so the 8 → 9 migration can add
     * the column NOT NULL and every existing peer reads back unblocked.
     */
    @ColumnInfo(defaultValue = "0")
    val isBlocked: Boolean = false,
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
            publicKey.contentEquals(other.publicKey) &&
            agreementKey.contentEquals(other.agreementKey) &&
            nickname == other.nickname &&
            isBlocked == other.isBlocked
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (displayName?.hashCode() ?: 0)
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + isVerified.hashCode()
        result = 31 * result + firstSeenAt.hashCode()
        result = 31 * result + lastSeenAt.hashCode()
        result = 31 * result + agreementKey.contentHashCode()
        result = 31 * result + (nickname?.hashCode() ?: 0)
        result = 31 * result + isBlocked.hashCode()
        return result
    }
}

/**
 * The name to show for this peer: the user's own nickname first, then the name
 * the peer chose for itself.
 *
 * The nickname wins because it is the only name nobody else can set. A display
 * name is whatever arrived in a PROFILE packet, and two strangers can both
 * claim to be "Maya".
 */
val PeerEntity.preferredName: String?
    get() = nickname?.takeIf { it.isNotBlank() } ?: displayName?.takeIf { it.isNotBlank() }

/**
 * Whether the user has a reason to keep this peer: they checked its key by QR,
 * named it, blocked it, or have a thread with it ([hasThread]).
 *
 * Everyone else is a stranger who announced within range, and a crowded place
 * makes hundreds of those. A kept peer is listed and its keys stay in memory
 * however many strangers come after it; a stranger gets neither once it has
 * not been heard for a while. Blocked counts as kept so the user can still find
 * the row to unblock it.
 */
fun PeerEntity.isKept(hasThread: Boolean): Boolean =
    isVerified || isBlocked || nickname != null || hasThread

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
    /**
     * How long a new message in this thread stays on this phone, in millis, or
     * null to keep messages. In a direct chat a change is also sent to the
     * other phone as a TIMER packet, and one it sends is applied here; see
     * `MeshCoordinator.setDisappearing`. A phone on an older build ignores it.
     */
    val disappearAfterMillis: Long? = null,
    /**
     * No notifications for this thread. Local only: nothing is sent, and the
     * other phone cannot tell. NOT NULL with a default, like `peers.isBlocked`.
     */
    @ColumnInfo(defaultValue = "0")
    val isMuted: Boolean = false,
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
        Index("expiresAt"),
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
    /**
     * File name of this message's image in `AttachmentStore`, or null for an
     * ordinary text message.
     *
     * A name rather than the bytes: a 14 KB BLOB here would be dragged through
     * the cursor by every query on this table, and the chat list re-queries on
     * every inbound packet.
     *
     * Non-null currently implies a JPEG, since that is the only attachment kind
     * the mesh can carry. A second kind would need a companion type column
     * rather than sniffing the extension.
     */
    val attachmentFile: String? = null,
    /**
     * Links an outgoing message crossed to reach its recipient, as the
     * recipient's ACK reported it. Null until delivered, for incoming messages,
     * and whenever the receipt did not say. A hint derived from the unsigned ttl
     * byte, not a measurement; it only ever feeds the `· N hops` suffix.
     */
    val hops: Int? = null,
    /**
     * When this device stored the row, epoch millis by this device's clock.
     *
     * Unread counting compares this, never [timestamp], against
     * `ConversationEntity.lastReadAt`. [timestamp] comes from the sender's
     * clock, and phones with no network and no SIM never sync theirs: a
     * sender an hour behind would have every new message counted as already
     * read, and one an hour ahead would leave the badge stuck after reading.
     */
    @ColumnInfo(defaultValue = "0")
    val receivedAt: Long = 0L,
    /**
     * When this phone deletes the row, by this phone's clock, or null to keep it.
     *
     * Stamped once, when the row is written, from the thread's
     * [ConversationEntity.disappearAfterMillis]. Changing the timer later
     * therefore never reaches back: turning it on cannot wipe a history the
     * user had no warning about, and turning it off cannot rescue messages
     * that were sent in the belief they would disappear.
     */
    val expiresAt: Long? = null,
) {
    /** Whether this message carries an image rather than text. */
    val hasAttachment: Boolean get() = attachmentFile != null

    /**
     * The time to show on the bubble: the sender's [timestamp], but never later
     * than [receivedAt].
     *
     * A message cannot have been written after it arrived, so a later stamp is
     * only the sender's clock running fast. Without the clamp, a bubble from a
     * phone a year ahead would show tomorrow's time in a thread ordered by
     * arrival. An earlier stamp is kept: a store-and-forwarded message really
     * was written hours before it got here. Rows from before `receivedAt`
     * existed carry 0 and keep their timestamp.
     */
    val shownAt: Long
        get() = if (isOutgoing || receivedAt <= 0L) timestamp else minOf(timestamp, receivedAt)
}

/**
 * A packet held for a recipient that was not reachable when we tried to send it.
 *
 * This is the persistent half of `ForwardQueue`; the policy — 500 entry cap, 24
 * hour expiry, oldest-first eviction — lives there, in pure Kotlin. Persisting
 * it is what lets a message composed with nobody in range survive a restart and
 * still go out when the recipient finally appears.
 *
 * There is no foreign key to [MessageEntity]. Most rows here are ours, but the
 * mesh also relays for third parties, and those packets have no local message
 * row to point at.
 *
 * [messageId] is the 16-byte wire id hex-encoded to 32 characters, and it is the
 * primary key for the same reason it is on [MessageEntity]: flooding offers the
 * same packet by several paths, and `OnConflictStrategy.IGNORE` turns a
 * duplicate into a no-op rather than a second row.
 */
@Entity(
    tableName = "forward_queue",
    indices = [Index("recipientId", "queuedAt")],
)
data class ForwardQueueEntity(
    @PrimaryKey val messageId: String,
    /** Recipient peer id, hex-encoded. All zeroes means broadcast. */
    val recipientId: String,
    /**
     * When the queue accepted this packet — not the packet's own timestamp,
     * which comes from whichever device composed it and may be badly wrong.
     * Drives both expiry and eviction order.
     */
    val queuedAt: Long,
    /** The whole packet, exactly as `PacketCodec.encode` produced it. */
    val packet: ByteArray,
) {
    // ByteArray compares by identity by default, which would make two rows
    // holding the same packet look different. Same reason as PeerEntity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ForwardQueueEntity) return false
        return messageId == other.messageId &&
            recipientId == other.recipientId &&
            queuedAt == other.queuedAt &&
            packet.contentEquals(other.packet)
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + recipientId.hashCode()
        result = 31 * result + queuedAt.hashCode()
        result = 31 * result + packet.contentHashCode()
        return result
    }
}
