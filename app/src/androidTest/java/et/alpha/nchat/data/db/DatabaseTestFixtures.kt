package et.alpha.nchat.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider

/** Shared setup for the Room instrumented tests. */
object DatabaseTestFixtures {

    /**
     * An in-memory database, discarded when the process ends.
     *
     * Not `allowMainThreadQueries()`: every test drives the DAOs from `runTest`,
     * so leaving the main-thread guard on means a suspend function accidentally
     * made blocking would fail the test instead of quietly working.
     */
    fun createDatabase(): NChatDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NChatDatabase::class.java,
        ).build()

    const val SELF = "0011223344556677"
    const val PEER_A = "aabbccddeeff0011"
    const val PEER_B = "1122334455667788"

    const val T0 = 1_700_000_000_000L

    fun conversation(
        id: String = "direct:$PEER_A",
        peerId: String? = PEER_A,
        createdAt: Long = T0,
        lastActivityAt: Long = T0,
        lastReadAt: Long = 0L,
    ) = ConversationEntity(
        id = id,
        peerId = peerId,
        createdAt = createdAt,
        lastActivityAt = lastActivityAt,
        lastReadAt = lastReadAt,
    )

    fun message(
        id: String,
        conversationId: String = "direct:$PEER_A",
        senderId: String = PEER_A,
        body: String = "hello",
        timestamp: Long = T0,
        deliveryState: DeliveryState = DeliveryState.DELIVERED,
        isOutgoing: Boolean = false,
    ) = MessageEntity(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        body = body,
        timestamp = timestamp,
        deliveryState = deliveryState,
        isOutgoing = isOutgoing,
    )

    fun peer(
        id: String = PEER_A,
        displayName: String? = null,
        publicKey: ByteArray? = null,
        isVerified: Boolean = false,
        firstSeenAt: Long = T0,
        lastSeenAt: Long = T0,
    ) = PeerEntity(
        id = id,
        displayName = displayName,
        publicKey = publicKey,
        isVerified = isVerified,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
    )
}
