package et.alpha.nchat.ui.chat

import android.content.ClipData
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import et.alpha.nchat.R
import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.media.decodeBoundedImage
import et.alpha.nchat.mesh.Transfer
import et.alpha.nchat.mesh.TransferState
import et.alpha.nchat.mesh.canDeleteLocally
import et.alpha.nchat.mesh.protocol.MessagePacket
import et.alpha.nchat.mesh.protocol.Quote
import et.alpha.nchat.ui.components.EmptyState
import et.alpha.nchat.ui.components.MessageBubble
import et.alpha.nchat.ui.theme.Danger
import et.alpha.nchat.ui.theme.MonoBody
import et.alpha.nchat.ui.theme.MonoSmall
import et.alpha.nchat.ui.theme.MotionPlacementSpec
import et.alpha.nchat.ui.theme.MotionSpec
import et.alpha.nchat.ui.theme.NChatTheme
import et.alpha.nchat.ui.theme.rememberAnimationsEnabled
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One conversation: history, live updates, and a send box.
 *
 * The bubbles and the delivery state come from `ui/components`, so this file
 * owns the thread — scrolling, banners, the send box, decoding attachments — and
 * owns none of the message chrome.
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
    // Keyed on peerId so switching threads leaves the old one before entering
    // the new; resumed-only so a backgrounded app still gets notified.
    LifecycleResumeEffect(peerId) {
        viewModel.onVisible(peerId, visible = true)
        onPauseOrDispose { viewModel.onVisible(peerId, visible = false) }
    }

    val state by viewModel.state.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val draftBytes by viewModel.draftBytes.collectAsState()
    val replyTo by viewModel.replyTo.collectAsState()
    val transfers by viewModel.transfers.collectAsState()
    val isPreparingImage by viewModel.isPreparingImage.collectAsState()
    val error by viewModel.errors.collectAsState()

    val animate = rememberAnimationsEnabled()
    val sends = rememberSendAnimations()

    val listState = rememberLazyListState()
    // Follow the tail as messages arrive, which is what a chat log should do.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        val tail = state.messages.lastIndex
        if (animate) listState.animateScrollToItem(tail) else listState.scrollToItem(tail)
    }

    // PickVisualMedia rather than GetContent: it runs in the system photo picker,
    // which needs no storage permission at all and shows only what the user
    // explicitly chooses. READ_MEDIA_IMAGES would mean asking for the whole
    // gallery to send one picture.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::onImagePicked) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val copiedText = stringResource(R.string.chat_copied)

    // Saveable so a rotation does not silently cancel a delete the user was
    // about to confirm.
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    pendingDelete?.let { messageId ->
        DeleteMessageDialog(
            onConfirm = {
                viewModel.onDelete(messageId)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
    var isRenaming by rememberSaveable { mutableStateOf(false) }
    if (isRenaming) {
        RenameDialog(
            current = state.nickname.orEmpty(),
            onSave = {
                viewModel.onRename(it)
                isRenaming = false
            },
            onDismiss = { isRenaming = false },
        )
    }
    var isDeletingChat by rememberSaveable { mutableStateOf(false) }
    if (isDeletingChat) {
        DeleteChatDialog(
            onConfirm = {
                viewModel.onDeleteChat()
                isDeletingChat = false
            },
            onDismiss = { isDeletingChat = false },
        )
    }
    var isChoosingTimer by rememberSaveable { mutableStateOf(false) }
    if (isChoosingTimer) {
        DisappearingDialog(
            current = state.disappearAfterMillis,
            onChoose = {
                viewModel.onSetDisappearing(it)
                isChoosingTimer = false
            },
            onDismiss = { isChoosingTimer = false },
        )
    }
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onErrorShown()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ChatTopBar(
                title = state.title,
                subtitle = when {
                    state.isBroadcast -> "Everyone in range"
                    state.isPeerOnline -> "In range"
                    else -> "Out of range"
                } + state.disappearAfterMillis
                    ?.let { " · disappears after ${DisappearingTimer.label(it)}" }
                    .orEmpty(),
                // A bare peer id is machine text people compare character by
                // character; a name or the broadcast thread's "Everyone" is a word
                // and reads as one.
                isMonoTitle = !state.isBroadcast && !state.isNamed,
                isOnline = state.isBroadcast || state.isPeerOnline,
                onBack = onBack,
                onRename = if (state.isBroadcast) null else ({ isRenaming = true }),
                onDisappearing = { isChoosingTimer = true },
                onDeleteChat = { isDeletingChat = true },
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

            Box(modifier = Modifier.weight(1f)) {
                // The title is empty only in the initial state, before the first
                // query has come back. Waiting for it keeps "no messages yet"
                // from flashing across a thread that in fact has history.
                if (state.messages.isEmpty() && state.title.isNotEmpty()) {
                    EmptyState(
                        modifier = Modifier.align(Alignment.Center),
                        title = "No messages yet",
                        // Says what the mesh will do with a message sent into an
                        // empty room, because "nobody is here" is not the same
                        // thing as "this will not work".
                        detail = "Anything you send now is queued and goes out as " +
                            "soon as someone is in range.",
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.messages, key = { it.id }) { message ->
                            ChatMessage(
                                message = message,
                                isBroadcast = state.isBroadcast,
                                transfer = transfers[message.id],
                                attachmentPath = message.attachmentFile
                                    ?.let(viewModel::attachmentPath),
                                onResend = { viewModel.onResend(message.id) },
                                onCopy = {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            // The answer only: the quoted words are
                                            // someone else's, already in the thread.
                                            messageClip(
                                                Quote.replyText(message.body),
                                                sensitive = !state.isBroadcast,
                                            ),
                                        )
                                        // Android 13 and later confirm a copy themselves.
                                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                            snackbarHostState.showSnackbar(copiedText)
                                        }
                                    }
                                },
                                onDelete = { pendingDelete = message.id },
                                onReply = { viewModel.onReply(message) },
                                // Settled on the row's first composition and kept:
                                // a bubble scrolled out of view and back is the
                                // same message, not a second send.
                                animateSend = remember(message.id) {
                                    sends.isFreshSend(message) && animate
                                },
                                modifier = Modifier.animateItem(
                                    // No fade in: the bubble runs its own
                                    // entrance, and two overlapping entrances
                                    // read as a stutter. The list only animates
                                    // the rows the new message displaces.
                                    fadeInSpec = null,
                                    placementSpec = if (animate) MotionPlacementSpec else null,
                                    fadeOutSpec = if (animate) MotionSpec else null,
                                ),
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            replyTo?.let { quote ->
                ReplyStrip(quote = quote, onCancel = viewModel::onCancelReply)
            }
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
 * The thread's header: who you are talking to, and whether they can be reached.
 *
 * A plain Row rather than a Material `TopAppBar`. The bar carries two lines —
 * the peer and their reachability — and a `TopAppBar` is a fixed 64dp container
 * that clips whatever does not fit. At 200% font scale those two lines measure
 * around 100dp and the second one disappeared, taking the only statement this
 * screen makes about the radio with it. This grows instead.
 */
@Composable
private fun ChatTopBar(
    title: String,
    subtitle: String,
    isMonoTitle: Boolean,
    isOnline: Boolean,
    onBack: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDisappearing: (() -> Unit)? = null,
    onDeleteChat: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Glyphs rather than vector assets throughout this screen:
            // material-icons is not in the Compose BOM here, and pulling
            // the artifact in for four symbols is not worth the size.
            IconButton(onClick = onBack) {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    // The glyph is a picture of an arrow, not a word. Cleared and
                    // relabelled so a screen reader announces the button rather
                    // than reading out a punctuation character.
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = "Back to peers"
                    },
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = if (isMonoTitle) MonoBody else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MonoSmall,
                    // Reachability is the one fact this screen can state about
                    // the link, so it is coloured rather than left as chrome.
                    color = if (isOnline) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (onRename != null || onDisappearing != null || onDeleteChat != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Text(
                            text = "⋮",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = "More options"
                            },
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        onRename?.let { rename ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_rename_action)) },
                                onClick = {
                                    menuOpen = false
                                    rename()
                                },
                            )
                        }
                        onDisappearing?.let { choose ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_disappearing_action)) },
                                onClick = {
                                    menuOpen = false
                                    choose()
                                },
                            )
                        }
                        onDeleteChat?.let { delete ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_delete_chat_action)) },
                                onClick = {
                                    menuOpen = false
                                    delete()
                                },
                            )
                        }
                    }
                }
            }
        }
        // The design system forbids elevation shadows, so the bar is separated
        // from the thread by the hairline instead.
        HorizontalDivider()
    }
}

