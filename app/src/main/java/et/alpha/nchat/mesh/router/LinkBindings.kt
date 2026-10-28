package et.alpha.nchat.mesh.router

/**
 * A verified ANNOUNCE that crossed one of this device's links.
 *
 * [endpointId] is the link it arrived on (link-layer namespace, like
 * [et.alpha.nchat.mesh.transport.InboundPayload.fromPeer]); [peerId] is the
 * 8-byte protocol id its signature proved.
 */
class LinkProof(val endpointId: String, val peerId: ByteArray) {
    override fun toString(): String = "LinkProof(endpoint=$endpointId)"
}

/**
 * Decides when a link belongs to the peer it claims to be.
 *
 * A Nearby endpoint names itself with a peer id, and anyone can advertise any
 * name. Trusting the name let a stranger in range pose as a contact: the
 * coordinator would hand it everything the forward queue held for that contact
 * and mark it all `sent`, so the real contact never got it and the sender was
 * told it went. A link is bound only once a fresh ANNOUNCE signed by the claimed
 * id has arrived *on that link*, which a stranger cannot forge.
 *
 * What it does not stop: a stranger who is also in range of the real contact
 * can relay that contact's live announcement onto its own link in the moment
 * it is made. Closing that needs a challenge only the key holder can answer
 * (a wire change). Queued direct messages are sealed to the contact, so the
 * residual cost is loss, not disclosure.
 *
 * The claim (the endpoint connecting) and the proof (its announcement being
 * delivered) come from separate flows and can arrive in either order, so both
 * are remembered until the other side shows up or the endpoint leaves.
 *
 * Pure Kotlin and thread-safe: the coordinator calls it from two collectors.
 */
class LinkBindings {

    private val lock = Any()
    private val claims = mutableMapOf<String, String>()
    private val proofs = mutableMapOf<String, MutableSet<String>>()
    private val bound = mutableSetOf<String>()

    /**
     * Records that [endpointId] connected naming itself [claimedPeerId].
     *
     * @return true if a matching proof had already arrived, so the link is
     *   bound now. False until then, and for an endpoint already bound.
     */
    fun claim(endpointId: String, claimedPeerId: ByteArray): Boolean = synchronized(lock) {
        val claimed = claimedPeerId.hex()
        claims[endpointId] = claimed
        tryBind(endpointId, claimed)
    }

    /**
     * Records a verified announcement from [LinkProof.peerId] on [LinkProof.endpointId].
     *
     * @return true if that endpoint claimed exactly this id and was not bound yet.
     *   A proof for a different id (a distant peer's announcement relayed down
     *   this link) never binds it.
     */
    fun prove(proof: LinkProof): Boolean = synchronized(lock) {
        val proved = proof.peerId.hex()
        val claimed = claims[proof.endpointId]
        if (claimed == null) {
            // The announcement beat the connection event. Kept per id rather than
            // as one slot, because relayed announcements share the link.
            val pending = proofs.getOrPut(proof.endpointId) { mutableSetOf() }
            if (pending.size < MAX_EARLY_PROOFS) pending.add(proved)
            return false
        }
        if (claimed != proved) return false
        tryBind(proof.endpointId, claimed, proved)
    }

    /** Whether [endpointId] has proved it is the peer it named. */
    fun isBound(endpointId: String): Boolean = synchronized(lock) { endpointId in bound }

    /**
     * Hex ids of the peers on a bound link: who is provably next to us.
     *
     * What "online" and "1 hop" must be drawn from. The endpoint's advertised
     * name is not enough, because a stranger naming itself after a contact
     * would make that contact look like it was standing next to us.
     */
    fun boundPeers(): Set<String> = synchronized(lock) {
        bound.mapNotNullTo(mutableSetOf()) { claims[it] }
    }

    /**
     * Forgets every endpoint not in [connected], so a peer that comes back must
     * prove itself again on its new link.
     */
    fun retain(connected: Set<String>) = synchronized(lock) {
        claims.keys.retainAll(connected)
        bound.retainAll(connected)
        // Early proofs for endpoints the transport has not reported yet are
        // kept: they are exactly the ones waiting for their claim. The cap
        // above bounds what a link that never connects can leave behind.
        if (proofs.size > MAX_PENDING_ENDPOINTS) proofs.keys.retainAll(connected)
    }

    private fun tryBind(endpointId: String, claimed: String, proved: String? = null): Boolean {
        if (endpointId in bound) return false
        val ok = proved == claimed || proofs[endpointId]?.contains(claimed) == true
        if (!ok) return false
        proofs.remove(endpointId)
        bound.add(endpointId)
        return true
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        /** Distinct ids remembered for one not-yet-connected endpoint. */
        const val MAX_EARLY_PROOFS = 16

        /** Not-yet-connected endpoints with early proofs before a cleanup. */
        const val MAX_PENDING_ENDPOINTS = 64
    }
}
