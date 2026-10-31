package et.alpha.nchat.ui.chat

import et.alpha.nchat.data.db.MessageEntity
import et.alpha.nchat.data.db.TimerChangeEntity

/** One row of the thread: a message, or a line saying the timer changed. */
sealed interface ThreadRow {
    /** Stable across recompositions, and never equal between a message and a line. */
    val key: String

    data class Message(val message: MessageEntity) : ThreadRow {
        override val key: String get() = message.id
    }

    data class TimerLine(val change: TimerChangeEntity) : ThreadRow {
        override val key: String get() = "timer:${change.id}"
    }
}

/**
 * Interleaves a thread's [messages] and timer [changes], both already oldest
 * first, by when this phone stored them.
 *
 * Both sides use this phone's clock (`receivedAt`, `changedAt`), never a
 * sender's, so a line lands between the messages the user saw before and
 * after the change. On a tie the message goes first: a change made in the
 * same millisecond as a message was made after reading it.
 */
internal fun threadRows(messages: List<MessageEntity>, changes: List<TimerChangeEntity>): List<ThreadRow> {
    if (changes.isEmpty()) return messages.map(ThreadRow::Message)
    val rows = ArrayList<ThreadRow>(messages.size + changes.size)
    var c = 0
    for (message in messages) {
        while (c < changes.size && changes[c].changedAt < message.receivedAt) {
            rows += ThreadRow.TimerLine(changes[c++])
        }
        rows += ThreadRow.Message(message)
    }
    while (c < changes.size) rows += ThreadRow.TimerLine(changes[c++])
    return rows
}

/**
 * What a timer line says.
 *
 * [peerName] is the name the thread's title uses, or null when the peer has
 * none; the line then says "Your contact" rather than a 16-character id.
 */
internal fun timerLineText(change: TimerChangeEntity, peerName: String?): String {
    val who = if (change.byPeer) peerName ?: "Your contact" else "You"
    return when (val millis = change.disappearAfterMillis) {
        null -> "$who turned off disappearing messages"
        else -> "$who set messages to disappear after ${DisappearingTimer.label(millis)}"
    }
}
