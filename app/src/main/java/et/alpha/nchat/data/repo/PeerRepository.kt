package et.alpha.nchat.data.repo

import et.alpha.nchat.data.db.PeerDao
import et.alpha.nchat.data.db.PeerEntity
import et.alpha.nchat.mesh.protocol.Profile
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read and write path for the peer table.
 *
 * "Online" is not a stored flag. A mesh peer never announces that it left — it
 * just stops being heard from, often because it walked out of range. So presence
 * is derived from [PeerEntity.lastSeenAt] against a freshness window, and a stale
 * peer ages out on its own rather than needing a disconnect event that may never
 * come.
 */
@Singleton
class PeerRepository @Inject constructor(
    private val peerDao: PeerDao,
) {

    fun observePeers(): Flow<List<PeerEntity>> = peerDao.observeAll()

    fun observePeer(peerId: String): Flow<PeerEntity?> = peerDao.observeById(peerId)

    /** Peers heard from within [window] millis of [now]. */
    fun observeActivePeers(
        window: Long = DEFAULT_PRESENCE_WINDOW_MS,
        now: Long = System.currentTimeMillis(),
    ): Flow<List<PeerEntity>> = peerDao.observeActiveSince(now - window)

    suspend fun findPeer(peerId: String): PeerEntity? = peerDao.findById(peerId)

    /**
     * Records contact with a peer, creating it on first sight.
     *
     * Null arguments leave the stored value alone, so an ANNOUNCE that omits a
     * display name does not erase one we already learned.
     */
    suspend fun recordSeen(
        peerId: String,
        displayName: String? = null,
        publicKey: ByteArray? = null,
        agreementKey: ByteArray? = null,
        now: Long = System.currentTimeMillis(),
    ) = peerDao.recordSeen(
        id = peerId,
        displayName = displayName,
        publicKey = publicKey,
        agreementKey = agreementKey,
        now = now,
    )

    suspend fun touch(peerId: String, now: Long = System.currentTimeMillis()): Boolean =
        peerDao.touch(peerId, now) > 0

    /**
     * Marks a peer's key as confirmed out-of-band.
     *
     * Only call this after the user has actually compared a QR code or spoken
     * fingerprint. It is what the UI uses to tell the user the channel is
     * authenticated, so setting it on anything weaker than an out-of-band check
     * would make that claim a lie.
     */
    suspend fun markVerified(peerId: String, verified: Boolean = true): Boolean =
        peerDao.setVerified(peerId, verified) > 0

    /**
     * Gives a peer a local nickname, or removes it when [input] has nothing
     * showable left.
     *
     * Normalised exactly like a display name (see [Profile.normalize]): the same
     * right-to-left overrides and zero-width characters that could disguise a
     * received name have no business in one the user typed either, and the
     * same byte cap keeps the label from pushing the short id off a row.
     *
     * @return false if no such peer is stored.
     */
    suspend fun rename(peerId: String, input: String): Boolean =
        peerDao.setNickname(peerId, Profile.fromInput(input)?.displayName) > 0

    suspend fun forget(peerId: String): Boolean = peerDao.deleteById(peerId) > 0

    companion object {
        /** How long since [PeerEntity.lastSeenAt] a peer still counts as present. */
        const val DEFAULT_PRESENCE_WINDOW_MS: Long = 2 * 60 * 1000L
    }
}
