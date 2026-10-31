package et.alpha.nchat.mesh.crypto

import et.alpha.nchat.data.db.toHexId
import et.alpha.nchat.mesh.protocol.Announcement
import et.alpha.nchat.mesh.protocol.LinkTokenProof
import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketCodec
import et.alpha.nchat.mesh.protocol.PacketType
import et.alpha.nchat.mesh.protocol.PacketVerifier
import et.alpha.nchat.mesh.protocol.Verification
import et.alpha.nchat.mesh.protocol.peerIdFor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public keys we hold, keyed by the peer id they belong to.
 *
 * Synchronous and in-memory because `MeshRouter.onInbound` is not a suspend
 * function — it is a plain collector on `Transport.incoming`, and a signature
 * has to be checked there, inline, before the packet is allowed to claim a
 * dedup slot. A Room lookup at that point would mean restructuring the whole
 * collector; a map read costs nothing.
 *
 * Room remains the durable copy. This is a cache warmed from it at startup and
 * updated as ANNOUNCE packets arrive.
 */
@Singleton
class PublicKeyDirectory(
    private val maxTransient: Int,
) {
    @Inject constructor() : this(MAX_TRANSIENT_KEYS)

    /**
     * The signing key is fixed once known; the agreement key can arrive later.
     *
     * They are separable because they reach us by different routes. A peer
     * stored before agreement keys were persisted (database version 3 and
     * earlier) has only a signing key, which is enough to verify its packets
     * but not to encrypt to it. The agreement key turns up with the peer's next
     * ANNOUNCE.
     */
    private class Entry(
        val signingKey: ByteArray,
        @Volatile var agreementKey: ByteArray? = null,
    )

    /**
     * Keys this device has a reason to keep for the whole session: its own,
     * contacts restored at start and anyone scanned by QR. Never evicted.
     */
    private val pinned = HashMap<String, Entry>()

    /**
     * Everyone else, learned off the air, least recently used first.
     *
     * Every self-signed ANNOUNCE within six hops lands here, and keys cost a
     * modified phone nothing to mint, so without a cap the map grows until the
     * next restart. Access order means a stranger the user is chatting with
     * stays: every packet verified from them and every message sealed to them
     * touches the entry. An evicted peer is not lost, only forgotten until its
     * next ANNOUNCE, which re-files it (and re-signals its agreement key, which
     * releases anything held for it). Its Room row keeps its keys either way.
     */
    private val transient = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > maxTransient
    }

    /** Guards both maps; reads mutate [transient]'s access order. */
    private val lock = Any()

    private fun entry(hex: String): Entry? = synchronized(lock) { pinned[hex] ?: transient[hex] }

    private val _agreementKeyLearned = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * Hex ids of peers that just became encryptable.
     *
     * Direct messages to a peer without an agreement key are held rather than
     * sent in the clear, so whoever holds them needs to hear the moment a key
     * turns up — whether it came over the radio in an ANNOUNCE or from a QR
     * scan, which reach this class by different callers. Emits once per peer,
     * because the agreement key is first-wins and never replaced.
     */
    val agreementKeyLearned: SharedFlow<String> = _agreementKeyLearned.asSharedFlow()

    /**
     * Records a peer's signing key under the id it commits to.
     *
     * The id is derived rather than accepted alongside the key, so a device
     * cannot register under someone else's id: whatever it hands us is only ever
     * filed under `peerIdFor(signingKey)`.
     *
     * **First key wins.** A different signing key for an id we already hold is
     * ignored. An attacker who can inject an ANNOUNCE must not be able to
     * displace a key the user verified by QR — trust-on-first-use is weak, but
     * silently accepting replacement would be weaker still. Genuine key rotation
     * needs the user to forget the peer and re-pair, deliberately.
     *
     * @return the peer id this key was filed under.
     */
    fun recordSigningKey(signingKey: ByteArray): ByteArray = file(signingKey, pin = false)

    /**
     * Files [signingKey] first-wins, and with [pin] moves an existing transient
     * entry into [pinned] so it survives any number of strangers after it.
     */
    private fun file(signingKey: ByteArray, pin: Boolean): ByteArray {
        val peerId = peerIdFor(signingKey)
        val hex = peerId.toHexId()
        synchronized(lock) {
            if (hex in pinned) return peerId
            val existing = if (pin) transient.remove(hex) else transient[hex]
            val entry = existing ?: Entry(signingKey.copyOf())
            if (pin) pinned[hex] = entry else if (existing == null) transient[hex] = entry
        }
        return peerId
    }

    /**
     * Records both of a peer's keys.
     *
     * The signing key follows first-wins. The agreement key is filled in if we
     * do not have one, which is how a peer restored from the database — signing
     * key only — becomes encryptable once it announces. An existing agreement
     * key is *not* replaced, for the same reason the signing key is not.
     */
    fun record(announcement: Announcement, pin: Boolean = false): ByteArray {
        val peerId = file(announcement.signingKey, pin)
        val hex = peerId.toHexId()
        val learned = entry(hex)?.let { entry ->
            synchronized(entry) {
                if (entry.agreementKey == null) {
                    entry.agreementKey = announcement.agreementKey.copyOf()
                    true
                } else {
                    false
                }
            }
        } ?: false
        // A full buffer drops the signal rather than blocking a non-suspend
        // caller. The held rows are still PENDING, and the next connection's
        // ANNOUNCE is not a second signal (first key wins), so a dropped one
        // would strand them until restart. 64 slots is far more peers than will
        // announce in the instant before the collector runs.
        if (learned) _agreementKeyLearned.tryEmit(hex)
        return peerId
    }

    /**
     * Refills the directory from a stored peer at start.
     *
     * The keys come from our own database rather than off the air, but are
     * still filed with the same first-wins rules: anything learned since start
     * is at least as trustworthy. An [agreementKey] of the wrong size (a
     * corrupt row) is dropped rather than thrown, so one bad row cannot stop
     * the rest of the directory from warming; the peer simply stays
     * unencryptable until it announces.
     *
     * [pin] is for peers the user has a reason to keep (see [pinned]); a
     * stranger restored unpinned competes for the transient slots like one
     * heard on the air, so a crowded week cannot fill the pinned map.
     */
    fun restore(signingKey: ByteArray, agreementKey: ByteArray?, pin: Boolean = false) {
        if (agreementKey != null && agreementKey.size == Announcement.KEY_SIZE) {
            record(Announcement(signingKey, agreementKey), pin)
        } else {
            file(signingKey, pin)
        }
    }

    fun signingKeyFor(peerId: ByteArray): ByteArray? = entry(peerId.toHexId())?.signingKey

    /** The X25519 key needed to derive a message key, or null if not yet known. */
    fun agreementKeyFor(peerId: ByteArray): ByteArray? = entry(peerId.toHexId())?.agreementKey

    fun knows(peerId: ByteArray): Boolean = entry(peerId.toHexId()) != null

    val size: Int get() = synchronized(lock) { pinned.size + transient.size }

    companion object {
        /**
         * Strangers' keys held at once. About 100 bytes each, so the cap is
         * tens of kilobytes; far more peers than a phone meets in a day, and few
         * enough that minting keys cannot grow the heap.
         */
        const val MAX_TRANSIENT_KEYS = 1024
    }
}

