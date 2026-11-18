package et.alpha.nchat.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The contacts the user asked to hear about when they come into range
 * (roadmap #77).
 *
 * In preferences rather than Room: a handful of peer ids read as one set,
 * which does not justify a schema version. Kept across restarts, because
 * "tell me when Maya is here" is meant for later, often after a reboot.
 * Forgetting a contact removes it (see `ChatViewModel.onForget`), and a panic
 * wipe clears it with everything else.
 */
@Singleton
class RangeWatchStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val _watched = MutableStateFlow(
        preferences.getStringSet(KEY_WATCHED, null).orEmpty().toSet(),
    )

    val watched: StateFlow<Set<String>> = _watched.asStateFlow()

    @Synchronized
    fun setWatched(peerHex: String, watched: Boolean) {
        val next = if (watched) _watched.value + peerHex else _watched.value - peerHex
        if (next == _watched.value) return
        // A fresh set each time: the platform keeps a reference to the one it
        // returned and may not persist edits made to it in place.
        preferences.edit().putStringSet(KEY_WATCHED, HashSet(next)).apply()
        _watched.value = next
    }

    private companion object {
        const val PREFERENCES = "settings"
        const val KEY_WATCHED = "range_watch"
    }
}
