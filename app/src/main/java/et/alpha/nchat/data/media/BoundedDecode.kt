package et.alpha.nchat.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decodes a stored image for display, never larger than
 * [ImagePolicy.MAX_DECODED_DIMENSION] on its long edge.
 *
 * Two passes, because the file may have come from anyone in range: the first
 * reads only the header, so a JPEG that claims huge dimensions costs nothing,
 * and the second decodes at the sample size the header allows. A plain
 * `decodeFile` would trust the header and allocate whatever it asked for.
 *
 * @return null when the file is missing, not an image, or claims no size.
 */
fun decodeBoundedImage(path: String): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val sample = ImagePolicy.displaySampleSize(bounds.outWidth, bounds.outHeight) ?: return null
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
