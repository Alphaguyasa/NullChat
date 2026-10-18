package et.alpha.nchat.ui.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsStateTest {

    @Test
    fun `an unchanged name cannot be saved`() {
        assertFalse(SettingsState(savedName = "Maya", draft = "Maya").canSave)
    }

    @Test
    fun `a name that only differs in stray spaces counts as unchanged`() {
        // It would be stored as "Maya" again, so saving would do nothing.
        assertFalse(SettingsState(savedName = "Maya", draft = "  Maya ").canSave)
    }

    @Test
    fun `a new name can be saved`() {
        assertTrue(SettingsState(savedName = "Maya", draft = "Maya T").canSave)
    }

    @Test
    fun `clearing a saved name can be saved`() {
        assertTrue(SettingsState(savedName = "Maya", draft = "").canSave)
    }

    @Test
    fun `an empty box with no name saved has nothing to save`() {
        assertFalse(SettingsState(savedName = null, draft = "   ").canSave)
    }

    @Test
    fun `the counter measures what would be stored, in bytes`() {
        // Three Ge'ez letters at three bytes each; the spaces around them go.
        assertEquals(9, SettingsState(draft = " ሰላም ").draftBytes)
    }
}
