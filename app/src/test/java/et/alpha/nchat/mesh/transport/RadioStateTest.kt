package et.alpha.nchat.mesh.transport

import android.os.Build
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which switches block the mesh, per API level.
 *
 * Worth pinning because the failure this guards against is invisible: with the
 * location toggle off, Nearby reports `MISSING_PERMISSION_ACCESS_COARSE_LOCATION`
 * — naming a permission that is granted — so trusting Nearby's own error would
 * send the user to the wrong screen entirely.
 */
class RadioStateTest {

    private val android10 = Build.VERSION_CODES.Q // 29
    private val android12 = Build.VERSION_CODES.S // 31
    private val android13 = Build.VERSION_CODES.TIRAMISU // 33

    private fun state(bluetooth: Boolean, location: Boolean) =
        RadioState(bluetoothEnabled = bluetooth, locationServicesEnabled = location)

    @Test
    fun `everything on is ready`() {
        val ready = state(bluetooth = true, location = true)

        for (sdk in listOf(android10, android12, android13)) {
            assertTrue(ready.isReadyOn(sdk), "SDK $sdk should be ready")
            assertEquals(emptyList<RadioBlocker>(), ready.blockersOn(sdk))
        }
    }

    @Test
    fun `bluetooth off blocks every version`() {
        val noBluetooth = state(bluetooth = false, location = true)

        for (sdk in listOf(android10, android12, android13)) {
            assertEquals(listOf(RadioBlocker.BLUETOOTH_OFF), noBluetooth.blockersOn(sdk))
        }
    }

    @Test
    fun `location off blocks through SDK 31 only`() {
        val noLocation = state(bluetooth = true, location = false)

        assertEquals(listOf(RadioBlocker.LOCATION_SERVICES_OFF), noLocation.blockersOn(android10))
        assertEquals(listOf(RadioBlocker.LOCATION_SERVICES_OFF), noLocation.blockersOn(android12))

        // Android 12 decoupled Bluetooth scanning from location, so demanding the
        // toggle there would be asking the user to enable something for nothing.
        assertTrue(noLocation.isReadyOn(32))
        assertTrue(noLocation.isReadyOn(android13))
    }

    @Test
    fun `both off reports both, bluetooth first`() {
        val nothing = state(bluetooth = false, location = false)

        assertEquals(
            listOf(RadioBlocker.BLUETOOTH_OFF, RadioBlocker.LOCATION_SERVICES_OFF),
            nothing.blockersOn(android10),
        )
        assertFalse(nothing.isReadyOn(android10))

        // On 13 the location toggle is irrelevant, so only Bluetooth is reported —
        // the screen must not offer a control that fixes nothing.
        assertEquals(listOf(RadioBlocker.BLUETOOTH_OFF), nothing.blockersOn(android13))
    }

    @Test
    fun `unknown state is treated as blocked`() {
        // Before anything is sampled the mesh must not be assumed workable.
        assertFalse(RadioState.UNKNOWN.isReadyOn(android10))
        assertFalse(RadioState.UNKNOWN.isReadyOn(android13))
    }
}
