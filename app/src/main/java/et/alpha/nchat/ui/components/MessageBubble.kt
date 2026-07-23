package et.alpha.nchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.ui.theme.MonoSmall
import et.alpha.nchat.ui.theme.NChatTheme

private val BubbleCorner = 18.dp

/** The corner nearest the speaker, tightened so the bubble points at them. */
private val TailCorner = 4.dp

/**
 * One message in a thread.
 *
 * Stateless and format-free: [timestamp] arrives already rendered, because a
 * component that reaches for a locale and a clock cannot be previewed or tested
 * without one.
 *
 * Outgoing is drawn as a 12%-alpha wash of [Accent][et.alpha.nchat.ui.theme.Accent]
 * inside a 1dp Accent border rather than a solid fill. A solid accent bubble
 * would make the user's own words the loudest thing on a screen whose actual
 * subject is what other people said and how far it travelled.
 *
 * Incoming is solid `BgSurface`, which sits only a few values above `BgBase`.
 * That step is too small to read as an edge on an OLED panel at night, so the
 * bubble also carries the design system's separation hairline — 1dp `AccentDim`
 * at 20% — since elevation shadows are not available to do the job.
 *
 * @param senderId shown above the body, for broadcast threads where any peer
 *   could be talking. Null in a direct thread, which has only one possible sender.
 */
@Composable
fun MessageBubble(
    text: String,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
    senderId: String? = null,
    timestamp: String? = null,
    deliveryState: DeliveryState? = null,
    hopCount: Int? = null,
) {
    val shape = RoundedCornerShape(
        topStart = BubbleCorner,
        topEnd = BubbleCorner,
        bottomStart = if (isOutgoing) BubbleCorner else TailCorner,
        bottomEnd = if (isOutgoing) TailCorner else BubbleCorner,
    )
    val accent = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(shape)
                .background(
                    if (isOutgoing) {
                        accent.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .border(
                    width = 1.dp,
                    color = if (isOutgoing) accent else MaterialTheme.colorScheme.outlineVariant,
                    shape = shape,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (senderId != null) {
                Text(
                    text = senderId,
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }

            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (timestamp != null || deliveryState != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (timestamp != null) {
                        Text(
                            text = timestamp,
                            style = MonoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (deliveryState != null) {
                        if (timestamp != null) Spacer(Modifier.width(8.dp))
                        DeliveryStateLabel(state = deliveryState, hopCount = hopCount)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E1A26)
@Composable
private fun MessageBubblePreview() {
    NChatTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MessageBubble(
                    text = "meet me at the north gate",
                    isOutgoing = false,
                    senderId = "9e8b7a65",
                    timestamp = "21:04",
                )
                MessageBubble(
                    text = "on my way",
                    isOutgoing = true,
                    timestamp = "21:05",
                    deliveryState = DeliveryState.DELIVERED,
                    hopCount = 2,
                )
                MessageBubble(
                    text = "nobody in range yet, this will go out when someone walks past",
                    isOutgoing = true,
                    timestamp = "21:06",
                    deliveryState = DeliveryState.PENDING,
                )
                MessageBubble(
                    text = "did that arrive?",
                    isOutgoing = true,
                    timestamp = "21:07",
                    deliveryState = DeliveryState.SENT,
                    hopCount = 1,
                )
                MessageBubble(
                    text = "gave up after 24 hours",
                    isOutgoing = true,
                    timestamp = "21:08",
                    deliveryState = DeliveryState.FAILED,
                )
            }
        }
    }
}
