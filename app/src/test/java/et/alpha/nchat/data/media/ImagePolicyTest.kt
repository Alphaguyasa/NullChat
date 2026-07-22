package et.alpha.nchat.data.media

import et.alpha.nchat.mesh.protocol.Packet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * [ImagePolicy].
 *
 * These are the numbers that decide whether image support degrades the mesh for
 * everyone else, so they are asserted against the limits they were derived from
 * — `SeenCache`'s capacity, the packet budget — rather than against themselves.
 * If someone raises the image ceiling without re-checking those, this fails.
 */
class ImagePolicyTest {

    // region derived limits

    @Test
    fun `a full-size image stays under the dedup cache capacity`() {
        // The constraint that matters most: an image needing more fragments than
        // SeenCache holds evicts its own earliest entries mid-transfer, and the
        // fragments then loop back looking new and get re-flooded.
        val fragments = ImagePolicy.fragmentCount(ImagePolicy.MAX_ENCODED_BYTES)

        assertTrue(
            fragments <= ImagePolicy.MAX_FRAGMENTS,
            "a maximum image is $fragments fragments, over the ${ImagePolicy.MAX_FRAGMENTS} cap",
        )
        // With room left for ordinary traffic, not merely under the line.
        assertTrue(fragments < 1000 / 2, "$fragments fragments leaves too little of the cache")
    }

    @Test
    fun `a full-size image stays under the forward queue capacity`() {
        // Fragments are not queued today, but staying under 500 keeps that
        // decision reversible rather than permanently ruled out.
        assertTrue(ImagePolicy.fragmentCount(ImagePolicy.MAX_ENCODED_BYTES) < 500)
    }

    @Test
    fun `payload size accounts for the envelope and the cipher tag`() {
        // 1 byte of content type, 16 of AES-GCM tag. Forgetting either would make
        // fragmentCount under-report and let an image past the ceiling.
        assertEquals(1000 + 1 + 16, ImagePolicy.payloadSize(1000))
    }

    @Test
    fun `fragment count matches what the fragmenter would actually produce`() {
        val payload = ImagePolicy.payloadSize(ImagePolicy.MAX_ENCODED_BYTES)

        // Ceiling division by the 51-byte chunk, not the 71-byte packet payload.
        assertEquals((payload + 50) / 51, ImagePolicy.fragmentCount(ImagePolicy.MAX_ENCODED_BYTES))
    }

    @Test
    fun `wire cost is roughly three and a half times the image size`() {
        // 51 bytes of chunk inside a 180-byte packet. Worth asserting because it
        // is the number that makes images expensive, and it is easy to forget.
        val cost = ImagePolicy.wireCost(ImagePolicy.MAX_ENCODED_BYTES)

        assertTrue(cost > ImagePolicy.MAX_ENCODED_BYTES * 3)
        assertTrue(cost < ImagePolicy.MAX_ENCODED_BYTES * 4)
        assertEquals(Packet.MAX_PACKET_SIZE, 180)
    }

    // endregion

    // region fits

    @ParameterizedTest
    @CsvSource(
        "1, true",
        "5000, true",
        "14336, true",
        "14337, false",
        "0, false",
        "-1, false",
        "1000000, false",
    )
    fun `fits accepts only images inside every limit`(size: Int, expected: Boolean) {
        assertEquals(expected, ImagePolicy.fits(size))
    }

    @Test
    fun `the quality ladder descends`() {
        // Each rung has to be smaller than the last, or the retry loop in
        // ImageScaler would re-encode at the same size forever.
        assertEquals(ImagePolicy.QUALITY_LADDER.sortedDescending(), ImagePolicy.QUALITY_LADDER)
        assertTrue(ImagePolicy.QUALITY_LADDER.isNotEmpty())
        assertEquals(ImagePolicy.QUALITY_LADDER.first(), ImagePolicy.DEFAULT_QUALITY)
        assertTrue(ImagePolicy.QUALITY_LADDER.all { it in 1..100 })
    }

    // endregion

    // region sample size

    @ParameterizedTest
    @CsvSource(
        // width, height, expected inSampleSize at a 320px target
        "320, 240, 1",
        "640, 480, 2",
        "639, 480, 1",
        "1280, 960, 4",
        "4000, 3000, 8",
        "240, 320, 1",
        "960, 1280, 4",
        "100, 100, 1",
    )
    fun `sample size halves without dropping below the target`(w: Int, h: Int, expected: Int) {
        assertEquals(expected, ImagePolicy.sampleSizeFor(w, h))
    }

    @Test
    fun `sampling never takes the long edge below the target`() {
        // The point of under-shrinking: sampling past the target throws away
        // detail that the exact scale step would have used.
        for (size in listOf(321, 500, 700, 1500, 4000, 8000)) {
            val sample = ImagePolicy.sampleSizeFor(size, size)
            assertTrue(
                size / sample >= ImagePolicy.MAX_DIMENSION,
                "$size sampled by $sample gives ${size / sample}, under the target",
            )
        }
    }

    @Test
    fun `rejects non-positive dimensions`() {
        assertThrows<IllegalArgumentException> { ImagePolicy.sampleSizeFor(0, 100) }
        assertThrows<IllegalArgumentException> { ImagePolicy.sampleSizeFor(100, -1) }
        assertThrows<IllegalArgumentException> { ImagePolicy.sampleSizeFor(100, 100, target = 0) }
    }

    // endregion

    // region scaled dimensions

    @ParameterizedTest
    @CsvSource(
        "640, 480, 320, 240",
        "480, 640, 240, 320",
        "1000, 1000, 320, 320",
        "3000, 2000, 320, 213",
    )
    fun `scaling caps the long edge and keeps the aspect ratio`(
        w: Int,
        h: Int,
        expectedW: Int,
        expectedH: Int,
    ) {
        assertEquals(expectedW to expectedH, ImagePolicy.scaledDimensions(w, h))
    }

    @Test
    fun `an image already small enough is left alone`() {
        // Upscaling would cost bytes and add no detail.
        assertEquals(200 to 150, ImagePolicy.scaledDimensions(200, 150))
        assertEquals(320 to 240, ImagePolicy.scaledDimensions(320, 240))
    }

    @Test
    fun `an extreme aspect ratio keeps at least one pixel`() {
        // A 4000x3 panorama scales to a height of 0.24 — which would round to
        // zero and fail to allocate a bitmap.
        val (width, height) = ImagePolicy.scaledDimensions(4000, 3)

        assertEquals(320, width)
        assertTrue(height >= 1)
    }

    @Test
    fun `every scaled result is within the dimension cap`() {
        val cases = listOf(4000 to 3000, 100 to 8000, 8000 to 100, 321 to 320, 1 to 1)

        cases.forEach { (w, h) ->
            val (sw, sh) = ImagePolicy.scaledDimensions(w, h)
            assertTrue(
                maxOf(sw, sh) <= ImagePolicy.MAX_DIMENSION,
                "${w}x$h scaled to ${sw}x$sh, over the cap",
            )
            assertTrue(sw >= 1 && sh >= 1)
        }
    }

    // endregion
}
