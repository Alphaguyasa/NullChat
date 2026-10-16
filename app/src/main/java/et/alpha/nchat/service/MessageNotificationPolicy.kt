package et.alpha.nchat.service

import et.alpha.nchat.mesh.Arrival

/**
 * What a new-message notification says and when it appears, kept free of
 * Android types so it runs as a plain JVM test.
 */
internal object MessageNotificationPolicy {

    /** Characters of a peer id shown as its name, matching the rest of the UI. */
    const val SHORT_ID_CHARS: Int = 8

    /** Longest preview put in the notification; the full text is in the thread. */
    const val MAX_PREVIEW_CHARS: Int = 120

    /**
     * Whether [arrival] should interrupt the user.
     *
     * Direct messages only. The broadcast thread is everyone in range talking
     * at once, and in a busy room a notification per line would train people
     * to switch notifications off altogether — losing the direct messages that
     * actually need them. Nothing is posted for the thread already on screen.
     */
    fun shouldNotify(arrival: Arrival, foregroundConversationId: String?): Boolean =
        !arrival.isBroadcast && arrival.conversationId != foregroundConversationId

    /** Title: who it is from. Short id until display names exist (roadmap #11). */
    fun title(arrival: Arrival): String = arrival.senderId.take(SHORT_ID_CHARS)

    /**
     * Body: the newest message, or a count once several are waiting.
     *
     * Replacing the text with a count rather than stacking lines keeps the
     * notification to one row per conversation and never shows more message
     * content than the newest line.
     */
    fun text(arrival: Arrival, unseen: Int): String = when {
        unseen > 1 -> "$unseen new messages"
        arrival.isImage -> "Image"
        else -> arrival.preview.take(MAX_PREVIEW_CHARS)
    }

    /**
     * The peer a notification tap should open, if [peerId] looks like one.
     *
     * The activity is exported, so this extra can come from any app. A peer id
     * is exactly 16 lowercase hex characters; anything else opens nothing.
     */
    fun peerIdFromExtra(peerId: String?): String? =
        peerId?.takeIf { PEER_ID.matches(it) }

    private val PEER_ID = Regex("[0-9a-f]{16}")
}
