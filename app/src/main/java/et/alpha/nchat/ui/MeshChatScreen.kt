package et.alpha.nchat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import et.alpha.nchat.R
import et.alpha.nchat.ui.chat.ChatScreen
import et.alpha.nchat.ui.pair.QrPairScreen
import et.alpha.nchat.ui.peers.PeerListScreen
import et.alpha.nchat.ui.settings.SettingsScreen
import et.alpha.nchat.ui.theme.motionSpec
import et.alpha.nchat.ui.theme.rememberAnimationsEnabled

/** Where the user currently is. */
private enum class Destination { PEERS, CHAT, PAIR, SETTINGS }

/**
 * Host for the app's four screens.
 *
 * Still no navigation graph. At four destinations this is a small enum and a
 * `when`, and still the only argument passed anywhere is the open thread — less
 * machinery than a nav library and nothing to get wrong. The roadmap's fourth
 * destination (settings) was the point to reconsider; it changed nothing,
 * because every screen still returns to the peer list. A screen reached from
 * another non-root screen, or a need for deep links, is the next such point.
 *
 * The destination and open conversation are [rememberSaveable] so a rotation
 * does not throw the user back to the peer list mid-conversation.
 *
 * On a wide window ([usesTwoPanes]) the list and the open chat sit side by
 * side, with the same destinations: PEERS is the list with no chat picked, and
 * back from a chat closes it. Pairing and settings still take the whole window;
 * they are tasks of their own, not something to glance at beside a thread.
 *
 * Screens cross-fade rather than slide. A slide claims the destinations sit in
 * some spatial order — that chat is "to the right of" the peer list — and with a
 * flat enum and no back stack there is no such order to be honest about.
 *
 * @param openPeerRequest a direct thread to jump to, from a notification tap.
 *   Reported back through [onOpenRequestHandled] once taken, so that a second
 *   tap for the same peer is a new request rather than an unchanged value.
 * @param shareRequest text another app shared in, taken the same way through
 *   [onShareRequestHandled]. The peer list then asks which chat it is for, and
 *   that chat opens with the text in its box; see `SharedText`.
 */
@Composable
fun MeshChatScreen(
    modifier: Modifier = Modifier,
    openPeerRequest: String? = null,
    onOpenRequestHandled: () -> Unit = {},
    shareRequest: String? = null,
    onShareRequestHandled: () -> Unit = {},
) {
    var destination by rememberSaveable { mutableStateOf(Destination.PEERS) }

    // Kept separate from [destination] because null is already meaningful here:
    // it is the broadcast thread, not "no thread open".
    var openPeerId by rememberSaveable { mutableStateOf<String?>(null) }

    // Text waiting for the user to pick a chat, shared in or forwarded. Saveable
    // like the rest, and bounded by `SharedText.MAX_CHARS` for that reason.
    var pendingShare by rememberSaveable { mutableStateOf<String?>(null) }
    // Picked: on its way into the chat's box, until the chat takes it.
    var draftToFill by rememberSaveable { mutableStateOf<String?>(null) }

    // Outside the crossfade: for the length of a fade both the outgoing and the
    // incoming screen are composed, and two enabled handlers would race over
    // which one a back press reached.
    BackHandler(enabled = destination != Destination.PEERS) { destination = Destination.PEERS }
    // Back out of the picker drops the share, which is what leaving a share
    // sheet without choosing does everywhere else.
    BackHandler(enabled = destination == Destination.PEERS && pendingShare != null) { pendingShare = null }

    LaunchedEffect(openPeerRequest) {
        val peerId = openPeerRequest ?: return@LaunchedEffect
        openPeerId = peerId
        destination = Destination.CHAT
        onOpenRequestHandled()
    }

    LaunchedEffect(shareRequest) {
        val text = shareRequest ?: return@LaunchedEffect
        pendingShare = text
        destination = Destination.PEERS
        onShareRequestHandled()
    }

    // A chat and the list share these, in one pane or two.
    val chat: @Composable (Modifier) -> Unit = { paneModifier ->
        ChatScreen(
            peerId = openPeerId,
            onBack = { destination = Destination.PEERS },
            onPair = { destination = Destination.PAIR },
            modifier = paneModifier,
            onForward = { text ->
                pendingShare = text
                destination = Destination.PEERS
            },
            draftToFill = draftToFill,
            onDraftFilled = { draftToFill = null },
        )
    }
    val peerList: @Composable (Modifier) -> Unit = { paneModifier ->
        PeerListScreen(
            onOpenConversation = { peerId ->
                openPeerId = peerId
                draftToFill = pendingShare
                pendingShare = null
                destination = Destination.CHAT
            },
            onOpenPairing = { destination = Destination.PAIR },
            modifier = paneModifier,
            onOpenSettings = { destination = Destination.SETTINGS },
            isPickingForShare = pendingShare != null,
            onCancelShare = { pendingShare = null },
        )
    }

    // The window's width, not the screen's: in split screen a tablet is a phone.
    BoxWithConstraints(modifier = modifier) {
        val twoPanes = usesTwoPanes(maxWidth.value.toInt()) &&
            (destination == Destination.PEERS || destination == Destination.CHAT)
        if (twoPanes) {
            Row(Modifier.fillMaxSize()) {
                peerList(Modifier.width(listPaneWidthDp(maxWidth.value.toInt()).dp).fillMaxHeight())
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (destination == Destination.CHAT) {
                        // Keyed on the thread so search, dialogs and scroll from
                        // the previous chat do not carry over into the next one;
                        // in one pane the cross-fade does that by recomposing.
                        key(openPeerId) { chat(Modifier.fillMaxSize()) }
                    } else {
                        NoChatOpen()
                    }
                }
            }
        } else {
            Crossfade(
                targetState = destination,
                animationSpec = motionSpec(rememberAnimationsEnabled()),
                label = "destination",
            ) { current ->
                when (current) {
                    Destination.CHAT -> chat(Modifier)
                    Destination.PAIR -> QrPairScreen(onBack = { destination = Destination.PEERS })
                    Destination.SETTINGS -> SettingsScreen(onBack = { destination = Destination.PEERS })
                    Destination.PEERS -> peerList(Modifier)
                }
            }
        }
    }
}

/**
 * The chat pane before a chat is picked. Says what to do rather than leaving
 * half the screen blank, which reads as something failing to load.
 */
@Composable
private fun NoChatOpen() {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(R.string.two_pane_no_chat),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
