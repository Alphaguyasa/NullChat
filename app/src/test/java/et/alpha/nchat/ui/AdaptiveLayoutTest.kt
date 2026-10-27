package et.alpha.nchat.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdaptiveLayoutTest {

    @Test
    fun `phones upright and tablets in split screen keep one pane`() {
        assertFalse(usesTwoPanes(360))
        assertFalse(usesTwoPanes(600))
        assertFalse(usesTwoPanes(TWO_PANE_MIN_WIDTH_DP - 1))
    }

    @Test
    fun `expanded widths get the list and the chat side by side`() {
        assertTrue(usesTwoPanes(TWO_PANE_MIN_WIDTH_DP))
        assertTrue(usesTwoPanes(1280))
    }

    @Test
    fun `the list pane is a third of the window within its bounds`() {
        assertEquals(320, listPaneWidthDp(840))
        assertEquals(360, listPaneWidthDp(1080))
        assertEquals(420, listPaneWidthDp(1600))
    }
}
