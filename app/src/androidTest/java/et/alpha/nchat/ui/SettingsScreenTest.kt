package et.alpha.nchat.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import et.alpha.nchat.ui.settings.SettingsContent
import et.alpha.nchat.ui.settings.SettingsState
import et.alpha.nchat.ui.theme.NChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun saveIsOfferedOnlyOnceTheNameChanges() {
        var saves = 0
        // Outside setContent, so a recomposition reads it rather than recreating it.
        var state by mutableStateOf(SettingsState(selfId = SELF, savedName = "Maya", draft = "Maya"))
        compose.setContent {
            NChatTheme {
                SettingsContent(
                    state = state,
                    saved = false,
                    wipeFailed = false,
                    onBack = {},
                    onDraftChange = { state = state.copy(draft = it) },
                    onSave = { saves++ },
                    onWipe = {},
                )
            }
        }

        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Maya").performTextReplacement("Maya T")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals(1, saves)
    }

    @Test
    fun theFullIdIsShown() {
        compose.setContent {
            NChatTheme {
                SettingsContent(
                    state = SettingsState(selfId = SELF),
                    saved = false,
                    wipeFailed = false,
                    onBack = {},
                    onDraftChange = {},
                    onSave = {},
                    onWipe = {},
                )
            }
        }

        compose.onNodeWithText(SELF).assertExists()
    }

    @Test
    fun wipingAsksFirstAndCancelErasesNothing() {
        var wipes = 0
        setWipeContent(wipeFailed = false) { wipes++ }

        compose.onNodeWithText("Wipe this phone").performScrollTo().performClick()
        compose.onNodeWithText("Wipe everything?").assertIsDisplayed()
        assertEquals(0, wipes)

        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Wipe everything?").assertDoesNotExist()
        assertEquals(0, wipes)
    }

    @Test
    fun confirmingRunsTheWipeOnce() {
        var wipes = 0
        setWipeContent(wipeFailed = false) { wipes++ }

        compose.onNodeWithText("Wipe this phone").performScrollTo().performClick()
        compose.onNodeWithText("Wipe").performClick()

        assertEquals(1, wipes)
    }

    @Test
    fun aRefusedWipeSaysNothingWasErased() {
        setWipeContent(wipeFailed = true) {}

        compose.onNodeWithText("nothing was erased", substring = true).assertExists()
    }

    @Test
    fun theLockSwitchReportsTheNewSetting() {
        val changes = mutableListOf<Boolean>()
        setLockContent(lockEnabled = false, lockAvailable = true) { changes += it }

        compose.onNode(isToggleable()).performScrollTo().assertIsOff().performClick()

        assertEquals(listOf(true), changes)
    }

    @Test
    fun withoutAScreenLockTheSwitchCannotBeTurnedOn() {
        setLockContent(lockEnabled = false, lockAvailable = false) {}

        compose.onNode(isToggleable()).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("screen lock, set in Android settings", substring = true).assertExists()
    }

    @Test
    fun aLockThatIsOnCanAlwaysBeTurnedOff() {
        // The phone's screen lock was removed after the app lock was turned on.
        val changes = mutableListOf<Boolean>()
        setLockContent(lockEnabled = true, lockAvailable = false) { changes += it }

        compose.onNode(isToggleable()).performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf(false), changes)
    }

    private fun setLockContent(
        lockEnabled: Boolean,
        lockAvailable: Boolean,
        onLockChange: (Boolean) -> Unit,
    ) {
        compose.setContent {
            NChatTheme {
                SettingsContent(
                    state = SettingsState(selfId = SELF, lockEnabled = lockEnabled),
                    saved = false,
                    wipeFailed = false,
                    onBack = {},
                    onDraftChange = {},
                    onSave = {},
                    onWipe = {},
                    lockAvailable = lockAvailable,
                    onLockChange = onLockChange,
                )
            }
        }
    }

    private fun setWipeContent(wipeFailed: Boolean, onWipe: () -> Unit) {
        compose.setContent {
            NChatTheme {
                SettingsContent(
                    state = SettingsState(selfId = SELF),
                    saved = false,
                    wipeFailed = wipeFailed,
                    onBack = {},
                    onDraftChange = {},
                    onSave = {},
                    onWipe = onWipe,
                )
            }
        }
    }

    private companion object {
        const val SELF = "1ac3f00d9e8b7a65"
    }
}
