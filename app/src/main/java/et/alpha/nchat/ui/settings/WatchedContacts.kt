package et.alpha.nchat.ui.settings

import et.alpha.nchat.data.db.PeerEntity
import et.alpha.nchat.data.db.preferredName
import et.alpha.nchat.ui.peers.peerLabel

/** A contact with the in-range alert on, as Settings lists it. */
data class WatchedContact(val peerId: String, val label: String)

/**
 * The contacts in [watched], labelled the way the peer list labels them and
 * sorted by that label, so the list reads like the one the user knows.
 *
 * An id with no peer row still gets a line, by its short id: forgetting a
 * contact drops its alert, but an alert left over from some other path must
 * stay visible here, or the user could never turn it off.
 */
internal fun watchedContacts(watched: Set<String>, peers: List<PeerEntity>): List<WatchedContact> {
    if (watched.isEmpty()) return emptyList()
    val byId = peers.associateBy { it.id }
    return watched
        .map { id -> WatchedContact(peerId = id, label = peerLabel(byId[id]?.preferredName, id)) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, WatchedContact::label).thenBy(WatchedContact::peerId))
}
