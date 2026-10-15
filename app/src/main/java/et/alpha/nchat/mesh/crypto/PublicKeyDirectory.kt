package et.alpha.nchat.mesh.crypto

import et.alpha.nchat.data.db.toHexId
import et.alpha.nchat.mesh.protocol.Announcement
import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketCodec
import et.alpha.nchat.mesh.protocol.PacketType
import et.alpha.nchat.mesh.protocol.PacketVerifier
import et.alpha.nchat.mesh.protocol.Verification
import et.alpha.nchat.mesh.protocol.peerIdFor
import java.util.concurrent.ConcurrentHashMap
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
class PublicKeyDirectory @Inject constructor() {

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

    private val entries = ConcurrentHashMap<String, Entry>()

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
    fun recordSigningKey(signingKey: ByteArray): ByteArray {
        val peerId = peerIdFor(signingKey)
        entries.putIfAbsent(peerId.toHexId(), Entry(signingKey.copyOf()))
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
    fun record(announcement: Announcement): ByteArray {
        val peerId = recordSigningKey(announcement.signingKey)
        entries[peerId.toHexId()]?.let { entry ->
            if (entry.agreementKey == null) entry.agreementKey = announcement.agreementKey.copyOf()
        }
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
     */
    fun restore(signingKey: ByteArray, agreementKey: ByteArray?) {
        if (agreementKey != null && agreementKey.size == Announcement.KEY_SIZE) {
            record(Announcement(signingKey, agreementKey))
        } else {
            recordSigningKey(signingKey)
        }
    }

    fun signingKeyFor(peerId: ByteArray): ByteArray? = entries[peerId.toHexId()]?.signingKey

    /** The X25519 key needed to derive a message key, or null if not yet known. */
    fun agreementKeyFor(peerId: ByteArray): ByteArray? = entries[peerId.toHexId()]?.agreementKey

    fun knows(peerId: ByteArray): Boolean = entries.containsKey(peerId.toHexId())

    val size: Int get() = entries.size
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

        val publicKey = directory.signingKeyFor(packet.senderId)
            ?: return Verification.UNKNOWN_SENDER
        return if (verifyEd25519(publicKey, signedBytes, packet.signature)) {
            Verification.VALID
        } else {
            Verification.INVALID
        }
    }
}