/**
 * A caution strip across the top of the thread.
 *
 * Deliberately not dismissible: the condition it reports does not go away by
 * being acknowledged, and a warning the user can silence is one they will
 * silence.
 *
 * Drawn as a thin bar with a `Danger` edge rather than a filled red block. The
 * unverified state is the *default* for every contact, so this is on screen most
 * of the time; a full-bleed alarm colour that is always present stops being read
 * within a day, while an edge stays legible without shouting.
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
            // Intrinsic height so the Danger edge can match the text it marks:
            // a Row is as tall as its tallest child, and fillMaxHeight on the
            // edge would otherwise have nothing finite to fill.
            .height(IntrinsicSize.Min)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(Danger),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Danger,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(text = actionLabel) }
        }
    }
}

/**
 * One row of the thread: a design-system bubble, plus whatever this message
 * carries beyond text.
 *
 * The delivery state passed down is the outgoing one only. An incoming message
 * has no delivery state worth showing — it is here, which is the whole story.
 */
@Composable
private fun ChatMessage(
    message: MessageEntity,
    isBroadcast: Boolean,
    modifier: Modifier = Modifier,
    transfer: Transfer? = null,
    attachmentPath: String? = null,
    animateSend: Boolean = false,
    onResend: () -> Unit = {},
    onCopy: () -> Unit = {},
    onDelete: () -> Unit = {},
    onReply: () -> Unit = {},
) {
    val outgoing = message.isOutgoing
    val inFlight = transfer != null && transfer.state != TransferState.COMPLETE
    // Only ours, only failed. A queued message is still on its way and a second
    // copy would go out beside it; see `resendTarget` for the same rule below.
    val resendable = outgoing && message.deliveryState == DeliveryState.FAILED
    val copyable = message.body.isNotEmpty()
    val deletable = canDeleteLocally(message)
    // Anything that has arrived or gone out. Our own queued or failed message
    // has not reached anyone yet, so there is nothing for a reply to point at.
    val repliable = !outgoing || message.deliveryState == DeliveryState.SENT ||
        message.deliveryState == DeliveryState.DELIVERED
    val parsed = remember(message.body) { Quote.parse(message.body) }
    var menuOpen by remember { mutableStateOf(false) }
    val extras: @Composable (ColumnScope.() -> Unit)? =
        if (attachmentPath != null || inFlight) {
            {
                if (attachmentPath != null) {
                    AttachmentImage(path = attachmentPath, transfer = transfer)
                }
                if (transfer != null && inFlight) TransferIndicator(transfer)
            }
        } else {
            null
        }

    MessageBubble(
        text = parsed?.reply ?: message.body,
        quote = parsed?.quote,
        isOutgoing = outgoing,
        modifier = modifier,
        // In the broadcast thread anyone can be talking, so incoming bubbles are
        // labelled. A direct thread has only one possible sender.
        senderId = if (!outgoing && isBroadcast) message.senderId.take(SHORT_ID_CHARS) else null,
        timestamp = TIME_FORMAT.format(Date(message.shownAt)),
        deliveryState = message.deliveryState.takeIf { outgoing },
        // Only the recipient sees a packet's remaining ttl, so the count comes
        // back in its ACK. `DeliveryStateLabel` drops it on states with no route.
        hopCount = message.hops.takeIf { outgoing },
        animateSend = animateSend,
        onAction = onResend.takeIf { resendable },
        actionLabel = stringResource(R.string.chat_resend_action),
        // A queued image has neither text to copy nor a delete it can honour, so
        // its bubble stays inert rather than opening an empty menu.
        onLongAction = { menuOpen = true }
            .takeIf { resendable || copyable || deletable || repliable },
        longActionLabel = stringResource(R.string.chat_actions_label),
        menu = {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (resendable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_resend_action)) },
                        onClick = {
                            menuOpen = false
                            onResend()
                        },
                    )
                }
                if (repliable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_reply_action)) },
                        onClick = {
                            menuOpen = false
                            onReply()
                        },
                    )
                }
                if (copyable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_copy_action)) },
                        onClick = {
                            menuOpen = false
                            onCopy()
                        },
                    )
                }
                if (deletable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_delete_action)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
        failedHint = stringResource(R.string.chat_resend_hint).takeIf { resendable },
        content = extras,
    )
}

