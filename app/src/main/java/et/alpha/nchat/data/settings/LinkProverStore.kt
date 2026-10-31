package et.alpha.nchat.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Peers that have sent this phone a LINK_PROOF matching its link; see
 * `LinkBindings`. From then on their announcement alone binds no link.
 *
 * It must survive a restart: otherwise a relay could pose as a contact again
 * every time this phone restarts, just by not passing the proof on.
 *
 * Read synchronously from the binding check, so the set is held in memory and
 * written through with `apply()`. One 16-character id per updated peer ever
 * met; capped so a crowd of throwaway identities cannot grow it without limit.
 * Past the cap a new peer is simply not remembered, which is the older, looser
 * rule, not a lockout.
 *
 * Kept when a contact is forgotten: stricter is the safe side. Panic wipe
 * clears it with the rest of the app's data.
 */
@Singleton
class LinkProverStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val provers: MutableSet<String> by lazy {
        ConcurrentHashMap.newKeySet<String>().apply {
            addAll(preferences.getStringSet(KEY, emptySet()).orEmpty())
        }
    }

    fun contains(peerHex: String): Boolean = peerHex in provers

    fun add(peerHex: String) {
        if (peerHex in provers || provers.size >= MAX_PROVERS) return
        provers.add(peerHex)
        // A copy: the set getStringSet returns must not be modified in place.
        preferences.edit().putStringSet(KEY, HashSet(provers)).apply()
    }

    private companion object {
        const val PREFERENCES = "link_provers"
        const val KEY = "peers"
        const val MAX_PROVERS = 5000
    }
}
