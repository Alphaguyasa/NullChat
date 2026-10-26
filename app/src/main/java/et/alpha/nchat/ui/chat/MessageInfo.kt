package et.alpha.nchat.ui.chat

import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.data.db.MessageEntity

/** One line of the message info dialog. The screen maps [field] to its label. */
internal data class MessageInfoRow(val field: MessageInfoField, val value: String)

internal enum class MessageInfoField { STATE, FROM, WRITTEN, RECEIVED, DISAPPEARS }

/**
 * What the message info dialog shows for [message], in order.
 *
 * Pure, so the honesty rules are tested on the JVM rather than by eye:
 *
 * - Our own message shows its delivery state with the same words and hop rule as
 *   the bubble, so the dialog never calls a queued message sent.
 * - An incoming message shows the sender's stamp and this phone's arrival time
 *   as two separate rows. Phones with no network never sync their clocks, and a
 *   store-and-forwarded message can arrive hours after it was written; one
 *   blended time would hide both. The bubble already shows the clamped time.
 * - Rows with nothing true to say (no arrival time on rows from before it was
 *   stored, no timer) are left out rather than shown as a dash or a zero.
 *
 * @param formatTime renders an epoch-millis instant; passed in so the result does
 *   not depend on the test machine's locale or time zone.
 */
internal fun messageInfoRows(
    message: MessageEntity,
    formatTime: (Long) -> String,
): List<MessageInfoRow> = buildList {
    if (message.isOutgoing) {
        add(MessageInfoRow(MessageInfoField.STATE, deliveryText(message.deliveryState, message.hops)))
    } else {
        add(MessageInfoRow(MessageInfoField.FROM, message.senderId))
    }
    add(MessageInfoRow(MessageInfoField.WRITTEN, formatTime(message.timestamp)))
    if (!message.isOutgoing && message.receivedAt > 0L) {
        add(MessageInfoRow(MessageInfoField.RECEIVED, formatTime(message.receivedAt)))
    }
    message.expiresAt?.let { add(MessageInfoRow(MessageInfoField.DISAPPEARS, formatTime(it))) }
}

/**
 * Whether an incoming message claims to have been written well after it arrived.
 *
 * That is only possible if the sender's clock runs fast, and saying so explains
 * why the "written" row reads later than "received". A minute of slack covers
 * ordinary drift, which is not worth a warning.
 */
internal fun senderClockAhead(message: MessageEntity): Boolean =
    !message.isOutgoing && message.receivedAt > 0L &&
        message.timestamp - message.receivedAt > CLOCK_SLACK_MILLIS

/**
 * The bubble's delivery words, as plain text. Mirrors `DeliveryStateLabel`,
 * including the rule that a message with no route has no hop count.
 */
internal fun deliveryText(state: DeliveryState, hops: Int?): String {
    val word = when (state) {
        DeliveryState.PENDING -> "queued"
        DeliveryState.SENT -> "sent"
        DeliveryState.DELIVERED -> "delivered"
        DeliveryState.FAILED -> "failed"
    }
    val routed = state == DeliveryState.SENT || state == DeliveryState.DELIVERED
    val count = hops?.takeIf { it > 0 && routed } ?: return word
    return if (count == 1) "$word · 1 hop" else "$word · $count hops"
}

private const val CLOCK_SLACK_MILLIS = 60_000L
