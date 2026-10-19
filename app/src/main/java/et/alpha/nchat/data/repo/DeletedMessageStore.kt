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

    fun isDeleted(messageId: String, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) { tombstones.contains(messageId, now) }

    private companion object {
        const val PREFERENCES = "deleted_messages"
        const val KEY_IDS = "ids"
    }
}
