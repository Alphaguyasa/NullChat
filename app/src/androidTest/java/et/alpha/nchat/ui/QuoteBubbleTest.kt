package et.alpha.nchat.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import et.alpha.nchat.mesh.protocol.Quote
import et.alpha.nchat.ui.components.MessageBubble
import et.alpha.nchat.ui.theme.NChatTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a reply shows its quote apart from its answer, and that a screen reader
 * hears the quote as a quote rather than as the opening words of the message.
 */
@RunWith(AndroidJUnit4::class)
class QuoteBubbleTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aReplyShowsItsQuoteAndItsAnswerSeparately() {
        val parsed = Quote.parse(Quote.compose("meet me at the north gate", "on my way"))!!
        compose.setContent {
            NChatTheme {
                MessageBubble(text = parsed.reply, quote = parsed.quote, isOutgoing = false)
            }
        }

        compose.onNodeWithText("on my way").assertIsDisplayed()
        compose.onNodeWithContentDescription("In reply to: meet me at the north gate")
            .assertIsDisplayed()
    }
}
