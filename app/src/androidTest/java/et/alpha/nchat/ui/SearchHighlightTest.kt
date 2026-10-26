package et.alpha.nchat.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import et.alpha.nchat.ui.chat.ChatSearch
import et.alpha.nchat.ui.components.MessageBubble
import et.alpha.nchat.ui.theme.NChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** That a search marks the match inside a bubble and leaves the words themselves alone. */
@RunWith(AndroidJUnit4::class)
class SearchHighlightTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aMatchIsMarkedWithoutChangingTheText() {
        val body = "meet at the North gate"
        compose.setContent {
            NChatTheme {
                MessageBubble(text = body, highlights = ChatSearch.matches(body, "north"), isOutgoing = false)
            }
        }

        val node = compose.onNodeWithText(body).assertIsDisplayed().fetchSemanticsNode()
        val shown = node.config.getOrNull(SemanticsProperties.Text)!!.single()
        assertEquals(body, shown.text)
        assertEquals(listOf(12 to 17), shown.spanStyles.map { it.start to it.end })
    }
}
