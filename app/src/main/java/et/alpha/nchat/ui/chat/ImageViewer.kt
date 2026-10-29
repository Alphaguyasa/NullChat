package et.alpha.nchat.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import et.alpha.nchat.data.media.decodeBoundedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pinch-zoom bounds for [ImageViewer], kept free of Compose so they run as a
 * plain JVM test.
 */
internal object ImageZoom {

    const val MIN_SCALE = 1f

    /**
     * Images are at most 14 KB of JPEG, a few hundred pixels across. Past 4x a
     * phone screen shows single pixels as blocks, which reads as broken rather
     * than as detail.
     */
    const val MAX_SCALE = 4f

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /**
     * Keeps a zoomed picture covering the spot it was scaled up from: at
     * [scale] the image overhangs [size] by `(scale - 1) * size / 2` on each
     * side, and panning further would drag blank space into view. At 1x there
     * is nothing to pan.
     */
    fun clampOffset(offset: Offset, scale: Float, size: IntSize): Offset {
        // Returned outright rather than clamped to ±0: coerceIn(-0f, 0f) hands
        // back -0f for a negative drag, and Offset compares its packed bits, so
        // that "zero" would not equal Offset.Zero.
        if (scale <= MIN_SCALE) return Offset.Zero
        val maxX = (scale - 1f) * size.width / 2f
        val maxY = (scale - 1f) * size.height / 2f
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }
}

/**
 * One image filling the screen: pinch to zoom, drag to pan when zoomed,
 * double tap to go back to 1x, and back or the close button to leave.
 *
 * A dialog rather than a destination: it has nothing to say to the rest of
 * the app, and the screen enum in `MeshChatScreen` is for places, not overlays.
 * No enter animation; the picture is already on screen in the bubble, and
 * growing it in would be motion for its own sake.
 */
@Composable
fun ImageViewer(path: String, onDismiss: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeBoundedImage(path)?.asImageBitmap() }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        scale = ImageZoom.clampScale(scale * zoomChange)
        // The gesture is read outside the graphics layer, so a pan is already in
        // screen pixels and is not scaled again.
        offset = ImageZoom.clampOffset(offset + panChange, scale, size)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // The theme's base, not black: dark-first already, and a pure
                // black panel is the one surface in the app with no token.
                .background(MaterialTheme.colorScheme.background)
                .onSizeChanged { size = it },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = "Image, full screen",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        // Pans only once zoomed, so at 1x a drag is left alone.
                        .transformable(transform, lockRotationOnZoomPan = true, canPan = { scale > 1f })
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    scale = 1f
                                    offset = Offset.Zero
                                },
                            )
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .safeDrawingPadding()
                    .padding(4.dp),
            ) {
                Text(
                    text = "×",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = "Close image" },
                )
            }
        }
    }
}
