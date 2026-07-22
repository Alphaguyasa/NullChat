package et.alpha.nchat.mesh.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import et.alpha.nchat.mesh.protocol.Packet
import et.alpha.nchat.mesh.protocol.PacketCodec
import et.alpha.nchat.mesh.protocol.PacketType
import et.alpha.nchat.mesh.protocol.Verification
import et.alpha.nchat.mesh.protocol.peerIdFor
import et.alpha.nchat.mesh.protocol.signing
import et.alpha.nchat.mesh.protocol.verifying
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [KeyManager] against real Tink and a real Android Keystore.
 *
 * Instrumented rather than JVM because Keystore does not exist off-device —
 * there is no way to exercise the sealing path in the unit suite at all.
 */
@RunWith(AndroidJUnit4::class)
class KeyManagerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStoredKeys() {
        // A fresh identity per test. The Keystore master key survives this and
        // is reused, which is the realistic path: only the sealed blob is gone.
        context.getSharedPreferences("mesh_keys", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun generatesKeysOfTheRightShape() {
        val keys = KeyManager(context)

        assertEquals("Ed25519 public keys are 32 bytes", 32, keys.signingPublicKey.size)
        assertEquals("X25519 public keys are 32 bytes", 32, keys.agreementPublicKey.size)
        assertEquals(Packet.PEER_ID_SIZE, keys.peerId.size)
    }

    @Test
    fun peerIdCommitsToTheSigningKey() {
        val keys = KeyManager(context)

        // The whole security property: the id is not a name the device chose,
        // it is a hash of the key it must sign with.
        assertArrayEquals(peerIdFor(keys.signingPublicKey), keys.peerId)
    }

    @Test
    fun theSameIdentityComesBackOnASecondInstance() {
        val first = KeyManager(context)
        val firstKey = first.signingPublicKey
        val firstId = first.peerId

        val second = KeyManager(context)

        assertArrayEquals("the sealed key must survive a restart", firstKey, second.signingPublicKey)
        assertArrayEquals(firstId, second.peerId)
        assertArrayEquals(first.agreementPublicKey, second.agreementPublicKey)
    }

    @Test
    fun clearingStoredKeysMintsANewIdentity() {
        val firstId = KeyManager(context).peerId

        clearStoredKeys()
        val secondId = KeyManager(context).peerId

        // Documents the cost of losing the key: the device becomes a different
        // device, which is why the keyset is excluded from backup rather than
        // being restorable.
        assertNotEquals(firstId.toList(), secondId.toList())
    }

    @Test
    fun unreadableStoredKeysDoNotThrow() {
        KeyManager(context)
        context.getSharedPreferences("mesh_keys", Context.MODE_PRIVATE)
            .edit().putString("sealed_identity", "not valid hex at all").commit()

        // Regenerating beats crashing: a service that throws on every start
        // cannot be recovered from by the user.
        val recovered = KeyManager(context)
        assertEquals(32, recovered.signingPublicKey.size)
    }

    @Test
    fun signatureIsExactlyTheWireFormatWidth() {
        val keys = KeyManager(context)

        val signature = keys.sign("anything".toByteArray())

        // Tink's default output prefix would add 5 bytes and overflow the field.
        assertEquals(Packet.SIGNATURE_SIZE, signature.size)
    }

    @Test
    fun aSignedPacketVerifies() {
        val keys = KeyManager(context)
        val identity = KeyManagerIdentity(keys)
        val directory = PublicKeyDirectory().apply { record(keys.signingPublicKey) }
        val verifier = DirectoryPacketVerifier(directory)

        val signed = identity.signing(packetFrom(keys.peerId))

        assertEquals(Verification.VALID, verifier.verifying(signed))
    }

    @Test
    fun aTamperedPayloadFailsVerification() {
        val keys = KeyManager(context)
        val identity = KeyManagerIdentity(keys)
        val directory = PublicKeyDirectory().apply { record(keys.signingPublicKey) }
        val verifier = DirectoryPacketVerifier(directory)

        val signed = identity.signing(packetFrom(keys.peerId))
        val tampered = signed.copy(payload = "tampered".toByteArray())

        assertEquals(Verification.INVALID, verifier.verifying(tampered))
    }

    @Test
    fun aSignatureSurvivesAHop() {
        val keys = KeyManager(context)
        val identity = KeyManagerIdentity(keys)
        val directory = PublicKeyDirectory().apply { record(keys.signingPublicKey) }
        val verifier = DirectoryPacketVerifier(directory)

        val signed = identity.signing(packetFrom(keys.peerId))
        val relayed = signed.decrementTtl()!!

        // The reason ttl is outside the signed region. If this fails, nothing
        // beyond a direct neighbour can ever be authenticated.
        assertEquals(Verification.VALID, verifier.verifying(relayed))
    }

    @Test
    fun aSignatureSurvivesAnEncodeDecodeRoundTrip() {
        val keys = KeyManager(context)
        val identity = KeyManagerIdentity(keys)
        val directory = PublicKeyDirectory().apply { record(keys.signingPublicKey) }
        val verifier = DirectoryPacketVerifier(directory)

        val signed = identity.signing(packetFrom(keys.peerId))
        val throughTheWire = PacketCodec.decode(PacketCodec.encode(signed))

        assertEquals(Verification.VALID, verifier.verifying(throughTheWire))
    }

    @Test
    fun anUnknownSenderIsNotReportedAsForged() {
        val keys = KeyManager(context)
        val identity = KeyManagerIdentity(keys)
        val verifier = DirectoryPacketVerifier(PublicKeyDirectory())

        val signed = identity.signing(packetFrom(keys.peerId))

        assertEquals(Verification.UNKNOWN_SENDER, verifier.verifying(signed))
    }

    @Test
    fun theDirectoryFilesAKeyUnderTheIdItCommitsTo() {
        val keys = KeyManager(context)
        val directory = PublicKeyDirectory()

        val filed = directory.record(keys.signingPublicKey)

        assertArrayEquals(keys.peerId, filed)
        assertTrue(directory.knows(keys.peerId))
        assertArrayEquals(keys.signingPublicKey, directory.keyFor(keys.peerId))
    }

    @Test
    fun theDirectoryKeepsTheFirstKeyItWasGiven() {
        val directory = PublicKeyDirectory()
        val original = KeyManager(context).signingPublicKey
        directory.record(original)

        clearStoredKeys()
        val impostor = KeyManager(context).signingPublicKey
        directory.record(impostor)

        // Different keys give different ids, so both are held — the point is
        // that neither displaced the other.
        assertArrayEquals(original, directory.keyFor(peerIdFor(original)))
        assertFalse(peerIdFor(original).contentEquals(peerIdFor(impostor)))
    }

    private fun packetFrom(senderId: ByteArray) = Packet(
        type = PacketType.MESSAGE,
        messageId = ByteArray(Packet.MESSAGE_ID_SIZE) { it.toByte() },
        senderId = senderId,
        recipientId = Packet.BROADCAST_ID,
        ttl = Packet.DEFAULT_TTL,
        timestamp = 1_700_000_000_000L,
        payload = "hello".toByteArray(),
        signature = ByteArray(Packet.SIGNATURE_SIZE),
    )
}
