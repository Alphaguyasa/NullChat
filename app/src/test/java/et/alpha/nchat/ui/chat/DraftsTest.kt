package et.alpha.nchat.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DraftsTest {

    @Test
    fun `a draft left in one thread comes back when it is opened again`() {
        val drafts = Drafts()

        val entered = drafts.swap(leaving = "a", draft = Draft("half a mess", "quoted"), entering = "b")
        val back = drafts.swap(leaving = "b", draft = entered, entering = "a")

        // The bug this guards: switching chats cleared the box.
        assertEquals(Draft(), entered)
        assertEquals(Draft("half a mess", "quoted"), back)
    }

    @Test
    fun `an emptied box is not kept`() {
        val drafts = Drafts()
        drafts.swap(leaving = "a", draft = Draft("text"), entering = "b")
        drafts.swap(leaving = "b", draft = Draft(), entering = "a")

        drafts.swap(leaving = "a", draft = Draft(), entering = "b")

        assertEquals(0, drafts.size)
    }

    @Test
    fun `rebinding the same thread keeps the box as it is`() {
        val drafts = Drafts()

        assertEquals(Draft("typing"), drafts.swap(leaving = "a", draft = Draft("typing"), entering = "a"))
        assertEquals(0, drafts.size)
    }

    @Test
    fun `the least recently left draft goes first once full`() {
        val drafts = Drafts(capacity = 2)
        drafts.swap(leaving = "a", draft = Draft("1"), entering = "x")
        drafts.swap(leaving = "b", draft = Draft("2"), entering = "x")
        drafts.swap(leaving = "c", draft = Draft("3"), entering = "x")

        assertEquals(2, drafts.size)
        assertEquals(Draft(), drafts.swap(leaving = "x", draft = Draft(), entering = "a"))
        assertEquals(Draft("3"), drafts.swap(leaving = "a", draft = Draft(), entering = "c"))
    }
}
