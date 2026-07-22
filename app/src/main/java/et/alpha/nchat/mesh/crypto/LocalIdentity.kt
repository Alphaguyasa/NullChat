package et.alpha.nchat.mesh.crypto

import et.alpha.nchat.mesh.protocol.Announcement
import et.alpha.nchat.mesh.protocol.PacketSigner
import javax.inject.Inject
import javax.inject.Singleton

/** This device's stable mesh identity. */
interface LocalIdentity {

    /**
     * The 8-byte id this device is known by on the mesh.
     *
     * Stable across restarts: it keys conversations in the database and is what
     * peers address packets to. Derived from [publicKey], so it cannot be
     * claimed by a device that does not hold the matching private key.
     */
    val peerId: ByteArray

    /**
     * This device's Ed25519 public key, 32 bytes.
     *
     * Published freely — it goes out in ANNOUNCE packets and QR codes, and is
     * what peers verify our signatures against.
     */
    val publicKey: ByteArray

    /** Both public keys, in the form an ANNOUNCE or a QR code carries them. */
    val announcement: Announcement
}

/**
 * The real identity, backed by [KeyManager]'s Ed25519 keypair.
 *
 * Replaces the random-bytes placeholder that stood here until the crypto layer
 * existed. The consequence of the swap is that **every device's peer id
 * changes**: ids used to be 8 random bytes and are now a hash of the signing
 * key. Conversations, messages and queued packets stored against an old id are
 * orphaned, because an old id cannot be derived from a new key. The app is
 * pre-v1 and `TESTING.md` already documents that clearing app data changes the
 * id, so upgrading means clearing data rather than migrating to a value that
 * does not exist.
 */
@Singleton
class KeyManagerIdentity @Inject constructor(
    private val keyManager: KeyManager,
) : LocalIdentity, PacketSigner {

    override val peerId: ByteArray get() = keyManager.peerId.copyOf()

    override val publicKey: ByteArray get() = keyManager.signingPublicKey

    override val announcement: Announcement
        get() = Announcement(
            signingKey = keyManager.signingPublicKey,
            agreementKey = keyManager.agreementPublicKey,
        )

    override fun sign(signedBytes: ByteArray): ByteArray = keyManager.sign(signedBytes)
}
