package et.alpha.nchat.ui.permissions

import android.Manifest
import android.os.Build
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The per-API-level permission sets.
 *
 * Worth pinning precisely because getting these wrong does not throw: Android
 * ignores a request for a permission it does not recognise, so an incorrect list
 * surfaces as "discovery silently finds nobody" on one OS version and works fine
 * on another.
 */
class MeshPermissionsTest {

    private val legacy = Build.VERSION_CODES.P // 28
    private val android12 = Build.VERSION_CODES.S // 31
    private val android13 = Build.VERSION_CODES.TIRAMISU // 33

    @Test
    fun `below SDK 31 asks for location, not the new bluetooth permissions`() {
        val required = MeshPermissions.requiredOn(legacy)

        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            required,
        )
    }

    @Test
    fun `fine location never travels without coarse`() {
        // Android 12's precise/approximate toggle denies a lone FINE request.
        for (sdk in listOf(legacy, android12, android13)) {
            val required = MeshPermissions.requiredOn(sdk)
            if (Manifest.permission.ACCESS_FINE_LOCATION in required) {
                assertTrue(
                    Manifest.permission.ACCESS_COARSE_LOCATION in required,
                    "SDK $sdk requests FINE without COARSE",
                )
            }
        }
    }

    @Test
    fun `SDK 31 needs the bluetooth trio AND location`() {
        val required = MeshPermissions.requiredOn(android12)

        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            required,
        )
    }

    @Test
    fun `location stops being requested at SDK 32`() {
        // The boundary Google's manifest sample puts at maxSdkVersion=31, which is
        // inclusive. Off by one in either direction and Nearby refuses to start on
        // a whole OS version, silently.
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in MeshPermissions.requiredOn(31))
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in MeshPermissions.requiredOn(32))
    }

    @Test
    fun `SDK 33 adds nearby wifi and notifications`() {
        val required = MeshPermissions.requiredOn(android13)

        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            required,
        )
    }

    @Test
    fun `notifications are requested but never treated as essential`() {
        // The mesh works without it; the user just cannot see that it is running.
        // Gating the app on a notification permission would be disproportionate.
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in MeshPermissions.requiredOn(android13))
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in MeshPermissions.essentialOn(android13))
    }

    @Test
    fun `every level asks for something`() {
        for (sdk in listOf(legacy, android12, android13, Build.VERSION_CODES.UPSIDE_DOWN_CAKE)) {
            assertTrue(
                MeshPermissions.essentialOn(sdk).isNotEmpty(),
                "SDK $sdk must still require at least one essential permission",
            )
        }
    }
}