/**
 * Asks before deleting, and says what "delete" can and cannot reach.
 *
 * The body is the point of the dialog: on a mesh with no server there is no
 * unsend, and someone who expects one would be misled by a plain "Delete?".
 */
@Composable
private fun DeleteMessageDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_delete_title)) },
        text = { Text(stringResource(R.string.chat_delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.chat_delete_confirm), color = Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_delete_cancel)) }
        },
    )
}

/**
 * Confirms emptying a thread. Says what stays, so nobody believes it unsent
 * anything: the other phone keeps its copy and queued messages still go.
 */
@Composable
private fun DeleteChatDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_delete_chat_title)) },
        text = { Text(stringResource(R.string.chat_delete_chat_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.chat_delete_confirm), color = Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_delete_cancel)) }
        },
    )
}

/**
 * Sets the user's own name for a peer.
 *
 * The body says who sees it: a name typed here is easily mistaken for one the
 * peer will receive, and someone who believed that might soften what they type.
 */
@Composable
private fun RenameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.chat_rename_body))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    label = { Text(stringResource(R.string.chat_rename_label)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.chat_rename_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_delete_cancel)) }
        },
    )
}

/**
 * Picks this thread's disappearing timer.
 *
 * The body is the part that matters: the timer is this phone's alone and only
 * reaches messages from now on. Someone who believed it also cleared the other
 * phone, or the history already here, would be trusting a deletion that never
 * happens.
 */
