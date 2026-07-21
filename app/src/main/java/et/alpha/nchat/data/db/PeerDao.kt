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
     */
    @Transaction
    suspend fun recordSeen(
        id: String,
        displayName: String? = null,
        publicKey: ByteArray? = null,
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
                ),
            )
        } else {
            update(
                existing.copy(
                    lastSeenAt = now,
                    displayName = displayName ?: existing.displayName,
                    publicKey = publicKey ?: existing.publicKey,
                ),
            )
        }
    }

    /** Marks a peer verified after an out-of-band QR comparison. */
    @Query("UPDATE peers SET isVerified = :verified WHERE id = :id")
    suspend fun setVerified(id: String, verified: Boolean): Int

    @Query("UPDATE peers SET lastSeenAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long): Int

    @Query("DELETE FROM peers WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
