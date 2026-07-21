package et.alpha.nchat.mesh.transport

import android.os.Build

/**
 * Highest SDK on which the mesh still depends on location — both the
 * `ACCESS_FINE_LOCATION` permission and the system location *toggle*.
 *
 * The two move together: Android gated BLE scanning behind location until 12,
 * so any release that needs the permission also needs the service switched on.
 * Declared here, in the layer that talks to the radio, and referenced from
 * `MeshPermissions` so the boundary is stated once.
 */
const val LOCATION_REQUIRED_MAX_SDK: Int = Build.VERSION_CODES.S

/** Something switched off that stops the mesh working, and that the user can fix. */
enum class RadioBlocker {
    BLUETOOTH_OFF,
    LOCATION_SERVICES_OFF,
}

/**
 * Whether the radios the mesh needs are actually switched on.
 *
 * Distinct from permissions: a granted permission to a disabled service still
 * gets you nothing. Nearby is actively misleading here — with the system
 * location toggle off it fails discovery with
 * `8034 MISSING_PERMISSION_ACCESS_COARSE_LOCATION`, naming a permission that is
 * granted. Checking the toggles ourselves is the only way to tell the user
 * something true.
 */
data class RadioState(
    val bluetoothEnabled: Boolean,
    val locationServicesEnabled: Boolean,
) {

    /**
     * What is switched off that needs to be on, for a given API level.
     *
     * Empty means the mesh can actually run. Order is the order to present them
     * in: Bluetooth first, because it is the one users recognise as related to
     * talking to nearby phones.
     */
    fun blockersOn(sdkInt: Int): List<RadioBlocker> = buildList {
        if (!bluetoothEnabled) add(RadioBlocker.BLUETOOTH_OFF)
        if (sdkInt <= LOCATION_REQUIRED_MAX_SDK && !locationServicesEnabled) {
            add(RadioBlocker.LOCATION_SERVICES_OFF)
        }
    }

    fun isReadyOn(sdkInt: Int): Boolean = blockersOn(sdkInt).isEmpty()

    /** Convenience for production code, which always means the running device. */
    val blockers: List<RadioBlocker> get() = blockersOn(Build.VERSION.SDK_INT)
    val isReady: Boolean get() = blockers.isEmpty()

    companion object {
        /** Assumed state before anything has been sampled: blocked, not ready. */
        val UNKNOWN = RadioState(bluetoothEnabled = false, locationServicesEnabled = false)
    }
}
