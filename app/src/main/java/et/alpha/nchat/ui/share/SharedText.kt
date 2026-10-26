package et.alpha.nchat.ui.share

/**
 * Text handed to Null Chat from elsewhere: another app's share sheet, or a
 * message being forwarded from one chat to another.
 *
 * It only ever becomes a **draft**. Nothing is sent until the user has picked a
 * chat, seen the words in the box and pressed Send, because a share sheet is
 * one mistaken tap away from a stranger, and on a mesh there is no unsend.
 */
object SharedText {

    /** `Intent.ACTION_SEND`, spelled out so this file stays free of Android. */
    const val ACTION_SEND: String = "android.intent.action.SEND"

    /**
     * The longest shared text kept, in characters.
     *
     * Several times what one message may carry (1000 bytes), so the box can show
     * the user how far over they are and let them cut it down, but bounded so a
     * pasted book cannot bloat the saved instance state the draft rides in
     * across a rotation, which has a hard size limit.
     */
    const val MAX_CHARS: Int = 4000

    /**
     * The text of a share intent, or null if it is not one we take.
     *
     * Only `text/plain` (a link is text too). Blank is null, because opening the
     * chat picker for nothing would be a dead end.
     */
    fun fromIntent(action: String?, mimeType: String?, text: CharSequence?): String? {
        if (action != ACTION_SEND) return null
        if (mimeType == null || !mimeType.startsWith("text/plain")) return null
        return clean(text)
    }

    /** [text] trimmed and capped at [MAX_CHARS], or null when nothing is left. */
    fun clean(text: CharSequence?): String? {
        val trimmed = text?.toString()?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length <= MAX_CHARS) trimmed else trimmed.take(MAX_CHARS).trimEnd()
    }

    /**
     * The draft after [shared] arrives in a chat whose box holds [current].
     *
     * Added below rather than replacing it, so something half-typed is not lost
     * to a share the user may not even have meant for this chat.
     */
    fun intoDraft(current: String, shared: String): String =
        if (current.isBlank()) shared else current.trimEnd() + "\n" + shared
}
