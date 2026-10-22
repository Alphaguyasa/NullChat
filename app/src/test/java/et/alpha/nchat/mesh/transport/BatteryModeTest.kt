package et.alpha.nchat.mesh.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatteryModeTest {

    @Test
    fun `automatic follows the phone's battery saver`() {
        assertTrue(BatteryMode.AUTOMATIC.saves(systemSaverOn = true))
        assertFalse(BatteryMode.AUTOMATIC.saves(systemSaverOn = false))
    }

    @Test
    fun `normal and saver ignore the phone's switch`() {
        assertFalse(BatteryMode.NORMAL.saves(systemSaverOn = true))
        assertTrue(BatteryMode.SAVER.saves(systemSaverOn = false))
    }

    @Test
    fun `a stored choice reads back, and anything else is automatic`() {
        assertEquals(BatteryMode.SAVER, BatteryMode.fromStored("SAVER"))
        assertEquals(BatteryMode.AUTOMATIC, BatteryMode.fromStored(null))
        assertEquals(BatteryMode.AUTOMATIC, BatteryMode.fromStored("TURBO"))
    }
}
