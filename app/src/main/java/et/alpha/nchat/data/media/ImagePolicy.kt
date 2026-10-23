package et.alpha.nchat.data.media

import et.alpha.nchat.mesh.protocol.Attachment
import et.alpha.nchat.mesh.protocol.Fragmenter
import et.alpha.nchat.mesh.protocol.MessagePacket

/**
 * How large an image may be before the mesh cannot carry it.
 *
 * Pure Kotlin and no Android types, so the arithmetic that decides whether a
 * photo is sendable is unit-testable on the JVM — the Bitmap work that uses it
 * lives in [ImageScaler] and can only be exercised on a device.
 *
 * ## Where these numbers come from
 *
 * They are not taste. Each one is derived from a limit that already exists
 * elsewhere in the app, and an image that breaks any of them degrades the mesh
 * for text traffic too:
 *
 * - A fragment carries [Fragmenter.DEFAULT_CHUNK_SIZE] = 51 bytes inside a
 *   180-byte packet, so the wire cost of an image is ~3.5x its own size.
 * - `SeenCache` holds 1000 message ids for 10 minutes. An image needing more
 *   fragments than that evicts its own earliest entries mid-transfer, at which
 *   point a fragment looping back looks new and gets re-flooded. [MAX_FRAGMENTS]
 *   is set well under 1000 so ordinary text traffic still has room.
 * - `ForwardQueue` caps at 500 packets. Images are deliberately **not**
 *   store-and-forwarded — `MeshRouter.enqueueForLater` only queues MESSAGE — but
 *   staying under the cap keeps that decision reversible.
 * - The reassembly window is 60 seconds. At the 1-10 KB/s Nearby manages over
 *   Bluetooth-only, [MAX_ENCODED_BYTES] is what can realistically land inside it.
 *
 * The visible consequence is that images are thumbnails. A full-size photo needs
 * a direct-link bulk transfer that bypasses flooding entirely; it is not
 * something the flooding mesh can deliver.
 */
object ImagePolicy {

    /** Longest edge of a sent image, in pixels. The short edge follows the aspect ratio. */
    const val MAX_DIMENSION: Int = 320

    /**
     * JPEG quality levels tried in order until one fits [MAX_ENCODED_BYTES].
     *
     * A ladder rather than a single setting because compressibility varies
     * enormously with content: a photo of the sky hits the ceiling at quality 40
     * where a page of text does not. Re-encoding at a lower quality is cheap
     * compared to failing to send.
     */
    val QUALITY_LADDER: List<Int> = listOf(40, 30, 20)

    /** Where the ladder starts, and the quality a typical image is sent at. */
    val DEFAULT_QUALITY: Int get() = QUALITY_LADDER.first()

    /**
     * Ceiling on the JPEG bytes, before the envelope and the cipher tag.
     *
     * 14 KiB, chosen so a fully-loaded image lands at [MAX_FRAGMENTS] with the
     * envelope and AES-GCM tag paid for.
     */
    const val MAX_ENCODED_BYTES: Int = 14 * 1024

    /**
     * Fragments the largest permitted image occupies.
     *
     * Kept comfortably under `SeenCache`'s 1000-entry capacity so a transfer
     * cannot evict its own dedup entries while it is still running.
     */
    const val MAX_FRAGMENTS: Int = 300

    /** Bytes an image of [encodedSize] occupies once wrapped and encrypted. */
    fun payloadSize(encodedSize: Int): Int =
        encodedSize + Attachment.HEADER_SIZE + MessagePacket.CIPHER_OVERHEAD_BYTES

    /** How many FRAGMENT packets an image of [encodedSize] will need. */
    fun fragmentCount(encodedSize: Int): Int =
        Fragmenter.fragmentCount(payloadSize(encodedSize))

    /** Total bytes on the wire for one hop, including packet headers and signatures. */
    fun wireCost(encodedSize: Int): Int = fragmentCount(encodedSize) * 180

    /**
     * Longest edge a received image is decoded to, exclusive.
     *
     * Our own build never sends more than [MAX_DIMENSION], but the header of a
     * 14 KB JPEG may claim 30000 x 30000, and decoding that at full size is a
     * gigabyte of bitmap: a stranger's broadcast could crash the Everyone thread
     * every time it scrolled into view. A bubble is at most about 720 px wide, so
     * nothing visible is lost.
     */
    const val MAX_DECODED_DIMENSION: Int = 1024

    /**
     * The `inSampleSize` for showing a received [width] x [height] image, or
     * null when the header's dimensions are not usable.
     *
     * Samples toward half of [MAX_DECODED_DIMENSION], since [sampleSizeFor] stops
     * at the last halving that stays at or above its target: the result's long
     * edge is then always under [MAX_DECODED_DIMENSION]. An image we sent
     * ourselves is decoded at sample 1.
     */
    fun displaySampleSize(width: Int, height: Int): Int? =
        if (width <= 0 || height <= 0) null else sampleSizeFor(width, height, MAX_DECODED_DIMENSION / 2)

    /** Whether an image of [encodedSize] is within every limit above. */
    fun fits(encodedSize: Int): Boolean =
        encodedSize in 1..MAX_ENCODED_BYTES && fragmentCount(encodedSize) <= MAX_FRAGMENTS

    /**
     * The power-of-two `inSampleSize` that gets [width] x [height] closest to
     * [MAX_DIMENSION] without going under it.
     *
     * BitmapFactory only honours powers of two and rounds down to one, so this
     * deliberately under-shrinks: sampling to 400px and then scaling exactly to
     * 320 keeps more detail than sampling straight down to 200. Decoding at a
     * sample size at all is what keeps a 12-megapixel camera photo from being
     * fully decoded into memory before it is shrunk.
     */
    fun sampleSizeFor(width: Int, height: Int, target: Int = MAX_DIMENSION): Int {
        require(width > 0 && height > 0) { "image dimensions must be positive" }
        require(target > 0) { "target must be positive, was $target" }

        var sample = 1
        // Halve while the *next* halving would still leave the long edge at or
        // above the target.
        while (maxOf(width, height) / (sample * 2) >= target) sample *= 2
        return sample
    }

    /**
     * The dimensions [width] x [height] becomes when its long edge is capped at
     * [MAX_DIMENSION]. An image already smaller is returned untouched rather than
     * upscaled — enlarging a small image would cost bytes and add no detail.
     */
    fun scaledDimensions(
        width: Int,
        height: Int,
        target: Int = MAX_DIMENSION,
    ): Pair<Int, Int> {
        require(width > 0 && height > 0) { "image dimensions must be positive" }

        val longest = maxOf(width, height)
        if (longest <= target) return width to height

        val ratio = target.toDouble() / longest
        // At least one pixel: a 4000x3 panorama would otherwise scale to zero
        // height and fail to allocate.
        return maxOf(1, Math.round(width * ratio).toInt()) to
            maxOf(1, Math.round(height * ratio).toInt())
    }
}
