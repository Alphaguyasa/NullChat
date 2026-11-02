package et.alpha.nchat.data.media

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [isOrphanAttachment], the rule behind the start-up prune. */
class OrphanAttachmentTest {

    private val live = setOf("a.jpg")

    @Test
    fun `an old file no row points at is an orphan`() {
        assertTrue(isOrphanAttachment("gone.jpg", STARTED_AT - 1, live, STARTED_AT))
    }

    @Test
    fun `a file a row points at is kept`() {
        assertFalse(isOrphanAttachment("a.jpg", STARTED_AT - 1, live, STARTED_AT))
    }

    @Test
    fun `a leftover temp file is an orphan even when its name is otherwise live`() {
        assertTrue(isOrphanAttachment("a.jpg.tmp", STARTED_AT - 1, setOf("a.jpg", "a.jpg.tmp"), STARTED_AT))
    }

    @Test
    fun `a file written since start is kept even before its row exists`() {
        // The race: an incoming image's file lands a moment before its row.
        assertFalse(isOrphanAttachment("new.jpg", STARTED_AT, live, STARTED_AT))
        assertFalse(isOrphanAttachment("new.jpg.tmp", STARTED_AT + 5, live, STARTED_AT))
    }

    @Test
    fun `a file whose time cannot be read counts as old`() {
        assertTrue(isOrphanAttachment("gone.jpg", 0L, live, STARTED_AT))
        assertFalse(isOrphanAttachment("a.jpg", 0L, live, STARTED_AT))
    }

    private companion object {
        const val STARTED_AT = 1_800_000_000_000L
    }
}
