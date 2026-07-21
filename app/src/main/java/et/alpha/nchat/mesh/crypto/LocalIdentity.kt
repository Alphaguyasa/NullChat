package et.alpha.nchat.mesh.crypto

import android.content.Context
import et.alpha.nchat.data.db.hexIdToBytes
import et.alpha.nchat.data.db.toHexId
import et.alpha.nchat.mesh.protocol.Packet
import java.security.SecureRandom

/** This device's stable mesh identity. */
interface LocalIdentity {

    /**
     * The 8-byte id this device is known by on the mesh.
     *
     * Stable across restarts: it keys conversations in the database and is what
     * peers address packets to.
     */
    val peerId: ByteArray
}

/**
 * A stand-in identity until `KeyManager` exists.
 *
 * **This is not the real thing.** CLAUDE.md specifies the peer id as the first 8
 * bytes of SHA-256 of an Ed25519 public key held in the Android Keystore. There
 * is no keypair yet, so this generates 8 random bytes on first launch and keeps
 * them in preferences.
 *
 * The consequences, none of which matter before the crypto layer lands but all
 * of which matter after:
 * - The id is not bound to any key, so nothing can verify a peer is who its id
 *   claims. Packet signatures cannot be checked against it.
 * - Clearing app data mints a new identity and orphans existing conversations.
 * - The id does not survive a reinstall.
 *
 * Replacing this with a Keystore-backed implementation is a prerequisite for
 * shipping, not a nice-to-have. The [LocalIdentity] seam exists so that swap
 * touches this file only.
 */
class PlaceholderLocalIdentity(context: Context) : LocalIdentity {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override val peerId: ByteArray by lazy { loadOrCreate() }

    private fun loadOrCreate(): ByteArray {
        val stored = preferences.getString(KEY_PEER_ID, null)
        if (stored != null) {
            // Corrupt or truncated preferences are recoverable — mint a new id
            // rather than crashing the service on every start.
            runCatching { stored.hexIdToBytes() }
                .getOrNull()
                ?.takeIf { it.size == Packet.PEER_ID_SIZE }
                ?.let { return it }
        }

        val generated = ByteArray(Packet.PEER_ID_SIZE).also { SecureRandom().nextBytes(it) }
        preferences.edit().putString(KEY_PEER_ID, generated.toHexId()).apply()
        return generated
    }

    private companion object {
        const val PREFERENCES = "mesh_identity"
        const val KEY_PEER_ID = "local_peer_id"
    }
}
