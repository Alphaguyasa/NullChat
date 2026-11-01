package et.alpha.nchat.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which `startForeground` failures the mesh service swallows with `stopSelf()`.
 *
 * Swallowing too much would hide real bugs; swallowing too little is the crash
 * loop of roadmap #55, where START_STICKY restarts a service that throws on
 * every start.
 */
class ForegroundStartTest {

    @Test
    fun `missing foreground service type permission is a refusal`() {
        assertTrue(isForegroundStartRefusal(SecurityException("connectedDevice needs BLUETOOTH_CONNECT")))
    }

    @Test
    fun `background start not allowed is a refusal`() {
        // ForegroundServiceStartNotAllowedException is an IllegalStateException.
        assertTrue(isForegroundStartRefusal(IllegalStateException("startForegroundService() not allowed")))
    }

    @Test
    fun `other failures still crash`() {
        assertFalse(isForegroundStartRefusal(NullPointerException()))
        assertFalse(isForegroundStartRefusal(IllegalArgumentException()))
    }
}
