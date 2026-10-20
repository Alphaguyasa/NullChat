package et.alpha.nchat.data.wipe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanicWipeTest {

    @Test
    fun `an accepted wipe reports success`() {
        var calls = 0
        assertTrue(PanicWipe { calls++; true }.wipe())
        assertEquals(1, calls)
    }

    @Test
    fun `a refused wipe reports failure`() {
        assertFalse(PanicWipe { false }.wipe())
    }

    @Test
    fun `a wipe that throws reports failure rather than crashing the screen`() {
        // A crash here would close the app and look exactly like a wipe that worked.
        assertFalse(PanicWipe { throw SecurityException("denied") }.wipe())
    }
}
