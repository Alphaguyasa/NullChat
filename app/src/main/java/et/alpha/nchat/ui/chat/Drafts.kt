package et.alpha.nchat.ui.chat

/** A thread's unsent text and the quote it would carry. */
internal data class Draft(val text: String = "", val replyTo: String? = null) {
    val isEmpty: Boolean get() = text.isEmpty() && replyTo == null
}

/**
 * Unsent drafts of the threads not on screen, keyed by conversation id.
 *
 * One `ChatViewModel` serves every thread (see its `bind`), so before this the
 * box was simply cleared on a switch and half a message was lost to a quick
 * look at another chat. In memory only: a draft lasts as long as the app
 * process, like the text in any other open screen, and never touches disk,
 * where it would outlive a panic wipe or a disappearing timer.
 *
 * Least recently left first, capped at [capacity], so tapping through a long
 * peer list cannot grow it without limit. Used from the main thread only.
 */
internal class Drafts(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val held = object : LinkedHashMap<String, Draft>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Draft>) =
            size > capacity
    }

    /**
     * Stores [draft] under [leaving] and hands back what [entering] had, or an
     * empty draft. The entering thread's entry is removed: while it is on
     * screen the view model holds it, and an empty draft is never stored.
     */
    fun swap(leaving: String, draft: Draft, entering: String): Draft {
        if (leaving == entering) return draft
        if (draft.isEmpty) held.remove(leaving) else held[leaving] = draft
        return held.remove(entering) ?: Draft()
    }

    val size: Int get() = held.size

    companion object {
        const val DEFAULT_CAPACITY: Int = 50
    }
}
