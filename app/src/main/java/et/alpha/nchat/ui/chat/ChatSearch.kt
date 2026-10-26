package et.alpha.nchat.ui.chat

import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.mesh.protocol.Quote

/**
 * Finding words in one thread.
 *
 * Run over the list the screen already holds rather than as a SQL `LIKE`: the
 * whole thread is in memory for the `LazyColumn` anyway, and SQLite's `LIKE`
 * folds case for ASCII only, so a search for "привет" would miss "Привет".
 * Kotlin's `ignoreCase` compares character by character in every script.
 */
internal object ChatSearch {

    /**
     * Where [query] occurs in [text], ignoring case, left to right and not overlapping.
     *
     * Empty for a blank query, so an empty box highlights nothing rather than
     * every position in every message.
     */
    fun matches(text: String, query: String): List<IntRange> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val at = text.indexOf(needle, startIndex = from, ignoreCase = true)
            if (at < 0) break
            ranges += at until at + needle.length
            from = at + needle.length
        }
        return ranges
    }

    /**
     * The words a search looks at: the sender's own, without a quoted line.
     *
     * The quote is somebody else's message, already in the thread on its own; a
     * hit on it would land the user on the reply instead of on what was said,
     * the same reasoning as copying the answer only.
     */
    fun searchableText(message: MessageEntity): String = Quote.replyText(message.body)

    /** Ids of the messages in [messages] that contain [query], in thread order. */
    fun hits(messages: List<MessageEntity>, query: String): List<String> =
        if (query.isBlank()) {
            emptyList()
        } else {
            messages.filter { matches(searchableText(it), query).isNotEmpty() }.map { it.id }
        }

    /**
     * The hit to show after moving [delta] from [current] among [count] hits.
     *
     * Wraps at both ends, so pressing "older" on the oldest hit goes back to
     * the newest instead of doing nothing, which reads as broken.
     */
    fun step(current: Int, count: Int, delta: Int): Int =
        if (count == 0) 0 else Math.floorMod(current + delta, count)
}
