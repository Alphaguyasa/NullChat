package et.alpha.nchat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import et.alpha.nchat.ui.chat.ChatScreen
import et.alpha.nchat.ui.peers.PeerListScreen

/**
 * Host for the two chat screens.
 *
 * No navigation graph: with exactly two destinations, a nullable "open
 * conversation" value plus [BackHandler] is less machinery than a nav library
 * and has no argument-passing to get wrong.
 *
 * The open conversation is a [rememberSaveable] so a rotation does not throw the
 * user back to the peer list mid-conversation.
 */
@Composable
fun MeshChatScreen(modifier: Modifier = Modifier) {
    // Two pieces of state rather than one nullable, because null is already
    // meaningful: it is the broadcast thread, not "no thread open".
    var isChatOpen by rememberSaveable { mutableStateOf(false) }
    var openPeerId by rememberSaveable { mutableStateOf<String?>(null) }

    if (isChatOpen) {
        BackHandler { isChatOpen = false }
        ChatScreen(
            peerId = openPeerId,
            onBack = { isChatOpen = false },
            modifier = modifier,
        )
    } else {
        PeerListScreen(
            onOpenConversation = { peerId ->
                openPeerId = peerId
                isChatOpen = true
            },
            modifier = modifier,
        )
    }
}
