package et.alpha.nchat.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImageZoomTest {

    private val screen = IntSize(1000, 2000)

    @Test
    fun `scale stays between 1x and 4x`() {
        assertEquals(1f, ImageZoom.clampScale(0.3f))
        assertEquals(2.5f, ImageZoom.clampScale(2.5f))
        assertEquals(4f, ImageZoom.clampScale(9f))
    }

    @Test
    fun `nothing pans at 1x`() {
        assertEquals(Offset.Zero, ImageZoom.clampOffset(Offset(300f, -300f), 1f, screen))
    }

    @Test
    fun `a zoomed image pans only as far as its overhang`() {
        // At 2x the picture is 2000 x 4000 on a 1000 x 2000 screen: 500 and
        // 1000 pixels over on each side.
        assertEquals(Offset(500f, -1000f), ImageZoom.clampOffset(Offset(900f, -5000f), 2f, screen))
        assertEquals(Offset(-120f, 40f), ImageZoom.clampOffset(Offset(-120f, 40f), 2f, screen))
    }
}
