package et.alpha.nchat.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether opening the app asks for the phone's fingerprint, face or screen lock.
 *
 * The switch lives in the same plain `settings` preferences as the display
 * name. It is not a secret: the lock is the system's own credential check, so
 * there is no app PIN to store, to forget, or to brute-force offline.
 *
 * Owns the process-wide [gate] too, because the locked state has to outlive
 * the activity — a rotation or a notification tap recreates it, and neither
 * may count as unlocking.
 */
@Singleton
class AppLockStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val gate = AppLockGate(initiallyLocked = _enabled.value)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
        // Turning it on while using the app should not lock the screen the
        // user is looking at; turning it off must never leave it locked.
        gate.unlock()
    }

    private companion object {
        const val PREFERENCES = "settings"
        const val KEY_ENABLED = "app_lock"
    }
}
