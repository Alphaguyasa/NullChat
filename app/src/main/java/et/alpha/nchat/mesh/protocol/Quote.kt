package et.alpha.nchat.mesh.protocol

/**
 * Reply-with-quote, carried inside the message text itself.
 *
 * A reply's body is `"> " + snippet + "\n" + reply`. That is the whole format:
 * no new packet type, no new payload layout, nothing old builds can misread.
 * A phone on an older build shows the line exactly as an email client would,
 * a `>` line above the answer, which is still a readable quote. A binary
 * reference (the quoted message's id) was the alternative, and every existing
 * build would have rendered those bytes as text.
 *
 * The cost is that the quote is not a link: it carries the words, not the
 * message, so a reply still reads correctly after the original was deleted,
 * was never received by this phone, or has scrolled out of a 10-minute
 * seen-cache somewhere on the mesh.
 */
object Quote {

    /**
     * The longest snippet, in UTF-8 bytes, ellipsis included.
     *
     * A quote is a reminder of what is being answered, not a copy of it. 64
     * bytes is roughly one line of a bubble, and it leaves 932 of the 1000-byte
     * budget for the reply.
     */
    const val MAX_SNIPPET_BYTES: Int = 64

    /** What an image without a caption is quoted as. */
    const val IMAGE_SNIPPET: String = "Image"

    private const val PREFIX = "> "
    private const val ELLIPSIS = "…"

    /** A reply split back into what it quotes and what it says. */
    data class Parsed(val quote: String, val reply: String)

    /**
     * The one-line snippet that stands for [body] inside a reply.
     *
     * Line breaks are folded into spaces because the format ends the quote at
     * the first newline. A reply to a reply quotes only the answer, not the
     * nested quote, so threads do not grow a ladder of `>` lines.
     */
    fun snippetOf(body: String): String {
        val text = (parse(body)?.reply ?: body)
            .replace(WHITESPACE, " ")
            .trim()
        if (text.isEmpty()) return IMAGE_SNIPPET
        if (MessagePacket.bodyByteCount(text) <= MAX_SNIPPET_BYTES) return text

        val budget = MAX_SNIPPET_BYTES - MessagePacket.bodyByteCount(ELLIPSIS)
        val out = StringBuilder()
        var used = 0
        var i = 0
        // By code point, so an emoji's surrogate pair is never split in half
        // and sent as malformed UTF-16.
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val chars = Character.charCount(cp)
            val bytes = MessagePacket.bodyByteCount(text.substring(i, i + chars))
            if (used + bytes > budget) break
            out.append(text, i, i + chars)
            used += bytes
            i += chars
        }
        return out.toString().trimEnd() + ELLIPSIS
    }

    /** The body of a reply to [quotedBody] that says [reply]. */
    fun compose(quotedBody: String, reply: String): String =
        PREFIX + snippetOf(quotedBody) + "\n" + reply

    /**
     * Splits [body] into quote and reply, or null when it is not a reply.
     *
     * Both halves must have text: a lone `> something` line with nothing under
     * it is somebody typing a `>`, and is shown as they typed it.
     */
    fun parse(body: String): Parsed? {
        if (!body.startsWith(PREFIX)) return null
        val newline = body.indexOf('\n')
        if (newline < 0) return null
        val quote = body.substring(PREFIX.length, newline).trim()
        val reply = body.substring(newline + 1).trim()
        if (quote.isEmpty() || reply.isEmpty()) return null
        return Parsed(quote, reply)
    }

    /**
     * Just the answer, for places that show one line of a message: the peer
     * list and notifications. A preview that opened with the quoted words
     * would show the other person's text as the latest thing said.
     */
    fun replyText(body: String): String = parse(body)?.reply ?: body

    private val WHITESPACE = Regex("\\s+")
}
