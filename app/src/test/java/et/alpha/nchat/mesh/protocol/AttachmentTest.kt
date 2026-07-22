package et.alpha.nchat.mesh.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

/** The one-byte envelope that tells an image from text inside a fragmented message. */
class AttachmentTest {

    @ParameterizedTest
    @EnumSource(ContentType::class)
    fun `round-trips every content type`(type: ContentType) {
        val original = Attachment(type, ByteArray(64) { it.toByte() })

        val parsed = Attachment.parse(original.encode())

        assertEquals(type, parsed?.contentType)
        assertArrayEquals(original.data, parsed?.data)
    }

    @Test
    fun `round-trips text`() {
        val attachment = Attachment.text("meet me at the north gate 🚪")

        val parsed = Attachment.parse(attachment.encode())

        assertEquals(ContentType.TEXT_UTF8, parsed?.contentType)
        assertEquals("meet me at the north gate 🚪", parsed?.asText())
    }

    @Test
    fun `round-trips a jpeg`() {
        // JPEG SOI marker and some payload, to prove nothing reinterprets the bytes.
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(500) { 7 }
        val attachment = Attachment.jpeg(jpeg)

        val parsed = Attachment.parse(attachment.encode())

        assertEquals(ContentType.IMAGE_JPEG, parsed?.contentType)
        assertArrayEquals(jpeg, parsed?.data)
    }

    @Test
    fun `costs exactly one byte over the data`() {
        // Every byte here is a byte not carrying image, at 51 bytes per fragment.
        assertEquals(1, Attachment.HEADER_SIZE)
        assertEquals(501, Attachment.jpeg(ByteArray(500)).encode().size)
    }

    @Test
    fun `round-trips an empty payload`() {
        val parsed = Attachment.parse(Attachment(ContentType.IMAGE_JPEG, ByteArray(0)).encode())

        assertEquals(ContentType.IMAGE_JPEG, parsed?.contentType)
        assertEquals(0, parsed?.data?.size)
    }

    @Test
    fun `rejects an empty buffer`() {
        assertNull(Attachment.parse(ByteArray(0)))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 3, 4, 99, 255])
    fun `rejects an unknown content type rather than crashing`(wire: Int) {
        // A newer build sending a kind this one cannot render must not take it
        // down — the payload is simply dropped.
        assertNull(Attachment.parse(byteArrayOf(wire.toByte()) + ByteArray(10)))
    }

    @Test
    fun `content types map to their wire values`() {
        assertEquals(1, ContentType.TEXT_UTF8.wire)
        assertEquals(2, ContentType.IMAGE_JPEG.wire)
        assertEquals(ContentType.TEXT_UTF8, ContentType.fromWire(1))
        assertEquals(ContentType.IMAGE_JPEG, ContentType.fromWire(2))
        assertNull(ContentType.fromWire(0))
    }

    @Test
    fun `an image survives the whole fragment round trip`() {
        // The end-to-end path: envelope, split, shuffle, reassemble, unwrap.
        val jpeg = ByteArray(14 * 1024) { (it % 251).toByte() }
        val messageId = ByteArray(Packet.MESSAGE_ID_SIZE) { (0x40 + it).toByte() }
        val senderId = ByteArray(Packet.PEER_ID_SIZE) { (0x11 + it).toByte() }

        val fragments = Fragmenter.fragment(
            messageId = messageId,
            senderId = senderId,
            recipientId = Packet.BROADCAST_ID,
            payload = Attachment.jpeg(jpeg).encode(),
            timestamp = 1_700_000_000_000L,
        ).shuffled(kotlin.random.Random(20260722))

        val reassembler = FragmentReassembler()
        val message = fragments.firstNotNullOfOrNull { reassembler.offer(it) }

        val attachment = Attachment.parse(message!!.payload)
        assertEquals(ContentType.IMAGE_JPEG, attachment?.contentType)
        assertArrayEquals(jpeg, attachment?.data)
    }
}
