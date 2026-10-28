package et.alpha.nchat.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import et.alpha.nchat.data.db.BROADCAST_CONVERSATION_ID
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.directConversationId
import et.alpha.nchat.data.db.preferredName
import et.alpha.nchat.data.db.toHexId
import et.alpha.nchat.data.media.AttachmentStore
import et.alpha.nchat.data.media.ImageScaler
import et.alpha.nchat.data.repo.MessageRepository
import et.alpha.nchat.data.repo.PeerRepository
import et.alpha.nchat.mesh.MeshCoordinator
import et.alpha.nchat.mesh.SendResult
import et.alpha.nchat.mesh.Transfer
import et.alpha.nchat.mesh.TransferTracker
import et.alpha.nchat.mesh.protocol.MessagePacket
import et.alpha.nchat.mesh.protocol.Quote
import et.alpha.nchat.mesh.transport.NearbyTransport
import et.alpha.nchat.service.ForegroundConversation
import et.alpha.nchat.ui.peers.peerLabel
import et.alpha.nchat.ui.share.SharedText
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatState(
    val messages: List<MessageEntity> = emptyList(),
    val title: String = "",
    val isPeerOnline: Boolean = false,
    val isBroadcast: Boolean = true,
    /**
     * Whether the user has confirmed this peer's key by QR.
     *
     * Drives the warning banner. False for a peer whose key merely arrived over
     * the radio, because nothing about that ties the key to a person.
     */
    val isVerified: Boolean = false,
    /** The user's own name for this peer, for the rename dialog to start from. */
    val nickname: String? = null,
    /** True when [title] is a name rather than a bare peer id. */
    val isNamed: Boolean = false,
    /** This thread's disappearing timer on this phone, or null when off. */
    val disappearAfterMillis: Long? = null,
    /** True when the user blocked this peer; its new messages are dropped. */
    val isBlocked: Boolean = false,
)

