package et.alpha.nchat.mesh.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Watches the switches the mesh depends on: the Bluetooth adapter and the system
 * location toggle.
 *
 * Both are user-flippable at any moment from the notification shade, so this is
 * a live [Flow] rather than a one-off check at startup — the common case is
 * precisely that the user turns Bluetooth on *after* seeing us say it is off.
 */
class RadioMonitor(context: Context) {

    private val appContext = context.applicationContext

    private val bluetoothAdapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val locationManager: LocationManager? =
        appContext.getSystemService(LocationManager::class.java)

    /** Samples both switches right now. */
    fun current(): RadioState = RadioState(
        bluetoothEnabled = isBluetoothEnabled(),
        locationServicesEnabled = isLocationEnabled(),
    )

    /**
     * Current state, re-emitted whenever either switch changes.
     *
     * Emits immediately on collection so a subscriber never has to wait for a
     * broadcast that may not come for hours.
     */
    val state: Flow<RadioState> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(current())
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }

        // NOT_EXPORTED: these are system broadcasts we listen for, never something
        // another app should be able to spoof into our process.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        trySend(current())

        awaitClose { appContext.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    /**
     * Reading the adapter needs a Bluetooth permission that may not be granted
     * yet, and a SecurityException here would take down whatever is collecting.
     * An unreadable adapter is reported as off, which is the safe direction: the
     * user sees "turn Bluetooth on" rather than a mesh that silently does nothing.
     */
    private fun isBluetoothEnabled(): Boolean =
        runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false)

    private fun isLocationEnabled(): Boolean =
        runCatching { locationManager?.let(LocationManagerCompat::isLocationEnabled) == true }
            .getOrDefault(false)
}