@Composable
private fun DisappearingDialog(current: Long?, onChoose: (Long?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_disappearing_title)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                Text(stringResource(R.string.chat_disappearing_body))
                Spacer(Modifier.height(8.dp))
                DisappearingTimer.OPTIONS.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == current,
                                onClick = { onChoose(option) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The row is the touch target and carries the semantics;
                        // the button is only the picture of the choice.
                        RadioButton(selected = option == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(DisappearingTimer.label(option))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_delete_cancel)) }
        },
    )
}

/**
 * A copied message, flagged sensitive outside the broadcast thread.
 *
 * The flag keeps Android 13+ from previewing a direct message in the clipboard
 * overlay. Spelled as its string value because the constant only exists from
 * API 33; older versions ignore the extra.
 */
private fun messageClip(text: String, sensitive: Boolean): ClipEntry {
    val clip = ClipData.newPlainText("message", text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    return ClipEntry(clip)
}

/**
 * Which bubbles should play the send animation.
 *
 * The question is "did the user just send this", and the answer is the clock: a
 * message stamped after this screen opened cannot be history. Timestamps rather
 * than a diff of successive message lists, because the ViewModel is shared
 * across conversations and holds the previous thread's rows until the new query
 * returns — a diff would see a whole conversation appear at once and flash every
 * bubble in it.
 *
 * Only outgoing messages qualify. An incoming one was not sent from here, and
 * its timestamp came off another phone's clock, which this device has no reason
 * to trust to the millisecond.
 */
@Composable
private fun rememberSendAnimations(): SendAnimations =
    remember { SendAnimations(openedAt = System.currentTimeMillis()) }

/**
 * Deliberately not Compose state: it is read during composition and written in
 * the same pass, and making it observable would invalidate the thread every time
 * a bubble was marked.
 */
private class SendAnimations(private val openedAt: Long) {
    private val played = mutableSetOf<String>()

    /** True exactly once per message, for one the user sent while this screen was up. */
    fun isFreshSend(message: MessageEntity): Boolean =
        message.isOutgoing && message.timestamp >= openedAt && played.add(message.id)
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
 * feature is one 14 KB JPEG from local storage, and BitmapFactory does that
 * without adding a dependency. The file may come from a stranger, so it is
 * decoded with bounds: see [decodeBoundedImage].
 */
@Composable
private fun AttachmentImage(path: String, transfer: Transfer?) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            decodeBoundedImage(path)?.asImageBitmap()
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
                    // BgSurface, not BgBase: inside an outgoing bubble's accent
                    // wash a base-coloured block reads as a hole punched through
                    // the bubble rather than as a picture that has not arrived.
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (transfer?.state == TransferState.FAILED) "!" else "…",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (transfer?.state == TransferState.FAILED) {
                        Danger
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * Progress for an image moving across the mesh in ~280 pieces.
 *
 * Shown as a determinate bar with a fragment count rather than a spinner,
 * because on a slow link this genuinely takes tens of seconds and an
 * indeterminate spinner for that long is indistinguishable from a hang.
 *
 * The count is monospace for the same reason hop counts are: it is a number that
 * changes every few hundred milliseconds, and a proportional font makes the
 * whole line jitter as the digits change width.
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
                    .padding(top = 6.dp, bottom = 4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                drawStopIndicator = {},
            )
        }
        Text(
            text = when {
                failed && transfer.isOutgoing -> "not sent — nobody in range"
                failed -> "incomplete — pieces did not arrive"
                transfer.isOutgoing -> "sending ${transfer.completed}/${transfer.total}"
                else -> "receiving ${transfer.completed}/${transfer.total}"
            },
            style = MonoSmall,
            color = if (failed) Danger else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the next send will quote, with a way out.
 *
 * Above the box rather than inside it, so the quote is plainly not part of the
 * text being typed and the byte counter's jump when a reply starts has a cause
 * the user can see.
 */
@Composable
private fun ReplyStrip(quote: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_replying_to),
                style = MonoSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = quote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val cancelLabel = stringResource(R.string.chat_reply_cancel)
        IconButton(onClick = onCancel) {
            Text(
                text = "×",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { contentDescription = cancelLabel },
            )
        }
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
    val overLimit = draftBytes > MessagePacket.MAX_TEXT_BYTES
    val canSend = draft.isNotBlank() && !overLimit

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 8.dp),
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
                color = if (isPreparingImage) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                // Says what the button does, and what it is busy doing — a
                // screen reader gets nothing usable out of "+" or an ellipsis.
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription =
                        if (isPreparingImage) "Preparing image" else "Attach an image"
                },
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = {
                Text(
                    text = "Message",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            isError = overLimit,
            supportingText = {
                // Counted in bytes, not characters: one emoji costs four, and a
                // character counter would quietly mislead.
                Text(
                    text = "$draftBytes/${MessagePacket.MAX_TEXT_BYTES} bytes",
                    style = MonoSmall,
                    color = if (overLimit) Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                errorBorderColor = Danger,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            maxLines = 6,
        )
        Spacer(Modifier.width(4.dp))
        TextButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.padding(bottom = 20.dp),
        ) {
            Text(text = "Send", style = MaterialTheme.typography.labelLarge)
        }
    }
}

private const val SHORT_ID_CHARS = 8
private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

/** The chrome around the thread: the bar, and what a thread with nothing in it says. */
@Preview(showBackground = true, backgroundColor = 0xFF0E1A26)
@Composable
private fun ChatChromePreview() {
    NChatTheme {
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            ChatTopBar(
                title = "9e8b7a65",
                subtitle = "In range",
                isMonoTitle = true,
                isOnline = true,
                onBack = {},
            )
            WarningBanner(
                text = "You have not verified this contact.",
                actionLabel = "Pair",
                onAction = {},
            )
            EmptyState(
                modifier = Modifier.padding(top = 48.dp),
                title = "No messages yet",
                detail = "Anything you send now is queued and goes out as soon as " +
                    "someone is in range.",
            )
            Spacer(Modifier.height(48.dp))
            ChatTopBar(
                title = "Everyone",
                subtitle = "Everyone in range",
                isMonoTitle = false,
                isOnline = true,
                onBack = {},
            )
            WarningBanner(text = "Messages to Everyone are not encrypted.")
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E1A26)
@Composable
private fun ChatPreview() {
    NChatTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 12.dp),
        ) {
            WarningBanner(
                text = "You have not verified this contact.",
                actionLabel = "Pair",
                onAction = {},
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChatMessage(
                    message = MessageEntity(
                        id = "a", conversationId = "c", senderId = "9e8b7a65",
                        body = "meet me at the north gate", timestamp = 1_700_000_000_000,
                        deliveryState = DeliveryState.DELIVERED, isOutgoing = false,
                    ),
                    isBroadcast = true,
                )
                ChatMessage(
                    message = MessageEntity(
                        id = "b", conversationId = "c", senderId = "1ac3f00d",
                        body = "on my way", timestamp = 1_700_000_060_000,
                        deliveryState = DeliveryState.SENT, isOutgoing = true,
                    ),
                    isBroadcast = true,
                )
                ChatMessage(
                    message = MessageEntity(
                        id = "r", conversationId = "c", senderId = "9e8b7a65",
                        body = "> on my way\nsee you there", timestamp = 1_700_000_090_000,
                        deliveryState = DeliveryState.DELIVERED, isOutgoing = false,
                    ),
                    isBroadcast = true,
                )
                ChatMessage(
                    message = MessageEntity(
                        id = "c", conversationId = "c", senderId = "1ac3f00d",
                        body = "no one in range yet", timestamp = 1_700_000_120_000,
                        deliveryState = DeliveryState.PENDING, isOutgoing = true,
                    ),
                    isBroadcast = true,
                )
                ChatMessage(
                    message = MessageEntity(
                        id = "d", conversationId = "c", senderId = "1ac3f00d",
                        body = "", timestamp = 1_700_000_180_000,
                        deliveryState = DeliveryState.PENDING, isOutgoing = true,
                        attachmentFile = "d.jpg",
                    ),
                    isBroadcast = true,
                    transfer = Transfer(
                        messageId = "d", completed = 96, total = 282, isOutgoing = true,
                    ),
                    attachmentPath = "/nonexistent-in-preview.jpg",
                )
            }
            Spacer(Modifier.height(12.dp))
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
