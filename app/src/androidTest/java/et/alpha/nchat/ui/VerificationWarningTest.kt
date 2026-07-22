package et.alpha.nchat.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import et.alpha.nchat.ui.chat.WarningBanner
import et.alpha.nchat.ui.peers.PeerRow
import et.alpha.nchat.ui.peers.PeerRowItem
import et.alpha.nchat.ui.theme.NChatTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the app actually *says* when a contact has not been verified.
 *
 * The crypto tests prove a forged packet is rejected. These prove the other
 * half, which is easier to get wrong and impossible to notice from a unit test:
 * that a user looking at the screen can tell the difference between a contact
 * whose key they checked and one they did not.
 */
@RunWith(AndroidJUnit4::class)
class VerificationWarningTest {

    @get:Rule
    val compose = createComposeRule()

    private fun peer(isVerified: Boolean) = PeerRow(
        peerId = "aabbccddeeff0011",
        label = "aabbccdd",
        isOnline = true,
        lastMessage = null,
        unreadCount = 0,
        isVerified = isVerified,
    )

    @Test
    fun anUnverifiedPeerIsBadged() {
        compose.setContent {
            NChatTheme { PeerRowItem(row = peer(isVerified = false), onClick = {}) }
        }

        compose.onNodeWithText("unverified").assertIsDisplayed()
    }

    @Test
    fun aVerifiedPeerIsNotBadged() {
        compose.setContent {
            NChatTheme { PeerRowItem(row = peer(isVerified = true), onClick = {}) }
        }

        // The badge marks the exception, not the rule. If it showed on a
        // verified contact too it would carry no information at all.
        compose.onAllNodesWithText("unverified").fetchSemanticsNodes().let {
            assertTrue("a verified peer must not be badged", it.isEmpty())
        }
    }

    @Test
    fun theBroadcastRowIsNotBadged() {
        val broadcast = PeerRow(
            peerId = null,
            label = "Everyone",
            isOnline = true,
            lastMessage = null,
            unreadCount = 0,
            isVerified = false,
        )

        compose.setContent { NChatTheme { PeerRowItem(row = broadcast, onClick = {}) } }

        // "Everyone" is not a contact and cannot be verified, so badging it
        // would be noise.
        compose.onAllNodesWithText("unverified").fetchSemanticsNodes().let {
            assertTrue("the broadcast row must not be badged", it.isEmpty())
        }
    }

    @Test
    fun theUnverifiedBannerOffersPairing() {
        var pairTapped = false
        compose.setContent {
            NChatTheme {
                WarningBanner(
                    text = "You have not verified this contact.",
                    actionLabel = "Pair",
                    onAction = { pairTapped = true },
                )
            }
        }

        compose.onNodeWithText("You have not verified this contact.").assertIsDisplayed()
        compose.onNodeWithText("Pair").performClick()

        assertTrue("the warning must lead somewhere, not just scold", pairTapped)
    }

    @Test
    fun aBannerWithoutAnActionStillShows() {
        compose.setContent {
            NChatTheme { WarningBanner(text = "Messages to Everyone are not encrypted.") }
        }

        compose.onNodeWithText("Messages to Everyone are not encrypted.").assertIsDisplayed()
    }
}
