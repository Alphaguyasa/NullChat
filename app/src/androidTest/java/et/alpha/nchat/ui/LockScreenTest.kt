package et.alpha.nchat.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import et.alpha.nchat.ui.lock.LockScreen
import et.alpha.nchat.ui.theme.NChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LockScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unlockAsksAgainAfterACancelledPrompt() {
        var prompts = 0
        compose.setContent { NChatTheme { LockScreen(onUnlock = { prompts++ }) } }

        compose.onNodeWithText("Null Chat is locked").assertIsDisplayed()
        compose.onNodeWithText("Unlock").performClick()
        compose.onNodeWithText("Unlock").performClick()

        assertEquals(2, prompts)
    }
}