/**
 * Checks packet signatures against [directory], using Tink's Ed25519.
 *
 * Lives here rather than in `mesh/router` so the router keeps talking to the
 * pure [PacketVerifier] interface and stays testable on the JVM without Tink or
 * Android on the classpath.
 */
@Singleton
class DirectoryPacketVerifier @Inject constructor(
    private val directory: PublicKeyDirectory,
) : PacketVerifier {

    override fun verify(packet: Packet): Verification {
        val signedBytes = PacketCodec.signedBytes(packet)

        // An ANNOUNCE carries the key it is signed with, so it is checked
        // against its own payload rather than against the directory. This is
        // what lets a device we have never heard of introduce itself without
        // the introduction being trivially forgeable.
        if (packet.type == PacketType.ANNOUNCE) {
            val announced = Announcement.parse(packet.payload) ?: return Verification.INVALID

            // The binding that makes this safe: the id must be the hash of the
            // key inside. Without this check anyone could announce their own key
            // under someone else's id and become that peer.
            if (!announced.peerId.contentEquals(packet.senderId)) return Verification.INVALID

            return if (verifyEd25519(announced.signingKey, signedBytes, packet.signature)) {
                Verification.VALID
            } else {
                Verification.INVALID
            }
        }

        // Self-authenticating for the same reason, and so that a proof which
        // overtakes the announcement filing its key is not lost.
        if (packet.type == PacketType.LINK_PROOF) {
            val proof = LinkTokenProof.parse(packet.payload) ?: return Verification.INVALID
            if (!proof.peerId.contentEquals(packet.senderId)) return Verification.INVALID
            return if (verifyEd25519(proof.signingKey, signedBytes, packet.signature)) {
                Verification.VALID
            } else {
                Verification.INVALID
            }
        }

        val publicKey = directory.signingKeyFor(packet.senderId)
            ?: return Verification.UNKNOWN_SENDER
        return if (verifyEd25519(publicKey, signedBytes, packet.signature)) {
            Verification.VALID
        } else {
            Verification.INVALID
        }
    }
}
