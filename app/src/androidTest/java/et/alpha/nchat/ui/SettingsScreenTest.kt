package et.alpha.nchat.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
                    onBack = {},
                    onDraftChange = { state = state.copy(draft = it) },
                    onSave = { saves++ },
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
                    onBack = {},
                    onDraftChange = {},
                    onSave = {},
                )
            }
        }

        compose.onNodeWithText(SELF).assertExists()
    }

    private companion object {
        const val SELF = "1ac3f00d9e8b7a65"
    }
}
