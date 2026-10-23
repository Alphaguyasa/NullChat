package et.alpha.nchat.mesh

import et.alpha.nchat.data.media.ImagePolicy
import et.alpha.nchat.mesh.protocol.Attachment
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
    fun `long text is not held to the image limit`() {
        // Text is bounded by MessagePacket on the sending side and the
        // reassembler on this one; this check is about images only.
        assertTrue(acceptsInbound(Attachment.text("a".repeat(ImagePolicy.MAX_ENCODED_BYTES + 1))))
    }
}
