package et.alpha.nchat.ui.permissions

import android.Manifest
import android.os.Build

/**
 * Which runtime permissions the mesh needs, which differs sharply by API level.
 *
 * Android reorganised this area twice in three releases, and the sets are close
 * enough to look interchangeable while being anything but: requesting a
 * permission the platform does not know about is silently ignored, so a wrong
 * list fails as "discovery never finds anyone" rather than as an error.
 *
 * [requiredOn] takes the API level as a parameter rather than reading
 * [Build.VERSION.SDK_INT] so every branch is reachable from a JVM unit test.
 */
object MeshPermissions {

    /** The permissions to request on this device. */
    val required: List<String> get() = requiredOn(Build.VERSION.SDK_INT)

    fun requiredOn(sdkInt: Int): List<String> = buildList {
        if (sdkInt >= Build.VERSION_CODES.S) {
            // Android 12 split the old catch-all BLUETOOTH/BLUETOOTH_ADMIN into
            // three narrow runtime permissions.
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Location gated scanning through SDK 31 *inclusive*, so Android 12 needs
        // both this and the BLUETOOTH_* trio above. Nearby Connections refuses to
        // advertise or discover if any required permission is missing, so getting
        // this boundary wrong by one shows up as a mesh that finds nobody rather
        // than as an error. Google's own manifest sample uses maxSdkVersion=31.
        if (sdkInt <= Build.VERSION_CODES.S) {
            // Below 12, BLUETOOTH and BLUETOOTH_ADMIN are install-time and need no
            // request. This is the one permission users balk at — hence the
            // rationale screen.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            // Android 12 added the precise/approximate toggle. A FINE request that
            // does not carry COARSE with it is denied by the system, so these two
            // always travel together.
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13 moved Wi-Fi peer discovery out from under location.
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            // Not a mesh permission as such, but without it the foreground
            // service's notification is never shown — and a foreground service the
            // user cannot see is one they cannot stop.
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Permissions whose denial stops the mesh working at all, as opposed to
     * merely degrading it.
     *
     * POST_NOTIFICATIONS is the exception: the mesh runs fine without it, the
     * user just cannot see that it is running. Blocking the app on it would be
     * disproportionate.
     */
    fun essentialOn(sdkInt: Int): List<String> =
        requiredOn(sdkInt).filterNot { it == Manifest.permission.POST_NOTIFICATIONS }

    val essential: List<String> get() = essentialOn(Build.VERSION.SDK_INT)
}
