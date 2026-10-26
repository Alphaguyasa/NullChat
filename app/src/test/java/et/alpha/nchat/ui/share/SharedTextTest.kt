package et.alpha.nchat.ui.share

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SharedTextTest {

    @Test
    fun `plain text from a share is taken, trimmed`() {
        assertEquals("see you at 5", SharedText.fromIntent(SharedText.ACTION_SEND, "text/plain", "  see you at 5\n"))
    }

    @Test
    fun `a mime type with a charset still counts as plain text`() {
        assertEquals("hi", SharedText.fromIntent(SharedText.ACTION_SEND, "text/plain; charset=utf-8", "hi"))
    }

    @Test
    fun `anything that is not a plain text send is refused`() {
        assertNull(SharedText.fromIntent("android.intent.action.MAIN", "text/plain", "hi"))
        assertNull(SharedText.fromIntent(SharedText.ACTION_SEND, "image/jpeg", "hi"))
        assertNull(SharedText.fromIntent(SharedText.ACTION_SEND, null, "hi"))
    }

    @Test
    fun `an empty share opens nothing`() {
        assertNull(SharedText.fromIntent(SharedText.ACTION_SEND, "text/plain", null))
        assertNull(SharedText.fromIntent(SharedText.ACTION_SEND, "text/plain", "   "))
    }

    @Test
    fun `a huge share is cut to the cap`() {
        val shared = SharedText.clean("a".repeat(SharedText.MAX_CHARS + 500))!!

        assertEquals(SharedText.MAX_CHARS, shared.length)
    }

    @Test
    fun `a share fills an empty box`() {
        assertEquals("link", SharedText.intoDraft("  ", "link"))
    }

    @Test
    fun `a share goes below what the user had typed`() {
        assertEquals("look at this\nlink", SharedText.intoDraft("look at this ", "link"))
    }
}
