package et.alpha.nchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import et.alpha.nchat.ui.theme.MonoSmall
import et.alpha.nchat.ui.theme.NChatTheme

private val BarWidth = 3.dp
private val BarSpacing = 2.dp
private val ShortestBar = 5.dp
private val BarStep = 3.dp

/**
 * Link quality to one peer, as a rising ramp of bars.
 *
 * Proximity is the primary information in this app, so signal strength is
 * ordinary screen furniture rather than something buried behind a long-press.
 *
 * Bars are filled with `Accent`. Empty ones are `AccentDim` at 20% — the design
 * system's separation value — rather than solid `AccentDim`. Solid was the first
 * attempt, since the token's documented job is "relay links, inactive nodes",
 * but at 3dp of width #3D8A72 and #5DCAA5 are the same green to the eye and
 * every level rendered identically. A node on the radar is big enough to carry
 * that distinction; a bar is not.
 *
 * Empty bars stay visible rather than vanishing, so the reading is always out of
 * a known total: one bar lit out of four is a different statement from one bar
 * lit alone.
 *
 * @param level bars to light, clamped to `0..barCount`. Zero is a peer that is
 *   known but not currently reachable.
 */
@Composable
fun SignalBars(
    level: Int,
    modifier: Modifier = Modifier,
    barCount: Int = 4,
) {
    val lit = level.coerceIn(0, barCount)
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = modifier.semantics { contentDescription = "signal $lit of $barCount" },
        horizontalArrangement = Arrangement.spacedBy(BarSpacing),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(barCount) { index ->
            Box(
                modifier = Modifier
                    .width(BarWidth)
                    .height(ShortestBar + BarStep * index)
                    .background(
                        color = if (index < lit) activeColor else inactiveColor,
                        shape = RoundedCornerShape(1.dp),
                    ),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E1A26)
@Composable
private fun SignalBarsPreview() {
    NChatTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (level in 0..4) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        SignalBars(level = level)
                        Text(
                            text = "  level $level",
                            style = MonoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Out-of-range input is clamped rather than crashing on a peer
                // whose RSSI mapping produced something unexpected.
                SignalBars(level = 9)
            }
        }
    }
}
