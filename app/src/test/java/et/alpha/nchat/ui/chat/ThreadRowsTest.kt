package et.alpha.nchat.ui.chat

import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.TimerChangeEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThreadRowsTest {

    private fun message(id: String, receivedAt: Long) = MessageEntity(
        id = id,
        conversationId = "direct:peer",
        senderId = "peer",
        body = id,
        // The sender's clock, deliberately far off: only receivedAt may decide.
        timestamp = receivedAt + 10 * HOUR,
        deliveryState = DeliveryState.DELIVERED,
        isOutgoing = false,
        receivedAt = receivedAt,
    )

    private fun change(id: Long, changedAt: Long, byPeer: Boolean = true, millis: Long? = HOUR) =
        TimerChangeEntity(
            id = id,
            conversationId = "direct:peer",
            byPeer = byPeer,
            disappearAfterMillis = millis,
            changedAt = changedAt,
        )

    @Test
    fun `lines sit between the messages by this phone's clock`() {
        val rows = threadRows(
            messages = listOf(message("a", 100), message("b", 300)),
            changes = listOf(change(1, 50), change(2, 200), change(3, 400)),
        )

        assertEquals(listOf("timer:1", "a", "timer:2", "b", "timer:3"), rows.map { it.key })
    }

    @Test
    fun `on a tie the message comes first`() {
        val rows = threadRows(listOf(message("a", 100)), listOf(change(1, 100)))

        assertEquals(listOf("a", "timer:1"), rows.map { it.key })
    }

    @Test
    fun `a thread with no lines is just its messages`() {
        val rows = threadRows(listOf(message("a", 100), message("b", 200)), emptyList())

        assertEquals(listOf("a", "b"), rows.map { it.key })
    }

    @Test
    fun `a thread whose messages have all disappeared still shows its lines`() {
        val rows = threadRows(emptyList(), listOf(change(1, 100), change(2, 200)))

        assertEquals(listOf("timer:1", "timer:2"), rows.map { it.key })
    }

    @Test
    fun `a line says who changed the timer and to what`() {
        assertEquals(
            "Maya set messages to disappear after 1 hour",
            timerLineText(change(1, 0, byPeer = true, millis = HOUR), "Maya"),
        )
        assertEquals(
            "Maya turned off disappearing messages",
            timerLineText(change(1, 0, byPeer = true, millis = null), "Maya"),
        )
        assertEquals(
            "You set messages to disappear after 1 week",
            timerLineText(change(1, 0, byPeer = false, millis = 7 * 24 * HOUR), "Maya"),
        )
        assertEquals(
            "You turned off disappearing messages",
            timerLineText(change(1, 0, byPeer = false, millis = null), null),
        )
    }

    @Test
    fun `an unnamed contact is not shown as a bare id`() {
        assertEquals(
            "Your contact turned off disappearing messages",
            timerLineText(change(1, 0, byPeer = true, millis = null), null),
        )
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
