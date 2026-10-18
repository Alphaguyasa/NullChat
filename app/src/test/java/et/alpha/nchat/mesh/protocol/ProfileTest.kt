package et.alpha.nchat.mesh.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfileTest {

    @Test
    fun `a profile round-trips through its payload`() {
        val profile = Profile.fromInput("Maya")!!

        assertEquals("Maya", Profile.parse(profile.encode())!!.displayName)
    }

    @Test
    fun `a profile round-trips through a whole packet`() {
        val sender = ByteArray(Packet.PEER_ID_SIZE) { (it + 1).toByte() }
        val packet = profilePacket(
            messageId = ByteArray(Packet.MESSAGE_ID_SIZE) { 7 },
            senderId = sender,
            profile = Profile.fromInput("ሰላም Abebe")!!,
            timestamp = 1_700_000_000_000L,
        )

        val decoded = PacketCodec.decode(PacketCodec.encode(packet))

        assertEquals(PacketType.PROFILE, decoded.type)
        assertTrue(decoded.isBroadcast)
        assertArrayEquals(sender, decoded.senderId)
        assertEquals("ሰላም Abebe", Profile.parse(decoded.payload)!!.displayName)
    }

    @Test
    fun `the payload starts with the format byte`() {
        assertEquals(Profile.FORMAT.toByte(), Profile.fromInput("a")!!.encode()[0])
    }

    @Test
    fun `the longest name fits a single packet`() {
        val longest = Profile.fromInput("x".repeat(500))!!.encode()

        assertEquals(Profile.MAX_PAYLOAD_SIZE, longest.size)
        assertTrue(longest.size <= Packet.MAX_PAYLOAD_SIZE)
    }

    @Test
    fun `whitespace is trimmed and collapsed`() {
        assertEquals("Maya Tesfaye", Profile.normalize("  Maya \t\n  Tesfaye  "))
    }

    @Test
    fun `invisible characters are removed`() {
        // U+202E right-to-left override, U+200B zero-width space, a NUL and a BEL.
        assertEquals("Maya", Profile.normalize("‮Ma​ya\u0000\u0007"))
    }

    @Test
    fun `nothing showable means no profile`() {
        assertNull(Profile.fromInput(""))
        assertNull(Profile.fromInput("   "))
        assertNull(Profile.fromInput("​‮"))
    }

    @Test
    fun `a long name is cut on a character boundary`() {
        // Ge'ez letters are three bytes each: 48 bytes hold exactly 16 of them.
        val name = Profile.normalize("ሀ".repeat(20))

        assertEquals(16, name.length)
        assertEquals(Profile.MAX_NAME_BYTES, name.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `a cut never splits a surrogate pair`() {
        // 47 ASCII bytes, then a four-byte emoji that does not fit.
        val name = Profile.normalize("a".repeat(47) + "😀")

        assertEquals("a".repeat(47), name)
    }

    @Test
    fun `a cut never leaves a trailing space`() {
        val name = Profile.normalize("a".repeat(47) + " bcd")

        assertEquals("a".repeat(47), name)
    }

    @Test
    fun `a name not in normal form is refused`() {
        assertNull(Profile.parse(payload(" Maya")))
        assertNull(Profile.parse(payload("Ma‮ya")))
        assertNull(Profile.parse(payload("Maya  Tesfaye")))
    }

    @Test
    fun `malformed payloads are refused`() {
        assertNull(Profile.parse(ByteArray(0)))
        assertNull(Profile.parse(byteArrayOf(Profile.FORMAT.toByte())))
        // A format this build does not know.
        assertNull(Profile.parse(byteArrayOf(2, 'a'.code.toByte())))
        // Not UTF-8.
        assertNull(Profile.parse(byteArrayOf(Profile.FORMAT.toByte(), 0xC3.toByte())))
        // Longer than any name this build would send.
        assertNull(Profile.parse(payload("a".repeat(Profile.MAX_NAME_BYTES + 1))))
    }

    private fun payload(name: String): ByteArray =
        byteArrayOf(Profile.FORMAT.toByte()) + name.toByteArray(Charsets.UTF_8)
}
