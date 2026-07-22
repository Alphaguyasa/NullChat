package et.alpha.nchat.mesh.crypto

import et.alpha.nchat.data.db.toHexId
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
 * updated as keys arrive.
 */
@Singleton
class PublicKeyDirectory @Inject constructor() {

    private val keys = ConcurrentHashMap<String, ByteArray>()

    /**
     * Records [publicKey] as belonging to the peer it commits to.
     *
     * The id is derived from the key rather than accepted alongside it, so a
     * device cannot register a key under someone else's id: whatever it hands
     * us is only ever filed under `SHA-256(key)[0..8]`.
     *
     * First key wins. A second, different key for an id we already know is
     * ignored — an attacker who can inject one must not be able to displace the
     * key a user verified by QR. Real rotation needs a re-pair, deliberately.
     *
     * @return the peer id this key was filed under.
     */
    fun record(publicKey: ByteArray): ByteArray {
        val peerId = peerIdFor(publicKey)
        keys.putIfAbsent(peerId.toHexId(), publicKey.copyOf())
        return peerId
    }

    fun keyFor(peerId: ByteArray): ByteArray? = keys[peerId.toHexId()]?.copyOf()

    fun knows(peerId: ByteArray): Boolean = keys.containsKey(peerId.toHexId())

    val size: Int get() = keys.size
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

    override fun verify(
        signedBytes: ByteArray,
        signature: ByteArray,
        senderId: ByteArray,
    ): Verification {
        val publicKey = directory.keyFor(senderId) ?: return Verification.UNKNOWN_SENDER
        return if (verifyEd25519(publicKey, signedBytes, signature)) {
            Verification.VALID
        } else {
            Verification.INVALID
        }
    }
}
