package et.alpha.nchat.ui.peers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import et.alpha.nchat.R
import et.alpha.nchat.ui.components.EmptyState
import et.alpha.nchat.ui.components.MeshRadar
import et.alpha.nchat.ui.components.RadarPeer
import et.alpha.nchat.ui.components.SignalBars
import et.alpha.nchat.ui.theme.MonoBody
import et.alpha.nchat.ui.theme.MonoSmall
import et.alpha.nchat.ui.theme.MotionSpec
import et.alpha.nchat.ui.theme.NChatTheme
import et.alpha.nchat.ui.theme.TextMuted
import et.alpha.nchat.ui.theme.rememberAnimationsEnabled

/**
 * The peers this device knows about, with who is reachable right now.
 *
 * A peer that has ever been heard from stays listed after it walks away — the
 * history is still there and still worth opening. The signal bars, not the
 * presence of the row, are what say "in range".
 *
 * The radar sits above the list, inside the same scroll rather than pinned over
 * it: at 200% font scale a fixed 180dp of graphic plus the header would leave
 * almost nothing for the rows, and the radar is the screen's headline rather
 * than its permanent furniture. Scrolling it away also disposes it, which stops
 * its animation clock.
 */
@Composable
fun PeerListScreen(
    onOpenConversation: (peerId: String?) -> Unit,
    onOpenPairing: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    viewModel: PeerListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val animate = rememberAnimationsEnabled()
    val rows = visibleRows(state.rows)
    val radarPeers = radarPeers(state.rows)

    // Which rows have already played their entrance. Held here rather than inside
    // each row, because a LazyColumn throws a row's composition away when it
    // scrolls out of view and scrolling back would otherwise replay the animation
    // for a peer that never went anywhere.
    val entered = remember { mutableSetOf<String>() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Header(
            onlineCount = state.onlineCount,
            selfId = state.selfId,
            selfName = state.selfName,
            onOpenPairing = onOpenPairing,
            onOpenSettings = onOpenSettings,
        )
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = RADAR_KEY) {
                MeshRadar(
                    peers = radarPeers,
                    animate = animate,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                HorizontalDivider()
            }

            items(rows, key = { it.row.key }) { visible ->
                PeerPresence(
                    rowKey = visible.row.key,
                    entered = entered,
                    isPresent = visible.isPresent,
                    animate = animate,
                    onExitFinished = visible.onExitFinished,
                ) {
                    // The divider is inside the animated content so it fades with
                    // the row instead of collapsing a frame early.
                    Column {
                        PeerRowItem(
                            row = visible.row,
                            onClick = { onOpenConversation(visible.row.peerId) },
                        )
                        HorizontalDivider()
                    }
                }
            }

            // An empty mesh still has a broadcast row, so this sits after it as a
            // flowing item rather than as a centred overlay: at 200% font scale a
            // centred one would land on top of the row above it.
            if (state.rows.none { !it.isBroadcast }) {
                item {
                    EmptyState(
                        modifier = Modifier.padding(top = 40.dp),
                        title = "searching for peers nearby",
                        // Says what happens meanwhile, because an empty mesh is a
                        // normal state of a radio and not a broken app.
                        detail = "Phones running Mesh appear here as soon as they " +
                            "are in range. Anything you send before then is queued.",
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    onlineCount: Int,
    selfId: String,
    onOpenPairing: () -> Unit,
    selfName: String? = null,
    onOpenSettings: () -> Unit = {},
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Mesh",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                // Weighted so that at 200% font scale the title gives way to the
                // pairing button instead of pushing it off the end of the row.
                // Nothing else on this screen reaches pairing.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // The entry point to pairing hangs off the identity line, which is
            // already where this screen talks about who you are.
            TextButton(onClick = onOpenPairing) {
                Text(
                    text = stringResource(R.string.pair_title),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

        }
        // Monospace and coloured by the answer: this is a reading off the radio,
        // and how many phones are within reach is the headline fact of the screen.
        Text(
            text = when (onlineCount) {
                0 -> "no devices in range"
                1 -> "1 device in range"
                else -> "$onlineCount devices in range"
            },
            style = MonoSmall,
            color = if (onlineCount > 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        // Settings sits on the identity line rather than beside pairing: the
        // title row already gives way to one button at 200% font scale, and the
        // name this line shows is the main thing the settings screen changes.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selfId.isNotEmpty()) {
                // Shown so two phones under test can be told apart at a glance, and
                // so the tester can confirm each has a distinct identity.
                Text(
                    text = "you  " + peerLabel(selfName, selfId),
                    style = MonoSmall,
                    color = TextMuted,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            TextButton(onClick = onOpenSettings) {
                Text(
                    text = stringResource(R.string.settings_open),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
internal fun PeerRowItem(row: PeerRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Proximity first, literally: how well this peer can be reached is the
        // leftmost thing on the row, ahead of who they are.
        SignalBars(level = row.signalLevel)
        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.label,
                    // A peer id is machine text people compare character by
                    // character; the broadcast row's "Everyone" is a word and
                    // reads as one.
                    style = if (row.isBroadcast) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MonoBody
                    },
                    // 500 for unread, 400 otherwise. Bold is not in the type
                    // ramp — two weights, and these are them.
                    fontWeight = if (row.unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                    // Out of range is dimmed as well as unlit, so the state is
                    // legible without counting bars.
                    color = if (row.isOnline) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // fill = false so a long name gives way to the badge rather
                    // than pushing it off the row at large font scales.
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Shown on the unverified state rather than the verified one, on
                // purpose. A badge that appears when things are *fine* trains
                // people to ignore its absence; this way the default state of a
                // contact nobody has checked is visibly flagged.
                if (!row.isBroadcast && !row.isVerified) {
                    Spacer(Modifier.width(6.dp))
                    UnverifiedBadge()
                }
            }
            val subtitle = row.lastMessage ?: when {
                row.isBroadcast && row.isOnline -> "Everyone in range"
                // Not "Everyone in range" regardless, which is what this used to
                // say: with nobody around it claimed an audience that does not
                // exist, directly under a header reading "no devices in range".
                row.isBroadcast -> "Nobody in range yet"
                else -> "No messages yet"
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (row.unreadCount > 0) {
            Spacer(Modifier.width(10.dp))
            UnreadCount(row.unreadCount)
        }
    }
}

/**
 * A small "unverified" chip, in the error colour so it reads as a caution.
 *
 * Outlined rather than filled. The fill was `errorContainer`, which is BgSurface,
 * and Danger on BgSurface measures 3.9:1 — the chip was the one place in the app
 * where a caution was written on a lighter surface than the page it sits on, and
 * it was the hardest text on the screen to read. On BgBase the same colour is
 * 5.02:1, so dropping the fill and keeping a border keeps the chip shape and
 * makes the word legible.
 */
@Composable
internal fun UnverifiedBadge() {
    Text(
        text = stringResource(R.string.peer_unverified_badge),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * Unread count, as a filled accent pill.
 *
 * Not a Material `Badge`: its container defaults to the error colour, and in this
 * palette red means an unverified peer or a failed delivery. Unread messages are
 * neither. Monospace like every other number in the app.
 */
@Composable
private fun UnreadCount(count: Int) {
    Text(
        text = count.toString(),
        style = MonoSmall,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * A row that fades and scales in when its peer appears, and out when it goes.
 *
 * Nothing slides. Rows are not just list entries — the order is "reachable first",
 * so a row moving is a claim about the radio, and a slide would animate that claim
 * into something the user watches rather than reads. Hence no placement animation
 * at all: rows appear, disappear, and otherwise sit still.
 *
 * @param entered keys that have already animated in, so a scroll does not replay
 *   the entrance. Mutated here, on the row's first composition, which is the only
 *   moment that both reads and settles the answer.
 * @param onExitFinished called once the exit has actually finished playing, which
 *   is what removes the row from the list. Driven by the animation rather than by
 *   a matching timer, so the two cannot disagree about the duration.
 */
@Composable
private fun PeerPresence(
    rowKey: String,
    entered: MutableSet<String>,
    isPresent: Boolean,
    animate: Boolean,
    onExitFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    val visibleState = remember {
        MutableTransitionState(!animate || rowKey in entered).also { entered.add(rowKey) }
    }
    visibleState.targetState = isPresent

    AnimatedVisibility(
        visibleState = visibleState,
        enter = if (animate) {
            fadeIn(MotionSpec) + scaleIn(MotionSpec, initialScale = ENTER_SCALE)
        } else {
            EnterTransition.None
        },
        exit = if (animate) {
            fadeOut(MotionSpec) + scaleOut(MotionSpec, targetScale = EXIT_SCALE)
        } else {
            ExitTransition.None
        },
        content = { content() },
    )

    if (!isPresent && visibleState.isIdle && !visibleState.currentState) {
        LaunchedEffect(rowKey) { onExitFinished() }
    }
}

/**
 * The rows to draw: the roster, plus peers that have just dropped off it, held in
 * place until their exit has finished playing.
 *
 * A LazyColumn can only fade an item out on removal — by the time the animation
 * would run the item is gone from the list, so there is nothing left to scale. So
 * departures are kept here, marked absent, and dropped when the row itself says
 * its exit is done.
 *
 * A departing row keeps the slot it last held rather than being appended, so it
 * fades out where the user last saw it instead of jumping down the list first.
 */
@Composable
private fun visibleRows(roster: List<PeerRow>): List<VisibleRow> {
    val departing = remember { mutableStateMapOf<String, IndexedValue<PeerRow>>() }
    var lastRoster by remember { mutableStateOf(emptyList<PeerRow>()) }

    LaunchedEffect(roster) {
        val keys = roster.mapTo(mutableSetOf()) { it.key }
        // A peer that came back cancels its own exit.
        departing.keys.removeAll(keys)
        lastRoster.forEachIndexed { index, row ->
            if (row.key !in keys) departing[row.key] = IndexedValue(index, row)
        }
        lastRoster = roster
    }

    val visible = roster.mapTo(mutableListOf()) { row ->
        VisibleRow(row = row, isPresent = true, onExitFinished = {})
    }
    departing.values.sortedBy { it.index }.forEach { (index, row) ->
        visible.add(
            index.coerceAtMost(visible.size),
            VisibleRow(row = row, isPresent = false, onExitFinished = { departing.remove(row.key) }),
        )
    }
    return visible
}

/**
 * The rows the radar can honestly place, which is not all of them.
 *
 * A peer with no hop count is one this device has only ever read out of the
 * database — known, but not reachable by any route we have seen. The list keeps
 * it, because the conversation still exists; the radar drops it, because putting
 * a node on a ring is a claim that the peer is out there right now.
 *
 * The broadcast row has no node either: it is everyone at once, and everyone at
 * once has no distance.
 */
@Composable
private fun radarPeers(rows: List<PeerRow>): List<RadarPeer> = remember(rows) {
    rows.mapNotNull { row ->
        val peerId = row.peerId ?: return@mapNotNull null
        if (row.hops == null) return@mapNotNull null
        RadarPeer(
            id = peerId,
            hops = row.hops,
            signalLevel = row.signalLevel,
            isOnline = row.isOnline,
            lastRelayAt = row.lastRelayAt,
        )
    }
}

/** One row as the list should draw it right now. */
private data class VisibleRow(
    val row: PeerRow,
    /** False for a peer that has left the roster and is finishing its exit. */
    val isPresent: Boolean,
    val onExitFinished: () -> Unit,
)

private val PeerRow.key: String get() = peerId ?: BROADCAST_KEY

private const val BROADCAST_KEY = "__broadcast__"
private const val RADAR_KEY = "__radar__"
private const val ENTER_SCALE = 0.9f
private const val EXIT_SCALE = 0.95f

@Preview(showBackground = true, backgroundColor = 0xFF0E1A26)
@Composable
private fun PeerListPreview() {
    NChatTheme {
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            Header(onlineCount = 2, selfId = "1ac3f00d9e8b7a65", onOpenPairing = {})
            HorizontalDivider()
            MeshRadar(
                peers = listOf(
                    RadarPeer("9e8b7a65", hops = 1, signalLevel = 4, isOnline = true),
                    RadarPeer("44ff0011", hops = 1, signalLevel = 4, isOnline = true),
                    RadarPeer("c0ffee12", hops = 2, signalLevel = 0, isOnline = false),
                ),
                modifier = Modifier.padding(vertical = 12.dp),
            )
            HorizontalDivider()
            PeerRowItem(
                row = PeerRow(null, "Everyone", true, "meet me at the north gate", 2),
                onClick = {},
            )
            HorizontalDivider()
            PeerRowItem(
                row = PeerRow("9e8b7a65", "9e8b7a65", true, "on my way", 0, isVerified = true),
                onClick = {},
            )
            HorizontalDivider()
            PeerRowItem(
                row = PeerRow("44ff0011", "44ff0011", true, "who else is here", 7),
                onClick = {},
            )
            HorizontalDivider()
            PeerRowItem(row = PeerRow("c0ffee12", "c0ffee12", false, null, 0), onClick = {})
            HorizontalDivider()
        }
    }
}

/** Nobody in range yet, which is how the app starts every time. */
@Preview(showBackground = true, backgroundColor = 0xFF0E1A26)
@Composable
private fun PeerListEmptyPreview() {
    NChatTheme {
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            Header(onlineCount = 0, selfId = "1ac3f00d9e8b7a65", onOpenPairing = {})
            HorizontalDivider()
            MeshRadar(peers = emptyList(), modifier = Modifier.padding(vertical = 12.dp))
            HorizontalDivider()
            PeerRowItem(row = PeerRow(null, "Everyone", false, null, 0), onClick = {})
            HorizontalDivider()
            EmptyState(
                modifier = Modifier.padding(top = 40.dp),
                title = "searching for peers nearby",
                detail = "Phones running Mesh appear here as soon as they are in " +
                    "range. Anything you send before then is queued.",
            )
        }
    }
}
