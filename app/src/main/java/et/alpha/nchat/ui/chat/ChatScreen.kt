package et.alpha.nchat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.mesh.protocol.MessagePacket
import et.alpha.nchat.ui.theme.NChatTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One conversation: history, live updates, and a send box.
 *
 * @param peerId the peer to talk to, or null for the mesh-wide broadcast thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peerId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    LaunchedEffect(peerId) { viewModel.bind(peerId) }

    val state by viewModel.state.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val draftBytes by viewModel.draftBytes.collectAsState()

    val listState = rememberLazyListState()
    // Follow the tail as messages arrive, which is what a chat log should do.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.title,
                            fontFamily = if (state.isBroadcast) {
                                FontFamily.Default
                            } else {
                                FontFamily.Monospace
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = when {
                                state.isBroadcast -> "Everyone in range"
                                state.isPeerOnline -> "In range"
                                else -> "Out of range"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    // Glyphs rather than vector assets throughout this screen:
                    // material-icons is not in the Compose BOM here, and pulling
                    // the artifact in for four symbols is not worth the size.
                    IconButton(onClick = onBack) {
                        Text(text = "←", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }

            HorizontalDivider()
            SendBox(
                draft = draft,
                draftBytes = draftBytes,
                onDraftChange = viewModel::onDraftChange,
                onSend = viewModel::onSend,
            )
        }
    }
}

@Composable
private fun MessageBubble(message: MessageEntity) {
    val outgoing = message.isOutgoing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (outgoing) 16.dp else 4.dp,
                        bottomEnd = if (outgoing) 4.dp else 16.dp,
                    ),
                )
                .background(
                    if (outgoing) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // In the broadcast thread anyone can be talking, so incoming bubbles
            // are labelled. A direct thread has only one possible sender.
            if (!outgoing) {
                Text(
                    text = message.senderId.take(SHORT_ID_CHARS),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = message.body, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.size(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = TIME_FORMAT.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (outgoing) {
                    Spacer(Modifier.width(6.dp))
                    DeliveryIndicator(message.deliveryState)
                }
            }
        }
    }
}

/**
 * Delivery state, as a glyph.
 *
 * PENDING is a faded dot — not yet on the air. SENT is a tick. DELIVERED shows a
 * double tick but is unreachable in v1, since it requires ACK packets that are
 * not implemented; it is here so the state is not silently indistinguishable
 * from SENT once ACKs land.
 */
@Composable
private fun DeliveryIndicator(deliveryState: DeliveryState) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    when (deliveryState) {
        DeliveryState.PENDING -> Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.4f)),
        )

        DeliveryState.SENT -> Text(
            text = "✓",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )

        DeliveryState.DELIVERED -> Text(
            text = "✓✓",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        DeliveryState.FAILED -> Text(
            text = "!",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SendBox(
    draft: String,
    draftBytes: Int,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val overLimit = draftBytes > MessagePacket.MAX_BODY_BYTES
    val canSend = draft.isNotBlank() && !overLimit

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message") },
            isError = overLimit,
            supportingText = {
                // Counted in bytes, not characters: one emoji costs four of the
                // seventy-one, and a character counter would quietly mislead.
                Text(
                    text = "$draftBytes/${MessagePacket.MAX_BODY_BYTES} bytes",
                    color = if (overLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            maxLines = 4,
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onSend, enabled = canSend) {
            Text("Send")
        }
    }
}

private const val SHORT_ID_CHARS = 8
private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

@Preview(showBackground = true)
@Composable
private fun ChatPreview() {
    NChatTheme {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MessageBubble(
                MessageEntity(
                    id = "a", conversationId = "c", senderId = "9e8b7a65",
                    body = "meet me at the north gate", timestamp = 1_700_000_000_000,
                    deliveryState = DeliveryState.DELIVERED, isOutgoing = false,
                ),
            )
            MessageBubble(
                MessageEntity(
                    id = "b", conversationId = "c", senderId = "1ac3f00d",
                    body = "on my way", timestamp = 1_700_000_060_000,
                    deliveryState = DeliveryState.SENT, isOutgoing = true,
                ),
            )
            MessageBubble(
                MessageEntity(
                    id = "c", conversationId = "c", senderId = "1ac3f00d",
                    body = "no one in range yet", timestamp = 1_700_000_120_000,
                    deliveryState = DeliveryState.PENDING, isOutgoing = true,
                ),
            )
            HorizontalDivider()
            SendBox(draft = "hello", draftBytes = 5, onDraftChange = {}, onSend = {})
        }
    }
}
