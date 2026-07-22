package et.alpha.nchat.ui.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import et.alpha.nchat.R
import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.mesh.Transfer
import et.alpha.nchat.mesh.TransferState
import et.alpha.nchat.mesh.protocol.MessagePacket
import et.alpha.nchat.ui.theme.NChatTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    LaunchedEffect(peerId) { viewModel.bind(peerId) }

    val state by viewModel.state.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val draftBytes by viewModel.draftBytes.collectAsState()
    val transfers by viewModel.transfers.collectAsState()
    val isPreparingImage by viewModel.isPreparingImage.collectAsState()
    val error by viewModel.errors.collectAsState()

    val listState = rememberLazyListState()
    // Follow the tail as messages arrive, which is what a chat log should do.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    // PickVisualMedia rather than GetContent: it runs in the system photo picker,
    // which needs no storage permission at all and shows only what the user
    // explicitly chooses. READ_MEDIA_IMAGES would mean asking for the whole
    // gallery to send one picture.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::onImagePicked) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onErrorShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            // Shown for the *absence* of verification rather than its presence.
            // A reassuring badge on a verified contact would train people to
            // read "no badge" as "fine", which is exactly backwards when
            // unverified is the default state of every contact.
            if (state.isBroadcast) {
                WarningBanner(text = stringResource(R.string.chat_broadcast_warning))
            } else if (!state.isVerified) {
                WarningBanner(
                    text = stringResource(R.string.chat_unverified_warning),
                    actionLabel = stringResource(R.string.chat_pair_action),
                    onAction = onPair,
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        transfer = transfers[message.id],
                        attachmentPath = message.attachmentFile?.let(viewModel::attachmentPath),
                    )
                }
            }

            HorizontalDivider()
            SendBox(
                draft = draft,
                draftBytes = draftBytes,
                isPreparingImage = isPreparingImage,
                onDraftChange = viewModel::onDraftChange,
                onSend = viewModel::onSend,
                onPickImage = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
        }
    }
}

/**
 * A caution strip across the top of the thread.
 *
 * Deliberately not dismissible: the condition it reports does not go away by
 * being acknowledged, and a warning the user can silence is one they will
 * silence.
 */
@Composable
internal fun WarningBanner(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(text = actionLabel) }
        }
    }
}

@Composable
private fun MessageBubble(
    message: MessageEntity,
    transfer: Transfer? = null,
    attachmentPath: String? = null,
) {
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
            if (attachmentPath != null) {
                AttachmentImage(path = attachmentPath, transfer = transfer)
            }
            // An image with no caption has an empty body; a bare empty Text would
            // still occupy a line and leave a gap under the picture.
            if (message.body.isNotEmpty()) {
                Text(text = message.body, style = MaterialTheme.typography.bodyLarge)
            }
            if (transfer != null && transfer.state != TransferState.COMPLETE) {
                TransferIndicator(transfer)
            }
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
 * An image attachment, decoded off the main thread.
 *
 * `produceState` rather than a plain `remember`: these are small JPEGs, but
 * decoding one still costs milliseconds, and doing it inline in composition
 * would stutter the list on every scroll past a picture. Keyed by path, so a
 * bubble whose file appears later — an image still arriving — decodes as soon as
 * the file lands rather than staying blank.
 *
 * No image-loading library. Coil would be the obvious choice, but the whole
 * feature is one 14 KB JPEG from local storage, and BitmapFactory does that in
 * three lines without adding a dependency.
 */
@Composable
private fun AttachmentImage(path: String, transfer: Transfer?) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                // Null rather than a generic "image": a description that says
                // nothing is noise to a screen reader. The sender is announced
                // by the bubble's own label.
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } else {
            // A placeholder of roughly image proportions, so the bubble does not
            // resize under the user when the picture finishes arriving.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (transfer?.state == TransferState.FAILED) "!" else "…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.size(4.dp))
}

/**
 * Progress for an image moving across the mesh in ~280 pieces.
 *
 * Shown as a determinate bar with a fragment count rather than a spinner,
 * because on a slow link this genuinely takes tens of seconds and an
 * indeterminate spinner for that long is indistinguishable from a hang.
 */
@Composable
private fun TransferIndicator(transfer: Transfer) {
    val failed = transfer.state == TransferState.FAILED
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!failed) {
            LinearProgressIndicator(
                progress = { transfer.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
        }
        Text(
            text = when {
                failed && transfer.isOutgoing -> "Not sent — nobody in range"
                failed -> "Incomplete — pieces did not arrive"
                transfer.isOutgoing -> "Sending ${transfer.completed}/${transfer.total}"
                else -> "Receiving ${transfer.completed}/${transfer.total}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
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
    isPreparingImage: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickImage: () -> Unit,
) {
    val overLimit = draftBytes > MessagePacket.MAX_BODY_BYTES
    val canSend = draft.isNotBlank() && !overLimit

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Disabled while a pick is being downscaled, so a second pick cannot
        // start before the first has a row to attach itself to.
        IconButton(
            onClick = onPickImage,
            enabled = !isPreparingImage,
            modifier = Modifier.padding(bottom = 24.dp),
        ) {
            Text(
                text = if (isPreparingImage) "…" else "+",
                style = MaterialTheme.typography.titleLarge,
            )
        }
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
            MessageBubble(
                MessageEntity(
                    id = "d", conversationId = "c", senderId = "1ac3f00d",
                    body = "", timestamp = 1_700_000_180_000,
                    deliveryState = DeliveryState.PENDING, isOutgoing = true,
                    attachmentFile = "d.jpg",
                ),
                transfer = Transfer(
                    messageId = "d", completed = 96, total = 282, isOutgoing = true,
                ),
                attachmentPath = "/nonexistent-in-preview.jpg",
            )
            HorizontalDivider()
            SendBox(
                draft = "hello",
                draftBytes = 5,
                isPreparingImage = false,
                onDraftChange = {},
                onSend = {},
                onPickImage = {},
            )
        }
    }
}
