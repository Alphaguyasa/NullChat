package et.alpha.nchat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import et.alpha.nchat.ui.chat.ChatScreen
import et.alpha.nchat.ui.pair.QrPairScreen
import et.alpha.nchat.ui.peers.PeerListScreen
import et.alpha.nchat.ui.theme.motionSpec
import et.alpha.nchat.ui.theme.rememberAnimationsEnabled

/** Where the user currently is. */
private enum class Destination { PEERS, CHAT, PAIR }

/**
 * Host for the app's three screens.
 *
 * Still no navigation graph. This used to justify itself with "exactly two
 * destinations"; at three it is a small enum and a `when`, which is still less
 * machinery than a nav library and still has no argument-passing to get wrong.
 * A fourth destination, or any need for deep links, is the point to reconsider.
 *
 * The destination and open conversation are [rememberSaveable] so a rotation
 * does not throw the user back to the peer list mid-conversation.
 *
 * Screens cross-fade rather than slide. A slide claims the destinations sit in
 * some spatial order — that chat is "to the right of" the peer list — and with a
 * flat enum and no back stack there is no such order to be honest about.
 */
@Composable
fun MeshChatScreen(modifier: Modifier = Modifier) {
    var destination by rememberSaveable { mutableStateOf(Destination.PEERS) }

    // Kept separate from [destination] because null is already meaningful here:
    // it is the broadcast thread, not "no thread open".
    var openPeerId by rememberSaveable { mutableStateOf<String?>(null) }

    // Outside the crossfade: for the length of a fade both the outgoing and the
    // incoming screen are composed, and two enabled handlers would race over
    // which one a back press reached.
    BackHandler(enabled = destination != Destination.PEERS) { destination = Destination.PEERS }

    Crossfade(
        targetState = destination,
        modifier = modifier,
        animationSpec = motionSpec(rememberAnimationsEnabled()),
        label = "destination",
    ) { current ->
        when (current) {
            Destination.CHAT -> ChatScreen(
                peerId = openPeerId,
                onBack = { destination = Destination.PEERS },
                onPair = { destination = Destination.PAIR },
            )

            Destination.PAIR -> QrPairScreen(onBack = { destination = Destination.PEERS })

            Destination.PEERS -> PeerListScreen(
                onOpenConversation = { peerId ->
                    openPeerId = peerId
                    destination = Destination.CHAT
                },
                onOpenPairing = { destination = Destination.PAIR },
            )
        }
    }
}
