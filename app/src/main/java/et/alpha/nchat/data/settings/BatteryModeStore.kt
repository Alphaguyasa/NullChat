package et.alpha.nchat.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import et.alpha.nchat.mesh.transport.BatteryMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's [BatteryMode], in the same plain `settings` preferences as the app lock. */
@Singleton
class BatteryModeStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(BatteryMode.fromStored(preferences.getString(KEY_MODE, null)))

    val mode: StateFlow<BatteryMode> = _mode.asStateFlow()

    fun set(mode: BatteryMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    private companion object {
        const val PREFERENCES = "settings"
        const val KEY_MODE = "battery_mode"
    }
}
