package et.alpha.nchat.ui.chat

import et.alpha.nchat.data.db.MessageEntity

/**
 * One chat as a plain-text file the user chose to save.
 *
 * Pure, so what leaves the app is decided by tests rather than by eye:
 *
 * - Only what the thread shows goes in: the words, who said them, when, and the
 *   delivery state of our own messages, in the same words as the bubble so the
 *   file never calls a queued message sent. No full peer ids beyond the label the
 *   screen already shows, no keys, no image bytes (an image is a marker line).
 * - Times are [MessageEntity.shownAt], the time the bubble shows, so a sender
 *   whose clock runs fast does not put lines from tomorrow into the file.
 * - Continuation lines of a multi-line message are indented, so a line in a
 *   message that happens to start with `[` cannot pass for a new message.
 *
 * @param senderLabel renders an incoming sender id; for a direct chat the screen
 *   passes the chat title, for the broadcast thread a name and short id.
 * @param formatTime renders an epoch-millis instant; passed in so the result does
 *   not depend on the test machine's locale or time zone.
 */
internal object ChatExport {

    const val MIME_TYPE = "text/plain"

    fun render(
        title: String,
        exportedAt: String,
        messages: List<MessageEntity>,
        senderLabel: (String) -> String,
        formatTime: (Long) -> String,
    ): String = buildString {
        append("Null Chat · ").append(title).append('\n')
        append("Exported ").append(exportedAt).append(". Times are as shown on this phone.\n")
        if (messages.isEmpty()) {
            append("\nNo messages.\n")
            return@buildString
        }
        append('\n')
        for (message in messages) {
            append('[').append(formatTime(message.shownAt)).append("] ")
            if (message.isOutgoing) {
                append("You (").append(deliveryText(message.deliveryState, message.hops)).append(')')
            } else {
                append(senderLabel(message.senderId))
            }
            append(": ")
            val text = if (message.attachmentFile != null) IMAGE_MARKER else message.body
            text.lines().forEachIndexed { i, line ->
                if (i > 0) append('\n').append(CONTINUATION)
                append(line)
            }
            append('\n')
        }
    }

    /**
     * The name the system file picker suggests, which the user can change.
     *
     * Only letters, digits, `-` and `_` survive from the title, because a peer
     * chooses its own display name and a `/` or `:` in it would make some
     * document providers refuse the file or invent folders.
     */
    fun fileName(title: String, date: String): String {
        val safe = title
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(MAX_TITLE_CHARS)
            .ifEmpty { "chat" }
        return "nullchat-$safe-$date.txt"
    }

    private const val IMAGE_MARKER = "[image]"
    private const val CONTINUATION = "    "
    private const val MAX_TITLE_CHARS = 40
}
