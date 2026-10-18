package et.alpha.nchat.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import et.alpha.nchat.data.db.DeliveryState
import et.alpha.nchat.ui.theme.AccentBright
import et.alpha.nchat.ui.theme.MonoSmall
import et.alpha.nchat.ui.theme.MotionSpec
import et.alpha.nchat.ui.theme.NChatTheme

private val BubbleCorner = 18.dp

/** The corner nearest the speaker, tightened so the bubble points at them. */
private val TailCorner = 4.dp

/** Where a sent bubble starts before it settles at full size. */
private const val SEND_SCALE = 0.96f

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
 * @param content optional slot above the body, for a message that carries more
 *   than words — an image, a transfer bar. A slot rather than an `attachment`
 *   parameter, so this component never learns what a bitmap is: decoding,
 *   caching and progress belong to the screen, the bubble chrome belongs here.
 *   [text] may be empty when this is supplied, for a picture with no caption.
 * @param animateSend plays the send animation described below. Set only on a
 *   message the user has just sent — a thread of history opens at rest — and only
 *   when the device wants motion at all, which is
 *   [rememberAnimationsEnabled][et.alpha.nchat.ui.theme.rememberAnimationsEnabled]'s
 *   answer. False leaves both animations parked at their resting values, so an
 *   ordinary bubble costs nothing to draw.
 * @param onAction makes the bubble itself tappable, on both a tap and a long
 *   press, and [actionLabel] names what that does for TalkBack. Both gestures,
 *   because a long press is where chat apps have taught people to look and a
 *   tap is what someone does who has not been taught. Null leaves the bubble
 *   inert, which is every bubble but a failed one of ours today.
 * @param failedHint passed through to [DeliveryStateLabel].
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
    animateSend: Boolean = false,
    onAction: (() -> Unit)? = null,
    actionLabel: String? = null,
    failedHint: String? = null,
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(
        topStart = BubbleCorner,
        topEnd = BubbleCorner,
        bottomStart = if (isOutgoing) BubbleCorner else TailCorner,
        bottomEnd = if (isOutgoing) TailCorner else BubbleCorner,
    )
    val accent = MaterialTheme.colorScheme.primary

    val scale = remember { Animatable(if (animateSend) SEND_SCALE else 1f) }
    LaunchedEffect(animateSend) {
        if (animateSend) scale.animateTo(1f, MotionSpec)
    }

    // Re-fired on every delivery-state change, with a peak that falls each step,
    // so the bubble flashes hardest at the moment of sending and asks for less
    // attention as its state settles. See [flashPeak].
    val flash = remember { Animatable(0f) }
    LaunchedEffect(animateSend, deliveryState) {
        if (!animateSend) return@LaunchedEffect
        val peak = flashPeak(deliveryState)
        if (peak > 0f) {
            flash.snapTo(peak)
            flash.animateTo(0f, MotionSpec)
        }
    }

    val sendScale = if (animateSend) {
        Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            // Anchored at the tail, so the bubble grows out of the corner that
            // points at its sender rather than swelling in place.
            transformOrigin = TransformOrigin(if (isOutgoing) 1f else 0f, 1f)
        }
    } else {
        // No graphics layer at all on a bubble that is not animating. A thread is
        // a long list, and every row paying for a layer to hold a scale of 1 is
        // not a trade worth making.
        Modifier
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = sendScale
                .widthIn(max = 280.dp)
                .clip(shape)
                .then(
                    // After the clip, so the ripple stays inside the bubble's
                    // corners instead of flooding a rectangle around them.
                    if (onAction != null) {
                        Modifier.combinedClickable(
                            onClickLabel = actionLabel,
                            onLongClickLabel = actionLabel,
                            onLongClick = onAction,
                            onClick = onAction,
                        )
                    } else {
                        Modifier
                    },
                )
                .background(
                    if (isOutgoing) {
                        accent.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .border(
                    width = 1.dp,
                    // The flash lifts the border towards AccentBright and hands
                    // it straight back. Zero is spelled out rather than left to
                    // lerp, so a bubble at rest is the accent itself and not a
                    // round trip through Oklab that lands near it.
                    color = when {
                        !isOutgoing -> MaterialTheme.colorScheme.outlineVariant
                        flash.value == 0f -> accent
                        else -> lerp(accent, AccentBright, flash.value)
                    },
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

            if (content != null) {
                content()
                if (text.isNotEmpty()) Spacer(Modifier.height(6.dp))
            }

            // Skipped rather than rendered empty: a blank Text still occupies a
            // line, which would leave a gap under an uncaptioned picture.
            if (text.isNotEmpty()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

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
                        // Weighted so it wraps onto a second line rather than
                        // running out of the bubble. At 200% font scale
                        // "21:04  delivered · 2 hops" is wider than the 280dp a
                        // bubble is allowed, and the hop count — the one thing
                        // this app exists to tell the user — fell off the end.
                        DeliveryStateLabel(
                            state = deliveryState,
                            hopCount = hopCount,
                            failedHint = failedHint,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
    }
}

/**
 * How hard the border flashes when a message arrives at [state].
 *
 * The design system asks for a flash that "decays as delivery state advances",
 * and this is the decay: full strength on the send itself, half on `sent`, a
 * hint on `delivered`. Each advance is real news about where the message got to,
 * so each earns a flash — just a quieter one than the last.
 *
 * `failed` gets nothing. `DeliveryStateLabel` already says so in `Danger`, and a
 * mesh-green flash over that word would be the palette contradicting itself.
 */
private fun flashPeak(state: DeliveryState?): Float = when (state) {
    DeliveryState.PENDING -> 1f
    DeliveryState.SENT -> 0.5f
    DeliveryState.DELIVERED -> 0.25f
    DeliveryState.FAILED, null -> 0f
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