/**
 * State for one conversation.
 *
 * The target is supplied by [bind] rather than by a `SavedStateHandle`, because
 * this app has two screens and no navigation graph to carry arguments — see the
 * host in `MeshChatScreen`. Everything downstream keys off that flow, so
 * rebinding simply re-points the queries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messages: MessageRepository,
    private val peers: PeerRepository,
    private val coordinator: MeshCoordinator,
    private val transport: NearbyTransport,
    private val imageScaler: ImageScaler,
    private val attachments: AttachmentStore,
    private val foregroundConversation: ForegroundConversation,
    @ApplicationContext private val context: Context,
    transferTracker: TransferTracker,
) : ViewModel() {

    /** Null means the mesh-wide broadcast thread. */
    private val peerId = MutableStateFlow<String?>(null)

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /**
     * The snippet the next send will quote, or null for a plain message.
     *
     * Held as the snippet text rather than the message id: the quote is words,
     * not a link (see `Quote`), so a message deleted while the reply is being
     * typed still leaves something to quote.
     */
    private val _replyTo = MutableStateFlow<String?>(null)
    val replyTo: StateFlow<String?> = _replyTo.asStateFlow()

    /** Progress bars for images in flight, keyed by message id. */
    val transfers: StateFlow<Map<String, Transfer>> = transferTracker.transfers

    /**
     * True while a picked image is being downscaled.
     *
     * Separate from [transfers] because it happens before the message row exists:
     * decoding and re-compressing a camera photo takes long enough on a
     * mid-range phone to look like the button did nothing.
     */
    private val _isPreparingImage = MutableStateFlow(false)
    val isPreparingImage: StateFlow<Boolean> = _isPreparingImage.asStateFlow()

    /** One-shot snackbar text: failures (an unreadable pick, an empty mesh), and an export's outcome. */
    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()

    val state: StateFlow<ChatState> = peerId.flatMapLatest { peer ->
        combine(
            messages.observeMessages(conversationIdFor(peer)),
            transport.connectedPeers.map { endpoints ->
                peer == null || endpoints.any { transport.peerIdFor(it)?.toHexId() == peer }
            },
            // A peer row may not exist yet for someone we have never heard from,
            // which reads as unverified — correctly.
            if (peer == null) flowOf(null) else peers.observePeer(peer),
            messages.observeConversation(conversationIdFor(peer)),
        ) { rows, online, peerRow, conversation ->
            val name = peerRow?.preferredName
            ChatState(
                messages = rows,
                // A named peer reads like the peer list; an unnamed one keeps its
                // full id, the one thing to compare against a QR code.
                title = when {
                    peer == null -> EVERYONE
                    name != null -> peerLabel(name, peer)
                    else -> peer
                },
                nickname = peerRow?.nickname,
                isNamed = name != null,
                isPeerOnline = online,
                isBroadcast = peer == null,
                isVerified = peerRow?.isVerified == true,
                disappearAfterMillis = conversation?.disappearAfterMillis,
                isBlocked = peerRow?.isBlocked == true,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = ChatState(),
    )

    /**
     * Bytes the draft would occupy on the wire, quote included.
     *
     * The quote travels in the same body, so a counter that left it out would
     * let the user type up to a limit the send then refuses.
     */
    val draftBytes: StateFlow<Int> = combine(_draft, _replyTo) { draft, quote ->
        MessagePacket.bodyByteCount(outgoingBody(draft, quote))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    /** Points this ViewModel at a conversation. Null opens the broadcast thread. */
    fun bind(target: String?) {
        if (peerId.value == target) return
        peerId.value = target
        _draft.value = ""
        _replyTo.value = null
        viewModelScope.launch { messages.markRead(conversationIdFor(target)) }
    }

    /**
     * Tells the app this thread is on screen, or no longer is.
     *
     * Silences its notifications while the user is reading it. Also marks it
     * read on the way in, and again on the way out: everything that arrived
     * while the thread was on screen has been seen, and without the second
     * mark it would come back as unread on the peer list.
     *
     * Takes the thread explicitly rather than reading the bound one: the
     * resume effect can run before [bind]'s coroutine has re-pointed this
     * ViewModel, and would then silence the previous thread instead.
     */
    fun onVisible(target: String?, visible: Boolean) {
        val conversationId = conversationIdFor(target)
        if (visible) {
            foregroundConversation.enter(conversationId)
            viewModelScope.launch { messages.markRead(conversationId) }
        } else {
            foregroundConversation.leave(conversationId)
            viewModelScope.launch { messages.markRead(conversationId) }
        }
    }

    fun onDraftChange(value: String) {
        _draft.value = value
    }

    /** Puts text shared or forwarded here into the box, under anything already typed. */
    fun onShared(text: String) {
        _draft.value = SharedText.intoDraft(_draft.value, text)
    }

    /** Starts a reply to [message]; the next send quotes it. */
    fun onReply(message: MessageEntity) {
        _replyTo.value = Quote.snippetOf(message.body)
    }

    fun onCancelReply() {
        _replyTo.value = null
    }

    /**
     * Sends the draft, clearing the box immediately.
     *
     * Safe to clear before the send resolves: the message is already a PENDING
     * row by then and is on screen, so leaving the text in the box would show it
     * twice.
     */
    fun onSend() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        val body = outgoingBody(text, _replyTo.value)
        if (!MessagePacket.isSendable(body)) return

        val target = peerId.value
        _draft.value = ""
        _replyTo.value = null
        viewModelScope.launch {
            when (val result = coordinator.send(body = body, recipientHexId = target)) {
                is SendResult.Rejected -> _errors.value = result.reason
                // Only long, fragmented text can fail outright; a short message
                // with nobody in range is Pending and goes out later.
                is SendResult.Failed -> _errors.value = result.reason
                SendResult.Pending, SendResult.Sent -> Unit
            }
        }
    }

    /**
     * Downscales a picked image and sends it.
     *
     * Scaling first, off the main thread, is what keeps a 12-megapixel pick from
     * either freezing the UI or being handed to a mesh that cannot carry it —
     * see `ImagePolicy` for why the ceiling is where it is.
     */
    fun onImagePicked(uri: Uri) {
        if (_isPreparingImage.value) return
        val target = peerId.value

        viewModelScope.launch {
            _isPreparingImage.value = true
            val scaled = try {
                imageScaler.scale(uri)
            } finally {
                _isPreparingImage.value = false
            }

            if (scaled == null) {
                _errors.value = "That image could not be prepared for sending."
                return@launch
            }

            when (val result = coordinator.sendImage(scaled.jpeg, target)) {
                is SendResult.Rejected -> _errors.value = result.reason
                // Images are not store-and-forwarded: a fragment that finds no
                // peer ends the transfer, so this is a real failure the user has
                // to know about, not the quiet "will go out later" that text gets.
                is SendResult.Failed -> _errors.value = result.reason
                SendResult.Pending, SendResult.Sent -> Unit
            }
        }
    }

    /**
     * Sends a failed message again, in its place at the bottom of the thread.
     *
     * Every outcome but Rejected shows up in the row itself, so only that one
     * needs a message of its own.
     */
    fun onResend(messageId: String) {
        viewModelScope.launch {
            when (val result = coordinator.resend(messageId)) {
                is SendResult.Rejected -> _errors.value = result.reason
                is SendResult.Failed -> _errors.value = result.reason
                SendResult.Pending, SendResult.Sent -> Unit
            }
        }
    }

    /**
     * Deletes a message from this phone. The menu only offers it where
     * `canDeleteLocally` allows, so a refusal here means the row went first.
     */
    fun onDelete(messageId: String) {
        viewModelScope.launch {
            if (!coordinator.deleteLocally(messageId)) {
                _errors.value = "That message could not be deleted."
            }
        }
    }

    /** Empties the open thread on this phone; the user's queued messages stay. */
    fun onDeleteChat() {
        val peer = peerId.value
        viewModelScope.launch { coordinator.deleteChat(peer) }
    }

    /**
     * Forgets the open peer; see `MeshCoordinator.forgetPeer` for what goes and
     * why its keys stay. The screen leaves the thread at once rather than
     * waiting: the work runs in the mesh scope and finishes either way.
     */
    fun onForget() {
        val peer = peerId.value ?: return
        viewModelScope.launch { coordinator.forgetPeer(peer) }
    }

    /** The name the save dialog suggests for this chat's export. */
    fun exportFileName(): String =
        ChatExport.fileName(state.value.title, SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()))

    /**
     * Writes the open thread as text to [uri], a document the user just created
     * in the system file picker.
     *
     * Read from the database at the moment of export rather than from [state],
     * which can lag a message that landed while the picker was open. Only ever
     * called from that picker's result, so nothing is written anywhere the user
     * did not choose; the app holds no storage permission for this.
     *
     * Takes the thread explicitly, like [onVisible]: if the process died while
     * the picker was open, the result arrives before [bind] has re-pointed this
     * ViewModel, and reading the bound thread would save the broadcast thread
     * under the contact's file name. The title is looked up for the same reason.
     */
    fun onExport(uri: Uri, peer: String?) {
        viewModelScope.launch {
            val title = when (peer) {
                null -> EVERYONE
                else -> peers.findPeer(peer)?.preferredName?.let { peerLabel(it, peer) } ?: peer
            }
            val rows = messages.observeMessages(conversationIdFor(peer)).first()
            // A direct thread has one other sender, already named by the title;
            // the broadcast thread labels each sender as its bubbles do.
            val names = if (peer == null) {
                peers.observePeers().first().associate { it.id to it.preferredName }
            } else {
                emptyMap()
            }
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val text = ChatExport.render(
                title = title,
                exportedAt = timeFormat.format(Date()),
                messages = rows,
                senderLabel = { id -> if (peer != null) title else peerLabel(names[id], id) },
                formatTime = { timeFormat.format(Date(it)) },
            )
            val saved = withContext(Dispatchers.IO) {
                try {
                    // "wt" truncates: picking an existing, longer file must not
                    // leave its old tail after the new text.
                    context.contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                        true
                    } ?: false
                } catch (e: IOException) {
                    false
                } catch (e: SecurityException) {
                    false
                } catch (e: IllegalArgumentException) {
                    // A provider that does not support the "wt" mode.
                    false
                }
            }
            _errors.value = if (saved) EXPORT_SAVED else EXPORT_FAILED
        }
    }

    /** Absolute path of a stored attachment, for the bubble to decode. */
    fun attachmentPath(fileName: String): String = attachments.pathFor(fileName)

    fun onErrorShown() {
        _errors.value = null
    }

    private fun outgoingBody(draft: String, quote: String?): String {
        val text = draft.trim()
        // A quote with nothing under it is not a reply; the count shows the bare draft.
        return if (quote == null || text.isEmpty()) text else Quote.compose(quote, text)
    }

    /**
     * Saves the user's nickname for the open peer; blank input removes it.
     * Does nothing in the broadcast thread, which is nobody in particular.
     */
    fun onRename(input: String) {
        val peer = peerId.value ?: return
        viewModelScope.launch { peers.rename(peer, input) }
    }

    /**
     * Blocks or unblocks the open peer. Does nothing in the broadcast thread:
     * a spammer there is blocked from their own chat, reached from the peer list.
     */
    fun onSetBlocked(blocked: Boolean) {
        val peer = peerId.value ?: return
        viewModelScope.launch { peers.block(peer, blocked) }
    }

    /** Sets this thread's disappearing timer; null turns it off. */
    fun onSetDisappearing(millis: Long?) {
        val peer = peerId.value
        viewModelScope.launch { coordinator.setDisappearing(peer, millis) }
    }

    private fun conversationIdFor(peer: String?): String =
        peer?.let(::directConversationId) ?: BROADCAST_CONVERSATION_ID

    private companion object {
        const val EVERYONE = "Everyone"
        const val EXPORT_SAVED = "Chat saved."
        const val EXPORT_FAILED = "The chat could not be saved."
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
