package et.alpha.nchat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import et.alpha.nchat.ui.chat.ChatTopBar
import et.alpha.nchat.ui.peers.PeerRow
import et.alpha.nchat.ui.peers.PeerRowItem
import et.alpha.nchat.ui.theme.NChatTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two places where 200% font scale used to cost information: the chat
 * bar's second line, and the badge beside a long contact name.
 *
 * Both are laid out at 360dp, the narrowest common phone width, because that
 * is where a long line has the least room to give.
 */
@RunWith(AndroidJUnit4::class)
class LargeFontTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setLarge(content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                NChatTheme { Box(Modifier.width(360.dp).testTag(FRAME)) { content() } }
            }
        }
    }

    @Test
    fun theChatBarKeepsItsSubtitleWithTimerMuteAndAlert() {
        val subtitle = "in range · disappears after 1 week · muted · in-range alert on"
        setLarge {
            ChatTopBar(
                title = "Maya Tesfaye",
                subtitle = subtitle,
                isMonoTitle = false,
                isOnline = true,
                onBack = {},
                onMute = {},
                isMuted = true,
                onWatchRange = {},
                isWatchingRange = true,
            )
        }

        compose.onNodeWithText("Maya Tesfaye").assertIsDisplayed()
        compose.onNodeWithText(subtitle).assertIsDisplayed()
        compose.onNodeWithContentDescription("More options").assertIsDisplayed()

        // Displayed is not enough: a fixed-height bar would place the line and
        // clip it. The bar must have grown to hold all of it.
        val bar = compose.onNodeWithTag(FRAME).getUnclippedBoundsInRoot()
        val line = compose.onNodeWithText(subtitle).getUnclippedBoundsInRoot()
        assertTrue("subtitle ends below the bar", line.bottom <= bar.bottom)
        assertTrue("subtitle runs past the bar's width", line.right <= bar.right)
    }

    @Test
    fun aLongNameGivesWayToTheBadgeAndTheUnreadCount() {
        setLarge {
            PeerRowItem(
                row = PeerRow(
                    peerId = "aabbccddeeff0011",
                    label = "Alemayehu Gebremedhin Woldemariam",
                    isOnline = true,
                    lastMessage = "see you at the north gate",
                    unreadCount = 3,
                    isVerified = false,
                ),
                onClick = {},
            )
        }

        val row = compose.onNodeWithTag(FRAME).getUnclippedBoundsInRoot()
        compose.onNodeWithText("unverified").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        val badge = compose.onNodeWithText("unverified").getUnclippedBoundsInRoot()
        val unread = compose.onNodeWithText("3").getUnclippedBoundsInRoot()
        assertTrue("badge pushed off the row", badge.right <= row.right)
        assertTrue("unread count pushed off the row", unread.right <= row.right)
        assertTrue("badge overlaps the unread count", badge.right <= unread.left)
    }

    private companion object {
        const val FRAME = "frame"
    }
}
