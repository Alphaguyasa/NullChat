package et.alpha.nchat.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists [Tombstones] across restarts.
 *
 * In preferences rather than Room: the set is short-lived, never queried by
 * anything but an exact id, and a table for it would cost a schema version and a
 * migration for data that expires within a week. It survives a restart because
 * the forward queues that would replay a deleted message do too.
 *
 * Two sets with separate caps: the user's own deletions (delete, delete chat,
 * forget contact, disappearing messages) and the Everyone thread's automatic
 * trim. The trim writes one tombstone for every stranger's broadcast past the
 * thread's cap, so in a crowd it can write thousands an hour; sharing one cap
 * let that flood push out the user's deletions and bring deleted messages back.
 */
@Singleton
class DeletedMessageStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val lock = Any()

    // Read on first use, which is on the mesh's own scope, not the main thread.
    private val tombstones: Tombstones by lazy {
        Tombstones.decode(preferences.getString(KEY_IDS, null))
    }

    private val trimmed: Tombstones by lazy {
        Tombstones.decode(preferences.getString(KEY_TRIMMED, null), TRIMMED_MAX_ENTRIES)
    }

    /** Records that the user deleted [messageId], before the row goes. */
    suspend fun remember(messageId: String, now: Long = System.currentTimeMillis()) {
        withContext(Dispatchers.IO) {
            // The write is inside the lock so two deletions cannot commit out of
            // order and leave the older set on disk.
            synchronized(lock) {
                tombstones.add(messageId, now)
                preferences.edit().putString(KEY_IDS, tombstones.encode()).commit()
            }
        }
    }

    /**
     * Records several deletions with one write, for the disappearing-message
     * sweep, which can expire a whole evening of a busy thread at once.
     */
    suspend fun rememberAll(messageIds: List<String>, now: Long = System.currentTimeMillis()) {
        if (messageIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                messageIds.forEach { tombstones.add(it, now) }
                preferences.edit().putString(KEY_IDS, tombstones.encode()).commit()
            }
        }
    }

    /**
     * Records broadcasts the Everyone thread dropped to stay under its cap, in
     * their own set, so they never evict a deletion the user made.
     */
    suspend fun rememberTrimmed(messageIds: List<String>, now: Long = System.currentTimeMillis()) {
        if (messageIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                messageIds.forEach { trimmed.add(it, now) }
                preferences.edit().putString(KEY_TRIMMED, trimmed.encode()).commit()
            }
        }
    }

    fun isDeleted(messageId: String, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) { tombstones.contains(messageId, now) || trimmed.contains(messageId, now) }

    private companion object {
        const val PREFERENCES = "deleted_messages"
        const val KEY_IDS = "ids"
        const val KEY_TRIMMED = "trimmed_ids"

        /**
         * Only needs to outlast a burst: a trimmed broadcast that comes back
         * after its tombstone went lands as the newest row and is trimmed again
         * on the next arrival, so the failure is one stale line, not a loop.
         */
        const val TRIMMED_MAX_ENTRIES = 1000
    }
}
