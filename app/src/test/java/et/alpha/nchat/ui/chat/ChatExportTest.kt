package et.alpha.nchat.ui.chat

import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatExportTest {

    private val format: (Long) -> String = { "t$it" }
    private val label: (String) -> String = { "Maya · ${it.take(4)}" }

    private fun message(
        id: String,
        body: String,
        isOutgoing: Boolean,
        state: DeliveryState = DeliveryState.SENT,
        timestamp: Long = 1_000L,
        receivedAt: Long = 0L,
        hops: Int? = null,
        attachmentFile: String? = null,
    ) = MessageEntity(
        id = id,
        conversationId = "c1",
        senderId = "a4f29c11de0b7e35",
        body = body,
        timestamp = timestamp,
        deliveryState = state,
        isOutgoing = isOutgoing,
        attachmentFile = attachmentFile,
        hops = hops,
        receivedAt = receivedAt,
    )

    private fun render(vararg messages: MessageEntity) =
        ChatExport.render("Maya · a4f2", "today", messages.toList(), label, format)

    @Test
    fun `lists messages in order with sender, time and our delivery state`() {
        val text = render(
            message("1", "hi", isOutgoing = false, timestamp = 1_000L),
            message("2", "hello", isOutgoing = true, state = DeliveryState.DELIVERED, timestamp = 2_000L, hops = 2),
        )
        assertEquals(
            "Null Chat · Maya · a4f2\n" +
                "Exported today. Times are as shown on this phone.\n" +
                "\n" +
                "[t1000] Maya · a4f2: hi\n" +
                "[t2000] You (delivered · 2 hops): hello\n",
            text,
        )
    }

    @Test
    fun `a queued message is never written as sent`() {
        val text = render(message("1", "later", isOutgoing = true, state = DeliveryState.PENDING, hops = 3))
        assertTrue(text.contains("You (queued): later"))
        assertFalse(text.contains("sent"))
    }

    @Test
    fun `a sender clock running fast does not date a line in the future`() {
        val text = render(message("1", "hi", isOutgoing = false, timestamp = 9_000L, receivedAt = 5_000L))
        assertTrue(text.contains("[t5000]"))
        assertFalse(text.contains("t9000"))
    }

    @Test
    fun `continuation lines are indented so they cannot pass for a new message`() {
        val text = render(message("1", "> quoted\n[t1] Maya: fake", isOutgoing = false))
        assertTrue(text.contains("Maya · a4f2: > quoted\n    [t1] Maya: fake\n"))
    }

    @Test
    fun `an image is a marker line, not its file name`() {
        val text = render(message("1", "", isOutgoing = false, attachmentFile = "secret-name.jpg"))
        assertTrue(text.contains("Maya · a4f2: [image]"))
        assertFalse(text.contains("secret-name"))
    }

    @Test
    fun `an empty chat says so`() {
        assertTrue(render().endsWith("\nNo messages.\n"))
    }

    @Test
    fun `file name keeps only safe characters from the title`() {
        assertEquals("nullchat-Maya-a4f2-2026-10-28.txt", ChatExport.fileName("Maya · a4f2", "2026-10-28"))
        assertEquals("nullchat-a-b-c-2026-10-28.txt", ChatExport.fileName("../a/b:c", "2026-10-28"))
        assertEquals("nullchat-chat-2026-10-28.txt", ChatExport.fileName("///", "2026-10-28"))
        assertEquals("nullchat-Привет-2026-10-28.txt", ChatExport.fileName("Привет", "2026-10-28"))
    }
}
