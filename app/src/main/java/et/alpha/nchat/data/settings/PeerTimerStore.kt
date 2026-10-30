package et.alpha.nchat.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import et.alpha.nchat.mesh.PeerTimerStamp
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Remembers, per peer, the last timer change accepted from them; see
 * `acceptsPeerTimer`.
 *
 * In preferences rather than Room, for the reason `DeletedMessageStore` gives:
 * it is read by exact key only and is worth nothing once the replay window is
 * over, which does not justify a schema version. It must survive a restart,
 * because the forward queues that would replay an old change do too.
 *
 * Also remembers when the user last changed each chat's timer on this phone,
 * under a prefixed key, so a contact's older change cannot undo it.
 *
 * Kept when a contact is forgotten. A forgotten peer's old TIMER replayed by a
 * relay would otherwise bring the thread back with a timer nobody chose.
 */
@Singleton
class PeerTimerStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    suspend fun get(peerHex: String): PeerTimerStamp? = withContext(Dispatchers.IO) {
        decode(preferences.getString(peerHex, null))
    }

    suspend fun put(peerHex: String, stamp: PeerTimerStamp) {
        withContext(Dispatchers.IO) {
            preferences.edit().putString(peerHex, "${stamp.sentAt}$FIELD${stamp.appliedAt}").commit()
        }
    }

    /** When the user last changed the timer of the chat with [peerHex] here, or null. */
    suspend fun getLocalChange(peerHex: String): Long? = withContext(Dispatchers.IO) {
        preferences.getString(LOCAL_PREFIX + peerHex, null)?.toLongOrNull()
    }

    suspend fun putLocalChange(peerHex: String, changedAt: Long) {
        withContext(Dispatchers.IO) {
            preferences.edit().putString(LOCAL_PREFIX + peerHex, changedAt.toString()).commit()
        }
    }

    private fun decode(value: String?): PeerTimerStamp? {
        val (sentAt, appliedAt) = value?.split(FIELD)?.takeIf { it.size == 2 } ?: return null
        return PeerTimerStamp(sentAt.toLongOrNull() ?: return null, appliedAt.toLongOrNull() ?: return null)
    }

    private companion object {
        const val PREFERENCES = "peer_timers"
        const val FIELD = ":"

        /** Peer ids are hex, so this can never collide with a peer's own key. */
        const val LOCAL_PREFIX = "local:"
    }
}
