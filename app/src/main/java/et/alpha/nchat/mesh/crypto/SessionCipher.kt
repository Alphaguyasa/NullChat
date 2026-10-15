package et.alpha.nchat.mesh.crypto

import com.google.crypto.tink.subtle.Hkdf
import et.alpha.nchat.mesh.protocol.Packet
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts and decrypts MESSAGE payloads between this device and one peer.
 *
 * X25519 key agreement produces a shared secret, HKDF turns it into an AES-256
 * key, and each message is sealed with AES-GCM. Both sides derive the same key
 * from their own private key and the other's public key, so no key material is
 * ever transmitted.
 *
 * ## Why the nonce is the message id
 *
 * The packet budget is 180 bytes: 45 header, 64 signature, leaving 71 for the
 * payload. A conventional AES-GCM layer spends 12 of those on an explicit nonce
 * and 16 on the tag, which would cut the longest sendable message to 43 bytes.
 * The header already carries a unique random 16-byte `messageId`, so the first
 * 12 bytes of it serve as the nonce and only the tag is charged — 55 bytes.
 *
 * This is safe **only** because message ids are random UUIDs, unique per
 * message. GCM fails catastrophically on nonce reuse under the same key: two
 * messages sharing one exposes their XOR and lets the authentication key be
 * recovered. The id is also inside the signed region, so it cannot be altered
 * in transit without breaking the signature.
 *
 * A retransmission reuses both the id and the plaintext, producing byte
 * identical ciphertext. That leaks nothing beyond "this is the same message",
 * which the id already says.
 *
 * ## What is not encrypted
 *
 * **Broadcast messages are sent in plaintext.** They have no single recipient,
 * so there is no pair of keys to agree on, and the mesh-wide thread is readable
 * by anyone in range. Group encryption needs a sender-key scheme that does not
 * exist here. `MeshCoordinator` marks broadcasts accordingly and the UI says so.
 */
@Singleton
class SessionCipher @Inject constructor(
    private val keyManager: KeyManager,
    private val directory: PublicKeyDirectory,
) {

    /**
     * Seals [plaintext] for [peerId].
     *
     * @return null if no agreement key is known for that peer, which means we
     *   have not yet had an ANNOUNCE from them. Direct messages are then
     *   held, never sent in the clear; see `sealOrHold` in MeshCoordinator.
     */
    fun encrypt(peerId: ByteArray, messageId: ByteArray, plaintext: ByteArray): ByteArray? {
        val key = sessionKey(peerId) ?: return null
        return cipher(Cipher.ENCRYPT_MODE, key, messageId).doFinal(plaintext)
    }

    /**
     * Opens a payload from [peerId].
     *
     * @return null if there is no key for the peer, or if the tag does not
     *   check out. A failed decryption is not distinguished from a missing key
     *   on purpose — neither produces a message, and the caller has nothing
     *   useful to do differently.
     */
    fun decrypt(peerId: ByteArray, messageId: ByteArray, ciphertext: ByteArray): ByteArray? {
        val key = sessionKey(peerId) ?: return null
        return runCatching { cipher(Cipher.DECRYPT_MODE, key, messageId).doFinal(ciphertext) }
            .getOrNull()
    }

    fun canReach(peerId: ByteArray): Boolean = directory.agreementKeyFor(peerId) != null

    /**
     * Derives the AES key shared with [peerId].
     *
     * Not cached: the derivation is a scalar multiplication and an HKDF, and
     * caching it would mean holding message keys in memory indefinitely and
     * inventing an invalidation rule for when a peer re-announces.
     */
    private fun sessionKey(peerId: ByteArray): ByteArray? {
        val peerAgreementKey = directory.agreementKeyFor(peerId) ?: return null
        return runCatching {
            val shared = keyManager.sharedSecretWith(peerAgreementKey)
            Hkdf.computeHkdf(HKDF_MAC, shared, null, HKDF_INFO, AES_KEY_SIZE)
        }.getOrNull()
    }

    @Throws(GeneralSecurityException::class)
    private fun cipher(mode: Int, key: ByteArray, messageId: ByteArray): Cipher {
        // Tink deliberately does not expose nonce control — its AesGcmJce always
        // generates and prepends a random IV, which would cost the 12 bytes this
        // design exists to save. The JCE provider is the standard way to supply
        // one, and AES-GCM itself is still a platform primitive, not a hand-
        // rolled construction.
        val nonce = messageId.copyOf(NONCE_SIZE)
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        }
    }

    companion object {
        /** AES-GCM's authentication tag, the only per-message overhead. */
        const val TAG_SIZE: Int = 16

        /** Longest plaintext that still fits a packet once the tag is paid for. */
        const val MAX_PLAINTEXT_SIZE: Int = Packet.MAX_PAYLOAD_SIZE - TAG_SIZE

        private const val NONCE_SIZE = 12
        private const val TAG_BITS = TAG_SIZE * 8
        private const val AES_KEY_SIZE = 32
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val HKDF_MAC = "HMACSHA256"

        /**
         * Domain separation for the derived key. Changing this string makes
         * every previously derived session key different, so it must not drift.
         */
        private val HKDF_INFO = "nchat.session.v1".toByteArray()
    }
}
