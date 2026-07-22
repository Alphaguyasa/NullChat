package et.alpha.nchat.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A picked image, downscaled and compressed to something the mesh can carry. */
data class ScaledImage(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val quality: Int,
) {
    /** Fragments this image will occupy. */
    val fragmentCount: Int get() = ImagePolicy.fragmentCount(jpeg.size)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScaledImage) return false
        return width == other.width && height == other.height &&
            quality == other.quality && jpeg.contentEquals(other.jpeg)
    }

    override fun hashCode(): Int {
        var result = jpeg.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + quality
        return result
    }

    override fun toString(): String =
        "ScaledImage(${width}x$height, q$quality, ${jpeg.size} bytes, $fragmentCount fragments)"
}

/**
 * Turns a gallery pick into a JPEG small enough to survive the mesh.
 *
 * The policy — how small, at what quality — is [ImagePolicy], which is pure
 * Kotlin and unit-tested. This class is only the Android half: `BitmapFactory`,
 * `Bitmap.compress` and EXIF, none of which run on the JVM, so it is verified on
 * a device rather than in the JVM suite.
 *
 * Decoding is two-pass on purpose. The first pass reads the header alone
 * (`inJustDecodeBounds`) to learn the dimensions, so the second can decode at an
 * `inSampleSize` that never materialises a 12-megapixel bitmap in memory — that
 * allocation, not the mesh, is what would kill the app on a mid-range phone.
 */
@Singleton
class ImageScaler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Reads [uri], downscales it, and compresses until it fits.
     *
     * @return null if the image could not be read or could not be squeezed under
     *   [ImagePolicy.MAX_ENCODED_BYTES] even at the bottom of the quality ladder.
     */
    suspend fun scale(uri: Uri): ScaledImage? = withContext(Dispatchers.IO) {
        val bounds = readBounds(uri) ?: return@withContext null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "picked image has no usable dimensions")
            return@withContext null
        }

        val sampled = decodeSampled(uri, bounds) ?: return@withContext null
        val rotated = applyOrientation(uri, sampled)
        val scaled = resize(rotated)

        compress(scaled).also {
            // Recycled explicitly rather than left to the GC: these are the two
            // largest allocations in the app and a picker used repeatedly will
            // OOM long before a finalizer runs.
            if (scaled !== rotated) scaled.recycle()
            if (rotated !== sampled) rotated.recycle()
            sampled.recycle()
        }
    }

    /**
     * Reads the image header for its dimensions, without decoding the pixels.
     *
     * `decodeStream` returns **null on purpose** here: under `inJustDecodeBounds`
     * it populates [BitmapFactory.Options] and deliberately allocates no bitmap.
     * So its result must be discarded rather than null-checked — treating that
     * null as a failure rejects every image ever picked, which is precisely the
     * bug this comment exists to stop coming back.
     *
     * The only real failure here is a [Uri] that will not open at all.
     */
    private fun readBounds(uri: Uri): BitmapFactory.Options? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("content resolver would not open the picked image")
        stream.use { BitmapFactory.decodeStream(it, null, options) }
        options
    }.onFailure { Log.w(TAG, "could not read image bounds: ${it.message}") }.getOrNull()

    private fun decodeSampled(uri: Uri, bounds: BitmapFactory.Options): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImagePolicy.sampleSizeFor(bounds.outWidth, bounds.outHeight)
            // 565 halves the decode buffer and costs nothing visible at 320px
            // after JPEG has quantised the colour anyway.
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("content resolver would not open the picked image")
        // Here a null result *is* a failure: without inJustDecodeBounds a
        // successful decode always returns a bitmap.
        stream.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("image data could not be decoded")
    }.onFailure { Log.w(TAG, "could not decode image: ${it.message}") }.getOrNull()

    /**
     * Applies the EXIF rotation the camera recorded.
     *
     * Without this a photo taken in portrait arrives sideways, because the
     * sensor wrote it in landscape and left a rotation tag rather than rotating
     * the pixels. The tag does not survive re-compression, so the rotation has
     * to be baked in here.
     */
    private fun applyOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        if (degrees == 0f) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun resize(bitmap: Bitmap): Bitmap {
        val (width, height) = ImagePolicy.scaledDimensions(bitmap.width, bitmap.height)
        if (width == bitmap.width && height == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /**
     * Compresses down the quality ladder until the result fits.
     *
     * Returns the first encoding under the ceiling rather than the smallest one,
     * so an image that fits at quality 40 is never needlessly degraded to 20.
     */
    private fun compress(bitmap: Bitmap): ScaledImage? {
        for (quality in ImagePolicy.QUALITY_LADDER) {
            val stream = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                Log.w(TAG, "JPEG compression failed at quality $quality")
                return null
            }
            val bytes = stream.toByteArray()
            if (ImagePolicy.fits(bytes.size)) {
                Log.i(
                    TAG,
                    "scaled to ${bitmap.width}x${bitmap.height} q$quality " +
                        "${bytes.size}B in ${ImagePolicy.fragmentCount(bytes.size)} fragments",
                )
                return ScaledImage(bytes, bitmap.width, bitmap.height, quality)
            }
        }
        // Everything on the ladder was still too big. Rare at 320px, but a
        // high-noise photo can defeat JPEG entirely, and sending something the
        // receiver can never reassemble is worse than refusing.
        Log.w(TAG, "image would not fit under ${ImagePolicy.MAX_ENCODED_BYTES} bytes")
        return null
    }

    private companion object {
        const val TAG = "ImageScaler"
    }
}
