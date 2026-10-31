package et.alpha.nchat.mesh.router

/**
 * A verified ANNOUNCE or LINK_PROOF that crossed one of this device's links.
 *
 * [endpointId] is the link it arrived on (link-layer namespace, like
 * [et.alpha.nchat.mesh.transport.InboundPayload.fromPeer]); [peerId] is the
 * 8-byte protocol id its signature proved. [tokenDigest] is set for a
 * LINK_PROOF: the digest of the link token the sender signed, which binds the
 * proof to one link (see [et.alpha.nchat.mesh.protocol.LinkTokenProof]).
 */
class LinkProof(
    val endpointId: String,
    val peerId: ByteArray,
    val tokenDigest: ByteArray? = null,
) {
    override fun toString(): String =
        "LinkProof(endpoint=$endpointId, token=${tokenDigest != null})"
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
 * An announcement alone does not stop a stranger who is also in range of the
 * real contact: it can relay the contact's live announcement onto its own link
 * the moment it is made. A LINK_PROOF closes that. It signs the digest of the
 * link's authentication token, which differs on each of the relay's two links,
 * so it binds only when its digest equals the one this side of the link sees.
 *
 * Builds before LINK_PROOF never send one, so an announcement still binds a peer
 * this phone has never had a token proof from. Once one has arrived,
 * [requiresTokenProof] says so (the coordinator remembers it across restarts),
 * and from then on that peer's announcement alone binds nothing. A relay cannot
 * strip the proof to fall back to the old rule. A contact who goes back to an
 * old build then stays unbound until it updates again, which is the price.
 *
 * The claim (the endpoint connecting) and the proof (its packet being
 * delivered) come from separate flows and can arrive in either order, so both
 * are remembered until the other side shows up or the endpoint leaves.
 *
 * Pure Kotlin and thread-safe: the coordinator calls it from two collectors.
 *
 * @param requiresTokenProof whether a peer (hex id) is known to send token proofs.
 * @param onTokenProof called with the hex id each time a token proof matches its
 *   link, bound already or not, so the caller can remember that peer.
 */
class LinkBindings(
    private val requiresTokenProof: (String) -> Boolean = { false },
    private val onTokenProof: (String) -> Unit = {},
) {

    private val lock = Any()
    private val claims = mutableMapOf<String, Claim>()
    private val proofs = mutableMapOf<String, MutableSet<Pending>>()
    private val bound = mutableSetOf<String>()

    /**
     * Records that [endpointId] connected naming itself [claimedPeerId].
     *
     * @param tokenDigest the digest of the link token this side sees, or null if
     *   the transport gives none. Without it no token proof can match.
     * @return true if a matching proof had already arrived, so the link is
     *   bound now. False until then, and for an endpoint already bound.
     */
    fun claim(
        endpointId: String,
        claimedPeerId: ByteArray,
        tokenDigest: ByteArray? = null,
    ): Boolean {
        val proved = mutableListOf<String>()
        val result = synchronized(lock) {
            val claim = Claim(claimedPeerId.hex(), tokenDigest?.hex())
            claims[endpointId] = claim
            val early = proofs.remove(endpointId).orEmpty()
            var binds = false
            for (pending in early) {
                if (pending.peerHex != claim.peerHex) continue
                if (pending.digestHex != null) {
                    if (pending.digestHex != claim.digestHex) continue
                    proved.add(claim.peerHex)
                } else if (requiresTokenProof(claim.peerHex)) {
                    continue
                }
                binds = true
            }
            binds && bound.add(endpointId)
        }
        proved.forEach(onTokenProof)
        return result
    }

    /**
     * Records a verified proof from [LinkProof.peerId] on [LinkProof.endpointId].
     *
     * @return true if that endpoint claimed exactly this id, the proof is strong
     *   enough for it, and it was not bound yet. A proof for a different id (a
     *   distant peer's announcement relayed down this link) never binds it.
     */
    fun prove(proof: LinkProof): Boolean {
        val provedHex = proof.peerId.hex()
        val digestHex = proof.tokenDigest?.hex()
        var tokenMatched = false
        val result = synchronized(lock) {
            val claim = claims[proof.endpointId]
            if (claim == null) {
                // The proof beat the connection event. Kept per id rather than
                // as one slot, because relayed announcements share the link.
                val pending = proofs.getOrPut(proof.endpointId) { mutableSetOf() }
                if (pending.size < MAX_EARLY_PROOFS) pending.add(Pending(provedHex, digestHex))
                return@synchronized false
            }
            if (claim.peerHex != provedHex) return@synchronized false
            if (digestHex != null) {
                // A token proof that does not match this side's token was made
                // for another link: the contact's link to a relay.
                if (digestHex != claim.digestHex) return@synchronized false
                tokenMatched = true
            } else if (requiresTokenProof(provedHex)) {
                return@synchronized false
            }
            bound.add(proof.endpointId)
        }
        if (tokenMatched) onTokenProof(provedHex)
        return result
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
        bound.mapNotNullTo(mutableSetOf()) { claims[it]?.peerHex }
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

    private data class Claim(val peerHex: String, val digestHex: String?)

    private data class Pending(val peerHex: String, val digestHex: String?)

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        /** Distinct proofs remembered for one not-yet-connected endpoint. */
        const val MAX_EARLY_PROOFS = 16

        /** Not-yet-connected endpoints with early proofs before a cleanup. */
        const val MAX_PENDING_ENDPOINTS = 64
    }
}
