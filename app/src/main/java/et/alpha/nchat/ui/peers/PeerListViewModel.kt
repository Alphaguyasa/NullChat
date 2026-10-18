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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.runningFold
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
    /**
     * Links between here and this peer — 1 for a direct neighbour — or null when
     * we have no idea, which is the honest answer for a peer we have only ever
     * read out of the database.
     *
     * Certain for a connected peer and a guess for everyone else, because a
     * multi-hop reading comes from `ttl`, which the packet signature deliberately
     * skips so relays can spend a hop. Anything drawing rings from this is
     * drawing an unauthenticated hint. See `MeshCoordinator.observedHops`.
     */
    val hops: Int? = null,
    /**
     * When a packet last crossed the link between this device and this peer, as
     * epoch millis, or null if none has this session.
     *
     * Reads "traffic passed between us just now", and nothing wider. This device
     * can only see its own links: peer B relaying for peer C somewhere out in the
     * mesh is invisible from here, so an idle-looking node may be the busiest one
     * in the room.
     */
    val lastRelayAt: Long? = null,
) {
    val isBroadcast: Boolean get() = peerId == null

    /**
     * Bars to light for this peer.
     *
     * Full or none, because full or none is all this device actually knows.
     * Nearby Connections reports no RSSI — a peer is either connected or it is
     * not — and inventing three intermediate levels from nothing would be exactly
     * the kind of confident lie the state-honesty rule exists to prevent. When a
     * transport starts reporting link quality (`BleTransport` gets
     * `ScanResult.rssi`), this property is the only place that has to change.
     *
     * It lives on the row rather than on the list screen so that the radar and
     * the list cannot end up disagreeing about how strong a link is. Deliberately
     * not a `Transport` member: `NearbyTransport` would implement it as "no idea"
     * forever, and an interface method with one honest answer is dead weight.
     *
     * On the broadcast row the same rule reads as "is the mesh reachable at all",
     * since that row is online exactly when at least one peer is.
     */
    val signalLevel: Int get() = if (isOnline) SIGNAL_BARS else 0
}

/** Bars a reachable peer lights, matching `SignalBars`' own default. */
private const val SIGNAL_BARS = 4

/** A peer the transport has a live link to is, by definition, one hop away. */
private const val DIRECT_HOPS = 1

data class PeerListState(
    val rows: List<PeerRow> = emptyList(),
    val onlineCount: Int = 0,
    val selfId: String = "",
)

/**
 * How a peer is named in the list: their chosen name with the short id beside
 * it, or the short id alone.
 *
 * The id stays even when a name is known. A name is whatever the peer typed,
 * and two strangers can both be "Maya"; eight hex characters derived from the
 * key are the part nobody can pick for themselves.
 */
private const val SHORT_ID_CHARS = 8

internal fun peerLabel(displayName: String?, peerId: String): String {
    val shortId = peerId.take(SHORT_ID_CHARS)
    return if (displayName.isNullOrBlank()) shortId else "$displayName · $shortId"
}

@HiltViewModel
class PeerListViewModel @Inject constructor(
    peers: PeerRepository,
    messages: MessageRepository,
    private val transport: NearbyTransport,
    coordinator: MeshCoordinator,
) : ViewModel() {

    val selfId: String = coordinator.selfId

    /**
     * Relay pulses folded into "when did a packet last cross this peer's link".
     *
     * The router speaks link-layer endpoint ids and the rows are keyed by
     * protocol peer ids; the two namespaces are resolved here, which is the first
     * point that holds both the transport and the roster. An event from an
     * endpoint whose peer id is not known yet is dropped rather than guessed at.
     *
     * Cold, so it accumulates only while the screen is watching. Restarting empty
     * costs nothing: these timestamps drive a momentary animation, and a pulse
     * that happened while the list was closed is not one anybody missed.
     */
    private val relayActivity: Flow<Map<String, Long>> = coordinator.relayActivity
        .mapNotNull { event -> transport.peerIdFor(event.endpointId)?.toHexId() }
        .runningFold(emptyMap<String, Long>()) { active, peerId ->
            active + (peerId to System.currentTimeMillis())
        }

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
        coordinator.observedHops,
        relayActivity,
    ) { knownPeers, conversations, endpoints, observedHops, relayedAt ->
        val online = endpoints.mapNotNullTo(mutableSetOf()) { transport.peerIdFor(it)?.toHexId() }
        val byConversation = conversations.associateBy { it.conversation.id }

        val broadcastRow = byConversation[BROADCAST_CONVERSATION_ID].toBroadcastRow(online.size)
        val peerRows = knownPeers.map { peer ->
            val summary = byConversation[directConversationId(peer.id)]
            PeerRow(
                peerId = peer.id,
                label = peerLabel(peer.displayName, peer.id),
                isOnline = peer.id in online,
                lastMessage = summary?.lastMessageBody,
                unreadCount = summary?.unreadCount ?: 0,
                isVerified = peer.isVerified,
                // A live link is the one distance this device actually knows, so
                // it wins outright over anything read off a ttl. The observed map
                // only ever answers for peers we cannot reach directly.
                hops = if (peer.id in online) DIRECT_HOPS else observedHops[peer.id],
                lastRelayAt = relayedAt[peer.id],
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
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
