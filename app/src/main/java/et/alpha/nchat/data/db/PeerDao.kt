package et.alpha.nchat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(peer: PeerEntity)

    @Update
    suspend fun update(peer: PeerEntity)

    @Query("SELECT * FROM peers WHERE id = :id")
    suspend fun findById(id: String): PeerEntity?

    @Query("SELECT * FROM peers ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE id = :id")
    fun observeById(id: String): Flow<PeerEntity?>

    /** Peers heard from since [since] — the practical definition of "online". */
    @Query("SELECT * FROM peers WHERE lastSeenAt >= :since ORDER BY lastSeenAt DESC")
    fun observeActiveSince(since: Long): Flow<List<PeerEntity>>

    /**
     * Records an announcement from a peer, creating the row on first contact.
     *
     * Only ever widens what we know. [PeerEntity.firstSeenAt] and
     * [PeerEntity.isVerified] are preserved on update, and null arguments leave
     * the stored value alone.
     *
     * Preserving `isVerified` is a security property, not tidiness: an attacker
     * who can spoof an ANNOUNCE for a peer id must not be able to clear the
     * verified flag a user established by scanning a QR code, because the UI
     * uses that flag to tell the user the channel is authenticated.
     *
     * [PeerEntity.agreementKey] is first-wins, like `PublicKeyDirectory`: the
     * directory is warmed from this column at start, and if the two disagreed
     * about which key is current, a restart would silently change which key
     * messages are sealed to.
     */
    @Transaction
    suspend fun recordSeen(
        id: String,
        displayName: String? = null,
        publicKey: ByteArray? = null,
        agreementKey: ByteArray? = null,
        now: Long,
    ) {
        val existing = findById(id)
        if (existing == null) {
            insert(
                PeerEntity(
                    id = id,
                    displayName = displayName,
                    publicKey = publicKey,
                    isVerified = false,
                    firstSeenAt = now,
                    lastSeenAt = now,
                    agreementKey = agreementKey,
                ),
            )
        } else {
            update(
                existing.copy(
                    lastSeenAt = now,
                    displayName = displayName ?: existing.displayName,
                    publicKey = publicKey ?: existing.publicKey,
                    agreementKey = existing.agreementKey ?: agreementKey,
                ),
            )
        }
    }

    /** Marks a peer verified after an out-of-band QR comparison. */
    @Query("UPDATE peers SET isVerified = :verified WHERE id = :id")
    suspend fun setVerified(id: String, verified: Boolean): Int

    /**
     * Sets or, with null, clears the local nickname. Only the user calls this;
     * [recordSeen] carries the row's nickname over untouched.
     */
    @Query("UPDATE peers SET nickname = :nickname WHERE id = :id")
    suspend fun setNickname(id: String, nickname: String?): Int

    /**
     * Blocks or unblocks a peer. Only the user calls this; [recordSeen] copies
     * the row, so nothing a peer announces can lift a block.
     */
    @Query("UPDATE peers SET isBlocked = :blocked WHERE id = :id")
    suspend fun setBlocked(id: String, blocked: Boolean): Int

    /** Null for a peer with no row, which nobody can have blocked. */
    @Query("SELECT isBlocked FROM peers WHERE id = :id")
    suspend fun isBlocked(id: String): Boolean?

    @Query("UPDATE peers SET lastSeenAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long): Int

    @Query("DELETE FROM peers WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /**
     * Deletes strangers last heard before [cutoff]: rows the user never verified,
     * named or blocked, with no thread and no message anywhere, broadcast included.
     *
     * Every self-signed ANNOUNCE within six hops creates a row, so without this a
     * busy place leaves hundreds of rows that only ever grow. A pruned stranger
     * who comes back is recreated by its next ANNOUNCE, keys and all, since its
     * id is the hash of the key it signs with. Anyone the user has a reason to
     * keep is excluded by one of the conditions.
     */
    @Query(
        """
        DELETE FROM peers
        WHERE lastSeenAt < :cutoff
          AND isVerified = 0
          AND isBlocked = 0
          AND nickname IS NULL
          AND id NOT IN (SELECT peerId FROM conversations WHERE peerId IS NOT NULL)
          AND id NOT IN (SELECT senderId FROM messages)
        """,
    )
    suspend fun pruneStrangers(cutoff: Long): Int
}
