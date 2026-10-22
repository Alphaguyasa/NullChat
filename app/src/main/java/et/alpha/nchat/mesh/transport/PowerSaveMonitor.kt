package et.alpha.nchat.mesh.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Watches the phone's own battery saver switch, which [BatteryMode.AUTOMATIC]
 * follows.
 *
 * A live flow for the same reason as [RadioMonitor]: the switch sits in the
 * notification shade and gets flipped when the battery runs low, which is
 * exactly while the mesh is running.
 */
@Singleton
class PowerSaveMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val appContext = context.applicationContext

    private val powerManager: PowerManager? = appContext.getSystemService(PowerManager::class.java)

    fun isOn(): Boolean = powerManager?.isPowerSaveMode == true

    /** Current state, re-emitted whenever the system switch changes. */
    val saverOn: Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(isOn())
            }
        }
        // NOT_EXPORTED, as in RadioMonitor: a system broadcast still arrives, and
        // no other app can fake one to slow this phone's mesh down.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        trySend(isOn())
        awaitClose { appContext.unregisterReceiver(receiver) }
    }.distinctUntilChanged()
}
