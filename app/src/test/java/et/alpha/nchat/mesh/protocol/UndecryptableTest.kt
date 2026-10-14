package et.alpha.nchat.mesh.protocol

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UndecryptableTest {

    @Test
    fun `well-formed text survives the strict decoder`() {
        assertEquals("hello, mesh", MessagePacket.strictUtf8("hello, mesh".toByteArray()))
        assertEquals("ሰላም \uD83D\uDE00", MessagePacket.strictUtf8("ሰላም \uD83D\uDE00".toByteArray()))
    }

    @Test
    fun `malformed utf-8 is refused rather than patched with replacement characters`() {
        assertNull(MessagePacket.strictUtf8(byteArrayOf(0xC3.toByte(), 0x28)))
        assertNull(MessagePacket.strictUtf8(byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
    }

    @Test
    fun `a plaintext fallback message is still shown`() {
        // A peer that has not heard our announcement yet sends in the clear.
        assertEquals("hi", MessagePacket.undecryptedBody("hi".toByteArray()))
    }

    @Test
    fun `ciphertext that would not open is never shown as text`() {
        // Ciphertext looks like random bytes; across many samples, none may leak
        // through as text. Seeded so a failure is reproducible.
        val random = Random(42)
        repeat(1_000) {
            val ciphertext = random.nextBytes(17 + random.nextInt(55))
            assertEquals(MessagePacket.UNDECRYPTABLE_BODY, MessagePacket.undecryptedBody(ciphertext))
        }
    }
}
