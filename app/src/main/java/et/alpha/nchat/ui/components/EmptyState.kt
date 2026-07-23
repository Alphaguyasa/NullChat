package et.alpha.nchat.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import et.alpha.nchat.ui.theme.NChatTheme

/**
 * What a screen says when it has nothing to show.
 *
 * No illustration and no sad face. An empty mesh is not a failure — it is the
 * normal state of a radio that has not found anyone yet, and drawing it as
 * disappointment tells the user something untrue about their own app.
 *
 * Text only, so this composes underneath whatever visual a screen already owns:
 * the peer list keeps its radar drawn dim above this rather than replacing it.
 *
 * @param action optional slot for the one thing that might help, e.g. turning a
 *   radio back on. Omitted when there is nothing useful to offer, which is most
 *   of the time — waiting is usually the correct action.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E1A26)
@Composable
private fun EmptyStatePreview() {
    NChatTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                EmptyState(title = "Searching for peers nearby")
                EmptyState(
                    title = "Searching for peers nearby",
                    detail = "Messages you send now are queued and delivered when " +
                        "someone comes into range.",
                )
                EmptyState(
                    title = "Bluetooth is off",
                    detail = "The mesh needs Bluetooth to find the phones around you.",
                    action = { TextButton(onClick = {}) { Text("Turn on Bluetooth") } },
                )
            }
        }
    }
}
