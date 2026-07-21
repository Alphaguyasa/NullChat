package et.alpha.nchat.ui.peers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import et.alpha.nchat.ui.theme.NChatTheme

/**
 * The peers this device knows about, with who is reachable right now.
 *
 * A peer that has ever been heard from stays listed after it walks away — the
 * history is still there and still worth opening. The dot, not the presence of
 * the row, is what says "in range".
 */
@Composable
fun PeerListScreen(
    onOpenConversation: (peerId: String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PeerListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        Header(onlineCount = state.onlineCount, selfId = state.selfId)
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.rows, key = { it.peerId ?: BROADCAST_KEY }) { row ->
                PeerRowItem(row = row, onClick = { onOpenConversation(row.peerId) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Header(onlineCount: Int, selfId: String) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Mesh", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(4.dp))
        Text(
            text = when (onlineCount) {
                0 -> "No devices in range"
                1 -> "1 device in range"
                else -> "$onlineCount devices in range"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selfId.isNotEmpty()) {
            // Shown so two phones under test can be told apart at a glance, and
            // so the tester can confirm each has a distinct identity.
            Text(
                text = "You: ${selfId.take(SHORT_ID_CHARS)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PeerRowItem(row: PeerRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OnlineDot(isOnline = row.isOnline)
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = if (row.isBroadcast) FontFamily.Default else FontFamily.Monospace,
                fontWeight = if (row.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
            )
            val subtitle = row.lastMessage
                ?: if (row.isBroadcast) "Everyone in range" else "No messages yet"
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (row.unreadCount > 0) {
            Spacer(Modifier.width(8.dp))
            Badge { Text(row.unreadCount.toString()) }
        }
    }
}

@Composable
private fun OnlineDot(isOnline: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                color = if (isOnline) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = CircleShape,
            ),
    )
}

private const val BROADCAST_KEY = "__broadcast__"
private const val SHORT_ID_CHARS = 8

@Preview(showBackground = true)
@Composable
private fun PeerListPreview() {
    NChatTheme {
        Column {
            Header(onlineCount = 2, selfId = "1ac3f00d9e8b7a65")
            HorizontalDivider()
            PeerRowItem(
                row = PeerRow(null, "Everyone", true, "meet me at the north gate", 2),
                onClick = {},
            )
            HorizontalDivider()
            PeerRowItem(
                row = PeerRow("9e8b7a65", "9e8b7a65", true, "on my way", 0),
                onClick = {},
            )
            HorizontalDivider()
            PeerRowItem(row = PeerRow("44ff0011", "44ff0011", false, null, 0), onClick = {})
        }
    }
}
