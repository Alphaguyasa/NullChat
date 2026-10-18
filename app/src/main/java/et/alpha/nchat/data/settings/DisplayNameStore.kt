package et.alpha.nchat.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import et.alpha.nchat.mesh.protocol.Profile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The name this device shows to people nearby, if the user picked one.
 *
 * In plain preferences rather than Room: it is one value, it has no history,
 * and the mesh needs it synchronously the moment a peer connects. Not in the
 * `mesh_keys` file either, which is excluded from backup because it holds
 * private keys — a display name is nothing to protect, and keeping the two
 * apart means a future "reset name" can never touch the identity.
 *
 * Stored already normalised by [Profile.normalize], so what is read back is
 * exactly what goes on the air and what receivers will accept.
 */
@Singleton
class DisplayNameStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val _displayName = MutableStateFlow(preferences.getString(KEY_NAME, null))

    /** The chosen name, or null when the user has not set one. */
    val displayName: StateFlow<String?> = _displayName.asStateFlow()

    /**
     * Saves [input] in normal form, or clears the name when nothing showable is
     * left.
     *
     * @return the name as stored, or null when it was cleared.
     */
    suspend fun set(input: String): String? {
        val name = Profile.fromInput(input)?.displayName
        withContext(Dispatchers.IO) {
            preferences.edit().apply {
                if (name == null) remove(KEY_NAME) else putString(KEY_NAME, name)
            }.commit()
        }
        _displayName.value = name
        return name
    }

    private companion object {
        const val PREFERENCES = "settings"
        const val KEY_NAME = "display_name"
    }
}
