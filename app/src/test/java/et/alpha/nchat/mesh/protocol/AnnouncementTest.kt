package et.alpha.nchat.mesh.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AnnouncementTest {

    private val signing = ByteArray(32) { it.toByte() }
    private val agreement = ByteArray(32) { (it + 100).toByte() }

    @Test
    fun `an announcement round-trips through its payload`() {
        val parsed = Announcement.parse(Announcement(signing, agreement).encode())!!

        assertArrayEquals(signing, parsed.signingKey)
        assertArrayEquals(agreement, parsed.agreementKey)
    }

    @Test
    fun `an announcement fits the payload budget`() {
        val encoded = Announcement(signing, agreement).encode()

        assertEquals(Announcement.PAYLOAD_SIZE, encoded.size)
        // The check that matters: two 32-byte keys have to survive inside the
        // 71 bytes left after the header and signature are paid for.
        assertEquals(64, encoded.size)
        assertEquals(true, encoded.size <= Packet.MAX_PAYLOAD_SIZE)
    }

    @Test
    fun `an announcement commits to the id derived from its signing key`() {
        assertArrayEquals(peerIdFor(signing), Announcement(signing, agreement).peerId)
    }

    @Test
    fun `a payload of the wrong length is not an announcement`() {
        // Null rather than throwing: these bytes come from an unauthenticated
        // stranger, so malformed input is routine, not a programming error.
        assertNull(Announcement.parse(ByteArray(63)))
        assertNull(Announcement.parse(ByteArray(65)))
        assertNull(Announcement.parse(ByteArray(0)))
    }

    @Test
    fun `a key of the wrong size is refused at construction`() {
        assertThrows<IllegalArgumentException> { Announcement(ByteArray(31), agreement) }
        assertThrows<IllegalArgumentException> { Announcement(signing, ByteArray(33)) }
    }

    @Test
    fun `a pairing uri round-trips`() {
        val announcement = Announcement(signing, agreement)

        val parsed = parsePairingUri(announcement.toPairingUri())!!

        assertArrayEquals(signing, parsed.signingKey)
        assertArrayEquals(agreement, parsed.agreementKey)
    }

    @Test
    fun `a pairing uri is tagged with scheme and version`() {
        val uri = Announcement(signing, agreement).toPairingUri()

        assertEquals(true, uri.startsWith("nchat:1:"))
        // Base64url of 64 bytes without padding: ceil(64 * 8 / 6) = 86 chars.
        // Short enough that the QR stays a low version and scans quickly.
        assertEquals(86, uri.substringAfterLast(':').length)
    }

    @Test
    fun `foreign or malformed qr text is not a pairing code`() {
        assertNull(parsePairingUri("https://example.com"))
        assertNull(parsePairingUri("nchat:2:AAAA"), "a future version must not be misparsed")
        assertNull(parsePairingUri("nchat:1:not-valid-base64!!"))
        assertNull(parsePairingUri("nchat:1:${"A".repeat(4)}"), "right shape, wrong length")
        assertNull(parsePairingUri(""))
    }

    @Test
    fun `surrounding whitespace does not stop a code being read`() {
        val uri = Announcement(signing, agreement).toPairingUri()

        assertArrayEquals(signing, parsePairingUri("  $uri\n")!!.signingKey)
    }

    @Test
    fun `an announce packet is a broadcast from the announced id`() {
        val announcement = Announcement(signing, agreement)

        val packet = announcementPacket(
            messageId = ByteArray(Packet.MESSAGE_ID_SIZE),
            announcement = announcement,
            timestamp = 1_700_000_000_000L,
        )

        assertEquals(PacketType.ANNOUNCE, packet.type)
        assertEquals(true, packet.isBroadcast, "an introduction is for whoever can hear it")
        assertArrayEquals(announcement.peerId, packet.senderId)
        assertEquals(true, packet.encodedSize <= Packet.MAX_PACKET_SIZE)
    }
}
