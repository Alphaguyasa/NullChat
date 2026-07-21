package et.alpha.nchat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.nchat.data.db.BROADCAST_CONVERSATION_ID
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.directConversationId
import et.alpha.nchat.data.db.toHexId
import et.alpha.nchat.data.repo.MessageRepository
import et.alpha.nchat.mesh.MeshCoordinator
import et.alpha.nchat.mesh.protocol.MessagePacket
import et.alpha.nchat.mesh.transport.NearbyTransport
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatState(
    val messages: List<MessageEntity> = emptyList(),
    val title: String = "",
    val isPeerOnline: Boolean = false,
    val isBroadcast: Boolean = true,
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
    private val coordinator: MeshCoordinator,
    private val transport: NearbyTransport,
) : ViewModel() {

    /** Null means the mesh-wide broadcast thread. */
    private val peerId = MutableStateFlow<String?>(null)

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    val state: StateFlow<ChatState> = peerId.flatMapLatest { peer ->
        combine(
            messages.observeMessages(conversationIdFor(peer)),
            transport.connectedPeers.map { endpoints ->
                peer == null || endpoints.any { transport.peerIdFor(it)?.toHexId() == peer }
            },
        ) { rows, online ->
            ChatState(
                messages = rows,
                title = peer ?: EVERYONE,
                isPeerOnline = online,
                isBroadcast = peer == null,
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
        if (body.isEmpty() || !MessagePacket.fits(body)) return

        val target = peerId.value
        _draft.value = ""
        viewModelScope.launch { coordinator.send(body = body, recipientHexId = target) }
    }

    private fun conversationIdFor(peer: String?): String =
        peer?.let(::directConversationId) ?: BROADCAST_CONVERSATION_ID

    private companion object {
        const val EVERYONE = "Everyone"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
