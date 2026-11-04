package et.alpha.nchat.mesh

import et.alpha.nchat.data.media.ImagePolicy
import et.alpha.nchat.mesh.protocol.Attachment
import et.alpha.nchat.mesh.protocol.MessagePacket
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InboundImageTest {

    @Test
    fun `an image over the send limit is refused`() {
        // The bug: the reassembler allows 64 KB, and the image was stored and
        // decoded whatever its size.
        val oversized = Attachment.jpeg(ByteArray(ImagePolicy.MAX_ENCODED_BYTES + 1))

        assertFalse(acceptsInbound(oversized))
    }

    @Test
    fun `the largest image our own build sends is accepted`() {
        assertTrue(acceptsInbound(Attachment.jpeg(ByteArray(ImagePolicy.MAX_ENCODED_BYTES))))
    }

    @Test
    fun `an empty image is refused`() {
        assertFalse(acceptsInbound(Attachment.jpeg(ByteArray(0))))
    }

    @Test
    fun `the longest text a sender may send is kept`() {
        assertTrue(acceptsInbound(Attachment.text("a".repeat(MessagePacket.MAX_TEXT_BYTES))))
        assertTrue(acceptsInbound(Attachment.text("a".repeat(MAX_INBOUND_TEXT_BYTES))))
    }

    @Test
    fun `text far past the send limit is dropped`() {
        // The reassembler alone let 64 KB through; 2000 such rows in the
        // Everyone thread is about 128 MB loaded at once (roadmap #69).
        assertFalse(acceptsInbound(Attachment.text("a".repeat(MAX_INBOUND_TEXT_BYTES + 1))))
        assertFalse(acceptsInbound(Attachment.text("a".repeat(ImagePolicy.MAX_ENCODED_BYTES + 1))))
    }
}
