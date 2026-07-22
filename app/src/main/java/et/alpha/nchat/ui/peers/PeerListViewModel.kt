package et.alpha.nchat.ui.peers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.nchat.data.db.BROADCAST_CONVERSATION_ID
import et.alpha.nchat.data.db.ConversationSummary
import et.alpha.nchat.data.db.directConversationId
import et.alpha.nchat.data.db.toHexId
import et.alpha.nchat.data.repo.MessageRepository
import et.alpha.nchat.data.repo.PeerRepository
import et.alpha.nchat.mesh.MeshCoordinator
import et.alpha.nchat.mesh.transport.NearbyTransport
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One row in the peer list. */
data class PeerRow(
    /** Hex peer id, or null for the broadcast row. */
    val peerId: String?,
    val label: String,
    /** Reachable this instant, as opposed to merely known about. */
    val isOnline: Boolean,
    val lastMessage: String?,
    val unreadCount: Int,
    /**
     * Whether the user has confirmed this peer's key out of band, by QR.
     *
     * False covers two very different situations that the UI deliberately does
     * not distinguish: a peer whose key arrived over the radio and was accepted
     * on trust, and one whose key we do not have at all. Neither has been
     * checked by a human, and telling them apart would invite the user to treat
     * the first as safe.
     */
    val isVerified: Boolean = false,
) {
    val isBroadcast: Boolean get() = peerId == null
}

data class PeerListState(
    val rows: List<PeerRow> = emptyList(),
    val onlineCount: Int = 0,
    val selfId: String = "",
)

@HiltViewModel
class PeerListViewModel @Inject constructor(
    peers: PeerRepository,
    messages: MessageRepository,
    private val transport: NearbyTransport,
    coordinator: MeshCoordinator,
) : ViewModel() {

    val selfId: String = coordinator.selfId

    /**
     * Online status comes from the transport, not from
     * `PeerRepository.observeActivePeers`.
     *
     * That method takes `now` as a parameter, evaluated once when the flow is
     * built — a long-lived subscription would freeze its freshness window and
     * keep showing peers as present long after they walked away. So the database
     * supplies the roster and the transport supplies who is actually reachable.
     */
    val state: StateFlow<PeerListState> = combine(
        peers.observePeers(),
        messages.observeConversations(),
        transport.connectedPeers,
    ) { knownPeers, conversations, endpoints ->
        val online = endpoints.mapNotNullTo(mutableSetOf()) { transport.peerIdFor(it)?.toHexId() }
        val byConversation = conversations.associateBy { it.conversation.id }

        val broadcastRow = byConversation[BROADCAST_CONVERSATION_ID].toBroadcastRow(online.size)
        val peerRows = knownPeers.map { peer ->
            val summary = byConversation[directConversationId(peer.id)]
            PeerRow(
                peerId = peer.id,
                label = peer.displayName ?: peer.id.take(SHORT_ID_CHARS),
                isOnline = peer.id in online,
                lastMessage = summary?.lastMessageBody,
                unreadCount = summary?.unreadCount ?: 0,
                isVerified = peer.isVerified,
            )
        // Reachable peers first, then most recently heard from. Someone standing
        // next to you matters more than someone you spoke to yesterday.
        }.sortedWith(compareByDescending<PeerRow> { it.isOnline }.thenBy { it.label })

        PeerListState(
            rows = listOf(broadcastRow) + peerRows,
            onlineCount = online.size,
            selfId = selfId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = PeerListState(selfId = selfId),
    )

    private fun ConversationSummary?.toBroadcastRow(onlineCount: Int) = PeerRow(
        peerId = null,
        label = "Everyone",
        isOnline = onlineCount > 0,
        lastMessage = this?.lastMessageBody,
        unreadCount = this?.unreadCount ?: 0,
    )

    private companion object {
        const val SHORT_ID_CHARS = 8
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
