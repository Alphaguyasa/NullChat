package et.alpha.nchat.mesh.protocol

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
}
