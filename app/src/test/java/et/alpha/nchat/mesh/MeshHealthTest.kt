package et.alpha.nchat.mesh

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeshHealthTest {

    @Test
    fun `a quiet mesh is not suspicious`() {
        assertFalse(MeshHealth().suspicious)
    }

    @Test
    fun `missed relays alone are people walking away, not an attack`() {
        assertFalse(MeshHealth(failedForwards = 40).suspicious)
    }

    @Test
    fun `any forged, unreadable or over-rate packet is flagged`() {
        assertTrue(MeshHealth(forged = 1).suspicious)
        assertTrue(MeshHealth(malformed = 1).suspicious)
        assertTrue(MeshHealth(rateLimited = 1).suspicious)
    }
}
