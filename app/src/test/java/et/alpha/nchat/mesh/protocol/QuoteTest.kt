package et.alpha.nchat.mesh.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuoteTest {

    @Test
    fun `a composed reply parses back into quote and reply`() {
        val body = Quote.compose("meet me at the north gate", "on my way")
        assertEquals("> meet me at the north gate\non my way", body)
        assertEquals(Quote.Parsed("meet me at the north gate", "on my way"), Quote.parse(body))
    }

    @Test
    fun `a reply survives the packet round trip`() {
        val body = Quote.compose("where are you?", "by the well")
        val packet = MessagePacket.build(
            messageId = "00112233445566778899aabbccddeeff",
            senderId = ByteArray(Packet.PEER_ID_SIZE) { 1 },
            recipientHexId = null,
            body = body,
            timestamp = 1_700_000_000_000,
        )
        val decoded = PacketCodec.decode(PacketCodec.encode(packet))
        assertEquals(Quote.Parsed("where are you?", "by the well"), Quote.parse(MessagePacket.bodyOf(decoded)))
    }

    @Test
    fun `ordinary text is not a reply`() {
        assertNull(Quote.parse("hello"))
        assertNull(Quote.parse("a > b\nc"))
        // Someone typing a single quoted line, with nothing under it.
        assertNull(Quote.parse("> just this"))
        assertNull(Quote.parse("> \nno quote"))
        assertNull(Quote.parse("> quote\n   "))
    }

    @Test
    fun `reply text drops the quote and leaves other text alone`() {
        assertEquals("on my way", Quote.replyText("> north gate\non my way"))
        assertEquals("plain", Quote.replyText("plain"))
    }

    @Test
    fun `line breaks in the quoted message fold into one line`() {
        val body = Quote.compose("first\nsecond\t third", "ok")
        assertEquals(Quote.Parsed("first second third", "ok"), Quote.parse(body))
    }

    @Test
    fun `a long message is cut to the snippet budget with an ellipsis`() {
        val snippet = Quote.snippetOf("a".repeat(500))
        assertTrue(MessagePacket.bodyByteCount(snippet) <= Quote.MAX_SNIPPET_BYTES)
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `the cut never splits an emoji`() {
        // Four bytes each, so the budget runs out mid-sequence.
        val snippet = Quote.snippetOf("😀".repeat(40))
        assertTrue(MessagePacket.bodyByteCount(snippet) <= Quote.MAX_SNIPPET_BYTES)
        assertEquals(snippet, MessagePacket.strictUtf8(snippet.toByteArray()))
        assertEquals(0, snippet.removeSuffix("…").length % 2)
    }

    @Test
    fun `an image without a caption is quoted as Image`() {
        assertEquals(Quote.IMAGE_SNIPPET, Quote.snippetOf(""))
    }

    @Test
    fun `replying to a reply quotes only its answer`() {
        val first = Quote.compose("north gate?", "on my way")
        val second = Quote.compose(first, "great")
        assertEquals(Quote.Parsed("on my way", "great"), Quote.parse(second))
    }

    @Test
    fun `a short reply to a short message still fits one packet`() {
        assertTrue(MessagePacket.fits(Quote.compose("where?", "here")))
    }
}
