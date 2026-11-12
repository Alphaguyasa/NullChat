package et.alpha.nchat.ui.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuildIdentityTest {

    @Test
    fun `a CI build shows version, run number and the short commit`() {
        assertEquals(
            "1.0.214 (214) · 3f9c2ab",
            BuildIdentity.label("1.0.214", 214, "3f9c2ab0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6"),
        )
    }

    @Test
    fun `a build without a commit says local`() {
        assertEquals("1.0 (1) · local", BuildIdentity.label("1.0", 1, ""))
    }

    @Test
    fun `the commit is lower-cased and trimmed`() {
        assertEquals("3f9c2ab", BuildIdentity.shortCommit("  3F9C2AB0D1  "))
    }

    @Test
    fun `a value that is not a commit is not shown`() {
        assertEquals(BuildIdentity.LOCAL, BuildIdentity.shortCommit("<b>hello</b>"))
        assertEquals(BuildIdentity.LOCAL, BuildIdentity.shortCommit("abc12"))
    }
}
