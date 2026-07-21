package et.alpha.nchat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Sanity check that the JUnit 5 unit-test toolchain is wired up.
 * Protocol and router tests (pure JVM, no Android deps) belong in this source set.
 */
class ExampleUnitTest {
    @Test
    fun `junit5 toolchain runs`() {
        assertEquals(4, 2 + 2)
    }
}
