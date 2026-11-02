package et.alpha.nchat.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the user wants the mesh running: finding phones, receiving and
 * relaying.
 *
 * On by default, so an install or a panic wipe starts meshing as before. Kept
 * across restarts, because someone who stopped the mesh to save battery or to
 * stay silent on the air expects it to stay stopped when the phone reboots or
 * the app is reopened, not to come back on its own.
 *
 * In the same plain `settings` preferences as the app lock and battery mode.
 */
@Singleton
class MeshEnabledStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, true))

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        // apply is enough even for the notification's Stop action: Android
        // waits for pending preference writes before it finishes stopping a
        // service.
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
    }

    private companion object {
        const val PREFERENCES = "settings"
        const val KEY_ENABLED = "mesh_enabled"
    }
}
