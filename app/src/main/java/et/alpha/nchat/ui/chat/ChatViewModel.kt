package et.alpha.nchat.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.nchat.data.db.BROADCAST_CONVERSATION_ID
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.directConversationId
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
import et.alpha.nchat.mesh.transport.NearbyTransport
import et.alpha.nchat.service.ForegroundConversation
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    transferTracker: TransferTracker,
) : ViewModel() {

    /** Null means the mesh-wide broadcast thread. */
    private val peerId = MutableStateFlow<String?>(null)

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

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

    /** One-shot user-facing failures — an unreadable pick, an empty mesh. */
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
        ) { rows, online, peerRow ->
            ChatState(
                messages = rows,
                title = peer ?: EVERYONE,
                isPeerOnline = online,
                isBroadcast = peer == null,
                isVerified = peerRow?.isVerified == true,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = ChatState(),
    )

    /** Bytes the draft would occupy on the wire, against the single-packet budget. */
    val draftBytes: StateFlow<Int> = _draft
        .map(MessagePacket::bodyByteCount)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    /** Points this ViewModel at a conversation. Null opens the broadcast thread. */
    fun bind(target: String?) {
        if (peerId.value == target) return
        peerId.value = target
        _draft.value = ""
        viewModelScope.launch { messages.markRead(conversationIdFor(target)) }
    }

    /**
     * Tells the app this thread is on screen, or no longer is.
     *
     * Silences its notifications while the user is reading it. Also marks it
     * read on the way in: a message that arrived while the app was in the
     * background is read the moment the user comes back to this screen.
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
        }
    }

    fun onDraftChange(value: String) {
        _draft.value = value
    }

    /**
     * Sends the draft, clearing the box immediately.
     *
     * Safe to clear before the send resolves: the message is already a PENDING
     * row by then and is on screen, so leaving the text in the box would show it
     * twice.
     */
    fun onSend() {
        val body = _draft.value.trim()
        if (body.isEmpty() || !MessagePacket.isSendable(body)) return

        val target = peerId.value
        _draft.value = ""
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

    /** Absolute path of a stored attachment, for the bubble to decode. */
    fun attachmentPath(fileName: String): String = attachments.pathFor(fileName)

    fun onErrorShown() {
        _errors.value = null
    }

    private fun conversationIdFor(peer: String?): String =
        peer?.let(::directConversationId) ?: BROADCAST_CONVERSATION_ID

    private companion object {
        const val EVERYONE = "Everyone"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
