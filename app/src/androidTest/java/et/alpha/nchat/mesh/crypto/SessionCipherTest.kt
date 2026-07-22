package et.alpha.nchat.mesh.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import et.alpha.nchat.mesh.protocol.MessagePacket
import et.alpha.nchat.mesh.protocol.Packet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two devices agreeing a key and exchanging a sealed message.
 *
 * Instrumented because it needs real Keystore-backed [KeyManager]s. "Alice" and
 * "Bob" are two KeyManagers built from separate preference files, which is as
 * close to two phones as a single process gets.
 */
@RunWith(AndroidJUnit4::class)
class SessionCipherTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var alice: KeyManager
    private lateinit var bob: KeyManager

    /** Alice's view of the world, with Bob in it. */
    private lateinit var aliceCipher: SessionCipher

    /** Bob's view, with Alice in it. */
    private lateinit var bobCipher: SessionCipher

    @Before
    fun setUpTwoDevices() {
        context.getSharedPreferences("mesh_keys", Context.MODE_PRIVATE).edit().clear().commit()
        alice = KeyManager(context)

        // A second identity needs a second store; clearing and regenerating is
        // the only way to get one from a single KeyManager implementation.
        context.getSharedPreferences("mesh_keys", Context.MODE_PRIVATE).edit().clear().commit()
        bob = KeyManager(context)

        val aliceDirectory = PublicKeyDirectory().apply { record(KeyManagerIdentity(bob).announcement) }
        val bobDirectory = PublicKeyDirectory().apply { record(KeyManagerIdentity(alice).announcement) }

        aliceCipher = SessionCipher(alice, aliceDirectory)
        bobCipher = SessionCipher(bob, bobDirectory)
    }

    @Test
    fun bobOpensWhatAliceSealed() {
        val messageId = messageId()
        val plaintext = "meet me at the north gate".toByteArray()

        val sealed = aliceCipher.encrypt(bob.peerId, messageId, plaintext)!!
        val opened = bobCipher.decrypt(alice.peerId, messageId, sealed)

        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun theCiphertextIsNotThePlaintext() {
        val plaintext = "meet me at the north gate".toByteArray()

        val sealed = aliceCipher.encrypt(bob.peerId, messageId(), plaintext)!!

        assertFalse(
            "the payload must not be readable off the wire",
            sealed.toList().windowed(plaintext.size).any { it == plaintext.toList() },
        )
    }

    @Test
    fun sealingCostsExactlyTheTag() {
        val plaintext = ByteArray(MessagePacket.MAX_BODY_BYTES)

        val sealed = aliceCipher.encrypt(bob.peerId, messageId(), plaintext)!!

        // The whole reason the nonce is taken from the messageId: only the tag
        // is charged, so a longest message still fits the packet.
        assertEquals(plaintext.size + SessionCipher.TAG_SIZE, sealed.size)
        assertEquals(Packet.MAX_PAYLOAD_SIZE, sealed.size)
    }

    @Test
    fun aLongestMessageStillFitsAPacket() {
        val body = "x".repeat(MessagePacket.MAX_BODY_BYTES)
        val packet = MessagePacket.build(
            messageId = "00112233445566778899aabbccddeeff",
            senderId = alice.peerId,
            recipientHexId = bob.peerId.joinToString("") { "%02x".format(it) },
            body = body,
            timestamp = 1_700_000_000_000L,
        )

        val sealed = aliceCipher.encrypt(bob.peerId, packet.messageId, packet.payload)!!
        val encrypted = packet.copy(payload = sealed)

        assertEquals(Packet.MAX_PACKET_SIZE, encrypted.encodedSize)
    }

    @Test
    fun aTamperedCiphertextDoesNotOpen() {
        val messageId = messageId()
        val sealed = aliceCipher.encrypt(bob.peerId, messageId, "hello".toByteArray())!!
        val tampered = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

        // GCM authenticates as well as encrypts, so a flipped bit is a failed
        // tag check rather than garbled plaintext.
        assertNull(bobCipher.decrypt(alice.peerId, messageId, tampered))
    }

    @Test
    fun theWrongMessageIdDoesNotOpen() {
        val sealed = aliceCipher.encrypt(bob.peerId, messageId(), "hello".toByteArray())!!

        // The nonce is the message id, so it is effectively part of the
        // ciphertext's identity — a replay under a different id fails.
        assertNull(bobCipher.decrypt(alice.peerId, messageId(seed = 9), sealed))
    }

    @Test
    fun aStrangerCannotOpenIt() {
        context.getSharedPreferences("mesh_keys", Context.MODE_PRIVATE).edit().clear().commit()
        val eve = KeyManager(context)
        val eveCipher = SessionCipher(
            eve,
            PublicKeyDirectory().apply { record(KeyManagerIdentity(alice).announcement) },
        )

        val messageId = messageId()
        val sealed = aliceCipher.encrypt(bob.peerId, messageId, "private".toByteArray())!!

        assertNull(eveCipher.decrypt(alice.peerId, messageId, sealed))
    }

    @Test
    fun thereIsNoKeyForAPeerThatHasNotAnnounced() {
        val strangerCipher = SessionCipher(alice, PublicKeyDirectory())

        assertFalse(strangerCipher.canReach(bob.peerId))
        assertNull(strangerCipher.encrypt(bob.peerId, messageId(), "hi".toByteArray()))
    }

    @Test
    fun bothSidesDeriveTheSameKey() {
        // Proved indirectly but decisively: each can open what the other sealed.
        val one = messageId(seed = 1)
        val two = messageId(seed = 2)

        val fromAlice = aliceCipher.encrypt(bob.peerId, one, "ping".toByteArray())!!
        val fromBob = bobCipher.encrypt(alice.peerId, two, "pong".toByteArray())!!

        assertArrayEquals("ping".toByteArray(), bobCipher.decrypt(alice.peerId, one, fromAlice))
        assertArrayEquals("pong".toByteArray(), aliceCipher.decrypt(bob.peerId, two, fromBob))
    }

    @Test
    fun differentMessageIdsGiveDifferentCiphertext() {
        val plaintext = "same text".toByteArray()

        val first = aliceCipher.encrypt(bob.peerId, messageId(seed = 1), plaintext)!!
        val second = aliceCipher.encrypt(bob.peerId, messageId(seed = 2), plaintext)!!

        // If these matched, the nonce would not be varying and GCM's security
        // would be gone.
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun canReachIsTrueOnceKeysAreKnown() {
        assertTrue(aliceCipher.canReach(bob.peerId))
    }

    private fun messageId(seed: Int = 0) =
        ByteArray(Packet.MESSAGE_ID_SIZE) { (it + seed * 31).toByte() }
}
