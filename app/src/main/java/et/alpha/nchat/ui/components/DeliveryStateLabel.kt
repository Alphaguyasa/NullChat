package et.alpha.nchat.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.ui.theme.MonoSmall
import et.alpha.nchat.ui.theme.NChatTheme
import et.alpha.nchat.ui.theme.TextMuted
import et.alpha.nchat.ui.theme.Warning

/**
 * Delivery state as a monospace word, never a checkmark.
 *
 * A tick is a claim without a subject — it cannot distinguish "handed to a radio
 * that nobody was listening to" from "the recipient's phone acknowledged it",
 * and on a mesh those are completely different outcomes. The word says which.
 *
 * The design system writes the progression as
 * `queued` → `sent` → `2 hops` → `delivered`. The hop count is not a fifth state
 * but a suffix: it is known only once a message has actually travelled, so it
 * renders alongside `sent` and `delivered` rather than replacing either. Showing
 * it on a delivered message is the point of the app — it is the only place the
 * user learns that a stranger's phone carried their words.
 *
 * @param hopCount hops the message travelled, or null when unknown. Zero means
 *   it never left this device, so nothing is appended.
 * @param failedHint what the user can do about a failed message, appended after
 *   `failed` and nowhere else. A dead end the user cannot see a way out of reads
 *   as "the app is broken"; one with the way out written next to it reads as
 *   "the mesh was empty".
 */
@Composable
fun DeliveryStateLabel(
    state: DeliveryState,
    modifier: Modifier = Modifier,
    hopCount: Int? = null,
    failedHint: String? = null,
) {
    val word = when (state) {
        DeliveryState.PENDING -> "queued"
        DeliveryState.SENT -> "sent"
        DeliveryState.DELIVERED -> "delivered"
        DeliveryState.FAILED -> "failed"
    }
    // Warning has no Material slot to map onto — there is no "caution" role in a
    // ColorScheme — so the queued state reads the token directly.
    val wordColor = when (state) {
        DeliveryState.PENDING -> Warning
        DeliveryState.SENT -> MaterialTheme.colorScheme.onSurfaceVariant
        DeliveryState.DELIVERED -> MaterialTheme.colorScheme.primary
        DeliveryState.FAILED -> MaterialTheme.colorScheme.error
    }

    // A message that failed or never left is a message with no route, so a hop
    // count on either would be a contradiction.
    val hops = hopCount
        ?.takeIf { it > 0 && (state == DeliveryState.SENT || state == DeliveryState.DELIVERED) }
    val hint = failedHint?.takeIf { state == DeliveryState.FAILED }

    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = wordColor)) { append(word) }
            if (hops != null) {
                withStyle(SpanStyle(color = TextMuted)) {
                    append(if (hops == 1) " · 1 hop" else " · $hops hops")
                }
            }
            if (hint != null) {
                withStyle(SpanStyle(color = TextMuted)) { append(" · $hint") }
            }
        },
        style = MonoSmall,
        modifier = modifier,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0E1A26)
@Composable
private fun DeliveryStateLabelPreview() {
    NChatTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DeliveryStateLabel(DeliveryState.PENDING)
                DeliveryStateLabel(DeliveryState.SENT)
                DeliveryStateLabel(DeliveryState.SENT, hopCount = 1)
                DeliveryStateLabel(DeliveryState.DELIVERED, hopCount = 2)
                DeliveryStateLabel(DeliveryState.DELIVERED, hopCount = 4)
                DeliveryStateLabel(DeliveryState.FAILED)
                DeliveryStateLabel(DeliveryState.FAILED, failedHint = "tap to resend")
                // Hop counts are suppressed on states that cannot have a route.
                DeliveryStateLabel(DeliveryState.PENDING, hopCount = 3)
            }
        }
    }
}
