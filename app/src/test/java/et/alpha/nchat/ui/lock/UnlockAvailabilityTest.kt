package et.alpha.nchat.ui.lock

import androidx.biometric.BiometricManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When an app lock that is on must turn itself off, because nothing on the
 * phone could ever answer the prompt (roadmap #67).
 */
class UnlockAvailabilityTest {

    private val android14 = 34

    @Test
    fun `a phone with a screen lock keeps the app lock`() {
        assertFalse(unlockIsImpossible(android14, BiometricManager.BIOMETRIC_SUCCESS))
    }

    @Test
    fun `a removed screen lock can never be answered`() {
        assertTrue(unlockIsImpossible(android14, BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
    }

    @Test
    fun `no hardware or an unsupported setup can never be answered`() {
        assertTrue(unlockIsImpossible(android14, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertTrue(unlockIsImpossible(android14, BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED))
    }

    @Test
    fun `a backup restored onto Android 8 can never be answered`() {
        // The prompt is not offered below Android 9 at all; see SystemUnlock.
        assertTrue(unlockIsImpossible(27, BiometricManager.BIOMETRIC_SUCCESS))
    }

    @Test
    fun `a sensor that is busy for now does not turn the lock off`() {
        // Otherwise anyone holding the phone while the sensor is unavailable
        // would get in; the PIN fallback still answers these.
        assertFalse(unlockIsImpossible(android14, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
        assertFalse(unlockIsImpossible(android14, BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED))
        assertFalse(unlockIsImpossible(android14, BiometricManager.BIOMETRIC_STATUS_UNKNOWN))
    }
}
