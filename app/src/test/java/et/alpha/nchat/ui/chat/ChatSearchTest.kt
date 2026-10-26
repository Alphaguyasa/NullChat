package et.alpha.nchat.ui.chat

import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatSearchTest {

    @Test
    fun `matches ignore case and do not overlap`() {
        assertEquals(listOf(0..2, 8..10), ChatSearch.matches("Gate by gate", "gat"))
        assertEquals(listOf(0..1, 2..3), ChatSearch.matches("aaaa", "aa"))
    }

    @Test
    fun `case is folded outside ASCII too`() {
        // The reason this is not a SQL LIKE, which folds ASCII only.
        assertEquals(listOf(0..5), ChatSearch.matches("Привет всем", "привет"))
    }

    @Test
    fun `a blank query matches nothing`() {
        assertTrue(ChatSearch.matches("anything", "  ").isEmpty())
        assertTrue(ChatSearch.hits(listOf(message("1", "anything")), "").isEmpty())
    }

    @Test
    fun `surrounding spaces in the query are ignored`() {
        assertEquals(listOf(5..8), ChatSearch.matches("meet north", " nort "))
    }

    @Test
    fun `hits come back in thread order`() {
        val thread = listOf(
            message("1", "north gate"),
            message("2", "south gate"),
            message("3", "North again"),
        )

        assertEquals(listOf("1", "3"), ChatSearch.hits(thread, "north"))
    }

    @Test
    fun `a quoted line is not searched, only the answer`() {
        val reply = message("1", "> meet at the north gate\non my way")

        assertTrue(ChatSearch.hits(listOf(reply), "north").isEmpty())
        assertEquals(listOf("1"), ChatSearch.hits(listOf(reply), "my way"))
    }

    @Test
    fun `moving past either end wraps around`() {
        assertEquals(0, ChatSearch.step(current = 2, count = 3, delta = 1))
        assertEquals(2, ChatSearch.step(current = 0, count = 3, delta = -1))
        assertEquals(1, ChatSearch.step(current = 0, count = 3, delta = 1))
        assertEquals(0, ChatSearch.step(current = 0, count = 0, delta = -1))
    }

    private fun message(id: String, body: String) = MessageEntity(
        id = id,
        conversationId = "broadcast",
        senderId = "a",
        body = body,
        timestamp = 0L,
        deliveryState = DeliveryState.DELIVERED,
        isOutgoing = false,
    )
}
