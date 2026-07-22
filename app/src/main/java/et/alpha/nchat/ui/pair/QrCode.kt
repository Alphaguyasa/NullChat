package et.alpha.nchat.ui.pair

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [text] as a QR bitmap.
 *
 * Error correction is deliberately low: the pairing payload is 88 characters,
 * and a higher level would push the code to a denser version that is harder to
 * scan on a cracked or dim screen — which is the realistic condition for two
 * people pairing phones. The code is on screen for seconds, not printed on a
 * box, so redundancy buys little.
 */
fun qrBitmap(text: String, sizePx: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to QR_MARGIN_MODULES,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        val row = y * matrix.width
        for (x in 0 until matrix.width) {
            pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

/**
 * Reads a QR code out of a camera frame, or null if there is not one.
 *
 * Only the Y plane is used. CameraX hands over YUV_420_888, whose luma plane is
 * already the greyscale image ZXing wants, so the chroma planes are ignored
 * rather than converted — a full colour conversion per frame would cost far more
 * than the decode itself.
 */
fun ImageProxy.readQrText(): String? {
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }

    // rowStride can exceed width when the driver pads rows; PlanarYUVLuminanceSource
    // needs the stride as its data width or every row comes out sheared.
    val source = PlanarYUVLuminanceSource(
        data,
        plane.rowStride,
        height,
        0,
        0,
        minOf(width, plane.rowStride),
        height,
        false,
    )

    val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    // A frame with no code in it throws rather than returning null, and most
    // frames have no code in them. This is the normal path, not an error path.
    return runCatching {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    }.getOrNull()
}

private const val QR_MARGIN_MODULES = 1
