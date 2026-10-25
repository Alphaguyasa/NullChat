package et.alpha.nchat.service

import et.alpha.nchat.data.db.BROADCAST_CONVERSATION_ID
import et.alpha.nchat.data.db.directConversationId
import et.alpha.nchat.mesh.Arrival
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageNotificationPolicyTest {

    private val maya = "a1b2c3d4e5f60718"
    private val sam = "0011223344556677"

    private fun direct(from: String = maya, preview: String = "hello", isImage: Boolean = false) =
        Arrival(
            conversationId = directConversationId(from),
            senderId = from,
            isBroadcast = false,
            preview = preview,
            isImage = isImage,
        )

    private val broadcast = Arrival(
        conversationId = BROADCAST_CONVERSATION_ID,
        senderId = maya,
        isBroadcast = true,
        preview = "anyone here?",
        isImage = false,
    )

    @Test
    fun `a direct message notifies when no thread is open`() {
        assertTrue(MessageNotificationPolicy.shouldNotify(direct(), foregroundConversationId = null))
    }

    @Test
    fun `a direct message is silent while its thread is on screen`() {
        assertFalse(MessageNotificationPolicy.shouldNotify(direct(), directConversationId(maya)))
    }

    @Test
    fun `a direct message notifies while a different thread is on screen`() {
        assertTrue(MessageNotificationPolicy.shouldNotify(direct(), directConversationId(sam)))
    }

    @Test
    fun `broadcasts never notify`() {
        assertFalse(MessageNotificationPolicy.shouldNotify(broadcast, foregroundConversationId = null))
    }

    @Test
    fun `the title is the short peer id`() {
        assertEquals("a1b2c3d4", MessageNotificationPolicy.title(direct(), senderName = null))
    }

    @Test
    fun `a known name leads the title, with the short id kept beside it`() {
        assertEquals("Mom · a1b2c3d4", MessageNotificationPolicy.title(direct(), senderName = "Mom"))
    }

    @Test
    fun `one message shows its text`() {
        assertEquals("hello", MessageNotificationPolicy.text(direct(), unseen = 1))
    }

    @Test
    fun `a long message is cut to the preview length`() {
        val text = MessageNotificationPolicy.text(direct(preview = "x".repeat(1000)), unseen = 1)
        assertEquals(MessageNotificationPolicy.MAX_PREVIEW_CHARS, text?.length)
    }

    @Test
    fun `a reply shows its answer, not the words it quotes`() {
        val text = MessageNotificationPolicy.text(direct(preview = "> north gate?\non my way"), unseen = 1)
        assertEquals("on my way", text)
    }

    @Test
    fun `an image says so instead of showing nothing`() {
        assertEquals("Image", MessageNotificationPolicy.text(direct(preview = "", isImage = true), unseen = 1))
    }

    @Test
    fun `several unseen messages show a count, not the newest text`() {
        assertEquals("3 new messages", MessageNotificationPolicy.text(direct(), unseen = 3))
    }

    @Test
    fun `a notification tap opens a well-formed peer id`() {
        assertEquals(maya, MessageNotificationPolicy.peerIdFromExtra(maya))
    }

    @Test
    fun `an extra from another app that is not a peer id opens nothing`() {
        assertNull(MessageNotificationPolicy.peerIdFromExtra(null))
        assertNull(MessageNotificationPolicy.peerIdFromExtra(""))
        assertNull(MessageNotificationPolicy.peerIdFromExtra("A1B2C3D4E5F60718"))
        assertNull(MessageNotificationPolicy.peerIdFromExtra("a1b2c3d4e5f6071"))
        assertNull(MessageNotificationPolicy.peerIdFromExtra("../../a1b2c3d4e5f60718"))
    }

    // The bug: with the app lock on, the shade still named the sender and
    // showed the words, so the lock guarded the threads and nothing else.
    @Test
    fun `the app lock hides who wrote and what they said`() {
        val arrival = direct(preview = "meet at the gate")

        assertEquals("New message", MessageNotificationPolicy.title(arrival, senderName = "Mom", hideContent = true))
        assertNull(MessageNotificationPolicy.text(arrival, unseen = 1, hideContent = true))
    }

    @Test
    fun `the app lock hides an image the same way`() {
        assertNull(MessageNotificationPolicy.text(direct(isImage = true), unseen = 1, hideContent = true))
    }

    @Test
    fun `the app lock still shows how many are waiting`() {
        assertEquals("3 new messages", MessageNotificationPolicy.text(direct(), unseen = 3, hideContent = true))
    }
}
